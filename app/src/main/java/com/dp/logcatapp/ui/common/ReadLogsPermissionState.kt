package com.dp.logcatapp.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dp.logcatapp.util.isReadLogsPermissionGranted
import kotlinx.coroutines.delay

private const val PERMISSION_POLL_INTERVAL_MS = 1_000L

/**
 * Whether `READ_LOGS` is currently granted, kept in sync with the actual state of the app.
 *
 * The permission is not requested from the app itself but granted from the outside, usually via
 * `adb shell pm grant ...` (that's the documented "manual method"). Such a grant can happen at any
 * time, including while the app is in the foreground, and it can also be revoked again.
 *
 * Reading the value once and caching it (which is what `remember { isReadLogsPermissionGranted() }`
 * does) produces a stale state: the permission prompt would not show up after a revocation even
 * though logs cannot be read anymore, and the app would keep claiming that the permission is
 * missing after the user granted it without restarting the app. Both cases end up with the app
 * unable to show the permission dialog, which is exactly the "no dialog and no logs" situation.
 */
@Composable
fun rememberReadLogsPermissionGranted(): Boolean {
  val context = LocalContext.current
  var granted by remember(context) { mutableStateOf(context.isReadLogsPermissionGranted()) }
  val lifecycleOwner = LocalLifecycleOwner.current

  DisposableEffect(lifecycleOwner, context) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        granted = context.isReadLogsPermissionGranted()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  // The grant is done from outside of the app (adb/root) while the app is running, so there is no
  // lifecycle event or broadcast to listen to - poll until the permission shows up.
  LaunchedEffect(granted, context) {
    if (granted) {
      return@LaunchedEffect
    }
    while (true) {
      delay(PERMISSION_POLL_INTERVAL_MS)
      if (context.isReadLogsPermissionGranted()) {
        granted = true
        break
      }
    }
  }

  return granted
}
