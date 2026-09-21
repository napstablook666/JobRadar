package com.getjobs.worker.liepin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiepinPageProgressTest {

    @Test
    void nextStartPage_defaultsToOne() {
        assertEquals(1, LiepinPageProgress.nextStartPage(null));
        assertEquals(1, LiepinPageProgress.nextStartPage(0));
        assertEquals(1, LiepinPageProgress.nextStartPage(-3));
    }

    @Test
    void nextStartPage_resumesAfterCompleted() {
        assertEquals(4, LiepinPageProgress.nextStartPage(3));
        assertEquals(2, LiepinPageProgress.nextStartPage(1));
    }

    @Test
    void resolveStartPage_clampsOutOfRangeToFirstPage() {
        assertEquals(1, LiepinPageProgress.resolveStartPage(10, 3));
        assertEquals(3, LiepinPageProgress.resolveStartPage(3, 5));
        assertEquals(1, LiepinPageProgress.resolveStartPage(0, 5));
    }

    @Test
    void isProgressOutOfRange_onlyWhenPlannedBeyondMax() {
        assertTrue(LiepinPageProgress.isProgressOutOfRange(8, 3));
        assertFalse(LiepinPageProgress.isProgressOutOfRange(3, 5));
        assertFalse(LiepinPageProgress.isProgressOutOfRange(1, 1));
    }

    @Test
    void normalize_trimsNullSafe() {
        assertEquals("", LiepinPageProgress.normalize(null));
        assertEquals("放疗物理师", LiepinPageProgress.normalize(" 放疗物理师 "));
    }

}
