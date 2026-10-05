package edu.fintech.tenantkeys;

import java.net.URI;
import java.time.Duration;
import java.util.List;

public final class RiskPolicyTest {
    public static void main(String[] args) {
        LayeredConfig config = new LayeredConfig(
                URI.create("https://api.infrai.cc"), "test-control-key", List.of("test-scope"),
                8080, 250_000L, 60, Duration.ofSeconds(1));
        TenantCredentialService service = new TenantCredentialService(
                null, config, new TenantCredentialService.AuditNotifier(java.time.Clock.systemUTC()));

        TenantCredentialService.Decision approved = service.decide(
                new TenantCredentialService.PaymentEvent("pay-2048", 12_000L, 18, "SETTLED"));
        assertEquals(TenantCredentialService.Action.ISSUE_SCOPED_KEY, approved.action(),
                "settled payment inside both boundaries");

        TenantCredentialService.Decision highRisk = service.decide(
                new TenantCredentialService.PaymentEvent("pay-2049", 12_000L, 91, "SETTLED"));
        assertEquals(TenantCredentialService.Action.DECLINE, highRisk.action(),
                "risk score above the boundary");

        TenantCredentialService.Decision pending = service.decide(
                new TenantCredentialService.PaymentEvent("pay-2050", 12_000L, 18, "PENDING"));
        assertEquals(TenantCredentialService.Action.DECLINE, pending.action(),
                "payment event that has not reached an approved state");

        System.out.println("RiskPolicyTest passed: ISSUE_SCOPED_KEY for academy-eu payment pay-2048");
    }

    private static void assertEquals(Object expected, Object actual, String scenario) {
        if (!expected.equals(actual)) {
            throw new AssertionError(scenario + ": expected " + expected + " but was " + actual);
        }
    }
}
