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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Co-speakers, rule by rule (C1-C12 in workflow/specs/co-speakers.md). Runs the real app on a random port with a
 * throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CoSpeakersTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void c1c2c5_addAndRemoveInTheOrderAdded() throws Exception {
        long main = speaker();
        long a = speaker();
        long b = speaker();
        long talk = talk(main);

        assertEquals(List.of(a), ids(ok(put(talk, a))));
        assertEquals(List.of(a, b), ids(ok(put(talk, b))));
        assertEquals(List.of(a, b), ids(get("/talks/" + talk)));

        assertEquals(List.of(b), ids(ok(delete(talk, a))));
        assertEquals(List.of(b, a), ids(ok(put(talk, a)))); // added again: goes to the end
        assertEquals(List.of(b, a), ids(get("/talks/" + talk)));
    }

    @Test
    void c3_atMostTwo() throws Exception {
        long talk = talk(speaker());
        long a = speaker();
        long b = speaker();
        long c = speaker();
        ok(put(talk, a));
        ok(put(talk, b));
        HttpResponse<String> third = put(talk, c);
        assertEquals(409, third.statusCode(), third.body());
        assertEquals(List.of(a, b), ids(get("/talks/" + talk)));
    }

    @Test
    void c4_mainSpeakerCantCoSpeak() throws Exception {
        long main = speaker();
        long talk = talk(main);
        HttpResponse<String> res = put(talk, main);
        assertEquals(400, res.statusCode(), res.body());
        assertEquals(List.of(), ids(get("/talks/" + talk)));
    }

    @Test
    void c6_addingAgainChangesNothingEvenAtTwo() throws Exception {
        long talk = talk(speaker());
        long a = speaker();
        long b = speaker();
        ok(put(talk, a));
        ok(put(talk, b));
        assertEquals(List.of(a, b), ids(ok(put(talk, a))));
        assertEquals(List.of(a, b), ids(ok(put(talk, b))));
        assertEquals(List.of(a, b), ids(get("/talks/" + talk)));
    }

    @Test
    void c7_everyTalkShowsItsCoSpeakers() throws Exception {
        long main = speaker();
        long a = speaker();
        JsonNode created = json.readTree(send(post("/talks", talkJson(main))).body());
        assertTrue(created.get("coSpeakerIds").isArray());
        assertEquals(0, created.get("coSpeakerIds").size());
        long talk = created.get("id").asLong();
        ok(put(talk, a));

        assertEquals(List.of(a), ids(get("/talks/" + talk)));
        assertEquals(List.of(a), ids(get("/speakers/" + main + "/talks").get(0)));
        assertEquals(List.of(a), ids(get("/talks?speakerId=" + main).get(0)));
        assertEquals(List.of(a), ids(get("/talks?sort=rating&speakerId=" + main).get(0)));
        boolean found = false;
        for (JsonNode t : get("/talks")) {
            assertTrue(t.get("coSpeakerIds").isArray());
            if (t.get("id").asLong() == talk) {
                assertEquals(List.of(a), ids(t));
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void c8_aSpeakersOwnListShowsOnlyTheirMainTalks() throws Exception {
        long main = speaker();
        long co = speaker();
        long talk = talk(main);
        ok(put(talk, co));
        assertEquals(0, get("/speakers/" + co + "/talks").size());
        assertEquals(0, get("/talks?speakerId=" + co).size());
    }

    @Test
    void c9_coSpeakingDoesntUseUpSubmissions() throws Exception {
        long busy = speaker();
        for (int i = 0; i < 3; i++) {
            talk(busy);
        }
        assertEquals(409, send(post("/talks", talkJson(busy))).statusCode()); // at the limit
        long other = talk(speaker());
        ok(put(other, busy)); // can still co-speak

        long co = speaker();
        ok(put(other, co));
        for (int i = 0; i < 3; i++) {
            talk(co); // co-speaking used none of their 3
        }
    }

    @Test
    void c10_removingSomeoneWhoIsntACoSpeakerChangesNothing() throws Exception {
        long main = speaker();
        long a = speaker();
        long b = speaker();
        long talk = talk(main);
        ok(put(talk, a));
        assertEquals(List.of(a), ids(ok(delete(talk, b))));
        assertEquals(List.of(a), ids(ok(delete(talk, main))));
    }

    @Test
    void c11_unknownTalkOrSpeakerIs404() throws Exception {
        long talk = talk(speaker());
        long a = speaker();
        assertEquals(404, put(999_999_999L, a).statusCode());
        assertEquals(404, put(talk, 999_999_999L).statusCode());
        assertEquals(404, delete(999_999_999L, a).statusCode());
        assertEquals(404, delete(talk, 999_999_999L).statusCode());
        assertEquals(400, send(HttpRequest.newBuilder(uri("/talks/" + talk + "/co-speakers/abc"))
                .PUT(HttpRequest.BodyPublishers.noBody()).build()).statusCode());
    }

    @Test
    void c12_concurrentAddsNeverMakeThree() throws Exception {
        for (int round = 0; round < 5; round++) {
            long talk = talk(speaker());
            List<Long> candidates = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                candidates.add(speaker());
            }
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (long s : candidates) {
                for (int click = 0; click < 2; click++) {
                    sent.add(http.sendAsync(putRequest(talk, s), HttpResponse.BodyHandlers.ofString()));
                }
            }
            int ok = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                int status = f.join().statusCode();
                assertTrue(status == 200 || status == 409, "status " + status);
                if (status == 200) {
                    ok++;
                }
            }
            List<Long> after = ids(get("/talks/" + talk));
            assertEquals(2, after.size(), after.toString());
            assertTrue(candidates.containsAll(after));
            assertTrue(ok >= 2);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Co Speaker\",\"email\":\"co-" + UUID.randomUUID() + "@example.com\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speakerId) throws Exception {
        HttpResponse<String> res = send(post("/talks", talkJson(speakerId)));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private static String talkJson(long speakerId) {
        return "{\"title\":\"Together\",\"format\":\"PRESENTATION\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}";
    }

    private HttpResponse<String> put(long talk, long speaker) throws Exception {
        return send(putRequest(talk, speaker));
    }

    private HttpRequest putRequest(long talk, long speaker) {
        return HttpRequest.newBuilder(uri("/talks/" + talk + "/co-speakers/" + speaker))
                .PUT(HttpRequest.BodyPublishers.noBody()).build();
    }

    private HttpResponse<String> delete(long talk, long speaker) throws Exception {
        return send(HttpRequest.newBuilder(uri("/talks/" + talk + "/co-speakers/" + speaker)).DELETE().build());
    }

    private JsonNode ok(HttpResponse<String> res) throws Exception {
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private List<Long> ids(JsonNode talk) {
        List<Long> ids = new ArrayList<>();
        talk.get("coSpeakerIds").forEach(n -> ids.add(n.asLong()));
        return ids;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> res = send(HttpRequest.newBuilder(uri(path)).GET().build());
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
