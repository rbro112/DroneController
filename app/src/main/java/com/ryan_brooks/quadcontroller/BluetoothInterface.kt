package com.ryan_brooks.quadcontroller

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.ContentValues.TAG
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.*


class BluetoothInterface(private val handler: Handler) {

    companion object {
        val MESSAGE_CONNECTED = 0
        val MESSAGE_READ = 1
        val MESSAGE_WRITE = 2
        val MESSAGE_TOAST = 3

        val STATE_NONE = 0
        val STATE_CONNECTING = 1
        val STATE_CONNECTED = 2

        val HC06_NAME = "quadReceiver"
        val HC06_MAC_ADDRESS = "00:14:03:06:02:47"

        // INSECURE "8ce255c0-200a-11e0-ac64-0800200c9a66"
        // SECURE "fa87c0d0-afac-11de-8a39-0800200c9a66"
        // SPP "0001101-0000-1000-8000-00805F9B34FB"
        val HC06_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var state = STATE_NONE
    private val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
    private var droneDevice: BluetoothDevice? = null

    private var connectThread: ConnectThread? = null
    private var connectedThread: ConnectedThread? = null


    init {
        getDroneDevice()
    }

    private fun getDroneDevice() {
        val pairedDevices: Set<BluetoothDevice> = bluetoothAdapter.bondedDevices
        for (device in pairedDevices) {
            Log.w("Device:", device.name)
            Log.w("Type", device.type.toString())
            if (device.name == HC06_NAME) {
                droneDevice = device
                Log.w("Found device", device.name)
                continue
            }
        }
    }

    @Synchronized
    fun connect() {
        if (droneDevice == null) {
            // TODO: Report to activity that it's null
            return
        }

        // Cancel any thread attempting to make a connection
        if (state == STATE_CONNECTING) {
            if (connectThread != null) {
                connectThread!!.cancel()
                connectThread = null
            }
        }

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }

        // Start the thread to connect with the given device
        connectThread = ConnectThread()
        connectThread!!.start()

        state = STATE_CONNECTING
    }

    @Synchronized
    fun disconnect() {
        if (connectThread != null) {
            connectThread!!.cancel()
            connectThread = null
        }

        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }

        state = STATE_NONE
    }

    fun sendMessage(message: String) {
        if (state != STATE_CONNECTED) {
            Log.w(TAG, "bluetooth is not connected")
            return
        }

        if (message.isNotEmpty()) {
            // val EOT = 3.toChar()
            // Get the message bytes and tell the BluetoothChatService to write
            val send = message.toByteArray()
            write(send)
        }
    }

    fun sendFlightInfo(throttle: Byte, yaw: Byte, pitch: Byte, roll: Byte) {
        if (state != STATE_CONNECTED) {
            Log.w(TAG, "bluetooth is not connected")
            return
        }

        val bytes = ByteArray(4)

        bytes[0] = throttle
        bytes[1] = yaw
        bytes[2] = pitch
        bytes[3] = roll
        write(bytes)
    }

    fun isConnected(): Boolean {
        return state == STATE_CONNECTED
    }

    private fun sendMessageToHandler(message: Message, bundle: Bundle) {
        message.data = bundle
        handler.sendMessage(message)
    }

    private fun connectionEstablished() {
        state = STATE_CONNECTED

        val bundle = Bundle()
        bundle.putString("connected", droneDevice!!.name)
        sendMessageToHandler(handler.obtainMessage(MESSAGE_CONNECTED), bundle)
    }

    private fun connectionFailed() {
        val bundle = Bundle()
        bundle.putString("toast", "Unable to connect device")
        sendMessageToHandler(handler.obtainMessage(MESSAGE_TOAST), bundle)
    }

    private fun connectionLost() {
        val bundle = Bundle()
        bundle.putString("toast", "Connection lost")
        sendMessageToHandler(handler.obtainMessage(MESSAGE_TOAST), bundle)
    }

    private fun write(out: ByteArray) {
        // Create temporary object
        var tempThread: ConnectedThread? = null

        // Synchronize a copy of the ConnectedThread
        synchronized(this) {
            if (state != STATE_CONNECTED) {
                return
            }
            tempThread = connectedThread
        }

        // Perform the write unsynchronized
        tempThread?.write(out)
    }

    internal inner class ConnectThread : Thread() {

        private var clientSocket: BluetoothSocket? = null

        init {
            var socket: BluetoothSocket? = null

            try {
                socket = droneDevice?.createRfcommSocketToServiceRecord(HC06_UUID)
            } catch (e: IOException) {
                Log.e(ContentValues.TAG, "Client Socket's create() method failed", e)
            }

            clientSocket = socket
        }

        override fun run() {
            bluetoothAdapter.cancelDiscovery()

            try {
                clientSocket!!.connect()
                Log.d(ContentValues.TAG, "Client socket connected")
            } catch (connectException: IOException) {
                try {
                    Log.e(ContentValues.TAG, "Client socket closed in run()", connectException)

                    clientSocket = droneDevice!!::class.java.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(droneDevice, 1) as BluetoothSocket?
                    clientSocket?.connect()
                } catch (e: Exception) {
                    connectionFailed()
                    Log.e(ContentValues.TAG, "Could not close the client socket", e)
                }
            }

            connected(clientSocket!!)
        }

        internal fun cancel() {
            try {
                clientSocket!!.close()
                Log.d(ContentValues.TAG, "Client socket closed")
            } catch (e: IOException) {
                Log.e(ContentValues.TAG, "Could not close the client socket", e)
            }

        }
    }

    @Synchronized
    private fun connected(socket: BluetoothSocket) {

        // Cancel any thread currently running a connection
        if (connectedThread != null) {
            connectedThread!!.cancel()
            connectedThread = null
        }

        // Start the thread to manage the connection and perform transmissions
        connectedThread = ConnectedThread(socket)
        connectedThread!!.start()

        connectionEstablished()
    }

    internal inner class ConnectedThread(private val socket: BluetoothSocket) : Thread() {
        private var inputStream: InputStream? = null
        private var outputStream: OutputStream? = null

        init {
            var tempInputStream: InputStream? = null
            var tempOutputStream: OutputStream? = null

            try {
                tempInputStream = socket.inputStream
                tempOutputStream = socket.outputStream
            } catch (e: IOException) {
                Log.e(TAG, "temp sockets not created", e)
            }

            inputStream = tempInputStream
            outputStream = tempOutputStream
        }

        override fun run() {
            val buffer = ByteArray(1024)
            var bytes: Int

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = inputStream?.read(buffer)!!
                    Log.d(TAG, "message bytes " + bytes)
                    Log.d(TAG, "message string bytes " + bytes.toString())
                    Log.d(TAG, "message buffer " + String(buffer))
                    // Send the obtained bytes to the UI Activity
                    handler.obtainMessage(MESSAGE_READ, bytes, -1, buffer).sendToTarget()
                } catch (e: IOException) {
                    Log.e(TAG, "disconnected", e)
                    connectionLost()
                    break
                }

            }
        }

        fun write(buffer: ByteArray) {
            try {
                outputStream?.write(buffer)

                // Share the sent message back to the UI Activity
                handler.obtainMessage(MESSAGE_WRITE, -1, -1, buffer).sendToTarget()
            } catch (e: IOException) {
                Log.e(TAG, "Exception during write", e)
            }

        }

        fun cancel() {
            try {
                socket.close()
            } catch (e: IOException) {
                Log.e(TAG, "close() of connect socket failed", e)
            }
        }
    }
}