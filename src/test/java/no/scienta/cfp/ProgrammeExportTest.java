package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Programme export, rule by rule (E1-E12 in workflow/specs/programme-export.md). Runs the real app on a random port
 * with a throwaway database. Tests marked "guess" check a decision the feature request leaves open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProgrammeExportTest {

    private static final String HEADER = "slot,start,end,room,talkId,title,format,speakers\n";
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

    @BeforeEach
    void emptyProgramme() throws Exception {
        for (String room : ROOMS) {
            for (String slot : SLOTS) {
                send(HttpRequest.newBuilder(uri("/programme/" + room + "/" + slot)).DELETE().build());
            }
        }
    }

    @Test
    void e12_anEmptyProgrammeIsAnEmptyListOrJustTheHeader() throws Exception {
        assertEquals("[]", send(get("/programme")).body());
        HttpResponse<byte[]> csv = csv(null);
        assertEquals(200, csv.statusCode());
        assertArrayEquals(HEADER.getBytes(StandardCharsets.UTF_8), csv.body());
    }

    @Test
    void e1e2e3e4e6_everyScheduledTalkInDayOrderTheSameInBothViews() throws Exception {
        long grace = speaker("Grace Hopper");
        long ada = speaker("Ada Lovelace");
        long linus = speaker("Linus Torvalds");
        long workshop = accepted(talk(grace, "Debugging, hands on", "WORKSHOP"));
        coSpeaker(workshop, ada);
        coSpeaker(workshop, linus);
        long late = accepted(talk(ada, "Engines", "PRESENTATION"));
        long early = accepted(talk(linus, "Kernels", "LIGHTNING_10"));
        long sameSlot = accepted(talk(speaker("Barbara Liskov"), "Substitution", "LIGHTNING_20"));
        // put in out of order: the export sorts by start, then room
        schedule("R1", "S5", late);
        schedule("R3", "S1", early);
        schedule("R2", "S4", workshop);
        schedule("R1", "S1", sameSlot);

        JsonNode rows = json.readTree(send(get("/programme")).body());
        assertEquals(4, rows.size());
        assertEquals("{\"slot\":\"S1\",\"start\":\"09:00\",\"end\":\"10:00\",\"room\":\"R1\",\"talkId\":" + sameSlot
                + ",\"title\":\"Substitution\",\"format\":\"LIGHTNING_20\",\"speakers\":[\"Barbara Liskov\"]}",
                rows.get(0).toString());
        assertEquals(early, rows.get(1).get("talkId").asLong());
        assertEquals("{\"slot\":\"S4\",\"start\":\"13:30\",\"end\":\"15:30\",\"room\":\"R2\",\"talkId\":" + workshop
                + ",\"title\":\"Debugging, hands on\",\"format\":\"WORKSHOP\","
                + "\"speakers\":[\"Grace Hopper\",\"Ada Lovelace\",\"Linus Torvalds\"]}", rows.get(2).toString());
        assertEquals(late, rows.get(3).get("talkId").asLong());

        assertEquals(HEADER
                + "S1,09:00,10:00,R1," + sameSlot + ",Substitution,LIGHTNING_20,Barbara Liskov\n"
                + "S1,09:00,10:00,R3," + early + ",Kernels,LIGHTNING_10,Linus Torvalds\n"
                + "S4,13:30,15:30,R2," + workshop + ",\"Debugging, hands on\",WORKSHOP,"
                + "Grace Hopper; Ada Lovelace; Linus Torvalds\n"
                + "S5,15:45,16:45,R1," + late + ",Engines,PRESENTATION,Ada Lovelace\n",
                new String(csv(null).body(), StandardCharsets.UTF_8));
    }

    @Test
    void e5_speakersAsTheyAreNow() throws Exception {
        long grace = speaker("Grace Hopper");
        long t = accepted(talk(grace, "Compilers", "PRESENTATION"));
        schedule("R2", "S2", t);
        long ada = speaker("Ada Lovelace");
        coSpeaker(t, ada); // after scheduling
        HttpResponse<String> renamed = send(put("/speakers/" + grace,
                "{\"name\":\"Rear Admiral Grace Hopper\",\"email\":\"" + email() + "\",\"bio\":null}"));
        assertEquals(200, renamed.statusCode(), renamed.body());

        JsonNode row = json.readTree(send(get("/programme")).body()).get(0);
        assertEquals("[\"Rear Admiral Grace Hopper\",\"Ada Lovelace\"]", row.get("speakers").toString());
        assertTrue(new String(csv(null).body(), StandardCharsets.UTF_8)
                .endsWith(",Compilers,PRESENTATION,Rear Admiral Grace Hopper; Ada Lovelace\n"));
    }

    @Test
    void e1_aTalkWithdrawnAfterSchedulingIsStillOnTheProgramme_guess() throws Exception {
        long s = speaker("Grace Hopper");
        long t = accepted(talk(s, "Gone", "PRESENTATION"));
        schedule("R1", "S3", t);
        assertEquals(200, send(post("/talks/" + t + "/withdraw", "{\"speakerId\":" + s + "}")).statusCode());
        assertEquals(t, json.readTree(send(get("/programme")).body()).get(0).get("talkId").asLong());
    }

    @Test
    void e8_csvIsUtf8TextCsvEvenWhenJsonIsAskedFor() throws Exception {
        long t = accepted(talk(speaker("Søren Kierkegaard"), "Æsthetik – über alles", "PRESENTATION"));
        schedule("R1", "S2", t);
        for (String accept : new String[] {null, "application/json", "text/csv", "*/*"}) {
            HttpResponse<byte[]> res = csv(accept);
            assertEquals(200, res.statusCode(), "Accept " + accept);
            assertEquals("text/csv;charset=UTF-8", res.headers().firstValue("Content-Type").orElse(""), "Accept " + accept);
            String body = new String(res.body(), StandardCharsets.UTF_8);
            assertTrue(body.startsWith("slot,"), "no byte order mark");
            assertTrue(body.endsWith(",Æsthetik – über alles,PRESENTATION,Søren Kierkegaard\n"), body);
        }
    }

    @Test
    void e9e10_exactHeaderUnixLineEndingsNoBlankLineAtTheEnd() throws Exception {
        schedule("R1", "S1", accepted(talk(speaker("Grace Hopper"), "One", "PRESENTATION")));
        schedule("R2", "S1", accepted(talk(speaker("Ada Lovelace"), "Two", "PRESENTATION")));
        String body = new String(csv(null).body(), StandardCharsets.UTF_8);
        assertTrue(body.startsWith(HEADER), body);
        assertTrue(body.indexOf('\r') < 0, "no \\r");
        assertTrue(body.endsWith("\n") && !body.endsWith("\n\n"), "one \\n at the end");
        assertEquals(3, body.split("\n", -1).length - 1, "header and two rows, each ending in \\n");
    }

    @Test
    void e11_quotedOnlyWhenTheyHaveTo() throws Exception {
        long t1 = accepted(talk(speaker("Grace Hopper"), "Kotlin, \"really\" fast", "PRESENTATION"));
        long t2 = accepted(talk(speaker("Ada Lovelace"), "Line one\nline two", "PRESENTATION"));
        long t3 = accepted(talk(speaker("Hopper, Grace"), "Plain title", "PRESENTATION"));
        long t4 = accepted(talk(speaker("Linus Torvalds"), "Say \"hi\"", "PRESENTATION"));
        schedule("R1", "S1", t1);
        schedule("R2", "S1", t2);
        schedule("R3", "S1", t3);
        schedule("R1", "S2", t4);
        String body = new String(csv(null).body(), StandardCharsets.UTF_8);
        assertEquals(HEADER
                + "S1,09:00,10:00,R1," + t1 + ",\"Kotlin, \"\"really\"\" fast\",PRESENTATION,Grace Hopper\n"
                + "S1,09:00,10:00,R2," + t2 + ",\"Line one\nline two\",PRESENTATION,Ada Lovelace\n"
                + "S1,09:00,10:00,R3," + t3 + ",Plain title,PRESENTATION,\"Hopper, Grace\"\n"
                + "S2,10:15,11:15,R1," + t4 + ",\"Say \"\"hi\"\"\",PRESENTATION,Linus Torvalds\n", body);
    }

    @Test
    void e11_fieldQuoting() {
        assertEquals("plain", ProgrammeExportService.field("plain"));
        assertEquals(" spaced ", ProgrammeExportService.field(" spaced "));
        assertEquals("\"a,b\"", ProgrammeExportService.field("a,b"));
        assertEquals("\"a\"\"b\"", ProgrammeExportService.field("a\"b"));
        assertEquals("\"a\nb\"", ProgrammeExportService.field("a\nb"));
        assertEquals("\"a\rb\"", ProgrammeExportService.field("a\rb"));
        assertEquals("Grace Hopper; Ada Lovelace", ProgrammeExportService.field("Grace Hopper; Ada Lovelace"));
    }

    @Test
    void trailingSlashAnswersLikeWithout() throws Exception {
        HttpResponse<String> res = send(get("/programme/"));
        assertEquals(200, res.statusCode());
        assertEquals("[]", res.body());
    }

    // ------------------------------------------------------------------ helpers

    private static String email() {
        return "export-" + UUID.randomUUID() + "@example.com";
    }

    private long speaker(String name) throws Exception {
        HttpResponse<String> res = send(post("/speakers", "{\"name\":" + json.writeValueAsString(name)
                + ",\"email\":\"" + email() + "\"}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long talk(long speakerId, String title, String format) throws Exception {
        HttpResponse<String> res = send(post("/talks", "{\"title\":" + json.writeValueAsString(title)
                + ",\"format\":\"" + format + "\",\"level\":\"BEGINNER\",\"speakerId\":" + speakerId + "}"));
        assertEquals(201, res.statusCode(), res.body());
        return json.readTree(res.body()).get("id").asLong();
    }

    private long accepted(long talk) throws Exception {
        for (int reviewer = 1; reviewer <= 2; reviewer++) {
            assertEquals(200, send(put("/talks/" + talk + "/ratings/" + reviewer, "{\"score\":4}")).statusCode());
        }
        assertEquals(200, send(put("/talks/" + talk + "/status", "{\"status\":\"ACCEPTED\"}")).statusCode());
        return talk;
    }

    private void coSpeaker(long talk, long speaker) throws Exception {
        assertEquals(200, send(put("/talks/" + talk + "/co-speakers/" + speaker, null)).statusCode());
    }

    private void schedule(String room, String slot, long talk) throws Exception {
        HttpResponse<String> res = send(put("/programme/" + room + "/" + slot, "{\"talkId\":" + talk + "}"));
        assertEquals(200, res.statusCode(), res.body());
    }

    private HttpResponse<byte[]> csv(String accept) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri("/programme.csv")).GET();
        if (accept != null) {
            b.header("Accept", accept);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).GET().build();
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
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
