package com.thalovant.sdk

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/*
 * The loopback listener a browser sign-in waits on (RFC 8252 section 7.3).
 *
 * A program that signs in through the browser binds 127.0.0.1 on a port the
 * system picks, sends the browser to the authorization page with
 * http://127.0.0.1:<port>/callback as its redirect, and waits here for the
 * browser to come back. Only GET /callback is served; anything else is a 404.
 * A callback whose state is not this sign-in's is a 400 and changes nothing:
 * a stray tab -- an old attempt's, another program's, a page probing local
 * ports -- must not end a real sign-in. The first callback with the right
 * state is accepted, the port stops listening, and its tab is answered once
 * the sign-in has ended.
 *
 * Everything that decides what a request means is in classifyLoopbackRequest,
 * which touches no socket, so it can be checked against the shared vectors on
 * its own. The listener is a plain ServerSocket with a small reader rather
 * than the JDK's HttpServer, which Android does not have and which answers
 * some requests itself; it writes nothing to a log, so the code and the state
 * in a request line never reach one.
 */

/** What the tab shows when the sign-in worked. */
internal const val SIGNED_IN_TEXT: String = "Signed in to Thalovant. You can close this tab."

/** What the tab shows when it did not: denied, failed, the exchange refused, or a timeout. */
internal const val NOT_COMPLETED_TEXT: String = "Sign-in was not completed."

/** What a tab whose answer belongs to another sign-in shows. */
internal const val WRONG_STATE_TEXT: String = "This answer does not belong to the sign-in in progress."

/** What anything other than `GET /callback` shows. */
internal const val NOT_FOUND_TEXT: String = "Not found."

/**
 * Sent with every answer. The pages load nothing, and nothing they show may be
 * cached or leak the callback's address (code and state) as a referrer.
 */
internal val LOOPBACK_PAGE_HEADERS: List<Pair<String, String>> = listOf(
    "Content-Type" to "text/html; charset=utf-8",
    "Cache-Control" to "no-store",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to "default-src 'none'",
    "X-Content-Type-Options" to "nosniff",
)

/** The largest request head read; a callback's is a few hundred bytes. */
internal const val MAX_REQUEST_HEAD: Int = 16 * 1024

/** How long one connection may take to send its request head. */
internal const val REQUEST_READ_TIMEOUT_MILLIS: Long = 10_000

/**
 * The `error` values RFC 6749 section 4.1.2.1 defines. Only these are kept
 * from a callback: anything else is a value somebody else chose, and it could
 * be the state or a code, which must never reach an error message.
 */
internal val OAUTH_ERRORS: Set<String> = setOf(
    "invalid_request", "unauthorized_client", "access_denied", "unsupported_response_type",
    "invalid_scope", "server_error", "temporarily_unavailable",
)

/** How long closing waits for an accepted tab's page to be written. */
internal const val CLOSE_GRACE_MILLIS: Long = 1_000

/**
 * After an answer, how long, and how much, of what the browser still sends is
 * read and dropped before the connection closes. A socket closed with bytes
 * unread answers them with a reset, and macOS then throws away the answer the
 * browser had not read yet -- an oversized head's 400, say.
 */
internal const val DRAIN_TIMEOUT_MILLIS: Long = 2_000
internal const val DRAIN_LIMIT: Int = 1024 * 1024

private val REASONS = mapOf(200 to "OK", 400 to "Bad Request", 404 to "Not Found")
private val HEAD_END = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

/**
 * An accepted callback: the browser came back with this sign-in's state.
 *
 * [Outcome.CODE] when the person approved; [Outcome.DENIED] for
 * `error=access_denied`; [Outcome.FAILED] for any other `error`, or for
 * neither an error nor exactly one code. [error] is the `error` the page sent
 * when it is one of [OAUTH_ERRORS], and null otherwise. A plain class whose
 * [toString] leaves [code] out.
 */
internal class LoopbackCallback(val outcome: Outcome, val code: String? = null, val error: String? = null) {
    enum class Outcome { CODE, DENIED, FAILED }

    override fun toString(): String = "LoopbackCallback(outcome=$outcome, error=$error)"
}

