"""Readiness regressions; no Docker or external services are required."""
import importlib.util
import itertools
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("docker_smoke", Path(__file__).with_name("docker-smoke.py"))
smoke_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke_module)


class WarmRoutesTest(unittest.TestCase):
    def run_probe(self, inventory_statuses, order_statuses=(), persistent=False):
        smoke = smoke_module.Smoke.__new__(smoke_module.Smoke)
        smoke.env = {"GATEWAY_CB_MIN_CALLS": "4"}
        statuses = {"inventory": iter(inventory_statuses), "orders": iter(order_statuses)}
        calls = []

        def request(path, method="GET", body=None, expected=200, port=None):
            self.assertEqual((method, body, expected), ("GET", None, 404))
            route = path.split("/")[2]
            status = next(statuses[route], 503 if persistent else 404)
            calls.append((route, status))
            smoke_module.require(status == expected, f"HTTP {status}")

        smoke.request = request
        ticks = itertools.count()
        with patch.object(smoke_module.time, "sleep"), patch.object(
                smoke_module.time, "monotonic", side_effect=lambda: next(ticks)):
            smoke.warm_routes("missing-sku", "missing-order")
        return calls

    def test_healthy_routes_require_minimum_call_count_before_writes(self):
        calls = self.run_probe([])
        self.assertEqual(calls.count(("inventory", 404)), 4)
        self.assertEqual(calls.count(("orders", 404)), 4)

    def test_circuit_opens_after_initial_successful_probe(self):
        # Regression: early discovery failures can open the breaker after an
        # initial 404. The following 503 must be observed before issuing a POST.
        calls = self.run_probe([404, 404, 503])
        last_failure = calls.index(("inventory", 503))
        self.assertGreaterEqual(calls[last_failure + 1:].count(("inventory", 404)), 4)
        self.assertGreaterEqual(calls[last_failure + 1:].count(("orders", 404)), 4)

    def test_order_route_must_also_recover(self):
        calls = self.run_probe([], [404, 503])
        last_failure = calls.index(("orders", 503))
        self.assertGreaterEqual(calls[last_failure + 1:].count(("orders", 404)), 4)
        self.assertGreaterEqual(calls[last_failure + 1:].count(("inventory", 404)), 4)

    def test_persistent_discovery_failure_still_fails(self):
        with self.assertRaisesRegex(AssertionError, "Timed out: Gateway routes settle"):
            self.run_probe([], persistent=True)


if __name__ == "__main__":
    unittest.main()
