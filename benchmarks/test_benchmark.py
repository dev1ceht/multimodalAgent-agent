from __future__ import annotations

import csv
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

        self.assertEqual(120, len(stage))
        self.assertEqual(60, len(end_to_end))
        self.assertEqual(
            {"direct": 40, "colloquial": 30, "multi_source": 20, "insufficient": 20, "misleading": 10},
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


def counts(rows: list[dict], key: str) -> dict[str, int]:
    result: dict[str, int] = {}
    for row in rows:
        value = row[key]
        result[value] = result.get(value, 0) + 1
    return result


if __name__ == "__main__":
    unittest.main()
