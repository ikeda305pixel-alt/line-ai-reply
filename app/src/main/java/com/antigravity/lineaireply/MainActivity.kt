package com.antigravity.lineaireply

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.antigravity.lineaireply.ai.FreeTierGuard

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var guard: FreeTierGuard

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("line_ai_prefs", Context.MODE_PRIVATE)
        guard = FreeTierGuard(this)

        val editKey = findViewById<EditText>(R.id.editGeminiApiKey)
        val textStatus = findViewById<TextView>(R.id.textFreeTierStatus)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnAccessibility = findViewById<Button>(R.id.btnOpenAccessibility)
        val btnOverlay = findViewById<Button>(R.id.btnOpenOverlay)

        editKey.setText(prefs.getString("gemini_key", ""))
        updateStatus(textStatus)

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
                    Toast.makeText(this, "重ねて表示は既に許可されています", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnSave.setOnClickListener {
            prefs.edit()
                .putString("gemini_key", editKey.text.toString().trim())
                .apply()
            Toast.makeText(this, "設定を保存しました", Toast.LENGTH_SHORT).show()
            updateStatus(textStatus)
        }
    }

    private fun updateStatus(tv: TextView) {
        val count = guard.getDailyUsage()
        tv.text = getString(R.string.status_free_tier, count)
    }

    override fun onResume() {
        super.onResume()
        findViewById<TextView>(R.id.textFreeTierStatus)?.let { updateStatus(it) }
    }
}
