import json, time
import pytest
from hearth.node import HearthNode

def _pair(tmp_path):
    a = HearthNode.create(tmp_path / "a", "A", "a-dev")
    b = HearthNode.create(tmp_path / "b", "B", "b-dev")
    a.store.set_meta("gossip_addr", "127.0.0.1:7101")
    b.store.set_meta("gossip_addr", "127.0.0.1:7103")
    return a, b

def test_invite_carries_expiry(tmp_path):
    a, _ = _pair(tmp_path)
    inv = json.loads(a.create_invite(ttl_seconds=600))
    assert inv["expires_at"] > time.time()

def test_expired_invite_rejected_at_finalize(tmp_path):
    a, b = _pair(tmp_path)
    inv = a.create_invite(ttl_seconds=0)          # already expired
    resp = b.respond_to_invite(inv)
    with pytest.raises(ValueError):
        a.finalize_invite(resp)
    assert a.store.is_known(b.identity_pub) is False

def test_single_active_new_code_kills_old(tmp_path):
    a, b = _pair(tmp_path)
    inv1 = a.create_invite()
    a.create_invite()                              # a new code invalidates inv1
    resp = b.respond_to_invite(inv1)
    with pytest.raises(ValueError):
        a.finalize_invite(resp)

def test_valid_invite_still_completes(tmp_path):
    a, b = _pair(tmp_path)
    inv = a.create_invite()
    a.finalize_invite(b.respond_to_invite(inv))
    assert a.store.is_known(b.identity_pub)
