package com.theanchor.ui;

import com.theanchor.evidence.EvidenceStore;
import com.theanchor.model.AnchorModels;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class AnchorPanelSubmissionTest
{
	@Test public void resolvesSubmissionIconFromFirstItem()
	{
		EvidenceStore.Record record = new EvidenceStore.Record();
		record.metadata = new AnchorModels.EventEnvelope();
		assertNull(AnchorPanel.submissionItemId(record));

		AnchorModels.Item item = new AnchorModels.Item();
		item.itemId = 34027;
		record.metadata.items.add(item);

		assertEquals(Integer.valueOf(34027), AnchorPanel.submissionItemId(record));
	}
}
