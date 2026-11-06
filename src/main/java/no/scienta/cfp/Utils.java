package no.scienta.cfp;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Misc helpers.
 */
public class Utils {

    public static final String DB_DATE_TIME = "yyyy-MM-dd HH:mm:ss";

    public static final int MAX_TALKS = 5;

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public static String today() {
        return new SimpleDateFormat("dd.MM.yyyy").format(new Date());
    }

    public static String now() {
        SimpleDateFormat f = new SimpleDateFormat(DB_DATE_TIME);
        f.setTimeZone(UTC);
        return f.format(new Date());
    }

    public static Date parse(String s) {
        try {
            SimpleDateFormat f = new SimpleDateFormat(DB_DATE_TIME);
            f.setTimeZone(UTC);
            return f.parse(s);
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Epoch millis to a date-only java.sql.Date (UTC day).
     */
    public static java.sql.Date toSqlDate(long millis) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd");
        f.setTimeZone(UTC);
        return java.sql.Date.valueOf(f.format(new Date(millis)));
    }

    /**
     * "11/4/26, 1:05 PM", what the reviewer UI shows. An explicit pattern, not the locale's SHORT style: from Java 20
     * the CLDR data puts a narrow no-break space (U+202F) before AM/PM, and the Ratings spec wants an ordinary space.
     */
    public static String usShort(Date d) {
        DateFormat f = new SimpleDateFormat("M/d/yy, h:mm a", Locale.US);
        f.setTimeZone(UTC);
        return f.format(d);
    }

    /**
     * "4. nov. 2026" for the committee spreadsheet.
     */
    public static String norwegianDate(Date d) {
        DateFormat f = DateFormat.getDateInstance(DateFormat.MEDIUM, new Locale("nb", "NO"));
        f.setTimeZone(TimeZone.getTimeZone("Europe/Oslo"));
        return f.format(d);
    }

    /**
     * Makes a value safe for the semicolon CSV export.
     */
    public static String csv(String s) {
        if (s == null) {
            return "";
        }
        if (s.contains(";") || s.contains("\"")) {
            return "\"" + s.replace("\"", "'") + "\"";
        }
        return s;
    }

    // local@domain.tld: exactly one @, no whitespace, a non-empty local part, and a domain of dot-separated,
    // non-empty labels with at least one dot. Deliberately loose otherwise (plus tags, unicode, capitals are fine).
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$");

    public static boolean isValidEmail(String email) {
        return email != null && EMAIL.matcher(email).matches();
    }

    public static boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    public static boolean isNumeric(String s) {
        if (isEmpty(s)) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!Character.isDigit(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A talk's average score as the Ratings spec shows it: one decimal, halves rounded up (4.25 -> 4.3).
     */
    public static double averageRating(long total, int count) {
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Rounds a rating average to one decimal, cutting off. Only /admin/stats uses it; talks use averageRating.
     */
    public static double roundRating(double avg) {
        return Math.floor(avg * 10) / 10.0;
    }

    public static String capitalize(String s) {
        if (isEmpty(s)) {
            return s;
        }
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    // public static String slug(String title) {
    //     return title.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    // }
}
