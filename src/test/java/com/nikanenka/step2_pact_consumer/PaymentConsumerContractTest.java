package com.nikanenka.step2_pact_consumer;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactBuilder;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentDeclinedException;
import com.nikanenka.order.PaymentResult;
import com.nikanenka.order.PaymentUnavailableException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Instant;
import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ШАГ 2. Контрактный тест со стороны CONSUMER (order-service).
 *
 * <p>Чем это отличается от WireMock из шага 1?
 * <ul>
 *   <li>Там мы руками писали, ЧТО вернёт провайдер. Это наше воображение, и оно может разойтись с реальностью.</li>
 *   <li>Здесь мы описываем, что consumer РЕАЛЬНО использует, и описание сохраняется в файл - контракт.</li>
 *   <li>Потом этот файл приезжает к провайдеру и проверяется против настоящего сервиса.</li>
 * </ul>
 *
 * <p>Внутри теста всё читается как WireMock: Pact поднимает HTTP-мок-сервер по контракту
 * и следит, чтобы мы вызвали ровно то, что объявили.
 *
 * <p>Три важных правила, которые видно прямо в коде:
 * <ol>
 *   <li><b>Матчеры вместо значений.</b> {@code stringType}, {@code integerType}, {@code stringMatcher}
 *       говорят "подойдёт любой UUID / любое целое / одно из перечисленного", а не "ровно это значение".</li>
 *   <li><b>Примеры детерминированные.</b> Второй аргумент матчера - это пример, который попадёт в контракт.
 *       Если он случайный, файл контракта будет меняться на каждый билд и засорит git.</li>
 *   <li><b>Контракт - только про то, что нужно consumer-у.</b> Мы не описываем поля, которые не читаем.</li>
 * </ol>
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service")
@DisplayName("Шаг 2 - Pact consumer: контракт order-service -> payment-service")
class PaymentConsumerContractTest {

    private static final String CONSUMER = "order-service";
    private static final String PROVIDER = "payment-service";

    /** Пример paymentId. Фиксированный, чтобы контракт не "шумёл" в git. */
    private static final String EXAMPLE_PAYMENT_ID = "8a1f1e8c-0000-4000-8000-000000000001";
    private static final String EXAMPLE_ORDER_ID = "11111111-2222-3333-4444-555555555555";
    private static final String ISO_TIMESTAMP = "yyyy-MM-dd'T'HH:mm:ssXXX";

