package com.ispengya.nginx4j.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Date;
import java.util.Locale;

/**
 * RFC 1123 (IMF-fixdate) formatting and parsing for HTTP header dates such as {@code Date},
 * {@code Last-Modified}, {@code If-Modified-Since} and {@code If-Range}.
 *
 * <p>{@code DateTimeFormatter} is immutable and thread-safe, so the instances are shared across all
 * EventLoop threads. The obsolete RFC 850 format is not accepted on input because its two-digit year
 * is ambiguous and modern clients never send it.
 */
public final class HttpDates {

    private static final DateTimeFormatter IMF_FIXDATE = DateTimeFormatter
            .ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            .withZone(ZoneId.of("GMT"));

    /** asctime form still emitted by some old clients: {@code Sun Nov 6 08:49:37 1994}. */
    private static final DateTimeFormatter ASCTIME = DateTimeFormatter
            .ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)
            .withZone(ZoneId.of("GMT"));

    private HttpDates() {
    }

    /**
     * Formats an instant as an IMF-fixdate, e.g. {@code Sun, 06 Nov 1994 08:49:37 GMT}.
     *
     * @param date instant to format, never {@code null}
     * @return the formatted header value
     */
    public static String format(Date date) {
        return IMF_FIXDATE.format(date.toInstant());
    }

    /**
     * Parses an HTTP date header value, accepting IMF-fixdate and asctime.
     *
     * @param value raw header value, may be {@code null}
     * @return the parsed instant, or {@code null} when the value is absent or unparseable
     */
    public static Date parse(String value) {
        if (value == null) {
            return null;
        }
        // asctime pads the day with a space, so collapse whitespace before trying either pattern.
        String normalized = value.trim().replaceAll("\\s+", " ");
        Date parsed = parse(normalized, IMF_FIXDATE);
        return parsed != null ? parsed : parse(normalized, ASCTIME);
    }

    private static Date parse(String value, DateTimeFormatter formatter) {
        try {
            ZonedDateTime dateTime = ZonedDateTime.parse(value, formatter);
            Instant instant = dateTime.toInstant();
            return Date.from(instant);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}