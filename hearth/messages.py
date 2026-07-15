"""Payload semantics for the three Hearth message kinds (spec: Data model)."""
from __future__ import annotations

import hashlib
import time
from typing import Optional, Sequence, Tuple

from .identity import DeviceKeys, SignedMessage

KIND_POST = "post"
KIND_PROFILE = "profile"
KIND_DELETE = "delete"
KIND_ENCKEY = "enckey"
KIND_DM = "dm"
KIND_STORY = "story"
KIND_RING = "ring"
KIND_PROFILE_LAYOUT = "profile_layout"
KIND_ALBUM = "album"
KIND_WRAP_GRANT = "wrap_grant"
MAX_LAYOUT = 500
GRID_LAYOUTS = ("auto", "cols2", "cols3", "hero", "masonry")
SIZE_LAYOUTS = ("small", "wide", "full")
# Text block styling (spec 2026-07-14): each tuple's FIRST value is the
# default, dropped from storage by set_block_text. `color` is validated
# separately (not an enum tuple): "default" / "accent" / one of ACCENTS.
TEXT_STYLE_ENUMS = {
    "h": ("left", "center", "right"),
    "v": ("top", "middle", "bottom"),
    "size": ("auto", "s", "m", "l", "xl"),
    "font": ("sans", "disp"),
    "weight": ("normal", "bold"),
    "style": ("normal", "italic"),
}
WALL_COLS = 4      # collage canvas width in cells (spec 2026-07-13)
MAX_BLOCK_H = 8    # tallest block in row-units
RINGS = ("inner", "kreds")
MAX_CAPTION = 200
STORY_TTL = 86400
DEFRIEND_TTL = 14 * 86400
DEFRIEND_RETRY = 3600        # seconds between delivery attempts for one
                             # outbox record after a failed/non-target-
                             # refused dial -- the ~3s gossip loop must not
                             # hammer the same (possibly offline) address
                             # every tick (whole-branch review, Fix 3).
MAX_NAME = 80
MAX_BIO = 240
ACCENTS = ("#2743d6", "#c0563b", "#3e7c55", "#8a5cd0", "#17191e",
           "#1f8a8a", "#c79a2e", "#c0567e", "#4a5568", "#7a4e8a")
AVATAR_SHAPES = ("circle", "squircle", "square", "triangle")
AVATAR_SIZES = ("s", "m", "l")
AVATAR_ALIGNS = ("left", "center", "right")
MAX_BLOB_BYTES = 10 * 1024 * 1024      # PROTOCOL: store + sync + doors all
                                       # key off this. Raised 5->10 MB in
                                       # 0.3.11; peers on <=0.3.10 refuse
                                       # bigger blobs until they update
                                       # (accepted single-release window).
MAX_IMAGE_UPLOAD = 50 * 1024 * 1024    # raw image upload cap, checked before
                                       # the photo gate (gate output is still
                                       # bound by MAX_BLOB_BYTES)
MAX_VIDEO_UPLOAD = 100 * 1024 * 1024   # raw upload cap, checked before the
                                       # transcode gate (transcoded output is
                                       # still bound by MAX_BLOB_BYTES)
ONION_SYNC_INTERVAL = 45.0   # seconds; onion peers sync slower than TCP


def blob_hash(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _now(now: Optional[float]) -> float:
    return now if now is not None else time.time()


def make_post(device: DeviceKeys, scope: str, body_nonce: str,
              body_ct: str, wraps: dict, blob_refs: Sequence[str] = (),
              created_at: Optional[float] = None,
              expires_at: Optional[float] = None,
              placement: str = "journal", media: str = "photo",
              poster: Optional[str] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_POST, "scope": scope, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": _now(created_at), "expires_at": expires_at,
        "placement": placement, "media": media, "poster": poster,
    })


def make_ring(device: DeviceKeys, member: str, ring: str,
              now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_RING, "member": member, "ring": ring,
        "created_at": _now(now),
    })


