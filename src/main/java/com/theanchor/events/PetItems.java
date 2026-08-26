/* Portions adapted from the Llama Club pet notification code. */
package com.theanchor.events;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

final class PetItems
{
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
