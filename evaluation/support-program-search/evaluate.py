#!/usr/bin/env python3
"""Offline retrieval comparison; no API calls, credentials, or production index writes."""

import argparse
import json
import re
import unicodedata
from pathlib import Path


def load_fixture(path):
    fixture = json.loads(Path(path).read_text(encoding="utf-8"))
    docs, cases = fixture["docs"], fixture["cases"]
    doc_ids = [doc["id"] for doc in docs]
    case_ids = [case["id"] for case in cases]
    if len(set(doc_ids)) != len(doc_ids) or len(set(case_ids)) != len(case_ids):
        raise ValueError("Duplicate document or query ID in fixture")
    known_docs = set(doc_ids)
    for doc in docs:
        if not all(isinstance(doc.get(key), str) and doc[key] for key in ("id", "text", "sortTimestamp")):
            raise ValueError("Documents require nonempty id, text, sortTimestamp strings")
    for case in cases:
        if not isinstance(case.get("query"), str) or not case["query"].strip():
            raise ValueError(f"Missing query for {case['id']}")
        if case.get("split") not in ("dev", "heldout"):
            raise ValueError(f"Invalid split for {case['id']}")
        labels = case["relevantIds"]
        if labels is not None:
            if not isinstance(labels, list) or any(not isinstance(item, str) for item in labels):
                raise ValueError(f"relevantIds must be an ID list or null for {case['id']}")
            if len(set(labels)) != len(labels) or not set(labels) <= known_docs:
                raise ValueError(f"Duplicate or unknown relevant document for {case['id']}")
    return fixture


def newest_first(docs):
    # Stable two-pass sorting matches timestamp DESC, ID ASC, including tied dates.
    return sorted(sorted(docs, key=lambda doc: doc["id"]), key=lambda doc: doc["sortTimestamp"], reverse=True)


def tokenize(text):
    return set(re.findall(r"[a-z0-9가-힣]+", unicodedata.normalize("NFC", text).casefold()))


def baseline_results(docs, cases, k):
    ordered = newest_first(docs)
    latest = [doc["id"] for doc in ordered[:k]]
    latest_results, keyword_results = {}, {}
    for case in cases:
        query_words = tokenize(case["query"])
        scored = [(doc, len(query_words & tokenize(doc["text"]))) for doc in ordered]
        scored.sort(key=lambda item: item[1], reverse=True)
        latest_results[case["id"]] = latest.copy()
        keyword_results[case["id"]] = [doc["id"] for doc, score in scored if score > 0][:k]
    return latest_results, keyword_results


def validate_results(results, docs, all_cases, selected_cases):
    if not isinstance(results, dict):
        raise ValueError("Search results must map query IDs to ordered document ID lists")
    known_queries = {case["id"] for case in all_cases}
    known_docs = {doc["id"] for doc in docs}
    if not set(results) <= known_queries:
        raise ValueError("Search results contain unknown query IDs")
    required = {case["id"] for case in selected_cases if case["relevantIds"] is not None}
    if not required <= set(results):
        raise ValueError("Search results are incomplete; use [] for an observed empty result, not a missing key")
    for query_id, ids in results.items():
        if not isinstance(ids, list) or any(not isinstance(item, str) for item in ids):
            raise ValueError(f"Expected document ID list for {query_id}")
        if len(set(ids)) != len(ids) or not set(ids) <= known_docs:
            raise ValueError(f"Duplicate or unknown returned document for {query_id}")


def evaluate_results(cases, results, k=20):
    if k < 1:
        raise ValueError("k must be positive")
    recalls, no_match_false_positives, details = [], [], []
    skipped = 0
    for case in cases:
        labels = case["relevantIds"]
        if labels is None:
            skipped += 1
            details.append({"queryId": case["id"], "status": "unlabeled_skipped"})
            continue
        returned = results[case["id"]][:k]
        if labels:
            hits = set(returned) & set(labels)
            recall = len(hits) / len(set(labels))
            recalls.append(recall)
            details.append({"queryId": case["id"], "recall": recall, "returnedIds": returned, "missingRelevantIds": sorted(set(labels) - hits)})
        else:
            false_positive = bool(returned)
            no_match_false_positives.append(false_positive)
            details.append({"queryId": case["id"], "noMatchFalsePositive": false_positive, "returnedIds": returned})
    return {
        "k": k,
        "answerableQueries": len(recalls),
        "macroRecallAtK": sum(recalls) / len(recalls) if recalls else None,
        "noMatchQueries": len(no_match_false_positives),
        "noMatchFalsePositiveRate": sum(no_match_false_positives) / len(no_match_false_positives) if no_match_false_positives else None,
        "unlabeledQueriesSkipped": skipped,
        "perQuery": details,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", type=Path, default=Path(__file__).with_name("fixture.json"))
    parser.add_argument("--semantic-results", type=Path, help="Saved semantic candidate IDs by query ID, from this same corpus")
    parser.add_argument("--split", choices=("all", "dev", "heldout"), default="all")
    parser.add_argument("--k", type=int, default=20)
    args = parser.parse_args()
    if args.k < 1:
        parser.error("--k must be positive")
    try:
        fixture = load_fixture(args.fixture)
        cases = [case for case in fixture["cases"] if args.split == "all" or case["split"] == args.split]
        latest, keyword = baseline_results(fixture["docs"], cases, args.k)
        report = {
            "fixture": fixture["name"],
            "dataType": "synthetic_not_real_programs",
            "split": args.split,
            "documentCount": len(fixture["docs"]),
            "queryCount": len(cases),
            "latest": evaluate_results(cases, latest, args.k),
            "keyword": evaluate_results(cases, keyword, args.k),
            "semantic": None,
        }
        if args.semantic_results:
            saved = json.loads(args.semantic_results.read_text(encoding="utf-8"))
            validate_results(saved, fixture["docs"], fixture["cases"], cases)
            report["semantic"] = evaluate_results(cases, saved, args.k)
        print(json.dumps(report, ensure_ascii=False, indent=2))
    except (OSError, ValueError, KeyError, TypeError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
