"""HearthNode - one device's daemon state: keys + store + change events."""
from __future__ import annotations

import asyncio
import json
import logging
import os
import secrets
import time
from pathlib import Path
from typing import List, Optional, Sequence

from . import applock, invitecodec, update
from .dmcrypt import (decrypt_blob, decrypt_body, dm_aad, encrypt_blob,
                      encrypt_body, new_content_key, open_content_key,
                      post_aad, seal_content_key, unwrap_key, wrap_key)
from .identity import (DeviceKeys, DeviceView, ENC_ROTATION_PERIOD,
                       EnrollmentCert, IdentityCeremony, PROTOCOL,
                       canonical, _sig_ok)
from .imagegate import AVATAR_MAX, BANNER_MAX, transcode, transcode_photo
from .videogate import STORY_IMAGE_MAX, transcode_video
from .messages import (ACCENTS, DEFRIEND_RETRY, DEFRIEND_TTL, GRID_LAYOUTS,
                       KIND_ALBUM, KIND_DELETE, KIND_DM, KIND_POST,
                       KIND_WRAP_GRANT, MAX_BLOCK_H, MAX_CAPTION, MAX_LAYOUT,
                       SIZE_LAYOUTS, TEXT_STYLE_ENUMS, WALL_COLS, is_expired,
                       make_album, make_delete, make_dm, make_enckey,
                       make_post, make_profile, make_profile_layout,
                       make_ring, make_story, make_wrap_grant)
from .store import IngestResult, Store

logger = logging.getLogger(__name__)

# Auto-update check cadence (0.3.15): first check this long after the
# gossip loop starts, then every this many seconds thereafter. See
# HearthNode.maybe_check_update.
UPDATE_CHECK_STARTUP_DELAY = 60.0
UPDATE_CHECK_INTERVAL = 6 * 3600


def _atomic_write(path: Path, text: str) -> None:
    """Write `text` to `path` without ever leaving it truncated/partial:
    write to a sibling .tmp file in the same directory, fsync it, then
    atomically replace `path` with it via os.replace (POSIX rename
    semantics on Windows too -- unlike Path.write_text's
    truncate-then-write). Used for every applock.json/keys.json write:
    both are, at various points in App-lock's lifecycle, the ONLY on-disk
    copy of key material, so a crash mid-write must never corrupt them
    (whole-branch review, IMPORTANT #3).

    The fsync (minor A, redone) is what makes this power-loss safe, not
    just crash safe: os.replace alone is atomic with respect to a process
    crash (the old file is never observably half-overwritten), but on a
    power loss the rename can be persisted by the filesystem journal
    before the .tmp file's own data actually reaches disk, leaving `path`
    pointing at a zero-length or garbage file after the next boot.
    Flushing Python's buffer + fsync-ing the fd before the replace forces
    the data itself to disk first, so the rename can never outrace it."""
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(text)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


