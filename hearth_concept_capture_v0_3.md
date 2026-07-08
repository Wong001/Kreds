# Hearth — Concept Capture

**Status:** Parked / notes only. Not in active development.
**Priority:** Secondary to Solaris. No build hours, CAD time, or decision-energy until Solaris hits its prototype milestone (~1–2 months out).
**Working name:** Hearth (held loosely — revisit once the thing has a shape).
**Origin:** Idea first sketched Oct/Nov 2020. Revisited mid-2026.
**Version:** v0.3 — D2 spike findings folded in (July 2026). Changes from v0.2 logged at bottom.

---

## One-line thesis

A private, non-commercial, peer-to-peer social space where your data lives on hardware you own, you can only add people you've physically met (QR exchange — no lookup, no discovery), and the influencer economy is impossible *by architecture* rather than forbidden by rule.

The core property worth building around: **privacy as a structural outcome, not a promise.**
- No influencers — because there is no discovery, not because a policy forbids it.
- No surveillance — because there is no server to harvest, not because a policy says so.
This is the kind of value you can't betray even if you wanted to. That's the whole point.

*(v0.2 honesty note: one feature — deletion among friends — is protocol-enforced rather than structural. Stated plainly in its section rather than papered over. Everything else in the thesis survives the adversarial pass.)*

---

## What this is (and isn't)

- **Is:** a protocol / piece of public infrastructure, used like email or a phone line. Not a destination you "go to" to be seen.
- **Isn't:** a growth-oriented mass platform. The no-lookup rule that makes it principled is the same rule that caps its spread. That's acceptable *by design* — "if you want to be seen, this isn't for you."
- **Honest framing:** strong as a *thing that should exist*; weaker as a *thing that wins at scale*. Both projects closest to this (Briar, SSB) stayed small. If a correct, lasting, small piece of infrastructure for the right tens of thousands of people is the goal, this is excellent. If scale is the secret hope, the architecture fights it. Decide which success is actually wanted — it changes every downstream decision.

---

## The competitive landscape (2026)

Nothing is a 1:1 match. The two literal requirements — fully P2P **and** no lookup — both exist and work in shipping software, but split across two projects that each got only half.

### Briar — nails the trust model; it's messaging, not social media
- Fully P2P, no central servers; syncs device-to-device over Tor / Bluetooth / Wi-Fi (Bramble protocol).
- Add contacts via **in-person QR** (or link / mutual-contact intro). **No user directory; no way to discover who uses it without their participation.** This is exactly the anti-lookup model.
- BUT: it's secure *messaging* (chats, forums, blogs) — no rich profile, no creative ownership, aimed at activists/journalists, not general public/youth.
- **Android-only — no iOS — specifically because of Apple's background-process restrictions on P2P.** This is the mobile wall, confirmed in the wild. Briar didn't take the home-node escape hatch and paid for it. (Mechanism: Briar stays reachable via a *persistent Android foreground service* holding a 24/7 Tor connection — exactly the trick iOS forbids. Notifications are generated locally on sync; no push server ever touched. See iOS notification fork below.)
- **Briar Mailbox (2022) — closest prior art to the home node.** An always-on device (typically a repurposed Android phone) reachable as a Tor onion service, caching your messages while your phone is offline. It is a *message buffer only* — not an identity anchor, not a full node, no key custody role — so the Hearth home node is meaningfully more. But it is (a) prior art to cite honestly, and (b) field proof that the onion-service-at-home transport works for exactly this use case.
- Dev began 2011 (Michael Rogers, Eleanor Saitta). Still shipping (release March 2026). Non-profit, grant-funded (OTF, EU digital-rights).

### Scuttlebutt (SSB) / Manyverse — nails the social-media feel; not strictly no-lookup, and fading
- Real self-hosted social network: posts, threads, likes, profiles, "all the features you'd expect." Data on your phone, you own it. Non-commercial by design.
- BUT: has **friend-of-friends discovery** within replication hops — strangers a few hops away *are* surfaced. Fails the strict no-lookup test.
- Not purely P2P: uses "pubs" and "rooms" (servers) for NAT traversal / onboarding.
- Created by Dominic Tarr, 2014. ~30,000 users across the networks. **Lead developer has announced he's stepping away** — the "community-driven, undriven project dies" pattern, with a name and date on it.

