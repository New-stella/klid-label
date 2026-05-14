-- ============================================================
-- Phase 2: 영상 단위 비식별 영상 파일 경로 컬럼.
--   - V2 정책 "영상 2벌 보관": 원본 영상(FILE_PATH) + 비식별 영상(DEID_FILE_PATH).
--   - 외부 비식별 API 가 영상 단위 인터페이스 제공 시 활용.
--   - NULL 허용 — 비식별 미적용/V1 호환 row 도 보존.
-- klid_system 공유 DB 영향: 단일 컬럼 ADD, NULL 허용, 기존 row 호환 (관제서버팀 통보 필요).
-- ============================================================
ALTER TABLE LS_DATA_RAW
    ADD COLUMN IF NOT EXISTS DEID_FILE_PATH VARCHAR(500) NULL DEFAULT NULL;