/**
 * What the listener answers one request with, and the callback it accepts.
 *
 * `404 to null` for anything but `GET` on exactly `/callback`; `400 to null`
 * when `state` is missing, given more than once, or not this sign-in's
 * (compared in constant time, as UTF-8 bytes); otherwise `200 to callback`.
 * An `error` wins over a `code` sent with it: a refusal that also carries a
 * code is still a refusal. An `error` outside [OAUTH_ERRORS] is not kept.
 * Query values are decoded once, as a form decodes them ([formDecode]);
 * `error_description` is not read.
 */
internal fun classifyLoopbackRequest(method: String, target: String, state: String): Pair<Int, LoopbackCallback?> {
    val path = target.substringBefore('?')
    val query = target.substringAfter('?', "")
    if (method != "GET" || path != NativeSignIn.LOOPBACK_CALLBACK_PATH) return 404 to null
    val values = linkedMapOf<String, MutableList<String>>()
    for ((key, value) in formDecode(query)) values.getOrPut(key) { mutableListOf() } += value
    val given = values["state"].orEmpty()
    if (given.size != 1 ||
        !MessageDigest.isEqual(given[0].toByteArray(Charsets.UTF_8), state.toByteArray(Charsets.UTF_8))
    ) {
        return 400 to null
    }
    val errors = values["error"].orEmpty()
    if (errors.isNotEmpty()) {
        if (errors == listOf("access_denied")) {
            return 200 to LoopbackCallback(LoopbackCallback.Outcome.DENIED, error = "access_denied")
        }
        return 200 to LoopbackCallback(LoopbackCallback.Outcome.FAILED, error = errors[0].takeIf { it in OAUTH_ERRORS })
    }
    val codes = values["code"].orEmpty()
    if (codes.size == 1 && codes[0].isNotEmpty()) {
        return 200 to LoopbackCallback(LoopbackCallback.Outcome.CODE, code = codes[0])
    }
    return 200 to LoopbackCallback(LoopbackCallback.Outcome.FAILED)
}

/**
 * A query string's pairs, decoded as a form decodes them -- the reference's
 * `parse_qsl(query, keep_blank_values=True)`: split on `&`, an empty piece
 * skipped, a piece without `=` read as a name with an empty value, `+` as a
 * space, and percent-escapes decoded once as UTF-8 with a malformed byte
 * replaced. A `%` not followed by two hex digits stays as it is.
 */
internal fun formDecode(query: String): List<Pair<String, String>> =
    query.split('&').filter { it.isNotEmpty() }.map { piece ->
        val name = piece.substringBefore('=')
        val value = piece.substringAfter('=', "")
        unquote(name.replace('+', ' ')) to unquote(value.replace('+', ' '))
    }

/**
 * Percent-decoding as Python's `urllib.parse.unquote` does it: each run of
 * ASCII characters has its escapes turned into bytes and is read as UTF-8,
 * with a malformed sequence replaced by U+FFFD; anything outside ASCII is
 * kept as it is.
 */
private fun unquote(text: String): String {
    if ('%' !in text) return text
    val out = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        if (text[index].code > 0x7F) {
            out.append(text[index])
            index++
            continue
        }
        val bytes = ByteArrayOutputStream()
        while (index < text.length && text[index].code <= 0x7F) {
            val char = text[index]
            val high = if (char == '%' && index + 2 < text.length) hexValue(text[index + 1]) else -1
            val low = if (high >= 0) hexValue(text[index + 2]) else -1
            if (high >= 0 && low >= 0) {
                bytes.write(high * 16 + low)
                index += 3
            } else {
                bytes.write(char.code)
                index++
            }
        }
        out.append(
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(ByteBuffer.wrap(bytes.toByteArray())),
        )
    }
    return out.toString()
}

private fun hexValue(char: Char): Int = when (char) {
    in '0'..'9' -> char - '0'
    in 'a'..'f' -> char - 'a' + 10
    in 'A'..'F' -> char - 'A' + 10
    else -> -1
}

/** A page that loads nothing: no script, no style sheet, no image, no link. */
private fun loopbackPage(text: String): ByteArray =
    ("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<title>Thalovant</title></head><body><p>$text</p></body></html>\n").toByteArray(Charsets.UTF_8)

/**
 * A whole HTTP/1.1 answer: the status, [LOOPBACK_PAGE_HEADERS], and a small
 * page. The answer to a `HEAD` ([head]) carries no body (RFC 9110 section
 * 9.3.2); its `Content-Length` is the length the page would have had.
 */
