# 07. can-i-deploy: гейт «а можно ли это выкатывать»

Это продолжение главы [03-pact.md](03-pact.md), раздел 6. Там было «брокер хранит контракты»,
здесь — «брокер отвечает на вопрос, можно ли деплоить». Именно ради этого ответа всё и затевается.

---

## 1. Что это такое в одном абзаце

`can-i-deploy` — команда, которая спрашивает Pact Broker:

> «Версия **X** участника **Y** — совместима ли она со всем, что **уже развёрнуто** в окружении **Z**?»

Ответ — не текст для чтения, а **код возврата**: `0` = да, `1` = нет. Поэтому это не отчёт,
а **гейт в пайплайне**: если `1`, деплой-джоба падает и версия не уезжает в окружение.

```
   consumer CI                                            Pact Broker
   ───────────                                            ───────────
   tests → publish pact (version 1.4.0) ───────────────►  contract 1.4.0
                                                          + verification results
   can-i-deploy -a order-service -e 1.4.0 ─────────────►  «а payment-service,
      --to-environment staging                             который сейчас в staging,
                                                            проверен против 1.4.0?»
        │                                                        │
        ├── exit 0 ──► деплой разрешён                          │
        └── exit 1 ──► деплой заблокирован ─────────────────────┘
```

Важно: `can-i-deploy` **ничего не тестирует**. Он только сопоставляет три факта, которые уже лежат
в брокере. Если этих фактов нет — он честно скажет «не знаю» и заблокирует деплой (fail closed).

---

## 2. Три факта, без которых ответа не будет

| № | Факт | Кто пишет | Как это выглядит в брокере |
|---|---|---|---|
| 1 | Контракт версии, которую хотим катить | consumer CI после consumer-тестов | `PUT /pacts/provider/.../consumer/.../version/<git sha>` |
| 2 | Результат верификации этого контракта провайдером | provider CI (`pact.verifier.publishResults=true`) | `success` / `failed` у версии контракта |
| 3 | Какая версия провайдера **сейчас работает** в целевом окружении | deploy-джоба провайдера | `record-deployment` (или `record-release`) |

Если нет №1 — «No pacts or verifications have been published for version ...».
Если нет №2 — «There is no verified pact between ...».
Если нет №3 — «no version is currently recorded as deployed/released in this environment».

Третий факт — тот, который чаще всего забывают. Люди публикуют контракты и результаты верификации,
затем запускают `can-i-deploy` и получают «нет», потому что брокер не знает, что вообще развёрнуто.
**Запись деплоя — такая же обязательная часть флоу, как публикация контракта.**

---

## 3. Как он считает (по шагам)

Для consumer-а (`order-service` версии `1.4.0`, окружение `staging`):

1. Находит в брокере все pact-файлы, где `order-service` — consumer, а версия — `1.4.0`.
2. Для каждого провайдера (`payment-service`) смотрит: какая версия `payment-service`
   **записана как развёрнутая** в `staging`.
3. Берёт контракт `order-service 1.4.0 ↔ payment-service` и ищет успешную верификацию
   **именно этой версии контракта** от версии провайдера из п.2.
4. Если для всех провайдеров верификация есть и она успешна → `exit 0`.
   Если хоть для одного нет/провал → `exit 1` и строка в таблице с `???`.

Для провайдера (`payment-service 2.0.0`, окружение `staging`) логика зеркальная:

1. Находит все consumer-версии, **развёрнутые** в `staging`.
2. Для каждой берёт их контракты и проверяет, есть ли успешная верификация этих контрактов
   **версией `payment-service 2.0.0`**.
3. Если кто-то из соседей сломается от вашей версии — `exit 1`.

Именно поэтому провайдерский гейт имеет смысл только когда деплои consumer-ов записаны.
В нашем демо мы их запишем руками — в жизни это делает deploy-джоба каждого сервиса.

### Тонкость про «версию контракта»

