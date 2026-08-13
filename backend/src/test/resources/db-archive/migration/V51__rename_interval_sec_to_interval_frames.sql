-- V51: (무효화) 프레임간격수 컬럼은 V45 에서 표준 약어 FRME_INTV_NOCS 로 직접 정의됨.
--      행안부 공통표준 정합 작업으로 구 INTERVAL_SEC/INTERVAL_FRAMES 컬럼이 더 이상 존재하지 않으므로
--      본 마이그레이션은 no-op 으로 유지한다 (이미 적용된 환경의 Flyway 이력 정합용).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'ls_marking' AND column_name = 'interval_sec'
    ) THEN
        ALTER TABLE LS_MARKING RENAME COLUMN INTERVAL_SEC TO FRME_INTV_NOCS;
    END IF;
END $$;
