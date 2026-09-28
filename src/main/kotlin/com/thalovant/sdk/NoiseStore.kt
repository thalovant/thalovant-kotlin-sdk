package com.thalovant.sdk

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

/**
 * Persistent client static key and trust-on-first-use server pins. Use an app-private
 * directory on Android; on POSIX private files are required to have mode 0600.
 * Pins are never removed automatically after an authentication failure.
 *
 * A hub pins one client key per connection, so every program that uses one
 * identity must present the same key. [forIdentity] keeps an identity read
 * from a file in a folder beside that file, and a client built with no store
 * of its own uses it.
 */
public class HiveMindNoiseStore(public val directory: Path = defaultDirectory()) {
    /** Where another program using the same identity likely keeps its key, for [ThalovantClientKeyRejectedException]. */
    internal var otherFolder: Path? = null

    /**
     * A folder this store's key used to live in, adopted on first use: see
     * [forIdentity]. Cleared once looked at.
     */
    private var adoptFrom: Path? = null

    internal fun staticKey(): ByteArray = synchronized(lock) {
        prepareDirectory()
        val file = directory.resolve(STATIC_KEY)
        if (!Files.exists(file, NOFOLLOW_LINKS)) {
            try { writeNew(file, Noise.hex(Noise.randomKey())) }
            catch (_: java.nio.file.FileAlreadyExistsException) { /* another process created the same key */ }
        }
        readKey(file)
    }
    private fun pinFile(nodeId: String): Path = directory.resolve("noise-pin-${Noise.hex(Noise.hash(nodeId.toByteArray()))}.key")
    internal fun pin(nodeId: String): ByteArray? = synchronized(lock) {
        prepareDirectory()
        adoptOnce(nodeId)
        pinFile(nodeId).let { if (Files.exists(it, NOFOLLOW_LINKS)) readKey(it) else null }
    }

