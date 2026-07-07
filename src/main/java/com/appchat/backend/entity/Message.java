package com.appchat.backend.entity;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "messages")
@CompoundIndex(name = "people_messages_idx", def = "{'type': 1, 'sender': 1, 'receiver': 1, 'createdAt': -1}")
@CompoundIndex(name = "room_messages_idx", def = "{'type': 1, 'receiver': 1, 'createdAt': -1}")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    private String id;

    @Indexed
    private String type; // 'people' hoặc 'room'

    @Indexed
    private String sender;

    @Indexed
    private String receiver;

    private String content;

    @Builder.Default
    private Boolean recalled = false;

    @Builder.Default
    private Boolean edited = false;

    @Builder.Default
    private String status = "SENT";

    private LocalDateTime deliveredAt;

    private LocalDateTime readAt;

    @CreatedDate
    @Indexed
    private LocalDateTime createdAt;
}
