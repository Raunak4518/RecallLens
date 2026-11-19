import argparse
import json
import math
from collections import defaultdict
from pathlib import Path
from statistics import fmean


def load_rows(path):
    rows = {}
    with path.open(encoding="utf-8-sig") as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                query_id = row["query_id"]
                if not isinstance(query_id, str) or not query_id.strip() or query_id in rows:
                    raise ValueError("query_id must be a unique nonempty string")
                rows[query_id] = row
            except (TypeError, ValueError, KeyError) as error:
                raise ValueError(f"{path}:{number}: {error}") from error
    if not rows:
        raise ValueError(f"{path}: no query records")
    return rows


def ids(value):
    if not isinstance(value, list) or any(not isinstance(item, str) or not item for item in value):
        raise ValueError("Each route requires a ranked array of nonempty photo IDs")
    return list(dict.fromkeys(value))


def rank(routes, selected, tiers, depth):
    if not isinstance(routes, dict):
        raise ValueError("routes must be an object keyed by route ID")
    active = {route: ids(items)[:depth] for route, items in routes.items() if route in selected}
    priority = [item for route in tiers for item in active.get(route, [])]
    scores = defaultdict(float)
    for route, items in active.items():
        if route not in tiers:
            for position, item in enumerate(items, 1):
                scores[item] += 1 / (60 + position)
    return list(dict.fromkeys(priority + sorted(scores, key=scores.get, reverse=True)))


def quality(ranked, evidence, k):
    grades = evidence.get("grades")
    if not isinstance(grades, dict) or any(type(g) is not int or not 0 <= g <= 3 for g in grades.values()):
        raise ValueError("grades must map photo IDs to integer relevance from zero through three")
    if not isinstance(evidence.get("absent", False), bool):
        raise ValueError("absent must be a boolean")
    positives = {item for item, grade in grades.items() if grade > 0}
    absent = evidence.get("absent", False)
    if absent and positives:
        raise ValueError("an absent-target query cannot have positive judgments")
    top = ranked[:k]
    observed = {item for item in top if item in grades}
    hits = positives.intersection(top)
    metrics = {
        "judgment_coverage": len(observed) / len(top) if top else None,
        "precision_among_judged": len(hits) / len(observed) if observed else None,
        "unjudged_placements": len(top) - len(observed),
        "returned": len(top),
        "any_result_on_absent_query": int(bool(top)) if absent else None,
    }
    if positives:
        def dcg(values):
            return sum((2**value - 1) / math.log2(index + 2) for index, value in enumerate(values))
        metrics.update({
            "hit_at_1": int(bool(top) and top[0] in positives),
            "hit_at_5": int(bool(positives.intersection(ranked[:5]))),
            "reciprocal_rank_at_k": next((1 / pos for pos, item in enumerate(top, 1) if item in positives), 0.0),
            "known_positive_recall_at_k": len(hits) / len(positives),
            "candidate_known_positive_recall": len(positives.intersection(ranked)) / len(positives),
            "ndcg_at_k_unjudged_zero": dcg(grades.get(item, 0) for item in top) / dcg(sorted(grades.values(), reverse=True)[:k]),
        })
    return metrics


def macro(rows):
    values = defaultdict(list)
    for row in rows:
        for metric, value in row.items():
            if value is not None:
                values[metric].append(value)
    return {metric: {"mean": fmean(items), "queries": len(items)} for metric, items in sorted(values.items())}


def replay(candidates, judgments, selected, tiers, k, depth, include_rows):
    results = {}
    families = defaultdict(list)
    for query_id, evidence in judgments.items():
        try:
            ranked = rank(candidates[query_id]["routes"], selected, tiers, depth)
            metrics = quality(ranked, evidence, k)
            families[str(evidence.get("family", "unspecified"))].append(metrics)
            results[query_id] = metrics
        except (KeyError, TypeError, ValueError) as error:
            raise ValueError(f"query {query_id}: {error}") from error
    report = {
        "routes": sorted(selected),
        "priority_routes": [route for route in tiers if route in selected],
        "overall": macro(results.values()),
        "by_family": {family: macro(rows) for family, rows in sorted(families.items())},
    }
    if include_rows:
        report["by_query"] = results
    return report


def main():
    parser = argparse.ArgumentParser(description="Replay local photo candidate routes against independently judged photo IDs.")
    parser.add_argument("candidates", type=Path, help='JSONL with query_id and routes such as {"visual":["p1"],"lexical":["p2"]}')
    parser.add_argument("judgments", type=Path, help='JSONL with query_id, family, absent and grades such as {"p1":3,"p2":0}')
    parser.add_argument("--routes", nargs="+", help="Enabled candidate routes, all by default")
    parser.add_argument("--priority", nargs="*", default=["confirmed", "remembered_text"], help="Ordered exact tiers preceding reciprocal-rank fusion")
    parser.add_argument("--k", type=int, default=5)
    parser.add_argument("--depth", type=int, default=100, help="Maximum candidates retained per route")
    parser.add_argument("--ablate", action="store_true", help="Also rerun after dropping each selected route")
    parser.add_argument("--by-query", action="store_true")
    args = parser.parse_args()
    try:
        if min(args.k, args.depth) < 1:
            raise ValueError("k and depth must be positive")
        candidates, judgments = load_rows(args.candidates), load_rows(args.judgments)
        if candidates.keys() != judgments.keys():
            raise ValueError("candidate and judgment query IDs differ")
        available = set().union(*(row["routes"].keys() for row in candidates.values()))
        selected = set(args.routes) if args.routes else available
        if not selected or selected - available:
            raise ValueError("requested routes missing from the input manifest")
        def run(routes):
            return replay(candidates, judgments, routes, args.priority, args.k, args.depth, args.by_query)
        report = {"cutoff": args.k, "per_route_depth": args.depth, "combined": run(selected)}
        if args.ablate:
            report["without_route"] = {route: run(selected - {route}) for route in sorted(selected)}
        print(json.dumps(report, indent=2, allow_nan=False))
    except (OSError, TypeError, ValueError, AttributeError, KeyError) as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
