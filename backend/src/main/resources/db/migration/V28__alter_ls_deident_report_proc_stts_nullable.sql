-- LS_DEIDENT_REPORT.PROC_STTS_CD 를 NULL 허용으로 변경.
-- Phase 3 통합 정책: 한 row 가 시스템 비식별 트랜잭션이거나 사용자 신고이거나 둘 중 하나.
-- 사용자 신고 row 는 PROC_STTS_CD 가 null 이고 REPORT_STTS_CD 만 채워진다.
-- 기준: docs/requirements/final-clean/관제서버_DB_신규테이블_DDL.md §5 (Phase 3+4 통합)
ALTER TABLE LS_DEIDENT_REPORT MODIFY COLUMN PROC_STTS_CD VARCHAR(20) NULL;
