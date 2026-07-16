-- =============================================================================
-- V101: 데이터마트 View 재구성 — 통합 메타 스냅샷(LS_DATASET_VIDEO_META) 기반 재정의
--
-- 배경:
--   현재 V_COMPLETED_VIDEO 는 LS_DATA_RAW ⨝ LS_RAW_DATA_STATUS(APPROVED) 로 조달하며
--   MNG_* 메타(cctv명·좌표·코덱·fps 등)를 노출하지 못한다. Phase 1~3 에서 검수 승인(APPROVED)
--   시점에 영상 메타를 동결(materialize)한 통합 물리 테이블 LS_DATASET_VIDEO_META 가 생겼으므로,
--   V_COMPLETED_VIDEO 를 이 동결 스냅샷(ACTIVE_YN='Y') 기반으로 재정의한다.
--     ① MNG_* live JOIN 의존 제거(동결 무결성)  ② 신규 메타 컬럼(cctv명·좌표·코덱·fps 등) 노출.
--
-- 하위호환(Critical):
--   - 기존 V_COMPLETED_VIDEO 출력 16개 컬럼(계약)을 동일 이름·순서·타입으로 100% 보존한다.
--     (관제 데이터마트 소비자 SELECT 회귀 0 — 위험 #3 대응.)
--   - 신규 메타 컬럼은 기존 16개 "뒤에만" 추가(컬럼 제거/이름변경/타입변경 절대 금지).
--   - 상태/버전은 동결 대상이 아니므로 LS_DATA_RAW/LS_RAW_DATA_STATUS 를 RAW_SN 으로 라이브 조인해 유지:
--       BATCH_STTS_CD  = r.DATA_STTS_CD
--       REVIEW_STTS_CD = s.DATA_STTS_CD
--       REVIEW_VERSION = s.VER
--     (REVIEW_COMPLETED_AT 은 스냅샷 동결값 m.RVW_CMPL_DT — APPROVED 확정 시각.)
--
-- 멱등: CREATE OR REPLACE VIEW.
--   보존 16개 컬럼은 이름·순서·타입이 동일하고 신규 컬럼은 끝에만 추가하므로
--   PostgreSQL CREATE OR REPLACE VIEW 규칙("동일 컬럼 + 끝에 추가만 허용")을 만족한다.
--
-- V_COMPLETED_META 는 video.* 기술메타를 통합 테이블(V_COMPLETED_VIDEO)로 이관했으므로
--   해당 6개 키(video.fps/codec/bit_rate/duration_ms/filesize/resolution)를 제외하고
--   VLM/외부 시계열 메타만 노출한다(출력 컬럼 구조는 V84 정의 그대로 보존).
--
-- V_COMPLETED_FRAME / V_COMPLETED_LABEL / V_COMPLETED_LABEL_ATTR 는 건드리지 않는다.
--
-- PostgreSQL 표준 문법.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- 1. V_COMPLETED_VIDEO — 동결 스냅샷 기반 재정의
--    기존 16개 출력 컬럼(계약) 보존 + 신규 메타 18컬럼 추가(끝에만).
-- -----------------------------------------------------------------------------
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
    r.DATA_STTS_CD     AS BATCH_STTS_CD,     -- 라이브(배치 단계 상태)
    s.DATA_STTS_CD     AS REVIEW_STTS_CD,    -- 라이브(작업/검수 워크플로우 상태)
    m.RVW_CMPL_DT      AS REVIEW_COMPLETED_AT, -- 스냅샷 동결(APPROVED 확정 시각)
    s.VER              AS REVIEW_VERSION,     -- 라이브(검수 버전)
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
-- 상태/버전은 동결 대상이 아니므로 라이브 조인(스냅샷은 SoT 라 LEFT JOIN 으로 행 보존).
LEFT JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN
LEFT JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = m.RAW_SN
WHERE m.ACTIVE_YN = 'Y';


-- -----------------------------------------------------------------------------
-- 2. V_COMPLETED_META — VLM/외부 시계열 메타만(video.* 기술메타 제외)
--    출력 컬럼 구조는 V84 정의 그대로 보존, WHERE 에 video.* 제외 조건만 추가.
-- -----------------------------------------------------------------------------
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
