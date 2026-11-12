package com.campusfood.rider.service;

import java.time.LocalTime;
import java.util.regex.Pattern;

final class DeliveryAvailability {
    private static final Pattern RANGE = Pattern.compile("^(?:午间 |晚间 )?([0-2]\\d:[0-5]\\d)\\s*[-–—至]\\s*([0-2]\\d:[0-5]\\d)$");

    private DeliveryAvailability() {
    }

    static String validate(String value) {
        if ("午间与晚间".equals(value) || parse(value) != null) {
            return value;
        }
        throw Inputs.invalid("可送餐时间须填写为 HH:mm-HH:mm，例如 14:00-16:00");
    }

    static boolean includes(String value, LocalTime now) {
        if ("午间与晚间".equals(value)) {
            return includes("11:00-13:00", now) || includes("17:00-19:00", now);
        }
        var range = parse(value);
        return range != null && !now.isBefore(range.start()) && now.isBefore(range.end());
    }

    private static TimeRange parse(String value) {
        if (value == null) {
            return null;
        }
        var match = RANGE.matcher(value);
        if (!match.matches()) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(match.group(1));
            LocalTime end = LocalTime.parse(match.group(2));
            return start.isBefore(end) ? new TimeRange(start, end) : null;
        } catch (java.time.DateTimeException exception) {
            return null;
        }
    }

    private record TimeRange(LocalTime start, LocalTime end) {
    }
}
