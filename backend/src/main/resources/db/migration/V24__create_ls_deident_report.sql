-- ============================================================
-- Phase 3 (라벨러): 비식별 누락 신고 워크플로우 — LS_DEIDENT_REPORT.
--   - WORKER 또는 REVIEWER 가 라벨링 중 비식별 미흡(얼굴/번호판 미블러 등) 발견 시 신고.
--   - STTS_CD: OPEN(신고됨) / RESOLVED(재비식별 완료) / DISMISSED(반려).
--   - 신고 발생 시 LS_DATA_RAW.LOCK_STTS_CD='LOCKED_FOR_REDEIDENT' 로 잠금 → 라벨 commit 차단.
--   - 재비식별 성공 시 LOCK_STTS_CD = NULL + 본 테이블 OPEN → RESOLVED.
-- klid_system 공유 DB 영향: 신규 테이블 1건 (관제서버팀 통보 필요).
-- ============================================================
CREATE TABLE IF NOT EXISTS LS_DEIDENT_REPORT (
    RPRT_SN BIGINT NOT NULL AUTO_INCREMENT,
    RAW_SN BIGINT NOT NULL,
    REPORTER_NO BIGINT NOT NULL,
    REASON VARCHAR(1000) NOT NULL,
    STTS_CD VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    RPRT_DT TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    RESOLVED_DT TIMESTAMP NULL,
    PRIMARY KEY (RPRT_SN)
);

CREATE INDEX IF NOT EXISTS IDX_LS_DEIDENT_REPORT_RAW ON LS_DEIDENT_REPORT (RAW_SN);
CREATE INDEX IF NOT EXISTS IDX_LS_DEIDENT_REPORT_STTS ON LS_DEIDENT_REPORT (STTS_CD, RPRT_DT);
