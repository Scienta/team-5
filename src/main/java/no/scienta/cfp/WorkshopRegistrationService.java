package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Registrations for accepted workshops, at most {@value #CAPACITY} each. Rules W1-W11 and the order of checks are in
 * workflow/specs/workshop-registration.md. Every registration locks the workshop's talk row first, so a rush of
 * concurrent registrations is handled one at a time and can never overfill a workshop (W7).
 */
@Service
public class WorkshopRegistrationService {

    static final int CAPACITY = 20;

    private static final RowMapper<Map<String, Object>> REGISTRATION = (rs, n) -> {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", rs.getLong("id"));
        r.put("workshopId", rs.getLong("talk_id"));
        r.put("name", rs.getString("name"));
        r.put("email", rs.getString("email"));
        return r;
    };

    private final JdbcTemplate jdbc;

    public WorkshopRegistrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> register(long workshopId, Map<String, Object> body) {
        String status = workshop(workshopId, true);
        String name = text(body, "name");
        String email = text(body, "email");
        if (!Utils.isValidEmail(email)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid email");
        }
        if (!"ACCEPTED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "only accepted workshops take registrations");
        }
        Integer taken = jdbc.queryForObject(
                "SELECT COUNT(*) FROM workshop_registration WHERE talk_id = ? AND lower(email) = lower(?)",
                Integer.class, workshopId, email);
        if (taken != null && taken > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered for the workshop");
        }
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM workshop_registration WHERE talk_id = ?",
                Integer.class, workshopId);
        if (count != null && count >= CAPACITY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "the workshop is full (" + CAPACITY + " places)");
        }
        try {
            return jdbc.queryForObject("INSERT INTO workshop_registration (talk_id, name, email, registered_at) "
                    + "VALUES (?, ?, ?, ?) RETURNING id, talk_id, name, email", REGISTRATION,
                    workshopId, name, email, System.currentTimeMillis());
        } catch (DuplicateKeyException e) { // the unique index, should the lock ever be bypassed
            throw new ResponseStatusException(HttpStatus.CONFLICT, "this email is already registered for the workshop");
        }
    }

    public List<Map<String, Object>> registrations(long workshopId) {
        workshop(workshopId, false);
        return jdbc.query("SELECT id, talk_id, name, email FROM workshop_registration WHERE talk_id = ? ORDER BY id",
                REGISTRATION, workshopId);
    }

    /** The workshop's status; 404 if there's no such talk or it isn't a workshop (W3). */
    private String workshop(long talkId, boolean lock) {
        List<String[]> rows = jdbc.query("SELECT format, status FROM talk WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, n) -> new String[] {rs.getString("format"), rs.getString("status")}, talkId);
        if (rows.isEmpty() || !"WORKSHOP".equals(rows.get(0)[0])) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "workshop not found");
        }
        return rows.get(0)[1];
    }

    private static String text(Map<String, Object> body, String field) {
        Object value = body == null ? null : body.get(field);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return (String) value;
    }
}
