-- ============================================================================
-- V17: 검증 이벤트 유형별 질문 문구를 저작도구가 보관한다  @design ERD-033 · ERD-013 · ADR-051
--
--   1) ls_vrfc_evnt_type   검증 이벤트 유형 (신규 테이블 + 시드 7건)
--   2) ls_vrfc_evnt_qstn   유형별 질문       (신규 테이블 + 시드 7건)
--   3) ls_marking.vrfc_evnt_qstn_sn  작업자가 고른 질문 (컬럼 추가)
--
-- 외부 시계열 분석의 추가 질문 창구는 <질문 문장을 사업자 서버가 이벤트별로 관리>하며 연동 시스템이
-- 지정할 수도, 응답으로 받을 수도 없다. 그런데 이벤트 어노테이션의 질문 칸은 채워져야 하고, 이후
-- 규격이 질의를 받는 방향으로 바뀔 수 있다는 협의가 있었다. 그래서 문구를 <우리가 보관>하고 그
-- 목록에서 조달한다 — 마킹에서 고른 값이 1순위, 없으면 그 유형의 첫 번째.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (규칙 3 — 우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   검증 = VRFC (행안부) · 유형 = TYPE (행안부) · 질문 = QSTN (행안부) · 내용 = CN (행안부)
--   정렬 = SORT (행안부) · 순서 = SEQ (행안부) · 명 = NM (행안부) · 설명 = EXPLN (행안부)
--   일련번호 = SN (행안부)
--   이벤트 = EVNT (사업표준 — 행안부 <미등록> 개념이라 보충. 우선순위상 행안부에 있으면 그것을 썼다)
--
--   도메인도 정본을 따른다.
--     질문내용 = QSTN_CN / 내용V4000        -> varchar(4000)
--     정렬순서 = SORT_SEQ / 순서N10          -> numeric(10)
--     유형명   = TYPE_NM / 명V300            -> varchar(300)
--     설명 계열은 내용V4000 이 우세          -> varchar(4000)
--     검증이벤트유형코드는 인입 원장의 같은 컬럼과 <같은 값 공간>이라 그 폭(코드 계열 20)을 따른다.
--
--   ⚠ 이 저장소의 기존 sort_seq 3곳은 integer 인데 <신규 컬럼은 정본대로 numeric(10)> 을 쓴다.
--     기존 3곳의 정정은 이 변경의 범위가 아니다(별도 백로그). 같은 선례가 V14 의 mdfr_id 에 있다.
--
-- ----------------------------------------------------------------------------
-- * ★ ls_vrfc_evnt_type 은 허용목록이 아니다 (되돌리지 말 것)
-- ----------------------------------------------------------------------------
--   확정 정책은 <검증 이벤트 유형 값이 미수신이거나 우리 목록 밖이어도 그대로 실어 위탁하고 수용
--   여부는 사업자 응답이 정한다>이다. 이 표를 위탁 게이팅에 쓰면 그 정책이 뒤집힌다 — 과거에
--   사업자 열거값의 사본이 두 번째 진실원이 되어 <정상 값을 우리가 먼저 막은> 결함이 있었다.
--   이 표에 없는 유형의 영상도 위탁은 그대로 나가고 <질문 칸만 빈다>.
--
--   시드 7건은 연동 규격서의 지원 이벤트·이벤트별 질문 문장에서 <기계 추출>한 값이다. 재타이핑이
--   아니라 추출본에서 생성했다.
--   ⚠ 이 가운데 smoke 는 인입 상수 6종에 없다. 시드 7건 / 상수 6종은 <모순이 아니다> — 그 상수는
--     이미 "허용목록이 아니라 프리셋" 으로 규정돼 있고, 인입 수신 여부는 관제 문의 대기 중이다.
--
-- ----------------------------------------------------------------------------
-- * ★ (유형코드, 정렬순서) 유일 — 「첫 번째 질문」의 결정성 근거
-- ----------------------------------------------------------------------------
--   마킹을 거치지 않는 경로는 <언제나 그 유형의 첫 번째 질문>을 쓴다. 정렬 키 없이 조회 순서에
--   기대면 그 기본값이 <실행마다 달라진다>. 유일 제약이 그것을 고정한다.
--
-- ----------------------------------------------------------------------------
-- * FK 방향과 삭제 정책
-- ----------------------------------------------------------------------------
--   질문 -> 유형 : ON DELETE RESTRICT. 질문이 딸린 유형을 지우면 그 질문들이 갈 곳을 잃는다.
--     유형 삭제는 애초에 정상 동선이 아니다(규격에서 온 시드 카탈로그다).
--
--   ★ ls_marking.vrfc_evnt_qstn_sn 에는 <물리 FK 를 걸지 않는다>. 질문 목록은 관리 화면에서
--     <전체 교체>로 저장되므로 기존 행이 사라지는 것이 정상 동선인데,
--       RESTRICT -> 질문 편집이 과거 마킹 때문에 막힌다
--       CASCADE  -> 마킹 행이 지워진다
--       SET NULL -> 동작은 맞지만 「선택 없음」과 「고른 질문이 사라짐」이 값으로 구분되지 않는다
--     어차피 조달 시점에 <그 유형에 속한 질문인지 검증하고 아니면 첫 번째로 되돌리는> fail-safe 가
--     있으므로, 참조 무결성을 DB 가 아니라 조달 판정기가 갖는다. 형제 컬럼 raw_sn 이 FK 를 갖는
--     것과는 사유가 다르다.
--
-- ----------------------------------------------------------------------------
-- * 멱등 · 두 경로 수렴
-- ----------------------------------------------------------------------------
--   IF NOT EXISTS + ON CONFLICT DO NOTHING + <제약은 카탈로그 조회 후 없을 때만 추가> 라 두 번
--   돌아도 안전하다.
--   ★ 제약을 DROP IF EXISTS 후 무조건 ADD 하는 흔한 꼴을 <쓰지 않는다>. 유형 표의 기본키는 질문
--     표의 외래키가 물고 있어, 두 번째 실행에서 그 DROP 이 "cannot drop ... because other objects
--     depend on it" 로 거부된다. 실제로 그렇게 적었다가 재실행 실증에서 깨졌다.
--   ★ 카탈로그 조회는 conrelid = to_regclass(...) 로 <스코프>한다. 스코프 없는 조회는 다른
--     스키마의 동명 객체를 보고 조작 대상과 조회 대상이 어긋난다.
--   · 신규 설치(빈 DB) : V1 이 이 객체들 없이 스키마를 만들고 여기서 추가된다.
--     V1__baseline.sql 은 이미 적용된 이력이라 고치지 않는다(체크섬 불일치 = 전 노드 기동 실패).
--   · 기존 DB          : V16 까지 적용된 상태에서 여기서 추가된다.
--
--   ★ 시드는 <운영자가 고친 문구를 되돌리지 않는다>. ON CONFLICT DO NOTHING 이라 이미 있는 행은
--     건드리지 않으며, 운영자가 지운 질문을 재삽입하지도 않는다(같은 (유형, 순서) 자리가 비면
--     다시 들어갈 수 있다는 한계는 인지한다 — 시드는 초기 1회 적재가 목적이다).
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   신규 테이블 생성·시드는 기존 트래픽과 경합하지 않는다.
--   ls_marking 의 ADD COLUMN 은 <DEFAULT 없는 nullable> 이라 카탈로그만 고치므로 테이블 재작성이
--   없고 잠금은 순간이다.
--
--   ★ 이 변경은 <하위호환>이다. 구 jar 노드는 새 컬럼·새 표를 모르는 SQL 을 만들 뿐이고
--     (엔티티 매핑에 없으므로 SELECT/INSERT 목록에 나타나지 않는다) 새 jar 노드만 쓰고 읽는다.
--     따라서 롤링 재기동으로 무중단 배포가 가능하다.
--
-- ★ 롤백 절차 — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   되돌리면 <운영자가 편집한 질문 문구와 마킹의 선택 이력이 함께 사라진다>. 어노테이션에 이미
--   적재된 질문 문장은 그 본문 안에 남아 있으므로 산출물은 깨지지 않는다.
--
--     ALTER TABLE ls_marking DROP COLUMN vrfc_evnt_qstn_sn;
--     DROP TABLE ls_vrfc_evnt_qstn;
--     DROP TABLE ls_vrfc_evnt_type;
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '17';
--
-- ----------------------------------------------------------------------------
-- * 이번에 손대지 않는 것 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   · ls_evnt_type (관제 이벤트 코드 마스터) — <다른 코드 체계>다. 합치거나 참조로 잇지 않는다.
--   · 관제 코드 <-> 검증 유형 매핑표 — 만들지 않는다. 관제가 인입 행에서 이미 둘을 짝지어 보내며,
--     사본을 만들면 두 번째 진실원이 된다. 화면은 인입 원장을 <읽어서> 짝을 보여줄 뿐이다.
--   · 인입 상수의 검증 유형 6종 — 위 smoke 설명 참조. 이 변경의 범위가 아니다.
--   · 기존 행 백필 — 마킹의 새 컬럼은 <선택값>이며 비어 있으면 그 유형의 첫 번째가 쓰인다.
--     백필할 사실이 존재하지 않는다.
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 커넥션의 search_path(기본 klid_at)가
--     정하고, 카탈로그 조회는 current_schema() 로 스코프해 조회 대상과 조작 대상을 같게 만든다.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) 검증 이벤트 유형
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_vrfc_evnt_type (
    vrfc_evnt_type_cd character varying(20) NOT NULL,
    vrfc_evnt_type_nm character varying(300) NOT NULL,
    vrfc_evnt_type_expln character varying(4000),
    sort_seq numeric(10) DEFAULT 0 NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_vrfc_evnt_type') AND conname = 'ls_vrfc_evnt_type_pkey'
    ) THEN
        ALTER TABLE ls_vrfc_evnt_type ADD CONSTRAINT ls_vrfc_evnt_type_pkey PRIMARY KEY (vrfc_evnt_type_cd);
    END IF;
