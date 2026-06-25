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
run "SELECT DATA_LBL_SN, DATA_RAW_SN, DATA_SRC_SN, LBL_SN, TRCK_ID, POINT, REG_DT, MDFCN_DT
     FROM LS_DATA_LBL" > "$OUT/lbl.tsv"

echo "[export] LS_PJT_LBL -> $OUT/pjt_lbl.tsv"
run "SELECT LBL_SN, LBL_ID, PRC_TYPE_CD, LBL_NM, LBL_COLR, SORT_SEQ
     FROM LS_PJT_LBL" > "$OUT/pjt_lbl.tsv"

echo "[export] done. files (헤더 없음, \\copy FORMAT text 로 바로 적재):"
wc -l "$OUT"/raw.tsv "$OUT"/src.tsv "$OUT"/lbl.tsv "$OUT"/pjt_lbl.tsv
