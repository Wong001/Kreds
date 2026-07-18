# Reactions + comments (journal posts), story replies-as-DMs (design, 2026-07-18)

Product stance (August): "no likes" was really "no discovery" — there is
no public audience to farm reactions from, and that stays true. Reactions
and comments from FRIENDS are wanted. Phase A: a comment section + fixed
reaction set on journal-feed posts. Phase B: story reactions/replies
delivered to the story owner as a DM (the Instagram mechanic).

## Why author-relay (the structural constraint)

`ingest_message` refuses messages from unknown identities (store.py —
the same gate that blocks unfriend-resurrection; load-bearing, not
weakened). The post author's friends are not necessarily each other's
friends, so a commenter's message can never sync directly to the rest
of the audience. Chosen architecture (August, over mutual-only gossip):
**responses travel through the author's node.** Consequences we want:
one consistent comment section for every viewer, verifiable authorship,
and moderation for free — the author's node relaying IS the author's
comment section. Honest cost: fan-out waits for the author's node to be
online (a home node makes that invisible), one extra hop of latency.

## Engagement privacy (August's requirement — the load-bearing part)

Naive author-relay would disclose the commenter's identity (name +
identity key) to the post's ENTIRE audience — strangers to the
commenter. That is graph discovery through a side door and is rejected.

**Private-by-default, two-tier disclosure:**

- Every response carries an **alias**: a random per-post pseudonym seed
  chosen by the commenter's client, rendered as a neutral name +
  default avatar tinted from the seed. Stable within one post's thread
  (the same stranger reads as the same alias there), unlinkable across
  posts (fresh seed per post).
- A **mutual box** rides alongside: the commenter's real identity +
  signature, sealed into per-recipient slots for the commenter's OWN
  friends' devices at comment time. Slots are ANONYMOUS (sealed-box
  construction: ephemeral X25519 key per slot + HKDF + ChaCha20-
  Poly1305 — existing primitives, no new dependency) and carry **no
  recipient identifier**: a labeled wrap list would leak the
  commenter's friend graph to the audience, the same hole again.
  Recipients trial-open slots; friends of the commenter find theirs and
  render the real identity, strangers render the alias.
- Slot counts are padded to fixed buckets (8 / 16 / 32 / 64 slots,
  dummy slots of random bytes) so the count only weakly buckets, not
  measures, the commenter's friend-device count. Stated as an honest
  residual disclosure, not hidden.
- **The author always sees the real identity** — the response is
  addressed to them; moderation and abuse handling require knowing who
  wrote what. (August: "OP and others that know you see your name.")
- **Settings toggle** — "Show my name on comments to people who don't
  know me", default OFF, per the structured-settings convention. ON →
  the response is public: identity rides openly in the record, no box.
- Verification split, stated honestly: the commenter's signature is
  over the response plaintext, so mutuals (and the author) verify
  authorship cryptographically; strangers see an author-attested alias
  entry and trust the author's relay — the same trust they place in the
  author's post itself.
- **Community personas tie-in:** this aliasing is the embryo of the
  public/private personas design (ROADMAP "communities" entry gets a
  cross-reference). Engagement privacy and personas must grow from this
  one mechanism, not two competing ones.

## Phase A protocol

**`KIND_RESPONSE`** (responder → author; new kind):

```
{kind, target: <post msg_id>, rkind: "comment" | "reaction" | "retract",
 body: <comment text (<= MAX_COMMENT 500) | reaction token |
        retracted entry ref>,
 alias_seed: <random hex>, public: bool,
 mutual_box: [sealed slots] | null (null when public),
 created_at, body encrypted+wrapped to the AUTHOR's devices only}
```

Routing: the existing self-describing wrap-set routing — wrapped to
author devices only, so only the author ever receives it (the DM
mechanic). Signed by the responder like every message.

**Reaction tokens:** fixed six — `heart, laugh, wow, sad, up, fire`
(rendered ❤️ 😂 😮 😢 👍 🔥). One reaction per person per post:
latest-wins per (responder, post); a `clear` token removes. Structured
options, no free emoji (same rule as text colors).

**Responses record** (author-signed, latest-wins per (author, post) —
the album/profile-layout pattern):

```
{kind: KIND_RESPONSES, target: <post msg_id>,
 entries: [{alias_seed | identity (public entries), name (author-
   attested, public entries only), rkind, body, created_at,
   responder_sig, mutual_box}],
 body encrypted+wrapped to the POST's audience (the post's ring at
 republish time), created_at}
```

(Reaction counts are not a wire field — clients derive them from
entries. Mutual commenters' display names are resolved locally by the
viewers who know them, never carried in the record.)

