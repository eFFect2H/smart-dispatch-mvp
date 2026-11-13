#!/usr/bin/env python3
"""Run organizer-style API v1 scenarios and collect baseline metrics (stdlib only)."""
import argparse
import csv
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from datetime import date, datetime, timedelta
from pathlib import Path


class SimulationError(RuntimeError):
    pass


def read_json(path):
    try:
        with Path(path).open(encoding="utf-8-sig") as stream:
            return json.load(stream)
    except FileNotFoundError as exc:
        raise SimulationError(f"File not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise SimulationError(f"Invalid JSON in {path}: {exc}") from exc


def array_file(path, key):
    value = read_json(path)
    if isinstance(value, dict):
        value = value.get(key)
    if not isinstance(value, list) or any(not isinstance(x, dict) for x in value):
        raise SimulationError(f"{path}: expected an array of objects (or wrapper '{key}')")
    return value


def load_work_types(path):
    required = {"code", "name", "profiles", "duration_min", "max_start_delay_min",
                "deadline_kind", "deadline_value", "repeat_deadline_value", "pgp_relevant"}
    result = []
    try:
        with Path(path).open(encoding="utf-8-sig", newline="") as stream:
            reader = csv.DictReader(stream)
            missing = sorted(required - set(reader.fieldnames or []))
            if missing:
                raise SimulationError(f"work_types.csv missing columns: {', '.join(missing)}")
            for line, raw in enumerate(reader, 2):
                row = {k: (raw.get(k) or "").strip() for k in required}
                row["profiles"] = [x.strip() for x in row["profiles"].split("|") if x.strip()]
                if not row["profiles"]:
                    raise SimulationError(f"work_types.csv line {line}: profiles is empty")
                for key in ("duration_min", "deadline_value", "repeat_deadline_value"):
                    try:
                        row[key] = int(row[key])
                    except ValueError as exc:
                        raise SimulationError(f"work_types.csv line {line}: {key} must be an integer") from exc
                row["max_start_delay_min"] = int(row["max_start_delay_min"]) if row["max_start_delay_min"] else None
                if row["pgp_relevant"].lower() not in ("true", "false"):
                    raise SimulationError(f"work_types.csv line {line}: pgp_relevant must be true/false")
                row["pgp_relevant"] = row["pgp_relevant"].lower() == "true"
                result.append(row)
    except FileNotFoundError as exc:
        raise SimulationError(f"File not found: {path}") from exc
    if not result:
        raise SimulationError("work_types.csv contains no rows")
    return result


def instant(value, label):
    if not isinstance(value, str):
        raise SimulationError(f"{label} must be an ISO-8601 string with UTC offset")
    text = value[:-1] + "+00:00" if value.endswith("Z") else value
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError as exc:
        raise SimulationError(f"{label} is not a valid ISO-8601 timestamp: {value}") from exc
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise SimulationError(f"{label} must include an offset, e.g. +05:00")
    return parsed


def day_spec(text):
    if "=" not in text:
        raise SimulationError('--day must be DATE=NOW, e.g. 2026-10-26=2026-10-26T08:00:00+05:00')
    raw_date, now_text = text.split("=", 1)
    try:
        day = date.fromisoformat(raw_date)
    except ValueError as exc:
        raise SimulationError(f"Invalid day: {raw_date}") from exc
    now = instant(now_text, "NOW")
    if now.date() != day:
        raise SimulationError(f"Date {day} does not match NOW local date {now.date()}")
    return day, now_text, now


def build_day(orders, day, now_text, now, absent=None):
    selected, other_day, future = [], 0, 0
    for i, order in enumerate(orders):
        order_id = order.get("order_id", f"index {i}")
        if not isinstance(order.get("slot"), dict) or "start" not in order["slot"]:
            raise SimulationError(f"Order {order_id}: slot.start is missing")
        if instant(order["slot"]["start"], f"{order_id}.slot.start").date() != day:
            other_day += 1
            continue
        if "registered_at" not in order:
            raise SimulationError(f"Order {order_id}: registered_at is missing")
        if instant(order["registered_at"], f"{order_id}.registered_at") > now:
            future += 1
            continue
        selected.append(order)
    return {
        "date": day.isoformat(), "now": now_text, "orders": selected,
        "absent_staff_ids": sorted(set(absent or [])),
    }, {"orders_in_file": len(orders), "orders_selected": len(selected),
        "orders_other_day": other_day, "orders_registered_after_now": future}


def load_events(path, day):
    if not path:
        return []
    raw = read_json(path)
    if isinstance(raw, dict):
        raw = raw.get("events")
    if not isinstance(raw, list):
        raise SimulationError("Events file must be an array or an object containing an 'events' array")
    result = []
    for i, item in enumerate(raw):
        if not isinstance(item, dict):
            raise SimulationError(f"Event #{i+1} must be a JSON object")
        if not all(k in item for k in ("event_id", "event_type", "occurred_at")):
            raise SimulationError(
                f"Event #{i+1} is not an expanded EventRequest. Trigger-based EventScenarioFile "
                "needs an explicit adapter; no sample/trigger schema is in this repository."
            )
        occurred = instant(item["occurred_at"], f"event #{i+1}.occurred_at")
        if occurred.date() != day:
            continue
        event = {k: item[k] for k in ("event_id", "event_type", "occurred_at")}
        for key in ("order_id", "staff_id", "target_staff_id", "payload"):
            if item.get(key) is not None:
                event[key] = item[key]
        if "payload" in event and not isinstance(event["payload"], dict):
            raise SimulationError(f"Event {event['event_id']}: payload must be an object")
        result.append((occurred, i, event))
    result.sort(key=lambda x: (x[0], x[1]))
    return [x[2] for x in result]


class Api:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.timings = []

    def call(self, method, path, body=None, timeout=30):
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json"}
        if data is not None:
            headers["Content-Type"] = "application/json; charset=utf-8"
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=timeout) as response:
                raw = response.read()
                elapsed = (time.perf_counter() - started) * 1000
                result = json.loads(raw.decode("utf-8")) if raw else None
                self.timings.append({"method": method, "path": path, "status": response.status,
                                     "elapsed_ms": round(elapsed, 2)})
                return result, elapsed
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode("utf-8", errors="replace")[:2000]
            self.timings.append({"method": method, "path": path, "status": exc.code,
                                 "elapsed_ms": round((time.perf_counter()-started)*1000, 2)})
            raise SimulationError(f"{method} {path}: HTTP {exc.code}: {raw}") from exc
        except (urllib.error.URLError, TimeoutError) as exc:
            raise SimulationError(f"{method} {path} failed: {exc}") from exc

    def wait_health(self, timeout):
        end = time.monotonic() + timeout
        last = None
        while time.monotonic() < end:
            try:
                result, _ = self.call("GET", "/api/v1/health", timeout=5)
                if isinstance(result, dict) and result.get("status") == "ok":
                    return result
                last = f"unexpected response: {result}"
            except SimulationError as exc:
                last = str(exc)
            time.sleep(2)
        raise SimulationError(f"API health not ready within {timeout}s: {last}")


