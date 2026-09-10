#!/usr/bin/env python3
"""Return whether published APK tags already cover a Freenet core release.

A core tag vX.Y.Z is covered by an exact APK tag vX.Y.Z or by a fork suffix
vX.Y.Z.N (for example v0.2.135.1). v0.2.1350 does not cover v0.2.135.
"""

from __future__ import annotations

import argparse
import sys


def normalize_tag(tag: str) -> str:
    value = tag.strip()
    if not value:
        return ""
    return value if value.startswith("v") else f"v{value}"


def apk_covers_core(core_tag: str, published_tags: list[str]) -> bool:
    core = normalize_tag(core_tag)
    if not core:
        return False
    prefix = core + "."
    for raw in published_tags:
        tag = normalize_tag(raw)
        if tag == core or tag.startswith(prefix):
            return True
    return False


def self_test() -> None:
    cases: list[tuple[str, list[str], bool]] = [
        ("v0.2.135", ["v0.2.135"], True),
        ("v0.2.135", ["v0.2.135.1"], True),
        ("0.2.135", ["v0.2.135.2", "v0.2.134.11"], True),
        ("v0.2.135", ["v0.2.134", "v0.2.134.11"], False),
        ("v0.2.135", ["v0.2.1350"], False),
        ("v0.2.135", [], False),
    ]
    for core, tags, expected in cases:
        got = apk_covers_core(core, tags)
        if got != expected:
            raise SystemExit(
                f"self-test failed: core={core!r} tags={tags!r} expected={expected} got={got}"
            )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--core", help="freenet-core tag, e.g. v0.2.135")
    parser.add_argument(
        "--self-test",
        action="store_true",
        help="run built-in checks and exit",
    )
    args = parser.parse_args()
    if args.self_test:
        self_test()
        return
    if not args.core:
        raise SystemExit("--core is required unless --self-test")
    tags = [line.strip() for line in sys.stdin if line.strip()]
    sys.exit(0 if apk_covers_core(args.core, tags) else 1)


if __name__ == "__main__":
    main()
