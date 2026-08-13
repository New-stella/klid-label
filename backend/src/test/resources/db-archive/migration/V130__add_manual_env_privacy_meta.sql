-- =============================================================================
-- V130: 촬영환경(영상 단위)·개인정보(프레임 단위) 수동입력 메타 컬럼 신설
--
-- 목적: 작업자/검수자가 수동 입력하는 촬영환경 메타(날씨/시간대/계절)와 프레임 단위
--       개인정보 포함여부(익명/가명/개인정보)의 편집 소스 컬럼을 신설한다.
--       본 마이그레이션은 컬럼 신설 + 엔티티 매핑만 담당한다(값 변경 로직·수동입력 API·
--       파생 프리필은 후속 Phase). ddl-auto=validate 정합이 목표.
--
-- NULL 정책(Critical):
--   6컬럼 모두 NULL 허용(DEFAULT 없음). NULL = 미입력 상태 = 조회/표시 시점의 파생 폴백
--   대상임을 구분하기 위함이다. DEFAULT 를 두면 '미입력'과 '명시적 입력값'을 구분할 수 없다.
--
-- 표준용어/표준도메인:
--   물리명은 V97 LS_DATASET_VIDEO_META 의 표준명을 그대로 재사용(임의 약어 생성 금지).
--   - WTHR(날씨)/DAY_NGT(주야간)/SESN(계절)/ANONY(익명)/PSDO(가명)/PRVC(개인정보)/INCL(포함)
--   - 명(NM) 표준도메인 = VARCHAR(20) : WTHR_NM
--       근거) 공통표준도메인(공공)의 명(NM) 계열은 명V5/V20/V30/V40/V80/V100 등 이산 크기값만
--       존재하며 32 는 어떤 표준도메인에도 없다 → 최소 충족 크기인 '명V20' 채택.
--       날씨 값("맑음/흐림/비/눈/안개" 등 8자 이내)이므로 20 으로 충분(절삭 위험 없음).
--       기존 V97 LS_DATASET_VIDEO_META.WTHR_NM(32) 은 표준 근거가 아니라 비표준 값의 선행
--       도입이므로 재확산시키지 않는다(해당 컬럼 정정은 별도 백로그).
--   - 코드값(CD) 표준도메인 = VARCHAR(20) : DAY_NGT_CD / SESN_CD (EVNT_TYPE_CD 와 동일)
--   - 여부(YN) 도메인 = CHAR(1) : *_INCL_YN
--       근거) 프로젝트 표준(V85 — 공공 표준 여부C1, 예외 0)에 정합. 동일 물리명
--       ANONY_INCL_YN/PSDO_INCL_YN/PRVC_INCL_YN 이 LS_DEADLINE 에 이미 CHAR(1)로 존재한다.
--       (태스크 확정값의 'VARCHAR(1)'은 추상 표준도메인명이며, 이 코드베이스의 여부 도메인
--        구현 표준은 CHAR(1)이다 — 드리프트/ddl-auto=validate 불일치 방지를 위해 CHAR(1)로 신설.)
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- LS_DATA_RAW: 촬영환경 메타(영상 단위, 전부 NULL 허용 — NULL=미입력/파생폴백)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS WTHR_NM    VARCHAR(20); -- 날씨(수동입력, NULL=미입력) — 표준도메인 명V20
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS DAY_NGT_CD VARCHAR(20); -- 시간대 코드 주간/야간(수동입력, NULL=미입력)
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS SESN_CD    VARCHAR(20); -- 계절 코드(수동입력, NULL=미입력)

COMMENT ON COLUMN LS_DATA_RAW.WTHR_NM    IS '촬영 날씨(작업자 수동입력). NULL=미입력(조회 시 파생 폴백 대상).';
COMMENT ON COLUMN LS_DATA_RAW.DAY_NGT_CD IS '촬영 시간대 코드 주간/야간(작업자 수동입력). NULL=미입력(파생 폴백 대상).';
COMMENT ON COLUMN LS_DATA_RAW.SESN_CD    IS '촬영 계절 코드(작업자 수동입력). NULL=미입력(파생 폴백 대상).';

-- -----------------------------------------------------------------------------
-- LS_DATA_SRC: 개인정보 포함여부 메타(프레임 단위, 전부 NULL 허용 — NULL=미입력)
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_SRC ADD COLUMN IF NOT EXISTS ANONY_INCL_YN CHAR(1); -- 익명정보 포함여부(수동입력, NULL=미입력)
ALTER TABLE LS_DATA_SRC ADD COLUMN IF NOT EXISTS PSDO_INCL_YN  CHAR(1); -- 가명정보 포함여부(수동입력, NULL=미입력)
ALTER TABLE LS_DATA_SRC ADD COLUMN IF NOT EXISTS PRVC_INCL_YN  CHAR(1); -- 개인정보 포함여부(수동입력, NULL=미입력)

COMMENT ON COLUMN LS_DATA_SRC.ANONY_INCL_YN IS '프레임 익명정보 포함여부(작업자 수동입력). NULL=미입력.';
COMMENT ON COLUMN LS_DATA_SRC.PSDO_INCL_YN  IS '프레임 가명정보 포함여부(작업자 수동입력). NULL=미입력.';
COMMENT ON COLUMN LS_DATA_SRC.PRVC_INCL_YN  IS '프레임 개인정보 포함여부(작업자 수동입력). NULL=미입력.';
