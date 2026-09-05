import unittest
from pathlib import Path

from evaluate import baseline_results, evaluate_results, load_fixture, validate_results


class RetrievalEvaluationTest(unittest.TestCase):
    def test_recall_uses_full_relevant_set_and_only_first_k_candidates(self):
        cases = [{"id": "Q", "relevantIds": ["A", "B"]}]
        report = evaluate_results(cases, {"Q": ["A", "X", "B"]}, k=2)
        self.assertEqual(0.5, report["macroRecallAtK"])
        self.assertIsNone(report["noMatchFalsePositiveRate"])

    def test_no_match_queries_are_separate_from_recall(self):
        cases = [{"id": "Q1", "relevantIds": ["A"]}, {"id": "Q2", "relevantIds": []}, {"id": "Q3", "relevantIds": []}]
        report = evaluate_results(cases, {"Q1": ["A"], "Q2": [], "Q3": ["B"]})
        self.assertEqual(1.0, report["macroRecallAtK"])
        self.assertEqual(0.5, report["noMatchFalsePositiveRate"])
        self.assertEqual(1, report["answerableQueries"])
        self.assertEqual(2, report["noMatchQueries"])

    def test_unlabeled_is_skipped_not_treated_as_no_match(self):
        report = evaluate_results([{"id": "Q", "relevantIds": None}], {})
        self.assertEqual(1, report["unlabeledQueriesSkipped"])
        self.assertEqual(0, report["noMatchQueries"])
        self.assertIsNone(report["macroRecallAtK"])
        self.assertIsNone(report["noMatchFalsePositiveRate"])

    def test_empty_case_set_does_not_divide_by_zero(self):
        report = evaluate_results([], {})
        self.assertIsNone(report["macroRecallAtK"])

    def test_missing_observation_is_not_silently_empty_result(self):
        cases = [{"id": "Q", "relevantIds": ["A"]}]
        with self.assertRaisesRegex(ValueError, "incomplete"):
            validate_results({}, [{"id": "A"}], cases, cases)

    def test_duplicate_and_unknown_results_are_rejected(self):
        cases = [{"id": "Q", "relevantIds": ["A"]}]
        for ids in (["A", "A"], ["UNKNOWN"]):
            with self.subTest(ids=ids), self.assertRaises(ValueError):
                validate_results({"Q": ids}, [{"id": "A"}], cases, cases)

    def test_keyword_no_overlap_returns_empty_and_tie_order_is_stable(self):
        docs = [{"id": "B", "text": "서울 AI", "sortTimestamp": "20260101"}, {"id": "A", "text": "서울 AI", "sortTimestamp": "20260101"}]
        cases = [{"id": "Q1", "query": "서울"}, {"id": "Q2", "query": "부산"}]
        latest, keyword = baseline_results(docs, cases, 20)
        self.assertEqual(["A", "B"], latest["Q1"])
        self.assertEqual(["A", "B"], keyword["Q1"])
        self.assertEqual([], keyword["Q2"])

    def test_fixture_has_old_relevant_cases_and_separate_splits(self):
        fixture = load_fixture(Path(__file__).with_name("fixture.json"))
        self.assertEqual(40, len(fixture["docs"]))
        self.assertEqual(30, len(fixture["cases"]))
        self.assertEqual(20, sum(case["split"] == "dev" for case in fixture["cases"]))
        self.assertEqual(10, sum(case["split"] == "heldout" for case in fixture["cases"]))
        latest, _ = baseline_results(fixture["docs"], fixture["cases"], 20)
        self.assertNotIn("SYNTH_AI_SEOUL", latest["Q01"])
        self.assertNotIn("SYNTH_PATENT", latest["Q27"])
        report = evaluate_results(fixture["cases"], latest)
        self.assertEqual(26, report["answerableQueries"])
        self.assertEqual(3, report["noMatchQueries"])
        self.assertEqual(1, report["unlabeledQueriesSkipped"])


if __name__ == "__main__":
    unittest.main()
