package com.ncmc.ricky

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.ncmc.ricky.core.ConversionEngine
import com.ncmc.ricky.model.ConversionItem
import java.io.File

/**
 * 前台服务：转换任务进入后台后仍持续运行，
 * 通过常驻通知实时展示整体进度，支持在通知栏直接取消。
 */
class NcmConversionService : Service() {

    companion object {
        private const val CHANNEL_ID = "ncm_conversion"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var wakelock: PowerManager.WakeLock
    private var progressReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakelock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "NCM-Converter:convert")
        registerProgressReceiver()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ConversionEngine.ACTION_START -> startConversion(intent)
            ConversionEngine.ACTION_CANCEL -> {
                ConversionEngine.cancel(applicationContext)
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startConversion(intent: Intent) {
        if (ConversionEngine.running) return

        val input = intent.getStringExtra(ConversionEngine.EXTRA_INPUT)
        val output = intent.getStringExtra(ConversionEngine.EXTRA_OUTPUT)
        if (input.isNullOrEmpty() || output.isNullOrEmpty()) {
            stopSelf()
            return
        }

        startForeground(NOTIFICATION_ID, buildNotification(0, 0, getString(R.string.notification_preparing)))
        if (!wakelock.isHeld) wakelock.acquire()

        ConversionEngine.start(File(input), File(output), applicationContext)

        // 守护线程：等待转换结束后回收前台状态
        Thread {
            try {
                while (ConversionEngine.running) {
                    Thread.sleep(200)
                }
            } catch (e: InterruptedException) {
                // ignore
            }
            stopForeground(true)
            if (wakelock.isHeld) wakelock.release()
            stopSelf()
        }.start()
    }

    private fun buildNotification(percent: Int, doneFiles: Int, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, NcmConversionService::class.java).setAction(ConversionEngine.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_music)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
            .addAction(0, getString(R.string.notification_cancel), cancelIntent)
            .build()
    }

    private fun registerProgressReceiver() {
        val filter = IntentFilter(ConversionEngine.ACTION_PROGRESS)
        progressReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val total = ConversionEngine.items.size
                val done = ConversionEngine.items.count {
                    it.status == ConversionItem.Status.DONE || it.status == ConversionItem.Status.FAILED
                }
                val percent = if (ConversionEngine.totalBytes > 0) {
                    (ConversionEngine.completedBytes.get() * 100.0 / ConversionEngine.totalBytes).toInt()
                } else 0
                val text = getString(R.string.notification_progress, done, total, percent)
                NotificationManagerCompat.from(this@NcmConversionService)
                    .notify(NOTIFICATION_ID, buildNotification(percent, done, text))
            }
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(progressReceiver!!, filter)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        progressReceiver?.let {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(it)
        }
        if (::wakelock.isInitialized && wakelock.isHeld) wakelock.release()
        super.onDestroy()
    }
}
