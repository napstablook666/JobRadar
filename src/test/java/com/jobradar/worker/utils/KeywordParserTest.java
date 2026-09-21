package com.jobradar.worker.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
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

    @Test
    void repairsRepeatedUtf8MojibakeInLegacyLines() {
        String raw = doubleMojibake("放射技师") + "\n" + doubleMojibake("医疗设备售后");

        assertEquals(List.of("放射技师", "医疗设备售后"), KeywordParser.parse(raw));
        assertEquals("[\"放射技师\",\"医疗设备售后\"]", KeywordParser.toStorage(raw));
    }

    @Test
    void leavesCanonicalTextUntouched() {
        assertEquals("放射技师", KeywordParser.repairUtf8Mojibake("放射技师"));
        assertEquals("plain", KeywordParser.repairUtf8Mojibake("plain"));
    }

    private static String doubleMojibake(String value) {
        String once = new String(value.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        return new String(once.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }
}
