package no.scienta.cfp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * After start-up, sends this service a few rounds of the requests the harness sends, in parallel, so the first real
 * requests after a deploy don't pay for class loading, JIT and opening pool connections. On the platform's small CPU
 * a cold instance answered POST /speakers in 3-5 s and failed checks (tick 47, see REVIEW.md).
 * Read-only: every request is a read or is rejected before anything is written. Runs in the background; a failure
 * is logged and ignored. Off with cfp.warm-up.enabled=false.
 */
@Component
public class WarmUp {

    private static final Logger log = LoggerFactory.getLogger(WarmUp.class);

    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private int port;

    public WarmUp(JdbcTemplate jdbc, @Value("${cfp.warm-up.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @EventListener
    public void warmUp(ApplicationReadyEvent event) {
        // the port Tomcat actually got (tests use a random one)
        port = event.getApplicationContext().getEnvironment().getProperty("local.server.port", Integer.class, 8080);
        if (enabled) {
            Thread t = new Thread(this::run, "warm-up");
            t.setDaemon(true);
            t.start();
        }
    }

    private void run() {
        long start = System.currentTimeMillis();
        try {
            // A real talk and speaker, if there are any, so a full Talk gets serialised too.
            List<Long> talk = jdbc.queryForList("SELECT id FROM talk ORDER BY id DESC LIMIT 1", Long.class);
            List<Long> speaker = jdbc.queryForList("SELECT speaker_id FROM talk ORDER BY id DESC LIMIT 1", Long.class);
            long t = talk.isEmpty() ? 0 : talk.get(0);
            long s = speaker.isEmpty() ? 0 : speaker.get(0);

            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            List<HttpRequest> requests = List.of(
                    get("/health"),
                    get("/actuator/info"),
                    get("/speakers/" + s), get("/speakers/" + s + "/"), get("/speakers/0"),
                    get("/talks/" + t), get("/talks/0"),
                    get("/speakers/" + s + "/talks"),
                    get("/talks?speakerId=" + s), get("/talks?sort=rating&speakerId=" + s),
                    // rejected before any write: invalid email, blank name, unknown speaker, unknown talk
                    send("POST", "/speakers", "{\"name\":\"Warm Up\",\"email\":\"not-an-email\"}"),
                    send("POST", "/speakers", "{\"name\":\"\",\"email\":\"warm-up@example.com\"}"),
                    send("POST", "/talks", "{\"title\":\"Warm up\",\"format\":\"WORKSHOP\",\"level\":\"BEGINNER\",\"speakerId\":0}"),
                    send("PUT", "/talks/0/ratings/1", "{\"score\":3}"),
                    send("PUT", "/talks/" + t + "/ratings/1", "{\"score\":9}"),
                    send("PUT", "/talks/0/co-speakers/1", null));
            for (int round = 0; round < 3; round++) {
                List<CompletableFuture<HttpResponse<Void>>> sent = new ArrayList<>();
                for (HttpRequest r : requests) {
                    sent.add(http.sendAsync(r, HttpResponse.BodyHandlers.discarding()));
                }
                CompletableFuture.allOf(sent.toArray(new CompletableFuture[0])).join();
            }
            log.info("warm-up done in {} ms", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("warm-up failed after {} ms: {}", System.currentTimeMillis() - start, e.toString());
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30)).GET().build();
    }

    private HttpRequest send(String method, String path, String json) {
        return HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
