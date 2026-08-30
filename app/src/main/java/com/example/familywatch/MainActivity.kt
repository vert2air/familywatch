package com.example.familywatch

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var editKeyword: EditText
    private lateinit var editPackage: EditText
    private lateinit var editInterval: EditText
    private lateinit var statusText: TextView

    // MediaProjectionの許可ダイアログの結果を受け取る
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                saveConfig()
                val serviceIntent = Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_START
                    putExtra(MonitorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MonitorService.EXTRA_RESULT_DATA, result.data)
                    putExtra(MonitorService.EXTRA_KEYWORD, editKeyword.text.toString())
                    putExtra(MonitorService.EXTRA_PACKAGE, editPackage.text.toString())
                    putExtra(
                        MonitorService.EXTRA_INTERVAL_MIN,
                        editInterval.text.toString().toIntOrNull() ?: 15
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                statusText.text = "監視中..."
            } else {
                Toast.makeText(this, "画面キャプチャの許可が必要です", Toast.LENGTH_LONG).show()
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("familywatch_prefs", Context.MODE_PRIVATE)

        editKeyword = findViewById(R.id.editKeyword)
        editPackage = findViewById(R.id.editPackage)
        editInterval = findViewById(R.id.editInterval)
        statusText = findViewById(R.id.statusText)

        editKeyword.setText(prefs.getString("keyword", ""))
        editPackage.setText(
            prefs.getString("package", "com.google.android.apps.kids.familylink")
        )
        editInterval.setText(prefs.getInt("interval", 15).toString())

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (editKeyword.text.isBlank()) {
                Toast.makeText(this, "判定キーワード(地名など)を入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            val mgr = getSystemService(MediaProjectionManager::class.java)
            screenCaptureLauncher.launch(mgr.createScreenCaptureIntent())
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            val serviceIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_STOP
            }
            startService(serviceIntent)
            statusText.text = "停止しました"
        }

        findViewById<Button>(R.id.btnStopAlarm).setOnClickListener {
            val serviceIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_STOP_ALARM
            }
            startService(serviceIntent)
        }

        findViewById<Button>(R.id.btnTestCapture).setOnClickListener {
            val serviceIntent = Intent(this, MonitorService::class.java).apply {
                action = MonitorService.ACTION_TEST_CAPTURE
            }
            startService(serviceIntent)
            Toast.makeText(
                this,
                "テスト実行しました。数秒後に通知欄を確認してください",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("keyword", editKeyword.text.toString())
            .putString("package", editPackage.text.toString())
            .putInt("interval", editInterval.text.toString().toIntOrNull() ?: 15)
            .apply()
    }
}
