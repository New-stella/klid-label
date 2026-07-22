-- Phase 2 event_annotation 테스트용 정리.
-- LS_EVNT_ANNO 는 LS_DATA_RAW(RAW_SN) 를 FK 참조하므로, test-data-video.sql 의
-- DELETE FROM LS_DATA_RAW 보다 먼저 자식 테이블을 비워 FK 위반을 방지한다.
DELETE FROM LS_EVNT_ANNO_REVIEW;
DELETE FROM LS_EVNT_ANNO;
