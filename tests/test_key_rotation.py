from hearth.identity import DeviceKeys, ENC_GRACE


def test_rotate_enc_retires_current_and_generates_fresh():
    d = DeviceKeys.create("phone")
    old_priv, old_pub = d.enc_priv, d.enc_pub
    d.rotate_enc(now=1000.0)
    assert (d.enc_priv, d.enc_pub) != (old_priv, old_pub)
    assert d.retired_enc == [{"enc_priv": old_priv, "enc_pub": old_pub,
                              "retired_at": 1000.0}]


def test_prune_retired_deletes_only_past_grace():
    d = DeviceKeys.create("phone")
    d.rotate_enc(now=0.0)                    # retired at t=0
    d.rotate_enc(now=ENC_GRACE)              # retired at t=GRACE
    # t=0 entry is exactly at the boundary (age == GRACE, kept)
    assert len(d.retired_enc) == 2
    assert d.prune_retired(now=ENC_GRACE + 1.0) is True
    assert [r["retired_at"] for r in d.retired_enc] == [ENC_GRACE]
    assert d.prune_retired(now=ENC_GRACE + 1.0) is False   # idempotent


def test_enc_privs_current_first_then_retired():
    d = DeviceKeys.create("phone")
    first = d.enc_priv
    d.rotate_enc(now=1.0)
    second = d.enc_priv
    d.rotate_enc(now=2.0)
    assert d.enc_privs() == [d.enc_priv, first, second]


def test_keys_json_roundtrip_and_legacy_load():
    d = DeviceKeys.create("phone")
    d.rotate_enc(now=5.0)
    j = d.to_json()
    d2 = DeviceKeys.from_json(j)
    assert d2.retired_enc == d.retired_enc
    assert d2.storage_key == d.storage_key
    # legacy keys.json (v0.1, no rotation fields) still loads and gains
    # fresh defaults
    legacy = {k: v for k, v in j.items()
              if k not in ("retired_enc", "storage_key")}
    d3 = DeviceKeys.from_json(legacy)
    assert d3.retired_enc == []
    assert isinstance(d3.storage_key, str) and len(d3.storage_key) == 64
