# 05. Шпаргалка

## 1. Что выбрать под задачу

```
Нужно проверить...
│
├─ мою логику/расчёты ──────────────────────────────► unit-тест (JUnit, Mockito)
│
├─ мой сервис + чужие HTTP-сервисы ─────────────────► WireMock (in-JVM)
│   ├─ и нужны ошибки/таймауты/обрывы ──────────────► WireMock withFault / withFixedDelay
│   └─ и нужны «правильные» ожидания от чужого ─────► + Pact consumer test
│
├─ что чужой сервис всё ещё соответствует нашим
│  ожиданиям ───────────────────────────────────────► Pact provider verification (в CI провайдера)
│
├─ что версия совместима со всем, что уже в стейдже ► pact-broker can-i-deploy
│
├─ мою интеграцию с БД/очередью/кэшем ──────────────► Testcontainers (postgres/kafka/redis)
│
├─ реальный артефакт (образ), который поедет в прод ► Testcontainers + образ сервиса
│
├─ что стенд с виртуальными сервисами работает ────► WireMock standalone (compose) + Testcontainers
│
└─ что пользователь может пройти сценарий целиком ──► e2e на стенде (3-5 ключевых путей)
```

## 2. Команды репозитория

```bash
mvn test                                  # всё (docker-тесты скипнутся без Docker, broker-тест выключен)
mvn test -Dtest="A_BasicStubTest,..."      # выборочно
mvn test -Dtest=PaymentConsumerContractTest        # сгенерировать контракт в pacts/
mvn test -Dtest=PaymentProviderVerificationTest    # верифицировать провайдера по pacts/
mvn test -Dtest=PaymentProviderBrokerVerificationTest -Dpact.broker.url=http://localhost:9292
mvn exec:java                              # поднять payment-service на :8081

docker compose up -d wiremock              # WireMock-стенд на :8089
docker compose up -d pact-broker           # broker на :9292
docker compose down                        # остановить
./scripts/publish-pact.ps1                 # опубликовать контракт
./scripts/record-deployment.ps1 -Pacticipant payment-service -Version 1.0.0 -Environment production
./scripts/can-i-deploy.ps1 -Pacticipant order-service -Version 1.0.0 -Environment production
                                           # exit 0 = можно катить, exit 1 = нельзя

curl http://localhost:8089/payments -H "Content-Type: application/json" `
     -d '{"orderId":"o-1","amountCents":1999,"currency":"EUR"}'
