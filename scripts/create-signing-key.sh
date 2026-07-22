#!/bin/sh
set -eu

umask 077
mkdir -p keys
test ! -e keys/repository-private.pem || { printf 'Repository signing key already exists.\n' >&2; exit 1; }
openssl ecparam -name prime256v1 -genkey -noout -out keys/repository-private.pem
openssl ec -in keys/repository-private.pem -pubout -outform DER | base64 | tr -d '\n' > keys/repository-public-base64.txt
printf 'Created repository signing key. Back it up securely; never commit it.\n'

