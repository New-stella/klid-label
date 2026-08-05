-- =============================================================================
-- V168: 저작도구 소유 이벤트유형 마스터(LS_EVNT_TYPE) 신설 + 관제 이벤트유형 마스터 2종 제거
--       MNG_EX_EVNT_TYPE · MNG_EX_EVNT_TYPE_MAP
--
-- 배경(관제 데이터 참조 전면 제거 — Phase 4): 이벤트유형 필터·라벨은 지금까지 관제 공유 마스터
--   2종을 조인해 만들었다. 관제 2차에서 적재 주체가 반전되어(LS_DATA_INGEST 직접 INSERT) 관제는
--   이벤트유형코드·이벤트명·이벤트분류코드를 <인입 평면값>으로 실어 보낸다. 따라서 마스터를
--   조회할 이유가 사라졌고, 대신 <우리 소유 마스터>에 인입값을 자동 등록해 관리한다.
--
--   신규 규칙(사용자 확정 2026-08-04):
--     * 필터·라벨의 축은 <이벤트유형(EVNT_TYPE_CD)>이다 — 어노테이션 계약(export JSON NiaVideo)의
--       event_id/event_name 이 영상당 <단일 유형 값>이기 때문이다(카테고리는 나가지 않는다).
--     * 그러나 <원래 이벤트 구조는 3계층>(대분류 01 → 카테고리 0001 → 유형 EV01000101)이고
--       <이름은 카테고리 레벨에만 있었다>(MNG_EX_EVNT_TYPE_MAP CD_TYPE='02' 10건). 유형별 이름은
--       관제 마스터에 애초에 없었고, 이것이 "EV01000101/102/103 이 전부 침수(범람)" 인 진짜 이유다.
--       → 그 구조를 그대로 보존한다: 카테고리 마스터(LS_EVNT_CTGRY)를 두고, 유형은 카테고리코드를
--         갖는다. 표시명은 4단 폴백으로 해석한다:
--             COALESCE(운영자 표시명, 관제 수신 유형명, 카테고리명, 유형코드)
--         ★중복 이름은 결함이 아니라 <아직 고유 이름이 없어 카테고리명으로 표시 중>이라는 정상
--           상태다. 관제가 EV01000102 에 '수위상승' 을 보내면 그 유형만 갈라진다(점진 전환).
--     * <관제 칸과 운영자 칸을 분리>한다 — EVNT_NM(관제 수신, 인입이 갱신) / OPTR_INDCT_NM(운영자
--       표시명, 관리 API 전용). 각자 자기 칸만 쓰므로 "사람이 고쳤다" 표식 컬럼이 필요 없고,
--       운영자가 표시명을 지우면 관제값으로 자연 복귀하며 관제 원본이 유실되지 않는다.
--     * 인입 소비 시점(TrainingVideoIngestTx)에 미등록 유형코드면 자동 등록한다(원자 upsert).
--       이미 등록된 유형은 인입값으로 <덮어쓰지 않는다> — 운영자가 정정한 이름을 관제 수신값이
--       되돌리면 안 된다.
--     * 대분류는 <유도하지 않고 관제에서 받는다>. 구 안(SUBSTRING(EVNT_TYPE_CD,3,2))은 폐기됐다 —
--       실측상 비규격 코드('INTRUSION' 9건)가 존재해 유도하면 없는 대분류가 만들어지고 제외 필터가
--       영상을 잘못 숨긴다. 이 마이그레이션이 LS_DATA_INGEST.EVNT_CLSF_CD 를 신설해 <관제 송신
--       통로>를 먼저 만든다(V166 이 EVNT_TYPE_CD·개인정보 3필드에 쓴 것과 동일 패턴).
--
-- 표준용어·표준도메인 근거 (신규 객체 한정 — 기존 컬럼은 미변경):
--   LS_EVNT_TYPE                 LS_(저작도구 소유 접두) + 이벤트 EVNT + 유형 TYPE
--   EVNT_TYPE_CD  VARCHAR(20)    이벤트=EVNT(사업표준단어) · 유형=TYPE(공통표준단어) ·
--                                코드=CD(공통표준단어) / 도메인 코드V20(사업표준도메인 VARCHAR 20).
--                                LS_DATA_RAW·LS_DATA_INGEST 의 동명 컬럼과 동일 규격.
--   EVNT_NM       VARCHAR(200)   이벤트=EVNT(사업) · 명=NM(공통) / 도메인 명V200(공통·사업 공통).
--                                LS_DATA_INGEST.EVNT_NM(V147, 명V200)과 동일 규격.
--   EVNT_CLSF_CD  VARCHAR(20)    이벤트=EVNT(사업) · 분류=CLSF(공통표준단어) · 코드=CD(공통) /
--                                도메인 코드V20. ★관제 소유 테이블의 구 이름 EVNT_CLS_CD 의 'CLS'
--                                는 공통표준단어에 등재돼 있으나 <종목(Class)> 이라는 다른 의미이며,
--                                여기서 필요한 '분류' 의미로는 부적합하므로 승계하지 않고 CLSF 를 쓴다.
--   CLCT_YN       CHAR(1)        수집=CLCT(공통) · 여부=YN(공통) / 도메인 여부C1(공통, CHAR(1), Y/N).
--   REG_DT        TIMESTAMP      등록=REG(공통) · 일시=DT(공통) / 도메인 연월일시분초D.
--   ※ 사전 우선순위는 공통(행안부) 우선, 없을 때만 사업표준이다. '이벤트'는 공통에 없어 사업표준
--     EVNT 를 쓴다(기존 EVNT_* 컬럼군과도 정합).
--
-- 데이터 영향:
--   * 이관 ① 관제 유형 15종 → LS_EVNT_TYPE(유형코드·대분류·카테고리·수집여부). 관제 수신
--     유형명 칸은 <비운다> — 관제 마스터에 유형별 이름이 없었기 때문이다.
--   * 이관 ①-b 카테고리명 10건(MAP CD_TYPE='02') → LS_EVNT_CTGRY. 표시명 폴백의 원천이다.
--   * 이관 ② 인입 원장(LS_DATA_INGEST)에 실재하는 유형코드 중 ①에 없는 것 → 자동등록과 <같은
--     규칙>으로 등록한다(관제 수신명=최신 수신 EVNT_NM, 대분류=수신값). 이 문장이 없으면
--     기존 인입분(비규격 코드 포함)이 영영 등록되지 않아 필터·라벨에서 사라진다.
--   * 프리셋 축 전환 — LS_LABEL_PRESET.EVNT_TYPE_CD 는 V72 이후 <카테고리 키>(6자리 숫자)를
--     담고 있다. 축이 유형으로 바뀌므로 <대표 유형코드>(그 카테고리의 최소 코드)로 정정한다.
--     ⚠ 이것은 <의도된 동작 축소>다: 구 카테고리 프리셋은 소속 상세코드 전부에 적용됐지만,
--       전환 후에는 대표 코드 1개에만 적용된다. 1:N 확장(프리셋 복제)을 하지 않은 이유는
--       LS_LABEL_PRESET 에 UNIQUE(PRESET_NM) 이 있고 자식(LS_LABEL_PRESET_CODE)의 컬럼 구성이
--       V16/V80/V117~V119 로 여러 번 바뀌어, 마이그레이션에서의 복제 실패가 <전 노드 기동 실패>로
--       이어지기 때문이다. 축소된 유형은 이제 필터 드롭다운에 <각자의 옵션으로 노출>되므로
--       REVIEWER 가 화면에서 프리셋을 추가해 복구할 수 있다(관측·복구 가능한 축소).
--     멱등: 전환 후 값은 EV-코드라 '^[0-9]{6}$' 술어에 다시 걸리지 않는다.
--
--   같은 커밋에서 db/seed/dev-seed.sql · src/test/resources/db/test-data-stats-clean.sql 의
--   해당 INSERT/SELECT 도 함께 교체한다(누락 시 시드/IT 가 전량 실패).
--
-- 소유권(★ "MNG_* 변경은 관제서버팀 선승인 필수" 규칙과의 관계): 2종 모두 이름만 MNG_ 접두일 뿐
--   <저작도구가 자기 스키마에 V2 로 직접 CREATE 하고 V71 로 교정한 스텁>이다. 관제 2차 실DB 에는
--   MNG_ 접두 테이블이 0개이며 저작도구는 관제 DB 를 조회하지 않는다. 따라서 이 DROP 은 공유 스키마
--   변경이 아니라 자체 소유 객체 정리다. 데이터마트 뷰(V_COMPLETED_*)도 2종을 참조하지 않는다.
--
-- Flyway 순서 안전성(신규 설치 포함): V2(CREATE) → V71(스텁 교정) → V72(프리셋 카테고리 전환,
--   주석에서만 언급) → V168(이관·DROP, 이 파일). 즉 이관 시점에 2종이 반드시 존재한다.
--   V2/V71/V72 는 이미 적용된 이력이라 내용을 수정할 수 없다(체크섬 불일치 → 2노드 기동 실패).
--
-- 잠금 주의(2노드 Active-Active): DROP TABLE 은 ACCESS EXCLUSIVE 락을 잡는다. 2종 모두 수십 행
--   규모라 잠금 구간이 매우 짧고, 이 커밋 이후 런타임 참조가 0 이라 실질 영향이 없다.
--   ALTER TABLE ... ADD COLUMN(nullable, DEFAULT 없음)은 PostgreSQL 11+ 에서 테이블 재작성이 없다.
--
-- 범위 한정(★): 이 마이그레이션의 DROP 대상은 <위 2개 테이블뿐>이다. 인입 원장(LS_DATA_INGEST)·
--   영상(LS_DATA_RAW)·사용자 마스터(MNG_ACCT_USER)는 절대 DROP 하지 않는다.
--   회귀 가드: EvntTypeMasterTableRemovalTest.
--
-- ★ 롤백 절차 (Critical — 앱을 이 마이그레이션 적용 <이전> 버전으로 내릴 때 필수)
--   구버전 코드에는 @Table(name = "MNG_EX_EVNT_TYPE") / @Table(name = "MNG_EX_EVNT_TYPE_MAP")
--   엔티티와 그 조인을 쓰는 native 쿼리(DatasetMetaSourceRepository)가 존재한다. 스키마를 되돌리지
--   않고 구버전 jar 로 롤백하면 이벤트 필터·라벨·동결이 런타임에 "relation does not exist" 로 깨진다
--   (ddl-auto=validate 는 이 프로젝트에서 실제로 수행되지 않아 기동은 성공하고 <나중에> 터진다).
--   Flyway 는 down-migration 을 수행하지 않으므로 아래 SQL 을 <구버전 재설치 전에> DBA 가 수동
--   적용해야 한다. DDL 은 V71 최종 형상과 컬럼 단위로 동일하다(V85/V107 은 이 2종을 건드리지 않았다).
--   (운영 절차 문서: deploy/onprem/docs/07-uninstall-rollback.md § 롤백)
--
--     -- 1) 테이블 재생성 (구버전 조회 경로 복구용)
--     CREATE TABLE IF NOT EXISTS public.MNG_EX_EVNT_TYPE (
--         EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
--         EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
--         EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
--         CLCT_EVNT_NM   VARCHAR(4000),
--         CLCT_YN        VARCHAR(2)    NOT NULL,
--         PRIMARY KEY (EVNT_TYPE_CD)
--     );
--     CREATE INDEX IF NOT EXISTS IX_MNG_EX_EVNT_TYPE_CLCT_YN
--         ON public.MNG_EX_EVNT_TYPE (CLCT_YN);
--     CREATE TABLE IF NOT EXISTS public.MNG_EX_EVNT_TYPE_MAP (
--         CD_TYPE        VARCHAR(2)    NOT NULL,
--         EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
--         EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
--         DTL_EVNT       VARCHAR(2)    NOT NULL,
--         EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
--         EVNT_NM        VARCHAR(4000),
--         USE_YN         VARCHAR(2)    NOT NULL,
--         PRIMARY KEY (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD)
--     );
--     CREATE INDEX IF NOT EXISTS IX_MNG_EX_EVNT_TYPE_MAP_CD_TYPE
--         ON public.MNG_EX_EVNT_TYPE_MAP (CD_TYPE);
--     -- 2) 마스터 데이터 복원: 내용은 전부 dev-seed 시드값이며 운영에서 이 2종에 기록을 남기는
--     --    주체가 없다. 구버전 롤백 후 dev 시드를 재실행하면 복원된다. 운영은 관제 제공값이므로
--     --    복원이 필요하면 관제서버팀에 재적재를 요청한다.
--     -- 3) 프리셋 축 되돌리기(선택 — 구버전은 카테고리 키를 기대한다).
--     --    ※ 대표 코드로 축소된 상태라 <원래 어떤 카테고리였는지>는 코드 앞 6자리로 복원 가능하다.
--     UPDATE LS_LABEL_PRESET
--        SET EVNT_TYPE_CD = SUBSTRING(EVNT_TYPE_CD, 3, 6)
--      WHERE EVNT_TYPE_CD ~ '^EV[0-9]{8}$';
--     -- 4) 신설 객체 제거(선택 — 남겨도 구버전 동작에 영향 없다)
--     DROP TABLE IF EXISTS LS_EVNT_TYPE;
--     DROP TABLE IF EXISTS LS_EVNT_CTGRY;
--     ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS EVNT_CLSF_CD;
--     ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS EVNT_CTGRY_CD;
--     -- 5) Flyway 이력에서 이 버전 제거 — 남겨두면 구버전 앱이 "적용됐는데 파일이 없다"로 기동 실패한다.
--     DELETE FROM flyway_schema_history WHERE version = '168';
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 인입 이벤트분류코드(대분류) 신설 — <관제 송신 대상>.
--
--    nullable 이라 기존 인입 행에 영향이 없고, 관제가 채우기 전까지는 null 이다.
--    ★폴백(코드에서 유도)을 만들지 않는다 — 계획 확정 정책(R6)이며, 유도를 남기면 방금 폐기한
--      SUBSTRING 규칙이 되살아나 비규격 코드에서 없는 대분류가 만들어진다.
--    null 대분류의 제외 필터 취급은 <fail-open(노출)>이다 — EventTypeService 참조.
-- -----------------------------------------------------------------------------
ALTER TABLE LS_DATA_INGEST
    ADD COLUMN IF NOT EXISTS EVNT_CLSF_CD VARCHAR(20);

