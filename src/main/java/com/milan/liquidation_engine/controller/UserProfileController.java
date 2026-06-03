package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.entity.UserProfile;
import com.milan.liquidation_engine.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/profiles")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserProfileService service;

    @PostMapping
    public UserProfile createProfile(
            @RequestBody UserProfile profile) {

        return service.saveProfile(profile);
    }
}