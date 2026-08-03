-- =============================================================================
-- V161: 영상 단위 개인정보(익명/가명/개인정보 포함여부) 수동입력 메타 컬럼 신설
--
-- 목적: 검수자/작업자가 <영상 단위>로 개인정보 판정을 수동 입력할 수 있게 한다.
--       - 신규 API GET/PUT /v1/videos/{rawSn}/privacy-meta 의 저장소.
--       - 학습데이터 export JSON 의 video 블록 개인정보 3필드(anonymity/pseudonymity/
--         privacy_included) 원천(2026-08-03 확정 — 비식별 산출물만 판정, 원천은 null).
--       - 선행 Phase 에서 업로드 시 PRVC_TYPE_CD 입력이 사라지고 PRVC 고정(fail-closed)이
--         되었으므로, 이 화면이 개인정보 판정을 정정하는 유일한 통로다.
--
-- 선례 정합(V130): 동일 물리명 3컬럼을 LS_DATA_SRC(프레임 단위)에 이미 신설했다.
--       본 마이그레이션은 같은 물리명/타입/크기를 LS_DATA_RAW(영상 단위)에 대칭 신설한다.
--       → 표준용어 신규 등록 0건(기존 등록 단어 조합 재사용).
--
-- NULL 정책(Critical):
--   3컬럼 모두 NULL 허용(DEFAULT 없음). NULL = 미입력 상태 = 조회 시 비식별 기본상수
--   프리필(DERIVED) 대상임을 구분하기 위함이다. DEFAULT 를 두면 '미입력'과 '사람이
--   명시적으로 판정한 값'을 구분할 수 없어, export 가 추정값을 사실로 실어버린다.
--
-- 표준용어/표준도메인 (2026-08-03 CSV 정본 대조 완료 — 신규 등록 0건):
--   - PSDO=가명 / PRVC=개인정보 / INCL=포함 / YN=여부 : 사업·공통 표준단어 등록어 그대로.
--   - 여부(YN) 도메인 = CHAR(1) : 공통표준도메인 '여부C1'(C, 1) + 프로젝트 표준(V85, 예외 0)에 정합.
--   - ANONY : 사업표준단어에는 '비식별=ANONY'로 등록돼 있고 '익명'의 공공 표준약어는 ANMT 다.
--     그럼에도 ANONY_INCL_YN 을 쓰는 이유는 <동일 개념의 물리명을 테이블 간에 갈라놓지 않기>
--     위해서다 — LS_DATA_SRC(V130)·LS_DEADLINE 이 이미 ANONY_INCL_YN 을 쓰고 있어, 여기만
--     ANMT_INCL_YN 으로 바꾸면 "같은 뜻 두 이름"이라는 더 큰 드리프트가 생긴다(신규 약어 생성 아님).
--     ANONY→ANMT 정정은 3개 테이블 일괄 rename + 엔티티/뷰 동반 변경이라 별도 백로그로 둔다.
--
-- CHECK (… IN ('Y','N')) 미도입 결정 (2026-08-03 DEV_FIX 검토 — 재논의 방지용 기록):
--   검토했고 <넣지 않기로> 했다. 근거 3가지.
--   1) YN 컬럼에 값 CHECK 를 두는 것이 이 스키마의 관행이 아니다 — LS_DATA_SRC(V130) 등 같은 3컬럼을
--      포함해 대다수 YN 컬럼에 값 CHECK 가 없다. 여기에만 걸면 "같은 도메인인데 한 컬럼만 제약"이라는
--      새 비대칭(=드리프트)이 생긴다.
--      ※ 2026-08-03 DEV_FIX 2차 정정 — 구 문구 "선례 0건 / YN 컬럼 전부에 CHECK 없음"은 <부정확>했다.
--        실제 선례가 있다: V85:67 CHECK (BBOX_ENABLED='Y' OR POLYGON_ENABLED='Y'),
--        V57:45/48/64 CHECK (... IN (...)). <결정(미도입)은 그대로 유지>하되 근거를 사실로 고친다.
--   2) 값 화이트리스트가 이미 3계층 — DTO @Pattern("\A[YN]\z") · 서비스 validate(Set.of("Y","N"))
--      · 엔티티 changePrivacyMeta/normalizeYn. 엔티티는 빌더/setter 를 외부에 노출하지 않아
--      (CWE-915 방어) 이 3필드를 쓰는 경로가 도메인 메서드 하나로 강제된다.
--   3) 형제 YN 컬럼들이 무방비인 채 이 3개만 CHECK 를 두면 "DB 가 값을 보증한다"는 <잘못된
--      안심>을 준다. 값 보증을 DB 로 올리려면 YN 도메인 전체를 한 번에 다루는 별도 작업이어야 한다.
--   → 재도입하려면 V161 단독이 아니라 전 YN 컬럼 일괄 마이그레이션으로 설계할 것.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
-- =============================================================================

ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS ANONY_INCL_YN CHAR(1); -- 익명정보 포함여부(수동입력, NULL=미입력)
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS PSDO_INCL_YN  CHAR(1); -- 가명정보 포함여부(수동입력, NULL=미입력)
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS PRVC_INCL_YN  CHAR(1); -- 개인정보 포함여부(수동입력, NULL=미입력)

COMMENT ON COLUMN LS_DATA_RAW.ANONY_INCL_YN IS '영상 익명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 Y 프리필 대상).';
COMMENT ON COLUMN LS_DATA_RAW.PSDO_INCL_YN  IS '영상 가명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';
COMMENT ON COLUMN LS_DATA_RAW.PRVC_INCL_YN  IS '영상 개인정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';
