# The Anchor

RuneLite Plugin Hub project for The Anchor clan.

## Features

- Home panel with member ranks, points, next-rank progress, BOTW, and SOTW.
- Event alerts delivered to game chat with an optional in-game overlay.
- Evidence screenshots always include the player's RSN, even when chat is closed. Enable **Bingo Overlay → Screenshot only** to include active Bingo details only in captured evidence; leave it off to keep the normal in-game Bingo overlay.
- Automatic full-client evidence capture for eligible loot, collection-log unlocks, pets, Combat Achievement tiers, and PBs.
- Durable local outbox and editable loot-submission drafts.
- Raid scoreboard or observed boss-interaction party estimates, including same-clan participant RSNs while excluding guest names.
- Live and bulk personal-best sync, including Adventure Log team-size records.
- Automatic first-connection and 30-minute sync for every Combat Achievement task and all skill levels/XP.
- One-time collection-log prompt followed by an automatic exact item/quantity upload when the log is opened.
- Permanent Discord `/connect` code authentication using an `Authorization: Bearer` header.

## Local development

### Temporary raid / clan diagnostics

Enable **The Anchor → Testing → Clan / raid debug (temporary)** before entering a raid. No loot drop is needed. The default is off.

Search `.runelite/logs/client.log` for `Raid party debug`. While enabled, diagnostics sample every 10 game ticks (about 6 seconds), log changes, and repeat unchanged state every 50 ticks (about 30 seconds). Reports include raw raid rosters (including The Fractured Archive), party size, clan/guest/unknown counts, visible players' clan flags, and whether the clan roster is ready. If no raid is detected, the raw roster fields still appear. Output stays in local RuneLite logs and does not create evidence or API requests. Turn the setting off after the run.

To remove periodic diagnostics, delete `RaidPartyDiagnostics.java`, its field and tick call in `PartyTracker`, and `raidDiagnosticReport()`. The existing `debugRaidPartyDetection` config key is retained for compatibility.

### Running locally

Run `gradlew.bat clean build` to compile and package the plugin, or `gradlew.bat run` to launch RuneLite in developer mode. The development launcher lives in `src/dev`; there is no automated test suite.

The production API origin is `https://the-anchor.cc`. During development, enable RuneLite's hidden settings and change the plugin API base URL.

The exact request and response shapes expected by the plugin are documented in [docs/api-contract.md](docs/api-contract.md).

Evidence and caches are stored under `.runelite/the-anchor`. Authentication codes are stored only in RuneLite configuration and are never written into evidence metadata or logs.
