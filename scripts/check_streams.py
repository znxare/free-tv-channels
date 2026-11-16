#!/usr/bin/env python3
"""Check that every stream in channels.json still plays.

A channel passes when its playlist answers with a real HLS playlist (#EXTM3U).
For a master playlist, the first quality level is fetched too, because a
master file often stays online after the actual video has gone.
Each channel gets three tries before it is marked broken, so one slow
response doesn't raise a false alarm.

Standard library only, so it runs on a stock GitHub runner or any PC:

    python scripts/check_streams.py                 # print a report
    python scripts/check_streams.py --report r.md   # also write it as Markdown
    python scripts/check_streams.py --fail          # exit 1 if anything is broken
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

TIMEOUT = 15
TRIES = 3
WAIT_BETWEEN_TRIES = 5
WORKERS = 16
UA = "Mozilla/5.0 (Linux; Android 11; Android TV) FreeTV-link-check"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return r.geturl(), r.read(256 * 1024).decode("utf-8", "replace")


def check_once(url):
    """Return None when the stream looks alive, else a short reason."""
    try:
        final, body = fetch(url)
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}"
    except Exception as e:  # timeout, DNS, TLS, refused…
        return type(e).__name__ if not str(e) else f"{type(e).__name__}: {str(e)[:80]}"
    if not body.lstrip().startswith("#EXTM3U"):
        return "not an HLS playlist"
    if "#EXT-X-STREAM-INF" not in body:
        return None  # media playlist: it's the video itself
    # Master playlist: follow the first quality level.
    lines = body.splitlines()
    variant = next((lines[i + 1].strip() for i, l in enumerate(lines[:-1])
                    if l.startswith("#EXT-X-STREAM-INF") and lines[i + 1].strip()), None)
    if not variant:
        return "master playlist lists no streams"
    try:
        _, vbody = fetch(urljoin(final, variant))
    except urllib.error.HTTPError as e:
        return f"quality level: HTTP {e.code}"
    except Exception as e:
        return f"quality level: {type(e).__name__}"
    return None if vbody.lstrip().startswith("#EXTM3U") else "quality level is not an HLS playlist"


def check(channel):
    reason = None
    for attempt in range(TRIES):
        reason = check_once(channel["url"])
        if reason is None:
            return channel, None
        if attempt < TRIES - 1:
            time.sleep(WAIT_BETWEEN_TRIES)
    return channel, reason


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--channels", default="channels.json")
    ap.add_argument("--report", help="write a Markdown report to this file")
    ap.add_argument("--fail", action="store_true", help="exit 1 when any channel is broken")
    args = ap.parse_args()

    with open(args.channels, encoding="utf-8") as f:
        channels = json.load(f)

    with ThreadPoolExecutor(WORKERS) as pool:
        results = list(pool.map(check, channels))
    broken = [(c, r) for c, r in results if r]
    ok = len(channels) - len(broken)

    print(f"{ok} of {len(channels)} channels working")
    for c, r in broken:
        print(f"  BROKEN  {c['n']:>3}  {c['name']}  ({r})")

    if args.report:
        # The marker line lets the workflow tell whether the broken set changed since last run.
        marker = ",".join(str(c["n"]) for c, _ in broken)
        out = [f"<!-- broken:{marker} -->",
               f"**{ok} of {len(channels)} channels working.** "
               f"Checked {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}.", ""]
        if broken:
            out += ["| # | Channel | Group | Problem |", "|---|---|---|---|"]
            out += [f"| {c['n']} | {c['name']} | {c['group']} | {r} |" for c, r in broken]
            out += ["",
                    "Checked from a GitHub server in the US. A channel that only allows viewers "
                    "in India can show here even though it plays fine on a TV in India. "
                    "Test it in VLC before removing it.",
                    "",
                    f"Fix the `url` in [`channels.json`](https://github.com/"
                    f"{os.environ.get('GITHUB_REPOSITORY', 'znxare/free-tv-channels')}/blob/main/channels.json), "
                    "or remove the channel "
                    "and renumber. This issue updates itself every day and closes when everything plays."]
        with open(args.report, "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")

    sys.exit(1 if (args.fail and broken) else 0)


if __name__ == "__main__":
    main()
