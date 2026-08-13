-- 검수완료 영상 재비식별(Approved Re-deidentification) — 비식별 처리 경로 구분용 추적 컬럼 추가.
-- 배경:
--   - 비식별 완료 시 "기존 배치 비식별 경로" 와 "검수완료 후 재비식별 경로" 를 구분해야 후속 처리(통지/상태 전이)를 분기할 수 있다.
--   - REQ_KIND_CD = null  → 기존 배치 비식별 경로(무영향, 기존 행/배치 동작 그대로).
--   - REQ_KIND_CD = 'REDEIDENT' → 검수완료 재비식별 경로.
-- 영향: nullable 추가만 — 기존 컬럼/제약/배치 적재 경로(LsDeidentProcLog.request→succeed→fail)에 무영향.
--   기존 행은 null 로 남아 기존 배치 경로로 간주되며, 엔티티 추가 매핑이어도 ddl-auto=validate 기동에 영향 없다.
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN REQ_KIND_CD VARCHAR(20) NULL;  -- null=기존 배치 경로, 'REDEIDENT'=검수완료 재비식별 경로
