from hearth.identity import SeenSet


def test_in_order_compacts():
    s = SeenSet()
    for i in (1, 2, 3):
        assert s.add(i) is True
    assert s.to_json() == {"contiguous": 3, "sparse": []}


def test_out_of_order_is_legal_then_compacts():
    s = SeenSet()
    assert s.add(3) is True          # later message arrives first
    assert s.add(1) is True          # earlier one still accepted (Ambush 2)
    assert s.to_json() == {"contiguous": 1, "sparse": [3]}
    assert s.add(2) is True          # gap fills -> full compaction
    assert s.to_json() == {"contiguous": 3, "sparse": []}


def test_reuse_rejected_in_all_positions():
    s = SeenSet()
    for i in (1, 2, 5):
        s.add(i)
    assert s.add(1) is False         # below contiguous
    assert s.add(5) is False         # in sparse
    assert s.add(0) is False and s.add(-4) is False


def test_max_seen_and_roundtrip():
    s = SeenSet()
    for i in (1, 7, 3):
        s.add(i)
    assert s.max_seen() == 7
    clone = SeenSet.from_json(s.to_json())
    assert clone.has(7) and clone.has(1) and not clone.has(2)


def test_summary_has_static():
    s = SeenSet()
    for i in (1, 2, 6):
        s.add(i)
    summary = s.to_json()
    assert SeenSet.summary_has(summary, 2) is True
    assert SeenSet.summary_has(summary, 6) is True
    assert SeenSet.summary_has(summary, 4) is False
