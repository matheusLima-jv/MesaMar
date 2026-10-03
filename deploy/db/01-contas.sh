#!/usr/bin/env bash
set -euo pipefail
# Executado pelo administrador apenas no primeiro boot de um volume novo.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv runtime_user DB_USER
\getenv runtime_password DB_PASSWORD
\getenv migration_user DB_MIGRATION_USER
\getenv migration_password DB_MIGRATION_PASSWORD
CREATE ROLE :"migration_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'migration_password';
CREATE ROLE :"runtime_user" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'runtime_password';
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE, CREATE ON SCHEMA public TO :"migration_user";
GRANT USAGE ON SCHEMA public TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_user";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_user" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"runtime_user";
SQL
