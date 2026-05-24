package com.appchat.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "group_themes")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GroupTheme {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "group_name", nullable = false, unique = true)
    private String groupName;

    @Builder.Default
    @Column(name = "theme_id", length = 100)
    private String themeId = "DEFAULT";

    @Column(name = "last_changed_by")
    private String lastChangedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
