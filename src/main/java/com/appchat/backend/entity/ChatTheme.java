package com.appchat.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "chat_themes", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user1", "user2"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatTheme {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String user1;

    @Column(nullable = false)
    private String user2;

    @Builder.Default
    @Column(name = "theme_id", length = 100)
    private String themeId = "DEFAULT";

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
