-- =============================================================================
-- V117: 프리셋 라벨 코드 → 라벨 마스터(LS_LABEL) FK 연결 + 형태 스냅샷 컬럼 제거.
--
-- 배경: 프리셋 자식 LS_LABEL_PRESET_CODE 는 라벨 코드 문자열(LBL_CD)만 보유하고
--   라벨 마스터(LS_LABEL)와의 연결이 없었다. 형태(BBOX/POLYGON)는 프리셋에 스냅샷
--   (BBOX_ENABLED/POLYGON_ENABLED)으로 중복 저장되어 마스터 LBL_TYPE_CD 와 이원화됐다.
--
-- 변경 (확정 설계):
--   1. LBL_ID(BIGINT, NULL) 컬럼 추가 — 마스터 PK 재사용. nullable = '미연결' 허용.
--   2. 이름 매칭 backfill: UPPER(TRIM(LBL_CD)) = UPPER(TRIM(LBL_NM)) AND USE_YN='Y'.
--      매칭 실패 행은 LBL_ID null(미연결)로 남긴다 — 자동 생성/삭제 없음.
--   3. LBL_ID → LS_LABEL(LBL_ID) FK 제약 (NOT NULL 미부여: 미연결 허용).
--      ON DELETE 절 없음 — 라벨은 soft delete 만 하므로 하드 삭제로 FK 가 깨질 일 없음.
--   4. FK 컬럼 인덱스 추가 (외래키 컬럼 인덱스 규칙).
--   5. BBOX_ENABLED / POLYGON_ENABLED 스냅샷 컬럼 제거 — 형태는 마스터 LBL_TYPE_CD 가 소유.
--      V85 에서 재작성된 CHECK 제약(CK_LS_LABEL_PRESET_CODE_ANNOTATION)을 컬럼 DROP 전에 먼저 제거.
--
--   LBL_CD 는 미연결 행의 표시용으로 nullable 유지(제거하지 않음).
--   조회 join·CRUD·오토라벨 형태 파생은 후속 Phase — 본 마이그레이션은 스키마 전환만.
-- =============================================================================

-- 1) LBL_ID 컬럼 추가 (미연결 허용 → NULL)
ALTER TABLE LS_LABEL_PRESET_CODE
    ADD COLUMN IF NOT EXISTS LBL_ID BIGINT NULL;

-- 2) 이름 매칭 backfill (PostgreSQL UPDATE ... FROM). 대소문자/공백 무시, 활성 라벨만.
UPDATE LS_LABEL_PRESET_CODE pc
    SET LBL_ID = l.LBL_ID
    FROM LS_LABEL l
    WHERE UPPER(TRIM(pc.LBL_CD)) = UPPER(TRIM(l.LBL_NM))
      AND l.USE_YN = 'Y';

-- 3) LS_LABEL 참조 FK 제약 (NOT NULL 미부여 — 미연결 행 허용)
ALTER TABLE LS_LABEL_PRESET_CODE
    ADD CONSTRAINT FK_LS_LABEL_PRESET_CODE_LABEL
        FOREIGN KEY (LBL_ID) REFERENCES LS_LABEL(LBL_ID);

-- 4) FK 컬럼 인덱스
CREATE INDEX IF NOT EXISTS IDX_LS_LABEL_PRESET_CODE_LABEL
    ON LS_LABEL_PRESET_CODE (LBL_ID);

-- 5) 형태 스냅샷 제거 — CHECK 제약 선(先) 제거 후 컬럼 DROP
ALTER TABLE LS_LABEL_PRESET_CODE
    DROP CONSTRAINT IF EXISTS CK_LS_LABEL_PRESET_CODE_ANNOTATION;

ALTER TABLE LS_LABEL_PRESET_CODE
    DROP COLUMN IF EXISTS BBOX_ENABLED;

ALTER TABLE LS_LABEL_PRESET_CODE
    DROP COLUMN IF EXISTS POLYGON_ENABLED;
