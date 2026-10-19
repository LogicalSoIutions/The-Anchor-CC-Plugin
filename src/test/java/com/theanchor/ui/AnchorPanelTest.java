package com.theanchor.ui;

import com.theanchor.service.AnchorDataService;
import java.awt.Dimension;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
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

	@Test public void sidebarKeepsItsWidthAndContentHeightWhenSettingsChangesAvailableSpace() throws Exception
	{
		SwingUtilities.invokeAndWait(() -> {
			JPanel content = new JPanel();
			content.setPreferredSize(new Dimension(200, 1200));
			JScrollPane scroll = AnchorPanel.scroll(content, true);
			Scrollable view = (Scrollable) scroll.getViewport().getView();
			int viewportWidth = -1;
			// Cross the overflow threshold, then reopen and close the settings section.
			for (int height : new int[] { 1300, 850, 750, 850, 1300, 750 }) {
				scroll.setSize(240, height);
				scroll.doLayout();
				scroll.getViewport().doLayout();
				((JPanel) scroll.getViewport().getView()).doLayout();
				assertTrue(scroll.getVerticalScrollBar().isVisible());
				assertFalse(view.getScrollableTracksViewportHeight());
				assertTrue(content.getHeight() >= 1200);
				if (viewportWidth < 0) viewportWidth = scroll.getViewport().getWidth();
				assertEquals(viewportWidth, scroll.getViewport().getWidth());
			}
		});
	}
}
