package com.theanchor.ui;

import com.theanchor.service.AnchorDataService;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetPositionMode;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.api.events.BeforeRender;

/** Adds a small Anchor refresh button beside RuneProfile's collection-log button. */
@Singleton
public class CollectionLogRefreshButton
{

	private static final int COLLECTION_INIT_SCRIPT = 2240;
	private static final int COLLECTION_LOG_SETUP = 7797;
	private static final int SEARCH_ICON_CHILD = 9;
	private static final int BUTTON_WIDTH = 24;
	private static final int BUTTON_GAP = 3;
	private static final int CORNER_SIZE = 9;
	private static final int FONT_COLOR_INACTIVE = 0xd6d6d6;
	private static final int FONT_COLOR_ACTIVE = 0xffffff;

	private static final int[] SPRITE_IDS_INACTIVE = {
		SpriteID.TRADEBACKING,
		SpriteID.V2StoneButtonOut.A_TOP_LEFT,
		SpriteID.V2StoneButtonOut.A_TOP_RIGHT,
		SpriteID.V2StoneButtonOut.A_BOTTOM_LEFT,
		SpriteID.V2StoneButtonOut.A_BOTTOM_RIGHT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_LEFT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_TOP,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_RIGHT,
		SpriteID.V2StoneButtonOut.A_MAP_EDGE_BOTTOM,
	};

	private static final int[] SPRITE_IDS_ACTIVE = {
		SpriteID.TRADEBACKING_DARK,
		SpriteID.V2StoneButtonIn.A_TOP_LEFT,
		SpriteID.V2StoneButtonIn.A_TOP_RIGHT,
		SpriteID.V2StoneButtonIn.A_BOTTOM_LEFT,
		SpriteID.V2StoneButtonIn.A_BOTTOM_RIGHT,
		SpriteID.V2StoneButtonIn.A_LEFT,
		SpriteID.V2StoneButtonIn.A_TOP,
		SpriteID.V2StoneButtonIn.A_RIGHT,
		SpriteID.V2StoneButtonIn.A_BOTTOM,
	};

	private final Client client;
	private final ClientThread clientThread;
	private final EventBus eventBus;
	private final CollectionLogAutoSync autoSync;
	private final AnchorDataService data;
	private Widget buttonLayer;
	private Widget[] buttonSprites;
	private Widget buttonText;

