# Stage D1 — API simulator and baseline metrics

A standard-library-only Python runner that replays the organizer's API v1 sequence against a running backend. Organizer datasets remain outside Git.

## Inputs

- `service_centers.json`, `staff.json`, `access_nodes.json`: JSON arrays (or wrappers with the matching property).
- `work_types.csv`: RFC 4180 CSV with `WorkTypeDto` fields; `profiles` are separated by `|`.
- `travel_matrix.json`: `TravelMatrixRequest` object.
- `orders_train.json`: `OrderDto` array or an object with an `orders` array.
- Optional events: array of expanded `EventRequest` objects, or `{ "events": [...] }`.

For each `--day DATE=NOW`, orders are selected when local `slot.start` date equals `DATE` and `registered_at <= NOW`. `NOW` must include a UTC offset, for example `+05:00`. Multiple `--day` arguments are processed in date order. Reference and travel matrix are uploaded once; each day is uploaded and planned separately.

The repository references `events_example.json` as an `EventScenarioFile` but does not contain that file or its trigger schema. This first version accepts already-expanded event requests (`event_id`, `event_type`, `occurred_at`, optional IDs and payload). Trigger scenarios are rejected with a clear message, not guessed.

## Run from repository root (PowerShell)

```powershell
python tools/simulator/run_scenario.py `
  --base-url http://localhost:8080 `
  --service-centers .\organizer-data\service_centers.json `
  --staff .\organizer-data\staff.json `
  --access-nodes .\organizer-data\access_nodes.json `
  --work-types .\organizer-data\work_types.csv `
  --travel-matrix .\organizer-data\travel_matrix.json `
  --orders .\organizer-data\orders_train.json `
  --day "2026-10-26=2026-10-26T08:00:00+05:00" `
  --events .\organizer-data\events-expanded.json `
  --report .\artifacts\stage-d-report.json `
  --plans-dir .\artifacts\plans
```

The simulator follows the documented sequence: initial `POST /plan`; for each timestamp-sorted event, `POST /events` followed by `GET /plan`; then final `GET /plan`. It saves final plan JSON separately from the metrics report. `--strict` returns exit code 2 when H9, timing, or event-replan conformance warnings are found.

## Metrics

The report includes slot adherence (`slot.start <= VISIT.start <= latest_start`), deadline adherence (`VISIT.end <= deadline`), repeat-weighted variants (`is_repeat=true` has weight 2), visits/unassigned/PGP/cancelled counts, H9 exactly-once accounting, route travel seconds/metres, workload CV from route work minutes, the API's `utilization_cv`, LATE_RISK timing, and API latencies including event POST and POST+GET cycles.

These are diagnostic metrics, not a replacement for the organizer validator. PGP precision/recall needs labeled incident ground truth; working-day SLA verification needs the relevant holiday calendar. Neither is present in this repository.

## Unit tests

```powershell
python -m unittest discover -s tools/simulator -v
```

The tests require neither the backend nor organizer data.
