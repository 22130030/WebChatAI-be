package com.appchat.backend.repository;

import com.appchat.backend.entity.ChatTheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ChatThemeRepository extends JpaRepository<ChatTheme, Long> {
    @Query("SELECT c FROM ChatTheme c WHERE (c.user1 = :u1 AND c.user2 = :u2) OR (c.user1 = :u2 AND c.user2 = :u1)")
    Optional<ChatTheme> findThemeBetweenUsers(@Param("u1") String u1, @Param("u2") String u2);
}
