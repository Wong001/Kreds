# Releasing Kreds

How to cut a signed Kreds release: build the `.exe` -> assemble the release
bundle -> sign it -> publish it to `kreds_updater` -> verify a client picks
it up. Read `packaging/README.md` first if you haven't built the `.exe`
before -- this is the release half of that same pipeline.

**The offline release private key never touches this repo, CI, or any
script that runs unattended.** Every signing step below is a manual,
local, human-run command with the key file passed explicitly by path.
`.gitignore` blocks `release_private_key*` as a last-resort safety net, but
the real control is: the key only ever lives on the developer's machine
(or on paper -- the "book key"), never checked in, never emailed, never
pasted into a Claude/CI session.

## 0. One-time setup

Generate the release keypair once, offline:

```powershell
.venv\Scripts\python.exe -c "
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization as s
k = Ed25519PrivateKey.generate()
priv = k.private_bytes(s.Encoding.Raw, s.PrivateFormat.Raw, s.NoEncryption()).hex()
pub  = k.public_key().public_bytes(s.Encoding.Raw, s.PublicFormat.Raw).hex()
print('PRIVATE (keep offline, e.g. release_private_key.hex -- never commit):')
print(priv)
print('PUBLIC (bake into hearth/update.py RELEASE_PUBKEY):')
print(pub)
"
```

- Write the private key to paper (or a hardware key / offline vault) --
  the "book key" -- and to a local file `release_private_key.hex`
  (gitignored) for signing. Do not store the only copy in a place that can
  vanish with one disk failure.
- Put the public key hex into `hearth/update.py`'s `RELEASE_PUBKEY`
  constant and commit that (it's meant to be public -- it's what every
  installed Kreds app carries baked in to verify updates against).
- Create the public feed repo once: `gh repo create wong001/kreds_updater
  --public` (or via the GitHub UI). It only ever holds Releases (signed
  manifests + bundles) -- no source code.

## 1. Bump the version

`hearth/__init__.py`'s `__version__` is `CORE_VERSION`. Bump it (semver:
`MAJOR.MINOR.PATCH`) and commit that change like any other code change
*before* building -- the `.exe`, the bundles, and the manifest all derive
their version from this one place.

## 2. Build the `.exe` (signed)

Release builds are Authenticode-signed with the Certum code-signing
certificate ("Open Source Developer August Wong", via SimplySign cloud).
First connect **SimplySign Desktop** (tray icon -> Connect to SimplySign,
log in with the SimplySign ID + the 6-digit token from the mobile app) --
the cert is only visible to Windows while that session is connected. Then:

```powershell
.venv\Scripts\Activate.ps1
pip install -r requirements.txt -r requirements-dev.txt
.\packaging\build.ps1 -Sign
```

`-Sign` signs the launcher exe, the payload exe, and `KredsSetup.exe`,
each with a Certum timestamp (signatures stay valid after the cert
expires). It must happen in THIS step, not after: step 3's manifest hashes
the payload bytes, so signing later would break client update
verification. Plain `.\packaging\build.ps1` (dev build) is unchanged and
produces unsigned exes.

The cert thumbprint is a default parameter in `packaging/build.ps1`
(`-SignThumbprint`); it's a public identifier, not a secret, and only
changes if the certificate is ever reissued. The SimplySign credentials
follow the same hygiene as the release key: never in a script, never in
an agent session.

Produces `dist\Kreds\` (see `packaging/README.md`), including the payload
at `dist\Kreds\versions\<CORE_VERSION>\` -- that payload directory is the
`--core` input to the next step.

Smoke-test the build before releasing it: start `dist\Kreds\Kreds.exe`,
confirm it launches to a working node (poll for its listening port,
`GET /api/bootstrap` -> `200`), then close it. Don't publish an exe you
haven't started at least once.

## 3. Assemble the release (`release-build`)

```powershell
.venv\Scripts\python.exe -m hearth release-build `
  --version <CORE_VERSION> `
  --web hearth\web `
  --core dist\Kreds\versions\<CORE_VERSION> `
  --out release `
  --notes "what changed in this release"
```

This writes, into `release/`:

- `web-<version>.zip` -- `hearth/web/` zipped (`update.build_web_bundle`).
- `core-<version>.zip` -- the PyInstaller payload dir zipped
  (`update.build_core_bundle`; `Kreds.exe` lands at the zip root, which is
  what `hearth.coreupdate.apply_staged_core` expects on extract).
- `manifest.json` -- `{version, core_version, min_core_for_web, web:
  {url, sha256, size}, core: {url, sha256, size}, notes}`. The `sha256`/
  `size` fields are computed from the ACTUAL bytes just written above --
  not placeholders -- so a client that downloads and re-hashes a bundle
  must match exactly. `web`/`core` `url`s already point at this release's
  final `kreds_updater` asset locations
  (`.../releases/download/v<version>/<file>`), even though nothing is
  uploaded yet.

