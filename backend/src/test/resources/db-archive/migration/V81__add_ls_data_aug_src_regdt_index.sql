-- ============================================================
-- V81 — LS_DATA_AUG (SRC_SN, REG_DT) 인덱스 추가 (성능)
--
-- 증강 잡 카드 목록(AugmentReviewService.listAll)의 1차 조회는
--   SELECT a.srcSn FROM LsDataAug a GROUP BY a.srcSn ORDER BY MIN(a.regDt) DESC, a.srcSn DESC
-- 이며, REVIEWER 워크리스트가 5초 주기로 폴링한다. 기존 인덱스는
--   (SRC_SN, AUG_TYPE_CD), (AUG_PROC_STTS_CD, REG_DT) 뿐이라 GROUP BY SRC_SN + MIN(REG_DT)
-- 를 커버하지 못해 폴링마다 전체 스캔+정렬이 발생한다.
-- (SRC_SN, REG_DT) 복합 인덱스로 그룹별 MIN(REG_DT) 도출을 Index Scan 으로 가속한다.
--
-- LS_* 전용 테이블이라 자체 관리(관제서버팀 협의 불필요). 순수 additive/idempotent —
-- 인덱스 추가만 하며 컬럼 rename/파괴 DDL 없음. PostgreSQL 표준 문법.
CREATE INDEX IF NOT EXISTS IDX_LS_DATA_AUG_SRC_REGDT
    ON LS_DATA_AUG (SRC_SN, REG_DT);
