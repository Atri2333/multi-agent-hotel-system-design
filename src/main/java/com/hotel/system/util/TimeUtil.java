package com.hotel.system.util;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

public final class TimeUtil {
    private TimeUtil() {
    }

    public static String nowIso() {
        return OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
