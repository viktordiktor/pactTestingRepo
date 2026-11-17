# 02. WireMock: практическое использование

Код к этой главе: `src/test/java/com/nikanenka/step1_wiremock/` и `wiremock/mappings/`.

---

## 1. Что это такое

WireMock — HTTP-сервер, которому вы описываете правило:

> «Если пришёл `POST /payments` с телом, где `amountCents = 1999`, ответь `201` с таким-то JSON».

Он умеет:
* матчить запросы по методу, пути, query, заголовкам, телу (JSON/XML/текст), cookies;
* отдавать заданный ответ: статус, заголовки, тело, задержку;
* **ломаться по команде**: 5xx, таймаут, обрыв соединения, мусор в ответе;
* вести журнал запросов и проверять, что именно вы отправили (`verify`);
* менять поведение по шагам (`scenario`);
* записывать реальный трафик и превращать его в маппинги (record/playback).

Технически это Java-приложение (Jetty + свой matching engine). Поэтому у него два режима жизни:

| Режим | Как запускается | Где живёт | Для чего |
|---|---|---|---|
| **In-JVM** | `new WireMockServer(options().dynamicPort())` в `@BeforeAll` | внутри процесса теста | юнит/компонентные тесты, максимально быстро, ничего не нужно согласовывать |
| **Standalone** | Docker-контейнер / `java -jar wiremock-standalone.jar` | отдельный процесс, порт, стенд | «виртуальный сервис» для команды: ручное тестирование, e2e, демо, ещё не готовая интеграция |

Оба режима используют **одни и те же** маппинги. В этом репозитории так и сделано:
`WireMockInDockerTest` поднимает контейнер с теми же файлами, что и `docker compose`.

---

## 2. Режим 1: WireMock в JVM (то, с чего начинают)

```java
WireMockServer wireMock = new WireMockServer(options().dynamicPort());
wireMock.start();

wireMock.stubFor(post(urlEqualTo("/payments"))
        .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"paymentId": "pay_1", "status": "AUTHORIZED"}
                        """)));

OrderServiceClient client = new OrderServiceClient(wireMock.baseUrl());
PaymentResult result = client.authorizePayment(new Order("order-1", 1999, "EUR"));

wireMock.verify(postRequestedFor(urlEqualTo("/payments"))
        .withRequestBody(matchingJsonPath("$.orderId", equalTo("order-1"))));
```

Правила, которые экономят часы:

1. **`dynamicPort()`**, а не `8089`. Иначе два прогона (или два теста в параллель) подерутся за порт.
2. **Один сервер на класс**, `@BeforeEach wireMock.resetAll()`. Иначе стабы протекают между тестами —
   и вы полдня ищете, почему «в одном тесте работает, в другом нет».
3. **Тестируете свой код, а не мок.** Утверждения — про поведение клиента (`result.status()`),
   а `verify` — про то, что клиент правильно сформировал запрос.
4. **Не пишите в стабе то, что не важно.** Если клиенту нужен только `status`, не надо копировать
   весь ответ продакшена на 40 полей: такое «зеркало» устареет через неделю.

---

## 3. Матчеры: шпаргалка

Запрос:

| Что матчим | Пример |
|---|---|
| путь | `urlEqualTo("/payments")`, `urlPathEqualTo("/payments")`, `urlPathMatching("/payments/.*")`, `urlMatching("/payments\\?.*")` |
| метод | `post(...)`, `get(...)`, `anyUrl()`, `request("PATCH", ...)` |
| заголовок | `.withHeader("Content-Type", containing("application/json"))`, `equalTo`, `matching(".*json")`, `absent()` |
| JSON-тело целиком | `equalToJson(json, true, true)` — `ignoreArrayOrder`, `ignoreExtraElements` |
| поле JSON | `matchingJsonPath("$.orderId", equalTo("order-1"))` |
| JSONPath-условие | `matchingJsonPath("$[?(@.amountCents > 500000)]")` |
| XPath | `matchingXPath("/order/total[text()='100']")` |
| форма | `equalToJson`, `matchingJsonSchema(...)`, `equalToXml` |
| отсутствие/наличие | `.withRequestBody(absent())` |

