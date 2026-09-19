/* Portions adapted from the Llama Club pet notification code. */
package com.theanchor.events;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;

final class PetItems
{
	// Only unambiguous encounter-to-pet associations; clan names take precedence.
	private static final Map<String, String> BOSS_PETS = Map.ofEntries(
		Map.entry("abyssal sire", "Abyssal orphan"), Map.entry("alchemical hydra", "Ikkle hydra"),
		Map.entry("araxxor", "Nid"), Map.entry("artio", "Callisto cub"),
		Map.entry("callisto", "Callisto cub"), Map.entry("calvarion", "Vet'ion jr."),
		Map.entry("cerberus", "Hellpuppy"), Map.entry("chaos elemental", "Pet chaos elemental"),
		Map.entry("chaos fanatic", "Pet chaos elemental"), Map.entry("commander zilyana", "Pet zilyana"),
		Map.entry("corporeal beast", "Pet dark core"), Map.entry("dagannoth prime", "Pet dagannoth prime"),
		Map.entry("dagannoth rex", "Pet dagannoth rex"), Map.entry("dagannoth supreme", "Pet dagannoth supreme"),
		Map.entry("duke sucellus", "Baron"), Map.entry("general graardor", "Pet general graardor"),
		Map.entry("giant mole", "Baby mole"), Map.entry("grotesque guardians", "Noon"),
		Map.entry("kalphite queen", "Kalphite princess"), Map.entry("king black dragon", "Prince black dragon"),
		Map.entry("kraken", "Pet kraken"), Map.entry("kreearra", "Pet kree'arra"),
		Map.entry("kril tsutsaroth", "Pet k'ril tsutsaroth"), Map.entry("nex", "Nexling"),
		Map.entry("nightmare", "Little nightmare"), Map.entry("the nightmare", "Little nightmare"),
		Map.entry("phosanis nightmare", "Little nightmare"), Map.entry("phantom muspah", "Muphin"),
		Map.entry("sarachnis", "Sraracha"), Map.entry("scorpia", "Scorpia's offspring"),
		Map.entry("scurrius", "Scurry"), Map.entry("skotizo", "Skotos"),
		Map.entry("spindel", "Venenatis spiderling"), Map.entry("venenatis", "Venenatis spiderling"),
		Map.entry("vetion", "Vet'ion jr."), Map.entry("vorkath", "Vorki"),
		Map.entry("thermonuclear smoke devil", "Pet smoke devil"), Map.entry("zulrah", "Pet snakeling"),
		Map.entry("the leviathan", "Lil'viathan"), Map.entry("the whisperer", "Wisp"),
		Map.entry("vardorvis", "Butch"), Map.entry("the gauntlet", "Youngllef"),
		Map.entry("the corrupted gauntlet", "Youngllef"), Map.entry("wintertodt", "Phoenix"),
		Map.entry("tempoross", "Tiny tempor"), Map.entry("zalcano", "Smolcano"),
		Map.entry("chambers of xeric", "Olmlet"), Map.entry("chambers of xeric challenge mode", "Olmlet"),
		Map.entry("theatre of blood", "Lil' zik"), Map.entry("theatre of blood hard mode", "Lil' zik"),
		Map.entry("tombs of amascut", "Tumeken's guardian"),
		Map.entry("tombs of amascut expert mode", "Tumeken's guardian")
	);

	static String forBoss(String boss)
	{
		if (boss == null) return null;
		return BOSS_PETS.get(boss.toLowerCase(java.util.Locale.ROOT)
			.replace('\u2019', '\'').replace("'", "").replace(':', ' ').replace('-', ' ')
			.trim().replaceAll("\\s+", " "));
	}

	private static final Set<String> PET_NAMES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		"abyssal orphan", "abyssal protector", "baby chinchompa", "baby mole", "baron", "beaver",
		"bloodhound", "bran", "butch", "callisto cub", "chompy chick", "dom", "giant squirrel",
		"gull", "hellpuppy", "herbi", "heron", "huberte", "ikkle hydra", "jal-nib-rek",
		"kalphite princess", "lil' creator", "lil' zik", "lil'viathan", "little nightmare", "moxi",
		"muphin", "nexling", "nid", "noon", "olmlet", "phoenix", "prince black dragon",
		"quetzin", "rift guardian", "rock golem", "rocky", "scorpia's offspring", "scurry", "skotos",
		"smol heredit", "smolcano", "soup", "sraracha", "tangleroot", "tiny tempor",
		"tumeken's guardian", "tzrek-jad", "venenatis spiderling", "vet'ion jr.", "vorki", "wisp",
		"yami", "youngllef", "pet chaos elemental", "pet dagannoth prime", "pet dagannoth rex",
		"pet dagannoth supreme", "pet dark core", "pet general graardor", "pet k'ril tsutsaroth",
		"pet kraken", "pet kree'arra", "pet penance queen", "pet smoke devil", "pet snakeling",
		"pet zilyana"
	)));

	private PetItems() {}

	static boolean isPet(String itemName)
	{
		if (itemName == null || itemName.isBlank()) return false;
		return PET_NAMES.contains(itemName.trim().toLowerCase(java.util.Locale.ROOT));
	}
}
