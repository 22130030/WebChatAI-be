package com.appchat.backend.dto;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatSummaryDto {

    private String type;
    private String target;

    private String period;
    private String mode;

    private LocalDateTime fromTime;
    private LocalDateTime toTime;

    private Integer limit;
    private Integer messageCount;
    private String lastMessageId;

    private String summary;

    private Boolean cached;
    private String aiProvider;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
