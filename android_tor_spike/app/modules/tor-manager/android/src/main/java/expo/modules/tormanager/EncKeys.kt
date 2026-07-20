package expo.modules.tormanager

import java.security.SecureRandom
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters

/** Phone-side X25519 enc-key generation + persistence (B.2 Task 3). Generates
 *  a keypair once via BouncyCastle (SecureRandom, same primitive as
 *  KotlinDmcrypt's unwrap side), persists it through the SyncStore (both
 *  InMemorySyncStore and SqliteSyncStore), and returns the same pair on every
 *  later call -- this is the enc key hearth's maintain_own_device_grants
 *  (Task 2) publishes a wrap_grant against, so the phone can decrypt its
 *  own-authored history. Plaintext at rest is the accepted posture (matches
 *  desktop) -- no Keystore wrapping. */
object EncKeys {
    /** @Synchronized (on this singleton object's monitor) serializes
     *  concurrent callers within one process -- TorManagerModule constructs a
     *  fresh SqliteSyncStore per call and dispatches on a coroutine scope, so
     *  two concurrent getOrCreate calls (each against their own SqliteSyncStore
     *  instance, but the same underlying sync_store.db) are plausible. Without
     *  this, two callers could each pass the null check, generate DIFFERENT
     *  keypairs, and interleave their setEncKey writes into a mismatched
     *  (privA, pubB) pair that then reads back as non-null forever. */
    @Synchronized
    fun getOrCreate(store: SyncStore): Pair<String, String> {
        store.getEncKey()?.let { (priv, pub) ->
            if (pubMatchesPriv(priv, pub)) return priv to pub
            // Defense in depth: a stored pair whose pub does not derive from
            // its priv is corrupt (e.g. a pre-transaction-fix race, or any
            // future write bug) -- treat it as absent and self-heal by
            // regenerating, rather than handing out a broken keypair forever.
        }
        return generateAndPersist(store)
    }

    private fun pubMatchesPriv(priv: String, pub: String): Boolean = try {
        derivePub(priv) == pub
    } catch (e: Exception) { false }   // malformed hex etc. -- treat as mismatch

    private fun derivePub(priv: String): String =
        KotlinWire.toHex(
            X25519PrivateKeyParameters(KotlinWire.fromHex(priv), 0).generatePublicKey().encoded
        )

    private fun generateAndPersist(store: SyncStore): Pair<String, String> {
        val p = X25519PrivateKeyParameters(SecureRandom())
        val priv = KotlinWire.toHex(p.encoded)
        val pub = KotlinWire.toHex(p.generatePublicKey().encoded)
        store.setEncKey(priv, pub)
        return priv to pub
    }
}
