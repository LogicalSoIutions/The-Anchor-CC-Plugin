package com.theanchor.ui;

import com.theanchor.service.AnchorDataService;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import net.runelite.client.ui.ColorScheme;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

	@Test public void scrollPanePaintsUnusedViewportWithThePanelBackground()
	{
		JScrollPane scroll = AnchorPanel.scroll(new JPanel());

		assertEquals(ColorScheme.DARK_GRAY_COLOR, scroll.getBackground());
		assertEquals(ColorScheme.DARK_GRAY_COLOR, scroll.getViewport().getBackground());
		assertTrue(scroll.getViewport().isOpaque());
	}
}
