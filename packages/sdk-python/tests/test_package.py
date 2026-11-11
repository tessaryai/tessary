# SPDX-License-Identifier: Apache-2.0
from importlib import resources


def test_the_package_ships_a_py_typed_marker():
    # Bug: with no py.typed marker, type checkers treat the annotated package as untyped and mypy fails on import.
    assert resources.files("tessary_prompts").joinpath("py.typed").is_file()
