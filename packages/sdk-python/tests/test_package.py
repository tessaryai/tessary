# SPDX-License-Identifier: Apache-2.0
import subprocess
import sys
from importlib import metadata, resources

from tessary_prompts import __version__


def test_the_package_ships_a_py_typed_marker():
    # Bug: with no py.typed marker, type checkers treat the annotated package as untyped and mypy fails on import.
    assert resources.files("tessary_prompts").joinpath("py.typed").is_file()


def test_importing_the_package_does_not_import_opentelemetry():
    # Bug: a hard OpenTelemetry dependency sneaks into the main entry, so a process with no tracing fails to import.
    code = (
        "import sys, tessary_prompts\n"
        "from tessary_prompts import TextPrompt\n"
        "TextPrompt(name='a', version=1, prompt='p').span_attributes()\n"
        "loaded = sorted(m for m in sys.modules if m.split('.')[0] == 'opentelemetry')\n"
        "assert not loaded, loaded\n"
    )
    subprocess.run([sys.executable, "-c", code], check=True)


def test_the_version_constant_matches_the_package_metadata():
    # Bug: the release stamp writes pyproject.toml but not _version.py, so the User-Agent names the wrong version.
    assert __version__ == metadata.version("tessary-prompts")
