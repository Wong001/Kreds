import sys
from pathlib import Path

# Make android_tor_spike/tools importable and ensure the repo root (for
# `import hearth`) is on the path regardless of pytest rootdir.
_SPIKE = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(_SPIKE / "tools"))
sys.path.insert(0, str(_SPIKE.parent))
