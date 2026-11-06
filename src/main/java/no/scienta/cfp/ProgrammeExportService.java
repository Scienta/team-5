package no.scienta.cfp;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The whole programme as JSON rows and as CSV, for the website and the print shop. Rules E1-E12 are in
 * workflow/specs/programme-export.md. Both views come from {@link #rows()}, so they have the same rows in the same
 * order (E2).
 */
@Service
public class ProgrammeExportService {

    static final String CSV_HEADER = "slot,start,end,room,talkId,title,format,speakers";

    private static final List<String> SLOT_ORDER = new ArrayList<>(SchedulingService.SLOTS.keySet());

    // Speakers as they are now (E5): the main speaker, then co-speakers in the order they were added (E6).
    private static final String SELECT = "SELECT p.slot, p.room, t.id, t.title, t.format, s.name AS main, "
            + "(SELECT COALESCE(array_agg(cs.name ORDER BY c.seq), '{}') FROM talk_co_speaker c "
            + "JOIN speaker cs ON cs.id = c.speaker_id WHERE c.talk_id = t.id) AS co "
            + "FROM programme p JOIN talk t ON t.id = p.talk_id JOIN speaker s ON s.id = t.speaker_id";

    private final JdbcTemplate jdbc;

    public ProgrammeExportService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** E3: earliest start first, then by room. */
    public List<Map<String, Object>> rows() {
        List<Map<String, Object>> rows = jdbc.query(SELECT, (rs, n) -> {
            String slot = rs.getString("slot");
            List<String> speakers = new ArrayList<>();
            speakers.add(rs.getString("main"));
            Array co = rs.getArray("co");
            speakers.addAll(Arrays.asList((String[]) co.getArray()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("slot", slot);
            row.put("start", SchedulingService.SLOTS.get(slot)[0]);
            row.put("end", SchedulingService.SLOTS.get(slot)[1]);
            row.put("room", rs.getString("room"));
            row.put("talkId", rs.getLong("id"));
            row.put("title", rs.getString("title"));
            row.put("format", rs.getString("format"));
            row.put("speakers", speakers);
            return row;
        });
        rows.sort(Comparator.<Map<String, Object>>comparingInt(r -> SLOT_ORDER.indexOf((String) r.get("slot")))
                .thenComparing(r -> (String) r.get("room")));
        return rows;
    }

    /** E9-E12: the header, then one line per row; every line ends in \n, nothing after the last one. */
    @SuppressWarnings("unchecked")
    public String csv() {
        StringBuilder out = new StringBuilder(CSV_HEADER).append('\n');
        for (Map<String, Object> row : rows()) {
            out.append(field(row.get("slot"))).append(',')
                    .append(field(row.get("start"))).append(',')
                    .append(field(row.get("end"))).append(',')
                    .append(field(row.get("room"))).append(',')
                    .append(field(row.get("talkId"))).append(',')
                    .append(field(row.get("title"))).append(',')
                    .append(field(row.get("format"))).append(',')
                    .append(field(String.join("; ", (List<String>) row.get("speakers"))))
                    .append('\n');
        }
        return out.toString();
    }

    /** E11 (RFC 4180): quoted only with a comma, a quote or a line break in it; a quote inside is doubled. */
    static String field(Object value) {
        String s = String.valueOf(value);
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }
}
