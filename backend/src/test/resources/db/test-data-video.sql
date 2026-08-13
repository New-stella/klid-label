-- Phase 3 영상 도메인 테스트용 시드 데이터.
-- @Sql(scripts = "/db/test-data-video.sql") 로 명시적 적용.
--
-- ★CCTV 마스터 시드는 없다 (V167 — 관제 공유 MNG_RESOURCE_CCTV 제거).
--   CCTV 명의 유일한 조달처는 관제 인입 평면값(LS_DATA_INGEST.CCTV_NM)이며, 그 행은 <영상
--   (RAW_SN) 단위>라 여기서 미리 넣을 수 없다(RAW_SN 은 각 테스트가 영상을 만들 때 생긴다).
--   CCTV 명이 필요한 테스트는 영상을 만든 뒤 자기 픽스처에서 인입 행을 INSERT 한다
--   (예: VideoListSearchFilterIT#seedIngest).
--
-- ★LS_EVNT_ANNO_REVIEW → LS_EVNT_ANNO 는 FK_LS_EVNT_ANNO_REVIEW_ANNO 가 ON DELETE 규칙이
--   없어(기본 NO ACTION) LS_DATA_RAW 삭제의 CASCADE(FK_LS_EVNT_ANNO_RAW, V146)를 막는다.
--   다른 패키지 테스트가 남긴 검토 행이 있으면 "update or delete on table ls_evnt_anno
--   violates foreign key constraint ... on table ls_evnt_anno_review" 로 실패한다(교차 패키지
--   오염). test-data-evntanno-clean.sql 과 동일하게 자식 → 부모 순서로 먼저 비운다.
DELETE FROM LS_EVNT_ANNO_REVIEW;
DELETE FROM LS_EVNT_ANNO;
DELETE FROM LS_CLIP_SCHEDULE_QUE;
-- LS_DATA_RAW_HSTRY 정리는 V4(사용처 0 테이블 제거 2회차)로 대상이 사라졌다.
DELETE FROM LS_DATA_RAW;
