# The Anchor

RuneLite Plugin Hub project for The Anchor clan.

## Features

- Reval-inspired clan panel with profile and ROTM/BOTW/SOTW competitions, leaderboards, PvM Diary, local submissions, and settings tabs.
- PvM and Clan leaderboards backed by the website cache, with member search and personal standings.
- PvM Diary categories and tier requirements open inside the sidebar with back navigation.
- Event alerts delivered to game chat with an optional in-game overlay.
- Evidence screenshots always include the player's RSN, even when chat is closed. Enable **Bingo Overlay → Screenshot only** to include active Bingo details only in captured evidence; leave it off to keep the normal in-game Bingo overlay.
- Automatic full-client evidence capture for eligible loot, collection-log unlocks, pets, Combat Achievement tiers, and PBs.
- Durable local outbox and editable loot-submission drafts.
- Raid scoreboard or observed boss-interaction party estimates, including same-clan participant RSNs while excluding guest names.
- Live and bulk personal-best sync, including Adventure Log team-size records.
- Automatic first-connection and 30-minute sync for every Combat Achievement task and all skill levels/XP.
- One-time collection-log prompt, then automatic full item/quantity sync when your collection log is opened and finishes loading, at most once per 24 hours per account after a successful sync. The in-log Anchor refresh button can sync anytime.
- Permanent Discord `/connect` code authentication using an `Authorization: Bearer` header.

## Clan member notes

Right-click a member in the clan side panel and select **Add note**. Existing notes have an **Edit note** option that opens the saved text. Notes are limited to 255 characters; clear the text and submit to delete a note, or press Escape to cancel.

Notes are personal and saved in RuneLite configuration, independently of your friends list and Anchor authentication. They are keyed by player name, so a name change requires recreating the note under the new name. They are not sent to The Anchor or shared with clan members.

## Local development

### Clan / raid debug

Enable **The Anchor → Testing → Clan / raid debug** before entering a raid. No loot drop is needed. The default is off.

Search `.runelite/logs/client.log` for `Raid party debug`. While enabled, diagnostics sample every 10 game ticks (about 6 seconds), log changes, and repeat unchanged state every 50 ticks (about 30 seconds). Reports include raw raid rosters (including The Fractured Archive's eight slot states and names), party size, clan/guest/unknown counts, visible players' clan flags, and whether the clan roster is ready. They also identify the player's WorldView, game state, and whether evidence features are active. The reporter runs independently of feature activation and roster polling, and logs reporting errors before retrying. If no raid is detected, the raw roster fields still appear. Output stays in local RuneLite logs and does not create evidence or API requests. Turn the setting off after the run.

Archive roster decoding follows live cache scripts 10034/10039/10042: nonzero member states at varbits 16387–16394 indicate occupied slots, paired with names at varcs 1573–1580. Empty or literal `null` names remain unknown. Script 10047 shows that varbit 16386 controls the lobby's Party / Step Inside button; it does not prove raid presence. Tracking retains the last roster for up to 10 ticks of empty slots during a transition and stores the completed roster when the final Ocellus form (NPC 16677) dies.

### Running locally

Run `gradlew.bat clean build` to compile and package the plugin, or `gradlew.bat run` to launch RuneLite in developer mode. The development launcher lives in `src/dev`; there is no automated test suite.

The production API origin is `https://the-anchor.cc`. During development, enable RuneLite's hidden settings and change the plugin API base URL.

The exact request and response shapes expected by the plugin are documented in [docs/api-contract.md](docs/api-contract.md).

Evidence and caches are stored under `.runelite/the-anchor`. Authentication codes are stored only in RuneLite configuration and are never written into evidence metadata or logs.

### UI attribution

The sidebar visual components adapt the Reval Clan plugin by Lightroom, under the BSD 2-Clause license. See [LICENSES/reval-clan-LICENSE.txt](LICENSES/reval-clan-LICENSE.txt).
