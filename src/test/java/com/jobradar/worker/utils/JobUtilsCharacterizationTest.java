package com.jobradar.worker.utils;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Characterization tests for JobUtils before dead-code cleanup (e.g. main()).
 */
class JobUtilsCharacterizationTest {

    @Test
    void appendParam_skipsNullEmptyAndUnlimited() {
        assertEquals("", JobUtils.appendParam("city", null));
        assertEquals("", JobUtils.appendParam("city", ""));
        assertEquals("", JobUtils.appendParam("city", Constant.UNLIMITED_CODE));
        assertEquals("&city=101010100", JobUtils.appendParam("city", "101010100"));
    }

    @Test
    void appendListParam_emptyOrUnlimitedYieldsEmpty() {
        assertEquals("", JobUtils.appendListParam("salary", null));
        assertEquals("", JobUtils.appendListParam("salary", Collections.emptyList()));
        assertEquals("", JobUtils.appendListParam("salary", List.of(Constant.UNLIMITED_CODE)));
        assertEquals("", JobUtils.appendListParam("salary", List.of("10", Constant.UNLIMITED_CODE, "20")));
        assertEquals("&salary=10,20", JobUtils.appendListParam("salary", Arrays.asList("10", "20")));
    }

    @Test
    void formatDuration_fromDates() {
        Date start = new Date(0);
        Date end = new Date(3_661_000L); // 1h 1m 1s
        assertEquals("1时1分1秒", JobUtils.formatDuration(start, end));
    }

    @Test
    void formatDuration_fromSeconds() {
        assertEquals("0时0分0秒", JobUtils.formatDuration(0));
        assertEquals("1时1分1秒", JobUtils.formatDuration(3661));
        assertEquals("2时0分0秒", JobUtils.formatDuration(7200));
    }

    @Test
    void getRandomNumberInRange_boundsInclusive() {
        for (int i = 0; i < 50; i++) {
            int n = JobUtils.getRandomNumberInRange(3, 5);
            assertTrue(n >= 3 && n <= 5);
        }
        assertEquals(7, JobUtils.getRandomNumberInRange(7, 7));
    }

    @Test
    void getRandomNumberInRange_rejectsInvertedBounds() {
        assertThrows(IllegalArgumentException.class, () -> JobUtils.getRandomNumberInRange(5, 1));
    }
}
