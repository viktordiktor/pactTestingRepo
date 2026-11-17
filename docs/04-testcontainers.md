# 04. Testcontainers: зачем, когда и как

Код к этой главе: `src/test/java/com/nikanenka/step4_testcontainers/WireMockInDockerTest.java`,
плюс `docker-compose.yml` для ручного стенда.

---

## 1. Что это

Testcontainers — библиотека, которая **из кода теста** запускает Docker-контейнеры и убирает их после теста.

```java
@Testcontainers(disabledWithoutDocker = true)
class WireMockInDockerTest {

    @Container
    static final GenericContainer<?> WIREMOCK =
            new GenericContainer<>(DockerImageName.parse("wiremock/wiremock:3.13.2"))
                    .withExposedPorts(8080)
                    .withCommand("--global-response-templating", "--verbose")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("wiremock").toAbsolutePath()),
                            "/home/wiremock")
                    .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    @Test
    void authorizePayment() {
        String baseUrl = "http://" + WIREMOCK.getHost() + ":" + WIREMOCK.getMappedPort(8080);
        // ... обычный тест на OrderServiceClient
    }
}
```

Что произошло:
1. Testcontainers через Docker API запустил контейнер `wiremock/wiremock:3.13.2`.
2. Пробросил порт 8080 на случайный порт хоста (`getMappedPort(8080)`).
3. Дождался, пока WireMock реально начнёт отвечать (`waitingFor`), а не «просто 5 секунд поспим».
4. Скопировал внутрь наши маппинги (те же, что использует стенд).
5. После тестов остановил контейнер и всё убрал (этим занимается **Ryuk** — отдельный контейнер-мусорщик,
   который переживает падение JVM).

---

## 2. Зачем это AQA, если есть Docker Compose

Compose-файл — это «стенд, который кто-то запустил». Testcontainers — это «инфраструктура, которая
является частью теста».

| | Docker Compose | Testcontainers |
|---|---|---|
| Кто запускает | человек/CI-шаг | сам тест |
| Изоляция | общие порты, общие данные, общий «кто-то уже занял 5432» | случайные порты, своё состояние |
| Параллельный запуск | конфликты | несколько копий одновременно |
| «Забыл поднять» | тест упал непонятно | тест поднял сам |
| Готовность | `sleep 10` и надежда | `waitingFor` |
| Подходит для | ручное тестирование, стенд, демо | автотесты |

Практика обычно такая: **compose для людей, Testcontainers для тестов**. У нас в репозитории есть оба,
и они используют один и тот же `wiremock/mappings` — это правильный признак.

---

## 3. Главные кейсы для AQA

### 3.1 Настоящая БД вместо H2

```java
@Container
static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
```

Зачем: H2 «почти как Postgres», но не Postgres. Разные типы, ограничения, `ON CONFLICT`, JSONB,
локаль, сортировка. Тест на H2 зелёный, прод на Postgres — красный. Классика.

### 3.2 Kafka / RabbitMQ / Redis / S3 (MinIO)

```java
@Container
static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));
```

Зачем: проверить, что сервис реально публикует/читает сообщения, а не «мок очереди вернул ок».

### 3.3 Ваш собственный сервис в контейнере

```java
@Container
static GenericContainer<?> provider = new GenericContainer<>("registry/payment-service:" + tag)
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/actuator/health").forStatusCode(200));
```

Зачем: проверить **артефакт, который реально поедет в прод** (образ), а не классы из IDE.
Это уже почти «интеграционный тест на стенде», но воспроизводимый.

### 3.4 Инфраструктура для Pact

Контейнер с Pact Broker (+Postgres), контейнер с провайдером — тогда `can-i-deploy` проверяется
в тесте, а не «когда-нибудь в пайплайне».

### 3.5 WireMock как отдельный процесс (наш пример)

Зачем: проверить, что маппинги, которые вы положили на стенд, действительно работают как задумано
(монтирование файлов, флаги запуска, шаблоны, приоритеты). В JVM-режиме вы проверите Java-API,
а в контейнере — ровно ту конфигурацию, которую увидят коллеги.

---

## 4. Паттерны, которые стоит знать

**Контейнер на класс** (`static` + `@Container`): запускается один раз, живёт весь класс. Быстро.
**Контейнер на тест** (`@Container` без `static`): чистые данные на каждый тест, но медленно.
**Singleton-контейнер** (`static` + свой `start()` в `@BeforeAll` базового класса): один на всю JVM,
все тесты переиспользуют. Максимальная скорость, но состояние общее — нужен аккуратный `reset`.

