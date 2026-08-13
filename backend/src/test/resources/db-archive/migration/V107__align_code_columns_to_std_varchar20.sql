-- =============================================================================
-- V107: LS_* 코드성 컬럼을 표준 도메인 길이(VARCHAR 20)로 정합
--
-- 근거: docs/표준용어 코드V20(VARCHAR 20) 정합 — 감리 지적("이벤트 관련 용어 타입/길이
--       제각각") 대응. 저작도구 소유 LS_* 테이블의 코드성 컬럼 길이를 32/8/10 에서
--       표준 도메인 코드V20(VARCHAR 20) 으로 통일한다.
--
-- 값 안전성(값 ≤20 확인):
--   대상 값은 모두 짧은 enum/코드다 — TASK_COMPLETED(14)·LABELER(7)·PENDING(7)·
--   APPROVED(8)·해상도/코덱 코드(RES_1080P 등 ≤10)·EV-상세이벤트코드(10) 등 모두 ≤20.
--   따라서 32→20 축소는 truncation 없음. SESN_CD(8→20)·LS_TUS_UPLOAD.LCLGV_CD(10→20)
--   은 확대(무손실).
--
-- 범위(엄수): LS_* 저작도구 소유 테이블만 변경한다.
--   - MNG_*/QRTZ_* 공유 스키마(예: MNG_EX_EVNT_TYPE.EVNT_TYPE_CD, MNG_EX_LOCAL_GOV.LCLGV_CD,
--     UP_EVNT_TYPE_CD)는 관제서버 소유라 미변경.
--   - 식별자 컬럼(REG_ID/MDFCN_ID/MDFR_ID/TRCK_ID)은 본 정합 대상 아님(별도 결정 대기).
--   - PJT_STTS_CD 는 소속 테이블 LS_PJT 가 V35 에서 DROP 되어 잔존 컬럼 없음(대상 없음).
--
-- View 의존 처리(Critical):
--   대상 컬럼 LS_RAW_DATA_STATUS.DATA_STTS_CD 를 데이터마트 5개 View 가(WHERE EXISTS 등)
--   참조하고, LS_DATA_RAW.DATA_STTS_CD 및 LS_DATASET_VIDEO_META.(EVNT_TYPE_CD/LCLGV_CD/
--   VDO_CDC/SESN_CD)를 V_COMPLETED_VIDEO 가 참조한다. PostgreSQL 은 View 가 참조하는 컬럼의
--   타입 변경을 차단("cannot alter type of a column used by a view or rule")하므로,
--   5개 View 를 선삭제 → ALTER → 최신 정의 그대로 재생성한다(계약/동작 불변).
--     - V_COMPLETED_VIDEO      : V102 정의 그대로
--     - V_COMPLETED_META       : V101 정의 그대로
--     - V_COMPLETED_FRAME      : V104 정의 그대로
--     - V_COMPLETED_LABEL      : V93 정의 계승(내용 동일)
--     - V_COMPLETED_LABEL_ATTR : V93 재생성 정의(물리 ATRB_*, 출력 alias 보존)
--
-- 인덱스/UK: LS_TASK_ASSIGNMENT.TASK_TYPE_CD(UK+IX)·LS_LABEL_PRESET.EVNT_TYPE_CD(UK) 는
--   varchar 길이 변경 시 PostgreSQL 이 인덱스를 자동 재구성한다(별도 DROP 불필요, 길이 의존
--   CHECK 제약 없음).
--
-- PostgreSQL 표준 문법.
-- =============================================================================

-- ------------------------------------------------------------------------------
-- 1) 의존 데이터마트 View 선삭제 (ALTER 차단 회피)
-- ------------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_VIDEO;
DROP VIEW IF EXISTS V_COMPLETED_META;
DROP VIEW IF EXISTS V_COMPLETED_FRAME;
DROP VIEW IF EXISTS V_COMPLETED_LABEL;
DROP VIEW IF EXISTS V_COMPLETED_LABEL_ATTR;

-- ------------------------------------------------------------------------------
-- 2) 코드성 컬럼 → VARCHAR(20)
-- ------------------------------------------------------------------------------
ALTER TABLE LS_DATA_RAW               ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);
ALTER TABLE LS_DATA_RAW               ALTER COLUMN LCLGV_CD     TYPE VARCHAR(20);
ALTER TABLE LS_DATA_RAW               ALTER COLUMN DATA_STTS_CD TYPE VARCHAR(20);

ALTER TABLE LS_LABEL_PRESET           ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);

ALTER TABLE LS_DATASET_VIDEO_META     ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META     ALTER COLUMN LCLGV_CD     TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META     ALTER COLUMN VDO_CDC      TYPE VARCHAR(20);
ALTER TABLE LS_DATASET_VIDEO_META     ALTER COLUMN SESN_CD      TYPE VARCHAR(20);

