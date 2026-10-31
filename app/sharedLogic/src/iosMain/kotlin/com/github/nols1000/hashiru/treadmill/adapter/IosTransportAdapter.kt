package com.github.nols1000.hashiru.treadmill.adapter

import com.github.nols1000.hashiru.log.log
import com.github.nols1000.hashiru.toByteArray
import com.github.nols1000.hashiru.toNSData
import com.github.nols1000.hashiru.treadmill.domain.ConnectionState
import com.github.nols1000.hashiru.treadmill.domain.TreadmillError
import com.github.nols1000.hashiru.treadmill.domain.TreadmillException
import com.github.nols1000.hashiru.treadmill.port.TreadmillTransportPort
import com.github.nols1000.hashiru.treadmill.port.WalkingPadGatt
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBAdvertisementDataServiceUUIDsKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBCentralManagerRestoredStatePeripheralsKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerState
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateResetting
import platform.CoreBluetooth.CBManagerStateUnauthorized
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBManagerStateUnsupported
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBPeripheralStateConnected
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import platform.darwin.dispatch_get_main_queue
import kotlin.time.Duration.Companion.seconds

/**
 * CoreBluetooth transport with state restoration. All delegate callbacks arrive on the main queue.
 *
 * Create it during app launch: when iOS relaunches the app for Bluetooth activity, the central manager
 * with the same restoration identifier must exist before launch finishes to receive the connection.
 *
 * A known address gets a pending connection with no time limit, the iOS counterpart of Android's
 * `autoConnect`. It survives suspension, and it is re-issued after an unexpected drop, so the pad
 * reconnects (and wakes the app) whenever it is back in range.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosTransportAdapter : TreadmillTransportPort {
    private val _connectionState = MutableStateFlow(ConnectionState.Disconnected)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    override val incomingBytes: Flow<ByteArray> = _incomingBytes

    private val _restoredSessions = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val restoredSessions: Flow<Unit> = _restoredSessions

    private val delegate = Delegate()
    private val managerState = MutableStateFlow(CBManagerStateUnknown)
    private val central = CBCentralManager(
        delegate,
        dispatch_get_main_queue(),
        mapOf<Any?, Any>(CBCentralManagerOptionRestoreIdentifierKey to RESTORE_IDENTIFIER),
    )

    private var peripheral: CBPeripheral? = null
    /** A restored peripheral to resume once the manager is powered on; it ignores commands before that. */
    private var awaitingPowerOn: CBPeripheral? = null
    /** The pad to connect to again once Bluetooth is back on; switching it off invalidates the peripheral. */
    private var awaitingBluetooth: NSUUID? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var reconnectsAfterDrop = false
    private var pendingDiscovery: CompletableDeferred<CBPeripheral>? = null
    private var pendingSetup: CompletableDeferred<Unit>? = null
    private var pendingWrite: CompletableDeferred<Unit>? = null
    private var pendingWriteSpace: CompletableDeferred<Unit>? = null

    override suspend fun connect(address: String?) {
        val state = withTimeoutOrNull(STATE_TIMEOUT) {
            managerState.first { it != CBManagerStateUnknown && it != CBManagerStateResetting }
        }
        if (state != CBManagerStatePoweredOn) throw TreadmillException(bluetoothError(state), "Bluetooth is unavailable (state $state)")
        // State restoration is delivered before the first state update and may already have bound the pad.
        if (peripheral != null) return

        _connectionState.value = ConnectionState.Connecting
        val setup = CompletableDeferred<Unit>()
        pendingSetup = setup
        try {
            val known = address != null
            val target = if (address != null) {
                known(address)
            } else {
                withTimeoutOrNull(SCAN_TIMEOUT) { scan() } ?: throw TreadmillException(TreadmillError.PadNotFound())
            }
            log(TAG, "connecting to ${target.identifier.UUIDString} (pending=$known)")
            bind(target)
            reconnectsAfterDrop = known
            central.connectPeripheral(target, null)
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
        peripheral?.let { central.cancelPeripheralConnection(it) }
        clearConnection()
    }

    override suspend fun send(bytes: ByteArray) {
        val peripheral = peripheral ?: error("Not connected")
        val characteristic = writeCharacteristic ?: error("Not connected")
        if (characteristic.properties and CBCharacteristicPropertyWriteWithoutResponse != 0uL) {
            // CoreBluetooth silently drops writes without response once its queue is full.
            if (!peripheral.canSendWriteWithoutResponse) {
                val space = CompletableDeferred<Unit>()
                pendingWriteSpace = space
                try {
                    withTimeout(WRITE_TIMEOUT) { space.await() }
                } finally {
                    pendingWriteSpace = null
                }
            }
            peripheral.writeValue(bytes.toNSData(), characteristic, CBCharacteristicWriteWithoutResponse)
            return
        }
        val written = CompletableDeferred<Unit>()
        pendingWrite = written
        try {
            peripheral.writeValue(bytes.toNSData(), characteristic, CBCharacteristicWriteWithResponse)
            withTimeout(WRITE_TIMEOUT) { written.await() }
        } finally {
            pendingWrite = null
        }
    }

    private fun known(address: String): CBPeripheral {
        val uuid = NSUUID(uUIDString = address)
        return central.retrievePeripheralsWithIdentifiers(listOf(uuid)).filterIsInstance<CBPeripheral>().firstOrNull()
            ?: throw TreadmillException(TreadmillError.PairAgain, "The paired WalkingPad is unknown to this device")
    }

    private fun bluetoothError(state: CBManagerState?): TreadmillError = when (state) {
        CBManagerStateUnauthorized -> TreadmillError.BluetoothUnauthorized
        CBManagerStateUnsupported -> TreadmillError.BluetoothUnsupported
        else -> TreadmillError.BluetoothOff
    }

    private suspend fun scan(): CBPeripheral {
        val discovery = CompletableDeferred<CBPeripheral>()
        pendingDiscovery = discovery
        // Filter by service: iOS drops wildcard scans while the app is in the background.
        central.scanForPeripheralsWithServices(listOf(SERVICE_UUID), null)
        return try {
            discovery.await()
        } finally {
            central.stopScan()
            pendingDiscovery = null
        }
    }

    private fun bind(target: CBPeripheral) {
        peripheral = target
        target.delegate = delegate
    }

    /** Picks up a peripheral handed back by state restoration where the previous process left off. */
    private fun restore(restored: CBPeripheral) {
        log(TAG, "restored ${restored.identifier.UUIDString} (state ${restored.state})")
        bind(restored)
        reconnectsAfterDrop = true
        _connectionState.value = ConnectionState.Connecting
        _restoredSessions.tryEmit(Unit)
        // Restoration arrives before the first state update, and commands sent before power-on are dropped.
        if (central.state == CBManagerStatePoweredOn) resume(restored) else awaitingPowerOn = restored
    }

    private fun resume(restored: CBPeripheral) {
        if (restored.state == CBPeripheralStateConnected) {
            // Re-discovering is cheap when services are cached and re-checks the notify subscription.
            restored.discoverServices(listOf(SERVICE_UUID))
        } else {
            central.connectPeripheral(restored, null)
        }
    }

    /**
     * Bluetooth went off: iOS tears every connection down without reporting the disconnects, and the
     * peripheral may become invalid. A pad meant to reconnect is retrieved again by identifier on power-on.
     */
    private fun onBluetoothLost() {
        val lost = peripheral ?: return
        if (!reconnectsAfterDrop) {
            clearConnection()
            return
        }
        log(TAG, "Bluetooth lost, reconnecting to ${lost.identifier.UUIDString} once it is back")
        lost.delegate = null
        peripheral = null
        writeCharacteristic = null
        awaitingBluetooth = lost.identifier
        _connectionState.value = ConnectionState.Connecting
        // Failed last, so whoever is waiting sees the adapter already settled.
        pendingSetup?.completeExceptionally(IllegalStateException("Disconnected"))
        pendingWrite?.completeExceptionally(IllegalStateException("Disconnected"))
        pendingWriteSpace?.completeExceptionally(IllegalStateException("Disconnected"))
    }

    private fun reconnect(identifier: NSUUID) {
        val target = central.retrievePeripheralsWithIdentifiers(listOf(identifier)).filterIsInstance<CBPeripheral>().firstOrNull()
        if (target == null) {
            clearConnection()
            return
        }
        log(TAG, "Bluetooth is back, connecting to ${identifier.UUIDString}")
        bind(target)
        central.connectPeripheral(target, null)
    }

    private fun clearConnection() {
        pendingSetup?.completeExceptionally(IllegalStateException("Disconnected"))
        pendingWrite?.completeExceptionally(IllegalStateException("Disconnected"))
        pendingWriteSpace?.completeExceptionally(IllegalStateException("Disconnected"))
        peripheral?.delegate = null
        peripheral = null
        awaitingPowerOn = null
        awaitingBluetooth = null
        writeCharacteristic = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun matches(peripheral: CBPeripheral, advertisementData: Map<Any?, *>): Boolean {
        val name = peripheral.name ?: advertisementData[CBAdvertisementDataLocalNameKey] as? String
        val services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? List<*>
        return WalkingPadGatt.matches(name, services?.contains(SERVICE_UUID) == true)
    }

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val wasOn = managerState.value == CBManagerStatePoweredOn
            managerState.value = central.state
            if (central.state != CBManagerStatePoweredOn) {
                if (wasOn) onBluetoothLost()
                return
            }
            awaitingPowerOn?.let {
                awaitingPowerOn = null
                resume(it)
            }
            awaitingBluetooth?.let {
                awaitingBluetooth = null
                reconnect(it)
            }
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, willRestoreState: Map<Any?, *>) {
            val restored = (willRestoreState[CBCentralManagerRestoredStatePeripheralsKey] as? List<*>)
                ?.filterIsInstance<CBPeripheral>()
                ?.firstOrNull()
                ?: return
            restore(restored)
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) {
            val discovery = pendingDiscovery ?: return
            if (!discovery.isCompleted && matches(didDiscoverPeripheral, advertisementData)) {
                discovery.complete(didDiscoverPeripheral)
            }
        }

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) {
            didConnectPeripheral.discoverServices(listOf(SERVICE_UUID))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) {
            pendingSetup?.completeExceptionally(connectionFailed(error))
        }

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            log(TAG, "disconnected ${error?.localizedDescription ?: ""}")
            if (didDisconnectPeripheral != peripheral) return
            if (reconnectsAfterDrop && pendingSetup == null) {
                // Dropped after a successful setup: leave a pending connection that completes once the pad is back.
                writeCharacteristic = null
                pendingWrite?.completeExceptionally(IllegalStateException("Disconnected"))
                _connectionState.value = ConnectionState.Connecting
                central.connectPeripheral(didDisconnectPeripheral, null)
                return
            }
            clearConnection()
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
            val service = peripheral.services?.filterIsInstance<CBService>()?.firstOrNull { it.UUID == SERVICE_UUID }
            if (didDiscoverServices != null || service == null) {
                pendingSetup?.completeExceptionally(TreadmillException(TreadmillError.NotAWalkingPad))
                return
            }
            peripheral.discoverCharacteristics(listOf(NOTIFY_UUID, WRITE_UUID), service)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) {
            val characteristics = didDiscoverCharacteristicsForService.characteristics?.filterIsInstance<CBCharacteristic>().orEmpty()
            val notify = characteristics.firstOrNull { it.UUID == NOTIFY_UUID }
            val write = characteristics.firstOrNull { it.UUID == WRITE_UUID }
            if (error != null || notify == null || write == null) {
                pendingSetup?.completeExceptionally(TreadmillException(TreadmillError.NotAWalkingPad))
                return
            }
            writeCharacteristic = write
            if (notify.isNotifying) {
                onSetupComplete()
            } else {
                peripheral.setNotifyValue(true, notify)
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error == null) {
                onSetupComplete()
            } else {
                pendingSetup?.completeExceptionally(connectionFailed(error))
            }
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error != null || didUpdateValueForCharacteristic.UUID != NOTIFY_UUID) return
            val bytes = didUpdateValueForCharacteristic.value?.toByteArray() ?: return
            _incomingBytes.tryEmit(bytes)
        }

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) {
            if (error == null) {
                pendingWrite?.complete(Unit)
            } else {
                pendingWrite?.completeExceptionally(IllegalStateException(error.localizedDescription))
            }
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) {
            pendingWriteSpace?.complete(Unit)
        }

        private fun connectionFailed(error: NSError?) =
            TreadmillException(TreadmillError.ConnectionFailed(error?.code?.toInt()), error?.localizedDescription)

        private fun onSetupComplete() {
            pendingSetup?.complete(Unit)
            _connectionState.value = ConnectionState.Connected
        }
    }

    private companion object {
        const val TAG = "WalkingPadBle"
        const val RESTORE_IDENTIFIER = "com.github.nols1000.hashiru.walkingpad"
        val SERVICE_UUID: CBUUID = CBUUID.UUIDWithString(WalkingPadGatt.SERVICE_UUID)
        val NOTIFY_UUID: CBUUID = CBUUID.UUIDWithString(WalkingPadGatt.NOTIFY_CHARACTERISTIC_UUID)
        val WRITE_UUID: CBUUID = CBUUID.UUIDWithString(WalkingPadGatt.WRITE_CHARACTERISTIC_UUID)
        val STATE_TIMEOUT = 5.seconds
        val SCAN_TIMEOUT = 15.seconds
        val CONNECT_TIMEOUT = 15.seconds
        val WRITE_TIMEOUT = 2.seconds
    }
}
