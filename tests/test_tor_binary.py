import hashlib

import pytest

from hearth import tor


def _write(p, data=b"fake-tor-exe"):
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_bytes(data)
    return p


def test_cached_binary_short_circuits(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    exe = _write(tor.tor_app_dir() / "tor" / "tor.exe")
    # cache hit returns before the download tier is ever reached
    assert tor.ensure_tor_binary(download=False) == exe


def test_bundled_wins_over_cache(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    _write(tor.tor_app_dir() / "tor" / "tor.exe", b"cached")
    bundled = tmp_path / "bundle"
    bexe = _write(bundled / "tor" / "tor.exe", b"bundled")
    assert tor.ensure_tor_binary(bundled_dir=bundled, download=False) == bexe


def test_missing_binary_without_download_raises(tmp_path, monkeypatch):
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    with pytest.raises(RuntimeError):
        tor.ensure_tor_binary(download=False)


def test_verify_sha256_matches_and_mismatches(tmp_path):
    p = tmp_path / "f"
    p.write_bytes(b"payload")
    good = hashlib.sha256(b"payload").hexdigest()
    assert tor.verify_sha256(p, good) is True
    assert tor.verify_sha256(p, "00" * 32) is False


def test_frozen_default_uses_paths_bundled_tor_dir(tmp_path, monkeypatch):
    # bundled_dir=None + frozen -> paths.bundled_tor_dir() is consulted so a
    # packaged run finds its own bundled tor.exe without every caller having
    # to pass it explicitly.
    from hearth import paths
    monkeypatch.setattr(paths, "is_frozen", lambda: True)
    bundled = tmp_path / "bundle"
    bexe = _write(bundled / "tor" / "tor.exe", b"bundled")
    monkeypatch.setattr(paths, "bundled_tor_dir", lambda: bundled)
    assert tor.ensure_tor_binary(download=False) == bexe


def test_non_frozen_default_ignores_paths_bundled_tor_dir(tmp_path, monkeypatch):
    # bundled_dir=None + NOT frozen -> today's behavior is unchanged: no
    # bundled tier is consulted at all, straight to cache/download.
    from hearth import paths
    monkeypatch.setenv("LOOP_TOR_DIR", str(tmp_path))
    monkeypatch.setattr(paths, "is_frozen", lambda: False)
    bundled = tmp_path / "bundle"
    _write(bundled / "tor" / "tor.exe", b"bundled")
    monkeypatch.setattr(paths, "bundled_tor_dir", lambda: bundled)
    with pytest.raises(RuntimeError):
        tor.ensure_tor_binary(download=False)
