package com.example.remoteeyes

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.IOException

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class ScreenCaptureService : Service(), SignalingClient.Listener {

interface UiListener {
    fun onHelpCode(code: String)
    fun onHelperConnected()
    fun onStatus(text: String)
}

companion object {

    private const val TAG = "HELP_WEBRTC"

    const val ACTION_START =
        "com.example.remoteeyes.START_SCREEN_CAPTURE"

    const val ACTION_STOP =
        "com.example.remoteeyes.STOP_SCREEN_CAPTURE"

    const val EXTRA_RESULT_CODE =
        "resultCode"

    const val EXTRA_PROJECTION_DATA =
        "projectionData"

    private const val CHANNEL_ID =
        "screen_capture"

    private const val NOTIFICATION_ID =
        1001

    private const val STOP_REQUEST_CODE =
        2001

    var uiListener: UiListener? = null
}

private var mediaProjection: MediaProjection? = null

private var peerConnectionFactory:
        PeerConnectionFactory? = null

private var videoSource:
        VideoSource? = null

private var videoTrack:
        VideoTrack? = null

private var screenCapturer:
        ScreenCapturerAndroid? = null

private var surfaceTextureHelper:
        SurfaceTextureHelper? = null

private var eglBase:
        EglBase? = null

private var peerConnection:
        PeerConnection? = null

private var signalingClient:
        SignalingClient? = null

private val mainHandler =
    Handler(Looper.getMainLooper())

/*
 * Exact Intent returned by
 * MediaProjectionManager.createScreenCaptureIntent().
 */
private var projectionIntent: Intent? = null

private var started = false

/*
 * Prevent cleanup from running twice.
 */
private var stopping = false

// ==============================================================
// Service lifecycle
// ==============================================================

override fun onCreate() {
    super.onCreate()

    Log.d(TAG, "=== onCreate ===")

    createNotificationChannel()
    startForegroundNotification()

    Log.d(TAG, "Foreground service started")
}

override fun onStartCommand(
    intent: Intent?,
    flags: Int,
    startId: Int
): Int {

    Log.d(TAG, "=== onStartCommand ===")
    Log.d(TAG, "intent=$intent")
    Log.d(TAG, "action=${intent?.action}")
    Log.d(TAG, "extras=${intent?.extras}")

    /*
     * ----------------------------------------------------------
     * STOP
     * ----------------------------------------------------------
     */
    if (intent?.action == ACTION_STOP) {

        Log.d(TAG, "Stop request received")

        stopScreenCapture()

        return START_NOT_STICKY
    }

    /*
     * No Intent.
     */
    if (intent == null) {

        Log.e(TAG, "Intent is null")

        stopSelf()

        return START_NOT_STICKY
    }

    /*
     * Result code returned from the Android
     * MediaProjection permission dialog.
     */
    val resultCode =
        intent.getIntExtra(
            EXTRA_RESULT_CODE,
            Activity.RESULT_CANCELED
        )

    /*
     * Retrieve the permission Intent.
     */
    val projectionData: Intent? =
        if (Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.TIRAMISU
        ) {

            intent.getParcelableExtra(
                EXTRA_PROJECTION_DATA,
                Intent::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            intent.getParcelableExtra(
                EXTRA_PROJECTION_DATA
            )
        }

    Log.d(
        TAG,
        "resultCode=$resultCode"
    )

    Log.d(
        TAG,
        "projection data exists=${projectionData != null}"
    )

    /*
     * Permission wasn't granted.
     */
    if (resultCode != Activity.RESULT_OK) {

        Log.e(
            TAG,
            "Invalid MediaProjection resultCode: $resultCode"
        )

        stopSelf()

        return START_NOT_STICKY
    }

    /*
     * Permission Intent is required.
     */
    if (projectionData == null) {

        Log.e(
            TAG,
            "MediaProjection permission data is null"
        )

        stopSelf()

        return START_NOT_STICKY
    }

    /*
     * Don't initialize twice.
     */
    if (started) {

        Log.d(
            TAG,
            "Screen capture already started"
        )

        return START_STICKY
    }

    /*
     * Store the exact permission Intent.
     */
    projectionIntent = projectionData

    Log.d(
        TAG,
        "Projection Intent stored successfully"
    )

    try {

        /*
         * ------------------------------------------------------
         * MediaProjection
         * ------------------------------------------------------
         */

        Log.d(
            TAG,
            "Obtaining MediaProjection"
        )

        val projectionManager =
            getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as android.media.projection.MediaProjectionManager

        mediaProjection =
            projectionManager.getMediaProjection(
                resultCode,
                projectionData
            )

        if (mediaProjection == null) {

            throw IllegalStateException(
                "MediaProjection returned null"
            )
        }

        Log.d(
            TAG,
            "MediaProjection obtained"
        )

        /*
         * Android requires a MediaProjection callback.
         */
        mediaProjection!!.registerCallback(
            object : MediaProjection.Callback() {

                override fun onStop() {

                    Log.d(
                        TAG,
                        "MediaProjection.onStop()"
                    )

                    mainHandler.post {

                        if (!stopping) {
                            stopScreenCapture()
                        }
                    }
                }
            },
            mainHandler
        )

        /*
         * ------------------------------------------------------
         * WebRTC
         * ------------------------------------------------------
         */

        initializeWebRtc()

        started = true

        Log.d(
            TAG,
            "Screen capture initialization complete"
        )

        return START_STICKY

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Failed to start screen capture",
            e
        )

        stopScreenCapture()

        return START_NOT_STICKY
    }
}

// ==============================================================
// WebRTC initialization
// ==============================================================

private fun initializeWebRtc() {

    Log.d(
        TAG,
        "Initializing WebRTC"
    )

    /*
     * Shared, process-lifetime EGL context and
     * PeerConnectionFactory, owned by WebRtcRuntime.
     *
     * WebRtcRuntime.initialize() runs the WebRTC native
     * library's global PeerConnectionFactory.initialize()
     * exactly once per process (guarded internally). Building a
     * fresh EglBase/PeerConnectionFactory here on every session
     * start would re-run that global init a second time if this
     * service is stopped and restarted within the same process,
     * which is unsafe. eglBase/peerConnectionFactory below are
     * therefore borrowed references, not owned by this service.
     */
    eglBase =
        WebRtcRuntime.eglBase(applicationContext)

    peerConnectionFactory =
        WebRtcRuntime.factory(applicationContext)

    Log.d(
        TAG,
        "PeerConnectionFactory created"
    )

    /*
     * Video source.
     */
    videoSource =
        peerConnectionFactory!!.createVideoSource(
            false
        )

    Log.d(
        TAG,
        "VideoSource created"
    )

    /*
     * SurfaceTextureHelper.
     */
    surfaceTextureHelper =
        SurfaceTextureHelper.create(
            "ScreenCaptureThread",
            eglBase!!.eglBaseContext
        )

    if (surfaceTextureHelper == null) {

        throw IllegalStateException(
            "Could not create SurfaceTextureHelper"
        )
    }

    Log.d(
        TAG,
        "SurfaceTextureHelper created"
    )

    /*
     * Permission Intent.
     */
    val captureIntent =
        projectionIntent
            ?: throw IllegalStateException(
                "Projection Intent unavailable"
            )

    /*
     * ScreenCapturerAndroid.
     */
    screenCapturer =
        ScreenCapturerAndroid(
            captureIntent,
            object : MediaProjection.Callback() {

                override fun onStop() {

                    Log.d(
                        TAG,
                        "ScreenCapturer MediaProjection stopped"
                    )

                    mainHandler.post {

                        if (!stopping) {
                            stopScreenCapture()
                        }
                    }
                }
            }
        )

    Log.d(
        TAG,
        "ScreenCapturerAndroid initialized"
    )

    /*
     * Connect capturer to VideoSource.
     */
    screenCapturer!!.initialize(
        surfaceTextureHelper,
        applicationContext,
        videoSource!!.capturerObserver
    )

    /*
     * Capture configuration.
     */
    val width = 720
    val height = 1280
    val fps = 30

    /*
     * Start screen capture.
     */
    screenCapturer!!.startCapture(
        width,
        height,
        fps
    )

    Log.d(
        TAG,
        "Screen capture started: ${width}x${height}@${fps}"
    )

    /*
     * VideoTrack.
     */
    videoTrack =
        peerConnectionFactory!!.createVideoTrack(
            "screen-video",
            videoSource
        )

    videoTrack!!.setEnabled(true)

    Log.d(
        TAG,
        "VideoTrack created"
    )

    Log.d(
        TAG,
        "VideoTrack enabled=${videoTrack!!.enabled()}"
    )

    /*
     * Start signaling.
     */
    setupSignaling()
}

// ==============================================================
// Signaling
// ==============================================================

private fun setupSignaling() {

    Log.d(
        TAG,
        "Connecting to signaling server: ${BuildConfig.SIGNALING_URL}"
    )

    signalingClient =
        SignalingClient(
            BuildConfig.SIGNALING_URL,
            this
        )

    signalingClient!!.connect()
}

override fun onOpen() {

    Log.d(
        TAG,
        "Signaling connected, requesting session"
    )

    mainHandler.post {

        signalingClient?.requestSession()
    }
}

override fun onHelpCode(
    code: String
) {

    Log.d(
        TAG,
        "Help code: $code"
    )

    mainHandler.post {

        uiListener?.onHelpCode(code)
    }
}

override fun onHelperConnected() {

    Log.d(
        TAG,
        "Helper connected, creating offer"
    )

    mainHandler.post {

        uiListener?.onHelperConnected()

        fetchIceServersAndOffer()
    }
}

override fun onPeerDisconnected() {

    Log.d(
        TAG,
        "Helper disconnected"
    )

    mainHandler.post {

        uiListener?.onStatus(
            "Helper disconnected"
        )
    }
}

override fun onSessionExpired() {

    Log.d(
        TAG,
        "Session expired"
    )

    mainHandler.post {

        uiListener?.onStatus(
            "Session expired"
        )
    }

    stopScreenCapture()
}

override fun onSignal(
    json: JSONObject
) {

    mainHandler.post {

        when (
            json.optString("signalType")
        ) {

            "answer" -> {

                val sdp =
                    SessionDescription(
                        SessionDescription.Type.ANSWER,
                        json.optString("sdp")
                    )

                peerConnection?.setRemoteDescription(
                    object : SdpObserver {

                        override fun onSetSuccess() {

                            Log.d(
                                TAG,
                                "Remote description (answer) set"
                            )
                        }

                        override fun onSetFailure(
                            error: String?
                        ) {

                            Log.e(
                                TAG,
                                "setRemoteDescription failed: $error"
                            )
                        }

                        override fun onCreateSuccess(
                            p0: SessionDescription?
                        ) {
                        }

                        override fun onCreateFailure(
                            p0: String?
                        ) {
                        }

                    },
                    sdp
                )
            }

            "ice-candidate" -> {

                val candidateSdp =
                    json.optString(
                        "candidate"
                    )

                if (
                    candidateSdp.isBlank()
                ) {

                    Log.d(
                        TAG,
                        "Received end-of-candidates marker"
                    )

                } else {

                    val candidate =
                        IceCandidate(
                            json.optString(
                                "sdpMid"
                            ),
                            json.optInt(
                                "sdpMLineIndex"
                            ),
                            candidateSdp
                        )

                    peerConnection
                        ?.addIceCandidate(
                            candidate
                        )
                }
            }

            else -> {

                Log.d(
                    TAG,
                    "Unhandled signalType: " +
                        json.optString("signalType")
                )
            }
        }
    }
}

override fun onError(
    message: String
) {

    Log.e(
        TAG,
        "Signaling error: $message"
    )

    mainHandler.post {

        uiListener?.onStatus(
            "Error: $message"
        )
    }
}

override fun onClosed() {

    Log.d(
        TAG,
        "Signaling closed"
    )
}

// ==============================================================
// PeerConnection
// ==============================================================

private fun fetchIceServersAndOffer() {

    val iceConfigUrl =
        BuildConfig.SIGNALING_URL
            .replaceFirst("wss://", "https://")
            .replaceFirst("ws://", "http://") +
            "/ice-config"

    val client = OkHttpClient()

    val request =
        Request.Builder()
            .url(iceConfigUrl)
            .build()

    client.newCall(request).enqueue(object : Callback {

        override fun onFailure(call: Call, e: IOException) {

            Log.e(
                TAG,
                "Failed to fetch ICE config, falling back to STUN-only",
                e
            )

            mainHandler.post {
                createPeerConnectionAndOffer(defaultIceServers())
            }
        }

        override fun onResponse(call: Call, response: Response) {

            val servers =
                try {
                    parseIceServers(
                        response.body?.string().orEmpty()
                    )
                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed to parse ICE config",
                        e
                    )

                    defaultIceServers()
                }

            mainHandler.post {
                createPeerConnectionAndOffer(servers)
            }
        }
    })
}

