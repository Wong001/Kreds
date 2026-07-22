"""PairingCodes (spec 2026-07-22-android-first-load-pairing-design):
short-lived single-use bearer code for the phone<->desktop pairing
ceremony. See hearth/pairingcodes.py's module docstring for the trust
model -- this file only exercises the mint/verify_and_consume contract
in isolation, no wire/API layer involved (that's tests/test_pair_api.py
+ Task 2's own wire tests)."""
import hmac

from hearth import pairingcodes
from hearth.pairingcodes import PairingCodes


def test_mint_then_verify_true_once_then_false():
    p = PairingCodes()
    now = 1000.0
    code = p.mint(now)
    assert p.verify_and_consume(code, now) is True
    assert p.verify_and_consume(code, now) is False   # consumed -- single-use


def test_expired_code_rejected():
    p = PairingCodes()
    now = 1000.0
    code = p.mint(now)
    assert p.verify_and_consume(code, now + 601) is False   # 1s past the 600s TTL


def test_wrong_code_rejected():
    p = PairingCodes()
    now = 1000.0
    p.mint(now)
    assert p.verify_and_consume("not-the-code", now) is False


def test_wrong_code_does_not_burn_the_real_one():
    # An honest typo (or a stray probe) must not consume the human's
    # only live code -- the correct code must still verify afterwards.
    p = PairingCodes()
    now = 1000.0
    code = p.mint(now)
    assert p.verify_and_consume("nope", now) is False
    assert p.verify_and_consume(code, now) is True


def test_second_mint_invalidates_first():
    p = PairingCodes()
    now = 1000.0
    first = p.mint(now)
    second = p.mint(now)
    assert first != second
    assert p.verify_and_consume(first, now) is False
    assert p.verify_and_consume(second, now) is True


def test_no_active_code_rejected():
    p = PairingCodes()
    assert p.verify_and_consume("anything", 1000.0) is False


def test_non_string_code_rejected_not_raised():
    p = PairingCodes()
    p.mint(1000.0)
    assert p.verify_and_consume(None, 1000.0) is False
    assert p.verify_and_consume(12345, 1000.0) is False


def test_code_uses_lookalike_free_alphabet_and_expected_length():
    p = PairingCodes()
    code = p.mint(1000.0)
    assert len(code) == pairingcodes.CODE_LEN
    assert set("0OIl").isdisjoint(set(code))


def test_verify_uses_constant_time_compare(monkeypatch):
    """Pin the constant-time-compare MECHANISM itself, not just its
    behavior -- a functionally-correct but non-constant-time rewrite
    (e.g. a plain == on the hash bytes) would still pass every other
    test in this file, so this one spies on hmac.compare_digest
    directly to catch that regression."""
    p = PairingCodes()
    now = 1000.0
    code = p.mint(now)
    calls = []
    real_compare = hmac.compare_digest

    def spy(a, b):
        calls.append((a, b))
        return real_compare(a, b)

    monkeypatch.setattr(pairingcodes.hmac, "compare_digest", spy)
    assert p.verify_and_consume(code, now) is True
    assert len(calls) == 1
