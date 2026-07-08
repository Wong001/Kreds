# -*- mode: python ; coding: utf-8 -*-
"""PyInstaller spec for Kreds (one-folder build).

Destinations in `datas` MUST match hearth/paths.py's frozen-path
expectations:
    resource_dir()      -> sys._MEIPASS (PyInstaller sets this to the
                            onefolder build's _internal dir automatically)
    bundled_web_dir()    -> resource_dir()/hearth/web
    bundled_tor_dir()    -> resource_dir()  (tor.py then looks for
                            <bundled_tor_dir>/tor/tor.exe, i.e.
                            resource_dir()/tor/tor.exe)
So: hearth/web -> 'hearth/web', and packaging/tor/tor.exe -> 'tor'.

Run via `packaging/build.ps1` (which first stages packaging/tor/tor.exe),
or directly: `pyinstaller packaging/kreds.spec --noconfirm` from the repo
root with the project .venv active.
"""
import os

from PyInstaller.utils.hooks import collect_data_files, collect_submodules

# SPECPATH is PyInstaller's builtin: the directory CONTAINING the .spec file
# (i.e. already packaging/, not the .spec file path itself -- no extra
# dirname() needed here).
PACKAGING_DIR = os.path.abspath(SPECPATH)                        # packaging/
PROJECT_ROOT = os.path.dirname(PACKAGING_DIR)                     # repo root

block_cipher = None

# --- data files -------------------------------------------------------
datas = [
    (os.path.join(PROJECT_ROOT, "hearth", "web"), os.path.join("hearth", "web")),
]

tor_exe = os.path.join(PACKAGING_DIR, "tor", "tor.exe")
if os.path.isfile(tor_exe):
    datas.append((tor_exe, "tor"))
else:
    # build.ps1 stages this before invoking PyInstaller; a direct
    # `pyinstaller packaging/kreds.spec` run without that step still
    # produces a build, but the frozen app will fail to find tor.exe.
    print("WARNING: packaging/tor/tor.exe not found -- built app will not "
          "find a bundled tor.exe. Run packaging/build.ps1 instead of "
          "invoking pyinstaller directly, or stage tor.exe manually.")

# Tray icon at runtime (hearth/paths.py's tray_icon_path expects
# resource_dir()/packaging/kreds.ico when frozen).
datas.append((os.path.join(PACKAGING_DIR, "kreds.ico"), "packaging"))

# imageio_ffmpeg locates its bundled ffmpeg binary via
# importlib.resources.files("imageio_ffmpeg.binaries"), so the ffmpeg exe
# must land at the SAME relative path (imageio_ffmpeg/binaries/...) inside
# the frozen bundle that it has inside the installed package.
datas += collect_data_files("imageio_ffmpeg", subdir="binaries")

# --- hidden imports -----------------------------------------------------
hiddenimports = []
hiddenimports += collect_submodules("uvicorn")     # protocols/*, lifespan/*, loops/*
hiddenimports += collect_submodules("websockets")
hiddenimports += [
    "uvicorn.protocols.http.auto",
    "uvicorn.protocols.websockets.auto",
    "uvicorn.lifespan.on",
    "uvicorn.loops.auto",
    "webview.platforms.edgechromium",
    "cryptography",
    "imageio_ffmpeg",
    "imageio_ffmpeg.binaries",
    "qrcode",
]

a = Analysis(
    [os.path.join(PACKAGING_DIR, "kreds_main.py")],
    pathex=[PROJECT_ROOT],
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
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
    [],
    exclude_binaries=True,
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

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name="Kreds",
)