`min_core_for_web` defaults to `"0.0.0"` (this release's web bundle works
with any already-installed core). Pass `--min-core-for-web <version>`
explicitly only when the web bundle genuinely depends on something new in
this same release's core (then a client only gets the web update offered
*after* it has picked up the matching core update -- see
`hearth/update.py`'s `check()` docstring for the exact two-step gating
this produces).

`--released-at <ISO8601>` is optional and omitted by default -- there's no
wall-clock default, so re-running `release-build` for the same inputs
produces the same manifest content modulo that one optional field.

## 4. Sign it (`release-sign`) -- OFFLINE KEY, manual step

```powershell
.venv\Scripts\python.exe -m hearth release-sign `
  --manifest release\manifest.json `
  --key <path to release_private_key.hex, e.g. from the book/offline vault>
```

Writes `release\manifest.sig` (a sibling of `manifest.json`, matching what
`hearth.update.check()` fetches by that exact filename next to the feed
URL). Do this on the machine/session that has the offline key, not in an
unattended script.

Sanity-check locally before publishing (no network needed):

```powershell
.venv\Scripts\python.exe -c "
from hearth import update
m = open('release/manifest.json','rb').read()
s = open('release/manifest.sig','rb').read()
print('signature verifies:', update.verify_manifest(m, s))
"
```

## 5. Publish (`release-publish` / `gh`)

```powershell
.venv\Scripts\python.exe -m hearth release-publish --version <CORE_VERSION> --dir release `
  --installer dist\KredsSetup.exe
```

`--installer` attaches the Inno Setup installer to the same release. This
is what keeps the website's stable download link
(`.../releases/latest/download/KredsSetup.exe`) alive — every release must
carry it or the kreds.eu download page 404s on the next "latest".

The kreds.eu download page (Kreds_website repo,
`src/pages/download.astro`) pins a VERSIONED installer URL + its SHA-256,
so it never desyncs from what it hashes. When publishing a release, update
that page's version, URL, and `sha256sum dist\KredsSetup.exe` alongside.

This is a thin wrapper around:

```powershell
gh release create v<CORE_VERSION> `
  release\manifest.json release\manifest.sig `
  release\web-<CORE_VERSION>.zip release\core-<CORE_VERSION>.zip `
  --repo wong001/kreds_updater
```

(`release-publish --dry-run` prints the exact `gh` command without running
it, e.g. to hand off to someone else or double-check the asset list
first. Requires `gh auth login` once, with push access to
`wong001/kreds_updater`.)

GitHub's `/releases/latest/download/manifest.json` redirect (what
`hearth/update.py`'s `FEED_URL` points at) always resolves to the newest
release's `manifest.json` asset -- so publishing a new release
automatically becomes what every client's next `check()` sees; there's no
separate "make it live" step.

## 6. Verify a client updates

1. Run a Kreds build at the PREVIOUS version (or set
   `HEARTH_UPDATE_FEED` to the new release's `manifest.json` asset URL
   against an older local install, for a faster loop than waiting on a
   full previous build).
2. Open Settings -> Updates -> Check for updates. It should report the
   new version, matching `web-<version>.zip`'s pinned sha256.
3. Apply it: a web-only bump hot-swaps and reloads with no restart; a
   core bump stages `pending-core.zip`/`pending-core.json` and prompts a
   restart, which the launcher applies (re-verifying against
   `RELEASE_PUBKEY` again from scratch -- see `packaging/README.md`'s
   "Core-swap-on-restart layout") before running the new payload.
4. Confirm the running app now reports the new version (Settings, or
   `dist\Kreds\current`'s content after a core update).

If any step fails, nothing should have been half-applied -- `update.py`
and `coreupdate.py` are both written so a bad signature, bad hash, or
downgrade attempt changes nothing on disk. Treat any partial/half-applied
state as a bug to fix before shipping the next release, not a one-off to
patch around.

## Key hygiene reminders

- The private key file (`release_private_key.hex` or wherever it's
  staged for a signing session) is gitignored (`release_private_key*`)
  but that's a safety net, not the control -- never `git add -f` it,
  never paste its contents into a chat/agent session, never leave it on
  a machine that isn't the one you trust with releases.
- `release/` (the assembled bundles + manifest) is build output, not
  source -- gitignored, rebuilt fresh each release.
- If the key is ever suspected compromised: generate a new keypair (step
  0), ship one final release signed with the OLD key that updates
  `RELEASE_PUBKEY` to the new public key, then retire the old private key
  entirely. There is no remote revocation mechanism -- an already-
  installed client only stops trusting the old key once it has applied
  that transition release.