ALTER TABLE LS_DATA_INGEST
    ADD COLUMN IF NOT EXISTS EVNT_CTGRY_CD VARCHAR(20);

COMMENT ON COLUMN LS_DATA_INGEST.EVNT_CTGRY_CD IS
    '이벤트카테고리코드(관제 수신 — 송신 요청 대상). 원래 이벤트 구조 3계층(대분류→카테고리→유형)의 중간 레벨이며 LS_EVNT_TYPE.EVNT_CTGRY_CD 의 원천이다. 유형에 고유 이름이 없을 때 표시명이 카테고리명(LS_EVNT_CTGRY)으로 폴백하는 근거다. ★코드에서 유도하지 않는다.';

COMMENT ON COLUMN LS_DATA_INGEST.EVNT_CLSF_CD IS
    '이벤트분류코드(대분류, 관제 수신 — 송신 요청 대상). LS_EVNT_TYPE.EVNT_CLSF_CD 의 원천이며 이벤트유형 제외 필터(eventtype.excluded-class-codes)의 판정축이다. ★코드에서 유도하지 않는다: 비규격 유형코드가 실재해 유도하면 존재하지 않는 대분류가 만들어진다. 관제가 채우지 않으면 null 이고 그 유형은 제외 필터에 걸리지 않는다(fail-open).';

