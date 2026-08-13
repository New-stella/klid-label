-- =============================================================================
-- V170: LS_DATA_INGEST 원천 개인정보 3컬럼에 fail-closed DB DEFAULT 부여
--
-- ★★ 이 파일은 V166 의 확정 서술 하나를 <폐기>한다 (2026-08-04 사용자 확정)
--
--   V166 은 "인입은 관제가 보낸 것을 그대로 보관하는 수신 원장이므로 서버 보정도 DB DEFAULT 도
--   두지 않는다. fail-closed 기본값은 소비 시점(export 판정)에서 적용한다" 고 적었다.
--   그 서술은 <폐기>한다. 사용자 확정 사항이 바뀌었기 때문이다 —
--
--     "관제가 (개인정보 3필드를) 무조건 채워서 보낼거야" + "db에 디폴트값 넣으라 했잖아"
--
--   즉 3필드는 <관제가 항상 송신하는 필수 값>이고, 그럼에도 컬럼을 지정하지 않은 INSERT 가
--   들어올 경우를 DB 가 fail-closed 로 메운다. 그러면 원천 축에 null 이 생기지 않으므로
--   소비 시점(ExportPrivacyPolicy)의 원천 분기는 <인입값을 그대로 싣는 단순 경로>가 되고,
--   앱 레이어에 원천용 상수 폴백을 따로 두지 않는다(판정 분기를 두 곳에 만들지 않는다).
--
--   ⚠ V166 파일 자체는 수정하지 않는다 — Flyway 체크섬이 깨지면 기존 배포본이 기동 실패한다.
--     정정은 이 파일의 주석과 COMMENT ON COLUMN 재정의, 그리고 CLAUDE.md 가 보유한다.
--
-- ★ 이 정정이 잃는 것 (사용자 인지·수용 사항 — 되돌리지 말 것)
--   DEFAULT 가 생기면 <"관제가 안 보냈다"와 "관제가 N 을 보냈다"를 영영 구분할 수 없다>.
--   이것이 V166 이 DEFAULT 를 피했던 유일한 근거이며, 그 손실을 알고도 채택한 결정이다.
--   (구분이 필요해지면 "수신 여부" 별도 컬럼이 필요하다 — 값 자체로는 복원 불가능하다.)
--
-- 기본값 — fail-closed (원천 영상은 <개인정보가 있다>고 본다)
--   ANONY_INCL_YN  익명정보 포함여부   DEFAULT 'N'
--   PSDO_INCL_YN   가명정보 포함여부   DEFAULT 'N'
--   PRVC_INCL_YN   개인정보 포함여부   DEFAULT 'Y'
--
--   근거: 원천 영상은 비식별 처리 <전> 이라 마스킹되지 않은 얼굴·번호판이 남아 있는 것이 기본
--   상태다. 값이 없을 때 "개인정보 없음(N)" 으로 보면 학습데이터 산출물이 <과소 신고>된다
--   (CWE-359). 반대로 익명·가명은 "처리를 거쳤다"는 주장이므로 근거 없이 Y 로 볼 수 없다.
--
-- ★ 기존 행은 백필하지 않는다 (판단 근거)
--   1) <사실이 아닌 판정을 소급 주입하게 된다>. 기존 행은 V166(2026-08-04) 이전에 수신된 것이라
--      관제가 이 컬럼을 보낼 통로 자체가 없었다 — 판정이 실제로 존재하지 않았고, null 이 그 사실의
--      정확한 표현이다. UPDATE 로 'Y' 를 채우면 "관제가 개인정보 있다고 판정했다"고 주장하는
--      셈이라, 관제가 실제로 보낸 값과 영구히 구분되지 않는다.
--   2) <잠금 비용>. SET DEFAULT 는 카탈로그만 갱신해 기존 행을 재작성하지 않지만(PostgreSQL),
--      전 행 UPDATE 는 테이블 rewrite 수준의 부하이며 2노드 Active-Active 무중단 배포에 불리하다.
--   3) 귀결: 기존 행을 원천으로 삼는 영상은 export 원천 3필드가 계속 null(=판정 없음)이다.
--      채워야 한다면 관제가 해당 클립을 재송신하거나, 근거를 명시한 별도 1회성 마이그레이션으로
--      결정한다 — 이 파일의 범위가 아니다.
--
-- 표준용어·표준도메인
--   신규 컬럼이 아니라 <기존 컬럼의 DEFAULT 추가>다. 물리명·타입·크기 변경 0건
--   (ANONY_INCL_YN / PSDO_INCL_YN / PRVC_INCL_YN = 사업표준용어 등록 물리명, 공통표준도메인
--   여부C1 = CHAR(1)). 기본값 'Y'/'N' 은 여부C1 의 허용값 그대로라 도메인 위반이 없다.
--
-- 잠금 영향 (2노드 Active-Active 무중단 배포)
--   ALTER TABLE ... ALTER COLUMN ... SET DEFAULT 는 <카탈로그 메타데이터만> 갱신한다. 테이블·인덱스
--   재작성이 없고 기존 행을 읽지도 않는다. ACCESS EXCLUSIVE 락을 잡지만 상수 시간이라 순간이다.
--
-- 데이터 영향
--   기존 행 0건 변경. 이 마이그레이션 <이후의 INSERT> 중 해당 컬럼을 지정하지 않은 것만 DEFAULT 로
--   채워진다. 관제가 명시적으로 보낸 값은 그대로 우선한다(DEFAULT 는 미지정일 때만 적용).
--   ⚠ 명시적 NULL 을 INSERT 하면 DEFAULT 가 적용되지 않고 NULL 이 들어간다(SQL 표준) — 내부 업로드
--     통로(InternalUploadIngestWriter)는 이 3컬럼을 INSERT 컬럼 목록에 <아예 넣지 않으므로> DEFAULT 를
--     받는다. 관제가 NULL 을 명시 송신하면 null 이 남는데, 그 경우의 산출은 "판정 없음"이다.
--
-- ★ 롤백 절차
--   DEFAULT <추가>이며 컬럼 구조·데이터는 그대로다. 구버전 jar 는 이 컬럼을 읽기만 하므로 스키마를
--   되돌리지 않아도 기동·동작에 문제가 없다(선행 조치 불필요). 스키마까지 되돌리려면 DBA 가 아래를
--   수동 적용한다 — 실행 SQL 전문:
--
--     ALTER TABLE LS_DATA_INGEST ALTER COLUMN ANONY_INCL_YN DROP DEFAULT;
--     ALTER TABLE LS_DATA_INGEST ALTER COLUMN PSDO_INCL_YN  DROP DEFAULT;
--     ALTER TABLE LS_DATA_INGEST ALTER COLUMN PRVC_INCL_YN  DROP DEFAULT;
--     DELETE FROM flyway_schema_history WHERE version = '170';
--
--   ⚠ DROP DEFAULT 는 <이미 DEFAULT 로 채워져 저장된 행의 값을 되돌리지 않는다>. 그 행들은 계속
--     'N'/'N'/'Y' 를 보유하며, 그 값이 관제가 보낸 것인지 DEFAULT 인지는 구분할 수 없다(위 §잃는 것).
--
-- 문법: PostgreSQL 표준. 같은 값을 다시 SET 해도 무해하므로 재실행 안전(멱등).
-- =============================================================================

