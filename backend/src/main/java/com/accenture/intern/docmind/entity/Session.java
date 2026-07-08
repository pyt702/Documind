package com.accenture.intern.docmind.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.*;

@Entity
@Table(name = "sessions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long sessionId;

    @ManyToOne
    private User user;

    private String title;

    private Boolean archived = false;

    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<Message> messages = new ArrayList<>();

    /**
     * All attachments uploaded during this session.
     *
     * IMPORTANT:
     * Do NOT cascade REMOVE and do NOT use orphanRemoval here. Attachments must
     * remain in the attachments table even after their original session is deleted.
     * SessionService.deleteSession(...) detaches them by setting session_id = null.
     */
    @OneToMany(mappedBy = "session")
    @Builder.Default
    private List<Attachment> attachments = new ArrayList<>();

    /**
     * View-attachment join records for this session.
     * Cascade ALL + orphanRemoval ensures rows in view_attachments are cleaned
     * up automatically when the session is deleted.
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ViewAttachment> viewAttachments = new ArrayList<>();

    /**
     * Conversation timelines for this session.
     * Cascade ALL + orphanRemoval ensures these are cleaned up automatically
     * when the session is deleted.
     */
    @OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ConversationTimeline> conversationTimelines = new ArrayList<>();

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
