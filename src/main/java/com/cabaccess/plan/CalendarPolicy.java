package com.cabaccess.plan;

import java.time.Instant;
import java.time.ZoneId;

public final class CalendarPolicy {
    private CalendarPolicy() {
    }

    public static Instant end(Instant start, String timezone, Duration duration) {
        var local = start.atZone(ZoneId.of(timezone));
        return switch (duration) {
            case WEEKLY -> local.plusDays(7).toInstant();
            case MONTHLY -> local.plusMonths(1).toInstant();
            case QUARTERLY -> local.plusMonths(3).toInstant();
            case YEARLY -> local.plusYears(1).toInstant();
        };
    }

    public static boolean contains(Instant start, Instant end, Instant at) {
        return !at.isBefore(start) && at.isBefore(end);
    }

    public enum Duration {WEEKLY, MONTHLY, QUARTERLY, YEARLY}
}