Ответ:

| Что задаём | Пример |
|---|---|
| статус | `withStatus(402)` |
| заголовки | `withHeader("Content-Type", "application/json")` |
| тело строкой | `withBody("...")` |
| тело JSON-объектом | `withJsonBody(...)` / в файле `"jsonBody": {...}` |
| тело из файла | `withBodyFile("payment-not-found.json")` / `"bodyFileName"` |
| задержка | `withFixedDelay(3000)`, `withUniformRandomDelay(100, 500)`, `withChunkedDribbleDelay(...)` |
| разрыв/мусор | `withFault(Fault.CONNECTION_RESET_BY_PEER)`, `EMPTY_RESPONSE`, `MALFORMED_RESPONSE_CHUNK` |
| шаблон | `withBody("...{{jsonPath request.body '$.orderId'}}...")` + `globalTemplating(true)` |
| приоритет | `atPriority(1)` — **чем меньше, тем важнее** |

---

## 4. Приоритеты: как описать «если, иначе»

В WireMock нет `if/else`. Есть несколько стабов на один URL и **приоритет**.
Наш пример (`wiremock/mappings/`):

```jsonc
// 02-payments-decline.json: priority 1 (важнее)
{ "priority": 1,
  "request": { "method": "POST", "urlPath": "/payments",
               "bodyPatterns": [{ "matchesJsonPath": "$[?(@.amountCents > 500000)]" }] },
  "response": { "status": 402, "jsonBody": { "status": "DECLINED", "reason": "LIMIT_EXCEEDED" } } }

// 01-payments-authorize.json: priority 5 (сработает, только если первый не подошёл)
{ "priority": 5,
  "request": { "method": "POST", "urlPath": "/payments",
               "bodyPatterns": [{ "matchesJsonPath": "$[?(@.amountCents <= 500000)]" }] },
  "response": { "status": 201, ... } }
```

Тонкость: если подошли два стаба с одинаковым приоритетом, WireMock выберет **последний добавленный**
(в standalone — порядок не гарантирован между файлами). Поэтому приоритеты лучше указывать явно.

---

## 5. Шаблоны ответов: стаб, который «ведёт себя как живой»

Без шаблонов стаб всегда отвечает одинаково, и вы не поймаете баг «клиент перепутал поля местами».

```java
WireMockServer wireMock = new WireMockServer(options().dynamicPort().globalTemplating(true));
...
.withBody("""
        { "paymentId": "pay_1",
          "orderId": "{{jsonPath request.body '$.orderId'}}",
          "amountCents": {{jsonPath request.body '$.amountCents'}} }
        """)
```

Полезные хелперы Handlebars:

| Хелпер | Пример |
|---|---|
| поле из запроса | `{{jsonPath request.body '$.orderId'}}` |
| случайное значение | `{{randomValue length=8 type='ALPHANUMERIC'}}` |
| UUID | `{{randomValue type='UUID'}}` |
| текущее время | `{{now}}`, `{{now offset='3 days' format='yyyy-MM-dd'}}` |
| заголовок запроса | `{{request.headers.X-Correlation-Id}}` |
| query-параметр | `{{request.query.id}}` |

Глобально шаблоны включаются `--global-response-templating` (Docker) или
`options().globalTemplating(true)` (Java). Точечно — `"transformers": ["response-template"]` в стабе.

> ⚠️ Не увлекайтесь. Шаблон — это уже мини-логика внутри мока. Если вам нужно «посчитать сумму налога» —
> это не задача WireMock.

---

## 6. Плохие сценарии: главный аргумент за WireMock

Код: `C_FaultsAndTimeoutsTest`.

```java
// провайдер тормозит 3 секунды, у клиента таймаут 500 мс
stubFor(post(anyUrl()).willReturn(aResponse().withStatus(201).withFixedDelay(3_000)));
new OrderServiceClient(wireMock.baseUrl(), Duration.ofMillis(500))
        .authorizePayment(...);   // -> PaymentUnavailableException

// обрыв TCP
stubFor(post(anyUrl()).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

// 503 с телом
stubFor(post(anyUrl()).willReturn(aResponse().withStatus(503)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"error\":\"SERVICE_UNAVAILABLE\"}")));
```

