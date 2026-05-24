package com.appchat.backend.repository;

import com.appchat.backend.entity.GroupTheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GroupThemeRepository extends JpaRepository<GroupTheme, Long> {
    Optional<GroupTheme> findByGroupName(String groupName);
}
