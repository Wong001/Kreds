"""SQLite persistence for one Hearth node (one device)."""
from __future__ import annotations

import json
import sqlite3
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

from .identity import (
    DeviceView, EnrollmentCert, RevocationCert, SeenSet, SignedMessage,
    Verifier,
)
from .messages import (
    KIND_ALBUM, KIND_DELETE, KIND_DM, KIND_ENCKEY, KIND_POST, KIND_PROFILE,
    KIND_PROFILE_LAYOUT, KIND_RING, KIND_STORY, KIND_WRAP_GRANT,
    MAX_BLOB_BYTES, blob_hash, validate_payload,
)

_SCHEMA = """
CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT);
CREATE TABLE IF NOT EXISTS identities(
  identity_pub TEXT PRIMARY KEY, is_self INTEGER NOT NULL,
  added_at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS device_views(
  identity_pub TEXT NOT NULL, device_pub TEXT NOT NULL,
  cert_json TEXT, revocation_json TEXT, seen_json TEXT NOT NULL,
  PRIMARY KEY(identity_pub, device_pub));
CREATE TABLE IF NOT EXISTS messages(
  msg_id TEXT PRIMARY KEY, identity_pub TEXT NOT NULL,
  device_pub TEXT NOT NULL, seq INTEGER NOT NULL, kind TEXT NOT NULL,
  target_id TEXT, recipient TEXT, msg_json TEXT NOT NULL,
  created_at REAL NOT NULL, expires_at REAL);
CREATE TABLE IF NOT EXISTS tombstones(
  msg_id TEXT PRIMARY KEY, reason TEXT NOT NULL, at REAL NOT NULL);
CREATE TABLE IF NOT EXISTS blobs(hash TEXT PRIMARY KEY, data BLOB NOT NULL);
CREATE TABLE IF NOT EXISTS peers(address TEXT PRIMARY KEY,
  identity_pub TEXT);
CREATE INDEX IF NOT EXISTS idx_delete_guard
  ON messages(kind, target_id, identity_pub);
CREATE TABLE IF NOT EXISTS dm_keys(
  msg_id TEXT PRIMARY KEY, sealed_key TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS defriend_outbox(
  target_identity TEXT PRIMARY KEY, address TEXT NOT NULL,
  notice_json TEXT NOT NULL, created_at REAL NOT NULL,
  expires_at REAL NOT NULL, next_attempt_at REAL NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS disconnected(
  identity_pub TEXT PRIMARY KEY, name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS undecryptable(
  msg_id TEXT PRIMARY KEY, since REAL NOT NULL);
"""


@dataclass
class IngestResult:
    accepted: bool
    reason: str
    msg_id: Optional[str] = None
    retro_dropped: List[str] = field(default_factory=list)
    deleted_target: Optional[str] = None