private fun defaultIceServers(): List<PeerConnection.IceServer> =
    listOf(
        PeerConnection.IceServer
            .builder("stun:stun.l.google.com:19302")
            .createIceServer()
    )

private fun parseIceServers(json: String): List<PeerConnection.IceServer> {

    val array = JSONObject(json).getJSONArray("iceServers")

    return (0 until array.length()).map { i ->

        val entry = array.getJSONObject(i)
        val urlsValue = entry.get("urls")

        val builder =
            if (urlsValue is JSONArray) {
                val urls = (0 until urlsValue.length()).map { urlsValue.getString(it) }
                PeerConnection.IceServer.builder(urls)
            } else {
                PeerConnection.IceServer.builder(urlsValue as String)
            }

        if (entry.has("username")) {
            builder.setUsername(entry.getString("username"))
        }

        if (entry.has("credential")) {
            builder.setPassword(entry.getString("credential"))
        }

        builder.createIceServer()
    }
}

private fun createPeerConnectionAndOffer(
    iceServers: List<PeerConnection.IceServer>
) {

    if (stopping) {
        return
    }

    if (peerConnection != null) {

        Log.d(
            TAG,
            "PeerConnection already exists"
        )

        return
    }

    val rtcConfig =
        PeerConnection
            .RTCConfiguration(
                iceServers
            )
            .apply {

                sdpSemantics =
                    PeerConnection
                        .SdpSemantics
                        .UNIFIED_PLAN

                tcpCandidatePolicy =
                    PeerConnection
                        .TcpCandidatePolicy
                        .DISABLED
            }

    peerConnection =
        peerConnectionFactory!!
            .createPeerConnection(
                rtcConfig,
                object : PeerConnection.Observer {

                    override fun onIceCandidate(
                        candidate: IceCandidate
                    ) {

                        sendIceCandidate(
                            candidate
                        )
                    }

                    override fun onIceCandidatesRemoved(
                        candidates: Array<out IceCandidate>
                    ) {
                    }

                    override fun onConnectionChange(
                        newState:
                        PeerConnection.PeerConnectionState
                    ) {

                        Log.d(
                            TAG,
                            "PeerConnection state: $newState"
                        )

                        if (
                            newState ==
                            PeerConnection.PeerConnectionState.FAILED
                        ) {

                            mainHandler.post {

                                uiListener?.onStatus(
                                    "WebRTC connection failed"
                                )
                            }
                        }
                    }

                    override fun onIceConnectionChange(
                        newState:
                        PeerConnection.IceConnectionState
                    ) {

                        Log.d(
                            TAG,
                            "ICE connection state: $newState"
                        )
                    }

                    override fun onIceConnectionReceivingChange(
                        receiving: Boolean
                    ) {
                    }

                    override fun onIceGatheringChange(
                        newState:
                        PeerConnection.IceGatheringState
                    ) {

                        Log.d(
                            TAG,
                            "ICE gathering state: $newState"
                        )
                    }

                    override fun onAddStream(
                        stream: MediaStream?
                    ) {
                    }

                    override fun onRemoveStream(
                        stream: MediaStream?
                    ) {
                    }

                    override fun onDataChannel(
                        dataChannel: DataChannel?
                    ) {
                    }

                    override fun onRenegotiationNeeded() {
                    }

                    override fun onSignalingChange(
                        newState:
                        PeerConnection.SignalingState
                    ) {
                    }

                    override fun onAddTrack(
                        receiver: RtpReceiver?,
                        mediaStreams:
                        Array<out MediaStream>?
                    ) {
                    }
                }
            )

    if (peerConnection == null) {

        Log.e(
            TAG,
            "Failed to create PeerConnection"
        )

        return
    }

    /*
     * Add screen video.
     */
    peerConnection!!.addTrack(
        videoTrack,
        listOf(
            "screen-share-stream"
        )
    )

    /*
     * Create offer.
     */
    peerConnection!!.createOffer(
        object : SdpObserver {

            override fun onCreateSuccess(
                sdp: SessionDescription
            ) {

                if (stopping) {
                    return
                }

                peerConnection!!
                    .setLocalDescription(
                        object : SdpObserver {

                            override fun onSetSuccess() {

                                Log.d(
                                    TAG,
                                    "Local description set, sending offer"
                                )

                                sendSessionDescription(
                                    sdp
                                )
                            }

                            override fun onSetFailure(
                                error: String?
                            ) {

                                Log.e(
                                    TAG,
                                    "setLocalDescription failed: $error"
                                )
                            }

                            override fun onCreateSuccess(
                                p0: SessionDescription?
                            ) {
                            }

                            override fun onCreateFailure(
                                p0: String?
                            ) {
                            }

                        },
                        sdp
                    )
            }

            override fun onCreateFailure(
                error: String?
            ) {

                Log.e(
                    TAG,
                    "createOffer failed: $error"
                )
            }

            override fun onSetSuccess() {
            }

            override fun onSetFailure(
                error: String?
            ) {
            }

        },
        MediaConstraints()
    )
}

