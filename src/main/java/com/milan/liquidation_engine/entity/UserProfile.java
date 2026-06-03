package com.milan.liquidation_engine.entity;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Data
@Document(collection = "user_profiles")
public class UserProfile {

    @Id
    private String id;

    private Long userId;

    private String email;

    private String profileImage;

    private String bio;

    private List<String> skills;
}