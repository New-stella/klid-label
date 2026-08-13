-- =============================================================================
-- V108: LS_* 식별자 컬럼을 표준 도메인 길이(VARCHAR 30)로 정합
--
-- 근거: docs/표준용어/산업용어/사업표준용어.csv 의 도메인 "식별자V30"(VARCHAR 30) 정합.
--       V94 에서 감사 ID(REG_ID/MDFCN_ID/MDFR_ID) 를 전 테이블 VARCHAR(64) 로 통일했으나,
--       표준 도메인은 식별자=VARCHAR(30) 이다. 트랙 ID(TRCK_ID) 도 동일 식별자 도메인이므로
--       64 에서 30 으로 축소해 통일한다. (코드성 컬럼은 V107 에서 코드V20 으로 별도 정합됨)
--
-- 값 안전성(값 ≤30 확인 — 배포 DB klid_system_246 실측):
--   REG_ID/MDFCN_ID/MDFR_ID 실데이터 최대 길이 = 4 (USER_NO/'SYSTEM' 등 짧은 식별자),
--   TRCK_ID 실데이터 최대 길이 = 3. 30 대비 훨씬 짧아 64→30 축소는 truncation 없음.
--
-- 범위(엄수): LS_* 저작도구 소유 테이블만 변경한다.
--   - MNG_*/QRTZ_* 공유 스키마(관제서버 소유)는 미변경.
--   - 코드성 컬럼(EVNT_TYPE_CD 등)은 V107 에서 이미 코드V20 으로 정합 — 재변경 없음.
--   - V35/V73 에서 DROP 된 LS_PJT* 계열은 잔존 컬럼 없음(대상 없음).
--
-- View 의존 처리(Critical):
--   대상 컬럼 LS_DATA_LBL.TRCK_ID 를 데이터마트 View V_COMPLETED_LABEL 이 출력 컬럼으로
--   참조한다. PostgreSQL 은 View 가 참조하는 컬럼의 타입 변경을 차단
--   ("cannot alter type of a column used by a view or rule")하므로, 해당 View 를
--   선삭제 → ALTER → 최신 정의(V107 재생성 정의) 그대로 재생성한다(출력 계약·동작 불변).
--   나머지 대상 컬럼(REG_ID/MDFCN_ID/MDFR_ID)은 어떤 View 도 참조하지 않아 View 처리 불필요.
--     - V_COMPLETED_LABEL : V107 재생성 정의(=V61 정의) 그대로
--
-- 인덱스/UK: 대상 식별자 컬럼에 길이 의존 CHECK 제약이 없고, varchar 길이 변경 시
--   PostgreSQL 이 관련 인덱스를 자동 재구성한다(별도 DROP 불필요).
--
-- PostgreSQL 표준 문법.
-- =============================================================================

-- ------------------------------------------------------------------------------
-- 1) 의존 데이터마트 View 선삭제 (TRCK_ID ALTER 차단 회피)
-- ------------------------------------------------------------------------------
DROP VIEW IF EXISTS V_COMPLETED_LABEL;

-- ------------------------------------------------------------------------------
-- 2) 식별자 컬럼 → VARCHAR(30)
-- ------------------------------------------------------------------------------

-- 2-1. REG_ID (등록아이디) — 14개 테이블
ALTER TABLE LS_AUTH_WORK_LOCK     ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_BATCH_PROC_LOG     ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DATA_AUG_LBL_MAP   ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DATA_AUG_RVW       ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DATA_LBL_AI_INFO   ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DATA_META_REVIEW   ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DEIDENT_PROC_LOG   ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DEIDENT_REPORT     ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_LABEL              ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_LABEL_ATTR         ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_LABEL_VERSION      ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_RESOLUTION_EXPORT  ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_NOTICE             ALTER COLUMN REG_ID   TYPE VARCHAR(30);
ALTER TABLE LS_DATASET_VIDEO_META ALTER COLUMN REG_ID   TYPE VARCHAR(30);

-- 2-2. MDFCN_ID (수정아이디) — 9개 테이블
ALTER TABLE LS_AUTH_WORK_LOCK     ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_BATCH_PROC_LOG     ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_DATA_AUG_RVW       ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_DATA_LBL_AI_INFO   ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_DATA_META_REVIEW   ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_DEIDENT_PROC_LOG   ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_DEIDENT_REPORT     ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_LABEL              ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);
ALTER TABLE LS_LABEL_ATTR         ALTER COLUMN MDFCN_ID TYPE VARCHAR(30);

-- 2-3. MDFR_ID (수정자아이디) — 2개 테이블
ALTER TABLE LS_NOTICE             ALTER COLUMN MDFR_ID  TYPE VARCHAR(30);
ALTER TABLE LS_SYSTEM_CONFIG      ALTER COLUMN MDFR_ID  TYPE VARCHAR(30);

-- 2-4. TRCK_ID (트랙아이디) — 1개 테이블 (View 의존)
ALTER TABLE LS_DATA_LBL           ALTER COLUMN TRCK_ID  TYPE VARCHAR(30);

-- ------------------------------------------------------------------------------
-- 3) 데이터마트 View 재생성 (V107 재생성 정의 그대로 — 출력 계약·동작 불변)
-- ------------------------------------------------------------------------------

-- 3-1. V_COMPLETED_LABEL — V107 재생성 정의(=V61 정의) 그대로
CREATE OR REPLACE VIEW V_COMPLETED_LABEL AS
SELECT
    lbl.LBL_SN,
    lbl.SRC_SN,
    src.RAW_SN,
    src.FRM_NO AS FRAME_NO,
    lbl.LBL_TYPE_CD,
    lbl.LBL_ID     AS LABEL_ID,
    lbl.LBL_NM     AS LABEL,
    lbl.POINT_CN   AS POINTS_JSON,
    lbl.TRCK_ID,
    lbl.REG_USER_NO,
    lbl.REG_DT  AS LABEL_REG_DT,
    lbl.MDFCN_DT AS LABEL_UPD_DT,
    label.LBL_NM        AS LABEL_NAME,
    label.COLR_VL       AS LABEL_COLOR,
    label.LBL_TYPE_CD   AS LABEL_TYPE,
    label.SORT_SEQ      AS LABEL_SORT_NO
FROM LS_DATA_LBL lbl
INNER JOIN LS_DATA_SRC src ON src.SRC_SN  = lbl.SRC_SN
LEFT  JOIN LS_LABEL    label ON label.LBL_ID = lbl.LBL_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
