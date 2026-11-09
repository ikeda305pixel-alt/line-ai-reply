package com.antigravity.lineaireply

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import com.antigravity.lineaireply.service.LineAccessibilityService
import com.antigravity.lineaireply.service.SmartOverlayManager
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard
    private lateinit var memoryManager: ConversationMemoryManager

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

        val editKey = findViewById<EditText>(R.id.editGeminiApiKey)
        val textStatus = findViewById<TextView>(R.id.textFreeTierStatus)
        val textUserStyleStatus = findViewById<TextView>(R.id.textUserStyleStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOpenOverlay)
        val btnTestBubble = findViewById<Button>(R.id.btnTestBubble)
        val btnTestApiKey = findViewById<Button>(R.id.btnTestApiKey)
        val btnImportLineTxt = findViewById<Button>(R.id.btnImportLineTxt)
        val btnPasteChatLog = findViewById<Button>(R.id.btnPasteChatLog)

        editKey.setText(prefs.getString("gemini_key", ""))
        updateStatus(textStatus, textUserStyleStatus)

        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        btnOverlay.setOnClickListener {
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

        btnTestBubble.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "⚠️ 「重ねて表示」が未許可です。先にボタンから許可してください", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                return@setOnClickListener
            }

            val overlay = SmartOverlayManager(this)
            overlay.showBubble("LINE") {
                Toast.makeText(this, "バブルが正常に動作しています！LINEでも同様に画面端に表示されます", Toast.LENGTH_SHORT).show()
            }
            Toast.makeText(this, "画面端に「💬」バブルを表示しました！タップしてみてください", Toast.LENGTH_SHORT).show()
        }

        btnTestApiKey.setOnClickListener {
            val key = editKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "⚠️ 先にAPIキーを入力してください", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestApiKey.isEnabled = false
            btnTestApiKey.text = "Gemini 1.5 Flash 通信中..."

            lifecycleScope.launch {
                val (success, details) = SmartChatAssistant.testApiKey(key)
                btnTestApiKey.isEnabled = true
                btnTestApiKey.text = "🔑 APIキーの通信テスト"

                if (success) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("✅ 通信成功！")
                        .setMessage("Gemini 1.5 Flash と正常に通信できました！\nLINEやBumbleで文脈を深く汲み取った高精度なAI返信が利用可能です。")
                        .setPositiveButton("OK", null)
                        .show()
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("❌ 通信エラー")
                        .setMessage("Gemini APIとの通信に失敗しました。\n\n【詳細】\n$details\n\n※Google AI StudioでAPIキーを再確認するか、通信環境をご確認ください。")
                        .setPositiveButton("閉じる", null)
                        .show()
                }
            }
        }

        btnImportLineTxt.setOnClickListener {
            openTxtLauncher.launch("*/*")
        }

        btnPasteChatLog.setOnClickListener {
            showPasteDialog(textUserStyleStatus)
        }

        btnSave.setOnClickListener {
            val key = editKey.text.toString().trim()
            prefs.edit()
                .putString("gemini_key", key)
                .apply()
            Toast.makeText(this, "設定を保存しました！直ちに反映されます", Toast.LENGTH_SHORT).show()
            updateStatus(textStatus, textUserStyleStatus)
        }
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
                            updateStatus(findViewById(R.id.textFreeTierStatus), findViewById(R.id.textUserStyleStatus))
                        }
                        .show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "ファイル読み込み失敗: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showPasteDialog(statusTv: TextView?) {
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
                            updateStatus(findViewById(R.id.textFreeTierStatus), statusTv)
                        }
                        .show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun updateStatus(tvFreeTier: TextView, tvUserStyle: TextView?) {
        val count = guard.getDailyUsage()
        tvFreeTier.text = getString(R.string.status_free_tier, count)

        tvUserStyle?.let {
            val style = memoryManager.getUserStyle()
            it.text = "俺スタイル（学習数 ${style.totalLearnedCount} 件）:\n${style.summary}"
        }

        val btnAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOpenOverlay)

        if (isAccessibilityEnabled()) {
            btnAccessibility.text = "ユーザー補助: 有効 ✅"
        } else {
            btnAccessibility.text = getString(R.string.btn_open_accessibility)
        }

        if (isOverlayEnabled()) {
            btnOverlay.text = "重ねて表示: 許可済み ✅"
        } else {
            btnOverlay.text = getString(R.string.btn_open_overlay)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val myService = ComponentName(this, LineAccessibilityService::class.java).flattenToString()
        return enabledServices.contains(myService, ignoreCase = true)
    }

    private fun isOverlayEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else true
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.textFreeTierStatus)?.let {
            updateStatus(it, findViewById(R.id.textUserStyleStatus))
        }
    }
}
