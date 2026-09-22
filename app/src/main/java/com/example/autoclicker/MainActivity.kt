package com.example.autoclicker

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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

    private var pendingTargetText: String = ""

    private val requestNotificationPermission =
        registerForActivityResult(
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

    private val requestScreenCapture =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (
                result.resultCode == RESULT_OK &&
                result.data != null
            ) {

                launchAutoClickService(
                    result.resultCode,
                    result.data!!
                )

            } else {

                Toast.makeText(
                    this,
                    "Screen capture permission zaroori hai.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        binding =
            ActivityMainBinding.inflate(
                layoutInflater
            )

        setContentView(binding.root)

        /*
         * Keep the UI minimum interval at 50 ms.
         */
        if (
            binding.intervalInput.text
                .toString()
                .trim()
                .isEmpty()
        ) {
            binding.intervalInput.setText(
                DEFAULT_INTERVAL_MS.toString()
            )
        }

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
            binding.textInput.text
                .toString()
                .trim()

        if (TextUtils.isEmpty(targetText)) {

            Toast.makeText(
                this,
                "Pehle target text bharein.",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        /*
         * Current AutoClickService does not consume
         * interval/retry values directly.
         *
         * Keep the existing UI validation so an invalid
         * interval cannot start the automation.
         */
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

        val interval =
            enteredInterval.coerceAtLeast(
                MIN_INTERVAL_MS
            )

        binding.intervalInput.setText(
            interval.toString()
        )

        if (!isAccessibilityServiceEnabled()) {

            Toast.makeText(
                this,
                "Accessibility service ON karein: Settings -> Accessibility -> ${getString(R.string.app_name)}",
                Toast.LENGTH_LONG
            ).show()

            startActivity(
                Intent(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS
                )
            )

            return
        }

        /*
         * Ask for notification permission on Android 13+.
         */
        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            requestNotificationPermission.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        /*
         * Save only the value currently required by
         * AutoClickService.
         */
        pendingTargetText =
            targetText

        val projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        requestScreenCapture.launch(
            projectionManager
                .createScreenCaptureIntent()
        )
    }

    private fun launchAutoClickService(
        resultCode: Int,
        data: Intent
    ) {

        /*
         * Convert Activity result into MediaProjection.
         */
        val projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

        val mediaProjection =
            projectionManager.getMediaProjection(
                resultCode,
                data
            )

        if (mediaProjection == null) {

            Toast.makeText(
                this,
                "MediaProjection start nahi ho saka.",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        /*
         * Make projection available to AutoClickService.
         */
        MediaProjectionHolder.mediaProjection =
            mediaProjection

        /*
         * Current AutoClickService API:
         *
         * ACTION_START
         * EXTRA_TARGETS
         *
         * TextSequence controls target order.
         */
        val serviceIntent =
            Intent(
                this,
                AutoClickService::class.java
            ).apply {

                action =
                    AutoClickService.ACTION_START

                putExtra(
                    AutoClickService.EXTRA_TARGETS,
                    pendingTargetText
                )
            }

        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )

        /*
         * AutoClickService sets its own isRunning state
         * after successful initialization.
         *
         * Update UI immediately for responsiveness.
         */
        updateStatusUi(
            running = true
        )
    }

    private fun onStopClicked() {

        val serviceIntent =
            Intent(
                this,
                AutoClickService::class.java
            ).apply {

                action =
                    AutoClickService.ACTION_STOP
            }

        startService(
            serviceIntent
        )

        updateStatusUi(
            running = false
        )
    }

    private fun updateStatusUi(
        running: Boolean =
            AutoClickService.isRunning
    ) {

        binding.statusText.text =
            getString(
                if (running) {
                    R.string.status_running
                } else {
                    R.string.status_idle
                }
            )

        binding.startBtn.isEnabled =
            !running

        binding.stopBtn.isEnabled =
            running
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

        splitter.setString(
            enabledServices
        )

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
