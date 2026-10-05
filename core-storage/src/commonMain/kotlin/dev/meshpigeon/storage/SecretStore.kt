package dev.meshpigeon.storage

/**
 * The master key, and where it lives (plan D11).
 *
 * Everything private — the master key, an identity's private key, a channel PSK,
 * a device PIN — lives in the database, **sealed**: a key that never leaves this
 * store, and a value that is useless without it. A database that gets copied,
 * synced, backed up or pasted into a bug report is then worth nothing on its
 * own.
 *
 * What this owns today is the key and its storage, and nothing else:
 *
 * * **Native CLI**: a `0600` key file at `<home>/keys/master.key`, created once.
 * * **Android**: the Keystore, never a file — that platform arrives with the app.
 *
 * **The sealing cipher is not here**, deliberately. The plan chooses it in P3
 * (D6/D23) by measuring byte-parity against MeshCore C and `openhop_core`, and
 * an at-rest cipher invented before that measurement would be one nobody ever
 * checked against anything. `pin_sealed` in the schema stays null until then:
 * the column and the key store are P2's, the cipher is P3's.
 *
 * **This is not protection against an attacker who has the machine.** The key
 * file is `0600` beside the database; an attacker with both has both. The threat
 * it answers is a database that travels, which is the realistic one.
 */
public class SecretStore internal constructor(masterKey: ByteArray) {
    private val key: ByteArray = masterKey

    /** The master key: 32 bytes, stable for the life of this home. */
    public fun masterKey(): ByteArray = key.copyOf()

    /** A fresh 32-byte key, for something that has never been sealed. */
    public fun newKey(): ByteArray = newMasterKey()

    public companion object {
        /**
         * The store for [home], creating the key if there is none.
         *
         * Creating and *using* a key are separate on purpose: "sealed with a key
         * I do not have" and "no key yet" are different failures, and only the
         * second one should create one.
         */
        public fun open(home: String): SecretStore =
            SecretStore(requireKey(loadOrCreateMasterKey(home), home))
    }
}

/** Reads the key for [home], creating one if this is the first time. */
internal expect fun loadOrCreateMasterKey(home: String): ByteArray?

/** 32 bytes of randomness from this platform. */
internal expect fun newMasterKey(): ByteArray
