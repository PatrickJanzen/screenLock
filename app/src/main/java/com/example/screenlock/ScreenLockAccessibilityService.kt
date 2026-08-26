package com.example.screenlock

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ScreenLockAccessibilityService : AccessibilityService() {

    private var serverSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var executorService: ExecutorService? = null
    private val tcpPort = 9999
    private val udpPort = 9998

    companion object {
        private const val TAG = "ScreenLockService"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Accessibility Service Connected")
        startServers()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServers()
    }

    private fun startServers() {
        executorService = Executors.newFixedThreadPool(2)

        // TCP Lock Server
        executorService?.submit {
            try {
                serverSocket = ServerSocket(tcpPort)
                Log.d(TAG, "TCP Server started on port $tcpPort")
                while (!Thread.currentThread().isInterrupted && serverSocket?.isClosed == false) {
                    val socket = serverSocket?.accept() ?: break
                    handleTcpClient(socket)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in TCP server socket: ${e.message}", e)
            }
        }

        // UDP Discovery Server
        executorService?.submit {
            try {
                udpSocket = DatagramSocket(udpPort)
                udpSocket?.broadcast = true
                val buffer = ByteArray(1024)
                Log.d(TAG, "UDP Discovery Server started on port $udpPort")

                while (!Thread.currentThread().isInterrupted && udpSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val message = String(packet.data, 0, packet.length).trim()

                    if (message == "DISCOVER") {
                        val deviceName = android.provider.Settings.Global.getString(
                            contentResolver,
                            android.provider.Settings.Global.DEVICE_NAME
                        ) ?: android.provider.Settings.Global.getString(
                            contentResolver,
                            "device_name"
                        ) ?: Build.MODEL
                        val responseMessage = "LOCK_SERVICE_HERE:$deviceName"
                        val responseData = responseMessage.toByteArray()
                        val responsePacket = DatagramPacket(
                            responseData,
                            responseData.size,
                            packet.address,
                            packet.port
                        )
                        udpSocket?.send(responsePacket)
                        Log.d(TAG, "Sent discovery response to ${packet.address}:${packet.port}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in UDP discovery socket: ${e.message}", e)
            }
        }
    }

    private fun handleTcpClient(socket: Socket) {
        Executors.newSingleThreadExecutor().submit {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine()
                if (line != null && line.trim().lowercase() == "lock") {
                    Log.d(TAG, "Lock command received. Locking screen...")
                    performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
                }
                socket.getOutputStream().write("OK\n".toByteArray())
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error handling TCP client: ${e.message}", e)
            }
        }
    }

    private fun stopServers() {
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing TCP server: ${e.message}", e)
        }
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing UDP discovery: ${e.message}", e)
        }
        executorService?.shutdownNow()
    }
}
