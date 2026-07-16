-- =============================================================================
-- V99: LS_DATASET_VIDEO_META 활성 스냅샷 1건 불변식 — 부분 유니크 인덱스(DB fail-safe)
--
-- 목적: RAW_SN 당 ACTIVE_YN='Y' 스냅샷이 항상 정확히 1건이도록 DB 레벨에서 강제한다.
--       Phase 2 materialize 어댑터는 rawSn 단위 advisory 락 + deactivate-then-insert 순서로
--       "활성 0/2건"을 방지하지만, 애플리케이션 결함/우회 경로에서도 2건이 남지 않도록
--       부분 유니크 인덱스로 원천 차단한다(database-reviewer Phase 1 HIGH 이월 대응).
--
-- 동작:
--   - 활성('Y') 행에 한해 RAW_SN 유니크 → 같은 RAW_SN 에 활성 2건 INSERT/UPDATE 시 위반(에러).
--   - 비활성('N') 이력 행은 인덱스 대상 밖 → append-only 이력은 제약 없이 누적된다.
--   - 이 때문에 materialize 는 반드시 "기존 활성 비활성화 → 신규 활성 삽입"(deactivate-then-insert)
--     순서를 지켜야 한다(insert-then-deactivate 는 순간 2 활성으로 위반).
--
-- ddl-auto=validate 무관(부분 인덱스는 엔티티 매핑 검증 대상 아님). PostgreSQL 표준 문법.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_DATASET_VIDEO_META_RAW_ACTIVE
    ON LS_DATASET_VIDEO_META (RAW_SN)
    WHERE ACTIVE_YN = 'Y';
