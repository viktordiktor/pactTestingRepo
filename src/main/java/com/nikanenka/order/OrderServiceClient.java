package com.nikanenka.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP-клиент payment-service. Это и есть наш CONSUMER.
 *
 * <p>Один и тот же класс мы будем гонять против трёх разных "провайдеров":
 * <ol>
 *   <li>WireMock, застабанный руками (step1);</li>
 *   <li>мок-сервер, который поднимает Pact из контракта (step2);</li>
 *   <li>настоящий payment-service в Docker (step4) и настоящий payment-service в JVM (step3).</li>
 * </ol>
 *
 * <p>Именно поэтому контрактное тестирование вообще работает: клиент не знает,
 * с кем он разговаривает, ему важно только соблюдение протокола.
 */
public class OrderServiceClient {

    private static final String JSON = "application/json";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String baseUrl;

    public OrderServiceClient(String baseUrl) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build());
    }

    /**
     * @param timeout таймаут на один запрос (полезно в тестах с инъекцией задержек)
     */
    public OrderServiceClient(String baseUrl, Duration timeout) {
        this(baseUrl, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build(), timeout);
    }

    public OrderServiceClient(String baseUrl, HttpClient http) {
        this(baseUrl, http, Duration.ofSeconds(5));
    }

    private OrderServiceClient(String baseUrl, HttpClient http, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = http;
        this.requestTimeout = timeout;
    }

    private final Duration requestTimeout;

    /**
     * POST /payments - авторизовать платёж.
     *
     * @throws PaymentDeclinedException    если провайдер ответил 402
     * @throws PaymentUnavailableException если провайдер ответил 5xx / не ответил / оборвал соединение
     */
    public PaymentResult authorizePayment(Order order) {
        try {
            String body = mapper.writeValueAsString(order);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/payments"))
                    .timeout(requestTimeout)
                    .header("Content-Type", JSON)
                    .header("Accept", JSON)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status == 201) {
                return mapper.readValue(response.body(), PaymentResult.class);
            }
            if (status == 402) {
                throw new PaymentDeclinedException(reasonFrom(response.body()));
            }
            if (status >= 500) {
                throw new PaymentUnavailableException(
                        "payment-service unavailable: HTTP " + status + " " + response.body());
            }
            throw new PaymentUnavailableException(
                    "unexpected response from payment-service: HTTP " + status + " " + response.body());
        } catch (IOException e) {
            // сюда попадают обрыв соединения, таймаут чтения, недоступный хост
            throw new PaymentUnavailableException("payment-service is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentUnavailableException("interrupted while calling payment-service", e);
        }
    }

    /**
     * GET /payments/{id} - прочитать платёж по идентификатору.
     */
    public PaymentResult getPayment(String paymentId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/payments/" + paymentId))
                    .timeout(requestTimeout)
                    .header("Accept", JSON)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return mapper.readValue(response.body(), PaymentResult.class);
            }
            if (response.statusCode() >= 500) {
                throw new PaymentUnavailableException("payment-service unavailable: HTTP " + response.statusCode());
            }
            throw new PaymentUnavailableException(
                    "unexpected response from payment-service: HTTP " + response.statusCode());
        } catch (IOException e) {
            throw new PaymentUnavailableException("payment-service is not reachable: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PaymentUnavailableException("interrupted while calling payment-service", e);
        }
    }

    private String reasonFrom(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            JsonNode reason = node.get("reason");
            return reason == null ? "UNKNOWN" : reason.asText();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