Брокер хранит верификацию не «за номером версии consumer-а», а **за содержимым контракта**
(по его хешу). Мы это увидели вживую: опубликовали контракт как `1.1.0`, ничего в нём не поменяв,
и `can-i-deploy` для `1.1.0` сразу стал зелёным — потому что содержимое то же, а верификация
для этого содержимого уже была. Это не баг, а удобство: пересборка без изменения контракта
не требует повторной верификации. Как только меняется хотя бы одна интеракция — хеш другой,
и нужна новая верификация.

В ссылке из вывода `.../pact-version/<hash>/metadata/Y3ZuPTEuMC4w/...` кусок `Y3ZuPTEuMC4w` —
это base64 от `cvn=1.0.0` (`cvn` = consumer version number). Читать это не нужно, просто
чтобы не пугаться.

---

## 4. Демо в этом репозитории (реальный вывод)

Всё ниже прогнано на этом проекте. Брокер: `docker compose up -d pact-broker`.

### 4.1. Подготовка: контракт → верификация → запись деплоя

```bash
./scripts/publish-pact.ps1 -Version 1.0.0
mvn test -Dtest=PaymentProviderBrokerVerificationTest \
  -Dpact.broker.url=http://localhost:9292 \
  -Dpact.verifier.publishResults=true
./scripts/record-deployment.ps1 -Pacticipant payment-service -Version 1.0.0 -Environment production
```

Третья команда — это наш локальный аналог `pact-broker record-deployment` (почему скрипт, а не CLI —
см. раздел 8.3). Она делает `POST` на
`/pacticipants/payment-service/versions/1.0.0/deployed-versions/environment/<uuid>`.

### 4.2. Зелёный ответ

```bash
./scripts/can-i-deploy.ps1 -Pacticipant order-service -Version 1.0.0 -Environment production
```

```
can-i-deploy: order-service 1.0.0 -> production
Computer says yes \o/

CONSUMER      | C.VERSION | PROVIDER        | P.VERSION | SUCCESS? | RESULT#
--------------|-----------|-----------------|-----------|----------|--------
order-service | 1.0.0     | payment-service | 1.0.0     | true     | 1

VERIFICATION RESULTS
--------------------
1. http://localhost:9292/pacts/provider/payment-service/consumer/order-service/pact-version/7808f5b97.../metadata/Y3ZuPTEuMC4w/verification-results/200 (success)

All required verification results are published and successful
can-i-deploy exit code: 0
```

Читается так: «order-service 1.0.0 требует payment-service; в production сейчас payment-service 1.0.0;
его верификация контракта успешна (1 результат)». Можно катить.

### 4.3. Красный ответ №1: в окружении ничего не записано

Если не сделать `record-deployment`:

```
Computer says no ¯\_(ツ)_/¯

CONSUMER      | C.VERSION | PROVIDER        | P.VERSION | SUCCESS? | RESULT#
--------------|-----------|-----------------|-----------|----------|--------
order-service | 1.0.0     | payment-service | ???       | ???      |

There is no verified pact between version 1.0.0 of order-service and a version of payment-service
currently in production (no version is currently recorded as deployed/released in this environment)
```

Это самая частая «ложная тревога» при внедрении: контракты и верификации есть, а деплои не пишутся.

### 4.4. Красный ответ №2: контракт изменился, провайдер его ещё не видел

Публикуем версию `1.3.0`, в которой изменена одна интеракция (добавлено новое требование),
и **не** прогоняем верификацию:

```
Computer says no ¯\_(ツ)_/¯

CONSUMER      | C.VERSION | PROVIDER        | P.VERSION | SUCCESS? | RESULT#
--------------|-----------|-----------------|-----------|----------|--------
order-service | 1.3.0     | payment-service | ???       | ???      |

There is no verified pact between version 1.3.0 of order-service and the version of payment-service
currently in production (1.0.0)
```

Обратите внимание: «P.VERSION = ???» здесь не значит «провайдер не развёрнут» — он развёрнут,
но проверить нечего. Это ровно тот случай, когда consumer добавил требование, а провайдер
ещё не подтвердил, что его выполняет.

### 4.5. Красный ответ №3: такой версии вообще нет в брокере

