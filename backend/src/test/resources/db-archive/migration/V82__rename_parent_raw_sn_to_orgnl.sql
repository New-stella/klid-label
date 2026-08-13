-- =============================================================================
-- V82: LS_DATA_RAW.PARENT_RAW_SN → ORGNL_RAW_SN 용어 표준 정합 (rename)
--
-- 배경: 본 프로젝트 표준단어는 RAW=원시(물리 RAW_SN 유지), ORGNL=원본.
--       파생물(증강/리사이즈)의 부모=원본을 가리키는 PARENT_RAW_SN 은
--       행안부 표준단어(ORGNL) 불일치라 ORGNL_RAW_SN 으로 교체한다.
--       물리 RAW_SN / LS_DATA_RAW 테이블명은 이번 대상이 아니다(불변).
--
-- 무손실 rename + 뷰 재정의(파괴적 DROP 없음). 데이터 손실 0.
--
-- ⚠️ 데이터마트 뷰(V_COMPLETED_VIDEO)의 출력 컬럼명 PARENT_RAW_SN 은
--    관제서버 데이터마트 계약이므로 무단 변경 금지 —
--    base 컬럼은 r.ORGNL_RAW_SN 로 교체하되 AS PARENT_RAW_SN 로 alias 하여
--    출력 컬럼명은 기존(PARENT_RAW_SN) 그대로 유지한다(외부 소비자 계약 보존).
-- =============================================================================

-- 1. 물리 컬럼 rename
ALTER TABLE LS_DATA_RAW RENAME COLUMN PARENT_RAW_SN TO ORGNL_RAW_SN;

-- 2. 인덱스 rename (V46 에서 생성된 IDX_LDR_PARENT)
ALTER INDEX IF EXISTS IDX_LDR_PARENT RENAME TO IDX_LDR_ORGNL;

-- 3. 데이터마트 뷰 재정의 — base 컬럼만 ORGNL_RAW_SN 로 교체, 출력명은 alias 로 보존.
--    (V61 의 최신 V_COMPLETED_VIDEO 정의를 계승: DE_IDENT_YN→DE_IDNTF_YN,
--     VDO_LEN_SEC→DURATION_SEC, VER→REVIEW_VERSION alias 유지)
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
    r.ORGNL_RAW_SN     AS PARENT_RAW_SN,
    r.DATA_STTS_CD AS BATCH_STTS_CD,
    s.DATA_STTS_CD AS REVIEW_STTS_CD,
    s.UPD_DT       AS REVIEW_COMPLETED_AT,
    s.VER          AS REVIEW_VERSION
FROM LS_DATA_RAW r
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = r.RAW_SN
WHERE s.DATA_STTS_CD = 'APPROVED';
