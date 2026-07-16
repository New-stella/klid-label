-- =============================================================================
-- V95: 데이터마트 뷰 V_COMPLETED_VIDEO 출력 컬럼 ORGNL_RAW_SN 통일
--   근거: 데이터마트 뷰의 외부 소비자가 아직 없어 구 PARENT_RAW_SN 외부 계약명을
--         유지할 필요가 없어짐(사용자 결정) → 내부·외부 모두 ORGNL_RAW_SN 로 통일.
--   변경: r.ORGNL_RAW_SN AS PARENT_RAW_SN → r.ORGNL_RAW_SN (alias 제거) 단 한 줄.
--         나머지 출력 컬럼·alias·JOIN·WHERE(APPROVED) 는 V85 정의 그대로 보존.
-- =============================================================================

DROP VIEW IF EXISTS V_COMPLETED_VIDEO;

CREATE VIEW V_COMPLETED_VIDEO AS
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
    r.ORGNL_RAW_SN,
    r.DATA_STTS_CD AS BATCH_STTS_CD,
    s.DATA_STTS_CD AS REVIEW_STTS_CD,
    s.UPD_DT       AS REVIEW_COMPLETED_AT,
    s.VER          AS REVIEW_VERSION
FROM LS_DATA_RAW r
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = r.RAW_SN
WHERE s.DATA_STTS_CD = 'APPROVED';
