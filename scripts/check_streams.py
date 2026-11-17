#!/usr/bin/env python3
"""Check that every stream in channels.json still plays.

A channel passes when its playlist answers with a real HLS playlist (#EXTM3U).
For a master playlist, the first quality level is fetched too, because a
master file often stays online after the actual video has gone.
Each channel gets three tries before it is marked broken, so one slow
response doesn't raise a false alarm. A channel that answers "403 Forbidden"
is listed as India-only instead of broken: that's how Indian broadcasters
turn away viewers abroad, including GitHub's US servers.

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
    # "403 Forbidden" means the server is up but refuses this viewer. Indian broadcasters
    # (Zee, Times Network, Shemaroo…) answer that way outside India, so from a US runner
    # it says nothing about whether the channel plays for viewers in India.
    india_only = [(c, r) for c, r in results if r and "HTTP 403" in r]
    broken = [(c, r) for c, r in results if r and "HTTP 403" not in r]
    ok = len(channels) - len(broken) - len(india_only)

    print(f"{ok} of {len(channels)} channels working, {len(india_only)} India-only (not checked), {len(broken)} broken")
    for c, r in broken:
        print(f"  BROKEN      {c['n']:>3}  {c['name']}  ({r})")
    for c, _ in india_only:
        print(f"  INDIA-ONLY  {c['n']:>3}  {c['name']}")

    if args.report:
        # The marker line lets the workflow tell whether the broken set changed since last run.
        marker = ",".join(str(c["n"]) for c, _ in broken)
        out = [f"<!-- broken:{marker} -->",
               f"**{len(broken)} broken, {ok} working, {len(india_only)} India-only.** "
               f"Checked {time.strftime('%Y-%m-%d %H:%M UTC', time.gmtime())}.", ""]
        if broken:
            out += ["### Broken", "", "| # | Channel | Group | Problem |", "|---|---|---|---|"]
            out += [f"| {c['n']} | {c['name']} | {c['group']} | {r} |" for c, r in broken]
            out += ["",
                    f"Fix the `url` in [`channels.json`](https://github.com/"
                    f"{os.environ.get('GITHUB_REPOSITORY', 'znxare/free-tv-channels')}/blob/main/channels.json), "
                    "or remove the channel and renumber. Test the link in VLC first. "
                    "This issue updates itself every day and closes when nothing is broken.", ""]
        if india_only:
            out += ["<details><summary>India-only: not checked (" + str(len(india_only)) + ")</summary>", "",
                    "These answered *403 Forbidden* to the US checking server, which is how Indian "
                    "broadcasters block viewers abroad. They aren't counted as broken; check them on a TV in India.",
                    ""]
            out += [f"- {c['n']} {c['name']}" for c, _ in india_only]
            out += ["", "</details>"]
        with open(args.report, "w", encoding="utf-8") as f:
            f.write("\n".join(out) + "\n")

    sys.exit(1 if (args.fail and broken) else 0)


if __name__ == "__main__":
    main()
