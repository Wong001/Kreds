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
    fun getOrCreate(store: SyncStore): Pair<String, String> {
        store.getEncKey()?.let { return it }
        val p = X25519PrivateKeyParameters(SecureRandom())
        val priv = KotlinWire.toHex(p.encoded)
        val pub = KotlinWire.toHex(p.generatePublicKey().encoded)
        store.setEncKey(priv, pub)
        return priv to pub
    }
}
