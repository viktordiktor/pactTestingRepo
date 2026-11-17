package com.nikanenka.step4_testcontainers;

import com.nikanenka.order.Order;
import com.nikanenka.order.OrderServiceClient;
import com.nikanenka.order.PaymentDeclinedException;
import com.nikanenka.order.PaymentResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ШАГ 4. Testcontainers: WireMock как отдельный процесс в Docker.
 *
 * <p>Зачем, если уже есть WireMock в JVM (шаг 1)?
 * <ul>
 *   <li>На стенде WireMock - это отдельный сервис. Здесь мы проверяем ровно ту конфигурацию,
 *       которая крутится на стенде: те же файлы маппингов, тот же образ, те же флаги запуска.</li>
 *   <li>Можно проверить то, что в JVM не проверишь: сеть, порты, монтирование файлов, health-check,
 *       поведение при перезапуске контейнера.</li>
 *   <li>Одинаково работает локально, в CI и у коллеги на ноутбуке: нужен только Docker.</li>
 * </ul>
 *
 * <p>{@code disabledWithoutDocker = true} - если Docker не запущен, тесты не падают, а помечаются
 * как skipped с понятным сообщением. Для локальной разработки это удобно, для CI - повод
 * поставить отдельный job, где Docker обязателен.
 */
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Шаг 4 - Testcontainers: WireMock в Docker")
class WireMockInDockerTest {

    private static final int WIREMOCK_PORT = 8080;

    @Container
    static final GenericContainer<?> WIREMOCK =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
                    .withExposedPorts(WIREMOCK_PORT)
                    // те же флаги, что и в docker-compose.yml
                    .withCommand("--global-response-templating", "--verbose")
                    // монтируем те же самые маппинги, что использует стенд
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("wiremock").toAbsolutePath()),
                            "/home/wiremock")
                    .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    private String baseUrl() {
        return "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(WIREMOCK_PORT);
    }

    @Test
    @DisplayName("Контейнер отдаёт те же маппинги, что и стенд: успешная оплата")
    void authorizePayment_againstContainerizedStub() {
        OrderServiceClient client = new OrderServiceClient(baseUrl());

        PaymentResult result = client.authorizePayment(new Order("order-1", 1999, "EUR"));

        assertThat(result.status()).isEqualTo("AUTHORIZED");
        assertThat(result.paymentId()).isEqualTo("pay_stand_1");
    }

    @Test
    @DisplayName("Приоритеты маппингов работают и в контейнере: сумма сверх лимита -> 402")
    void declinedPayment_againstContainerizedStub() {
        OrderServiceClient client = new OrderServiceClient(baseUrl());

        assertThatThrownBy(() -> client.authorizePayment(new Order("order-2", 600_000, "EUR")))
                .isInstanceOf(PaymentDeclinedException.class)
                .hasMessageContaining("LIMIT_EXCEEDED");
    }

    @Test
    @DisplayName("GET по существующему платежу отдаёт 200 с телом из маппинга")
    void getPayment_againstContainerizedStub() {
        OrderServiceClient client = new OrderServiceClient(baseUrl());

        PaymentResult result = client.getPayment("P-1");

        assertThat(result.paymentId()).isEqualTo("P-1");
        assertThat(result.currency()).isEqualTo("EUR");
    }
}
