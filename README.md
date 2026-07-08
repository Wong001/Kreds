# Kreds

[![tests](https://github.com/Wong001/Kreds/actions/workflows/ci.yml/badge.svg)](https://github.com/Wong001/Kreds/actions/workflows/ci.yml)

**A private social space for people you've actually met.**

Kreds is a peer-to-peer, end-to-end-encrypted social app. Your data lives
on hardware you own, reachable over the Tor network — there is no server,
no company database, no feed algorithm, and no way to "go viral." You can
only add people you already know — connection takes a single-use invite
code that expires in ten minutes. The influencer economy is impossible by
architecture, not by policy.

Website: [kreds.eu](https://kreds.eu) · Status: **alpha (0.3.x)** — the
protocol is still evolving and breaking changes between versions are
possible.

## What it does

- **Journal-first feed** — a day-grouped view of what your circle shared;
  the circle itself is the navigation.
- **Encrypted posts with audience rings** — every post is encrypted
  per-recipient to a scope you pick: **Kreds** (all friends) or **Inner**
  (a hand-picked subset). There is no public wall and no plaintext post.
- **Encrypted DMs** (text + photos) with windowed forward secrecy —
  daily key rotation, retired keys permanently deleted after 7 days.
- **Curated profiles** — a block-canvas wall (photos, grids, video, text)
  separate from the journal, arrangeable by drag-and-drop.
- **Tor-native** — every node is a `.onion` service; no relay of ours
  ever sees who talks to whom. Tor is bundled and managed; you never
  install it.
- **Invite-code friend-adding** — one short-lived, single-use code,
  shared in person or over any channel you already trust (it works once
  and dies in ten minutes). No discovery, no search, no friend
  suggestions.
- **Structural deletion & unfriending** — signed delete notices gossip to
  every holder; unfriending purges both sides and blocks resurrection.
- **App-lock** — key material encrypted at rest under scrypt + Windows
  DPAPI (opt-in).
- **Signed auto-updates** — releases are Ed25519-signed; clients verify
  before applying anything.

## What it honestly does not do

This project documents its limits as carefully as its features. The
short version: deletion and unfriending are compliant-client behavior,
not DRM — a modified client can keep what it already received, and a
screenshot survives everything. Forward secrecy is windowed, not
per-message. Post recipients can see which devices a post was addressed
to. The full, unvarnished list lives in
[docs/engineering-notes.md](docs/engineering-notes.md) — every feature
section there ends with its honest boundary.

## Run it

**Windows app:** download the installer from
[kreds.eu](https://kreds.eu) or the
[releases feed](https://github.com/Wong001/kreds_updater/releases).

**From source** (Python 3.12, Windows for the full experience —
the node itself is cross-platform, App-lock and packaging are
Windows-only today):

```
python -m venv .venv
.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m hearth demo          # four local nodes on ports 7201-7204
python -m hearth serve --dir <data-dir> --http-port <port>   # a real node
python -m hearth demo --tor    # the demo over real onion services
```

Run the tests:

```
pip install -r requirements-dev.txt
python -m pytest tests -q
```

(Internal package and module names are `hearth` — the project's working
name before it became Kreds.)

## How it's built

Kreds is built by August Wong, with AI-assisted development under human
direction. Every feature ships through the same pipeline: written spec →
implementation → adversarial review → regression tests (the suite is
650+ tests and every fix lands with one). The complete design history —
specs, plans, review findings, and the bugs they caught — is public in
[docs/](docs/), because a privacy product should show its work.

Security review, bug reports, and skeptical reading of the crypto are
genuinely welcome. Open an issue, or reach out via
[kreds.eu](https://kreds.eu).

## License

[AGPL-3.0](LICENSE). Any fork — shipped or hosted as a service — must
stay open.
