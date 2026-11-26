package com.postly.app.service

import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.postly.app.PostlyApplication
import com.postly.app.UnlockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DispatchForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val scheduleId = intent?.getLongExtra(AlarmReceiver.EXTRA_SCHEDULE_ID, -1L) ?: -1L
        if (scheduleId == -1L) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundNotification()
        acquireWakeLock()

        serviceScope.launch {
            val app = applicationContext as PostlyApplication
            val schedule = app.database.scheduleDao().getScheduleById(scheduleId)

            if (schedule == null) {
                releaseWakeLock()
                stopSelf()
                return@launch
            }

            // Check network connectivity before starting automation
            if (!com.postly.app.util.NetworkUtils.isNetworkAvailable(applicationContext)) {
                Log.w(TAG, "Device is offline / mobile data or Wi-Fi is off. Triggering retry for schedule: ${schedule.id}")
                RetryManager.handleFailure(applicationContext, schedule, "Network issue: Mobile data or Wi-Fi is off")
                releaseWakeLock()
                stopSelf()
                return@launch
            }

            // Assign pending task to Accessibility Service and reset state
            PostlyAccessibilityService.pendingTask = schedule
            PostlyAccessibilityService.currentStep = AutomationStep.START

            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val isLocked = keyguardManager.isKeyguardLocked

            if (isLocked) {
                Log.d(TAG, "Screen is locked! Launching UnlockScreenActivity to turn screen on & dismiss keyguard...")
                val unlockIntent = Intent(applicationContext, UnlockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(unlockIntent)
                delay(2500) // Allow Accessibility Service time to swipe and enter PIN
            }

            // Launch targeted WhatsApp
            WhatsAppLauncher.launch(applicationContext, schedule)

            // Keep service alive to allow accessibility service to finish typing & clicking
            delay(20000)
            releaseWakeLock()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val unlockIntent = Intent(this, UnlockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        val fullScreenPendingIntent = android.app.PendingIntent.getActivity(
            this,
            2002,
            unlockIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, PostlyApplication.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Postly Automator")
            .setContentText("Dispatching scheduled WhatsApp message...")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "Postly:DispatchWakeLock"
        ).apply {
            acquire(45000)
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "DispatchFGService"
    }
}