### Others (for completeness)
- **Nostr** — protocol written 2020 (fiatjaf), launched 2022. ~18M+ users (2023), 100M+ posts. Relay-based (not pure P2P) and discoverable (not no-lookup) — fails both axes, but the most *alive* in this space, and has solved adjacent problems worth studying (delegated key signing for key safety; Blossom for decentralized media, both 2026). *(v0.2 note: the device-enrollment model below reduces the need for Nostr-style delegation — the phone signs with its own enrolled key — but their work remains the reference design if enrollment needs refinement.)*
- **Diaspora** (2010), **Mastodon** (2016) — federated (servers), discoverable. Fail both.
- **Bluesky / AT Protocol / EuroSky** — discoverable, not P2P. Fail both.

### Timing note
Going earlier would NOT have made you first — Briar (2011) and SSB (2014) predate even the 2020 sketch. You were never going to be first on the *tech*. But the *demand-and-legitimacy environment* (EU sovereignty money, "safety over virality" in policy, post-Twitter exodus, influencer fatigue) is all 2024–2026. Tech mature **and** moment arrived = the rare combination. Now is better, not worse.

---

## Locked architecture decisions (v0.2)

Decisions settled during the July 2026 adversarial review. Treat as locked unless explicitly reopened.

### D1 — Home node runs as a Tor onion service *(closes NAT for the keystone link + metadata privacy in one move)*
- Onion services are NAT-proof by construction: no port forwarding, no dynamic DNS, no rendezvous server of ours. The phone dials the home node's onion address whenever the app is foregrounded.
- Transport is metadata-blind for free — no relay of ours ever sees who talks to whom, when. This closes the "dumb relay sees IP pairs" surveillance gap that a Nostr-style relay layer would have opened.
- Tor is a free volunteer network, not rented infrastructure; Briar has ridden it since 2011 and Briar Mailbox proves the onion-service-at-home pattern specifically.
- **Standing requirement:** transport must be metadata-blind. Tor is the default candidate; an EU-built equivalent (if one matures under EuroStack-adjacent funding) is a swap-in, not a redesign.

### D2 — Device enrollment model for identity *(replaces "home node as key custodian")*
**Status: locked and demonstrated** — validated as running Ed25519 code in the July 2026 spike (`hearth_d2_spike/SPIKE_REPORT.md`, 18/18 tests). All six lifecycle stories (dual enrollment, offline QR friend-add, phone death → desk, node death → phone, total loss → paper seed, wrong-seed rejection) and the forgery/theft surface run as real cryptography, not prose.
- The identity key signs each device's own device key: phone, home node, future tablet. Every enrolled device is a first-class holder of identity.
- Losing any one device loses nothing. Losing *all* enrolled devices is the only identity death. Optional paper seed backup for the cautious.
- Residual risk stated plainly: house fire while the phone is in the house. Acceptable; say it, don't hide it.
- Side effect: **solves delegated signing.** In-person QR friend-adds are signed on the spot by the phone's own enrolled key — no need to reach the home node for authority, no Nostr-style delegation machinery required.
- Security note carried from review: an always-on, internet-reachable box administered by a normal person is not automatically a better key custodian than a modern phone with a secure enclave. Enrollment sidesteps this by making no single device *the* custodian — but the home node's hardening (auto-updates mandatory, minimal attack surface) is a real implementation requirement, not a nice-to-have.
- Hardening addendum (spike, July 2026): **theft of an unlocked device is identity compromise until revoked** — the identity-key replica lives on every enrolled device, so a thief holding a live device *is* the identity until a revocation propagates. Real clients must keep identity-key use behind the OS keystore / biometric gate; the spike models the compromise honestly but the mitigation is implementation-layer.

