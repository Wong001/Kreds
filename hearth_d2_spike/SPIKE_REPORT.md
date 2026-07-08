# Hearth D2 Spike Report — Device Enrollment Model

**Date:** July 2026 · **Scope:** one session, contained by agreement
**Code:** `hearth_identity.py` (~430 lines) + 18 tests, all passing
**Run it:** `pip install cryptography pytest` → `python demo.py` → `pytest tests/ -v`
**Status of Hearth:** still parked. This spike validates a locked design decision; it does not un-park the project.

---

## Verdict

**D2 survives contact with executable reality.** Every lifecycle story locked in the concept capture v0.2 runs as real Ed25519 cryptography, not as prose. Two genuine ambushes were found — both were *findable only by building* — and both have known resolutions that go into the design notes, not back to the drawing board.

## What was proven (17 of 18 tests)

Confidence: high — these are direct cryptographic demonstrations, not arguments.

1. **Identity ceremony works deterministically.** 32-byte seed → HKDF → identity key. Same seed always yields the same identity, which is what makes paper recovery real.
2. **The delegated-signing claim holds.** An in-person QR friend-add verifies fully offline: cert chain (identity signs device) + device signature over the *scanner's* fresh nonce. The test destroys the home node first to prove no round trip exists. The nonce also kills the photographed-QR replay: a QR captured at one meeting is rejected at any other.
3. **Phone death → walk to your desk.** Home node enrolls the replacement and revokes the corpse; the friend accepts the new device with **no re-meeting**, because everything chains to the identity key she already trusts. Symmetric case (node death, phone re-enrolls) also passes.
4. **Total loss → paper seed.** All devices destroyed; seed from the drawer re-derives the identical identity; fresh device enrolled; old devices revoked; friends accept without re-meeting. This is the story Briar and SSB cannot tell, demonstrated end-to-end.
5. **The forgery surface is closed.** Wrong-identity certs, grafted certs, impostor QRs against an existing friendship, and revocations forged by a non-identity key are all rejected at the right pipeline stage.
6. **Theft + revocation ordering works — with one condition** (see Ambush 1). Post-revocation signatures (seq > last_valid) rejected; out-of-order gossip where the revocation arrives *before* the device's cert still kills the device on arrival.

## Ambush 1 — Backdating thief vs. the naive client *(predicted, confirmed, resolved)*

The revocation scheme carries a `last_valid_seq`: "distrust this device above sequence N." A thief holding the stolen device can simply **sign with a reused low sequence number** — "this post is from before the theft, honest." The test suite demonstrates the attack succeeding against a client that doesn't track sequence numbers, and failing against one that does.

**Design consequence (binding):** sequence tracking per friend-device is **not an optimization — it is part of the security model.** A compliant Hearth client MUST remember which sequence numbers it has accepted. This was exactly the "revocation ordering is messy without a timestamp authority" ambush predicted before the spike; the resolution costs one integer-set per contact-device, which is nothing in a hand-verified friend graph.

## Ambush 2 — Anti-replay vs. out-of-order gossip *(not predicted; found by building)*

The spike's defense against Ambush 1 is a strict high-water mark: accept only seq > max-seen. But gossip networks deliver out of order routinely — and the test shows a **legitimate** earlier message being rejected because a later one arrived first. The anti-theft mechanism and the transport's delivery model are in direct tension.

**Design consequence (binding):** production clients need a **seen-sequence set (or sliding window)** per device, not a high-water mark: accept any *unseen* seq ≤ current time-of-check bound, reject *reuse*. Backdating protection then comes from the combination of the seen-set (no reuse of already-delivered numbers) and the revocation's last_valid_seq (nothing new above N) — closing Ambush 1 without breaking gossip. Memory cost is bounded and prunable (windows can compact below the lowest contiguous seq).

**This finding is the spike's best justification for existing:** it appears nowhere in the concept doc, in Briar's docs, or in the review discussion. It only shows up when the acceptance pipeline is real code.

## Honest limitation demonstrated (not a bug): the gossip-lag window

A friend who has not yet received a revocation accepts the thief's messages until it arrives — demonstrated with two verifiers, one informed, one not. No signature scheme fixes this; revocation is information and information travels at gossip speed. Two consequences for the real build: (a) revocations must be **highest-priority gossip**, propagated ahead of content; (b) clients must support **retro-drop** — re-evaluating already-accepted messages when a revocation arrives late. The window is then bounded by propagation time, which in a friends-replicate-your-feed mesh is short.

## What the spike deliberately did not touch

Networking, Tor, persistence, encryption-at-rest, secure-element key storage, the deletion-tag machinery (D3), and multi-identity households. The identity-key-replica-on-every-device model also means **device theft of an unlocked device is identity compromise until revoked** — the spike models this honestly (the thief's device is "live") but the mitigation (OS keystore, biometric gate on identity-key use) is implementation-layer, out of scope.

## Proposed concept-doc deltas (for v0.3, on confirmation — not yet applied)

1. D2 status: "locked on paper" → **"locked and demonstrated (spike, July 2026)"**, with pointer to this spike.
2. Add to D2: sequence tracking is part of the security model (Ambush 1); seen-set not high-water mark (Ambush 2); revocation gossip priority + retro-drop requirement (lag window).
3. Add to D2 hardening note: unlocked-device theft = compromise-until-revoked; identity-key use behind OS keystore/biometric in real clients.
4. Study-list addition: prunable seen-set/window designs (SSB and Bramble both have relevant art).

## File map

```
hearth_d2_spike/
├── SPIKE_REPORT.md            ← this file
├── hearth_identity.py         ← the library (identity, devices, certs, verifier)
├── demo.py                    ← narrated end-to-end run: python demo.py
└── tests/
    ├── test_lifecycle.py      ← the 6 concept-doc stories
    ├── test_adversarial.py    ← forgery, theft, backdating, gossip lag
    └── test_ordering_ambush.py← Ambush 2, demonstrated
```

Back in the drawer it goes.
