-- ============================================================
-- Phase 3 (라벨러): 영상 잠금 상태 컬럼 — LS_DATA_RAW.LOCK_STTS_CD.
--   - NULL = 정상 / 'LOCKED_FOR_REDEIDENT' = 비식별 재처리 중 (라벨 commit 차단).
--   - LS_DEIDENT_REPORT 신고 시 자동 설정, 재비식별 성공 시 자동 해제.
--   - NULL 허용 — 기존 row 호환.
-- klid_system 공유 DB 영향: 단일 컬럼 ADD + 인덱스 1건 (관제서버팀 통보 필요).
-- ============================================================
ALTER TABLE LS_DATA_RAW
    ADD COLUMN IF NOT EXISTS LOCK_STTS_CD VARCHAR(32) NULL DEFAULT NULL;

CREATE INDEX IF NOT EXISTS IDX_LS_DATA_RAW_LOCK ON LS_DATA_RAW (LOCK_STTS_CD);
