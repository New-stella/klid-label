-- =============================================================================
-- V92: 공공 표준 도메인 정합 — 컬럼 길이 확대 7건 (gov-first 타입/크기 감사 승인분).
--
-- 저작도구 전용 LS_* 컬럼만 대상. 전부 데이터 무손실 확대(길이만 증가):
--   LS_LABEL.LBL_NM               VARCHAR(64)   → VARCHAR(80)    라벨명=명V80
--   LS_LABEL_ATTR.ATRB_NM         VARCHAR(64)   → VARCHAR(100)   속성명=명V100
--   LS_NOTICE_ATTACH.ORGNL_FILE_NM VARCHAR(255) → VARCHAR(300)   원본파일명=명V300
--   LS_NOTICE_ATTACH.STRG_FILE_NM VARCHAR(255)  → VARCHAR(300)   저장파일명=명V300
--   LS_AUTH_WORK_LOCK.RMV_RSN     VARCHAR(500)  → VARCHAR(4000)  해제사유=내용V4000
--   LS_DATA_AUG_RVW.RJCT_RSN      VARCHAR(1000) → VARCHAR(4000)  반려사유=내용V4000
--   LS_DATA_META_REVIEW.RJCT_RSN  VARCHAR(1000) → VARCHAR(4000)  반려사유=내용V4000
--
-- 뷰 의존성 (PostgreSQL 은 뷰가 참조하는 컬럼의 타입/길이 변경을 차단):
--   LS_LABEL.LBL_NM       → V_COMPLETED_LABEL       (label.LBL_NM AS LABEL_NAME, 최신 정의 V84)
--   LS_LABEL_ATTR.ATRB_NM → V_COMPLETED_LABEL_ATTR  (attr.ATRB_NM AS ATTR_NAME, 최신 정의 V91)
-- 두 뷰만 DROP → ALTER → 직전 최신 정의 그대로 재생성(출력 별칭·컬럼 계약 불변).
-- 나머지 5개 컬럼(파일명·사유)은 어떤 뷰도 SELECT 하지 않아 뷰 처리 불필요.
-- CHECK 제약이 길이를 참조하는 컬럼 없음(확인 완료).
-- 참조 스타일: V84(align_column_types) / V87(widen_reject_rsn).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 대상 뷰 선(先) DROP (재생성 전제)
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_LABEL;
DROP VIEW IF EXISTS V_COMPLETED_LABEL_ATTR;

-- -----------------------------------------------------------------------------
-- 1) 컬럼 길이 확대 (전부 비파괴 — 데이터 손실 0)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL            ALTER COLUMN LBL_NM        TYPE VARCHAR(80);
ALTER TABLE LS_LABEL_ATTR       ALTER COLUMN ATRB_NM       TYPE VARCHAR(100);
ALTER TABLE LS_NOTICE_ATTACH    ALTER COLUMN ORGNL_FILE_NM TYPE VARCHAR(300);
ALTER TABLE LS_NOTICE_ATTACH    ALTER COLUMN STRG_FILE_NM  TYPE VARCHAR(300);
ALTER TABLE LS_AUTH_WORK_LOCK   ALTER COLUMN RMV_RSN        TYPE VARCHAR(4000);
ALTER TABLE LS_DATA_AUG_RVW     ALTER COLUMN RJCT_RSN       TYPE VARCHAR(4000);
ALTER TABLE LS_DATA_META_REVIEW ALTER COLUMN RJCT_RSN       TYPE VARCHAR(4000);

-- -----------------------------------------------------------------------------
-- 2) 뷰 재생성 (V84/V91 최신 정의와 동일 — 출력 컬럼명/계약 보존. 길이만 자연 반영)
-- -----------------------------------------------------------------------------

-- V_COMPLETED_LABEL — V84 정의 그대로. label.LBL_NM(VARCHAR80) 자연 반영, 출력 alias 보존.
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

-- V_COMPLETED_LABEL_ATTR — V91 정의 그대로. attr.ATRB_NM(VARCHAR100) 자연 반영, 출력 alias 보존.
CREATE VIEW V_COMPLETED_LABEL_ATTR AS
SELECT
    av.ATTR_VAL_ID,
    av.LBL_SN,
    av.ATTR_ID,
    attr.ATRB_NM       AS ATTR_NAME,
    attr.INPUT_TYPE_CD AS ATTR_INPUT_TYPE,
    attr.VALUES_CN     AS ATTR_VALUES_JSON,
    attr.DFLT_VL       AS ATTR_DEFAULT_VAL,
    av.ATTR_VL         AS ATTR_VAL,
    av.REG_DT,
    av.MDFCN_DT
FROM LS_DATA_LBL_ATTR_VAL av
LEFT JOIN LS_LABEL_ATTR attr ON attr.ATTR_ID = av.ATTR_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_DATA_LBL lbl
      JOIN LS_DATA_SRC src       ON src.SRC_SN      = lbl.SRC_SN
      JOIN LS_RAW_DATA_STATUS s  ON s.RAW_DATA_ID   = src.RAW_SN
     WHERE lbl.LBL_SN     = av.LBL_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
