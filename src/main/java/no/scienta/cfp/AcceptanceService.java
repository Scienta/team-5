package no.scienta.cfp;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The committee's decisions and the speaker's withdrawal. Rules A1-A12 and the transition table are in
 * workflow/specs/acceptance.md. Every change locks the talk row first and checks the rules on the locked row, so
 * concurrent decisions run one at a time and can't leave a talk in a state the rules don't allow (A11).
 */
@Service
public class AcceptanceService {

    enum Status { SUBMITTED, ACCEPTED, REJECTED, WAITLISTED, WITHDRAWN }

    static final int REVIEWERS_TO_ACCEPT = 2;

    private static final Set<Status> DECISIONS = EnumSet.of(Status.ACCEPTED, Status.REJECTED, Status.WAITLISTED);

    /** Which decisions the committee may take from each status (A1-A3, A8). */
    private static final Map<Status, Set<Status>> DECIDE = new HashMap<>();

    /** The statuses a speaker may withdraw from (A6, A8). */
    private static final Set<Status> WITHDRAWABLE = EnumSet.of(Status.SUBMITTED, Status.WAITLISTED, Status.ACCEPTED);

    static {
        DECIDE.put(Status.SUBMITTED, EnumSet.of(Status.ACCEPTED, Status.REJECTED, Status.WAITLISTED));
        DECIDE.put(Status.WAITLISTED, EnumSet.of(Status.ACCEPTED, Status.REJECTED));
        DECIDE.put(Status.ACCEPTED, EnumSet.noneOf(Status.class));
        DECIDE.put(Status.REJECTED, EnumSet.noneOf(Status.class));
        DECIDE.put(Status.WITHDRAWN, EnumSet.noneOf(Status.class));
    }

    private final JdbcTemplate jdbc;
    private final ConferenceService conference;

    public AcceptanceService(JdbcTemplate jdbc, ConferenceService conference) {
        this.jdbc = jdbc;
        this.conference = conference;
    }

    @Transactional
    public TalkDto decide(long talkId, Map<String, Object> body) {
        Locked talk = lock(talkId);
        Object value = body == null ? null : body.get("status");
        Status decision = parse(value);
        if (decision == null || !DECISIONS.contains(decision)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "status must be one of " + DECISIONS);
        }
        if (!DECIDE.get(talk.status).contains(decision)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "a " + talk.status + " talk can't be " + decision);
        }
        if (decision == Status.ACCEPTED) {
            Integer reviewers = jdbc.queryForObject("SELECT COUNT(DISTINCT reviewer_id) FROM rating WHERE talk_id = ?",
                    Integer.class, talkId);
            if (reviewers == null || reviewers < REVIEWERS_TO_ACCEPT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "a talk needs ratings from at least " + REVIEWERS_TO_ACCEPT + " reviewers to be accepted");
            }
        }
        jdbc.update("UPDATE talk SET status = ? WHERE id = ?", decision.name(), talkId);
        return conference.getTalk(talkId);
    }

    @Transactional
    public TalkDto withdraw(long talkId, Map<String, Object> body) {
        Locked talk = lock(talkId);
        Object speaker = body == null ? null : body.get("speakerId");
        if (!(speaker instanceof Integer || speaker instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "speakerId must be a whole number");
        }
        if (((Number) speaker).longValue() != talk.speakerId) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the talk's speaker can withdraw it");
        }
        if (!WITHDRAWABLE.contains(talk.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "a " + talk.status + " talk can't be withdrawn");
        }
        jdbc.update("UPDATE talk SET status = ? WHERE id = ?", Status.WITHDRAWN.name(), talkId);
        return conference.getTalk(talkId);
    }

    /** 404 if there's no such talk; otherwise the talk row stays locked until the transaction ends. */
    private Locked lock(long talkId) {
        List<Locked> rows = jdbc.query("SELECT speaker_id, status FROM talk WHERE id = ? FOR UPDATE",
                (rs, n) -> new Locked(rs.getLong("speaker_id"), Status.valueOf(rs.getString("status"))), talkId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }
        return rows.get(0);
    }

    private static Status parse(Object value) {
        if (!(value instanceof String)) {
            return null;
        }
        for (Status s : Status.values()) {
            if (s.name().equals(value)) { // exact capitals only
                return s;
            }
        }
        return null;
    }

    private static final class Locked {
        final long speakerId;
        final Status status;

        Locked(long speakerId, Status status) {
            this.speakerId = speakerId;
            this.status = status;
        }
    }
}