def pct(n, d):
    return round(100 * n / d, 2) if d else None


def parse_version(plan):
    value = plan.get("plan_version") if isinstance(plan, dict) else None
    return value if isinstance(value, int) else None


def metrics(plan, day_payload, events, plan_ms, event_results, day_http):
    orders = {o["order_id"]: o for o in day_payload["orders"] if o.get("order_id")}
    for event in events:
        if event["event_type"] == "NEW_ORDER":
            new_order = (event.get("payload") or {}).get("order")
            if isinstance(new_order, dict) and new_order.get("order_id"):
                orders[new_order["order_id"]] = new_order
    visits, placements, travel, distance, work = [], [], 0, 0, []
    for route in plan.get("routes") or []:
        totals = route.get("totals") or {}
        travel += int(totals.get("travel_sec") or 0)
        distance += int(totals.get("distance_m") or 0)
        work.append(float(totals.get("work_min") or 0))
        for stop in route.get("stops") or []:
            if stop.get("type") == "VISIT" and stop.get("order_id"):
                visits.append((stop["order_id"], stop))
                placements.append(stop["order_id"])
    unassigned, cancelled, groups = plan.get("unassigned") or [], plan.get("cancelled") or [], plan.get("pgp_groups") or []
    placements += [x["order_id"] for x in unassigned if x.get("order_id")]
    placements += [x["order_id"] for x in cancelled if x.get("order_id")]
    pgp_ids = [oid for group in groups for oid in (group.get("order_ids") or [])]
    placements += pgp_ids
    counts = Counter(placements)
    expected, seen = set(orders), set(counts)
    missing, unknown = sorted(expected-seen), sorted(seen-expected)
    duplicates = sorted(k for k, v in counts.items() if v != 1)
    slot_ok = deadline_ok = both_ok = weighted_slot = weighted_deadline = weighted_both = weight_sum = repeats = 0
    for order_id, stop in visits:
        order = orders.get(order_id)
        if not order:
            continue
        try:
            in_slot = instant(order["slot"]["start"], "slot.start") <= instant(stop["start"], "visit.start") <= instant(order["latest_start"], "latest_start")
            on_time = instant(stop["end"], "visit.end") <= instant(order["deadline"], "deadline")
        except (KeyError, SimulationError):
            continue
        weight = 2 if order.get("is_repeat") is True else 1
        repeats += int(weight == 2)
        weight_sum += weight
        slot_ok += int(in_slot); deadline_ok += int(on_time); both_ok += int(in_slot and on_time)
        weighted_slot += weight * int(in_slot); weighted_deadline += weight * int(on_time)
        weighted_both += weight * int(in_slot and on_time)
    mean_work = statistics.fmean(work) if work else 0
    workload_cv = round(statistics.pstdev(work) / mean_work, 4) if mean_work and len(work) > 1 else None
    alerts = [a for a in (plan.get("alerts") or []) if a.get("type") == "LATE_RISK"]
    alerts_by_order = {}
    for alert in alerts:
        if alert.get("order_id"):
            alerts_by_order.setdefault(alert["order_id"], []).append(alert)
    timely = 0
    for order_id, alert_items in alerts_by_order.items():
        if order_id not in orders:
            continue
        try:
            threshold = instant(orders[order_id]["slot"]["start"], "slot.start") - timedelta(minutes=30)
            timely += int(any(instant(a["created_at"], "alert.created_at") <= threshold
                              for a in alert_items if a.get("created_at")))
        except (KeyError, SimulationError):
            pass
    post_ms = [x["post_ms"] for x in event_results]
    cycle_ms = [x["cycle_ms"] for x in event_results]
    def p95(values):
        return round(sorted(values)[max(0, math.ceil(.95*len(values))-1)], 2) if values else None
    summary = plan.get("summary") or {}
    return {
        "plan_version": plan.get("plan_version"), "orders_total": len(expected), "visits": len(visits),
        "slot_adherence_pct": pct(slot_ok, len(visits)), "deadline_adherence_pct": pct(deadline_ok, len(visits)),
        "slot_and_deadline_adherence_pct": pct(both_ok, len(visits)), "repeat_visits": repeats,
        "repeat_weighted_slot_adherence_pct": pct(weighted_slot, weight_sum),
        "repeat_weighted_deadline_adherence_pct": pct(weighted_deadline, weight_sum),
        "repeat_weighted_combined_adherence_pct": pct(weighted_both, weight_sum),
        "unassigned_orders": len(unassigned), "pgp_groups": len(groups), "pgp_orders": len(pgp_ids),
        "cancelled_orders": len(cancelled), "h9_exactly_once": not missing and not unknown and not duplicates,
        "h9_missing": missing, "h9_unknown": unknown, "h9_duplicates": duplicates,
        "travel_sec": travel, "distance_m": distance, "route_work_min": work,
        "workload_cv_from_work_min": workload_cv, "server_utilization_cv": summary.get("utilization_cv"),
        "late_risk_alerts": len(alerts), "orders_with_late_risk_alert": len(alerts_by_order),
        "timely_late_risk_alerts": timely,
        "timely_late_risk_pct_of_alerted_orders": pct(timely, len(alerts_by_order)),
        "initial_plan_ms": round(plan_ms, 2), "event_count": len(event_results),
        "event_post_max_ms": round(max(post_ms), 2) if post_ms else None,
        "event_post_p95_ms": p95(post_ms), "event_post_get_cycle_max_ms": round(max(cycle_ms), 2) if cycle_ms else None,
        "http_calls_for_day": len(day_http),
    }


