/*
 * Boss sprite mappings and loading behavior adapted from PB Tracker Sync.
 * See LICENSES/pb-tracker-sync-LICENSE.txt.
 */
package com.theanchor.ui;

import java.awt.Image;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import net.runelite.client.game.SpriteManager;

/** Loads the same boss sprites used by RuneLite's Hiscores panel. */
final class BossIcons
{
	private static final int SIZE = 28;
	private static final Map<Integer, ImageIcon> CACHE = new ConcurrentHashMap<>();
	private static final Map<String, Integer> SPRITE_IDS = buildSpriteIds();
	private static final Map<String, String> ALIASES = Map.ofEntries(
		Map.entry("duke_sucellus_(awakened)", "duke_sucellus"),
		Map.entry("leviathan_(awakened)", "leviathan"),
		Map.entry("vardorvis_(awakened)", "vardorvis"),
		Map.entry("whisperer_(awakened)", "whisperer"),
		Map.entry("sol_heredit", "fortis_colosseum"),
		Map.entry("tzkal_zuk", "inferno"),
		Map.entry("fight_caves", "tzhaar_fight_cave"),
		Map.entry("tzhaar_ket_raks_challenges", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_first_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_second_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_third_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_fourth_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_fifth_challenge", "tztok_jad"),
		Map.entry("tzhaar_ket_raks_sixth_challenge", "tztok_jad")
	);

	private BossIcons() { }

	static void get(SpriteManager spriteManager, String boss, Consumer<ImageIcon> onLoaded)
	{
		Integer spriteId = spriteIdFor(boss);
		if (spriteManager == null || spriteId == null) return;
		ImageIcon cached = CACHE.get(spriteId);
		if (cached != null) { deliverOnEdt(onLoaded, cached); return; }
		spriteManager.getSpriteAsync(spriteId, 0, image ->
		{
			if (image == null) return;
			ImageIcon icon = new ImageIcon(image.getScaledInstance(SIZE, SIZE, Image.SCALE_SMOOTH));
			CACHE.put(spriteId, icon);
			deliverOnEdt(onLoaded, icon);
		});
	}

	private static void deliverOnEdt(Consumer<ImageIcon> consumer, ImageIcon icon)
	{
		if (SwingUtilities.isEventDispatchThread()) consumer.accept(icon);
		else SwingUtilities.invokeLater(() -> consumer.accept(icon));
	}

	static Integer spriteIdFor(String boss)
	{
		String slug = slugFor(boss);
		return slug == null ? null : SPRITE_IDS.get(ALIASES.getOrDefault(slug, slug));
	}

	private static String slugFor(String boss)
	{
		if (boss == null || boss.trim().isEmpty()) return null;
		String base = boss.trim().toLowerCase(java.util.Locale.ROOT);
		int dash = base.indexOf(" - ");
		if (dash >= 0) base = base.substring(0, dash);
		if (base.startsWith("the ")) base = base.substring(4);
		return base.replace("'", "").replace(" ", "_").replace("-", "_");
	}

	private static Map<String, Integer> buildSpriteIds()
	{
		Map<String, Integer> ids = new HashMap<>();
		ids.put("abyssal_sire", 4276); ids.put("alchemical_hydra", 4289); ids.put("amoxliatl", 5639);
		ids.put("araxxor", 5638); ids.put("artio", 5622); ids.put("bryophyta", 4262);
		ids.put("callisto", 5622); ids.put("calvarion", 5623); ids.put("cerberus", 4280);
		ids.put("chambers_of_xeric", 4288); ids.put("chaos_elemental", 5621); ids.put("chaos_fanatic", 5625);
		ids.put("commander_zilyana", 4284); ids.put("corporeal_beast", 4287); ids.put("crazy_archaeologist", 5626);
		ids.put("dagannoth_prime", 4294); ids.put("dagannoth_rex", 4293); ids.put("dagannoth_supreme", 4292);
		ids.put("deranged_archaeologist", 5627); ids.put("doom_of_mokhaiotl", 6347); ids.put("duke_sucellus", 5632);
		ids.put("general_graardor", 4282); ids.put("giant_mole", 4263); ids.put("grotesque_guardians", 4264);
		ids.put("hespori", 4271); ids.put("kalphite_queen", 4270); ids.put("king_black_dragon", 4274);
		ids.put("kraken", 4275); ids.put("kreearra", 4285); ids.put("kril_tsutsaroth", 4283);
		ids.put("lunar_chest", 5637); ids.put("mimic", 4260); ids.put("nex", 4291);
		ids.put("nightmare", 4286); ids.put("phosanis_nightmare", 4286); ids.put("obor", 4261);
		ids.put("phantom_muspah", 4299); ids.put("sarachnis", 4269); ids.put("scorpia", 5628);
		ids.put("scurrius", 5635); ids.put("skotizo", 4272); ids.put("fortis_colosseum", 5636);
		ids.put("spindel", 5624); ids.put("tempoross", 4265); ids.put("gauntlet", 4278);
		ids.put("corrupted_gauntlet", 4295); ids.put("hueycoatl", 5640); ids.put("leviathan", 5633);
		ids.put("royal_titans", 6345); ids.put("whisperer", 5631); ids.put("theatre_of_blood", 4290);
		ids.put("thermonuclear_smoke_devil", 4277); ids.put("tombs_of_amascut", 4297); ids.put("inferno", 5630);
		ids.put("tzhaar_fight_cave", 5629); ids.put("tztok_jad", 5629); ids.put("vardorvis", 5634);
		ids.put("venenatis", 5624); ids.put("vetion", 5623); ids.put("vorkath", 4281);
		ids.put("wintertodt", 4266); ids.put("yama", 6346); ids.put("zalcano", 4273); ids.put("zulrah", 4279);
		return ids;
	}
}
