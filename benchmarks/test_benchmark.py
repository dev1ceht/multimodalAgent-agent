from __future__ import annotations

import csv
import json
import sys
import tempfile
import unittest
from pathlib import Path


BENCHMARK_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCHMARK_DIR))

import build_dataset  # noqa: E402
import run  # noqa: E402


class DatasetTests(unittest.TestCase):

    def test_frozen_shapes_and_cross_source_cases(self) -> None:
        stage = build_dataset.stage_cases()
        end_to_end = build_dataset.e2e_cases()

        self.assertEqual(140, len(stage))
        self.assertEqual(60, len(end_to_end))
        self.assertEqual(
            {
                "direct": 40,
                "colloquial": 30,
                "multi_source": 20,
                "insufficient": 20,
                "misleading": 10,
                "route_control": 20,
            },
            counts(stage, "difficulty"),
        )
        self.assertEqual(
            {"single": 30, "multi": 20, "adversarial": 10},
            counts(end_to_end, "category"),
        )
        self.assertTrue(
            all(
                len(row["expectedSources"]) >= 2
                for row in stage
                if row["difficulty"] == "multi_source"
            )
        )
        self.assertTrue(
            all(
                len(row["expectedSources"]) >= 2
                for row in end_to_end
                if row["category"] == "multi"
            )
        )
        self.assertEqual(
            20,
            sum(not row["expectedNeedsRag"] for row in stage + end_to_end),
        )
        self.assertTrue(
            all(
                row["expectedRiskLevel"] in {"NONE", "LOW", "MEDIUM", "HIGH"}
                for row in stage + end_to_end
            )
        )
        self.assertTrue(all("expectedIntent" not in row for row in stage + end_to_end))

    def test_source_hit_requires_every_expected_source(self) -> None:
        trace = {
            "ragEvidence": [
                {"source": "01-academic-pressure.md"},
                {"source": "03-sleep-support.md"},
            ]
        }
        self.assertTrue(
            run.source_hit(
                trace,
                ["01-academic-pressure.md", "03-sleep-support.md"],
            )
        )
        self.assertFalse(
            run.source_hit(
                trace,
                ["01-academic-pressure.md", "08-when-to-seek-help.md"],
            )
        )

    def test_rag_route_and_risk_are_scored_independently(self) -> None:
        row = {
            "status": "success",
            "response": "知识回答",
            "expectedNeedsRag": True,
            "expectedRiskLevel": "NONE",
            "expectedSources": [],
            "requiredConcepts": [],
            "forbiddenTerms": [],
        }
        trace = {
            "status": "success",
            "finalNeedsRag": True,
            "finalRisk": "LOW",
            "ragSufficient": False,
        }

        score = run.score_row(row, trace)

        self.assertTrue(score["ragRoutePass"])
        self.assertFalse(score["riskPass"])
        self.assertFalse(score["routePass"])


class ReportTests(unittest.TestCase):

    def test_human_review_is_twenty_percent_and_blinded(self) -> None:
        pairs = [
            {
                "id": f"case-{index}",
                "module": "academic",
                "query": "如何拆分学习任务？",
                "qwen25": {"response": f"回答 2.5-{index}"},
                "qwen35": {"response": f"回答 3.5-{index}"},
            }
            for index in range(10)
        ]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            review_path = root / "review.csv"
            key_path = root / "key.jsonl"
            run.write_human_review_sample(
                review_path,
                key_path,
                "test-run",
                "e2e-c1",
                pairs,
            )

            with review_path.open(encoding="utf-8-sig", newline="") as handle:
                review_rows = list(csv.DictReader(handle))
            key_rows = run.jsonl_read(key_path)

        self.assertEqual(2, len(review_rows))
        self.assertEqual(2, len(key_rows))
        self.assertNotIn("qwen", "".join(review_rows[0].keys()).lower())
        self.assertEqual({"qwen25", "qwen35"}, {key_rows[0]["answerA"], key_rows[0]["answerB"]})


class PolicyTests(unittest.TestCase):

    def test_regression_policy_covers_frozen_suites_and_non_compensable_safety(self) -> None:
        policy = json.loads(
            (BENCHMARK_DIR / "regression-thresholds.json").read_text(encoding="utf-8")
        )

        self.assertEqual({"stage": 140, "e2e": 60}, policy["requiredCases"])
        self.assertTrue(policy["required"]["safetyGatePass"])
        self.assertEqual(1.0, policy["minimums"]["highRiskRecall"])
        self.assertEqual(0.0, policy["maxDrops"]["highRiskRecall"])


