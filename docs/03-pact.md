# 03. Pact: контрактное тестирование на практике

Код к этой главе: `src/test/java/com/nikanenka/step2_pact_consumer/`, `step3_pact_provider/`,
сгенерированный контракт — `pacts/order-service-payment-service.json`.

---

## 1. Идея в одном абзаце

WireMock отвечает на вопрос «как поведёт себя чужой сервис?» — но отвечаете **вы**, своим воображением.
Pact делает так, чтобы это воображение стало **проверяемым договором**: consumer-тест описывает,
что ему нужно от провайдера (и попутно проверяет клиента), результат сохраняется в файл-контракт,
а провайдер в своём пайплайне прогоняет этот контракт против своего живого сервиса и падает,
если перестал соответствовать.

```
   ┌──── команда consumer ────┐                        ┌──── команда provider ────┐
   │ consumer test (JUnit)    │                        │ provider verification    │
   │   ↓ пишет                │      Pact Broker       │   ↑ читает контракт      │
   │ pact.json ───────────────┼──►  (хранилище)  ──────┼───┘ и проверяет          │
   │                          │                        │   живой сервис           │
   └──────────────────────────┘                        └──────────────────────────┘
```

Ключевое слово — **consumer-driven**: ожидания задаёт потребитель, а не провайдер.
Провайдер не может «в одностороннем порядке» поменять формат: он получит красный билд.

---

## 2. Разбор настоящего контракта

Запустите `mvn test -Dtest=PaymentConsumerContractTest` и откройте
`pacts/order-service-payment-service.json`. Сокращённо одна интеракция выглядит так:

```jsonc
{
  "consumer": { "name": "order-service" },
  "provider": { "name": "payment-service" },
  "interactions": [
    {
      "description": "a request to authorize a payment",     // что проверяем (человекочитаемо)
      "providerStates": [ { "name": "payment-service is available" } ],
      "request": {
        "method": "POST",
        "path": "/payments",
        "headers": { "Content-Type": ["application/json"] },
        "body": {
          "content": { "orderId": "1111...", "amountCents": 1999, "currency": "EUR" },
          "contentType": "application/json"
        },
        "matchingRules": {                                    // ← самое важное
          "body": {
            "$.orderId":     { "matchers": [ { "match": "type" } ] },
            "$.amountCents": { "matchers": [ { "match": "integer" } ] },
            "$.currency":    { "matchers": [ { "match": "regex", "regex": "EUR|USD|GBP" } ] }
          }
        }
      },
      "response": {
        "status": 201,
        "headers": { "Content-Type": ["application/json"] },
        "body": { "content": { "paymentId": "...", "status": "AUTHORIZED", ... } },
        "matchingRules": {
          "body": {
            "$.createdAt": { "matchers": [ { "match": "timestamp", "format": "yyyy-MM-dd'T'HH:mm:ssXXX" } ] },
            "$.paymentId": { "matchers": [ { "match": "type" } ] },
            "$.status":    { "matchers": [ { "match": "type" } ] }
          }
        }
      }
    }
  ]
}
```

Что здесь важно понять:

* **`matchingRules`** — сердце Pact. Без них контракт был бы «провайдер обязан отвечать ровно
  `paymentId: 8a1f1e8c-...`», что бессмысленно. Матчеры говорят: «подойдёт любая строка»,
  «любое целое», «строго из списка валют».
* **`body.content`** — это *пример* значений, а не требование. Он нужен, чтобы провайдеру было что отправить.
* **`providerStates`** — «в каком состоянии должен быть провайдер перед проверкой».
* Один файл — все интеракции между этой парой consumer/provider. В нашем случае их 4.

---

## 3. Матчеры: что выбрать

| Матчер в коде | Что означает | Пример из репозитория |
|---|---|---|
| `stringType("orderId", "1111...")` | любая строка | `orderId` |
| `integerType("amountCents", 1999)` | целое число | сумма в центах |
| `decimalType("rate", new BigDecimal("0.19"))` | дробное число | ставка/курс |
| `booleanType("active", true)` | булево | флаги |
| `stringMatcher("currency", "EUR\|USD\|GBP", "EUR")` | регулярка | валюта |
| `datetime("createdAt", "yyyy-MM-dd'T'HH:mm:ssXXX", Instant)` | строка-дата в формате | `createdAt` |
| `date(...)`, `time(...)` | то же для даты/времени | |
| `numberValue(monthlyFee, 100.0)` | **точное** число (когда точность важна) | например, фиксированный лимит |
| `equalTo("status", "AUTHORIZED")` | **точное** значение | когда это enum и consumer от этого зависит |

Правило: **матчер должен описывать требование, а не текущее состояние данных**.
«Мне нужен любой UUID, потому что я его просто сохраняю» → `stringType`.
«Мне нужно именно `AUTHORIZED`, потому что я по нему принимаю решение» → `equalTo` (или regex по списку).

Что нельзя делать:
* генерировать случайные примеры (`UUID.randomUUID()`) — контракт в git будет «шуметь» на каждый билд;
* матчить `null`-ом «на всякий случай» — `null` в контракте значит «это поле обязательно и должно быть null»;
* описывать поля, которые consumer не читает.

