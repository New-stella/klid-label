-- ============================================================
-- Phase 2: 메타 출처 구분 (RAW: 원본 기준 메타, DEID: 비식별 영상 기준 메타).
--   - Phase 1 VLM_META.* 레코드는 DEFAULT 'RAW' 로 자동 분류 — 별도 백필 SQL 불필요.
--   - 향후 비식별 영상 기준 메타가 별도로 산출되는 경우 META_TYPE_CD='DEID' 분기 가능.
-- klid_system 공유 DB 영향: 단일 컬럼 ADD, NOT NULL DEFAULT 'RAW', 인덱스 1건 (관제서버팀 통보 필요).
-- ============================================================
ALTER TABLE LS_DATA_META
    ADD COLUMN IF NOT EXISTS META_TYPE_CD VARCHAR(8) NOT NULL DEFAULT 'RAW';

CREATE INDEX IF NOT EXISTS IX_LS_DATA_META_RAW_TYPE ON LS_DATA_META (RAW_SN, META_TYPE_CD);
