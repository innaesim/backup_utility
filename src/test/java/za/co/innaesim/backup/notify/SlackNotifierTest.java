package za.co.innaesim.backup.notify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import za.co.innaesim.backup.service.RunReport;
import com.sun.net.httpserver.HttpServer;

class SlackNotifierTest {
    private HttpServer server;
    private final List<String> bodies = new ArrayList<>();
    private int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
    }

    private static RunReport report(boolean ok, String error) {
        Map<String, String> destinations = new LinkedHashMap<>();
        destinations.put("offsite", "uploaded");
        return new RunReport("Backup", "orders", "mysql h:3306/orders", ok, Duration.ofSeconds(3),
                "orders.sql.gz", 2048L, destinations, List.of("Retention removed 1 local backup(s)"), error);
    }

    @Test
    void postsSuccessDetails() {
        new SlackNotifier(url(), Set.of("success", "failure")).send(report(true, null));
        assertEquals(1, bodies.size());
        String body = bodies.get(0);
        assertTrue(body.startsWith("{\"text\":\""), body);
        assertTrue(body.contains("succeeded"), body);
        assertTrue(body.contains("orders.sql.gz"), body);
        assertTrue(body.contains("2.0 KB"), body);
        assertTrue(body.contains("offsite: uploaded"), body);
    }

    @Test
    void failureEscapesErrorAsValidJson() {
        new SlackNotifier(url(), Set.of("failure")).send(report(false, "bad \"quote\"\nnext\\line"));
        String body = bodies.get(0);
        assertTrue(body.contains("FAILED"), body);
        assertTrue(body.contains("bad \\\"quote\\\"\\nnext\\\\line"), body);
    }

    @Test
    void honoursNotifyOn() {
        new SlackNotifier(url(), Set.of("failure")).send(report(true, null));
        assertTrue(bodies.isEmpty());
    }

    @Test
    void slackProblemsNeverThrow() {
        status = 500;
        assertDoesNotThrow(() -> new SlackNotifier(url(), Set.of("success")).send(report(true, null)));
        server.stop(0);
        assertDoesNotThrow(() -> new SlackNotifier(url(), Set.of("success")).send(report(true, null)));
    }
}
