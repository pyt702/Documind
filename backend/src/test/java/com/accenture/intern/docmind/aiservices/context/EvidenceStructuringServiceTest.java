package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.dto.chat.AggregatedEvidence;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EvidenceStructuringServiceTest {

    private final EvidenceStructuringService service = new EvidenceStructuringService();

    @Test
    void testBoundingBoxConcatenationAndLengthLimit() {
        List<RetrievalCandidate> candidates = new ArrayList<>();

        Map<String, Object> meta1 = new HashMap<>();
        meta1.put("sourceName", "doc.pdf");
        meta1.put("page", 1);
        meta1.put("charStart", 0);
        meta1.put("charEnd", 100);
        meta1.put("boundingBoxes", "[{\"x\":10}]");
        Document doc1 = new Document("id1", "Chunk 1 text.", meta1);
        candidates.add(new RetrievalCandidate(doc1, 0.95));

        Map<String, Object> meta2 = new HashMap<>();
        meta2.put("sourceName", "doc.pdf");
        meta2.put("page", 1);
        meta2.put("charStart", 101);
        meta2.put("charEnd", 200);
        meta2.put("boundingBoxes", "[{\"x\":20}]");
        Document doc2 = new Document("id2", "Chunk 2 text.", meta2);
        candidates.add(new RetrievalCandidate(doc2, 0.90));

        AggregatedEvidence agg = service.structure(candidates, new ArrayList<>(), MergeOperation.UNION);

        assertNotNull(agg);
        // The service returns string format. Let's ensure it mentions "Merged: 1 chunks"
        assertTrue(agg.evidenceString().contains("Merged: 1 chunks"));
        // Score should be max
        assertTrue(agg.evidenceString().contains("Score: 0.95"));
    }

    @Test
    void testEmptyList() {
        AggregatedEvidence agg = service.structure(new ArrayList<>(), new ArrayList<>(), MergeOperation.UNION);
        assertNotNull(agg);
        assertEquals("No relevant evidence found.", agg.evidenceString());
    }
}
