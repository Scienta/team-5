package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Submission cap change request, rule by rule (SC1-SC8 in workflow/specs/submission-cap-cr.md): the cap of 3 now
 * counts co-speaking too, and leaves out rejected and withdrawn talks. Runs the real app on a random port with a
 * throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SubmissionCapTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sc1_coSpeakingCountsTowardTheCap() throws Exception {
        long s = speaker();
        for (int i = 0; i < 3; i++) {
            okAddCo(talk(speaker()), s); // co-speaks on three in-the-running talks
        }
        assertEquals(409, submit(s).statusCode()); // now at the cap: can't submit their own
        assertEquals(409, addCo(talk(speaker()), s).statusCode()); // and can't co-speak on a fourth
    }

    @Test
    void sc1_mainAndCoSpeakingCountTogether() throws Exception {
        long s = speaker();
        assertEquals(201, submit(s).statusCode());
        assertEquals(201, submit(s).statusCode()); // two of their own
        okAddCo(talk(speaker()), s); // plus one co-spoken = 3
        assertEquals(409, submit(s).statusCode());
    }

    @Test
    void sc2_rejectedAndWithdrawnDontCount() throws Exception {
        long s = speaker();
        long a = talkOf(s);
        long b = talkOf(s);
        long c = talkOf(s);
        assertEquals(409, submit(s).statusCode()); // three in the running

        reject(a);
        assertEquals(201, submit(s).statusCode()); // a no longer counts, place freed (SC6)

        withdraw(b, s);
        assertEquals(201, submit(s).statusCode()); // b no longer counts
        // s now has: a REJECTED, b WITHDRAWN, c SUBMITTED, two new SUBMITTED = 3 in the running
        assertEquals(409, submit(s).statusCode());
        assertFalse(c == 0);
    }

    @Test
    void sc2_aCoSpeakerOnARejectedTalkDoesntCount() throws Exception {
        long s = speaker();
        long rejected = talk(speaker());
        okAddCo(rejected, s);
        reject(rejected);
        // the rejected talk doesn't count, so s can still fill three in-the-running places
        assertEquals(201, submit(s).statusCode());
        assertEquals(201, submit(s).statusCode());
        assertEquals(201, submit(s).statusCode());
        assertEquals(409, submit(s).statusCode());
    }

    @Test
    void sc5_addedToARejectedOrWithdrawnTalkEvenAtTheCap() throws Exception {
        long s = speaker();
        for (int i = 0; i < 3; i++) {
            talkOf(s); // at the cap with three of their own
        }
        long rejected = talk(speaker());
        reject(rejected);
        assertEquals(200, addCo(rejected, s).statusCode()); // fine: a rejected talk doesn't count (SC5)

        long withdrawnTalk = talkFor(speaker());
        withdraw(withdrawnTalk, mainOf(withdrawnTalk));
        assertEquals(200, addCo(withdrawnTalk, s).statusCode());

        assertEquals(409, addCo(talk(speaker()), s).statusCode()); // but still not an in-the-running one
    }

    @Test
    void sc6_removingACoSpeakerFreesTheirPlace() throws Exception {
        long s = speaker();
        long t1 = talk(speaker());
        long t2 = talk(speaker());
        long t3 = talk(speaker());
        okAddCo(t1, s);
        okAddCo(t2, s);
        okAddCo(t3, s);
        assertEquals(409, submit(s).statusCode()); // at the cap through co-speaking

        assertEquals(200, remove(t3, s).statusCode()); // taken off t3
        assertEquals(201, submit(s).statusCode()); // place freed straight away
    }

    @Test
    void sc8_overTheLimitSpeakersKeepWhatTheyHave() throws Exception {
        // A speaker with three in-the-running talks keeps all three; the cap only blocks new ones.
        long s = speaker();
        long a = talkOf(s);
        long b = talkOf(s);
        long c = talkOf(s);
        assertEquals(409, submit(s).statusCode());
        for (long id : new long[] {a, b, c}) {
            assertEquals(200, get("/talks/" + id).statusCode());
        }
    }

    @Test
    void sc8_talkListStillShowsOnlyMainSpeakerTalks() throws Exception {
        long s = speaker();
        long own = talkOf(s);
        long coSpoken = talk(speaker());
        okAddCo(coSpoken, s);
        List<Long> mine = new ArrayList<>();
        for (JsonNode t : json.readTree(get("/speakers/" + s + "/talks").body())) {
            mine.add(t.get("id").asLong());
        }
        assertTrue(mine.contains(own));
        assertFalse(mine.contains(coSpoken), "co-spoken talks don't show in the main-speaker list");
    }

    @Test
    void sc7_submissionsAndCoSpeakerAddsPilingInHoldTheCap() throws Exception {
        for (int round = 0; round < 5; round++) {
            long s = speaker();
            List<HttpRequest> ops = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                ops.add(post("/talks", talkJson(s))); // submit as main
                ops.add(coRequest(talk(speaker()), s)); // co-speak on a distinct in-the-running talk
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (HttpRequest op : ops) {
                sent.add(http.sendAsync(op, HttpResponse.BodyHandlers.ofString()));
            }
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                int code = f.join().statusCode();
                assertTrue(code == 200 || code == 201 || code == 409, "status " + code);
                if (code == 200 || code == 201) {
                    ok++;
                }
            }
            assertEquals(3, ok, "exactly the cap of 3 places filled, whatever the mix");
        }
    }

    // ------------------------------------------------------------------ helpers

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Capped Speaker\",\"email\":\"cap-" + UUID.randomUUID() + "@example.com\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    /** Submit a talk for this speaker, asserting it was created; returns its id. */
    private long talkOf(long speakerId) throws Exception {
        HttpResponse<String> res = submit(speakerId);
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    /** A fresh speaker's talk, as a target to co-speak on. */
    private long talk(long ownerId) throws Exception {
        return talkOf(ownerId);
    }

    private long talkFor(long ownerId) throws Exception {
        return talkOf(ownerId);
    }

    private long mainOf(long talkId) throws Exception {
        return json.readTree(get("/talks/" + talkId).body()).get("speakerId").asLong();
    }

    private HttpResponse<String> submit(long speakerId) throws Exception {
        return send(post("/talks", talkJson(speakerId)));
    }

    private String talkJson(long speakerId) {
        return "{\"title\":\"A talk\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}";
    }

    private HttpResponse<String> addCo(long talkId, long speakerId) throws Exception {
        return send(coRequest(talkId, speakerId));
    }

    private void okAddCo(long talkId, long speakerId) throws Exception {
        assertEquals(200, addCo(talkId, speakerId).statusCode());
    }

    private HttpRequest coRequest(long talkId, long speakerId) {
        return HttpRequest.newBuilder(uri("/talks/" + talkId + "/co-speakers/" + speakerId))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpResponse<String> remove(long talkId, long speakerId) throws Exception {
        return send(HttpRequest.newBuilder(uri("/talks/" + talkId + "/co-speakers/" + speakerId)).DELETE().build());
    }

    private void reject(long talkId) throws Exception {
        assertEquals(200, send(put("/talks/" + talkId + "/status", "{\"status\":\"REJECTED\"}")).statusCode());
    }

    private void withdraw(long talkId, long speakerId) throws Exception {
        assertEquals(200, send(post("/talks/" + talkId + "/withdraw",
                "{\"speakerId\":" + speakerId + "}")).statusCode());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(uri(path)).GET().build());
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpRequest put(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