Сравните с реальностью: чтобы проверить это на стенде, нужно остановить под, включить toxiproxy,
попросить DevOps... а через час «оно само заработало». Здесь — 0.5 секунды и полная повторяемость.

---

## 7. Сценарии: состояние между вызовами

Код: `D_ScenarioTest`.

```java
stubFor(post(anyUrl()).inScenario("payment-service recovers")
        .whenScenarioStateIs(STARTED)
        .willSetStateTo("RECOVERED")
        .willReturn(aResponse().withStatus(503)));

stubFor(post(anyUrl()).inScenario("payment-service recovers")
        .whenScenarioStateIs("RECOVERED")
        .willReturn(aResponse().withStatus(201).withBody(...)));
```

Первый вызов — 503, второй — 201. Так проверяют retry, идемпотентность, «заказ создан → оплачен»,
«товар зарезервирован → продан».

Управление состоянием вручную:

```java
wireMock.setScenarioState("payment-service recovers", "RECOVERED");
wireMock.resetScenario("payment-service recovers");   // вернуть в STARTED
wireMock.resetScenarios();                            // все сценарии
```

---

## 8. Проверка запросов и отладка

```java
wireMock.verify(postRequestedFor(urlEqualTo("/payments"))
        .withHeader("Content-Type", containing("application/json"))
        .withRequestBody(matchingJsonPath("$.amountCents", equalTo("1999"))));

wireMock.verify(2, postRequestedFor(anyUrl()));       // ровно 2 вызова
wireMock.verify(0, postRequestedFor(...));            // не вызывали вовсе
```

Когда тест «непонятно почему красный», смотрите:

| Что | Как |
|---|---|
| какие запросы пришли и что мы ответили | `wireMock.getAllServeEvents()` / `wireMock.getServeEvents()` |
| какие запросы **не** нашли стаб | `wireMock.findAllUnmatchedRequests()` |
| почему не сматчилось | в `ServeEvent` есть `getWasMatched()` и `getStubMapping()` |
| то же через HTTP (standalone) | `GET /__admin/requests`, `GET /__admin/mappings`, `POST /__admin/requests/reset` |

Совет: в CI при падении выводите `findAllUnmatchedRequests()` в лог. Это 90% времени отладки.

---

## 9. Standalone-режим: «виртуальный сервис» на стенде

Это тот самый кейс «сервис ещё не готов / не хочу зависеть от чужого стенда».

`docker-compose.yml`:

```yaml
wiremock:
  image: wiremock/wiremock:3.13.2
  command: ["--global-response-templating", "--verbose"]
  ports: ["8089:8080"]
  volumes: ["./wiremock:/home/wiremock:ro"]
```

Структура папки (её и видит контейнер как `/home/wiremock`):

```
wiremock/
├── mappings/          ← *.json: правила «запрос → ответ»
│   ├── 01-payments-authorize.json
│   ├── 02-payments-decline.json
│   ├── 03-payment-get.json
│   └── 04-payment-not-found.json
└── __files/           ← большие тела ответов, на них ссылаются через bodyFileName
    └── payment-not-found.json
```

Что это даёт:
* **маппинги в git** → они ревьюятся, версионируются, откатываются;
* **один и тот же WireMock** используют и автотесты, и ручное тестирование, и разработка;
* **изменение API** = PR в файл маппинга, а не «я поправил на стенде, и оно потерялось».

Правила гигиены:
1. Именуйте файлы с префиксами (`01-`, `02-`), иначе порядок загрузки сбивает с толку.
2. Приоритеты — явно.
3. Не держите на «виртуальном сервисе» бизнес-логику, которая есть в реальном сервисе:
   рано или поздно расхождение станет багом в проде.
4. Версионируйте образ и маппинги вместе: «стенд сломался» чаще всего значит «маппинг обновили, а consumer
   ещё на старой версии».

### Record / playback: маппинги из реального трафика

Если сервис уже есть (например, dev-инстанс), можно записать его ответы:

```java
wireMock.startRecording("http://real-payment-service:8080");
// ... прогоняем сценарии через прокси WireMock ...
wireMock.stopRecording();
wireMock.snapshotRecord();   // превратить записанное в стабы
```

или в standalone: `--proxy-all="http://real-service" --record-mappings`.

Это хороший способ **не выдумывать** ответы руками. Но записи надо чистить: там будут токены,
даты, персональные данные. И запись — это снимок, а не контракт: он не знает, что важно, а что случайно.
Именно поэтому следующий шаг — Pact.

---

## 10. WireMock vs Pact vs Mockito — чтобы не путать

| | **WireMock** | **Pact** | **Mockito** |
|---|---|---|---|
| Что подменяет | HTTP-сервис | HTTP-сервис, но по контракту | Java-объект/бин |
| Кто описывает поведение | вы, руками | consumer, по матчерам | вы, руками |
| Проверяет «чужие» ожидания | нет | да, на стороне провайдера | нет |
| Ошибки сети/таймауты | да | нет (это не про контракт) | нет |
| Где живёт мок | в тесте или на стенде | поднимается Pact-ом из контракта | в JVM теста |
| Когда брать | ваш сервис + чужие зависимости; проверка обработки ошибок; виртуальный сервис на стенде | проверить, что провайдер и consumer не разошлись | юнит-тесты классов |

Типичная ошибка: заменить WireMock на Pact «чтобы было модно». Pact **не умеет** имитировать таймаут
и обрыв — а вам для компонентных тестов это нужно. Их не «или», а «и»: WireMock для поведения,
Pact для договорённости.

---

## 11. Антипаттерны WireMock

| Антипаттерн | Почему плохо | Как правильно |
|---|---|---|
| Стаб на всё, включая поля, которые клиент не читает | мок устаревает, тест «зелёный» при сломанной интеграции | стаб = минимальный достаточный ответ |
| Один `WireMockServer` на все тесты без `resetAll()` | тесты зависят от порядка | `@BeforeEach resetAll()` |
| Жёсткий порт | конфликты в CI/параллели | `dynamicPort()` |
| Стабы прямо в тесте, 300 строк | дублирование, не переиспользуется на стенде | вынести в `wiremock/mappings` и/или билдер |
| «Мок всегда 200» | не проверены ошибки, которые обязательно будут в проде | отдельные тесты на 4xx/5xx/таймаут |
| Тест проверяет, что мок вернул то, что в него положили | это тавтология, тест ничего не проверяет | проверяйте свой код и сформированный запрос |

---

## 12. Когда WireMock точно нужен

* Проверить своего клиента против чужого API, не имея доступа к чужой среде.
* Смоделировать 4xx/5xx/таймауты/обрывы/невалидный JSON — воспроизводимо.
* Поднять «виртуальный сервис» для ручного тестирования, пока интеграция не готова.
* Демо/тренинг/разработка без внешних зависимостей.
* Временно подменить дорогой или нестабильный внешний сервис (банк, SMS-шлюз).
* Зафиксировать «эталонные» ответы для регрессии контракта (но лучше — Pact).

## 13. Когда WireMock НЕ нужен

* У вас есть настоящий провайдер и вы хотите проверить с ним интеграцию — берите Pact verification
  или интеграционные тесты.
* Нужно проверить БД/очередь — это Testcontainers, а не WireMock.
* Нужно проверить бизнес-логику — это unit-тесты, Mockito.

---

## 14. Что попробовать руками

1. Запустите `docker compose up -d wiremock` и подёргайте `http://localhost:8089/payments`.
2. Откройте `wiremock/mappings/02-payments-decline.json` и поменяйте порог с `500000` на `1000`.
   Убедитесь, что поведение всего «стенда» изменилось без пересборки тестов.
3. Добавьте маппинг на `POST /refunds` с ответом `202` и шаблоном, который возвращает `orderId` из запроса.
4. В `C_FaultsAndTimeoutsTest` замените `CONNECTION_RESET_BY_PEER` на `EMPTY_RESPONSE` и посмотрите,
   как меняется сообщение об ошибке.