private fun sendSessionDescription(
    sdp: SessionDescription
) {

    val json =
        JSONObject().apply {

            put(
                "signalType",
                sdp.type.canonicalForm()
            )

            put(
                "sdp",
                sdp.description
            )
        }

    signalingClient?.sendSignal(
        json
    )
}

private fun sendIceCandidate(
    candidate: IceCandidate
) {

    if (stopping) {
        return
    }

    val json =
        JSONObject().apply {

            put(
                "signalType",
                "ice-candidate"
            )

            put(
                "sdpMid",
                candidate.sdpMid
            )

            put(
                "sdpMLineIndex",
                candidate.sdpMLineIndex
            )

            put(
                "candidate",
                candidate.sdp
            )
        }

    signalingClient?.sendSignal(
        json
    )
}

// ==============================================================
// STOP / CLEANUP
// ==============================================================

private fun stopScreenCapture() {

    if (stopping) {

        Log.d(
            TAG,
            "Stop already in progress"
        )

        return
    }

    stopping = true

    Log.d(
        TAG,
        "=== STOP SCREEN CAPTURE ==="
    )

    /*
     * Tell UI.
     */
    mainHandler.post {

        uiListener?.onStatus(
            "Stopping screen sharing..."
        )
    }

    /*
     * ----------------------------------------------------------
     * Signaling
     * ----------------------------------------------------------
     */

    try {

        signalingClient?.close()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error closing signaling client",
            e
        )
    }

    signalingClient = null

    /*
     * ----------------------------------------------------------
     * PeerConnection
     * ----------------------------------------------------------
     */

    try {

        peerConnection?.close()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error closing PeerConnection",
            e
        )
    }

    try {

        peerConnection?.dispose()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disposing PeerConnection",
            e
        )
    }

    peerConnection = null

    /*
     * ----------------------------------------------------------
     * VideoTrack
     * ----------------------------------------------------------
     */

    try {

        videoTrack?.setEnabled(false)

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disabling VideoTrack",
            e
        )
    }

    try {

        videoTrack?.dispose()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disposing VideoTrack",
            e
        )
    }

    videoTrack = null

    /*
     * ----------------------------------------------------------
     * ScreenCapturer
     * ----------------------------------------------------------
     */

    try {

        screenCapturer?.stopCapture()

        Log.d(
            TAG,
            "Screen capturer stopped"
        )

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error stopping screen capturer",
            e
        )
    }

    try {

        screenCapturer?.dispose()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disposing screen capturer",
            e
        )
    }

    screenCapturer = null

    /*
     * ----------------------------------------------------------
     * VideoSource
     * ----------------------------------------------------------
     */

    try {

        videoSource?.dispose()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disposing VideoSource",
            e
        )
    }

    videoSource = null

    /*
     * ----------------------------------------------------------
     * SurfaceTextureHelper
     * ----------------------------------------------------------
     */

    try {

        surfaceTextureHelper?.dispose()

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error disposing SurfaceTextureHelper",
            e
        )
    }

    surfaceTextureHelper = null

    /*
     * ----------------------------------------------------------
     * PeerConnectionFactory
     * ----------------------------------------------------------
     *
     * peerConnectionFactory is the process-lifetime singleton
     * owned by WebRtcRuntime, not a per-session object created
     * by this service. Do NOT dispose it here: WebRtcRuntime has
     * no way to know its cached factory was torn down, so its
     * "initialized" flag would stay true while the underlying
     * native object is gone, permanently breaking every future
     * screen-share session in this process. Just drop this
     * service's local reference to it.
     */

    peerConnectionFactory = null

    /*
     * ----------------------------------------------------------
     * MediaProjection
     * ----------------------------------------------------------
     */

    try {

        mediaProjection?.stop()

        Log.d(
            TAG,
            "MediaProjection stopped"
        )

    } catch (e: Exception) {

        Log.e(
            TAG,
            "Error stopping MediaProjection",
            e
        )
    }

    mediaProjection = null

    /*
     * ----------------------------------------------------------
     * EGL
     * ----------------------------------------------------------
     *
     * eglBase is likewise the process-lifetime singleton owned
     * by WebRtcRuntime. Do NOT release it here, for the same
     * reason as peerConnectionFactory above.
     */

    eglBase = null

    /*
     * Clear permission data.
     */
    projectionIntent = null

    started = false

    mainHandler.post {

        uiListener?.onStatus(
            "Screen sharing stopped."
        )
    }

    Log.d(
        TAG,
        "All screen sharing resources released"
    )

    /*
     * Finally stop the foreground service.
     */
    stopForeground(STOP_FOREGROUND_REMOVE)

    stopSelf()
}

