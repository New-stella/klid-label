-- =============================================================================
-- V61: 물리 컬럼명 2차 정렬 — 사업 용어사전 기존 표준 약어에 코드를 맞춘다.
--
-- V60(1차, 27건) 다음 단계로, 잔여 12개 컬럼을 표준 약어로 rename 한다.
-- 테이블명은 불변, 컬럼명만 변경하며 LS_* 전용 테이블만 대상으로 한다
-- (MNG_*/QRTZ_* 공유 스키마 절대 불변).
--
-- PostgreSQL 의 RENAME COLUMN 은 해당 컬럼을 참조하는 인덱스/UNIQUE/FK 를 자동
-- 추종하므로 제약 재정의는 불필요하다. 다만 데이터마트 View(V_COMPLETED_VIDEO/
-- V_COMPLETED_FRAME)는 변경된 컬럼을 직접 참조하므로 CREATE OR REPLACE 로 재정의하되
-- 출력 컬럼명은 기존 그대로 alias 유지하여 데이터마트 소비 계약을 보존한다.
--
-- 적용 근거: 작업 범위 rename 맵. JPA ddl-auto=validate 환경이므로 엔티티
-- @Column(name=...) 와 1:1 정합되어야 부팅 가능.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- LS_DATA_RAW
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_RAW RENAME COLUMN DE_IDNTF_YN TO DE_IDENT_YN;
ALTER TABLE LS_DATA_RAW RENAME COLUMN DURATION_SEC TO VDO_LEN_SEC;

-- -----------------------------------------------------------------------------
-- LS_DATA_SRC
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_SRC RENAME COLUMN FRAME_NO TO FRM_NO;

-- -----------------------------------------------------------------------------
-- LS_TASK_EVENT_LOG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_TASK_EVENT_LOG RENAME COLUMN EVENT_SEQ TO EVNT_ID;

-- -----------------------------------------------------------------------------
-- LS_RAW_DATA_STATUS — JPA @Version 낙관적 잠금 컬럼 (이름만 변경)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_RAW_DATA_STATUS RENAME COLUMN VERSION TO VER;

-- -----------------------------------------------------------------------------
-- LS_DATA_ISSUE — JPA @Version 낙관적 잠금 컬럼 (이름만 변경)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_ISSUE RENAME COLUMN VERSION TO VER;

-- -----------------------------------------------------------------------------
-- LS_SYSTEM_CONFIG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_SYSTEM_CONFIG RENAME COLUMN CONFIG_KEY TO STNG_KEY;
ALTER TABLE LS_SYSTEM_CONFIG RENAME COLUMN CONFIG_VL TO STNG_VALUE;
ALTER TABLE LS_SYSTEM_CONFIG RENAME COLUMN CONFIG_TYPE_CD TO STNG_TYPE_CD;

-- -----------------------------------------------------------------------------
-- LS_LABEL_VERSION
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_VERSION RENAME COLUMN ACTIVE_YN TO ACTVTN_YN;

-- -----------------------------------------------------------------------------
-- LS_AUTH_WORK_LOCK
-- -----------------------------------------------------------------------------
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN EXPIRE_DT TO EXPD_DT;

-- -----------------------------------------------------------------------------
-- LS_NOTICE
-- -----------------------------------------------------------------------------
ALTER TABLE LS_NOTICE RENAME COLUMN PIN_YN TO UPEND_FIX_YN;


-- =============================================================================
-- 데이터마트 View 재정의 (rename 반영만 — 신규 컬럼 추가/노출 범위 변경 없음).
-- V_COMPLETED_META 는 V60 에서 재정의되었고 본 V61 의 rename 대상 컬럼을 참조하지
-- 않으므로 그대로 둔다. V_COMPLETED_LABEL 은 V60 정의가 src.FRAME_NO 를 참조하므로
-- FRM_NO rename 반영 위해 재정의한다.
-- =============================================================================

-- V_COMPLETED_LABEL — V60 정의 유지 + LS_DATA_SRC.FRM_NO 반영.
-- 출력 컬럼명 보존: FRM_NO → 기존 출력명 FRAME_NO, LBL_ID → LABEL_ID alias.
CREATE OR REPLACE VIEW V_COMPLETED_LABEL AS
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

-- V_COMPLETED_VIDEO — LS_DATA_RAW.DE_IDENT_YN/VDO_LEN_SEC, LS_RAW_DATA_STATUS.VER 반영.
-- 출력 컬럼명 보존: DE_IDENT_YN→기존 출력명 DE_IDNTF_YN, VDO_LEN_SEC→DURATION_SEC,
-- VER→기존 출력명 REVIEW_VERSION 으로 alias 하여 데이터마트 UPSERT 매핑을 보존한다.
CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    r.RAW_SN,
    r.VMS_CLIP_ID,
    r.VMS_CCTV_ID,
    r.EVNT_TYPE_CD,
    r.LCLGV_CD,
    r.PRVC_TYPE_CD,
    r.PRVC_YN,
    r.DE_IDENT_YN      AS DE_IDNTF_YN,
    r.RAW_FILE_PATH_NM AS ORIGINAL_VIDEO_PATH,
    r.SHT_DT           AS CAPTURED_AT,
    r.VDO_LEN_SEC      AS DURATION_SEC,
    r.PARENT_RAW_SN,
    r.DATA_STTS_CD AS BATCH_STTS_CD,
    s.DATA_STTS_CD AS REVIEW_STTS_CD,
    s.UPD_DT       AS REVIEW_COMPLETED_AT,
    s.VER          AS REVIEW_VERSION
FROM LS_DATA_RAW r
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = r.RAW_SN
WHERE s.DATA_STTS_CD = 'APPROVED';

-- V_COMPLETED_FRAME — LS_DATA_SRC.FRM_NO 반영.
-- 출력 컬럼명 보존: FRM_NO → 기존 출력명 FRAME_NO 로 alias.
CREATE OR REPLACE VIEW V_COMPLETED_FRAME AS
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
