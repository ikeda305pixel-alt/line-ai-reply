package com.antigravity.lineaireply

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.antigravity.lineaireply.ai.ConversationMemoryManager
import com.antigravity.lineaireply.ai.FreeTierGuard
import com.antigravity.lineaireply.ai.SmartChatAssistant
import com.antigravity.lineaireply.service.FloatingBubbleService
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var memoryManager: ConversationMemoryManager
    private lateinit var mediaProjectionManager: MediaProjectionManager

    private val screenCaptureLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_START
                putExtra(FloatingBubbleService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(FloatingBubbleService.EXTRA_RESULT_DATA, result.data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "🚀 バブルを起動しました！画面端の💬をタップしてください", Toast.LENGTH_SHORT).show()
            updateUIState()
        } else {
            Toast.makeText(this, "⚠️ 画面キャプチャの許可がキャンセルされました", Toast.LENGTH_SHORT).show()
        }
    }

    private val openTxtLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            handleImportTxtUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)
        memoryManager = ConversationMemoryManager(this)
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val tvAppVersion = findViewById<TextView>(R.id.tvAppVersion)
        val btnCheckUpdate = findViewById<Button>(R.id.btnCheckUpdate)
        val btnOpenOverlay = findViewById<Button>(R.id.btnOpenOverlay)
        val btnStartBubble = findViewById<Button>(R.id.btnStartBubble)
        val btnStopBubble = findViewById<Button>(R.id.btnStopBubble)
        val editKey = findViewById<EditText>(R.id.editGeminiApiKey)
        val btnTestApiKey = findViewById<Button>(R.id.btnTestApiKey)
        val btnImportLineTxt = findViewById<Button>(R.id.btnImportLineTxt)
        val btnPasteChatLog = findViewById<Button>(R.id.btnPasteChatLog)
        val btnSave = findViewById<Button>(R.id.btnSave)

        tvAppVersion.text = "v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})"
        btnCheckUpdate.setOnClickListener {
            val url = "https://github.com/ikeda305pixel-alt/line-ai-reply/releases/latest"
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(browserIntent)
        }

        editKey.setText(prefs.getString("gemini_key", ""))

        // Request notification permission for Android 13+ (for foreground service notification)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        btnOpenOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!Settings.canDrawOverlays(this)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "重ねて表示は既に許可されています ✅", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnStartBubble.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "⚠️ 先に「1. 重ねて表示を許可」してください", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            screenCaptureLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }

        btnStopBubble.setOnClickListener {
            val stopIntent = Intent(this, FloatingBubbleService::class.java).apply {
                action = FloatingBubbleService.ACTION_STOP
            }
            startService(stopIntent)
            Toast.makeText(this, "⏹️ バブルを停止しました", Toast.LENGTH_SHORT).show()
            updateUIState()
        }

        btnTestApiKey.setOnClickListener {
            val rawKey = editKey.text.toString()
            val cleanKey = SmartChatAssistant.sanitizeApiKey(rawKey)
            if (cleanKey.isBlank()) {
                Toast.makeText(this, "⚠️ 先にAPIキーを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            editKey.setText(cleanKey)

            btnTestApiKey.isEnabled = false
            btnTestApiKey.text = "Gemini 通信確認中..."

            lifecycleScope.launch {
                val (success, details) = SmartChatAssistant.testApiKey(cleanKey)
                btnTestApiKey.isEnabled = true
                btnTestApiKey.text = "🔑 APIキーの通信テスト"

                if (success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("✅ 通信成功！")
                        .setMessage(details)
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("❌ 通信エラー")
                        .setMessage(details)
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            }
        }

        btnImportLineTxt.setOnClickListener {
            openTxtLauncher.launch("*/*")
        }

        btnPasteChatLog.setOnClickListener {
            showPasteDialog()
        }

        btnSave.setOnClickListener {
            val rawKey = editKey.text.toString()
            val cleanKey = SmartChatAssistant.sanitizeApiKey(rawKey)
            editKey.setText(cleanKey)

            if (cleanKey.isNotBlank() && !cleanKey.startsWith("AIzaSy")) {
                AlertDialog.Builder(this)
                    .setTitle("⚠️ APIキーの確認")
                    .setMessage("入力されたキーは 'AIzaSy' から始まっていません。\n\nGoogle AI Studio (aistudio.google.com) で取得したGemini APIキーであることをご確認ください。\n\nこのまま保存しますか？")
                    .setPositiveButton("保存する") { _, _ ->
                        prefs.edit().putString("gemini_key", cleanKey).apply()
                        Toast.makeText(this, "APIキーを保存しました ✅", Toast.LENGTH_SHORT).show()
                        updateUIState()
                    }
                    .setNegativeButton("再確認する", null)
                    .show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("gemini_key", cleanKey)
                .apply()
            Toast.makeText(this, "設定を保存しました！直ちに反映されます ✅", Toast.LENGTH_SHORT).show()
            updateUIState()
        }

        updateUIState()
    }

    private fun updateUIState() {
        val tvBubbleStatus = findViewById<TextView>(R.id.tvBubbleStatus)
        val btnOpenOverlay = findViewById<Button>(R.id.btnOpenOverlay)
        val textFreeTierStatus = findViewById<TextView>(R.id.textFreeTierStatus)
        val textUserStyleStatus = findViewById<TextView>(R.id.textUserStyleStatus)

        // Overlay status
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this)) {
            btnOpenOverlay.text = "1. 重ねて表示: 許可済み ✅"
        } else {
            btnOpenOverlay.text = getString(R.string.btn_open_overlay)
        }

        // Bubble service running status
        if (FloatingBubbleService.isRunning) {
            tvBubbleStatus.text = "稼働状態: 稼働中 🟢（画面端の💬をタップしてAI返信）"
            tvBubbleStatus.setBackgroundColor(0xFFE8F5E9.toInt())
            tvBubbleStatus.setTextColor(0xFF1B5E20.toInt())
        } else {
            tvBubbleStatus.text = "稼働状態: 停止中 ⚪（上のボタンから起動）"
            tvBubbleStatus.setBackgroundColor(0xFFF3F4F6.toInt())
            tvBubbleStatus.setTextColor(0xFF374151.toInt())
        }

        // Usage count
        val count = guard.getDailyUsage()
        textFreeTierStatus.text = getString(R.string.status_free_tier, count)

        // User style summary
        val style = memoryManager.getUserStyle()
        textUserStyleStatus.text = "俺スタイル（学習数 ${style.totalLearnedCount} 件）:\n${style.summary}"
    }

    private fun handleImportTxtUri(uri: Uri) {
        val key = prefs.getString("gemini_key", "") ?: ""
        lifecycleScope.launch {
            Toast.makeText(this@MainActivity, "トーク履歴を解析・学習中...", Toast.LENGTH_SHORT).show()
            try {
                contentResolver.openInputStream(uri)?.use { stream ->
                    val reader = BufferedReader(InputStreamReader(stream))
                    val text = reader.readText()
                    val (success, message) = memoryManager.importAndLearnLog(text, key)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(if (success) "🧠 過去ログ学習完了！" else "⚠️ 学習失敗")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ ->
                            updateUIState()
                        }
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "ファイル読み込み失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPasteDialog() {
        val input = EditText(this).apply {
            hint = "LINEトーク履歴のテキスト、または自分の典型的な返信メッセージを複数行貼り付けてください"
            minLines = 6
            setPadding(32, 32, 32, 32)
        }

        AlertDialog.Builder(this)
            .setTitle("📋 過去ログ・口調テキストを貼り付け")
            .setView(input)
            .setPositiveButton("学習開始") { _, _ ->
                val pasted = input.text.toString().trim()
                if (pasted.isBlank()) {
                    Toast.makeText(this, "テキストが入力されていません", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val key = prefs.getString("gemini_key", "") ?: ""
                lifecycleScope.launch {
                    Toast.makeText(this@MainActivity, "AIが口調と相手の性格を学習中...", Toast.LENGTH_SHORT).show()
                    val (success, message) = memoryManager.importAndLearnLog(pasted, key)
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(if (success) "🧠 学習完了！" else "⚠️ 学習失敗")
                        .setMessage(message)
                        .setPositiveButton("OK") { _, _ ->
                            updateUIState()
                        }
                        .show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }
}
