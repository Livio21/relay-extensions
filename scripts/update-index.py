#!/usr/bin/env python3
"""Update catalog artifacts after a CI release."""
import argparse
import hashlib
import json
from pathlib import Path


parser = argparse.ArgumentParser()
parser.add_argument("--version", required=True)
parser.add_argument("--release-tag", required=True)
parser.add_argument("--repository", required=True)
parser.add_argument("--certificate", required=True)
parser.add_argument("--artifact", action="append", nargs=2, metavar=("ID", "PATH"), required=True)
args = parser.parse_args()

# Metadata for sources that are new in this release; the first CI publish adds them.
NEW_EXTENSIONS = {
    "org.relay.extensions.jamendo": {
        "name": "Jamendo",
        "kind": "SOURCE",
        "api": {"minimum": 2, "maximum": 2},
        "permissions": ["NETWORK"],
        "androidPackageName": "org.relay.extensions.jamendo",
    },
}

index_path = Path("index.json")
catalog = json.loads(index_path.read_text())
artifacts = {extension_id: Path(path) for extension_id, path in args.artifact}

by_id = {entry["id"]: entry for entry in catalog["extensions"]}
for extension_id in artifacts:
    if extension_id in by_id:
        continue
    metadata = NEW_EXTENSIONS.get(extension_id)
    if metadata is None:
        raise SystemExit(f"No catalog entry or metadata for {extension_id}")
    entry = {"id": extension_id, **metadata}
    catalog["extensions"].append(entry)
    by_id[extension_id] = entry

for entry in catalog["extensions"]:
    artifact = artifacts.get(entry["id"])
    if artifact is None:
        raise SystemExit(f"Missing artifact mapping for {entry['id']}")
    if not artifact.is_file():
        raise SystemExit(f"Missing APK: {artifact}")
    digest = hashlib.sha256(artifact.read_bytes()).hexdigest()
    entry["version"] = args.version
    entry["artifactUrl"] = (
        f"https://github.com/{args.repository}/releases/download/{args.release_tag}/{artifact.name}"
    )
    entry["sha256"] = digest
    entry["artifactSizeBytes"] = artifact.stat().st_size
    entry["androidSigningCertificateSha256"] = args.certificate.lower().replace(":", "")

index_path.write_text(json.dumps(catalog, indent=2) + "\n")
