import hashlib
import itertools
import json
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PATH = ROOT / ".benchmark-data/gallery-v1/manifest.json"


def main():
    manifest = json.loads(PATH.read_text(encoding="utf-8"))
    images = manifest["images"]
    parent = {image["id"]: image["id"] for image in images}
    def group(value):
        while parent[value] != value:
            parent[value] = parent[parent[value]]
            value = parent[value]
        return value
    seen = {}
    for image in images:
        for key in (image["sha256"], image["source"]):
            if key in seen:
                parent[group(image["id"])] = group(seen[key])
            else:
                seen[key] = image["id"]
    for image in images:
        image["group_id"] = group(image["id"])
        digest = hashlib.sha256(("gallery-v1/" + image["group_id"]).encode()).hexdigest()
        image["partition"] = "validation" if int(digest[:8], 16) % 5 == 0 else "test"
    names = manifest["class_names"]
    combinations = Counter()
    frequency = Counter(label for image in images for label in image["positive_labels"])
    broad = {"Person", "Man", "Woman", "Boy", "Girl", "Human face", "Human body"}
    for image in images:
        eligible = sorted(label for label in image["positive_labels"] if label in names)
        combinations.update(itertools.combinations(eligible, 2))
        combinations.update(itertools.combinations(eligible, 3))
    compounds = []
    for labels, count in sorted(combinations.items(), key=lambda item: (-len(item[0]), -item[1], item[0])):
        if count < 4 or sum(names[label] in broad for label in labels) > 1:
            continue
        if any(count >= .95 * min(frequency[a], frequency[b]) for a, b in itertools.combinations(labels, 2)):
            continue
        positive = [image["id"] for image in images if set(labels) <= set(image["positive_labels"])]
        negative = [image["id"] for image in images if set(labels) & set(image["negative_labels"])]
        if len(negative) < 8:
            continue
        compounds.append({"query": "a photo containing " + " and ".join(names[label] for label in labels),
            "labels": labels, "positive": positive, "explicit_negative": negative,
            "type": "human_verified_category_conjunction", "predicate_count": len(labels),
            "unjudged": "Any image without all positive judgments or an explicit negative is unknown"})
        if len(compounds) == 20:
            break
    relations = defaultdict(set)
    for image in images:
        for row in image["relationships"]:
            first, relation, second = row["LabelName1"], row["RelationshipLabel"], row["LabelName2"]
            if first in names and second in names:
                relations[(names[first], relation, names[second])].add(image["id"])
    relational = [{"query": "a photo of " + first + " " + relation + " " + second,
        "positive": sorted(ids), "explicit_negative": [], "type": "official_visual_relationship_positive_only",
        "unjudged": "Relationship annotation absence is unknown; false alert accuracy not estimable without reviewed negatives"}
        for (first, relation, second), ids in sorted(relations.items(), key=lambda item: (-len(item[1]), item[0]))[:20]]
    manifest["queries"] = manifest["queries"][:20] + compounds + relational
    manifest["duplicate_group_count"] = sum(count > 1 for count in Counter(image["group_id"] for image in images).values())
    manifest["images_without_verified_labels"] = sum(not image["positive_labels"] and not image["negative_labels"] for image in images)
    manifest["query_ground_truth"] = "Category and category conjunctions use positive/explicit-negative judgments; relationship queries positive-only. No ownership/person identity or GPS inferred."
    PATH.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"images": len(images), "queries": len(manifest["queries"]), "conjunctions": len(compounds),
        "relations": len(relational), "duplicate_groups": manifest["duplicate_group_count"], "sha256": hashlib.sha256(PATH.read_bytes()).hexdigest()}))


if __name__ == "__main__":
    main()
