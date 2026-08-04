package com.theanchor.service;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

public class BingoServiceTest
{
	@Test public void requiresBothConfiguredBossAndItem()
	{
		AnchorApiClient api = mock(AnchorApiClient.class);
		AnchorModels.BingoEvent event = new AnchorModels.BingoEvent();
		event.active = true;
		event.eventTitle = "The Anchor Bingo";
		event.itemIds = Collections.singletonList(20997);
		event.bossIds = Collections.singletonList(5886);
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked") AnchorApiClient.ResultCallback<AnchorModels.BingoEvent> callback = invocation.getArgument(0);
			callback.complete(AnchorApiClient.ApiResult.ok(200, event));
			return null;
		}).when(api).getBingo(any());

		BingoService service = new BingoService(api);
		service.refresh();
		AnchorModels.Item matching = item(20997);
		AnchorModels.Item other = item(21003);

		assertEquals(Collections.singletonList(matching), service.matchingItems(5886, Arrays.asList(matching, other)));
		assertTrue(service.matchingItems(9999, Collections.singletonList(matching)).isEmpty());
		assertTrue(service.matchingItems(5886, Collections.singletonList(other)).isEmpty());
		assertEquals(Collections.singletonList(matching),
			service.matchingItems(null, "Chambers of Xeric", Collections.singletonList(matching)));
	}

	private static AnchorModels.Item item(int id)
	{
		AnchorModels.Item item = new AnchorModels.Item();
		item.itemId = id;
		return item;
	}
}
