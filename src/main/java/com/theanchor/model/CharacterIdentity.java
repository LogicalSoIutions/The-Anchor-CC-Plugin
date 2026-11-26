package com.theanchor.model;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;

public final class CharacterIdentity
{
	private CharacterIdentity() { }

	public static AnchorModels.PlayerIdentity capture(Client client)
	{
		if (client.getGameState() != GameState.LOGGED_IN) return null;
		Player player = client.getLocalPlayer();
		long hash = client.getAccountHash();
		String name = player == null ? null : player.getName();
		if (hash == 0L || name == null || name.isBlank()) return null;

		AnchorModels.PlayerIdentity identity = new AnchorModels.PlayerIdentity();
		identity.name = name;
		identity.accountHash = Long.toString(hash);
		return identity;
	}
}
