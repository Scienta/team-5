package no.scienta.cfp;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * The programme: accepted talks in rooms and time slots. Rules P1-P14 and the order of checks are in
 * workflow/specs/scheduling.md. Putting a talk locks the three rooms of its slot first and checks the rules on the
 * locked rows, so concurrent changes to a slot run one at a time and can't break the rules (P12).
 */
@Service
public class SchedulingService {

    static final List<String> ROOMS = List.of("R1", "R2", "R3");

    /** Slot to {start, end}, as on the signs (P2). */
    static final Map<String, String[]> SLOTS = new LinkedHashMap<>();

    static final String WORKSHOP_SLOT = "S4";

    static {
        SLOTS.put("S1", new String[] {"09:00", "10:00"});
        SLOTS.put("S2", new String[] {"10:15", "11:15"});
        SLOTS.put("S3", new String[] {"11:30", "12:30"});
        SLOTS.put("S4", new String[] {"13:30", "15:30"});
        SLOTS.put("S5", new String[] {"15:45", "16:45"});
    }

    private final JdbcTemplate jdbc;
    private final TalkDao talks;

    public SchedulingService(JdbcTemplate jdbc, TalkDao talks) {
        this.jdbc = jdbc;
        this.talks = talks;
    }

    @Transactional
    public Map<String, Object> put(String room, String slot, Map<String, Object> body) {
        checkRoomAndSlot(room, slot);
        Object value = body == null ? null : body.get("talkId");
        if (!(value instanceof Integer || value instanceof Long)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "talkId must be a whole number");
        }
        long talkId = ((Number) value).longValue();
        TalkDto talk = talks.findById(talkId);
        if (talk == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "talk not found");
        }

        // P12: the slot's three rows stay locked until the transaction ends.
        Map<String, Long> inSlot = new LinkedHashMap<>();
        jdbc.query("SELECT room, talk_id FROM programme WHERE slot = ? ORDER BY room FOR UPDATE",
                rs -> {
                    String inRoom = rs.getString("room");
                    long id = rs.getLong("talk_id");
                    inSlot.put(inRoom, rs.wasNull() ? null : id); // wasNull() is about the column read last
                }, slot);

        if (!"ACCEPTED".equals(talk.status)) {
            throw conflict("only accepted talks go on the programme, this one is " + talk.status);
        }
        if ("WORKSHOP".equals(talk.format) && !WORKSHOP_SLOT.equals(slot)) {
            throw conflict("a workshop needs the long slot " + WORKSHOP_SLOT);
        }
        if (inSlot.get(room) != null) {
            throw conflict(room + " " + slot + " already has talk " + inSlot.get(room));
        }
        List<Map<String, Object>> elsewhere = jdbc.queryForList(
                "SELECT room, slot FROM programme WHERE talk_id = ?", talkId);
        if (!elsewhere.isEmpty()) {
            Map<String, Object> at = elsewhere.get(0);
            throw conflict("talk " + talkId + " is already on the programme in " + at.get("room") + " " + at.get("slot")
                    + "; take it out first");
        }
        Set<Long> speaking = speakersOf(talk);
        for (Long other : inSlot.values()) {
            if (other == null) {
                continue;
            }
            Set<Long> clash = new HashSet<>(speakersOf(talks.findById(other)));
            clash.retainAll(speaking);
            if (!clash.isEmpty()) {
                throw conflict("speaker " + clash.iterator().next() + " already speaks in " + slot
                        + " (talk " + other + ")");
            }
        }

        try {
            jdbc.update("UPDATE programme SET talk_id = ? WHERE room = ? AND slot = ?", talkId, room, slot);
        } catch (DuplicateKeyException e) {
            // P9/P12: the same talk was put in another slot at the same time, and that one won.
            throw conflict("talk " + talkId + " is already on the programme");
        }
        return entry(room, slot, talkId);
    }

    public Map<String, Object> get(String room, String slot) {
        checkRoomAndSlot(room, slot);
        Long talkId = jdbc.queryForObject("SELECT talk_id FROM programme WHERE room = ? AND slot = ?", Long.class,
                room, slot);
        if (talkId == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no talk in " + room + " " + slot);
        }
        return entry(room, slot, talkId);
    }

    @Transactional
    public void remove(String room, String slot) {
        checkRoomAndSlot(room, slot);
        int emptied = jdbc.update(
                "UPDATE programme SET talk_id = NULL WHERE room = ? AND slot = ? AND talk_id IS NOT NULL", room, slot);
        if (emptied == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no talk in " + room + " " + slot);
        }
    }

    /** P1: exact capitals only. */
    private static void checkRoomAndSlot(String room, String slot) {
        if (!ROOMS.contains(room) || !SLOTS.containsKey(slot)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "rooms are " + ROOMS + " and slots are " + SLOTS.keySet());
        }
    }

    private static Set<Long> speakersOf(TalkDto talk) {
        Set<Long> ids = new HashSet<>(talk.coSpeakerIds);
        ids.add(talk.speakerId);
        return ids;
    }

    private static Map<String, Object> entry(String room, String slot, long talkId) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("room", room);
        entry.put("slot", slot);
        entry.put("start", SLOTS.get(slot)[0]);
        entry.put("end", SLOTS.get(slot)[1]);
        entry.put("talkId", talkId);
        return entry;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
