package com.theanchor.ui;

import com.theanchor.service.AnchorDataService;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AnchorPanelTest
{
	@Test public void requiresAuthenticationImageOnlyForMissingOrInvalidCode()
	{
		assertTrue(AnchorPanel.requiresCompetitionAuthentication(AnchorDataService.Connection.NOT_CONFIGURED));
		assertTrue(AnchorPanel.requiresCompetitionAuthentication(AnchorDataService.Connection.INVALID));
		assertFalse(AnchorPanel.requiresCompetitionAuthentication(AnchorDataService.Connection.CHECKING));
		assertFalse(AnchorPanel.requiresCompetitionAuthentication(AnchorDataService.Connection.CONNECTED));
		assertFalse(AnchorPanel.requiresCompetitionAuthentication(AnchorDataService.Connection.UNAVAILABLE));
	}
}
