import pytest


def test_blob_cap_is_10mb(tmp_path):
    from hearth.messages import MAX_BLOB_BYTES
    from hearth.store import Store
    assert MAX_BLOB_BYTES == 10 * 1024 * 1024
    s = Store(tmp_path / "s.db")
    s.put_blob(b"x" * MAX_BLOB_BYTES)          # exactly at cap: accepted
    with pytest.raises(ValueError, match="10 MB"):
        s.put_blob(b"x" * (MAX_BLOB_BYTES + 1))


def test_blob_sizes_survives_hostile_length_want_list(tmp_path):
    """CRITICAL (whole-branch review): a hostile-friend post can claim tens
    of thousands of hex64 blob refs. blob_sizes used to build ONE SQL
    IN(...) with one bound variable per hash -- past SQLite's per-statement
    bound-variable limit (999 on older builds, 32766 on modern ones), this
    raised and bricked the sync session at the blobs phase for every honest
    peer. A hostile-length want list must instead degrade to a few slow,
    chunked round trips and never raise."""
    from hearth.store import Store
    s = Store(tmp_path / "s.db")
    fake_hashes = [format(i, "064x") for i in range(40000)]
    sizes = s.blob_sizes(fake_hashes)          # must not raise
    assert sizes == {}                          # none of these are held

    # Chunk-boundary correctness: seed 3 real blobs and place them right
    # across the 900-per-batch boundary (last item of batch 1, first two
    # of batch 2) in a 1000+-hash query, confirming all three sizes come
    # back correct even though they're split across chunks.
    h1 = s.put_blob(b"a" * 10)
    h2 = s.put_blob(b"b" * 20)
    h3 = s.put_blob(b"c" * 30)
    want = fake_hashes[:899] + [h1, h2, h3] + fake_hashes[899:1100]
    sizes = s.blob_sizes(want)
    assert sizes[h1] == 10 and sizes[h2] == 20 and sizes[h3] == 30
    assert len(sizes) == 3                      # none of the fakes are held
