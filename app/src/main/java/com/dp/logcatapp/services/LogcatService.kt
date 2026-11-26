package com.dp.logcatapp.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.dp.logcat.LogcatSession
import com.dp.logcat.LogcatUtil
import com.dp.logcatapp.R
import com.dp.logcatapp.ui.MainActivity
import com.dp.logcatapp.util.SettingsPrefKeys
import com.dp.logcatapp.util.isReadLogsPermissionGranted
import com.dp.logcatapp.util.getDefaultSharedPreferences
import com.dp.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

class LogcatService : BaseService() {

  sealed interface LogcatSessionStatus {
    data object Starting : LogcatSessionStatus
    data class Started(val session: LogcatSession) : LogcatSessionStatus
    data class FailedToStart(val reason: String? = null) : LogcatSessionStatus

    val sessionOrNull: LogcatSession?
      get() = (this as? Started)?.session
  }

  private val _logcatSession = MutableStateFlow<LogcatSessionStatus?>(null)
  val logcatSessionStatus = _logcatSession.asStateFlow()

  private val restartTrigger = Channel<Unit>(capacity = 1, onBufferOverflow = DROP_OLDEST)
  private val sessionLock = Mutex()
  private var automaticRestartCount = 0
  private var permissionWatcher: Job? = null

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= 26) {
      createNotificationChannel()
    }

    lifecycleScope.launch {
      startNewLogcatSession()
      restartTrigger.consumeEach {
        automaticRestartCount = 0
        startNewLogcatSession()
      }
    }
  }

  fun restartLogcatSession() {
    restartTrigger.trySend(Unit)
  }

  private suspend fun startNewLogcatSession() = sessionLock.withLock {
    val previous = _logcatSession.getAndUpdate { LogcatSessionStatus.Starting }
    previous?.sessionOrNull?.let { session ->
      withContext(Dispatchers.Default) { session.stop() }
    }

    // Without READ_LOGS logcat cannot read anything, so there is no point in spawning the process.
    if (!isReadLogsPermissionGranted()) {
      failToStart()
      return@withLock
    }

    val logcatSession = newLogcatSession()
    if (!logcatSession.start()) {
      failToStart(logcatSession.lastErrorOutput)
      return@withLock
    }

    _logcatSession.value = LogcatSessionStatus.Started(logcatSession)
    watchForFirstLog(logcatSession)
  }

  /**
   * Starting the logcat process succeeds even when the app is not allowed to read the log buffers
   * (which is the case when `READ_LOGS` has not been granted). Logcat then either fails to open the
   * log device and exits immediately, or it blocks waiting on the log daemon, in both cases writing
   * whatever went wrong to stderr only. The session looks "started" while it never produces a single
   * log, which left the UI stuck on "Waiting for logs…" forever.
   *
   * A healthy logcat dumps the whole buffer right after it starts, so if nothing arrived within
   * [FIRST_LOG_TIMEOUT_MS] the session is considered broken: it gets restarted a couple of times
   * (a permission granted from the outside takes a moment to reach the log daemon) and is reported
   * as failed afterwards.
   */
  private fun watchForFirstLog(session: LogcatSession) {
    lifecycleScope.launch {
      val receivedLog = withTimeoutOrNull(FIRST_LOG_TIMEOUT_MS) {
        session.firstLogReceived.first { it }
      } == true

      // The session may have been replaced by a restart in the meantime.
      if (_logcatSession.value?.sessionOrNull !== session) {
        return@launch
      }

      if (receivedLog) {
        automaticRestartCount = 0
        return@launch
      }

      Logger.debug(TAG, "logcat session is not producing any logs. stderr: ${session.lastErrorOutput}")
      handleUnresponsiveSession(session)
    }
  }

  private suspend fun handleUnresponsiveSession(session: LogcatSession) {
    withContext(Dispatchers.Default) { session.stop() }
    if (_logcatSession.value?.sessionOrNull !== session) {
      return
    }

    // A permission that was just granted from the outside (adb/root) is not immediately visible to
    // the log daemon, so try again a few times before declaring the session as failed.
    if (isReadLogsPermissionGranted() && automaticRestartCount < MAX_AUTOMATIC_RESTARTS) {
      automaticRestartCount++
      _logcatSession.value = LogcatSessionStatus.Starting
      delay(RESTART_DELAY_MS * automaticRestartCount)
      startNewLogcatSession()
    } else {
      failToStart(session.lastErrorOutput)
    }
  }

  private fun failToStart(reason: String? = null) {
    _logcatSession.value = LogcatSessionStatus.FailedToStart(reason)
    if (isReadLogsPermissionGranted()) {
      // The permission is there and reading the logs still failed; retrying on our own will not
      // help, the user has to restart the app (see the error message on the logs screen).
      permissionWatcher?.cancel()
    } else {
      watchForPermissionToBeGranted()
    }
  }

  /**
   * The permission is not requested from the app but granted from the outside (adb/root), and the
   * app is not notified about it. Poll for it so that the session recovers without the user having
   * to restart the app.
   */
  private fun watchForPermissionToBeGranted() {
    permissionWatcher?.cancel()
    permissionWatcher = lifecycleScope.launch {
      while (!isReadLogsPermissionGranted()) {
        delay(PERMISSION_POLL_INTERVAL_MS)
      }
      automaticRestartCount = 0
      startNewLogcatSession()
    }
  }

  override fun onPreRegisterSharedPreferenceChangeListener() {
    val defaultBuffers = SettingsPrefKeys.Logcat.Default.BUFFERS
    if (defaultBuffers.isNotEmpty() && LogcatUtil.AVAILABLE_BUFFERS.isNotEmpty()) {
      val buffers = getDefaultSharedPreferences()
        .getStringSet(SettingsPrefKeys.Logcat.KEY_BUFFERS, emptySet())
      if (buffers.isNullOrEmpty()) {
        getDefaultSharedPreferences().edit {
          putStringSet(SettingsPrefKeys.Logcat.KEY_BUFFERS, defaultBuffers)
        }
      }
    }
  }

  override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
  ): Int {
    super.onStartCommand(intent, flags, startId)
    startForeground(
      NOTIFICATION_ID,
      createNotification(_logcatSession.value?.sessionOrNull?.isRecording == true)
    )
    return START_STICKY
  }

  fun updateNotification(showStopRecording: Boolean) {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(NOTIFICATION_ID, createNotification(showStopRecording))
  }

  private fun createNotification(addStopRecordingAction: Boolean): Notification {
    val startIntent = Intent(this, MainActivity::class.java)
    startIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
    val contentIntent = PendingIntent.getActivity(
      this, 0, startIntent,
      FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
    )

    val exitIntent = Intent(this, MainActivity::class.java)
    exitIntent.putExtra(MainActivity.EXIT_EXTRA, true)
    exitIntent.action = "exit"
    val exitPendingIntent = PendingIntent.getActivity(
      this, 1, exitIntent,
      FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
    )

    val builder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
      .setSmallIcon(R.drawable.ic_perm_device_information_white_24dp)
      .setColor(ContextCompat.getColor(applicationContext, R.color.color_primary))
      .setContentTitle(getString(R.string.app_name))
      .setTicker(getString(R.string.app_name))
      .setContentText(getString(R.string.logcat_service))
      .setWhen(System.currentTimeMillis())
      .setContentIntent(contentIntent)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setCategory(NotificationCompat.CATEGORY_SERVICE)
      .setAutoCancel(false)

    if (addStopRecordingAction) {
      val stopRecordingIntent = Intent(this, MainActivity::class.java)
      stopRecordingIntent.putExtra(MainActivity.STOP_RECORDING_EXTRA, true)
      stopRecordingIntent.action = "stop recording"
      val stopRecordingPendingIntent = PendingIntent.getActivity(
        this, 2,
        stopRecordingIntent,
        FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
      )
      val stopRecordingAction = NotificationCompat.Action.Builder(
        R.drawable.ic_stop_white_24dp,
        getString(R.string.stop_recording), stopRecordingPendingIntent
      )
        .build()

      builder.addAction(stopRecordingAction)
    } else {
      val exitAction = NotificationCompat.Action.Builder(
        R.drawable.ic_clear_white_24dp,
        getString(R.string.exit), exitPendingIntent
      )
        .build()
      builder.addAction(exitAction)
    }

    builder.setLargeIcon(BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher))

    return builder.build()
  }

  @RequiresApi(26)
  private fun createNotificationChannel() {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    val nc = NotificationChannel(
      NOTIFICATION_CHANNEL,
      getString(R.string.logcat_service_channel_name), NotificationManager.IMPORTANCE_LOW
    )
    nc.enableLights(false)
    nc.enableVibration(false)
    nm.createNotificationChannel(nc)
  }

  @RequiresApi(26)
  private fun deleteNotificationChannel() {
    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    nm.deleteNotificationChannel(NOTIFICATION_CHANNEL)
  }

  override fun onDestroy() {
    super.onDestroy()
    _logcatSession.update { status ->
      status?.sessionOrNull?.stop()
      null
    }

    if (Build.VERSION.SDK_INT >= 26) {
      deleteNotificationChannel()
    }
  }

  override fun onSharedPreferenceChanged(
    sharedPreferences: SharedPreferences,
    key: String?
  ) {
    super.onSharedPreferenceChanged(sharedPreferences, key)
    when (key) {
      SettingsPrefKeys.Logcat.KEY_POLL_INTERVAL -> {
        val pollInterval = sharedPreferences.getInt(
          key,
          SettingsPrefKeys.Logcat.Default.POLL_INTERVAL
        )
        _logcatSession.value?.sessionOrNull?.apply { pollIntervalMs = pollInterval.toLong() }
      }
      SettingsPrefKeys.Logcat.KEY_BUFFERS,
      SettingsPrefKeys.Logcat.KEY_MAX_LOGS -> {
        restartTrigger.trySend(Unit)
      }
    }
  }

  private fun newLogcatSession(): LogcatSession {
    val sharedPreferences = getDefaultSharedPreferences()
    val bufferValues = sharedPreferences.getStringSet(
      SettingsPrefKeys.Logcat.KEY_BUFFERS,
      SettingsPrefKeys.Logcat.Default.BUFFERS
    )!!
    val pollInterval = sharedPreferences.getInt(
      SettingsPrefKeys.Logcat.KEY_POLL_INTERVAL,
      SettingsPrefKeys.Logcat.Default.POLL_INTERVAL
    )
    val maxLogs = sharedPreferences.getInt(
      SettingsPrefKeys.Logcat.KEY_MAX_LOGS,
      SettingsPrefKeys.Logcat.Default.MAX_LOGS
    )

    val buffers = LogcatUtil.AVAILABLE_BUFFERS
    val logcatBuffers = bufferValues.mapNotNull { e ->
      buffers.getOrNull(e.toInt())
        ?.lowercase(Locale.getDefault())
    }.toSet().ifEmpty {
      sharedPreferences.edit {
        putStringSet(
          SettingsPrefKeys.Logcat.KEY_BUFFERS,
          SettingsPrefKeys.Logcat.Default.BUFFERS
        )
      }
      LogcatUtil.DEFAULT_BUFFERS
    }

    return LogcatSession(
      capacity = maxLogs,
      buffers = logcatBuffers,
      pollIntervalMs = pollInterval.toLong()
    )
  }

  companion object {
    private const val TAG = "LogcatService"
    private const val NOTIFICATION_CHANNEL = "logcat_channel_01"
    private const val NOTIFICATION_ID = 1

    /**
     * How long to wait for the logcat process to print its first log line before considering the
     * session to have failed. Logcat dumps the whole buffer right after it starts, so a healthy
     * process always produces output within a few hundred milliseconds.
     */
    private const val FIRST_LOG_TIMEOUT_MS = 3_000L

    /**
     * How many times a session that produced no logs gets restarted automatically before the
     * failure is reported (and the user is asked to restart the app).
     */
    private const val MAX_AUTOMATIC_RESTARTS = 2

    private const val RESTART_DELAY_MS = 1_500L

    private const val PERMISSION_POLL_INTERVAL_MS = 1_000L

    fun start(context: Context) {
      val intent = Intent(context, LogcatService::class.java)
      ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
      val intent = Intent(context, LogcatService::class.java)
      context.stopService(intent)
    }
  }
}