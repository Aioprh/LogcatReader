package com.dp.logcatapp.ui.common

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dp.logcatapp.BuildConfig
import com.dp.logcatapp.R
import com.dp.logcatapp.util.SettingsPrefKeys
import com.dp.logcatapp.util.SuCommander
import com.dp.logcatapp.util.getDefaultSharedPreferences
import com.dp.logcatapp.util.rememberBooleanSharedPreference
import com.dp.logcatapp.util.showToast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val SHOW_PERMISSION_GRANTED_INFO_PREF_KEY = "show_permission_info_dialog"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaybeShowPermissionRequiredDialog(
  forceShow: Boolean = false,
  logcatSessionFailed: Boolean = false,
  onDismissed: (() -> Unit)? = null,
) {
  val context = LocalContext.current
  val permissionGranted = rememberReadLogsPermissionGranted()
  var showPermissionRequiredDialog by remember { mutableStateOf(false) }
  var showAskingForRootPermissionDialog by remember { mutableStateOf(false) }
  var showManualMethodDialog by remember { mutableStateOf(false) }
  val coroutineScope = rememberCoroutineScope()

  // Show the prompt whenever the permission is missing. This is intentionally driven by the
  // observed permission state and not by a value captured once at composition time, so that the
  // prompt also shows up when the permission gets revoked while the app is running.
  LaunchedEffect(permissionGranted, forceShow) {
    if (!permissionGranted || forceShow) {
      showPermissionRequiredDialog = true
    }
  }

  // Coming back to the app without the permission (for example after handing it out via adb, where
  // the grant may not have taken effect) must ask again - otherwise the prompt that was dismissed
  // earlier is gone for the rest of the process lifetime and logs can never be recorded.
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, permissionGranted) {
    val observer = LifecycleEventObserver { _, event ->
      val anotherDialogIsOpen = showManualMethodDialog ||
        showAskingForRootPermissionDialog
      if (event == Lifecycle.Event.ON_RESUME && !permissionGranted && !anotherDialogIsOpen) {
        showPermissionRequiredDialog = true
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  if (showPermissionRequiredDialog) {
    val failMessage = stringResource(R.string.fail)
    Dialog(
      modifier = Modifier.fillMaxWidth(),
      // Losing this dialog without granting the permission leaves the app unable to read logs and
      // with no way to get the prompt back, so it must not be dismissed by tapping outside.
      properties = DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnClickOutside = false,
      ),
      primaryButton = DialogButton(
        text = stringResource(R.string.manual_method),
        onClick = {
          showPermissionRequiredDialog = false
          showManualMethodDialog = true
        },
      ),
      onDismissRequest = {
        showPermissionRequiredDialog = false
        onDismissed?.invoke()
      },
      arrangement = DialogButtonArrangement.Stack,
      title = stringResource(R.string.read_logs_permission_required),
      content = { Text(stringResource(R.string.read_logs_permission_required_msg)) },
      secondaryButton = DialogButton(
        text = stringResource(R.string.root_method),
        onClick = {
          showPermissionRequiredDialog = false
          showAskingForRootPermissionDialog = true
          coroutineScope.launch {
            val result = enableRootLogcatMode(context)
            showAskingForRootPermissionDialog = false
            if (result) {
              // The service observes the preference and restarts logcat as root right away, so no
              // app restart is needed (and restarting would not help either: the grant is what
              // takes effect, not the process restart).
              onDismissed?.invoke()
            } else {
              showManualMethodDialog = true
              context.showToast(failMessage)
            }
          }
        }
      ),
      icon = {
        Icon(Icons.Default.Info, contentDescription = null)
      }
    )
  } else if (permissionGranted && !logcatSessionFailed) {
    // When the session failed there is nothing to celebrate: the error is shown on the logs screen
    // together with a way to recover.
    val showPermissionInfoDialog = rememberBooleanSharedPreference(
      key = SHOW_PERMISSION_GRANTED_INFO_PREF_KEY,
      default = true,
    )
    if (showPermissionInfoDialog.value) {
      Dialog(
        onDismissRequest = {
          showPermissionInfoDialog.value = false
          onDismissed?.invoke()
        },
        title = stringResource(R.string.permission_granted_info_title),
        content = {
          Text(stringResource(R.string.permission_granted_info_body))
        },
        primaryButton = DialogButton(
          text = stringResource(android.R.string.ok),
          onClick = {
            showPermissionInfoDialog.value = false
            onDismissed?.invoke()
          },
        )
      )
    }
  }

  if (showAskingForRootPermissionDialog) {
    Dialog(
      onDismissRequest = {},
      icon = {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          strokeWidth = 2.dp,
        )
      },
      content = {
        Text(stringResource(R.string.asking_permission_for_root_access))
      }
    )
  }

  if (showManualMethodDialog) {
    Dialog(
      onDismissRequest = {
        showManualMethodDialog = false
        onDismissed?.invoke()
      },
      title = stringResource(R.string.manual_method),
      arrangement = DialogButtonArrangement.Stack,
      content = {
        Text(
          text = buildAnnotatedString {
            append(stringResource(R.string.permission_instruction0))
            appendLine(); appendLine()
            append(stringResource(R.string.permission_instruction1))
            appendLine()
            append(stringResource(R.string.permission_instruction2))
            appendLine()
            append(
              AnnotatedString(
                text = stringResource(R.string.permission_instruction3),
                spanStyle = SpanStyle(
                  color = MaterialTheme.colorScheme.tertiary,
                  fontFamily = FontFamily.Monospace,
                  fontSize = 11.sp,
                )
              )
            )
            appendLine()
            append(stringResource(R.string.permission_instruction4))
            appendLine()
            append(stringResource(R.string.permission_instruction5))
            appendLine(); appendLine()
            append(stringResource(R.string.permission_instruction6))
          },
        )
      },
      primaryButton = DialogButton(
        text = stringResource(R.string.copy_adb_command),
        onClick = {
          showManualMethodDialog = false
          onDismissed?.invoke()
          val cmd = "adb shell pm grant ${BuildConfig.APPLICATION_ID} " +
            Manifest.permission.READ_LOGS
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as ClipboardManager
          cm.setPrimaryClip(ClipData.newPlainText("Adb command", cmd))
        }
      )
    )
  }
}

/**
 * Switches logcat to root mode.
 *
 * The `READ_LOGS` grant alone is not enough on devices whose log daemon refuses to serve app
 * processes; running `logcat` through `su` is. The permission is granted as well so that the
 * settings screen keeps showing a truthful state and devices without a working `su` still fall back
 * to the normal path.
 */
private suspend fun enableRootLogcatMode(context: Context): Boolean {
  return withContext(Dispatchers.IO) {
    val grant = "pm grant ${BuildConfig.APPLICATION_ID} ${Manifest.permission.READ_LOGS}"
    val suWorks = SuCommander(grant).run() || SuCommander("true").run()
    if (suWorks) {
      context.getDefaultSharedPreferences().edit {
        putBoolean(SettingsPrefKeys.Logcat.KEY_RUN_AS_ROOT, true)
      }
    }
    suWorks
  }
}