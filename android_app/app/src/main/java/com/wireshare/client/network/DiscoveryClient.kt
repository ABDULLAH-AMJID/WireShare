package com.wireshare.client.network

import android.util.Log
import com.google.gson.Gson
import com.wireshare.client.data.model.ServerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryClient(private val discoveryPort: Int = 8767) {

    private val gson = Gson()

    suspend fun discoverServer(timeoutMs: Int = 3000): ServerInfo? = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            socket.broadcast = true
            socket.soTimeout = timeoutMs

            val payload = """{"type":"DISCOVER","device":"WireShare-Android","version":"1.0"}"""
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val sendPacket = DatagramPacket(bytes, bytes.size, broadcastAddr, discoveryPort)

            Log.d("DiscoveryClient", "Sending UDP broadcast DISCOVER to port $discoveryPort...")
            socket.send(sendPacket)

            val buffer = ByteArray(2048)
            val recvPacket = DatagramPacket(buffer, buffer.size)
            socket.receive(recvPacket)

            val responseStr = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
            Log.d("DiscoveryClient", "Received reply from ${recvPacket.address.hostAddress}: $responseStr")

            val serverInfo = gson.fromJson(responseStr, ServerInfo::class.java)
            // Ensure IP is set to the packet source address if server reported a loopback/local hostname
            return@withContext serverInfo.copy(ip = recvPacket.address.hostAddress ?: serverInfo.ip)

        } catch (e: Exception) {
            Log.e("DiscoveryClient", "Discovery failed or timed out: ${e.message}")
            return@withContext null
        } finally {
            socket?.close()
        }
    }
}
