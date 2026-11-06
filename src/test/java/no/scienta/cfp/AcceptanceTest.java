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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Acceptance state machine, rule by rule (A1-A12 in workflow/specs/acceptance.md). Runs the real app on a random
 * port with a throwaway database. Tests marked "guess" check a decision the feature request leaves open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AcceptanceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void a1a5_rejectAndWaitlistNeedNoRatings() throws Exception {
        assertEquals("REJECTED", status(ok(decide(talk(), "REJECTED"))));
        assertEquals("WAITLISTED", status(ok(decide(talk(), "WAITLISTED"))));
    }

    @Test
    void a1a4_acceptNeedsTwoDifferentReviewers() throws Exception {
        long t = talk();
        assertEquals(409, decide(t, "ACCEPTED").statusCode());
        rate(t, 1, 4);
        rate(t, 1, 5); // the same reviewer twice is one reviewer
        assertEquals(409, decide(t, "ACCEPTED").statusCode());
        assertEquals("SUBMITTED", statusOf(t));
        rate(t, 2, 3);
        assertEquals("ACCEPTED", status(ok(decide(t, "ACCEPTED"))));
        assertEquals("ACCEPTED", statusOf(t));
    }

    @Test
    void a2_waitlistedCanStillBeAcceptedOrRejected() throws Exception {
        long t1 = ratedTalk();
        ok(decide(t1, "WAITLISTED"));
        assertEquals("ACCEPTED", status(ok(decide(t1, "ACCEPTED"))));
        long t2 = talk();
        ok(decide(t2, "WAITLISTED"));
        assertEquals("REJECTED", status(ok(decide(t2, "REJECTED"))));
    }

    @Test
    void a2_waitlistedAgainIs409_guess() throws Exception {
        long t = talk();
        ok(decide(t, "WAITLISTED"));
        assertEquals(409, decide(t, "WAITLISTED").statusCode());
        assertEquals("WAITLISTED", statusOf(t));
    }

    @Test
    void a3_acceptedAndRejectedAreFinal() throws Exception {
        long accepted = ratedTalk();
        ok(decide(accepted, "ACCEPTED"));
        for (String d : new String[] {"REJECTED", "WAITLISTED", "ACCEPTED"}) {
            assertEquals(409, decide(accepted, d).statusCode(), d);
        }
        assertEquals("ACCEPTED", statusOf(accepted));

        long rejected = ratedTalk();
        ok(decide(rejected, "REJECTED"));
        for (String d : new String[] {"ACCEPTED", "WAITLISTED", "REJECTED"}) {
            assertEquals(409, decide(rejected, d).statusCode(), d);
        }
        assertEquals("REJECTED", statusOf(rejected));
    }

    @Test
    void a6_speakerWithdrawsFromSubmittedWaitlistedOrAccepted() throws Exception {
        long s = speaker();
        long submitted = talk(s);
        assertEquals("WITHDRAWN", status(ok(withdraw(submitted, s))));

        long waitlisted = talk(s);
        ok(decide(waitlisted, "WAITLISTED"));
        assertEquals("WITHDRAWN", status(ok(withdraw(waitlisted, s))));

        long s2 = speaker();
        long accepted = talk(s2);
        rate(accepted, 1, 5);
        rate(accepted, 2, 5);
        ok(decide(accepted, "ACCEPTED"));
        assertEquals("WITHDRAWN", status(ok(withdraw(accepted, s2))));
    }

    @Test
    void a6a8_notFromRejectedOrWithdrawn() throws Exception {
        long s = speaker();
        long rejected = talk(s);
        ok(decide(rejected, "REJECTED"));
        assertEquals(409, withdraw(rejected, s).statusCode());
        assertEquals("REJECTED", statusOf(rejected));

        long withdrawn = talk(s);
        ok(withdraw(withdrawn, s));
        assertEquals(409, withdraw(withdrawn, s).statusCode());
        rate(withdrawn, 1, 5);
        rate(withdrawn, 2, 5);
        for (String d : new String[] {"ACCEPTED", "REJECTED", "WAITLISTED"}) {
            assertEquals(409, decide(withdrawn, d).statusCode(), d);
        }
        assertEquals("WITHDRAWN", statusOf(withdrawn));
    }

    @Test
    void a7_nobodyElseCanWithdraw_403isAGuess() throws Exception {
        long main = speaker();
        long other = speaker();
        long co = speaker();
        long t = talk(main);
        assertEquals(200, send(put("/talks/" + t + "/co-speakers/" + co, null)).statusCode());
        assertEquals(403, withdraw(t, other).statusCode());
        assertEquals(403, withdraw(t, co).statusCode());
        assertEquals(403, withdraw(t, 999_999_999L).statusCode());
        assertEquals("SUBMITTED", statusOf(t));
    }

    @Test
    void a9_statusShowsWhereverTheTalkIsShown() throws Exception {
        long s = speaker();
        long t = talk(s);
        ok(decide(t, "WAITLISTED"));
        assertEquals("WAITLISTED", statusOf(t));
        assertEquals("WAITLISTED", get("/speakers/" + s + "/talks").get(0).get("status").asText());
        assertEquals("WAITLISTED", get("/talks?speakerId=" + s).get(0).get("status").asText());
        assertEquals("WAITLISTED", get("/talks?sort=rating&speakerId=" + s).get(0).get("status").asText());
    }

    @Test
    void a12_badInput() throws Exception {
        long s = speaker();
        long t = talk(s);
        for (String body : new String[] {"{}", "{\"status\":null}", "{\"status\":\"SUBMITTED\"}",
                "{\"status\":\"WITHDRAWN\"}", "{\"status\":\"accepted\"}", "{\"status\":\"MAYBE\"}", "{\"status\":1}"}) {
            assertEquals(400, send(put("/talks/" + t + "/status", body)).statusCode(), body);
        }
        for (String body : new String[] {"{}", "{\"speakerId\":null}", "{\"speakerId\":\"" + s + "\"}",
                "{\"speakerId\":1.5}"}) {
            assertEquals(400, send(post("/talks/" + t + "/withdraw", body)).statusCode(), body);
        }
        assertEquals(400, send(put("/talks/" + t + "/status", null)).statusCode());
        assertEquals(400, send(post("/talks/" + t + "/withdraw", null)).statusCode());
        assertEquals("SUBMITTED", statusOf(t));

        assertEquals(404, decide(999_999_999L, "REJECTED").statusCode());
        assertEquals(404, withdraw(999_999_999L, s).statusCode());
    }

    @Test
    void a11_concurrentDecisionsHaveOneWinner() throws Exception {
        for (int round = 0; round < 5; round++) {
            long t = ratedTalk();
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                for (String d : new String[] {"ACCEPTED", "REJECTED"}) {
                    sent.add(http.sendAsync(put("/talks/" + t + "/status", "{\"status\":\"" + d + "\"}"),
                            HttpResponse.BodyHandlers.ofString()));
                }
            }
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                int code = f.join().statusCode();
                assertTrue(code == 200 || code == 409, "status " + code);
                if (code == 200) {
                    ok++;
                }
            }
            assertEquals(1, ok, "exactly one final decision wins");
            String end = statusOf(t);
            assertTrue(end.equals("ACCEPTED") || end.equals("REJECTED"), end);
        }
    }

    @Test
    void a11_concurrentWithdrawAndRejectEndInAnAllowedState() throws Exception {
        for (int round = 0; round < 5; round++) {
            long s = speaker();
            long t = talk(s);
            CompletableFuture<HttpResponse<String>> reject = http.sendAsync(
                    put("/talks/" + t + "/status", "{\"status\":\"REJECTED\"}"), HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> withdraw = http.sendAsync(
                    post("/talks/" + t + "/withdraw", "{\"speakerId\":" + s + "}"), HttpResponse.BodyHandlers.ofString());
            int r = reject.join().statusCode();
            int w = withdraw.join().statusCode();
            String end = statusOf(t);
            // either order is fine, but both can't win, and the end state matches the winner
            assertEquals(1, (r == 200 ? 1 : 0) + (w == 200 ? 1 : 0), "reject " + r + ", withdraw " + w);
            assertEquals(r == 200 ? "REJECTED" : "WITHDRAWN", end);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long ratedTalk() throws Exception {
        long t = talk();
        rate(t, 1, 4);
        rate(t, 2, 5);
        return t;
    }

    private long talk() throws Exception {
        return talk(speaker());
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Deciding Speaker\",\"email\":\"a-" + UUID.randomUUID() + "@example.com\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speakerId) throws Exception {
        HttpResponse<String> res = send(post("/talks", "{\"title\":\"Decide me\",\"format\":\"WORKSHOP\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private void rate(long talk, int reviewer, int score) throws Exception {
        HttpResponse<String> res = send(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + score + "}"));
        assertEquals(200, res.statusCode(), res.body());
    }

    private HttpResponse<String> decide(long talk, String status) throws Exception {
        return send(put("/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"));
    }

    private HttpResponse<String> withdraw(long talk, long speakerId) throws Exception {
        return send(post("/talks/" + talk + "/withdraw", "{\"speakerId\":" + speakerId + "}"));
    }

    private String statusOf(long talk) throws Exception {
        return get("/talks/" + talk).get("status").asText();
    }

    private JsonNode ok(HttpResponse<String> res) throws Exception {
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private static String status(JsonNode talk) {
        return talk.get("status").asText();
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri(path)).GET().build());
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
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