-- -----------------------------------------------------------------------------
-- 2) 저작도구 소유 이벤트유형 마스터 신설.
--
--    EVNT_NM 은 <nullable> 이다 — 관제가 이름 없이 유형코드만 보내도 등록은 성립해야 하고,
--    이름이 없으면 소비측이 <원문 코드 폴백>을 한다(기존 codeLabelMap 계약과 동일).
-- -----------------------------------------------------------------------------
--    ★ LS_EVNT_TYPE.(EVNT_CLSF_CD, EVNT_CTGRY_CD) → LS_EVNT_CTGRY 에 <FK 를 걸지 않는다>
--      (의도된 설계 — "FK 누락"으로 오판해 되돌리지 말 것)
--
--      이유: 이벤트유형은 <관제 인입 소비 시점에 자동 등록>된다. FK 가 있으면 관제가
--        LS_EVNT_CTGRY 에 없는 카테고리코드를 보내는 순간 그 INSERT 가 FK 위반으로 실패하고,
--        자동등록 예외가 흡수되더라도 <그 유형은 영구 미등록으로 남는다>. 그러면 그 영상은
--        이벤트 필터에서 사라지고 라벨이 원문 코드로 떨어진다 — 참조 무결성을 지키려다
--        <데이터가 아예 안 들어오는> 가용성 사고를 만드는 교환이다.
--        (같은 이유로 대분류·카테고리는 코드에서 <유도하지도> 않는다 — 비규격 코드가 실재한다.)
--
--      정합성은 왜 깨지지 않나: 표시명 4단 폴백이 <카테고리명 부재를 이미 정상 케이스로>
--        처리한다(COALESCE 의 3순위가 없으면 4순위인 유형코드로 내려간다). 즉 고아 카테고리코드는
--        오류가 아니라 "아직 카테고리명을 모르는 상태"이며, 관제가 카테고리를 보내오면 그때
--        이름이 붙는다. FK 는 이 점진적 수신 모델과 맞지 않는다.
--
--      대안(불필요): 카테고리를 먼저 등록하도록 강제하는 순서 의존을 만들면 관제 송신 순서에
--        우리 적재가 종속된다 — 인입은 그런 보장을 하지 않는다.
CREATE TABLE IF NOT EXISTS LS_EVNT_TYPE (
    EVNT_TYPE_CD   VARCHAR(20)  NOT NULL,
    EVNT_NM        VARCHAR(200),
    OPTR_INDCT_NM  VARCHAR(200),
    EVNT_CLSF_CD   VARCHAR(20),
    EVNT_CTGRY_CD  VARCHAR(20),
    CLCT_YN        CHAR(1)      NOT NULL DEFAULT 'Y',
    REG_DT         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (EVNT_TYPE_CD)
);

