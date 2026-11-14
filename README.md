# Smart Dispatch

Веб-приложение для планирования выездных бригад и контроля выполнения заявок. Демонстрационный набор данных — Екатеринбург.

## Реализованные возможности

- PostgreSQL, миграции Flyway и демонстрационные справочники: сервисные центры, сотрудники, объекты, виды работ и заявки.
- Распределение заявок между сотрудниками с учётом временных окон, смен, квалификаций, оборудования, перерывов и времени в пути.
- Контроль нераспределённых заявок, групповых заявок (ПГП), рисков опоздания и загрузки сотрудников.
- Оперативные события: задержка визита, отмена, передача заявки, недоступность сотрудника и добавление заявки. После события пересчитывается незавершённая часть плана.
- Карта Екатеринбурга с дорожной геометрией маршрутов, остановками и временной шкалой смен.
- Сравнение версий плана в текущем сеансе интерфейса.
- Основной API диспетчеризации `/api/v1` и legacy API `/api`.

Планировщик V1 использует детерминированную эвристику последовательного добавления заявок и проверки ограничений. Это MVP, а не гарантированно глобально оптимальный VRPTW-решатель. Время и расстояние рассчитываются по матрице дорожных перемещений OSRM; геометрия маршрутов запрашивается отдельно для карты. Адреса и координаты демо-набора — тестовые точки Екатеринбурга. Публичный OSRM не предоставляет SLA.

## Требования

**Docker:** Docker Desktop + Compose v2 на Windows либо Docker Engine + Compose plugin на Linux. Java, Maven, Node.js и PostgreSQL на хосте не нужны. Рекомендуется 4 ГБ RAM и 3 ГБ свободного места.

**Локальный запуск:** JDK 21, Maven 3.9+, Node.js 22.12+ и npm, PostgreSQL 16, доступ к интернету для OSRM и загрузки зависимостей.

## Запуск через Docker (Windows и Linux)

Команды одинаковы в PowerShell, Windows Terminal и Linux shell.

1. Клонируйте проект:

   ```bash
   git clone https://github.com/eFFect2H/smart-dispatch-mvp.git
   cd smart-dispatch-mvp
   ```

2. Создайте локальный файл конфигурации:

   ```bash
   cp .env.example .env
   ```

   В PowerShell: `Copy-Item .env.example .env`. Для постоянного окружения замените демонстрационный пароль. Файл `.env` не коммитьте.

3. Соберите образы и запустите сервисы:

   ```bash
   docker compose up --build -d
   ```

   Compose запускает PostgreSQL, Spring Boot backend и frontend на Nginx. Backend ждёт готовности БД; Flyway применяет миграции автоматически.

4. Откройте приложение:

   - Интерфейс: http://localhost:5173
   - Backend API: http://localhost:8080
   - Проверка backend: http://localhost:8080/api/v1/health
   - PostgreSQL с хоста: `localhost:5433`

5. Состояние и логи:

   ```bash
   docker compose ps
   docker compose logs -f backend
   docker compose logs -f frontend
   docker compose logs -f db
   ```

6. Остановите контейнеры:

   ```bash
   docker compose down
   ```

   Данные БД сохраняются в volume `smart_dispatch_pgdata`. Полный сброс демо-БД:

   ```bash
   docker compose down -v
   docker compose up --build -d
   ```

   **Важно:** миграция V6 заменяет прежние снимки V1 демонстрационным набором Екатеринбурга. Не запускайте её на БД с реальными рабочими данными. Для демо используйте новый Docker volume.

Полезные команды:

```bash
docker compose up --build -d backend
docker compose up --build -d frontend
docker compose exec db psql -U dispatch -d smart_dispatch
```

## Локальный запуск без Docker

Установите PostgreSQL 16, создайте базу `smart_dispatch` и пользователя `dispatch`. Для локального подключения используется порт `5432`.

### Backend

PowerShell:

```powershell
$env:DB_URL = "jdbc:postgresql://localhost:5432/smart_dispatch"
$env:DB_USERNAME = "dispatch"
$env:DB_PASSWORD = "your_password"
$env:OSRM_BASE_URL = "https://router.project-osrm.org"
mvn clean spring-boot:run
```

Linux / macOS:

```bash
export DB_URL="jdbc:postgresql://localhost:5432/smart_dispatch"
export DB_USERNAME="dispatch"
export DB_PASSWORD="your_password"
export OSRM_BASE_URL="https://router.project-osrm.org"
mvn clean spring-boot:run
```

Backend доступен на порту `8080`. При первом запуске Flyway создаёт схему и загружает демо-данные.

### Frontend

Во втором терминале:

```bash
cd frontend
npm ci
npm run dev
```

Интерфейс откроется на http://localhost:5173. Vite проксирует запросы `/api` на `http://localhost:8080`.

Production-сборка:

```bash
npm run build
npm run preview
```

Проверка backend:

```bash
curl http://localhost:8080/api/v1/health
curl http://localhost:8080/api/v1/demo
```

