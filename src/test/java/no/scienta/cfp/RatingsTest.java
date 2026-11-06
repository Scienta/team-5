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
 * Ratings spec and its change request: averageRating and ratingCount on every talk, trimming at 5+ ratings, 2 decimals, the ranking, replacing a rating, and
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
        rate(talk, 4, 5); // 4 ratings: no trimming; 4.25 stays 4.25 (2 decimals, ratings change request)

        JsonNode one = get("/talks/" + talk);
        assertEquals(4.25, one.get("averageRating").asDouble());
        assertEquals(4, one.get("ratingCount").asInt());

        JsonNode mine = get("/speakers/" + speaker + "/talks").get(0);
        assertEquals(4.25, mine.get("averageRating").asDouble());
        assertEquals(4, mine.get("ratingCount").asInt());

        JsonNode listed = get("/talks?speakerId=" + speaker).get(0);
        assertEquals(4.25, listed.get("averageRating").asDouble());
        assertEquals(4, listed.get("ratingCount").asInt());

        JsonNode all = get("/talks");
        boolean found = false;
        for (JsonNode t : all) {
            if (t.get("id").asLong() == talk) {
                assertEquals(4.25, t.get("averageRating").asDouble());
                assertEquals(4, t.get("ratingCount").asInt());
                found = true;
            }
        }
        assertTrue(found);
    }

    @Test
    void cr_fiveOrMoreRatingsLeaveOutExactlyOneHighestAndOneLowest() throws Exception {
        long four = talk(speaker());
        for (int[] r : new int[][] {{1, 5}, {2, 5}, {3, 5}, {4, 1}}) {
            rate(four, r[0], r[1]);
        }
        JsonNode t4 = get("/talks/" + four);
        assertEquals(4.0, t4.get("averageRating").asDouble()); // 4 ratings: every score counts, 16 / 4
        assertEquals(4, t4.get("ratingCount").asInt());

        long five = talk(speaker());
        for (int[] r : new int[][] {{1, 5}, {2, 5}, {3, 5}, {4, 1}, {5, 2}}) {
            rate(five, r[0], r[1]);
        }
        JsonNode t5 = get("/talks/" + five);
        assertEquals(4.0, t5.get("averageRating").asDouble()); // one 5 and the 1 left out: (5 + 5 + 2) / 3
        assertEquals(5, t5.get("ratingCount").asInt()); // still counts everyone

        long ties = talk(speaker());
        for (int[] r : new int[][] {{1, 1}, {2, 1}, {3, 4}, {4, 5}, {5, 5}, {6, 3}}) {
            rate(ties, r[0], r[1]);
        }
        // exactly one 5 and one 1 left out, though two reviewers gave each: (1 + 4 + 5 + 3) / 4 = 3.25
        assertEquals(3.25, get("/talks/" + ties).get("averageRating").asDouble());
    }

    @Test
    void cr_twoDecimalsWithBankersRounding() throws Exception {
        long t = talk(speaker()); // 10 ratings: one 5 and one 1 left out, the other 8 sum to 25: 3.125 -> 3.12
        int[] scores = {5, 1, 4, 4, 3, 3, 3, 3, 3, 2};
        for (int i = 0; i < scores.length; i++) {
            rate(t, i + 1, scores[i]);
        }
        assertEquals(3.12, get("/talks/" + t).get("averageRating").asDouble());
        long u = talk(speaker()); // the other 8 sum to 27: 3.375 -> 3.38
        int[] more = {5, 1, 4, 4, 4, 3, 3, 3, 3, 3};
        for (int i = 0; i < more.length; i++) {
            rate(u, i + 1, more[i]);
        }
        assertEquals(3.38, get("/talks/" + u).get("averageRating").asDouble());
        long third = talk(speaker()); // 3 ratings: 10 / 3 = 3.333... -> 3.33
        rate(third, 1, 3);
        rate(third, 2, 3);
        rate(third, 3, 4);
        assertEquals(3.33, get("/talks/" + third).get("averageRating").asDouble());
    }

    @Test
    void cr_theRankingUsesTheTrimmedAverage() throws Exception {
        long s = speaker();
        long trimmed = talk(s); // 5, 5, 5, 5, 1: 4.2 untrimmed, 5.0 trimmed
        for (int i = 1; i <= 4; i++) {
            rate(trimmed, i, 5);
        }
        rate(trimmed, 5, 1);
        long plain = talk(s); // 5, 5, 5, 4: 4.75, 4 ratings so not trimmed
        for (int i = 1; i <= 3; i++) {
            rate(plain, i, 5);
        }
        rate(plain, 4, 4);
        List<Long> ids = new ArrayList<>();
        for (JsonNode t : get("/talks?sort=rating&speakerId=" + s)) {
            ids.add(t.get("id").asLong());
        }
        assertEquals(List.of(trimmed, plain), ids);
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
        long d = talk(s2); // 4.25, best
        rate(a, 1, 4);
        rate(b, 1, 4);
        rate(b, 2, 4);
        rate(c, 1, 3);
        rate(c, 2, 5);
        rate(d, 1, 5);
        rate(d, 2, 4);
        rate(d, 3, 4);
        rate(d, 4, 4); // 17 / 4 = 4.25

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
