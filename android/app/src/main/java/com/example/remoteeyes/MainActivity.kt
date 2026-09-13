package com.example.remoteeyes

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity(), ScreenCaptureService.UiListener {

    companion object {
        private const val TAG = "HELP_WEBRTC"
        private const val SCREEN_CAPTURE_REQUEST = 100
    }

    private lateinit var status: TextView
    private lateinit var shareButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "Ready"
            textSize = 20f
        }

        shareButton = Button(this).apply {
            text = "Share screen"
            setOnClickListener {
                requestScreenCapture()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop sharing"
            setOnClickListener {
                val intent = Intent(
                    this@MainActivity,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_STOP
                }

                startService(intent)

                status.text = "Screen sharing stopped."
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 80, 48, 48)

            addView(
                status,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                shareButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )

            addView(
                stopButton,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        setContentView(layout)
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

    private fun requestScreenCapture() {
        Log.d(TAG, "Requesting MediaProjection permission")

        shareButton.isEnabled = false
        status.text = "Requesting screen-sharing permission..."

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

        shareButton.isEnabled = true

        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen sharing cancelled."
            return
        }

        status.text = "Permission granted. Starting capture..."

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
            status.text =
                "Help code: $code\nWaiting for helper..."
        }
    }

    override fun onHelperConnected() {
        runOnUiThread {
            status.text =
                "Helper connected.\nStarting WebRTC..."
        }
    }

    override fun onStatus(text: String) {
        runOnUiThread {
            status.text = text
        }
    }
}
