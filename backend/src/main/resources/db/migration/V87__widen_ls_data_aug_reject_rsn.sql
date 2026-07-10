-- =============================================================================
-- V87: LS_DATA_AUG.REJECT_RSN 길이 확대 VARCHAR(500) → VARCHAR(1000).
--
-- 근거(내부 길이 정합): 동일 의미(반려사유)의 형제 컬럼 및 사업표준용어 도메인과 일치시킨다.
--   - LS_DATA_AUG_RVW.REJECT_RSN   (V25) = VARCHAR(1000)
--   - LS_DATA_META_REVIEW.REJECT_RSN (V27) = VARCHAR(1000)
--   - 사업표준용어 반려사유(REJECT_RSN) 도메인 = V1000
-- 표준(1000) 데이터 유입 시 truncation 위험 제거를 위한 비파괴 확대.
-- =============================================================================

ALTER TABLE LS_DATA_AUG ALTER COLUMN REJECT_RSN TYPE VARCHAR(1000);