def run(args):
    reference = {"service_centers": array_file(args.service_centers, "service_centers"),
                 "staff": array_file(args.staff, "staff"), "access_nodes": array_file(args.access_nodes, "access_nodes"),
                 "work_types": load_work_types(args.work_types)}
    matrix = read_json(args.travel_matrix)
    if isinstance(matrix, dict) and "travel_matrix" in matrix:
        matrix = matrix["travel_matrix"]
    if not isinstance(matrix, dict):
        raise SimulationError("travel_matrix.json must be an object")
    raw_orders = read_json(args.orders)
    raw_orders = raw_orders.get("orders") if isinstance(raw_orders, dict) else raw_orders
    if not isinstance(raw_orders, list):
        raise SimulationError("orders file must be an array or contain an 'orders' array")
    api = Api(args.base_url)
    health = api.wait_health(args.health_timeout_sec)
    _, reference_ms = api.call("PUT", "/api/v1/reference", reference, 60)
    _, matrix_ms = api.call("PUT", "/api/v1/reference/travel-matrix", matrix, 60)
    day_results, warnings = [], []
    for day, now_text, now in sorted((day_spec(x) for x in args.day), key=lambda x: x[0]):
        payload, input_counts = build_day(raw_orders, day, now_text, now, args.absent_staff)
        events = load_events(args.events, day)
        prefix = f"/api/v1/days/{day.isoformat()}"
        day_start = len(api.timings)
        _, load_ms = api.call("PUT", prefix, payload, 30)
        plan_request = {"time_limit_sec": args.plan_time_limit_sec}
        initial, initial_ms = api.call("POST", prefix + "/plan", plan_request, 305)
        current, event_results = initial, []
        for event in events:
            accepted, post_ms = api.call("POST", prefix + "/events", event, 30)
            before = parse_version(current)
            fetched, get_ms = api.call("GET", prefix + "/plan", None, 30)
            after = parse_version(fetched)
            event_results.append({"event_id": event["event_id"], "event_type": event["event_type"],
                                 "post_ms": round(post_ms, 2), "get_ms": round(get_ms, 2),
                                 "cycle_ms": round(post_ms+get_ms, 2),
                                 "event_status": accepted.get("status") if isinstance(accepted, dict) else None,
                                 "plan_version_before": before, "plan_version_after_get": after,
                                 "replan_visible_after_event_get": before is not None and after is not None and after > before})
            current = fetched
        final_plan, final_get_ms = api.call("GET", prefix + "/plan", None, 30)
        day_http = api.timings[day_start:]
        day_metrics = metrics(final_plan, payload, events, initial_ms, event_results, day_http)
        day_metrics.update({"day_load_ms": round(load_ms, 2), "final_plan_get_ms": round(final_get_ms, 2),
                            "input_counts": input_counts, "event_checks": event_results})
        if not day_metrics["h9_exactly_once"]:
            warnings.append(f"{day}: H9 accounting mismatch; see h9_missing/h9_unknown/h9_duplicates")
        for check in event_results:
            if not check["replan_visible_after_event_get"]:
                warnings.append(f"{day}: plan_version did not increase after {check['event_id']} followed by GET /plan")
            if check["post_ms"] > 10000:
                warnings.append(f"{day}: event {check['event_id']} POST took longer than 10 seconds")
        if initial_ms > 300000:
            warnings.append(f"{day}: planning exceeded 300 seconds")
        plan_dir = Path(args.plans_dir); plan_dir.mkdir(parents=True, exist_ok=True)
        plan_file = plan_dir / f"{day.isoformat()}-final-plan.json"
        plan_file.write_text(json.dumps(final_plan, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        day_results.append({"date": day.isoformat(), "metrics": day_metrics, "final_plan_file": str(plan_file)})
    return {"simulator_version": "0.1.0", "api_base_url": args.base_url.rstrip("/"), "health": health,
            "reference_upload_ms": round(reference_ms, 2), "travel_matrix_upload_ms": round(matrix_ms, 2),
            "days": day_results, "warnings": warnings, "http_timings": api.timings}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--service-centers", required=True); parser.add_argument("--staff", required=True)
    parser.add_argument("--access-nodes", required=True); parser.add_argument("--work-types", required=True)
    parser.add_argument("--travel-matrix", required=True); parser.add_argument("--orders", required=True)
    parser.add_argument("--day", action="append", required=True, metavar="DATE=NOW")
    parser.add_argument("--events"); parser.add_argument("--absent-staff", action="append", default=[])
    parser.add_argument("--plan-time-limit-sec", type=int, default=300, choices=range(1, 301))
    parser.add_argument("--health-timeout-sec", type=float, default=120)
    parser.add_argument("--report", default="artifacts/stage-d-report.json")
    parser.add_argument("--plans-dir", default="artifacts/plans")
    parser.add_argument("--strict", action="store_true", help="exit 2 if warnings are found")
    args = parser.parse_args(argv)
    try:
        report = run(args)
        path = Path(args.report); path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
        print(f"Report: {path}")
        for item in report["days"]:
            m = item["metrics"]
            print(f"{item['date']}: visits={m['visits']}, slot={m['slot_adherence_pct']}%, "
                  f"deadline={m['deadline_adherence_pct']}%, H9={m['h9_exactly_once']}")
        if report["warnings"]:
            print("Warnings:\n" + "\n".join("- " + x for x in report["warnings"]))
            return 2 if args.strict else 0
        print("No conformance warnings.")
        return 0
    except SimulationError as exc:
        print(f"SIMULATION ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
