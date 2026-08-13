-- =============================================================================
-- V85: 여부(YN) 도메인 CHAR(1) 전환 — 공공 표준 여부C1('Y'/'N') 통일.
--
-- 근거: 공공 표준 `~여부` 도메인은 전부 여부C1(CHAR(1)), 예외 0.
-- 저작도구 전용 LS_* 컬럼만 대상. 데이터 무손실(값 'Y'/'N'/'F' 보존).
--   A. VARCHAR(1) → CHAR(1)  (12 컬럼 / 11 컬럼명, USE_YN 은 두 테이블)
--   B. BOOLEAN   → CHAR(1)  (LS_LABEL_PRESET_CODE.BBOX_ENABLED / POLYGON_ENABLED, USING 변환)
--
-- 뷰 의존성:
--   LS_DATA_RAW.DE_IDENT_YN / PRVC_YN → V_COMPLETED_VIDEO
-- PostgreSQL 은 뷰가 참조하는 컬럼의 타입 변경을 차단하므로, 해당 뷰를
--   DROP → ALTER → 최신 정의(V82)와 동일하게 재생성한다(출력 컬럼 계약 보존).
-- (다른 YN 컬럼은 어떤 뷰에서도 SELECT/WHERE 로 참조되지 않음 — 전수 grep 확인.)
--
-- 제외 (Critical):
--   MNG_CLIP_MASTER.JOB_DMND_YN / MNG_EX_EVNT_TYPE.CLCT_YN : 관제 공유 MNG_ 소유 — 미변경.
--   LS_TUS_UPLOAD / 기타 MNG_* / QRTZ_*                    : 미변경.
--
-- CHAR(1) 주의: PostgreSQL bpchar(1)는 값 길이 1이라 패딩/트림 이슈 없음.
--   기존 ='Y'/'N'/'F' 문자 비교(JPQL/QueryDSL/도메인 로직) 그대로 정상 동작.
--   DE_IDENT_YN 은 Y(성공)/F(실패)/N(미수행) 3값을 CHAR(1)로 보존.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 0) 대상 뷰 선(先) DROP (재생성 전제 — DE_IDENT_YN / PRVC_YN 의존)
-- -----------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_VIDEO;

-- -----------------------------------------------------------------------------
-- A. VARCHAR(1) → CHAR(1)  (필드 String 유지, 값 'Y'/'N'(/'F') 보존)
--    varchar→bpchar 는 문자열 카테고리 내 캐스트라 USING 불필요. DEFAULT/NOT NULL 보존.
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_VERSION    ALTER COLUMN ACTVTN_YN      TYPE CHAR(1);
ALTER TABLE LS_DEADLINE         ALTER COLUMN ANONY_INCL_YN  TYPE CHAR(1);
ALTER TABLE LS_DEADLINE         ALTER COLUMN PSDO_INCL_YN   TYPE CHAR(1);
ALTER TABLE LS_DEADLINE         ALTER COLUMN PRVC_INCL_YN   TYPE CHAR(1);
ALTER TABLE LS_DATA_LBL_AI_INFO ALTER COLUMN AUTO_LBL_YN    TYPE CHAR(1);
ALTER TABLE LS_DATA_AUG_LBL_MAP ALTER COLUMN COORD_RECALC_YN TYPE CHAR(1);
ALTER TABLE LS_DATA_RAW         ALTER COLUMN DE_IDENT_YN    TYPE CHAR(1);
ALTER TABLE LS_DATA_RAW         ALTER COLUMN PRVC_YN        TYPE CHAR(1);
ALTER TABLE LS_LABEL_ATTR       ALTER COLUMN MUTABLE_YN     TYPE CHAR(1);
ALTER TABLE LS_LABEL_ATTR       ALTER COLUMN USE_YN         TYPE CHAR(1);
ALTER TABLE LS_LABEL            ALTER COLUMN USE_YN         TYPE CHAR(1);
ALTER TABLE LS_NOTICE           ALTER COLUMN UPEND_FIX_YN   TYPE CHAR(1);

-- -----------------------------------------------------------------------------
-- B. BOOLEAN → CHAR(1)  (LS_LABEL_PRESET_CODE)
--    boolean DEFAULT/CHECK 는 char 로 자동 캐스트 불가 → CHECK DROP + DEFAULT DROP 선행,
--    USING 으로 값 변환(TRUE→'Y', FALSE→'N') 후 DEFAULT 재설정 + CHECK 를 문자식으로 재작성.
--    NOT NULL 은 타입 변경 중 보존된다.
-- -----------------------------------------------------------------------------
ALTER TABLE LS_LABEL_PRESET_CODE DROP CONSTRAINT IF EXISTS CK_LS_LABEL_PRESET_CODE_ANNOTATION;

ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN BBOX_ENABLED DROP DEFAULT;
ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN BBOX_ENABLED TYPE CHAR(1)
    USING (CASE WHEN BBOX_ENABLED THEN 'Y' ELSE 'N' END);
ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN BBOX_ENABLED SET DEFAULT 'Y';

ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN POLYGON_ENABLED DROP DEFAULT;
ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN POLYGON_ENABLED TYPE CHAR(1)
    USING (CASE WHEN POLYGON_ENABLED THEN 'Y' ELSE 'N' END);
ALTER TABLE LS_LABEL_PRESET_CODE ALTER COLUMN POLYGON_ENABLED SET DEFAULT 'Y';

-- CHECK 재작성: 최소 한 어노테이션 유형 활성 (boolean 식 → 'Y'/'N' 문자식).
ALTER TABLE LS_LABEL_PRESET_CODE
    ADD CONSTRAINT CK_LS_LABEL_PRESET_CODE_ANNOTATION
        CHECK (BBOX_ENABLED = 'Y' OR POLYGON_ENABLED = 'Y');

-- -----------------------------------------------------------------------------
-- 9) 뷰 재생성 (V82 최신 정의와 동일 — 출력 컬럼명/계약 보존. rename/노출 변경 없음)
--    DE_IDENT_YN → 출력명 DE_IDNTF_YN, ORGNL_RAW_SN → PARENT_RAW_SN(외부 계약명) alias 유지.
-- -----------------------------------------------------------------------------
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
    r.ORGNL_RAW_SN     AS PARENT_RAW_SN,
    r.DATA_STTS_CD AS BATCH_STTS_CD,
    s.DATA_STTS_CD AS REVIEW_STTS_CD,
    s.UPD_DT       AS REVIEW_COMPLETED_AT,
    s.VER          AS REVIEW_VERSION
FROM LS_DATA_RAW r
INNER JOIN LS_RAW_DATA_STATUS s ON s.RAW_DATA_ID = r.RAW_SN
WHERE s.DATA_STTS_CD = 'APPROVED';
