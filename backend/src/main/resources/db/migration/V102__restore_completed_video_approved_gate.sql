-- =============================================================================
-- V102: V_COMPLETED_VIDEO 라이브 APPROVED 게이트 복원 (데이터마트 불변식 회복)
--
-- 배경(회귀):
--   V101 은 V_COMPLETED_VIDEO 를 LS_DATASET_VIDEO_META(ACTIVE_YN='Y') 단독 게이트로 재정의했다.
--   그러나 ACTIVE_YN 은 재검수(APPROVED→PENDING/REJECTED) 시 즉시 내려가지 않고 "다음 승인의
--   deactivatePrevious 까지" 'Y' 로 남는다. 결과적으로 재검수·반려 중인 영상이 여전히 "완료"로
--   V_COMPLETED_VIDEO 에 노출되어 관제 데이터마트가 미완료본을 UPSERT 하는 위험이 있었다.
--   또한 V_COMPLETED_META 는 APPROVED EXISTS 가드를 유지하고 있어 두 뷰 간 불일치(orphan)도 발생.
--
-- 수정:
--   V95 의 불변식 "뷰에 있음 ⇒ 현재 라이브 상태가 APPROVED" 를 복원한다.
--     - LS_DATA_RAW / LS_RAW_DATA_STATUS 를 LEFT → INNER JOIN 으로 전환(RAW_SN 은 항상 존재).
--       LS_RAW_DATA_STATUS.RAW_DATA_ID 는 PK(1영상=1행)라 fan-out(행 증식) 없음.
--     - WHERE 에 s.DATA_STTS_CD='APPROVED' 게이트 추가.
--   → "활성 스냅샷 존재 AND 현재 라이브 상태 APPROVED" 일 때만 노출.
--     동결 메타(cctv명·좌표·코덱 등)는 스냅샷(m)에서, 상태/버전은 라이브(s/r)에서 조달(V101 매핑 유지).
--   부수효과: LEFT→INNER 로 status 미존재 행의 NULL 상태 노출(V101 잔여 위험)도 함께 해소.
--
-- 계약 보존(Critical):
--   출력 컬럼(기존 16 + 신규 18 = 총 18 신규 포함)의 이름·순서·타입을 V101 과 100% 동일하게 유지한다.
--   WHERE/JOIN 만 조정 → PostgreSQL CREATE OR REPLACE VIEW("동일 컬럼 + 끝에 추가만 허용") 규칙 만족.
--
-- V_COMPLETED_META 는 이미 APPROVED EXISTS 가드가 있어 본 마이그레이션에서 건드리지 않는다.
--   (video.* 제외는 안전 — 해당 키는 review row 를 만들지 않아 원래도 V_COMPLETED_META 에 미노출.)
--
-- 멱등: CREATE OR REPLACE VIEW. PostgreSQL 표준 문법.
-- =============================================================================

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
