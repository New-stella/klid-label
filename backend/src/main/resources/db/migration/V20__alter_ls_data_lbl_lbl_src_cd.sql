-- Phase 3 (트랙 보간 도입): LS_DATA_LBL 에 LBL_SRC_CD 컬럼 추가.
-- 자동 라벨링이 detection 으로 직접 발견했는지(NULL=DETECTED, 기본),
-- 또는 트랙 보간으로 추정·생성된 row 인지(INTERPOLATED) 구분한다.
--
-- 운영 정책:
--   - LS_DATA_LBL 은 LS_* 접두사 저작도구 전용 테이블 — V4(생성), V6(이력), V18(track_id), 본 V20 모두 면제 대상.
--   - LBL_SRC_CD 는 NULL 허용 — legacy row 와 detection 라벨은 NULL.
--   - 값 화이트리스트: NULL = DETECTED(기본), 'INTERPOLATED' = 트랙 보간 결과.
--   - VARCHAR(20) — 향후 'AUGMENTED', 'IMPORTED' 등 확장 여지.
--
-- H2 + MariaDB 호환: ALTER TABLE ... ADD COLUMN IF NOT EXISTS / CREATE INDEX IF NOT EXISTS.

ALTER TABLE LS_DATA_LBL
    ADD COLUMN IF NOT EXISTS LBL_SRC_CD VARCHAR(20) NULL DEFAULT NULL
    COMMENT '라벨 출처 (NULL=DETECTED, INTERPOLATED=트랙 보간)';

CREATE INDEX IF NOT EXISTS IDX_LS_DATA_LBL_LBL_SRC_CD ON LS_DATA_LBL (LBL_SRC_CD);