```
Computer says no ¯\_(ツ)_/¯
No pacts or verifications have been published for version 1.2.0 of order-service
```

Причины: забыли `publish`; опубликовали под другим номером версии; смотрим не тот пактиципант.

### 4.6. Гейт для провайдера

Перед деплоем `payment-service` проверяем, не сломает ли новая версия развёрнутых соседей.
Пишем деплой consumer-а в staging (в жизни — deploy-джоба order-service):

```bash
./scripts/record-deployment.ps1 -Pacticipant order-service -Version 1.0.0 -Environment staging
./scripts/can-i-deploy.ps1 -Pacticipant payment-service -Version 1.0.0 -Environment staging
```

```
can-i-deploy: payment-service 1.0.0 -> staging
Computer says yes \o/

CONSUMER      | C.VERSION | PROVIDER        | P.VERSION | SUCCESS?
--------------|-----------|-----------------|-----------|----------
order-service | 1.0.0     | payment-service | 1.0.0     | true

All required verification results are published and successful
can-i-deploy exit code: 0
```

А если версия провайдера не публиковала результаты верификации:

```
can-i-deploy: payment-service 9.9.9 -> staging
Computer says no ¯\_(ツ)_/¯
No pacts or verifications have been published for version 9.9.9 of payment-service
can-i-deploy exit code: 1
```

### 4.7. `--dry-run`: посмотреть, но не блокировать

Пока команда новая и все только учатся, гейт включают в режиме наблюдения:

```
[dry-run] Computer says no ¯\_(ツ)_/¯ (but you're ignoring this by enabling dry run)
[dry-run] ...
[dry-run] There is no verified pact between version 1.3.0 of order-service and ...
Dry run enabled - ignoring any failures
```

Код возврата `0`, деплой проходит, но в логе видно, что гейт бы сказал «нет».
Через пару недель, когда «нет» перестало быть нормой, `--dry-run` убирают.

---

## 5. Опции, которые реально нужны

```bash
# кого и какую версию проверяем (-a можно указывать несколько раз)
# --to-environment: куда собираемся катить (современный способ)
pact-broker can-i-deploy \
  -a order-service -e "$GIT_SHA" \
  --to-environment staging \
  --broker-base-url "$PACT_BROKER_URL"
```

| Опция | Зачем |
|---|---|
| `--to-environment <name>` | целевое окружение (нужны `record-deployment`) |
| `--to <tag>` | устаревший способ: «сравни с последней версией, помеченной тегом» |
| `--latest [tag]` | проверять последнюю версию пактиципанта, а не конкретную |
| `--branch <name>` / `--main-branch` | выбрать контракты по ветке (важно для feature-веток) |
| `--ignore <pacticipant>` | временно исключить соседа (только осознанно, с комментарием в CI) |
| `--dry-run` | посчитать, но не возвращать `1` |
| `--retry-while-unknown` + `--retry-attempts` | подождать, пока провайдер допубликует верификацию |
| `--output json` | машинный вывод для скриптов/дашбордов |
| `-u/-p` или `-k` | basic auth / bearer token (PactFlow, закрытый брокер) |

`--retry-while-unknown` — недооценённая вещь. В CI consumer и provider идут параллельно:
consumer опубликовал контракт и сразу спрашивает «ну как?», а provider ещё верифицирует.
Без ретраев вы получите случайные красные билды, и все возненавидят гейт.

---

## 6. Environments vs tags: что выбирать в 2026

| | Environments (рекомендуется) | Tags (legacy) |
|---|---|---|
| Модель | «в staging сейчас версия 1.2.3» | «версия 1.2.3 помечена тегом staging» |
| Несколько версий в окружении | да (несколько инстансов) | нет, тег один |
| История деплоев | есть (`record-deployment`/`record-release`) | только текущий тег |
| Флаг `production` | есть (влияет на «released»-семантику) | нет |
| Команда | `--to-environment production` | `--to production` |
| Когда использовать | всё новое | только если брокер старый / так исторически сложилось |

