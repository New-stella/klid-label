-- =============================================================================
-- V52: 데이터마트 적재용 View 4종
--
-- 목적: 관제서버 데이터마트가 검수 완료(APPROVED) 영상의 원본·비식별 프레임,
--      라벨, 시계열 메타를 영상 1건(RAW_SN) 단위로 일관되게 조회할 수 있도록
--      평면화된 뷰 인터페이스를 제공한다.
--
-- 검수 완료 판정: LS_RAW_DATA_STATUS.DATA_STTS_CD = 'APPROVED'
-- 메타 승인 판정: LS_DATA_META_REVIEW.RVW_STTS_CD = 'APPROVED'
-- 프레임 페어 : LS_DATA_SRC.FILE_PATH (원본) + SRC_BKUP_FILE_PATH (비식별)
--
-- 사용 패턴(권장): TASK_COMPLETED 통지 수신 → RAW_SN 으로 4개 View 단순 SELECT
--                → 데이터마트 영상 1건 = 1 row UPSERT.
--
-- 비식별 영상 파일 경로는 운영 컨벤션상 STORAGE_RAW_PATH → STORAGE_DEIDENTIFIED_PATH
-- 치환으로 도출되므로 본 View 에는 원본 경로만 노출한다 (애플리케이션 레이어 책임).
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. V_COMPLETED_VIDEO — 검수 완료 영상 메타
--
-- 데이터마트 검색·필터링용 평면 컬럼. PARENT_RAW_SN 으로 증강 영상의 원본 추적 가능.
-- REVIEW_COMPLETED_AT 은 LS_RAW_DATA_STATUS.UPD_DT(APPROVED 전이 시각)로 매핑한다.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    r.RAW_SN,
    r.VMS_CLIP_ID,
    r.VMS_CCTV_ID,
    r.EVNT_TYPE_CD,
    r.LCLGV_CD,
    r.PRVC_TYPE_CD,
    r.PRVC_YN,
    r.DE_IDNTF_YN,
    r.FILE_PATH AS ORIGINAL_VIDEO_PATH,
    r.CAPTURED_AT,
    r.DURATION_SEC,
    r.PARENT_RAW_SN,
    r.DATA_STTS_CD AS BATCH_STTS_CD,
    s.DATA_STTS_CD AS REVIEW_STTS_CD,
    s.UPD_DT       AS REVIEW_COMPLETED_AT,
    s.VERSION      AS REVIEW_VERSION
FROM LS_DATA_RAW r
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = r.RAW_SN
WHERE s.DATA_STTS_CD = 'APPROVED';


-- -----------------------------------------------------------------------------
-- 2. V_COMPLETED_FRAME — 프레임 페어 (원본 + 비식별)
--
-- 한 row 안에 원본 프레임 경로(ORIGINAL_PATH)와 비식별 프레임 경로(DEIDENTIFIED_PATH)
-- 가 같이 매핑되어 있다. ANONY 영상은 DEIDENTIFIED_PATH 가 NULL 일 수 있다.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_FRAME AS
SELECT
    src.SRC_SN,
    src.RAW_SN,
    src.FRAME_NO,
    src.FILE_PATH          AS ORIGINAL_PATH,
    src.SRC_BKUP_FILE_PATH AS DEIDENTIFIED_PATH,
    src.CAPTURED_AT,
    src.REG_DT,
    src.UPD_DT
FROM LS_DATA_SRC src
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);


-- -----------------------------------------------------------------------------
-- 3. V_COMPLETED_LABEL — 라벨 좌표 + 마스터 코드 평면
--
-- 프레임(SRC_SN)별 라벨에 영상 RAW_SN, FRAME_NO 와 라벨 마스터(LS_LABEL)의
-- LABEL_NM / COLR_VL / LABEL_TYPE_CD 를 함께 노출한다. 검수 완료 영상의 라벨만 조회된다.
-- 라벨 속성값(LS_DATA_LBL_ATTR_VAL)은 V_COMPLETED_LABEL_ATTR 로 분리.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_LABEL AS
SELECT
    lbl.LBL_SN,
    lbl.SRC_SN,
    src.RAW_SN,
    src.FRAME_NO,
    lbl.LBL_TYPE_CD,
    lbl.LABEL_ID,
    lbl.LABEL_NM   AS LABEL,
    lbl.POINT_CN   AS POINTS_JSON,
    lbl.TRCK_ID,
    lbl.REG_USER_NO,
    lbl.REG_DT  AS LABEL_REG_DT,
    lbl.MDFCN_DT AS LABEL_UPD_DT,
    label.LABEL_NM      AS LABEL_NAME,
    label.COLR_VL       AS LABEL_COLOR,
    label.LABEL_TYPE_CD AS LABEL_TYPE,
    label.SORT_SEQ      AS LABEL_SORT_NO
FROM LS_DATA_LBL lbl
INNER JOIN LS_DATA_SRC src ON src.SRC_SN  = lbl.SRC_SN
LEFT  JOIN LS_LABEL    label ON label.LABEL_ID = lbl.LABEL_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);


-- -----------------------------------------------------------------------------
-- 3b. V_COMPLETED_LABEL_ATTR — 라벨 속성값
--
-- 라벨(LBL_SN)별 속성값과 속성 정의(LS_LABEL_ATTR.ATTR_NM/INPUT_TYPE_CD/VALUES_CN)을
-- 함께 노출. 검수 완료 영상에 속한 라벨의 속성값만 조회된다.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_LABEL_ATTR AS
SELECT
    av.ATTR_VAL_ID,
    av.LBL_SN,
    av.ATTR_ID,
    attr.ATTR_NM       AS ATTR_NAME,
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


-- -----------------------------------------------------------------------------
-- 4. V_COMPLETED_META — 시계열 메타 (승인본)
--
-- VLM 또는 외부 인계 메타 중 REVIEWER 가 승인(RVW_STTS_CD='APPROVED')한 것만 노출.
-- LS_DATA_META 는 META_KEY/META_VL 의 KV 형태로 영상 단위(RAW_SN)로 적재됨.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_META AS
SELECT
    meta.META_SN,
    meta.RAW_SN,
    meta.META_KEY,
    meta.META_VL,
    meta.EXTERNAL_JOB_ID,
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
