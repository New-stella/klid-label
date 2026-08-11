-- =============================================================================
-- V182 — 데이터마트 뷰에서 폐기된 프레임(LS_DATA_SRC.DSCD_YN='Y') 제외 (R4)
--
-- 배경:
--   작업자가 라벨링 화면에서 프레임을 폐기하면 그 프레임은 학습데이터 산출물·이미지 세트·
--   데이터마트 노출에서 빠져야 한다. 애플리케이션 축(산출·관제 통지·관제 조회)은 JPQL 술어
--   (LsDataSrcRepository.NOT_DISCARDED)로 닫았고, 여기서는 관제가 직접 SELECT 하는 뷰 2종을 닫는다.
--   한 축이라도 빠지면 "폐기했는데 관제로 나간다".
--
--   폐기는 <논리 폐기>다 — 프레임 행·이미지 파일·라벨은 그대로 보존되고 표시만 바뀐다.
--   따라서 복원(DSCD_YN='N')하면 두 뷰에 <다시 나타난다>. 뷰가 라이브 LS_DATA_SRC 를 읽으므로
--   별도 복원 배선이 필요 없다.
--
-- 왜 이 두 개뿐인가 (조사 결과 — 다시 넓히지 말 것):
--   · V_COMPLETED_VIDEO.FRME_CNT — 산출 원장(LS_DATASET_EXPORT)의 값을 조인해 오므로,
--     산출 자체가 폐기분을 뺀 뒤 기록한 수가 그대로 노출된다(자동 정합 — 손댈 것 없음).
--   · V_COMPLETED_META — LS_DATA_SRC 를 참조하지 않는다(영상 단위 시계열 메타 축).
--
-- 판정 기준 통일:
--   COALESCE(DSCD_YN,'N') <> 'Y' — 애플리케이션 술어(LsDataSrcRepository.NOT_DISCARDED)·엔티티
--   판정(LsDataSrc.isDiscarded)과 <같은 기준>이다. 컬럼은 NOT NULL DEFAULT 'N'(V179)이라 정상
--   경로에서 NULL 이 생기지 않지만, 세 계층이 다른 기준을 쓰면 어느 한쪽만 거르는 창이 열린다.
--
-- 출력 컬럼(이름·순서·타입)은 두 뷰 모두 <완전히 동일>하다 — 관제 계약 변경 없음. 행이 줄어드는
--   것만이 변화이며, 그것이 이 마이그레이션의 목적이다.
--
-- 신규 컬럼/테이블 없음 → 표준용어·표준도메인 신규 등록 대상 아님. MNG_* 공유 스키마 무변경.
-- CREATE OR REPLACE VIEW 라 멱등 — Flyway 재실행/재적용 안전.
-- PostgreSQL 표준 문법.
--
-- @design D1
-- @req R4
-- @req R5
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 1) V_COMPLETED_FRAME — 프레임 페어(원본/비식별 경로). 뷰 1행 = 프레임 1행이라 폐기 프레임을
--    빼는 것으로 관제의 이미지 픽업 대상에서 그대로 빠진다.
--    기존 WHERE 절(검수 완료 게이트 + V133 비식별 경로 불변식 게이트)은 <그대로 유지>한다.
CREATE OR REPLACE VIEW V_COMPLETED_FRAME AS
SELECT
    src.SRC_SN,
    src.RAW_SN,
    src.FRM_NO                     AS FRAME_NO,
    src.SRC_FILE_PATH_NM          AS ORIGINAL_PATH,
    src.DE_IDNTF_SRC_FILE_PATH_NM AS DEIDENTIFIED_PATH,
    src.SHT_DT                    AS CAPTURED_AT,
    src.REG_DT,
    src.UPD_DT,
    src.FRM_EXPLN                 AS DESCRIPTION
FROM LS_DATA_SRC src
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
)
  -- V133 — 원본 경로와 비식별 경로가 <동일>한 행만 제외(= 비식별 미적용 프레임을 비식별로 노출하는 결함).
  -- 어느 한쪽이 결측(NULL 또는 공백)인 행은 결함이 아니므로 통과시킨다.
  AND NOT (
        src.SRC_FILE_PATH_NM IS NOT NULL
    AND TRIM(src.SRC_FILE_PATH_NM) <> ''
    AND src.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
    AND TRIM(src.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
    AND src.DE_IDNTF_SRC_FILE_PATH_NM = src.SRC_FILE_PATH_NM
  )
  -- V182 — 폐기된 프레임 제외 (R4).
  AND COALESCE(src.DSCD_YN, 'N') <> 'Y';

COMMENT ON VIEW V_COMPLETED_FRAME IS
    '검수완료 영상의 프레임 페어(원본/비식별) — 비식별 경로 불변식 게이트(V133), 폐기 프레임 제외(V182)';

-- ---------------------------------------------------------------------------
-- 2) V_COMPLETED_LABEL_CHANGE — 라벨 저장이벤트 변경점. 폐기된 프레임의 변경점을 계속 내보내면
--    관제가 <산출물에 존재하지 않는 프레임>의 변경을 픽업한다.
--    기존 WHERE 절(변경 0건 행 제외 V139 + 검수 완료 게이트)은 <그대로 유지>한다.
--    이력 테이블(LS_DATA_LBL_HSTRY)의 행 자체는 내부 감사 근거이므로 남긴다 — 노출면만 좁힌다
--    (V139 와 동일 원칙). 복원하면 그 프레임의 변경점이 다시 노출된다.
CREATE OR REPLACE VIEW V_COMPLETED_LABEL_CHANGE AS
SELECT
    h.LBL_HSTRY_SN,
    src.RAW_SN,
    h.SRC_SN,
    h.ADD_CNT,
    h.MDFCN_CNT,
    h.DEL_CNT,
    h.REG_ID,
    h.REG_DT
FROM LS_DATA_LBL_HSTRY h
INNER JOIN LS_DATA_SRC src ON src.SRC_SN = h.SRC_SN
WHERE COALESCE(h.ADD_CNT, 0) + COALESCE(h.MDFCN_CNT, 0) + COALESCE(h.DEL_CNT, 0) > 0
  -- V182 — 폐기된 프레임의 변경점 제외 (R4).
  AND COALESCE(src.DSCD_YN, 'N') <> 'Y'
  AND EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

COMMENT ON VIEW V_COMPLETED_LABEL_CHANGE IS
    '검수완료 영상의 라벨 저장이벤트 변경점 — 종류별 건수만 노출(V137), 변경 0건 행 제외(V139), 폐기 프레임 제외(V182)';
