package com.nikanenka.step3_pact_provider;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.IgnoreNoPactsToVerify;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactBroker;
import com.nikanenka.payment.Payment;
import com.nikanenka.payment.PaymentServiceServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ШАГ 3 (продвинутый вариант). То же самое, но контракты берутся из Pact Broker.
 *
 * <p>Так это работает в реальной жизни: папки {@code ./pacts} в проде нет,
 * контракты приезжают из брокера - от всех consumer-ов, которые его опубликовали.
 *
 * <p>Тест выключен, пока не задан {@code -Dpact.broker.url=...}:
 * <pre>
 *   docker compose up -d pact-broker
 *   mvn test -Dtest=PaymentProviderBrokerVerificationTest -Dpact.broker.url=http://localhost:9292
 * </pre>
 */
@Provider("payment-service")
@PactBroker(url = "${pact.broker.url}")
@IgnoreNoPactsToVerify
@EnabledIfSystemProperty(named = "pact.broker.url", matches = ".+")
@DisplayName("Шаг 3+ - Pact provider verification из Pact Broker")
class PaymentProviderBrokerVerificationTest {

    private static PaymentServiceServer provider;

    @BeforeAll
    static void startProvider() {
        provider = PaymentServiceServer.start(0);
    }

    @AfterAll
    static void stopProvider() {
        provider.close();
    }

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", provider.port(), "/"));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("payment-service is available")
    void paymentServiceIsAvailable() {
        provider.reset();
    }

    @State("payment-service is down")
    void paymentServiceIsDown() {
        provider.setMode(PaymentServiceServer.Mode.DOWN);
    }

    @State("payment P-1 exists")
    void paymentExists() {
        provider.reset();
        provider.seedPayment(new Payment(
                "P-1", "order-42", 1999, "EUR", "AUTHORIZED", "2026-01-01T00:00:00Z"));
    }
}
