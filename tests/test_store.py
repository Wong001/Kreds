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


def test_removed_responses_migration_adds_rkind_column(tmp_path):
    """CRITICAL (reviewer-repro'd, 2026-07-18): removed_responses widened
    from 3 columns to 4 (adding `rkind`, needed so process_responses'
    fold can tell a moderated comment (exact-match removal) apart from a
    moderated reaction (per-responder cutoff)) in a commit AFTER the
    table was first introduced on this branch. CREATE TABLE IF NOT
    EXISTS is a no-op against an already-existing table, so a DB created
    before that widening would be stuck on the old 3-column shape
    forever: every process_responses fold would then raise ("no such
    column: rkind") on its first target with any moderation history at
    all, and mark_response_removed's 4-value INSERT would raise
    uncaught. Pre-create the OLD 3-column shape directly (bypassing
    Store entirely, the way a real pre-upgrade DB on disk would look),
    then boot a real Store against that exact file and confirm the
    migration heals it before either the read or the write path is
    touched."""
    import sqlite3
    from hearth.store import Store

    db_path = tmp_path / "old.db"
    conn = sqlite3.connect(str(db_path))
    conn.execute(
        "CREATE TABLE removed_responses("
        "target TEXT NOT NULL, responder TEXT NOT NULL,"
        " created_at REAL NOT NULL,"
        " PRIMARY KEY(target, responder, created_at))")
    conn.commit()
    conn.close()

    s = Store(db_path)   # must not raise; migration runs at __init__
    cols = {row[1] for row in
            s._db.execute("PRAGMA table_info(removed_responses)")}
    assert "rkind" in cols

    # Write path: previously an uncaught OperationalError (4 values into
    # a 3-column table).
    s.mark_response_removed("t1", "r1", 100.0, "comment")
    s.mark_response_removed("t1", "r2", 200.0, "reaction")
    # Read path: previously an uncaught OperationalError ("no such
    # column: rkind") on the very first fold that reached it.
    got = set(s.removed_response_tombstones("t1"))
    assert got == {("r1", 100.0, "comment"), ("r2", 200.0, "reaction")}
