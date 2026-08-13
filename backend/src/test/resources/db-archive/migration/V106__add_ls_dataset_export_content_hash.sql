-- =============================================================================
-- V106: LS_DATASET_EXPORT 에 CONTENT_HASH 컬럼 추가 (Phase 4 — 승인 산출 오케스트레이션).
--
-- 목적: 검수 승인 시 라벨링 산출물을 파일로 저장할 때, "무수정 재승인" 을 멱등하게 skip 하기 위한
--       판정 키를 저장한다. 산출 시점 영상(RAW_SN) 전체 라벨 상태의 콘텐츠 해시(SHA-256 hex)이며,
--       직전 SUCCEEDED export 의 해시와 같으면 재산출을 건너뛴다(중복 v2 생성 방지).
--
-- 표준 정합:
--   - 물리명 CONTENT_HASH 는 기존 LS_LABEL_VERSION.VERSION_HASH·LS_DATASET_VIDEO_META.SNPSHT_HASH
--     의 해시 컬럼 관례(VARCHAR(64) = SHA-256 hex)와 정합.
--   - NULL 허용 — Phase 1/3 경로(파일 인프라 단독)에서 생성된 레코드는 해시 없이 존재할 수 있다.
--   - JPA ddl-auto=validate 정합 — 엔티티 LsDatasetExport.contentHash 와 컬럼/타입 일치.
--
-- PostgreSQL 표준 문법. 재실행 안전(IF NOT EXISTS).
-- =============================================================================

ALTER TABLE LS_DATASET_EXPORT ADD COLUMN IF NOT EXISTS CONTENT_HASH VARCHAR(64);
