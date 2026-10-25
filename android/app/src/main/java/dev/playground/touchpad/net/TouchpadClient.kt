package dev.playground.touchpad.net

import android.os.SystemClock
import dev.playground.touchpad.crypto.SecureChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import kotlin.random.Random

/**
 * Finds the PC, pairs with it, and streams input over an encrypted session.
 *
 * Concurrency: all protocol state is owned by one "actor" coroutine. The UI
 * and the socket reader only post messages to it: user actions to [Run.control]
 * and received datagrams to the bounded [Run.inbox], so a flood of packets
 * from the network drops old packets instead of growing memory.
 */
class TouchpadClient(private val store: PairingStore, private val phoneName: String) {

    sealed interface State {
        data class NotPaired(val pcs: List<FoundPc>) : State
        data class Pairing(val pcName: String, val step: PairingStep) : State
        data class Searching(val pcName: String) : State
        data class Connected(val pcName: String, val address: String, val pingMs: Int?) : State
    }

    data class FoundPc(val id: String, val name: String, val address: String, val pairingOpen: Boolean)

    sealed interface PairingStep {
        data object Contacting : PairingStep
        data class Compare(val code: Int) : PairingStep
        data object WaitingForPc : PairingStep
        data class Failed(val reason: FailReason) : PairingStep
    }

    enum class FailReason { CLOSED, BUSY, REJECTED, TIMED_OUT, TAMPERED }

