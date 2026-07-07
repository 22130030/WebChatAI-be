package com.appchat.backend.dto.admin;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AdminStatsDto {
    private long totalUsers;
    private long onlineUsers;
    private long totalRooms;
    private long totalMessages;
    private long newUsersToday;
    private long newMessagesToday;
    private long pendingConversations;
}
