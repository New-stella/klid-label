-- =============================================================================
-- V89: LS_DATA_LBL_HSTRY.REG_AT → REG_DT 물리 컬럼 리네임.
--
-- 근거(등록일시 표준약어 DT 정합): 일시(datetime) 표준약어는 DT 이며, 등록일시는 REG_DT 다.
--   본 테이블만 비표준 _AT(REG_AT, V83 REGISTERED_AT→REG_AT 산물)로 드리프트되어 있어
--   타 전체 테이블(LS_* REG_DT)과 일관되게 REG_DT 로 정합한다.
--   - 인덱스 IDX_LDLH_SRC(SRC_SN, REG_AT DESC) 는 컬럼 리네임을 자동 follow(인덱스명 무변경).
-- 비파괴 리네임(데이터 보존).
-- =============================================================================

ALTER TABLE LS_DATA_LBL_HSTRY RENAME COLUMN REG_AT TO REG_DT;
