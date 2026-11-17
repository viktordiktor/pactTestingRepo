package com.nikanenka.step1_wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ШАГ 1. WireMock как библиотека внутри JVM теста.
 *
 * <p>Самый частый сценарий у AQA: проверить свой клиент/интеграцию, не поднимая настоящий сервис.
 * WireMock - это HTTP-сервер, которому вы говорите "на такой запрос ответь вот так".
 *
 * <p>Что показать этим классом:
 * <ul>
 *   <li>сервер поднимается на случайном порту - ничего не надо согласовывать с командой и стендом;</li>
 *   <li>стаб описывает запрос (метод + путь + тело + заголовки) и ответ (статус + тело + заголовки);</li>
 *   <li>после вызова можно проверить, ЧТО именно улетело на сервер (request journal);</li>
 *   <li>всё это живёт и умирает вместе с тестовым классом - никакого мусора на стенде.</li>
 * </ul>
 */
@DisplayName("Шаг 1.1 - WireMock: базовый стаб и проверка запроса")
class A_BasicStubTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startServer() {
        // dynamicPort() - взять любой свободный порт. Порт узнаём через wireMock.port().
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopServer() {
        wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        // Без этого стабы одного теста протекут в другой - классическая беда.
        wireMock.resetAll();
    }

    @Test
    @DisplayName("Стаб отвечает 201, клиент разбирает ответ и мы видим, какой запрос он отправил")
    void authorizePayment_shouldReturnAuthorizedPayment() {
        wireMock.stubFor(post(urlEqualTo("/payments"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "paymentId": "pay_1",
                                  "status": "AUTHORIZED",
                                  "amountCents": 1999,
                                  "currency": "EUR",
                                  "createdAt": "2026-01-01T00:00:00Z"
                                }
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        PaymentResult result = client.authorizePayment(new Order("order-1", 1999, "EUR"));

        assertThat(result.paymentId()).isEqualTo("pay_1");
        assertThat(result.status()).isEqualTo("AUTHORIZED");
        assertThat(result.amountCents()).isEqualTo(1999);

        // Проверяем не только ответ, но и запрос: тело, заголовок, метод, путь.
        wireMock.verify(postRequestedFor(urlEqualTo("/payments"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(matchingJsonPath("$.orderId", equalTo("order-1")))
                .withRequestBody(matchingJsonPath("$.amountCents", equalTo("1999")))
                .withRequestBody(matchingJsonPath("$.currency", equalTo("EUR"))));
    }

    @Test
    @DisplayName("Если стаба нет - WireMock вернёт 404, и это видно в журнале как unmatched request")
    void noStub_shouldReturn404AndBeVisibleInJournal() {
        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        // Стаба нет вообще: WireMock отвечает 404 со своим дефолтным телом.
        assertThat(wireMock.findAllUnmatchedRequests()).isEmpty();

        try {
            client.authorizePayment(new Order("order-2", 100, "USD"));
        } catch (RuntimeException e) {
            // клиент на 404 кидает наше исключение - это ок, нам важен журнал
        }

        assertThat(wireMock.findAllUnmatchedRequests())
                .as("в журнале должен появиться запрос, под который не нашлось стаба")
                .hasSize(1);
    }
}
