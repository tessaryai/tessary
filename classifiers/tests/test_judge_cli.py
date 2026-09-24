# SPDX-License-Identifier: Apache-2.0
"""ClaudeCliJudge runs the agent CLI in a throwaway directory, so a file the agent decides to write
never lands in the repository. A corpus-generation run once left two untracked .json files in
classifiers/ that way."""

from __future__ import annotations

import sys
from pathlib import Path

from framework.judge import ClaudeCliJudge


def test_complete_returns_the_cli_text_and_leaves_nothing_in_the_working_directory(tmp_path, monkeypatch):
    ran_in = tmp_path / "ran_in.txt"
    fake = tmp_path / "claude"
    # Stands in for the CLI: behaves like an agent that saves its answer to a file as well as
    # printing it, and records where it ran.
    fake.write_text(
        f"#!{sys.executable}\n"
        "import os, sys\n"
        "sys.stdin.read()\n"
        "open('leak.json', 'w').write('{}')\n"
        f"open({str(ran_in)!r}, 'w').write(os.getcwd())\n"
        "print(' {\"label\": \"yes\"} ')\n"
    )
    fake.chmod(0o755)
    repo = tmp_path / "repo"
    repo.mkdir()
    monkeypatch.chdir(repo)

    text = ClaudeCliJudge(binary=str(fake)).complete("system prompt", "user prompt")

    assert text == '{"label": "yes"}'
    assert list(repo.iterdir()) == [], "the CLI wrote into the directory the judge was called from"
    sandbox = Path(ran_in.read_text())
    assert sandbox.resolve() != repo.resolve()
    assert not sandbox.exists(), "the throwaway directory, and what the CLI wrote there, is removed"
