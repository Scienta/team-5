package no.scienta.cfp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What clients rely on beyond the happy path, and what a framework upgrade changes silently: the Boot 2.7 to 4 upgrade
 * broke each of these until it was pinned down (trailing slashes, the date formats, field order, comments in JSON,
 * the charset and message of error responses). Runs the real app on a random port with a throwaway database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiCompatibilityTest {

    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.fresh(registry);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void speakerUrlsWorkWithATrailingSlash() throws Exception {
        HttpResponse<String> created = send("POST", "/speakers/", speaker("Grace Hopper"));
        assertEquals(201, created.statusCode(), created.body());
        String id = id(created.body());

        HttpResponse<String> found = send("GET", "/speakers/" + id + "/", null);
        assertEquals(200, found.statusCode(), found.body());
        assertEquals(created.body(), found.body());
    }

    @Test
    void talkDatesHaveTheLegacyFormatsAndFieldsKeepTheirOrder() throws Exception {
        String speaker = id(send("POST", "/speakers", speaker("Ada Lovelace")).body());
        HttpResponse<String> res = send("POST", "/talks",
                "{\"title\":\"Legacy\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":" + speaker + "}");
        assertEquals(201, res.statusCode(), res.body());

        String body = res.body();
        assertTrue(body.matches(".*\"submittedAt\":\"\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}\\+00:00\".*"), body);
        assertTrue(body.matches(".*\"submittedOn\":\"\\d{4}-\\d{2}-\\d{2}\".*"), body);
        assertTrue(body.matches("\\{\"id\":\\d+,\"title\":\"Legacy\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\","
                + "\"speakerId\":\\d+,\"durationMinutes\":120,\"status\":\"SUBMITTED\",\"submittedAt\":\"[^\"]+\","
                + "\"submittedOn\":\"[^\"]+\",\"averageRating\":null,\"ratingCount\":0,\"coSpeakerIds\":\\[\\]}"), body);
    }

    @Test
    void jsonWithCommentsIsAccepted() throws Exception {
        HttpResponse<String> res = send("POST", "/speakers",
                "{\"name\":\"Old Client\" /* the 2019 mobile client */,\"email\":\"" + email() + "\"}");
        assertEquals(201, res.statusCode(), res.body());
    }

    @Test
    void errorsAreUtf8JsonWithTheMessage() throws Exception {
        HttpResponse<String> res = send("GET", "/speakers/999999", null);
        assertEquals(404, res.statusCode());
        assertEquals("application/json;charset=UTF-8", res.headers().firstValue("Content-Type").orElse(""));
        assertTrue(res.body().contains("\"message\":\"speaker not found\""), res.body());
        assertTrue(res.body().matches(".*\"timestamp\":\"[^\"]+\\+00:00\".*"), res.body());
    }

    private static String speaker(String name) {
        return "{\"name\":\"" + name + "\",\"email\":\"" + email() + "\"}";
    }

    private static String email() {
        return "speaker-" + UUID.randomUUID() + "@example.com";
    }

    private static String id(String body) {
        Matcher m = ID.matcher(body);
        assertTrue(m.find(), body);
        return m.group(1);
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
