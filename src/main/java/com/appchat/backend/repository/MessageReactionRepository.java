package com.appchat.backend.repository;

import com.appchat.backend.entity.MessageReaction;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface MessageReactionRepository extends MongoRepository<MessageReaction, String> {

    List<MessageReaction> findByMessageId(String messageId);

    Optional<MessageReaction> findByMessageIdAndUsername(String messageId, String username);
}
