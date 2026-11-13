import tempfile
import unittest
from datetime import date
from pathlib import Path

from run_scenario import SimulationError, build_day, instant, load_events, load_work_types, day_spec, metrics


class SimulatorTests(unittest.TestCase):
    def test_csv_profiles_and_types(self):
        csv_text = ("code,name,profiles,duration_min,max_start_delay_min,deadline_kind,deadline_value,"
                    "repeat_deadline_value,pgp_relevant\nREPAIR,Repair,LTP3,60,0,HOURS,24,12,true\n"
                    "SURVEY,Survey,INSTALL|LTP3,45,,WORKING_DAYS,2,2,false\n")
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "work.csv"; path.write_text(csv_text, encoding="utf-8")
            rows = load_work_types(path)
        self.assertEqual(["INSTALL", "LTP3"], rows[1]["profiles"])
        self.assertIsNone(rows[1]["max_start_delay_min"])
        self.assertFalse(rows[1]["pgp_relevant"])

    def test_day_selection_respects_local_slot_date_and_model_now(self):
        day, now_text, now = day_spec("2026-10-26=2026-10-26T08:00:00+05:00")
        orders = [
            {"order_id":"A", "registered_at":"2026-10-26T07:00:00+05:00", "slot":{"start":"2026-10-26T09:00:00+05:00"}},
            {"order_id":"B", "registered_at":"2026-10-26T08:01:00+05:00", "slot":{"start":"2026-10-26T10:00:00+05:00"}},
            {"order_id":"C", "registered_at":"2026-10-26T07:00:00+05:00", "slot":{"start":"2026-10-27T09:00:00+05:00"}},
        ]
        payload, counts = build_day(orders, day, now_text, now, ["ST-2", "ST-1"])
        self.assertEqual(["A"], [x["order_id"] for x in payload["orders"]])
        self.assertEqual(["ST-1", "ST-2"], payload["absent_staff_ids"])
        self.assertEqual(1, counts["orders_other_day"])
        self.assertEqual(1, counts["orders_registered_after_now"])

    def test_timestamps_require_an_offset(self):
        with self.assertRaisesRegex(SimulationError, "must include an offset"):
            instant("2026-10-26T08:00:00", "test")

    def test_events_are_sorted_and_trigger_based_scenarios_are_rejected(self):
        data = '{"events":[{"event_id":"e2","event_type":"STAFF_UNAVAILABLE","occurred_at":"2026-10-26T10:00:00+05:00"},{"event_id":"e1","event_type":"VISIT_DELAYED","occurred_at":"2026-10-26T09:30:00+05:00","payload":{"delay_min":10}}]}'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "events.json"; path.write_text(data, encoding="utf-8")
            loaded = load_events(path, date(2026, 10, 26))
            self.assertEqual(["e1", "e2"], [x["event_id"] for x in loaded])
            path.write_text('{"events":[{"trigger":"after_plan"}]}', encoding="utf-8")
            with self.assertRaisesRegex(SimulationError, "needs an explicit adapter"):
                load_events(path, date(2026, 10, 26))

    def test_metrics_cover_h9_weighted_adherence_and_travel(self):
        day = {"orders":[
            {"order_id":"A", "slot":{"start":"2026-10-26T09:00:00+05:00"}, "latest_start":"2026-10-26T09:10:00+05:00", "deadline":"2026-10-26T10:10:00+05:00", "is_repeat":True},
            {"order_id":"B", "slot":{"start":"2026-10-26T10:00:00+05:00"}, "latest_start":"2026-10-26T10:10:00+05:00", "deadline":"2026-10-26T11:10:00+05:00", "is_repeat":False},
        ]}
        plan = {"routes":[{"totals":{"travel_sec":600,"distance_m":2000,"work_min":120},"stops":[
            {"type":"VISIT","order_id":"A","start":"2026-10-26T09:05:00+05:00","end":"2026-10-26T10:05:00+05:00"}]}],
            "unassigned":[{"order_id":"B"}], "pgp_groups":[], "cancelled":[], "alerts":[], "summary":{"utilization_cv":0.25}}
        result = metrics(plan, day, [], 1000, [], [])
        self.assertTrue(result["h9_exactly_once"])
        self.assertEqual(100.0, result["slot_adherence_pct"])
        self.assertEqual(100.0, result["repeat_weighted_combined_adherence_pct"])
        self.assertEqual(600, result["travel_sec"])
        self.assertEqual(2000, result["distance_m"])

    def test_h9_finds_duplicate_and_missing_order_placement(self):
        result = metrics({"routes":[{"stops":[{"type":"VISIT","order_id":"A"}]}],
                          "unassigned":[{"order_id":"A"}], "pgp_groups":[], "cancelled":[], "alerts":[]},
                         {"orders":[{"order_id":"A"},{"order_id":"B"}]}, [], 10, [], [])
        self.assertFalse(result["h9_exactly_once"])
        self.assertEqual(["B"], result["h9_missing"])
        self.assertEqual(["A"], result["h9_duplicates"])


if __name__ == "__main__":
    unittest.main()
