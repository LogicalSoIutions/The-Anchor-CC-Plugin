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
import com.theanchor.model.AnchorModels;
import com.theanchor.service.BingoService;
import java.lang.reflect.Field;
import java.util.Map;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
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
		verify(pipeline).capture(eq("pet"), eq("skotos"), org.mockito.ArgumentMatchers.any(AnchorModels.Source.class), isNull(), details.capture(), eq(false));
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
		when(bingo.shouldCapturePet(isNull(), eq("Skotizo"), org.mockito.ArgumentMatchers.anyList())).thenReturn(true);
		when(bingo.eventType()).thenReturn("bingo");
		inject(listener, "pipeline", pipeline);
		inject(listener, "bingo", bingo);
		inject(listener, "client", client);

		ChatMessage chat = mock(ChatMessage.class);
		when(chat.getType()).thenReturn(ChatMessageType.CLAN_MESSAGE);
		when(chat.getMessage()).thenReturn(
			"Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo.");

		listener.onChatMessage(chat);

		verify(pipeline).capture(eq("bingo"), eq("skotos"), org.mockito.ArgumentMatchers.any(AnchorModels.Source.class), org.mockito.ArgumentMatchers.anyList(),
			org.mockito.ArgumentMatchers.anyMap(), eq(false), isNull(), eq(false), eq(false));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void ignoresNormalCollectionLogItems()
	{
		assertNull(PetEventListener.extractPetName(
			"New item added to your collection log: Abyssal whip"));
	}

	@Test public void capturesUnnamedDuplicateAfterWaitingForName() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		String message = "You have a funny feeling like you would have been followed...";
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "<col=ff0000>" + message + "</col>"));
		listener.onGameTick(new GameTick());
		verifyNoMoreInteractions(pipeline);

		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());
		listener.onGameTick(new GameTick());

		ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
		verify(pipeline).capture(eq("pet"), eq("unknown pet"), isNull(), isNull(), details.capture(), eq(false));
		assertEquals(message, details.getValue().get("message"));
		assertEquals("Unknown pet", details.getValue().get("petName"));
		assertEquals(Boolean.TRUE, details.getValue().get("duplicate"));
		assertEquals(Boolean.FALSE, details.getValue().get("obtained"));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void resolvesPendingDuplicateFromClanBeforeFallback() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.SPAM,
			"You have a funny feeling like you would have been followed..."));
		listener.onChatMessage(chat(ChatMessageType.CLAN_MESSAGE,
			"Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo."));
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());

		ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
		verify(pipeline).capture(eq("pet"), eq("skotos"), org.mockito.ArgumentMatchers.any(AnchorModels.Source.class), isNull(), details.capture(), eq(false));
		assertEquals(Boolean.TRUE, details.getValue().get("duplicate"));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void unrelatedCollectionItemsDoNotNamePendingPet() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"New item added to your collection log: Abyssal whip"));
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"You have a funny feeling like you would have been followed..."));
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"New item added to your collection log: Abyssal dagger"));
		verifyNoMoreInteractions(pipeline);
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());

		verify(pipeline).capture(eq("pet"), eq("unknown pet"), isNull(), isNull(),
			org.mockito.ArgumentMatchers.anyMap(), eq(false));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void identifiesDuplicateFromRecentBossKill() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Your Dagannoth Prime kill count is: 2,114."));
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"You have a funny feeling like you would have been followed..."));
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());

		ArgumentCaptor<AnchorModels.Source> source = ArgumentCaptor.forClass(AnchorModels.Source.class);
		ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
		verify(pipeline).capture(eq("pet"), eq("pet dagannoth prime"), source.capture(), isNull(), details.capture(), eq(false));
		assertEquals("Dagannoth Prime", source.getValue().name);
		assertEquals("Pet dagannoth prime", details.getValue().get("petName"));
		assertEquals(Boolean.TRUE, details.getValue().get("duplicate"));
	}

	@Test public void clanBeforeGameMessageDoesNotLeaveUnknownPetPending() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.CLAN_MESSAGE,
			"Rutile has a funny feeling like he would have been followed: Skotos at 92 killcount from Skotizo."));
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"You have a funny feeling like you would have been followed..."));
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());

		ArgumentCaptor<AnchorModels.Source> source = ArgumentCaptor.forClass(AnchorModels.Source.class);
		verify(pipeline).capture(eq("pet"), eq("skotos"), source.capture(), isNull(),
			org.mockito.ArgumentMatchers.anyMap(), eq(false));
		assertEquals("Skotizo", source.getValue().name);
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void staleBossDoesNotNamePet() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Your Giant Mole kill count is: 100."));
		inject(listener, "recentBossAt", System.currentTimeMillis() - 20_000L);
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE,
			"You have a funny feeling like you would have been followed..."));
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());
		verify(pipeline).capture(eq("pet"), eq("unknown pet"), isNull(), isNull(),
			org.mockito.ArgumentMatchers.anyMap(), eq(false));
	}

	@Test public void capturesBackpackMessageUsingRecentBoss() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		String message = "You feel something weird sneaking into your backpack.";
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, "Your Giant Mole kill count is: 100."));
		listener.onChatMessage(chat(ChatMessageType.GAMEMESSAGE, message));
		inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
		listener.onGameTick(new GameTick());

		ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
		verify(pipeline).capture(eq("pet"), eq("baby mole"), org.mockito.ArgumentMatchers.any(AnchorModels.Source.class),
			isNull(), details.capture(), eq(false));
		assertEquals(message, details.getValue().get("message"));
		assertEquals(Boolean.TRUE, details.getValue().get("obtained"));
		assertEquals(Boolean.FALSE, details.getValue().get("duplicate"));
		verifyNoMoreInteractions(pipeline);
	}

	@Test public void resolvesBackpackMessageFromClanInEitherOrder() throws Exception
	{
		for (boolean clanFirst : new boolean[] {false, true})
		{
			EventPipeline pipeline = mock(EventPipeline.class);
			PetEventListener listener = listenerWithPipeline(pipeline);
			ChatMessage game = chat(ChatMessageType.SPAM, "You feel something weird sneaking into your backpack.");
			ChatMessage clan = chat(ChatMessageType.CLAN_MESSAGE,
				"Rutile feels something weird sneaking into his backpack: Skotos at 92 killcount from Skotizo.");
			listener.onChatMessage(clanFirst ? clan : game);
			listener.onChatMessage(clanFirst ? game : clan);
			inject(listener, "pendingPetAt", System.currentTimeMillis() - 6000L);
			listener.onGameTick(new GameTick());

			ArgumentCaptor<Map> details = ArgumentCaptor.forClass(Map.class);
			ArgumentCaptor<AnchorModels.Source> source = ArgumentCaptor.forClass(AnchorModels.Source.class);
			verify(pipeline).capture(eq("pet"), eq("skotos"), source.capture(), isNull(), details.capture(), eq(false));
			assertEquals("Skotizo", source.getValue().name);
			assertEquals("Skotos", details.getValue().get("petName"));
			assertEquals(Boolean.TRUE, details.getValue().get("obtained"));
			assertEquals(Boolean.FALSE, details.getValue().get("duplicate"));
			verifyNoMoreInteractions(pipeline);
		}
	}

	@Test public void ignoresOtherPlayersBackpackClanMessages() throws Exception
	{
		EventPipeline pipeline = mock(EventPipeline.class);
		PetEventListener listener = listenerWithPipeline(pipeline);
		listener.onChatMessage(chat(ChatMessageType.CLAN_MESSAGE,
			"Someone Else feels something weird sneaking into her backpack: Skotos at 92 killcount from Skotizo."));
		listener.onGameTick(new GameTick());
		verifyNoMoreInteractions(pipeline);
	}

	private static PetEventListener listenerWithPipeline(EventPipeline pipeline) throws Exception
	{
		PetEventListener listener = new PetEventListener();
		Client client = mock(Client.class);
		Player player = mock(Player.class);
		when(client.getLocalPlayer()).thenReturn(player);
		when(player.getName()).thenReturn("Rutile");
		inject(listener, "pipeline", pipeline);
		inject(listener, "bingo", mock(BingoService.class));
		inject(listener, "client", client);
		return listener;
	}

	private static ChatMessage chat(ChatMessageType type, String message)
	{
		ChatMessage event = mock(ChatMessage.class);
		when(event.getType()).thenReturn(type);
		when(event.getMessage()).thenReturn(message);
		return event;
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception
	{
		Field field = target.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
