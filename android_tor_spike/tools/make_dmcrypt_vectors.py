"""dmcrypt vectors from real hearth (THROWAWAY keys). The Kotlin side must
UNWRAP the committed wrap to the committed content key, and DECRYPT the
committed body_ct to the committed plaintext -- using the committed aad.
ASCII-only output.

Cases 0-1 (Task 1): one post + one dm, fresh per-case enc keypair each.
Cases 2-3 (Task 5, DecryptPass): two more posts sharing case 0's enc
keypair but at earlier/later created_at, so DecryptPass tests can decrypt
>=3 messages with ONE encPrivHex and assert newest-first feed ordering.

The "responses" case (B.2d-4 Task 1) is different in kind from every case
above: it is not hand-built JSON encrypted with hearth's primitives, it is
a REAL aggregated KIND_RESPONSES record produced by driving hearth's own
node/store API end to end (compose_post, compose_response, the author's
process_responses sweep) -- see _build_responses_case below."""
import json, sys, tempfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from hearth.dmcrypt import new_content_key, encrypt_body, wrap_key, post_aad, dm_aad
from hearth.identity import _gen_x25519_pair
from hearth.node import HearthNode
FIXTURE = Path(__file__).resolve().parents[1] / "fixtures" / "dmcrypt_vectors.json"


def _sync(a, b):
    """Hand-carry every message `a` holds (authored by `a`) that `b`
    hasn't ingested yet -- the same no-real-sockets idiom
    tests/test_responses.py uses (mirrors one direction of a real gossip
    round)."""
    for m in a.store.messages_not_in({}, {a.identity_pub}, b.identity_pub):
        b.store.ingest_message(m)


def _befriend(a, b):
    a.store.add_identity(b.identity_pub)
    b.store.add_identity(a.identity_pub)
    a.ensure_enckey()
    b.ensure_enckey()
    _sync(a, b)          # b learns a's enckey
    _sync(b, a)          # a learns b's enckey


