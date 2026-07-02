package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Stores a single chunk's raw text + metadata in Postgres so it can be searched
 * with native full-text search (ts_rank over a tsvector). This is the lexical /
 * keyword half of hybrid retrieval — it runs alongside the Pinecone dense vector
 * search, and results from both are fused (see HybridRetrievalService).
 * <p>
 * The "vectorId" column lets us line this row up with the corresponding Pinecone
 * record (same chunk -> same id) so that when both retrieval paths surface the same
 * chunk, fusion can recognize it's the same document rather than treating it as two
 * separate hits.
 * <p>
 * IMPORTANT: "content" is intentionally NOT annotated with @Lob. With the
 * PostgreSQL JDBC driver, Hibernate maps a @Lob String to the Postgres "oid" type
 * (a large-object reference), not "text" — which then breaks to_tsvector(), since
 * there's no to_tsvector(text, oid) overload. Chunks here are capped at ~1000
 * characters anyway (see EmbeddingService.CHUNK_SIZE), nowhere near needing a
 * large-object column, so a plain TEXT column is both correct and sufficient.
 */
@Entity
@Table(name = "document_chunks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Matches the Document id used when upserting the same chunk into Pinecone. */
    @Column(name = "vector_id", nullable = false, unique = true)
    private String vectorId;

    /** Scopes the chunk to a single chat session so retrieval never leaks across sessions. */
    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** Plain Postgres TEXT column — no @Lob, see class-level note above. */
    @Column(name = "content", nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "source_name")
    private String sourceName;

    @Column(name = "source_type")
    private String sourceType;

    /**
     * SHA-256 hex digest of the full document's raw text (the same value on
     * every chunk belonging to one document - it identifies the document, not
     * the chunk). Computed once in EmbeddingService before chunking even
     * happens, so a byte-for-byte re-upload of the same file can be detected
     * and skipped with a single indexed lookup, before doing any chunking or
     * embedding work.
     * <p>
     * Deliberately whole-document, not chunk-level: it catches the common case
     * (the exact same file re-uploaded, possibly under a different filename),
     * not near-duplicates with small edits - that would need per-chunk
     * embedding-similarity comparison, which is heavier and was intentionally
     * not what was wanted here.
     */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "original_file_name")
    private String originalFileName;

    @Column(name = "enriched_file_name")
    private String enrichedFileName;

    @Column(name = "asset_classification")
    private String assetClassification;

    @Column(name = "asset_tags", columnDefinition = "text")
    private String assetTags;

    @Column(name = "chunk_index")
    private Integer chunkIndex;

    @Column(name = "total_chunks")
    private Integer totalChunks;

    /**
     * Public URL of the source image when this chunk's content is a Gemini Vision
     * description of an image — either a standalone IMAGE attachment, or an image
     * (chart/graph/photo/diagram) extracted from inside a PDF page. Null for plain
     * text chunks. Lets the frontend render the actual image next to a citation
     * instead of just the text description.
     */
    @Column(name = "image_url")
    private String imageUrl;

    /**
     * The URL pointing to the original source file (e.g., Cloudinary URL or Wikipedia URL).
     * Rendered as "View Source" link in citations.
     */
    @Column(name = "source_url", length = 1024)
    private String sourceUrl;

    @Column(name = "bounding_boxes", columnDefinition = "text")
    private String boundingBoxes;

    @Column(name = "page_number")
    private Integer page;

    @Column(name = "section_path", length = 1024)
    private String sectionPath;

    @Column(name = "heading")
    private String heading;

    @Column(name = "char_start")
    private Integer charStart;

    @Column(name = "char_end")
    private Integer charEnd;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
