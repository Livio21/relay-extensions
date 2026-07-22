#!/bin/sh
set -eu

test -f keys/repository-private.pem || { printf 'Run scripts/create-signing-key.sh first.\n' >&2; exit 1; }
temporary=$(mktemp)
trap 'rm -f "$temporary"' EXIT
openssl dgst -sha256 -sign keys/repository-private.pem -out "$temporary" index.json
base64 < "$temporary" | tr -d '\n' > index.json.sig
printf 'Wrote index.json.sig.\n'

