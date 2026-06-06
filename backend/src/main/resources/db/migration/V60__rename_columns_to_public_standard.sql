-- =============================================================================
-- V60: 물리 컬럼명을 행안부 공공표준 + 사업 용어사전 약어에 맞춰 rename
--
-- 테이블명은 유지하고 컬럼명만 변경한다. LS_* 전용 테이블만 대상 (MNG_*/QRTZ_* 제외).
-- PostgreSQL 의 RENAME COLUMN 은 해당 컬럼을 참조하는 인덱스/제약/FK/뷰 정의를
-- 자동으로 따라 갱신하므로 별도 제약 재정의는 불필요하다. 데이터마트 View 2종은
-- 출력 컬럼명/소스를 명확히 하기 위해 CREATE OR REPLACE 로 재정의한다.
--
-- 적용 근거: 작업 범위 rename 맵. JPA ddl-auto=validate 환경이므로 엔티티
-- @Column(name=...) 와 1:1 정합되어야 부팅 가능.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- LS_DATA_META
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_META RENAME COLUMN IDEMPOTENCY_KEY TO IDMP_KEY;
ALTER TABLE LS_DATA_META RENAME COLUMN EXTERNAL_JOB_ID TO OTSD_JOB_ID;
ALTER TABLE LS_DATA_META RENAME COLUMN RETRY_COUNT TO RTRY_NMTM;

-- -----------------------------------------------------------------------------
-- LS_DEIDENT_PROC_LOG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN EXTERNAL_JOB_ID TO OTSD_JOB_ID;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN ERROR_CD TO ERR_CD;
ALTER TABLE LS_DEIDENT_PROC_LOG RENAME COLUMN ERROR_MSG TO ERR_MSG_CN;

-- -----------------------------------------------------------------------------
-- LS_TASK_EVENT_LOG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_TASK_EVENT_LOG RENAME COLUMN EVENT_TYPE_CD TO EVNT_TYPE_CD;

-- -----------------------------------------------------------------------------
-- LS_CONTROL_NOTIFY_FALLBACK
-- -----------------------------------------------------------------------------
ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK RENAME COLUMN EVENT_TYPE_CD TO EVNT_TYPE_CD;
ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK RENAME COLUMN RTRY_CNT TO RTRY_NMTM;
ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK RENAME COLUMN MAX_RTRY_CNT TO MAX_RTRY_NMTM;
ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK RENAME COLUMN LAST_ERR_MSG TO LAST_ERR_MSG_CN;

-- -----------------------------------------------------------------------------
-- LS_DATA_AUG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_AUG RENAME COLUMN RETRY_COUNT TO RTRY_NMTM;

-- -----------------------------------------------------------------------------
-- LS_BATCH_PROC_LOG
-- -----------------------------------------------------------------------------
ALTER TABLE LS_BATCH_PROC_LOG RENAME COLUMN RTRY_CNT TO RTRY_NMTM;
ALTER TABLE LS_BATCH_PROC_LOG RENAME COLUMN START_DT TO BGNG_DT;
ALTER TABLE LS_BATCH_PROC_LOG RENAME COLUMN ERROR_CD TO ERR_CD;
ALTER TABLE LS_BATCH_PROC_LOG RENAME COLUMN ERROR_MSG TO ERR_MSG_CN;

-- -----------------------------------------------------------------------------
-- LS_META
-- -----------------------------------------------------------------------------
ALTER TABLE LS_META RENAME COLUMN META_VAL TO META_VL;

-- -----------------------------------------------------------------------------
-- LS_NOTICE_ATTACH
-- -----------------------------------------------------------------------------
ALTER TABLE LS_NOTICE_ATTACH RENAME COLUMN FILE_SIZE TO FILE_SZ;

-- -----------------------------------------------------------------------------
-- LS_MARKING — CREATED_BY 는 BIGINT(사용자 번호) 이므로 REG_USER_NO 로 표준화
-- -----------------------------------------------------------------------------
ALTER TABLE LS_MARKING RENAME COLUMN CREATED_BY TO REG_USER_NO;

-- -----------------------------------------------------------------------------
-- LS_DATA_LBL
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_LBL RENAME COLUMN LABEL_ID TO LBL_ID;
ALTER TABLE LS_DATA_LBL RENAME COLUMN LABEL_NM TO LBL_NM;

-- -----------------------------------------------------------------------------
-- LS_LABEL
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL RENAME COLUMN LABEL_ID TO LBL_ID;
ALTER TABLE LS_LABEL RENAME COLUMN LABEL_NM TO LBL_NM;
ALTER TABLE LS_LABEL RENAME COLUMN LABEL_TYPE_CD TO LBL_TYPE_CD;

-- -----------------------------------------------------------------------------
-- LS_LABEL_ATTR
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_ATTR RENAME COLUMN LABEL_ID TO LBL_ID;

-- -----------------------------------------------------------------------------
-- LS_LABEL_VERSION
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_VERSION RENAME COLUMN LABEL_VERSION_SN TO LBL_VERSION_SN;
ALTER TABLE LS_LABEL_VERSION RENAME COLUMN LABEL_PAYLOAD TO LBL_PAYLOAD;

-- -----------------------------------------------------------------------------
-- LS_PORTAL_USER_LABEL
-- -----------------------------------------------------------------------------
ALTER TABLE LS_PORTAL_USER_LABEL RENAME COLUMN LABEL_NM TO LBL_NM;


-- =============================================================================
-- 데이터마트 View 재정의 (rename 반영만 — 신규 컬럼 추가/노출 범위 변경 없음)
-- =============================================================================

-- V_COMPLETED_LABEL — LS_DATA_LBL.LBL_ID/LBL_NM, LS_LABEL.LBL_ID/LBL_NM/LBL_TYPE_CD 반영.
-- 데이터마트 소비 컨트랙트(출력 컬럼명) 보존: 물리 rename 된 LBL_ID 는 기존 출력명
-- LABEL_ID 로 alias 하여 마트 UPSERT 컬럼 매핑이 깨지지 않게 한다.
CREATE OR REPLACE VIEW V_COMPLETED_LABEL AS
SELECT
    lbl.LBL_SN,
    lbl.SRC_SN,
    src.RAW_SN,
    src.FRAME_NO,
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

-- V_COMPLETED_META — LS_DATA_META.OTSD_JOB_ID 반영 (META_VL 는 기존부터 동일).
-- 출력 컬럼명 보존: OTSD_JOB_ID 를 기존 출력명 EXTERNAL_JOB_ID 로 alias.
CREATE OR REPLACE VIEW V_COMPLETED_META AS
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
