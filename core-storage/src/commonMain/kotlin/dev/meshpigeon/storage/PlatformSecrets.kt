package dev.meshpigeon.storage

/**
 * The parts of the key store that are the same everywhere: where the key file
 * is, how long the key is, and the refusal when the file is not a key.
 */
internal const val KEY_FILE_NAME = "keys/master.key"

/** AES-256 is 32 bytes, and so is everything that will be sealed with this. */
internal const val MASTER_KEY_BYTES = 32

/** A key file larger than this is not a key file, whatever it claims to be. */
internal const val MAX_KEY_FILE_BYTES = 64

internal fun keyFileFor(home: String): String = "$home/$KEY_FILE_NAME"

internal fun keyFileRefusal(home: String, reason: String): String =
    "$home's master key cannot be used: $reason"

/**
 * Whether [home] already has a master key.
 *
 * Asking must not create one: "is there a key yet" and "make me a key" are
 * different questions, and a `/db` that answered the first by doing the second
 * would report a key it had just made.
 */
internal expect fun masterKeyExists(home: String): Boolean

/**
 * Creates [path] and every directory above it, if they are not there.
 *
 * A first run has no `~/.local/share/meshpigeon` yet, and a database that
 * refused to open because its own directory was missing would be a strange
 * first impression. One level of `mkdir` is not enough — `home` can be several
 * deep — so this walks down from the root the way `mkdir -p` does.
 */
internal expect fun ensureDirectory(path: String)

/** 32 bytes of key, or a refusal that says which file was at fault. */
internal fun requireKey(bytes: ByteArray?, home: String): ByteArray = when {
    bytes == null -> throw IllegalStateException(keyFileRefusal(home, "there is no key at ${keyFileFor(home)}"))
    bytes.size != MASTER_KEY_BYTES ->
        throw IllegalStateException(keyFileRefusal(home, "the key at ${keyFileFor(home)} is ${bytes.size} bytes, not $MASTER_KEY_BYTES"))
    else -> bytes
}