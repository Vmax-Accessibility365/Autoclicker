package com.example.autoclicker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.autoclicker.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_INTERVAL_MS = 50L
        private const val DEFAULT_INTERVAL_MS = 50L
    }

    private lateinit var binding: ActivityMainBinding

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Notifications OFF karne par service ka status dikhana mushkil hoga.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val requestScreenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            launchAutoClickService(result.resultCode, result.data!!)
        } else {
            Toast.makeText(
                this,
                "Screen capture permission zaroori hai.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private var pendingTargetText: String = ""
    private var pendingInterval: Long = DEFAULT_INTERVAL_MS
    private var pendingRetry: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startBtn.setOnClickListener {
            onStartClicked()
        }

        binding.stopBtn.setOnClickListener {
            onStopClicked()
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    private fun onStartClicked() {

        val targetText =
            binding.textInput.text.toString().trim()

        if (TextUtils.isEmpty(targetText)) {
            Toast.makeText(
                this,
                "Pehle target text bharein.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val enteredInterval =
            binding.intervalInput.text
                .toString()
                .trim()
                .toLongOrNull()

        if (enteredInterval == null) {
            Toast.makeText(
                this,
                "Valid interval number dalein.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        /*
         * Minimum interval = 50 ms.
         *
         * Agar user 50 se kam value deta hai,
         * to service ko 50 ms hi bheja jayega.
         */
        val interval =
            enteredInterval.coerceAtLeast(MIN_INTERVAL_MS)

        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(
                this,
                "Accessibility service ON karein: Settings -> Accessibility -> ${getString(R.string.app_name)}",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )

            return
        }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        /*
         * Screen-capture permission dialog ke baad
         * ye values service ko bheji jayengi.
         */
        pendingTargetText = targetText
        pendingInterval = interval
        pendingRetry = binding.retryCheck.isChecked

        val projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        requestScreenCapture.launch(
            projectionManager.createScreenCaptureIntent()
        )
    }

    private fun launchAutoClickService(
        resultCode: Int,
        data: Intent
    ) {

        val intent =
            Intent(
                this,
                AutoClickService::class.java
            ).apply {

                putExtra(
                    AutoClickService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    AutoClickService.EXTRA_RESULT_DATA,
                    data
                )

                putExtra(
                    AutoClickService.EXTRA_TARGET_TEXT,
                    pendingTargetText
                )

                putExtra(
                    AutoClickService.EXTRA_INTERVAL_MS,
                    pendingInterval
                )

                putExtra(
                    AutoClickService.EXTRA_RETRY,
                    pendingRetry
                )
            }

        ContextCompat.startForegroundService(
            this,
            intent
        )

        updateStatusUi(running = true)
    }

    private fun onStopClicked() {

        val intent =
            Intent(
                this,
                AutoClickService::class.java
            ).apply {
                action = AutoClickService.ACTION_STOP
            }

        startService(intent)

        updateStatusUi(running = false)
    }

    private fun updateStatusUi(
        running: Boolean = AutoClickService.isRunning
    ) {

        binding.statusText.text =
            getString(
                if (running) {
                    R.string.status_running
                } else {
                    R.string.status_idle
                }
            )

        binding.startBtn.isEnabled = !running
        binding.stopBtn.isEnabled = running
    }

    private fun isAccessibilityServiceEnabled(): Boolean {

        val expectedComponent =
            ComponentName(
                this,
                ClickAccessibilityService::class.java
            )

        val enabledServices =
            Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

        val splitter =
            TextUtils.SimpleStringSplitter(':')

        splitter.setString(enabledServices)

        while (splitter.hasNext()) {

            val componentName =
                ComponentName.unflattenFromString(
                    splitter.next()
                )

            if (
                componentName != null &&
                componentName == expectedComponent
            ) {
                return true
            }
        }

        return false
    }
}
