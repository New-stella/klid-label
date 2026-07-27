-- =============================================================================
-- V137 — V_COMPLETED_LABEL_CHANGE 에서 라벨 본문(좌표·속성) 제거 (D-ISSUE-47)
--
-- 배경:
--   V114 슬림화 정책 = "라벨 좌표·속성 본문은 검수 승인 export 폴더 JSON 에 존재하므로 뷰로 중복
--   노출하지 않는다"(구 V_COMPLETED_LABEL / V_COMPLETED_LABEL_ATTR 제거). 그러나 V115 가 재정의한
--   V_COMPLETED_LABEL_CHANGE 는 h.CHG_DTL_CN(= List<LabelChange> diff JSON, before/after 라벨 전체
--   스냅샷 = 좌표 pointCn 포함)을 그대로 노출해 슬림화 목적을 무력화했다.
--
-- 조치:
--   변경점 뷰는 "변경이 있었다는 사실 + 종류별 건수" 만 전달한다. CHG_DTL_CN 컬럼을 뷰에서 제거한다.
--   테이블(LS_DATA_LBL_HSTRY.CHG_DTL_CN)은 내부 감사·복구 근거이므로 그대로 유지한다 — 노출면만 좁힌다.
--
-- 관제 연동 영향(협의 대상):
--   관제가 CHG_DTL_CN 을 SELECT 하고 있었다면 파손된다. 라벨 본문은 export 폴더 JSON
--   (V_COMPLETED_VIDEO.EXPORT_PATH_NM) 으로 픽업한다.
--
-- PostgreSQL 표준 문법. LS_* 전용 뷰 — 신규 컬럼/테이블 생성 없음(표준용어 신규 등록 대상 아님).
-- CREATE OR REPLACE 는 컬럼 목록이 바뀌면 실패하므로 DROP 후 재생성한다(멱등: IF EXISTS).
-- =============================================================================

DROP VIEW IF EXISTS V_COMPLETED_LABEL_CHANGE;

CREATE VIEW V_COMPLETED_LABEL_CHANGE AS
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
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

COMMENT ON VIEW V_COMPLETED_LABEL_CHANGE IS
    '검수완료 영상의 라벨 저장이벤트 변경점 — 종류별 건수만 노출(라벨 좌표·속성 본문 비노출, V137)';
