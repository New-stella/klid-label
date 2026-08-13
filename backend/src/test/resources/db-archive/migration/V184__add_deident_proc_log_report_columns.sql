-- =============================================================================
-- V184: 비식별 처리 결과 리포트 적재 컬럼 6종 신설 (LS_DEIDENT_PROC_LOG) [req: R14]
--
-- 목적: 외부 비식별 솔루션(KPST)의 처리 결과 리포트(GET /retrieve_report, 규격 §22.3.7)를
--       폴링 완료 시점에 1회 조회해 <무엇을 얼마나 가렸는지>를 남긴다. 지금까지 이 API 는
--       호출조차 하지 않아 검출 집계가 DB 에 전혀 없었다.
--
-- 왜 새 테이블을 만들지 않나:
--   LS_DEIDENT_PROC_LOG 는 위탁 <회차마다 새 행을 INSERT> 한다(KpstDeidentTxService.issueSubmitLedger
--   — 재비식별 재위탁도 append). 따라서 이 테이블의 행 자체가 곧 영상 단위 비식별 이력이며,
--   컬럼만 얹으면 영상 상세의 「비식별 이력」이 그대로 성립한다. 이력 전용 테이블은 두 번째 진실원이 된다.
--
-- 표준용어/표준도메인 (CSV 정본 대조 완료 — 신규 등록 0건):
--   · 얼굴검출수      FACE_DTCT_CNT  NUMERIC(10)
--       - 얼굴 = FACE : 사업표준단어(2026-08-11 신규 등록분). 공공 사전에는 미등록이라 사업 사전을 쓴다.
--       - 검출 = DTCT : 공통표준단어.csv(검출,DTCT,Detection) — 사업 사전도 동일 약어로 등재.
--       - 수   = CNT  : 공통표준단어.csv(수,CNT / 형식단어 Y / 도메인분류 '수').
--   · 번호판검출수    NOPLT_DTCT_CNT NUMERIC(10)
--       - 번호판 = NOPLT : 공통표준단어.csv(번호판,NOPLT,License Plate).
--   · 프레임수        FRME_CNT       NUMERIC(10)
--       - 공통표준용어.csv 에 <프레임수 = FRME_CNT / 도메인 수N10> 로 등재된 용어 그 자체다.
--         (사업 사전의 FRM_CNT 는 공공 사전과 갈리므로 채택하지 않는다 — 행안부 1순위.)
--   · 처리시작일시    PRCS_BGNG_DT   TIMESTAMP
--   · 처리종료일시    PRCS_END_DT    TIMESTAMP
--       - 둘 다 공통표준용어.csv 에 그 이름으로 등재(도메인 연월일시분초D).
--   · 리포트파일경로명 RPT_FILE_PATH_NM VARCHAR(1000)
--       - 리포트 = RPT : 공통표준단어.csv(보고,RPT,Report) · 사업표준단어.csv(리포트,RPT,Report).
--                        두 사전이 같은 약어라 우선순위 충돌이 없다.
--       - 파일경로명 = FILE_PATH_NM : 공통표준용어.csv 등재 용어(도메인 명V300).
--       - 같은 테이블의 신고 축(LS_DEIDENT_REPORT)은 DCLR_* 를 쓰므로 RPT_ 와 혼동되지 않는다.
--
-- 도메인 이탈 1건(의도적, 선례 승계):
--   RPT_FILE_PATH_NM 은 표준도메인 명V300 이 아니라 VARCHAR(1000) 이다. 값은 벤더 응답
--   dsStatus[].fileName 인데 이것이 <파일명이 아니라 원본 입력파일의 절대경로>임이 실서버에서
--   확인됐고(위키 §22.3.6/§22.6), 같은 테이블의 ORGNL_FILE_PATH_NM · DE_IDNTF_FILE_PATH_NM 이
--   이미 VARCHAR(1000)(V29)이다. 300 으로 잡으면 긴 NAS 경로에서 INSERT 가 DB 오류(500)로 샌다
--   — 이 저장소는 컬럼 폭 초과가 400 이 아니라 500 으로 새는 사고를 이미 겪었다. 애플리케이션도
--   적재 전 1000자로 절단한다(KpstDeidentReportSummary.MAX_REPORT_FILE_PATH_LEN, 2중 방어).
--
-- PROC / PRCS 주의(Critical):
--   같은 테이블의 기존 PROC_STTS_CD 의 'PROC' 는 선존 드리프트다 — 표준상 PROC=프로세스이고
--   <처리>는 PRCS 다. 신규 컬럼은 PRCS 를 쓰고, 기존 컬럼은 <손대지 않는다>. PROC_STTS_CD 는
--   데이터마트 뷰 V_COMPLETED_VIDEO 본문이 이 이름으로 참조하므로(V172 주석) rename 은 뷰 재작성을
--   동반하는 별도 작업이다.
--
-- NULL 정책 — 백필하지 않는다:
--   컬럼 신설 이전 회차는 리포트를 조회한 적이 없다. 그 값을 지어내지 않는다(이 저장소의
--   "관제 미송신 = null" 원칙과 동일). 리포트 조회에 실패한 신규 회차도 NULL 로 남는다 —
--   리포트 조회 실패는 비식별 완료 흐름을 막지 않기 때문이다(KpstDeidentService.fetchReportQuietly).
--   따라서 DEFAULT 를 두지 않는다(DEFAULT 를 두면 "조회 못 함"과 "0건 검출"이 구분되지 않는다).
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
--
-- 롤백 SQL (V162 규약 — 스키마 변경에는 롤백 전문을 동반한다):
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS FACE_DTCT_CNT;
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS NOPLT_DTCT_CNT;
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS FRME_CNT;
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS PRCS_BGNG_DT;
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS PRCS_END_DT;
--   ALTER TABLE LS_DEIDENT_PROC_LOG DROP COLUMN IF EXISTS RPT_FILE_PATH_NM;
--   ※ 롤백 시 적재된 집계값은 소실되며 복구 불가하다(외부 리포트를 다시 조회해야 한다).
--     값을 보존해야 하면 DROP 전에 백업 테이블로 복사할 것:
--       CREATE TABLE LS_DEIDENT_PROC_LOG_RPT_BAK AS
--         SELECT PROC_LOG_SN, FACE_DTCT_CNT, NOPLT_DTCT_CNT, FRME_CNT,
--                PRCS_BGNG_DT, PRCS_END_DT, RPT_FILE_PATH_NM
--           FROM LS_DEIDENT_PROC_LOG WHERE FRME_CNT IS NOT NULL;
--   ※ 애플리케이션 롤백도 함께 필요하다 — 엔티티 매핑(LsDeidentProcLog.faceDtctCnt 등)이 남아
--     있으면 컬럼 부재로 조회가 실패한다.
-- =============================================================================

ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS FACE_DTCT_CNT    NUMERIC(10);    -- 얼굴검출수
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS NOPLT_DTCT_CNT   NUMERIC(10);    -- 번호판검출수
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS FRME_CNT         NUMERIC(10);    -- 총 프레임수
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS PRCS_BGNG_DT     TIMESTAMP;      -- 처리시작일시
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS PRCS_END_DT      TIMESTAMP;      -- 처리종료일시
ALTER TABLE LS_DEIDENT_PROC_LOG ADD COLUMN IF NOT EXISTS RPT_FILE_PATH_NM VARCHAR(1000);  -- 리포트파일경로명

COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.FACE_DTCT_CNT IS
    '얼굴 검출 수 — KPST GET /retrieve_report 의 dsStatus[].faceCount(전체 검출 - 번호판). NULL=리포트 미조회/조회 실패.';
COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.NOPLT_DTCT_CNT IS
    '번호판 검출 수 — KPST GET /retrieve_report 의 dsStatus[].lpCount(license plate). NULL=리포트 미조회/조회 실패.';
COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.FRME_CNT IS
    '비식별 처리 대상 총 프레임 수 — KPST GET /retrieve_report 의 dsStatus[].totalFrame. NULL=리포트 미조회/조회 실패.';
COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.PRCS_BGNG_DT IS
    '외부 솔루션의 비식별 처리 시작 일시 — dsStatus[].startTime. 해석 불가 값("None" 등)이면 NULL.';
COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.PRCS_END_DT IS
    '외부 솔루션의 비식별 처리 종료 일시 — dsStatus[].endTime. 해석 불가 값("None" 등)이면 NULL.';
COMMENT ON COLUMN LS_DEIDENT_PROC_LOG.RPT_FILE_PATH_NM IS
    '리포트가 회신한 파일 경로 — dsStatus[].fileName. 실측 계약상 결과 파일명이 아니라 원본 입력파일의 절대경로다(비식별 산출물 경로는 DE_IDNTF_FILE_PATH_NM). 1000자 초과 시 절단 적재.';