def make_profile(device: DeviceKeys, name: str, bio: str = "",
                 accent: str = "#2743d6", avatar: Optional[str] = None,
                 avatar_shape: str = "circle", avatar_size: str = "m",
                 avatar_align: str = "left", banner: Optional[str] = None,
                 now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE, "name": name, "bio": bio, "accent": accent,
        "avatar": avatar, "avatar_shape": avatar_shape,
        "avatar_size": avatar_size, "avatar_align": avatar_align,
        "banner": banner, "created_at": _now(now),
    })


def make_delete(device: DeviceKeys, target_msg_id: str,
                now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_DELETE, "target": target_msg_id,
        "created_at": _now(now),
    })


def make_enckey(device: DeviceKeys,
                now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_ENCKEY, "enc_pub": device.enc_pub,
        "created_at": _now(now),
    })


def make_dm(device: DeviceKeys, to_identity: str, body_nonce: str,
            body_ct: str, wraps: dict, created_at: float,
            blob_refs: Sequence[str] = (),
            expires_at: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_DM, "to": to_identity, "body_nonce": body_nonce,
        "body_ct": body_ct, "wraps": wraps, "blobs": list(blob_refs),
        "created_at": created_at, "expires_at": expires_at,
    })


def make_story(device: DeviceKeys, media_kind: str, media: str,
               poster: Optional[str] = None, caption: str = "",
               now: Optional[float] = None) -> SignedMessage:
    created = _now(now)
    return device.sign_message({
        "kind": KIND_STORY, "media_kind": media_kind, "media": media,
        "poster": poster, "caption": caption, "created_at": created,
        "expires_at": created + STORY_TTL,
    })


def make_profile_layout(device: DeviceKeys, order: Sequence[str],
                        grids: Optional[dict] = None, sizes: Optional[dict] = None,
                        pins: Optional[dict] = None, spans: Optional[dict] = None,
                        texts: Optional[dict] = None,
                        now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_PROFILE_LAYOUT, "order": list(order),
        "grids": dict(grids or {}), "sizes": dict(sizes or {}),
        "pins": dict(pins or {}), "spans": dict(spans or {}),
        "texts": dict(texts or {}),
        "created_at": _now(now),
    })


def make_album(device: DeviceKeys, album_id: str, members: Sequence[str],
               now: Optional[float] = None) -> SignedMessage:
    return device.sign_message({
        "kind": KIND_ALBUM, "album_id": album_id, "members": list(members),
        "created_at": _now(now),
    })


def make_wrap_grant(device: DeviceKeys, target_msg_id: str, wraps: dict,
                    now: Optional[float] = None) -> SignedMessage:
    """Extra sealed content-key wraps for an EXISTING post ("a wall is a
    wall", spec 2026-07-15): additive, deduplicable — multiple grants for
    one target union at the reader. Only meaningful when signed by the
    target post's own author; consumers enforce that, not this shape."""
    return device.sign_message({
        "kind": KIND_WRAP_GRANT, "target": target_msg_id, "wraps": wraps,
        "created_at": _now(now),
    })


def _is_hexn(s, n) -> bool:
    return (isinstance(s, str) and len(s) == n
            and all(c in "0123456789abcdef" for c in s))


def _is_hex64(s) -> bool:
    return _is_hexn(s, 64)


def _is_hex_color(s) -> bool:
    return (isinstance(s, str) and len(s) == 7 and s[0] == "#"
            and all(c in "0123456789abcdef" for c in s[1:]))


def _valid_wraps(wraps) -> bool:
    if not isinstance(wraps, dict):
        return False
    for dpub, w in wraps.items():
        if not _is_hex64(dpub) or not isinstance(w, dict):
            return False
        if not _is_hex64(w.get("eph_pub")):
            return False
        if not _is_hexn(w.get("nonce"), 24):
            return False
        wk = w.get("wrapped_key")
        if (not isinstance(wk, str) or not wk
                or any(c not in "0123456789abcdef" for c in wk)):
            return False
    return True


