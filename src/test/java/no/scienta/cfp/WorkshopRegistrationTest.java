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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Workshop registration, rule by rule (W1-W11 in workflow/specs/workshop-registration.md). Runs the real app on a
 * random port with a throwaway database. Tests marked "guess" check a decision the feature request leaves open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WorkshopRegistrationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void w1_registerAndReadBackAsTyped() throws Exception {
        long w = acceptedWorkshop();
        HttpResponse<String> res = register(w, "Ada Lovelace", "Ada.Lovelace@Example.com");
        assertEquals(201, res.statusCode(), res.body());
        JsonNode r = json.readTree(res.body());
        assertTrue(r.get("id").isIntegralNumber());
        assertEquals(w, r.get("workshopId").asLong());
        assertEquals("Ada Lovelace", r.get("name").asText());
        assertEquals("Ada.Lovelace@Example.com", r.get("email").asText());

        JsonNode list = get("/workshops/" + w + "/registrations");
        assertEquals(1, list.size());
        assertEquals(r, list.get(0));
    }

    @Test
    void w1w2_nameAndEmailRequired() throws Exception {
        long w = acceptedWorkshop();
        for (String body : new String[] {"{}", "{\"name\":\"A\"}", "{\"email\":\"" + email() + "\"}",
                "{\"name\":\"\",\"email\":\"" + email() + "\"}", "{\"name\":\"  \",\"email\":\"" + email() + "\"}",
                "{\"name\":\"A\",\"email\":\"\"}", "{\"name\":\"A\",\"email\":null}", "{\"name\":7,\"email\":\"" + email() + "\"}",
                "{\"name\":\"A\",\"email\":\"not-an-email\"}"}) {
            assertEquals(400, send(post("/workshops/" + w + "/registrations", body)).statusCode(), body);
        }
        assertEquals(400, send(post("/workshops/" + w + "/registrations", null)).statusCode());
        assertEquals(0, get("/workshops/" + w + "/registrations").size());
    }

    @Test
    void w3_onlyWorkshops_409LikeNotAccepted() throws Exception {
        // the harness's check-2 (tick 119): an accepted LIGHTNING_20 talk gets the same 409 as a waitlisted workshop
        long talk = talk(speaker(), "LIGHTNING_20");
        rateTwice(talk);
        ok(decide(talk, "ACCEPTED"));
        assertEquals(409, register(talk, "A", email()).statusCode());
        assertEquals(0, get("/workshops/" + talk + "/registrations").size());
        assertEquals(404, register(999_999_999L, "A", email()).statusCode());
        assertEquals(400, send(post("/workshops/abc/registrations", "{\"name\":\"A\",\"email\":\"" + email() + "\"}")).statusCode());
    }

    @Test
    void w4_onlyAcceptedWorkshops_409isAGuess() throws Exception {
        long submitted = talk(speaker(), "WORKSHOP");
        assertEquals(409, register(submitted, "A", email()).statusCode());

        long waitlisted = talk(speaker(), "WORKSHOP");
        ok(decide(waitlisted, "WAITLISTED"));
        assertEquals(409, register(waitlisted, "A", email()).statusCode());

        long rejected = talk(speaker(), "WORKSHOP");
        ok(decide(rejected, "REJECTED"));
        assertEquals(409, register(rejected, "A", email()).statusCode());

        assertEquals(0, get("/workshops/" + submitted + "/registrations").size());
    }

    @Test
    void w5_withdrawnLaterKeepsItsListButTakesNoMore() throws Exception {
        long s = speaker();
        long w = talk(s, "WORKSHOP");
        rateTwice(w);
        ok(decide(w, "ACCEPTED"));
        assertEquals(201, register(w, "Early Bird", email()).statusCode());
        assertEquals(200, send(post("/talks/" + w + "/withdraw", "{\"speakerId\":" + s + "}")).statusCode());

        assertEquals(1, get("/workshops/" + w + "/registrations").size());
        assertEquals(409, register(w, "Late Comer", email()).statusCode());
        assertEquals(1, get("/workshops/" + w + "/registrations").size());
    }

    @Test
    void w6w10_twentyAndNeverMoreInTheOrderTheyRegistered() throws Exception {
        long w = acceptedWorkshop();
        List<String> emails = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String e = email();
            emails.add(e);
            assertEquals(201, register(w, "Attendee " + i, e).statusCode());
        }
        assertEquals(409, register(w, "Number 21", email()).statusCode());
        JsonNode list = get("/workshops/" + w + "/registrations");
        assertEquals(20, list.size());
        for (int i = 0; i < 20; i++) {
            assertEquals(emails.get(i), list.get(i).get("email").asText());
        }
    }

    @Test
    void w8w9_oneRegistrationPerEmailPerWorkshopWhateverTheCase() throws Exception {
        long w1 = acceptedWorkshop();
        long w2 = acceptedWorkshop();
        String e = "grace-" + UUID.randomUUID() + "@example.com";
        assertEquals(201, register(w1, "Grace", e).statusCode());
        assertEquals(409, register(w1, "Grace", e).statusCode());
        assertEquals(409, register(w1, "Grace Again", e.toUpperCase()).statusCode());
        assertEquals(1, get("/workshops/" + w1 + "/registrations").size());
        assertEquals(201, register(w2, "Grace", e.toUpperCase()).statusCode()); // another workshop is fine
    }

    @Test
    void w7_aRushNeverOverfills() throws Exception {
        for (int round = 0; round < 3; round++) {
            long w = acceptedWorkshop();
            List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                sent.add(http.sendAsync(post("/workshops/" + w + "/registrations",
                        "{\"name\":\"Rush " + i + "\",\"email\":\"" + email() + "\"}"), HttpResponse.BodyHandlers.ofString()));
            }
            int created = 0;
            for (CompletableFuture<HttpResponse<String>> f : sent) {
                int code = f.join().statusCode();
                assertTrue(code == 201 || code == 409, "status " + code);
                if (code == 201) {
                    created++;
                }
            }
            assertEquals(20, created);
            assertEquals(20, get("/workshops/" + w + "/registrations").size());
        }
    }

    @Test
    void w7w8_theSameEmailInARushRegistersOnce() throws Exception {
        long w = acceptedWorkshop();
        String e = "racer-" + UUID.randomUUID() + "@example.com";
        List<CompletableFuture<HttpResponse<String>>> sent = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String variant = i % 2 == 0 ? e : e.toUpperCase();
            sent.add(http.sendAsync(post("/workshops/" + w + "/registrations",
                    "{\"name\":\"Racer\",\"email\":\"" + variant + "\"}"), HttpResponse.BodyHandlers.ofString()));
        }
        int created = 0;
        for (CompletableFuture<HttpResponse<String>> f : sent) {
            int code = f.join().statusCode();
            assertTrue(code == 201 || code == 409, "status " + code);
            if (code == 201) {
                created++;
            }
        }
        assertEquals(1, created);
        assertEquals(1, get("/workshops/" + w + "/registrations").size());
    }

    // ------------------------------------------------------------------ helpers

    private long acceptedWorkshop() throws Exception {
        long w = talk(speaker(), "WORKSHOP");
        rateTwice(w);
        ok(decide(w, "ACCEPTED"));
        return w;
    }

    private void rateTwice(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            HttpResponse<String> res = send(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}"));
            assertEquals(200, res.statusCode(), res.body());
        }
    }

    private HttpResponse<String> decide(long talk, String status) throws Exception {
        return send(put("/talks/" + talk + "/status", "{\"status\":\"" + status + "\"}"));
    }

    private HttpResponse<String> register(long workshop, String name, String email) throws Exception {
        return send(post("/workshops/" + workshop + "/registrations",
                "{\"name\":\"" + name + "\",\"email\":\"" + email + "\"}"));
    }

    private long speaker() throws Exception {
        HttpResponse<String> res = send(post("/speakers",
                "{\"name\":\"Workshop Host\",\"email\":\"" + email() + "\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speakerId, String format) throws Exception {
        HttpResponse<String> res = send(post("/talks", "{\"title\":\"Hands on\",\"format\":\"" + format + "\","
                + "\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private static String email() {
        return "w-" + UUID.randomUUID() + "@example.com";
    }

    private JsonNode ok(HttpResponse<String> res) throws Exception {
        assertEquals(200, res.statusCode(), res.body());
        return json.readTree(res.body());
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
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
