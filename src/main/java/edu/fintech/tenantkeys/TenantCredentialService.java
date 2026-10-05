package edu.fintech.tenantkeys;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TenantCredentialService {
    private static final Set<String> APPROVED_PAYMENT_EVENTS = Set.of("AUTHORIZED", "SETTLED");
    private final InfraiClient client;
    private final LayeredConfig config;
    private final AuditNotifier notifier;

    public TenantCredentialService(InfraiClient client, LayeredConfig config, AuditNotifier notifier) {
        this.client = client;
        this.config = config;
        this.notifier = notifier;
    }

    public Decision decide(PaymentEvent event) {
        if (!APPROVED_PAYMENT_EVENTS.contains(event.eventType())) {
            return new Decision(Action.DECLINE, "payment event is not final enough");
        }
        if (event.amountCents() <= 0 || event.amountCents() > config.maxPaymentCents()) {
            return new Decision(Action.DECLINE, "payment amount is outside the credential boundary");
        }
        if (event.riskScore() < 0 || event.riskScore() > config.maxRiskScore()) {
            return new Decision(Action.DECLINE, "risk score is outside the credential boundary");
        }
        return new Decision(Action.ISSUE_SCOPED_KEY, "payment and risk boundaries passed");
    }

    public ProvisionedCredential provision(ProvisionRequest request) {
        Decision decision = decide(request.paymentEvent());
        notifier.record(request.tenantId(), request.paymentEvent().eventId(), "RISK_DECISION", decision.action().name());
        if (decision.action() != Action.ISSUE_SCOPED_KEY) throw new PolicyDeclinedException(decision.reason());

        String stableOperation = UUID.nameUUIDFromBytes(
                (request.tenantId() + ":" + request.paymentEvent().eventId()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString();
        Map<String, Object> user = client.createUser(
                request.email(), request.name(), "tenant-user-" + stableOperation);
        String userId = requiredString(user, "user_id");
        notifier.record(request.tenantId(), request.paymentEvent().eventId(), "USER_CREATED", userId);

        Map<String, Object> key = client.createScopedKey(
                request.projectId(), request.tenantId() + " tenant key", config.scopes(), "tenant-key-" + stableOperation);
        String keyId = requiredString(key, "key_id");
        String plaintextKey = requiredString(key, "key_secret");
        notifier.record(request.tenantId(), request.paymentEvent().eventId(), "KEY_ISSUED", keyId);
        return new ProvisionedCredential(request.tenantId(), userId, keyId, plaintextKey, List.copyOf(config.scopes()));
    }

    public OffboardingResult offboard(OffboardingRequest request) {
        client.revokeKey(request.keyId());
        notifier.record(request.tenantId(), request.correlationId(), "KEY_REVOKED", request.keyId());
        client.deleteUser(request.userId());
        notifier.record(request.tenantId(), request.correlationId(), "USER_DELETED", request.userId());
        return new OffboardingResult(request.tenantId(), request.keyId(), request.userId(), "COMPLETED");
    }

    private static String requiredString(Map<String, Object> object, String field) {
        Object value = object.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new InfraiClient.InfraiTransportException("Successful response omitted " + field);
        }
        return text;
    }

    public enum Action { ISSUE_SCOPED_KEY, DECLINE }
    public record Decision(Action action, String reason) {}
    public record PaymentEvent(String eventId, long amountCents, int riskScore, String eventType) {}
    public record ProvisionRequest(String tenantId, String projectId, String email, String name, PaymentEvent paymentEvent) {}
    public record ProvisionedCredential(String tenantId, String userId, String keyId, String plaintextKey, List<String> scopes) {}
    public record OffboardingRequest(String tenantId, String userId, String keyId, String correlationId) {}
    public record OffboardingResult(String tenantId, String keyId, String userId, String status) {}

    public static final class PolicyDeclinedException extends RuntimeException {
        PolicyDeclinedException(String message) { super(message); }
    }

    public static final class AuditNotifier {
        private final Clock clock;
        public AuditNotifier(Clock clock) { this.clock = clock; }
        public void record(String tenantId, String correlationId, String event, String subject) {
            Map<String, Object> notification = new LinkedHashMap<>();
            notification.put("occurredAt", Instant.now(clock).toString());
            notification.put("tenantId", tenantId);
            notification.put("correlationId", correlationId);
            notification.put("event", event);
            notification.put("subject", subject);
            System.out.println(Json.stringify(notification));
        }
    }
}
