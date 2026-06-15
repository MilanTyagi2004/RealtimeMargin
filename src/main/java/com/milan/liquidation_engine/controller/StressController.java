package com.milan.liquidation_engine.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/v1/stress")
@RequiredArgsConstructor
public class StressController {

    private final StringRedisTemplate redisTemplate;

    @PostMapping
    public ResponseEntity<Map<String, String>> setStressLevel(@RequestParam String level) {
        String upperLevel = level.toUpperCase();
        if (!"NORMAL".equals(upperLevel) && !"HIGH_VOLATILITY".equals(upperLevel) && !"EXTREME_PANIC".equals(upperLevel)) {
            throw new IllegalArgumentException("Invalid stress level. Must be NORMAL, HIGH_VOLATILITY, or EXTREME_PANIC.");
        }
        redisTemplate.opsForValue().set("marketStressLevel", upperLevel);

        Map<String, String> response = new HashMap<>();
        response.put("status", "SUCCESS");
        response.put("marketStressLevel", upperLevel);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<Map<String, String>> getStressLevel() {
        String level = redisTemplate.opsForValue().get("marketStressLevel");
        if (level == null) {
            level = "NORMAL";
        }

        Map<String, String> response = new HashMap<>();
        response.put("marketStressLevel", level);
        return ResponseEntity.ok(response);
    }
}
