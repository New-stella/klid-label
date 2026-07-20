-- =============================================================================
-- V112: LS_DATA_LBL_HSTRY 변경 이력 확장 — 변경종류(CHG_KIND_CD) + 작업자(REG_ID).
--
-- 배경(Phase 2): 기존 LS_DATA_LBL_HSTRY 는 비식별 신고 시 '삭제 이력'만 기록했다
--   (recordDeletion — LBL_SN/SRC_SN/REG_DT). 라벨 저장 경로(LabelService.bulkUpsert,
--   PUT /v1/frames/{srcSn}/labels)에서 변경 이력(ADDED/UPDATED/DELETED + 작업자)을
--   기록하고 프레임 단위 히스토리 조회 API 를 제공하기 위해 두 컬럼을 추가한다.
--
-- 하위호환(비파괴):
--   - 두 컬럼 모두 NULLABLE 로 추가한다(기존 row 및 삭제 이력 경로 무수정 보존).
--   - 기존 row 는 전량 recordDeletion 산물(V58 이후 삭제 이력 전용 테이블)이므로
--     CHG_KIND_CD 를 'DELETED' 로 backfill 한다(안전한 결정론적 backfill).
--
-- 물리명(표준용어 정합):
--   - CHG_KIND_CD : 변경종류 코드(ADDED/UPDATED/DELETED). 코드 컬럼 관례 _CD + VARCHAR(20).
--   - REG_ID      : 등록자(작업자) 식별자. 기존 LS_* 등록자 컬럼 관례 REG_ID + VARCHAR(30)
--                   (LS_AUTH_WORK_LOCK / LS_RESOLUTION_EXPORT / LS_DATASET_VIDEO_META 동일).
--
-- PostgreSQL 표준 문법. LS_* 전용 테이블 — 관제팀 협의 불요. ddl-auto=validate 대상.
-- =============================================================================

ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS CHG_KIND_CD VARCHAR(20);
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS REG_ID      VARCHAR(30);

-- 기존 이력은 전부 삭제 이력(recordDeletion) — 결정론적 backfill.
UPDATE LS_DATA_LBL_HSTRY SET CHG_KIND_CD = 'DELETED' WHERE CHG_KIND_CD IS NULL;
