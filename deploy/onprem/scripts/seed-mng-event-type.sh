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
# ⚠⚠⚠ 온프렘에서는 <쓸모가 없다 — 실행이 막혀 있다> (2026-08-30 판정).
#     세 겹으로 전제가 깨졌다. 하나만 어긋난 것이 아니라 원본·대상·경로가 모두 없다:
#       ① 대상 테이블 없음 — MNG_EX_EVNT_TYPE / MNG_EX_EVNT_TYPE_MAP 은 db/schema.sql 에
#          <존재하지 않는다>(그 파일의 MNG_* 는 0건). 온프렘은 Flyway 를 쓰지 않으므로 앱이
#          기동해도 생기지 않는다.
#       ② 원본 없음 — 관제 서버는 PostgreSQL 로 전환됐고 MNG_* 공유 테이블은 전면 정리로
#          제거됐다. 아래 코드가 전제하는 "관제 MariaDB/MySQL klid_system" 은 더 이상 없다.
#       ③ 목적 소멸 — 현행 이벤트 타입 마스터는 klid_at.ls_evnt_type / ls_evnt_ctgry 이고,
#          그 시드(유형 16행 · 카테고리 11행)는 이미 db/schema.sql 의 COPY 블록에 들어 있다.
#          설치가 16 단계에서 그 파일을 로드하면 <이 스크립트 없이도> 화면 필터·라벨이 뜬다.
#     → 그래서 아래에 fail-closed 가드를 두어 <기본적으로 실행을 거부>한다. 삭제하지 않고
#       남겨 두는 이유는 관제 MariaDB 가 아직 살아 있는 다른 환경(구 배포·이관 작업)에서
#       참고·재사용될 수 있기 때문이다. 그 경우에만 SEED_MNG_FORCE=1 로 연다.
#
# 전제:
#   1) 대상 PostgreSQL 에 두 테이블이 이미 생성돼 있어야 한다(위 경고 참고 — 현행 매체는 만들지 않는다).
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

# ----------------------------------------------------------------------------
# 폐기 가드 (fail-closed) — 위 헤더 「온프렘에서는 쓸모가 없다」 참조.
#   그냥 두면 이 스크립트는 없는 원본에 붙으려다, 혹은 없는 대상 테이블에 INSERT 하려다
#   <원인을 알기 어려운 접속/SQL 오류>로 죽는다. 사유를 먼저 말하고 멈추는 편이 낫다.
# ----------------------------------------------------------------------------
if [[ "${SEED_MNG_FORCE:-0}" != "1" ]]; then
  err "이 스크립트는 현행 온프렘 매체에서 쓰지 않습니다(대상 테이블·원본 DB·목적이 모두 없어졌습니다)."
  err ""
  err "  이벤트 타입 마스터는 이미 준비됩니다 — 별도 적재가 필요 없습니다:"
  err "    · 현행 테이블 : klid_at.ls_evnt_type / klid_at.ls_evnt_ctgry"
  err "    · 시드 반입   : deploy/onprem/db/schema.sql 의 COPY 블록(유형 16행 · 카테고리 11행)"
  err "    · 적재 시점   : 설치 16 단계(16-load-schema.sh)가 그 파일을 1회 로드할 때"
  err ""
  err "  확인 방법(대상 DB 에서):"
  err "    SELECT COUNT(*) FROM klid_at.ls_evnt_type;"
  err "  0 이면 스키마 로드가 안 된 것입니다 — 이 스크립트가 아니라 16 단계를 확인하세요."
  err ""
  err "  관제 MariaDB 가 아직 살아 있는 구 환경에서 <의도적으로> 쓰려면: SEED_MNG_FORCE=1"
  exit 1
fi
warn_forced() { printf '\033[33m[seed][WARN]\033[0m %s\n' "$*" >&2; }
warn_forced "SEED_MNG_FORCE=1 — 폐기 가드를 껐습니다. 대상에 MNG_EX_* 테이블이 실재하는지 먼저 확인하세요."

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
  || die "적재 실패 — 대상 접속/테이블 존재 확인(현행 db/schema.sql 에는 MNG_EX_* 가 없다)."

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