COMMENT ON TABLE  LS_EVNT_TYPE               IS '이벤트유형 마스터(저작도구 소유). 관제 인입 소비 시점에 미등록 유형코드가 자동 등록되고, 관제 수신 칸(EVNT_NM)은 인입이 갱신한다. 운영자 칸(OPTR_INDCT_NM)은 관리 API 전용이라 관제가 덮어쓰지 않는다.';
COMMENT ON COLUMN LS_EVNT_TYPE.EVNT_TYPE_CD  IS '이벤트유형코드(PK). LS_DATA_RAW.EVNT_TYPE_CD · export JSON event_id 와 같은 값이다.';
COMMENT ON COLUMN LS_EVNT_TYPE.EVNT_NM       IS '관제 수신 이벤트유형명. 관제 인입(LS_DATA_INGEST.EVNT_NM)이 값이 바뀔 때만 갱신한다. 관제 마스터에는 유형별 이름이 없었으므로 이관 시점에는 비어 있고, 관제가 보내기 시작하면 채워진다.';
COMMENT ON COLUMN LS_EVNT_TYPE.OPTR_INDCT_NM IS '운영자 표시명. 관리 API(PATCH /v1/manage/event-types) 전용이며 ★관제 인입은 절대 쓰지 않는다. 비우면 관제 수신명으로 자연 복귀한다(관제 원본 유실 없음).';
COMMENT ON COLUMN LS_EVNT_TYPE.EVNT_CLSF_CD  IS '이벤트분류코드(대분류). 관제 인입값(LS_DATA_INGEST.EVNT_CLSF_CD)에서 온다. 제외 대분류 설정의 판정축이며 null 이면 제외되지 않는다(fail-open).';
COMMENT ON COLUMN LS_EVNT_TYPE.EVNT_CTGRY_CD IS '이벤트카테고리코드 — 원래 3계층 구조(대분류→카테고리→유형)의 중간 레벨. LS_EVNT_CTGRY 조인 키이며, 유형명이 없을 때 표시명이 카테고리명으로 폴백하는 근거다.';
COMMENT ON COLUMN LS_EVNT_TYPE.CLCT_YN       IS '수집여부(Y/N). 필터 드롭다운 노출 여부. 자동등록 기본값은 Y 다(인입으로 실제 들어온 유형이므로). ★관제는 이 값을 보내지 않으므로 인입이 갱신하지 않는다 — 운영자가 숨긴 유형이 되살아나면 안 된다.';
COMMENT ON COLUMN LS_EVNT_TYPE.REG_DT        IS '등록일시.';

