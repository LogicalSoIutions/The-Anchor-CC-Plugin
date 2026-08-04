package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import com.theanchor.service.RulesService;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.client.events.NpcLootReceived;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LootEventListenerTest
{
	@Test public void capturesLowValueBingoItemFromConfiguredBoss() throws Exception
	{
		LootEventListener listener = new LootEventListener();
		ItemManager items = mock(ItemManager.class);
		RulesService rules = mock(RulesService.class);
		EventPipeline pipeline = mock(EventPipeline.class);
		BingoService bingo = mock(BingoService.class);
		inject(listener, "itemManager", items);
		inject(listener, "rules", rules);
		inject(listener, "pipeline", pipeline);
		inject(listener, "bingo", bingo);

		ItemStack stack = mock(ItemStack.class);
		when(stack.getId()).thenReturn(20997);
		when(stack.getQuantity()).thenReturn(1);
		ItemComposition composition = mock(ItemComposition.class);
		when(composition.getName()).thenReturn("Twisted bow");
		when(items.getItemComposition(20997)).thenReturn(composition);
		when(items.getItemPrice(20997)).thenReturn(1);
		when(rules.current()).thenReturn(new AnchorModels.Rules());
		when(bingo.matchingItems(eq(5886), eq("Abyssal Sire"), any())).thenAnswer(invocation ->
			Collections.singletonList(invocation.<List<AnchorModels.Item>>getArgument(2).get(0)));
		when(bingo.eventType()).thenReturn("bingo");
		when(bingo.rulesVersion()).thenReturn("2026-08-04.3");
		when(bingo.screenshotRequired()).thenReturn(true);
		when(bingo.finalizeSubmission()).thenReturn(true);

		NPC npc = mock(NPC.class);
		when(npc.getId()).thenReturn(5886);
		when(npc.getName()).thenReturn("Abyssal Sire");
		NpcLootReceived event = mock(NpcLootReceived.class);
		when(event.getNpc()).thenReturn(npc);
		when(event.getItems()).thenReturn(Collections.singletonList(stack));

		listener.onNpcLootReceived(event);

		verify(pipeline).capture(eq("bingo"), eq("twisted bow"), any(AnchorModels.Source.class),
			any(List.class), any(java.util.Map.class), eq(true), eq("2026-08-04.3"), eq(true), eq(true));
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