**Spike findings folded into D2 (binding on any implementation):**
- **Sequence tracking is part of the security model, not an optimization** (Ambush 1). Revocations carry a `last_valid_seq`; a thief can backdate signatures below it. A compliant client MUST remember which sequence numbers it has accepted per friend-device — the demonstrated attack succeeds against any client that doesn't. Cost: one integer-set per contact-device, negligible in a hand-verified graph.
- **Seen-sequence set (or sliding window), not a high-water mark** (Ambush 2 — found only by building). A strict "accept only seq > max-seen" rule rejects legitimate out-of-order gossip delivery. Production clients accept any *unseen* seq and reject *reuse*; backdating protection then comes from the seen-set plus the revocation's `last_valid_seq`. Memory is bounded and prunable (compact below the lowest contiguous seq).
- **Revocations are highest-priority gossip, and clients must support retro-drop** (the gossip-lag window). A friend who hasn't yet received a revocation accepts a thief's messages until it arrives — no signature scheme fixes this; the window equals propagation time. Clients must re-evaluate already-accepted messages when a revocation arrives late.

### D3 — Deletion: structural against strangers, protocol-enforced among friends *(honest reframing, locked)*
- Mechanism: a deletion policy (expiry / revoke tag) travels with every piece of content; compliant clients honor it automatically.
- What enforces it: **the recipient's client, not cryptography.** You cannot force another device to forget bits it holds; a modified client or a screenshot defeats any scheme, for everyone — this is the DRM problem and it has no structural solution.
- Why it still works here: recipients are friends you physically met and hand-verified, not strangers. Strangers never receive your content at all (that part *is* structural). Within a hand-verified graph, protocol-enforced deletion is genuinely strong.
- **Marketing rule:** never claim structural deletion among friends. One screenshot incident contradicting an overclaim costs more trust than the honest version ever would. Approved formulation: *"Deletion is structural against strangers — they never had it — and automatic among friends running compliant clients."*
- Design-for-deletion from day one still stands; SSB's append-only architecture proves it can't be bolted on later.

---

## Borrow / Build / Beware

### BORROW (already solved — don't reinvent)
- Briar's in-person QR handshake + zero-directory model → your friend-add and your anti-lookup. Lift wholesale.
- Briar's serverless Tor/Bluetooth/Wi-Fi transport (Bramble) — proven. **Plus Briar Mailbox's onion-service-at-home pattern** → direct prior art for the home node's transport layer.
- SSB's friends-replicate-your-feed gossip → your "friends carry a fragment" idea, already built.
- Keypair identity, local-first data, non-commercial governance — settled in both.