def validate_payload(p: dict) -> Tuple[bool, str]:
    if not isinstance(p, dict):
        return False, "payload not a dict"
    if not isinstance(p.get("created_at"), (int, float)):
        return False, "bad created_at"
    kind = p.get("kind")
    if kind == KIND_POST:
        if p.get("scope") not in ("inner", "kreds"):
            return False, "bad scope"
        if p.get("placement", "journal") not in ("journal", "profile"):
            return False, "bad placement"
        if not _is_hexn(p.get("body_nonce"), 24):
            return False, "bad body_nonce"
        ct = p.get("body_ct")
        if (not isinstance(ct, str) or not ct
                or any(c not in "0123456789abcdef" for c in ct)):
            return False, "bad body_ct"
        if not _valid_wraps(p.get("wraps")):
            return False, "bad wraps"
        blobs = p.get("blobs", [])
        if not isinstance(blobs, list) or not all(_is_hex64(b) for b in blobs):
            return False, "bad blobs"
        media = p.get("media", "photo")
        if media not in ("photo", "video"):
            return False, "bad media"
        poster = p.get("poster")
        if media == "video":
            if len(blobs) != 1:
                return False, "video needs one blob"
            if not _is_hex64(poster):
                return False, "bad poster"
        elif poster is not None:
            return False, "poster on non-video"
        exp = p.get("expires_at")
        if exp is not None and not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        return True, "ok"
    if kind == KIND_PROFILE:
        name = p.get("name")
        if not isinstance(name, str) or not (1 <= len(name) <= MAX_NAME):
            return False, "bad name"
        bio = p.get("bio", "")
        if not isinstance(bio, str) or len(bio) > MAX_BIO:
            return False, "bad bio"
        if not _is_hex_color(p.get("accent", "#2743d6")):
            return False, "bad accent"
        for field in ("avatar", "banner"):
            v = p.get(field)
            if v is not None and not _is_hex64(v):
                return False, f"bad {field}"
        if p.get("avatar_shape", "circle") not in AVATAR_SHAPES:
            return False, "bad avatar_shape"
        if p.get("avatar_size", "m") not in AVATAR_SIZES:
            return False, "bad avatar_size"
        if p.get("avatar_align", "left") not in AVATAR_ALIGNS:
            return False, "bad avatar_align"
        return True, "ok"
    if kind == KIND_DELETE:
        if not _is_hex64(p.get("target")):
            return False, "bad target"
        return True, "ok"
    if kind == KIND_ENCKEY:
        if not _is_hex64(p.get("enc_pub")):
            return False, "bad enc_pub"
        return True, "ok"
    if kind == KIND_DM:
        if not _is_hex64(p.get("to")):
            return False, "bad to"
        if not _is_hexn(p.get("body_nonce"), 24):
            return False, "bad body_nonce"
        ct = p.get("body_ct")
        if (not isinstance(ct, str) or not ct
                or any(c not in "0123456789abcdef" for c in ct)):
            return False, "bad body_ct"
        if not _valid_wraps(p.get("wraps")):
            return False, "bad wraps"
        blobs = p.get("blobs", [])
        if not isinstance(blobs, list) or not all(_is_hex64(b) for b in blobs):
            return False, "bad blobs"
        exp = p.get("expires_at")
        if exp is not None and not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        return True, "ok"
    if kind == KIND_STORY:
        if p.get("media_kind") not in ("photo", "video"):
            return False, "bad media_kind"
        if not _is_hex64(p.get("media")):
            return False, "bad media"
        poster = p.get("poster")
        if poster is not None and not _is_hex64(poster):
            return False, "bad poster"
        cap = p.get("caption", "")
        if not isinstance(cap, str) or len(cap) > MAX_CAPTION:
            return False, "bad caption"
        exp = p.get("expires_at")
        if not isinstance(exp, (int, float)):
            return False, "bad expires_at"
        if exp > p.get("created_at", 0) + STORY_TTL:
            return False, "story ttl too long"
        return True, "ok"
    if kind == KIND_RING:
        if not _is_hex64(p.get("member")):
            return False, "bad member"
        if p.get("ring") not in ("inner", "kreds"):
            return False, "bad ring"
        return True, "ok"
    if kind == KIND_PROFILE_LAYOUT:
        order = p.get("order")
        if not isinstance(order, list) or len(order) > MAX_LAYOUT:
            return False, "bad layout order"
        if not all(_is_hex64(x) for x in order):
            return False, "bad layout id"
        grids = p.get("grids", {})
        if not isinstance(grids, dict) or len(grids) > MAX_LAYOUT:
            return False, "bad layout grids"
        for k, v in grids.items():
            if not _is_hex64(k) or v not in GRID_LAYOUTS:
                return False, "bad layout grid"
        sizes = p.get("sizes", {})
        if not isinstance(sizes, dict) or len(sizes) > MAX_LAYOUT:
            return False, "bad layout sizes"
        for k, v in sizes.items():
            if not _is_hex64(k) or v not in SIZE_LAYOUTS:
                return False, "bad layout size"

        # Collage geometry (Slice A). Shape-only, like every other record
        # check here: overlapping pins are the CLIENT's job to prevent -
        # a hostile record just renders as stacked blocks, no crash.
        def _ok_geom(v, need_xy):
            keys = {"x", "y", "w", "h"} if need_xy else {"w", "h"}
            if not isinstance(v, dict) or set(v) != keys:
                return False
            if not all(isinstance(v[k], int) and not isinstance(v[k], bool)
                       for k in keys):
                return False
            if not (1 <= v["w"] <= WALL_COLS and 1 <= v["h"] <= MAX_BLOCK_H):
                return False
            if need_xy and not (0 <= v["x"] and v["x"] + v["w"] <= WALL_COLS
                                and 0 <= v["y"] <= MAX_LAYOUT):
                return False
            return True

        pins = p.get("pins", {})
        if not isinstance(pins, dict) or len(pins) > MAX_LAYOUT:
            return False, "bad layout pins"
        for k, v in pins.items():
            if not _is_hex64(k) or not _ok_geom(v, True):
                return False, "bad layout pin"
        spans = p.get("spans", {})
        if not isinstance(spans, dict) or len(spans) > MAX_LAYOUT:
            return False, "bad layout spans"
        for k, v in spans.items():
            if not _is_hex64(k) or not _ok_geom(v, False):
                return False, "bad layout span"

        # Text block styling (spec 2026-07-14): same idiom as the retired
        # grids map. Values are dicts whose keys are a subset of the seven
        # style fields, each enum-checked; an empty dict is refused (that
        # state is expressed by the key being ABSENT, per set_block_text).
        texts = p.get("texts", {})
        if not isinstance(texts, dict) or len(texts) > MAX_LAYOUT:
            return False, "bad layout texts"
        text_fields = set(TEXT_STYLE_ENUMS) | {"color"}
        for k, v in texts.items():
            if not _is_hex64(k):
                return False, "bad layout text id"
            if not isinstance(v, dict) or not v or not set(v) <= text_fields:
                return False, "bad layout text"
            for field, val in v.items():
                if field == "color":
                    if val not in ("default", "accent") and val not in ACCENTS:
                        return False, "bad layout text color"
                elif val not in TEXT_STYLE_ENUMS[field]:
                    return False, "bad layout text"

        return True, "ok"
    if kind == KIND_ALBUM:
        # A mutable grouping over immutable photo posts. Opaque ids only -
        # the member list is plaintext metadata (same existence-disclosure
        # class as the layout order/pins); content stays per-post encrypted.
        if not _is_hex64(p.get("album_id")):
            return False, "bad album id"
        members = p.get("members")
        if not isinstance(members, list) or len(members) > MAX_LAYOUT:
            return False, "bad album members"
        if not all(_is_hex64(x) for x in members):
            return False, "bad album member"
        if len(set(members)) != len(members):
            return False, "duplicate album member"
        return True, "ok"
    if kind == KIND_WRAP_GRANT:
        if not _is_hex64(p.get("target")):
            return False, "bad target"
        wraps = p.get("wraps")
        if not _valid_wraps(wraps) or not wraps:
            return False, "bad wraps"
        # optional per-entry annotation: which enc_pub the wrap was sealed
        # to, so the author-side sweep can detect stale wraps after the
        # recipient rotates (unwrap_key ignores the extra field)
        for w in wraps.values():
            ep = w.get("enc_pub")
            if ep is not None and not _is_hex64(ep):
                return False, "bad enc_pub"
        return True, "ok"
    return False, "unknown kind"


def is_expired(payload: dict, now: Optional[float] = None) -> bool:
    exp = payload.get("expires_at")
    return exp is not None and exp <= _now(now)
