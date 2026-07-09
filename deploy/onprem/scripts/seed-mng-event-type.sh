#!/usr/bin/env bash
# =============================================================================
# seed-mng-event-type.sh
#   관제(MariaDB/MySQL) klid_system 의 이벤트 타입 마스터를
#   온프렘 저작도구(PostgreSQL) 로 추출·적재한다.
#
# 대상 테이블 (2종, 이벤트 타입 진실원 SoT):
#   - MNG_EX_EVNT_TYPE       (이벤트 타입 마스터: EV-코드 53종)
#   - MNG_EX_EVNT_TYPE_MAP   (코드→한글명 계층 사전: 대분류/카테고리/상세)
#
# 언제 쓰나:
#   온프렘 PostgreSQL 이 "관제 공유 DB(klid_system)" 가 아니라 "독립 DB" 일 때.
#   독립 DB 면 위 두 테이블이 비어 있어 화면 이벤트 타입 필터/라벨이 안 뜬다.
#   (공유 klid_system 에 직접 붙는 배포면 이미 적재돼 있으므로 이 스크립트 불필요.)
#
# 전제:
#   1) 대상 PostgreSQL 에 두 테이블이 이미 생성돼 있어야 한다.
#      → 백엔드를 1회 기동(Flyway V2+V71 적용)했거나, 마이그레이션을 먼저 돌린 상태.
#   2) 빌드/관리 머신에 mysql(또는 mariadb) 클라이언트 + psql 클라이언트가 설치돼 있어야 한다.
#      - RHEL/Rocky:  dnf install -y mysql postgresql
#   3) 두 DB 에 네트워크로 접근 가능해야 한다.
#
# 특징:
#   - 멱등(ON CONFLICT DO NOTHING) — 여러 번 돌려도 안전(기존 행 보존).
#   - 단일 트랜잭션 적용 — 중간 실패 시 전체 롤백.
#   - 한글 키워드(콤마·괄호 포함) 안전(값에 작은따옴표/백슬래시 없음 확인됨).
#
# 사용:
#   1) 아래 CONFIG 블록의 접속 정보를 환경에 맞게 수정.
#   2) ./seed-mng-event-type.sh           # 적재
#      DRY_RUN=1 ./seed-mng-event-type.sh # 생성될 INSERT 만 출력(적재 안 함)
# =============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# CONFIG — 환경에 맞게 수정하세요 (환경변수로 덮어쓸 수도 있음)
# ----------------------------------------------------------------------------
# [원본] 관제 MariaDB/MySQL (klid_system)
SRC_HOST="${SRC_HOST:-192.168.102.102}"
SRC_PORT="${SRC_PORT:-13307}"
SRC_USER="${SRC_USER:-klid_user}"
SRC_PASS="${SRC_PASS:-CHANGE_ME}"
SRC_DB="${SRC_DB:-klid_system}"

# [대상] 온프렘 저작도구 PostgreSQL
PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_USER="${PG_USER:-klid}"
PG_PASS="${PG_PASS:-CHANGE_ME}"
PG_DB="${PG_DB:-klid_system}"
PG_SCHEMA="${PG_SCHEMA:-public}"     # V71 이 public 스키마에 생성함

DRY_RUN="${DRY_RUN:-0}"
# ----------------------------------------------------------------------------

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
OUT_SQL="${WORK}/seed-mng-event-type.generated.sql"

log()  { printf '\033[36m[seed]\033[0m %s\n' "$*"; }
err()  { printf '\033[31m[seed][ERR]\033[0m %s\n' "$*" >&2; }
die()  { err "$*"; exit 1; }

command -v mysql >/dev/null 2>&1 || command -v mariadb >/dev/null 2>&1 \
  || die "mysql/mariadb 클라이언트가 없습니다. (dnf install -y mysql)"
command -v psql  >/dev/null 2>&1 || die "psql 클라이언트가 없습니다. (dnf install -y postgresql)"
MYSQL_BIN="$(command -v mysql || command -v mariadb)"

[[ "${SRC_PASS}" != "CHANGE_ME" ]] || die "SRC_PASS 를 설정하세요(CONFIG 또는 환경변수)."
[[ "${PG_PASS}"  != "CHANGE_ME" ]] || die "PG_PASS 를 설정하세요(CONFIG 또는 환경변수)."

# ----------------------------------------------------------------------------
# 1) 원본(MariaDB)에서 PostgreSQL 용 INSERT 문 생성
#    - CHAR(39)=작은따옴표 로 값을 감싸 셸/SQL 따옴표 충돌 회피.
#    - -N -B : 헤더/박스 없는 raw 출력(행수 제한 없음).
#    - 값에 작은따옴표/백슬래시가 없음을 사전 확인했으므로 단순 단일따옴표 래핑이 PG-안전.
# ----------------------------------------------------------------------------
mysql_raw() {
  MYSQL_PWD="${SRC_PASS}" "${MYSQL_BIN}" \
    -h "${SRC_HOST}" -P "${SRC_PORT}" -u "${SRC_USER}" "${SRC_DB}" \
    -N -B -e "$1"
}

log "원본 MariaDB ${SRC_HOST}:${SRC_PORT}/${SRC_DB} 에서 추출…"

