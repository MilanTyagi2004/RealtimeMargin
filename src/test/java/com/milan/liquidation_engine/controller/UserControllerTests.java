package com.milan.liquidation_engine.controller;

import com.milan.liquidation_engine.entity.User;
import com.milan.liquidation_engine.repository.UserRepository;
import com.milan.liquidation_engine.service.AuditLogService;
import com.milan.liquidation_engine.service.MarginService;
import com.milan.liquidation_engine.service.RiskThresholdService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.milan.liquidation_engine.security.JwtUtil;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class UserControllerTests {

    private UserRepository userRepository;
    private MarginService marginService;
    private RiskThresholdService riskThresholdService;
    private AuditLogService auditLogService;
    private PasswordEncoder passwordEncoder;
    private JwtUtil jwtUtil;

    private UserController userController;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        marginService = mock(MarginService.class);
        riskThresholdService = mock(RiskThresholdService.class);
        auditLogService = mock(AuditLogService.class);
        passwordEncoder = new BCryptPasswordEncoder();
        jwtUtil = mock(JwtUtil.class);

        userController = new UserController(
                userRepository,
                marginService,
                riskThresholdService,
                auditLogService,
                passwordEncoder,
                jwtUtil
        );
    }

    @Test
    void testCreateUserEncryptsPassword() {
        User inputUser = User.builder()
                .username("milan")
                .password("rawPassword123")
                .balance(BigDecimal.TEN)
                .build();

        User savedUser = User.builder()
                .id(1L)
                .username("milan")
                .password("$2a$10$encodedPasswordHashHere...")
                .balance(BigDecimal.TEN)
                .build();

        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User userToSave = invocation.getArgument(0);
            // Verify it was encoded using bcrypt before saving
            assertTrue(passwordEncoder.matches("rawPassword123", userToSave.getPassword()));
            return savedUser;
        });

        ResponseEntity<User> response = userController.createUser(inputUser);

        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertEquals(savedUser, response.getBody());

        verify(userRepository, times(1)).save(any(User.class));
        verify(auditLogService, times(1)).logEvent(
                eq(1L),
                eq("USER_CREATED"),
                eq("SUCCESS"),
                contains("milan")
        );
    }
}
