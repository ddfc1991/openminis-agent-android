package com.openminis.agent

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI

class MainActivity : AppCompatActivity() {
    private var ws: WebSocketClient? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(android.R.layout.simple_list_item_1)

        // Very simple UI is left as exercise; this skeleton focuses on WS connectivity
        // You should replace with proper layout and input fields.

        // Example connect on start with hardcoded values (for skeleton/testing)
        val serverUrl = intent.getStringExtra("serverUrl") ?: "ws://10.0.2.2:3000/agent"
        val token = intent.getStringExtra("token") ?: ""

        connectWs(serverUrl, token)
    }

    private fun connectWs(serverUrl: String, token: String) {
        try {
            val uri = URI(serverUrl)
            ws = object : WebSocketClient(uri) {
                override fun onOpen(handshakedata: ServerHandshake?) {
                    Log.i("Agent", "ws open")
                    // send register
                    val register = "{ \"type\": \"register\", \"deviceId\": \"device-android-1\", \"token\": \"$token\", \"info\": { \"model\": \"android\" } }"
                    send(register)
                }

                override fun onMessage(message: String?) {
                    Log.i("Agent", "recv: $message")
                    // handle command messages and reply with command_result
                    // For skeleton, simply ack commands
                    try {
                        val data = message ?: return
                        // Parse JSON and respond with command_result when needed
                        // TODO: implement proper JSON parsing and action handling
                    } catch (e: Exception) {
                        Log.e("Agent", "err parse", e)
                    }
                }

                override fun onClose(code: Int, reason: String?, remote: Boolean) {
                    Log.i("Agent", "ws close $code $reason")
                }

                override fun onError(ex: Exception?) {
                    Log.e("Agent", "ws err", ex)
                }
            }
            ws?.connect()
        } catch (e: Exception) {
            Log.e("Agent", "connect fail", e)
        }
    }
}
