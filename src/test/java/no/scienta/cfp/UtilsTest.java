package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class UtilsTest {

    @Test
    void isEmpty() {
        assertTrue(Utils.isEmpty(null));
        assertTrue(Utils.isEmpty(""));
        assertTrue(Utils.isEmpty("   "));
        assertFalse(Utils.isEmpty("a"));
    }

    @Test
    void isNumeric() {
        assertTrue(Utils.isNumeric("123"));
        assertFalse(Utils.isNumeric("12a"));
        assertFalse(Utils.isNumeric(""));
    }

    @Test
    void capitalize() {
        assertEquals("Workshop", Utils.capitalize("WORKSHOP"));
    }

    // Averages are shown with one decimal, cut off (CFP-61)
    @Test
    void roundsRatingToOneDecimal() {
        assertEquals(4.2, Utils.roundRating(4.25));
        assertEquals(3.6, Utils.roundRating(3.6666));
        assertEquals(4.5, Utils.roundRating(4.5));
    }

    // Ratings spec: "M/d/yy, h:mm AM" in UTC, no leading zeros, exactly one ordinary space before AM/PM
    @Test
    void usShortIsTheReviewerUiFormatOnAnyJdk() {
        assertEquals("11/4/26, 1:05 PM", Utils.usShort(Utils.parse("2026-11-04 13:05:00")));
        assertEquals("11/4/26, 12:00 AM", Utils.usShort(Utils.parse("2026-11-04 00:00:00")));
        assertEquals("1/9/27, 12:30 PM", Utils.usShort(Utils.parse("2027-01-09 12:30:59")));
    }

    // Speakers spec: "a real email"
    @Test
    void isValidEmail() {
        assertTrue(Utils.isValidEmail("ada@example.com"));
        assertTrue(Utils.isValidEmail("ADA+cfp@Example.co.uk"));
        assertTrue(Utils.isValidEmail("ada-0f6e2b@mail.example.com"));
        assertFalse(Utils.isValidEmail(null));
        assertFalse(Utils.isValidEmail(""));
        assertFalse(Utils.isValidEmail("not-an-email"));
        assertFalse(Utils.isValidEmail("a@b"));
        assertFalse(Utils.isValidEmail("@example.com"));
        assertFalse(Utils.isValidEmail("ada@"));
        assertFalse(Utils.isValidEmail("ada@@example.com"));
        assertFalse(Utils.isValidEmail("ada@example..com"));
        assertFalse(Utils.isValidEmail("ada@.example.com"));
        assertFalse(Utils.isValidEmail("ada@example.com."));
        assertFalse(Utils.isValidEmail("ada lovelace@example.com"));
    }

    @Disabled("flaky on Jenkins")
    @Test
    void nowIsCurrentTime() {
        SimpleDateFormat f = new SimpleDateFormat(Utils.DB_DATE_TIME);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        assertEquals(f.format(new Date()), Utils.now());
    }
}
