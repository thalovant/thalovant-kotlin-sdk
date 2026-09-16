package com.thalovant.sdk

import java.util.zip.Inflater
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * WIRE-1 §4 binary frames.
 *
 * A hub answers `speak:synth` by rendering the utterance and sending the audio
 * back in one of these, so a client with no synthesiser of its own can still
 * speak; a file arrives the same way. They are not JSON: the header is packed
 * bit by bit, and for a BINARY frame the payload is raw bytes rather than text.
 *
 * Layout, reading left to right:
 *
 * ```
 *   0*            zero bits of left padding, then a single 1
 *   1 bit         versioned
 *   [8 bits]      protocol version, only when versioned
 *   5 bits        message type (see TYPE_CODES)
 *   1 bit         payload and metadata are zlib compressed
 *   8 bits        metadata length in bytes
 *   N bytes       metadata, JSON
 *   [4 bits]      binary payload type, only for a BINARY frame
 *   rest          the payload: raw bytes for BINARY, JSON text otherwise
 * ```
 *
 * The padding is inserted at the *front* by the encoder, which is why the
 * reader scans for the first set bit rather than assuming an offset, and why
 * a BINARY payload begins part-way through a byte.
 */
internal object HiveWire {

    /** WIRE-1 §4.3 message-type codes. A type with no code travels as text. */
    private val TYPE_CODES = mapOf(
        0 to "shake", 1 to "bus", 2 to "shared_bus", 3 to "broadcast", 4 to "propagate",
        5 to "escalate", 6 to "hello", 7 to "query", 8 to "cascade", 9 to "ping",
        10 to "rendezvous", 12 to "bin",
    )

    /** The binary payload types this SDK names. Unknown arrives as `binary:<n>`. */
    private val BINARY_KINDS = mapOf(
        1 to "raw_audio", 2 to "numpy_image", 3 to "file",
        4 to "stt_transcribe", 5 to "stt_handle", 6 to "tts_audio",
    )

    /** The name this SDK gives a wire payload type; unknown keeps its number. */
    fun nameForPayloadType(wire: Int): String = BINARY_KINDS[wire] ?: "binary:$wire"

    /** A decoded frame: either a bus-shaped one, or bytes with a kind. */
    internal data class Frame(
        val msgType: String,
        val text: String?,
        val binary: ThalovantBinary?,
    )

    /**
     * Decode one frame.
     *
     * Throws [ThalovantRuntimeException] for anything the layout cannot
     * account for; a malformed frame must not be mistaken for a short one.
     */
    fun decode(bytes: ByteArray): Frame {
        val reader = BitReader(bytes)
        reader.skipLeftPadding()
        if (reader.readBit() == 1) {
            val version = reader.readUInt(8)
            if (version > 1) {
                throw ThalovantRuntimeException("Unsupported HiveMind binary frame version: $version.")
            }
        }
        val typeCode = reader.readUInt(5)
        val compressed = reader.readBit() == 1
        val metadataLength = reader.readUInt(8)
        val metadataBytes = reader.readBytes(metadataLength)
        val msgType = TYPE_CODES[typeCode] ?: "3rdparty"
        if (msgType != "bin") {
            // A binarized text frame: the payload is JSON, like any other.
            return Frame(msgType, text(reader.readRemainingBytes(), compressed), null)
        }
        // A BINARY frame. The payload type is four bits, and the bytes after it
        // are the clip itself -- never compressed, never parsed.
        val kind = reader.readUInt(4)
        // The reference encoder pads at the front, so what follows the four-bit
        // kind lands on a byte boundary. One to seven bits left over means a
        // malformed frame, and integer division would drop them silently and
        // hand back the preceding bytes as though they were the whole clip.
        reader.requireByteAligned()
        val metadata = json(text(metadataBytes, compressed))
        return Frame(
            msgType,
            null,
            ThalovantBinary(
                kind = BINARY_KINDS[kind] ?: "binary:$kind",
                data = reader.readRemainingBytes(),
                metadata = metadata,
            ),
        )
    }

    private fun json(raw: String): JsonObject =
        if (raw.isEmpty()) EMPTY_JSON_OBJECT
        else runCatching { Json.parseToJsonElement(raw) as JsonObject }.getOrDefault(EMPTY_JSON_OBJECT)

    private fun text(bytes: ByteArray, compressed: Boolean): String =
        (if (compressed) inflate(bytes) else bytes).toString(Charsets.UTF_8)

    private fun inflate(bytes: ByteArray): ByteArray {
        // No early return for an empty input: a frame that says it is
        // compressed and then carries nothing is a truncated stream, not empty
        // metadata, and the loop below is what says so. Returning here let an
        // incomplete stream decode to {}.
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val out = java.io.ByteArrayOutputStream(bytes.size * 2)
            val chunk = ByteArray(8192)
            while (!inflater.finished()) {
                val read = inflater.inflate(chunk)
                if (read == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    // Truncated: the stream ended mid-block. Returning what had
                    // accumulated handed back half the metadata, and the JSON
                    // parse then fell back to an empty object -- so a truncated
                    // frame was accepted as one carrying no metadata at all.
                    throw ThalovantRuntimeException(
                        "Malformed HiveMind binary frame: compressed block ends early.",
                    )
                }
                out.write(chunk, 0, read)
            }
            return out.toByteArray()
        } catch (error: java.util.zip.DataFormatException) {
            throw ThalovantRuntimeException("Malformed HiveMind binary frame: bad compressed block.")
        } finally {
            inflater.end()
        }
    }

    /**
     * Reads fields that do not line up with byte boundaries.
     *
     * Everything after the 4-bit binary payload type is misaligned, so the
     * payload has to be reassembled bit by bit rather than sliced out.
     */
    private class BitReader(private val bytes: ByteArray) {
        private var offset = 0
        private val bits = bytes.size * 8

        fun skipLeftPadding() {
            while (offset < bits && readBit() == 0) {
                // Leading zeros are padding; the first 1 starts the frame.
            }
            if (offset >= bits) throw ThalovantRuntimeException("Malformed HiveMind binary frame: no start bit.")
        }

        fun readBit(): Int {
            if (offset >= bits) throw ThalovantRuntimeException("Malformed HiveMind binary frame: truncated.")
            val bit = (bytes[offset / 8].toInt() shr (7 - offset % 8)) and 1
            offset += 1
            return bit
        }

        fun readUInt(width: Int): Int {
            var value = 0
            repeat(width) { value = (value shl 1) or readBit() }
            return value
        }

        fun readBytes(count: Int): ByteArray {
            if (offset + count * 8 > bits) {
                throw ThalovantRuntimeException("Malformed HiveMind binary frame: truncated field.")
            }
            return ByteArray(count) { readUInt(8).toByte() }
        }

        fun requireByteAligned() {
            if ((bits - offset) % 8 != 0) {
                throw ThalovantRuntimeException(
                    "Malformed HiveMind binary frame: payload is not byte-aligned.",
                )
            }
        }

        fun readRemainingBytes(): ByteArray = readBytes((bits - offset) / 8)
    }
}