Два разных факта, которые легко перепутать:

* `record-deployment` — «эта версия **работает** в окружении» (обычный деплой сервиса);
* `record-release` — «эта версия **выпущена и поддерживается**» (мобильное приложение в сторе,
  библиотека у клиентов: она не «работает у нас», она «живёт у пользователей»).

В нашем демо окружение `production` создано с `production: true`, и записанного деплоя хватило.
В некоторых версиях брокера для production-окружений провайдер должен быть именно *released*.
Если гейт ведёт себя «странно» в проде — проверьте флаг окружения и `record-release`, это первое,
что стоит посмотреть.

---

## 7. Куда именно ставить гейт

### 7.1. Consumer (order-service): перед деплоем

```
PR:    mvn test  →  publish pact (version=sha, branch=feature/x)
       →  can-i-deploy --to-environment staging --branch feature/x   (гейт)
merge: publish pact (version=sha, branch=main)
deploy staging:  ... → record-deployment order-service $SHA staging
deploy prod:     can-i-deploy --to-environment production   (гейт)
                 ... → record-deployment order-service $SHA production
```

### 7.2. Provider (payment-service): перед деплоем и на PR

```
PR:    provider verification (publishResults=true)
       → can-i-deploy -a payment-service -e $SHA --to-environment staging
deploy: ... → record-deployment payment-service $SHA staging
```

### 7.3. Главный подводный камень порядка

Consumer не может получить «да», пока провайдер не проверил **новое содержимое** контракта.
Если consumer и provider — разные команды, то на PR consumer-а гейт будет красным по определению.
Поэтому в жизни делают так:

* на PR consumer-а: `--dry-run` или гейт по ветке (`--branch`), и уведомление провайдеру;
* провайдер настроен верифицировать контракты из feature-веток (webhook «контракт обновился →
  запусти provider CI»);
* жёсткий гейт — на merge/deploy, когда верификация уже есть.

Иначе получится «гейт, который всегда красный», его отключат через неделю — и это будет
правильным решением в той ситуации. Не доводите до этого.

---

## 8. Реальные проблемы, которые мы поймали на этом проекте

### 8.1. «Нельзя перепубликовать тот же номер версии с другим содержимым»

Попытка опубликовать изменённый контракт под уже занятым `1.0.0`:

```json
{"error":"Cannot change the content of the pact for order-service version 1.0.0 and provider payment-service,
as race conditions will cause unreliable results for can-i-deploy. Each pact must be published with a unique
consumer version number. ..."}
```

Это защита от гонок, а не баг. Версия контракта = версия артефакта consumer-а (обычно git sha).
Поменяли контракт — новый номер. В демо мы поэтому публиковали `1.1.0`, `1.2.0`, `1.3.0`.

### 8.2. «Последняя версия» — это по времени публикации, а не по semver

Мы один раз получили верификацию `0.9.0` вместо `1.0.0`, потому что `0.9.0` опубликовали позже.
`can-i-deploy --latest` и provider verification «latest pact» выбирают по времени публикации.
Следствие: не публикуйте старые версии после новых; в CI версия = git sha, и это не проблема.

### 8.3. CLI внутри Docker игнорировал `--broker-base-url`

`can-i-deploy` флаг `-b` уважает. А `record-deployment` в образе `pactfoundation/pact-cli` (клиент 1.78)
упорно шёл на `http://localhost:9292`, хотя мы передавали и `-b`, и `PACT_BROKER_BASE_URL`:

```
ERROR: Error making request - Errno::ECONNREFUSED Failed to open TCP connection to localhost:9292
```

Поэтому в репозитории запись деплоя сделана HTTP-запросом (`scripts/record-deployment.ps1`).
Урок на будущее: **читайте URL в тексте ошибки**. Он показывает, куда инструмент реально пошёл,
и это сразу отсекает половину гипотез.

### 8.4. Окружение надо создавать с флагом `production`

`POST /environments` без поля `production` отвечает `400 Bad Request`:

```json
{"errors":{"production":["is missing"]}}
```

