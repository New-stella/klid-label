-- =============================================================================
-- V100: LS_DATA_META 승인 hot path 복합 인덱스 (RAW_SN, META_KEY)
--
-- 배경: 통합 메타 동결(DatasetMetaSourceRepository.findSnapshotSource) 의 video.* 상관 서브쿼리
--       6개가 `WHERE RAW_SN=? AND META_KEY='video.*'` 로 조회한다. LS_DATA_META 는 VLM 시계열로
--       RAW_SN 당 다건이라, (RAW_SN) 단일 인덱스만으로는 META_KEY 필터가 인덱스 후 필터링되어
--       승인(동기) 트랜잭션이 지연될 수 있다(database-reviewer 지적).
--
-- 정합: 저작도구 전용 LS_* 테이블이라 관제 협의 불요. PostgreSQL 표준, ddl-auto=validate 무관
--       (인덱스는 엔티티 매핑 검증 대상 아님).
--
-- 중복 방지(중요): V4 는 이미 `CONSTRAINT UK_LS_DATA_META_RAW_KEY UNIQUE (RAW_SN, META_KEY)` 를
--       두었고, PostgreSQL 은 이 UNIQUE 제약을 (RAW_SN, META_KEY) 복합 btree 인덱스로 뒷받침한다.
--       즉 hot path 복합 인덱스는 사실상 이미 존재한다. 여기서 같은 컬럼 순서의 비유니크 인덱스를
--       무조건 추가하면 쓰기 비용만 늘리는 <b>중복 인덱스</b>가 된다. 따라서 (RAW_SN, META_KEY) 를
--       선두로 커버하는 인덱스가 없을 때만 생성한다 — 정상 스키마(V4 UK 존재)에서는 no-op 이고,
--       UK 가 없는 예외 환경에서만 보강 인덱스를 만든다.
-- =============================================================================

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND tablename = 'ls_data_meta'
          AND indexdef ILIKE '%(raw_sn, meta_key)%'
    ) THEN
        CREATE INDEX IF NOT EXISTS IX_LS_DATA_META_RAW_KEY ON LS_DATA_META (RAW_SN, META_KEY);
    END IF;
END $$;
