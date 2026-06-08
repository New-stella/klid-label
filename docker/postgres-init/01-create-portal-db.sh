#!/bin/bash
# postgres 컨테이너 최초 기동 시 1회 실행 (initdb).
# control DB(POSTGRES_DB)는 엔트리포인트가 생성. portal DataSource 연결용 portal DB 를 추가 생성한다.
# portal 에는 엔티티/마이그레이션이 매핑되지 않으므로 빈 DB 로 충분(연결 가능성만 필요).
set -e

PORTAL_DB="${PORTAL_DB_NAME:-portal}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SELECT 'CREATE DATABASE ${PORTAL_DB}'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '${PORTAL_DB}')\gexec
EOSQL

echo "[init] ensured portal database: ${PORTAL_DB}"
