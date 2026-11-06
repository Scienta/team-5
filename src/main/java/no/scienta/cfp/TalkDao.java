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

    /** The cap of 3 talks per speaker (submission cap change request). */
    public static final int MAX_TALKS = 3;

    // A talk counts toward a speaker's cap when it's still in the running (not rejected or withdrawn) and the speaker
    // is on it as main speaker or co-speaker (submission cap change request, SC1/SC2). Two bind params: the speaker id.
    private static final String COUNT_TOWARD_CAP =
            "SELECT COUNT(*) FROM talk t WHERE t.status NOT IN ('REJECTED', 'WITHDRAWN') "
                    + "AND (t.speaker_id = ? OR EXISTS "
                    + "(SELECT 1 FROM talk_co_speaker c WHERE c.talk_id = t.id AND c.speaker_id = ?))";

    /** How many talks count toward this speaker's cap right now. Caller locks the speaker row first (SC7). */
    public int countTowardCap(long speakerId) {
        Integer n = jdbc.queryForObject(COUNT_TOWARD_CAP, Integer.class, speakerId, speakerId);
        return n == null ? 0 : n;
    }

    /**
     * Inserts the talk only if the speaker is below the cap, counting their in-the-running talks as main speaker or
     * co-speaker (SC1-SC3). Returns the new id, or null. The speaker row is locked first so two submissions, or a
     * submission and a co-speaker add, for the same speaker can't both see room under the cap (SC7).
     */
    @Transactional
    public Long insertIfBelow(int max, String title, String format, int duration, String level, long speakerId, long submittedAt) {
        jdbc.queryForList("SELECT id FROM speaker WHERE id = ? FOR UPDATE", speakerId);
        List<Long> ids = jdbc.queryForList(
                "INSERT INTO talk (title, format, duration, level, status, speaker_id, submitted_at) "
                        + "SELECT ?, ?, ?, ?, 'SUBMITTED', ?, ? "
                        + "WHERE (" + COUNT_TOWARD_CAP + ") < ? RETURNING id",
                Long.class, title, format, duration, level, speakerId, submittedAt, speakerId, speakerId, max);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // old version, raced when the mobile app double-posted
    // public long insert(String title, String format, int duration, String level, long speakerId) {
    //     jdbc.update("INSERT INTO talk (title, format, duration, level, status, speaker_id) VALUES (?, ?, ?, ?, 'SUBMITTED', ?)",
    //             title, format, duration, level, speakerId);
    //     return jdbc.queryForObject("SELECT currval('talk_id_seq')", Long.class);
    // }
}
