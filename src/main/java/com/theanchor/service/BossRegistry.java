/*
 * Boss naming, aliases, and encounter grouping adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.service;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical Anchor boss sources and the encounters which may be shared. */
public final class BossRegistry
{
	private static final Set<String> MULTI = Set.of(
		"callisto", "chambers of xeric", "chambers of xeric challenge mode", "chaos elemental",
		"chaos fanatic", "commander zilyana", "corporeal beast", "crazy archaeologist",
		"general graardor", "giant mole", "kalphite queen", "king black dragon", "kreearra",
		"kril tsutsaroth", "nex", "nightmare", "scurrius", "tempoross", "the hueycoatl",
		"the royal titans", "theatre of blood", "theatre of blood hard mode", "tombs of amascut",
		"tombs of amascut expert mode", "vetion", "wintertodt", "yama"
	);

	private static final Set<String> SUPPORTED = Set.of(
		"abyssal sire", "alchemical hydra", "amoxliatl", "araxxor", "artio", "barrows chests",
		"brutus", "bryophyta", "callisto", "calvarion", "cerberus", "chambers of xeric",
		"chambers of xeric challenge mode", "chaos elemental", "chaos fanatic", "commander zilyana",
		"corporeal beast", "crazy archaeologist", "dagannoth prime", "dagannoth rex",
		"dagannoth supreme", "deranged archaeologist", "doom of mokhaiotl", "duke sucellus",
		"general graardor", "giant mole", "grotesque guardians", "hespori", "kalphite queen",
		"king black dragon", "kraken", "kreearra", "kril tsutsaroth", "lunar chests", "mad angel",
		"maggot king", "mimic", "nex", "nightmare", "obor", "phantom muspah", "phosanis nightmare",
		"sarachnis", "scorpia", "shellbane gryphon", "skotizo", "sol heredit", "spindel",
		"tempoross", "the corrupted gauntlet", "the gauntlet", "the hueycoatl", "the leviathan",
		"the royal titans", "the whisperer", "theatre of blood", "theatre of blood hard mode",
		"thermonuclear smoke devil", "tombs of amascut", "tombs of amascut expert mode", "tzkal zuk",
		"tztok jad", "vardorvis", "venenatis", "vetion", "vorkath", "wintertodt", "yama", "zalcano", "zulrah"
	);

	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("barrows", "barrows chests"), Map.entry("lunar chest", "lunar chests"),
		Map.entry("the mimic", "mimic"), Map.entry("the nightmare", "nightmare"),
		Map.entry("dusk", "grotesque guardians"), Map.entry("dawn", "grotesque guardians"),
		Map.entry("crystalline hunllef", "the gauntlet"),
		Map.entry("corrupted hunllef", "the corrupted gauntlet"),
		Map.entry("gauntlet", "the gauntlet"), Map.entry("corrupted gauntlet", "the corrupted gauntlet"),
		Map.entry("hueycoatl", "the hueycoatl"), Map.entry("leviathan", "the leviathan"),
		Map.entry("royal titans", "the royal titans"), Map.entry("whisperer", "the whisperer"),
		Map.entry("branda the fire queen", "the royal titans"),
		Map.entry("eldric the ice king", "the royal titans"),
		Map.entry("chambers of xeric cm", "chambers of xeric challenge mode"),
		Map.entry("theatre of blood entry mode", "theatre of blood"),
		Map.entry("tombs of amascut entry mode", "tombs of amascut"),
		Map.entry("fortis colosseum", "sol heredit")
	);

	private BossRegistry() { }

	public static boolean isSupported(String source) { return SUPPORTED.contains(normalize(source)); }
	public static boolean canBeMulti(String source) { return MULTI.contains(normalize(source)); }
	public static boolean isRaid(String source)
	{
		String value = normalize(source);
		return value.startsWith("chambers of xeric") || value.startsWith("theatre of blood") || value.startsWith("tombs of amascut");
	}

	static String normalize(String source)
	{
		if (source == null) return "";
		String value = source.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace("'", "")
			.replace(':', ' ').replace('-', ' ').trim().replaceAll("\\s+", " ");
		return ALIASES.getOrDefault(value, value);
	}
}
