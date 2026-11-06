package no.scienta.cfp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The programme, one room and slot at a time (workflow/specs/scheduling.md), and all of it as JSON and CSV
 * (workflow/specs/programme-export.md).
 */
@RestController
public class ProgrammeController {

    private final SchedulingService scheduling;
    private final ProgrammeExportService export;

    public ProgrammeController(SchedulingService scheduling, ProgrammeExportService export) {
        this.scheduling = scheduling;
        this.export = export;
    }

    @GetMapping("/programme")
    public List<Map<String, Object>> programme() {
        return export.rows();
    }

    // E8: written straight to the response, so it's text/csv whatever the client's Accept header says (no 406).
    @GetMapping("/programme.csv")
    public void programmeCsv(HttpServletResponse response) throws IOException {
        byte[] body = export.csv().getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("text/csv;charset=UTF-8");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    // The body is optional here so a missing one is our 400 (P13), after the room and slot check (P1).
    @PutMapping("/programme/{room}/{slot}")
    public Map<String, Object> put(@PathVariable String room, @PathVariable String slot,
                                   @RequestBody(required = false) Map<String, Object> body) {
        return scheduling.put(room, slot, body);
    }

    @GetMapping("/programme/{room}/{slot}")
    public Map<String, Object> get(@PathVariable String room, @PathVariable String slot) {
        return scheduling.get(room, slot);
    }

    // guess (P5): 204 without a body, the request gives no response for DELETE
    @DeleteMapping("/programme/{room}/{slot}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable String room, @PathVariable String slot) {
        scheduling.remove(room, slot);
    }
}
