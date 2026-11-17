package com.nikanenka.step3_pact_provider;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.nikanenka.payment.Payment;
import com.nikanenka.payment.PaymentServiceServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ШАГ 3. Верификация контракта на стороне PROVIDER (payment-service).
 *
 * <p>Этот тест живёт в репозитории провайдера и запускается в его пайплайне.
 * Он читает контракты consumer-ов и проверяет: "а мы правда так отвечаем?".
 *
 * <p>Ключевой момент: проверяется НАСТОЯЩИЙ сервис, а не мок. Pact берёт запрос из контракта,
 * отправляет его в живой payment-service и сравнивает ответ с ожидаемым (по матчерам, а не по символам).
 *
 * <p>Что такое provider state ({@link State}): перед каждой проверкой Pact вызывает метод,
 * который приводит провайдер в нужное состояние ("сервис доступен", "платёж P-1 существует").
 * Это замена "подготовки данных" в обычных e2e-тестах, только детерминированная.
 */
@Provider("payment-service")
// Папка, откуда берём контракты. ${pact.folder} задан в pom.xml и указывает на ./pacts.
// В реальном проекте здесь был бы @PactBroker(url = "${pact.broker.url}") - см. BrokerVerificationTest.
@PactFolder("${pact.folder}")
@DisplayName("Шаг 3 - Pact provider verification: payment-service")
class PaymentProviderVerificationTest {

    private static PaymentServiceServer provider;

    @BeforeAll
    static void startProvider() {
        // Поднимаем НАСТОЯЩИЙ сервис на случайном порту. В реальности тут было бы
        // @SpringBootTest(webEnvironment = RANDOM_PORT) или Testcontainers с образом приложения.
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
        // Один "тест" на каждую интеракцию из всех контрактов.
        context.verifyInteraction();
    }

    // ------------------------------------------------------------ provider states

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
                "P-1",
                "order-42",
                1999,
                "EUR",
                "AUTHORIZED",
                "2026-01-01T00:00:00Z"));
    }
}
