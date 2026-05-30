package com.appchat.backend.repository;

import com.appchat.backend.entity.GroupTheme;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
public interface GroupThemeRepository extends JpaRepository<GroupTheme, Long> {
    Optional<GroupTheme> findByGroupName(String groupName);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = "UPDATE group_themes SET group_name = :newName WHERE group_name = :oldName", nativeQuery = true)
    int renameGroupTheme(@Param("oldName") String oldName, @Param("newName") String newName);

    @Transactional
    void deleteByGroupName(String groupName);

}
