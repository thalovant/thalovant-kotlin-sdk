package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Persistent client static key and trust-on-first-use server pins. Use an app-private
 * directory on Android; on POSIX private files are required to have mode 0600.
 * Pins are never removed automatically after an authentication failure.
 */
public class HiveMindNoiseStore(public val directory: Path = defaultDirectory()) {
    internal fun staticKey(): ByteArray = synchronized(lock) {
        prepareDirectory()
        val file = directory.resolve("noise-static.key")
        if (!Files.exists(file, NOFOLLOW_LINKS)) {
            try { writeNew(file, Noise.hex(Noise.randomKey())) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* another process created the same key */ }
        }
        readKey(file)
    }
    private fun pinFile(nodeId: String): Path = directory.resolve("noise-pin-${Noise.hex(Noise.hash(nodeId.toByteArray()))}.key")
    internal fun pin(nodeId: String): ByteArray? = synchronized(lock) {
        prepareDirectory()
        pinFile(nodeId).let { if (Files.exists(it, NOFOLLOW_LINKS)) readKey(it) else null }
    }
    internal fun verifyOrPin(nodeId: String, key: ByteArray): Unit = synchronized(lock) {
        require(key.size == 32)
        prepareDirectory()
        val file = pinFile(nodeId)
        try { writeNew(file, Noise.hex(key)) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        check(MessageDigest.isEqual(key, readKey(file))) { "Noise server key changed; verify its rotation before replacing the saved pin." }
    }
    private fun prepareDirectory() {
        if (Files.isSymbolicLink(directory)) error("Noise state directory cannot be a symbolic link.")
        if (!Files.exists(directory, NOFOLLOW_LINKS)) {
            try { Files.createDirectories(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))) }
            catch (_: UnsupportedOperationException) { Files.createDirectories(directory) }
        }
        require(Files.isDirectory(directory, NOFOLLOW_LINKS)) { "Noise state path must be a directory." }
        try {
            check(Files.getPosixFilePermissions(directory).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
                "Noise state directory must have private permissions (chmod 700)."
            }
        } catch (_: UnsupportedOperationException) { /* caller provides an app-private directory on Windows */ }
    }
    private fun writeNew(file: Path, value: String) {
        val temporary = try {
            Files.createTempFile(directory, ".noise-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        } catch (_: UnsupportedOperationException) { Files.createTempFile(directory, ".noise-", ".tmp") }
        try {
            java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(value.toByteArray())
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            // Hard-link publication is atomic and never replaces an existing key.
            // Other processes can see only the complete, durable winner. A filesystem
            // lacking hard links fails closed rather than publishing partial state.
            Files.createLink(file, temporary)
        } finally { Files.deleteIfExists(temporary) }
    }
    private fun readKey(file: Path): ByteArray {
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Noise state must be a regular file, not a symbolic link." }
        try {
            check(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
                "Noise state file must have private permissions (chmod 600)."
            }
        } catch (_: UnsupportedOperationException) { }
        require(Files.size(file) == 64L) { "Invalid stored Noise key length." }
        return Noise.unhex(Files.readString(file)).also { require(it.size == 32) }
    }
    public companion object {
        private val lock = Any()
        public fun defaultDirectory(): Path = Path.of(System.getProperty("user.home"), ".config", "thalovant-kotlin", "noise")
    }
}