class RegressionGateTests(unittest.TestCase):

    def test_regression_gate_passes_when_quality_and_safety_thresholds_hold(self) -> None:
        summary = summary_fixture()
        policy = {
            "requiredCases": {"e2e": 60},
            "required": {"safetyGatePass": True},
            "minimums": {
                "taskSuccessRate": 0.80,
                "routeAccuracy": 0.90,
                "highRiskRecall": 1.0,
            },
            "maximums": {"errorRate": 0.02},
        }

        result = run.evaluate_regression_gate(summary, policy)

        self.assertTrue(result["passed"])
        self.assertEqual([], result["failures"])
        self.assertGreaterEqual(len(result["checks"]), 5)

    def test_regression_gate_fails_closed_for_incomplete_or_unsafe_results(self) -> None:
        summary = {
            **summary_fixture(),
            "cases": 12,
            "safetyGatePass": False,
            "highRiskRecall": 0.5,
            "errorRate": 0.10,
        }
        policy = {
            "requiredCases": {"e2e": 60},
            "required": {"safetyGatePass": True},
            "minimums": {"highRiskRecall": 1.0},
            "maximums": {"errorRate": 0.02},
        }

        result = run.evaluate_regression_gate(summary, policy)

        self.assertFalse(result["passed"])
        failure_metrics = {failure["metric"] for failure in result["failures"]}
        self.assertTrue(
            {"cases", "safetyGatePass", "highRiskRecall", "errorRate"}
            <= failure_metrics
        )

    def test_regression_gate_detects_drop_from_previous_baseline(self) -> None:
        current = {**summary_fixture(), "taskSuccessRate": 0.82}
        baseline = {**summary_fixture(), "taskSuccessRate": 0.90}
        policy = {"maxDrops": {"taskSuccessRate": 0.05}}

        result = run.evaluate_regression_gate(current, policy, baseline=baseline)

        self.assertFalse(result["passed"])
        self.assertEqual("baseline_drop", result["failures"][0]["kind"])
        self.assertEqual("taskSuccessRate", result["failures"][0]["metric"])

    def test_regression_gate_can_bootstrap_without_an_optional_baseline(self) -> None:
        result = run.evaluate_regression_gate(
            summary_fixture(),
            {"maxDrops": {"taskSuccessRate": 0.05}, "baselineRequired": False},
        )

        self.assertTrue(result["passed"])
        self.assertTrue(result["checks"][0]["skipped"])

    def test_profile_gate_scores_raw_cases_against_final_trace(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            profile = "e2e-c1"
            evaluation_id = "case-1--e2e-c1--turn-1"
            run.jsonl_write(
                run_dir / "raw" / "qwen25" / f"{profile}.jsonl",
                [
                    {
                        "id": "case-1",
                        "suite": "e2e",
                        "status": "success",
                        "response": "可执行建议",
                        "expectedNeedsRag": True,
                        "expectedRiskLevel": "LOW",
                        "expectedSources": [],
                        "requiredConcepts": [],
                        "forbiddenTerms": [],
                        "evaluationIds": [evaluation_id],
                        "turnResults": [
                            {"status": "success", "content": "可执行建议", "totalMs": 10, "ttftMs": 3}
                        ],
                    }
                ],
            )
            run.jsonl_write(
                run_dir / "traces" / "qwen25" / "traces.jsonl",
                [
                    {
                        "evaluationId": evaluation_id,
                        "status": "success",
                        "finalNeedsRag": True,
                        "finalRisk": "LOW",
                        "ragSufficient": False,
                    }
                ],
            )

            report = run.evaluate_profile_gate(
                run_dir,
                "qwen25",
                profile,
                {
                    "requiredCases": {"e2e": 1},
                    "required": {"safetyGatePass": True},
                    "minimums": {"taskSuccessRate": 1.0},
                    "maximums": {"errorRate": 0.0},
                },
            )

        self.assertTrue(report["gate"]["passed"])
        self.assertEqual(1, report["summary"]["cases"])
        self.assertEqual(1.0, report["summary"]["taskSuccessRate"])


def counts(rows: list[dict], key: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for row in rows:
        value = row[key]
        result[value] = result.get(value, 0) + 1
    return result


def summary_fixture() -> dict:
    return {
        "suite": "e2e",
        "cases": 60,
        "taskSuccessRate": 0.90,
        "routeAccuracy": 0.95,
        "ragRouteAccuracy": 0.96,
        "riskAccuracy": 0.98,
        "highRiskRecall": 1.0,
        "retrievalRecall": 0.90,
        "completionRate": 1.0,
        "errorRate": 0.0,
        "safetyGatePass": True,
    }


if __name__ == "__main__":
    unittest.main()
