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
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.clan.ClanSettings;
import net.runelite.api.events.GameTick;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

@Singleton
public class PartyTracker
{
	private static final int ENCOUNTER_IDLE_TICKS = 12;
	private static final int TOA_MEMBER_NAME = 1099;
	private static final int TOB_MEMBER_NAME = 330;

	@Inject private Client client;
	private NPC target;
	private final Map<Player, Boolean> participants = new IdentityHashMap<>();
	private int idleTicks;

	@Subscribe
	public void onGameTick(GameTick event)
	{
		Player local = client.getLocalPlayer();
		Actor interacting = local == null ? null : local.getInteracting();
		if (interacting instanceof NPC)
		{
			NPC current = (NPC) interacting;
			if (target != current) { target = current; participants.clear(); }
			idleTicks = 0;
			participants.put(local, Boolean.TRUE);
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
			return fixedSoloParty();

		if (BossRegistry.isRaid(sourceName))
		{
			List<String> names = raidNames(sourceName);
			int count = raidCount(sourceName, names.size());
			if (count > 0) return buildParty(count, names, "raid_party", "high");
		}

		List<String> observed = observedNames();
		if (!observed.isEmpty()) return buildParty(observed.size(), observed, "boss_interaction", "medium");
		return buildParty(1, localNameList(), "unknown", "low");
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
			members.add(new MemberSnapshot(name, clan == null ? null : clan.findMember(name) != null));
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
			return Math.max(namedCount,
				Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P0), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P1), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P2), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P3), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P4), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P5), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P6), 1)
				+ Math.min(client.getVarbitValue(VarbitID.TOA_CLIENT_P7), 1));
		return namedCount;
	}

	private List<String> chambersNames()
	{
		Widget list = client.getWidget(InterfaceID.RaidsSidepanel.LIST);
		if (list == null || list.getChildren() == null) return List.of();
		List<String> names = new ArrayList<>();
		for (Widget child : list.getChildren())
		{
			String name = clean(child.getName());
			if (!name.isEmpty()) names.add(name);
		}
		return names;
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
