package com.example.remoteeyes

import android.content.Context
import android.util.Log

import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.PeerConnectionFactory

object WebRtcRuntime {

    private const val TAG = "HELP_WEBRTC"

    private val lock = Any()

    @Volatile
    private var initialized = false

    private var eglBase: EglBase? = null

    private var factory: PeerConnectionFactory? = null

    fun initialize(context: Context) {
        if (initialized) {
            return
        }

        synchronized(lock) {
            if (initialized) {
                return
            }

            Log.d(TAG, "Initializing global WebRTC runtime")

            val applicationContext =
                context.applicationContext

            val newEglBase =
                EglBase.create()

            try {
                /*
                 * WebRTC native library initialization.
                 *
                 * IMPORTANT:
                 * This is called exactly once for the process.
                 */
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory
                        .InitializationOptions
                        .builder(applicationContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )

                /*
                 * Hardware accelerated video codecs.
                 */
                val encoderFactory =
                    DefaultVideoEncoderFactory(
                        newEglBase.eglBaseContext,
                        true,
                        true
                    )

                val decoderFactory =
                    DefaultVideoDecoderFactory(
                        newEglBase.eglBaseContext
                    )

                /*
                 * Keep the network monitor enabled.
                 *
                 * ACCESS_NETWORK_STATE MUST be present in AndroidManifest.xml.
                 */
                val options =
                    PeerConnectionFactory.Options().apply {
                        disableNetworkMonitor = false
                    }

                val newFactory =
                    PeerConnectionFactory
                        .builder()
                        .setOptions(options)
                        .setVideoEncoderFactory(
                            encoderFactory
                        )
                        .setVideoDecoderFactory(
                            decoderFactory
                        )
                        .createPeerConnectionFactory()

                eglBase = newEglBase
                factory = newFactory
                initialized = true

                Log.d(
                    TAG,
                    "Global WebRTC runtime initialized"
                )

            } catch (t: Throwable) {

                Log.e(
                    TAG,
                    "WebRTC runtime initialization failed",
                    t
                )

                try {
                    newEglBase.release()
                } catch (_: Throwable) {
                }

                throw t
            }
        }
    }

    fun factory(context: Context): PeerConnectionFactory {
        initialize(context)

        return synchronized(lock) {
            factory
                ?: throw IllegalStateException(
                    "PeerConnectionFactory is not initialized"
                )
        }
    }

    fun eglBase(context: Context): EglBase {
        initialize(context)

        return synchronized(lock) {
            eglBase
                ?: throw IllegalStateException(
                    "EglBase is not initialized"
                )
        }
    }
}
