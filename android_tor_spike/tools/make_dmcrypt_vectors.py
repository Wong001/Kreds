"""dmcrypt vectors from real hearth (THROWAWAY keys). The Kotlin side must
UNWRAP the committed wrap to the committed content key, and DECRYPT the
committed body_ct to the committed plaintext -- using the committed aad.
ASCII-only output."""
import json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hearth.dmcrypt import new_content_key, encrypt_body, wrap_key, post_aad, dm_aad
from hearth.identity import _gen_x25519_pair
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "dmcrypt_vectors.json"


def build():
    cases = []
    # a POST body wrapped to a device with a fresh enc keypair
    enc_priv, enc_pub = _gen_x25519_pair()
    author = "11" * 32
    created_at = 1752900000.123456
    aad = post_aad(author, "kreds", created_at)
    key = new_content_key()
    body = {"kind": "post", "text": "hello from the desk", "blobs": []}
    nonce_hex, ct_hex = encrypt_body(key, body, aad)
    wraps = wrap_key(key, {"dev1": enc_pub}, aad)
    cases.append({
        "kind": "post", "author": author, "scope": "kreds",
        "created_at": created_at, "enc_priv": enc_priv,
        "wrap": wraps["dev1"], "body_nonce": nonce_hex, "body_ct": ct_hex,
        "content_key": key.hex(), "plaintext": body,
    })
    # a DM body
    enc_priv2, enc_pub2 = _gen_x25519_pair()
    to = "22" * 32
    aad2 = dm_aad(author, to, created_at)
    key2 = new_content_key()
    body2 = {"kind": "dm", "text": "secret dm", "to": to}
    n2, c2 = encrypt_body(key2, body2, aad2)
    w2 = wrap_key(key2, {"dev2": enc_pub2}, aad2)
    cases.append({
        "kind": "dm", "author": author, "to": to, "created_at": created_at,
        "enc_priv": enc_priv2, "wrap": w2["dev2"], "body_nonce": n2,
        "body_ct": c2, "content_key": key2.hex(), "plaintext": body2,
    })
    return {"cases": cases}


def main():
    FIXTURE.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print("wrote", FIXTURE)


if __name__ == "__main__":
    main()