```

### can-i-deploy: что должно быть в брокере

| Факт | Кто пишет | Без него |
|---|---|---|
| контракт версии | consumer CI (`publish`) | `No pacts ... published for version X` |
| результат верификации | provider CI (`publishResults=true`) | `There is no verified pact between ...` |
| какая версия работает в окружении | deploy-джоба (`record-deployment`) | `no version is currently recorded as deployed ...` |

Exit code: `0` — да, `1` — нет. `--dry-run` всегда возвращает `0`, но печатает, что бы сказал гейт.
Полностью — [07-can-i-deploy.md](07-can-i-deploy.md).

## 3. Матчеры: WireMock vs Pact

| Задача | WireMock | Pact |
|---|---|---|
| любая строка | `matching(".*")` / `matchesJsonPath("$.x")` | `stringType("x", "example")` |
| целое | `equalTo("1999")` | `integerType("x", 1)` |
| число-диапазон | `matchingJsonPath("$[?(@.x > 10)]")` | нет прямого (только тип + regex для строк) |
| regex | `matching("EUR|USD")` | `stringMatcher("currency", "EUR|USD", "EUR")` |
| точное значение | `equalToJson("""{...}""")` | `equalTo("status", "AUTHORIZED")` |
| дата | `matching(".*")` | `datetime("createdAt", "yyyy-MM-dd'T'HH:mm:ssXXX", instant)` |
| массив | `matchingJsonPath("$.items[*]", ...)` | `minArrayLike("items", 1)` / `eachLike` |
| заголовок | `withHeader("X", equalTo("v"))` | `.header("X", "v")` (в V4 — `header`/`headers`) |

## 4. Словарь

| Термин | По-русски / смысл |
|---|---|
| **consumer** | сервис-потребитель, который вызывает чужой API |
| **provider** | сервис, который предоставляет API |
| **contract / pact** | файл-договорённость: запрос → ожидаемый ответ + матчеры |
| **provider state** | предусловие на стороне провайдера («платёж P-1 существует») |
| **verification** | прогон контракта против живого провайдера |
| **Pact Broker** | хранилище контрактов + результатов верификации + can-i-deploy |
| **can-i-deploy** | «совместима ли версия X со всем, что уже развёрнуто в окружении Y» |
| **virtual service** | WireMock-стенд, изображающий чужой сервис |
| **stub / mapping** | правило «такой запрос → такой ответ» |
| **scenario** | состояние стаба между вызовами (503 → 201) |
| **tolerant reader** | умение клиента игнорировать неизвестные поля |
| **fixture** | подготовленные данные для теста |
| **flaky** | нестабильный тест, который иногда падает без изменения кода |

## 5. Типовые ошибки → быстрый фикс

| Симптом | Причина | Фикс |
|---|---|---|
| `Could not find a valid Docker environment` | Docker не запущен | запустить Docker Desktop / CI-джоб с Docker |
| `No tests matching pattern "com.pkg.*"` | surefire не понимает пакет в `-Dtest` | перечисляйте классы: `-Dtest=A_Test,B_Test` |
| `No stub for ...` / 404 от WireMock | стабов нет или не сматчилось | `findAllUnmatchedRequests()`, проверить матчеры/приоритеты |
| WireMock вернул 500 и пустоту | `withFault` без тела — это норма | это и есть проверка обработки обрыва |
| Pact: `Method ... does not conform required method signature 'V4Pact ...'` | старый DSL | использовать `PactBuilder` + `V4Pact` |
| Pact: «expected ... but was ...» | провайдер реально изменил ответ | либо чинить провайдера, либо обновлять контракт |
| Pact: верификация «не видит» контракты | не тот `pact.rootDir`/`@PactFolder`/broker URL | проверить `-Dpact.rootDir`, лог загрузки |
| Pact: `Skipping publishing of verification results` | не задан `pact.verifier.publishResults=true` | добавить в CI |
| can-i-deploy: `no version is currently recorded as deployed/released in this environment` | не пишутся деплои | `record-deployment` в deploy-джобе (или `scripts/record-deployment.ps1`) |
| can-i-deploy: `There is no verified pact between version X ...` | контракт изменился, провайдер его не верифицировал | прогнать provider verification для этой версии контракта |
| can-i-deploy: `No pacts or verifications have been published for version X` | такой версии нет в брокере | проверить версию/пактиципанта, опубликовать контракт |
| Broker: `Cannot change the content of the pact ...` | перепубликация того же `version` с другим содержимым | публиковать новую версию (в CI это git sha) |
| Pact CLI в Docker: `ECONNREFUSED ... localhost:9292` | подкоманда игнорирует `--broker-base-url` | смотреть URL в тексте ошибки; использовать HTTP API/рабочий флаг |
| Порт занят при `mvn exec:java` | :8081 занят | `PaymentServiceServer.start(0)` или освободить порт |

## 6. Десять правил, которые сэкономят вам месяц

1. Стаб — это **фикстура**, а не «наш собственный прод». Минимум полей.
2. Контракт — это **только то, что consumer реально использует**.
3. Матчеры вместо значений; примеры — детерминированные.
4. Provider verification должен быть **в CI провайдера**, иначе контрактов нет.
5. Динамические порты везде. `resetAll()` в `@BeforeEach`.
6. Ошибки (4xx/5xx/таймаут/обрыв) — обязательная часть тестов, не «потом».
7. `waitingFor`, а не `Thread.sleep`.
8. Образы — с пином версии, не `latest`.
9. E2E на стенде — 3-5 путей, а не «всё, что смогли придумать».
10. Любой новый инструмент внедряется через **один реальный пойманный баг**, а не «потому что модно».
