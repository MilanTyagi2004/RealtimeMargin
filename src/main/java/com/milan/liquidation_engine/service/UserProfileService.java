package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.UserProfile;
import com.milan.liquidation_engine.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository repository;

    @CachePut(value = "userProfiles", key = "#profile.userId")
    public UserProfile saveProfile(UserProfile profile) {
        return repository.save(profile);
    }

    @Cacheable(value = "userProfiles", key = "#userId")
    public UserProfile getProfileByUserId(Long userId) {
        return repository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("User profile not found for user ID: " + userId));
    }
}