class Store:
    def __init__(self, path):
        self._lock = threading.RLock()
        self._db = sqlite3.connect(str(path), check_same_thread=False)
        self._db.execute("PRAGMA journal_mode=WAL")
        self._db.executescript(_SCHEMA)
        # Migration for a defriend_outbox table created before the
        # next_attempt_at retry-backoff column existed (whole-branch
        # review, Fix 3): CREATE TABLE IF NOT EXISTS above is a no-op on
        # an already-existing table, so a pre-upgrade DB needs this ALTER
        # instead. Guarded because it errors ("duplicate column") on a
        # table that already has the column -- including every freshly
        # created DB, since the CREATE above already includes it there.
        try:
            self._db.execute(
                "ALTER TABLE defriend_outbox ADD COLUMN"
                " next_attempt_at REAL NOT NULL DEFAULT 0")
        except sqlite3.OperationalError:
            pass
        self._db.commit()

    # -- meta ---------------------------------------------------------------

    def set_meta(self, k: str, v: str):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO meta VALUES(?,?)", (k, v))
            self._db.commit()

    def get_meta(self, k: str) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT v FROM meta WHERE k=?", (k,)).fetchone()
            return row[0] if row else None

    # -- identities -----------------------------------------------------------

    def add_identity(self, identity_pub: str, is_self: bool = False):
        with self._lock:
            self._db.execute(
                "INSERT OR IGNORE INTO identities VALUES(?,?,?)",
                (identity_pub, 1 if is_self else 0, time.time()))
            self._db.commit()

    def known_identities(self) -> List[str]:
        with self._lock:
            return [r[0] for r in
                    self._db.execute("SELECT identity_pub FROM identities")]

    def is_known(self, identity_pub: str) -> bool:
        with self._lock:
            return self._db.execute(
                "SELECT 1 FROM identities WHERE identity_pub=?",
                (identity_pub,)).fetchone() is not None

    def self_identity(self) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT identity_pub FROM identities WHERE is_self=1"
            ).fetchone()
            return row[0] if row else None

    def remove_identity(self, identity_pub: str):
        with self._lock:
            self._db.execute("DELETE FROM identities WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    # -- device views ----------------------------------------------------------

    def load_views(self, identity_pub: str) -> Dict[str, DeviceView]:
        with self._lock:
            out: Dict[str, DeviceView] = {}
            for dpub, cj, rj, sj in self._db.execute(
                    "SELECT device_pub, cert_json, revocation_json, seen_json"
                    " FROM device_views WHERE identity_pub=?",
                    (identity_pub,)):
                out[dpub] = DeviceView(
                    cert=(EnrollmentCert.from_dict(json.loads(cj))
                          if cj else None),
                    revocation=(RevocationCert.from_dict(json.loads(rj))
                                if rj else None),
                    seen=SeenSet.from_json(json.loads(sj)))
            return out

    def save_views(self, identity_pub: str, views: Dict[str, DeviceView],
                   commit: bool = True):
        with self._lock:
            for dpub, v in views.items():
                self._db.execute(
                    "INSERT OR REPLACE INTO device_views VALUES(?,?,?,?,?)",
                    (identity_pub, dpub,
                     json.dumps(v.cert.to_dict()) if v.cert else None,
                     json.dumps(v.revocation.to_dict()) if v.revocation
                     else None,
                     json.dumps(v.seen.to_json())))
            if commit:
                self._db.commit()

    def all_summaries(self) -> dict:
        with self._lock:
            out: dict = {}
            for ipub, dpub, sj in self._db.execute(
                    "SELECT identity_pub, device_pub, seen_json"
                    " FROM device_views"):
                out.setdefault(ipub, {})[dpub] = json.loads(sj)
            return out

    def list_revocations(self) -> List[RevocationCert]:
        with self._lock:
            return [RevocationCert.from_dict(json.loads(rj))
                    for (rj,) in self._db.execute(
                        "SELECT revocation_json FROM device_views"
                        " WHERE revocation_json IS NOT NULL")]

    # -- peers ------------------------------------------------------------------

    def add_peer(self, address: str, identity_pub: Optional[str] = None):
        with self._lock:
            self._db.execute("INSERT OR REPLACE INTO peers VALUES(?,?)",
                             (address, identity_pub))
            self._db.commit()

    def list_peers(self) -> List[dict]:
        with self._lock:
            return [{"address": a, "identity_pub": i} for a, i in
                    self._db.execute(
                        "SELECT address, identity_pub FROM peers")]

    def remove_peer(self, address: str):
        with self._lock:
            self._db.execute("DELETE FROM peers WHERE address=?", (address,))
            self._db.commit()

    def address_for(self, identity_pub: str) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT address FROM peers WHERE identity_pub=?",
                (identity_pub,)).fetchone()
            return row[0] if row else None

    # -- blobs --------------------------------------------------------------------

    def put_blob(self, data: bytes) -> str:
        if len(data) > MAX_BLOB_BYTES:
            raise ValueError("blob exceeds 10 MB cap")
        h = blob_hash(data)
        with self._lock:
            self._db.execute("INSERT OR IGNORE INTO blobs VALUES(?,?)",
                             (h, data))
            self._db.commit()
        return h

    def get_blob(self, h: str) -> Optional[bytes]:
        with self._lock:
            row = self._db.execute(
                "SELECT data FROM blobs WHERE hash=?", (h,)).fetchone()
            return bytes(row[0]) if row else None

    def has_blob(self, h: str) -> bool:
        return self.get_blob(h) is not None

    # -- tombstones ---------------------------------------------------------------

    def is_tombstoned(self, msg_id: str) -> bool:
        with self._lock:
            return self._db.execute(
                "SELECT 1 FROM tombstones WHERE msg_id=?",
                (msg_id,)).fetchone() is not None

    def message_kind(self, msg_id: str):
        """Kind of a held message row, or None if not held (tombstoned or
        never seen). Used by the delete-creation guard and tests."""
        with self._lock:
            row = self._db.execute(
                "SELECT kind FROM messages WHERE msg_id=?", (msg_id,)).fetchone()
            return row[0] if row else None

    def _tombstone(self, msg_id: str, reason: str):
        self._db.execute("INSERT OR IGNORE INTO tombstones VALUES(?,?,?)",
                         (msg_id, reason, time.time()))
        self._db.execute("DELETE FROM messages WHERE msg_id=?", (msg_id,))
        self._db.execute("DELETE FROM dm_keys WHERE msg_id=?", (msg_id,))
        self._db.execute("DELETE FROM undecryptable WHERE msg_id=?",
                         (msg_id,))

    # -- ingest ---------------------------------------------------------------------

    def ingest_message(self, msg: SignedMessage,
                       now: Optional[float] = None) -> IngestResult:
        now = now if now is not None else time.time()
        with self._lock:
            identity = msg.cert.identity_pub
            if not self.is_known(identity):
                return IngestResult(False, "unknown identity")
            mid = msg.msg_id
            if self.is_tombstoned(mid):
                return IngestResult(False, "tombstoned", mid)
            if self._db.execute("SELECT 1 FROM messages WHERE msg_id=?",
                                (mid,)).fetchone():
                return IngestResult(False, "duplicate", mid)
            ok, why = validate_payload(msg.payload)
            if not ok:
                return IngestResult(False, why, mid)
            views = self.load_views(identity)
            ok, why = Verifier(identity, views).verify_message(msg)
            if not ok:
                self.save_views(identity, views)     # commits as before
                return IngestResult(False, why, mid)
            self.save_views(identity, views, commit=False)

            kind = msg.payload["kind"]
            target = (msg.payload.get("target")
                      if kind in (KIND_DELETE, KIND_WRAP_GRANT) else None)
            recipient = (msg.payload.get("to")
                         if kind == KIND_DM else None)
            deleted_target = None
            if kind == KIND_DELETE:
                row = self._db.execute(
                    "SELECT identity_pub, kind FROM messages WHERE msg_id=?",
                    (target,)).fetchone()
                if row is not None:
                    if row[1] == KIND_DELETE:
                        # Wart 1: delete tags are immune to deletion.
                        # Tombstones are permanent (no undelete), so a
                        # delete-of-a-delete can only halt the tag's
                        # propagation -> permanent divergence.
                        self._db.commit()
                        return IngestResult(
                            False, "delete tag cannot target a delete tag",
                            mid)
                    if row[0] != identity:
                        self._db.commit()
                        return IngestResult(False, "delete not authorized",
                                            mid)
                    self._tombstone(target, "deleted")
                    deleted_target = target
                # "A wall is a wall" GC: a deleted post's grants must stop
                # gossiping too (mirror of the meta-delete hygiene below).
                # Keyed on the delete's target regardless of whether the
                # post row was present -- grants can be held even when the
                # post row never arrived or died earlier.
                for (g,) in self._db.execute(
                        "SELECT msg_id FROM messages WHERE kind=?"
                        " AND target_id=?",
                        (KIND_WRAP_GRANT, target)).fetchall():
                    self._tombstone(g, "invalid")
            else:
                # A delete tag for this message may have gossiped in first
                # (out-of-order delivery hits every kind, not just posts).
                # The identity_pub match keeps delete authorization intact:
                # only the author's own tag can kill content on arrival.
                if self._db.execute(
                        "SELECT 1 FROM messages WHERE kind=? AND target_id=?"
                        " AND identity_pub=?",
                        (KIND_DELETE, mid, identity)).fetchone():
                    self._tombstone(mid, "deleted")
                    self._db.commit()
                    self.gc_blobs()
                    return IngestResult(True, "deleted on arrival", mid,
                                        deleted_target=mid)

            if kind == KIND_WRAP_GRANT and self.is_tombstoned(target):
                # Target already deleted/expired. Views were saved above,
                # so the seq is consumed; tombstone the grant so peers
                # stop offering it (a bare refusal would leave a summary
                # gap and re-offers forever).
                self._tombstone(mid, "invalid")
                self._db.commit()
                return IngestResult(True, "grant for tombstoned target", mid)

            self._db.execute(
                "INSERT INTO messages VALUES(?,?,?,?,?,?,?,?,?,?)",
                (mid, identity, msg.cert.device_pub, msg.seq, kind, target,
                 recipient, json.dumps(msg.to_dict()),
                 msg.payload.get("created_at", now),
                 msg.payload.get("expires_at")))
            if kind == KIND_DELETE:
                # Hygiene: any held meta-delete targeting THIS tag is now
                # provably invalid - tombstone it (reason 'invalid') so it
                # stops gossiping. Tombstone, not DELETE: a bare row-delete
                # would be re-fetched from peers on the next summary diff.
                for (bad,) in self._db.execute(
                        "SELECT msg_id FROM messages WHERE kind=?"
                        " AND target_id=?", (KIND_DELETE, mid)).fetchall():
                    self._tombstone(bad, "invalid")
            if kind == KIND_WRAP_GRANT:
                # Un-poison: the post row may have arrived first, failed to
                # decrypt, and been negative-cached -- this grant is exactly
                # the missing key material, so let the next sweep retry.
                self._db.execute("DELETE FROM undecryptable WHERE msg_id=?",
                                 (target,))
            self._db.commit()
            if deleted_target:
                self.gc_blobs()
            return IngestResult(True, "ok", mid,
                                deleted_target=deleted_target)

    def ingest_revocation(self, rev: RevocationCert) -> IngestResult:
        with self._lock:
            identity = rev.identity_pub
            if not self.is_known(identity):
                return IngestResult(False, "unknown identity")
            views = self.load_views(identity)
            ok, why = Verifier(identity, views).process_revocation(rev)
            if not ok:
                return IngestResult(False, why)
            self.save_views(identity, views)
            dropped = []
            for (mid,) in self._db.execute(
                    "SELECT msg_id FROM messages WHERE device_pub=?"
                    " AND seq>?", (rev.device_pub, rev.last_valid_seq)):
                dropped.append(mid)
            for mid in dropped:
                self._tombstone(mid, "retro-drop")
            self._db.commit()
            if dropped:
                self.gc_blobs()
            return IngestResult(True, "ok", retro_dropped=dropped)

    def sweep_expired(self, now: Optional[float] = None) -> List[str]:
        now = now if now is not None else time.time()
        with self._lock:
            swept = [mid for (mid,) in self._db.execute(
                "SELECT msg_id FROM messages WHERE expires_at IS NOT NULL"
                " AND expires_at<=?", (now,))]
            for mid in swept:
                self._tombstone(mid, "expired")
                for (g,) in self._db.execute(
                        "SELECT msg_id FROM messages WHERE kind=?"
                        " AND target_id=?", (KIND_WRAP_GRANT, mid)).fetchall():
                    self._tombstone(g, "expired")
            self._db.commit()
            if swept:
                self.gc_blobs()
            return swept

    def prune_superseded_enckeys(self) -> int:
        """Tombstone (reason 'superseded') every enckey row that is not the
        latest for its (identity_pub, device_pub), by the same
        (created_at, seq) tie-break enckey_records resolves with. Rotation
        is daily (maintain_enckey), so without this the table grows one row
        per device per day forever, replicated to every friend. Tombstone,
        never DELETE: a bare row-delete reads as "missing" to the next
        summary diff and peers re-send it forever; a tombstone stops both
        the holding and the offering, so superseded rows evaporate
        network-wide as each node prunes independently. Safe: nothing reads
        superseded rows (senders wrap to latest; recipients decrypt with
        retired PRIVATE keys, client-side, untouched here)."""
        with self._lock:
            rows = self._db.execute(
                "SELECT msg_id, identity_pub, device_pub, created_at, seq "
                "FROM messages WHERE kind=?", (KIND_ENCKEY,)).fetchall()
            latest = {}
            for mid, ident, dpub, created, seq in rows:
                cur = latest.get((ident, dpub))
                if cur is None or (created, seq) > cur[1]:
                    latest[(ident, dpub)] = (mid, (created, seq))
            keep = {v[0] for v in latest.values()}
            pruned = 0
            for mid, *_rest in rows:
                if mid not in keep:
                    self._tombstone(mid, "superseded")
                    pruned += 1
            if pruned:
                self._db.commit()
            return pruned

    def prune_superseded_wrap_grants(self) -> int:
        """Tombstone (reason 'superseded') every wrap_grant row that is not
        the latest for its (identity_pub, target_id), by the same
        (created_at, seq) tie-break wrap_grants() unions with. Recipients
        rotate enc keys daily (ENC_ROTATION_PERIOD), so maintain_wrap_grants
        re-mints a fresh grant per granted wall post per day; without this
        the wrap_grant table grows one row per granted post per day forever,
        replicated to every friend -- exactly the growth
        prune_superseded_enckeys exists to stop, one level up.

        SAFE ONLY BECAUSE MINTS ARE FULL-COVERAGE. maintain_wrap_grants
        wraps the content key to EVERY currently-grantable device on each
        mint, so the newest grant per (author, target) covers every device
        any older grant did. An older PARTIAL grant could cover a device the
        newest one doesn't -- so reverting the sweep to missing-only mints
        would make this prune silently strip a still-needed wrap. The
        full-coverage mint and this prune must land, and stay, together.
        Tombstone, never DELETE (see prune_superseded_enckeys for why)."""
        with self._lock:
            rows = self._db.execute(
                "SELECT msg_id, identity_pub, target_id, created_at, seq "
                "FROM messages WHERE kind=?", (KIND_WRAP_GRANT,)).fetchall()
            latest = {}
            for mid, ident, target, created, seq in rows:
                cur = latest.get((ident, target))
                if cur is None or (created, seq) > cur[1]:
                    latest[(ident, target)] = (mid, (created, seq))
            keep = {v[0] for v in latest.values()}
            pruned = 0
            for mid, *_rest in rows:
                if mid not in keep:
                    self._tombstone(mid, "superseded")
                    pruned += 1
            if pruned:
                self._db.commit()
            return pruned

    # -- reads -----------------------------------------------------------------------

    def profiles(self) -> Dict[str, str]:
        with self._lock:
            best: Dict[str, tuple] = {}
            for ipub, seq, dpub, mj in self._db.execute(
                    "SELECT identity_pub, seq, device_pub, msg_json"
                    " FROM messages WHERE kind=?", (KIND_PROFILE,)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if ipub not in best or key > best[ipub][0]:
                    best[ipub] = (key, p["name"])
            return {k: v[1] for k, v in best.items()}

    def profile_avatars(self) -> Dict[str, str]:
        """identity_pub -> avatar blob hash from each author's latest
        profile record (same latest-wins tie-break as profiles()).
        Authors whose latest profile has no avatar are absent -- post-row
        enrichment reads .get() and falls back to the letter circle."""
        with self._lock:
            best: Dict[str, tuple] = {}
            for ipub, seq, dpub, mj in self._db.execute(
                    "SELECT identity_pub, seq, device_pub, msg_json"
                    " FROM messages WHERE kind=?", (KIND_PROFILE,)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if ipub not in best or key > best[ipub][0]:
                    best[ipub] = (key, p.get("avatar"))
            return {k: v[1] for k, v in best.items() if v[1]}

    def profile(self, identity_pub: str) -> Optional[dict]:
        with self._lock:
            best = None
            best_key = None
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_PROFILE, identity_pub)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if best is None or key > best_key:
                    best = p
                    best_key = key
            if best is None:
                return None
            return {
                "name": best.get("name", identity_pub[:8]),
                "bio": best.get("bio", ""),
                "accent": best.get("accent", "#2743d6"),
                "avatar": best.get("avatar"),
                "avatar_shape": best.get("avatar_shape", "circle"),
                "avatar_size": best.get("avatar_size", "m"),
                "avatar_align": best.get("avatar_align", "left"),
                "banner": best.get("banner"),
                "banner_pos": best.get("banner_pos", 50),
            }

    def profile_layout(self, identity_pub: str) -> dict:
        """Latest-wins {order: [...], grids: {msg_id: layout},
        sizes: {msg_id: size}, pins: {msg_id: {x,y,w,h}},
        spans: {msg_id: {w,h}}, texts: {msg_id: {h,v,size,font,weight,
        style,color}}} for this author's wall. Empty when never
        arranged."""
        with self._lock:
            best, best_key = None, None
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_PROFILE_LAYOUT, identity_pub)):
                p = json.loads(mj)["payload"]
                key = (p["created_at"], seq, dpub)
                if best is None or key > best_key:
                    best = {"order": p.get("order", []),
                            "grids": p.get("grids", {}),
                            "sizes": p.get("sizes", {}),
                            "pins": p.get("pins", {}),
                            "spans": p.get("spans", {}),
                            "texts": p.get("texts", {})}
                    best_key = key
            return best or {"order": [], "grids": {}, "sizes": {},
                            "pins": {}, "spans": {}, "texts": {}}

    def albums(self, identity_pub: str) -> dict:
        """Latest-wins {album_id: members} for this author (collage Slice
        C). Same (created_at, seq, device_pub) tie-break as profile_layout;
        resolved per album_id. Empty members lists are returned as-is -
        the node layer treats them as ungrouped."""
        with self._lock:
            best: dict = {}
            best_key: dict = {}
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_ALBUM, identity_pub)):
                p = json.loads(mj)["payload"]
                aid = p["album_id"]
                key = (p["created_at"], seq, dpub)
                if aid not in best or key > best_key[aid]:
                    best[aid] = list(p.get("members", []))
                    best_key[aid] = key
            return best

    def messages_not_in(self, summaries: dict, entitled: Set[str],
                        peer_identity: str) -> List[SignedMessage]:
        with self._lock:
            out = []
            peer_devices = set(self.load_views(peer_identity).keys())
            # (author, target) -> devices named by the AUTHOR's own grants.
            # Author-keyed so a hostile friend's grant for someone else's
            # post can never widen that post's audience.
            grant_devs: Dict[tuple, set] = {}
            for gipub, gtarget, gmj in self._db.execute(
                    "SELECT identity_pub, target_id, msg_json FROM messages"
                    " WHERE kind=?", (KIND_WRAP_GRANT,)):
                grant_devs.setdefault((gipub, gtarget), set()).update(
                    json.loads(gmj)["payload"].get("wraps", {}))
            for mid, ipub, dpub, seq, kind, rcpt, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, device_pub, seq, kind,"
                    " recipient, msg_json FROM messages ORDER BY seq ASC"):
                if ipub not in entitled:
                    continue
                if kind == KIND_DM and peer_identity not in (ipub, rcpt):
                    continue          # DMs never relay through friends
                if kind == KIND_RING and peer_identity != ipub:
                    continue          # ring records are author-private
                if kind == KIND_POST:
                    wr = set(json.loads(mj)["payload"].get("wraps", {}))
                    wr |= grant_devs.get((ipub, mid), set())
                    if peer_identity != ipub and not (peer_devices & wr):
                        continue      # wrap-set union grant audience + author
                if kind == KIND_WRAP_GRANT:
                    wr = set(json.loads(mj)["payload"].get("wraps", {}))
                    if peer_identity != ipub and not (peer_devices & wr):
                        continue      # grants route to named devices + author
                dev = summaries.get(ipub, {}).get(dpub)
                if dev is not None and SeenSet.summary_has(dev, seq):
                    continue
                out.append(SignedMessage.from_dict(json.loads(mj)))
            return out

    # -- DM support -----------------------------------------------------------

    def enckey_records(self, identity_pub: str) -> Dict[str, Tuple[float, str]]:
        """device_pub -> (created_at, enc_pub) for non-revoked devices,
        latest-wins by (created_at, seq) — rotation makes same-timestamp
        ties realistic, so bare created_at is not enough."""
        with self._lock:
            revoked = {dpub for dpub, v in
                       self.load_views(identity_pub).items()
                       if v.revocation is not None}
            best: Dict[str, tuple] = {}
            for dpub, seq, mj in self._db.execute(
                    "SELECT device_pub, seq, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_ENCKEY, identity_pub)):
                if dpub in revoked:
                    continue
                p = json.loads(mj)["payload"]
                rank = (p["created_at"], seq)
                if dpub not in best or rank > best[dpub][0]:
                    best[dpub] = (rank, p["enc_pub"])
            return {d: (rank[0], pub) for d, (rank, pub) in best.items()}

    def wrap_grants(self, target_msg_id: str,
                    author_identity: str) -> dict:
        """device_pub -> wrap entry, unioned over every wrap-grant BY THAT
        AUTHOR for this target; later mints override earlier per device
        (a re-mint after enc-key rotation supersedes the stale wrap).
        Author-filtered on purpose: a grant is only honored when signed by
        the post's own author -- anyone else's 'grant' must neither widen
        routing nor feed key resolution (see messages_not_in and
        node._content_key)."""
        with self._lock:
            out: dict = {}
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " AND target_id=? AND identity_pub=?"
                    " ORDER BY created_at ASC, seq ASC",
                    (KIND_WRAP_GRANT, target_msg_id, author_identity)):
                out.update(json.loads(mj)["payload"].get("wraps", {}))
            return out

    def enckeys(self, identity_pub: str) -> Dict[str, str]:
        return {d: pub for d, (_, pub) in
                self.enckey_records(identity_pub).items()}

    def rings(self, identity_pub: str) -> Dict[str, str]:
        """member_identity -> 'inner'|'kreds' for this author, latest-wins
        by (created_at, seq, device_pub) -- same tie-break as profiles(),
        needed because two of the author's own devices can publish a ring
        record for the same member with an identical (created_at, seq).
        Absent members default to 'kreds' at the caller. Author-private;
        never disclosed to friends (see routing)."""
        with self._lock:
            best: Dict[str, tuple] = {}
            for seq, dpub, mj in self._db.execute(
                    "SELECT seq, device_pub, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_RING, identity_pub)):
                p = json.loads(mj)["payload"]
                rank = (p["created_at"], seq, dpub)
                member = p["member"]
                if member not in best or rank > best[member][0]:
                    best[member] = (rank, p["ring"])
            return {m: r for m, (rank, r) in best.items()}

    def ring_since(self, self_identity: str, member: str) -> Optional[float]:
        """Return the created_at of the latest ring record for that member if
        one exists, else the member's added_at from identities, else None."""
        with self._lock:
            best = None
            for seq, mj in self._db.execute(
                    "SELECT seq, msg_json FROM messages"
                    " WHERE kind=? AND identity_pub=?",
                    (KIND_RING, self_identity)):
                p = json.loads(mj)["payload"]
                if p["member"] != member:
                    continue
                rank = (p["created_at"], seq)
                if best is None or rank > best[0]:
                    best = (rank, p["created_at"])
            if best is not None:
                return best[1]
            row = self._db.execute(
                "SELECT added_at FROM identities WHERE identity_pub=?",
                (member,)).fetchone()
            return row[0] if row else None

    def cache_message_key(self, msg_id: str, sealed_hex: str):
        """Message-scoped content-key cache (DMs and posts alike); the
        underlying table stays named dm_keys -- renaming it is churn for
        no behavioral gain, the accessor names are what read as
        message-scoped."""
        with self._lock:
            if self.is_tombstoned(msg_id):
                return          # a delete may have landed in the meantime
            self._db.execute("INSERT OR IGNORE INTO dm_keys VALUES(?,?)",
                             (msg_id, sealed_hex))
            self._db.execute("DELETE FROM undecryptable WHERE msg_id=?",
                             (msg_id,))
            self._db.commit()

    def replace_message_key(self, msg_id: str, sealed_hex: str):
        """Overwrite a cached row (healing a corrupt/unopenable one) --
        unlike cache_message_key's INSERT OR IGNORE, this always takes the
        new value."""
        with self._lock:
            if self.is_tombstoned(msg_id):
                return          # a delete may have landed in the meantime
            self._db.execute("INSERT OR REPLACE INTO dm_keys VALUES(?,?)",
                             (msg_id, sealed_hex))
            self._db.execute("DELETE FROM undecryptable WHERE msg_id=?",
                             (msg_id,))
            self._db.commit()

    def cached_message_key(self, msg_id: str) -> Optional[str]:
        with self._lock:
            row = self._db.execute(
                "SELECT sealed_key FROM dm_keys WHERE msg_id=?",
                (msg_id,)).fetchone()
            return row[0] if row else None

    def mark_undecryptable(self, msg_id: str, now=None) -> None:
        """Wart 3: background-sweep negative cache. LOCAL ONLY - never
        synced. Only the gossip-round sweep writes here (and only while the
        node is unlocked - see node.cache_message_keys); per-view reads in
        dm_thread stay correct-first and ignore this table."""
        with self._lock:
            self._db.execute(
                "INSERT OR IGNORE INTO undecryptable VALUES(?,?)",
                (msg_id, time.time() if now is None else now))
            self._db.commit()

    def clear_undecryptable(self, msg_id=None) -> None:
        with self._lock:
            if msg_id is None:
                self._db.execute("DELETE FROM undecryptable")
            else:
                self._db.execute(
                    "DELETE FROM undecryptable WHERE msg_id=?", (msg_id,))
            self._db.commit()

    def undecryptable_ids(self) -> set:
        with self._lock:
            return {r[0] for r in self._db.execute(
                "SELECT msg_id FROM undecryptable")}

    def uncached_message_ids(self, self_identity: str) -> List[str]:
        """msg_id list, kind-agnostic: DM rows where self is a party, plus
        post rows authored by self or whose wraps contains one of self's
        devices -- with no cached key yet."""
        with self._lock:
            out = []
            for mid, kind, ipub, rcpt, mj in self._db.execute(
                    "SELECT msg_id, kind, identity_pub, recipient, msg_json"
                    " FROM messages WHERE kind IN (?,?)"
                    " AND msg_id NOT IN (SELECT msg_id FROM dm_keys)"
                    " AND msg_id NOT IN (SELECT msg_id FROM undecryptable)",
                    (KIND_DM, KIND_POST)):
                if kind == KIND_DM:
                    if self_identity in (ipub, rcpt):
                        out.append(mid)
                else:                                    # post
                    if ipub == self_identity:
                        out.append(mid); continue
                    my_devs = set(self.load_views(self_identity).keys())
                    wrapped = set(json.loads(mj)["payload"].get("wraps", {}))
                    if not (my_devs & wrapped):
                        # not in the at-post-time audience -- but a wall
                        # wrap-grant from the author may cover us
                        wrapped |= set(self.wrap_grants(mid, ipub))
                    if my_devs & wrapped:
                        out.append(mid)
            return out

    def post_messages(
            self, identity_pub: Optional[str] = None) -> List[SignedMessage]:
        # created_at alone ties (wall-clock time.time(), sub-second
        # collisions are common on fast successive composes) and SQLite
        # leaves same-key order unspecified/implementation-defined -- so
        # wall/feed/profile_view order (this is the query behind all of
        # them) was silently nondeterministic on a tie.
        #
        # rowid (monotonic per insert) is this store's own local arrival
        # order -- the ONLY tie-break signal that answers "which post did
        # I see/compose last", mirroring dm_thread's fix (see that
        # method's comment for why per-device seq and device_pub are
        # WRONG tie-breaks here too: seq is a per-device publish counter,
        # meaningless to compare across different identities' devices, and
        # equal/skewed seqs both decay the tie to something unrelated to
        # arrival order). DESC here (vs dm_thread's ASC) because this
        # query is newest-first: among same-second posts, the latest
        # local arrival sorts first. Sync stays consistent with this: a
        # peer ingests one author's posts in seq order
        # (messages_not_in ORDER BY seq ASC), so arrival order there
        # matches compose order too.
        with self._lock:
            if identity_pub is None:
                rows = self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " ORDER BY created_at DESC, rowid DESC", (KIND_POST,))
            else:
                rows = self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?"
                    " AND identity_pub=? ORDER BY created_at DESC, rowid DESC",
                    (KIND_POST, identity_pub))
            return [SignedMessage.from_dict(json.loads(mj)) for (mj,) in rows]

    def messages_by_author(self, identity_pub: str) -> List[SignedMessage]:
        with self._lock:
            return [SignedMessage.from_dict(json.loads(mj)) for (mj,) in
                    self._db.execute(
                        "SELECT msg_json FROM messages WHERE identity_pub=?",
                        (identity_pub,))]

    def get_message(self, msg_id: str) -> Optional[SignedMessage]:
        with self._lock:
            row = self._db.execute(
                "SELECT msg_json FROM messages WHERE msg_id=?",
                (msg_id,)).fetchone()
            return (SignedMessage.from_dict(json.loads(row[0]))
                    if row else None)

    def dm_thread(self, a_identity: str,
                  b_identity: str) -> List[SignedMessage]:
        with self._lock:
            # created_at alone ties (wall-clock time.time(), sub-second
            # collisions are common on fast successive composes) and SQLite
            # leaves same-key order unspecified/implementation-defined --
            # so "last message" (and the web client's chat-bubble order)
            # was silently nondeterministic.
            #
            # rowid (monotonic per insert) is this store's own local
            # arrival order -- the ONLY tie-break signal that answers
            # "which message did I see/compose last", which is what
            # last_from_me and bubble order actually need. Do NOT add
            # per-message identity/device fields to this ORDER BY:
            # Store.profile()'s (created_at, seq, device_pub) idiom was
            # tried and is wrong here, because this query spans TWO
            # identities. seq is a PER-DEVICE publish counter, so
            # comparing it across two people's devices is meaningless --
            # equal seqs decay the tie to a device_pub coin flip
            # (verified: ~1 in 3 real runs showed the wrong side as
            # "last"), and UNequal seqs are worse: a chatty device's high
            # seq would override the arrival order outright (review
            # counterexample, pinned by test). Same class of wrongness
            # either way.
            return [SignedMessage.from_dict(json.loads(mj))
                    for (mj,) in self._db.execute(
                        "SELECT msg_json FROM messages WHERE kind=?"
                        " AND ((identity_pub=? AND recipient=?)"
                        "  OR (identity_pub=? AND recipient=?))"
                        " ORDER BY created_at ASC, rowid ASC",
                        (KIND_DM, a_identity, b_identity,
                         b_identity, a_identity))]

    def dm_conversations(self, self_identity: str) -> List[str]:
        with self._lock:
            out: List[str] = []
            for ipub, rcpt in self._db.execute(
                    "SELECT identity_pub, recipient FROM messages"
                    " WHERE kind=? AND (identity_pub=? OR recipient=?)"
                    " ORDER BY created_at ASC",
                    (KIND_DM, self_identity, self_identity)):
                other = rcpt if ipub == self_identity else ipub
                if other != self_identity and other not in out:
                    out.append(other)
            return out

    # -- unfriend / defriend -------------------------------------------------

    def purge_authored_by(self, identity_pub: str) -> int:
        """Delete every message this identity authored (posts, profile,
        enckey, dm, ring, etc.) then GC any blobs that fall out of
        reference. Also drops the dm_keys sealed-key rows for those exact
        messages (mirrors _tombstone's own dm_keys cleanup) so no
        orphaned cached content key survives a "delete everything" op
        (whole-branch review, Fix 6). Returns the row count deleted."""
        with self._lock:
            ids = [r[0] for r in self._db.execute(
                "SELECT msg_id FROM messages WHERE identity_pub=?",
                (identity_pub,))]
            if ids:
                self._db.executemany(
                    "DELETE FROM dm_keys WHERE msg_id=?",
                    [(mid,) for mid in ids])
                self._db.executemany(
                    "DELETE FROM undecryptable WHERE msg_id=?",
                    [(mid,) for mid in ids])
            cur = self._db.execute(
                "DELETE FROM messages WHERE identity_pub=?", (identity_pub,))
            self._db.commit()
        self.gc_blobs()
        return cur.rowcount

    def remove_peer_identity(self, identity_pub: str):
        """Forget every peer address on file for this identity (whole-
        branch review, Fix 4/5 use this from both teardown paths so an
        ex-friend's IP is never dialed again)."""
        with self._lock:
            self._db.execute("DELETE FROM peers WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    def remove_device_views(self, identity_pub: str):
        """Forget every device_views row (enrollment certs + seen-state +
        any revocation) for this identity (whole-branch review, Fix 5):
        left behind, a stale row's revocation could even make a later
        session wrongly refuse this identity's device as "revoked"."""
        with self._lock:
            self._db.execute("DELETE FROM device_views WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    def unfriend_teardown(self, self_identity: str, other: str):
        """Local-side removal: drop `other` from identities, delete
        everything they authored, the DM thread with them, and any ring
        records I authored about them; forget their peer address and
        device views (enrollment certs / seen-state / revocations)."""
        with self._lock:
            self._db.execute("DELETE FROM identities WHERE identity_pub=?",
                             (other,))
            # dm_keys cleanup (Fix 6) for every message about to be
            # deleted below -- both what `other` authored AND what I sent
            # them (a DM's cached content key is stored by msg_id
            # regardless of which party authored it).
            ids = [r[0] for r in self._db.execute(
                "SELECT msg_id FROM messages WHERE identity_pub=?"
                " OR (kind='dm' AND recipient=?)", (other, other))]
            if ids:
                self._db.executemany(
                    "DELETE FROM dm_keys WHERE msg_id=?",
                    [(mid,) for mid in ids])
                self._db.executemany(
                    "DELETE FROM undecryptable WHERE msg_id=?",
                    [(mid,) for mid in ids])
            self._db.execute("DELETE FROM messages WHERE identity_pub=?",
                             (other,))
            self._db.execute(
                "DELETE FROM messages WHERE kind='dm' AND recipient=?",
                (other,))
            self._db.execute(
                "DELETE FROM messages WHERE kind='ring' AND identity_pub=?"
                " AND json_extract(msg_json,'$.payload.member')=?",
                (self_identity, other))
            self._db.commit()
        self.remove_peer_identity(other)
        self.remove_device_views(other)          # Fix 5
        self.gc_blobs()

    def add_disconnected(self, identity_pub: str, name: str):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO disconnected VALUES(?,?)",
                (identity_pub, name))
            self._db.commit()

    def list_disconnected(self) -> List[dict]:
        with self._lock:
            return [{"identity_pub": r[0], "name": r[1]} for r in
                    self._db.execute(
                        "SELECT identity_pub, name FROM disconnected")]

    def remove_disconnected(self, identity_pub: str):
        with self._lock:
            self._db.execute("DELETE FROM disconnected WHERE identity_pub=?",
                             (identity_pub,))
            self._db.commit()

    def add_outbox(self, notice, address: str, expires_at: float):
        with self._lock:
            self._db.execute(
                "INSERT OR REPLACE INTO defriend_outbox VALUES(?,?,?,?,?,?)",
                (notice.target_identity, address, json.dumps(notice.to_dict()),
                 notice.created_at, expires_at, 0))
            self._db.commit()

    def list_outbox(self) -> List[dict]:
        with self._lock:
            return [{"target_identity": r[0], "address": r[1],
                     "notice": json.loads(r[2]), "created_at": r[3],
                     "expires_at": r[4], "next_attempt_at": r[5]}
                    for r in self._db.execute(
                        "SELECT target_identity, address, notice_json,"
                        " created_at, expires_at, next_attempt_at"
                        " FROM defriend_outbox")]

    def drop_outbox(self, target_identity: str):
        with self._lock:
            self._db.execute(
                "DELETE FROM defriend_outbox WHERE target_identity=?",
                (target_identity,))
            self._db.commit()

    def set_outbox_retry(self, target_identity: str, next_attempt_at: float):
        """Back off a still-pending record so the gossip-cadence delivery
        pass does not re-dial the same (possibly offline) address every
        round (whole-branch review, Fix 3)."""
        with self._lock:
            self._db.execute(
                "UPDATE defriend_outbox SET next_attempt_at=?"
                " WHERE target_identity=?", (next_attempt_at, target_identity))
            self._db.commit()

    def wipe_all(self):
        with self._lock:
            for table in ("meta", "identities", "device_views", "messages",
                          "tombstones", "blobs", "peers", "dm_keys",
                          "defriend_outbox", "disconnected", "undecryptable"):
                self._db.execute(f"DELETE FROM {table}")
            self._db.commit()

    # -- blob GC -----------------------------------------------------------------------

    def referenced_blobs(self) -> Set[str]:
        with self._lock:
            refs: Set[str] = set()
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind IN (?,?)",
                    (KIND_POST, KIND_DM)):
                p = json.loads(mj)["payload"]
                refs.update(p.get("blobs", []))
                # video blocks (KIND_POST, media="video") carry a poster
                # blob referenced only by this field, not by `blobs` -- a
                # video block's poster must sync just like its mp4, the
                # same class of blob referenced_blobs() already handles
                # for KIND_STORY and KIND_PROFILE below. Guard the type: this
                # query also spans KIND_DM, whose payload is NOT poster-
                # validated on ingest, so a modified friend client could
                # persist a junk (non-str) poster -- adding that to `refs`
                # would brick every sync round + gc_blobs() (unhashable, or
                # str/int compare in sorted(missing_blobs())).
                poster = p.get("poster")
                if isinstance(poster, str) and poster:
                    refs.add(poster)
                # thumbs (spec 2026-07-18): same junk-guard as poster --
                # the query spans KIND_DM whose payload isn't thumb-
                # validated. isinstance-check the CONTAINER itself, not
                # just its elements: a truthy non-list smuggled through an
                # unvalidated KIND_DM payload (e.g. "thumbs": 1) would
                # otherwise TypeError on iteration ("or []" only
                # substitutes on a FALSY value) and brick every sync round
                # + gc_blobs() -- exactly the failure class this guard
                # exists to prevent (review finding: the first cut of this
                # guard still had that hole).
                thumbs = p.get("thumbs")
                if isinstance(thumbs, list):
                    for t in thumbs:
                        if isinstance(t, str) and t:
                            refs.add(t)
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?",
                    (KIND_PROFILE,)):
                p = json.loads(mj)["payload"]
                for h in (p.get("avatar"), p.get("banner")):
                    if h:
                        refs.add(h)
            for (mj,) in self._db.execute(
                    "SELECT msg_json FROM messages WHERE kind=?",
                    (KIND_STORY,)):
                p = json.loads(mj)["payload"]
                for h in (p.get("media"), p.get("poster")):
                    if h:
                        refs.add(h)
            return refs

    def missing_blobs(self) -> Set[str]:
        with self._lock:
            have = {h for (h,) in
                    self._db.execute("SELECT hash FROM blobs")}
            return self.referenced_blobs() - have

    def blob_sizes(self, hashes) -> dict:
        """{hash: stored byte length} for the hashes we hold (others absent)."""
        with self._lock:
            if not hashes:
                return {}
            qs = ",".join("?" for _ in hashes)
            return {h: n for (h, n) in self._db.execute(
                f"SELECT hash, LENGTH(data) FROM blobs WHERE hash IN ({qs})",
                list(hashes))}

    def gc_blobs(self) -> int:
        with self._lock:
            refs = self.referenced_blobs()
            gone = [h for (h,) in self._db.execute("SELECT hash FROM blobs")
                    if h not in refs]
            for h in gone:
                self._db.execute("DELETE FROM blobs WHERE hash=?", (h,))
            self._db.commit()
            return len(gone)

    def active_stories(self, now: Optional[float] = None) -> List[dict]:
        now = now if now is not None else time.time()
        with self._lock:
            groups: Dict[str, dict] = {}
            for mid, ipub, mj in self._db.execute(
                    "SELECT msg_id, identity_pub, msg_json FROM messages"
                    " WHERE kind=? ORDER BY created_at ASC", (KIND_STORY,)):
                p = json.loads(mj)["payload"]
                if p.get("expires_at") is not None and p["expires_at"] <= now:
                    continue
                g = groups.setdefault(ipub, {"identity_pub": ipub,
                                             "items": [], "_last": 0})
                g["items"].append({
                    "msg_id": mid, "media_kind": p["media_kind"],
                    "media": p["media"], "poster": p.get("poster"),
                    "caption": p.get("caption", ""),
                    "created_at": p["created_at"],
                })
                g["_last"] = max(g["_last"], p["created_at"])
            out = sorted(groups.values(), key=lambda g: g["_last"],
                         reverse=True)
            for g in out:
                del g["_last"]
            return out

    def close(self):
        with self._lock:
            self._db.close()
