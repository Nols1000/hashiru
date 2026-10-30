package com.github.nols1000.hashiru.treadmill.adapter

import com.github.nols1000.hashiru.treadmill.domain.PairingCancelledException
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociation
import com.github.nols1000.hashiru.treadmill.port.DeviceAssociationPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pairs instantly with a simulated WalkingPad. [simulatePresence] reproduces the OS presence callbacks, and
 * [cancelNextPick] a user dismissing the picker.
 */
class MockAssociationAdapter(initial: DeviceAssociation? = null) : DeviceAssociationPort {
    override val isAvailable: Boolean = true

    private val _association = MutableStateFlow(initial)
    override val association: StateFlow<DeviceAssociation?> = _association.asStateFlow()

    private val _presenceChanges = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    override val presenceChanges: Flow<Boolean> = _presenceChanges

    var cancelNextPick = false

    override suspend fun associate(): DeviceAssociation {
        if (cancelNextPick) {
            cancelNextPick = false
            throw PairingCancelledException()
        }
        return DeviceAssociation(id = MOCK_ID, displayName = "WalkingPad (simulated)").also { _association.value = it }
    }

    override suspend fun disassociate() {
        _association.value = null
    }

    fun simulatePresence(nearby: Boolean) {
        _presenceChanges.tryEmit(nearby)
    }

    private companion object {
        const val MOCK_ID = "00:00:00:00:00:00"
    }
}
