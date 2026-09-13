package com.example.remoteeyes

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), ScreenCaptureService.UiListener {

    companion object {
        private const val TAG = "HELP_WEBRTC"
        private const val SCREEN_CAPTURE_REQUEST = 100
        private const val NOTIFICATION_PERMISSION_REQUEST = 101

        private const val COLOR_SHARE = "#2E7D32"
        private const val COLOR_STOP = "#C62828"
    }

    private lateinit var status: TextView
    private lateinit var codeText: TextView
    private lateinit var actionButton: Button

    private var isSharing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        status = TextView(this).apply {
            textSize = 24f
            gravity = Gravity.CENTER
        }

        codeText = TextView(this).apply {
            textSize = 72f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = android.view.View.GONE
        }

        actionButton = Button(this).apply {
            textSize = 28f
            minHeight = dpToPx(96)
            setOnClickListener {
                if (isSharing) {
                    stopSharing()
                } else {
                    requestScreenCapture()
                }
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(32), dpToPx(48), dpToPx(32), dpToPx(32))

            addView(
                status,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(24) }
            )

            addView(
                codeText,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dpToPx(24) }
            )

            addView(
                actionButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(layout)

        setSharingUiState(sharing = false)
        status.text = getString(R.string.ready_status)
    }

    override fun onStart() {
        super.onStart()
        ScreenCaptureService.uiListener = this
    }

    override fun onStop() {
        if (ScreenCaptureService.uiListener === this) {
            ScreenCaptureService.uiListener = null
        }

        super.onStop()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun setSharingUiState(sharing: Boolean) {
        isSharing = sharing

        actionButton.text =
            getString(if (sharing) R.string.stop_button else R.string.share_button)

        actionButton.setBackgroundColor(
            Color.parseColor(if (sharing) COLOR_STOP else COLOR_SHARE)
        )
        actionButton.setTextColor(Color.WHITE)

        if (!sharing) {
            codeText.visibility = android.view.View.GONE
            codeText.text = ""
        }
    }

    private fun stopSharing() {
        val intent = Intent(
            this@MainActivity,
            ScreenCaptureService::class.java
        ).apply {
            action = ScreenCaptureService.ACTION_STOP
        }

        startService(intent)

        setSharingUiState(sharing = false)
        status.text = getString(R.string.stopped_status)
    }

    private fun requestScreenCapture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Requesting POST_NOTIFICATIONS permission")

            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_PERMISSION_REQUEST
            )
            return
        }

        startMediaProjectionRequest()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode != NOTIFICATION_PERMISSION_REQUEST) {
            return
        }

        Log.d(
            TAG,
            "POST_NOTIFICATIONS result: granted=" +
                "${grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED}"
        )

        startMediaProjectionRequest()
    }

    private fun startMediaProjectionRequest() {
        Log.d(TAG, "Requesting MediaProjection permission")

        actionButton.isEnabled = false
        status.text = getString(R.string.requesting_permission)

        val manager =
            getSystemService(MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager

        val intent = manager.createScreenCaptureIntent()

        startActivityForResult(
            intent,
            SCREEN_CAPTURE_REQUEST
        )
    }

    @Deprecated("Using Activity Result API comes next")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != SCREEN_CAPTURE_REQUEST) {
            return
        }

        Log.d(
            TAG,
            "MediaProjection result: resultCode=$resultCode data=${data != null}"
        )

        actionButton.isEnabled = true

        if (resultCode != RESULT_OK || data == null) {
            status.text = getString(R.string.share_cancelled)
            return
        }

        setSharingUiState(sharing = true)
        status.text = getString(R.string.starting_status)

        val serviceIntent =
            Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START

                putExtra(
                    ScreenCaptureService.EXTRA_RESULT_CODE,
                    resultCode
                )

                putExtra(
                    ScreenCaptureService.EXTRA_PROJECTION_DATA,
                    data
                )
            }

        Log.d(
            TAG,
            "Starting ScreenCaptureService"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    // ---------------------------------------------------------------------
    // ScreenCaptureService.UiListener
    // ---------------------------------------------------------------------

    override fun onHelpCode(code: String) {
        runOnUiThread {
            codeText.text = code.chunked(2).joinToString(" ")
            codeText.visibility = android.view.View.VISIBLE
            status.text = getString(R.string.waiting_for_helper)
        }
    }

    override fun onHelperConnected() {
        runOnUiThread {
            status.text = getString(R.string.helper_connected_status)
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread {
            status.text = text

            // The service has already stopped itself at this point
            // (ScreenCaptureService.onSessionExpired calls
            // stopScreenCapture()) - reflect that in the button/code
            // display rather than leaving them showing a "Stop sharing"
            // state for a session that no longer exists.
            if (text == getString(R.string.session_expired_status)) {
                setSharingUiState(sharing = false)
            }
        }
    }
}
