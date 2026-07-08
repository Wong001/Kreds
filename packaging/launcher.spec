# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for the Kreds LAUNCHER (one-file build).

This is the STABLE, rarely-rebuilt top-level `Kreds.exe` that ships at
`dist/Kreds/Kreds.exe` (see packaging/build.ps1, which assembles the final
dist/Kreds/ tree out of this build's output plus kreds.spec's payload
build). It re-verifies and applies any staged core update
(hearth.coreupdate, re-verifying against ITS OWN bundled copy of
hearth.update.RELEASE_PUBKEY -- independent of whatever the staged/updated
payload itself might claim), then runs whichever versioned payload is
current (`versions/<version>/Kreds.exe`, built separately by kreds.spec).

One-file (unlike kreds.spec's one-folder payload build) -- there should be
exactly one file at the top level that users/the OS ever see, and it
should change as rarely as possible: only when the launcher/update logic
itself needs a fix, never just because the app's core version bumped.

Run via `packaging/build.ps1`, or directly:
`pyinstaller packaging/launcher.spec --noconfirm` from the repo root with
the project .venv active (produces a standalone dist/Kreds.exe, NOT yet
assembled into the versions/ layout -- that assembly step is build.ps1's
job, not this spec's).
"""
import os

PACKAGING_DIR = os.path.abspath(SPECPATH)          # packaging/
PROJECT_ROOT = os.path.dirname(PACKAGING_DIR)        # repo root

block_cipher = None

# The launcher only needs hearth.coreupdate + hearth.update (Ed25519
# verify via `cryptography`) -- NOT the full node stack (fastapi/uvicorn/
# webview/tor), which stays entirely inside the versioned payload build.
a = Analysis(
    [os.path.join(PACKAGING_DIR, "launcher.py")],
    pathex=[PROJECT_ROOT],
    binaries=[],
    datas=[],
    hiddenimports=["cryptography"],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=[],
    noarchive=False,
    cipher=block_cipher,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

icon_path = os.path.join(PACKAGING_DIR, "kreds.ico")
version_file = os.path.join(PACKAGING_DIR, "version_info.txt")

exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.zipfiles,
    a.datas,
    [],
    name="Kreds",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,
    disable_windowed_traceback=False,
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
    icon=icon_path if os.path.isfile(icon_path) else None,
    version=version_file if os.path.isfile(version_file) else None,
)
