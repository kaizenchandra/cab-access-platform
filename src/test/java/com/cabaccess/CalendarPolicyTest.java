package com.cabaccess;

import static org.junit.jupiter.api.Assertions.*;
import com.cabaccess.plan.CalendarPolicy;
import com.cabaccess.plan.CalendarPolicy.Duration;
import java.time.*;
import org.junit.jupiter.api.Test;

class CalendarPolicyTest {
  @Test void monthEndAndLeapYear(){assertEquals(Instant.parse("2024-02-29T12:00:00Z"),CalendarPolicy.end(Instant.parse("2024-01-31T12:00:00Z"),"UTC",Duration.MONTHLY));assertEquals(Instant.parse("2025-02-28T12:00:00Z"),CalendarPolicy.end(Instant.parse("2024-02-29T12:00:00Z"),"UTC",Duration.YEARLY));assertEquals(Instant.parse("2024-04-30T12:00:00Z"),CalendarPolicy.end(Instant.parse("2024-01-31T12:00:00Z"),"UTC",Duration.QUARTERLY));}
  @Test void calendarDaysAcrossDstGapAndOverlap(){var start=ZonedDateTime.of(2024,3,3,2,30,0,0,ZoneId.of("America/New_York")).toInstant();var end=CalendarPolicy.end(start,"America/New_York",Duration.WEEKLY);assertEquals(ZonedDateTime.of(2024,3,10,3,30,0,0,ZoneId.of("America/New_York")).toInstant(),end);var overlap=ZonedDateTime.of(2024,10,27,1,30,0,0,ZoneId.of("America/New_York")).toInstant();assertEquals(Instant.parse("2024-11-03T05:30:00Z"),CalendarPolicy.end(overlap,"America/New_York",Duration.WEEKLY));}
  @Test void exactStartInclusiveAndEndExclusive(){var start=Instant.parse("2024-01-01T00:00:00Z");var end=start.plusSeconds(10);assertTrue(CalendarPolicy.contains(start,end,start));assertTrue(CalendarPolicy.contains(start,end,end.minusNanos(1)));assertFalse(CalendarPolicy.contains(start,end,end));assertFalse(CalendarPolicy.contains(start,end,start.minusNanos(1)));}
  @Test void facilityTimezoneChangesUtcBoundary(){var start=Instant.parse("2024-03-03T17:00:00Z");assertEquals(Instant.parse("2024-03-10T16:00:00Z"),CalendarPolicy.end(start,"America/New_York",Duration.WEEKLY));assertEquals(Instant.parse("2024-03-10T17:00:00Z"),CalendarPolicy.end(start,"Asia/Kolkata",Duration.WEEKLY));}
}
