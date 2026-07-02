package com.accenture.intern.docmind.aiservices.context;

import com.accenture.intern.docmind.aiservices.understanding.MergeOperation;
import com.accenture.intern.docmind.dto.chat.AggregatedEvidence;
import com.accenture.intern.docmind.dto.chat.VisualEvidence;
import com.accenture.intern.docmind.dto.retrieval.RetrievalCandidate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class EvidenceStructuringService {

    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(19\\d{2}|20\\d{2}|[1-9]\\d* (million|billion|crore|lakh) years|10\\^[-]?\\d+ sec)\\b", Pattern.CASE_INSENSITIVE);

    public AggregatedEvidence structure(List<RetrievalCandidate> textEvidence, List<VisualEvidence> visualEvidence, MergeOperation mergeOperation) {
        StringBuilder structuredOutput = new StringBuilder();
        int initialSize = textEvidence == null ? 0 : textEvidence.size();

        // 1. Process Visual Evidence (Keep existing visual logic)
        List<VisualEvidence> updatedVisuals = new ArrayList<>();
        if (visualEvidence != null && !visualEvidence.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Visual Evidence\n\n");
            int imgCounter = 1;
            for (VisualEvidence ve : visualEvidence) {
                String stableId = String.format("IMG_%02d", imgCounter++);
                String displayTitle = "Image from " + ve.sourceDocument();
                Matcher m = Pattern.compile("\\[Summary: (.*?)\\]").matcher(ve.caption() != null ? ve.caption() : "");
                if (m.find()) {
                    String summary = m.group(1).trim();
                    String[] parts = summary.split("\\.");
                    String title = parts[0];
                    title = title.replaceAll("^(?i)(A|The|This) (detailed |descriptive )?(diagram|image|chart|screenshot|photo) (showing|of|illustrating|comparing) ", "");
                    if (title.length() > 60) title = title.substring(0, 60) + "...";
                    if (!title.isEmpty()) displayTitle = title.substring(0, 1).toUpperCase() + title.substring(1);
                }

                VisualEvidence updated = new VisualEvidence(
                        ve.semanticId(), stableId, ve.imageUrl(), ve.thumbnailUrl(),
                        displayTitle, ve.score(), ve.sourceDocument()
                );
                updatedVisuals.add(updated);

                structuredOutput.append(String.format("Image %d\nDisplay Title: %s\nDetails: %s\n\n",
                        imgCounter - 1, displayTitle, ve.caption()));
            }
        }

        if (textEvidence == null || textEvidence.isEmpty()) {
            if (updatedVisuals.isEmpty()) {
                structuredOutput.append("No relevant evidence found.");
            }
            return new AggregatedEvidence(structuredOutput.toString().trim(), updatedVisuals);
        }

        // 2. Remove Duplicates (by exact chunk ID or text)
        List<RetrievalCandidate> deduplicated = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (RetrievalCandidate cand : textEvidence) {
            String id = cand.chunk().getId();
            if (id != null && seenIds.add(id)) {
                deduplicated.add(cand);
            } else if (id == null) {
                deduplicated.add(cand); // if somehow no ID, keep it
            }
        }
        int duplicatesRemoved = initialSize - deduplicated.size();

        // 3. Sort before merging: Primary: sourceName, Secondary: Page, Tertiary: charStart
        deduplicated.sort(Comparator
                .comparing((RetrievalCandidate c) -> (String) c.chunk().getMetadata().getOrDefault("sourceName", ""))
                .thenComparing((RetrievalCandidate c) -> (Integer) c.chunk().getMetadata().getOrDefault("page", 0))
                .thenComparing((RetrievalCandidate c) -> (Integer) c.chunk().getMetadata().getOrDefault("charStart", 0)));

        // 4. Merge Overlapping/Adjacent Chunks
        List<RetrievalCandidate> merged = new ArrayList<>();
        int mergedCount = 0;
        
        if (!deduplicated.isEmpty()) {
            RetrievalCandidate current = deduplicated.get(0);
            for (int i = 1; i < deduplicated.size(); i++) {
                RetrievalCandidate next = deduplicated.get(i);
                
                String currDoc = (String) current.chunk().getMetadata().getOrDefault("sourceName", "");
                String nextDoc = (String) next.chunk().getMetadata().getOrDefault("sourceName", "");
                
                int currPage = (Integer) current.chunk().getMetadata().getOrDefault("page", -1);
                int nextPage = (Integer) next.chunk().getMetadata().getOrDefault("page", -1);
                
                int currEnd = (Integer) current.chunk().getMetadata().getOrDefault("charEnd", -1);
                int nextStart = (Integer) next.chunk().getMetadata().getOrDefault("charStart", -1);
                
                boolean sameDoc = currDoc.equals(nextDoc) && !currDoc.isEmpty();
                boolean adjacentPage = Math.abs(currPage - nextPage) <= 1 && currPage != -1;
                boolean overlappingOrAdjacentText = currEnd != -1 && nextStart != -1 && (nextStart <= currEnd + 50);
                boolean similarScore = Math.abs(current.finalScore() - next.finalScore()) <= 0.15;
                
                int combinedLength = current.chunk().getText().length() + next.chunk().getText().length();
                boolean withinLengthLimit = combinedLength <= 2500;

                if (sameDoc && adjacentPage && overlappingOrAdjacentText && similarScore && withinLengthLimit) {
                    // Merge text
                    String newText = current.chunk().getText() + "\n... " + next.chunk().getText();
                    current.chunk().getMetadata().put("charEnd", Math.max(currEnd, (Integer) next.chunk().getMetadata().getOrDefault("charEnd", currEnd)));
                    
                    // Merge bounding boxes
                    try {
                        String bboxes1 = (String) current.chunk().getMetadata().get("boundingBoxes");
                        String bboxes2 = (String) next.chunk().getMetadata().get("boundingBoxes");
                        
                        if (bboxes1 != null && bboxes2 != null) {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode arr1 = mapper.readTree(bboxes1);
                            com.fasterxml.jackson.databind.JsonNode arr2 = mapper.readTree(bboxes2);
                            if (arr1.isArray() && arr2.isArray()) {
                                com.fasterxml.jackson.databind.node.ArrayNode mergedArr = mapper.createArrayNode();
                                mergedArr.addAll((com.fasterxml.jackson.databind.node.ArrayNode) arr1);
                                mergedArr.addAll((com.fasterxml.jackson.databind.node.ArrayNode) arr2);
                                current.chunk().getMetadata().put("boundingBoxes", mapper.writeValueAsString(mergedArr));
                            }
                        } else if (bboxes2 != null) {
                            current.chunk().getMetadata().put("boundingBoxes", bboxes2);
                        }
                    } catch (Exception e) {
                        // ignore parsing errors, just keep the first chunk's boxes
                    }

                    // Update score to max
                    double maxScore = Math.max(current.finalScore(), next.finalScore());
                    
                    current = new RetrievalCandidate(
                            new org.springframework.ai.document.Document(current.chunk().getId(), newText, current.chunk().getMetadata()),
                            maxScore
                    );
                    mergedCount++;
                } else {
                    merged.add(current);
                    current = next;
                }
            }
            merged.add(current);
        }

        // 5. Final Sort: Primary: Score, Secondary: Page, Tertiary: charStart
        merged.sort(Comparator
                .comparing(RetrievalCandidate::finalScore).reversed()
                .thenComparing((RetrievalCandidate c) -> (Integer) c.chunk().getMetadata().getOrDefault("page", 0))
                .thenComparing((RetrievalCandidate c) -> (Integer) c.chunk().getMetadata().getOrDefault("charStart", 0)));


        // 6. Split into Primary (>0.85) and Supporting (<0.85)
        List<RetrievalCandidate> primary = new ArrayList<>();
        List<RetrievalCandidate> supporting = new ArrayList<>();
        for (RetrievalCandidate c : merged) {
            if (c.finalScore() > 0.85) {
                primary.add(c);
            } else {
                supporting.add(c);
            }
        }

        structuredOutput.append("Question Context\n\n");
        
        if (!primary.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Primary Evidence\n\n");
            int idx = 1;
            for (RetrievalCandidate c : primary) {
                appendCandidate(structuredOutput, c, idx++);
            }
        }
        
        if (!supporting.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Supporting Evidence\n\n");
            int idx = primary.size() + 1;
            for (RetrievalCandidate c : supporting) {
                appendCandidate(structuredOutput, c, idx++);
            }
        }

        // 7. Hybrid Timeline Extraction
        List<String> timelineEvents = new ArrayList<>();
        for (RetrievalCandidate c : merged) {
            String text = c.chunk().getText();
            Matcher m = YEAR_PATTERN.matcher(text);
            if (m.find()) {
                // opportunistic timeline
                String snippet = text.length() > 100 ? text.substring(0, 100) + "..." : text;
                timelineEvents.add(m.group(1) + " : " + snippet.replaceAll("\\s+", " "));
            }
        }
        
        if (!timelineEvents.isEmpty()) {
            structuredOutput.append("=====================\n");
            structuredOutput.append("Timeline (opportunistic)\n\n");
            for (String event : timelineEvents) {
                structuredOutput.append("- ").append(event).append("\n");
            }
            structuredOutput.append("\n");
        }

        // 8. Notes
        structuredOutput.append("=====================\n");
        structuredOutput.append("Notes\n\n");
        structuredOutput.append(String.format("Merged: %d chunks\n", mergedCount));
        structuredOutput.append(String.format("Duplicates removed: %d\n", duplicatesRemoved));

        return new AggregatedEvidence(structuredOutput.toString().trim(), updatedVisuals);
    }

    private void appendCandidate(StringBuilder sb, RetrievalCandidate cand, int index) {
        String name = (String) cand.chunk().getMetadata().getOrDefault("sourceName", "unknown");
        Integer page = (Integer) cand.chunk().getMetadata().getOrDefault("page", null);
        String pageStr = page != null ? "Page " + page : "Unknown Page";
        double score = cand.finalScore();
        
        sb.append(String.format("[%d]\n", index));
        sb.append(String.format("Source: %s\n", name));
        sb.append(String.format("%s\n", pageStr));
        sb.append(String.format("Score: %.2f\n\n", score));
        sb.append(cand.chunk().getText()).append("\n\n");
    }
}
