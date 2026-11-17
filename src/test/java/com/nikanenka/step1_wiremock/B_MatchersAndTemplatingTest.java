package com.nikanenka.step1_wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentDeclinedException;
import com.nikanenka.order.PaymentResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ШАГ 1.2 - матчеры, приоритеты и шаблоны ответов.
 *
 * <p>Главная мысль: стаб не обязан быть "точным слепком" запроса.
 * Можно описать условие ("если сумма больше 500000"), а ответ собрать
 * из данных запроса. Это то, что делает WireMock сильно гибче, чем тупой json-файл.
 */
@DisplayName("Шаг 1.2 - WireMock: матчеры, приоритеты, шаблонизация ответа")
class B_MatchersAndTemplatingTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startServer() {
        // globalTemplating(true) включает Handlebars в телах ответов для всех стабов.
        wireMock = new WireMockServer(options().dynamicPort().globalTemplating(true));
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    @Test
    @DisplayName("Два стаба на один путь: приоритет решает, какой сработает")
    void declineStubWithHigherPriority_wins() {
        // Приоритет: чем МЕНЬШЕ число, тем ВАЖНЕЕ стаб.
        wireMock.stubFor(post(anyUrl())
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$[?(@.amountCents > 500000)]"))
                .willReturn(aResponse()
                        .withStatus(402)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"status": "DECLINED", "reason": "LIMIT_EXCEEDED"}
                                """)));

        wireMock.stubFor(post(anyUrl())
                .atPriority(5)
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentId": "pay_1", "status": "AUTHORIZED", "amountCents": 1999,
                                 "currency": "EUR", "createdAt": "2026-01-01T00:00:00Z"}
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-big", 999_999, "EUR")))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("LIMIT_EXCEEDED");

        PaymentResult ok = client.authorizePayment(new Order("order-small", 1999, "EUR"));
        assertThat(ok.status()).isEqualTo("AUTHORIZED");
    }

    @Test
    @DisplayName("Ответ собирается из данных запроса (шаблонизация) - стаб ведёт себя как живой сервис")
    void responseTemplating_echoesRequestData() throws Exception {
        wireMock.stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentId": "pay_1",
                                  "status": "AUTHORIZED",
                                  "orderId": "{{jsonPath request.body '$.orderId'}}",
                                  "amountCents": {{jsonPath request.body '$.amountCents'}},
                                  "currency": "{{jsonPath request.body '$.currency'}}",
                                  "createdAt": "2026-01-01T00:00:00Z"
                                }
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());
        PaymentResult result = client.authorizePayment(new Order("order-42", 2500, "USD"));

        assertThat(result.status()).isEqualTo("AUTHORIZED");

        // Достаём "сырой" ответ, который WireMock отдал клиенту, и проверяем, что шаблон подставился.
        String rawBody = wireMock.getAllServeEvents().stream()
                .map(event -> event.getResponse().getBodyAsString())
                .findFirst()
                .orElseThrow();
        JsonNode echoed = new ObjectMapper().readTree(rawBody);
        assertThat(echoed.path("orderId").asText()).isEqualTo("order-42");
        assertThat(echoed.path("amountCents").asLong()).isEqualTo(2500);
        assertThat(echoed.path("currency").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("equalToJson умеет игнорировать лишние поля и порядок ключей")
    void equalToJson_canIgnoreExtraFields() {
        wireMock.stubFor(post(anyUrl())
                .withRequestBody(equalToJson("""
                        {"orderId": "order-7", "amountCents": 1999}
                        """, true, true))  // ignoreArrayOrder, ignoreExtraElements
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentId": "pay_7", "status": "AUTHORIZED", "amountCents": 1999,
                                 "currency": "EUR", "createdAt": "2026-01-01T00:00:00Z",
                                 "someNewFieldFromProvider": "which consumer must ignore"}
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        // Клиент прислал ещё и currency - матчер это прощает, т.к. ignoreExtraElements = true.
        // А "someNewFieldFromProvider" в ответе клиент игнорирует - это tolerant reader.
        PaymentResult result = client.authorizePayment(new Order("order-7", 1999, "EUR"));

        assertThat(result.paymentId()).isEqualTo("pay_7");
        wireMock.verify(postRequestedFor(anyUrl())
                .withRequestBody(equalToJson("""
                        {"orderId": "order-7", "amountCents": 1999, "currency": "EUR"}
                        """)));
    }
}
