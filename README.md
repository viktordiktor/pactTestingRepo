# PACT + WireMock + Testcontainers — практикум для AQA

Репозиторий-тренажёр: здесь на одном маленьком примере показано, **зачем** нужны контрактные тесты,
**где** в этой истории WireMock, **при чём тут** Testcontainers и **как это меняет работу тестировщика**,
если раньше вы тестировали только полностью задеплоенный QA-стенд.

Всё, что здесь есть, — рабочий код. `mvn test` проходит.

---

## 1. Главная мысль за 60 секунд

Раньше был один вопрос: «работает ли система целиком на стенде?».
В микросервисах он разваливается на два разных вопроса, и на каждый нужен свой инструмент:

| Вопрос | Кто отвечает | Инструмент | Что тестируем |
|---|---|---|---|
| «Правильно ли **наш** сервис дёргает чужой?» | QA / dev **consumer**-а | **WireMock** (стабы) или **Pact** (контракты) | нашего клиента, без чужого сервиса |
| «Не сломались ли **чужие** потребители от наших изменений?» | dev / QA **provider**-а | **Pact provider verification** | чужой контракт против нашего живого сервиса |
| «Как поднять всё это одинаково локально и в CI?» | QA / DevOps | **Testcontainers** | реальная инфраструктура в Docker из кода теста |

Три инструмента решают три разные боли. Они не заменяют друг друга и не заменяют E2E на стенде —
они снимают с E2E-стенда нагрузку, из-за которой он был медленным и флаки.

**Если совсем коротко:**

- **WireMock** — «я знаю, как выглядит ответ чужого сервиса, дайте мне его по команде, детерминированно,
  вместе с 503, таймаутом и обрывом соединения».
- **Pact** — «мой стаб не должен быть выдумкой: пусть он станет контрактом, который провайдер обязан проверять
  в своём пайплайне».
- **Testcontainers** — «хочу, чтобы стаб/БД/брокер поднимались из кода теста, а не “попроси админа обновить стенд”».

---

## 2. Что внутри

Два «сервиса», которые в реальности живут в разных репозиториях и у разных команд:

```
order-service   (CONSUMER)  ──POST /payments──►  payment-service (PROVIDER)
```

* `src/main/java/com/nikanenka/order` — клиент, который дёргает платежи (`OrderServiceClient`).
* `src/main/java/com/nikanenka/payment` — настоящий HTTP-сервис на JDK `HttpServer` (`PaymentServiceServer`).
  Специально без Spring: суть контрактов в HTTP, а не во фреймворке.

Тесты идут по шагам — **читайте их в этом порядке**, это и есть учебная программа:

```
src/test/java/com/nikanenka/
├── step1_wiremock/            ← WireMock в JVM: стабы, матчеры, шаблоны, ошибки, сценарии
│   ├── A_BasicStubTest
│   ├── B_MatchersAndTemplatingTest
│   ├── C_FaultsAndTimeoutsTest
│   └── D_ScenarioTest
├── step2_pact_consumer/       ← Pact: из ожиданий consumer-а рождается контракт
│   └── PaymentConsumerContractTest
├── step3_pact_provider/       ← Pact: провайдер проверяет контракт на своём живом сервисе
│   ├── PaymentProviderVerificationTest        (контракты из папки pacts/)
│   └── PaymentProviderBrokerVerificationTest  (контракты из Pact Broker)
└── step4_testcontainers/      ← Testcontainers: тот же WireMock, но отдельным контейнером
    └── WireMockInDockerTest
```

Рядом:

```
pacts/                        ← сгенерированный контракт (в реальности его хранит Pact Broker)
wiremock/mappings/*.json      ← «виртуальный сервис» для стенда (standalone WireMock)
wiremock/__files/*.json       ← тела ответов для маппингов
docker-compose.yml            ← WireMock-стенд + Pact Broker + Postgres
scripts/publish-pact.ps1      ← публикация контракта в брокер вручную
scripts/record-deployment.ps1 ← «эта версия теперь работает в окружении X»
scripts/can-i-deploy.ps1      ← гейт: можно ли катить версию в окружение (exit 0/1)
scripts/ci/pact-broker.sh     ← те же операции для CI (curl + jq)
.github/workflows/ci.yml      ← пайплайн GitHub Actions
.gitlab-ci.yml                ← пайплайн GitLab CI
docs/                         ← объяснения, флоу, шпаргалки
```

---

## 3. Как запускать

Требуется JDK 19+ и Maven. Docker нужен только для шага 4 и для брокера.

```bash
# всё сразу (docker-тесты без Docker просто скипнутся, брокерный тест выключен по умолчанию)
mvn test

# только WireMock
mvn test -Dtest="A_BasicStubTest,B_MatchersAndTemplatingTest,C_FaultsAndTimeoutsTest,D_ScenarioTest"

# только контракт consumer-а (создаст pacts/order-service-payment-service.json)
mvn test -Dtest=PaymentConsumerContractTest

# верификация провайдера по контрактам из папки
mvn test -Dtest=PaymentProviderVerificationTest

# поднять настоящий payment-service руками на :8081
mvn exec:java
curl http://localhost:8081/payments -H "Content-Type: application/json" `
     -d '{"orderId":"o-1","amountCents":1999,"currency":"EUR"}'
