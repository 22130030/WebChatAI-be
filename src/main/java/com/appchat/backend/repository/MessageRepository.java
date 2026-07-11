package com.appchat.backend.repository;

import com.appchat.backend.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface MessageRepository extends MongoRepository<Message, String> {

    @Query(value = "{ 'type': 'people', '$or': [ { 'sender': ?0, 'receiver': ?1 }, { 'sender': ?1, 'receiver': ?0 } ] }", sort = "{ 'createdAt': -1 }")
    List<Message> findPeopleMessages(
            String userA,
            String userB,
            Pageable pageable
    );

    @Query(value = "{ 'type': 'room', 'receiver': ?0 }", sort = "{ 'createdAt': -1 }")
    List<Message> findRoomMessages(
            String roomName,
            Pageable pageable
    );

    @Query(value = "{ 'type': 'people', '$or': [ { 'sender': ?0, 'receiver': ?1 }, { 'sender': ?1, 'receiver': ?0 } ], 'createdAt': { '$gte': ?2, '$lt': ?3 } }", sort = "{ 'createdAt': -1 }")
    List<Message> findPeopleMessagesBetween(
            String userA,
            String userB,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    );

    @Query(value = "{ 'type': 'room', 'receiver': ?0, 'createdAt': { '$gte': ?1, '$lt': ?2 } }", sort = "{ 'createdAt': -1 }")
    List<Message> findRoomMessagesBetween(
            String roomName,
            Instant fromTime,
            Instant toTime,
            Pageable pageable
    );

    Optional<Message> findTopByTypeAndSenderAndReceiverOrTypeAndSenderAndReceiverOrderByCreatedAtDesc(
            String type1,
            String sender1,
            String receiver1,
            String type2,
            String sender2,
            String receiver2
    );

    Optional<Message> findTopByTypeAndReceiverOrderByCreatedAtDesc(
            String type,
            String receiver
    );

    @Query(value = "{ 'type': 'people', '$or': [ { 'sender': ?0, 'receiver': ?1 }, { 'sender': ?1, 'receiver': ?0 } ] }", sort = "{ 'createdAt': -1 }")
    List<Message> findLastPeopleMessage(
            String user1,
            String user2,
            Pageable pageable
    );

    List<Message> findByTypeAndReceiver(String type, String receiver);

    long deleteByTypeAndReceiver(String type, String receiver);

    long countByCreatedAtAfter(java.time.LocalDateTime time);

    @Query(value = "{ $and: [ " +
            "{ $or: [ { 'type': ?0 }, { $expr: { $eq: [?0, null] } } ] }, " +
            "{ $or: [ { 'sender': ?1 }, { $expr: { $eq: [?1, null] } } ] }, " +
            "{ $or: [ { 'receiver': ?2 }, { $expr: { $eq: [?2, null] } } ] } " +
            "] }")
    List<Message> adminSearch(
            String type,
            String sender,
            String receiver,
            Pageable pageable
    );

    @Query(value = "{ $and: [ " +
            "{ $or: [ { 'type': ?0 }, { $expr: { $eq: [?0, null] } } ] }, " +
            "{ $or: [ { 'sender': ?1 }, { $expr: { $eq: [?1, null] } } ] }, " +
            "{ $or: [ { 'receiver': ?2 }, { $expr: { $eq: [?2, null] } } ] } " +
            "] }", count = true)
    long adminSearchCount(
            String type,
            String sender,
            String receiver
    );

}