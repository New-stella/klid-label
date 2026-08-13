-- =============================================================================
-- blocker#2: V_COMPLETED_FRAME 에 프레임 설명(DESCRIPTION) 노출.
--
-- Phase 1(V103)에서 LS_DATA_SRC.FRM_EXPLN 에 프레임 단위 자연어 설명(NIA
-- image.description 조달원)을 저장한다. 데이터마트/NIA export 가 이 값을 조달하려면
-- V_COMPLETED_FRAME 뷰에 노출돼야 한다.
--
-- 하위호환(관제 소비자 회귀 0) 최우선:
--   V61 기준 기존 출력 8컬럼(SRC_SN·RAW_SN·FRAME_NO·ORIGINAL_PATH·DEIDENTIFIED_PATH
--   ·CAPTURED_AT·REG_DT·UPD_DT)의 이름·순서·WHERE(APPROVED 게이트)를 1:1 보존하고,
--   끝에 src.FRM_EXPLN AS DESCRIPTION 만 추가한다. 컬럼 제거/이름변경/타입변경 없음.
--
-- CREATE OR REPLACE 라 Flyway 재실행에도 멱등. PostgreSQL 표준 문법.
-- =============================================================================
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
