package edu.fintech.tenantkeys;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InfraiClient {
    private static final int MAX_ATTEMPTS = 4;
    private final HttpClient http;
    private final URI baseUri;
    private final String apiKey;
    private final Duration timeout;

    public InfraiClient(LayeredConfig config) {
        this(HttpClient.newBuilder().connectTimeout(config.requestTimeout()).build(), config);
    }

    InfraiClient(HttpClient http, LayeredConfig config) {
        this.http = http;
        this.baseUri = config.baseUri();
        this.apiKey = config.apiKey();
        this.timeout = config.requestTimeout();
    }

    public Map<String, Object> createUser(String email, String name, String idempotencyKey) {
        return send("POST", "/v1/auth/user/create", Map.of(
                "email", email,
                "name", name,
                "idempotency_key", idempotencyKey));
    }

    public Map<String, Object> createScopedKey(
            String projectId, String name, List<String> scopes, String idempotencyKey) {
        return send("POST", "/v1/account/keys/create", Map.of(
                "project_id", projectId,
                "name", name,
                "scopes", scopes,
                "idempotency_key", idempotencyKey));
    }

    public void revokeKey(String keyId) {
        send("DELETE", "/v1/account/keys/revoke/" + pathSegment(keyId), null);
    }

    public void deleteUser(String userId) {
        send("DELETE", "/v1/auth/user/delete/" + pathSegment(userId), null);
    }

    private Map<String, Object> send(String method, String path, Map<String, Object> body) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri.resolve(path))
                    .timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json");
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body)));
            }
            HttpResponse<String> response;
            try {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException error) {
                throw new InfraiTransportException("Infrai request could not be completed", error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new InfraiTransportException("Infrai request was interrupted", error);
            }

            Map<String, Object> envelope;
            try {
                envelope = Json.object(Json.parse(response.body()));
            } catch (IllegalArgumentException error) {
                throw new InfraiTransportException("Infrai returned an unreadable envelope", error);
            }
            if (response.statusCode() == 429 && attempt < MAX_ATTEMPTS) {
                pause(retryDelay(response, attempt));
                continue;
            }
            Object ok = envelope.get("ok");
            if (!Boolean.TRUE.equals(ok)) {
                Map<String, Object> error = envelope.get("error") instanceof Map<?, ?> value
                        ? Json.object(value) : Map.of("message", "Request rejected");
                String code = String.valueOf(error.getOrDefault("code", "REQUEST_REJECTED"));
                throw new InfraiException(code, error, response.statusCode());
            }
            if (response.statusCode() >= 500) {
                throw new InfraiTransportException("Infrai returned an unexpected server response");
            }
            Object data = envelope.get("data");
            return data instanceof Map<?, ?> value ? new LinkedHashMap<>(Json.object(value)) : Map.of();
        }
        throw new InfraiTransportException("Infrai rate limit retry budget was exhausted");
    }

    private Duration retryDelay(HttpResponse<?> response, int attempt) {
        return response.headers().firstValue("Retry-After")
                .flatMap(InfraiClient::seconds)
                .map(Duration::ofSeconds)
                .orElse(Duration.ofMillis(250L * (1L << (attempt - 1))));
    }

    private static java.util.Optional<Long> seconds(String value) {
        try { return java.util.Optional.of(Long.parseLong(value.trim())); }
        catch (NumberFormatException ignored) { return java.util.Optional.empty(); }
    }

    private static void pause(Duration duration) {
        try { Thread.sleep(duration.toMillis()); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new InfraiTransportException("Retry wait was interrupted", error);
        }
    }

    private static String pathSegment(String value) {
        if (!value.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("Invalid identifier");
        return value;
    }

    public static final class InfraiException extends RuntimeException {
        private final String code;
        private final Map<String, Object> details;
        private final int statusCode;
        InfraiException(String code, Map<String, Object> details, int statusCode) {
            super(String.valueOf(details.getOrDefault("message", code)));
            this.code = code;
            this.details = Map.copyOf(details);
            this.statusCode = statusCode;
        }
        public String code() { return code; }
        public Map<String, Object> details() { return details; }
        public int statusCode() { return statusCode; }
    }

    public static final class InfraiTransportException extends RuntimeException {
        InfraiTransportException(String message) { super(message); }
        InfraiTransportException(String message, Throwable cause) { super(message, cause); }
    }
}
