package com.theanchor.events;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.model.AnchorModels;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.game.ItemManager;
import org.junit.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class WeaponCreationEventListenerTest
{
	@Test public void detectsCompletedRecipes() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(halberdPieces());
		f.inventory(ItemID.NOXIOUS_HALBERD);
		f.verifyCapture(ItemID.NOXIOUS_HALBERD, "noxious halberd");

		f = new Fixture();
		f.inventory(ItemID.SOULREAPER_AXE_HEAD, ItemID.SOULREAPER_AXE_EYE,
			ItemID.SOULREAPER_AXE_STAFF, ItemID.SOULREAPER_AXE_LURE);
		f.inventory(ItemID.SOULREAPER);
		f.verifyCapture(ItemID.SOULREAPER, "soulreaper axe");

		f = new Fixture();
		f.inventory(ItemID.HYDRA_HEART, ItemID.HYDRA_EYE, ItemID.HYDRA_FANG);
		f.inventory(ItemID.BRIMSTONE_RING);
		f.inventory(ItemID.BRIMSTONE_RING);
		f.verifyCapture(ItemID.BRIMSTONE_RING, "brimstone ring");
		verifyNoMoreInteractions(f.pipeline);

		f = new Fixture();
		f.inventory(ItemID.TWINFLAME_PIECE_1, ItemID.TWINFLAME_PIECE_2, ItemID.BATTLESTAFF);
		f.inventory(ItemID.TWINFLAME_STAFF);
		f.inventory(ItemID.TWINFLAME_STAFF);
		f.verifyCapture(ItemID.TWINFLAME_STAFF, "twinflame staff");
		verifyNoMoreInteractions(f.pipeline);
	}

	@Test public void newRecipesRequireConsumptionOfEveryComponent() throws Exception
	{
		int[][] recipes = {
			{ItemID.BRIMSTONE_RING, ItemID.HYDRA_HEART, ItemID.HYDRA_EYE, ItemID.HYDRA_FANG},
			{ItemID.TWINFLAME_STAFF, ItemID.TWINFLAME_PIECE_1, ItemID.TWINFLAME_PIECE_2,
				ItemID.BATTLESTAFF}
		};
		for (int[] recipe : recipes)
		{
			Fixture acquired = new Fixture();
			acquired.inventory();
			acquired.inventory(recipe[0]);
			verifyNoInteractions(acquired.items, acquired.pipeline);

			for (int component = 1; component < recipe.length; component++)
			{
				Fixture f = new Fixture();
				f.inventory(recipe[1], recipe[2], recipe[3]);
				f.inventory(recipe[0], recipe[component]);
				verifyNoInteractions(f.items, f.pipeline);
			}
		}
	}

	@Test public void ordinaryUpdatesDoNotLookUpItemsOrCapture() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(995, 385);
		f.inventory(995);
		f.inventory(halberdPieces());
		f.inventory(halberdPieces());
		verifyNoInteractions(f.items, f.pipeline);
	}

	@Test public void acquiringWeaponWithoutConsumingComponentsIsNotCreation() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory();
		f.inventory(ItemID.NOXIOUS_HALBERD);
		f.inventory(halberdPieces());
		f.inventory(ItemID.NOXIOUS_HALBERD, ItemID.NOXIOUS_HALBERD_PART_1,
			ItemID.NOXIOUS_HALBERD_PART_2, ItemID.NOXIOUS_HALBERD_PART_3);
		verifyNoInteractions(f.items, f.pipeline);
	}

	@Test public void requiresEveryComponentToBeConsumed() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(halberdPieces());
		f.inventory(ItemID.NOXIOUS_HALBERD, ItemID.NOXIOUS_HALBERD_PART_3);
		verifyNoInteractions(f.items, f.pipeline);
	}

	@Test public void repeatedUpdatesDoNotResubmit() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(halberdPieces());
		f.inventory(ItemID.NOXIOUS_HALBERD);
		f.inventory(ItemID.NOXIOUS_HALBERD);
		f.verifyCapture(ItemID.NOXIOUS_HALBERD, "noxious halberd");
		verifyNoMoreInteractions(f.pipeline);
	}

	@Test public void canCreateAnotherWeaponWhileAlreadyOwningOne() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(ItemID.NOXIOUS_HALBERD, ItemID.NOXIOUS_HALBERD_PART_1,
			ItemID.NOXIOUS_HALBERD_PART_2, ItemID.NOXIOUS_HALBERD_PART_3);
		f.inventory(ItemID.NOXIOUS_HALBERD, ItemID.NOXIOUS_HALBERD);
		f.verifyCapture(ItemID.NOXIOUS_HALBERD, "noxious halberd");
	}

	@Test public void resetDiscardsOldBaseline() throws Exception
	{
		Fixture f = new Fixture();
		f.inventory(halberdPieces());
		f.listener.reset();
		f.inventory(ItemID.NOXIOUS_HALBERD);
		verifyNoInteractions(f.items, f.pipeline);
	}

	@Test public void ignoresOtherContainers() throws Exception
	{
		Fixture f = new Fixture();
		ItemContainerChanged event = mock(ItemContainerChanged.class);
		when(event.getContainerId()).thenReturn(InventoryID.BANK.getId());
		f.listener.onItemContainerChanged(event);
		verify(event, never()).getItemContainer();
		verifyNoInteractions(f.items, f.pipeline);
	}

	private static int[] halberdPieces()
	{
		return new int[] {ItemID.NOXIOUS_HALBERD_PART_1, ItemID.NOXIOUS_HALBERD_PART_2,
			ItemID.NOXIOUS_HALBERD_PART_3};
	}

	private static class Fixture
	{
		final WeaponCreationEventListener listener = new WeaponCreationEventListener();
		final ItemManager items = mock(ItemManager.class);
		final EventPipeline pipeline = mock(EventPipeline.class);

		Fixture() throws Exception
		{
			Client client = mock(Client.class);
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
			inject("client", client);
			inject("itemManager", items);
			inject("pipeline", pipeline);
			ItemComposition halberd = mock(ItemComposition.class);
			when(halberd.getName()).thenReturn("Noxious halberd");
			when(items.getItemComposition(ItemID.NOXIOUS_HALBERD)).thenReturn(halberd);
			ItemComposition axe = mock(ItemComposition.class);
			when(axe.getName()).thenReturn("Soulreaper axe");
			when(items.getItemComposition(ItemID.SOULREAPER)).thenReturn(axe);
			ItemComposition ring = mock(ItemComposition.class);
			when(ring.getName()).thenReturn("Brimstone ring");
			when(items.getItemComposition(ItemID.BRIMSTONE_RING)).thenReturn(ring);
			ItemComposition staff = mock(ItemComposition.class);
			when(staff.getName()).thenReturn("Twinflame staff");
			when(items.getItemComposition(ItemID.TWINFLAME_STAFF)).thenReturn(staff);
		}

		void inventory(int... ids)
		{
			Item[] contents = new Item[ids.length];
			for (int i = 0; i < ids.length; i++) contents[i] = new Item(ids[i], 1);
			ItemContainer container = mock(ItemContainer.class);
			when(container.getItems()).thenReturn(contents);
			ItemContainerChanged event = mock(ItemContainerChanged.class);
			when(event.getContainerId()).thenReturn(InventoryID.INVENTORY.getId());
			when(event.getItemContainer()).thenReturn(container);
			listener.onItemContainerChanged(event);
		}

		void verifyCapture(int id, String name)
		{
			verify(items).getItemComposition(id);
			verify(pipeline).capture(eq("loot"), eq(name), any(AnchorModels.Source.class),
				org.mockito.ArgumentMatchers.<List<AnchorModels.Item>>argThat(list ->
					list.size() == 1 && list.get(0).itemId == id && list.get(0).quantity == 1),
				org.mockito.ArgumentMatchers.<Map<String, Object>>argThat(details ->
					Boolean.TRUE.equals(details.get("autoSubmit"))), eq(true));
		}

		void inject(String name, Object value) throws Exception
		{
			Field field = WeaponCreationEventListener.class.getDeclaredField(name);
			field.setAccessible(true);
			field.set(listener, value);
		}
	}
}
