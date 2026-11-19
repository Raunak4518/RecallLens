import argparse
from collections import defaultdict
import hashlib
import json
import os
from pathlib import Path

from tools.prepare_docci_hard_manifest import (
    DOCCI_HOME, DOCCI_IMAGES, IMAGE_LICENSE, example_filename,
    extract_selected_images, file_digest, output_filename, sha256_file, write_json_atomic,
    DOCCI_DESCRIPTION_ETAG, DOCCI_METADATA_ETAG,
)


def json_rows(path):
    with path.open(encoding="utf-8") as stream:
        for line in stream:
            yield json.loads(line)


def stable_order(value):
    return hashlib.sha256(value.encode("utf-8")).digest(), value


def load_groups(descriptions, metadata, excluded):
    clusters = {}
    for row in json_rows(metadata):
        identifier = row["example_id"]
        if identifier in clusters:
            raise ValueError(f"Duplicate DOCCI metadata for {identifier}")
        clusters[identifier] = str(row["cluster_id"])
    groups = defaultdict(lambda: defaultdict(list))
    identifiers = set()
    filenames = set()
    for row in json_rows(descriptions):
        identifier = row["example_id"]
        filename = example_filename(row)
        if identifier in identifiers or filename.casefold() in filenames:
            raise ValueError(f"Duplicate DOCCI description or filename for {identifier}")
        identifiers.add(identifier)
        filenames.add(filename.casefold())
        if row["split"] not in ("train", "qual_dev", "qual_test") or filename.casefold() in excluded:
            continue
        cluster = clusters[identifier]
        if not cluster.isdecimal() or not row["description"].strip():
            raise ValueError(f"Missing cluster or caption for {identifier}")
        groups[row["split"]][cluster].append({
            "id": identifier, "cluster": cluster, "sourceFilename": filename,
            "caption": row["description"],
        })
    return groups


def select_rows(groups, limits):
    used = set()
    selected = {}
    for split in ("qual_test", "qual_dev", "train"):
        rows = []
        pending = sorted(set(groups[split]) - used, key=lambda cluster: stable_order(f"{split}:{cluster}"))
        if sum(len(groups[split][cluster]) for cluster in pending) < limits[split]:
            raise ValueError(f"Insufficient independent DOCCI rows for {split}")
        while len(rows) < limits[split]:
            for cluster in pending:
                members = groups[split][cluster]
                if members:
                    rows.append(members.pop())
                    used.add(cluster)
                    if len(rows) == limits[split]:
                        break
        selected[split] = rows
    return selected


def reuse_images(dataset_path, selected, image_dir, source_hashes):
    prior = json.loads(dataset_path.read_bytes())
    if prior["imagesAcquired"] is not True or prior["sourceSha256"] != source_hashes:
        raise ValueError("Reusable images must come from a verified copy of the same source")
    rows = {row["sourceFilename"]: row for split in prior["splits"].values() for row in split}
    image_dir.mkdir(parents=True, exist_ok=True)
    reused = 0
    for filename in sorted(set(selected).intersection(rows)):
        row = rows[filename]
        basename = output_filename(filename)
        if row["file"] != f"images/{basename}":
            raise ValueError("Unexpected path in reusable image receipt")
        source = dataset_path.parent / "images" / basename
        target = image_dir / basename
        if source.stat().st_size != row["bytes"] or sha256_file(source) != row["sha256"]:
            raise ValueError(f"Reusable image changed: {filename}")
        if target.exists():
            if sha256_file(target) != row["sha256"]:
                raise ValueError(f"Existing target differs from reusable image: {filename}")
        else:
            os.link(source, target)
        reused += 1
    return reused


def attach_images(selected, regression, output, max_bytes, reuse_dataset, source_hashes):
    requested = {row["sourceFilename"]: split for split, rows in selected.items() for row in rows}
    reused = reuse_images(reuse_dataset, requested, output / "images", source_hashes) if reuse_dataset else 0
    receipt = extract_selected_images(DOCCI_IMAGES, requested, output / "images", max_total_bytes=max_bytes)
    receipt["reusedFromVerifiedDataset"] = reused
    known_hashes = {row["sha256"] for row in regression["images"]}
    for rows in selected.values():
        for row in rows:
            filename = row["sourceFilename"]
            image = receipt["images"][filename]
            if image["sha256"] in known_hashes:
                raise ValueError(f"Byte duplicate across selected or regression images: {filename}")
            known_hashes.add(image["sha256"])
            row.update(file=f"images/{output_filename(filename)}", sha256=image["sha256"], bytes=image["bytes"])
    return receipt


def main():
    parser = argparse.ArgumentParser(description="Select cluster-disjoint DOCCI rows for a reranking experiment.")
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--regression", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--train", default=800, type=int)
    parser.add_argument("--dev", default=40, type=int)
    parser.add_argument("--test", default=40, type=int)
    parser.add_argument("--acquire-images", action="store_true")
    parser.add_argument("--reuse-dataset", type=Path)
    parser.add_argument("--max-image-storage-mib", default=1024, type=int)
    args = parser.parse_args()
    limits = {"train": args.train, "qual_dev": args.dev, "qual_test": args.test}
    if min(*limits.values(), args.max_image_storage_mib) < 1:
        parser.error("Split sizes and storage limit must be positive")
    regression = json.loads(args.regression.read_bytes())
    excluded = {row["source_filename"].casefold() for row in regression["images"] if row.get("dataset") == "DOCCI"}
    excluded.update(f"{row['source_example_id'].casefold()}.jpg" for row in regression["queries"])
    descriptions = args.source / "docci_descriptions.jsonlines"
    metadata = args.source / "docci_metadata.jsonlines"
    for path, digest in ((descriptions, DOCCI_DESCRIPTION_ETAG), (metadata, DOCCI_METADATA_ETAG)):
        if file_digest(path, "md5") != digest:
            raise ValueError(f"Publisher checksum mismatch for {path}")
    groups = load_groups(descriptions, metadata, excluded)
    for clusters in groups.values():
        for members in clusters.values():
            members.sort(key=lambda row: stable_order(row["id"]), reverse=True)
    selected = select_rows(groups, limits)
    source_hashes = {"descriptions": sha256_file(descriptions), "metadata": sha256_file(metadata)}
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / ("dataset.json" if args.acquire_images else "selection.json")
    if target.exists():
        raise FileExistsError(target)
    receipt = attach_images(selected, regression, args.output, args.max_image_storage_mib * 1024**2,
                            args.reuse_dataset, source_hashes) if args.acquire_images else None
    counts = {split: {"examples": len(rows), "clusters": len({row["cluster"] for row in rows})}
              for split, rows in selected.items()}
    write_json_atomic(target, {
        "version": 1, "publisher": "Google LLC", "source": DOCCI_HOME, "license": IMAGE_LICENSE,
        "regressionManifestSha256": sha256_file(args.regression), "exclusions": len(excluded),
        "sourceSha256": source_hashes,
        "imagesAcquired": args.acquire_images, "counts": counts, "splits": selected, "acquisition": receipt,
    })
    print(json.dumps(counts))


if __name__ == "__main__":
    main()
