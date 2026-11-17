package com.nikanenka.step1_wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentUnavailableException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ШАГ 1.3 - то, ради чего WireMock чаще всего и берут: воспроизводимые "плохие" сценарии.
 *
 * <p>Попробуйте на реальном QA-стенде стабильно поймать:
 * <ul>
 *   <li>ответ ровно через 3 секунды, чтобы проверить таймаут клиента;</li>
 *   <li>обрыв TCP-соединения посреди ответа;</li>
 *   <li>503 от платежного провайдера в момент, когда вы нажимаете "Оплатить".</li>
 * </ul>
 * Обычно это "руками, через админку, если повезёт, и не на вашем стенде".
 * WireMock делает это детерминированно и за миллисекунды.
 */
@DisplayName("Шаг 1.3 - WireMock: 5xx, таймауты и обрывы соединения")
class C_FaultsAndTimeoutsTest {

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
    @DisplayName("Провайдер ответил 503 - клиент кидает PaymentUnavailableException")
    void provider503_shouldBeUnavailable() {
        wireMock.stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withStatus(503)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"error": "SERVICE_UNAVAILABLE"}
                                """)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-1", 1999, "EUR")))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessageContaining("HTTP 503");
    }

    @Test
    @DisplayName("Провайдер тормозит дольше таймаута клиента - срабатывает таймаут")
    void slowProvider_shouldTimeout() {
        wireMock.stubFor(post(anyUrl())
                .willReturn(aResponse()
                        .withStatus(201)
                        .withFixedDelay(3_000)      // ответим через 3 секунды
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"paymentId\": \"pay_1\", \"status\": \"AUTHORIZED\"}")));

        // Таймаут клиента - 500 мс. Тест не будет ждать 3 секунды.
        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl(), Duration.ofMillis(500));

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-1", 1999, "EUR")))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessageContaining("not reachable");
    }

    @Test
    @DisplayName("Обрыв соединения посреди ответа - самый неприятный для клиента кейс")
    void connectionReset_shouldBeUnavailable() {
        wireMock.stubFor(post(anyUrl())
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-1", 1999, "EUR")))
                .isInstanceOf(PaymentUnavailableException.class)
                .hasMessageContaining("not reachable");
    }

    @Test
    @DisplayName("Пустой ответ от сервера - тоже ошибка, и клиент должен её пережить")
    void emptyResponse_shouldBeUnavailable() {
        wireMock.stubFor(post(anyUrl())
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-1", 1999, "EUR")))
                .isInstanceOf(PaymentUnavailableException.class);
    }
}
