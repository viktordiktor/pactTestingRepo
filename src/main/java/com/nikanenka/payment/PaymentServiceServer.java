package com.nikanenka.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Настоящий (ну, почти) payment-service. PROVIDER в терминах Pact.
 *
 * <p>Специально написан на голом JDK HttpServer, чтобы не тащить Spring:
 * вся суть контрактного тестирования в HTTP, а не в фреймворке.
 *
 * <p>Что важно для нас как для тестировщиков:
 * <ul>
 *   <li>он умеет работать на случайном порту ({@code start(0)}), поэтому тесты не конфликтуют;</li>
 *   <li>у него есть "рубильник" {@link Mode#DOWN}, чтобы проверять сценарии недоступности;</li>
 *   <li>у него есть {@link #seedPayment} - подготовка данных для provider state.</li>
 * </ul>
 *
 * <p>Запуск руками: {@code mvn exec:java} (порт 8081).
 */
public final class PaymentServiceServer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceServer.class);

    /** В этом режиме сервис отвечает 503 на всё: имитация упавшего или выключенного пода. */
    public enum Mode { NORMAL, DOWN }

    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("EUR", "USD", "GBP");
    private static final long DECLINE_THRESHOLD_CENTS = 500_000L; // > 5000.00 - отказ
    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Payment> payments = new ConcurrentHashMap<>();
    private final HttpServer httpServer;
    private final ExecutorService executor;
    private volatile Mode mode = Mode.NORMAL;

    private PaymentServiceServer(HttpServer httpServer, ExecutorService executor) {
        this.httpServer = httpServer;
        this.executor = executor;
    }

    /**
     * @param port 0 - взять случайный свободный порт
     */
    public static PaymentServiceServer start(int port) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            PaymentServiceServer app = new PaymentServiceServer(server, executor);

            server.createContext("/payments", app::handlePayments);
            server.setExecutor(executor);
            server.start();
            log.info("payment-service started on port {}", app.port());
            return app;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start payment-service", e);
        }
    }

    public static void main(String[] args) {
        start(8081);
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    public String baseUrl() {
        return "http://localhost:" + port();
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Mode getMode() {
        return mode;
    }

    /** Подготовка данных для provider state: "платёж с таким id уже существует". */
    public void seedPayment(Payment payment) {
        payments.put(payment.paymentId(), payment);
    }

    public void reset() {
        payments.clear();
        mode = Mode.NORMAL;
    }

    @Override
    public void close() {
        httpServer.stop(0);
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ HTTP

    private void handlePayments(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();

            if (mode == Mode.DOWN) {
                respond(exchange, 503, Map.of("error", "SERVICE_UNAVAILABLE"));
                return;
            }

            if ("POST".equals(exchange.getRequestMethod()) && "/payments".equals(path)) {
                authorize(exchange);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/payments/")) {
                getPayment(exchange, path.substring("/payments/".length()));
                return;
            }
            respond(exchange, 404, Map.of("error", "NOT_FOUND"));
        } catch (Exception e) {
            log.error("Unexpected error while handling {}", exchange.getRequestURI(), e);
            respond(exchange, 500, Map.of("error", "INTERNAL_ERROR"));
        } finally {
            exchange.close();
        }
    }

    private void authorize(HttpExchange exchange) throws IOException {
        PaymentRequest request;
        try {
            request = mapper.readValue(readBody(exchange), PaymentRequest.class);
        } catch (Exception e) {
            respond(exchange, 400, Map.of("error", "MALFORMED_JSON"));
            return;
        }

        List<String> problems = validate(request);
        if (!problems.isEmpty()) {
            respond(exchange, 400, Map.of("error", "VALIDATION_FAILED", "details", problems));
            return;
        }

        if (request.amountCents() > DECLINE_THRESHOLD_CENTS) {
            respond(exchange, 402, Map.of(
                    "status", "DECLINED",
                    "reason", "LIMIT_EXCEEDED"));
            return;
        }

        String now = OffsetDateTime.now(ZoneOffset.UTC).format(ISO_SECONDS);
        Payment payment = new Payment(
                UUID.randomUUID().toString(),
                request.orderId(),
                request.amountCents(),
                request.currency(),
                "AUTHORIZED",
                now);
        payments.put(payment.paymentId(), payment);

        respond(exchange, 201, payment);
    }

    private void getPayment(HttpExchange exchange, String paymentId) throws IOException {
        Payment payment = payments.get(paymentId);
        if (payment == null) {
            respond(exchange, 404, Map.of("error", "PAYMENT_NOT_FOUND"));
            return;
        }
        respond(exchange, 200, payment);
    }

    private List<String> validate(PaymentRequest request) {
        if (request.orderId() == null || request.orderId().isBlank()) {
            return List.of("orderId must not be blank");
        }
        if (request.amountCents() <= 0) {
            return List.of("amountCents must be positive");
        }
        if (request.currency() == null || !SUPPORTED_CURRENCIES.contains(request.currency())) {
            return List.of("currency must be one of " + SUPPORTED_CURRENCIES);
        }
        return List.of();
    }

    private String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
