package com.appchat.backend.entity;

import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Document(collection = "message_reactions")
@CompoundIndex(name = "message_reaction_unique_idx", def = "{'messageId': 1, 'username': 1}", unique = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageReaction {

    @Id
    private String id;

    @Indexed
    private String messageId;

    @Indexed
    private String username;

    private String reaction;

    @CreatedDate
    private LocalDateTime createdAt;
}
