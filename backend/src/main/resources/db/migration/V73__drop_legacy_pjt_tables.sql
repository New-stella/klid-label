-- ============================================================
-- V73 — LS_PJT_* 레거시 하위 테이블 제거 (프로젝트 개념 잔재 정리)
--
-- 프로젝트 단위 개념은 V34/V35 에서 폐기되어 영상 단위(RAW_SN)로
-- 전환되었으나, LS_PJT 본체(V35 DROP)와 달리 아래 하위 테이블들은
-- 명시적 DROP 없이 고아로 남아 있었다. 어느 엔티티도 매핑하지 않으며
-- 라이브 테이블의 외래키 참조도 없음(전부 0행)을 확인 후 제거한다.
-- IF EXISTS 로 멱등 — 이미 없는 환경에서도 안전하게 재실행 가능.
-- ============================================================

DROP TABLE IF EXISTS LS_PJT_DATA_MPNG CASCADE;
DROP TABLE IF EXISTS LS_PJT_DATA_STTS CASCADE;
DROP TABLE IF EXISTS LS_PJT_DDLN CASCADE;
DROP TABLE IF EXISTS LS_PJT_META CASCADE;
DROP TABLE IF EXISTS LS_PJT_TASK_EVENT_LOG CASCADE;
DROP TABLE IF EXISTS LS_PJT_USER_AUTHRT CASCADE;
DROP TABLE IF EXISTS LS_PJT_USER_AUTHRT_HSTRY CASCADE;
