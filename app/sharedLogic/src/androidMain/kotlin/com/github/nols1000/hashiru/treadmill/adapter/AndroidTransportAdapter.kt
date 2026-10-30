package com.github.nols1000.hashiru.treadmill.adapter

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.github.nols1000.hashiru.treadmill.codec.toHex
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import com.github.nols1000.hashiru.treadmill.port.WalkingPadGatt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration.Companion.seconds

/**
 * BLE transport. Requires BLUETOOTH_CONNECT (API 31+) to be granted before [connect] is called, and
 * location (API ≤ 25) when scanning for an unpaired device.
 *
 * A known address gets a background connection (`autoConnect`): the stack waits for the device without
 * a time limit, and after the link drops the GATT is kept so the stack reconnects once the pad is back.
 */
@SuppressLint("MissingPermission")
class AndroidTransportAdapter(context: Context) : TreadmillTransportPort {
    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)

    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBytes: Flow<ByteArray> = _incomingBytes

    // A GATT connection never outlives its process on Android; a restarted service connects afresh.
    override val restoredSessions: Flow<Unit> = emptyFlow()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var writeCharacteristic: BluetoothGattCharacteristic? = null
    @Volatile private var pendingSetup: CompletableDeferred<Unit>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null
    @Volatile private var reconnectsAfterDrop = false

    override suspend fun connect(address: String?) {
        val adapter = bluetoothManager?.adapter ?: throw TreadmillException(TreadmillError.BluetoothUnsupported)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            throw TreadmillException(TreadmillError.BluetoothUnauthorized)
        }
        if (!adapter.isEnabled) throw TreadmillException(TreadmillError.BluetoothOff)

        _connectionState.value = ConnectionState.Connecting
        val setup = CompletableDeferred<Unit>()
        pendingSetup = setup
        try {
            val known = address != null
            val device = if (address != null) {
                adapter.getRemoteDevice(address)
            } else {
                // The scanner is null while Bluetooth is switching off.
                val scanner = adapter.bluetoothLeScanner ?: throw TreadmillException(TreadmillError.BluetoothOff)
                withTimeoutOrNull(SCAN_TIMEOUT) { scan(scanner) } ?: throw TreadmillException(TreadmillError.PadNotFound())
            }
            Log.d(TAG, "Connecting to ${device.address} (autoConnect=$known)")
            reconnectsAfterDrop = known
            gatt = device.connectGatt(appContext, known, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: throw TreadmillException(TreadmillError.ConnectionFailed())
            if (known) {
                setup.await()
            } else {
                withTimeoutOrNull(CONNECT_TIMEOUT) { setup.await() } ?: throw TreadmillException(TreadmillError.ConnectionFailed())
            }
        } catch (e: Throwable) {
            disconnect()
            throw e
        } finally {
            pendingSetup = null
        }
    }

    override fun disconnect() {
        reconnectsAfterDrop = false
        // Cancelling (rather than failing) a pending connect ends it quietly, as the user asked for this.
        pendingSetup?.cancel()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        writeCharacteristic = null
        pendingWrite?.cancel()
        _connectionState.value = ConnectionState.Disconnected
    }

    override suspend fun send(bytes: ByteArray) {
        val gatt = gatt ?: error("Not connected")
        val characteristic = writeCharacteristic ?: error("Not connected")
        val writeType =
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            }

        val written = CompletableDeferred<Unit>()
        pendingWrite = written
        Log.d(TAG, "TX ${bytes.toHex()}")
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(characteristic, bytes, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        try {
            check(accepted) { "Write was rejected" }
            withTimeout(WRITE_TIMEOUT) { written.await() }
        } finally {
            pendingWrite = null
        }
    }

    private suspend fun scan(scanner: BluetoothLeScanner): BluetoothDevice =
        suspendCancellableCoroutine { continuation ->
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    if (!continuation.isActive || !matches(result)) return
                    scanner.stopScan(this)
                    continuation.resume(result.device)
                }

                override fun onScanFailed(errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(TreadmillException(TreadmillError.PadNotFound(errorCode), "BLE scan failed ($errorCode)"))
                    }
                }
            }
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            scanner.startScan(null, settings, callback)
            continuation.invokeOnCancellation { scanner.stopScan(callback) }
        }

    private fun matches(result: ScanResult): Boolean {
        val advertisesService = result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE_UUID)) == true
        return WalkingPadGatt.matches(result.device.name ?: result.scanRecord?.deviceName, advertisesService)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                gatt.discoverServices()
                return
            }
            Log.d(TAG, "Disconnected (status $status)")
            pendingWrite?.completeExceptionally(IllegalStateException("Disconnected"))
            if (this@AndroidTransportAdapter.gatt !== gatt) return
            writeCharacteristic = null
            if (reconnectsAfterDrop && pendingSetup == null) {
                // Dropped after a successful setup: re-arm the background connection instead of giving up.
                _connectionState.value = ConnectionState.Connecting
                gatt.connect()
                return
            }
            pendingSetup?.completeExceptionally(TreadmillException(TreadmillError.ConnectionFailed(status), "Connection failed (status $status)"))
            gatt.close()
            this@AndroidTransportAdapter.gatt = null
            _connectionState.value = ConnectionState.Disconnected
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            val notify = service?.getCharacteristic(NOTIFY_UUID)
            val write = service?.getCharacteristic(WRITE_UUID)
            val cccd = notify?.getDescriptor(CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || write == null || cccd == null) {
                pendingSetup?.completeExceptionally(TreadmillException(TreadmillError.NotAWalkingPad))
                return
            }
            writeCharacteristic = write
            gatt.setCharacteristicNotification(notify, true)
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingSetup?.complete(Unit)
                _connectionState.value = ConnectionState.Connected
            } else {
                pendingSetup?.completeExceptionally(TreadmillException(TreadmillError.ConnectionFailed(status), "Enabling notifications failed ($status)"))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite?.complete(Unit)
            } else {
                pendingWrite?.completeExceptionally(IllegalStateException("Write failed ($status)"))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotification(characteristic, value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                onNotification(characteristic, characteristic.value)
            }
        }
    }

    private fun onNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != NOTIFY_UUID) return
        Log.d(TAG, "RX ${value.toHex()}")
        _incomingBytes.tryEmit(value.copyOf())
    }

    private companion object {
        const val TAG = "WalkingPadBle"
        val SERVICE_UUID: UUID = UUID.fromString(WalkingPadGatt.SERVICE_UUID)
        val NOTIFY_UUID: UUID = UUID.fromString(WalkingPadGatt.NOTIFY_CHARACTERISTIC_UUID)
        val WRITE_UUID: UUID = UUID.fromString(WalkingPadGatt.WRITE_CHARACTERISTIC_UUID)
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val SCAN_TIMEOUT = 15.seconds
        val CONNECT_TIMEOUT = 15.seconds
        val WRITE_TIMEOUT = 2.seconds
    }
}
