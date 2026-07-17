-- =============================================================================
-- 포털 DB 복제본 정합 — LS_DATASET_VIDEO_META 코드성 컬럼 → VARCHAR(20)
--
-- ⚠ 이 스크립트는 애플리케이션 Flyway 가 자동 실행하지 않는다(db/portal/ 는 migration 경로 밖).
--    control 원본(db/migration/V107)과 스키마를 동형으로 유지하기 위한 복제본 정합 DDL 이며,
--    인프라/운영이 포털 DB(PORTAL_DB_*)에 1회 수동 적용한다(V1 프로비저닝 스크립트와 동일 취급).
--
-- 근거: docs/표준용어 코드V20(VARCHAR 20) 정합 — 감리 지적("이벤트 관련 용어 타입/길이 제각각")
--       대응. control 원본 V107 과 동일하게 코드성 컬럼을 VARCHAR(20) 으로 통일한다.
--
-- 값 안전성(값 ≤20 확인): EVNT_TYPE_CD·LCLGV_CD·VDO_CDC 는 32→20 축소(모두 ≤20, truncation 없음),
--   SESN_CD 는 8→20 확대(무손실).
--
-- 포털 복제본에는 데이터마트 View 가 없어 View 선삭제/재생성 불필요.
-- PostgreSQL 표준 문법.
-- =============================================================================
ALTER TABLE LS_DATASET_VIDEO_META ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META ALTER COLUMN LCLGV_CD     TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META ALTER COLUMN VDO_CDC      TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META ALTER COLUMN SESN_CD      TYPE VARCHAR(20);