class HearthNode:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir)
        keys_path = self.data_dir / "keys.json"
        if not keys_path.exists():
            raise FileNotFoundError(
                f"no keys.json in {self.data_dir}; initialize first")
        raw = json.loads(keys_path.read_text())
        # App-lock (Kreds security slice): when applock.json exists, keys.json
        # holds only the NON-secret subset of DeviceKeys.to_json() (+ an
        # "applock": true marker) -- the secret bundle (device_priv,
        # identity_priv, enc_priv, retired_enc, storage_key) lives encrypted
        # in applock.json instead. The node boots LOCKED in that case: no
        # private key material is ever read off disk until unlock().
        self._applock_path = self.data_dir / "applock.json"
        self.applock_enabled = self._applock_path.exists()
        self._applock_master = None    # held only while unlocked; NEVER the credential
        # The paper backup seed (identity_priv's recovery material, see
        # paper_seed.txt / IdentityCeremony.paper_seed): while App-lock is
        # enabled it is folded into the sealed applock.json bundle instead
        # of sitting in plaintext on disk, and held here ONLY while
        # unlocked -- never persisted outside applock.json (whole-branch
        # review, CRITICAL #1). None on a non-applock node (paper_seed.txt
        # is simply plaintext on disk there, untouched by any of this) and
        # while locked (popped back out by lock()).
        self._paper_seed = None
        # Auto-lock (Kreds security slice): last_activity is touched by the
        # API middleware on every allowed /api/* request; _last_tick is
        # touched once per periodic-loop tick so a wall-clock jump between
        # ticks (bigger than the loop interval + margin) can be told apart
        # from normal idling -- see maybe_autolock(). Throttle counters are
        # in-memory-only (an online-guess slow-down, not a security
        # boundary by themselves -- the DPAPI-sealed device secret is).
        self.last_activity = time.time()
        self._last_tick = time.time()
        self._unlock_fail_count = 0
        self._unlock_next_allowed = 0.0
        self.revoked = bool(raw.get("revoked", False))
        self.store = Store(self.data_dir / "hearth.db")
        self.subscribers: set = set()
        self._pending_invites = {}
        self._pending_responses = {}
        # Set by SyncService.__init__ to its own _sync_session(address)
        # bound method: the same outbound-session dialer sync_with wraps
        # for the gossip loop, but returning (success, peer_identity,
        # applied_by_peer) so deliver_defriends can bind a delivery
        # record's disposition to the AUTHENTICATED peer (whole-branch
        # review, Fix 1) and tell a real application-level ack apart from
        # a session that merely completed without raising. None until a
        # SyncService wraps this node (or in tests that never wire one
        # up), in which case delivery is a no-op until a later gossip
        # round retries.
        self.web_dir = None       # set by runner; used by the auto-update check
        self.update_status = {"available": False, "kind": None,
                              "version": None}
        self._update_started_at = None   # monotonic of first tick
        self._update_last_check = None    # monotonic of last check()
        self._dial = None
        # Set by SyncService.__init__ to its own deliver_friend_add(address,
        # response_json) bound method: lets add_friend_via_invite (below)
        # push B's response to A over the SAME gossip listener/transport
        # (Tor-capable) instead of requiring manual copy-paste. None until a
        # SyncService wraps this node, in which case add_friend_via_invite
        # simply falls back to the manual {"status": "manual"} response.
        self._friend_dial = None
        okp = self.data_dir / "onion_key"
        self.onion_key = okp.read_text() if okp.exists() else None
        if self.applock_enabled:
            # Crash-residue scrub (whole-branch review, IMPORTANT #2): a
            # crash between enable_applock's two writes (or a reencrypt
            # _save_keys' two writes) leaves applock.json written but
            # keys.json still the OLD full plaintext bundle. applock.json
            # existing already proves the secret bundle is safely sealed,
            # so it is always safe to scrub keys.json back down now.
            if any(f in raw for f in DeviceKeys.SECRET_FIELDS):
                raw = self._nonsecret_json(raw)
                _atomic_write(keys_path, json.dumps(raw))
            paper_seed_path = self.data_dir / "paper_seed.txt"
            if paper_seed_path.exists():
                # Same reasoning: enable_applock writes applock.json
                # (which already captured paper_seed) strictly before
                # deleting paper_seed.txt -- a crash in that window can
                # leave the plaintext file behind, and applock.json
                # existing proves it is safe to remove now. Logged (minor
                # C) purely so a surprising deletion is traceable -- this
                # does not change the behavior, which is already safe.
                logger.info(
                    "app-lock: removing leftover plaintext paper_seed.txt "
                    "at %s (crash residue from a prior enable_applock -- "
                    "already sealed in applock.json)", paper_seed_path)
                paper_seed_path.unlink()
            # 0.3.11 misfire fix: lock_on_sleep shipped default-ON and
            # nobody chose it -- migrate any record that predates the
            # settings_v marker to OFF, once. applock.migrate_settings is
            # idempotent (a record already marked, or one where the user
            # re-enabled the setting since, is returned unchanged).
            try:
                applock_record = json.loads(self._applock_path.read_text())
            except (OSError, ValueError):
                # Corrupt/unreadable applock.json must NOT brick boot: skip
                # the one-time settings migration this boot. The record
                # recovers at unlock time exactly as before (unlock re-reads
                # applock.json); until then the migration simply retries on a
                # future boot once the file is readable again.
                applock_record = None
            if applock_record is not None:
                applock_record, applock_migrated = applock.migrate_settings(
                    applock_record)
            else:
                applock_migrated = False
            if applock_migrated:
                try:
                    _atomic_write(self._applock_path,
                                  json.dumps(applock_record))
                except OSError:
                    # A read-only/permission/disk-full failure here must
                    # not crash startup -- boot anyway. The on-disk record
                    # stays unmarked (no settings_v), so migrate_settings
                    # simply retries on the next boot; until a write lands,
                    # the record's old explicit lock_on_sleep value keeps
                    # applying (applock_status re-reads from disk).
                    logger.warning(
                        "app-lock: could not persist the lock_on_sleep "
                        "migration to %s -- continuing; the migration will "
                        "retry on the next boot", self._applock_path,
                        exc_info=True)
            self.device = DeviceKeys.locked_from_json(raw)
            self.locked = True
            # The revoked-view discovery and legacy-storage-key migration
            # below both need secret material (identity_priv / storage_key)
            # that a locked boot never reads off disk -- deferred to
            # unlock(), which re-runs the same two checks once the secret
            # bundle is actually in memory.
        else:
            self.device = DeviceKeys.from_json(raw)
            self.locked = False
            if not self.revoked and self.device.identity_pub is not None:
                view = self.store.load_views(self.identity_pub).get(
                    self.device.device_pub)
                if view is not None and view.revocation is not None:
                    self.enter_revoked_state()
            if not self.revoked and "storage_key" not in raw:
                self._save_keys()      # legacy keys.json: pin the newly
                                       # generated storage key to disk

    @classmethod
    def create(cls, data_dir, person_name: str, device_name: str,
               seed: Optional[bytes] = None) -> "HearthNode":
        data_dir = Path(data_dir)
        data_dir.mkdir(parents=True, exist_ok=True)
        ceremony = IdentityCeremony(seed)
        device = DeviceKeys.create(device_name)
        ceremony.enroll_first_device(device)
        _atomic_write(data_dir / "keys.json", json.dumps(device.to_json()))
        _atomic_write(data_dir / "paper_seed.txt", ceremony.paper_seed())
        node = cls(data_dir)
        node.store.add_identity(ceremony.identity_pub, is_self=True)
        node.store.save_views(ceremony.identity_pub, {
            device.device_pub: DeviceView(cert=device.cert)})
        node.set_profile(person_name)
        return node

    @property
    def identity_pub(self) -> str:
        return self.device.identity_pub

    def _nonsecret_json(self, full: dict) -> dict:
        """The subset of DeviceKeys.to_json() safe to keep in plaintext
        keys.json once App-lock is on -- everything NOT in SECRET_FIELDS,
        plus the applock marker so a reload knows to boot locked."""
        nonsecret = {k: v for k, v in full.items()
                    if k not in DeviceKeys.SECRET_FIELDS}
        nonsecret["applock"] = True
        if self.revoked:
            nonsecret["revoked"] = True
        return nonsecret

    def enable_applock(self, credential: str, cred_type: str):
        """Turn App-lock on for an already-unlocked node: the live secret
        bundle -- PLUS the paper backup seed, if paper_seed.txt still
        exists (whole-branch review, CRITICAL #1: that file is the
        identity private key's recovery material in plaintext, and was
        previously untouched by App-lock entirely) -- is sealed into
        applock.json under (credential + a fresh DPAPI-sealed device
        secret), keys.json is rewritten to hold only the non-secret
        subset, and paper_seed.txt is deleted. The node stays unlocked
        (self.device keeps its private material in memory) -- only what's
        ON DISK changes shape."""
        if self.locked:
            raise RuntimeError("locked")
        if not applock.DPAPI_AVAILABLE:
            raise RuntimeError("app-lock requires Windows")
        full = self.device.to_json()
        secrets = {k: full[k] for k in DeviceKeys.SECRET_FIELDS}
        paper_seed_path = self.data_dir / "paper_seed.txt"
        paper_seed = (paper_seed_path.read_text()
                     if paper_seed_path.exists() else None)
        secrets["paper_seed"] = paper_seed
        record, master = applock.enable(secrets, credential, cred_type,
                                        applock.dpapi_seal)
        _atomic_write(self._applock_path, json.dumps(record))
        _atomic_write(self.data_dir / "keys.json",
                      json.dumps(self._nonsecret_json(full)))
        if paper_seed_path.exists():
            paper_seed_path.unlink()
        self.applock_enabled = True
        self._applock_master = master
        self._paper_seed = paper_seed

    def unlock(self, credential: str):
        """Verify `credential` against applock.json, rebuild the full
        DeviceKeys from the decrypted secret bundle + the on-disk
        non-secret subset, and hold the returned master (never the
        credential) for future _save_keys() re-encryption. Raises
        applock.BadCredential (via applock.unlock) on a wrong credential/
        device secret, leaving the node exactly as locked as before.

        A no-op if the node is already unlocked (whole-branch review,
        minor #13): re-deriving and rebuilding self.device against
        whatever credential happens to be passed here is unnecessary work
        that could even fail (wrong/stale credential) on a node that is
        already fine, and rebuilding is simply not needed for a node that
        already holds its keys."""
        if not self.applock_enabled:
            raise RuntimeError("app-lock is not enabled")
        if not self.locked:
            return
        record = json.loads(self._applock_path.read_text())
        secrets, master = applock.unlock(record, credential,
                                         applock.dpapi_unseal)
        # paper_seed rides inside the sealed secrets bundle (CRITICAL #1)
        # but is NOT a DeviceKeys field -- pop it out before from_json so
        # the merge below is unaffected, and hold it the same way
        # _applock_master is held: in memory only, never the credential.
        self._paper_seed = secrets.pop("paper_seed", None)
        nonsecret = json.loads((self.data_dir / "keys.json").read_text())
        merged = {**nonsecret, **secrets}
        self.device = DeviceKeys.from_json(merged)
        self._applock_master = master
        self.locked = False
        # Re-run the deferred boot checks from __init__ now that the secret
        # bundle (identity_priv / storage_key) is actually in memory.
        if not self.revoked and self.device.identity_pub is not None:
            view = self.store.load_views(self.identity_pub).get(
                self.device.device_pub)
            if view is not None and view.revocation is not None:
                self.enter_revoked_state()
        if not self.revoked and "storage_key" not in merged:
            self._save_keys()          # legacy secret bundle: pin one now
        # Wart 3: restored key material (current + retired enc keys) may
        # make previously-marked messages decryptable again -- drop the
        # whole negative cache rather than risk stale permanent misses.
        self.store.clear_undecryptable()
        self._touch()
        self.notify()

    def lock(self):
        """Drop all private key material from memory. self.device keeps its
        non-secret fields (device_pub, cert, seq, enc_pub) so device
        listings / identity_pub (cert fallback) still work while locked;
        signing/decrypting is impossible until the next unlock(). Refuses
        to strand the node: locking is only meaningful once applock.json
        exists to unlock back from."""
        if not self.applock_enabled:
            raise RuntimeError("app-lock is not enabled")
        self.device._device_priv = None
        self.device._identity_priv = None
        self.device.enc_priv = None
        self.device.retired_enc = []
        self.device.storage_key = None
        self._applock_master = None
        self._paper_seed = None
        self.locked = True
        self.notify()

    # -- App-lock: API-facing status/settings/change/disable, throttle,
    # node-side auto-lock -------------------------------------------------

    def _touch(self):
        """Mark authenticated activity now -- the idle-autolock reference
        point. Called by the API middleware on every /api/* request the
        locked guard actually let through, and by unlock() itself."""
        self.last_activity = time.time()

    def stamp_autolock_tick(self, now: Optional[float] = None):
        """Set the sleep-gap baseline. Called by gossip_loop immediately
        BEFORE its inter-round sleep, so maybe_autolock (called right
        after the sleep) measures sleep-duration + suspend time ONLY --
        a slow gossip round's dial time can no longer masquerade as a
        suspend (0.3.11 misfire fix). Honest limit: a suspend that
        happens mid-round is not detected by this heuristic; the idle
        timer remains the backstop for long absences."""
        self._last_tick = now if now is not None else time.time()

    def applock_status(self) -> dict:
        """Non-secret App-lock status -- safe to read even while locked
        (applock.json is plaintext except for its ciphertext blob)."""
        if not self.applock_enabled:
            return {"enabled": False, "locked": False, "cred_type": None,
                    "settings": {"idle_minutes": 0, "lock_on_sleep": False}}
        record = json.loads(self._applock_path.read_text())
        return {"enabled": True, "locked": self.locked,
                "cred_type": record.get("cred_type"),
                "settings": record.get(
                    "settings", {"idle_minutes": 0, "lock_on_sleep": False})}

    def update_applock_settings(self, idle_minutes: int, lock_on_sleep: bool):
        """Persist idle_minutes (0 = idle timer off) / lock_on_sleep into
        applock.json's settings. Per-device, local-only -- never gossiped."""
        if not self.applock_enabled:
            raise RuntimeError("app-lock is not enabled")
        record = json.loads(self._applock_path.read_text())
        record["settings"] = {"idle_minutes": idle_minutes,
                              "lock_on_sleep": lock_on_sleep}
        _atomic_write(self._applock_path, json.dumps(record))

    def change_applock_credential(self, old: str, new: str):
        """Re-encrypt applock.json under `new` (verifies `old` first, via
        applock.change_credential -> applock.unlock -- a wrong `old` raises
        applock.BadCredential and leaves the record untouched). change_
        credential mints a FRESH device secret, so if currently unlocked
        the previously-held master would silently stop matching the new
        on-disk record -- hold the new one.

        applock.change_credential now returns the new master alongside the
        new record (it already derives it internally, via its own enable()
        call) -- there is no second, separate unlock() call here anymore
        (whole-branch review, IMPORTANT #4): that second derivation could
        raise (e.g. a transient DPAPI error) AFTER the new record was
        already written, leaving self._applock_master holding the OLD
        master against the NEW on-disk record -- permanently corrupting
        the next _save_keys() re-encrypt. With the master coming from the
        same already-successful call that produced the record, that
        failure mode no longer exists."""
        if not self.applock_enabled:
            raise RuntimeError("app-lock is not enabled")
        record = json.loads(self._applock_path.read_text())
        new_record, master = applock.change_credential(
            record, old, new, applock.dpapi_unseal, applock.dpapi_seal)
        _atomic_write(self._applock_path, json.dumps(new_record))
        if not self.locked:
            self._applock_master = master

    def disable_applock(self, credential: str):
        """Verify `credential` against the live record, then restore a
        plaintext keys.json from the CURRENT in-memory secrets, restore
        paper_seed.txt if a paper seed was sealed away (CRITICAL #1), and
        drop applock.json -- App-lock is off, the node behaves like it
        never had it. Requires the node to already be unlocked (self.device
        holds the secrets to restore); the API's locked-guard never
        allowlists this route, so in practice it is unreachable locked.

        Writes both plaintext files BEFORE deleting applock.json (not
        after, as this used to): a crash between the two used to leave
        applock.json gone and keys.json still the old non-secret subset --
        every secret permanently unrecoverable, since nothing on disk held
        them anymore. Writing first means a crash in that window instead
        leaves applock.json (with the OLD credential) still sitting
        alongside a fully-restored plaintext keys.json/paper_seed.txt --
        recoverable either way, and the locked-boot crash-residue scrub
        (IMPORTANT #2) cleans keys.json back up on the next boot."""
        if not self.applock_enabled:
            raise RuntimeError("app-lock is not enabled")
        if self.locked:
            raise RuntimeError("locked")
        record = json.loads(self._applock_path.read_text())
        secrets, _ = applock.unlock(record, credential, applock.dpapi_unseal)
        paper_seed = secrets.get("paper_seed")
        if paper_seed:
            _atomic_write(self.data_dir / "paper_seed.txt", paper_seed)
        full = self.device.to_json()
        _atomic_write(self.data_dir / "keys.json", json.dumps(full))
        self._applock_path.unlink()
        self.applock_enabled = False
        self._applock_master = None
        self._paper_seed = None
        self.notify()

    def throttle_wait(self, now: Optional[float] = None) -> float:
        now = now if now is not None else time.time()
        return max(0.0, self._unlock_next_allowed - now)

    def _throttle_fail(self, now: Optional[float] = None):
        """Escalating in-memory delay after a failed unlock attempt: an
        online-guess slow-down, not the security boundary itself (that's
        the DPAPI-sealed device secret + scrypt) -- so in-memory-only and
        reset by process restart is an accepted tradeoff."""
        now = now if now is not None else time.time()
        self._unlock_fail_count += 1
        n = self._unlock_fail_count
        delay = 300 if n >= 8 else 30 if n >= 5 else 5 if n >= 3 else 0
        self._unlock_next_allowed = now + delay

    def _throttle_reset(self):
        self._unlock_fail_count = 0
        self._unlock_next_allowed = 0.0

    def maybe_autolock(self, interval: float = 3.0,
                       now: Optional[float] = None):
        """Ticked by gossip_loop immediately after its inter-round sleep;
        stamp_autolock_tick sets the baseline immediately before it. Not by
        the HTTP layer, since request arrival timing is not a reliable clock
        for sleep detection. Locks on either: (a) idle -- no touched activity for
        settings.idle_minutes (0 = off); or (b) a wall-clock jump bigger
        than `interval + 30s` since the last tick, when settings.
        lock_on_sleep is on -- the process was almost certainly suspended
        and has just resumed. A no-op unless App-lock is enabled and the
        node is currently unlocked."""
        now = now if now is not None else time.time()
        if not self.applock_enabled or self.locked:
            self._last_tick = now
            return
        settings = self.applock_status()["settings"]
        gap = now - self._last_tick
        self._last_tick = now
        if settings.get("lock_on_sleep", False) and gap > interval + 30:
            self.lock()
            return
        idle_minutes = settings.get("idle_minutes", 0)
        if idle_minutes > 0 and now - self.last_activity > idle_minutes * 60:
            self.lock()

    async def maybe_check_update(self, now_monotonic: float) -> None:
        """Cadence-gated auto-update check (0.3.15): first check
        UPDATE_CHECK_STARTUP_DELAY after the loop starts, then every
        UPDATE_CHECK_INTERVAL. Runs the UNCHANGED update.check() off the
        event loop; on a status CHANGE, notify() pushes it to the UI over
        /ws. Best-effort - check() never raises; None means no banner."""
        if self._update_started_at is None:
            self._update_started_at = now_monotonic
        if now_monotonic - self._update_started_at < UPDATE_CHECK_STARTUP_DELAY:
            return
        if (self._update_last_check is not None
                and now_monotonic - self._update_last_check < UPDATE_CHECK_INTERVAL):
            return
        self._update_last_check = now_monotonic
        info = await asyncio.to_thread(update.check, web_dir=self.web_dir)
        if info and info.get("core_available"):
            new = {"available": True, "kind": "core", "version": info["version"]}
        elif info and info.get("web_available"):
            new = {"available": True, "kind": "web", "version": info["version"]}
        else:
            new = {"available": False, "kind": None, "version": None}
        if new != self.update_status:
            self.update_status = new
            self.notify()

    def _save_keys(self):
        if self.applock_enabled:
            if self.locked:
                raise RuntimeError(
                    "locked: cannot persist secret key material")
            full = self.device.to_json()
            secrets = {k: full[k] for k in DeviceKeys.SECRET_FIELDS}
            # paper_seed is not a DeviceKeys field, so it never appears in
            # full/secrets above -- carry the in-memory copy forward on
            # every re-encrypt (e.g. enc-key rotation) or it would silently
            # drop out of the bundle on the very first _save_keys() call
            # after enable_applock (CRITICAL #1).
            secrets["paper_seed"] = self._paper_seed
            record = json.loads(self._applock_path.read_text())
            record = applock.reencrypt(record, secrets, self._applock_master)
            _atomic_write(self._applock_path, json.dumps(record))
            _atomic_write(self.data_dir / "keys.json",
                         json.dumps(self._nonsecret_json(full)))
        else:
            _atomic_write(self.data_dir / "keys.json",
                         json.dumps(self.device.to_json()))

    def save_onion_key(self, blob: str):
        self.onion_key = blob
        (self.data_dir / "onion_key").write_text(blob)

    def notify(self):
        for q in list(self.subscribers):
            try:
                q.put_nowait("changed")
            except Exception:
                pass

    def _publish(self, msg) -> str:
        result = self.store.ingest_message(msg)
        self._save_keys()
        if not result.accepted:
            raise RuntimeError(f"own message rejected: {result.reason}")
        self.notify()
        return result.msg_id

    def _scope_device_pubs(self, scope: str) -> dict:
        """device_pub -> enc_pub for every recipient of a post in `scope`
        (ring members) plus this node's own devices. Members whose enc
        keys we do not yet hold are skipped (best-effort, like DMs)."""
        rings = self.store.rings(self.identity_pub)
        friends = [i for i in self.store.known_identities()
                   if i != self.identity_pub]
        if scope == "inner":
            members = [i for i in friends if rings.get(i) == "inner"]
        else:
            members = friends                       # kreds = all friends
        pubs = {}
        for ident in members:
            pubs.update(self.store.enckeys(ident))
        mine = self.store.enckeys(self.identity_pub)
        mine[self.device.device_pub] = self.device.enc_pub
        pubs.update(mine)
        return pubs

    def compose_post(self, text: str, scope: str = "kreds",
                     photos=(), expires_seconds=None,
                     placement: str = "journal", video=None,
                     span_w=None, span_h=None,
                     auto_place: bool = True) -> str:
        if scope not in ("inner", "kreds"):
            raise ValueError("scope must be inner or kreds")
        if placement not in ("journal", "profile"):
            raise ValueError("placement must be journal or profile")
        if (span_w is None) != (span_h is None):
            raise ValueError("span_w and span_h must be given together")
        if span_w is not None and not (
                isinstance(span_w, int) and isinstance(span_h, int)
                and not isinstance(span_w, bool) and not isinstance(span_h, bool)
                and 1 <= span_w <= WALL_COLS and 1 <= span_h <= MAX_BLOCK_H):
            raise ValueError("bad span")
        pubs = self._scope_device_pubs(scope)
        created_at = time.time()
        expires_at = (created_at + expires_seconds
                      if expires_seconds is not None else None)
        aad = post_aad(self.identity_pub, scope, created_at)
        key = new_content_key()
        if video is not None:
            mp4, poster_png = transcode_video(video)      # story gate; raises ValueError
            vref = self.store.put_blob(encrypt_blob(key, mp4))
            pref = self.store.put_blob(encrypt_blob(key, poster_png))
            nonce, ct = encrypt_body(key, {"text": text, "blobs": [vref]}, aad)
            wraps = wrap_key(key, pubs, aad)
            mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                          [vref], created_at, expires_at,
                                          placement=placement, media="video", poster=pref))
            self._cache_message_key(mid, key)
            has_media = True
        else:
            gated = [transcode_photo(p) for p in photos]   # raises ValueError
            refs = [self.store.put_blob(encrypt_blob(key, p)) for p in gated]
            nonce, ct = encrypt_body(key, {"text": text, "blobs": refs}, aad)
            wraps = wrap_key(key, pubs, aad)
            mid = self._publish(make_post(self.device, scope, nonce, ct, wraps,
                                          refs, created_at, expires_at,
                                          placement=placement))
            self._cache_message_key(mid, key)
            has_media = bool(refs)
        if placement == "profile" and auto_place:
            # Creation is FIRST-FIT (spec 2026-07-15, August's revert of
            # the 2026-07-14 top-insert-push): the new post takes the
            # first open slot its composer-chosen (or default) size fits
            # - gaps get used, nothing already placed ever moves. The
            # separate span-seed call stays gone.
            #
            # auto_place=False is for album-bound posts (the deck grow
            # flow, "+"): the new photo becomes DECK CONTENT, not a wall
            # block of its own, so it must not disturb the wall - the
            # smoke-caught bug was this very auto-pin pushing the whole
            # wall (including the album the photo was about to join) down
            # before set_album could fold it in. Skew note: an old client
            # growing without the flag still converges (set_album clears
            # the member's pin) - it just shows the transient push.
            #
            # The post is ALREADY published at this point (the layout
            # record needs its msg_id). If _push_place raises here (wall
            # full), the caller sees a 400 with no msg_id while the post
            # EXISTS, orphaned-unplaced: no pin, no span. That degrades
            # honestly - profile_view's legacy flow-below fallback renders
            # it for owner and visitors alike, and /api/wall-autoplace
            # adopts it later. Do not "fix" this by hiding the post: this
            # is the e29c53b "vanished orphan" bug class - content must
            # never silently disappear on a placement error. And the
            # trigger is sharper than a 500-posts-scale event: ONE block
            # manually pinned at y=MAX_LAYOUT makes the next overlapping
            # auto-place cascade past the cap and trip it.
            span = ({"w": span_w, "h": span_h} if span_w is not None
                    else ({"w": 2, "h": 2} if has_media else {"w": 4, "h": 1}))
            cur = self.store.profile_layout(self.identity_pub)
            pins = self._first_fit_place(cur["pins"], mid, span)
            spans = dict(cur["spans"])
            spans.pop(mid, None)
            if len(pins) > MAX_LAYOUT:
                raise ValueError("too many pinned blocks")
            self._publish(make_profile_layout(
                self.device, cur["order"], grids=cur["grids"],
                sizes=cur["sizes"], pins=pins, spans=spans, texts=cur["texts"]))
        return mid

    def set_ring(self, member_identity: str, ring: str) -> str:
        if ring not in ("inner", "kreds"):
            raise ValueError("ring must be inner or kreds")
        if member_identity == self.identity_pub:
            raise ValueError("cannot ring yourself")
        if not self.store.is_known(member_identity):
            raise ValueError("not a friend")
        return self._publish(make_ring(self.device, member_identity, ring))

    def _ring_and_since(self, identity_pub: str):
        """(ring, since) for a friend from THIS node's ring records; ring
        defaults to 'kreds'. `since` = the ring record's created_at if ringed,
        else the friend's identity added_at (from the identities table)."""
        ring = self.store.rings(self.identity_pub).get(identity_pub, "kreds")
        since = self.store.ring_since(self.identity_pub, identity_pub)
        return ring, since

    def kreds_list(self):
        """List of known identities (excluding self) with ring and since info.
        Returns a list of dicts with keys: identity_pub, name, ring, since."""
        names = self.store.profiles()
        out = []
        for ident in self.store.known_identities():
            if ident == self.identity_pub:
                continue
            ring, since = self._ring_and_since(ident)
            out.append({"identity_pub": ident,
                        "name": names.get(ident, ident[:8]),
                        "ring": ring, "since": since})
        return out

    _IMAGE_MAGIC = (b"\x89PNG", b"\xff\xd8", b"GIF8", b"BM",
                    b"II*\x00", b"MM\x00*")   # PNG, JPEG, GIF, BMP, TIFF-LE, TIFF-BE

    def compose_story(self, media_bytes: bytes, caption: str = "") -> str:
        if len(caption) > MAX_CAPTION:
            raise ValueError("caption too long")
        is_image = (media_bytes[:4] == b"RIFF" and media_bytes[8:12] == b"WEBP") \
            or any(media_bytes.startswith(m) for m in self._IMAGE_MAGIC)
        if is_image:
            media = self.store.put_blob(
                transcode(media_bytes, STORY_IMAGE_MAX))
            msg = make_story(self.device, "photo", media, poster=None,
                             caption=caption)
        else:
            mp4, poster_png = transcode_video(media_bytes)
            media = self.store.put_blob(mp4)
            poster = self.store.put_blob(poster_png)
            msg = make_story(self.device, "video", media, poster=poster,
                             caption=caption)
        return self._publish(msg)

    def stories_view(self):
        known = set(self.store.known_identities())
        out = []
        for g in self.store.active_stories():
            ipub = g["identity_pub"]
            if ipub != self.identity_pub and ipub not in known:
                continue
            prof = self.store.profile(ipub) or {}
            g = {**g, "mine": ipub == self.identity_pub,
                 "name": prof.get("name", ipub[:8]),
                 "avatar": prof.get("avatar")}
            out.append(g)
        out.sort(key=lambda g: (not g["mine"],))    # self first, keep order
        return out

    def set_profile(self, name: str, bio: str = "",
                    accent: str = "#2743d6", avatar_bytes=None,
                    avatar_shape: str = "circle", avatar_size: str = "m",
                    avatar_align: str = "left", banner_bytes=None,
                    banner_pos=None) -> str:
        current = self.store.profile(self.identity_pub) or {}
        avatar = current.get("avatar")
        banner = current.get("banner")
        if avatar_bytes is not None:
            avatar = self.store.put_blob(transcode(avatar_bytes, AVATAR_MAX))
        if banner_bytes is not None:
            banner = self.store.put_blob(transcode(banner_bytes, BANNER_MAX))
        if banner_pos is None:                       # editor didn't touch the crop
            banner_pos = current.get("banner_pos", 50)
        if not isinstance(banner_pos, int) or isinstance(banner_pos, bool) \
                or not 0 <= banner_pos <= 100:       # pre-check -> 400, not a 500 from _publish
            raise ValueError("bad banner_pos")
        return self._publish(make_profile(
            self.device, name, bio=bio, accent=accent, avatar=avatar,
            avatar_shape=avatar_shape, avatar_size=avatar_size,
            avatar_align=avatar_align, banner=banner, banner_pos=banner_pos))

    def set_profile_layout(self, order: List[str]) -> str:
        # Pre-validate (mirrors set_ring/compose_post) so a bad order surfaces
        # as a caught ValueError -> 400, not a RuntimeError from _publish
        # rejecting the invalid own-message -> unhandled 500.
        if not isinstance(order, list) or len(order) > MAX_LAYOUT:
            raise ValueError("bad layout order")
        if not all(isinstance(x, str) and len(x) == 64
                   and all(c in "0123456789abcdef" for c in x) for x in order):
            raise ValueError("bad layout id")
        # Reorder must never drop per-block grid/size styles, nor the
        # collage pins/spans maps: carry every one of them forward into
        # the republished layout.
        cur = self.store.profile_layout(self.identity_pub)
        return self._publish(make_profile_layout(
            self.device, order, grids=cur["grids"], sizes=cur["sizes"],
            pins=cur["pins"], spans=cur["spans"], texts=cur["texts"]))

    def set_block_grid(self, msg_id: str, grid: str) -> str:
        if grid not in GRID_LAYOUTS:
            raise ValueError("bad grid")
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")
        cur = self.store.profile_layout(self.identity_pub)
        grids = dict(cur["grids"])
        if grid == "auto":
            grids.pop(msg_id, None)          # keep the map small
        else:
            grids[msg_id] = grid
        if len(grids) > MAX_LAYOUT:           # pre-check -> 400, not a 500 from _publish
            raise ValueError("too many styled blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=grids, sizes=cur["sizes"],
            pins=cur["pins"], spans=cur["spans"], texts=cur["texts"]))

    def set_block_size(self, msg_id: str, size: str) -> str:
        if size not in SIZE_LAYOUTS:
            raise ValueError("bad size")
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")
        cur = self.store.profile_layout(self.identity_pub)
        sizes = dict(cur["sizes"])
        if size == "full":
            sizes.pop(msg_id, None)          # default -> keep the map small
        else:
            sizes[msg_id] = size
        if len(sizes) > MAX_LAYOUT:          # pre-check -> 400, not a 500 from _publish
            raise ValueError("too many sized blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"], sizes=sizes,
            pins=cur["pins"], spans=cur["spans"], texts=cur["texts"]))

    def _check_block_id(self, msg_id):
        if not (isinstance(msg_id, str) and len(msg_id) == 64
                and all(c in "0123456789abcdef" for c in msg_id)):
            raise ValueError("bad msg_id")

    def _push_place(self, pins: dict, msg_id: str, geom: dict) -> dict:
        """One placement rule (spec 2026-07-14): the placed block anchors
        exactly at its target; every other pinned block, processed in
        (y, x, id) order, keeps its own (x, w, h) and settles just below
        anything already settled that it overlaps. Non-colliding blocks
        never move; chains cascade straight down, never sideways. The
        published record carries the RESULT, so every peer renders the
        same layout without re-running this."""
        def overlaps(a, b):
            return (a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"]
                    and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"])
        rest = sorted(((k, dict(v)) for k, v in pins.items() if k != msg_id),
                      key=lambda kv: (kv[1]["y"], kv[1]["x"], kv[0]))
        final = {msg_id: dict(geom)}
        for oid, og in rest:
            g = og
            bumped = True
            while bumped:
                bumped = False
                for fg in final.values():
                    if overlaps(g, fg):
                        g["y"] = fg["y"] + fg["h"]
                        bumped = True
            if g["y"] > MAX_LAYOUT:
                raise ValueError("wall is full")
            final[oid] = g
        return final

    def _first_fit_place(self, pins: dict, msg_id: str, span: dict) -> dict:
        """Creation/ungroup placement rule (spec 2026-07-15, reverting
        2026-07-14's top-insert-push for creation): scan cells in (y, x)
        order and settle the block at the first open spot its w x h
        footprint fits inside the canvas - gaps in the middle get used,
        and NOTHING already placed ever moves. With no fitting gap the
        scan lands just below the lowest block. Content-deterministic
        like _push_place; the published record carries the RESULT."""
        def overlaps(a, b):
            return (a["x"] < b["x"] + b["w"] and b["x"] < a["x"] + a["w"]
                    and a["y"] < b["y"] + b["h"] and b["y"] < a["y"] + a["h"])
        others = [g for k, g in pins.items() if k != msg_id]
        w, h = span["w"], span["h"]
        for y in range(MAX_LAYOUT + 1):        # same top-row cap as set_block_pin
            for x in range(WALL_COLS - w + 1):
                geom = {"x": x, "y": y, "w": w, "h": h}
                if not any(overlaps(geom, g) for g in others):
                    return {**{k: dict(v) for k, v in pins.items()
                               if k != msg_id},
                            msg_id: geom}
        raise ValueError("wall is full")

    def set_block_pin(self, msg_id: str, x: int, y: int, w: int, h: int) -> str:
        """Place (or move/resize) a block at explicit cell coordinates:
        pushes any block already in the way straight down (spec
        2026-07-14 dynamic placement) - the client no longer vetoes
        overlap. Shape-checked here for a clean 400."""
        self._check_block_id(msg_id)
        for v in (x, y, w, h):
            if not isinstance(v, int) or isinstance(v, bool):
                raise ValueError("bad pin geometry")
        if not (1 <= w <= WALL_COLS and 1 <= h <= MAX_BLOCK_H
                and 0 <= x and x + w <= WALL_COLS and 0 <= y <= MAX_LAYOUT):
            raise ValueError("bad pin geometry")
        cur = self.store.profile_layout(self.identity_pub)
        pins = self._push_place(cur["pins"], msg_id, {"x": x, "y": y, "w": w, "h": h})
        spans = dict(cur["spans"])
        spans.pop(msg_id, None)          # geometry lives in the pin now
        if len(pins) > MAX_LAYOUT:
            raise ValueError("too many pinned blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=pins, spans=spans, texts=cur["texts"]))

    def unpin_block(self, msg_id: str) -> str:
        """Wire-compat: no UI caller since dynamic placement (spec
        2026-07-14) retired the tray - kept for old clients. Strips the
        block's pin, keeping its size in `spans` (unplaced); a re-pin or
        the next owner-visit auto_place_unplaced adopts it back onto the
        canvas."""
        self._check_block_id(msg_id)
        cur = self.store.profile_layout(self.identity_pub)
        pins = dict(cur["pins"])
        geom = pins.pop(msg_id, None)
        spans = dict(cur["spans"])
        if geom is not None:
            spans[msg_id] = {"w": geom["w"], "h": geom["h"]}
        if len(spans) > MAX_LAYOUT:           # pre-check -> 400, not a 500 from _publish
            raise ValueError("too many sized blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=pins, spans=spans, texts=cur["texts"]))

    def set_block_span(self, msg_id: str, w: int, h: int) -> str:
        """Size an UNPLACED block. A pinned block's geometry lives in its
        pin (set_block_pin) - one source of truth, so this refuses."""
        self._check_block_id(msg_id)
        if not (isinstance(w, int) and isinstance(h, int)
                and not isinstance(w, bool) and not isinstance(h, bool)
                and 1 <= w <= WALL_COLS and 1 <= h <= MAX_BLOCK_H):
            raise ValueError("bad span")
        cur = self.store.profile_layout(self.identity_pub)
        if msg_id in cur["pins"]:
            raise ValueError("block is pinned - move/resize via block-pin")
        spans = dict(cur["spans"])
        spans[msg_id] = {"w": w, "h": h}
        if len(spans) > MAX_LAYOUT:
            raise ValueError("too many sized blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=cur["pins"], spans=spans,
            texts=cur["texts"]))

    def set_block_text(self, msg_id: str, **style) -> str:
        """Style a wall TEXT block in place (spec 2026-07-14): the texts
        map is presentation, not content - same idiom as the retired
        grids map. REPLACES the block's whole style each call (the modal
        always posts the complete current selection). Defaults are
        dropped so the map stays minimal; an all-default style removes
        the entry."""
        self._check_block_id(msg_id)
        msg = self.store.get_message(msg_id)
        if msg is None or msg.cert.identity_pub != self.identity_pub:
            raise ValueError("not your block")
        pl = msg.payload
        if pl.get("kind") != KIND_POST or pl.get("placement") != "profile" \
                or pl.get("blobs") or pl.get("media") == "video":
            raise ValueError("text styling applies to text blocks only")
        cleaned = {}
        for k, v in style.items():
            if k == "color":
                if v not in ("default", "accent") and v not in ACCENTS:
                    raise ValueError("bad text color")
                if v != "default":
                    cleaned[k] = v
                continue
            enum = TEXT_STYLE_ENUMS.get(k)
            if enum is None or v not in enum:
                raise ValueError("bad text style")
            if v != enum[0]:                      # enum[0] is the default
                cleaned[k] = v
        cur = self.store.profile_layout(self.identity_pub)
        texts = dict(cur["texts"])
        if cleaned:
            texts[msg_id] = cleaned
        else:
            texts.pop(msg_id, None)
        if len(texts) > MAX_LAYOUT:
            raise ValueError("too many styled blocks")
        return self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=cur["pins"], spans=cur["spans"],
            texts=texts))

    def set_album(self, members, album_id: str | None = None) -> str:
        """Group own profile photo posts into a growable album (collage
        Slice C). Members must be THIS identity's own profile-placement
        photo posts; empty members un-groups an EXISTING album. The record
        carries opaque ids only - content stays per-post encrypted."""
        if not isinstance(members, list) or len(members) > MAX_LAYOUT:
            raise ValueError("bad album members")
        if len(set(members)) != len(members):
            raise ValueError("duplicate album member")
        if not members and album_id is None:
            raise ValueError("empty album needs an album_id (ungroup)")
        for mid in members:
            self._check_block_id(mid)
            msg = self.store.get_message(mid)
            if msg is None or msg.cert.identity_pub != self.identity_pub:
                raise ValueError("album member must be your own post")
            pl = msg.payload
            if pl.get("kind") != KIND_POST or pl.get("placement") != "profile":
                raise ValueError("album member must be a profile post")
            if pl.get("media") == "video" or not pl.get("blobs"):
                raise ValueError("album members are photo posts")
        if album_id is None:
            album_id = secrets.token_hex(32)
        else:
            self._check_block_id(album_id)
        # album_id shares the msg_id namespace (pins/spans/settings-modal
        # all key on it uniformly) - refuse one that collides with a real
        # message id so an album pseudo-block can never be confused with,
        # or shadow, an actual message (review finding).
        if self.store.get_message(album_id) is not None:
            raise ValueError("album_id collides with a message id")
        if members:
            # The album/pin-map interplay (review finding): a grouped
            # member is never independently placed, so folding it into an
            # album must clear any pin it held, restoring it to `spans`
            # (kept size, unplaced) - an ungroup later then honestly
            # top-inserts the member back onto the canvas with no stale
            # pin able to overlap anything. If exactly one member carried
            # a pin and the album itself has no pin yet, the album
            # inherits that member's exact geometry (growing a pinned
            # post's deck no longer silently un-places it); two-or-more
            # pinned members have no deterministic choice of which
            # geometry wins, so the album lands unplaced instead.
            cur = self.store.profile_layout(self.identity_pub)
            pins = dict(cur["pins"])
            spans = dict(cur["spans"])
            pinned_geoms = [pins[m] for m in members if m in pins]
            for mid in members:
                geom = pins.pop(mid, None)
                if geom is not None:
                    spans[mid] = {"w": geom["w"], "h": geom["h"]}
            if album_id not in pins and len(pinned_geoms) == 1:
                pins[album_id] = dict(pinned_geoms[0])
            if len(spans) > MAX_LAYOUT:      # pre-check -> 400, not a 500 from _publish
                raise ValueError("too many sized blocks")
            self._publish(make_profile_layout(
                self.device, cur["order"], grids=cur["grids"],
                sizes=cur["sizes"], pins=pins, spans=spans,
                texts=cur["texts"]))
        else:
            # Ungroup (spec 2026-07-14): read the outgoing record's
            # members from the store BEFORE publishing the empty one
            # below (that publish IS what erases them). Fold in the
            # album's own residual pin/span here too (review finding,
            # folded in since this branch is being touched anyway), then
            # first-fit each restored member NEWEST first so the newest
            # claims the highest open slot (spec 2026-07-15) - nothing
            # already placed moves, no limbo.
            prior_members = self.store.albums(self.identity_pub).get(album_id, [])
            cur = self.store.profile_layout(self.identity_pub)
            pins = dict(cur["pins"])
            spans = dict(cur["spans"])
            pins.pop(album_id, None)
            spans.pop(album_id, None)
            if prior_members:
                def _created_at(mid):
                    msg = self.store.get_message(mid)
                    return msg.payload["created_at"] if msg else 0
                for mid in sorted(prior_members, key=_created_at, reverse=True):
                    span = spans.pop(mid, None)
                    if span is None:
                        msg = self.store.get_message(mid)
                        pl = msg.payload if msg else {}
                        has_media = (bool(pl.get("blobs"))
                                    or pl.get("media") == "video")
                        span = {"w": 2, "h": 2} if has_media else {"w": 4, "h": 1}
                    pins = self._first_fit_place(pins, mid, span)
            if len(pins) > MAX_LAYOUT:
                raise ValueError("too many pinned blocks")
            self._publish(make_profile_layout(
                self.device, cur["order"], grids=cur["grids"],
                sizes=cur["sizes"], pins=pins, spans=spans,
                texts=cur["texts"]))
        self._publish(make_album(self.device, album_id, members))
        return album_id

    @staticmethod
    def _fold_album_members(albums: dict) -> dict:
        """{member_msg_id: winning_album_id}. A member claimed by two
        albums folds into the lexically-smallest album_id -
        content-deterministic, so every device resolves the conflict
        identically regardless of ingest order (dict order is SQL scan
        order, which differs across devices). This is profile_view's
        rendering rule; auto_place_unplaced shares it so migration
        candidacy can never disagree with what profile_view actually
        renders (review finding: auto_place_unplaced pinned an album
        whose only member was shadowed elsewhere - a permanent invisible
        hole in the grid, reachable via two own devices minting albums
        around the same post offline)."""
        member_of = {}
        for aid, mids in sorted(albums.items()):
            for mid in mids:
                member_of.setdefault(mid, aid)     # smallest album_id wins a conflict
        return member_of

    def auto_place_unplaced(self) -> int:
        """One-shot migration (spec 2026-07-14): pin every unplaced own
        wall block at the top, oldest first so the newest ends on top.
        One layout publish; zero-publish no-op when nothing is unplaced."""
        cur = self.store.profile_layout(self.identity_pub)
        pins = dict(cur["pins"])
        spans = dict(cur["spans"])
        posts = self.posts_by(self.identity_pub, "profile")
        by_id = {p["msg_id"]: p for p in posts}
        # posts is posts_by's canonical newest-first order (created_at DESC,
        # rowid DESC - the 0.3.10 store fix). rank turns that ordering into
        # a tie-break: 0 = newest. This rank is LOCAL (rowid arrival order)
        # - acceptable here because auto_place_unplaced is the owner-device
        # migration that PUBLISHES a layout record (other devices receive
        # the record and never recompute it), unlike _push_place's own
        # (y, x, msg_id) tie-break, which stays content-deterministic
        # because every device runs it.
        rank = {p["msg_id"]: i for i, p in enumerate(posts)}
        albums = self.store.albums(self.identity_pub)
        member_of = self._fold_album_members(albums)
        album_members = set(member_of)
        candidates = []          # (msg_id, created_at, rank, default_span)
        for p in posts:
            mid = p["msg_id"]
            if mid in pins or mid in album_members:
                continue        # already placed, or rendered inside its album
            has_media = bool(p.get("blobs")) or p.get("media") == "video"
            candidates.append((mid, p["created_at"], rank[mid],
                               {"w": 2, "h": 2} if has_media else {"w": 4, "h": 1}))
        for aid, mids in albums.items():
            if aid in pins:
                continue
            newest = None
            newest_rank = None
            for mid in mids:
                if member_of.get(mid) != aid:
                    continue    # shadowed by a lexically-smaller album -
                                # profile_view will never render this
                                # member here, so aid can't be a candidate
                m = by_id.get(mid)
                if m is None or m.get("media") == "video" or not m.get("blobs"):
                    continue
                if newest is None or m["created_at"] > newest or (
                        m["created_at"] == newest and rank[mid] < newest_rank):
                    newest = m["created_at"]
                    newest_rank = rank[mid]
            if newest is not None:       # an unpinned album with photos counts
                candidates.append((aid, newest, newest_rank, {"w": 2, "h": 2}))
        if not candidates:
            return 0
        # oldest first -> newest ends on top; created_at alone ties on
        # same-second composes, so the secondary key rides posts_by's
        # canonical rank instead of relying on sort-stability happenstance
        # (which reversed newest-on-top: build order is newest-first).
        candidates.sort(key=lambda c: (c[1], -c[2]))
        for mid, _created_at, _rank, default_span in candidates:
            span = spans.pop(mid, None) or default_span
            pins = self._push_place(pins, mid, {"x": 0, "y": 0, **span})
        if len(pins) > MAX_LAYOUT:
            raise ValueError("too many pinned blocks")
        self._publish(make_profile_layout(
            self.device, cur["order"], grids=cur["grids"],
            sizes=cur["sizes"], pins=pins, spans=spans, texts=cur["texts"]))
        return len(candidates)

    def profile_view(self, identity_pub: str):
        if identity_pub != self.identity_pub \
                and not self.store.is_known(identity_pub):
            return None
        rec = self.store.profile(identity_pub)
        if rec is None:
            if identity_pub != self.identity_pub:
                return None
            rec = {"name": self.store.profiles().get(
                       identity_pub, identity_pub[:8]),
                   "bio": "", "accent": "#2743d6", "avatar": None,
                   "avatar_shape": "circle", "avatar_size": "m",
                   "avatar_align": "left", "banner": None,
                   "banner_pos": 50}
        ring, since = (("kreds", None) if identity_pub == self.identity_pub
                       else self._ring_and_since(identity_pub))
        # The collage is geometry-ruled (spec 2026-07-13): pins say where a
        # block sits; unpinned blocks flow newest-first (posts_by's order).
        # The legacy order/grids maps ride the wire untouched but no longer
        # shape rendering; legacy sizes map to default spans so a
        # never-arranged wall still has sane geometry.
        wall = self.posts_by(identity_pub, "profile")   # newest-first
        layout = self.store.profile_layout(identity_pub)
        pins, spans, sizes = layout["pins"], layout["spans"], layout["sizes"]
        texts = layout["texts"]
        # Fully-defaulted so the client never guesses (spec 2026-07-14):
        # enum defaults are each tuple's FIRST value; color's default is
        # "default" (theme ink), not part of TEXT_STYLE_ENUMS.
        text_style_defaults = {k: v[0] for k, v in TEXT_STYLE_ENUMS.items()}
        text_style_defaults["color"] = "default"

        def _default_span(p):
            size = sizes.get(p["msg_id"], "full")
            if size == "small":
                return {"w": 1, "h": 1}
            if size == "wide":
                return {"w": 2, "h": 2}
            has_media = bool(p.get("blobs")) or p.get("media") == "video"
            return {"w": 4, "h": 2} if has_media else {"w": 4, "h": 1}

        for p in wall:
            pin = pins.get(p["msg_id"])
            p["pin"] = pin
            if pin is not None:
                p["span"] = {"w": pin["w"], "h": pin["h"]}
            else:
                p["span"] = spans.get(p["msg_id"]) or _default_span(p)
            # Text styling annotation (spec 2026-07-14): plain text blocks
            # only - never blobs (photos), never video. Album pseudo-blocks
            # are synthesized below, after this loop, so they never pass
            # through here.
            if not p.get("blobs") and p.get("media") != "video":
                p["text_style"] = {**text_style_defaults,
                                   **texts.get(p["msg_id"], {})}

        # Album folding (Slice C): members render inside their album's
        # deck, never standalone; the album pseudo-block borrows the
        # msg_id slot (album_id, same 64-hex width) so every layout path
        # (pins/spans/drag/modal) treats it like any block. A viewer's
        # deck holds only members THEY decrypted; an album with none is
        # simply absent (honest hole). Empty members = ungrouped.
        albums = self.store.albums(identity_pub)
        by_id = {p["msg_id"]: p for p in wall}
        # _fold_album_members resolves a member claimed by two albums into
        # the lexically-smallest album_id -- content-deterministic, so
        # every device resolves the conflict identically regardless of
        # ingest order (dict order here is SQL scan order, which differs
        # across devices). auto_place_unplaced shares this same fold.
        member_of = self._fold_album_members(albums)
        folded = [p for p in wall if p["msg_id"] not in member_of]
        for aid, mids in sorted(albums.items()):
            photos, newest, scope_newest = [], None, "kreds"
            for mid in mids:
                p = by_id.get(mid)
                if p is None or member_of.get(mid) != aid:
                    continue                        # undecryptable/unknown/conflicted
                if p.get("media") == "video":
                    continue    # hostile/legacy record naming a video post - never fold an mp4 into photos
                for h in p.get("blobs") or []:
                    photos.append({"m": mid, "h": h})
                if newest is None or p["created_at"] > newest:
                    newest = p["created_at"]
                    scope_newest = p.get("scope", "kreds")
            if not photos:
                continue
            pin = pins.get(aid)
            folded.append({
                "album": True, "msg_id": aid,
                "mine": identity_pub == self.identity_pub,
                "photos": photos, "count": len(photos),
                "created_at": newest, "scope_newest": scope_newest,
                "pin": pin,
                "span": ({"w": pin["w"], "h": pin["h"]} if pin
                         else spans.get(aid) or {"w": 2, "h": 2}),
            })
        folded.sort(key=lambda p: p["created_at"], reverse=True)
        wall = folded

        return {**rec, "identity_pub": identity_pub,
                "mine": identity_pub == self.identity_pub,
                "ring": ring, "since": since,
                "wall": wall,
                "journal": self.posts_by(identity_pub, "journal")}

    def delete_post(self, target_msg_id: str) -> str:
        if self.store.message_kind(target_msg_id) == KIND_DELETE:
            raise ValueError("cannot delete a delete tag")
        return self._publish(make_delete(self.device, target_msg_id))

    def _decrypt_post_row(self, msg, names, now, avatars=None):
        avatars = avatars or {}
        p = msg.payload
        if p.get("expires_at") is not None and p["expires_at"] <= now:
            return None
        key, aad = self._content_key(msg)
        if key is None:
            return None                              # not for this device
        body = decrypt_body(key, p["body_nonce"], p["body_ct"], aad)
        if body is None:
            return None
        ipub = msg.cert.identity_pub
        return {
            "msg_id": msg.msg_id, "identity_pub": ipub,
            "author_name": names.get(ipub, ipub[:8]),
            "author_avatar": avatars.get(ipub),
            "text": body["text"], "blobs": body["blobs"],
            "scope": p["scope"], "created_at": p["created_at"],
            "expires_at": p.get("expires_at"),
            "mine": ipub == self.identity_pub,
            "placement": p.get("placement", "journal"),
            "media": p.get("media", "photo"),
            "poster": p.get("poster"),
        }

    def feed(self) -> List[dict]:
        now = time.time()
        names = self.store.profiles()
        avatars = self.store.profile_avatars()
        out = []
        for msg in self.store.post_messages():
            row = self._decrypt_post_row(msg, names, now, avatars)
            if row is not None and row["placement"] == "journal":
                out.append(row)
        return out

    def posts_by(self, identity_pub: str, placement=None) -> List[dict]:
        now = time.time()
        names = self.store.profiles()
        avatars = self.store.profile_avatars()
        out = []
        for msg in self.store.post_messages(identity_pub):
            row = self._decrypt_post_row(msg, names, now, avatars)
            if row is not None and (placement is None
                                    or row["placement"] == placement):
                out.append(row)
        return out

    def devices(self) -> List[dict]:
        views = self.store.load_views(self.identity_pub)
        return [{
            "device_pub": dpub,
            "name": v.cert.device_name if v.cert else "(unknown)",
            "revoked": v.revocation is not None,
            "this_device": dpub == self.device.device_pub,
        } for dpub, v in views.items()]

    def revoke_device(self, device_pub: str) -> IngestResult:
        if device_pub == self.device.device_pub:
            raise ValueError("cannot revoke this device from itself")
        views = self.store.load_views(self.identity_pub)
        view = views.get(device_pub)
        bound = view.seen.max_seen() if view else 0
        rev = self.device.make_revocation(device_pub, bound)
        result = self.store.ingest_revocation(rev)
        self.notify()
        return result

    # -- unfriend / defriend notice ---------------------------------------------

    def unfriend(self, identity_pub: str) -> None:
        """Local teardown + queue a signed notice for delivery. The notice
        is minted the same way revoke_device mints a RevocationCert: via
        this node's own enrolled device (self.device), never a bare
        identity key."""
        if identity_pub == self.identity_pub:
            raise ValueError("cannot unfriend yourself")
        notice = self.device.make_defriend(identity_pub)
        addr = self.store.address_for(identity_pub) or ""   # best-known address
        self.store.unfriend_teardown(self.identity_pub, identity_pub)
        self.store.add_outbox(notice, addr,
                              expires_at=notice.created_at + DEFRIEND_TTL)
        self.notify()

    def apply_defriend_notice(self, notice) -> bool:
        """Receiving-side retention rule: verify signature + that the
        notice targets me + that the author is currently known, then
        purge their content, drop the identity, and mark them
        disconnected. Idempotent: once applied, the author is no longer
        known so a re-delivered notice is ignored (returns False)."""
        if notice.target_identity != self.identity_pub:
            return False
        # Self-author guard (whole-branch review, Fix 7): a notice that
        # targets AND claims to be authored by me must never be allowed
        # to purge my own identity's content -- this can only be a bug
        # or a forgery (verify() would fail on a real forgery anyway,
        # since it'd have to be signed by my own identity key, but this
        # is belt-and-braces against ever reaching that check).
        if notice.author_identity == self.identity_pub:
            return False
        if not notice.verify():
            return False
        author = notice.author_identity
        if not self.store.is_known(author):
            return False                       # unknown / already applied
        name = self.store.profiles().get(author, author[:8])
        self.store.purge_authored_by(author)
        self.store.remove_identity(author)
        # Mirror unfriend_teardown's local cleanup (whole-branch review,
        # Fixes 4 + 5): otherwise the gossip loop keeps dialing the
        # ex-friend's known address forever (IP disclosure), and a stale
        # device_views row (enrollment cert / revocation) for them can
        # linger and even cause a later session to wrongly refuse them.
        self.store.remove_peer_identity(author)
        self.store.remove_device_views(author)
        self.store.add_disconnected(author, name)
        self.notify()
        return True

    async def deliver_defriends(self, now=None) -> List[str]:
        """Direct-only delivery pass over the outbox: dial each notice's
        target directly (never mesh/broadcast it -- the `defriends`
        session phase only ever carries a notice to its exact target)
        and drop the record only once the target ACKS having applied it,
        or once the 14-day window expires. A session merely completing
        without raising is NOT an ack -- the recipient may have declined
        (e.g. a tampered notice failing notice.verify(), or any other
        reason apply_defriend_notice returned False) while still holding
        our content, so the record is kept for retry until the target's
        own applied-list actually names us.

        Both the ack and a mid-window refusal are bound to the peer
        _sync_session actually AUTHENTICATED this dial as (whole-branch
        review, Fix 1 + Fix 3), never to the bare address on file: a
        poisoned peers-table row could otherwise freeze a malicious
        node's address into the outbox, and that node's own (unverified)
        `applied` claim -- or its own refusal -- must not be able to
        speak for the real target.

        Returns the target_identity of every record dropped because it
        was acked this round (expiry / no-address / refused-by-non-
        target-node drops are not included -- those are a give-up or a
        cleanup, not a confirmation)."""
        now = now if now is not None else time.time()
        acked = []
        for rec in self.store.list_outbox():
            target = rec["target_identity"]
            if now >= rec["expires_at"] or not rec["address"]:
                self.store.drop_outbox(target)
                continue
            if rec["next_attempt_at"] > now:
                continue            # backoff window not elapsed yet
            if self._dial is None:
                continue            # no transport wired yet; retry later
            ok, peer_identity, applied = await self._dial(rec["address"])
            if peer_identity == target and self.identity_pub in applied:
                # A genuine application-level ack, from the identity the
                # AUTH device-key challenge actually proved to be the
                # record's target -- not merely whoever answered at the
                # address on file.
                self.store.drop_outbox(target)
                acked.append(target)
            elif (not ok) and peer_identity == target:
                # The record's own target refused this dial outright: it
                # no longer knows us, meaning it already applied our
                # notice on some earlier round it didn't get to ack (spec
                # Component 2: a refusal BY THE TARGET is cleanup, not a
                # fresh deletion trigger). A refusal from any OTHER
                # authenticated node (e.g. one answering at a poisoned
                # address) fails this check and falls through to retry.
                self.store.drop_outbox(target)
            else:
                # Unreachable, declined, or answered by a different node
                # entirely: keep the record but back off so the ~3s
                # gossip loop does not re-dial the same address every
                # tick; the expiry check above still eventually cleans up
                # a target that never comes back (or never truly applies).
                self.store.set_outbox_retry(target, now + DEFRIEND_RETRY)
        return acked

    # -- friend ceremony (copy-paste stands in for the QR scan) -----------------

    def create_invite(self, ttl_seconds=600) -> str:
        self._pending_invites = {}          # single active: drop any prior unused invite
        nonce = os.urandom(16).hex()
        expiry = time.time() + ttl_seconds
        self._pending_invites[nonce] = expiry
        return invitecodec.encode_invite(
            self.device.cert.identity_pub[:8], self.store.get_meta("gossip_addr"),
            nonce, expiry)

    def respond_to_invite(self, invite_json: str) -> str:
        try:
            typ, inv = invitecodec.decode(invite_json)
        except ValueError:
            raise ValueError("not an invite")
        if typ != "invite":
            raise ValueError("not an invite")
        # no cert to verify here anymore (it arrives in the final); keep the
        # id_prefix so complete_invite can bind it.
        my_nonce = os.urandom(16).hex()
        self._pending_responses = {k: v for k, v in self._pending_responses.items()
                                   if v[2] > time.time()}          # purge expired (v[2]=expiry)
        # store (id_prefix, addr, expiry, invite_nonce) - expiry is index 2,
        # which is what the purge above and complete_invite's unpack both read.
        self._pending_responses[my_nonce] = (
            inv["id_prefix"], inv["addr"], time.time() + 600, inv["nonce"])
        return invitecodec.encode_response(
            self.store.get_meta("gossip_addr"), inv["nonce"], my_nonce,
            self.device.sign_raw(_friend_add_body(inv["nonce"])),
            self.device.cert.to_dict())

    def finalize_invite(self, response_json: str) -> str:
        try:
            typ, resp = invitecodec.decode(response_json)
        except ValueError:
            raise ValueError("no matching invite")
        if typ != "response":
            raise ValueError("no matching invite")
        nonce = resp["nonce"]
        exp = self._pending_invites.get(nonce)
        if exp is None:
            raise ValueError("no matching invite")
        if time.time() >= exp:
            del self._pending_invites[nonce]
            raise ValueError("invite expired")
        cert = EnrollmentCert.from_dict(resp["cert"])
        if not cert.verify() or not _sig_ok(
                cert.device_pub, resp["sig"], _friend_add_body(nonce)):
            raise ValueError("invalid response signature")
        # Sign BEFORE mutating (task review CRITICAL, defense-in-depth):
        # sign_raw raises RuntimeError("locked") on a locked device. Doing
        # the signature FIRST means a locked call fails here with nothing
        # yet touched -- the pending invite is still there and no friend
        # has been added -- instead of the old order (delete pending,
        # _add_friend, THEN sign), which mutated the store first and only
        # discovered "locked" on the very last step. _handle_friend_add's
        # locked guard is the primary defense (this should never actually
        # be reached while locked); this reorder is what makes finalize_
        # invite itself fail safe even if that guard is ever bypassed or
        # this method is called directly. Response validation above
        # (nonce present + not expired + cert.verify + sig_ok) is
        # unchanged and still runs first.
        #
        # Consume the nonce AFTER the store write (whole-branch review,
        # Fix 3): if _add_friend ever raised, deleting the nonce first
        # would consume it with no friend actually added -- a permanently
        # stuck invite the peer could never retry. sign -> _add_friend ->
        # del nonce keeps every step ordered from "detectable failure,
        # nothing consumed" to "fully applied, then consumed".
        sig = self.device.sign_raw(_friend_add_body(resp["peer_nonce"]))
        self._add_friend(cert, resp["addr"])
        del self._pending_invites[nonce]
        # final now carries A's cert (moved out of the invite)
        return invitecodec.encode_final(resp["peer_nonce"], sig, self.device.cert.to_dict())

    def complete_invite(self, final_json: str):
        try:
            typ, fin = invitecodec.decode(final_json)
        except ValueError:
            raise ValueError("no matching response")
        if typ != "final":
            raise ValueError("no matching response")
        entry = self._pending_responses.get(fin["nonce"])
        if entry is None:
            raise ValueError("no matching response")
        id_prefix, addr, exp, _a_nonce = entry
        if time.time() >= exp:
            del self._pending_responses[fin["nonce"]]
            raise ValueError("response expired")
        cert = EnrollmentCert.from_dict(fin["cert"])
        # binding check: the identity that completed must match the invite's
        # fingerprint (spec 2026-07-10-compact-invite; forces full
        # substitution, makes the human 4-char check meaningful).
        if cert.identity_pub[:8] != id_prefix:
            raise ValueError("identity does not match invite fingerprint")
        if not cert.verify() or not _sig_ok(cert.device_pub, fin["sig"],
                                            _friend_add_body(fin["nonce"])):
            raise ValueError("invalid final signature")
        del self._pending_responses[fin["nonce"]]
        self._add_friend(cert, addr)

    async def add_friend_via_invite(self, invite_json: str) -> dict:
        """B's side: build the response, deliver it to A over Tor, and complete
        the add automatically. Falls back to returning the response for manual
        copy-paste when A is unreachable (or _friend_dial isn't wired -- e.g.
        no SyncService). respond_to_invite is the only validation of A's
        invite here (bad invite/malformed code/etc raises before any dial is
        attempted); the auto-delivered response is authenticated on A's end
        solely by finalize_invite (nonce must match a live, non-expired
        pending invite + valid sig) -- this method adds no friend-auth of its
        own. A's cert no longer travels in the invite (spec
        2026-07-10-compact-invite -- it moved to the final), so the display
        name below is read from the FINAL's cert, decoded again after
        complete_invite has already verified it (cert + sig + binding
        check) -- not from the invite."""
        resp = self.respond_to_invite(invite_json)      # validates A's invite (raises on bad)
        typ, inv = invitecodec.decode(invite_json)
        addr = inv.get("addr")
        final = None
        if self._friend_dial and addr:
            final = await self._friend_dial(addr, resp)
        if final:
            try:
                self.complete_invite(final)             # adds A (verifies A's cert + sig + binding)
            except (ValueError, KeyError, TypeError):
                # A malformed/bogus friend-final (protocol error, tampered
                # relay, buggy peer) -- complete_invite verifies A's sig
                # BEFORE mutating, so a failure here has added nobody.
                # Fall through to the same manual fallback used when A is
                # unreachable: B still holds its own response to hand
                # over by hand instead of raising out of this method.
                return {"status": "manual", "response": resp}
            _, fin = invitecodec.decode(final)          # already verified above; cert lives here now
            cert = EnrollmentCert.from_dict(fin["cert"])
            return {"status": "connected",
                    "friend": self.store.profiles().get(
                        cert.identity_pub, cert.identity_pub[:8])}
        return {"status": "manual", "response": resp}

    def _add_friend(self, cert: EnrollmentCert, addr):
        self.store.add_identity(cert.identity_pub)
        # If we'd previously unfriended this identity and a signed
        # notice is still queued for delivery (14-day retry window not
        # yet expired), drop it now: re-adding them as a friend must not
        # leave a stale notice in flight that would later arrive and
        # silently undo this re-friend.
        self.store.drop_outbox(cert.identity_pub)
        # Re-friending clears the "no longer connected" marker a
        # previously-applied defriend notice may have left: a re-added
        # friend must show as a friend, not as still disconnected.
        self.store.remove_disconnected(cert.identity_pub)
        views = self.store.load_views(cert.identity_pub)
        if cert.device_pub not in views:
            views[cert.device_pub] = DeviceView(cert=cert)
            self.store.save_views(cert.identity_pub, views)
        if addr:
            self.store.add_peer(addr, cert.identity_pub)
        self.notify()

    # -- device pairing (copy-paste stands in for the secure local channel) ------

    @classmethod
    def pair_request(cls, data_dir, device_name: str) -> str:
        data_dir = Path(data_dir)
        data_dir.mkdir(parents=True, exist_ok=True)
        device = DeviceKeys.create(device_name)
        _atomic_write(data_dir / "keys.json", json.dumps(device.to_json()))
        return json.dumps({
            "t": "hearth-pair-request", "protocol": PROTOCOL,
            "device_pub": device.device_pub, "device_name": device_name,
        })

    def accept_pairing(self, request_json: str) -> str:
        try:
            req = json.loads(request_json)
        except json.JSONDecodeError:
            raise ValueError("pairing request is not valid JSON")
        if req.get("t") != "hearth-pair-request":
            raise ValueError("not a pairing request")
        cert = self.device.enroll_other(req["device_pub"],
                                        req["device_name"])
        views = self.store.load_views(self.identity_pub)
        views[req["device_pub"]] = DeviceView(cert=cert)
        self.store.save_views(self.identity_pub, views)
        self.notify()
        return json.dumps({
            "t": "hearth-pair-package", "protocol": PROTOCOL,
            "cert": cert.to_dict(),
            "identity_priv": self.device.to_json()["identity_priv"],
            "friends": [i for i in self.store.known_identities()
                        if i != self.identity_pub],
            "peers": self.store.list_peers(),
            "my_addr": self.store.get_meta("gossip_addr"),
        })

    @classmethod
    def pair_install(cls, data_dir, package_json: str) -> "HearthNode":
        data_dir = Path(data_dir)
        pkg = json.loads(package_json)
        if pkg.get("t") != "hearth-pair-package":
            raise ValueError("not a pairing package")
        device = DeviceKeys.from_json(
            json.loads((data_dir / "keys.json").read_text()))
        device.install(EnrollmentCert.from_dict(pkg["cert"]),
                       pkg["identity_priv"])
        _atomic_write(data_dir / "keys.json", json.dumps(device.to_json()))
        node = cls(data_dir)
        node.store.add_identity(device.identity_pub, is_self=True)
        node.store.save_views(device.identity_pub, {
            device.device_pub: DeviceView(cert=device.cert)})
        for ident in pkg.get("friends", []):
            node.store.add_identity(ident)
        for p in pkg.get("peers", []):
            node.store.add_peer(p["address"], p.get("identity_pub"))
        if pkg.get("my_addr"):
            node.store.add_peer(pkg["my_addr"], device.identity_pub)
        return node

    # -- encrypted DMs ---------------------------------------------------------

    def ensure_enckey(self):
        if self.revoked or self.locked:
            return
        latest = self.store.enckeys(self.identity_pub).get(
            self.device.device_pub)
        if latest != self.device.enc_pub:
            self._publish(make_enckey(self.device))

    def maintain_enckey(self, now: Optional[float] = None):
        """Periodic key hygiene (startup + gossip loop): publish the enc
        key if missing/stale, rotate it past ENC_ROTATION_PERIOD, prune
        retired keys past grace. Deletion of retired keys is the forward
        secrecy; publication rides the existing enckey message kind.

        Guarded on `self.locked` (App-lock): a locked node has no
        enc_priv/retired_enc to rotate or prune, and any publish would need
        device.sign_message, which raises while locked -- skip entirely
        until unlock() re-enables this (mirrors the pre-existing
        `self.revoked` guard, which has the same shape of reasoning)."""
        if self.revoked or self.locked or self.device.identity_pub is None:
            return
        now = now if now is not None else time.time()
        if self.device.prune_retired(now):
            self._save_keys()
        rec = self.store.enckey_records(self.identity_pub).get(
            self.device.device_pub)
        if rec is None or rec[1] != self.device.enc_pub:
            self._publish(make_enckey(self.device, now=now))
        elif now - rec[0] >= ENC_ROTATION_PERIOD:
            self.device.rotate_enc(now)
            self._save_keys()
            self._publish(make_enckey(self.device, now=now))

    def maintain_wrap_grants(self, now: Optional[float] = None):
        """'A wall is a wall' (0.3.11): kreds-scope PROFILE posts are for
        CURRENT friends, not friends-at-post-time. For each own kreds wall
        post, mint an author-signed wrap_grant covering any current
        friend device the payload wraps + existing grants miss. Also heals
        the enckey-not-yet-synced transient (a post composed right after
        friend-add gets granted on the next sweep). Journal and inner are
        deliberately untouched -- a journal is a moment in time, and inner
        stays future-only (spec 2026-07-15-wall-wrap-grants).

        Guard mirrors maintain_enckey: minting signs, so locked/revoked/
        unenrolled skip entirely. Multi-device races just union."""
        if self.revoked or self.locked or self.device.identity_pub is None:
            return
        now = now if now is not None else time.time()
        friends = [i for i in self.store.known_identities()
                   if i != self.identity_pub]
        if not friends:
            return
        # Hoist the per-friend enckey scan out of the post loop: it is
        # identity-scoped, not post-scoped, so running it inside the loop
        # was P*F kind-scan queries every ~3s round (whole-branch review).
        friend_keys = {f: self.store.enckeys(f) for f in friends}
        for msg in self.store.post_messages(self.identity_pub):
            p = msg.payload
            if (p.get("placement", "journal") != "profile"
                    or p.get("scope") != "kreds" or is_expired(p, now)):
                continue
            wrapped = set(p.get("wraps", {}))
            granted = self.store.wrap_grants(msg.msg_id, self.identity_pub)
            grantable = {}       # every current friend device the post does
                                 # NOT already wrap -> its CURRENT enc key
            missing = False      # any grantable device lacking coverage?
            for friend in friends:
                for dpub, enc_pub in friend_keys[friend].items():
                    if dpub in wrapped:
                        continue
                    grantable[dpub] = enc_pub
                    g = granted.get(dpub)
                    # covered only if a prior grant sealed to the device's
                    # CURRENT enc key (enc_pub annotation from mint time; a
                    # pre-annotation grant counts as covered)
                    if g is None or g.get("enc_pub", enc_pub) != enc_pub:
                        missing = True
            if not missing:
                continue
            key, aad = self._content_key(msg)
            if key is None:
                continue    # own post yet unrecoverable: never mint garbage
            # FULL-COVERAGE mint: wrap to ALL currently-grantable devices,
            # not just the missing ones, so the newest grant per (author,
            # target) is self-sufficient. This is the invariant
            # store.prune_superseded_wrap_grants relies on to safely
            # tombstone every older (possibly partial) grant -- the two
            # must stay together.
            wraps = wrap_key(key, grantable, aad)
            for dpub in wraps:
                wraps[dpub]["enc_pub"] = grantable[dpub]
            if wraps:
                self._publish(make_wrap_grant(self.device, msg.msg_id,
                                              wraps, now=now))

    def _dm_device_pubs(self, to_identity: str) -> dict:
        theirs = self.store.enckeys(to_identity)
        if not theirs:
            raise ValueError(
                "no encryption keys known for recipient yet")
        mine = self.store.enckeys(self.identity_pub)
        mine[self.device.device_pub] = self.device.enc_pub
        return {**theirs, **mine}

    def compose_dm(self, to_identity: str, text: str,
                   photos=(), expires_seconds=None) -> str:
        if to_identity == self.identity_pub:
            raise ValueError("cannot DM yourself")
        if not self.store.is_known(to_identity):
            raise ValueError("recipient is not a friend")
        pubs = self._dm_device_pubs(to_identity)
        created_at = time.time()
        expires_at = (created_at + expires_seconds
                      if expires_seconds is not None else None)
        aad = dm_aad(self.identity_pub, to_identity, created_at)
        key = new_content_key()
        gated = [transcode_photo(p) for p in photos]       # raises ValueError
        refs = [self.store.put_blob(encrypt_blob(key, p)) for p in gated]
        nonce, ct = encrypt_body(key, {"text": text, "blobs": refs}, aad)
        wraps = wrap_key(key, pubs, aad)
        mid = self._publish(make_dm(self.device, to_identity, nonce, ct,
                                    wraps, created_at, refs, expires_at))
        self._cache_message_key(mid, key)
        return mid

    def _cache_message_key(self, msg_id: str, key: bytes):
        if self.device.storage_key is None:
            return
        if self.store.cached_message_key(msg_id) is None:
            self.store.cache_message_key(msg_id, seal_content_key(
                self.device.storage_key, msg_id, key))

    def _replace_message_key(self, msg_id: str, key: bytes):
        if self.device.storage_key is None:
            return
        self.store.replace_message_key(msg_id, seal_content_key(
            self.device.storage_key, msg_id, key))

    def _content_key(self, msg):
        p = msg.payload
        kind = p["kind"]
        if kind == KIND_DM:
            aad = dm_aad(msg.cert.identity_pub, p["to"], p["created_at"])
        else:                                        # post
            aad = post_aad(msg.cert.identity_pub, p["scope"], p["created_at"])
        # 1) local cache: survives enc-key rotation and grace deletion
        sealed = self.store.cached_message_key(msg.msg_id)
        stale_row = False
        if sealed is not None and self.device.storage_key is not None:
            key = open_content_key(self.device.storage_key, msg.msg_id,
                                   sealed)
            if key is not None:
                return key, aad
            stale_row = True    # row present but unopenable: heal it below
        # 2) envelope unwrap: current key first, then retired (grace)
        for priv in self.device.enc_privs():
            key = unwrap_key(p["wraps"], self.device.device_pub, priv, aad)
            if key is not None:
                if stale_row:
                    self._replace_message_key(msg.msg_id, key)
                else:
                    self._cache_message_key(msg.msg_id, key)
                return key, aad
        # 3) wall wrap-grant unwrap (kreds back-catalog): only grants
        # signed by the POST'S author count -- store.wrap_grants filters
        # on author, so a hostile friend's grant naming someone else's
        # post is inert here (and in routing).
        if kind == KIND_POST:
            grants = self.store.wrap_grants(msg.msg_id,
                                            msg.cert.identity_pub)
            if grants:
                for priv in self.device.enc_privs():
                    key = unwrap_key(grants, self.device.device_pub, priv,
                                     aad)
                    if key is not None:
                        if stale_row:
                            self._replace_message_key(msg.msg_id, key)
                        else:
                            self._cache_message_key(msg.msg_id, key)
                        return key, aad
        return None, aad

    def cache_message_keys(self):
        """Eagerly cache content keys for DMs/posts that arrived via sync,
        so history survives rotation even on nodes that never display.

        Wart 3: also negative-caches messages that fail to decrypt, so a
        permanently-undecryptable message (e.g. wrapped to a device key we
        never held) isn't retried every gossip round forever. Locked (or
        storage key absent): EVERYTHING fails to decrypt - recording now
        would mass-poison the negative cache, so skip entirely rather than
        guard the mark_undecryptable call alone (revoked nodes already
        clear storage_key, so that guard covers both cases).
        Guard mirrors maintain_enckey's (revoked/locked/unenrolled) plus storage_key (needed to seal cache rows)."""
        if (self.revoked or self.locked or self.device.identity_pub is None
                or self.device.storage_key is None):
            return
        for mid in self.store.uncached_message_ids(self.identity_pub):
            msg = self.store.get_message(mid)
            if msg is not None:
                key, _ = self._content_key(msg)
                if key is None:
                    self.store.mark_undecryptable(mid)

    def dm_thread(self, other_identity: str):
        out = []
        for msg in self.store.dm_thread(self.identity_pub, other_identity):
            key, aad = self._content_key(msg)
            body = (decrypt_body(key, msg.payload["body_nonce"],
                                 msg.payload["body_ct"], aad)
                    if key else None)
            out.append({
                "msg_id": msg.msg_id,
                "from_me": msg.cert.identity_pub == self.identity_pub,
                "created_at": msg.payload["created_at"],
                "expires_at": msg.payload.get("expires_at"),
                "text": body["text"] if body else None,
                "blobs": body["blobs"] if body else [],
                "undecryptable": body is None,
            })
        return out

    def conversations(self):
        names = self.store.profiles()
        out = []
        for other in self.store.dm_conversations(self.identity_pub):
            thread = self.dm_thread(other)
            last = thread[-1] if thread else None
            out.append({
                "identity_pub": other,
                "name": names.get(other, other[:8]),
                "last_text": last["text"] if last else None,
                "last_from_me": last["from_me"] if last else None,
                "last_at": last["created_at"] if last else None,
                "count": len(thread),
            })
        out.sort(key=lambda c: c["last_at"] or 0, reverse=True)
        return out

    def dm_blob(self, msg_id: str, h: str):
        msg = self.store.get_message(msg_id)
        if msg is None or msg.payload.get("kind") != KIND_DM:
            return None
        key, _ = self._content_key(msg)
        data = self.store.get_blob(h)
        if key is None or data is None:
            return None
        return decrypt_blob(key, data)

    def post_blob(self, msg_id: str, h: str):
        msg = self.store.get_message(msg_id)
        if msg is None or msg.payload.get("kind") != KIND_POST:
            return None
        key, _ = self._content_key(msg)
        data = self.store.get_blob(h)
        if key is None or data is None:
            return None
        return decrypt_blob(key, data)

    # -- revocation self-logout ---------------------------------------------------

    def enter_revoked_state(self):
        # Revoked means this device is permanently dead: destroy ALL key
        # material, not just the identity/enc/storage secrets -- device_priv
        # is a secret too (crypto review CRITICAL) and to_json() would
        # otherwise happily re-emit it in plaintext below.
        self.device._device_priv = None
        self.device._identity_priv = None
        self.device.enc_priv = None
        self.device.retired_enc = []
        self.device.storage_key = None
        j = self.device.to_json()
        j["revoked"] = True
        if self.applock_enabled:
            # The paper backup seed protects the IDENTITY -- usable from
            # this person's other, non-revoked devices -- not just this
            # now-dead device: restore it to plaintext before the sealed
            # bundle holding it is destroyed below, exactly like
            # disable_applock, or it would be lost forever with no device
            # left able to unseal it. self._paper_seed is always populated
            # here: applock_enabled + reaching this method at all means
            # this device is currently unlocked (a locked boot defers the
            # revoked-view check entirely to unlock(), and a live sync
            # session -- the only other caller -- cannot even complete its
            # AUTH device-key proof while locked), and unlock()/
            # enable_applock always populate self._paper_seed first.
            if self._paper_seed:
                _atomic_write(self.data_dir / "paper_seed.txt",
                              self._paper_seed)
            # The encrypted secret bundle in applock.json is moot now: the
            # device key it was protecting no longer exists, and the store
            # is being wiped. Drop it and the in-memory master along with
            # it, and stop treating this node as App-lock-gated -- keys.json
            # (device_priv already None above) is the whole, non-secret
            # picture from here on.
            if self._applock_path.exists():
                self._applock_path.unlink()
            self.applock_enabled = False
            self._applock_master = None
            self._paper_seed = None
        _atomic_write(self.data_dir / "keys.json", json.dumps(j))
        self.store.wipe_all()
        self.revoked = True
        self.locked = False
        self.onion_key = None
        okp = self.data_dir / "onion_key"
        if okp.exists():
            okp.unlink()
        self.notify()

    def close(self):
        self.store.close()


def _friend_add_body(nonce: str) -> bytes:
    return canonical({"type": "friend-add", "protocol": PROTOCOL,
                      "nonce": nonce})
