package com.nikanenka.step1_wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentResult;
import com.nikanenka.order.PaymentUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ШАГ 1.4 - сценарии (stateful stubs).
 *
 * <p>Обычный стаб всегда отвечает одинаково. Сценарий позволяет менять ответ
 * в зависимости от того, сколько раз и в каком порядке дёргали сервис:
 * "первый вызов - 503, второй - 201". Это ровно то, что нужно для проверки retry.
 *
 * <p>Дальше по жизни: именно так моделируют "заказ создан, но ещё не оплачен",
 * "платёж в обработке -> подтверждён", "товар зарезервирован -> продан".
 */
@DisplayName("Шаг 1.4 - WireMock: сценарии и проверка retry")
class D_ScenarioTest {

    private static final String SCENARIO = "payment-service recovers";

    private static WireMockServer wireMock;

    @BeforeAll
    static void startServer() {
        wireMock = new WireMockServer(options().dynamicPort());
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
    @DisplayName("Первый вызов падает с 503, второй проходит - так проверяют retry-логику")
    void firstCallFailsThenSucceeds() {
        wireMock.stubFor(post(anyUrl())
                .inScenario(SCENARIO)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("RECOVERED")
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\": \"SERVICE_UNAVAILABLE\"}")));

        wireMock.stubFor(post(anyUrl())
                .inScenario(SCENARIO)
                .whenScenarioStateIs("RECOVERED")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentId": "pay_1", "status": "AUTHORIZED", "amountCents": 1999,
                                 "currency": "EUR", "createdAt": "2026-01-01T00:00:00Z"}
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());
        Order order = new Order("order-1", 1999, "EUR");

        // Первая попытка - сервис "лежит".
        assertThatThrownBy(() -> client.authorizePayment(order))
                .isInstanceOf(PaymentUnavailableException.class);

        // Вторая - сервис "поднялся", и это тот же самый стаб, просто сценарий перешёл в другое состояние.
        PaymentResult result = client.authorizePayment(order);
        assertThat(result.status()).isEqualTo("AUTHORIZED");

        // Ровно два запроса, оба дошли до WireMock.
        wireMock.verify(2, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("Состояние сценария можно переключить руками - удобно для подготовки данных")
    void scenarioStateCanBeResetManually() {
        wireMock.stubFor(post(anyUrl())
                .inScenario(SCENARIO)
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("RECOVERED")
                .willReturn(aResponse().withStatus(503)));

        wireMock.stubFor(post(anyUrl())
                .inScenario(SCENARIO)
                .whenScenarioStateIs("RECOVERED")
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"paymentId": "pay_1", "status": "AUTHORIZED", "amountCents": 1999,
                                 "currency": "EUR", "createdAt": "2026-01-01T00:00:00Z"}
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        // Сразу переводим сценарий в "сервис жив", не тратя запрос на 503.
        wireMock.setScenarioState(SCENARIO, "RECOVERED");

        assertThat(client.authorizePayment(new Order("order-1", 1999, "EUR")).status())
                .isEqualTo("AUTHORIZED");
    }
}
