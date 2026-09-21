package com.getjobs.worker.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordParserTest {
    @Test
    void parsesOneKeywordPerLineAndIgnoresBlankLines() {
        assertEquals(
                List.of("放疗设备现场技术支持", "放疗设备售后", "医疗设备售后"),
                KeywordParser.parse("\n放疗设备现场技术支持\r\n\r\n放疗设备售后\n医疗设备售后\n")
        );
    }

    @Test
    void preservesJsonItemsThatContainCommas() {
        assertEquals(
                List.of("医疗,设备", "医学影像设备工程师"),
                KeywordParser.parse("[\"医疗,设备\",\"医学影像设备工程师\"]")
        );
    }

    @Test
    void acceptsLegacyCommaAndBracketFormats() {
        assertEquals(List.of("Java", "后端", "Spring"), KeywordParser.parse("[Java，后端, Spring]"));
    }

}
