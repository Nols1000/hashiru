package com.github.nols1000.hashiru.share

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A frame as viewers get it: still sealed and signed, so the relay can neither read nor alter it. */
data class StoredFrame(val seq: Long, val snapshot: Boolean, val bytes: ByteArray)

/** What a viewer's stream receives after the frames held so far. */
sealed interface ShareSignal {
    data class Frame(val frame: StoredFrame) : ShareSignal
    data object Ended : ShareSignal
}

/**
 * A viewer's view of a share: what was held when it arrived, then what follows. [signals] closes when the viewer
 * fell too far behind, or when the relay forgot the share; the viewer reconnects and starts over from [held].
 */
class Watch internal constructor(val held: List<StoredFrame>, val ended: Boolean, val signals: ReceiveChannel<ShareSignal>)

/**
 * Live shares, in memory only: nothing about them reaches the disk, and a restart forgets them all, which the
 * phones notice and answer by opening their shares again. The relay keeps a hash of each share's write token and
 * its presence key, which proves that a viewer holds the link without opening any frame.
 */
class ShareStore(
    private val nowMs: () -> Long = System::currentTimeMillis,
    val limits: Limits = Limits(),
) {
    data class Limits(
        val maxShares: Int = 10_000,
        val maxSnapshotBytes: Int = 2 * 1024 * 1024,
        val maxFrameBytes: Int = 64 * 1024,
        /** Frames held after the snapshot; the phone sends a fresh snapshot every few hundred, well before this. */
        val maxFramesAfterSnapshot: Int = 2_000,
        val maxWatchers: Int = 2_000,
        /** Signals a viewer may lag behind before its stream closes, so a slow viewer never holds up the others. */
        val maxBehind: Int = 64,
        val lifetimeMs: Long = 24 * 3_600_000L,
        val keepEndedMs: Long = 3_600_000L,
        val challengeMs: Long = 60_000L,
    )

    class Share internal constructor(
        val id: String,
        internal val tokenHash: ByteArray,
        internal val presenceKey: ByteArray,
        internal val createdMs: Long,
    ) {
        internal var endedMs: Long? = null
        internal var snapshot: StoredFrame? = null
        internal val frames = TreeMap<Long, StoredFrame>()
        internal var watchers = 0
        internal val viewers = LinkedHashSet<Channel<ShareSignal>>()

        val ended: Boolean get() = endedMs != null

        /** Hands [signal] to every viewer, closing those with no room left; call it holding the share's lock. */
        internal fun signal(signal: ShareSignal) {
            viewers.removeIf { viewer -> viewer.trySend(signal).isFailure.also { if (it) viewer.close() } }
        }
    }

    sealed interface Result {
        data object Ok : Result
        data object NotFound : Result
        data object Forbidden : Result
        data object TooLarge : Result
        data object Full : Result
    }

    private val shares = ConcurrentHashMap<String, Share>()
    private val challenges = ConcurrentHashMap<String, Pair<String, Long>>()
    private val random = SecureRandom()

    /** Registers [id], or confirms it for the same token; the phone repeats this after a restart of the relay. */
    fun open(id: String, token: ByteArray, presenceKey: ByteArray): Result = synchronized(this) {
        val existing = shares[id]
        if (existing != null) return if (MessageDigest.isEqual(existing.tokenHash, hash(token))) Result.Ok else Result.Forbidden
        if (shares.size >= limits.maxShares) return Result.Full
        shares[id] = Share(id, hash(token), presenceKey.copyOf(), nowMs())
        Result.Ok
    }

    fun publish(id: String, token: ByteArray, frame: StoredFrame): Result {
        val share = authorized(id, token) ?: return if (shares.containsKey(id)) Result.Forbidden else Result.NotFound
        val limit = if (frame.snapshot) limits.maxSnapshotBytes else limits.maxFrameBytes
        if (frame.bytes.size > limit) return Result.TooLarge
        synchronized(share) {
            if (frame.snapshot) {
                // Frames older than the snapshot are in it.
                if (frame.seq < (share.snapshot?.seq ?: Long.MIN_VALUE)) return Result.Ok
                share.snapshot = frame
                share.frames.headMap(frame.seq, true).clear()
            } else {
                if (share.frames.size >= limits.maxFramesAfterSnapshot) return Result.TooLarge
                share.frames[frame.seq] = frame
            }
            share.signal(ShareSignal.Frame(frame))
        }
        return Result.Ok
    }

    fun close(id: String, token: ByteArray): Result {
        val share = authorized(id, token) ?: return if (shares.containsKey(id)) Result.Forbidden else Result.NotFound
        synchronized(share) {
            if (share.endedMs == null) {
                share.endedMs = nowMs()
                share.signal(ShareSignal.Ended)
            }
        }
        return Result.Ok
    }

    /** Pages open on [id], for its walker only. */
    fun watchers(id: String, token: ByteArray): Int? = authorized(id, token)?.let { synchronized(it) { it.watchers } }

    /** A single-use nonce a viewer answers with `HMAC-SHA-512(presence key, nonce)` to be counted. */
    fun challenge(id: String): String? {
        if (!shares.containsKey(id)) return null
        val nonce = ByteArray(32).also(random::nextBytes).let { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        challenges[nonce] = id to nowMs()
        return nonce
    }

    /** The share, if [proof] answers a fresh challenge for it; the challenge is spent either way. */
    fun admit(id: String, nonce: String, proof: ByteArray): Share? {
        val (challenged, atMs) = challenges.remove(nonce) ?: return null
        if (challenged != id || nowMs() - atMs > limits.challengeMs) return null
        val share = shares[id] ?: return null
        val expected = Mac.getInstance("HmacSHA512").run {
            init(SecretKeySpec(share.presenceKey, "HmacSHA512"))
            doFinal(nonce.encodeToByteArray())
        }
        return share.takeIf { MessageDigest.isEqual(expected, proof) }
    }

    /** Counts a viewer in; false when the share has as many as it may. */
    fun join(share: Share): Boolean = synchronized(share) {
        if (share.watchers >= limits.maxWatchers) return false
        share.watchers++
        true
    }

    fun leave(share: Share) = synchronized(share) { share.watchers = (share.watchers - 1).coerceAtLeast(0) }

    /** The snapshot and what followed it, for a viewer who just arrived. */
    fun held(share: Share): List<StoredFrame> = synchronized(share) { listOfNotNull(share.snapshot) + share.frames.values }

    /** Starts handing [share]'s signals to a viewer, from what is held right now, so nothing in between is missed. */
    fun watch(share: Share): Watch = synchronized(share) {
        val signals = Channel<ShareSignal>(limits.maxBehind)
        share.viewers += signals
        Watch(held(share), share.ended, signals)
    }

    fun unwatch(share: Share, watch: Watch) = synchronized(share) {
        share.viewers.remove(watch.signals)
        watch.signals.cancel()
    }

    /** Forgets shares that ended a while ago or ran out of time, and challenges no one answered. */
    fun sweep() {
        val now = nowMs()
        shares.values.removeIf { share ->
            val expired = share.endedMs?.let { now - it > limits.keepEndedMs } ?: false || now - share.createdMs > limits.lifetimeMs
            if (expired) synchronized(share) {
                share.viewers.forEach { it.close() }
                share.viewers.clear()
            }
            expired
        }
        challenges.values.removeIf { now - it.second > limits.challengeMs }
    }

    private fun authorized(id: String, token: ByteArray): Share? =
        shares[id]?.takeIf { MessageDigest.isEqual(it.tokenHash, hash(token)) }

    private fun hash(token: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(token)
}
