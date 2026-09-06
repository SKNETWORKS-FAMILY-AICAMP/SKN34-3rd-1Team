#!/usr/bin/env python3
"""Verify a frozen support-program review run without API or model execution."""
import argparse
import contextlib
import io
import importlib.util
import json
import shutil
import sys
import tempfile
from pathlib import Path


HERE = Path(__file__).resolve().parent


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, HERE / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


RUNNER = load_module("verify_ai_runner", "run-ai-review.py")
SELECTOR = load_module("verify_selector", "select-review-mode.py")


def read(path):
    return RUNNER.PAGE.load_json(path)


def invoke(module, args):
    old = module.parse_args
    module.parse_args = lambda: args
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            module.main()
    finally:
        module.parse_args = old


def same_json(left, right):
    return read(left) == read(right)


def verify_run(run_dir):
    run = Path(run_dir).resolve()
    review = run / "review-v2"
    ai = review / "codex-ai-v1"
    source = {
        "fixture": run / "fixture-unlabeled.json",
        "query_set": run / "query-set.json",
        "config": run / "pool-config.json",
        "review_pool": review / "review-pool.csv",
        "provenance": review / "review-pool-provenance.csv",
        "pool_manifest": review / "review-pool-manifest.json",
        "conversation": review / "conversation-judgments.json",
        "ai_review": ai / "ai-review.json",
        "prepared": ai / "prepared.json",
        "policy": ai / "policy.json",
        "assignments": ai / "assignments.json",
        "blind_input": ai / "blind-input.jsonl",
    }
    judges = [ai / f"judge-{number}.jsonl" for number in range(1, 6)]
    selected_dirs = {
        "ai-only": review / "selected-ai-v1",
        "hybrid": review / "selected-hybrid-v1",
        "human": review / "selected-human-v1",
    }
    selected_files = [directory / filename for directory in selected_dirs.values()
                      for filename in ("selection.json", "reviewed.csv", "review-progress.json")]
    required = list(source.values()) + judges + selected_files
    missing = [str(path.relative_to(run)) for path in required if not path.is_file()]
    if missing:
        raise ValueError("missing source: " + ", ".join(missing))

    # Existing source/pool validators reject changed fixture, query, manifest, or CSV.
    fixture = RUNNER.PAGE.load_fixture(source["fixture"])
    query = RUNNER.PAGE.load_json(source["query_set"])
    manifest = RUNNER.PAGE.load_json(source["pool_manifest"])
    config = RUNNER.PAGE.load_json(source["config"])
    RUNNER.PAGE.POOL.validate_inputs(fixture, query, config)
    rows = RUNNER.PAGE.load_verified_pool(source["review_pool"], manifest)
    if manifest.get("configSha256") != RUNNER.PAGE.POOL.sha256_path(source["config"]):
        raise ValueError("Pool-config hash differs")
    if manifest.get("provenanceCsvSha256") != RUNNER.PAGE.POOL.sha256_path(source["provenance"]):
        raise ValueError("Provenance CSV hash differs")
    RUNNER.PAGE.validate_sources(fixture, query, manifest, rows)
    policy = read(source["policy"])
    RUNNER.validate_policy(policy)
    model = policy["model"]

    with tempfile.TemporaryDirectory(prefix="verify-shared-run-") as temp_name:
        temp = Path(temp_name)
        copied = {}
        for key in ("fixture", "query_set", "config", "review_pool", "provenance", "pool_manifest", "conversation", "prepared", "policy", "assignments", "blind_input"):
            target = temp / Path(source[key]).name
            shutil.copyfile(source[key], target)
            copied[key] = target
        copied_judges = []
        for path in judges:
            target = temp / path.name
            shutil.copyfile(path, target)
            copied_judges.append(str(target))
        prepared_dir = temp / "prepared"
        prepared_dir.mkdir()
        for key in ("prepared", "policy"):
            shutil.copyfile(copied[key], prepared_dir / Path(copied[key]).name)
        shutil.copyfile(copied["blind_input"], prepared_dir / "blind-input.jsonl")
        # Use the saved prepared/policy/assignments and original judge JSONL in isolation.
        collected = temp / "ai-review.json"
        collect_args = argparse.Namespace(
            fixture=str(copied["fixture"]), query_set=str(copied["query_set"]),
            review_pool=str(copied["review_pool"]), pool_manifest=str(copied["pool_manifest"]),
            model=model, prepared_dir=str(prepared_dir), assignments=str(copied["assignments"]),
            judge_file=copied_judges, output=str(collected),
        )
        with contextlib.redirect_stdout(io.StringIO()):
            RUNNER.collect(collect_args)
        if not same_json(collected, source["ai_review"]):
            raise ValueError("recollected ai-review.json differs")
        saved_ai = temp / "saved-ai-review.json"
        shutil.copyfile(source["ai_review"], saved_ai)

        mode_results = {}
        for mode in ("ai-only", "hybrid", "human"):
            out = temp / ("selected-" + mode)
            args = argparse.Namespace(
                fixture=str(copied["fixture"]), query_set=str(copied["query_set"]),
                review_pool=str(copied["review_pool"]), pool_manifest=str(copied["pool_manifest"]),
                mode=mode, ai_review=str(saved_ai) if mode != "human" else None,
                human_review=None, conversation_judgments=str(copied["conversation"]),
                output_dir=str(out),
            )
            invoke(SELECTOR, args)
            saved = selected_dirs[mode]
            for filename in ("selection.json", "reviewed.csv", "review-progress.json"):
                generated = out / filename
                expected = saved / filename
                if filename.endswith(".json"):
                    equal = same_json(generated, expected)
                else:
                    equal = generated.read_bytes() == expected.read_bytes()
                if not equal:
                    raise ValueError(f"{mode} {filename} differs")
            mode_results[mode] = read(out / "selection.json")["status"]

    return {"status": "ok", "checks": 1 + len(mode_results), "modes": mode_results,
            "judgments": len(read(source["ai_review"])["judgments"]),
            "actualSearchEvaluated": False}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-dir", required=True)
    args = parser.parse_args(argv)
    try:
        result = verify_run(args.run_dir)
    except (OSError, ValueError, KeyError, json.JSONDecodeError) as error:
        print(json.dumps({"status": "failed", "error": str(error)}, ensure_ascii=False))
        return 1
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    sys.exit(main())
