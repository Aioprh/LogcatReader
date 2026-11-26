package com.dp.logcatapp.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.dp.logcatapp.services.LogcatService
import com.dp.logcatapp.ui.MainActivity

/**
 * Kills the app and reopens it right after.
 *
 * `READ_LOGS` is granted from the outside (adb/root) and a process that was already running when
 * the grant happened cannot read the log buffers: the log daemon only allows processes that were
 * started with the permission in place. Restarting the app is therefore the only reliable way to
 * pick the permission up, and doing it in one tap beats asking the user to swipe the app away.
 */
fun Context.restartApp() {
  val intent = Intent(this, MainActivity::class.java).apply {
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
  }
  val pendingIntent = PendingIntent.getActivity(
    this,
    0,
    intent,
    PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
  )
  val alarmManager = getSystemService(AlarmManager::class.java)
  alarmManager?.set(
    AlarmManager.RTC,
    System.currentTimeMillis() + 500L,
    pendingIntent,
  )
  LogcatService.stop(this)
  Process.killProcess(Process.myPid())
}
