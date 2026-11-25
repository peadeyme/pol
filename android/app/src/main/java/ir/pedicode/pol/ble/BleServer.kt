package ir.pedicode.pol.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque

/**
 * BLE GATT server. The Android phone is the peripheral; the iPhone connects to it.
 * All callbacks are moved onto the main thread so state stays simple.
 */
@SuppressLint("MissingPermission")
class BleServer(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected(device: BluetoothDevice)
        fun onSubscribed(device: BluetoothDevice)
        fun onMessage(device: BluetoothDevice, json: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private var server: BluetoothGattServer? = null
    private lateinit var tx: BluetoothGattCharacteristic

    private var central: BluetoothDevice? = null
    private var subscribed = false
    private var mtu = 23
    private val reassembler = Reassembler()
    private val outbox = ArrayDeque<ByteArray>()
    private var sending = false

    val isConnected: Boolean get() = central != null && subscribed

    /** False on phones whose Bluetooth chip cannot advertise (cannot act as a BLE peripheral). */
    val supportsPeripheral: Boolean
        get() = manager.adapter?.let { it.isMultipleAdvertisementSupported || it.bluetoothLeAdvertiser != null } ?: false

    fun start() {
        val adapter = manager.adapter ?: return
        if (!adapter.isEnabled) return
        if (server != null) return

        server = manager.openGattServer(context, callback)
        val service = BluetoothGattService(Protocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        tx = BluetoothGattCharacteristic(
            Protocol.TX,
            BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                Protocol.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        val rx = BluetoothGattCharacteristic(
            Protocol.RX,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        service.addCharacteristic(tx)
        service.addCharacteristic(rx)
        server?.addService(service)
        startAdvertising()
    }

    fun stop() {
        stopAdvertising()
        central?.let { server?.cancelConnection(it) }
        server?.close()
        server = null
        central = null
        subscribed = false
        outbox.clear()
        sending = false
    }

    /** Queue one JSON message for the connected iPhone. */
    fun send(json: String) {
        main.post {
            if (!isConnected) return@post
            outbox.addAll(Protocol.split(json, mtu))
            pump()
        }
    }

    /** Some Bluetooth stacks skip onNotificationSent now and then; without this the queue would stall forever. */
    private var sendSeq = 0
    private val watchdog = Runnable {
        if (sending) {
            sending = false
            pump()
        }
    }

    private fun pump() {
        if (sending) return
        val device = central ?: return
        val packet = outbox.pollFirst() ?: return
        val s = server ?: return
        sending = true
        sendSeq++
        main.removeCallbacks(watchdog)
        main.postDelayed(watchdog, 250)
        BridgeStats.tx++
        val ok = try {
            if (Build.VERSION.SDK_INT >= 33) {
                s.notifyCharacteristicChanged(device, tx, false, packet) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.value = packet
                @Suppress("DEPRECATION")
                s.notifyCharacteristicChanged(device, tx, false)
            }
        } catch (e: Exception) {
            // Never let one bad packet take the whole app down.
            Log.w(TAG, "notify failed", e)
            outbox.clear()
            sending = false
            return
        }
        if (!ok) {
            // Stack is busy: put it back and retry shortly.
            outbox.addFirst(packet)
            sending = false
            main.postDelayed({ pump() }, 20)
        }
    }

    private val prepared = java.io.ByteArrayOutputStream()

    private fun deliver(device: BluetoothDevice, packet: ByteArray) {
        BridgeStats.rx++
        main.post {
            val message = reassembler.push(packet) ?: return@post
            listener.onMessage(device, message)
        }
    }

    // ---- advertising ----

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Protocol.SERVICE))
            .setIncludeDeviceName(false)
            .build()
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        manager.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
    }

    // ---- GATT callbacks ----

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    central = device
                    subscribed = false
                    mtu = 23
                    outbox.clear()
                    sending = false
                    listener.onConnected(device)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED && device == central) {
                    central = null
                    subscribed = false
                    outbox.clear()
                    sending = false
                    listener.onDisconnected(device)
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            main.post { mtu = newMtu }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            main.post {
                main.removeCallbacks(watchdog)
                sending = false
                pump()
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (preparedWrite) {
                // Long write: pieces arrive with offsets and are committed in onExecuteWrite.
                // The response must echo offset and value, or iOS aborts the write.
                synchronized(prepared) {
                    if (offset == 0) prepared.reset()
                    prepared.write(value)
                }
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                return
            }
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (characteristic.uuid != Protocol.RX) return
            deliver(device, value)
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val value = synchronized(prepared) {
                val v = prepared.toByteArray()
                prepared.reset()
                v
            }
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && value.isNotEmpty()) deliver(device, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            if (responseNeeded) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
            if (descriptor.uuid == Protocol.CCCD) {
                val enabled = value.isNotEmpty() && value[0].toInt() != 0
                main.post {
                    subscribed = enabled
                    if (enabled) listener.onSubscribed(device)
                }
            }
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor,
        ) {
            val v = if (subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }
    }

    companion object {
        private const val TAG = "PolBle"
    }
}

/** Packet counters shown on the Android screen for troubleshooting. */
object BridgeStats {
    @Volatile var tx = 0
    @Volatile var rx = 0
}
