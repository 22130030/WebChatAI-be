package com.appchat.backend.service;

import com.appchat.backend.entity.Friendship;
import com.appchat.backend.repository.FriendshipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class FriendshipService {

    private final FriendshipRepository friendshipRepository;

    public Friendship createFriendship(String userA, String userB) {
        Pair pair = normalizePair(userA, userB);

        return friendshipRepository.findByUserLowAndUserHigh(pair.userLow(), pair.userHigh())
                .orElseGet(() -> friendshipRepository.save(
                        Friendship.builder()
                                .userLow(pair.userLow())
                                .userHigh(pair.userHigh())
                                .build()
                ));
    }

    public void removeFriendship(String userA, String userB) {
        Pair pair = normalizePair(userA, userB);
        friendshipRepository.findByUserLowAndUserHigh(pair.userLow(), pair.userHigh())
                .ifPresent(friendshipRepository::delete);
    }

    public boolean areFriends(String userA, String userB) {
        Pair pair = normalizePair(userA, userB);
        return friendshipRepository.existsByUserLowAndUserHigh(pair.userLow(), pair.userHigh());
    }

    public List<Friendship> findFriendships(String username) {
        if (username == null || username.isBlank()) {
            return List.of();
        }

        return friendshipRepository.findByUsername(username);
    }

    public String otherUser(Friendship friendship, String username) {
        if (friendship == null || username == null) {
            return null;
        }

        return username.equals(friendship.getUserLow())
                ? friendship.getUserHigh()
                : friendship.getUserLow();
    }

    private Pair normalizePair(String userA, String userB) {
        if (userA == null || userA.isBlank() || userB == null || userB.isBlank() || userA.equals(userB)) {
            throw new IllegalArgumentException("Cặp người dùng không hợp lệ.");
        }

        List<String> users = List.of(userA.trim(), userB.trim())
                .stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        return new Pair(users.get(0), users.get(1));
    }

    private record Pair(String userLow, String userHigh) {
    }
}
