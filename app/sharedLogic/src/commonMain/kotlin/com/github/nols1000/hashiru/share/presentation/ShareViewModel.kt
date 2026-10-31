package com.github.nols1000.hashiru.share.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.nols1000.hashiru.share.domain.LiveShareStatus
import com.github.nols1000.hashiru.share.engine.LiveShareEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class ShareUiPhase { Off, Live, Ended, Failed }

/** Live sharing on the workout screens, flattened from [LiveShareStatus] for Swift. */
data class ShareUi(
    val phase: ShareUiPhase = ShareUiPhase.Off,
    /** Set while Live and Ended; includes the fragment, which holds the keys. */
    val link: String? = null,
    val watchers: Int = 0,
    val offline: Boolean = false,
    /** The relay is full for now and the share not open yet; it keeps trying. */
    val waitingForRoom: Boolean = false,
    val includeHeartRate: Boolean = false,
    /** The persisted default for the heart-rate switch in the share dialog. */
    val heartRateByDefault: Boolean = false,
)

/** The state before anything is known; for Swift, which cannot use the constructor's default arguments. */
fun initialShareUi(): ShareUi = ShareUi()

/** Presents the [LiveShareEngine]: whether the workout is shared, and the link to it. The engine outlives this ViewModel. */
class ShareViewModel(private val share: LiveShareEngine) : ViewModel() {
    val uiState: StateFlow<ShareUi> =
        combine(share.status, share.shareHeartRateByDefault) { status, heartRateByDefault -> status.toUi(heartRateByDefault) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ShareUi())

    /** Shares the workout in progress, or the next one; [includeHeartRate] becomes the default for the next share. */
    fun startShare(includeHeartRate: Boolean) = share.share(includeHeartRate)
    fun stopShare() = share.stopSharing()
    fun dismissShareEnded() = share.dismissEnded()

    /** Swift-friendly observation of [uiState]; call `close()` on the result to stop. */
    fun watch(onChange: (ShareUi) -> Unit): AutoCloseable {
        val job = viewModelScope.launch { uiState.collect { onChange(it) } }
        return AutoCloseable { job.cancel() }
    }

    private fun LiveShareStatus.toUi(heartRateByDefault: Boolean): ShareUi = when (this) {
        LiveShareStatus.Off -> ShareUi(heartRateByDefault = heartRateByDefault)
        is LiveShareStatus.Live -> ShareUi(
            phase = ShareUiPhase.Live,
            link = link,
            watchers = watchers,
            offline = offline,
            waitingForRoom = waitingForRoom,
            includeHeartRate = includeHeartRate,
            heartRateByDefault = heartRateByDefault,
        )
        is LiveShareStatus.Ended -> ShareUi(phase = ShareUiPhase.Ended, link = link, heartRateByDefault = heartRateByDefault)
        LiveShareStatus.Failed -> ShareUi(phase = ShareUiPhase.Failed, heartRateByDefault = heartRateByDefault)
    }
}
