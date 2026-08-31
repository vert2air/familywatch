package com.example.familywatch

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions

class MonitorService : Service() {

    companion object {
        const val ACTION_START = "action_start"
        const val ACTION_STOP = "action_stop"
        const val ACTION_STOP_ALARM = "action_stop_alarm"
        const val ACTION_TEST_CAPTURE = "action_test_capture"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_CONDITION = "condition"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_INTERVAL_MIN = "interval_min"
        private const val CHANNEL_ID = "familywatch_channel"
        private const val ALARM_CHANNEL_ID = "familywatch_alarm_channel"
        private const val NOTIF_ID = 1

        // MainActivityへ状態を知らせるブロードキャスト
        const val ACTION_STATUS = "com.example.familywatch.ACTION_STATUS"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_SECONDS_LEFT = "seconds_left"
        const val EXTRA_LAST_RESULT = "last_result"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var conditionExpr: String = ""
    private var targetPackage: String = ""
    private var intervalMs: Long = 15 * 60 * 1000L
    private var running = false
    private var nextTriggerAtMillis: Long = 0L
    private var lastResultText: String = "まだ実行していません"
    private var isCapturing = false

    private val loopRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            captureOnce(isManualTest = false)
            nextTriggerAtMillis = System.currentTimeMillis() + intervalMs
            handler.postDelayed(this, intervalMs)
        }
    }

    // 1秒ごとにアプリ画面へ状態(次回までの秒数など)を知らせる
    private val statusTicker = object : Runnable {
        override fun run() {
            broadcastStatus()
            if (running) {
                handler.postDelayed(this, 1000)
            }
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
                // アラーム/バイブのみ止める。監視自体には触れない。
                AlarmPlayer.stop(this)
                return START_STICKY
            }
            ACTION_TEST_CAPTURE -> {
                if (mediaProjection != null) {
                    if (isCapturing) {
                        lastResultText = "前回のキャプチャがまだ進行中です。少し待ってから再度お試しください"
                        updateNotification(lastResultText)
                    } else {
                        lastResultText = "テストキャプチャ実行中..."
                        updateNotification(lastResultText)
                        captureOnce(isManualTest = true)
                    }
                } else {
                    updateNotification("先に「監視を開始」してください")
                }
                broadcastStatus()
                return START_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                conditionExpr = intent.getStringExtra(EXTRA_CONDITION) ?: ""
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
                    running = true
                    lastResultText = "まだ実行していません"
                    // 初回は少し待ってから開始
                    nextTriggerAtMillis = System.currentTimeMillis() + 3000
                    handler.postDelayed(loopRunnable, 3000)
                    handler.post(statusTicker)
                }
                return START_STICKY
            }
        }
        return START_STICKY
    }

    private fun ensureImageReader(): ImageReader {
        val dm = resources.displayMetrics
        val width = dm.widthPixels
        val height = dm.heightPixels

        imageReader?.close() // 前回分を解放してからリークを防ぐ
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        val projection = mediaProjection ?: return reader
        virtualDisplay?.release()
        // 注意: VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR はシステム権限が必要なフラグで
        // 一般アプリが使うとSecurityExceptionでクラッシュする。
        // MediaProjectionでの画面キャプチャには VIRTUAL_DISPLAY_FLAG_PUBLIC を使う。
        virtualDisplay = projection.createVirtualDisplay(
            "FamilyWatchCapture",
            width, height, dm.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
            reader.surface, null, handler
        )
        return reader
    }

    private fun captureOnce(isManualTest: Boolean) {
        if (isCapturing) {
            // 前回のキャプチャがまだ進行中(監視周期とテスト実行が重なった等)。今回はスキップ。
            return
        }
        isCapturing = true

        // 1. Family Linkを前面に呼び出す
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
            if (launchIntent != null) {
                // CLEAR_TOPは付けない: 付けるとFamily Linkの画面履歴がリセットされ、
                // 毎回トップ画面に戻ってしまい住所詳細画面が表示されなくなるため。
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
            }
        } catch (e: Exception) {
            // パッケージが見つからない等。次周期に持ち越す
            isCapturing = false
            return
        }

        // 2. 描画待ち後にキャプチャ
        handler.postDelayed({
            try {
                val reader = ensureImageReader()
                handler.postDelayed({
                    try {
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
                            runOcr(bitmap) // runOcr完了時にisCapturingをfalseに戻す
                        } else {
                            // 画像が取得できなかった(タイミング等)。エラーではなく空振りとして記録。
                            lastResultText = if (isManualTest) {
                                "画面のキャプチャに失敗しました。もう一度「今すぐ1回テスト実行」を押してください"
                            } else {
                                "画面のキャプチャに失敗しました。次回の自動チェック(約${intervalMs / 60000}分後)で再試行します"
                            }
                            updateNotification(lastResultText)
                            broadcastStatus()
                            isCapturing = false
                        }
                    } catch (e: Exception) {
                        // 画面キャプチャの許可が無効化された可能性が高い(例: システムに打ち切られた)。
                        // 中途半端な状態を残さず、監視を安全に停止してユーザーに再開始を促す。
                        isCapturing = false
                        stopMonitoring()
                        lastResultText = "画面キャプチャの権限が無効になりました。「監視を開始」を押し直してください"
                        updateNotification(lastResultText)
                        broadcastStatus()
                    }
                }, 500)
            } catch (e: Exception) {
                isCapturing = false
                stopMonitoring()
                lastResultText = "画面キャプチャの権限が無効になりました。「監視を開始」を押し直してください"
                updateNotification(lastResultText)
                broadcastStatus()
            }
        }, 2000)
    }

    private fun runOcr(bitmap: Bitmap) {
        val recognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                val matched = try {
                    conditionExpr.isNotBlank() &&
                        ConditionMatcher.evaluate(ConditionMatcher.parse(conditionExpr), text)
                } catch (e: Exception) {
                    lastResultText = "条件式のエラー: ${e.message}"
                    updateNotification(lastResultText)
                    broadcastStatus()
                    return@addOnSuccessListener
                }

                if (matched) {
                    val (soundOk, soundError) = AlarmPlayer.start(this)
                    lastResultText = if (soundOk) {
                        "条件に一致しました(音・バイブ作動中)"
                    } else {
                        "条件に一致しました(バイブのみ作動中。音声再生エラー: $soundError)"
                    }
                    updateNotification(lastResultText)
                } else {
                    // デバッグ用: 何を読み取ったか常に通知に出す(通知を長押し/展開すると全文見えます)
                    val preview = if (text.isBlank()) {
                        "(文字を認識できませんでした)"
                    } else {
                        text.replace("\n", " ").take(120)
                    }
                    lastResultText = "前回読み取り結果: $preview"
                    updateNotification(lastResultText)
                }
                broadcastStatus()
            }
            .addOnFailureListener { e ->
                lastResultText = "OCR失敗: ${e.message}"
                updateNotification(lastResultText)
                broadcastStatus()
            }
            .addOnCompleteListener {
                bitmap.recycle()
                isCapturing = false
            }
    }

    private fun stopMonitoring() {
        running = false
        isCapturing = false
        handler.removeCallbacks(loopRunnable)
        handler.removeCallbacks(statusTicker)
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        broadcastStatus()
    }

    override fun onDestroy() {
        stopMonitoring()
        super.onDestroy()
    }

    private fun broadcastStatus() {
        val secondsLeft = if (running) {
            ((nextTriggerAtMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        } else {
            0L
        }
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNNING, running)
            putExtra(EXTRA_SECONDS_LEFT, secondsLeft.toInt())
            putExtra(EXTRA_LAST_RESULT, lastResultText)
        }
        sendBroadcast(intent)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "監視ステータス", NotificationManager.IMPORTANCE_DEFAULT)
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
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}