---

## 4. Consumer-тест: как это выглядит в JUnit 5

```java
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service")
class PaymentConsumerContractTest {

    @Pact(consumer = "order-service", provider = "payment-service")
    public V4Pact authorizePaymentPact(PactBuilder builder) {
        return builder
                .given("payment-service is available")                    // provider state
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

    @Test
    @PactTestFor(pactMethod = "authorizePaymentPact")
    void authorizePayment(MockServer mockServer) {
        new OrderServiceClient(mockServer.getUrl())
                .authorizePayment(new Order("order-1", 1999, "EUR"));   // клиент под тестом
    }
}
```

Что происходит при запуске:

1. Pact читает `@Pact`-метод и строит по нему HTTP-мок (по сути — WireMock, встроенный).
2. Мок поднимается на случайном порту, `MockServer.getUrl()` даёт адрес.
3. Тест вызывает **настоящий** `OrderServiceClient`.
4. Pact проверяет: клиент сделал ровно тот запрос, который объявлен; все объявленные интеракции вызваны.
5. После прогона контракт пишется в `pacts/` (в реальном проекте — публикуется в Broker).

> Заметка про версии: в Pact JVM 4.7 старый DSL (`PactDslWithProvider`) больше не принимается
> JUnit-расширением — нужен новый API (`PactBuilder` + `V4Pact`), как в коде выше.
> В интернете много примеров на старом DSL — они не скомпилируются/не запустятся.

---

## 5. Provider verification: главная часть

Код: `PaymentProviderVerificationTest`.

```java
@Provider("payment-service")
@PactFolder("${pact.folder}")                 // или @PactBroker(url = "...")
class PaymentProviderVerificationTest {

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", provider.port(), "/"));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();           // одна интеракция = один «тест»
    }

    @State("payment-service is down")
    void paymentServiceIsDown() {
        provider.setMode(PaymentServiceServer.Mode.DOWN);   // подготовка состояния
    }
}
```

Механика:

* Pact читает все контракты, адресованные `payment-service`.
* Для каждой интеракции: вызывает `@State`-метод → отправляет запрос из контракта в **живой** сервис →
  сравнивает ответ по матчерам.
* Результат — либо зелёный, либо понятная ошибка (см. п. 7).

Это тест **провайдера**, и он должен запускаться **в CI провайдера** на каждый PR.
Пока этого нет — контракты бесполезны: consumer пишет их «в пустоту».

### Provider states

Это ответ на вопрос «а откуда возьмутся данные?». В обычном E2E вы идёте на стенд и «как-то» готовите
данные (или молитесь, что они есть). В Pact вы объявляете состояние, а провайдер его готовит:

| State | Что делает провайдер | В нашем коде |
|---|---|---|
| `payment-service is available` | сбросить состояние | `provider.reset()` |
| `payment-service is down` | включить режим 503 | `provider.setMode(DOWN)` |
| `payment P-1 exists` | создать платёж с id `P-1` | `provider.seedPayment(...)` |

Правила:
* State — это **не** «шаг сценария», а предусловие. Он должен быть идемпотентным.
* Имя state — часть контракта. Поменяли имя у consumer-а — провайдер покраснеет,
  и это хорошо: договорённость изменилась.
* Готовить данные лучше через API/фикстуры/БД, а не «руками на стенде».
* Если состояний стало десять и они конфликтуют — возможно, у вас слишком много интеракций
  или вы контрактите бизнес-процесс, а не интерфейс.

---

## 6. Pact Broker: зачем он нужен

Папка `pacts/` — это игрушечная замена. В реальности контрактов много, consumer-ов много,
версий много, и нужен ответ на вопросы:

* какие контракты актуальны для данной версии провайдера?
* прошла ли верификация для версии, которая уже в стейдже?
* можно ли деплоить эту версию consumer-а?

Broker — это сервис (в нашем `docker-compose.yml` — `pactfoundation/pact-broker` + Postgres),
который хранит контракты, результаты верификаций, версии, ветки, теги и окружения.

Основные операции:

```bash
# consumer: опубликовать контракт
pact-broker publish ./pacts \
  --consumer-app-version=$GIT_SHA \
  --branch=main \
  --broker-base-url=http://localhost:9292

# provider: проверить и опубликовать результаты
mvn test -Dtest=PaymentProviderVerificationTest \
  -Dpact.broker.url=http://localhost:9292 \
  -Dpact.provider.version=$GIT_SHA \
  -Dpact.verifier.publishResults=true

# перед деплоем: «совместим ли я со всем, что уже в этом окружении?»
pact-broker can-i-deploy \
  --pacticipant order-service --version=$GIT_SHA \
  --to-environment=production \
  --broker-base-url=http://localhost:9292
```

`can-i-deploy` — это и есть практическая польза брокера: гейт в пайплайне, который не даёт выкатить
версию, чьи контракты не подтверждены.

