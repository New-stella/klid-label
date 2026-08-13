-- =============================================================================
-- V114: 데이터마트 View 슬림화 — 라벨 내용 뷰 제거 + export 폴더 경로 노출 + 변경점 뷰 신설
--
-- 배경(계약 변경):
--   검수 승인(APPROVED) 시점에 학습데이터 export 폴더({labeling_root}/{RAW_SN}/v{n}/orgnl|deid/)에
--   COCO 기반 프레임별 라벨 JSON + 이미지가 이미 산출된다(LS_DATASET_EXPORT 원장). 따라서 라벨 내용을
--   DB 뷰(V_COMPLETED_LABEL / V_COMPLETED_LABEL_ATTR)로 중복 노출할 필요가 없다. 관제서버는
--   ① export 폴더 경로(파일=라벨 내용 진실원) + ② 변경점·메타만 DB 뷰로 쿼리하면 충분하다.
--
--   ⇒ 이 마이그레이션은 데이터마트 뷰를 아래로 재편한다.
--     ① 라벨 내용 뷰 2종 제거(V_COMPLETED_LABEL, V_COMPLETED_LABEL_ATTR).
--     ② V_COMPLETED_VIDEO 에 export 폴더 경로(EXPORT_PATH_NM) + 프레임수(FRAME_CNT) 노출.
--     ③ V_COMPLETED_LABEL_CHANGE 신설 — LS_DATA_LBL_HSTRY 기반 변경점(ADDED/UPDATED/DELETED)만 노출.
--
-- 멱등/안전:
--   - ① DROP VIEW IF EXISTS(참조 의존 뷰 없음 — grep 확인). 순서: ATTR → LABEL(의존 없지만 관례 유지).
--   - ② CREATE OR REPLACE VIEW: V102 의 출력 34컬럼(16 계약 + 18 메타) 이름·순서·타입 100% 보존 +
--        맨 끝에만 EXPORT_PATH_NM, FRAME_CNT 2컬럼 append("동일 컬럼 + 끝에 추가만 허용" 규칙 만족).
--   - ③ CREATE OR REPLACE VIEW: 신설/재실행 안전.
--
-- PostgreSQL 표준 문법. LS_* 전용 뷰 — 관제팀 협의 불요.
-- =============================================================================


-- -----------------------------------------------------------------------------
-- ① 라벨 내용 뷰 제거 (export JSON 으로 책임 이관)
--    SKELETON 삼중값 등 라벨 본문 노출 책임은 이제 export 폴더의 COCO JSON 이 담당한다.
--    다른 뷰가 이 두 뷰를 FROM/JOIN 참조하지 않으므로 제거 안전.
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_LABEL_ATTR;
DROP VIEW IF EXISTS V_COMPLETED_LABEL;


-- -----------------------------------------------------------------------------
-- ② V_COMPLETED_VIDEO — export 폴더 경로 + 프레임수 노출(끝에만 추가)
--    V102 정의(동결 스냅샷 기반 + 라이브 APPROVED 게이트)를 그대로 유지하고,
--    export 최신 SUCCEEDED 1건을 LEFT JOIN LATERAL 로 붙여 행 증식 0을 보장한다.
--      - LATERAL LIMIT 1 : 같은 영상의 export 다버전이 있어도 영상당 1 row 유지.
--      - EXPORT_STTS_CD='SUCCEEDED' : 진행중(PENDING)/실패(FAILED)/부분(PARTIAL) export 는 제외.
--      - LEFT JOIN : 미export 영상도 두 값 NULL 로 노출(영상 행 보존).
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    -- ===== 기존 출력 컬럼(계약) — 이름·순서·타입 보존(V102 동일) =====
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
    -- ===== 신규 메타 컬럼(V102 append) =====
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
    -- ===== V114 신규(맨 끝에만 추가): export 폴더 경로 + 프레임수 =====
    e.EXPORT_PATH_NM,
    e.FRAME_CNT
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
WHERE m.ACTIVE_YN = 'Y'
  AND s.DATA_STTS_CD = 'APPROVED';   -- V95/V102 불변식: 뷰 노출 ⇔ 현재 라이브 APPROVED


-- -----------------------------------------------------------------------------
-- ③ V_COMPLETED_LABEL_CHANGE — 라벨 변경점(검수 완료 영상)
--    LS_DATA_LBL_HSTRY(V58 생성 + V112 확장: CHG_KIND_CD/REG_ID)는 RAW_SN 컬럼이 없어
--    LS_DATA_SRC 로 조인해 RAW_SN 을 얻는다. 시각 컬럼은 V89 리네임 후 REG_DT(엔티티 검증).
--    APPROVED 게이트는 기존 V_COMPLETED_FRAME 패턴(EXISTS LS_RAW_DATA_STATUS)을 재사용.
--    관제서버는 이 뷰로 "무엇이 언제 누구에 의해 바뀌었나"만 조회하고, 라벨 본문은 export JSON 에서 읽는다.
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW V_COMPLETED_LABEL_CHANGE AS
SELECT
    h.LBL_HSTRY_SN,
    src.RAW_SN,
    h.SRC_SN,
    h.LBL_SN,
    h.CHG_KIND_CD,
    h.REG_ID,
    h.REG_DT
FROM LS_DATA_LBL_HSTRY h
INNER JOIN LS_DATA_SRC src ON src.SRC_SN = h.SRC_SN
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