{
  echo "-- 자동 생성 — 관제 klid_system → 온프렘 PostgreSQL 이벤트 타입 마스터 시드"
  echo "SET search_path TO ${PG_SCHEMA};"
  echo "BEGIN;"

  # MNG_EX_EVNT_TYPE (저작도구 stub 매핑 컬럼만: EVNT_TYPE_CD/EVNT_CLS_CD/EVNT_CTGRY_CD/CLCT_EVNT_NM/CLCT_YN)
  mysql_raw "SELECT CONCAT(
      'INSERT INTO MNG_EX_EVNT_TYPE (EVNT_TYPE_CD,EVNT_CLS_CD,EVNT_CTGRY_CD,CLCT_EVNT_NM,CLCT_YN) VALUES (',
      CHAR(39),EVNT_TYPE_CD,CHAR(39),',',
      CHAR(39),EVNT_CLS_CD,CHAR(39),',',
      CHAR(39),EVNT_CTGRY_CD,CHAR(39),',',
      IF(CLCT_EVNT_NM IS NULL,'NULL',CONCAT(CHAR(39),CLCT_EVNT_NM,CHAR(39))),',',
      CHAR(39),CLCT_YN,CHAR(39),
      ') ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;')
    FROM MNG_EX_EVNT_TYPE
    ORDER BY EVNT_TYPE_CD"

  # MNG_EX_EVNT_TYPE_MAP (복합 PK 5컬럼 + EVNT_NM/USE_YN)
  mysql_raw "SELECT CONCAT(
      'INSERT INTO MNG_EX_EVNT_TYPE_MAP (CD_TYPE,EVNT_CLS_CD,EVNT_CTGRY_CD,DTL_EVNT,EVNT_TYPE_CD,EVNT_NM,USE_YN) VALUES (',
      CHAR(39),CD_TYPE,CHAR(39),',',
      CHAR(39),EVNT_CLS_CD,CHAR(39),',',
      CHAR(39),EVNT_CTGRY_CD,CHAR(39),',',
      CHAR(39),DTL_EVNT,CHAR(39),',',
      CHAR(39),EVNT_TYPE_CD,CHAR(39),',',
      IF(EVNT_NM IS NULL,'NULL',CONCAT(CHAR(39),EVNT_NM,CHAR(39))),',',
      CHAR(39),USE_YN,CHAR(39),
      ') ON CONFLICT (CD_TYPE,EVNT_CLS_CD,EVNT_CTGRY_CD,DTL_EVNT,EVNT_TYPE_CD) DO NOTHING;')
    FROM MNG_EX_EVNT_TYPE_MAP
    ORDER BY CD_TYPE,EVNT_CLS_CD,EVNT_CTGRY_CD,DTL_EVNT,EVNT_TYPE_CD"

  echo "COMMIT;"
} > "${OUT_SQL}"

TYPE_CNT="$(grep -c 'INSERT INTO MNG_EX_EVNT_TYPE '       "${OUT_SQL}" || true)"
MAP_CNT="$(grep -c 'INSERT INTO MNG_EX_EVNT_TYPE_MAP '    "${OUT_SQL}" || true)"
log "추출 완료 — MNG_EX_EVNT_TYPE ${TYPE_CNT}건, MNG_EX_EVNT_TYPE_MAP ${MAP_CNT}건"
[[ "${TYPE_CNT}" -gt 0 && "${MAP_CNT}" -gt 0 ]] || die "추출된 행이 없습니다 — 원본 접속/스키마 확인."

if [[ "${DRY_RUN}" == "1" ]]; then
  log "DRY_RUN — 생성된 SQL 출력(적재 안 함):"
  cat "${OUT_SQL}"
  exit 0
fi

# ----------------------------------------------------------------------------
# 2) 대상 PostgreSQL 에 적재(단일 트랜잭션, 멱등)
# ----------------------------------------------------------------------------
log "대상 PostgreSQL ${PG_HOST}:${PG_PORT}/${PG_DB}(schema=${PG_SCHEMA}) 에 적재…"
PGPASSWORD="${PG_PASS}" psql \
  -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PG_DB}" \
  -v ON_ERROR_STOP=1 -q -f "${OUT_SQL}" \
  || die "적재 실패 — 대상 접속/테이블 존재(백엔드 1회 기동으로 Flyway 적용) 확인."

# ----------------------------------------------------------------------------
# 3) 검증 — 적재 결과 카운트
# ----------------------------------------------------------------------------
log "적재 후 검증:"
PGPASSWORD="${PG_PASS}" psql \
  -h "${PG_HOST}" -p "${PG_PORT}" -U "${PG_USER}" -d "${PG_DB}" -At \
  -c "SET search_path TO ${PG_SCHEMA};
      SELECT 'MNG_EX_EVNT_TYPE='     || COUNT(*) FROM MNG_EX_EVNT_TYPE;
      SELECT 'MNG_EX_EVNT_TYPE(Y)='  || COUNT(*) FROM MNG_EX_EVNT_TYPE WHERE CLCT_YN='Y';
      SELECT 'MNG_EX_EVNT_TYPE_MAP=' || COUNT(*) FROM MNG_EX_EVNT_TYPE_MAP;"

log "완료. (멱등 — 재실행해도 기존 행 보존)"
