package com.example.screenlock

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private val discoveredDevices = mutableListOf<String>()
    private val deviceIps = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private var selectedDeviceIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val modeRadioGroup = findViewById<RadioGroup>(R.id.modeRadioGroup)
        val lockModeLayout = findViewById<LinearLayout>(R.id.lockModeLayout)
        val triggerModeLayout = findViewById<LinearLayout>(R.id.triggerModeLayout)

        val ipTextView = findViewById<TextView>(R.id.ipTextView)
        val settingsButton = findViewById<Button>(R.id.settingsButton)

        val scanButton = findViewById<Button>(R.id.scanButton)
        val devicesListView = findViewById<ListView>(R.id.devicesListView)
        val lockSelectedButton = findViewById<Button>(R.id.lockSelectedButton)

        // Setup ListView
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_single_choice, discoveredDevices)
        devicesListView.adapter = adapter
        devicesListView.choiceMode = ListView.CHOICE_MODE_SINGLE
        devicesListView.setOnItemClickListener { _, _, position, _ ->
            selectedDeviceIp = deviceIps[position]
            lockSelectedButton.isEnabled = true
        }

        // Mode Toggling
        modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioLockMode) {
                lockModeLayout.visibility = View.VISIBLE
                triggerModeLayout.visibility = View.GONE
            } else {
                lockModeLayout.visibility = View.GONE
                triggerModeLayout.visibility = View.VISIBLE
            }
        }

        // Lock Mode Setup
        val ipAddress = getLocalIpAddress(this)
        ipTextView.text = if (ipAddress != null) {
            String.format(
                Locale.getDefault(),
                "IP Address: %s\nPort: 9999 (TCP), 9998 (UDP)\n\nWaiting for triggers...",
                ipAddress
            )
        } else {
            "Not connected to Wi-Fi"
        }

        settingsButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Trigger Mode Setup
        scanButton.setOnClickListener {
            discoveredDevices.clear()
            deviceIps.clear()
            adapter.notifyDataSetChanged()
            lockSelectedButton.isEnabled = false
            selectedDeviceIp = null
            scanDevices()
        }

        lockSelectedButton.setOnClickListener {
            val ip = selectedDeviceIp
            if (ip != null) {
                sendLockCommand(ip)
            }
        }
    }

    private fun scanDevices() {
        thread {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                socket.soTimeout = 2000 // 2 second scan timeout

                val buffer = "DISCOVER".toByteArray()
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(buffer, buffer.size, broadcastAddress, 9998)
                socket.send(packet)

                val responseBuffer = ByteArray(1024)
                val startTime = System.currentTimeMillis()

                runOnUiThread {
                    Toast.makeText(this, "Scanning local network...", Toast.LENGTH_SHORT).show()
                }

                while (System.currentTimeMillis() - startTime < 2000) {
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    try {
                        socket.receive(responsePacket)
                        val response = String(responsePacket.data, 0, responsePacket.length).trim()
                        if (response.startsWith("LOCK_SERVICE_HERE:")) {
                            val deviceName = response.substringAfter("LOCK_SERVICE_HERE:")
                            val deviceIp = responsePacket.address.hostAddress ?: ""
                            val displayText = "$deviceName ($deviceIp)"

                            if (deviceIp.isNotEmpty() && !deviceIps.contains(deviceIp)) {
                                runOnUiThread {
                                    discoveredDevices.add(displayText)
                                    deviceIps.add(deviceIp)
                                    adapter.notifyDataSetChanged()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Timeout or socket closed
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                socket?.close()
            }
        }
    }

    private fun sendLockCommand(targetIp: String) {
        thread {
            var socket: Socket? = null
            try {
                socket = Socket(targetIp, 9999)
                val out: OutputStream = socket.getOutputStream()
                out.write("lock\n".toByteArray())
                out.flush()
                runOnUiThread {
                    Toast.makeText(this, "Lock command sent successfully!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Failed to connect: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                socket?.close()
            }
        }
    }

    private fun getLocalIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        val ipAddress = connectionInfo.ipAddress
        if (ipAddress == 0) return null
        return String.format(
            Locale.getDefault(),
            "%d.%d.%d.%d",
            ipAddress and 0xff,
            ipAddress shr 8 and 0xff,
            ipAddress shr 16 and 0xff,
            ipAddress shr 24 and 0xff
        )
    }
}
