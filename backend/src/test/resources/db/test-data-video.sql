-- Phase 3 영상 도메인 테스트용 시드 데이터.
-- @Sql(scripts = "/db/test-data-video.sql") 로 명시적 적용.
-- CCTV 마스터: VMS_CCTV_ID = "CCTV-001" / "CCTV-002" 만 존재.

DELETE FROM MNG_CLIP_SCHEDULE_QUE;
DELETE FROM LS_DATA_RAW_HSTRY;
DELETE FROM LS_DATA_RAW;
DELETE FROM MNG_RESOURCE_CCTV;

INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, SHT_ADDR, OG_NM, RESOLUTION, USE_YN) VALUES
  ('CCTV-001', '동대문구 회기로 CCTV', '서울특별시 동대문구 회기로 1', '서울시청', '1920x1080', 'Y'),
  ('CCTV-002', '강남구 테헤란로 CCTV', '서울특별시 강남구 테헤란로 1', '서울시청', '3840x2160', 'Y');
