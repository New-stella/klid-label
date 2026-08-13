-- =====================================================================
-- V38 — LS_RAW_DATA_STATUS.DATA_STTS_CD 인덱스 추가
-- =====================================================================
-- 목적: 검수 목록 조회/통계 집계 쿼리의 Full Scan 방지
-- 영향 쿼리:
--   - ReviewRepository.searchByStatus (WHERE dataSttsCd = :status)
--   - StatsQueryRepository.countByDataSttsCd (GROUP BY dataSttsCd)
--   - StatsQueryRepository.countMyTaskByStatus (WHERE dataSttsCd IN)
-- 코드 리뷰 (.code-review-result Phase 10~13) HIGH 1건 해소
-- =====================================================================

CREATE INDEX IF NOT EXISTS IX_LS_RAW_DATA_STATUS_STTS
    ON LS_RAW_DATA_STATUS (DATA_STTS_CD);
