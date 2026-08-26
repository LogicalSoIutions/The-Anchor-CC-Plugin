package com.theanchor.service;

import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Selects loot worth submitting without assigning or estimating points. */
public final class LootEligibility
{
	private static final Set<String> ALWAYS_CAPTURE = new HashSet<>(Arrays.asList(
		"arcane prayer scroll", "dexterous prayer scroll", "dinh's bulwark", "twisted buckler",
		"dragon hunter crossbow", "dragon claws", "kodai insignia", "twisted kit", "metamorphic dust",
		"justiciar faceguard", "justiciar chestguard", "justiciar legguards", "sanguinesti staff (uncharged)",
		"ghrazi rapier", "sanguine ornament kit", "holy ornament kit", "sanguine dust", "lightbearer",
		"osmumten's fang", "elidinis' ward", "masori mask", "masori chaps", "masori body", "nightmare staff",
		"eldritch orb", "harmonised orb", "volatile orb", "parasitic egg", "dizana's quiver",
		"sunfire fanatic helm", "sunfire fanatic cuirass", "sunfire fanatic chausses", "echo crystal",
		"tonalztics of ralos (uncharged)", "tanzanite mutagen", "magma mutagen", "infernal cape", "araxyte fang",
		// Untradeable rare drops can have a low or zero GE price, but are still
		// meaningful collection-log loot and should be captured on every drop.
		"elder venator fang", "noxious point", "noxious blade", "noxious pommel"
	));

	private LootEligibility() {}

	public static boolean shouldCapture(AnchorModels.Item item, AnchorModels.Rules rules)
	{
		if (item == null || rules == null) return false;
		String name = item.name == null ? "" : item.name.trim().toLowerCase(Locale.ROOT);
		if (ALWAYS_CAPTURE.contains(name)) return true;
		return item.tradeable && item.quantity >= 1 && meetsMinimumValue(item, rules);
	}

	/** Value-only check used when a collection-log unlock must be promoted to clan-point loot. */
	public static boolean meetsMinimumValue(AnchorModels.Item item, AnchorModels.Rules rules)
	{
		return item != null && rules != null && item.unitGeValue >= rules.minimumLootValue;
	}
}
