package no.scienta.cfp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * A talk as the API returns it. Also used internally all over the place.
 */
public class TalkDto {

    public Long id;
    public String title;
    public String format;
    public String level;
    public Long speakerId;
    public Integer durationMinutes;
    public String status;
    // The legacy reporting tools read these two as plain strings (Talk submissions spec), so the format is pinned here
    // rather than left to Jackson's default, which writes UTC as "Z" since Jackson 3. Always UTC, hence the literal.
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSS'+00:00'", timezone = "UTC")
    public Date submittedAt;
    public String submittedOn;  // yyyy-MM-dd, the UTC date of submittedAt
    public Double averageRating;
    public int ratingCount;
    public List<Long> coSpeakerIds = new ArrayList<>();  // Co-speakers spec: in the order they were added
}
