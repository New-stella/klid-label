-- =============================================================================
-- V93: 스키마 내부 명명 불일치 해소 3그룹 (저작도구 전용 LS_* 만 대상, 무손실).
--
-- 그룹 1 — LBL_NM 길이 통일 (255 → 80, 마스터 LS_LABEL.LBL_NM=80 과 일치)
--   대상: LS_DATA_LBL.LBL_NM, LS_PORTAL_USER_LABEL.LBL_NM
--   컬럼명 불변(LBL_NM) — 길이만 축소. 실데이터는 마스터 클래스명 파생(≤64)이라 통과 예상.
--   사전 가드로 LENGTH>80 행 존재 시 RAISE EXCEPTION (데이터 손실 방지).
--
-- 그룹 2 — LS_AUTH_WORK_LOCK LCK 형제 통일 (물리 rename 3건)
--   LOCK_TARGET_CD→LCK_TARGET_CD, LOCK_STTS_CD→LCK_STTS_CD, LOCK_ID→LCK_ID
--   제약/인덱스(UK_LS_AUTH_WORK_LOCK_ID·IDX 2종·V69 partial unique UX_..._RAW_ACTIVE 의
--   WHERE 절)는 PostgreSQL 이 컬럼 rename 을 자동 follow 하므로 별도 수정 불필요.
--
-- 그룹 3 — ATTR→ATRB 컬럼 통일 (물리 rename 4건, 테이블명·인덱스명·FK명은 유지=별건)
--   LS_LABEL_ATTR.ATTR_ID→ATRB_ID
--   LS_DATA_LBL_ATTR_VAL.ATTR_VAL_ID→ATRB_VL_ID, .ATTR_ID→ATRB_ID, .ATTR_VL→ATRB_VL
--   FK_LS_DATA_LBL_ATTR_ATTR·UK 2종·IDENTITY 는 자동 follow.
--
-- 뷰 의존성 (PostgreSQL 은 뷰가 참조하는 컬럼의 타입/rename 변경을 차단):
--   V_COMPLETED_LABEL       ← lbl.LBL_NM (그룹1 대상)      → DROP → ALTER → 재생성
--   V_COMPLETED_LABEL_ATTR  ← av.ATTR_*·attr.ATTR_ID (그룹3) → DROP → RENAME → 재생성
--   두 뷰 모두 출력 컬럼명(외부 관제 계약)은 절대 불변 — V92 정의 그대로, 출력 alias 만 보존.
-- 참조 스타일: V92(widen_columns), V84(align_column_types).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 대상 뷰 선(先) DROP (그룹1·그룹3 재생성 전제)
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_LABEL;
DROP VIEW IF EXISTS V_COMPLETED_LABEL_ATTR;

-- -----------------------------------------------------------------------------
-- 그룹 1) LBL_NM 255→80 축소 (사전 가드 후 무손실 확인)
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    over_cnt BIGINT;
BEGIN
    SELECT COUNT(*) INTO over_cnt
      FROM (
        SELECT 1 FROM LS_DATA_LBL          WHERE LENGTH(LBL_NM) > 80
        UNION ALL
        SELECT 1 FROM LS_PORTAL_USER_LABEL WHERE LENGTH(LBL_NM) > 80
      ) t;
    IF over_cnt > 0 THEN
        RAISE EXCEPTION
            'V93 중단: LBL_NM 길이 80 초과 행 % 건 존재 — 80 축소 시 데이터 손실. 선 정리 필요.',
            over_cnt;
    END IF;
END $$;

ALTER TABLE LS_DATA_LBL          ALTER COLUMN LBL_NM TYPE VARCHAR(80);
ALTER TABLE LS_PORTAL_USER_LABEL ALTER COLUMN LBL_NM TYPE VARCHAR(80);

-- -----------------------------------------------------------------------------
-- 그룹 2) LS_AUTH_WORK_LOCK LCK 형제 rename (제약/인덱스 자동 follow)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN LOCK_TARGET_CD TO LCK_TARGET_CD;
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN LOCK_STTS_CD   TO LCK_STTS_CD;
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN LOCK_ID        TO LCK_ID;

-- -----------------------------------------------------------------------------
-- 그룹 3) ATTR→ATRB 컬럼 rename (FK/UK/IDENTITY 자동 follow)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_ATTR        RENAME COLUMN ATTR_ID     TO ATRB_ID;
ALTER TABLE LS_DATA_LBL_ATTR_VAL RENAME COLUMN ATTR_VAL_ID TO ATRB_VL_ID;
ALTER TABLE LS_DATA_LBL_ATTR_VAL RENAME COLUMN ATTR_ID     TO ATRB_ID;
ALTER TABLE LS_DATA_LBL_ATTR_VAL RENAME COLUMN ATTR_VL     TO ATRB_VL;

-- -----------------------------------------------------------------------------
-- 뷰 재생성 (V92 정의와 동일 — 출력 컬럼명/계약 보존)
-- -----------------------------------------------------------------------------

-- V_COMPLETED_LABEL — V92 정의 그대로. lbl.LBL_NM(VARCHAR80) 자연 반영, 출력 alias 보존.
CREATE VIEW V_COMPLETED_LABEL AS
SELECT
    lbl.LBL_SN,
    lbl.SRC_SN,
    src.RAW_SN,
    src.FRM_NO AS FRAME_NO,
    lbl.LBL_TYPE_CD,
    lbl.LBL_ID     AS LABEL_ID,
    lbl.LBL_NM     AS LABEL,
    lbl.POINT_CN   AS POINTS_JSON,
    lbl.TRCK_ID,
    lbl.REG_USER_NO,
    lbl.REG_DT  AS LABEL_REG_DT,
    lbl.MDFCN_DT AS LABEL_UPD_DT,
    label.LBL_NM        AS LABEL_NAME,
    label.COLR_VL       AS LABEL_COLOR,
    label.LBL_TYPE_CD   AS LABEL_TYPE,
    label.SORT_SEQ      AS LABEL_SORT_NO
FROM LS_DATA_LBL lbl
INNER JOIN LS_DATA_SRC src ON src.SRC_SN  = lbl.SRC_SN
LEFT  JOIN LS_LABEL    label ON label.LBL_ID = lbl.LBL_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

-- V_COMPLETED_LABEL_ATTR — V92 정의 기반. 물리 컬럼만 ATRB_* 로 바뀌고
-- 출력 컬럼명(ATTR_VAL_ID / ATTR_ID / ATTR_VAL 등)은 외부 관제 계약이라 alias 로 보존.
CREATE VIEW V_COMPLETED_LABEL_ATTR AS
SELECT
    av.ATRB_VL_ID      AS ATTR_VAL_ID,
    av.LBL_SN,
    av.ATRB_ID         AS ATTR_ID,
    attr.ATRB_NM       AS ATTR_NAME,
    attr.INPUT_TYPE_CD AS ATTR_INPUT_TYPE,
    attr.VALUES_CN     AS ATTR_VALUES_JSON,
    attr.DFLT_VL       AS ATTR_DEFAULT_VAL,
    av.ATRB_VL         AS ATTR_VAL,
    av.REG_DT,
    av.MDFCN_DT
FROM LS_DATA_LBL_ATTR_VAL av
LEFT JOIN LS_LABEL_ATTR attr ON attr.ATRB_ID = av.ATRB_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_DATA_LBL lbl
      JOIN LS_DATA_SRC src       ON src.SRC_SN      = lbl.SRC_SN
      JOIN LS_RAW_DATA_STATUS s  ON s.RAW_DATA_ID   = src.RAW_SN
     WHERE lbl.LBL_SN     = av.LBL_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