    // ------------------------------------------------------------------ контракты

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    public V4Pact authorizePaymentPact(PactBuilder builder) {
        return builder
                .given("payment-service is available")
                .expectsToReceiveHttpInteraction("a request to authorize a payment", interaction -> interaction
                        .withRequest(request -> request
                                .method("POST")
                                .path("/payments")
                                .header("Content-Type", "application/json")
                                .body(authorizeRequest(1999)))
                        .willRespondWith(response -> response
                                .status(201)
                                .header("Content-Type", "application/json")
                                .body(authorizedPayment(EXAMPLE_PAYMENT_ID))))
                .toPact();
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    public V4Pact declinedPaymentPact(PactBuilder builder) {
        return builder
                .given("payment-service is available")
                .expectsToReceiveHttpInteraction("a request to authorize a payment over the limit", interaction -> interaction
                        .withRequest(request -> request
                                .method("POST")
                                .path("/payments")
                                .header("Content-Type", "application/json")
                                .body(authorizeRequest(600_000)))
                        .willRespondWith(response -> response
                                .status(402)
                                .header("Content-Type", "application/json")
                                .body(new PactDslJsonBody()
                                        .stringType("status", "DECLINED")
                                        .stringMatcher("reason", "LIMIT_EXCEEDED|INSUFFICIENT_FUNDS", "LIMIT_EXCEEDED"))))
                .toPact();
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    public V4Pact providerDownPact(PactBuilder builder) {
        return builder
                // provider state: перед проверкой провайдер должен оказаться "выключенным"
                .given("payment-service is down")
                .expectsToReceiveHttpInteraction("a request to authorize a payment while payment-service is down", interaction -> interaction
                        .withRequest(request -> request
                                .method("POST")
                                .path("/payments")
                                .header("Content-Type", "application/json")
                                .body(authorizeRequest(1999)))
                        .willRespondWith(response -> response
                                .status(503)
                                .header("Content-Type", "application/json")
                                .body(new PactDslJsonBody()
                                        .stringType("error", "SERVICE_UNAVAILABLE"))))
                .toPact();
    }

    @Pact(consumer = CONSUMER, provider = PROVIDER)
    public V4Pact getPaymentPact(PactBuilder builder) {
        return builder
                .given("payment P-1 exists")
                .expectsToReceiveHttpInteraction("a request to get an existing payment", interaction -> interaction
                        .withRequest(request -> request
                                .method("GET")
                                // здесь id - литерал: он же используется в названии provider state
                                .path("/payments/P-1"))
                        .willRespondWith(response -> response
                                .status(200)
                                .header("Content-Type", "application/json")
                                .body(authorizedPayment("P-1"))))
                .toPact();
    }

    // ------------------------------------------------------------------ тело запроса/ответа

    private DslPart authorizeRequest(long amountCents) {
        return new PactDslJsonBody()
                // строку можно описать как "любая строка" - главное, что это string
                .stringType("orderId", EXAMPLE_ORDER_ID)
                // сумма - целое
                .integerType("amountCents", amountCents)
                // а вот валюта - строго из списка: это реальное требование протокола
                .stringMatcher("currency", "EUR|USD|GBP", "EUR");
    }

    private DslPart authorizedPayment(String paymentId) {
        return new PactDslJsonBody()
                .stringType("paymentId", paymentId)
                .stringType("status", "AUTHORIZED")
                .integerType("amountCents", 1999)
                .stringType("currency", "EUR")
                // Время в UTC и фиксированный пример: контракт не должен меняться от машины к машине.
                .datetime("createdAt", ISO_TIMESTAMP,
                        Instant.parse("2026-01-01T00:00:00Z"), TimeZone.getTimeZone("UTC"));
    }

    // ------------------------------------------------------------------ тесты

    @Test
    @PactTestFor(pactMethod = "authorizePaymentPact")
    @DisplayName("Клиент умеет авторизовать платёж")
    void authorizePayment(MockServer mockServer) {
        OrderServiceClient client = new OrderServiceClient(mockServer.getUrl());

        PaymentResult result = client.authorizePayment(new Order("order-1", 1999, "EUR"));

        assertThat(result.paymentId()).isEqualTo(EXAMPLE_PAYMENT_ID);
        assertThat(result.status()).isEqualTo("AUTHORIZED");
    }

    @Test
    @PactTestFor(pactMethod = "declinedPaymentPact")
    @DisplayName("Клиент различает отказ по бизнес-причине")
    void declinedPayment(MockServer mockServer) {
        OrderServiceClient client = new OrderServiceClient(mockServer.getUrl());

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-2", 600_000, "EUR")))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("LIMIT_EXCEEDED");
    }

    @Test
    @PactTestFor(pactMethod = "providerDownPact")
    @DisplayName("Клиент переживает недоступность провайдера")
    void providerDown(MockServer mockServer) {
        OrderServiceClient client = new OrderServiceClient(mockServer.getUrl());

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-3", 1999, "EUR")))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessageContaining("503");
    }

    @Test
    @PactTestFor(pactMethod = "getPaymentPact")
    @DisplayName("Клиент умеет читать платёж по id")
    void getPayment(MockServer mockServer) {
        OrderServiceClient client = new OrderServiceClient(mockServer.getUrl());

        PaymentResult result = client.getPayment("P-1");

        assertThat(result.paymentId()).isEqualTo("P-1");
        assertThat(result.status()).isEqualTo("AUTHORIZED");
    }
}
