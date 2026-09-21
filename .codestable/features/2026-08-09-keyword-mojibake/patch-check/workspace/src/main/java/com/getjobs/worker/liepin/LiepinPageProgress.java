package com.getjobs.worker.liepin;

import com.getjobs.worker.utils.KeywordParser;

public final class LiepinPageProgress {
    private LiepinPageProgress() {
    }

    public static String normalize(String value) {
        return KeywordParser.repairUtf8Mojibake(value == null ? "" : value).trim();
    }

    public static int nextStartPage(Integer lastCompletedPage) {
        if (lastCompletedPage == null || lastCompletedPage < 1) {
            return 1;
        }
        return lastCompletedPage + 1;
    }

    public static int resolveStartPage(int plannedStartPage, int maxPage) {
        int start = plannedStartPage < 1 ? 1 : plannedStartPage;
        if (maxPage < 1) {
            return 1;
        }
        if (start > maxPage) {
            return 1;
        }
        return start;
    }

    public static boolean isProgressOutOfRange(int plannedStartPage, int maxPage) {
        return plannedStartPage > 1 && maxPage >= 1 && plannedStartPage > maxPage;
    }
}
