# SPDX-License-Identifier: Apache-2.0
"""Tiny ``.env`` loader — no ``python-dotenv`` dependency, mirrors what ``pull_langfuse.py``
assumes is already in ``os.environ``. Call ``load()`` once at process start; it never overrides
variables already set in the real environment.
"""

from __future__ import annotations

import os
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[3]
ENV_PATH = REPO_ROOT / ".env"


def load(path: Path = ENV_PATH) -> None:
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        key = key.strip()
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        os.environ.setdefault(key, value)
