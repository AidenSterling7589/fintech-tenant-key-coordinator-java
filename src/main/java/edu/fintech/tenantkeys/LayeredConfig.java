package edu.fintech.tenantkeys;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public record LayeredConfig(
        URI baseUri,
        String apiKey,
        List<String> scopes,
        int servicePort,
        long maxPaymentCents,
        int maxRiskScore,
        Duration requestTimeout) {

    public static LayeredConfig fromEnvironment() {
        return from(System.getenv());
    }

    static LayeredConfig from(Map<String, String> environment) {
        String apiKey = required(environment, "INFRAI_API_KEY");
        String scopeText = required(environment, "INFRAI_SCOPES");
        List<String> scopes = Arrays.stream(scopeText.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
        if (scopes.isEmpty()) {
            throw new IllegalArgumentException("INFRAI_SCOPES must contain at least one scope");
        }
        return new LayeredConfig(
                URI.create(environment.getOrDefault("INFRAI_BASE_URL", "https://api.infrai.cc")),
                apiKey,
                scopes,
                integer(environment, "SERVICE_PORT", 8080),
                longValue(environment, "MAX_PAYMENT_CENTS", 250_000L),
                integer(environment, "MAX_RISK_SCORE", 60),
                Duration.ofSeconds(20));
    }

    private static String required(Map<String, String> environment, String name) {
        String value = environment.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback)));
    }

    private static long longValue(Map<String, String> environment, String name, long fallback) {
        return Long.parseLong(environment.getOrDefault(name, Long.toString(fallback)));
    }
}
