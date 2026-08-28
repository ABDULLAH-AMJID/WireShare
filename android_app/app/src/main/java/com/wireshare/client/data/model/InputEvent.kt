package com.wireshare.client.data.model

import com.google.gson.annotations.SerializedName

data class ServerInfo(
    @SerializedName("hostname") val hostname: String = "Desktop-PC",
    @SerializedName("ip") val ip: String = "192.168.1.50",
    @SerializedName("ws_port") val wsPort: Int = 8765,
    @SerializedName("udp_port") val udpPort: Int = 8766,
    @SerializedName("status") val status: String = "READY"
)

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    PAIRING,
    CONNECTED,
    MODE_ACTIVE
}

data class MouseEvent(
    val seqNum: Long,
    val dx: Int,
    val dy: Int,
    val buttons: Int,
    val scrollY: Int,
    val scrollX: Int
) {
    companion object {
        const val BTN_LEFT = 0x01
        const val BTN_RIGHT = 0x02
        const val BTN_MIDDLE = 0x04
    }
}

data class KeyboardEvent(
    @SerializedName("action") val action: String = "KEY_DOWN",
    @SerializedName("key_code") val keyCode: String = "",
    @SerializedName("char") val char: String = "",
    @SerializedName("modifiers") val modifiers: List<String> = emptyList()
)

data class WsMessage(
    @SerializedName("type") val type: String = "",
    @SerializedName("success") val success: Boolean = false,
    @SerializedName("message") val message: String = "",
    @SerializedName("active") val active: Boolean = false,
    @SerializedName("mode") val mode: String = "",
    @SerializedName("action") val action: String = "",
    @SerializedName("key_code") val keyCode: String = "",
    @SerializedName("char") val char: String = ""
)
