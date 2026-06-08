package com.aizonme.agenthost

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONException
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val msg = if (granted) "Notification permission granted" else "Notification permission denied"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val authJsonInput = findViewById<EditText>(R.id.authJsonInput)

        findViewById<Button>(R.id.saveAuthButton).setOnClickListener {
            saveAuth(authJsonInput.text.toString())
        }

        findViewById<Button>(R.id.requestNotificationsButton).setOnClickListener {
            requestNotifications()
        }

        findViewById<Button>(R.id.requestBatteryButton).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }

        val autostartButton = findViewById<Button>(R.id.autostartButton)
        if (resolveAutostartIntent() != null) {
            autostartButton.visibility = android.view.View.VISIBLE
            autostartButton.setOnClickListener { openAutostartSettings() }
        }

        findViewById<Button>(R.id.startServiceButton).setOnClickListener {
            AgentService.start(this)
        }
    }

    /**
     * Known autostart / background-launch management screens on OEM skins that aggressively
     * kill background services (MIUI, ColorOS, EMUI, FuntouchOS, etc.). These are undocumented
     * component intents, so we resolve before launching and only surface the button when one exists.
     */
    private val autostartComponents = listOf(
        // Xiaomi (MIUI)
        ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        // Oppo / Realme (ColorOS)
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        // Vivo (FuntouchOS / OriginOS)
        ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        // Huawei (EMUI)
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        // Letv
        ComponentName("com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"),
        // Samsung (device care / battery)
        ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )

    private fun resolveAutostartIntent(): Intent? {
        for (component in autostartComponents) {
            val intent = Intent().setComponent(component)
            if (packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null) {
                return intent
            }
        }
        return null
    }

    private fun openAutostartSettings() {
        val intent = resolveAutostartIntent()
        if (intent == null) {
            Toast.makeText(this, "No autostart setting found on this device", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open autostart settings", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveAuth(raw: String) {
        val json = raw.trim()
        if (json.isEmpty()) {
            Toast.makeText(this, "Please paste your Claude Auth JSON", Toast.LENGTH_SHORT).show()
            return
        }

        // Verify the input is valid JSON before writing anything to disk.
        try {
            JSONObject(json)
        } catch (e: JSONException) {
            Toast.makeText(this, "Invalid JSON: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }

        val claudeDir = File(filesDir.absolutePath + "/.claude")
        if (!claudeDir.exists() && !claudeDir.mkdirs()) {
            Toast.makeText(this, "Could not create .claude directory", Toast.LENGTH_LONG).show()
            return
        }

        File(claudeDir, "auth.json").writeText(json)
        Toast.makeText(this, "Auth saved", Toast.LENGTH_SHORT).show()
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Notification permission not required on this version", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Notification permission already granted", Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:$packageName")
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fall back to the general battery optimization settings screen.
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
