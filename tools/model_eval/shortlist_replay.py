import argparse
import json
from pathlib import Path

from tools.model_eval.core import load_manifest, sha256_file


def load_rankings(path, route):
    rankings = {}
    with path.open(encoding="utf-8") as source:
        for line in source:
            row = json.loads(line)
            query_id = row["query_id"]
            ranked = row["routes"][route]
            if query_id in rankings or len(set(ranked)) != len(ranked):
                raise ValueError("Duplicate query or image in candidate export")
            rankings[query_id] = ranked
    return rankings


def replay(manifest, primary, reference, depths):
    queries = {row["query_id"]: set(row["positive"]) for row in manifest["queries"]}
    if set(primary) != queries.keys() or set(reference) != queries.keys():
        raise ValueError("Ranking exports must cover all manifest queries")
    gallery = {row["id"] for row in manifest["images"]}
    if any(not targets for targets in queries.values()):
        raise ValueError("This shortlist replay requires a known positive for every query")
    for rankings, minimum in ((primary, max(depths)), (reference, 5)):
        if any(len(row) < minimum or not set(row) <= gallery for row in rankings.values()):
            raise ValueError("Ranking export has insufficient depth or unknown image IDs")
    metrics = {}
    for depth in depths:
        counts = dict(available=0, rerankedHit1=0, rerankedHit5=0, gainedTop5=0, lostTop5=0)
        for query_id, positives in queries.items():
            shortlist = primary[query_id][:depth]
            shortlisted = set(shortlist)
            known_order = [image for image in reference[query_id] if image in shortlisted]
            ordered_ids = set(known_order)
            reranked = known_order + [image for image in shortlist if image not in ordered_ids]
            initial_hit = bool(positives.intersection(shortlist[:5]))
            reranked_hit = bool(positives.intersection(reranked[:5]))
            counts["available"] += bool(positives.intersection(shortlisted))
            counts["rerankedHit1"] += reranked[0] in positives
            counts["rerankedHit5"] += reranked_hit
            counts["gainedTop5"] += reranked_hit and not initial_hit
            counts["lostTop5"] += initial_hit and not reranked_hit
        metrics[depth] = counts
    return metrics


def main():
    parser = argparse.ArgumentParser(description="Replay a stronger encoder's order within fixed retrieval shortlists.")
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--manifest-sha256", required=True)
    parser.add_argument("--primary", required=True, type=Path)
    parser.add_argument("--primary-route", required=True)
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--reference-route", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    manifest, digest, _ = load_manifest(args.manifest, args.manifest_sha256)
    hashes = {path: sha256_file(path) for path in (args.primary, args.reference)}
    for path, file_hash in hashes.items():
        receipt = json.loads(path.with_name("results.json").read_text(encoding="utf-8"))
        if not receipt["complete"] or receipt["manifest_sha256"] != digest:
            raise ValueError(f"Candidate export comes from a different run: {path}")
        if "candidate_sha256" in receipt and receipt["candidate_sha256"] != file_hash:
            raise ValueError(f"Candidate export hash does not match its run: {path}")
    primary = load_rankings(args.primary, args.primary_route)
    reference = load_rankings(args.reference, args.reference_route)
    metrics = replay(manifest, primary, reference, (5, 10, 25, 50, 100))
    with args.output.open("x", encoding="utf-8") as output:
        json.dump({
            "manifestSha256": digest, "queries": len(manifest["queries"]),
            "primary": {"route": args.primary_route, "sha256": hashes[args.primary]},
            "reference": {"route": args.reference_route, "sha256": hashes[args.reference]},
            "countsByShortlistDepth": metrics,
            "scope": "Offline order replay. Shortlisted items absent from the reference export retain primary order after known items.",
        }, output, indent=2)
        output.write("\n")
    print(json.dumps(metrics))


if __name__ == "__main__":
    main()
