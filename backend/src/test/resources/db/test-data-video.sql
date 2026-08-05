-- Phase 3 영상 도메인 테스트용 시드 데이터.
-- @Sql(scripts = "/db/test-data-video.sql") 로 명시적 적용.
--
-- ★CCTV 마스터 시드는 없다 (V167 — 관제 공유 MNG_RESOURCE_CCTV 제거).
--   CCTV 명의 유일한 조달처는 관제 인입 평면값(LS_DATA_INGEST.CCTV_NM)이며, 그 행은 <영상
--   (RAW_SN) 단위>라 여기서 미리 넣을 수 없다(RAW_SN 은 각 테스트가 영상을 만들 때 생긴다).
--   CCTV 명이 필요한 테스트는 영상을 만든 뒤 자기 픽스처에서 인입 행을 INSERT 한다
--   (예: VideoListSearchFilterIT#seedIngest).
DELETE FROM LS_CLIP_SCHEDULE_QUE;
DELETE FROM LS_DATA_RAW_HSTRY;
DELETE FROM LS_DATA_RAW;