internal fun loopbackResponse(status: Int, text: String, head: Boolean = false): ByteArray {
    val body = loopbackPage(text)
    val lines = mutableListOf("HTTP/1.1 $status ${REASONS.getValue(status)}")
    lines += LOOPBACK_PAGE_HEADERS.map { (name, value) -> "$name: $value" }
    lines += listOf("Content-Length: ${body.size}", "Connection: close", "", "")
    return lines.joinToString("\r\n").toByteArray(Charsets.US_ASCII) + if (head) ByteArray(0) else body
}

/** The method and target of a request head, or null when it has no request line. */
internal fun loopbackRequestLine(head: ByteArray): Pair<String, String>? {
    val line = String(head, Charsets.ISO_8859_1).substringBefore("\r\n")
    val parts = line.split(" ")
    if (parts.size != 3 || !parts[2].startsWith("HTTP/")) return null
    return parts[0] to parts[1]
}

/** What reading a request head came to. */
private sealed class Head {
    class Read(val bytes: ByteArray) : Head()
    object TooLarge : Head()
    object Gone : Head()
}

/**
 * Waits on `127.0.0.1` for the browser to bring this sign-in's answer back.
 *
 * [start] binds the port (before any URL is built), [wait] returns the
 * accepted [LoopbackCallback] or null at the deadline, [finish] says which
 * page its tab gets, and [close] stops everything. Never binds anything but
 * the IPv4 loopback address.
 */
