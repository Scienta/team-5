package no.scienta.cfp;

import java.util.Date;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TalkDao {

    // Talks with their rating count, score total and co-speakers in one query (a LATERAL aggregate per talk, using the rating
    // primary key), instead of one extra query per talk. GET /talks returns every talk, and the table grows all day.
    private static final String SELECT = "SELECT t.id, t.title, t.format, t.duration, t.level, t.status, t.speaker_id, "
            + "t.submitted_at, r.cnt, r.total, r.top, r.bottom, c.co FROM talk t "
            + "CROSS JOIN LATERAL (SELECT COUNT(*) AS cnt, COALESCE(SUM(score), 0) AS total, "
            + "COALESCE(MAX(score), 0) AS top, COALESCE(MIN(score), 0) AS bottom "
            + "FROM rating WHERE rating.talk_id = t.id) r "
            + "CROSS JOIN LATERAL (SELECT COALESCE(array_agg(speaker_id ORDER BY seq), '{}') AS co "
            + "FROM talk_co_speaker WHERE talk_co_speaker.talk_id = t.id) c";

    private final JdbcTemplate jdbc;

    private final RowMapper<TalkDto> mapper = (rs, rowNum) -> {
        TalkDto t = new TalkDto();
        t.id = rs.getLong("id");
        t.title = rs.getString("title");
        t.format = rs.getString("format");
        t.durationMinutes = rs.getInt("duration");
        t.level = rs.getString("level");
        t.status = rs.getString("status");
        t.speakerId = rs.getLong("speaker_id");
        long millis = rs.getLong("submitted_at");
        t.submittedAt = new Date(millis);
        t.submittedOn = Utils.utcDate(millis);
        int count = rs.getInt("cnt");
        t.ratingCount = count;
        t.averageRating = count == 0 ? null
                : Utils.averageRating(rs.getLong("total"), count, rs.getInt("top"), rs.getInt("bottom"));
        for (Object id : (Object[]) rs.getArray("co").getArray()) {
            t.coSpeakerIds.add(((Number) id).longValue());
        }
        return t;
    };

    public TalkDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public TalkDto findById(long id) {
        List<TalkDto> list = jdbc.query(SELECT + " WHERE t.id = ?", mapper, id);
        return list.isEmpty() ? null : list.get(0);
    }

    public List<TalkDto> findBySpeaker(long speakerId) {
        return jdbc.query(SELECT + " WHERE t.speaker_id = ? ORDER BY t.id", mapper, speakerId);
    }

    public List<TalkDto> findAll() {
        return jdbc.query(SELECT + " ORDER BY t.id", mapper);
    }

    public boolean exists(long id) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM talk WHERE id = ?", Integer.class, id);
        return n != null && n > 0;
    }

    /**
     * Inserts the talk only if the speaker has fewer than {@code max} talks. Returns the new id, or null.
     * The speaker row is locked first so two submissions for the same speaker can't both see room under the limit.
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (SELECT COUNT(*) FROM talk WHERE speaker_id = ?) < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}
