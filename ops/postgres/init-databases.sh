#!/bin/bash
set -e
set -u

# Creates every database the stack needs, owned by $POSTGRES_USER.
# Runs once, on the first start of an empty postgres volume.
#
#   am_user_db     — user-api
#   am_control_db  — control-api
#   dbos           — DBOS system database, shared by control-api (producer)
#                    and agent-worker (consumer); both must point at this one.

for db in am_user_db am_control_db dbos; do
  echo "  creating database '$db'"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    CREATE DATABASE $db;
    GRANT ALL PRIVILEGES ON DATABASE $db TO $POSTGRES_USER;
EOSQL
done
