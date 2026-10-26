package dev.playground.touchpad.net

import dev.playground.touchpad.crypto.SecureChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Pairs with and drives a real touchpad-server, using the app's own protocol
 * classes. Skipped unless a server is given; run it with
 * `scripts/interop-test.ps1`, which starts a throwaway server and confirms the
 * pairing on its console only if both sides computed the same code.
 */
class InteropTest {
    private val host: String? = System.getProperty("touchpad.interop.host")
    private val codeFile: String? = System.getProperty("touchpad.interop.codefile")
    private val port: Int = System.getProperty("touchpad.interop.port")?.toIntOrNull() ?: Protocol.PORT

    @Test fun pairConnectAndDriveTheRealServer() {
        assumeTrue("set -PtouchpadInteropHost to run", host != null)
        val pc = InetSocketAddress(host, port)
        DatagramSocket().use { socket ->
            socket.soTimeout = 300
            fun send(bytes: ByteArray) = socket.send(DatagramPacket(bytes, bytes.size, pc))
            fun receive(): ByteArray? = try {
                val buf = ByteArray(2048)
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                buf.copyOf(p.length)
            } catch (_: SocketTimeoutException) {
                null
            }
            fun <T> exchange(packet: ByteArray, tries: Int = 20, accept: (Protocol.Reply) -> T?): T {
                repeat(tries) {
                    send(packet)
                    var raw = receive()
                    while (raw != null) {
                        Protocol.decode(raw)?.let(accept)?.let { return it }
                        raw = receive()
                    }
                }
                error("no matching reply")
            }

            // Discovery
            val announce = exchange(Protocol.discover()) { it as? Protocol.Reply.Announce }
            assertTrue("start the server with --pair", announce.pairingOpen)

            // Pairing
            val attempt = PairingAttempt("Interop test")
            val commit = exchange(attempt.startPacket()) { it as? Protocol.Reply.PairCommit }
            assertTrue(attempt.onCommit(commit))
            val reveal = exchange(attempt.noncePacket()) { it as? Protocol.Reply.PairReveal }
            val code = checkNotNull(attempt.onReveal(reveal)) { "PC answers were inconsistent" }
            codeFile?.let { File(it).writeText("%06d".format(code)) } // the harness compares it with the PC's
            // Keep confirming (as the app does) until the person at the PC answers.
            var paired: PairingAttempt.Outcome.Paired? = null
            val deadline = System.currentTimeMillis() + 60_000
            while (paired == null && System.currentTimeMillis() < deadline) {
                val outcome = exchange(attempt.confirmPacket()) { r -> (r as? Protocol.Reply.PairResult)?.let(attempt::onResult) }
                if (outcome is PairingAttempt.Outcome.Failed) error("pairing failed: ${outcome.status}")
                paired = outcome as? PairingAttempt.Outcome.Paired
                if (paired == null) Thread.sleep(300)
            }
            val pairing = checkNotNull(paired) { "operator never accepted" }
            assertTrue(pairing.serverId.contentEquals(announce.serverId))

            // A HELLO with a wrong MAC gets no answer at all.
            send(Protocol.hello(attempt.pairId, ByteArray(16), ByteArray(16)))
            assertNull(receive())

            // Session
            val handshake = SessionHandshake(attempt.pairId, pairing.key)
            val channel: SecureChannel = exchange(handshake.helloPacket()) { r ->
                (r as? Protocol.Reply.Welcome)?.let(handshake::onWelcome)
            }
            val heartbeat = channel.seal(Protocol.Inner.heartbeat(1, 0, 0))
            send(heartbeat)
            val ack = channel.open(checkNotNull(receive()))
            assertEquals(1, Protocol.Inner.decodeAck(checkNotNull(ack)))

            // The same packet again is a replay: ignored, no answer.
            send(heartbeat)
            assertNull(receive())

            // Move the pointer a little and back (net zero).
            repeat(5) { send(channel.seal(Protocol.Inner.move(4, 4))) }
            repeat(5) { send(channel.seal(Protocol.Inner.move(-4, -4))) }

            // Unpair from the phone: the PC forgets us and refuses new sessions.
            send(channel.seal(Protocol.Inner.forget()))
            Thread.sleep(200)
            send(SessionHandshake(attempt.pairId, pairing.key).helloPacket())
            assertNull(receive())
        }
    }
}
