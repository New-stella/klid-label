-- =============================================================================
-- V138 — V_COMPLETED_VIDEO 에 비식별 영상 경로(DE_IDNTF_FILE_PATH_NM) 노출 (Phase 5A / A-5)
--
-- 배경:
--   Phase 5A 로 검수 승인 산출물이 원본 영상과 같은 디렉터리 하위({dirname(원본)}/{RAW_SN}/) 로 모이고,
--   비식별 영상도 그 안({RAW_SN}/deid/) 에 함께 놓인다. 그런데 <비식별 영상의 파일명은 고정이 아니다>
--   — 외부 비식별 솔루션(KPST)이 정하며 원본 파일명에서 파생된다({원본stem}-mask{ext}). local mock 은
--   'deidentified.mp4' 를 쓴다. 즉 관제가 경로를 <조합/추측>해서 비식별 영상을 찾을 수 없다.
--
--   비식별 영상 경로의 유일한 진실원은 LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM 이며(프로젝트 CLAUDE.md
--   "문자열 치환 도출 아님"), 이 값은 지금까지 <어느 데이터마트 뷰에도 노출되지 않았다>. 따라서 관제가
--   폴더 픽업(EXPORT_PATH_NM 하위 탐색)을 하든 경로별 픽업을 하든 성립하도록 이 컬럼을 뷰에 싣는다.
--
-- 조치:
--   V_COMPLETED_VIDEO 맨 끝에 DE_IDNTF_FILE_PATH_NM 1컬럼 추가.
--     - 원천: 최신 성공(SUCCEEDED) LS_DEIDENT_PROC_LOG 1건의 DE_IDNTF_FILE_PATH_NM.
--     - LEFT JOIN LATERAL + LIMIT 1 : 재비식별로 procLog 가 누적돼도 영상 1건 = 1 row 유지.
--     - DB 에 적재된 <절대경로 원문 그대로> 노출한다(가공·치환 금지).
--     - 비식별 미완료 영상은 NULL(영상 행은 보존).
--
-- 표준용어/표준도메인:
--   신규 <물리 컬럼> 생성 없음 — 기존 컬럼(LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM, VARCHAR(1000))을
--   뷰 출력에 그대로 노출한다. 별칭(AS)조차 두지 않아 <새 물리명을 만들지 않는다> — 원천 컬럼명·타입·
--   길이를 100% 승계하므로 표준단어/표준도메인 신규 등록 대상이 아니다.
--   (별칭 후보였던 'DE_IDNTF_VIDEO_PATH' 는 영상 표준단어가 VDO 이고 비식별 표준단어가 ANONY 라
--    새 조합을 만들면 드리프트가 되므로 채택하지 않았다.)
--
-- 멱등/안전:
--   CREATE OR REPLACE VIEW 는 <기존 컬럼 이름·순서·타입 보존 + 맨 끝 추가>만 허용한다. V114 정의를
--   100% 보존하고 끝에 1컬럼만 append 하므로 REPLACE 로 안전하다(DROP 불필요, 재실행 안전).
--
-- 관제 연동 영향:
--   기존 컬럼/행은 불변이며 신규 컬럼 1개만 늘어난다(하위호환). 관제는 EXPORT_PATH_NM(산출 트리 루트)과
--   DE_IDNTF_FILE_PATH_NM(비식별 영상 실제 경로)를 함께 픽업하면 된다.
--
-- PostgreSQL 표준 문법. LS_* 전용 뷰 — 관제팀 스키마 협의 불요(MNG_* 미변경).
-- =============================================================================

CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    -- ===== 기존 출력 컬럼(계약) — 이름·순서·타입 보존(V114 동일) =====
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
    r.DATA_STTS_CD     AS BATCH_STTS_CD,
    s.DATA_STTS_CD     AS REVIEW_STTS_CD,
    m.RVW_CMPL_DT      AS REVIEW_COMPLETED_AT,
    s.VER              AS REVIEW_VERSION,
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
    m.WTHR_NM,
    e.EXPORT_PATH_NM,
    e.FRAME_CNT,
    -- ===== V138 신규(맨 끝에만 추가): 비식별 영상 실제 경로(적재값 원문, 원천 컬럼명 그대로) =====
    d.DE_IDNTF_FILE_PATH_NM
FROM LS_DATASET_VIDEO_META m
INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = m.RAW_SN
LEFT JOIN LATERAL (
    SELECT ex.EXPORT_PATH_NM, ex.FRAME_CNT
    FROM LS_DATASET_EXPORT ex
    WHERE ex.DATA_RAW_SN    = m.RAW_SN
      AND ex.EXPORT_STTS_CD = 'SUCCEEDED'
    ORDER BY ex.EXPORT_VER_NO DESC
    LIMIT 1
) e ON TRUE
LEFT JOIN LATERAL (
    SELECT pl.DE_IDNTF_FILE_PATH_NM
    FROM LS_DEIDENT_PROC_LOG pl
    WHERE pl.DATA_RAW_SN = m.RAW_SN
      AND pl.PROC_STTS_CD = 'SUCCEEDED'
      AND pl.DE_IDNTF_FILE_PATH_NM IS NOT NULL
    ORDER BY pl.REQ_DT DESC, pl.PROC_LOG_SN DESC   -- 앱 조회(findSuccessHistory: REQ_DT DESC)와 동일 기준
    LIMIT 1
) d ON TRUE
WHERE m.ACTIVE_YN = 'Y'
  AND s.DATA_STTS_CD = 'APPROVED';   -- V95/V102 불변식: 뷰 노출 ⇔ 현재 라이브 APPROVED
