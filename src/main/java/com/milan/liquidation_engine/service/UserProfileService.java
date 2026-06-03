package com.milan.liquidation_engine.service;

import com.milan.liquidation_engine.entity.UserProfile;
import com.milan.liquidation_engine.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserProfileRepository repository;

    public UserProfile saveProfile(UserProfile profile) {
        return repository.save(profile);
    }
}