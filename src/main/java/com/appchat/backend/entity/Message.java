package com.appchat.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String type; // 'people' hoặc 'room'

    @Column(nullable = false)
    private String sender;

    @Column(nullable = false)
    private String receiver;

    @Lob
    private String content;

    @Builder.Default
    @Column(nullable = false)
    private Boolean recalled = false;

    @Builder.Default
    @Column(nullable = false)
    private Boolean edited = false;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}