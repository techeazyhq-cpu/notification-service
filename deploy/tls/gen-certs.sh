#!/bin/sh
# Copyright 2026 Vasantha Kumar
# Licensed under the Apache License, Version 2.0 (see LICENSE).
# @author Vasantha Kumar <vasantha.kumar@hotmail.com>
#
# One-shot: creates a private CA and a server certificate for each datastore (PostgreSQL, Redis, Pulsar) so that
# every hop between the services and their datastores can be TLS with full certificate and hostname verification.
# Idempotent: does nothing if the CA already exists in the volume. For production, replace these files with
# certificates from your own PKI (same paths) and keep the CA key out of the volume.
set -eu
OUT=/certs
[ -f "$OUT/ca/ca.crt" ] && { echo "certificates already present"; exit 0; }

mkdir -p "$OUT/ca" "$OUT/postgres" "$OUT/redis" "$OUT/pulsar"
openssl req -x509 -newkey rsa:4096 -nodes -days 825 -subj "/CN=notification-service internal CA" \
  -keyout "$OUT/ca/ca.key" -out "$OUT/ca/ca.crt"

issue() {
  name=$1
  openssl req -newkey rsa:2048 -nodes -subj "/CN=$name" -keyout "$OUT/$name/$name.key" -out "$OUT/$name/$name.csr"
  printf 'subjectAltName=DNS:%s,DNS:localhost\nextendedKeyUsage=serverAuth\n' "$name" > "$OUT/$name/ext.cnf"
  openssl x509 -req -in "$OUT/$name/$name.csr" -CA "$OUT/ca/ca.crt" -CAkey "$OUT/ca/ca.key" -CAcreateserial \
    -days 825 -extfile "$OUT/$name/ext.cnf" -out "$OUT/$name/$name.crt"
  cp "$OUT/ca/ca.crt" "$OUT/$name/ca.crt"
  rm -f "$OUT/$name/$name.csr" "$OUT/$name/ext.cnf"
}
issue postgres
issue redis
issue pulsar
openssl pkcs8 -topk8 -nocrypt -in "$OUT/pulsar/pulsar.key" -out "$OUT/pulsar/pulsar.key.pk8"

printf 'local all all trust\nhostssl all all all scram-sha-256\n' > "$OUT/postgres/pg_hba.conf"

rm -f "$OUT/ca/ca.key" "$OUT/ca/ca.srl"
chmod 644 "$OUT"/*/*.crt "$OUT"/postgres/pg_hba.conf
chmod 644 "$OUT"/redis/redis.key "$OUT"/pulsar/pulsar.key.pk8
chmod 600 "$OUT"/postgres/postgres.key
echo "certificates created"