ALTER TABLE LS_DATA_INGEST ALTER COLUMN ANONY_INCL_YN SET DEFAULT 'N';
ALTER TABLE LS_DATA_INGEST ALTER COLUMN PSDO_INCL_YN  SET DEFAULT 'N';
ALTER TABLE LS_DATA_INGEST ALTER COLUMN PRVC_INCL_YN  SET DEFAULT 'Y';

-- -----------------------------------------------------------------------------
-- COMMENT 재정의 — V166 이 남긴 "미수신 시 null, 서버 보정 없음" 서술을 폐기하고 정정한다.
-- (COMMENT ON COLUMN 은 덮어쓰기라 V166 의 문구는 이 시점부터 남지 않는다.)
-- -----------------------------------------------------------------------------
COMMENT ON COLUMN LS_DATA_INGEST.ANONY_INCL_YN IS
    '원천 영상(비식별 처리 전)의 익명정보 포함여부(관제 수신, Y/N). 관제가 항상 송신하며 미지정 INSERT 는 DB DEFAULT ''N''(fail-closed)으로 채워진다(V170 — V166 의 "DEFAULT 없음" 서술 폐기). 저작도구가 사람 입력으로 관리하는 비식별 축(LS_DATA_RAW.ANONY_INCL_YN, V163)과 대상이 다른 별개 축이며 적재는 그 컬럼을 채우지 않는다. 파생영상은 원천 영상이 없으므로 이 값을 물려받지 않는다.';
COMMENT ON COLUMN LS_DATA_INGEST.PSDO_INCL_YN IS
    '원천 영상의 가명정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''N''(V170). 비식별 축(LS_DATA_RAW.PSDO_INCL_YN, V163)과 별개.';
COMMENT ON COLUMN LS_DATA_INGEST.PRVC_INCL_YN IS
    '원천 영상의 개인정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''Y''(fail-closed — 원천은 비식별 전이라 개인정보가 남아 있는 것이 기본 상태다. V170). 비식별 축(LS_DATA_RAW.PRVC_INCL_YN, V163)과 별개.';
