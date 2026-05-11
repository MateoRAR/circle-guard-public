package com.circleguard.dashboard.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class KAnonymityFilterTest {

    private KAnonymityFilter filter;

    @BeforeEach
    void setUp() {
        filter = new KAnonymityFilter();
    }

    @Test
    void apply_masksEntireResultWhenTotalUsersBelowDefaultK() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", 3L);
        stats.put("suspectCount", 1L);
        stats.put("department", "Engineering");

        Map<String, Object> result = filter.apply(stats);

        assertEquals("<5", result.get("totalUsers"));
        assertEquals("Insufficient data for privacy", result.get("note"));
        assertEquals("Engineering", result.get("department"));
        assertFalse(result.containsKey("suspectCount"));
    }

    @Test
    void apply_doesNotMaskWhenTotalUsersAtOrAboveDefaultK() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", 10L);
        stats.put("suspectCount", 8L);

        Map<String, Object> result = filter.apply(stats);

        assertEquals(10L, result.get("totalUsers"));
        assertEquals(8L, result.get("suspectCount"));
        assertFalse(result.containsKey("note"));
    }

    @Test
    void apply_masksIndividualCountFieldsBelowDefaultK() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", 100L);
        stats.put("suspectCount", 2L);
        stats.put("confirmedCount", 50L);

        Map<String, Object> result = filter.apply(stats);

        assertEquals("<5", result.get("suspectCount"));
        assertEquals(50L, result.get("confirmedCount"));
        assertEquals(100L, result.get("totalUsers"));
    }

    @Test
    void apply_withCustomK_masksWhenTotalUsersBelowThreshold() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalUsers", 8L);
        stats.put("suspectCount", 5L);

        Map<String, Object> result = filter.apply(stats, 10);

        assertEquals("<10", result.get("totalUsers"));
        assertFalse(result.containsKey("suspectCount"));
    }

    @Test
    void apply_nullInput_returnsEmptyMap() {
        assertTrue(filter.apply(null).isEmpty());
    }
}