END $$;

COMMENT ON TABLE ls_vrfc_evnt_type IS
    '검증이벤트유형 — 외부 시계열 분석에 위탁할 때 쓰는 이벤트 유형의 표시·시드 카탈로그. ★허용목록이 아니다: 위탁 가능 여부를 이 표로 게이팅하지 않으며, 여기에 없는 유형의 영상도 위탁은 그대로 나가고 질문 칸만 빈다. 사업자 열거값의 사본이 두 번째 진실원이 되어 정상 값을 우리가 먼저 막은 결함이 있었다. 관제 이벤트 코드 마스터와는 값 공간이 다른 별개 체계이므로 합치거나 참조로 잇지 않는다.';
COMMENT ON COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_cd IS '검증이벤트유형코드 — 관제 인입 원장이 실어 보내는 검증 이벤트 유형과 같은 값 공간이다.';
COMMENT ON COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_nm IS '검증이벤트유형명 — 화면에서 사람이 읽는 이름.';
COMMENT ON COLUMN ls_vrfc_evnt_type.vrfc_evnt_type_expln IS '검증이벤트유형설명 — 그 유형이 어떤 상황을 가리키는지의 서술.';
COMMENT ON COLUMN ls_vrfc_evnt_type.sort_seq IS '정렬순서 — 화면 표시 순서. 질문의 「첫 번째」 판정과는 다른 축이다.';
COMMENT ON COLUMN ls_vrfc_evnt_type.reg_id IS '등록자아이디';
COMMENT ON COLUMN ls_vrfc_evnt_type.reg_dt IS '등록일시';
COMMENT ON COLUMN ls_vrfc_evnt_type.mdfr_id IS '수정자아이디';
COMMENT ON COLUMN ls_vrfc_evnt_type.mdfcn_dt IS '수정일시';

