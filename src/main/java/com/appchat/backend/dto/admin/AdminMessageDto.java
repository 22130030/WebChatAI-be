package com.appchat.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminMessageDto {
    private String id;
    private String type;
    private String sender;
    private String receiver;
    private String content;
    private Boolean recalled;
    private Boolean edited;
    private String status;
    private LocalDateTime createdAt;
}