The author's node rebuilds and republishes this record automatically on
ingesting a valid `KIND_RESPONSE` targeting its own post. Comments are
append-ordered (flat, no threading); reactions latest-wins per
responder. Moderation: the author drops an entry and republishes (their
node's UI exposes per-comment remove on own posts). Retraction: a
responder sends `rkind: "retract"` naming their entry; the author's
node honors it automatically (compliant-client behavior — same honesty
class as deletion, stated in-app). Deleting the post tombstones the
record (delete-tag cascade); an expiring post's record carries the same
`expires_at`.

## Phase A UI (journal feed)

- Each feed entry: a quiet reaction bar (six, with counts, own
  reaction highlighted — tap to set, tap again to clear, tap another to
  switch) + "Comments (n)" toggle expanding the flat thread and a small
  composer (500-char cap mirrored client-side).
- Mutual commenters render with real name/avatar (mutual-box opened);
  strangers render alias name + tinted default avatar. The author's own
  view shows everyone real (their record view already has identities).
- The profile journal rail shows a read-only collapsed count.
- Author's own posts: per-comment remove affordance (their moderation).
- No notifications/badges for responses in v1 (named follow-up).

## Phase B — story reactions/replies as DMs

- Story viewer gains the six reactions + a reply text field.
- Both send a **plain DM to the story owner** with an additive
  `story_ref: {story_id, media_hash}` field on the DM payload.
- The chat thread renders story context above the message: thumbnail
  chip from the story's media/poster while the story lives; "story
  expired" fallback after the 24h TTL (media blob gone). Reaction-only
  replies render as a large emoji beside the chip (the IG look).
- No new kinds, no relay, no aliasing — a direct conversation (see
  Honest limits below for the story_ref correlation caveat this does
  NOT eliminate).

## Compatibility

- New kinds: peers on older cores refuse `KIND_RESPONSE`/
  `KIND_RESPONSES` harmlessly and re-offer until updated (the
  wrap_grant precedent) — comment sections simply appear once a peer
  updates.
- The new web UI calls new core APIs (`/api/react`, `/api/comment`,
  responses in feed rows, story_ref on DMs) → the release ships with
  `min_core_for_web` bumped to it (two-step update, 0.3.12/0.3.16
  precedent).
- All fields on existing kinds (DM `story_ref`) are additive.

## Honest limits (stated in-app where relevant)

- Comment fan-out requires the author's node online; laptop-only
  authors' sections update when they are.
- Retraction and moderation are compliant-client behavior, not DRM —
  a modified client can keep what it received (deletion's own honesty
  stance, unchanged).
- Mutual-box slot buckets weakly disclose friend-device-count range.
- Response existence/count metadata rides the same disclosure class as
  the parent post's wraps.
- Story reactions/replies: `story_ref` rides the plaintext DM envelope,
  the same disclosure class as the DM's own `to`/`wraps` metadata, never
  inside the encrypted body — a third party mutually connected to both
  the reactor and the story owner can correlate which story prompted a
  given DM (and in practice likely already holds that story's blob via
  ordinary story gossip anyway). Only the correlation is exposed; the
  reply's own text/photos stay inside the encrypted DM body. `story_ref`
  is also shape-validated only (`validate_payload`'s `_valid_story_ref`
  check) — never resolved against a real story the DM's target actually
  posted, the same compliant-client precedent as `KIND_DELETE.target`
  (also never verified to reference a real, existing message).

## Testing sketch

- messages: KIND_RESPONSE/KIND_RESPONSES validation (caps, tokens,
  alias/public/mutual_box shapes; hostile junk fails closed —
  referenced-blobs-guard discipline).
- crypto: sealed-slot round trip (friend opens, stranger cannot, author
  path, bucket padding sizes); responder_sig verification over
  plaintext canonical form; wrong-slot trial-decrypt cost sanity.
- node: respond → author auto-republish → audience row carries the
  section; latest-wins reaction switch/clear; retract; moderation drop;
  post delete cascades; expiry rides.
- sync: three-node — author A, mutuals B/C where B,C are NOT friends:
  B comments, C sees alias; B and C friends: C sees real identity.
  Old-core peer refuses the kinds harmlessly.
- UI: asset pins + UI_E2E smoke (react, comment, alias vs mutual
  rendering, story reply landing in DM with context chip).

## Out of scope (named)

Threading; reactions on comments; comment photos; wall/profile-post
comments (follow-up — the record mechanics transfer 1:1); response
notifications/badges; Open-scope anything; per-post privacy override
(the toggle is global, v1); alias persistence across posts (deliberate
non-goal — unlinkability wins).