-- -----------------------------------------------------------------------------
-- 2-1) 이벤트카테고리 마스터 신설 — <원래 구조의 이름 계층>이 제 자리를 찾는 곳.
--
--    관제 마스터에서 사람이 읽는 이름은 카테고리 레벨(CD_TYPE='02') 10건뿐이었다. 그 이름을
--    유형 테이블에 복사해 넣으면 같은 카테고리의 유형들이 <같은 이름을 중복 보유>하게 되고,
--    관제가 나중에 유형별 이름을 보내도 "무엇이 원래 값이고 무엇이 갱신값인지" 구분이 사라진다.
--    카테고리를 자기 테이블에 두면 중복이 사라지고 폴백의 근거가 명확해진다.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS LS_EVNT_CTGRY (
    EVNT_CLSF_CD   VARCHAR(20)  NOT NULL,
    EVNT_CTGRY_CD  VARCHAR(20)  NOT NULL,
    EVNT_CTGRY_NM  VARCHAR(200),
    REG_DT         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (EVNT_CLSF_CD, EVNT_CTGRY_CD)
);

COMMENT ON TABLE  LS_EVNT_CTGRY                IS '이벤트카테고리 마스터(저작도구 소유). 구 관제 매핑(MNG_EX_EVNT_TYPE_MAP CD_TYPE=''02'') 10건의 이관처이며, 유형에 고유 이름이 없을 때 표시명이 여기로 폴백한다. 어노테이션(export)에는 나가지 않지만 원래 3계층 구조를 보존하는 값이다.';
COMMENT ON COLUMN LS_EVNT_CTGRY.EVNT_CLSF_CD   IS '이벤트분류코드(대분류) — 복합 PK 상위.';
COMMENT ON COLUMN LS_EVNT_CTGRY.EVNT_CTGRY_CD  IS '이벤트카테고리코드 — 복합 PK 하위.';
COMMENT ON COLUMN LS_EVNT_CTGRY.EVNT_CTGRY_NM  IS '이벤트카테고리명 — 표시명 4단 폴백의 3순위.';
COMMENT ON COLUMN LS_EVNT_CTGRY.REG_DT         IS '등록일시.';

