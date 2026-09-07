#!/usr/bin/env python3
"""Rewrite documented Freenet core pins to a new release tag."""

from __future__ import annotations

import argparse
import datetime as dt
import re
from pathlib import Path


def replace_once(path: Path, pattern: str, repl: str) -> None:
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(pattern, repl, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise SystemExit(f"{path}: expected 1 match for {pattern!r}, found {count}")
    path.write_text(new, encoding="utf-8")


def replace_all(path: Path, pattern: str, repl: str, minimum: int = 1) -> None:
    text = path.read_text(encoding="utf-8")
    new, count = re.subn(pattern, repl, text, flags=re.MULTILINE)
    if count < minimum:
        raise SystemExit(
            f"{path}: expected at least {minimum} match(es) for {pattern!r}, found {count}"
        )
    path.write_text(new, encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag", help="freenet-core tag, e.g. v0.2.135")
    parser.add_argument("commit", help="full git commit of that tag")
    parser.add_argument(
        "--date",
        default=dt.date.today().isoformat(),
        help="ISO date for BASELINE.md",
    )
    args = parser.parse_args()

    if not re.fullmatch(r"v\d+\.\d+\.\d+", args.tag):
        raise SystemExit(f"tag must look like vX.Y.Z, got {args.tag!r}")
    version = args.tag[1:]
    repo = Path(__file__).resolve().parent.parent

    replace_all(
        repo / ".github/workflows/ci.yml",
        r'(freenet-core ref to build against \(e\.g\. )v\d+\.\d+\.\d+',
        rf"\g<1>{args.tag}",
    )
    replace_once(
        repo / ".github/workflows/ci.yml",
        r'(default: ")v\d+\.\d+\.\d+(")',
        rf"\g<1>{args.tag}\2",
    )
    replace_once(
        repo / ".github/workflows/ci.yml",
        r'(FREENET_CORE_VERSION: ")v\d+\.\d+\.\d+(")',
        rf"\g<1>{args.tag}\2",
    )
    replace_once(
        repo / "README.md",
        r"(currently core )\d+\.\d+\.\d+",
        rf"\g<1>{version}",
    )
    replace_once(
        repo / "README.md",
        r"(git checkout )v\d+\.\d+\.\d+",
        rf"\g<1>{args.tag}",
    )
    replace_once(
        repo / "docs/BASELINE.md",
        r"(Baseline captured: )\d{4}-\d{2}-\d{2}",
        rf"\g<1>{args.date}",
    )
    replace_once(
        repo / "docs/BASELINE.md",
        r"(Release tag: `)v\d+\.\d+\.\d+(`)",
        rf"\g<1>{args.tag}\2",
    )
    replace_once(
        repo / "docs/BASELINE.md",
        r"(Commit: `)[0-9a-f]{7,}(`)",
        rf"\g<1>{args.commit}\2",
    )
    replace_once(
        repo / "docs/BASELINE.md",
        r"(Core crate version: `)\d+\.\d+\.\d+(`)",
        rf"\g<1>{version}\2",
    )
    replace_once(
        repo / "docs/RELEASING.md",
        r"(freenet-core git ref to build against \(e\.g\. `)v\d+\.\d+\.\d+(`\))",
        rf"\g<1>{args.tag}\2",
    )
    replace_once(
        repo / "docs/RELEASING.md",
        r"(freenet_core_version=)v\d+\.\d+\.\d+",
        rf"\g<1>{args.tag}",
    )
    replace_once(
        repo / "scripts/cut-release.sh",
        r'(e\.g\. \$\(basename "\$0"\) )v\d+\.\d+\.\d+',
        rf"\g<1>{args.tag}",
    )
    print(f"Pinned documentation to {args.tag} ({args.commit[:12]})")


if __name__ == "__main__":
    main()
