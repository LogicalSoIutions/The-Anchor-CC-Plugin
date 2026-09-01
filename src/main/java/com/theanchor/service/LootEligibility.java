package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Selects loot worth submitting without assigning or estimating points. */
public final class LootEligibility
{
	/**
	 * Custom loot values supplied by the clan. These matchers intentionally bypass
	 * both the GE threshold and the tradeable check. The source list uses regular
	 * expressions, so keep matching case-insensitive and use find() rather than
	 * requiring an exact item-name match.
	 */
	private static final List<Pattern> ALWAYS_SUBMIT = Arrays.asList(
		Pattern.compile("prayer scroll", Pattern.CASE_INSENSITIVE),
		Pattern.compile("dinh's bulwark", Pattern.CASE_INSENSITIVE),
		Pattern.compile("twisted buckler", Pattern.CASE_INSENSITIVE),
		Pattern.compile("dragon hunter crossbow", Pattern.CASE_INSENSITIVE),
		Pattern.compile("dragon claws", Pattern.CASE_INSENSITIVE),
		Pattern.compile("kodai insignia", Pattern.CASE_INSENSITIVE),
		Pattern.compile("twisted kit", Pattern.CASE_INSENSITIVE),
		Pattern.compile("metamorphic dust", Pattern.CASE_INSENSITIVE),
		Pattern.compile("justiciar", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sanguinesti staff", Pattern.CASE_INSENSITIVE),
		Pattern.compile("ghrazi rapier", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sanguine ornament kit", Pattern.CASE_INSENSITIVE),
		Pattern.compile("holy ornament kit", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sanguine dust", Pattern.CASE_INSENSITIVE),
		Pattern.compile("lightbearer", Pattern.CASE_INSENSITIVE),
		Pattern.compile("osmumten's fang", Pattern.CASE_INSENSITIVE),
		Pattern.compile("elidinis' ward", Pattern.CASE_INSENSITIVE),
		Pattern.compile("masori mask", Pattern.CASE_INSENSITIVE),
		Pattern.compile("masori chaps", Pattern.CASE_INSENSITIVE),
		Pattern.compile("masori body", Pattern.CASE_INSENSITIVE),
		Pattern.compile("inquisitor", Pattern.CASE_INSENSITIVE),
		Pattern.compile("nightmare staff", Pattern.CASE_INSENSITIVE),
		// Only the Nightmare unique orbs belong here; matching the bare word
		// "orb" also captures ordinary Air orb and Earth orb drops.
		Pattern.compile("^(?:eldritch|harmonised|volatile) orb$", Pattern.CASE_INSENSITIVE),
		Pattern.compile("parasitic egg", Pattern.CASE_INSENSITIVE),
		Pattern.compile("zulrah mutagen", Pattern.CASE_INSENSITIVE),
		Pattern.compile("infernal cape", Pattern.CASE_INSENSITIVE),
		Pattern.compile("dizana's quiver", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sunfire fanatic helm", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sunfire fanatic cuirass", Pattern.CASE_INSENSITIVE),
		Pattern.compile("sunfire fanatic chausses", Pattern.CASE_INSENSITIVE),
		Pattern.compile("echo crystal", Pattern.CASE_INSENSITIVE),
		Pattern.compile("tonalztics of ralos", Pattern.CASE_INSENSITIVE),
		Pattern.compile("araxyte fang", Pattern.CASE_INSENSITIVE),
		Pattern.compile("mokhaiotl cloth", Pattern.CASE_INSENSITIVE),
		Pattern.compile("ultor vestige", Pattern.CASE_INSENSITIVE),
		Pattern.compile("venator vestige", Pattern.CASE_INSENSITIVE),
		// The supplied list spells these two as "vestage"; support the game name
		// as well as the source spelling.
		Pattern.compile("magus vest(?:i|a)ge", Pattern.CASE_INSENSITIVE),
		Pattern.compile("bellator vest(?:i|a)ge", Pattern.CASE_INSENSITIVE),
		Pattern.compile("twisted ancestral colour kit", Pattern.CASE_INSENSITIVE),
		Pattern.compile("crimson kisten", Pattern.CASE_INSENSITIVE),
		Pattern.compile("etched elder venator fang", Pattern.CASE_INSENSITIVE),
		Pattern.compile("jar of (?:venom|swamp|stone|spirits|souls|smoke|sand|miasma|feathers|eyes|dreams|dirt|decay|darkness|chemicals|light)", Pattern.CASE_INSENSITIVE),
		Pattern.compile("mr mcgroot", Pattern.CASE_INSENSITIVE),
		Pattern.compile("aggy", Pattern.CASE_INSENSITIVE),
		// Untradeable rare drops can have a low or zero GE price, but are still
		// meaningful collection-log loot and should be captured on every drop.
		Pattern.compile("elder venator fang", Pattern.CASE_INSENSITIVE),
		Pattern.compile("noxious (?:point|blade|pommel)", Pattern.CASE_INSENSITIVE)
	);

	private LootEligibility() {}

	public static boolean isAlwaysSubmit(String itemName)
	{
		if (itemName == null || itemName.isBlank()) return false;
		return ALWAYS_SUBMIT.stream().anyMatch(pattern -> pattern.matcher(itemName).find());
	}

	public static boolean shouldCapture(AnchorModels.Item item, AnchorModels.Rules rules)
	{
		if (item == null || rules == null) return false;
		String name = item.name == null ? "" : item.name.trim().toLowerCase(Locale.ROOT);
		if (isAlwaysSubmit(name)) return true;
		return item.tradeable && item.quantity >= 1 && meetsMinimumValue(item, rules);
	}

	/** Value-only check used when a collection-log unlock must be promoted to clan-point loot. */
	public static boolean meetsMinimumValue(AnchorModels.Item item, AnchorModels.Rules rules)
	{
		return item != null && rules != null && item.unitGeValue >= rules.minimumLootValue;
	}
}
