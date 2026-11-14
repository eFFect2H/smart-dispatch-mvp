# Smart Dispatch — frontend (рабочее место диспетчера)

React + TypeScript + Vite, карта — Leaflet. Работает с REST API по `api_spec.yaml`
(кейс «Умная диспетчеризация выездных бригад», `/api/v1`).

## Запуск

```bash
npm ci
npm run dev        # http://localhost:5173, запросы /api проксируются на http://localhost:8080
npm run build      # tsc -b && vite build
```

Другой адрес backend: `VITE_API_URL=http://host:8080 npm run dev`.

## Сценарий работы (соответствует шагам оценки из спецификации)

1. **Загрузка исходных данных** — `service_centers.json`, `staff.json`, `access_nodes.json`,
   `work_types.csv`, `travel_matrix.json` → `PUT /reference` и `PUT /reference/travel-matrix`.
2. **Наряды дня** — `orders_train.json`; даты в файле определяются автоматически, в день попадают
   наряды со слотом на выбранную дату и `registered_at ≤ now` (по умолчанию 08:30) → `PUT /days/{date}`.
3. **Построение плана** — `POST /days/{date}/plan` (таймаут клиента 330 с).
4. **События** — форма, быстрые действия в карточке наряда и кнопка «Недоступен» в диаграмме смен:
   `POST /days/{date}/events`, затем `GET /days/{date}/plan`.
   Поддержаны все типы: `NEW_ORDER`, `ORDER_CANCELLED`, `VISIT_DELAYED`, `ORDER_TRANSFERRED`,
   `STAFF_UNAVAILABLE`, `TIME_TICK`. Модельное время задаётся в панели над картой («Применить» = `TIME_TICK`).
5. **Сценарий событий** — `events_example.json`: события по возрастанию `timestamp`, триггеры
   (`VISIT_DELAYED`/`ORDER_TRANSFERRED`) разворачиваются по текущему плану, как делает оркестратор.

Правила, которые интерфейс проверяет до отправки: задержка и передача — только для начатого визита
(`start ≤ timestamp`), отмена — только для ещё не начатого, время события не раньше модельного времени плана.
Время ответа на событие измеряется и подсвечивается, если оно превысило лимит 10 с.

## Совместимость с backend

Тело события содержит поля спецификации (`type`, `timestamp`, …) **и** поля прежнего формата backend
(`event_type`, `occurred_at`, `payload`) — спецификация требует игнорировать неизвестные поля.
Если после события `GET /plan` не отражает его (`based_on_event_id` не совпал), клиент один раз вызывает
`POST /plan` и игнорирует `409`. Когда backend будет полностью соответствовать спецификации, оба механизма
перестанут срабатывать сами (`src/utils/events.ts`, `src/api/v1Dispatch.ts`).

Страницы «Обзор / Заявки / Бригады» относятся к исходному MVP (`/api/teams`, `/api/requests`) и в API кейса не входят.
