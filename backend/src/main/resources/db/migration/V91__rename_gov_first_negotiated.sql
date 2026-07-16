-- =============================================================================
-- V91: 공공(행안부) 표준용어 정합 — 협의 확정 rename 4건 (사용자 승인 완료, 2026-07-10).
--
-- LS_AUTH_WORK_LOCK 잠금 이력 컬럼과 LS_LABEL_ATTR 속성명 컬럼을 표준약어로 rename.
-- Java 필드명/게터/세터/파생쿼리 메서드명 불변 — @Column(name=...) 물리명만 정합.
--
-- 표준단어 근거: 잠금=LCK · 해제/제거=RMV · 속성=ATRB
--
-- ⚠️ LS_LABEL_ATTR.ATTR_NM 은 데이터마트 뷰 V_COMPLETED_LABEL_ATTR 이 SELECT 하므로
--    컬럼 rename 후 뷰를 재생성(CREATE OR REPLACE)한다. base 컬럼만 ATRB_NM 로 교체하고
--    출력 별칭 "AS ATTR_NAME" 은 반드시 유지 — 관제서버 데이터마트 계약 불변.
-- 비파괴 rename — 데이터 손실 0.
-- =============================================================================

ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN LOCK_DT     TO LCK_DT;
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN RELEASE_DT  TO RMV_DT;
ALTER TABLE LS_AUTH_WORK_LOCK RENAME COLUMN RELEASE_RSN TO RMV_RSN;
ALTER TABLE LS_LABEL_ATTR     RENAME COLUMN ATTR_NM     TO ATRB_NM;

-- 뷰 재생성 — V52 의 V_COMPLETED_LABEL_ATTR 정의를 계승, attr.ATTR_NM → attr.ATRB_NM 만 교체.
-- 출력 별칭 ATTR_NAME 은 관제서버 계약 보존 위해 그대로 유지한다.
CREATE OR REPLACE VIEW V_COMPLETED_LABEL_ATTR AS
SELECT
    av.ATTR_VAL_ID,
    av.LBL_SN,
    av.ATTR_ID,
    attr.ATRB_NM       AS ATTR_NAME,
    attr.INPUT_TYPE_CD AS ATTR_INPUT_TYPE,
    attr.VALUES_CN     AS ATTR_VALUES_JSON,
    attr.DFLT_VL       AS ATTR_DEFAULT_VAL,
    av.ATTR_VL         AS ATTR_VAL,
    av.REG_DT,
    av.MDFCN_DT
FROM LS_DATA_LBL_ATTR_VAL av
LEFT JOIN LS_LABEL_ATTR attr ON attr.ATTR_ID = av.ATTR_ID
WHERE EXISTS (
    SELECT 1
      FROM LS_DATA_LBL lbl
      JOIN LS_DATA_SRC src       ON src.SRC_SN      = lbl.SRC_SN
      JOIN LS_RAW_DATA_STATUS s  ON s.RAW_DATA_ID   = src.RAW_SN
     WHERE lbl.LBL_SN     = av.LBL_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);