def _build_responses_case():
    """Drive real hearth to produce a genuine KIND_RESPONSES record: an
    author node composes a journal post; one responder node has
    public_engagement on and PUBLICLY comments (so its entry carries a
    real identity/device_pub/responder_sig in the clear); a second
    responder PRIVATELY reacts (default: sealed behind a mutual box, no
    identity in the clear). The responses reach the author, whose
    process_responses() sweep folds them and republishes the aggregated,
    re-encrypted KIND_RESPONSES record -- the exact same fold path
    tests/test_responses.py's test_author_sweep_republishes_record pins.
    The decrypting party is the PRIVATE reactor's own device: a genuine
    scope ("kreds") recipient of the record, not the author re-reading
    their own post, proving the record is readable by its real audience."""
    with tempfile.TemporaryDirectory() as td:
        tdp = Path(td)
        author = HearthNode.create(tdp / "author", "Author", "author-dev")
        commenter = HearthNode.create(tdp / "commenter", "Commenter",
                                      "commenter-dev")
        reactor = HearthNode.create(tdp / "reactor", "Reactor", "reactor-dev")
        try:
            _befriend(author, commenter)
            _befriend(author, reactor)

            pid = author.compose_post("hello from the vector generator", "kreds")
            _sync(author, commenter)
            _sync(author, reactor)

            commenter.store.set_meta("public_engagement", "1")
            commenter.compose_response(pid, "comment", "nice post!")
            reactor.compose_response(pid, "reaction", "heart")

            _sync(commenter, author)         # raw responses reach the author
            _sync(reactor, author)
            rebuilt = author.process_responses()  # folds + republishes the record
            assert rebuilt == 1, "expected exactly one target rebuilt"

            rec = author.store.responses_record(pid, author.identity_pub)
            assert rec is not None, "author sweep produced no KIND_RESPONSES record"
            key, aad = author._content_key(rec)
            assert key is not None, "author could not decrypt its own record"
            from hearth.dmcrypt import decrypt_body
            body = decrypt_body(key, rec.payload["body_nonce"],
                                rec.payload["body_ct"], aad)
            entries = body["entries"]
            public_entry = next(e for e in entries if e["rkind"] == "comment")
            private_entry = next(e for e in entries if e["rkind"] == "reaction")
            assert public_entry.get("identity") == commenter.identity_pub
            assert public_entry.get("device_pub") == commenter.device.device_pub
            assert "identity" not in private_entry     # private stays sealed

            wraps = rec.payload["wraps"]
            reactor_wrap = wraps[reactor.device.device_pub]

            result = {
                "kind": "responses", "author": author.identity_pub,
                "target": pid, "created_at": rec.payload["created_at"],
                "enc_priv": reactor.device.enc_priv, "wrap": reactor_wrap,
                "body_nonce": rec.payload["body_nonce"],
                "body_ct": rec.payload["body_ct"],
                "content_key": key.hex(), "entries": entries,
                "public_responder_device": public_entry["device_pub"],
                "public_responder_identity": public_entry["identity"],
                "public_responder_sig": public_entry["responder_sig"],
            }
        finally:
            # Close every sqlite handle before the TemporaryDirectory
            # context tries to rmtree itself -- Windows refuses to
            # delete a file a still-open handle in THIS SAME process
            # holds (unlike POSIX, which allows unlinking an open file).
            author.store.close()
            commenter.store.close()
            reactor.store.close()
        return result


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
    # Two more posts (Task 5, B.2 DecryptPass): SAME recipient enc keypair
    # (enc_priv/enc_pub) as the first case above but DISTINCT created_at
    # timestamps. The two cases above intentionally use fresh, per-case enc
    # keypairs and share an IDENTICAL created_at -- neither property alone
    # lets a single Kotlin-side encPrivHex decrypt >=2 messages with
    # different timestamps, which is exactly what a feed-ordering
    # ("newest first") test needs.
    created_earlier = created_at - 100.0
    aad3 = post_aad(author, "kreds", created_earlier)
    key3 = new_content_key()
    body3 = {"kind": "post", "text": "older post for ordering", "blobs": []}
    n3, c3 = encrypt_body(key3, body3, aad3)
    w3 = wrap_key(key3, {"dev1": enc_pub}, aad3)
    cases.append({
        "kind": "post", "author": author, "scope": "kreds",
        "created_at": created_earlier, "enc_priv": enc_priv,
        "wrap": w3["dev1"], "body_nonce": n3, "body_ct": c3,
        "content_key": key3.hex(), "plaintext": body3,
    })
    created_later = created_at + 100.0
    aad4 = post_aad(author, "kreds", created_later)
    key4 = new_content_key()
    body4 = {"kind": "post", "text": "newer post for ordering", "blobs": []}
    n4, c4 = encrypt_body(key4, body4, aad4)
    w4 = wrap_key(key4, {"dev1": enc_pub}, aad4)
    cases.append({
        "kind": "post", "author": author, "scope": "kreds",
        "created_at": created_later, "enc_priv": enc_priv,
        "wrap": w4["dev1"], "body_nonce": n4, "body_ct": c4,
        "content_key": key4.hex(), "plaintext": body4,
    })
    # A post whose BODY carries real blob hash refs, with THUMBS living
    # only in the OUTER (never-encrypted) payload (Task 4, B.2d
    # DecryptPass): mirrors node.py's compose_post exactly --
    # encrypt_body(key, {"text": text, "blobs": refs}, aad) never includes
    # "thumbs" in the body; thumbs rides in the outer SIGNED payload only
    # (make_post's `thumbs` param, same plaintext-envelope-metadata class as
    # poster/codec). This case proves DecryptPass's body-blobs / payload-
    # thumbs split against REAL hearth-shaped ciphertext, not hand-rolled
    # JSON. "thumbs" is a top-level case field (not part of "plaintext",
    # since it is deliberately NOT in the encrypted body) -- a null entry
    # mirrors a failed thumbnail generation (node.py's
    # `thumbs.append(None)`).
    enc_priv5, enc_pub5 = _gen_x25519_pair()
    created_5 = created_at + 5.0
    aad5 = post_aad(author, "kreds", created_5)
    key5 = new_content_key()
    blob_refs5 = ["ab" * 32, "cd" * 32]
    body5 = {"kind": "post", "text": "post with photos", "blobs": blob_refs5}
    n5, c5 = encrypt_body(key5, body5, aad5)
    w5 = wrap_key(key5, {"dev1": enc_pub5}, aad5)
    cases.append({
        "kind": "post", "author": author, "scope": "kreds",
        "created_at": created_5, "enc_priv": enc_priv5,
        "wrap": w5["dev1"], "body_nonce": n5, "body_ct": c5,
        "content_key": key5.hex(), "plaintext": body5,
        "thumbs": ["ef" * 32, None],
    })
    # a blob encrypted with a content key (BLOB_AAD, no per-message aad)
    from hearth.dmcrypt import encrypt_blob
    bkey = new_content_key()
    blob_plain = b"\x89PNG\r\n\x1a\n" + b"kreds-blob-vector-bytes" * 4
    blob_cipher = encrypt_blob(bkey, blob_plain)
    cases.append({
        "kind": "blob", "content_key": bkey.hex(),
        "cipher": blob_cipher.hex(), "plain": blob_plain.hex(),
    })
    # A real, hearth-aggregated KIND_RESPONSES record (B.2d-4 Task 1):
    # unlike every case above, this is produced by driving hearth's
    # actual node/store API (compose_post/compose_response/
    # process_responses), not by hand-encrypting JSON.
    cases.append(_build_responses_case())
    return {"cases": cases}


def main():
    FIXTURE.write_text(json.dumps(build(), indent=2) + "\n", encoding="utf-8")
    print("wrote", FIXTURE)


if __name__ == "__main__":
    main()
