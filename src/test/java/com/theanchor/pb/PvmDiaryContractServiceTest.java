package com.theanchor.pb;

import com.theanchor.model.AnchorModels;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class PvmDiaryContractServiceTest
{
	@Test public void mapsOnlyAnExactContractBackedCoxTrio()
	{
		PvmDiaryContractService service = new PvmDiaryContractService();
		AnchorModels.PvmDiaryContract contract = new AnchorModels.PvmDiaryContract();
		contract.catalogueVersion = "2026-09-19";
		AnchorModels.PvmDiaryContractActivity activity = new AnchorModels.PvmDiaryContractActivity();
		activity.id = "cox-trio"; activity.kind = "time"; activity.teamSize = 3;
		contract.activities.add(activity);
		inject(service, "contract", contract);

		AnchorModels.PbRecord record = PersonalBestService.diaryRecordFromKey("chambers of xeric 3 players", 880);
		Map<String, Object> details = service.detailsForObservedResult(record, "chambers of xeric 3 players", null, "pb-1");

		assertNotNull(details);
		assertEquals("cox-trio", details.get("activityId"));
		assertEquals(880000L, details.get("result"));
		assertEquals(3, details.get("teamSize"));
	}

	@Test public void rejectsTeamBucketsAndToaTimes()
	{
		PvmDiaryContractService service = new PvmDiaryContractService();
		AnchorModels.PvmDiaryContract contract = new AnchorModels.PvmDiaryContract();
		contract.catalogueVersion = "2026-09-19";
		inject(service, "contract", contract);
		assertNull(service.detailsForObservedResult(PersonalBestService.diaryRecordFromKey("chambers of xeric 5+ players", 800),
			"chambers of xeric 5+ players", null, "pb-1"));
		assertNull(service.detailsForObservedResult(PersonalBestService.diaryRecordFromKey("tombs of amascut expert mode solo", 800),
			"tombs of amascut expert mode solo", null, "pb-2"));
	}

	@Test public void mapsObservedRaidCompletionWithoutRequiringAPersonalBest()
	{
		PvmDiaryContractService service = new PvmDiaryContractService();
		AnchorModels.PvmDiaryContract contract = new AnchorModels.PvmDiaryContract();
		contract.catalogueVersion = "2026-09-19";
		AnchorModels.PvmDiaryContractActivity activity = new AnchorModels.PvmDiaryContractActivity();
		activity.id = "cox-five"; activity.kind = "time"; activity.teamSize = 5;
		contract.activities.add(activity);
		inject(service, "contract", contract);

		AnchorModels.PbRecord record = PersonalBestService.coxCompletionRecord(
			"Team size: 5 players Duration: 11:15.00 Personal best: 9:52.80 Olm duration: 5:13.2");
		Map<String, Object> details = service.detailsForRaidCompletion(record,
			"chambers of xeric 5 players", "raid-1");

		assertNotNull(details);
		assertEquals("cox-five", details.get("activityId"));
		assertEquals("time", details.get("resultKind"));
		assertEquals(675000L, details.get("result"));
		assertEquals("raid_completion", details.get("source"));
	}

	@Test public void mapsCompletionToaAndDoomResultKinds()
	{
		PvmDiaryContractService service = new PvmDiaryContractService();
		AnchorModels.PvmDiaryContract contract = new AnchorModels.PvmDiaryContract();
		contract.catalogueVersion = "2026-09-19";
		contract.activities.add(activity("tob-solo", "completion", 1));
		contract.activities.add(activity("toa-300", "time", 1));
		AnchorModels.PvmDiaryContractActivity doom = activity("doom", "wave", 1);
		doom.tiers.add(tier("easy", 10));
		doom.tiers.add(tier("medium", 16));
		doom.tiers.add(tier("hard", 40));
		contract.activities.add(doom);
		inject(service, "contract", contract);

		AnchorModels.PbRecord tob = PersonalBestService.diaryRecordFromKey("theatre of blood solo", 1800);
		Map<String, Object> completion = service.detailsForObservedResult(tob,
			"theatre of blood solo", null, "raid-2");
		assertEquals("completion", completion.get("resultKind"));
		assertTrue(completion.containsKey("result"));
		assertNull(completion.get("result"));

		AnchorModels.PbRecord toa = PersonalBestService.diaryRecordFromKey("tombs of amascut expert mode solo", 1200);
		Map<String, Object> toaDetails = service.detailsForObservedResult(toa,
			"tombs of amascut expert mode 1 players", 300, "raid-3");
		assertEquals("toa-300", toaDetails.get("activityId"));
		assertEquals(300, toaDetails.get("invocation"));
		assertNull(service.detailsForObservedResult(toa,
			"tombs of amascut expert mode 1 players", 350, "raid-4"));

		assertEquals(40L, service.detailsForWave(40, "doom-1").get("result"));
		assertFalse(service.isDoomWaveEligible(9));
		assertTrue(service.isDoomWaveEligible(10));
		assertTrue(service.isDoomWaveEligible(20));
	}

	@Test public void deserializesLiveCatalogueField()
	{
		AnchorModels.PvmDiaryContract contract = new com.google.gson.Gson().fromJson(
			"{\"catalogueVersion\":\"2026-09-19\",\"catalogue\":[{\"id\":\"cg\",\"kind\":\"time\",\"teamSize\":1}]}",
			AnchorModels.PvmDiaryContract.class);
		assertEquals(1, contract.activities.size());
		assertEquals("cg", contract.activities.get(0).id);
	}

	private static AnchorModels.PvmDiaryContractActivity activity(String id, String kind, int teamSize)
	{
		AnchorModels.PvmDiaryContractActivity activity = new AnchorModels.PvmDiaryContractActivity();
		activity.id = id; activity.kind = kind; activity.teamSize = teamSize;
		return activity;
	}

	private static AnchorModels.PvmDiaryTier tier(String name, long target)
	{
		AnchorModels.PvmDiaryTier tier = new AnchorModels.PvmDiaryTier();
		tier.tier = name; tier.target = target;
		return tier;
	}

	private static void inject(Object target, String fieldName, Object value)
	{
		try
		{
			Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		}
		catch (ReflectiveOperationException e) { throw new AssertionError(e); }
	}
}