### BUILD (the actual net-new — this is the product)
- **Home-node + thin-client spine** *(the keystone differentiator).* In neither project (Briar Mailbox is a buffer, not an anchor). Fixes the two things that hurt Briar and SSB most:
  - Key recovery — via the **device enrollment model (D2)**: identity survives any single device loss; only losing everything at once kills it. (Both Briar and SSB: lose your key = identity erased forever. Mailbox doesn't change that.)
  - The mobile wall — the always-on node is the home box reachable as an **onion service (D1)**; the phone is an intermittent node (full node only while app is foregrounded) + thin client. Routes around the Android-only trap.
  - **Lead with this.**
- **Creative profile customization** — present but not load-bearing. Ship a small set of well-designed defaults (good dark theme + a couple of clean alternatives); customization available, not required or prominent. Most take the default. Full open-canvas styling is a V2 question *if users ask*. (Drop the MySpace/Arto lineage — keep the principle "your page is yours," lose the dated aesthetic association.)
- **User-controlled ephemerality / auto-delete** — per **D3**: deletion tags travel with content, honest two-tier framing (structural vs. protocol-enforced). Design for deletion from day one.
- **Photos as a first-class citizen** — both are weak here (Briar text-heavy; SSB hop-limited blobs).
- **Explicit anti-influencer framing for normal people and youth** — not activists.

### BEWARE (where both bled — plan for it)
- **NAT traversal** — largely absorbed by D1 for the phone↔home-node link and node↔node sync over Tor. What remains of the old "who runs the relays" question is much smaller than v0.1 feared: Tor's volunteer network *is* the rendezvous layer, at zero cost and metadata-blind. Watch item: Tor performance for media-heavy sync (photos as first-class citizen vs. onion-service bandwidth) — needs empirical testing, not assumption.
- **Mobile background wall** — real and confirmed (Briar = no iOS). Home node is the answer for reachability; the **iOS notification fork** below is the remaining open decision.
- **The orphan risk** — "release it and let the community keep it alive, undriven" is SSB's exact ending. Keep a steward. Non-commercial ≠ undriven. The trap is the ad-and-data *business model* (the disease), not having a committed maintainer. Survivors (Signal nonprofit, Mastodon, Matrix, Nostr) all kept a small committed core or donation/grant funding.

---

## Open fork: iOS notifications (decide consciously, not three months in)

Briar's answer (persistent foreground service + local notification on sync) is Android-only by nature — it is *why* Briar has no iOS client, not a solution to borrow. The realistic menu for Hearth on iOS:

1. **No push at all** — acceptable to activists; not to the stated audience (normal people, youth).
2. **Pull-on-open only** — clean, zero infrastructure, but "you find out when you check" breaks messaging expectations.
3. **Content-free wake ping via APNs** — a minimal notification relay sends "check your node" with zero content and zero social-graph information through Apple's push service. Leaks the least while keeping normal-person UX; it *is* a central dependency (Apple) and a small piece of infrastructure someone must run.

Leaning: option 3, logged honestly as a concession alongside the metadata-blind-transport requirement. **Not yet locked** — revisit when implementation starts. (Android needs none of this: foreground service à la Briar, or the home node covers it.)

---

## Child-safety stance (coherent, with corrected emphasis)

- Architecture is **strong against the body-image / comparison / influencer-distortion harm** — no virality, no discovery, no follower counts means the machinery that manufactures that harm has no surface to run on. *This is the knockout punch — lead with it, not predators.*
- Architecture is **medium against predators** — kills cold-contact (the biggest vector) via no-lookup, but post-QR-introduction grooming is less protected, and no central authority = nobody to report to. Say so honestly; don't overclaim.
- Education is the right complement — and the "teach kids like the 90s" analogy actually holds *better* inside this walled, un-engineered, no-algorithm garden than it does on mainstream platforms, because you've recreated the conditions that made it viable. Framing: "we rebuilt an environment where parenting like 1999 works again," not "parent like 1999 on the current internet."

---

## Naming

- **Hearth** *(current pick)* — warmth, gathering close, ownership. Enclosure/intimacy framing.
- Other enclosure/intimacy options: Enclave, Commons (fits EU "digital commons" language).
- Anti-discovery / provocative: Unlisted ("the name is the feature"), Offhand, Within Reach.
- Tagline family: "If you want to be seen, this isn't for you." / "No followers. No discovery. No audience. On purpose." / "A protocol, not a stage." / "You can't go viral here. That's the feature."
- (Danish option *Nær* — "near/close" — considered and set aside; not going with a Danish name.)

---

## Study list (free lessons from others' scars)
- **Briar Mailbox** — the onion-service-at-home implementation: provisioning UX (QR-pairing the mailbox), sync protocol, what they got wrong. Closest running code to the home node's transport layer.
- **Nostr** — delegated key signing (reference design if the enrollment model needs refinement); Blossom for decentralized media (2026).
- **Scuttlebutt** — the friend-gossip replication subsystem; the append-only-vs-deletion tension; the maintainer-burnout failure mode.
- **Prunable seen-set / sliding-window designs** — SSB and Bramble both have relevant art; needed for the Ambush 2 resolution (seen-sequence set per device that compacts below the lowest contiguous seq).

---

## Open questions to resolve later
1. Which success is actually being chased — durable-and-small, or scale? (Gates everything.)
2. Home-node form factor — desktop app left running, or a small always-on box (Raspberry Pi-class)? The latter narrows the audience to the more committed/capable. Choose consciously. *(New sub-question from D2: hardening requirements — auto-update story, attack surface — may favor a dedicated box over a family PC.)*
3. iOS notification fork (see section above) — leaning option 3, not locked.
4. Tor bandwidth for media-heavy sync — photos-as-first-class vs. onion-service throughput. Empirical; test, don't assume.
5. Whitespace or desert? The no-lookup gap is genuinely unoccupied — but Briar and SSB both stayed tiny *because* of it. Empirical question; answer by talking to people, not more searching.
6. Household/multi-user home node — one node per person or per household? (Per-household = key separation questions; per-person = cost/complexity.) Minor; decide at implementation.

*(v0.1's open question #3 — "who runs the rendezvous layer" — is retired: D1 makes Tor's volunteer network the rendezvous layer.)*

---

## Reality check (v0.2 — post-adversarial-review)

1. **OS background limits — CLOSED (design), dependency stated.** Home-node-as-onion-service + phone-as-intermittent-node/thin-client. The phone↔home-node link runs over Tor — so this closure *depends on* the metadata-blind transport (D1), which v0.1 didn't state. With that dependency named, it holds. Field precedent: Briar Mailbox. Remaining work = implementation.
2. **Key recovery — CLOSED (design), stronger than v0.1.** Device enrollment (D2): identity survives any single device loss; only total loss kills it; paper seed optional. v0.1's version merely *relocated* the single point of failure to the home node — the enrollment model actually removes it. Residual risk (all devices destroyed at once) stated plainly.
3. **NAT traversal — LARGELY ABSORBED by D1.** Tor onion services eliminate the port-forwarding/rendezvous problem for both the keystone link and node↔node sync, at zero infrastructure cost, metadata-blind. Remaining watch item: media bandwidth over Tor (open question #4). The v0.1 "who runs the relays" concession has shrunk to "does Tor carry photos acceptably" — a testable question, not a governance problem.
4. **Deletion — RESOLVED BY HONEST REFRAMING (D3).** Structural against strangers, protocol-enforced among friends. Not a structural guarantee within the friend graph, and never to be marketed as one.
5. **iOS notifications — OPEN FORK, consciously deferred.** Menu defined, leaning identified, decision deferred to implementation start.
6. **Cold-start / network effects — NOT an architecture problem.** Unchanged from v0.1. A human-adoption problem; Briar and SSB both have working architecture and both stayed tiny. Code can't touch this; it's the item most likely to decide whether Hearth lives.

**The thing to keep in view (unchanged, still true):** "the design is done, it just needs coding" is the sentence that makes a side project feel closer than it is. "Just coding" here = a home-node daemon (now: an onion-service daemon with a hardening story), a phone client, encrypted sync with enrollment and deletion-tag machinery, and an iOS notification path. The v0.2 review closed design gaps but *added* implementation surface — this is months of real engineering, and it stays parked behind a hardware project that's 1–2 months from something physical. Next real input when unparked: open question #5 — talk to people, not more paper.

---

## Changelog

**v0.3 (July 2026)** — D2 spike findings folded in (contained one-session spike, Claude Fable 5; code in `hearth_d2_spike/`):
- D2 status upgraded: "locked on paper" → **"locked and demonstrated"** — all six lifecycle stories and the forgery/theft surface run as real Ed25519 code, 18/18 tests passing. Pointer to `hearth_d2_spike/SPIKE_REPORT.md`.
- Added binding spike findings to D2: sequence tracking is part of the security model (Ambush 1 — backdating thief defeats a non-tracking client); seen-sequence set / sliding window, not a high-water mark (Ambush 2 — strict monotonicity rejects legitimate out-of-order gossip); revocations as highest-priority gossip + retro-drop requirement (gossip-lag window).
- Added D2 hardening addendum: theft of an unlocked device = identity compromise until revoked; identity-key use goes behind OS keystore / biometric in real clients.
- Study-list addition: prunable seen-set / sliding-window designs (SSB and Bramble art).
- No changes to thesis, landscape, D1, D3, or open questions.

**v0.2 (July 2026)** — adversarial review pass (Claude Fable 5 fresh-eyes session, findings locked in chat before writing):
- Added **Locked architecture decisions**: D1 home node as Tor onion service (closes phone↔node NAT + metadata privacy in one move); D2 device enrollment model (replaces node-as-custodian, removes single point of failure, absorbs delegated signing); D3 honest two-tier deletion framing (structural vs. protocol-enforced) with marketing rule.
- Added **Briar Mailbox (2022)** to landscape as closest prior art + field proof of onion-service-at-home; clarified Briar's foreground-service notification mechanism and why it can't port to iOS.
- Added **iOS notification fork** section (three-option menu, leaning content-free APNs wake ping, not locked).
- Reality check rewritten: #1 dependency named, #2 upgraded (relocated → removed), #3 shrunk to a bandwidth test, deletion honesty added, iOS fork added.
- Open questions: retired "who runs the rendezvous layer" (absorbed by D1); added Tor media bandwidth, home-node hardening/form-factor sub-question, household-vs-person node.
- Thesis untouched except one honesty footnote on deletion.

**v0.1** — first capture (landscape, borrow/build/beware, child-safety stance, naming, original reality check).
