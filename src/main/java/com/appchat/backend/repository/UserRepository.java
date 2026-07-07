package com.appchat.backend.repository;

import com.appchat.backend.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);

    long countByStatus(String status);

    long countByCreatedAtAfter(LocalDateTime time);

    @Query("SELECT u FROM User u WHERE " +
           "(:search IS NULL OR LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(u.displayName) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<User> findBySearchQuery(@Param("search") String search, Pageable pageable);
}
