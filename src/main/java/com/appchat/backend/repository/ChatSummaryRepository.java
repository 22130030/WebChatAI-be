package com.appchat.backend.repository;

import com.appchat.backend.entity.ChatSummary;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface ChatSummaryRepository extends JpaRepository<ChatSummary, Long> {

    @Query("""
            SELECT s
            FROM ChatSummary s
            WHERE s.conversationType = :conversationType
              AND s.target = :target
              AND s.summaryMode = :summaryMode
              AND s.periodType = :periodType
              AND s.createdBy = :createdBy
              AND s.messageLimit = :messageLimit
              AND s.messageCount = :messageCount
              AND s.lastMessageId = :lastMessageId
              AND (
                    (:fromTime IS NULL AND s.fromTime IS NULL)
                    OR s.fromTime = :fromTime
                  )
              AND (
                    (:toTime IS NULL AND s.toTime IS NULL)
                    OR s.toTime = :toTime
                  )
            ORDER BY s.updatedAt DESC
            """)
    List<ChatSummary> findReusableSummary(
            String conversationType,
            String target,
            String summaryMode,
            String periodType,
            String createdBy,
            Integer messageLimit,
            Integer messageCount,
            String lastMessageId,
            LocalDateTime fromTime,
            LocalDateTime toTime,
            Pageable pageable
    );
}
