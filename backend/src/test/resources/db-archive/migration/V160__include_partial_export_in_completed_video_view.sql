-- =============================================================================
-- V160 — V_COMPLETED_VIDEO 가 PARTIAL export 도 노출하도록 확장 (E-ISSUE-81, HIGH)
--
-- 배경(관제 동기화 사각지대):
--   export 가 PARTIAL(일부 프레임만 산출)로 마감되면 세 판정 기준이 서로 어긋났다.
--     ① 통지  : PARTIAL 은 정상 마감이라 TASK_COMPLETED/TASK_MODIFIED 가 <발송된다>.
--     ② 뷰    : LATERAL 조인이 EXPORT_STTS_CD='SUCCEEDED' 만 봐서 PARTIAL 은 <배제된다>.
--     ③ 회수기: findRetryableFailedAnchors 가 'FAILED' 만 앵커로 삼아 PARTIAL 은 <재산출 대상이 아니다>.
--   결과적으로 관제는 통지를 받고 뷰를 조회했는데 EXPORT_PATH_NM/FRAME_CNT 가 구 버전 값이거나
--   (최초 export 가 PARTIAL 이면) NULL 이고, 자동 회수 경로도 없어 <영구 미동기화>가 된다.
--   디스크에는 산출물이 실재하는데(실측 rawSn=94: .../94/v2/{orgnl,deid}/*.jpg) 데이터마트에서는
--   미산출로 보이는 상태다. 이는 DatasetExportFailureRecoverer 가 만들어진 계기(EXPORT_PATH_NM 이
--   NULL 인 행이 영구 노출)와 동일 증상인데, FAILED 축만 막혀 있고 PARTIAL 축이 열려 있었다.
--
-- 조치(판정 기준 일치):
--   ① LATERAL 조인 조건을 EXPORT_STTS_CD IN ('SUCCEEDED','PARTIAL') 로 확장한다.
--      - 최신 버전 우선(EXPORT_VER_NO DESC LIMIT 1)은 그대로 — 영상 1건 = 1 row 불변.
--      - 통지가 나간 산출은 반드시 뷰에서 보인다(CLAUDE.md "검수 완료·통지 건에 대한 관제 접근은
--        무조건 보장한다" 구속 정책과 정합).
--      - 멱등 baseline(DatasetExportTxService — SUCCEEDED+PARTIAL)과도 기준이 일치한다.
--   ② 맨 끝에 EXPORT_STTS_CD 1컬럼을 추가해 관제가 <부분 산출임을 식별>할 수 있게 한다.
--      부분 산출을 조용히 완전 산출처럼 보이게 하지 않는다(관제가 필요하면 자체 판단 가능 — 우리가
--      강제하지 않는다는 기존 DE_IDNTF_YN 노출 원칙과 동일).
--
--   회수기(FAILED 앵커)는 <건드리지 않는다>. PARTIAL 을 재시도 앵커로 넣으면 원천 이미지가 영구
--   부재한 영상이 max-attempts 를 소진할 때까지 매 tick 마다 새 버전 폴더 + 이미지 2벌을 재복사한다
--   (디스크 누적). PARTIAL 은 "산출물이 실재하는 정상 종결"로 취급하고 뷰에 노출하는 것이 요구
--   ("데이터마트 학습데이터셋의 라벨링 정보 동기화")에 정합적이다.
--
-- 표준용어/표준도메인:
--   신규 <물리 컬럼> 생성 없음 — 기존 컬럼 LS_DATASET_EXPORT.EXPORT_STTS_CD(VARCHAR(20), 코드값
--   표준도메인)를 뷰 출력에 그대로 노출한다. 별칭(AS)을 두지 않아 새 물리명을 만들지 않으며 원천
--   컬럼명·타입·길이를 100% 승계한다(V138 과 동일 방침).
--
-- 멱등/안전:
--   CREATE OR REPLACE VIEW 는 <기존 컬럼 이름·순서·타입 보존 + 맨 끝 추가>만 허용한다. V138 정의를
--   100% 보존하고 끝에 1컬럼만 append 하므로 REPLACE 로 안전하다(DROP 불필요, 재실행 안전).
--
-- 관제 연동 영향:
--   기존 컬럼/행 <수> 는 불변(영상 1건 = 1 row). 다만 최신 산출이 PARTIAL 인 영상에서
--   EXPORT_PATH_NM/FRAME_CNT 가 <구 SUCCEEDED 값 또는 NULL> 에서 <최신 PARTIAL 값> 으로 바뀐다 —
--   이것이 이 마이그레이션의 목적(최신 산출물 픽업)이다. 신규 컬럼 1개 추가는 하위호환.
--
-- PostgreSQL 표준 문법. LS_* 전용 뷰 — 관제팀 스키마 협의 불요(MNG_* 미변경).
-- =============================================================================

CREATE OR REPLACE VIEW V_COMPLETED_VIDEO AS
SELECT
    -- ===== 기존 출력 컬럼(계약) — 이름·순서·타입 보존(V138 동일) =====
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
    d.DE_IDNTF_FILE_PATH_NM,
    -- ===== V160 신규(맨 끝에만 추가): 산출 종결 상태(SUCCEEDED | PARTIAL) — 부분 산출 식별용 =====
    e.EXPORT_STTS_CD
FROM LS_DATASET_VIDEO_META m
INNER JOIN LS_DATA_RAW        r ON r.RAW_SN      = m.RAW_SN
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = m.RAW_SN
LEFT JOIN LATERAL (
    SELECT ex.EXPORT_PATH_NM, ex.FRAME_CNT, ex.EXPORT_STTS_CD
    FROM LS_DATASET_EXPORT ex
    WHERE ex.DATA_RAW_SN    = m.RAW_SN
      -- V160 — 통지가 나간 산출(PARTIAL 포함)은 반드시 뷰에서 보여야 한다(E-ISSUE-81).
      AND ex.EXPORT_STTS_CD IN ('SUCCEEDED', 'PARTIAL')
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
