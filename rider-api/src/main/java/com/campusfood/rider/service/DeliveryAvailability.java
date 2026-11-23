package com.campusfood.rider.service;

import com.campusfood.domain.BusinessStates.Availability;

import java.time.LocalTime;
import java.util.regex.Pattern;

final class DeliveryAvailability {
    private static final Pattern RANGE = Pattern.compile("^([0-2]\\d:[0-5]\\d)-([0-2]\\d:[0-5]\\d)$");

    private DeliveryAvailability() {
    }

    static String validate(Availability availability, String value) {
        String time = value == null ? "" : value.trim();
        if (availability == Availability.CUSTOM ? parse(time) == null : !time.isEmpty()) {
            throw Inputs.invalid("自定义送餐时间须填写为 HH:mm-HH:mm");
        }
        return time;
    }

    static boolean includes(Availability availability, String value, LocalTime now) {
        return switch (availability) {
            case MIDDAY -> includes("11:00-13:00", now);
            case EVENING -> includes("17:00-19:00", now);
            case MIDDAY_AND_EVENING -> includes("11:00-13:00", now) || includes("17:00-19:00", now);
            case CUSTOM -> includes(value, now);
        };
    }

    private static boolean includes(String value, LocalTime now) {
        var range = parse(value);
        return range != null && !now.isBefore(range.start()) && now.isBefore(range.end());
    }

    private static TimeRange parse(String value) {
        if (value == null) return null;
        var match = RANGE.matcher(value);
        if (!match.matches()) return null;
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
