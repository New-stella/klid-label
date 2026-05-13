-- Phase 3 (YOLO Track 도입): LS_DATA_LBL 에 TRACK_ID 컬럼 추가.
-- ultralytics BoT-SORT 트래커가 부여한 객체 ID 를 자동 라벨 row 와 함께 보관해
-- 동일 객체의 프레임 간 연속성을 유지한다.
--
-- 운영 정책:
--   - LS_DATA_LBL 은 LS_* 접두사 저작도구 전용 테이블 — V4(생성), V6(이력 컬럼), 본 V18 모두 면제 대상.
--   - TRACK_ID 는 NULL 허용 — 수동 라벨/legacy row 호환 + 트래커 저신뢰 detection(track_id 미부여) 호환.
--   - VARCHAR(64) — int track_id 를 문자열로 보관 (운영 중 영상별 prefix 결합 가능).
--
-- H2 + MariaDB 호환: ALTER TABLE ... ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS.

ALTER TABLE LS_DATA_LBL
    ADD COLUMN IF NOT EXISTS TRACK_ID VARCHAR(64) NULL COMMENT '자동라벨링 트래커 부여 객체 ID';

CREATE INDEX IF NOT EXISTS IDX_LS_DATA_LBL_TRACK_ID ON LS_DATA_LBL (TRACK_ID);
