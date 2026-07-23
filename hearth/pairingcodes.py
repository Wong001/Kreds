"""Pairing-code service (spec 2026-07-22-android-first-load-pairing-
design): the short-lived, single-use bearer code hearth's desktop
Add-device screen mints and displays as a QR ("link" = the code plus the
node's own onion address, packed by invitecodec.encode_pair) for a phone
to redeem over Tor. The code is deliberately weak proof BY ITSELF -- it
only proves "saw the desktop's screen inside the last 10 minutes"; the
actual trust decision is the human Accept/Deny step gating
node.accept_pairing (api.py's /api/pair/accept, node.pending_pair).

In-memory only, like node.py's own _pending_invites (create_invite) --
a restart mid-ceremony just means the human reopens Add-device and gets
a fresh code, same posture as the existing friend-invite ceremony.

Pure stdlib: secrets.choice for the code itself (CSPRNG), hashlib.sha256
for the at-rest form (the code is never stored in the clear, matching
applock's credential-hash posture -- see hearth/applock.py), and
hmac.compare_digest for the verification compare so a process timing the
response can't learn how many leading bytes of a guess matched the real
hash and narrow a brute-force search below the 10-minute TTL window."""
import hashlib
import hmac
import secrets

from .invitecodec import _B58   # the SAME lookalike-free alphabet the
                                 # rest of the ceremony uses -- a human
                                 # may have to type this code by hand
                                 # (the design doc's "typed-code
                                 # fallback"), so 0/O/I/l are excluded
                                 # here for the same reason invitecodec
                                 # excludes them from its own alphabet.

TTL_SECONDS = 600   # 10 minutes (design doc: "short TTL (10 min)")
CODE_LEN = 8         # 8 symbols from a 58-char alphabet is ~46.9 bits of
                     # entropy -- far more than a single guess needs to
                     # beat inside one 10-minute window against only one
                     # active code at a time (a wrong guess neither
                     # extends nor restarts that window) -- while
                     # staying short enough to type by hand.


class PairingCodes:
    """One active code at a time, held on the node object (node.pairing).
    mint() always replaces whatever was active -- reopening the
    Add-device screen must invalidate a code left showing from an
    earlier visit, so a QR photographed once and abandoned can't be
    redeemed later after the human has moved on."""

    def __init__(self):
        self._hash = None          # bytes | None -- sha256 of the active code
        self.expires_at = None     # float | None -- absolute time.time() deadline

    def mint(self, now: float) -> str:
        code = "".join(secrets.choice(_B58) for _ in range(CODE_LEN))
        self._hash = hashlib.sha256(code.encode("utf-8")).digest()
        self.expires_at = now + TTL_SECONDS
        return code

    def verify_and_consume(self, code: str, now: float) -> bool:
        """True exactly once, for the currently active, unexpired code.
        Consuming is atomic: a matching call clears the stored hash
        before returning, so a second call with the SAME code -- a
        replayed wire frame, a retried accept after the phone already
        saw success -- returns False rather than re-authorizing. A
        wrong or expired attempt leaves the active code untouched (an
        honest typo, or a request that merely arrived late, shouldn't
        burn the human's only live code). No lock guards the check-then-
        clear below: this node runs everything -- the HTTP API and the
        Tor wire handler alike -- as coroutines on ONE asyncio event
        loop (hearth/runner.py's run_node), and there is no `await`
        between reading self._hash and clearing it, so nothing can
        interleave a second call inside that window."""
        if not isinstance(code, str):
            # A hostile/malformed wire frame's "code" field might not
            # even be a string (Task 2 decodes untrusted JSON off the
            # wire before this is ever called) -- fail closed rather
            # than raising out of what is, from the caller's point of
            # view, a security check.
            return False
        if self._hash is None or self.expires_at is None:
            return False
        if now > self.expires_at:
            return False
        ok = hmac.compare_digest(
            self._hash, hashlib.sha256(code.encode("utf-8")).digest())
        if ok:
            self._hash = None
            self.expires_at = None
        return ok