ALTER TABLE LS_TASK_ASSIGNMENT        ALTER COLUMN TASK_TYPE_CD TYPE VARCHAR(20);
ALTER TABLE LS_TASK_ASSIGN_HISTORY    ALTER COLUMN TASK_TYPE_CD TYPE VARCHAR(20);

ALTER TABLE LS_RAW_DATA_STATUS        ALTER COLUMN DATA_STTS_CD TYPE VARCHAR(20);
ALTER TABLE LS_TASK_EVENT_LOG         ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);

ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);

ALTER TABLE LS_TUS_UPLOAD             ALTER COLUMN EVNT_TYPE_CD TYPE VARCHAR(20);
ALTER TABLE LS_TUS_UPLOAD             ALTER COLUMN LCLGV_CD     TYPE VARCHAR(20);

-- ------------------------------------------------------------------------------
-- 3) 데이터마트 View 재생성 (최신 정의 그대로 — 출력 계약·동작 불변)
-- ------------------------------------------------------------------------------

-- 3-1. V_COMPLETED_VIDEO — V102 정의 그대로(라이브 APPROVED 게이트 포함)
CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    -- ===== 기존 출력 컬럼(계약) — 이름·순서·타입 보존 =====
    m.RAW_SN,
    m.VMS_CLIP_ID,
    m.VMS_CCTV_ID,
    m.EVNT_TYPE_CD,
    m.LCLGV_CD,
    m.PRVC_TYPE_CD,
    m.PRVC_YN,
    m.DE_IDENT_YN      AS DE_IDNTF_YN,
    m.RAW_FILE_PATH_NM AS ORIGINAL_VIDEO_PATH,
    m.SHT_DT           AS CAPTURED_AT,
    m.VDO_LEN_SEC      AS DURATION_SEC,
    m.ORGNL_RAW_SN,
    r.DATA_STTS_CD     AS BATCH_STTS_CD,       -- 라이브(배치 단계 상태)
    s.DATA_STTS_CD     AS REVIEW_STTS_CD,      -- 라이브(작업/검수 워크플로우 상태 — INNER 게이트로 항상 APPROVED)
    m.RVW_CMPL_DT      AS REVIEW_COMPLETED_AT, -- 스냅샷 동결(APPROVED 확정 시각)
    s.VER              AS REVIEW_VERSION,       -- 라이브(검수 버전)
    -- ===== 신규 메타 컬럼(끝에만 추가) =====
    m.CCTV_NM,
    m.WGS84_LAT,
    m.WGS84_LOT,
    m.SIDO_NM,
    m.SGG_NM,
    m.FILE_FMT,
    m.EVNT_NM,
    m.VDO_CDC,
    m.FPS,
    m.BIT_RT,
    m.ASPRT_RT,
    m.RESL,
    m.VDO_WDTH,
    m.VDO_HGT,
    m.FILE_SZ,
    m.DAY_NGT_CD,
    m.SESN_CD,
    m.WTHR_NM
FROM LS_DATASET_VIDEO_META m
-- 상태/버전은 동결 대상이 아니라 라이브 조인. RAW_SN 은 항상 존재 + 상태 1:1(PK) 이므로 INNER 안전.
INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = m.RAW_SN
WHERE m.ACTIVE_YN = 'Y'
  AND s.DATA_STTS_CD = 'APPROVED';   -- V95 불변식 복원: 뷰 노출 ⇔ 현재 라이브 APPROVED

-- 3-2. V_COMPLETED_META — V101 정의 그대로(VLM/외부 시계열 메타만)
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
  AND meta.META_KEY NOT LIKE 'video.%'   -- video.* 기술메타는 V_COMPLETED_VIDEO 로 이관
  AND EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = meta.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

-- 3-3. V_COMPLETED_FRAME — V104 정의 그대로
CREATE OR REPLACE VIEW V_COMPLETED_FRAME AS
SELECT
    -- ===== 기존 출력 컬럼(계약) — 이름·순서 보존 (V61 정의와 1:1) =====
    src.SRC_SN,
    src.RAW_SN,
    src.FRM_NO                     AS FRAME_NO,
    src.SRC_FILE_PATH_NM          AS ORIGINAL_PATH,
    src.DE_IDNTF_SRC_FILE_PATH_NM AS DEIDENTIFIED_PATH,
    src.SHT_DT                    AS CAPTURED_AT,
    src.REG_DT,
    src.UPD_DT,
    -- ===== 신규(끝에 추가만) =====
    src.FRM_EXPLN                 AS DESCRIPTION
FROM LS_DATA_SRC src
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

-- 3-4. V_COMPLETED_LABEL — V93 정의 계승(내용 동일, V61 이후 최신 직전 정의는 V93)
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

-- 3-5. V_COMPLETED_LABEL_ATTR — V93 재생성 정의 그대로(물리 컬럼 ATRB_*, 출력 alias 보존)
CREATE OR REPLACE VIEW V_COMPLETED_LABEL_ATTR AS
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
