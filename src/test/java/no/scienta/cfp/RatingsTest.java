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
 * Ratings spec: averageRating and ratingCount on every talk, half-up rounding, the ranking, replacing a rating, and
 * concurrent ratings. Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RatingsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void averageIsRoundedHalfUpAndShownWhereverTheTalkIsShown() throws Exception {
        long speaker = speaker();
        long talk = talk(speaker);
        rate(talk, 1, 4);
        rate(talk, 2, 4);
        rate(talk, 3, 4);
        rate(talk, 4, 5); // 4.25 -> 4.3

        JsonNode one = get("/talks/" + talk);
        assertEquals(4.3, one.get("averageRating").asDouble());
        assertEquals(4, one.get("ratingCount").asInt());

        JsonNode mine = get("/speakers/" + speaker + "/talks").get(0);
        assertEquals(4.3, mine.get("averageRating").asDouble());
        assertEquals(4, mine.get("ratingCount").asInt());

        JsonNode listed = get("/talks?speakerId=" + speaker).get(0);
        assertEquals(4.3, listed.get("averageRating").asDouble());
        assertEquals(4, listed.get("ratingCount").asInt());

        JsonNode all = get("/talks");
        boolean found = false;
        for (JsonNode t : all) {
            if (t.get("id").asLong() == talk) {
                assertEquals(4.3, t.get("averageRating").asDouble());
                assertEquals(4, t.get("ratingCount").asInt());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void anUnratedTalkHasNoAverageAndCountZero() throws Exception {
        long talk = talk(speaker());
        JsonNode t = get("/talks/" + talk);
        assertTrue(t.get("averageRating").isNull());
        assertEquals(0, t.get("ratingCount").asInt());
    }

    @Test
    void ratingAgainReplacesTheScore() throws Exception {
        long talk = talk(speaker());
        rate(talk, 1, 2);
        HttpResponse<String> again = rate(talk, 1, 5);
        assertEquals(200, again.statusCode());
        JsonNode t = get("/talks/" + talk);
        assertEquals(5.0, t.get("averageRating").asDouble());
        assertEquals(1, t.get("ratingCount").asInt());
    }

    @Test
    void rankingIsRoundedAverageThenCountThenOldestWithUnratedLast() throws Exception {
        long s1 = speaker();
        long s2 = speaker();
        long unrated = talk(s1);
        long a = talk(s1); // 4.0 from 1 reviewer
        long b = talk(s1); // 4.0 from 2 reviewers -> before a
        long c = talk(s2); // 4.0 from 2 reviewers, newer than b -> after b
        long d = talk(s2); // 4.26 -> 4.3, best
        rate(a, 1, 4);
        rate(b, 1, 4);
        rate(b, 2, 4);
        rate(c, 1, 3);
        rate(c, 2, 5);
        rate(d, 1, 5);
        rate(d, 2, 4);
        rate(d, 3, 4);
        rate(d, 4, 4); // 17 / 4 = 4.25 -> 4.3

        List<Long> ids = new ArrayList<>();
        for (JsonNode t : get("/talks?sort=rating")) {
            long id = t.get("id").asLong();
            if (id == unrated || id == a || id == b || id == c || id == d) {
                ids.add(id);
            }
        }
        assertEquals(List.of(d, b, c, a, unrated), ids);
    }

    @Test
    void concurrentRatingsAreAllCountedOnce() throws Exception {
        long talk = talk(speaker());
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int reviewer = 1; reviewer <= 20; reviewer++) {
            for (int click = 0; click < 2; click++) { // every reviewer double-clicks
                sent.add(http.sendAsync(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":5}"),
                        HttpResponse.BodyHandlers.ofString()));
            }
        }
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            assertEquals(200, f.join().statusCode());
        }
        JsonNode t = get("/talks/" + talk);
        assertEquals(20, t.get("ratingCount").asInt());
        assertEquals(5.0, t.get("averageRating").asDouble());
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Reviewed Speaker\",\"email\":\"r-" + UUID.randomUUID() + "@example.com\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speakerId) throws Exception {
        HttpResponse<String> res = send(post("/talks", "{\"title\":\"Rated\",\"format\":\"PRESENTATION\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private HttpResponse<String> rate(long talk, int reviewer, int score) throws Exception {
        HttpResponse<String> res = send(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":" + score + "}"));
        assertEquals(200, res.statusCode(), res.body());
        return res;
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
