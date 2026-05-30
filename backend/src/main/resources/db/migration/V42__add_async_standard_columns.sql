-- Phase 4 — 비동기 표준 컬럼 4종 (idempotencyKey / externalJobId / retryCount / deadLetterAt) 추가.
-- ccarch async-standard-columns 명세. LS_DATA_META + LS_DATA_AUG 두 테이블에 동일 패턴 적용.
--
-- 정책
--  - IDEMPOTENCY_KEY / EXTERNAL_JOB_ID : NULL 허용 (기존 행 보호 — V43 backfill 로 결정적 키 채움).
--    NULL 다수 — MariaDB/MySQL/H2 모두 UNIQUE 컬럼의 NULL 중복 허용 (표준 SQL 의 정의).
--  - RETRY_COUNT : NOT NULL DEFAULT 0 — 재시도 횟수 추적.
--  - DEAD_LETTER_AT : NULL — 영구 실패 마킹 시점. polling 인덱스 대상.
--  - UNIQUE 제약 — 동시 webhook 인계 시 race 차단 (CWE-362, S-1 방어).
--
-- H2/MariaDB 호환을 위해 ALTER TABLE 1건당 ADD COLUMN 1개로 분리.
-- (H2 는 단일 ALTER TABLE 내 다중 ADD COLUMN 콤마 구문을 지원하지 않음)

-- ============================================================
-- LS_DATA_META : VLM 시계열 메타 결과 (Phase 2 webhook 수신)
-- ============================================================
ALTER TABLE LS_DATA_META ADD COLUMN IDEMPOTENCY_KEY VARCHAR(64) NULL;
ALTER TABLE LS_DATA_META ADD COLUMN EXTERNAL_JOB_ID VARCHAR(128) NULL;
ALTER TABLE LS_DATA_META ADD COLUMN RETRY_COUNT INT NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_META ADD COLUMN DEAD_LETTER_AT TIMESTAMP NULL;

ALTER TABLE LS_DATA_META ADD CONSTRAINT uk_meta_idempotency_key UNIQUE (IDEMPOTENCY_KEY);
ALTER TABLE LS_DATA_META ADD CONSTRAINT uk_meta_external_job_id UNIQUE (EXTERNAL_JOB_ID);

-- ============================================================
-- LS_DATA_AUG : 외부 SFR-07 증강 결과 (Phase 2 webhook 수신)
-- ============================================================
ALTER TABLE LS_DATA_AUG ADD COLUMN IDMP_KEY VARCHAR(64) NULL;
ALTER TABLE LS_DATA_AUG ADD COLUMN OTSD_JOB_ID VARCHAR(128) NULL;
ALTER TABLE LS_DATA_AUG ADD COLUMN RETRY_COUNT INT NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_AUG ADD COLUMN DEAD_LETTER_AT TIMESTAMP NULL;

ALTER TABLE LS_DATA_AUG ADD CONSTRAINT uk_aug_idempotency_key UNIQUE (IDMP_KEY);
ALTER TABLE LS_DATA_AUG ADD CONSTRAINT uk_aug_external_job_id UNIQUE (OTSD_JOB_ID);