Правильно: `{"name":"staging","displayName":"Staging","production":false}`.

---

## 9. Что делать, когда гейт красный

| Сообщение | Что произошло | Действия |
|---|---|---|
| `no version is currently recorded as deployed/released in this environment` | не пишутся деплои | добавить `record-deployment` в deploy-джобу; записать текущую версию руками |
| `There is no verified pact between version X ... and the version of Y currently in production (Z)` | контракт изменился и не верифицирован | прогнать provider verification для нового контракта; проверить, что publishResults=true |
| `No pacts or verifications have been published for version X` | версии нет в брокере | проверить версию/пактиципанта; опубликовать контракт |
| `The verification for the pact between ... failed` (с деталями) | провайдер реально сломал контракт | чинить провайдера или осознанно менять контракт |
| Всё зелёное, но «P.VERSION = ???» | провайдер не записан в этом окружении | `record-deployment` провайдера |
| Гейт мигает (то да, то нет) | гонка consumer/provider в CI | `--retry-while-unknown`, `--branch`, webhook |
| `401/403` от брокера | нет токена/прав | `-k $PACT_BROKER_TOKEN`, проверить права на окружение |

---

## 10. Чего `can-i-deploy` НЕ гарантирует

* Что бизнес-логика верна. Проверяется только протокол между двумя сервисами.
* Что фича работает целиком (UI, очередь, несколько вызовов, конфиг) — это e2e.
* Что данные совместимы (миграции БД, формат сообщений в Kafka, если нет message pacts).
* Что прод не упадёт по нагрузке/инфраструктуре.
* Что верификация была «честной»: если провайдер тестирует не тот артефакт, что поедет в прод,
  брокер об этом не узнает. Версия провайдера в верификации должна совпадать с версией деплоя.

Поэтому `can-i-deploy` — **не замена** регрессу и e2e. Это дешёвый и быстрый фильтр
«мы точно не разошлись в контракте», который снимает с e2e часть работы.

---

## 11. Чеклист внедрения для AQA

1. Договориться о **версии = git sha** и для consumer-а, и для провайдера.
2. В consumer CI: `mvn test` (контракты) → `pact-broker publish --branch`.
3. В provider CI: verification с `pact.verifier.publishResults=true` и `pact.provider.version=$SHA`.
4. В deploy-джобах: `record-deployment` **после** успешного деплоя (и для consumer, и для provider).
5. Включить `can-i-deploy` сначала с `--dry-run`, смотреть неделю на ложные красные.
6. Починить причины ложных красных (обычно — нет записи деплоя или гонка).
7. Убрать `--dry-run` на staging, потом на production.
8. Только после этого — сокращать e2e, которые дублируют контракты.

---

## 12. Файлы и команды этого репозитория

```bash
# брокер
docker compose up -d pact-broker
# контракт + верификация + деплой
./scripts/publish-pact.ps1 -Version 1.0.0
mvn test -Dtest=PaymentProviderBrokerVerificationTest `
  -Dpact.broker.url=http://localhost:9292 -Dpact.verifier.publishResults=true
./scripts/record-deployment.ps1 -Pacticipant payment-service -Version 1.0.0 -Environment production
# гейт
./scripts/can-i-deploy.ps1 -Pacticipant order-service -Version 1.0.0 -Environment production
```

| Файл | Что делает |
|---|---|
| `scripts/publish-pact.ps1` | `PUT` контракта в брокер (аналог `pact-broker publish`) |
| `scripts/record-deployment.ps1` | `POST` деплоя в окружение (аналог `record-deployment`) |
| `scripts/can-i-deploy.ps1` | гейт через `pact-cli`, возвращает код `0/1` |
| `.github/workflows/ci.yml` | тот же флоу в CI (GitHub Actions) |
| `.gitlab-ci.yml` | тот же флоу в CI (GitLab) |

Дальше: **[08-where-aqa-lives.md](08-where-aqa-lives.md)** — как AQA вообще попадает в репозитории
и сервисы разработчиков, если раньше работа была только black-box из отдельного автотестового проекта.
