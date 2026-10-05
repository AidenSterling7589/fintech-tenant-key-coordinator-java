package edu.fintech.tenantkeys;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

public final class TenantKeysHttpService {
    private final TenantCredentialService credentials;

    private TenantKeysHttpService(TenantCredentialService credentials) {
        this.credentials = credentials;
    }

    public static void main(String[] args) throws IOException {
        LayeredConfig config = LayeredConfig.fromEnvironment();
        InfraiClient client = new InfraiClient(config);
        TenantCredentialService credentials = new TenantCredentialService(
                client, config, new TenantCredentialService.AuditNotifier(Clock.systemUTC()));
        TenantKeysHttpService application = new TenantKeysHttpService(credentials);
        HttpServer server = HttpServer.create(new InetSocketAddress(config.servicePort()), 0);
        server.createContext("/tenants/provision", application::provision);
        server.createContext("/tenants/offboard", application::offboard);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("Tenant key service listening on http://localhost:" + config.servicePort());
    }

    private void provision(HttpExchange exchange) throws IOException {
        handlePost(exchange, input -> {
            TenantCredentialService.PaymentEvent payment = new TenantCredentialService.PaymentEvent(
                    string(input, "paymentEventId"), number(input, "amountCents").longValue(),
                    number(input, "riskScore").intValue(), string(input, "eventType"));
            return credentials.provision(new TenantCredentialService.ProvisionRequest(
                    string(input, "tenantId"), string(input, "projectId"), string(input, "email"),
                    string(input, "name"), payment));
        });
    }

    private void offboard(HttpExchange exchange) throws IOException {
        handlePost(exchange, input -> credentials.offboard(new TenantCredentialService.OffboardingRequest(
                string(input, "tenantId"), string(input, "userId"), string(input, "keyId"),
                string(input, "correlationId"))));
    }

    private void handlePost(HttpExchange exchange, Handler handler) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            respond(exchange, 405, Map.of("error", "method must be POST"));
            return;
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Object result = handler.apply(Json.object(Json.parse(body)));
            respond(exchange, 200, recordMap(result));
        } catch (TenantCredentialService.PolicyDeclinedException error) {
            respond(exchange, 422, Map.of("error", error.getMessage()));
        } catch (InfraiClient.InfraiException error) {
            int status = error.statusCode() >= 400 && error.statusCode() < 500 ? error.statusCode() : 502;
            respond(exchange, status, Map.of("error", error.getMessage(), "code", error.code()));
        } catch (IllegalArgumentException error) {
            respond(exchange, 400, Map.of("error", error.getMessage()));
        } catch (InfraiClient.InfraiTransportException error) {
            respond(exchange, 502, Map.of("error", "upstream request could not be completed"));
        }
    }

    private static Map<String, Object> recordMap(Object value) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (java.lang.reflect.RecordComponent component : value.getClass().getRecordComponents()) {
            try { result.put(component.getName(), component.getAccessor().invoke(value)); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        return result;
    }

    private static String string(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(field + " is required");
        return text;
    }

    private static Number number(Map<String, Object> input, String field) {
        Object value = input.get(field);
        if (!(value instanceof Number number)) throw new IllegalArgumentException(field + " must be a number");
        return number;
    }

    private static void respond(HttpExchange exchange, int status, Map<String, Object> payload) throws IOException {
        byte[] bytes = Json.stringify(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler { Object apply(Map<String, Object> input); }
}
