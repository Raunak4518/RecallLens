import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
from statistics import median

from tools.vlm_eval.decision import candidate_metrics, parse_decision
from tools.vlm_eval.image_input import policy_receipt
from tools.vlm_eval.run_rerank import PARAMETERS, SYSTEM_PROMPT, validate_inputs


def compare(corpus, baseline_path, judgments_path, journal_path):
    cases, hashes = validate_inputs(corpus, baseline_path, judgments_path)
    with journal_path.open(encoding="utf-8") as stream:
        records = [json.loads(line) for line in stream]
    if len(records) != len(cases) + 2:
        raise ValueError("A comparison requires a complete single run")
    header, *rows, terminal = records
    if (header.get("record") != "header" or header.get("version") != 2
            or header.get("case_count") != len(cases) or any(header.get(key) != value for key, value in hashes.items())
            or any(header.get(key) != value for key, value in policy_receipt().items())
            or header.get("system_prompt") != SYSTEM_PROMPT
            or header.get("prompt_sha256") != hashlib.sha256(SYSTEM_PROMPT.encode()).hexdigest()
            or any(header.get("parameters", {}).get(key) != value for key, value in PARAMETERS.items())
            or header.get("native_limits_expected", {}).get("image_max_tokens") != 64
            or terminal.get("record") != "terminal" or terminal.get("complete") is not True
            or terminal.get("completed_cases") != len(cases)):
        raise ValueError("The journal does not match the completed frozen run")
    grouped = {}
    for number, (case, row) in enumerate(zip(cases, rows, strict=True), 1):
        if (row.get("record") != "case" or row.get("number") != number or any(
                row.get(key) != case[key] for key in ("query_id", "query", "image_id", "source_sha256"))):
            raise ValueError("The journal contains an unexpected or missing candidate")
        elapsed = row.get("request_elapsed_ms")
        if isinstance(elapsed, bool) or not isinstance(elapsed, (int, float)) or not math.isfinite(elapsed) or elapsed < 0:
            raise ValueError("Invalid request timing")
        if row.get("request_status") == "completed" and isinstance(row.get("answer"), str) and row.get("http_status") == 200:
            try:
                answer = json.loads(row["raw_response"])["choices"][0]["message"]["content"]
            except (ValueError, KeyError, IndexError, TypeError) as error:
                raise ValueError("Invalid saved server response") from error
            if answer != row["answer"]:
                raise ValueError("Recorded answer disagrees with the saved server response")
            decision = parse_decision(answer)
        elif row.get("request_status") == "failed" and row.get("answer") is None:
            decision = "failed"
        else:
            raise ValueError("Invalid request outcome")
        if row.get("decision") != decision:
            raise ValueError("Recorded decision disagrees with the response")
        grouped.setdefault(case["query_id"], []).append(row)

    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    review = json.loads(judgments_path.read_text(encoding="utf-8"))
    rankings = {row["query"]: row["ranking"] for row in baseline["queries"]}
    results = []
    for query in review["queries"]:
        responses = grouped[query["id"]]
        results.append({"id": query["id"], "query": query["query"], "type": query["type"], **candidate_metrics(
            rankings[query["query"]][:8], {row["id"]: row["judgment"] for row in query["candidates"]},
            {row["image_id"]: row["decision"] for row in responses}),
            "request_ms_total": sum(row["request_elapsed_ms"] for row in responses)})
    ordinary = [row for row in results if row["type"] == "natural_relationship_pilot"]
    probes = [row for row in results if row["type"] == "candidate_pool_absence_probe"
              and row["candidate_relevant_count"] == row["candidate_unjudgeable_count"] == 0]

    def ranking_summary(method):
        return {"queries": len(ordinary), "top1_relevant": sum(row[method]["top1"] == "relevant" for row in ordinary),
                "top1_unjudgeable": sum(row[method]["top1"] == "unjudgeable" for row in ordinary),
                "top5_relevant_query_hits": sum(row[method]["top5_relevant"] > 0 for row in ordinary),
                **{key: sum(row[method][key] for row in ordinary) for key in (
                    "top5_relevant", "top5_irrelevant", "top5_unjudgeable")}}

    return {**hashes, "scope": "Provisional visual judgments of equal top-eight pools", "baseline": ranking_summary("baseline"),
            "reranked": ranking_summary("reranked"), "fully_irrelevant_probe_ids": [row["id"] for row in probes],
            "probes_with_explicit_false_support": sum(row["supported_irrelevant_count"] > 0 for row in probes) if probes else None,
            "decision_counts": dict(Counter(row["decision"] for row in rows)),
            "explicit_support_on_irrelevant": sum(row["supported_irrelevant_count"] for row in results),
            "explicit_support_on_unjudgeable": sum(row["supported_unjudgeable_count"] for row in results),
            "median_request_ms": median(row["request_elapsed_ms"] for row in rows),
            "median_eight_request_ms": median(row["request_ms_total"] for row in results), "queries": results}


def main():
    parser = argparse.ArgumentParser()
    for name in ("corpus", "baseline", "judgments", "journal", "output"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    report = compare(args.corpus, args.baseline, args.judgments, args.journal)
    with args.output.open("x", encoding="utf-8") as destination:
        json.dump(report, destination, indent=2, ensure_ascii=False)
        destination.write("\n")


if __name__ == "__main__":
    main()
