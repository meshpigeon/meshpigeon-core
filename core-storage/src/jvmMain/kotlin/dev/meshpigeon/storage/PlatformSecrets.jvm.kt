package dev.meshpigeon.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/**
 * The key store, on the JVM.
 *
 * `SecureRandom` rather than `/dev/urandom`, because the JVM is also what
 * Android runs — where the real answer is the Keystore, and this is the
 * fallback that keeps a desktop session honest until that arrives.
 */
internal actual fun loadOrCreateMasterKey(home: String): ByteArray? {
    val file = File(keyFileFor(home))
    // A file that exists but is the wrong length comes back as itself: "no key
    // yet" and "the key is broken" are different failures, and only the first one
    // may create a key.
    if (file.isFile) return file.readBytes()

    val fresh = newMasterKey()
    file.parentFile?.mkdirs()
    Files.write(file.toPath(), fresh, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
    // 0600 through the attributes rather than a umask: a shared machine must not
    // decide who may read the key.
    runCatching { Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------")) }
    return fresh
}

internal actual fun newMasterKey(): ByteArray = ByteArray(MASTER_KEY_BYTES).also { SecureRandom().nextBytes(it) }