**Reuse** (для локальной разработки):
```java
static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine")
        .withReuse(true);            // + testcontainers.reuse.enable=true в ~/.testcontainers.properties
```
Контейнер не убивается между прогонами → старт за миллисекунды. В CI не включайте.

**Сеть из нескольких контейнеров**:
```java
Network net = Network.newNetwork();
new GenericContainer<>("app:1").withNetwork(net).withNetworkAliases("app");
new GenericContainer<>("wiremock/wiremock").withNetwork(net).withNetworkAliases("stub");
// внутри app можно обращаться по http://stub:8080
```

**Ожидание готовности — только `waitingFor`**:
```java
.waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(60)))
.waitingFor(Wait.forLogMessage(".*Started Application.*", 1))
.waitingFor(Wait.forListeningPort())
```

**Инициализация данных**: `withInitScript("schema.sql")` (для JDBC-контейнеров),
`withCopyFileToContainer(...)`, `withCommand(...)`. Никаких «руками залил дамп на стенд».

**Docker Compose из теста**:
```java
@Container
static DockerComposeContainer<?> stack =
        new DockerComposeContainer<>(new File("docker-compose.yml"))
                .withExposedService("wiremock", 8080)
                .waitingForService("wiremock", Wait.forHttp("/__admin/mappings"));
```

---

## 5. Подводные камни (особенно в CI)

| Проблема | Симптом | Решение |
|---|---|---|
| Docker не установлен/не запущен | «Could not find a valid Docker environment» | `@Testcontainers(disabledWithoutDocker = true)` для локальной разработки; в CI — обязательный Docker-джоб |
| Медленный первый запуск | 1-2 минуты на pull образа | кешировать образы в CI, `withReuse` локально, лёгкие alpine-образы |
| Порт занят | конфликт | никогда не маппить фиксированный порт, всегда `getMappedPort` |
| Тест «побежал» раньше сервиса | `Connection refused` | `waitingFor`, а не `Thread.sleep` |
| Windows/macOS | другой механизм виртуализации, медленнее | это нормально; в CI — Linux |
| Ryuk не может подняться (ограничения CI) | контейнеры остаются | `TESTCONTAINERS_RYUK_DISABLED=true` (но тогда чистите сами) |
| Образы из приватного registry | 401 при pull | credentials для docker в CI |
| «Локально зелено, в CI красно» | разные версии Docker/архитектуры | пинить теги образов, не `latest`; в CI тот же образ, что и локально |

Важно про `latest`: тег `latest` в тестах — это отложенный флак. Пините версию, как мы пиним `3.13.2`.

---

## 6. Когда Testcontainers НЕ нужен

* **Юнит-тесты.** Никакой Docker там не нужен и только замедлит.
* **Проверка бизнес-логики.** Моки быстрее и точнее.
* **Если инфраструктура и так поднимается как часть продукта** (например, у вас в CI уже есть
  полноценное окружение на каждый PR) — не дублируйте.
* **Если нет Docker в CI и не будет** — тогда лучше честный dev-стенд + WireMock, чем «тесты,
  которые всегда скипаются».
* **Огромный compose на 15 сервисов** внутри теста — это уже e2e, и он будет медленным и флаки.
  Testcontainers хорошо работает с 1-4 контейнерами.

---

## 7. Как это связано с Pact и WireMock

Testcontainers — не третья альтернатива, а **транспорт для инфраструктуры**:

```
WireMock in-JVM            → быстрые компонентные тесты (без Docker)
WireMock in Docker         → проверка конфигурации стенда (этот репозиторий, шаг 4)
Pact consumer test         → контракт (без Docker)
Pact provider verification → живой сервис (в тесте — в JVM, в реальности — в контейнере/на стенде)
Pact Broker в Docker       → хранение контрактов (compose)
Postgres в Testcontainers  → реальная БД для интеграционных тестов
```

---

## 8. Что попробовать руками

1. Запустите `mvn test -Dtest=WireMockInDockerTest` при выключенном Docker — тесты должны
   **скипнуться**, а не упасть. Включите Docker — пройдут.
2. Добавьте второй `@Container` с `postgres:16-alpine` и проверьте, что он поднимается
   (даже если не используете — посмотрите на время старта и на то, как Testcontainers его уберёт).
3. Поменяйте версию образа WireMock на несуществующую — посмотрите, как выглядит ошибка pull.
4. Попробуйте `DockerComposeContainer` на нашем `docker-compose.yml` (сервис `wiremock`).