// ==============================================================
// Service binding
// ==============================================================

override fun onBind(
    intent: Intent?
): IBinder? {

    return null
}

// ==============================================================
// onDestroy
// ==============================================================

override fun onDestroy() {

    Log.d(
        TAG,
        "=== onDestroy ==="
    )

    /*
     * If Android destroys the service without our explicit
     * stop path running, clean everything here as well.
     */
    if (!stopping) {

        stopScreenCapture()
    }

    super.onDestroy()
}

// ==============================================================
// Notification
// ==============================================================

private fun createNotificationChannel() {

    if (
        Build.VERSION.SDK_INT <
        Build.VERSION_CODES.O
    ) {
        return
    }

    val channel =
        NotificationChannel(
            CHANNEL_ID,
            "Screen sharing",
            NotificationManager.IMPORTANCE_LOW
        )

    channel.description =
        "Screen sharing is active"

    val manager =
        getSystemService(
            NotificationManager::class.java
        )

    manager.createNotificationChannel(
        channel
    )
}

private fun startForegroundNotification() {

    /*
     * Intent used by the notification's
     * "Stop sharing" button.
     */
    val stopIntent =
        Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {

            action = ACTION_STOP
        }

    val stopPendingIntent =
        PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

    val notification: Notification

    if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.O
    ) {

        notification =
            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "Screen sharing"
                )
                .setContentText(
                    "Your screen is being shared"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Stop sharing",
                        stopPendingIntent
                    ).build()
                )
                .build()

    } else {

        @Suppress("DEPRECATION")
        notification =
            Notification.Builder(this)
                .setContentTitle(
                    "Screen sharing"
                )
                .setContentText(
                    "Your screen is being shared"
                )
                .setSmallIcon(
                    android.R.drawable.ic_menu_view
                )
                .setOngoing(true)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Stop sharing",
                        stopPendingIntent
                    ).build()
                )
                .build()
    }

    /*
     * Android 10+ requires the MediaProjection
     * foreground-service type.
     */
    if (
        Build.VERSION.SDK_INT >=
        Build.VERSION_CODES.Q
    ) {

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo
                .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        )

    } else {

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }
}

}
