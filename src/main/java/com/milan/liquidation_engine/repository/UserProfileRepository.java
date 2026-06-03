package com.milan.liquidation_engine.repository;

import com.milan.liquidation_engine.entity.UserProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserProfileRepository
        extends MongoRepository<UserProfile, String> {

    Optional<UserProfile> findByUserId(Long userId);
}