INSERT INTO ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq)
VALUES
    ('fire', '화재', '불꽃 등 화재 상황', 1),
    ('smoke', '연기', '연기 등 화재 상황', 2),
    ('fall', '쓰러짐', '사람이 쓰러지거나 바닥에 누워 있는 상황', 3),
    ('violence', '폭력', '폭행, 몸싸움, 물리적 충돌 상황', 4),
    ('flooding', '침수', '물이 차오르거나 공간이 물에 잠긴 상황', 5),
    ('car_accident', '교통사고', '차량 충돌, 전복, 사고 정황', 6),
    ('kidnapping', '납치', '강제로 끌고 가거나 납치로 의심되는 상황', 7)
ON CONFLICT (vrfc_evnt_type_cd) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 2) 유형별 질문
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_vrfc_evnt_qstn (
    vrfc_evnt_qstn_sn bigint NOT NULL,
    vrfc_evnt_type_cd character varying(20) NOT NULL,
    sort_seq numeric(10) NOT NULL,
    qstn_cn character varying(4000) NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('ls_vrfc_evnt_qstn')
          AND attname = 'vrfc_evnt_qstn_sn' AND attidentity <> ''
    ) THEN
        ALTER TABLE ls_vrfc_evnt_qstn ALTER COLUMN vrfc_evnt_qstn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME ls_vrfc_evnt_qstn_vrfc_evnt_qstn_sn_seq
            START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
        );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_vrfc_evnt_qstn') AND conname = 'ls_vrfc_evnt_qstn_pkey'
    ) THEN
        ALTER TABLE ls_vrfc_evnt_qstn ADD CONSTRAINT ls_vrfc_evnt_qstn_pkey PRIMARY KEY (vrfc_evnt_qstn_sn);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_vrfc_evnt_qstn') AND conname = 'fk_ls_vrfc_evnt_qstn_type'
    ) THEN
        ALTER TABLE ls_vrfc_evnt_qstn ADD CONSTRAINT fk_ls_vrfc_evnt_qstn_type
            FOREIGN KEY (vrfc_evnt_type_cd) REFERENCES ls_vrfc_evnt_type(vrfc_evnt_type_cd) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ls_vrfc_evnt_qstn_type_sort
    ON ls_vrfc_evnt_qstn USING btree (vrfc_evnt_type_cd, sort_seq);

