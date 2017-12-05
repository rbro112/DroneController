package com.ryan_brooks.quadcontroller

import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import io.github.controlwear.virtual.joystick.android.JoystickView
import org.jetbrains.anko.find


class MainActivity : AppCompatActivity() {

    companion object {
        val BT_AUTO_TRANSMIT_DELAY = 1000L

        val MAX_THROTTLE_STRENGTH = 60
        val ZERO_THROTTLE_STRENGTH = 100
    }

    private val bluetoothHandler = BluetoothHandler()
    private val bluetoothInterface = BluetoothInterface(bluetoothHandler)

    private lateinit var edittext: EditText
    private lateinit var button: Button
    private lateinit var connectButton: Button
    private lateinit var throttleStick: JoystickView
    private lateinit var aileronStick: JoystickView

    private val dataHandler = Handler()

    private var throttle: Byte = 0
    private var yaw: Byte = 127
    private var pitch: Byte = 127
    private var roll: Byte = 127

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        edittext = find(R.id.message_edittext)
        button = find(R.id.send_button)
        connectButton = find(R.id.connect_button)
        throttleStick = find(R.id.throttle_stick)
        aileronStick = find(R.id.aileron_stick)


        throttleStick.setOnMoveListener(throttleMoveListener)
        aileronStick.setOnMoveListener(aileronMoveListener)

        button.setOnClickListener({
            bluetoothInterface.sendMessage(edittext.text.toString().trim())
        })

        connectButton.setOnClickListener({
            bluetoothInterface.connect()
        })

        dataHandler.postDelayed(btTransmitRunnable, BT_AUTO_TRANSMIT_DELAY)
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothInterface.disconnect()
        dataHandler.removeCallbacks(btTransmitRunnable)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun calculateThrottleStrength(angle: Int, strength: Int) {
        var correctedStrength = strength
        // Max top throttle = 75
        if (angle == 90 && strength > MAX_THROTTLE_STRENGTH) {
            correctedStrength = MAX_THROTTLE_STRENGTH
        }

        // Max bottom throttle = 0
        if (angle == 270 && strength > ZERO_THROTTLE_STRENGTH) {
            correctedStrength = ZERO_THROTTLE_STRENGTH
        }

        // TODO: Adjust for angles when Yaw added
        val strengthDifference = 100 - correctedStrength
        when (angle) {
            0 -> throttle = 127
            270 -> {
                // % of 127
                throttle = ((strengthDifference.toFloat() / 100) * 127).toByte()
            }
            90 -> {
                // % of 255
                throttle = ((strengthDifference.toFloat() / 100) * -127).toByte()
            }
        }
    }

    private fun calculatePitchStrength(angle: Int, strength: Int) {
        var x = ((((127 * Math.sin(Math.toRadians(angle.toDouble())))) * (strength.toDouble() / 100)) * -1).toByte()

        if (strength == 0) {
            pitch = 127
            return
        }

        if (x == 0.toByte()) {
            x = 127
        }

        pitch = if (angle in 181..359) {
            (Math.ceil(127 - x.toDouble())).toByte()
        } else {
            x
        }
    }

    private fun calculateRollStrength(angle: Int, strength: Int) {
        var x = ((((127 * Math.cos(Math.toRadians(angle.toDouble())))) * (strength.toDouble() / 100)) * -1).toByte()

        if (strength == 0) {
            roll = 127
            return
        }

        if (x == 0.toByte()) {
            x = 127
        }

        roll = if (angle in 91..269) {
            (Math.ceil(127 - x.toDouble())).toByte()
        } else {
            x
        }
    }

    private val throttleMoveListener: JoystickView.OnMoveListener = JoystickView.OnMoveListener { angle, strength ->
        calculateThrottleStrength(angle, strength)
        bluetoothInterface.sendFlightInfo(throttle, yaw, pitch, roll)
        // TODO: Yaw
    }

    private val aileronMoveListener: JoystickView.OnMoveListener = JoystickView.OnMoveListener { angle, strength ->
        calculateRollStrength(angle, strength)
        calculatePitchStrength(angle, strength)
        bluetoothInterface.sendFlightInfo(throttle, yaw, pitch, roll)
    }


    // Send value every X seconds to ensure connection isn't killed
    private val btTransmitRunnable = object : Runnable {
        override fun run() {
            if (bluetoothInterface.isConnected()) {
                bluetoothInterface.sendFlightInfo(throttle, yaw, pitch, roll)
            }
            dataHandler.postDelayed(this, BT_AUTO_TRANSMIT_DELAY)
        }
    }

    internal inner class BluetoothHandler : Handler() {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
//                BluetoothInterface.Companion.MESSAGE_TOAST -> showToast(msg.data.getString("connected"))
                BluetoothInterface.Companion.MESSAGE_TOAST -> showToast(msg.data.getString("toast"))
            }
        }
    }
}
