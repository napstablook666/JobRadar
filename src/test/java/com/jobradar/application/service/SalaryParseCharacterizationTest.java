package com.jobradar.application.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for salary parsing and the Liepin delivery salary gate.
 */
class SalaryParseCharacterizationTest {

    @Nested
    @DisplayName("BossService.parseSalary (canonical K-range form)")
    class Boss {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "面议", "薪资面议"})
        void returnsNullForBlankOrNegotiable(String raw) {
            assertNull(BossService.parseSalary(raw));
        }

        @Test
        void parsesSimpleRange() {
            BossService.SalaryInfo info = BossService.parseSalary("20-40K");
            assertNotNull(info);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
            assertEquals(12, info.months);
            assertEquals(30.0, info.medianK);
            assertEquals(Math.round(30.0 * 1000 * 12), info.annualTotal);
        }

        @Test
        void parsesSingleK() {
            BossService.SalaryInfo info = BossService.parseSalary("30K");
            assertNotNull(info);
            assertEquals(30, info.minK);
            assertEquals(30, info.maxK);
            assertEquals(12, info.months);
            assertEquals(30.0, info.medianK);
        }

        @Test
        void parsesRangeWithMonthsSuffix() {
            BossService.SalaryInfo info = BossService.parseSalary("35-65K·16薪");
            assertNotNull(info);
            assertEquals(35, info.minK);
            assertEquals(65, info.maxK);
            assertEquals(16, info.months);
            assertEquals(50.0, info.medianK);
            assertEquals(Math.round(50.0 * 1000 * 16), info.annualTotal);
        }

        @Test
        void parsesSingleWithMonthsSuffix() {
            BossService.SalaryInfo info = BossService.parseSalary("30K·15薪");
            assertNotNull(info);
            assertEquals(30, info.minK);
            assertEquals(30, info.maxK);
            assertEquals(15, info.months);
        }

        @Test
        void acceptsLowercaseK() {
            BossService.SalaryInfo info = BossService.parseSalary("20-40k");
            assertNotNull(info);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
        }

        @Test
        void looseParseStripsNoiseCharacters() {
            // cleaned path: non [0-9Kk-] removed, then range match
            BossService.SalaryInfo info = BossService.parseSalary("约20-40K左右");
            assertNotNull(info);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
        }

        @Test
        void unparsableReturnsNull() {
            assertNull(BossService.parseSalary("竞争力强"));
            assertNull(BossService.parseSalary("abc"));
        }
    }

    @Nested
    @DisplayName("ZhilianService.parseSalary — must stay behavior-compatible with Boss")
    class Zhilian {

        @CsvSource({
                "20-40K,20,40,12,30.0",
                "30K,30,30,12,30.0",
                "35-65K·16薪,35,65,16,50.0",
                "30K·15薪,30,30,15,30.0",
                "20-40k,20,40,12,30.0"
        })
        @ParameterizedTest
        void matchesBossOnSharedInputs(String raw, int minK, int maxK, int months, double medianK) {
            BossService.SalaryInfo boss = BossService.parseSalary(raw);
            ZhilianService.SalaryInfo zl = ZhilianService.parseSalary(raw);
            assertNotNull(boss);
            assertNotNull(zl);
            assertEquals(minK, boss.minK);
            assertEquals(maxK, boss.maxK);
            assertEquals(months, boss.months);
            assertEquals(medianK, boss.medianK);
            assertEquals(boss.minK, zl.minK);
            assertEquals(boss.maxK, zl.maxK);
            assertEquals(boss.months, zl.months);
            assertEquals(boss.medianK, zl.medianK);
            assertEquals(boss.annualTotal, zl.annualTotal);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"面议"})
        void nullCasesMatchBoss(String raw) {
            assertEquals(
                    BossService.parseSalary(raw) == null,
                    ZhilianService.parseSalary(raw) == null
            );
        }
    }

    @Nested
    @DisplayName("LiepinService.parseSalary")
    class Liepin {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"面议"})
        void returnsNullForBlankOrNegotiable(String raw) {
            assertNull(LiepinService.parseSalary(raw));
        }

        @Test
        void parsesRangeWithTwoKTokens() {
            // regex: (\\d+)[Kk].*?(\\d+)[Kk] — needs two K markers
            LiepinService.SalaryInfo info = LiepinService.parseSalary("20K-40K");
            assertNotNull(info);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
            assertEquals(12, info.months);
            assertEquals(30.0, info.medianK);
        }

        @Test
        void parsesSingleK() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("30K");
            assertNotNull(info);
            assertEquals(30, info.minK);
            assertEquals(30, info.maxK);
        }

        @Test
        void monthsFromSuffix() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("20K-40K·16薪");
            assertNotNull(info);
            assertEquals(16, info.months);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
        }

        @Test
        void parsesRangeWithKOnlyOnUpperBound() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("20-40K");
            assertNotNull(info);
            assertEquals(20, info.minK);
            assertEquals(40, info.maxK);
            assertNotNull(BossService.parseSalary("20-40K"));
        }

        @Test
        void parsesLowercaseKRange() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("6-10k");
            assertNotNull(info);
            assertEquals(6, info.minK);
            assertEquals(10, info.maxK);
        }

        @Test
        void convertsAnnualTenThousandSalaryToMonthlyK() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("10-15万");
            assertNotNull(info);
            assertEquals(8, info.minK);
            assertEquals(13, info.maxK);
        }

        @Test
        void keepsExplicitMonthlyTenThousandSalaryAsMonthlyK() {
            LiepinService.SalaryInfo info = LiepinService.parseSalary("10-15万/月");
            assertNotNull(info);
            assertEquals(100, info.minK);
            assertEquals(150, info.maxK);
        }

        @Test
        void convertsConfiguredMonthlyRangeToWebAnnualWan() {
            LiepinService.WebSalaryRange range = LiepinService.toWebAnnualSalaryRange("6$10");
            assertNotNull(range);
            assertEquals(7.2, range.minWan(), 0.0001);
            assertEquals(12.0, range.maxWan(), 0.0001);
            assertEquals("7.2", range.minText());
            assertEquals("12", range.maxText());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"6-10", "10$6", "6$", "$10", "abc"})
        void rejectsMissingOrInvalidWebRange(String raw) {
            assertNull(LiepinService.toWebAnnualSalaryRange(raw));
        }

        @ParameterizedTest
        @CsvSource({
                "6-10k, true",
                "8-9K, true",
                "9-14k, true",
                "9-14K·15薪, true",
                "5-8k, true",
                "10-15万, true",
                "30-50k, false"
        })
        void configuredRangeKeepsAnyOverlappingMonthlyRange(String salaryText, boolean allowed) {
            assertEquals(allowed,
                    LiepinService.checkSalaryInRange(salaryText, "6$10").allowed);
        }

        @Test
        void rejectsDailyNegotiableAndMissingSalary() {
            assertFalse(LiepinService.checkSalaryInRange("300-500元/天", "6$10").allowed);
            assertEquals("非月薪岗位",
                    LiepinService.checkSalaryInRange("300-500元/天", "6$10").reason);
            assertEquals("薪资面议",
                    LiepinService.checkSalaryInRange("面议", "6$10").reason);
            assertEquals("岗位薪资缺失",
                    LiepinService.checkSalaryInRange(null, "6$10").reason);
        }

        @Test
        void rejectsInvalidConfiguredRange() {
            assertEquals("薪资范围配置无法解析",
                    LiepinService.checkSalaryInRange("6-10k", "6-10").reason);
        }
    }
}
