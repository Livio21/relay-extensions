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

index_path = Path("index.json")
catalog = json.loads(index_path.read_text())
artifacts = {extension_id: Path(path) for extension_id, path in args.artifact}
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
