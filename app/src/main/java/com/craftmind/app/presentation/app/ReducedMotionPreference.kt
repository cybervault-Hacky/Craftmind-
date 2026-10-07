package com.craftmind.app.presentation.app

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Reduced-motion preference (Phase 15 §15, §16).
 *
 * CraftMind honours the device's own accessibility choice instead of inventing a setting: when the system animator
 * scale is turned off (Settings → Accessibility → Remove animations), every design-system transition collapses to zero
 * duration. State changes still happen — only the motion is removed — so no information is lost.
 *
 * The value is observed live, so toggling the system setting takes effect without restarting the app.
 */
@Composable
fun rememberReducedMotionPreference(context: Context): Boolean {
    val resolver = context.contentResolver
    var reducedMotion by remember { mutableStateOf(isAnimatorScaleDisabled(resolver)) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reducedMotion = isAnimatorScaleDisabled(resolver)
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reducedMotion
}

/**
 * True when the system animator duration scale is zero.
 *
 * Reading the value can fail on unusual ROMs or restricted profiles; the failure is treated as "motion allowed",
 * which keeps the app functional and never silently disables state changes.
 */
private fun isAnimatorScaleDisabled(resolver: ContentResolver): Boolean = runCatching {
    Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)
