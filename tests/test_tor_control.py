from hearth.tor import _parse_control_reply


def test_parse_add_onion_reply_extracts_service_id_and_key():
    # Shape of a real ADD_ONION 250 reply (multi-line, dot-terminated)
    lines = [
        "250-ServiceID=6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad",
        "250-PrivateKey=ED25519-V3:aGVsbG8gd29ybGQgZmFrZSBrZXkgYmxvYg==",
        "250 OK",
    ]
    fields = _parse_control_reply(lines)
    assert fields["ServiceID"] == \
        "6kmaeg5eq7ivhgndfvj7dgoewiok4ujn3e2mly5kb5kglsjhx4kyhyad"
    assert fields["PrivateKey"] == \
        "ED25519-V3:aGVsbG8gd29ybGQgZmFrZSBrZXkgYmxvYg=="


def test_parse_control_reply_ignores_non_kv_lines():
    assert _parse_control_reply(["250 OK"]) == {}
