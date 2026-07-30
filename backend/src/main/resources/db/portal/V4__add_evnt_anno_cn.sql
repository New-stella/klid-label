-- =============================================================================
-- 포털 DB 복제본 정합 — LS_DATASET_VIDEO_META 에 EVNT_ANNO_CN(jsonb) 추가
--
-- ⚠ 이 스크립트는 애플리케이션 Flyway 가 자동 실행하지 않는다(db/portal/ 는 migration 경로 밖).
--    control 원본(db/migration/V128)과 스키마를 동형으로 유지하기 위한 복제본 정합 DDL 이며,
--    인프라/운영이 포털 DB(PORTAL_DB_*)에 1회 수동 적용한다(V1 프로비저닝 스크립트와 동일 취급).
--
-- 배경(드리프트 정정): control 은 V128(2026-07-24)로 이벤트 어노테이션 동결 컬럼 EVNT_ANNO_CN 을
--   추가했으나 포털 복제본 DDL(V1~V3)에는 반영되지 않아 두 스키마가 어긋나 있었다.
--   복제 경로 4종은 모두 네이티브 쿼리라 이 컬럼을 읽고 쓰지 않으므로 복제 자체는 동작하지만,
--   같은 엔티티(LsDatasetVideoMeta)를 쓰는 JPA 파생 조회(findByRawSnAndActiveYn)는 이 컬럼을
--   SELECT 하므로 DDL 대로 프로비저닝된 포털 DB 에서 42703(undefined_column)으로 깨진다.
--
-- 표준용어/표준도메인: control V128 과 동일 물리명·타입(EVNT_ANNO_CN, JSONB, NULL 허용).
--   신규 판단 대상 아님 — 원본 컬럼 정의를 그대로 복제한다.
--
-- 멱등: IF NOT EXISTS 로 재실행 안전. PostgreSQL 표준 문법.
-- =============================================================================

ALTER TABLE LS_DATASET_VIDEO_META
    ADD COLUMN IF NOT EXISTS EVNT_ANNO_CN JSONB NULL;

COMMENT ON COLUMN LS_DATASET_VIDEO_META.EVNT_ANNO_CN IS
    '동결된 event_annotation payload 원문(jsonb). 승인 시점 APPROVED event_annotation 스냅샷, 미승인/부재 시 NULL';
