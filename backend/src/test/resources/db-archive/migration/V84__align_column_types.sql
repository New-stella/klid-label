-- =============================================================================
-- V84: 안전 타입 정합 (비파괴 확대) — 표준 도메인(사업 표준단어/D9) 정합.
--
-- 저작도구 전용 LS_* 컬럼만 대상. 전부 데이터 무손실 확대:
--   A. INTEGER → BIGINT (표준 일련번호 B20)
--   B. VARCHAR 길이 확대
--   C. VARCHAR → TEXT (표준 도메인/D9 문서가 TEXT — LS_BATCH_PROC_LOG 은 이미 TEXT, 멱등 재확인)
--
-- 뷰 의존성:
--   LS_DATA_SRC.FRM_NO          → V_COMPLETED_FRAME / V_COMPLETED_LABEL
--   LS_DATA_META.OTSD_JOB_ID    → V_COMPLETED_META
-- PostgreSQL 은 뷰가 참조하는 컬럼의 타입 변경(길이 포함)을 차단하므로,
-- 세 뷰를 DROP → ALTER → 최신 정의(V61/V60)와 동일하게 재생성한다(출력 컬럼 계약 보존).
--
-- 제외:
--   LS_TUS_UPLOAD          : Phase 5 에서 테이블째 삭제 예정 — 미변경.
--   MNG_* / QRTZ_*         : 관제 공유/인프라 스키마 — 미변경.
--   LS_DATA_RAW.VMS_CLIP_ID(128)/VMS_CCTV_ID(64) : 이미 표준 길이 — ALTER 불필요(뷰 의존 회피 위해 제외).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 대상 뷰 선(先) DROP (재생성 전제)
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_LABEL;
DROP VIEW IF EXISTS V_COMPLETED_FRAME;
DROP VIEW IF EXISTS V_COMPLETED_META;

-- -----------------------------------------------------------------------------
-- A. INTEGER → BIGINT (표준 일련번호 B20)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_SRC ALTER COLUMN FRM_NO     TYPE BIGINT;
ALTER TABLE LS_DATA_SRC ALTER COLUMN VDO_FRM_NO TYPE BIGINT;

-- -----------------------------------------------------------------------------
-- B. VARCHAR 길이 확대
-- -----------------------------------------------------------------------------
ALTER TABLE LS_ISSUE_COMMENT    ALTER COLUMN CMNT_CN    TYPE VARCHAR(4000);
ALTER TABLE LS_DEIDENT_PROC_LOG ALTER COLUMN ERR_MSG_CN TYPE VARCHAR(4000);
ALTER TABLE LS_BATCH_PROC_LOG   ALTER COLUMN ERR_MSG_CN TYPE VARCHAR(4000);
ALTER TABLE LS_SYSTEM_CONFIG    ALTER COLUMN STNG_VALUE TYPE VARCHAR(4000);
ALTER TABLE LS_NOTICE_ATTACH    ALTER COLUMN FILE_PATH  TYPE VARCHAR(1000);
ALTER TABLE LS_MARKING          ALTER COLUMN EVNT_NM    TYPE VARCHAR(200);

-- IDMP_KEY → 128 (멱등키 표준). PK/UK 인덱스는 ALTER 시 자동 재구성.
ALTER TABLE LS_WEBHOOK_IDEMPOTENCY    ALTER COLUMN IDMP_KEY TYPE VARCHAR(128);
ALTER TABLE LS_DATA_AUG               ALTER COLUMN IDMP_KEY TYPE VARCHAR(128);
ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK ALTER COLUMN IDMP_KEY TYPE VARCHAR(128);
ALTER TABLE LS_DATA_META              ALTER COLUMN IDMP_KEY TYPE VARCHAR(128);

-- OTSD_JOB_ID → 200 (외부 작업 ID 표준). UK/인덱스는 ALTER 시 자동 재구성.
ALTER TABLE LS_WEBHOOK_IDEMPOTENCY ALTER COLUMN OTSD_JOB_ID TYPE VARCHAR(200);
ALTER TABLE LS_DATA_AUG            ALTER COLUMN OTSD_JOB_ID TYPE VARCHAR(200);
ALTER TABLE LS_DEIDENT_PROC_LOG    ALTER COLUMN OTSD_JOB_ID TYPE VARCHAR(200);
ALTER TABLE LS_DATA_META           ALTER COLUMN OTSD_JOB_ID TYPE VARCHAR(200);

-- -----------------------------------------------------------------------------
-- C. VARCHAR → TEXT (LS_BATCH_PROC_LOG 페이로드 — 이미 TEXT, 표준 정합 멱등 재확인)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_BATCH_PROC_LOG ALTER COLUMN REQ_PAYLOAD_CN TYPE TEXT;
ALTER TABLE LS_BATCH_PROC_LOG ALTER COLUMN RES_PAYLOAD_CN TYPE TEXT;

-- -----------------------------------------------------------------------------
-- 9) 뷰 재생성 (V61/V60 최신 정의와 동일 — 출력 컬럼명/계약 보존. rename/노출 변경 없음)
-- -----------------------------------------------------------------------------

-- V_COMPLETED_LABEL — LS_DATA_SRC.FRM_NO(BIGINT) 반영. 출력명 FRAME_NO/LABEL_ID alias 보존.
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

-- V_COMPLETED_FRAME — LS_DATA_SRC.FRM_NO(BIGINT) 반영. 출력명 FRAME_NO alias 보존.
CREATE VIEW V_COMPLETED_FRAME AS
SELECT
    src.SRC_SN,
    src.RAW_SN,
    src.FRM_NO AS FRAME_NO,
    src.SRC_FILE_PATH_NM          AS ORIGINAL_PATH,
    src.DE_IDNTF_SRC_FILE_PATH_NM AS DEIDENTIFIED_PATH,
    src.SHT_DT AS CAPTURED_AT,
    src.REG_DT,
    src.UPD_DT
FROM LS_DATA_SRC src
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

-- V_COMPLETED_META — LS_DATA_META.OTSD_JOB_ID(VARCHAR200) 반영. 출력명 EXTERNAL_JOB_ID alias 보존.
CREATE VIEW V_COMPLETED_META AS
SELECT
    meta.META_SN,
    meta.RAW_SN,
    meta.META_KEY,
    meta.META_VL,
    meta.OTSD_JOB_ID AS EXTERNAL_JOB_ID,
    mrev.DATA_META_REVIEW_SN,
    mrev.META_TYPE_CD,
    mrev.SRC_SYS_CD,
    mrev.RVW_STTS_CD,
    mrev.RVW_ID,
    mrev.RVW_DT AS REVIEWED_AT
FROM LS_DATA_META meta
INNER JOIN LS_DATA_META_REVIEW mrev ON mrev.DATA_META_SN = meta.META_SN
WHERE mrev.RVW_STTS_CD = 'APPROVED'
  AND EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = meta.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
