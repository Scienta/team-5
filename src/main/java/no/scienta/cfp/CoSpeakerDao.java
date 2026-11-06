package no.scienta.cfp;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Co-speakers of talks (workflow/specs/co-speakers.md). Callers lock the talk first, see {@link #lockTalk}.
 */
@Repository
public class CoSpeakerDao {

    private final JdbcTemplate jdbc;

    public CoSpeakerDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the talk row until the transaction ends and returns its main speaker's id, or null if there's no such
     * talk. Every change to a talk's co-speakers takes this lock first, so concurrent changes run one at a time.
     */
    public Long lockTalk(long talkId) {
        List<Long> main = jdbc.queryForList("SELECT speaker_id FROM talk WHERE id = ? FOR UPDATE", Long.class, talkId);
        return main.isEmpty() ? null : main.get(0);
    }

    /** The talk's status; the caller has locked the talk row with {@link #lockTalk}. */
    public String status(long talkId) {
        return jdbc.queryForObject("SELECT status FROM talk WHERE id = ?", String.class, talkId);
    }

    public List<Long> coSpeakers(long talkId) {
        return jdbc.queryForList("SELECT speaker_id FROM talk_co_speaker WHERE talk_id = ? ORDER BY seq", Long.class,
                talkId);
    }

    public void add(long talkId, long speakerId) {
        jdbc.update("INSERT INTO talk_co_speaker (talk_id, speaker_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
                talkId, speakerId);
    }

    /**
     * Returns whether there was such a co-speaker.
     */
    public boolean remove(long talkId, long speakerId) {
        return 1 == jdbc.update("DELETE FROM talk_co_speaker WHERE talk_id = ? AND speaker_id = ?", talkId, speakerId);
    }
}