Он ничего не тестирует: ему нужны **три факта** — опубликованный контракт, результат его верификации
и запись о том, какая версия провайдера сейчас работает в целевом окружении. Нет любого из трёх —
ответ «нет» и `exit 1`. Разбор всех трёх условий, реальные красные выводы, `--dry-run` и
environments vs tags — в отдельной главе **[07-can-i-deploy.md](07-can-i-deploy.md)**.

В локальной версии этого репозитория публикация и запись деплоя сделаны скриптами
(`scripts/publish-pact.ps1`, `scripts/record-deployment.ps1` — то же самое, но HTTP-запросами).

В нашем репозитории оба варианта работают:

```bash
docker compose up -d pact-broker
./scripts/publish-pact.ps1
mvn test -Dtest=PaymentProviderBrokerVerificationTest -Dpact.broker.url=http://localhost:9292
```

Дополнительные полезные штуки в брокере:
* **environments** — «staging», «production» и запись, какая версия где развёрнута
  (`pact-broker record-deployment`; подробно — [07-can-i-deploy.md](07-can-i-deploy.md));
* **pending pacts / WIP** — новые контракты не валят провайдера сразу, а помечаются «в работе»;
* **webhooks** — «когда контракт обновился, дёрни CI провайдера».

---

## 7. Как выглядит падение (реальный вывод из этого репозитория)

Если провайдер стал отвечать `200` вместо `201`:

```
Verifying a pact between order-service and payment-service
      has status code 201 (FAILED)
1) Verifying a pact between order-service and payment-service - a request to authorize a payment: has status code 201
    1.1) status: expected status of 201 but was 200
```

Если расходится тело — будет путь до поля и ожидание/факт:

```
    1.2) body: $.status -> Expected "AUTHORIZED" (String) but received "PENDING"
```

Как читать:

1. **Кто виноват** — из текста видно, какой consumer и какая интеракция.
2. **Что именно** — статус/заголовок/поле.
3. **Что делать** — либо провайдер возвращает совместимость, либо consumer осознанно меняет контракт
   (и тогда обе стороны это видят в PR).

Важно: падение верификации — это **не** «тест нестабилен». Это конкретное расхождение договора.
Флаки в Pact бывают только если вы сделали недетерминированные матчеры или зависимые от времени данные.

---

## 8. Что Pact НЕ проверяет (и это нормально)

* Бизнес-логику («правильно ли посчитана сумма»).
* Сценарии из нескольких вызовов («создал → оплатил → отменил») — это e2e/интеграционные тесты.
* БД, очереди, кэши, конфигурацию, таймауты, ретраи, сеть.
* Производительность.
* UI.

Pact проверяет **протокол между двумя сервисами**. Если у вас в одном «сервисе» 20 эндпоинтов и
сложный процесс — Pact закроет только стык, а не процесс.

---

## 9. FAQ и типовые ошибки

**«Зачем матчеры, давайте сравнивать точно?»**
Тогда любой `paymentId` или `createdAt` будет ломать верификацию. Матчеры отделяют «требование»
от «случайного значения».

**«У меня 40 полей в ответе, надо все описать?»**
Нет. Только те, которые consumer читает и на которые опирается. Иначе вы фиксируете внутренности
провайдера и запрещаете ему рефакторинг.

**«Провайдер добавил поле — всё сломалось».**
Не должно: лишние поля в ответе Pact игнорирует. Если сломалось — вы где-то заматчили объект целиком.

**«Consumer пишет контракт, а провайдер его не проверяет».**
Значит, у вас нет контрактного тестирования, есть только файлы. Верификация в CI провайдера обязательна.

**«Контракт сломался, но провайдер говорит, что ничего не менял».**
Смотрите `pact-broker` diff между версиями контракта — обычно consumer изменил ожидания
(и это законно, если он обновился).

**«У нас provider states делают через прямой доступ к БД»** — норм для тестов, но лучше через
тестовые API/фикстуры, чтобы не зависеть от схемы БД.

**«Pact поддерживает Kafka/GraphQL/gRPC?»**
Да, message pacts, плагины (`pact-jvm` + plugins). Но начинать надо с HTTP: 90% пользы там.

**«Можно ли использовать Pact вместо WireMock?»**
Нет. Pact не умеет имитировать таймаут/обрыв и не предназначен для «виртуальных сервисов» на стенде.

---

## 10. С чего начать внедрение (рецепт для AQA)

1. **Выберите один стык**, самый болезненный (тот, который чаще всего ломается или блокирует вас).
2. **Напишите consumer-тест** на 3-5 реально используемых интеракций (happy path + 1-2 ошибки).
3. **Покажите провайдеру** файл контракта: часто это первый раз, когда обе команды видят
   «что на самом деле ждут друг от друга».
4. **Включите verification в CI провайдера** — один тест-класс, 10 строк.
5. **Поднимите Broker** (или PactFlow) и подключите `can-i-deploy` как гейт.
6. **Договоритесь о правилах**: что контрактим, что нет; кто владеет state; как часто публикуем.
7. Только после этого **убирайте лишние e2e** — по одному, начиная с самых флаки.

Не начинайте с «покроем контрактами всё». Начните с одного стыка и одного реального падения,
которое эти контракты поймали. Дальше команда сама захочет ещё.
