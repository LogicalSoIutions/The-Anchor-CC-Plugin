/*
 * Party and shared-encounter tracking adapted from the Terpinheimer party tracker.
 * See LICENSES/terpinheimer-LICENSE.txt.
 */
package com.theanchor.service;

import com.theanchor.AnchorConfig;
import com.theanchor.model.AnchorModels;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanMember;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public class PartyTracker
{
	private static final Logger log = LoggerFactory.getLogger(PartyTracker.class);
	private static final Set<String> INDIVIDUAL_RAID_REWARDS = Set.of(
		"olmlet", "lil' zik", "lil zik", "metamorphic dust", "twisted kit",
		"twisted ancestral colour kit", "holy ornament kit", "sanguine ornament kit", "sanguine dust");
	private static final int ENCOUNTER_IDLE_TICKS = 12;
	private static final long RECENT_RAID_SOURCE_MILLIS = 15_000L;
	private static final long COMPLETED_RAID_MILLIS = 10 * 60_000L;
	private static final int TOA_MEMBER_NAME = 1099;
	private static final int TOB_MEMBER_NAME = 330;

	@Inject private Client client;
	@Inject private AnchorConfig config;
	private NPC target;
	private final Map<Player, Boolean> participants = new IdentityHashMap<>();
	private int idleTicks;
	private volatile String recentRaidSource;
	private volatile long recentRaidSourceAt;
	private String activeRaidSource;
	private List<String> activeRaidNames = List.of();
	private int activeRaidPartySize;
	private String raidStatusSource;
	private final Map<String, Boolean> raidPlayerClanStatus = new LinkedHashMap<>();
	private AnchorModels.Party completedRaidParty;
	private String completedRaidSource;
	private long completedRaidAt;
	private boolean awaitingCompletedRosterClear;
	private String lastDebugRosterSignature;

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
		return snapshot(sourceName, false);
	}

	/**
	 * Some CoX/ToB rewards are awarded to the player who receives them rather
	 * than split across the raid. Treat only those named rewards as solo for
	 * submission metadata; ordinary raid loot still uses the full roster.
	 */
	public AnchorModels.Party snapshot(String sourceName, boolean individualAward)
	{
		if (individualAward) return fixedSoloParty();
		if (!BossRegistry.canBeMulti(sourceName))
		{
			return fixedSoloParty();
		}

		if (BossRegistry.isRaid(sourceName))
		{
			AnchorModels.Party stored = storedRaidParty(sourceName);
			if (stored != null)
			{
				debugRaidSnapshot("snapshot-stored", sourceName, stored);
				return stored;
			}
			List<String> names = raidNames(sourceName);
			int count = raidCount(sourceName, names.size());
			if (count > 0 || !names.isEmpty())
			{
				AnchorModels.Party party = buildParty(Math.max(1, count), names, "raid_party", confidenceForRoster(count, names));
				debugRaidSnapshot("snapshot-live", sourceName, party);
				return party;
			}
			// A raid source without a trustworthy roster must not fall through to
			// nearby-player detection or be treated as a confirmed solo encounter.
			AnchorModels.Party party = buildParty(1, List.of(), "raid_party", "low");
			debugRaidSnapshot("snapshot-empty", sourceName, party);
			return party;
		}

		List<String> observed = observedNames();
		if (!observed.isEmpty()) return buildParty(observed.size(), observed, "boss_interaction", "medium");
		return buildParty(1, localNameList(), "unknown", "low");
	}

	public static boolean isIndividualRaidReward(String sourceName, String itemName)
	{
		if (sourceName == null || itemName == null) return false;
		String source = BossRegistry.normalize(sourceName);
		boolean chambers = source.startsWith("chambers of xeric") || source.startsWith("great olm");
		boolean theatre = source.startsWith("theatre of blood") || source.startsWith("verzik vitur");
		return (chambers || theatre) && INDIVIDUAL_RAID_REWARDS.contains(
			itemName.trim().toLowerCase(Locale.ROOT));
	}

	@Subscribe
	public void onActorDeath(ActorDeath event)
	{
		Actor actor = event.getActor();
		if (!(actor instanceof NPC)) return;
		String source = finalBossRaid(((NPC) actor).getName());
		if (source == null) return;
		observeRaidWorldView(source);
		List<String> finalNames = raidNames(source);
		if (!finalNames.isEmpty()) updateActiveRaid(source, finalNames);
		List<String> confirmedNames = sameRaid(source, activeRaidSource) ? activeRaidNames : finalNames;
		completedRaidSource = source;
		int detectedSize = Math.max(activeRaidPartySize, raidCount(source, confirmedNames.size()));
		completedRaidParty = buildParty(detectedSize, confirmedNames,
			"raid_party_final_boss", confidenceForRoster(detectedSize, confirmedNames));
		debugRaidSnapshot("final-boss", source, completedRaidParty);
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
		observeRaidWorldView(source);
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
			raidStatusSource = null;
			raidPlayerClanStatus.clear();
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
			raidStatusSource = null;
			raidPlayerClanStatus.clear();
		}

		if (activeRaidSource != null)
		{
			observeRaidWorldView(activeRaidSource);
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
			if (!sameRaid(source, raidStatusSource)) raidPlayerClanStatus.clear();
			raidStatusSource = source;
		}
		// Named roster entries are the authoritative size. The CoX party-size
		// varbit can briefly contain stale data while the instance is changing.
		// Only use it later as a fallback when no names are available.
		activeRaidPartySize = Math.max(activeRaidPartySize, cleaned.size());
		if (source.equals(activeRaidSource) && cleaned.equals(activeRaidNames)) return;
		activeRaidSource = source;
		activeRaidNames = cleaned;
		debugRaidRoster("roster-changed", source, cleaned);
		recentRaidSource = source;
		recentRaidSourceAt = System.currentTimeMillis();
	}

	private AnchorModels.Party storedRaidParty(String sourceName)
	{
		if (sameRaid(sourceName, activeRaidSource))
		{
			int size = Math.max(activeRaidPartySize, activeRaidNames.size());
			return buildParty(size, activeRaidNames, "raid_party_entry",
				confidenceForRoster(size, activeRaidNames));
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
		Set<String> clanNames = clanMemberKeys(clan);
		boolean clanRosterReady = clanRosterReady(clanNames);
		List<MemberSnapshot> members = new ArrayList<>();
		for (String name : dedupe(names))
		{
			// A missing/empty roster is an unknown state, not proof that a player
			// is a guest. Once the full local clan roster is available, absence is
			// a confirmed non-clan result.
			Boolean observedStatus = method.startsWith("raid_")
				? raidPlayerClanStatus.get(playerNameKey(name)) : null;
			// Prefer the complete clan-settings roster. The per-player flag is the
			// fallback for raid members that are visible in the instance but whose
			// names are not available in the settings lookup yet.
			Boolean clanMember = clanRosterReady
				? Boolean.valueOf(clanNames.contains(playerNameKey(name)))
				: observedStatus;
			members.add(new MemberSnapshot(name, clanMember));
		}
		return snapshotFromData(detectedSize, members, method,
			clanRosterReady || !raidPlayerClanStatus.isEmpty() ? confidence : "low");
	}

	private Set<String> clanMemberKeys(ClanSettings clan)
	{
		Set<String> names = new HashSet<>();
		if (clan == null || clan.getMembers() == null) return names;
		for (ClanMember member : clan.getMembers())
			if (member != null && member.getName() != null) names.add(playerNameKey(member.getName()));
		return names;
	}

	private boolean clanRosterReady(Set<String> clanNames)
	{
		Player local = client.getLocalPlayer();
		return !clanNames.isEmpty() && local != null && local.getName() != null
			&& clanNames.contains(playerNameKey(local.getName()));
	}

	private static String playerNameKey(String name)
	{
		return clean(name).toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "").replace("-", "");
	}

	private static String confidenceForRoster(int detectedSize, List<String> names)
	{
		return names != null && names.size() >= Math.max(1, detectedSize) ? "high" : "low";
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
			return namedCount > 0 ? namedCount : client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE);
		if (source.startsWith("tombs of amascut"))
			return namedCount > 0 ? namedCount : toaPartyCount();
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
		beginRaidStatus("Chambers of Xeric");
		Widget list = client.getWidget(InterfaceID.RaidsSidepanel.LIST);
		List<String> panelNames = new ArrayList<>();
		collectChambersNames(list, panelNames);
		// The local player's exact WorldView is the active raid instance. The
		// side-panel names are retained as a fallback when that view is unavailable.
		// Client.getPlayers() is the top-level view and may contain unrelated
		// players while the local player is inside an instanced raid.
		List<String> worldViewNames = new ArrayList<>();
		Player local = client.getLocalPlayer();
		if (local != null && local.getWorldView() != null && client.isInInstancedRegion())
			for (Player player : local.getWorldView().players())
				if (player != null && player.getName() != null)
				{
					worldViewNames.add(player.getName());
					raidPlayerClanStatus.put(playerNameKey(player.getName()), player.isClanMember());
				}
		return dedupe(worldViewNames.isEmpty() ? panelNames : worldViewNames);
	}

	private void beginRaidStatus(String source)
	{
		if (!sameRaid(source, raidStatusSource))
		{
			raidPlayerClanStatus.clear();
			raidStatusSource = source;
		}
	}

	private void observeRaidWorldView(String source)
	{
		beginRaidStatus(source);
		Player local = client.getLocalPlayer();
		if (local == null || local.getWorldView() == null || !client.isInInstancedRegion()) return;
		for (Player player : local.getWorldView().players())
			if (player != null && player.getName() != null)
				raidPlayerClanStatus.put(playerNameKey(player.getName()), player.isClanMember());
	}

	private void debugRaidRoster(String reason, String source, List<String> names)
	{
		if (config == null || !config.debugRaidPartyDetection()) return;
		String signature = reason + "|" + raidKey(source) + "|" + names + "|" + raidPlayerClanStatus;
		if (signature.equals(lastDebugRosterSignature)) return;
		lastDebugRosterSignature = signature;
		log.info("Raid party debug [{}]: {}", reason, raidDebugState(source, names));
	}

	private void debugRaidSnapshot(String reason, String source, AnchorModels.Party party)
	{
		if (config == null || !config.debugRaidPartyDetection()) return;
		log.info("Raid party debug [{}]: {} party={}", reason, raidDebugState(source, null), partyDebugState(party));
	}

	private String raidDebugState(String source, List<String> names)
	{
		Player local = client.getLocalPlayer();
		List<String> worldViewPlayers = new ArrayList<>();
		if (local != null && local.getWorldView() != null)
			for (Player player : local.getWorldView().players())
				if (player != null && player.getName() != null)
					worldViewPlayers.add(player.getName() + "=" + player.isClanMember());
		ClanSettings clan = client.getClanSettings();
		List<String> clanMembers = new ArrayList<>();
		if (clan != null && clan.getMembers() != null)
			for (ClanMember member : clan.getMembers())
				if (member != null && member.getName() != null) clanMembers.add(member.getName());
		int varbitPartySize = BossRegistry.normalize(source).startsWith("chambers of xeric")
			? client.getVarbitValue(VarbitID.RAIDS_CLIENT_PARTYSIZE) : -1;
		return "source=" + source
			+ " inInstance=" + client.isInInstancedRegion()
			+ " local=" + (local == null ? null : local.getName())
			+ " names=" + (names == null ? raidNames(source) : names)
			+ " activeNames=" + activeRaidNames
			+ " activeSize=" + activeRaidPartySize
			+ " varbitPartySize=" + varbitPartySize
			+ " worldViewPlayers=" + worldViewPlayers
			+ " cachedClanFlags=" + raidPlayerClanStatus
			+ " clanRosterReady=" + clanRosterReady(clanMemberKeys(clan))
			+ " clanMembers=" + clanMembers;
	}

	private static String partyDebugState(AnchorModels.Party party)
	{
		if (party == null) return "null";
		List<String> members = new ArrayList<>();
		for (AnchorModels.PartyMember member : party.members)
			if (member != null) members.add(member.name + "=" + member.clanMember);
		return "method=" + party.method
			+ ", confidence=" + party.confidence
			+ ", detectedSize=" + party.detectedPartySize
			+ ", clanCount=" + party.detectedClanMemberCount
			+ ", nonClanCount=" + party.detectedNonClanMemberCount
			+ ", members=" + members;
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
