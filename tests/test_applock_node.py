import json, os, pytest
from hearth import applock
from hearth.node import HearthNode, _atomic_write

def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Wong", "wong-phone")

def test_enable_strips_plaintext_secrets(tmp_path):
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    raw = json.loads((n.data_dir / "keys.json").read_text())
    assert raw.get("applock") is True
    for f in ("device_priv", "identity_priv", "enc_priv", "storage_key"):
        assert not raw.get(f)                      # no plaintext secret on disk
    assert (n.data_dir / "applock.json").exists()

def test_boot_locked_and_unlock(tmp_path):
    n = _node(tmp_path); ident = n.identity_pub
    n.enable_applock("1234", "pin")
    n2 = HearthNode(n.data_dir)                    # reboot
    assert n2.locked is True
    with pytest.raises(Exception):                 # can't sign while locked
        n2.compose_post("hi", scope="kreds")
    n2.unlock("1234")
    assert n2.locked is False and n2.identity_pub == ident
    mid = n2.compose_post("hi", scope="kreds")     # works after unlock
    assert mid

def test_wrong_credential_stays_locked(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin")
    n2 = HearthNode(n.data_dir)
    with pytest.raises(Exception):
        n2.unlock("0000")
    assert n2.locked is True

def test_lock_drops_keys(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin"); n.unlock("1234")
    a = n.compose_post("a", scope="kreds", placement="profile")
    n.lock()
    assert n.locked is True
    with pytest.raises(Exception):
        n.compose_post("b", scope="kreds")

def test_enc_rotation_persists_while_unlocked(tmp_path):
    n = _node(tmp_path); n.enable_applock("1234", "pin"); n.unlock("1234")
    n.device.rotate_enc(); n._save_keys()          # rotation mutates secret bundle
    n2 = HearthNode(n.data_dir); n2.unlock("1234")
    assert len(n2.device.retired_enc) == len(n.device.retired_enc)

def test_no_applock_path_unchanged(tmp_path):
    n = _node(tmp_path)
    assert n.locked is False and n.applock_enabled is False
    assert n.compose_post("x", scope="kreds")      # normal node still works

def test_revocation_wipes_device_key_and_drops_applock(tmp_path):
    # Crypto review CRITICAL: enter_revoked_state() must not reintroduce a
    # plaintext device_priv (or any other secret) into keys.json, and since
    # the device is permanently dead + the store is wiped, the encrypted
    # App-lock bundle protecting those secrets is moot and must go too.
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    n.enter_revoked_state()

    raw = json.loads((n.data_dir / "keys.json").read_text())
    for f in ("device_priv", "identity_priv", "enc_priv", "retired_enc",
             "storage_key"):
        assert not raw.get(f)                      # no plaintext secret on disk
    assert raw.get("revoked") is True
    assert not (n.data_dir / "applock.json").exists()
    assert n.applock_enabled is False

    # A revoked node reboots cleanly (device_priv=None tolerated), reports
    # revoked, and can no longer produce a signature with the dead key.
    n2 = HearthNode(n.data_dir)
    assert n2.locked is False
    assert n2.revoked is True
    assert n2.applock_enabled is False
    with pytest.raises(Exception):
        n2.device.sign_raw(b"proof-of-life")


# -- CRITICAL #1: paper_seed.txt sealed under App-lock -------------------------

def test_paper_seed_sealed_and_deleted_on_enable(tmp_path):
    n = _node(tmp_path)
    seed_path = n.data_dir / "paper_seed.txt"
    assert seed_path.exists()
    seed_hex = seed_path.read_text()

    n.enable_applock("1234", "pin")

    assert not seed_path.exists()           # plaintext deleted
    assert n._paper_seed == seed_hex        # held in memory while unlocked
    for raw in ((n.data_dir / "keys.json").read_text(),
               (n.data_dir / "applock.json").read_text()):
        assert seed_hex not in raw          # sealed, not merely copied in plaintext


def test_paper_seed_restored_on_disable(tmp_path):
    n = _node(tmp_path)
    seed_hex = (n.data_dir / "paper_seed.txt").read_text()
    n.enable_applock("1234", "pin")

    n.disable_applock("1234")

    seed_path = n.data_dir / "paper_seed.txt"
    assert seed_path.exists()
    assert seed_path.read_text() == seed_hex
    assert n._paper_seed is None


def test_paper_seed_survives_enc_rotation_save(tmp_path):
    n = _node(tmp_path)
    seed_hex = (n.data_dir / "paper_seed.txt").read_text()
    n.enable_applock("1234", "pin")

    n.device.rotate_enc(); n._save_keys()   # reencrypt path (must carry paper_seed forward)

    n2 = HearthNode(n.data_dir)
    n2.unlock("1234")
    assert n2._paper_seed == seed_hex
    n2.disable_applock("1234")
    assert (n2.data_dir / "paper_seed.txt").read_text() == seed_hex


def test_no_plaintext_identity_seed_on_disk_when_enabled(tmp_path):
    n = _node(tmp_path)
    seed_hex = (n.data_dir / "paper_seed.txt").read_text()
    n.enable_applock("1234", "pin")
    assert not (n.data_dir / "paper_seed.txt").exists()
    for f in ("keys.json", "applock.json"):
        assert seed_hex not in (n.data_dir / f).read_text()


def test_paper_seed_restored_on_revocation_not_lost(tmp_path):
    # The paper seed protects the IDENTITY (usable from other, non-revoked
    # devices), not just this now-dead device -- revoking must not destroy
    # the only copy along with the rest of this device's secrets.
    n = _node(tmp_path)
    seed_hex = (n.data_dir / "paper_seed.txt").read_text()
    n.enable_applock("1234", "pin")

    n.enter_revoked_state()

    seed_path = n.data_dir / "paper_seed.txt"
    assert seed_path.exists()
    assert seed_path.read_text() == seed_hex


# -- IMPORTANT #2: crash-residue scrub on locked boot --------------------------

def test_crash_residue_scrubbed_on_locked_boot(tmp_path):
    """Simulate a crash between enable_applock's two writes: applock.json
    exists (secrets already safely sealed) but keys.json still holds the
    OLD full plaintext bundle from before enable() ran. The next boot must
    scrub keys.json down to the non-secret subset immediately."""
    n = _node(tmp_path)
    full_before = n.device.to_json()
    n.enable_applock("1234", "pin")
    (n.data_dir / "keys.json").write_text(json.dumps(full_before))

    n2 = HearthNode(n.data_dir)             # reboot: locked-boot crash-residue path
    raw = json.loads((n2.data_dir / "keys.json").read_text())
    for f in ("device_priv", "identity_priv", "enc_priv", "storage_key"):
        assert not raw.get(f)
    assert raw.get("applock") is True
    assert n2.locked is True
    n2.unlock("1234")
    assert n2.identity_pub == n.identity_pub


def test_leftover_paper_seed_scrubbed_on_locked_boot(tmp_path):
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    # Simulate a crash between applock.json's write (paper_seed already
    # captured inside it) and paper_seed.txt's deletion.
    (n.data_dir / "paper_seed.txt").write_text("ff" * 32)

    n2 = HearthNode(n.data_dir)
    assert not (n2.data_dir / "paper_seed.txt").exists()


def test_leftover_paper_seed_scrub_is_logged(tmp_path, caplog):
    """Minor C: the deletion is safe (applock.json already sealed the
    seed) but must be traceable, not silent -- a note is logged when it
    happens. Behavior is unchanged (asserted above); this only pins that
    the log fires."""
    import logging
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    (n.data_dir / "paper_seed.txt").write_text("ff" * 32)

    with caplog.at_level(logging.INFO, logger="hearth.node"):
        HearthNode(n.data_dir)

    assert "paper_seed.txt" in caplog.text


# -- 0.3.11 misfire fix: lock_on_sleep migration runs at applock boot ---------

def test_pre_migration_record_flipped_on_boot(tmp_path):
    """A record written before 0.3.11 (no settings_v marker, lock_on_sleep
    left at its old True default) must be migrated the next time the node
    boots with App-lock enabled -- not just when applock.migrate_settings is
    called directly (unit-tested in test_applock_api.py); this pins the
    node.py __init__ wiring that actually invokes it."""
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    record = json.loads((n.data_dir / "applock.json").read_text())
    del record["settings_v"]
    record["settings"]["lock_on_sleep"] = True
    _atomic_write(n.data_dir / "applock.json", json.dumps(record))

    n2 = HearthNode(n.data_dir)             # reboot: migration should run
    on_disk = json.loads((n2.data_dir / "applock.json").read_text())
    assert on_disk["settings_v"] == 2
    assert on_disk["settings"]["lock_on_sleep"] is False
    assert n2.applock_status()["settings"]["lock_on_sleep"] is False


def test_migration_write_failure_does_not_crash_boot(tmp_path, monkeypatch):
    """The migration's persist is best-effort: a read-only/permission/
    disk-full failure on the _atomic_write must not raise out of
    HearthNode.__init__ -- the node still boots, the on-disk record stays
    unmarked, and the migration retries (and lands) on the next boot."""
    import hearth.node as node_mod
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    record = json.loads((n.data_dir / "applock.json").read_text())
    del record["settings_v"]
    record["settings"]["lock_on_sleep"] = True
    _atomic_write(n.data_dir / "applock.json", json.dumps(record))

    def boom(path, text):
        raise OSError("disk full / read-only")
    monkeypatch.setattr(node_mod, "_atomic_write", boom)
    n2 = HearthNode(n.data_dir)                  # must NOT raise
    assert n2.locked is True                     # booted normally
    on_disk = json.loads((n2.data_dir / "applock.json").read_text())
    assert "settings_v" not in on_disk           # write really failed

    monkeypatch.undo()                           # next boot: write works
    n3 = HearthNode(n.data_dir)
    on_disk = json.loads((n3.data_dir / "applock.json").read_text())
    assert on_disk["settings_v"] == 2            # retry landed
    assert on_disk["settings"]["lock_on_sleep"] is False


def test_already_migrated_record_untouched_on_boot(tmp_path):
    """Idempotency at the node level: a record already marked settings_v=2
    with lock_on_sleep re-enabled by the user must survive a reboot
    unchanged (the migration must not re-flip it)."""
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    n.update_applock_settings(idle_minutes=0, lock_on_sleep=True)  # user opt-in

    n2 = HearthNode(n.data_dir)
    assert n2.applock_status()["settings"]["lock_on_sleep"] is True
    on_disk = json.loads((n2.data_dir / "applock.json").read_text())
    assert on_disk["settings_v"] == 2


# -- IMPORTANT #3: atomic-write helper -----------------------------------------

def test_atomic_write_helper_roundtrips_and_cleans_up_tmp(tmp_path):
    p = tmp_path / "x.json"
    _atomic_write(p, '{"a": 1}')
    assert json.loads(p.read_text()) == {"a": 1}
    assert not p.with_suffix(p.suffix + ".tmp").exists()
    _atomic_write(p, '{"a": 2}')            # overwrite path
    assert json.loads(p.read_text()) == {"a": 2}


def test_atomic_write_fsyncs_before_replace(tmp_path, monkeypatch):
    """Minor A: the write must be fsync-ed before the rename, or it is only
    crash-safe (os.replace atomic w.r.t. a process crash) and not
    power-loss safe (the rename can land before the .tmp file's data
    actually reaches disk)."""
    import hearth.node as node_mod
    calls = []
    real_fsync = os.fsync
    def spy_fsync(fd):
        calls.append(fd)
        return real_fsync(fd)
    monkeypatch.setattr(node_mod.os, "fsync", spy_fsync)

    p = tmp_path / "y.json"
    node_mod._atomic_write(p, '{"a": 1}')
    assert calls, "os.fsync was never called"
    assert json.loads(p.read_text()) == {"a": 1}


def test_enable_and_save_keys_use_atomic_write(tmp_path, monkeypatch):
    """The direct-truncate write pattern (Path.write_text straight onto
    keys.json/applock.json) must not reappear -- assert the actual helper
    is what node.py calls for both files."""
    calls = []
    import hearth.node as node_mod
    real_atomic_write = node_mod._atomic_write
    def spy(path, text):
        calls.append(path)
        return real_atomic_write(path, text)
    monkeypatch.setattr(node_mod, "_atomic_write", spy)

    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    assert n.data_dir / "applock.json" in calls
    assert n.data_dir / "keys.json" in calls


# -- IMPORTANT #4: change_credential returns the master, no stale-master window

def test_change_credential_holds_correct_master_no_second_unlock(tmp_path):
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")

    n.change_applock_credential("1234", "5678")

    record = json.loads((n.data_dir / "applock.json").read_text())
    _secrets, expected_master = applock.unlock(record, "5678",
                                               applock.dpapi_unseal)
    assert n._applock_master == expected_master

    # And a _save_keys() reencrypt under the held master round-trips.
    n.device.rotate_enc(); n._save_keys()
    n2 = HearthNode(n.data_dir)
    n2.unlock("5678")
    assert len(n2.device.retired_enc) == len(n.device.retired_enc)


# -- minor #11: enable_applock on non-Windows raises cleanly -------------------

def test_enable_applock_non_windows_raises_cleanly(tmp_path, monkeypatch):
    n = _node(tmp_path)
    monkeypatch.setattr(applock, "DPAPI_AVAILABLE", False)
    with pytest.raises(RuntimeError, match="Windows"):
        n.enable_applock("1234", "pin")
    assert n.applock_enabled is False
    assert not (n.data_dir / "applock.json").exists()


# -- minor #13: unlock() on an already-unlocked node is a no-op ---------------

def test_unlock_on_already_unlocked_node_is_noop(tmp_path):
    n = _node(tmp_path)
    n.enable_applock("1234", "pin")
    assert n.locked is False
    n.unlock("this-credential-is-never-checked")   # must not raise or rebuild
    assert n.locked is False
    assert n.identity_pub
    assert n.compose_post("still works", scope="kreds")
