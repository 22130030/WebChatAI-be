package com.appchat.backend.repository;

import com.appchat.backend.entity.Friendship;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

    Optional<Friendship> findByUserLowAndUserHigh(String userLow, String userHigh);

    boolean existsByUserLowAndUserHigh(String userLow, String userHigh);

    @Query("""
        SELECT f FROM Friendship f
        WHERE f.userLow = :username OR f.userHigh = :username
    """)
    List<Friendship> findByUsername(@Param("username") String username);
}