	@Inject
	public CollectionLogRefreshButton(Client client, ClientThread clientThread, EventBus eventBus,
		CollectionLogAutoSync autoSync, AnchorDataService data)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.eventBus = eventBus;
		this.autoSync = autoSync;
		this.data = data;
	}

	public void startUp()
	{
		eventBus.register(this);
		clientThread.invokeLater(this::setupButton);
	}

	public void shutDown()
	{
		eventBus.unregister(this);
		clientThread.invokeLater(this::hideButton);
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.COLLECTION) return;
		hideButton();
		buttonLayer = null;
		buttonSprites = null;
		buttonText = null;
		// RuneProfile applies its wider button after the collection widgets settle.
		clientThread.invokeLater(() -> clientThread.invokeLater(this::setupButton));
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == COLLECTION_INIT_SCRIPT || event.getScriptId() == COLLECTION_LOG_SETUP)
		{
			clientThread.invokeLater(this::setupButton);
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender event)
	{
		if (!data.isCurrentPlayerClanMember())
		{
			hideButton();
			return;
		}
		Widget searchButton = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);
		Widget parent = client.getWidget(InterfaceID.COLLECTION, 0);
		if (searchButton == null || parent == null || isSearchOpen() || isPohLog())
		{
			hideButton();
			return;
		}

		if (buttonLayer == null || !isAttached(parent, buttonLayer))
		{
			setupButton();
		}
		else
		{
			boolean positioned = positionButton(parent, searchButton);
			buttonLayer.setHidden(!positioned);
		}
	}

	private void setupButton()
	{
		if (!data.isCurrentPlayerClanMember())
		{
			hideButton();
			return;
		}
		Widget searchButton = client.getWidget(InterfaceID.Collection.SEARCH_TOGGLE);
		if (searchButton == null || isSearchOpen() || isPohLog())
		{
			hideButton();
			return;
		}

		// SEARCH_TOGGLE is nested inside a button-sized layer. A sibling placed there is
		// clipped by that layer, so attach to the full collection interface instead.
		Widget parent = client.getWidget(InterfaceID.COLLECTION, 0);
		Widget[] sourceChildren = searchButton.getChildren();
		if (parent == null || sourceChildren == null || sourceChildren.length <= SEARCH_ICON_CHILD) return;

		if (buttonLayer == null || !isAttached(parent, buttonLayer))
		{
			createButton(parent, searchButton, sourceChildren);
		}
		else
		{
			boolean positioned = positionButton(parent, searchButton);
			buttonLayer.setHidden(!positioned);
		}
	}

	private boolean isAttached(Widget parent, Widget child)
	{
		if (parent == null || child == null) return false;
		Widget[] children = parent.getChildren();
		if (children == null) return false;
		for (Widget w : children)
		{
			if (w == child) return true;
		}
		return false;
	}

	private void createButton(Widget parent, Widget searchButton, Widget[] sourceChildren)
	{
		int height = Math.max(1, searchButton.getHeight());
		buttonLayer = parent.createChild(-1, WidgetType.LAYER).setSize(BUTTON_WIDTH, height);
		buttonLayer.setXPositionMode(WidgetPositionMode.ABSOLUTE_LEFT);
		buttonLayer.setYPositionMode(WidgetPositionMode.ABSOLUTE_TOP);
		boolean positioned = positionButton(parent, searchButton);
		buttonLayer.setHidden(!positioned);

		buttonSprites = new Widget[SEARCH_ICON_CHILD];
		int sourceWidth = Math.max(1, searchButton.getOriginalWidth());
		for (int i = 0; i < SEARCH_ICON_CHILD; i++)
		{
			Widget source = sourceChildren[i];
			if (source == null) continue;
			int width = source.getOriginalWidth();
			int x = source.getOriginalX();
			if (width == sourceWidth) width = BUTTON_WIDTH;
			else if (width == sourceWidth - 2 * CORNER_SIZE) width = BUTTON_WIDTH - 2 * CORNER_SIZE;
			if (x == sourceWidth - CORNER_SIZE) x = BUTTON_WIDTH - CORNER_SIZE;

			buttonSprites[i] = buttonLayer.createChild(-1, WidgetType.GRAPHIC)
				.setSpriteId(SPRITE_IDS_INACTIVE[i])
				.setPos(x, source.getOriginalY())
				.setSize(width, source.getOriginalHeight());
			buttonSprites[i].revalidate();
		}

		Widget sourceText = sourceChildren.length > SEARCH_ICON_CHILD + 1
			? sourceChildren[SEARCH_ICON_CHILD + 1] : null;
		buttonText = buttonLayer.createChild(-1, WidgetType.TEXT)
			.setText("A")
			.setTextColor(FONT_COLOR_INACTIVE)
			.setPos(0, 0)
			.setSize(BUTTON_WIDTH, height)
			.setXTextAlignment(1)
			.setYTextAlignment(1)
			.setHasListener(true);
		if (sourceText != null)
		{
			buttonText.setFontId(sourceText.getFontId()).setTextShadowed(sourceText.getTextShadowed());
		}
		buttonText.setOnMouseOverListener((JavaScriptCallback) ev -> setActive(true));
		buttonText.setOnMouseLeaveListener((JavaScriptCallback) ev -> setActive(false));
		buttonText.setAction(0, "Refresh for The Anchor");
		buttonText.setOnOpListener((JavaScriptCallback) ev -> refreshCollectionLog());
		buttonText.revalidate();
		buttonLayer.revalidate();
		parent.revalidate();
	}

	private boolean positionButton(Widget parent, Widget searchButton)
	{
		if (buttonLayer == null) return false;
		Point parentLocation = parent.getCanvasLocation();
		Point searchLocation = searchButton.getCanvasLocation();
		if (parentLocation == null || searchLocation == null) return false;
		if (parentLocation.getX() < 0 || searchLocation.getX() < 0) return false;

		int searchWidth = Math.max(searchButton.getWidth(), searchButton.getOriginalWidth());
		int x = clamp(searchLocation.getX() - parentLocation.getX() + searchWidth + BUTTON_GAP,
			0, Math.max(0, parent.getWidth() - BUTTON_WIDTH));
		int y = clamp(searchLocation.getY() - parentLocation.getY(),
			0, Math.max(0, parent.getHeight() - buttonLayer.getHeight()));
		buttonLayer.setPos(x, y);
		buttonLayer.revalidate();

		return true;
	}

	static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(value, maximum));
	}

	private void refreshCollectionLog()
	{
		if (isSearchOpen() || isPohLog()) return;
		autoSync.refreshRequested();
	}

	private void setActive(boolean active)
	{
		if (buttonSprites == null) return;
		int[] spriteIds = active ? SPRITE_IDS_ACTIVE : SPRITE_IDS_INACTIVE;
		for (int i = 0; i < buttonSprites.length; i++)
		{
			if (buttonSprites[i] != null) buttonSprites[i].setSpriteId(spriteIds[i]);
		}
		if (buttonText != null) buttonText.setTextColor(active ? FONT_COLOR_ACTIVE : FONT_COLOR_INACTIVE);
	}

	private void hideButton()
	{
		if (buttonLayer != null) buttonLayer.setHidden(true);
	}

	private boolean isSearchOpen()
	{
		Widget searchContainer = client.getWidget(InterfaceID.Collection.SEARCH_CONTAINER);
		return searchContainer != null && !searchContainer.isHidden();
	}

	private boolean isPohLog()
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}
}
