package com.example.remoteeyes

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onOpen()
        fun onHelpCode(code: String)
        fun onHelperConnected()
        fun onPeerDisconnected()
        fun onSessionExpired()
        fun onSignal(json: JSONObject)
        fun onError(message: String)
        fun onClosed()
    }

    companion object {
        private const val TAG = "HELP_WEBRTC_SIGNAL"
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

    fun connect() {
        val request = Request.Builder().url(serverUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket opened")
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Message: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket failure", t)
                listener.onError(t.message ?: "WebSocket failure")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                listener.onClosed()
            }
        })
    }

    private fun handleMessage(text: String) {
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            Log.e(TAG, "Invalid JSON from server", e)
            return
        }

        when (json.optString("type")) {
            "help-code" -> listener.onHelpCode(json.optString("code"))
            "helper-connected" -> listener.onHelperConnected()
            "peer-disconnected" -> listener.onPeerDisconnected()
            "expired" -> listener.onSessionExpired()
            "error" -> listener.onError(json.optString("message"))
            "signal" -> listener.onSignal(json)
            else -> Log.d(TAG, "Unhandled message type: ${json.optString("type")}")
        }
    }

    fun requestSession() {
        send(JSONObject().put("type", "create").put("token", BuildConfig.APP_TOKEN))
    }

    fun sendSignal(payload: JSONObject) {
        payload.put("type", "signal")
        send(payload)
    }

    private fun send(json: JSONObject) {
        val sent = webSocket?.send(json.toString()) ?: false
        if (!sent) {
            Log.e(TAG, "Failed to send: $json")
        }
    }

    fun close() {
        webSocket?.close(1000, "Client closing")
        webSocket = null
    }
}
