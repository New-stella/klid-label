-- =============================================================================
-- V128: LS_DATASET_VIDEO_META 에 이벤트 어노테이션 동결 컬럼(EVNT_ANNO_CN) 추가
--
-- 목적(Phase 3 — event_annotation export 연동, 동결 C2):
--   검수 승인(APPROVED) 시점에 그 시점의 승인된 event_annotation(LS_EVNT_ANNO.ANNO_CN,
--   RVW_STTS_CD='APPROVED') payload 원문을 통합 메타 스냅샷에 함께 <b>동결</b>한다.
--   이후 원본 event_annotation 이 편집돼도 export 는 동결본(EVNT_ANNO_CN)만 사용하므로
--   재export 가 멱등하다. 승인 안 됐거나 event_annotation 이 없으면 NULL(동결 대상 없음).
--
-- 표준용어/표준도메인 정합(Critical):
--   - EVNT_ANNO_CN(이벤트 EVNT + 어노테이션 ANNO + 내용 CN): payload 본문, 타입 jsonb.
--     V127 LS_EVNT_ANNO.ANNO_CN 선례와 동일 물리명/타입(jsonb, 길이 제한 없음)을 준용한다.
--   - NULL 허용: 동결 시점에 승인된 event_annotation 이 없는 영상은 NULL.
--
-- 멱등: IF NOT EXISTS 로 재실행 안전. PostgreSQL 표준 문법.
-- =============================================================================

ALTER TABLE LS_DATASET_VIDEO_META
    ADD COLUMN IF NOT EXISTS EVNT_ANNO_CN JSONB NULL;

COMMENT ON COLUMN LS_DATASET_VIDEO_META.EVNT_ANNO_CN IS
    '동결된 event_annotation payload 원문(jsonb). 승인 시점 APPROVED event_annotation 스냅샷, 미승인/부재 시 NULL';
