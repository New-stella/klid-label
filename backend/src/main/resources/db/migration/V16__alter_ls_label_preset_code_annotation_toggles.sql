-- Phase 1 — 프리셋 라벨 코드별 BBOX/POLYGON 어노테이션 토글 컬럼 추가.
-- 같은 객체가 YOLO(BBOX) + SAM2(POLYGON) 두 row 로 동시에 저장되어 사용자가 중복으로 인식하는 문제를 해결.
-- 라벨 코드(카테고리) 별로 BBOX 와 POLYGON 활성 여부를 개별 토글한다.
--
-- 기본값: 두 컬럼 모두 TRUE → 기존 row 자동 채워짐 + 기존 동작 100% 호환.
-- CHECK 제약: 둘 다 FALSE 인 조합은 거부 (DTO @AssertTrue + 엔티티 가드와 함께 다중 보호).
--
-- 호환성: H2 MySQL 모드는 한 ALTER 문에서 다중 절을 지원하지 않으므로 분리한다.

ALTER TABLE LS_LABEL_PRESET_CODE
    ADD COLUMN BBOX_ENABLED BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'BBOX 어노테이션 활성 여부';

ALTER TABLE LS_LABEL_PRESET_CODE
    ADD COLUMN POLYGON_ENABLED BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'POLYGON 어노테이션 활성 여부';

ALTER TABLE LS_LABEL_PRESET_CODE
    ADD CONSTRAINT CK_LS_LABEL_PRESET_CODE_ANNOTATION
        CHECK (BBOX_ENABLED = TRUE OR POLYGON_ENABLED = TRUE);
