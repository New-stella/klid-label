-- =============================================================================
-- V114: LS_DATA_LBL_HSTRY 재구조화 — "라벨 1건=1행" → "저장 이벤트=1행 + diff 페이로드".
--
-- 배경: 구조가 라벨 단위(LBL_SN + CHG_KIND_CD) 라 한 번의 저장/삭제 행위가 라벨 수만큼
--   여러 행으로 흩어졌다. 프레임 단위 저장 이벤트 1행으로 묶고, 종류별 건수(ADD/MDFCN/DEL)와
--   상세 diff(List<LabelChange> JSON) 를 담도록 전환한다.
--
-- 기존 라벨단위 행 폐기: 개발 단계(실데이터 없음)라 전량 DELETE 후 컬럼을 교체한다.
--   (구 LBL_SN/CHG_KIND_CD 기반 행은 신 저장이벤트 스키마로 backfill 할 의미가 없다.)
--
-- 물리명(표준용어 정합 — 사업표준단어/공공표준단어):
--   - 변경=CHG, 상세=DTL, 내용=CN → CHG_DTL_CN (TEXT, diff JSON)
--   - 추가=ADD,  수=CNT         → ADD_CNT
--   - 수정=MDFCN(기존 MDFCN_DT 정합, UPD 아님), 수=CNT → MDFCN_CNT
--   - 삭제=DEL,  수=CNT         → DEL_CNT
--
-- PostgreSQL 표준 문법. LS_* 전용 테이블 — 관제팀 협의 불요. ddl-auto=validate 대상.
-- (DROP COLUMN LBL_SN 시 이를 참조하던 IDX_LDLH_LBL 인덱스는 PostgreSQL 이 자동 삭제한다.)
-- =============================================================================

-- 1) 기존 라벨단위 이력 전량 폐기(개발 단계).
DELETE FROM LS_DATA_LBL_HSTRY;

-- 2) 구 라벨단위 컬럼 제거.
ALTER TABLE LS_DATA_LBL_HSTRY DROP COLUMN IF EXISTS LBL_SN;
ALTER TABLE LS_DATA_LBL_HSTRY DROP COLUMN IF EXISTS CHG_KIND_CD;

-- 3) 저장이벤트 컬럼 추가(종류별 건수 + diff 페이로드).
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS ADD_CNT    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS MDFCN_CNT  INTEGER NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS DEL_CNT    INTEGER NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS CHG_DTL_CN TEXT;

-- 4) 저장이벤트 의미 코멘트 갱신.
COMMENT ON TABLE  LS_DATA_LBL_HSTRY            IS '라벨 저장 이벤트 이력 — 프레임 단위 1행(종류별 건수 + diff 페이로드)';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.SRC_SN     IS '저장 이벤트가 발생한 프레임 LS_DATA_SRC.SRC_SN';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.REG_DT     IS '저장 이벤트 기록 일시';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.REG_ID     IS '작업자 식별자(감사) — 토큰/PII 미저장, 신고 경로는 NULL';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.ADD_CNT    IS '이 저장 이벤트의 추가(ADDED) 라벨 건수';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.MDFCN_CNT  IS '이 저장 이벤트의 수정(UPDATED) 라벨 건수';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.DEL_CNT    IS '이 저장 이벤트의 삭제(DELETED) 라벨 건수';
COMMENT ON COLUMN LS_DATA_LBL_HSTRY.CHG_DTL_CN IS '변경 상세 diff — List<LabelChange> JSON 직렬화(TEXT)';
