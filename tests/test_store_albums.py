"""Latest-wins album resolution per (identity, album_id)."""
from hearth.messages import make_album
from hearth.node import HearthNode

AID1 = "aa" * 32
AID2 = "bb" * 32
M1, M2, M3 = "11" * 32, "22" * 32, "33" * 32


def test_albums_latest_wins_per_id(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Anna", "anna-pc")
    n.store.ingest_message(make_album(n.device, AID1, [M1], now=100.0))
    n.store.ingest_message(make_album(n.device, AID2, [M2], now=101.0))
    n.store.ingest_message(make_album(n.device, AID1, [M1, M3], now=102.0))
    albums = n.store.albums(n.identity_pub)
    assert albums[AID1] == [M1, M3]        # newest record wins for AID1
    assert albums[AID2] == [M2]


def test_empty_members_resolves_empty(tmp_path):
    n = HearthNode.create(tmp_path / "n", "Anna", "anna-pc")
    n.store.ingest_message(make_album(n.device, AID1, [M1], now=100.0))
    n.store.ingest_message(make_album(n.device, AID1, [], now=101.0))
    assert n.store.albums(n.identity_pub)[AID1] == []
