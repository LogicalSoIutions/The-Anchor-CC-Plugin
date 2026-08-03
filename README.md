# The Anchor

RuneLite Plugin Hub project for The Anchor clan.

## Features

- Home panel with member ranks, points, next-rank progress, BOTW, and SOTW.
- Event alerts delivered to game chat with an optional in-game overlay.
- Automatic full-client evidence capture for eligible loot, collection-log unlocks, pets, Combat Achievement tiers, and PBs.
- Durable local outbox and editable loot-submission drafts.
- Raid scoreboard or observed boss-interaction party-size estimates without storing other player names.
- Live and bulk personal-best sync, including Adventure Log team-size records.
- Automatic first-connection sync for every Combat Achievement task and all skill levels/XP.
- One-time collection-log prompt followed by an automatic exact item/quantity upload when the log is opened.
- Permanent Discord `/connect` code authentication using an `Authorization: Bearer` header.

## Local development

Run `gradlew.bat test` to compile and test, or `gradlew.bat run` to launch RuneLite in developer mode.

The production API origin is `https://the-anchor.cc`. During development, enable RuneLite's hidden settings and change the plugin API base URL.

The exact request and response shapes expected by the plugin are documented in [docs/api-contract.md](docs/api-contract.md).

Evidence and caches are stored under `.runelite/the-anchor`. Authentication codes are stored only in RuneLite configuration and are never written into evidence metadata or logs.
