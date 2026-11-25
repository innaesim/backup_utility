package za.co.innaesim.backup.notify;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import za.co.innaesim.backup.service.RunReport;

import lombok.extern.slf4j.Slf4j;

/** Posts run results to a Slack incoming webhook. The URL is a secret, so it's never logged. */
@Slf4j
public class SlackNotifier implements Notifier {
    private final URI webhook;
    private final Set<String> notifyOn;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Creates a notifier for one webhook.
     *
     * @param webhookUrl the Slack incoming webhook URL
     * @param notifyOn which results to send: {@code success} and/or {@code failure}
     */
    public SlackNotifier(String webhookUrl, Set<String> notifyOn) {
        this.webhook = URI.create(webhookUrl);
        this.notifyOn = notifyOn;
    }

    @Override
    public void send(RunReport report) {
        if (!notifyOn.contains(report.succeeded() ? "success" : "failure")) return;
        try {
            HttpRequest request = HttpRequest.newBuilder(webhook)
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"text\":\"" + json(message(report)) + "\"}"))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) log.warn("Slack rejected the notification: HTTP {}", response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while sending the Slack notification");
        } catch (Exception e) {
            log.warn("Couldn't send the Slack notification: {}", e.getClass().getSimpleName());
        }
    }

    static String message(RunReport report) {
        StringBuilder text = new StringBuilder();
        text.append(report.succeeded() ? ":white_check_mark: *" : ":x: *")
                .append(report.operation()).append(report.succeeded() ? " succeeded" : " FAILED").append("*")
                .append(" for job `").append(report.job()).append("`\n");
        text.append("Database: ").append(report.database()).append('\n');
        text.append("Duration: ").append(report.duration().toSeconds()).append("s\n");
        if (report.file() != null) {
            text.append("File: `").append(report.file()).append("`");
            if (report.sizeBytes() != null) text.append(" (").append(humanSize(report.sizeBytes())).append(')');
            text.append('\n');
        }
        for (Map.Entry<String, String> destination : report.destinations().entrySet()) {
            text.append("Destination ").append(destination.getKey()).append(": ").append(destination.getValue()).append('\n');
        }
        for (String note : report.notes()) text.append("Note: ").append(note).append('\n');
        if (report.error() != null) text.append("Error:\n```").append(report.error()).append("```\n");
        return text.toString().stripTrailing();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unit]);
    }

    static String json(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.toString();
    }
}
