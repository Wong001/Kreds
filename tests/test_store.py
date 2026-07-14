import pytest


def test_blob_cap_is_10mb(tmp_path):
    from hearth.messages import MAX_BLOB_BYTES
    from hearth.store import Store
    assert MAX_BLOB_BYTES == 10 * 1024 * 1024
    s = Store(tmp_path / "s.db")
    s.put_blob(b"x" * MAX_BLOB_BYTES)          # exactly at cap: accepted
    with pytest.raises(ValueError, match="10 MB"):
        s.put_blob(b"x" * (MAX_BLOB_BYTES + 1))
