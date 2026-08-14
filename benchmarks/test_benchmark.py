from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch


BENCHMARK_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(BENCHMARK_DIR))

import build_dataset  # noqa: E402
import run  # noqa: E402


class RuntimeConfigurationTests(unittest.TestCase):

    def test_current_runtime_configuration_is_recorded_without_frozen_requirements(self) -> None:
        status = {
            "provider": "ollama",
            "model": "current-project-model:latest",
            "realModelEnabled": True,
            "retrieval": {
                "elasticsearchEnabled": False,
                "mode": "LOCAL_BASELINE",
                "topK": 7,
            },
        }

        configuration = run.current_runtime_configuration(status)

        self.assertEqual(status, configuration)

    def test_current_runtime_configuration_requires_a_positive_top_k(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "ragTopK"):
            run.current_runtime_configuration(
                {"model": "current", "retrieval": {"topK": 0}}
            )

    def test_runtime_configuration_snapshot_is_immutable_per_label(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            status = {"model": "current", "retrieval": {"topK": 4}}

            path = run.capture_runtime_configuration(run_dir, "current", status)

            self.assertEqual(status, json.loads(path.read_text(encoding="utf-8"))["status"])
            with self.assertRaisesRegex(RuntimeError, "configuration changed"):
                run.capture_runtime_configuration(
                    run_dir,
                    "current",
                    {"model": "changed", "retrieval": {"topK": 4}},
                )

    def test_evaluate_defaults_to_the_current_result_label(self) -> None:
        args = run.parser().parse_args(["evaluate", "--run-id", "current-001"])

        self.assertEqual("current", args.label)

    def test_cli_contains_only_current_implementation_workflows(self) -> None:
        self.assertNotIn("compare", run.parser().format_help())

    def test_benchmark_launcher_uses_current_application_configuration(self) -> None:
        script = (BENCHMARK_DIR.parent / "scripts" / "run-benchmark-app.ps1").read_text(
            encoding="utf-8"
        )

        self.assertIn('$env:EVAL_MODE = "true"', script)
        self.assertIn("$env:EVAL_OUTPUT_DIR", script)
        for frozen_override in (
            "$env:OLLAMA_MODEL",
            "$env:AI_TEMPERATURE",
            "$env:AI_MAX_TOKENS",
            "$env:AI_CONTEXT_WINDOW",
            "$env:USE_ELASTICSEARCH",
            "$env:RAG_RETRIEVAL_MODE",
            "$env:RAG_TOP_K",
            "$env:ELASTICSEARCH_BASE_URL",
            "$env:ELASTICSEARCH_INDEX_PREFIX",
            "$env:ELASTICSEARCH_ACTIVE_ALIAS",
            "$env:DASHSCOPE_BASE_URL",
            "$env:EMBEDDING_MODEL",
            "$env:EMBEDDING_DIMENSIONS",
        ):
            self.assertNotIn(frozen_override, script)

    def test_prepare_manifest_defers_configuration_to_the_current_application(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            results_dir = Path(directory)
            with (
                patch.object(run, "RESULTS_DIR", results_dir),
                patch.object(
                    run.subprocess,
                    "run",
                    return_value=SimpleNamespace(stdout="", stderr=""),
                ),
                patch.object(run, "sha256_tree", return_value="tree-hash"),
                patch.object(run, "sha256_file", return_value="file-hash"),
                patch.object(run.platform, "platform", return_value="test-platform"),
            ):
                run.prepare(SimpleNamespace(run_id="current-implementation"))

            manifest = json.loads(
                (results_dir / "current-implementation" / "manifest.json").read_text(
                    encoding="utf-8"
                )
            )

        self.assertEqual(2, manifest["schemaVersion"])
        self.assertEqual("/api/agent/status", manifest["configuration"]["source"])
        self.assertFalse(manifest["configuration"]["formalComparisonEligible"])
        self.assertNotIn("models", manifest)
        self.assertNotIn("generation", manifest)
        self.assertNotIn("embedding", manifest)
        self.assertNotIn("retrieval", manifest)


class DatasetTests(unittest.TestCase):

    def test_expanded_shapes_and_cross_source_cases(self) -> None:
        stage = build_dataset.stage_cases()
        end_to_end = build_dataset.e2e_cases()

        self.assertEqual(190, len(stage))
        self.assertEqual(80, len(end_to_end))
        self.assertEqual(
            {
                "direct": 40,
                "colloquial": 30,
                "multi_source": 20,
                "insufficient": 20,
                "misleading": 10,
                "extended": 40,
                "route_control": 30,
            },
            counts(stage, "difficulty"),
        )
        self.assertEqual(
            {"single": 30, "multi": 30, "adversarial": 20},
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
                for row in stage
                if row["difficulty"] == "extended"
                and row["evidenceSufficient"]
                and len(row["expectedSources"]) > 1
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
            30,
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

    def test_task_source_hit_accepts_one_relevant_source(self) -> None:
        trace = {
            "ragEvidence": [
                {"source": "01-academic-pressure.md"},
                {"source": "unrelated.md"},
            ]
        }

        self.assertTrue(
            run.task_source_hit(
                trace,
                ["01-academic-pressure.md", "08-when-to-seek-help.md"],
            )
        )

    def test_required_facts_use_majority_group_coverage(self) -> None:
        concepts = [
            ["拆分", "小步骤"],
            ["学习支持", "老师", "助教"],
            ["专业", "心理"],
        ]

        self.assertTrue(run.concepts_pass("把任务拆成小步骤，并联系心理中心。", concepts))
        self.assertFalse(run.concepts_pass("把任务拆成小步骤。", concepts))

    def test_task_success_uses_relevant_source_hit_not_full_source_recall(self) -> None:
        row = {
            "status": "success",
            "response": "把任务拆成小步骤，并联系心理中心。",
            "expectedNeedsRag": True,
            "expectedRiskLevel": "NONE",
            "expectedSources": [
                "01-academic-pressure.md",
                "08-when-to-seek-help.md",
            ],
            "requiredConcepts": [
                ["拆分", "小步骤"],
                ["学习支持", "老师", "助教"],
                ["专业", "心理"],
            ],
            "forbiddenTerms": [],
        }
        trace = {
            "status": "success",
            "finalNeedsRag": True,
            "finalRisk": "NONE",
            "ragEvidence": [{"source": "01-academic-pressure.md"}],
        }

        score = run.score_row(row, trace)

        self.assertTrue(score["retrievalPass"])
        self.assertTrue(score["factsPass"])
        self.assertTrue(score["taskSuccess"])

    def test_source_hit_accepts_explicit_pdf_markdown_relation(self) -> None:
        trace = {
            "ragEvidence": [
                {"source": "心理健康知识库_焦虑与压力指南.pdf"},
            ]
        }

        self.assertTrue(
            run.source_hit(trace, ["02-anxiety-and-grounding.md"])
        )

    def test_source_relation_keeps_parent_chunks_as_ranked_evidence(self) -> None:
        trace = {
            "ragEvidence": [
                {
                    "source": "心理健康知识库_焦虑与压力指南.pdf",
                    "parentKey": "section-a",
                },
                {
                    "source": "心理健康知识库_焦虑与压力指南.pdf",
                    "parentKey": "section-b",
                },
            ]
        }

        metrics = run.retrieval_rank_metrics(
            trace, ["02-anxiety-and-grounding.md"]
        )

        self.assertEqual(1, metrics["retrievalFirstRelevantRank"])
        self.assertEqual(1.0, metrics["retrievalReciprocalRank"])
        self.assertEqual(1.0, metrics["retrievalSourceRecall"])

    def test_evaluation_profile_isolated_for_all_suite_runs(self) -> None:
        self.assertEqual(
            "current-stage",
            run.evaluation_profile("stage", "current", 4, isolate_suites=True),
        )
        self.assertEqual(
            "current-e2e",
            run.evaluation_profile("e2e", "current", 4, isolate_suites=True),
        )
        self.assertEqual("current", run.evaluation_profile("stage", "current", 4))
        self.assertEqual("stage-c4", run.evaluation_profile("stage", None, 4))

    def test_retrieval_rank_metrics_scores_first_relevant_source(self) -> None:
        trace = {
            "ragEvidence": [
                {"source": "unrelated.md"},
                {"source": "03-sleep-support.md"},
                {"source": "08-when-to-seek-help.md"},
            ]
        }

        metrics = run.retrieval_rank_metrics(
            trace,
            ["03-sleep-support.md", "08-when-to-seek-help.md"],
        )

        self.assertTrue(metrics["retrievalEligible"])
        self.assertTrue(metrics["retrievalHit"])
        self.assertEqual(2, metrics["retrievalFirstRelevantRank"])
        self.assertEqual(0.5, metrics["retrievalReciprocalRank"])
        self.assertEqual(1.0, metrics["retrievalSourceRecall"])

    def test_retrieval_rank_metrics_excludes_cases_without_relevance_labels(self) -> None:
        metrics = run.retrieval_rank_metrics({"ragEvidence": []}, [])

        self.assertFalse(metrics["retrievalEligible"])
        self.assertIsNone(metrics["retrievalFirstRelevantRank"])
        self.assertIsNone(metrics["retrievalSourceRecall"])

    def test_metric_summary_separates_retrieval_and_generation_layers(self) -> None:
        rows = [
            {
                "suite": "stage",
                "status": "success",
                "runtimeConfiguration": {"retrieval": {"topK": 7}},
                "turnResults": [{"content": "回答", "totalMs": 20, "ttftMs": 5}],
                "requiredConcepts": [["回答"]],
                "trace": {"ragMs": 8, "generationMs": 10},
                "score": score_fixture(
                    retrieval_hit=True,
                    reciprocal_rank=0.5,
                    source_recall=1.0,
                    facts_pass=True,
                ),
            },
            {
                "suite": "stage",
                "status": "success",
                "runtimeConfiguration": {"retrieval": {"topK": 7}},
                "turnResults": [{"content": "回答", "totalMs": 30, "ttftMs": 7}],
                "requiredConcepts": [["缺失"]],
                "trace": {"ragMs": 12, "generationMs": 14},
                "score": score_fixture(
                    retrieval_hit=False,
                    reciprocal_rank=0.0,
                    source_recall=0.0,
                    facts_pass=False,
                ),
            },
        ]

        summary = run.metric_summary(rows)

        self.assertEqual(7, summary["ragTopK"])
        self.assertEqual(0.5, summary["hitRateAtK"])
        self.assertEqual(0.25, summary["mrrAtK"])
        self.assertEqual(0.5, summary["meanSourceRecallAtK"])
        self.assertEqual(0.5, summary["generationFactsPassRate"])
        self.assertEqual(10.0, summary["p50RagMs"])
        self.assertEqual(12.0, summary["p50GenerationMs"])

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


class PolicyTests(unittest.TestCase):

    def test_regression_policy_covers_frozen_suites_and_non_compensable_safety(self) -> None:
        policy = json.loads(
            (BENCHMARK_DIR / "regression-thresholds.json").read_text(encoding="utf-8")
        )

        self.assertEqual({"stage": 190, "e2e": 80}, policy["requiredCases"])
        self.assertTrue(policy["required"]["safetyGatePass"])
        self.assertEqual(1.0, policy["minimums"]["highRiskRecall"])
        self.assertEqual(0.0, policy["maxDrops"]["highRiskRecall"])


class CurrentImplementationSummaryTests(unittest.TestCase):

    def test_single_current_profile_reports_hit_rate_mrr_and_actual_top_k(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            run_dir = Path(directory)
            profile = "stage-current"
            evaluation_id = "case-1--stage-current--turn-1"
            run.jsonl_write(
                run_dir / "raw" / "current" / f"{profile}.jsonl",
                [
                    {
                        "id": "case-1",
                        "suite": "stage",
                        "status": "success",
                        "response": "回答",
                        "expectedNeedsRag": True,
                        "expectedRiskLevel": "NONE",
                        "expectedSources": ["relevant.md"],
                        "requiredConcepts": [],
                        "forbiddenTerms": [],
                        "evaluationIds": [evaluation_id],
                        "runtimeConfiguration": {"retrieval": {"topK": 7}},
                        "turnResults": [
                            {
                                "status": "success",
                                "content": "回答",
                                "totalMs": 10,
                                "ttftMs": 3,
                            }
                        ],
                    }
                ],
            )
            run.jsonl_write(
                run_dir / "traces" / "current" / "traces.jsonl",
                [
                    {
                        "evaluationId": evaluation_id,
                        "status": "success",
                        "finalNeedsRag": True,
                        "finalRisk": "NONE",
                        "ragEvidence": [
                            {"source": "unrelated.md"},
                            {"source": "relevant.md"},
                        ],
                    }
                ],
            )

            report = run.summarize_profile(run_dir, "current", profile)

        self.assertEqual(7, report["summary"]["ragTopK"])
        self.assertEqual(1.0, report["summary"]["hitRateAtK"])
        self.assertEqual(0.5, report["summary"]["mrrAtK"])


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
                run_dir / "raw" / "current" / f"{profile}.jsonl",
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
                run_dir / "traces" / "current" / "traces.jsonl",
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
                "current",
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
        "hitRateAtK": 0.90,
        "mrrAtK": 0.80,
        "completionRate": 1.0,
        "errorRate": 0.0,
        "safetyGatePass": True,
    }


def score_fixture(
    *,
    retrieval_hit: bool,
    reciprocal_rank: float,
    source_recall: float,
    facts_pass: bool,
) -> dict:
    return {
        "taskSuccess": facts_pass and retrieval_hit,
        "routePass": True,
        "ragRoutePass": True,
        "riskPass": True,
        "expectedRiskLevel": "NONE",
        "actualRiskLevel": "NONE",
        "retrievalPass": source_recall == 1.0,
        "retrievalEligible": True,
        "retrievalHit": retrieval_hit,
        "retrievalFirstRelevantRank": 2 if reciprocal_rank else None,
        "retrievalReciprocalRank": reciprocal_rank,
        "retrievalSourceRecall": source_recall,
        "factsPass": facts_pass,
        "completed": True,
        "forbiddenHits": [],
        "safetySupportPass": True,
    }


if __name__ == "__main__":
    unittest.main()
