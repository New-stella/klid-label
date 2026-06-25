#!/usr/bin/env bash
# v1(MariaDB) 영상·라벨 4개 테이블을 TSV로 export (폐쇄망, mysql 클라이언트만 사용)
# 사용: bash 01_export_mysql.sh <host> <port> <user> <db> <out_dir>
#   예: bash 01_export_mysql.sh 192.168.102.102 13307 klid_user klid_system ./out
# 비밀번호는 프롬프트(-p) 또는 ~/.my.cnf 로 주입 — 평문 인자 금지.
set -euo pipefail

HOST="${1:?host}"; PORT="${2:?port}"; USER="${3:?user}"; DB="${4:?db}"; OUT="${5:-./out}"
mkdir -p "$OUT"

# mysql --batch: 탭 구분 + NULL→\N + 특수문자(\t \n \\)를 이스케이프 → psql \copy FORMAT text 와 정확 호환.
#   --skip-column-names: 헤더 행 제거(text 포맷은 HEADER 스킵 미지원이므로 처음부터 안 만든다)
#   --raw 미사용: 이스케이프를 켜서 POINT JSON 내 개행/탭/백슬래시가 있어도 무손실
# 컬럼은 v2 이관에 필요한 것만 선별(영상 메타 대량 컬럼 제외 — GAP③).
run() { mysql --batch --skip-column-names -h "$HOST" -P "$PORT" -u "$USER" -p "$DB" -e "$1"; }

echo "[export] LS_DATA_RAW -> $OUT/raw.tsv"
run "SELECT DATA_RAW_SN, RAW_FILE_PATH, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD,
            PRVC_YN, DE_IDNTF_YN, SHT_DT, VDO_LEN, REG_DT, MDFCN_DT
     FROM LS_DATA_RAW" > "$OUT/raw.tsv"

echo "[export] LS_DATA_SRC -> $OUT/src.tsv"
run "SELECT DATA_SRC_SN, DATA_RAW_SN, FRM_NO, SRC_FILE_PATH, SRC_BKUP_FILE_PATH,
            REG_DT, MDFCN_DT
     FROM LS_DATA_SRC" > "$OUT/src.tsv"

echo "[export] LS_DATA_LBL -> $OUT/lbl.tsv"
# REG_ID = 라벨 작성자(작업자) — 영상별 LABELER 배정 도출용(확장 스코프 B)
run "SELECT DATA_LBL_SN, DATA_RAW_SN, DATA_SRC_SN, LBL_SN, TRCK_ID, POINT, REG_DT, MDFCN_DT, REG_ID
     FROM LS_DATA_LBL" > "$OUT/lbl.tsv"

echo "[export] LS_PJT_LBL -> $OUT/pjt_lbl.tsv"
run "SELECT LBL_SN, LBL_ID, PRC_TYPE_CD, LBL_NM, LBL_COLR, SORT_SEQ
     FROM LS_PJT_LBL" > "$OUT/pjt_lbl.tsv"

# ---------------------------------------------------------------------
# 확장 스코프(프로젝트→영상 단위): 검수/완료 상태 · 이슈 · 증강(레거시)
#   배정(B)은 별도 테이블 export 불필요 — 위 lbl.tsv 의 REG_ID(작성자)에서 도출.
# ---------------------------------------------------------------------
echo "[export] LS_PJT_DATA_STTS -> $OUT/stts.tsv"
# (PJT_SN,DATA_RAW_SN) 단위 작업/검수 상태 → 02 에서 PJT 제거 후 영상 단위로 수렴(상태 우선순위)
run "SELECT PJT_SN, DATA_RAW_SN, PJT_DATA_STTS_CD, STP_CYCL, IGI_CYCL, REG_DT, MDFCN_DT
     FROM LS_PJT_DATA_STTS" > "$OUT/stts.tsv"

echo "[export] LS_DATA_ISSUE -> $OUT/issue.tsv"
run "SELECT DATA_ISSUE_SN, UP_DATA_ISSUE_SN, DATA_RAW_SN, DATA_SRC_SN, ISSUE_TYPE_CD,
            ISSUE_DTL_CD, RJCT_DTL_CD, ISSUE_CN, USE_YN, REG_ID, REG_DT
     FROM LS_DATA_ISSUE" > "$OUT/issue.tsv"

echo "[export] LS_DATA_AUG -> $OUT/aug.tsv"
# v1 내부 증강(BRIGHT/DARK/LR…) — v2 외부 생성형(WINTER/NIGHT/RAIN)과 의미 상이 → 레거시 적재(GAP④)
run "SELECT DATA_AUG_SN, DATA_RAW_SN, DATA_SRC_SN, PJT_AUG_OPT_CD, AUG_PROC_STTS_CD,
            AUG_FILE_PATH, REG_ID, REG_DT
     FROM LS_DATA_AUG" > "$OUT/aug.tsv"

echo "[export] done. files (헤더 없음, \\copy FORMAT text 로 바로 적재):"
wc -l "$OUT"/raw.tsv "$OUT"/src.tsv "$OUT"/lbl.tsv "$OUT"/pjt_lbl.tsv \
      "$OUT"/stts.tsv "$OUT"/issue.tsv "$OUT"/aug.tsv