```

**Стенд с WireMock (как это выглядит «на проекте»):**

```bash
docker compose up -d wiremock
curl http://localhost:8089/payments -H "Content-Type: application/json" `
     -d '{"orderId":"o-1","amountCents":1999,"currency":"EUR"}'
# 201 + тело, собранное из нашего запроса
```

**Полный флоу контрактов с Pact Broker:**

```bash
docker compose up -d pact-broker
./scripts/publish-pact.ps1                     # контракт уехал в брокер
mvn test -Dtest=PaymentProviderBrokerVerificationTest -Dpact.broker.url=http://localhost:9292
# UI брокера: http://localhost:9292

# сказать брокеру, что версия провайдера уже работает в окружении
./scripts/record-deployment.ps1 -Pacticipant payment-service -Version 1.0.0 -Environment production

# гейт перед деплоем: exit 0 = можно, exit 1 = нельзя
./scripts/can-i-deploy.ps1 -Pacticipant order-service -Version 1.0.0 -Environment production
```

Подробно про `can-i-deploy` (все три условия, реальные красные выводы, `--dry-run`, environments vs tags):
**[docs/07-can-i-deploy.md](docs/07-can-i-deploy.md)**.

---

## 4. CI

В репозитории лежат два примера пайплайна с одинаковой логикой:

* `.github/workflows/ci.yml` — GitHub Actions;
* `.gitlab-ci.yml` — GitLab CI.

Логика одна и та же:

```
tests (unit/component/consumer)  →  publish pact  →  provider verification
                                 →  record-deployment  →  can-i-deploy (гейт)
```

В реальном проекте это два разных репозитория и два пайплайна; здесь они в одном файле,
потому что оба «сервиса» живут в одном модуле. Общие bash-функции — `scripts/ci/pact-broker.sh`.

---

## 5. Порядок чтения документации

Документация написана под вопрос «я AQA, я не понимаю, зачем это и когда что применять»:

1. **[docs/01-flow.md](docs/01-flow.md)** — почему флоу «всё задеплоено на QA-стенд» перестал
   масштабироваться, как выглядит современный флоу, кто что делает, как это отражается на вашей работе.
   **Начните отсюда.**
2. **[docs/02-wiremock.md](docs/02-wiremock.md)** — WireMock: два режима работы, анатомия маппинга,
   матчеры, шаблоны, инъекция ошибок, record/playback, антипаттерны.
3. **[docs/03-pact.md](docs/03-pact.md)** — Pact: consumer-driven контракты, разбор нашего файла контракта,
   provider states, брокер, can-i-deploy, как читать падения, чего Pact не делает.
4. **[docs/04-testcontainers.md](docs/04-testcontainers.md)** — Testcontainers: когда он реально нужен,
   паттерны, подводные камни в CI.
5. **[docs/05-cheatsheet.md](docs/05-cheatsheet.md)** — шпаргалка: команды, «что выбрать под задачу»,
   таблица матчеров, словарь терминов, типовые ошибки.
6. **[docs/06-practice.md](docs/06-practice.md)** — 14 заданий, чтобы руками пройти весь путь
   (самое полезное — сломать провайдер и посмотреть, как падает верификация).
7. **[docs/07-can-i-deploy.md](docs/07-can-i-deploy.md)** — гейт деплоя: какие три факта нужны брокеру,
   как читать «Computer says no», environments против tags, `--dry-run`, реальные ошибки и их лечение.
8. **[docs/08-where-aqa-lives.md](docs/08-where-aqa-lives.md)** — как AQA попадает в репозитории
   и сервисы разработчиков: отдельный автотестовый репозиторий vs тесты в репозитории сервиса,
   как просить доступ, что можно без него, как договариваться. **Если вы раньше работали только
   black-box — начните с этой главы после `01-flow.md`.**

---

## 6. Чего здесь сознательно нет

* **Spring Boot** — чтобы не прятать HTTP за магией. В реальном проекте провайдера поднимают
  `@SpringBootTest(webEnvironment = RANDOM_PORT)` или контейнером приложения.
* **Настоящей БД и Kafka** — но `docs/04-testcontainers.md` объясняет, как это выглядело бы
  (Postgres/Kafka контейнеры в тесте провайдера).
* **Рабочего кластера и секретов** — `.github/workflows/ci.yml` и `.gitlab-ci.yml` показывают
  логику пайплайна (publish → verification → can-i-deploy), но запускаются не в этом репозитории,
  а на вашем CI. Токены и адреса в реальности приходят из CI-переменных.

## 7. Первое, что стоит сделать руками

```bash
mvn test -Dtest=PaymentProviderVerificationTest
```

Затем откройте `src/main/java/com/nikanenka/payment/PaymentServiceServer.java`,
замените `respond(exchange, 201, payment)` на `respond(exchange, 200, payment)`,
запустите тест снова и прочитайте вывод. Это ровно то, что увидит разработчик провайдера,
если сломает контракт. Потом верните как было.
