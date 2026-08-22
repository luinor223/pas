#!/usr/bin/env bash
# Regenerates the RS256 JWT signing keypair.
# Private key: signs access tokens in identity-service (mounted, never committed).
# Public key: validates them at the Traefik edge (committed, and inlined into the
# Traefik dynamic config — update infra/docker/traefik/dynamic/jwt.yml after running).
set -euo pipefail
cd "$(dirname "$0")"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
openssl pkey -in jwt-private.pem -pubout -out jwt-public.pem
echo "wrote jwt-private.pem (gitignored) and jwt-public.pem"
