"""texts map on the layout record: validation, carry-forward, annotation."""
import pytest

from hearth.messages import ACCENTS
from hearth.node import HearthNode


def _node(tmp_path):
    return HearthNode.create(tmp_path / "n", "Anna", "anna-pc")


def _text_post(n, txt="ord"):
    return n.compose_post(txt, scope="kreds", placement="profile")


def test_set_and_annotate(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    n.set_block_text(t, h="center", v="middle", size="xl", font="disp",
                     weight="bold", style="italic", color=ACCENTS[0])
    view = n.profile_view(n.identity_pub)
    blk = next(p for p in view["wall"] if p["msg_id"] == t)
    assert blk["text_style"] == {"h": "center", "v": "middle", "size": "xl",
                                 "font": "disp", "weight": "bold",
                                 "style": "italic", "color": ACCENTS[0]}


def test_defaults_omitted_and_fully_annotated(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    n.set_block_text(t, h="center")               # one non-default field
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["texts"][t] == {"h": "center"}     # defaults dropped
    n.set_block_text(t, h="left")                 # back to all-default
    lay = n.store.profile_layout(n.identity_pub)
    assert t not in lay["texts"]                  # entry removed entirely
    view = n.profile_view(n.identity_pub)
    blk = next(p for p in view["wall"] if p["msg_id"] == t)
    assert blk["text_style"] == {"h": "left", "v": "top", "size": "auto",
                                 "font": "sans", "weight": "normal",
                                 "style": "normal", "color": "default"}


def test_validation(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    with pytest.raises(ValueError):
        n.set_block_text(t, h="diagonal")
    with pytest.raises(ValueError):
        n.set_block_text(t, color="#123456")      # arbitrary hex rejected
    with pytest.raises(ValueError):
        n.set_block_text(ph, h="center")          # not a text block
    with pytest.raises(ValueError):
        n.set_block_text("zz", h="center")
    aid = None
    # album refusal: build a real album, then try to style it
    p1 = n.compose_post("a", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    aid = n.set_album([p1])
    with pytest.raises(ValueError):
        n.set_block_text(aid, h="center")


def test_carry_forward_everywhere(tmp_path):
    n = _node(tmp_path)
    t = _text_post(n)
    ph = n.compose_post("pic", scope="kreds", placement="profile",
                        photos=[b"\x89PNG fake"])
    n.set_block_text(t, size="xl")
    n.set_block_pin(t, 0, 0, 2, 1)
    # dynamic placement (spec 2026-07-14): creation auto-pins now, so
    # reaching an unplaced/spanned block goes through an explicit unpin.
    n.unpin_block(ph)
    n.set_block_span(ph, 1, 1)
    n.set_block_size(ph, "small")                 # legacy writer
    n.set_profile_layout([t, ph])
    n.unpin_block(t)
    aid = n.set_album([ph])                       # reconciliation publish
    lay = n.store.profile_layout(n.identity_pub)
    assert lay["texts"][t] == {"size": "xl"}      # survived all seven writes