    private val _state = MutableStateFlow<State>(
        store.pairedPcName()?.let { State.Searching(it) } ?: State.NotPaired(emptyList()),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    private sealed interface Msg {
        data class Move(val dx: Int, val dy: Int) : Msg
        class Button(val button: Protocol.Button, val pressed: Boolean) : Msg
        class Key(val stroke: Protocol.KeyStroke) : Msg
        /** A repeat copy; dropped if the session changed since the original. */
        class Resend(val plaintext: ByteArray, val channel: SecureChannel?) : Msg
        data class Pair(val pcId: String) : Msg
        data object ConfirmCode : Msg
        data object CancelPairing : Msg
        data object Unpair : Msg
        data object PingNow : Msg
        data object Tick : Msg
        data object Stop : Msg
    }

    private class Datagram(val bytes: ByteArray, val from: InetSocketAddress)

    private class Run(val scope: CoroutineScope) {
        val control = Channel<Msg>(Channel.UNLIMITED)
        val inbox = Channel<Datagram>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    @Volatile private var run: Run? = null

    // Sub-count remainders so slow finger movement isn't rounded away. UI thread only.
    private var remainderX = 0f
    private var remainderY = 0f

    fun start() {
        if (run != null) return
        val r = Run(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        run = r
        r.scope.launch { Actor(r).run() }
    }

    /** Says goodbye to the PC (it releases held buttons) and stops. */
    fun stop() {
        run?.control?.trySend(Msg.Stop)
        run = null
    }

    fun move(dx: Float, dy: Float) {
        remainderX += dx
        remainderY += dy
        val ix = remainderX.toInt()
        val iy = remainderY.toInt()
        if (ix == 0 && iy == 0) return
        remainderX -= ix
        remainderY -= iy
        if (_state.value is State.Connected) {
            send(Msg.Move(ix.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
                iy.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())))
        }
    }

    fun setButton(button: Protocol.Button, pressed: Boolean) = send(Msg.Button(button, pressed))

    /** Types one key on the PC. Ignored while not connected, so nothing typed
     *  offline is replayed later. */
    fun key(stroke: Protocol.KeyStroke) = send(Msg.Key(stroke))
    fun pair(pc: FoundPc) = send(Msg.Pair(pc.id))
    fun confirmCode() = send(Msg.ConfirmCode)
    fun cancelPairing() = send(Msg.CancelPairing)
    fun unpair() = send(Msg.Unpair)
    fun pingNow() = send(Msg.PingNow)

    private fun send(msg: Msg) {
        run?.control?.trySend(msg)
    }

    /** Everything below runs on the actor coroutine only. */
    private inner class Actor(private val r: Run) {
        private lateinit var socket: DatagramSocket
        private var pairing: PairingStore.Pairing? = null
        private var pcAddress: InetSocketAddress? = null

        private var handshake: SessionHandshake? = null
        private var channel: SecureChannel? = null
        private var lastHeardAt = 0L
        private var lastHelloAt = 0L
        private var lastHeartbeatAt = 0L
        private var heartbeatSeq = 0
        private var pingSeq = -1
        private var pingSentAt = 0L
        // Random start so a restarted app never looks like a replay of old events.
        private var eventId = Random.nextInt(0x10000)
        private var buttons = 0
        // Keys have their own event ids. Every KEYS packet repeats the keys
        // sent in the last moment, so the next packet makes up for a lost one.
        private var keyEventId = Random.nextInt(0x10000)
        private val recentKeys = ArrayDeque<SentKey>()

        private val found = LinkedHashMap<String, Pair<FoundPc, Long>>()
        private val foundAddresses = HashMap<String, InetSocketAddress>()
        private var lastDiscoverAt = 0L
        private var broadcastTargets: List<InetAddress> = emptyList()
        private var broadcastTargetsAt = 0L

        private var attempt: PairingAttempt? = null
        private var attemptTarget: InetSocketAddress? = null
        private var attemptPcName = ""
        private var attemptStep = Step.START
        private var attemptStartedAt = 0L
        private var lastPairSendAt = 0L
        /** A failed pairing stays on screen until the user dismisses it. */
        private var failureShown = false

        suspend fun run() {
            socket = DatagramSocket().apply { broadcast = true }
            r.scope.launch { receiveLoop() }
            r.scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    r.control.trySend(Msg.Tick)
                }
            }
            pairing = store.load()
            pcAddress = store.lastAddress?.let(::addressOf)
            setState(pairing?.let { State.Searching(it.pcName) } ?: State.NotPaired(emptyList()))
            try {
                while (true) {
                    val keepGoing = select {
                        r.control.onReceive { handle(it) } // listed first: user input wins
                        r.inbox.onReceive {
                            onDatagram(it)
                            true
                        }
                    }
                    if (!keepGoing) break
                }
            } finally {
                socket.close()
                r.scope.cancel()
            }
        }

        private fun receiveLoop() {
            val buf = ByteArray(2048)
            val packet = DatagramPacket(buf, buf.size)
            while (true) {
                packet.length = buf.size
                try {
                    socket.receive(packet)
                } catch (_: SocketException) {
                    return // socket closed
                }
                val from = packet.socketAddress as? InetSocketAddress ?: continue
                r.inbox.trySend(Datagram(buf.copyOf(packet.length), from))
            }
        }

        private fun handle(msg: Msg): Boolean {
            val now = SystemClock.elapsedRealtime()
            when (msg) {
                is Msg.Move -> sendSecure(Protocol.Inner.move(msg.dx, msg.dy))
                is Msg.Button -> {
                    buttons = if (msg.pressed) buttons or msg.button.mask else buttons and msg.button.mask.inv()
                    eventId = (eventId + 1) and 0xFFFF
                    sendWithCopies(Protocol.Inner.button(eventId, msg.button, msg.pressed))
                }
                is Msg.Key -> if (channel != null) {
                    keyEventId = (keyEventId + 1) and 0xFFFF
                    recentKeys.addLast(SentKey(keyEventId, msg.stroke, now))
                    while (recentKeys.size > Protocol.Inner.MAX_KEYS || now - recentKeys.first().sentAt > KEY_REPEAT_MS) {
                        recentKeys.removeFirst()
                    }
                    sendWithCopies(Protocol.Inner.keys(recentKeys.first().id, recentKeys.map { it.stroke }))
                }
                is Msg.Resend -> if (msg.channel === channel) sendSecure(msg.plaintext)
                is Msg.Pair -> startPairing(msg.pcId, now)
                Msg.ConfirmCode -> if (attempt != null && attemptStep == Step.COMPARE) {
                    attemptStep = Step.CONFIRM
                    sendPairing(now)
                    setState(State.Pairing(attemptPcName, PairingStep.WaitingForPc))
                }
                Msg.CancelPairing -> {
                    attempt = null
                    failureShown = false
                    publishFound(now)
                }
                Msg.Unpair -> {
                    // Ask the PC to forget us too (authenticated by the session).
                    repeat(2) { sendSecure(Protocol.Inner.forget()) }
                    store.clear()
                    pairing = null
                    pcAddress = null
                    dropSession()
                    found.clear()
                    setState(State.NotPaired(emptyList()))
                }
                Msg.PingNow -> if (channel != null) sendHeartbeat(now) else lastHelloAt = 0L
                Msg.Tick -> onTick(now)
                Msg.Stop -> {
                    sendSecure(Protocol.Inner.bye())
                    return false
                }
            }
            return true
        }

        private fun onTick(now: Long) {
            val currentAttempt = attempt
            val currentPairing = pairing
            when {
                currentAttempt != null -> {
                    if (now - attemptStartedAt > PAIRING_TIMEOUT_MS) {
                        failPairing(FailReason.TIMED_OUT)
                    } else if (attemptStep != Step.COMPARE && now - lastPairSendAt >= RETRANSMIT_MS) {
                        sendPairing(now)
                    }
                }
                currentPairing == null -> {
                    if (now - lastDiscoverAt >= DISCOVER_UNPAIRED_MS) sendDiscover(now)
                    publishFound(now)
                }
                channel != null -> {
                    if (now - lastHeardAt > LOST_AFTER_MS) {
                        dropSession()
                        setState(State.Searching(currentPairing.pcName))
                    } else if (now - lastHeartbeatAt >= HEARTBEAT_MS) {
                        sendHeartbeat(now)
                    }
                }
                now - lastHelloAt >= RETRANSMIT_MS -> {
                    // Ask the last known address directly (one round trip), and
                    // broadcast in case the PC's address changed.
                    sendHello(now)
                    if (now - lastDiscoverAt >= RETRANSMIT_MS) sendDiscover(now)
                }
            }
        }

        private fun onDatagram(d: Datagram) {
            val now = SystemClock.elapsedRealtime()
            when (val reply = Protocol.decode(d.bytes) ?: return) {
                is Protocol.Reply.Announce -> onAnnounce(reply, d.from, now)
                is Protocol.Reply.PairCommit -> {
                    val a = attempt ?: return
                    if (d.from == attemptTarget && attemptStep == Step.START && a.onCommit(reply)) {
                        attemptStep = Step.NONCE
                        sendPairing(now)
                    }
                }
                is Protocol.Reply.PairReveal -> {
                    val a = attempt ?: return
                    if (d.from != attemptTarget || attemptStep != Step.NONCE) return
                    val code = a.onReveal(reply) ?: return failPairing(FailReason.TAMPERED)
                    attemptStep = Step.COMPARE
                    setState(State.Pairing(attemptPcName, PairingStep.Compare(code)))
                }
                is Protocol.Reply.PairResult -> onPairResult(reply, d.from, now)
                is Protocol.Reply.Welcome -> {
                    val ch = handshake?.onWelcome(reply) ?: return // not authentic: ignore
                    val p = pairing ?: return
                    channel = ch
                    handshake = null
                    recentKeys.clear()
                    pcAddress = d.from
                    store.lastAddress = d.from.address.hostAddress
                    lastHeardAt = now
                    setState(State.Connected(p.pcName, d.from.address.hostAddress ?: "", null))
                    sendHeartbeat(now)
                }
                is Protocol.Reply.Secure -> {
                    val ch = channel ?: return
                    val plaintext = ch.open(d.bytes) ?: return // forged, replayed or old
                    val seq = Protocol.Inner.decodeAck(plaintext) ?: return
                    val p = pairing ?: return
                    lastHeardAt = now
                    pcAddress = d.from // the PC may have changed address; it proved who it is
                    val ping = if (seq == pingSeq) (now - pingSentAt).toInt() else null
                    val previous = _state.value as? State.Connected
                    setState(State.Connected(p.pcName, d.from.address.hostAddress ?: "", ping ?: previous?.pingMs))
                }
            }
        }

        private fun onAnnounce(a: Protocol.Reply.Announce, from: InetSocketAddress, now: Long) {
            val p = pairing
            if (p != null) {
                // Announcements are unauthenticated, so they only tell us where
                // to send a HELLO; the PC must still prove itself in WELCOME.
                if (a.serverId.contentEquals(p.serverId) && channel == null) {
                    pcAddress = from
                    sendHello(now)
                }
                return
            }
            if (attempt != null) return
            val id = a.serverId.toHex()
            found[id] = FoundPc(id, a.name, from.address.hostAddress ?: "", a.pairingOpen) to now
            foundAddresses[id] = from
            publishFound(now)
        }

        private fun onPairResult(reply: Protocol.Reply.PairResult, from: InetSocketAddress, now: Long) {
            val a = attempt ?: return
            if (from != attemptTarget) return
            when (val outcome = a.onResult(reply) ?: return) {
                PairingAttempt.Outcome.WaitingForPc -> Unit
                is PairingAttempt.Outcome.Failed -> failPairing(
                    when (outcome.status) {
                        Protocol.PairStatus.CLOSED -> FailReason.CLOSED
                        Protocol.PairStatus.BUSY -> FailReason.BUSY
                        Protocol.PairStatus.REJECTED -> FailReason.REJECTED
                        Protocol.PairStatus.UNKNOWN -> FailReason.TIMED_OUT
                        else -> FailReason.TAMPERED
                    },
                )
                is PairingAttempt.Outcome.Paired -> {
                    val p = PairingStore.Pairing(outcome.serverId, attemptPcName, a.pairId, outcome.key)
                    store.save(p)
                    store.lastAddress = from.address.hostAddress
                    pairing = p
                    pcAddress = from
                    attempt = null
                    setState(State.Searching(p.pcName))
                    sendHello(now)
                }
            }
        }

        private fun startPairing(pcId: String, now: Long) {
            if (pairing != null) return
            val pc = found[pcId]?.first ?: return
            failureShown = false
            attempt = PairingAttempt(phoneName)
            attemptTarget = foundAddresses[pcId]
            attemptPcName = pc.name
            attemptStep = Step.START
            attemptStartedAt = now
            setState(State.Pairing(pc.name, PairingStep.Contacting))
            sendPairing(now)
        }

        private fun sendPairing(now: Long) {
            val a = attempt ?: return
            val target = attemptTarget ?: return
            val packet = when (attemptStep) {
                Step.START -> a.startPacket()
                Step.NONCE -> a.noncePacket()
                Step.CONFIRM -> a.confirmPacket()
                Step.COMPARE -> return
            }
            lastPairSendAt = now
            send(packet, target)
        }

        private fun failPairing(reason: FailReason) {
            attempt = null
            failureShown = true
            setState(State.Pairing(attemptPcName, PairingStep.Failed(reason)))
        }

        private fun sendHello(now: Long) {
            val p = pairing ?: return
            val target = pcAddress ?: return
            val h = handshake ?: SessionHandshake(p.pairId, p.key).also { handshake = it }
            lastHelloAt = now
            send(h.helloPacket(), target)
        }

        private fun sendHeartbeat(now: Long) {
            heartbeatSeq++
            pingSeq = heartbeatSeq
            pingSentAt = now
            lastHeartbeatAt = now
            sendSecure(Protocol.Inner.heartbeat(heartbeatSeq, eventId, buttons))
        }

        /**
         * Wi-Fi loses packets in bursts, so copies are spread out in time.
         * Each copy is sealed again (new counter); the PC applies only the
         * first copy of each event.
         */
        private fun sendWithCopies(plaintext: ByteArray) {
            sendSecure(plaintext)
            val sentOn = channel
            r.scope.launch {
                delay(20)
                r.control.trySend(Msg.Resend(plaintext, sentOn))
                delay(40)
                r.control.trySend(Msg.Resend(plaintext, sentOn))
            }
        }

        private fun sendSecure(plaintext: ByteArray) {
            val ch = channel ?: return
            val target = pcAddress ?: return
            send(ch.seal(plaintext), target)
        }

        private fun dropSession() {
            channel = null
            handshake = null
            // A new session starts with no history: old keys must never replay.
            recentKeys.clear()
            lastHelloAt = 0L
        }

        private fun sendDiscover(now: Long) {
            lastDiscoverAt = now
            val packet = Protocol.discover()
            pcAddress?.let { send(packet, it) }
            for (address in broadcastAddresses(now)) send(packet, InetSocketAddress(address, Protocol.PORT))
        }

        private fun publishFound(now: Long) {
            if (attempt != null || pairing != null || failureShown) return
            found.entries.removeAll { now - it.value.second > FOUND_TTL_MS }
            foundAddresses.keys.retainAll(found.keys)
            setState(State.NotPaired(found.values.map { it.first }))
        }

        private fun broadcastAddresses(now: Long): List<InetAddress> {
            if (now - broadcastTargetsAt > 5_000 || broadcastTargets.isEmpty()) {
                val subnets = try {
                    NetworkInterface.getNetworkInterfaces().toList()
                        .filter { it.isUp && !it.isLoopback }
                        .flatMap { it.interfaceAddresses }
                        .mapNotNull { it.broadcast }
                } catch (_: SocketException) {
                    emptyList()
                }
                broadcastTargets = (subnets + InetAddress.getByName("255.255.255.255")).distinct()
                broadcastTargetsAt = now
            }
            return broadcastTargets
        }

        private fun send(data: ByteArray, target: InetSocketAddress) {
            try {
                socket.send(DatagramPacket(data, data.size, target))
            } catch (_: Exception) {
                // No route (Wi-Fi off, airplane mode): the tick loop retries.
            }
        }

        private fun setState(s: State) {
            if (_state.value != s) _state.value = s
        }
    }

    private enum class Step { START, NONCE, COMPARE, CONFIRM }

    private class SentKey(val id: Int, val stroke: Protocol.KeyStroke, val sentAt: Long)

    private fun addressOf(host: String): InetSocketAddress? =
        // Only IP literals are stored, so this never does a DNS lookup.
        runCatching { InetSocketAddress(InetAddress.getByName(host), Protocol.PORT) }.getOrNull()

    private companion object {
        const val TICK_MS = 100L
        const val RETRANSMIT_MS = 400L
        const val HEARTBEAT_MS = 1_000L
        const val LOST_AFTER_MS = 3_500L
        const val DISCOVER_UNPAIRED_MS = 1_000L
        const val FOUND_TTL_MS = 4_000L
        const val PAIRING_TIMEOUT_MS = 120_000L
        /** How long a key keeps being repeated in later KEYS packets. */
        const val KEY_REPEAT_MS = 1_000L
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
