"""API layer for App-lock: 423 locked-guard, unlock/lock/setup/settings/
change/disable endpoints, throttle, and node-side auto-lock (idle+sleep)."""
import json
import time

from fastapi.testclient import TestClient

from hearth import applock
from hearth.api import build_app
from hearth.node import HearthNode


def _fresh(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")


def _locked(tmp_path, credential="1234", cred_type="pin"):
    """A node with App-lock enabled and then immediately locked (in-memory
    drop of secrets -- no reboot needed, mirrors test_applock_node.py's
    test_lock_drops_keys)."""
    n = _fresh(tmp_path)
    n.enable_applock(credential, cred_type)
    n.lock()
    return n


# -- locked guard -------------------------------------------------------------

def test_applock_status_readable_while_locked(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    r = c.get("/api/applock")
    assert r.status_code == 200
    body = r.json()
    assert body["enabled"] is True
    assert body["locked"] is True
    assert body["cred_type"] == "pin"
    assert body["settings"] == {"idle_minutes": 0, "lock_on_sleep": True}
    assert body["throttle_wait"] == 0


def test_content_routes_423_while_locked(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    assert c.get("/api/state").status_code == 423
    assert c.get(f"/api/profile/{n.identity_pub}").status_code == 423
    assert c.get("/api/feed").status_code == 423
    assert c.get("/api/kreds").status_code == 423
    assert c.post("/api/post", data={"text": "hi"}).status_code == 423


def test_allowlist_is_exact_path_not_prefix(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    # GET /api/applock (status) is allowlisted...
    assert c.get("/api/applock").status_code == 200
    # ...but /api/applock/settings is a DIFFERENT path -- still gated.
    r = c.post("/api/applock/settings",
               json={"idle_minutes": 5, "lock_on_sleep": True})
    assert r.status_code == 423


def test_bootstrap_status_ok_while_locked(tmp_path):
    # whole-branch review, MINOR #4: pin the allowlist entry - the client's
    # boot() (hearth/web/app.js) probes GET /api/bootstrap before it even
    # checks App-lock status, so it must never 423 on a locked node.
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    assert c.get("/api/bootstrap").status_code == 200


# -- unlock / lock ------------------------------------------------------------

def test_unlock_success_then_content_ok(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/unlock", json={"credential": "1234"})
    assert r.status_code == 200
    assert n.locked is False
    r2 = c.get("/api/state")
    assert r2.status_code == 200
    assert r2.json()["profile_name"] == "Wong"


def test_wrong_credential_401_with_throttle_wait(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/unlock", json={"credential": "0000"})
    assert r.status_code == 401
    assert r.json()["throttle_wait"] == 0
    assert n.locked is True


def test_throttle_escalates_then_blocks_before_next_allowed(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    waits = []
    for _ in range(3):
        r = c.post("/api/unlock", json={"credential": "0000"})
        assert r.status_code == 401
        waits.append(r.json()["throttle_wait"])
    assert waits[0] == 0 and waits[1] == 0
    assert waits[2] > 0                      # 3rd fail crosses >=3 -> 5s tier
    # A retry made immediately (inside the throttle window) is rejected
    # WITHOUT even checking the credential -- no additional fail recorded,
    # and even the RIGHT credential does not get through.
    r4 = c.post("/api/unlock", json={"credential": "1234"})
    assert r4.status_code == 401
    assert r4.json()["throttle_wait"] > 0
    assert n.locked is True


def test_throttle_escalation_tiers(tmp_path):
    # Deterministic, no real sleeping: drive the in-memory counters directly.
    n = _locked(tmp_path)
    for _ in range(3):
        n._throttle_fail()
    assert 0 < n.throttle_wait() <= 5
    for _ in range(2):
        n._throttle_fail()                   # fail_count now 5
    assert 5 < n.throttle_wait() <= 30
    for _ in range(3):
        n._throttle_fail()                   # fail_count now 8
    assert 30 < n.throttle_wait() <= 300


def test_success_resets_throttle(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    for _ in range(3):
        c.post("/api/unlock", json={"credential": "0000"})
    assert n.throttle_wait() > 0
    n._unlock_next_allowed = 0.0             # elapse the wait window
    r = c.post("/api/unlock", json={"credential": "1234"})
    assert r.status_code == 200
    assert n.throttle_wait() == 0
    assert n._unlock_fail_count == 0


def test_lock_relocks(tmp_path):
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/unlock", json={"credential": "1234"})
    assert c.get("/api/state").status_code == 200
    r = c.post("/api/lock")
    assert r.status_code == 200
    assert n.locked is True
    assert c.get("/api/state").status_code == 423


def test_lock_on_non_applock_node_400(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/lock")
    assert r.status_code == 400


def test_unlock_on_non_applock_node_400(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/unlock", json={"credential": "whatever"})
    assert r.status_code == 400


# -- setup / settings / change / disable --------------------------------------

def test_setup_on_fresh_unlocked_node(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/applock/setup",
               json={"credential": "9999", "cred_type": "pin"})
    assert r.status_code == 200
    assert n.applock_enabled is True
    assert n.locked is False                 # enabling never locks
    status = c.get("/api/applock").json()
    assert status["enabled"] is True and status["locked"] is False
    assert status["cred_type"] == "pin"


def test_setup_blocked_while_locked_423(tmp_path):
    # Setup requires an unlocked node with keys in memory -- the locked
    # guard already refuses this route (not in the allowlist) before the
    # handler's own node.locked check would ever run.
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/applock/setup",
              json={"credential": "9999", "cred_type": "pin"})
    assert r.status_code == 423


def test_setup_twice_400(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "9999", "cred_type": "pin"})
    r = c.post("/api/applock/setup",
              json={"credential": "8888", "cred_type": "pin"})
    assert r.status_code == 400


def test_settings_persist_into_applock_json(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "9999", "cred_type": "pin"})
    r = c.post("/api/applock/settings",
              json={"idle_minutes": 10, "lock_on_sleep": False})
    assert r.status_code == 200
    status = c.get("/api/applock").json()
    assert status["settings"] == {"idle_minutes": 10, "lock_on_sleep": False}
    on_disk = json.loads((n.data_dir / "applock.json").read_text())
    assert on_disk["settings"] == {"idle_minutes": 10, "lock_on_sleep": False}


def test_change_credential_flow(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "1111", "cred_type": "pin"})
    r = c.post("/api/applock/change", json={"old": "1111", "new": "2222"})
    assert r.status_code == 200
    n.lock()
    assert c.post("/api/unlock", json={"credential": "1111"}).status_code == 401
    assert n.locked is True
    r2 = c.post("/api/unlock", json={"credential": "2222"})
    assert r2.status_code == 200
    assert n.locked is False


def test_change_credential_wrong_old_401(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "1111", "cred_type": "pin"})
    r = c.post("/api/applock/change", json={"old": "wrong", "new": "2222"})
    assert r.status_code == 401


def test_disable_restores_plaintext_keys(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "3333", "cred_type": "pin"})
    assert (n.data_dir / "applock.json").exists()
    r = c.post("/api/applock/disable", json={"credential": "3333"})
    assert r.status_code == 200
    assert n.applock_enabled is False
    assert not (n.data_dir / "applock.json").exists()
    raw = json.loads((n.data_dir / "keys.json").read_text())
    assert raw.get("applock") is not True
    for f in ("device_priv", "identity_priv", "enc_priv", "storage_key"):
        assert raw.get(f)                    # plaintext secret restored
    assert c.get("/api/state").status_code == 200   # still works post-disable


def test_disable_wrong_credential_401(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "3333", "cred_type": "pin"})
    r = c.post("/api/applock/disable", json={"credential": "wrong"})
    assert r.status_code == 401
    assert n.applock_enabled is True
    assert (n.data_dir / "applock.json").exists()


# -- non-applock node unaffected -----------------------------------------------

def test_non_applock_node_routes_normal(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    assert n.locked is False
    assert c.get("/api/state").status_code == 200
    assert c.get("/api/feed").status_code == 200
    status = c.get("/api/applock").json()
    assert status == {"enabled": False, "locked": False, "cred_type": None,
                      "settings": {"idle_minutes": 0, "lock_on_sleep": True},
                      "throttle_wait": 0}
    r = c.post("/api/post", data={"text": "hello", "scope": "kreds"})
    assert r.status_code == 200


# -- node-side auto-lock (idle + sleep) ----------------------------------------

def test_idle_autolock_locks_after_timeout(tmp_path):
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    n.update_applock_settings(idle_minutes=1, lock_on_sleep=False)
    n.last_activity = time.time() - 61        # idle past 1 minute
    n._last_tick = time.time()                # no sleep-gap
    n.maybe_autolock(interval=3.0)
    assert n.locked is True


def test_idle_autolock_off_when_zero(tmp_path):
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    assert n.applock_status()["settings"]["idle_minutes"] == 0
    n.last_activity = time.time() - 10_000
    n._last_tick = time.time()
    n.maybe_autolock(interval=3.0)
    assert n.locked is False


def test_sleep_autolock_on_wall_clock_jump(tmp_path):
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")           # lock_on_sleep defaults True
    n.last_activity = time.time()
    n._last_tick = time.time() - 1000          # process "suspended" a while
    n.maybe_autolock(interval=3.0)
    assert n.locked is True


def test_sleep_autolock_off_when_disabled(tmp_path):
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    n.update_applock_settings(idle_minutes=0, lock_on_sleep=False)
    n.last_activity = time.time()
    n._last_tick = time.time() - 1000
    n.maybe_autolock(interval=3.0)
    assert n.locked is False


def test_maybe_autolock_noop_on_non_applock_node(tmp_path):
    n = _fresh(tmp_path)
    n._last_tick = time.time() - 1000
    n.maybe_autolock(interval=3.0)             # must not raise or lock
    assert n.locked is False


def test_maybe_autolock_noop_when_already_locked(tmp_path):
    n = _locked(tmp_path)
    n.maybe_autolock(interval=3.0)             # already locked; must not raise
    assert n.locked is True


# -- IMPORTANT #5 (redone): idle-autolock tracks an explicit activity signal
# (POST /api/activity) instead of inferring it from request traffic --------

def test_arbitrary_requests_never_touch_activity(tmp_path):
    """The middleware no longer touches last_activity on ANY request -- not
    a status poll, not a content fetch, not anything else. This is the
    fix for the denylist approach's actual defeat: a background WS-driven
    refresh() (untagged /api/feed, /api/kreds, /api/state) or a media
    <img>/<video> GET (/api/blob/, /api/post-blob/, /api/dm-blob/, none of
    which can send a header) must not be able to keep an abandoned tab's
    idle timer perpetually reset."""
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    c = TestClient(build_app(n))
    n.last_activity = time.time() - 1000
    old = n.last_activity
    assert c.get("/api/applock").status_code == 200
    assert c.get("/api/feed").status_code == 200
    assert c.get("/api/kreds").status_code == 200
    assert c.get("/api/state").status_code == 200
    assert n.last_activity == old


def test_post_activity_touches(tmp_path):
    n = _fresh(tmp_path)
    n.enable_applock("1234", "pin")
    c = TestClient(build_app(n))
    n.last_activity = time.time() - 1000
    old = n.last_activity
    r = c.post("/api/activity")
    assert r.status_code == 200
    assert r.json() == {"ok": True}
    assert n.last_activity > old


def test_activity_423_while_locked(tmp_path):
    # Deliberately NOT in the allowlist: a locked node has no "activity"
    # to extend, so this behaves like any other content route.
    n = _locked(tmp_path)
    c = TestClient(build_app(n))
    r = c.post("/api/activity")
    assert r.status_code == 423


def test_activity_on_non_applock_node_touches_harmlessly(tmp_path):
    # The client always attempts the ping regardless of App-lock status --
    # on a non-applock node this just touches a last_activity nothing ever
    # reads (maybe_autolock is a no-op unless applock_enabled).
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    n.last_activity = time.time() - 1000
    old = n.last_activity
    r = c.post("/api/activity")
    assert r.status_code == 200
    assert n.last_activity > old


# -- minor #11: enable_applock (via the API) on non-Windows raises cleanly ----

def test_setup_non_windows_400_not_500(tmp_path, monkeypatch):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    monkeypatch.setattr(applock, "DPAPI_AVAILABLE", False)
    r = c.post("/api/applock/setup",
              json={"credential": "9999", "cred_type": "pin"})
    assert r.status_code == 400
    assert n.applock_enabled is False


# -- minor #13: POST /api/unlock on an already-unlocked node is a no-op -------

def test_unlock_already_unlocked_node_ok_credential_unchecked(tmp_path):
    n = _fresh(tmp_path)
    c = TestClient(build_app(n))
    c.post("/api/applock/setup", json={"credential": "1234", "cred_type": "pin"})
    assert n.locked is False

    r = c.post("/api/unlock", json={"credential": "definitely-wrong"})
    assert r.status_code == 200
    assert n.locked is False
    assert c.get("/api/state").status_code == 200
