package com.theanchor.service;

import com.theanchor.api.AnchorApiClient;
import com.theanchor.model.AnchorModels;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class RulesService
{
	private final AnchorApiClient api;
	private volatile AnchorModels.Rules current = new AnchorModels.Rules();

	@Inject public RulesService(AnchorApiClient api) { this.api = api; }
	public AnchorModels.Rules current() { return current; }

	public void refresh(Runnable callback)
	{
		api.getRules(result ->
		{
			if (result.isSuccessful() && result.value != null)
			{
				AnchorModels.Rules loaded = result.value;
				if (loaded.minimumLootValue <= 0) loaded.minimumLootValue = 2_500_000L;
				current = loaded;
			}
			if (callback != null) callback.run();
		});
	}
}
