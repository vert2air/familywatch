package com.example.familywatch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var editCondition: EditText
    private lateinit var editPackage: EditText
    private lateinit var editIntervalMin: EditText
    private lateinit var editIntervalSec: EditText
    private lateinit var statusText: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent ?: return
            val running = intent.getBooleanExtra(MonitorService.EXTRA_RUNNING, false)
            val secondsLeft = intent.getIntExtra(MonitorService.EXTRA_SECONDS_LEFT, 0)
            val lastResult = intent.getStringExtra(MonitorService.EXTRA_LAST_RESULT) ?: ""
            statusText.text = buildString {
                append(if (running) "状態: 監視中\n" else "状態: 停止中\n")
                if (running) {
                    append("次回チェックまで: 約${secondsLeft}秒\n")
                }
                append("前回の結果: $lastResult")
            }
        }
    }

    // MediaProjectionの許可ダイアログの結果を受け取る
    private val screenCaptureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                saveConfig()
                val totalSeconds = currentIntervalSeconds()
                val serviceIntent = Intent(this, MonitorService::class.java).apply {
                    action = MonitorService.ACTION_START
                    putExtra(MonitorService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(MonitorService.EXTRA_RESULT_DATA, result.data)
                    putExtra(MonitorService.EXTRA_CONDITION, editCondition.text.toString())
                    putExtra(MonitorService.EXTRA_PACKAGE, editPackage.text.toString())
                    putExtra(MonitorService.EXTRA_INTERVAL_SECONDS, totalSeconds)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
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

        editCondition = findViewById(R.id.editCondition)
        editPackage = findViewById(R.id.editPackage)
        editIntervalMin = findViewById(R.id.editIntervalMin)
        editIntervalSec = findViewById(R.id.editIntervalSec)
        statusText = findViewById(R.id.statusText)

        editCondition.setText(prefs.getString("condition", ""))
        editPackage.setText(
            prefs.getString("package", "com.google.android.apps.kids.familylink")
        )
        // デフォルトは1分0秒
        editIntervalMin.setText(prefs.getInt("interval_min", 1).toString())
        editIntervalSec.setText(prefs.getInt("interval_sec", 0).toString())

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            if (editCondition.text.isBlank()) {
                Toast.makeText(this, "判定条件式を入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val error = ConditionMatcher.validate(editCondition.text.toString())
            if (error != null) {
                Toast.makeText(this, "条件式にエラーがあります: $error", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            val sec = editIntervalSec.text.toString().toIntOrNull() ?: 0
            if (sec % 10 != 0 || sec !in 0..50) {
                Toast.makeText(this, "秒は10秒単位(0,10,20,30,40,50)で入力してください", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (currentIntervalSeconds() <= 0) {
                Toast.makeText(this, "監視間隔は1秒以上にしてください", Toast.LENGTH_SHORT).show()
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
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(MonitorService.ACTION_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.registerReceiver(
                this, statusReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (_: Exception) {
        }
    }

    private fun currentIntervalSeconds(): Int {
        val min = editIntervalMin.text.toString().toIntOrNull() ?: 1
        val sec = editIntervalSec.text.toString().toIntOrNull() ?: 0
        return min * 60 + sec
    }

    private fun saveConfig() {
        prefs.edit()
            .putString("condition", editCondition.text.toString())
            .putString("package", editPackage.text.toString())
            .putInt("interval_min", editIntervalMin.text.toString().toIntOrNull() ?: 1)
            .putInt("interval_sec", editIntervalSec.text.toString().toIntOrNull() ?: 0)
            .apply()
    }
}
