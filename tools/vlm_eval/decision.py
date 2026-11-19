import re


def parse_decision(output):
    cleaned = re.sub(r"\x1b\[[0-?]*[ -/]*[@-~]", "", output).strip()
    match = re.fullmatch(r"(yes|no|uncertain)[.!]?", cleaned, re.IGNORECASE)
    return match.group(1).lower() if match else "malformed"


def ranked_candidates(candidates, decisions):
    ids = [row["id"] for row in candidates]
    if len(ids) != len(set(ids)) or set(ids) != set(decisions):
        raise ValueError("Need exactly one decision for each unique candidate")
    tiers = {"yes": 0, "uncertain": 1, "malformed": 1, "no": 2, "failed": 1}
    if any(value not in tiers for value in decisions.values()):
        raise ValueError("Unknown decision")
    return sorted(candidates, key=lambda row: tiers[decisions[row["id"]]])


def candidate_metrics(candidates, judgments, decisions):
    ids = [row["id"] for row in candidates]
    if set(ids) != set(judgments) or any(value not in {"relevant", "irrelevant", "unjudgeable"} for value in judgments.values()):
        raise ValueError("Need explicit visual judgments for the exact candidate pool")
    reranked = ranked_candidates(candidates, decisions)

    def measure(rows):
        top = rows[:5]
        relevant = sum(judgments[row["id"]] == "relevant" for row in top)
        irrelevant = sum(judgments[row["id"]] == "irrelevant" for row in top)
        return {
            "top1": judgments[rows[0]["id"]] if rows else None,
            "top5_relevant": relevant,
            "top5_irrelevant": irrelevant,
            "top5_unjudgeable": len(top) - relevant - irrelevant,
            "judged_top5_precision": relevant / (relevant + irrelevant) if relevant + irrelevant else None,
        }

    yes = [key for key in ids if decisions[key] == "yes"]
    return {
        "baseline": measure(candidates),
        "reranked": measure(reranked),
        "candidate_relevant_count": sum(value == "relevant" for value in judgments.values()),
        "candidate_unjudgeable_count": sum(value == "unjudgeable" for value in judgments.values()),
        "supported_count": len(yes),
        "supported_irrelevant_count": sum(judgments[key] == "irrelevant" for key in yes),
        "supported_unjudgeable_count": sum(judgments[key] == "unjudgeable" for key in yes),
        "abstentions": sum(value in {"uncertain", "malformed", "failed"} for value in decisions.values()),
        "scope": "Fixed reviewed candidate pool; not full-gallery recall or identity confirmation",
    }
