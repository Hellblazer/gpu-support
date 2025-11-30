package com.hellblazer.luciferase.resource;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ResourceConfiguration
 */
public class ResourceConfigurationTest {
    
    @Test
    void testDefaultConfiguration() {
        var config = ResourceConfiguration.defaultConfig();
        
        assertNotNull(config);
        assertEquals(512L * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.9f, config.getHighWaterMark());
        assertEquals(0.7f, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LRU, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(5), config.getMaxIdleTime());
        assertEquals(10000, config.getMaxResourceCount());
    }
    
    @Test
    void testMinimalConfiguration() {
        var config = ResourceConfiguration.minimalConfig();
        
        assertNotNull(config);
        assertEquals(64L * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.95f, config.getHighWaterMark());
        assertEquals(0.8f, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LRU, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(1), config.getMaxIdleTime());
        assertEquals(1000, config.getMaxResourceCount());
    }
    
    @Test
    void testProductionConfiguration() {
        var config = ResourceConfiguration.productionConfig();
        
        assertNotNull(config);
        assertEquals(2L * 1024 * 1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.85f, config.getHighWaterMark());
        assertEquals(0.6f, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.HYBRID, config.getEvictionPolicy());
        assertEquals(Duration.ofMinutes(15), config.getMaxIdleTime());
        assertEquals(50000, config.getMaxResourceCount());
    }
    
    @Test
    void testBuilderConfiguration() {
        var config = new ResourceConfiguration.Builder()
            .withMaxPoolSize(1024 * 1024)
            .withHighWaterMark(0.95f)
            .withLowWaterMark(0.5f)
            .withEvictionPolicy(ResourceConfiguration.EvictionPolicy.LFU)
            .withMaxIdleTime(Duration.ofSeconds(30))
            .withMaxResourceCount(500)
            .build();
        
        assertNotNull(config);
        assertEquals(1024 * 1024, config.getMaxPoolSizeBytes());
        assertEquals(0.95f, config.getHighWaterMark());
        assertEquals(0.5f, config.getLowWaterMark());
        assertEquals(ResourceConfiguration.EvictionPolicy.LFU, config.getEvictionPolicy());
        assertEquals(Duration.ofSeconds(30), config.getMaxIdleTime());
        assertEquals(500, config.getMaxResourceCount());
    }
    
    @Test
    void testInvalidWatermarks() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withHighWaterMark(0.5f)
                .withLowWaterMark(0.8f) // Low > High, should fail
                .build();
        });
    }
    
    @Test
    void testNegativePoolSize() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withMaxPoolSize(-1)
                .build();
        });
    }
    
    @Test
    void testWatermarkBounds() {
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withHighWaterMark(1.5f) // > 1.0f, should fail
                .build();
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new ResourceConfiguration.Builder()
                .withLowWaterMark(-0.1f) // < 0.0f, should fail
                .build();
        });
    }
}