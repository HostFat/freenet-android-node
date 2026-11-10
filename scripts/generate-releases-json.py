#!/usr/bin/env python3
"""Write public/releases.json with the newest app and core release per channel."""
import json
import os
import re
import sys
import urllib.request

TOKEN = os.environ.get("GITHUB_TOKEN", "")
HEADERS = {
    "Accept": "application/vnd.github+json",
    "User-Agent": "freenet-android-releases-feed",
}
if TOKEN:
    HEADERS["Authorization"] = f"Bearer {TOKEN}"

VERSION = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:\.(\d+))?(?:-.*)?$")


def parse(tag: str):
    match = VERSION.match(tag.strip())
    if not match:
        return None
    return tuple(int(part or 0) for part in match.groups())


def is_prerelease(tag: str) -> bool:
    clean = tag.strip().lstrip("v")
    return "-" in clean or len(clean.split(".")) > 3


def pick(releases, include_beta):
    best = None
    best_parts = None
    for release in releases:
        if release.get("draft"):
            continue
        if release.get("prerelease") and not include_beta:
            continue
        tag = release.get("tag_name") or release.get("title") or ""
        parts = parse(tag)
        if not parts:
            continue
        if best_parts is None or parts > best_parts:
            best_parts = parts
            best = {
                "tag": tag,
                "version": tag.lstrip("v"),
                "prerelease": bool(release.get("prerelease")),
                "url": release.get("html_url") or release.get("url") or "",
            }
    return best


def from_api(repo: str):
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases?per_page=30",
        headers=HEADERS,
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    return [
        {
            "tag_name": item.get("tag_name"),
            "html_url": item.get("html_url"),
            "draft": item.get("draft"),
            "prerelease": item.get("prerelease"),
        }
        for item in payload
    ]


def from_atom(repo: str):
    request = urllib.request.Request(
        f"https://github.com/{repo}/releases.atom",
        headers={"User-Agent": HEADERS["User-Agent"]},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        xml = response.read().decode("utf-8", "replace")
    releases = []
    for entry in re.findall(r"<entry>(.*?)</entry>", xml, re.S):
        title = re.search(r"<title>([^<]+)</title>", entry)
        link = re.search(r'<link[^>]+href="([^"]+)"', entry)
        if not title:
            continue
        tag = title.group(1).strip()
        releases.append(
            {
                "tag_name": tag,
                "html_url": link.group(1) if link else "",
                "draft": False,
                "prerelease": is_prerelease(tag),
            }
        )
    return releases


def releases_for(repo: str):
    try:
        return from_api(repo)
    except Exception as error:
        print(f"api failed for {repo}: {error}; trying atom", file=sys.stderr)
        return from_atom(repo)


def main():
    app = releases_for("HostFat/freenet-android-node")
    core = releases_for("freenet/freenet-core")
    payload = {
        "app": {
            "stable": pick(app, False),
            "beta": pick(app, True),
        },
        "core": {
            "stable": pick(core, False),
            "beta": pick(core, True),
        },
    }
    os.makedirs("public", exist_ok=True)
    with open("public/releases.json", "w", encoding="utf-8") as output:
        json.dump(payload, output, indent=2, sort_keys=True)
        output.write("\n")
    print(json.dumps(payload, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
