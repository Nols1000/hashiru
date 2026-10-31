package com.github.nols1000.hashiru.share.port

import kotlinx.coroutines.flow.Flow

/** What the share engine asks of the relay. [writeToken] authorizes it for [shareId]; the relay keeps only its hash. */
sealed interface RelayRequest {
    val shareId: String
    val writeToken: ByteArray

    /**
     * Registers the share, or registers it again after the relay lost it; harmless to repeat. [presenceKey] lets
     * the relay check that the pages it counts hold the link, without being able to read what they get.
     */
    class Open(override val shareId: String, override val writeToken: ByteArray, val presenceKey: ByteArray) : RelayRequest

    /** A signed SFrame; a [snapshot] replaces what the relay holds for viewers who open the link later. */
    class Publish(
        override val shareId: String,
        override val writeToken: ByteArray,
        val seq: Long,
        val frame: ByteArray,
        val snapshot: Boolean,
    ) : RelayRequest

    /**
     * No one is watching: tell the engine once someone opens the link, however the platform learns of it. The
     * adapter keeps watching through publishes and outages, and stops once it has reported [RelayEvent.Watchers]
     * above 0, on [Close], or when the relay no longer knows the share ([RelayEvent.Gone]); the engine asks again
     * the next time the count falls to 0. Harmless to repeat while one stands.
     */
    class AwaitViewers(override val shareId: String, override val writeToken: ByteArray) : RelayRequest

    /** The share ended; the relay keeps the last snapshot for a while, then forgets the share. */
    class Close(override val shareId: String, override val writeToken: ByteArray) : RelayRequest
}

/** What the relay reports back. */
sealed interface RelayEvent {
    data class Opened(val shareId: String) : RelayEvent

    /** The relay holds [seq]; [watchers] pages are open on the share. */
    data class Acked(val shareId: String, val seq: Long, val watchers: Int) : RelayEvent

    /** Someone opened the link while the engine was not publishing, in answer to [RelayRequest.AwaitViewers]. */
    data class Watchers(val shareId: String, val count: Int) : RelayEvent

    /**
     * Requests are failing for want of a network, or while the relay is briefly unavailable; [Reachable] follows
     * when it is back.
     */
    data object Unreachable : RelayEvent
    data object Reachable : RelayEvent

    /**
     * The relay is up but has no room to open the share for now; the adapter keeps trying, and [Opened] follows
     * once there is.
     */
    data class Busy(val shareId: String) : RelayEvent

    /** The relay does not know the share, as after it restarted; open it again. */
    data class Gone(val shareId: String) : RelayEvent

    /** The relay will not take the share, such as one past its size or time limit. */
    data class Refused(val shareId: String) : RelayEvent
}

/**
 * Driven port: the relay server that passes sealed frames on to the share's viewers. The engine sends requests
 * through [handle]; how they travel, are retried once the network returns, and how the platform learns that
 * someone opened the link (a push where it has one, else asking now and then) stays in the adapter.
 */
interface ShareRelayPort {
    /** Answers to [handle]. */
    val events: Flow<RelayEvent>

    /** Takes note of [request]; never blocks, answers come on [events]. */
    fun handle(request: RelayRequest)
}

/** Driven port: the phone's charge, which viewers see so they know when the phone may soon go quiet. */
fun interface BatteryPort {
    /** 0–100, or null where the platform does not tell. */
    fun percent(): Int?
}
