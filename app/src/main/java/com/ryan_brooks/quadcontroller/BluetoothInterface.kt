package com.ryan_brooks.quadcontroller

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice

class BluetoothInterface {

    companion object {
        val HC06_MAC_ADDRESS = "00:14:03:06:02:47"
    }

    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var droneDevice: BluetoothDevice? = null

    init {
        getDroneDevice()
    }

    fun connect() {

    }

    fun disconnect() {

    }

    private fun getDroneDevice() {
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        for (device in pairedDevices) {
            if (device.address.toLowerCase() == HC06_MAC_ADDRESS) {
                droneDevice = device
                continue
            }
        }
    }

    internal inner class ConnectThread: Thread() {
        // TODO
    }

    internal inner class DataTransmitThread: Thread() {

    }
}