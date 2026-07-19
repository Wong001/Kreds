package expo.modules.tormanager

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EncKeysTest {
    @Test fun storeRoundTrip() {
        val s = InMemorySyncStore()
        assertNull(s.getEncKey())                     // nothing persisted yet
        s.setEncKey("aa".repeat(32), "bb".repeat(32))
        assertEquals("aa".repeat(32) to "bb".repeat(32), s.getEncKey())
    }

    @Test fun getOrCreateGeneratesAndPersists() {
        val s = InMemorySyncStore()
        assertNull(s.getEncKey())                     // RED-proving precondition
        val (priv, pub) = EncKeys.getOrCreate(s)
        assertEquals(s.getEncKey(), priv to pub)       // persisted via setEncKey
    }

    @Test fun getOrCreateReturnsSameOnSecondCall() {
        val s = InMemorySyncStore()
        val first = EncKeys.getOrCreate(s)
        val second = EncKeys.getOrCreate(s)             // must not regenerate
        assertEquals(first, second)
    }

    @Test fun pubDerivesFromPriv() {
        val s = InMemorySyncStore()
        val (priv, pub) = EncKeys.getOrCreate(s)
        // re-derive the public key from the returned private key and confirm
        // it matches -- proves `pub` is X25519's actual derived public key,
        // not an independently-generated value.
        val rederived = KotlinWire.toHex(
            X25519PrivateKeyParameters(KotlinWire.fromHex(priv), 0).generatePublicKey().encoded
        )
        assertEquals(rederived, pub)
    }

    @Test fun getOrCreateSelfHealsMismatchedStoredPair() {
        val s = InMemorySyncStore()
        // A deliberately corrupt pair -- ff-repeat is not the real X25519
        // public key for aa-repeat's private scalar (this is what a
        // concurrent-write race could produce pre-fix: privA persisted with
        // pubB from a different generation). getOrCreate must not trust it.
        val badPriv = "aa".repeat(32); val badPub = "ff".repeat(32)
        s.setEncKey(badPriv, badPub)
        val (priv, pub) = EncKeys.getOrCreate(s)
        assertNotEquals(badPriv to badPub, priv to pub)      // corrupt pair replaced
        val rederived = KotlinWire.toHex(
            X25519PrivateKeyParameters(KotlinWire.fromHex(priv), 0).generatePublicKey().encoded
        )
        assertEquals(rederived, pub)                          // new pair is internally consistent
        assertEquals(s.getEncKey(), priv to pub)              // healed pair was persisted
    }
}
