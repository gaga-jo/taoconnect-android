package com.gaius.taoconnect

import android.Manifest
import android.app.Activity
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private lateinit var projectionManager: MediaProjectionManager
    private lateinit var permissionStatus: TextView
    private lateinit var serviceStatus: TextView

    private var continueAfterOverlaySettings = false
    private var autoConnectPending = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            startTranslationService(result.resultCode, result.data!!)
        } else {
            serviceStatus.setText(R.string.status_idle)
            Toast.makeText(
                this,
                "Le partage d’écran est nécessaire pour lire le chinois visible.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        requestScreenCapture()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        projectionManager = getSystemService(MediaProjectionManager::class.java)
        permissionStatus = findViewById(R.id.permissionStatus)
        serviceStatus = findViewById(R.id.serviceStatus)
        autoConnectPending = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)

        findViewById<MaterialButton>(R.id.startButton).setOnClickListener {
            beginActivation()
        }

        findViewById<MaterialButton>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, TranslationOverlayService::class.java))
            serviceStatus.setText(R.string.status_idle)
        }

        findViewById<MaterialButton>(R.id.openTaobaoButton).setOnClickListener {
            openTaobao()
        }

        findViewById<MaterialButton>(R.id.addQuickTileButton).setOnClickListener {
            requestQuickSettingsTile()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        autoConnectPending = intent.getBooleanExtra(EXTRA_AUTO_CONNECT, false)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()

        if (continueAfterOverlaySettings) {
            continueAfterOverlaySettings = false
            if (Settings.canDrawOverlays(this)) {
                requestNotificationThenCapture()
            } else {
                Toast.makeText(
                    this,
                    R.string.overlay_permission_explanation,
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (autoConnectPending) {
            autoConnectPending = false
            window.decorView.post { beginActivation() }
        }
    }

    private fun beginActivation() {
        if (TranslationOverlayService.isRunning) {
            openTaobao()
            finish()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            continueAfterOverlaySettings = true
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(permissionIntent)
            return
        }

        requestNotificationThenCapture()
    }

    private fun requestNotificationThenCapture() {
        serviceStatus.setText(R.string.status_starting)

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestScreenCapture()
        }
    }

    private fun requestScreenCapture() {
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projectionManager.createScreenCaptureIntent(
                MediaProjectionConfig.createConfigForDefaultDisplay()
            )
        } else {
            projectionManager.createScreenCaptureIntent()
        }
        projectionLauncher.launch(captureIntent)
    }

    private fun startTranslationService(resultCode: Int, resultData: Intent) {
        val serviceIntent = Intent(this, TranslationOverlayService::class.java).apply {
            action = TranslationOverlayService.ACTION_START
            putExtra(TranslationOverlayService.EXTRA_RESULT_CODE, resultCode)
            putExtra(TranslationOverlayService.EXTRA_RESULT_DATA, resultData)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        serviceStatus.setText(R.string.status_active)
        serviceStatus.postDelayed({
            openTaobao()
            finish()
        }, 650L)
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val statusBarManager = getSystemService(StatusBarManager::class.java)
            statusBarManager.requestAddTileService(
                ComponentName(this, TaoConnectTileService::class.java),
                getString(R.string.tile_label),
                Icon.createWithResource(this, R.drawable.ic_tile),
                ContextCompat.getMainExecutor(this)
            ) { result ->
                val message = if (
                    result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                ) {
                    R.string.tile_added
                } else {
                    R.string.tile_help
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        } else {
            Toast.makeText(this, R.string.tile_help, Toast.LENGTH_LONG).show()
        }
    }

    private fun updateStatus() {
        val overlayAllowed = Settings.canDrawOverlays(this)
        permissionStatus.setText(
            if (overlayAllowed) R.string.permission_ready else R.string.permission_missing
        )
        permissionStatus.setTextColor(
            ContextCompat.getColor(
                this,
                if (overlayAllowed) R.color.success else R.color.warning
            )
        )

        if (TranslationOverlayService.isRunning) {
            serviceStatus.setText(R.string.status_active)
        }
    }

    private fun openTaobao() {
        val launchIntent = packageManager.getLaunchIntentForPackage(TAOBAO_PACKAGE)
        if (launchIntent != null) {
            startActivity(launchIntent)
            return
        }

        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.taobao.com/"))
        if (webIntent.resolveActivity(packageManager) != null) {
            startActivity(webIntent)
        } else {
            Toast.makeText(this, "Taobao n’est pas installé.", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "auto_connect_taobao"
        private const val TAOBAO_PACKAGE = "com.taobao.taobao"
    }
}