internal class LoopbackListener(
    private val state: String,
    private val drainTimeoutMillis: Long = DRAIN_TIMEOUT_MILLIS,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accepted = CompletableDeferred<LoopbackCallback>()
    private val answer = CompletableDeferred<Boolean>()
    private val connections = mutableSetOf<Socket>()
    private var server: ServerSocket? = null
    private var started = false
    private var closed = false

    /** The connection that brought the accepted callback, whose tab is still owed its page. */
    private var acceptedJob: Job? = null

    /** The address bound, for a test to see that it is loopback and nothing else. */
    internal val boundAddress: InetAddress? get() = synchronized(lock) { server?.inetAddress }

    /** The connections still being served, for a test to see that none outlives its drain. */
    internal val openConnections: Int get() = synchronized(lock) { connections.size }

    /** Binds `127.0.0.1` on a port the system picks and starts accepting; returns the port. */
    fun start(): Int {
        val socket = ServerSocket()
        try {
            // No other socket may share the port. On Windows the JDK binds with
            // SO_EXCLUSIVEADDRUSE when address reuse is off.
            socket.reuseAddress = false
            // The literal, never getLoopbackAddress(), which can answer ::1,
            // and never a name such as localhost.
            socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
        synchronized(lock) {
            server = socket
            started = true
        }
        scope.launch { acceptLoop(socket) }
        return socket.localPort
    }

    /**
     * The first accepted callback, or null once [timeoutMillis] have passed.
     *
     * Measured on the monotonic clock and never cut short: a timer that wakes
     * early waits again for what is left. A request with the wrong state
     * neither ends the wait nor restarts it.
     */
    suspend fun wait(timeoutMillis: Long): LoopbackCallback? {
        check(synchronized(lock) { started }) { "The loopback listener was not started." }
        val deadline = System.nanoTime() + timeoutMillis.coerceAtLeast(0) * 1_000_000
        while (!accepted.isCompleted) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            withTimeoutOrNull((left + 999_999) / 1_000_000) { accepted.await() }
        }
        // Already completed, so this returns at once.
        return accepted.await()
    }

    /** Answers the accepted tab: signed in, or not completed. */
    fun finish(signedIn: Boolean) {
        answer.complete(signedIn)
    }

    /**
     * Stops listening, lets an accepted tab have its page (waiting at most
     * [CLOSE_GRACE_MILLIS]), and closes every other connection without
     * waiting for it: a browser opens connections it never sends anything on,
     * and the sign-in must not wait on them. Runs to the end even when the
     * caller was cancelled, so a cancelled sign-in closes its port.
     */
    suspend fun close() {
        withContext(NonCancellable) {
            finish(false)
            val job = synchronized(lock) {
                closed = true
                runCatching { server?.close() }
                acceptedJob
            }
            if (job != null) withTimeoutOrNull(CLOSE_GRACE_MILLIS) { job.join() }
            val open = synchronized(lock) { connections.toList().also { connections.clear() } }
            for (socket in open) runCatching { socket.close() }
            scope.cancel()
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val connection = try {
                socket.accept()
            } catch (_: Exception) {
                return // closed
            }
            val keep = synchronized(lock) { if (closed) false else connections.add(connection) }
            if (!keep) {
                runCatching { connection.close() }
                continue
            }
            scope.launch { handle(connection) }
        }
    }

    /** One connection: one request read, one answer written, then closed. */
    private suspend fun handle(socket: Socket) {
        try {
            serve(socket)
        } catch (_: Exception) {
            // One connection's failure is that connection's alone. Nothing is
            // logged: a request line carries the code and the state.
        } finally {
            synchronized(lock) { connections.remove(socket) }
            runCatching { socket.close() }
        }
    }

    private suspend fun serve(socket: Socket) {
        val head = when (val read = readHead(socket)) {
            is Head.Gone -> return
            is Head.TooLarge -> return answer(socket, 400, WRONG_STATE_TEXT)
            is Head.Read -> read.bytes
        }
        val request = loopbackRequestLine(head) ?: return answer(socket, 400, WRONG_STATE_TEXT)
        val (status, callback) = classifyLoopbackRequest(request.first, request.second, state)
        val mine = callback != null && synchronized(lock) {
            // A second answer with the right state, after the first was
            // accepted, is not this sign-in's any more either.
            if (closed || !accepted.complete(callback)) {
                false
            } else {
                acceptedJob = kotlin.coroutines.coroutineContext[Job]
                // The first accepted callback ends the listening; a tab
                // already connected is still answered.
                runCatching { server?.close() }
                true
            }
        }
        if (!mine) {
            val refused = if (callback != null) 400 else status
            return answer(socket, refused, if (refused == 404) NOT_FOUND_TEXT else WRONG_STATE_TEXT, head = request.first == "HEAD")
        }
        // Until the sign-in has ended, however long the exchange takes: the
        // page says how it ended, so it cannot be written before it has. A
        // deadline of its own, shorter than the exchange's, would say "not
        // completed" for a sign-in that then succeeds. close() always
        // answers, so this never outlives the sign-in.
        val signedIn = answer.await()
        answer(socket, 200, if (signedIn) SIGNED_IN_TEXT else NOT_COMPLETED_TEXT)
    }

    /** Reads up to the blank line that ends a request head, within the size and time limits. */
    private fun readHead(socket: Socket): Head {
        val deadline = System.nanoTime() + REQUEST_READ_TIMEOUT_MILLIS * 1_000_000
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        val input = socket.getInputStream()
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return Head.Gone
            socket.soTimeout = left.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
            val count = try {
                input.read(chunk)
            } catch (_: SocketTimeoutException) {
                return Head.Gone
            }
            if (count < 0) return Head.Gone
            buffer.write(chunk, 0, count)
            val bytes = buffer.toByteArray()
            val end = indexOf(bytes, HEAD_END)
            if (end > MAX_REQUEST_HEAD) return Head.TooLarge
            if (end >= 0) return Head.Read(bytes.copyOfRange(0, end + HEAD_END.size))
            if (bytes.size - HEAD_END.size + 1 > MAX_REQUEST_HEAD) return Head.TooLarge
        }
    }

    /**
     * Writes one answer, then lets the browser finish before the connection
     * closes. The write side is shut first (the browser sees the answer end),
     * then what the browser still sends -- the rest of an oversized head, a
     * body nobody asked for -- is read and dropped until it closes its side,
     * for at most [drainTimeoutMillis] and [DRAIN_LIMIT] bytes. Closing with
     * bytes unread would reset the connection, and macOS then discards the
     * answer the browser had not read yet.
     */
    private fun answer(socket: Socket, status: Int, text: String, head: Boolean = false) {
        val output = socket.getOutputStream()
        output.write(loopbackResponse(status, text, head))
        output.flush()
        runCatching {
            socket.shutdownOutput()
            val deadline = System.nanoTime() + drainTimeoutMillis * 1_000_000
            val input = socket.getInputStream()
            val sink = ByteArray(64 * 1024)
            var drained = 0
            while (drained <= DRAIN_LIMIT) {
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) return@runCatching
                socket.soTimeout = left.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
                val count = input.read(sink)
                if (count < 0) return@runCatching
                drained += count
            }
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (start in 0..haystack.size - needle.size) {
            for (offset in needle.indices) if (haystack[start + offset] != needle[offset]) continue@outer
            return start
        }
        return -1
    }
}