-- -----------------------------------------------------------------------------
-- 3) 이관 ① — 관제 유형 마스터 15종(유형코드 + 대분류 + 카테고리 + 수집여부).
--
--    ★EVNT_NM(관제 수신 유형명)은 <채우지 않는다>. 관제 마스터에는 유형별 이름이 없었기 때문이다
--    (가진 것은 CLCT_EVNT_NM = "범람,수위,위험수위,호우,홍수" 같은 수집 키워드 나열뿐이며 라벨이
--    아니다). 카테고리명을 여기 복사해 넣으면 <중복 이름>이 만들어지고 관제가 나중에 보내는 진짜
--    유형명과 구분이 불가능해진다. 이름은 아래 3-1 의 카테고리 마스터에서 폴백된다.
--    CLCT_YN 은 원본이 VARCHAR(2) 라 CHAR(1) 로 정규화한다(공백·소문자 방어).
-- -----------------------------------------------------------------------------
INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN)
SELECT
    et.EVNT_TYPE_CD,
    NULL,
    et.EVNT_CLS_CD,
    et.EVNT_CTGRY_CD,
    CASE WHEN UPPER(TRIM(COALESCE(et.CLCT_YN, ''))) = 'Y' THEN 'Y' ELSE 'N' END
FROM MNG_EX_EVNT_TYPE et
ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3-1) 이관 ①-b — 카테고리명 10건(구 MNG_EX_EVNT_TYPE_MAP 의 CD_TYPE='02' 카테고리명행).
--
--    카테고리명행 식별 조건은 CD_TYPE='02' AND DTL_EVNT='' AND EVNT_TYPE_CD='' 이다 — 같은
--    (cls, ctgry) 에 상세행이 섞여 있어 이 3조건을 모두 걸어야 카테고리명만 나온다.
-- -----------------------------------------------------------------------------
INSERT INTO LS_EVNT_CTGRY (EVNT_CLSF_CD, EVNT_CTGRY_CD, EVNT_CTGRY_NM)
SELECT em.EVNT_CLS_CD, em.EVNT_CTGRY_CD, LEFT(em.EVNT_NM, 200)
FROM MNG_EX_EVNT_TYPE_MAP em
WHERE em.CD_TYPE = '02' AND em.DTL_EVNT = '' AND em.EVNT_TYPE_CD = ''
ON CONFLICT (EVNT_CLSF_CD, EVNT_CTGRY_CD) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 4) 이관 ② — 인입 원장에 실재하는 유형코드 중 ①에 없는 것.
--
--    자동등록과 <같은 규칙>이다(관제 수신명=수신값, 대분류=수신값, 수집여부=Y). 이 문장이 없으면
--    이미 적재된 영상들의 유형(비규격 코드 포함)이 마스터에 없어 필터에서 사라지고 라벨이 원문
--    코드로 떨어진다. ①이 먼저 실행되므로 마스터 값이 항상 우선한다(ON CONFLICT DO NOTHING).
--    DISTINCT ON + ORDER BY RCPTN_SN DESC = 같은 코드가 여러 번 수신됐으면 <최신 수신 이름>을 쓴다.
-- -----------------------------------------------------------------------------
INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, CLCT_YN)
SELECT DISTINCT ON (TRIM(i.EVNT_TYPE_CD))
    TRIM(i.EVNT_TYPE_CD),
    LEFT(NULLIF(TRIM(COALESCE(i.EVNT_NM, '')), ''), 200),
    NULLIF(TRIM(COALESCE(i.EVNT_CLSF_CD, '')), ''),
    'Y'