COMMENT ON TABLE ls_vrfc_evnt_qstn IS
    '검증이벤트질문 — 검증 이벤트 유형별 질문 문구. 한 유형에 여러 개를 둘 수 있고 순서가 곧 의미다(정렬순서 첫 번째가 기본 질문). 이 문구는 이벤트 어노테이션의 질문 칸을 채우는 조달원이며, 마킹에서 고른 값이 1순위이고 없거나 그 유형에 속하지 않으면 첫 번째로 되돌린다. 질문 본문은 외부 사업자 요청 바디와 로그에 실릴 값이므로 제어문자·개행을 입구에서 막는다.';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.vrfc_evnt_qstn_sn IS '검증이벤트질문일련번호 — 질문 한 건을 가리킨다. 마킹이 고른 질문을 이 값으로 보관한다.';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.vrfc_evnt_type_cd IS '검증이벤트유형코드 — 이 질문이 딸린 유형.';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.sort_seq IS '정렬순서 — ★「첫 번째 질문」의 결정성 근거. (유형코드, 정렬순서) 유일 제약과 짝이며, 이것이 없으면 마킹을 거치지 않는 경로의 기본 질문이 실행마다 달라진다.';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.qstn_cn IS '질문내용 — 그 이벤트가 있었는지와 근거를 묻는 문장. ⚠ 현행 위탁 요청에는 이 문장을 실을 자리가 없어, 첫 번째가 아닌 질문을 고르면 기록된 질문과 사업자가 실제로 쓴 질문이 달라진다(인지·수용한 위험).';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.reg_id IS '등록자아이디';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.reg_dt IS '등록일시';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.mdfr_id IS '수정자아이디';
COMMENT ON COLUMN ls_vrfc_evnt_qstn.mdfcn_dt IS '수정일시';

INSERT INTO ls_vrfc_evnt_qstn (vrfc_evnt_type_cd, sort_seq, qstn_cn)
VALUES
    ('fire', 1, '영상에서 ''화염이 보이는 불'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('smoke', 1, '영상에서 ''특정 지점에서 피어올라 확산되는 연기'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('fall', 1, '영상에서 ''사람이 바닥에 쓰러지거나 쓰러져 있음'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('violence', 1, '영상에서 ''신체적 충돌을 동반한 싸움'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('flooding', 1, '영상에서 ''평소 물이 없던 공간이 물에 잠기는 침수'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('car_accident', 1, '영상에서 ''차량 충돌을 동반한 교통사고'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?'),
    ('kidnapping', 1, '영상에서 ''저항하는 사람을 강제로 데려가는 강제 이동'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?')
ON CONFLICT (vrfc_evnt_type_cd, sort_seq) DO NOTHING;

-- ----------------------------------------------------------------------------
-- 3) 마킹이 보관하는 질문 선택값
-- ----------------------------------------------------------------------------
ALTER TABLE ls_marking ADD COLUMN IF NOT EXISTS vrfc_evnt_qstn_sn bigint;

COMMENT ON COLUMN ls_marking.vrfc_evnt_qstn_sn IS
    '검증이벤트질문일련번호 — 작업자가 그 영상의 검증 이벤트 유형에 등록된 질문 가운데 고른 값. 기본 선택은 그 유형의 첫 번째 질문이다. 값이 없거나 그 유형에 속하지 않는 질문이면 조달 시점에 첫 번째로 되돌린다(화면 입력을 신뢰하지 않는다). 마킹을 거치지 않는 경로는 언제나 첫 번째를 쓴다. 검증 이벤트 유형이 미수신이면 비어 있고, 그래도 위탁은 그대로 나간다. ★물리 FK 를 걸지 않는다 — 질문 목록이 전체 교체로 저장되므로 참조 무결성을 조달 판정기가 갖는다.';