    /**
     * The first time this folder is used for a hub, take over the key and pins
     * [adoptFrom] holds -- copied, never moved, and only when that key has met
     * this hub ([nodeId] is pinned there) and this folder has no key yet -- so
     * a device that used to keep its key in the shared default gets no new key
     * and is not locked out.
     */
    private fun adoptOnce(nodeId: String) {
        val legacy = adoptFrom ?: return
        adoptFrom = null
        val key = directory.resolve(STATIC_KEY)
        if (Files.exists(key, NOFOLLOW_LINKS) || legacy == directory) return
        try {
            val old = HiveMindNoiseStore(legacy)
            if (!Files.exists(legacy.resolve(STATIC_KEY), NOFOLLOW_LINKS) || !Files.exists(old.pinFile(nodeId), NOFOLLOW_LINKS)) return
            writeNew(key, Noise.hex(old.readKey(legacy.resolve(STATIC_KEY))))
            Files.newDirectoryStream(legacy, "noise-pin-*.key").use { pins ->
                for (pin in pins) {
                    val target = directory.resolve(pin.fileName.toString())
                    if (!Files.exists(target, NOFOLLOW_LINKS)) {
                        try { writeNew(target, Noise.hex(old.readKey(pin))) } catch (_: java.nio.file.FileAlreadyExistsException) { }
                    }
                }
            }
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            // Another process adopted it first.
        } catch (_: Exception) {
            // An unreadable old folder is no reason to fail: this one starts afresh.
        }
    }
    internal fun verifyOrPin(nodeId: String, key: ByteArray): Unit = synchronized(lock) {
        require(key.size == 32)
        prepareDirectory()
        val file = pinFile(nodeId)
        try { writeNew(file, Noise.hex(key)) } catch (_: java.nio.file.FileAlreadyExistsException) { }
        // Typed, not `check`: an IllegalStateException here is
        // indistinguishable from every other one a caller catches, and the one
        // an app reaches for first is "this client is not paired" -- so a hub
        // that had simply been rebuilt was reported to people as a phone that
        // had never been set up. See [ThalovantHubIdentityChangedException].
        if (!MessageDigest.isEqual(key, readKey(file))) {
            throw ThalovantHubIdentityChangedException(
                "Noise server key changed; verify its rotation before replacing the saved pin.",
            )
        }
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
            publish(temporary, file)
        } finally { Files.deleteIfExists(temporary) }
    }
    /**
     * Put the finished temporary file in place under its real name.
     *
     * Hard-link publication is preferred: it is atomic, and it never replaces
     * an existing key, so concurrent writers cannot destroy each other's and
     * other processes see only the complete, durable winner.
     *
     * **Android refuses it.** `link(2)` is not permitted on app-private
     * storage, so `createLink` raises `AccessDeniedException` and this store
     * -- which failed closed rather than publish partial state -- made the
     * whole v3 WSS transport unusable on a phone. Measured on an emulator on
     * 2026-09-16: the temporary file is created, written and fsynced, and
     * only the link is refused. The class note above already said to pass an
     * app-private directory on Android; that is necessary and was not
     * sufficient.
     *
     * So where linking is refused, fall back to a plain move, which is
     * specified to fail when the target already exists -- the same answer
     * createLink gives, and the same `FileAlreadyExistsException` both
     * callers already read as "somebody got there first" before re-reading
     * the winner.
     *
     * Deliberately *not* `ATOMIC_MOVE`: that is a rename, and a rename
     * replaces, which is the one property the link was chosen for. Holding
     * it off with an `exists()` check first only narrows the window and
     * still lets a racing writer clobber a key somebody else published. The
     * check belongs to the platform, which can make it against the real
     * directory; this cannot.
     */
    private fun publish(temporary: Path, file: Path) {
        try {
            Files.createLink(file, temporary)
            return
        } catch (refused: Exception) {
            if (refused !is UnsupportedOperationException && refused !is java.nio.file.AccessDeniedException) {
                throw refused
            }
        }
        // No ATOMIC_MOVE, and no existence check of our own.
        //
        // ATOMIC_MOVE is a rename, and a rename replaces -- which is the one
        // property createLink was chosen for. Guarding it with an exists()
        // first only narrows that window, and leaves the racing writer to
        // clobber a key somebody else had already published.
        //
        // A plain move is specified to fail when the target exists, which is
        // exactly what createLink does, raising the same
        // FileAlreadyExistsException that both callers already read as
        // "somebody got there first" before re-reading the winner. The check
        // belongs to the platform, which can make it against the real
        // directory, rather than to this, which cannot.
        Files.move(temporary, file)
    }

    private fun readKey(file: Path): ByteArray {
        require(Files.isRegularFile(file, NOFOLLOW_LINKS)) { "Noise state must be a regular file, not a symbolic link." }
        try {
            check(Files.getPosixFilePermissions(file, NOFOLLOW_LINKS).none { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) {
                "Noise state file must have private permissions (chmod 600)."
            }
        } catch (_: UnsupportedOperationException) { }
        require(Files.size(file) == 64L) { "Invalid stored Noise key length." }
        // Not Files.readString: that is Java 11 and Android's core-oj does
        // not carry it, so it raises NoSuchMethodError -- on the socket
        // callback, on the main thread, killing the app outright the moment a
        // hub answered. readAllBytes has been there since java.nio.file
        // arrived on Android. Measured on an emulator, 2026-09-16.
        return Noise.unhex(String(Files.readAllBytes(file), Charsets.UTF_8)).also { require(it.size == 32) }
    }
    public companion object {
        private val lock = Any()
        private const val STATIC_KEY = "noise-static.key"

        /** The folder beside an identity file its key is kept in. */
        public const val IDENTITY_KEY_FOLDER: String = "thalovant-kotlin-noise"

        /**
         * The store a client uses for [identity] when it names none.
         *
         * An identity read from a file keeps its key in [IDENTITY_KEY_FOLDER]
         * beside that file, so two programs reading one file present one key --
         * a hub pins one per connection, and refuses any other. The first time
         * that folder is used, the key and hub pins this SDK kept in
         * [defaultDirectory] before are copied into it, when that key has met
         * the hub. Any other identity keeps [defaultDirectory]. On Android, pass
         * a store in the app's private storage.
         */
        public fun forIdentity(identity: ThalovantIdentity): HiveMindNoiseStore {
            val file = identity.sourcePath ?: return HiveMindNoiseStore()
            val parent = file.parent ?: return HiveMindNoiseStore()
            val legacy = defaultDirectory()
            return HiveMindNoiseStore(parent.resolve(IDENTITY_KEY_FOLDER)).also {
                it.otherFolder = legacy
                it.adoptFrom = legacy
            }
        }

        // Paths.get rather than Path.of for the same reason readKey avoids
        // readString: the Java 11 spelling is not on every Android this
        // supports, and the old one means exactly the same thing.
        public fun defaultDirectory(): Path =
            Paths.get(System.getProperty("user.home"), ".config", "thalovant-kotlin", "noise")
    }
}