FROM LS_DATA_INGEST i
WHERE i.EVNT_TYPE_CD IS NOT NULL
  AND TRIM(i.EVNT_TYPE_CD) <> ''
ORDER BY TRIM(i.EVNT_TYPE_CD), i.RCPTN_SN DESC
ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5) 프리셋 축 전환 — 카테고리 키(6자리 숫자) → 대표 유형코드.
--
--    ★DROP 보다 먼저 실행한다(마스터가 살아 있어야 소속 코드를 안다).
--    대표 = 그 카테고리의 최소 유형코드(결정적). 이미 그 코드를 쓰는 프리셋이 있으면
--    UNIQUE(EVNT_TYPE_CD) 충돌이 나므로 NOT EXISTS 로 건너뛴다(데이터 손실 없이 수동 정리 대상).
--    멱등: 전환 후 값(EV-코드)은 '^[0-9]{6}$' 에 다시 걸리지 않는다.
-- -----------------------------------------------------------------------------
UPDATE LS_LABEL_PRESET p
   SET EVNT_TYPE_CD = (SELECT MIN(m.EVNT_TYPE_CD)
                         FROM MNG_EX_EVNT_TYPE m
                        WHERE m.EVNT_CLS_CD || m.EVNT_CTGRY_CD = p.EVNT_TYPE_CD)
 WHERE p.EVNT_TYPE_CD ~ '^[0-9]{6}$'
   AND EXISTS (SELECT 1 FROM MNG_EX_EVNT_TYPE m
                WHERE m.EVNT_CLS_CD || m.EVNT_CTGRY_CD = p.EVNT_TYPE_CD)
   AND NOT EXISTS (SELECT 1 FROM LS_LABEL_PRESET x
                    WHERE x.EVNT_TYPE_CD = (SELECT MIN(m2.EVNT_TYPE_CD)
                                              FROM MNG_EX_EVNT_TYPE m2
                                             WHERE m2.EVNT_CLS_CD || m2.EVNT_CTGRY_CD = p.EVNT_TYPE_CD));

-- -----------------------------------------------------------------------------
-- 6) 관제 이벤트유형 매핑 제거 (마스터를 (cls, ctgry) 로 참조하던 쪽 먼저).
--    IF EXISTS — 이미 제거된 DB 에서는 no-op (멱등).
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE_MAP;

-- -----------------------------------------------------------------------------
-- 7) 관제 이벤트유형 마스터 제거.
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE;
