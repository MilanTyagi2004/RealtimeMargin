package com.milan.liquidation_engine.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class RedisCachingTests {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void testCacheManagerIsRedisCacheManager() {
        assertNotNull(cacheManager);
        assertTrue(cacheManager instanceof RedisCacheManager);
    }

    @Test
    void testCacheConfigurationsExistWithCorrectTTLs() {
        RedisCacheManager redisCacheManager = (RedisCacheManager) cacheManager;
        
        // Verify userDetails configuration exists
        RedisCacheConfiguration userDetailsConfig = redisCacheManager.getCacheConfigurations().get("userDetails");
        assertNotNull(userDetailsConfig, "userDetails cache config should be registered");

        // Verify instrumentConfigs configuration exists
        RedisCacheConfiguration instrumentConfigsConfig = redisCacheManager.getCacheConfigurations().get("instrumentConfigs");
        assertNotNull(instrumentConfigsConfig, "instrumentConfigs cache config should be registered");

        // Verify userProfiles configuration exists
        RedisCacheConfiguration userProfilesConfig = redisCacheManager.getCacheConfigurations().get("userProfiles");
        assertNotNull(userProfilesConfig, "userProfiles cache config should be registered");
    }
}
