-- =============================================================================
-- V118: 프리셋 라벨 코드 LBL_CD nullable 전환 (labelId 기반 CRUD 지원).
--
-- 배경: V117 에서 LBL_ID(마스터 FK) 를 추가했다. Phase 2 부터 프리셋 코드는 labelId 로
--   지정되며 라벨명·형태는 마스터(LS_LABEL)에서 실시간 조회한다(스냅샷 금지). 따라서
--   labelId 기반 신규 행은 LBL_CD(코드 문자열)를 보유하지 않는다(NULL).
--
-- 변경:
--   - LBL_CD 의 NOT NULL 제약 제거 → labelId 연결 행은 LBL_CD NULL, 미연결 레거시 행만
--     기존 코드 문자열을 보유(표시용 fallback).
--
--   UNIQUE(PRESET_ID, LBL_CD) 는 유지한다 — PostgreSQL 은 NULL 을 distinct 취급하므로
--   LBL_CD NULL 인 labelId 행 다수를 허용한다(같은 labelId 중복은 서비스/도메인 dedup 이 차단).
-- =============================================================================

ALTER TABLE LS_LABEL_PRESET_CODE
    ALTER COLUMN LBL_CD DROP NOT NULL;