В PowerShell используйте `Invoke-RestMethod`.

## API

Все endpoint'ы принимают и возвращают JSON. Дата в URL — `YYYY-MM-DD`, время события — ISO-8601 с часовым смещением. Основной интерфейс использует V1 API.

### Системные и демонстрационные данные

| Метод | Endpoint | Назначение |
|---|---|---|
| GET | `/api/v1/health` | Проверка доступности backend |
| GET | `/api/v1/demo` | Город, часовой пояс и статистика демо-набора |
| POST | `/api/v1/demo/days/{date}/initialize` | Идемпотентная инициализация дня из БД |
| GET | `/api/v1/reference` | Получение справочников |
| PUT | `/api/v1/reference` | Замена справочников |
| PUT | `/api/v1/reference/travel-matrix` | Загрузка матрицы времени и расстояний |

### Планирование и маршруты

| Метод | Endpoint | Назначение |
|---|---|---|
| PUT | `/api/v1/days/{date}` | Загрузка или замена входных данных дня |
| POST | `/api/v1/days/{date}/plan` | Построение и сохранение новой версии плана |
| GET | `/api/v1/days/{date}/plan` | Получение последнего сохранённого плана |
| GET | `/api/v1/days/{date}/plan/geometry` | Геометрия маршрутов для карты |

В демонстрационном режиме ручная загрузка справочников, матрицы и заявок не нужна: данные уже находятся в PostgreSQL. День создаётся через endpoint инициализации. Endpoint'ы `PUT` предназначены для интеграции с внешними источниками. Форматы тел запросов и ответов V1 определены DTO backend.

### События диспетчеризации

| Метод | Endpoint | Назначение |
|---|---|---|
| POST | `/api/v1/days/{date}/events` | Добавление события в журнал дня |
| GET | `/api/v1/days/{date}/events` | Получение журнала событий |

Типы событий: `NEW_ORDER`, `ORDER_CANCELLED`, `VISIT_DELAYED`, `STAFF_UNAVAILABLE`, `ORDER_TRANSFERRED`. Запрос `POST /events` только добавляет событие в журнал и не пересчитывает план. После него необходимо вызвать `POST /plan`: сервис повторно обрабатывает журнал и сохраняет новую версию. Затем `GET /plan` возвращает сохранённый результат.

Пример запроса задержки:

```http
POST /api/v1/days/2026-11-14/events
Content-Type: application/json

{
  "event_id": "evt-delay-001",
  "event_type": "VISIT_DELAYED",
  "occurred_at": "2026-11-14T10:30:00+05:00",
  "order_id": "ORD-20261114-0001",
  "payload": {
    "delay_min": 20
  }
}
```

### Legacy API

Эти endpoint'ы оставлены для базовых страниц и совместимости. Основная диспетчеризация работает через `/api/v1`.

| Метод | Endpoint | Назначение |
|---|---|---|
| GET | `/api/teams` | Список бригад |
| POST | `/api/teams` | Создание бригады |
| GET | `/api/requests` | Список заявок |
| POST | `/api/requests` | Создание заявки |
| POST | `/api/dispatch/optimize` | Legacy-оптимизация |

### Пример построения плана

```bash
curl http://localhost:8080/api/v1/reference
curl -X POST http://localhost:8080/api/v1/demo/days/2026-11-14/initialize
curl -X POST http://localhost:8080/api/v1/days/2026-11-14/plan
curl http://localhost:8080/api/v1/days/2026-11-14/plan
curl http://localhost:8080/api/v1/days/2026-11-14/plan/geometry
```

## Конфигурация

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/smart_dispatch` | JDBC URL PostgreSQL |
| `DB_USERNAME` | `postgres` | Пользователь БД |
| `DB_PASSWORD` | пусто | Пароль БД |
| `OSRM_BASE_URL` | `https://router.project-osrm.org` | Адрес OSRM |
| `OSRM_PROFILE` | `driving` | Профиль маршрутизации |
| `SERVER_PORT` | `8080` | Порт backend |

В Docker Compose JDBC URL автоматически указывает на сервис `db`. Не задавайте там `localhost`: внутри backend-контейнера это адрес самого контейнера.

## Структура проекта

- `src/main/java` — Spring Boot backend: контроллеры, DTO, сервисы, JPA и маршрутизация.
- `src/main/resources/db/migration` — миграции Flyway и демо-данные.
- `frontend/src` — React + TypeScript интерфейс.
- `Dockerfile`, `frontend/Dockerfile`, `docker-compose.yml` — сборка и запуск контейнеров.

## Ограничения демо-версии

- Публичный OSRM не гарантирует доступность; при сбое дорожная линия может не отобразиться.
- Адреса и координаты демонстрационные.
- Сравнение планов доступно до перезагрузки страницы.
- Авторизация и разграничение прав не реализованы.
- Для промышленной эксплуатации нужны собственный маршрутизатор, защищённые секреты, резервное копирование и отдельная стратегия миграций.
