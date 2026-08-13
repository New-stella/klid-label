-- =============================================================================
-- V171: 비식별 누락 신고에 <신고 단계> 컬럼 신설 (LS_DEIDENT_REPORT.DCLR_STP_CD)
--
-- 목적: 신고가 <어느 단계에서> 접수됐는지를 저장해, 해소(resolve) 후 재개 지점을 분기한다.
--       - MARKING  : 마킹 화면(POST /v1/videos/{rawSn}/deident-report) → 해소 후 <마킹부터 다시>
--       - LABELING : 라벨링 화면(POST /v1/labels/{srcSn}/deident-report) → 해소 후 <프레임만 재추출>
--                    (마킹 유지 + 라벨 좌표 보존 — LS_DATA_SRC 행을 dirty-update 해 SRC_SN 을 보존)
--       구 동작은 두 진입점의 부수효과가 완전히 동일해 재개 지점을 구분할 근거가 DB 에 없었다.
--
-- NULL 정책(Critical) — 백필하지 않는다:
--   기존 행은 <단계 미상>이라 NULL 로 남는다. 어디서 신고했는지 지어내지 않는다는 이 저장소
--   원칙(export 원천 축 "관제 미송신 = null" 과 동일)을 따른다. NULL 행은 해소 시 <단계별 재개
--   이벤트를 발행하지 않고> 기존 2종(DeidentGateReopenedEvent / DeidentReportResolvedEvent)만
--   발행한다 = 현행 동작 그대로다. 따라서 DEFAULT 도 두지 않는다(DEFAULT 를 두면 "미상"이 사라진다).
--
-- 표준용어/표준도메인 (2026-08-04 CSV 정본 대조 완료 — 신규 등록 0건):
--   - 신고 = DCLR  : 공공 표준용어/공통표준단어.csv:881 (신고,DCLR,Declaration). 같은 테이블의
--                    기존 컬럼 DCLR_DT(신고일시)가 이미 이 약어를 쓰고 있어 표기가 일치한다.
--   - 단계 = STP   : 공공 표준용어/공통표준단어.csv:339 (단계,STP,Step).
--   - 코드 = CD    : 공공 표준용어/공통표준단어.csv:1581 (코드,CD,Code — 형식단어 Y, 도메인분류 '코드').
--   - 도메인 코드V20 = VARCHAR(20) : 산업용어/사업표준도메인.csv:28 (코드V20,저작도구,VARCHAR,20).
--   ※ 같은 테이블의 REPORT_STTS_CD 의 'REPORT' 는 두 표준단어 사전 어디에도 없는 비표준 약어다.
--     이번 신규 컬럼은 그 비표준 표기를 <승계하지 않는다>(과거 정정 이력 ACCT→ACNT · EMAIL→EML_ADDR
--     · UPD→MDFCN 과 동일 취지). REPORT_STTS_CD 자체의 정정은 값 참조처가 넓어 별도 백로그로 둔다.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
--
-- 롤백 SQL (V162 규약 — 스키마 변경에는 롤백 전문을 동반한다):
--   ALTER TABLE LS_DEIDENT_REPORT DROP COLUMN IF EXISTS DCLR_STP_CD;
--   ※ 롤백 시 이 컬럼의 값(MARKING/LABELING)은 소실되며 복구 불가하다. 값을 보존해야 하면
--     DROP 전에 별도 백업 테이블로 복사할 것:
--       CREATE TABLE LS_DEIDENT_REPORT_STP_BAK AS
--         SELECT DEIDENT_REPORT_SN, DCLR_STP_CD FROM LS_DEIDENT_REPORT WHERE DCLR_STP_CD IS NOT NULL;
--   ※ 애플리케이션 롤백도 함께 필요하다 — 엔티티 매핑(LsDeidentReport.dclrStpCd)이 남아 있으면
--     컬럼 부재로 조회가 실패한다.
-- =============================================================================

ALTER TABLE LS_DEIDENT_REPORT ADD COLUMN IF NOT EXISTS DCLR_STP_CD VARCHAR(20); -- 신고단계코드(MARKING/LABELING). NULL=단계 미상(레거시).

COMMENT ON COLUMN LS_DEIDENT_REPORT.DCLR_STP_CD IS
    '신고 단계 코드 — MARKING(마킹 화면 신고, 해소 후 마킹부터 재개) / LABELING(라벨링 화면 신고, 해소 후 프레임만 재추출). NULL=단계 미상(컬럼 신설 이전 레거시 행, 재개 이벤트 미발행).';
