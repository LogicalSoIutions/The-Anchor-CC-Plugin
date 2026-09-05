package com.theanchor.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.theanchor.evidence.EventPipeline;
import com.theanchor.service.BingoService;
import java.lang.reflect.Field;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

public class PetEventListenerTest
{
	@Test public void extractsPetNameFromCollectionLogChat()
	{
		assertEquals("Phoenix", PetEventListener.extractPetName(
			"New item added to your collection log: Phoenix"));
	}

	@Test public void extractsPetNameFromNamedFollowedChat()
	{
		assertEquals("Aggi", PetEventListener.extractPetName(
			"This Whip has a funny feeling like he's being followed: Aggi at 90 kills from Mad Angel."));
	}

	@Test public void extractsDuplicatePetFromRutileMessage()
	{
		String message = "Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo.";
		assertEquals("Skotos", PetEventListener.extractPetName(message));
		assertTrue(PetEventListener.isDuplicatePetMessage(message));
	}

	@Test public void extractsPetNameFromUntradeableDrop()
	{
		assertEquals("Skotos", PetEventListener.extractPetName("Untradeable drop: Skotos"));
	}

	@Test public void recognizesClanNotificationTypes()
	{
		assertTrue(PetEventListener.isClanNotification(ChatMessageType.CLAN_MESSAGE));
		assertTrue(PetEventListener.isClanNotification(ChatMessageType.CLAN_GUEST_MESSAGE));
		assertTrue(PetEventListener.isClanNotification(ChatMessageType.CLAN_GIM_MESSAGE));
	}

	@Test public void capturesDuplicateClanMessageForLocalPlayer() throws Exception
	{
		PetEventListener listener = new PetEventListener();
		EventPipeline pipeline = mock(EventPipeline.class);
		BingoService bingo = mock(BingoService.class);
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("Rutile");
		inject(listener, "pipeline", pipeline);
		inject(listener, "bingo", bingo);
		inject(listener, "client", client);

		ChatMessage chat = mock(ChatMessage.class);
		when(chat.getType()).thenReturn(ChatMessageType.CLAN_MESSAGE);
		when(chat.getMessage()).thenReturn(
			"Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo.");

		listener.onChatMessage(chat);

		ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
		verify(pipeline).capture(eq("pet"), eq("skotos"), isNull(), isNull(), details.capture(), eq(false));
		assertEquals(Boolean.TRUE, details.getValue().get("duplicate"));
		assertEquals(Boolean.FALSE, details.getValue().get("obtained"));
	}

	@Test public void capturesOnlyOneEventWhenPetMatchesBingo() throws Exception
	{
		PetEventListener listener = new PetEventListener();
		EventPipeline pipeline = mock(EventPipeline.class);
		BingoService bingo = mock(BingoService.class);
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("Rutile");
		when(bingo.shouldCapturePet(isNull(), isNull(), org.mockito.ArgumentMatchers.anyList())).thenReturn(true);
		when(bingo.eventType()).thenReturn("bingo");
		inject(listener, "pipeline", pipeline);
		inject(listener, "bingo", bingo);
		inject(listener, "client", client);

		ChatMessage chat = mock(ChatMessage.class);
		when(chat.getType()).thenReturn(ChatMessageType.CLAN_MESSAGE);
		when(chat.getMessage()).thenReturn(
			"Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo.");

		listener.onChatMessage(chat);

		verify(pipeline).capture(eq("bingo"), eq("skotos"), isNull(), org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyMap(), eq(false), isNull(), eq(false), eq(false));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void ignoresNormalCollectionLogItems()
	{
		assertNull(PetEventListener.extractPetName(
			"New item added to your collection log: Abyssal whip"));
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
