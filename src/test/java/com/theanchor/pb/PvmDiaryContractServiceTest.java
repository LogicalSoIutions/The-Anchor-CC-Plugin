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
		Map<String, Object> details = service.detailsFor(record, "chambers of xeric 3 players", "pb-1");

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
		assertNull(service.detailsFor(PersonalBestService.diaryRecordFromKey("chambers of xeric 5+ players", 800),
			"chambers of xeric 5+ players", "pb-1"));
		assertNull(service.detailsFor(PersonalBestService.diaryRecordFromKey("tombs of amascut expert mode solo", 800),
			"tombs of amascut expert mode solo", "pb-2"));
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
