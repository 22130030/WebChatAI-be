package com.appchat.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "chat_summaries",
        indexes = {
                @Index(
                        name = "idx_chat_summary_lookup",
                        columnList = "conversation_type,target,summary_mode,period_type,created_by"
                ),
                @Index(
                        name = "idx_chat_summary_last_message",
                        columnList = "last_message_id"
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "conversation_type", nullable = false, length = 30)
    private String conversationType;

    @Column(name = "target", nullable = false, length = 255)
    private String target;

    @Column(name = "summary_mode", nullable = false, length = 30)
    private String summaryMode;

    @Column(name = "period_type", nullable = false, length = 30)
    private String periodType;

    @Column(name = "from_time")
    private LocalDateTime fromTime;

    @Column(name = "to_time")
    private LocalDateTime toTime;

    @Column(name = "message_limit")
    private Integer messageLimit;

    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "last_message_id", length = 64)
    private String lastMessageId;

    @Column(name = "summary_text", nullable = false, columnDefinition = "TEXT")
    private String summaryText;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "ai_provider", length = 50)
    private String aiProvider;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();

        if (createdAt == null) {
            createdAt = now;
        }

        if (updatedAt == null) {
            updatedAt = now;
        }

        if (aiProvider == null || aiProvider.isBlank()) {
            aiProvider = "gemini";
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
