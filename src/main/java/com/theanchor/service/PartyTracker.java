/*
 * Party and shared-encounter tracking adapted from the Terpinheimer party tracker.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.NpcSpawned;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

@Singleton
public class PartyTracker
{
	private static final int ENCOUNTER_IDLE_TICKS = 12;
	private static final long RECENT_RAID_SOURCE_MILLIS = 15_000L;
	private static final long COMPLETED_RAID_MILLIS = 10 * 60_000L;
	private static final int TOA_MEMBER_NAME = 1099;
	private static final int TOB_MEMBER_NAME = 330;

	@Inject private Client client;
	private NPC target;
	private final Map<Player, Boolean> participants = new IdentityHashMap<>();
	private int idleTicks;
	private volatile String recentRaidSource;
	private volatile long recentRaidSourceAt;
	private String activeRaidSource;
	private List<String> activeRaidNames = List.of();
	private int activeRaidPartySize;
	private AnchorModels.Party completedRaidParty;
	private String completedRaidSource;
	private long completedRaidAt;
	private boolean awaitingCompletedRosterClear;

	@Subscribe
	public void onGameTick(GameTick event)
	{
		pollRaidRoster();
		Player local = client.getLocalPlayer();
		NPC current = local != null && local.getInteracting() instanceof NPC
			? (NPC) local.getInteracting() : interactingNpc(client.getPlayers());
		if (current != null)
		{
			if (target != current) { target = current; participants.clear(); }
			idleTicks = 0;
			if (local != null) participants.put(local, Boolean.TRUE);
			for (Player player : client.getPlayers())
				if (player != null && player.getInteracting() == target) participants.put(player, Boolean.TRUE);
		}
		else if (target != null && ++idleTicks > ENCOUNTER_IDLE_TICKS)
		{
			target = null; participants.clear(); idleTicks = 0;
		}
	}

	/** Capture party state for the actual loot source; "Multi" means permitted, not assumed. */
	public AnchorModels.Party snapshot(String sourceName)
	{
		if (!BossRegistry.canBeMulti(sourceName))
		{
			return fixedSoloParty();
		}

		if (BossRegistry.isRaid(sourceName))
		{
			AnchorModels.Party stored = storedRaidParty(sourceName);
			if (stored != null) return stored;
			List<String> names = raidNames(sourceName);
			int count = raidCount(sourceName, names.size());
			if (count > 0) return buildParty(count, names, "raid_party", "high");
		}

		List<String> observed = observedNames();
		if (!observed.isEmpty()) return buildParty(observed.size(), observed, "boss_interaction", "medium");
		return buildParty(1, localNameList(), "unknown", "low");
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC)) return;
		String source = finalBossRaid(((NPC) actor).getName());
		if (source == null) return;
		List<String> finalNames = raidNames(source);
		if (!finalNames.isEmpty()) updateActiveRaid(source, finalNames);
		List<String> confirmedNames = sameRaid(source, activeRaidSource) ? activeRaidNames : finalNames;
		if (confirmedNames.isEmpty()) confirmedNames = localNameList();
		completedRaidSource = source;
		completedRaidParty = buildParty(Math.max(activeRaidPartySize,
			raidCount(source, confirmedNames.size())), confirmedNames,
			"raid_party_final_boss", "high");
		completedRaidAt = System.currentTimeMillis();
		activeRaidSource = null;
		activeRaidNames = List.of();
		activeRaidPartySize = 0;
		awaitingCompletedRosterClear = true;
	}

	@Subscribe
	public void onNpcSpawned(NpcSpawned event)
	{
		NPC npc = event.getNpc();
		if (npc == null) return;
		String source = firstBossRaid(npc.getName());
		if (source == null || (activeRaidSource != null && !sameRaid(source, activeRaidSource))) return;
		List<String> names = raidNames(source);
		if (names.isEmpty()) return;
		awaitingCompletedRosterClear = false;
		if (sameRaid(source, completedRaidSource)) clearCompletedRaid();
		updateActiveRaid(source, names);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			activeRaidSource = null;
			activeRaidNames = List.of();
			activeRaidPartySize = 0;
			completedRaidParty = null;
			completedRaidSource = null;
			completedRaidAt = 0;
			awaitingCompletedRosterClear = false;
		}
	}

	private static NPC interactingNpc(List<Player> players)
	{
		if (players == null) return null;
		for (Player player : players)
		{
			if (player != null && player.getInteracting() instanceof NPC)
				return (NPC) player.getInteracting();
		}
		return null;
	}

	/** Remember named raid loot before event filtering/deduplication so collection-log events share its category. */
	public void observeSource(String sourceName)
	{
		if (!BossRegistry.isRaid(sourceName)) return;
		recentRaidSource = sourceName;
		recentRaidSourceAt = System.currentTimeMillis();
		if (sameRaid(sourceName, activeRaidSource)) activeRaidSource = sourceName;
		if (sameRaid(sourceName, completedRaidSource)) completedRaidSource = sourceName;
	}

	/** Replace the synthetic Collection Log category when RuneLite just identified raid loot. */
	public AnchorModels.Source resolveCollectionLogSource(AnchorModels.Source fallback)
	{
		String source = raidContextSource();
		long age = System.currentTimeMillis() - recentRaidSourceAt;
		boolean sessionContext = source != null && (sameRaid(source, activeRaidSource)
			|| (sameRaid(source, completedRaidSource) && completedRaidAt > 0
				&& System.currentTimeMillis() - completedRaidAt <= COMPLETED_RAID_MILLIS));
		if (source != null && (sessionContext || (age >= 0 && age <= RECENT_RAID_SOURCE_MILLIS)))
		{
			AnchorModels.Source resolved = new AnchorModels.Source();
			resolved.type = fallback == null ? "collection_log" : fallback.type;
			resolved.id = fallback == null ? null : fallback.id;
			resolved.name = source;
			return resolved;
		}
		return fallback;
	}

	private void pollRaidRoster()
	{
		if (awaitingCompletedRosterClear && completedRosterCleared())
		{
			awaitingCompletedRosterClear = false;
		}

		if (activeRaidSource != null)
		{
			List<String> names = raidNames(activeRaidSource);
			if (!names.isEmpty()) updateActiveRaid(activeRaidSource, names);
			return;
		}

		if (client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) > 0)
		{
			startPolledRaid("Chambers of Xeric", chambersNames());
			return;
		}
		if (toaPartyCount() > 0)
			startPolledRaid("Tombs of Amascut", varcNames(TOA_MEMBER_NAME, 8));
	}

	private void startPolledRaid(String source, List<String> names)
	{
		if (names.isEmpty() || (awaitingCompletedRosterClear && sameRaid(source, completedRaidSource))) return;
		if (sameRaid(source, completedRaidSource)) clearCompletedRaid();
		updateActiveRaid(source, names);
	}

	private boolean completedRosterCleared()
	{
		if (completedRaidSource == null) return true;
		String source = BossRegistry.normalize(completedRaidSource);
		if (source.startsWith("chambers of xeric"))
			return client.getVarbitValue(VarbitID.RAIDS_CLIENT_INDUNGEON) == 0;
		if (source.startsWith("tombs of amascut"))
			return toaPartyCount() == 0 || varcNames(TOA_MEMBER_NAME, 8).isEmpty();
		if (source.startsWith("theatre of blood"))
			return varcNames(TOB_MEMBER_NAME, 5).isEmpty();
		return true;
	}

	private void clearCompletedRaid()
	{
		completedRaidParty = null;
		completedRaidSource = null;
		completedRaidAt = 0;
	}

	private void updateActiveRaid(String source, List<String> names)
	{
		List<String> cleaned = dedupe(names);
		if (sameRaid(source, activeRaidSource))
		{
			List<String> combined = new ArrayList<>(activeRaidNames);
			combined.addAll(cleaned);
			cleaned = dedupe(combined);
		}
		else
		{
			activeRaidPartySize = 0;
		}
		activeRaidPartySize = Math.max(activeRaidPartySize, raidCount(source, cleaned.size()));
		if (source.equals(activeRaidSource) && cleaned.equals(activeRaidNames)) return;
		activeRaidSource = source;
		activeRaidNames = cleaned;
		recentRaidSource = source;
		recentRaidSourceAt = System.currentTimeMillis();
	}

	private AnchorModels.Party storedRaidParty(String sourceName)
	{
		if (sameRaid(sourceName, activeRaidSource))
		{
			return buildParty(Math.max(activeRaidPartySize, activeRaidNames.size()), activeRaidNames,
				"raid_party_entry", "high");
		}
		if (completedRaidParty != null && completedRaidSource != null
			&& sameRaid(sourceName, completedRaidSource)
			&& System.currentTimeMillis() - completedRaidAt <= COMPLETED_RAID_MILLIS)
		{
			return completedRaidParty;
		}
		return null;
	}

	private String raidContextSource()
	{
		if (activeRaidSource != null) return activeRaidSource;
		if (completedRaidSource != null && completedRaidAt > 0
			&& System.currentTimeMillis() - completedRaidAt <= COMPLETED_RAID_MILLIS) return completedRaidSource;
		return recentRaidSource;
	}

	static String finalBossRaid(String name)
	{
		String value = BossRegistry.normalize(clean(name));
		if (value.startsWith("great olm")) return "Chambers of Xeric";
		if (value.startsWith("verzik vitur")) return "Theatre of Blood";
		if (value.startsWith("tumeken's warden") || value.startsWith("tumekens warden")) return "Tombs of Amascut";
		return null;
	}

	static String firstBossRaid(String name)
	{
		String value = BossRegistry.normalize(clean(name));
		if (value.startsWith("the maiden of sugadinti")) return "Theatre of Blood";
		if (value.equals("akkha") || value.startsWith("ba ba") || value.startsWith("baba")
			|| value.equals("kephri") || value.equals("zebak")) return "Tombs of Amascut";
		return null;
	}

	private static boolean sameRaid(String left, String right)
	{
		return left != null && right != null && raidKey(left).equals(raidKey(right));
	}

	private static String raidKey(String source)
	{
		String value = BossRegistry.normalize(source);
		if (value.startsWith("chambers of xeric")) return "chambers of xeric";
		if (value.startsWith("theatre of blood")) return "theatre of blood";
		if (value.startsWith("tombs of amascut")) return "tombs of amascut";
		return value;
	}

	/** Kept for PB captures and callers without a loot source. */
	public AnchorModels.Party snapshot() { return snapshot(null); }

	private AnchorModels.Party fixedSoloParty()
	{
		Player local = client.getLocalPlayer();
		List<MemberSnapshot> members = local == null || local.getName() == null
			? List.of() : List.of(new MemberSnapshot(local.getName(), true));
		return snapshotFromData(1, members, "fixed_solo", "high");
	}

	private AnchorModels.Party buildParty(int detectedSize, List<String> names, String method, String confidence)
	{
		ClanSettings clan = client.getClanSettings();
		List<MemberSnapshot> members = new ArrayList<>();
		for (String name : dedupe(names))
		{
			Boolean clanMember = clan == null ? null : clan.findMember(name) != null;
			members.add(new MemberSnapshot(name, clanMember));
		}
		return snapshotFromData(detectedSize, members, method, confidence);
	}

	static AnchorModels.Party snapshotFromData(int detectedSize, List<MemberSnapshot> members, String method, String confidence)
	{
		AnchorModels.Party party = new AnchorModels.Party();
		party.detectedPartySize = party.submittedPartySize = Math.max(1, detectedSize);
		party.method = method; party.confidence = confidence;
		if (members != null)
		{
			for (MemberSnapshot member : members)
			{
				if (member == null || member.name == null || member.name.isBlank()) continue;
				if (Boolean.TRUE.equals(member.clanMember))
				{
					party.detectedClanMemberCount++;
					AnchorModels.PartyMember value = new AnchorModels.PartyMember();
					value.name = member.name; value.clanMember = true; party.members.add(value);
					if (party.detectedPartySize > 1) party.clanMemberNames.add(member.name);
				}
				else if (Boolean.FALSE.equals(member.clanMember)) party.detectedNonClanMemberCount++;
			}
		}
		party.submittedClanMemberCount = party.detectedClanMemberCount;
		party.submittedNonClanMemberCount = party.detectedNonClanMemberCount;
		return party;
	}

	private List<String> raidNames(String sourceName)
	{
		String source = BossRegistry.normalize(sourceName);
		if (source.startsWith("chambers of xeric")) return chambersNames();
		if (source.startsWith("tombs of amascut")) return varcNames(TOA_MEMBER_NAME, 8);
		if (source.startsWith("theatre of blood")) return varcNames(TOB_MEMBER_NAME, 5);
		return List.of();
	}

	private int raidCount(String sourceName, int namedCount)
	{
		String source = BossRegistry.normalize(sourceName);
		if (source.startsWith("chambers of xeric"))
			return Math.max(namedCount, client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE));
		if (source.startsWith("tombs of amascut"))
			return Math.max(namedCount, toaPartyCount());
		return namedCount;
	}

	private int toaPartyCount()
	{
		return Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P0), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P1), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P2), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P3), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P4), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P5), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P6), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P7), 1);
	}

	private List<String> chambersNames()
	{
		Widget list = client.getWidget(InterfaceID.RaidsSidepanel.LIST);
		List<String> names = new ArrayList<>();
		collectChambersNames(list, names);
		for (Player player : client.getPlayers())
		{
			if (player != null && player.getName() != null) names.add(player.getName());
		}
		return dedupe(names);
	}

	static void collectChambersNames(Widget widget, List<String> names)
	{
		if (widget == null || names == null) return;
		String name = clean(widget.getName());
		String text = clean(widget.getText());
		if (isPlayerName(text)) names.add(text);
		else if (isPlayerName(name)) names.add(name);
		Widget[] children = widget.getChildren();
		if (children != null) for (Widget child : children) collectChambersNames(child, names);
	}

	private static boolean isPlayerName(String value)
	{
		if (value == null || value.isEmpty() || value.length() > 12) return false;
		boolean letter = false;
		for (int i = 0; i < value.length(); i++)
		{
			char c = value.charAt(i);
			if (Character.isLetter(c)) letter = true;
			else if (!Character.isDigit(c) && c != ' ' && c != '_') return false;
		}
		return letter;
	}

	private List<String> varcNames(int first, int count)
	{
		List<String> names = new ArrayList<>();
		for (int i = 0; i < count; i++)
		{
			String name = clean(client.getVarcStrValue(first + i));
			if (!name.isEmpty()) names.add(name);
		}
		return names;
	}

	private List<String> observedNames()
	{
		List<String> names = new ArrayList<>();
		for (Player player : participants.keySet()) if (player != null && player.getName() != null) names.add(player.getName());
		return dedupe(names);
	}

	private List<String> localNameList()
	{
		Player local = client.getLocalPlayer();
		return local == null || local.getName() == null ? List.of() : List.of(local.getName());
	}

	private static List<String> dedupe(List<String> names)
	{
		Map<String, String> unique = new LinkedHashMap<>();
		if (names != null) for (String name : names)
		{
			String clean = clean(name);
			if (!clean.isEmpty()) unique.putIfAbsent(clean.toLowerCase(Locale.ROOT), clean);
		}
		return new ArrayList<>(unique.values());
	}

	private static String clean(String value)
	{
		return value == null ? "" : Text.removeTags(value).replace('\u00a0', ' ').trim();
	}

	static final class MemberSnapshot
	{
		final String name;
		final Boolean clanMember;
		MemberSnapshot(String name, Boolean clanMember) { this.name = name; this.clanMember = clanMember; }
	}
}
