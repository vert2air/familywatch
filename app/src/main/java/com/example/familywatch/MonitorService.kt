package com.example.familywatch

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_ALARM = "action_stop_alarm"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_KEYWORD = "keyword"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_INTERVAL_MIN = "interval_min"
        private const val CHANNEL_ID = "familywatch_channel"
        private const val ALARM_CHANNEL_ID = "familywatch_alarm_channel"
        private const val NOTIF_ID = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var keyword: String = ""
    private var targetPackage: String = ""
    private var intervalMs: Long = 15 * 60 * 1000L
    private var running = false

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            captureOnce()
            handler.postDelayed(this, intervalMs)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannels()
        when (intent?.action) {
            ACTION_STOP -> {
                stopMonitoring()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP_ALARM -> {
                AlarmPlayer.stop(this)
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                keyword = intent.getStringExtra(EXTRA_KEYWORD) ?: ""
                targetPackage = intent.getStringExtra(EXTRA_PACKAGE) ?: ""
                val intervalMin = intent.getIntExtra(EXTRA_INTERVAL_MIN, 15)
                intervalMs = intervalMin * 60 * 1000L

                startForeground(NOTIF_ID, buildNotification("監視中: ${intervalMin}分間隔"))

                if (resultData != null) {
                    val mgr = getSystemService(MediaProjectionManager::class.java)
                    mediaProjection = mgr.getMediaProjection(resultCode, resultData)
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            stopMonitoring()
                        }
                    }, handler)
                    setupVirtualDisplay()
                    running = true
                    // 初回は少し待ってから開始
                    handler.postDelayed(loopRunnable, 3000)
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun setupVirtualDisplay() {
        // 実際のVirtualDisplay作成はensureImageReader()内で行う
        // (初回captureOnce()呼び出し時に画面サイズが確定してから作成するため)
    }

    private fun ensureImageReader(): ImageReader {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val projection = mediaProjection ?: return reader
        virtualDisplay?.release()
        virtualDisplay = projection.createVirtualDisplay(
            "FamilyWatchCapture",
            width, height, dm.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, handler
        )
        return reader
    }

    private fun captureOnce() {
        // 1. Family Linkを前面に呼び出す
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            // パッケージが見つからない等。次周期に持ち越す
            return
        }

        // 2. 描画待ち後にキャプチャ
        handler.postDelayed({
            val reader = ensureImageReader()
            handler.postDelayed({
                val image = reader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * image.width
                    val bitmap = Bitmap.createBitmap(
                        image.width + rowPadding / pixelStride,
                        image.height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()
                    runOcr(bitmap)
                }
            }, 500)
        }, 2000)
    }

    private fun runOcr(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                if (keyword.isNotBlank() && text.contains(keyword, ignoreCase = true)) {
                    AlarmPlayer.start(this)
                    updateNotification("検知しました: $keyword を含む表示を確認")
                }
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    private fun stopMonitoring() {
        running = false
        handler.removeCallbacks(loopRunnable)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "監視ステータス", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    ALARM_CHANNEL_ID, "接近アラーム", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FamilyWatch")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
