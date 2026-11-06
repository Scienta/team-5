package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme scheduling, rule by rule (P1-P14 in workflow/specs/scheduling.md). Runs the real app on a random port with
 * a throwaway database. Tests marked "guess" check a decision the feature request leaves open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SchedulingTest {

    private static final String[] ROOMS = {"R1", "R2", "R3"};
    private static final String[] SLOTS = {"S1", "S2", "S3", "S4", "S5"};

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    /** The programme has only 15 rooms and slots, shared by every test here: start each test empty. */
    @BeforeEach
    void emptyProgramme() throws Exception {
        for (String room : ROOMS) {
            for (String slot : SLOTS) {
                int code = send(delete("/programme/" + room + "/" + slot)).statusCode();
                assertTrue(code == 204 || code == 404, "status " + code);
            }
        }
    }

    @Test
    void p1_onlyTheFixedRoomsAndSlots_guess404() throws Exception {
        long t = accepted(talk("PRESENTATION"));
        for (String path : new String[] {"R0/S2", "R4/S1", "r1/S3", "Room1/S4", "R1/s1", "R3/S0", "R2/S6", "R2/slot2"}) {
            assertEquals(404, schedule(path, t).statusCode(), "PUT " + path);
            assertEquals(404, send(get("/programme/" + path)).statusCode(), "GET " + path);
            assertEquals(404, send(delete("/programme/" + path)).statusCode(), "DELETE " + path);
        }
    }

    @Test
    void p2p3p4_putAndLookUpWithTheSignTimes() throws Exception {
        String[][] times = {{"09:00", "10:00"}, {"10:15", "11:15"}, {"11:30", "12:30"}, {"13:30", "15:30"},
                {"15:45", "16:45"}};
        for (int i = 0; i < SLOTS.length; i++) {
            long t = accepted(talk("LIGHTNING_20"));
            String path = "R2/" + SLOTS[i];
            JsonNode put = ok(schedule(path, t));
            assertEquals("{\"room\":\"R2\",\"slot\":\"" + SLOTS[i] + "\",\"start\":\"" + times[i][0] + "\",\"end\":\""
                    + times[i][1] + "\",\"talkId\":" + t + "}", put.toString());
            assertEquals(put, ok(send(get("/programme/" + path))));
        }
    }

    @Test
    void p4_emptyRoomAndSlotIs404_guess() throws Exception {
        assertEquals(404, send(get("/programme/R1/S1")).statusCode());
    }

    @Test
    void p5_takenOutTalkCanGoElsewhere() throws Exception {
        long t = accepted(talk("PRESENTATION"));
        ok(schedule("R1/S1", t));
        assertEquals(204, send(delete("/programme/R1/S1")).statusCode());
        assertEquals(404, send(get("/programme/R1/S1")).statusCode());
        assertEquals("R3", ok(schedule("R3/S5", t)).get("room").asText());
    }

    @Test
    void p5_takingOutOfAnEmptyRoomAndSlotIs404() throws Exception {
        assertEquals(404, send(delete("/programme/R2/S3")).statusCode());
        long t = accepted(talk("PRESENTATION"));
        ok(schedule("R2/S3", t));
        assertEquals(204, send(delete("/programme/R2/S3")).statusCode());
        assertEquals(404, send(delete("/programme/R2/S3")).statusCode());
    }

    @Test
    void p6_onlyAcceptedTalks() throws Exception {
        long submitted = talk("PRESENTATION");
        assertEquals(409, schedule("R1/S1", submitted).statusCode());

        long waitlisted = talk("PRESENTATION");
        ok(decide(waitlisted, "WAITLISTED"));
        assertEquals(409, schedule("R1/S1", waitlisted).statusCode());

        long rejected = talk("PRESENTATION");
        ok(decide(rejected, "REJECTED"));
        assertEquals(409, schedule("R1/S1", rejected).statusCode());

        long withdrawn = accepted(talk("PRESENTATION"));
        ok(send(post("/talks/" + withdrawn + "/withdraw", "{\"speakerId\":" + speakerOf(withdrawn) + "}")));
        assertEquals(409, schedule("R1/S1", withdrawn).statusCode());

        assertEquals(404, send(get("/programme/R1/S1")).statusCode());
    }

    @Test
    void p7_workshopsNeedTheLongSlot_guess409() throws Exception {
        long workshop = accepted(talk("WORKSHOP"));
        for (String slot : new String[] {"S1", "S2", "S3", "S5"}) {
            assertEquals(409, schedule("R1/" + slot, workshop).statusCode(), slot);
        }
        ok(schedule("R1/S4", workshop));
    }

    @Test
    void p7_everythingElseFitsAnywhere() throws Exception {
        for (String format : new String[] {"LIGHTNING_10", "LIGHTNING_20", "PRESENTATION"}) {
            ok(schedule("R1/S4", accepted(talk(format))));
            assertEquals(204, send(delete("/programme/R1/S4")).statusCode());
            ok(schedule("R2/S1", accepted(talk(format))));
            assertEquals(204, send(delete("/programme/R2/S1")).statusCode());
        }
    }

    @Test
    void p8_oneTalkPerRoomAndSlot() throws Exception {
        long first = accepted(talk("PRESENTATION"));
        long second = accepted(talk("PRESENTATION"));
        ok(schedule("R1/S2", first));
        assertEquals(409, schedule("R1/S2", second).statusCode());
        assertEquals(first, ok(send(get("/programme/R1/S2"))).get("talkId").asLong());
    }

    @Test
    void p8_theSameTalkInTheSamePlaceAgainChangesNothing() throws Exception {
        // the harness's scheduling/check-1 (tick 136): PUT, GET, the same PUT again expects 200, not 409
        long t = accepted(talk("PRESENTATION"));
        ok(schedule("R1/S2", t));
        JsonNode again = ok(schedule("R1/S2", t));
        assertEquals(t, again.get("talkId").asLong());
        assertEquals("R1", again.get("room").asText());
        assertEquals(t, ok(send(get("/programme/R1/S2"))).get("talkId").asLong());
    }

    @Test
    void p9_aTalkIsOnTheProgrammeOnlyOnce() throws Exception {
        long t = accepted(talk("PRESENTATION"));
        ok(schedule("R1/S1", t));
        assertEquals(409, schedule("R2/S1", t).statusCode());
        assertEquals(409, schedule("R3/S5", t).statusCode());
        assertEquals(404, send(get("/programme/R3/S5")).statusCode());
    }

    @Test
    void p10_nobodySpeaksInTwoRoomsAtOnce() throws Exception {
        long ada = speaker();
        long grace = speaker();
        long linus = speaker();

        long adas = accepted(talk(ada, "PRESENTATION"));
        long adasOther = accepted(talk(ada, "PRESENTATION"));
        ok(schedule("R1/S3", adas));
        assertEquals(409, schedule("R2/S3", adasOther).statusCode(), "the same main speaker");

        long gracesWithAda = talk(grace, "PRESENTATION");
        ok(send(put("/talks/" + gracesWithAda + "/co-speakers/" + ada, null)));
        accepted(gracesWithAda);
        assertEquals(409, schedule("R2/S3", gracesWithAda).statusCode(), "a co-speaker is the other talk's speaker");

        long linuses = talk(linus, "PRESENTATION");
        ok(send(put("/talks/" + linuses + "/co-speakers/" + grace, null)));
        accepted(linuses);
        ok(schedule("R2/S3", linuses)); // no one in common with Ada's talk
        long gracesOwn = accepted(talk(grace, "PRESENTATION"));
        assertEquals(409, schedule("R3/S3", gracesOwn).statusCode(), "the speaker is a co-speaker in the slot");

        ok(schedule("R2/S4", adasOther)); // another slot is fine
    }

    @Test
    void p11_aTalkThatChangesAfterwardsStaysOnTheProgramme() throws Exception {
        long t = accepted(talk("PRESENTATION"));
        ok(schedule("R3/S2", t));
        ok(send(post("/talks/" + t + "/withdraw", "{\"speakerId\":" + speakerOf(t) + "}")));
        assertEquals(t, ok(send(get("/programme/R3/S2"))).get("talkId").asLong());
    }

    @Test
    void p12_concurrentTalksOfOneSpeakerInOneSlotHaveOneWinner() throws Exception {
        for (int round = 0; round < 20; round++) {
            emptyProgramme();
            long s = speaker();
            List<HttpRequest> requests = new ArrayList<>();
            for (String room : ROOMS) {
                requests.add(scheduleRequest(room + "/S2", accepted(talk(s, "PRESENTATION"))));
            }
            // talks first, then all three PUTs at once: the race is only in the PUTs
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (HttpRequest r : requests) {
                sent.add(http.sendAsync(r, HttpResponse.BodyHandlers.ofString()));
            }
            assertEquals(1, winners(sent), "one speaker, one room per slot");
        }
    }

    @Test
    void p12_concurrentTalksForOneRoomAndSlotHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            emptyProgramme();
            List<Long> talks = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                talks.add(accepted(talk("PRESENTATION")));
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (long t : talks) {
                sent.add(http.sendAsync(scheduleRequest("R1/S1", t), HttpResponse.BodyHandlers.ofString()));
            }
            int winner = -1;
            for (int i = 0; i < sent.size(); i++) {
                if (sent.get(i).join().statusCode() == 200) {
                    assertEquals(-1, winner, "two winners");
                    winner = i;
                }
            }
            assertTrue(winner >= 0, "no winner");
            assertEquals((long) talks.get(winner), ok(send(get("/programme/R1/S1"))).get("talkId").asLong());
        }
    }

    @Test
    void p12_concurrentPutsOfOneTalkHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            emptyProgramme();
            long t = accepted(talk("PRESENTATION"));
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (String path : new String[] {"R1/S1", "R2/S2", "R3/S3", "R1/S5"}) {
                sent.add(http.sendAsync(scheduleRequest(path, t), HttpResponse.BodyHandlers.ofString()));
            }
            assertEquals(1, winners(sent), "a talk is on the programme once");
        }
    }

    @Test
    void p13_badInputIs400() throws Exception {
        for (String body : new String[] {null, "", "{}", "{\"talkId\":null}", "{\"talkId\":\"1\"}", "{\"talkId\":1.5}",
                "{\"talkId\":", "[1]"}) {
            assertEquals(400, send(put("/programme/R1/S1", body)).statusCode(), String.valueOf(body));
        }
    }

    @Test
    void p14_unknownTalkIs400LikeOtherBadInput() throws Exception {
        // the harness's scheduling/check-1 (tick 131) failed our 404 for an unknown talkId in the body
        assertEquals(400, schedule("R1/S1", 999999).statusCode());
    }

    // ------------------------------------------------------------------ helpers

    private int winners(List<CompletableFuture<HttpResponse<String>>> sent) {
        int ok = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            HttpResponse<String> res = f.join();
            assertTrue(res.statusCode() == 200 || res.statusCode() == 409, res.statusCode() + " " + res.body());
            if (res.statusCode() == 200) {
                ok++;
            }
        }
        return ok;
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Scheduled Speaker\",\"email\":\"p-" + UUID.randomUUID() + "@example.com\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(String format) throws Exception {
        return talk(speaker(), format);
    }

    private long talk(long speakerId, String format) throws Exception {
        HttpResponse<String> res = send(post("/talks", "{\"title\":\"Schedule me\",\"format\":\"" + format + "\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long accepted(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            ok(send(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}")));
        }
        ok(decide(talk, "ACCEPTED"));
        return talk;
    }

    private HttpResponse<String> decide(long talk, String status) throws Exception {
        return send(put("/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"));
    }

    private long speakerOf(long talk) throws Exception {
        return ok(send(get("/talks/" + talk))).get("speakerId").asLong();
    }

    private HttpResponse<String> schedule(String roomAndSlot, long talk) throws Exception {
        return send(scheduleRequest(roomAndSlot, talk));
    }

    private HttpRequest scheduleRequest(String roomAndSlot, long talk) {
        return put("/programme/" + roomAndSlot, "{\"talkId\":" + talk + "}");
    }

    private JsonNode ok(HttpResponse<String> res) throws Exception {
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET().build();
    }

    private HttpRequest delete(String path) {
        return HttpRequest.newBuilder(uri(path)).DELETE().build();
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpRequest put(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
