-- ============================================================
-- Phase 5 hotfix: LS_DATA_META UK 보강.
--   - 기존 (RAW_SN, META_KEY) UK 는 V23 에서 META_TYPE_CD 컬럼 도입 후
--     동일 (RAW_SN, META_KEY) 조합으로 RAW/DEID 두 행을 동시 저장하지 못하는
--     제약으로 작용한다. Phase 5 정책상 META_TYPE_CD 분리 저장이 필요하므로
--     트리플 UK (RAW_SN, META_KEY, META_TYPE_CD) 로 확장한다.
--   - 기존 데이터는 V23 에서 DEFAULT 'RAW' 로 분류되어 있어 트리플 UK 변경 시
--     충돌이 발생하지 않는다.
-- klid_system 공유 DB 영향: UK 재정의 (DROP + ADD). 짧은 메타 락 — 운영 적용 전 관제팀 통보.
-- ============================================================

-- MariaDB / H2(MySQL mode) 호환: UK 는 CONSTRAINT + INDEX 양쪽에서 참조될 수 있으므로
-- 두 경로 모두 안전하게 DROP 시도. IF EXISTS 로 부재시 무시.
ALTER TABLE LS_DATA_META DROP INDEX IF EXISTS UK_LS_DATA_META_RAW_KEY;

ALTER TABLE LS_DATA_META
    ADD CONSTRAINT UK_LS_DATA_META_RAW_KEY_TYPE
    UNIQUE (RAW_SN, META_KEY, META_TYPE_CD);
