# Contributing to Free TV India

Thanks for helping keep Indian TV free and easy! 🙏

## The one rule

**Only official, free-to-air streams.** A stream is accepted only if it comes from:

- the broadcaster's own servers (for example `feeds.intoday.in`, `n18syndication.akamaized.net`, `aasthaott.akamaized.net`), or
- an official free-TV partner feed (Samsung TV Plus, Rakuten TV, Tubi, Xumo, Prasar Bharati and similar).

Paid channels, re-streams from third-party servers, bare IP addresses and links copied out of other apps are always rejected.

## Editing `channels.json`

Each channel is one entry:

```json
{ "n": 7, "name": "ABP News", "group": "Hindi news", "url": "https://…/master.m3u8" }
```

| Field | Rules |
|---|---|
| `n` | Channel number. The list must run **1, 2, 3… with no gaps**, in group order. |
| `name` | The channel's own name. |
| `group` | One of: `Government`, `Hindi news`, `English news`, `Business`, `World news`, `Devotional`, `Music`, `Movies`, `Entertainment`, `Knowledge`, `Kids`. |
| `url` | A direct `https://` HLS (`.m3u8`) link. No YouTube links: the app is deliberately browser-free. |

Before opening a pull request:

1. Play the link in VLC (**Media → Open Network Stream**) and check it works.
2. Keep numbers continuous and groups together.
3. Say in the PR where the stream comes from, so it can be checked as official.

Once merged, every TV running Free TV picks up the change the next time the app opens.

## App changes

The app lives in [`app/`](app). Keep it light: no new libraries without a very good reason, no web views and no analytics. Test on a real Android TV or projector if you can.
