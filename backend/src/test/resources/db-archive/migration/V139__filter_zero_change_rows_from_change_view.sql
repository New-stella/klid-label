-- =============================================================================
-- V139 — V_COMPLETED_LABEL_CHANGE 에서 "변경 0건" 행 제외 (DEV_FIX-B / M3)
--
-- 배경:
--   LS_DATA_LBL_HSTRY 는 <b>롤백 행위</b>를 라벨 델타 0건이어도 기록한다(의도된 설계 —
--   LsDataLblHstry.recordRollbackEvent: "누가·언제·어느 버전으로"는 감사 추적에 남아야 함).
--   또한 개인정보 메타 리셋 감사(M5)도 라벨 델타 0건으로 기록된다.
--   그런데 V137 이 재정의한 V_COMPLETED_LABEL_CHANGE 는 ADD_CNT/MDFCN_CNT/DEL_CNT 만 노출하고
--   건수 필터가 없어, 관제(데이터마트)가 <b>변경이 전혀 없는 행(0/0/0)</b> 을 변경점으로 헛 픽업한다.
--   판별 근거였던 CHG_DTL_CN 은 V137 에서 뷰 노출이 제거돼 소비자 측 구분도 불가능하다.
--
-- 조치:
--   ADD_CNT + MDFCN_CNT + DEL_CNT > 0 인 행만 노출한다. 컬럼 목록은 V137 과 동일하므로
--   CREATE OR REPLACE VIEW 로 대체 가능하다(멱등 — Flyway 재실행/재적용 안전).
--   테이블(LS_DATA_LBL_HSTRY)의 0건 행은 내부 감사 근거이므로 그대로 유지한다 — 노출면만 좁힌다.
--
-- 관제 연동 영향(협의 대상):
--   관제가 이 뷰를 폴링해 "변경 있음" 을 판정한다면, 이제 실제 라벨 델타가 있는 행만 내려간다.
--   롤백·개인정보 리셋처럼 라벨 본문이 그대로인 이벤트는 TASK_MODIFIED 통지로 전달된다.
--
-- PostgreSQL 표준 문법. LS_* 전용 뷰 — 신규 컬럼/테이블 없음(표준용어·표준도메인 신규 등록 대상 아님).
-- MNG_* 공유 스키마 무변경.
-- =============================================================================

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
  AND EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
);

COMMENT ON VIEW V_COMPLETED_LABEL_CHANGE IS
    '검수완료 영상의 라벨 저장이벤트 변경점 — 종류별 건수만 노출(라벨 본문 비노출 V137, 변경 0건 행 제외 V139)';
