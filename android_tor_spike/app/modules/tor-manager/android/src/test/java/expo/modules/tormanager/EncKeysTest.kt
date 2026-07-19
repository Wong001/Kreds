package expo.modules.tormanager

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.junit.Assert.assertEquals
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
}
