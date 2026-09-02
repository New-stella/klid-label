-- ============================================================================
-- V23: AI 추론 서버를 <목록>으로 관리한다 — 노드 원장 + 영상 배정  @design ADR-057
--
--   1) ls_ai_srvr         AI서버 원장   (신규 테이블 · 시드 없음)
--   2) ls_ai_srvr_altmnt  영상별 배정   (신규 테이블 · 시드 없음)
--
-- 추론 서버가 장비 두 대에 이중화로 올라가는데 저작도구가 부르는 주소는 설정값 하나뿐이라,
-- 두 대를 나눠 쓸 배선이 없었다. 이 표가 <그 목록을 데이터로> 옮겨, 앞으로 어느 노드로 보낼지를
-- 애플리케이션이 정할 수 있게 하는 저장 자리다.
--
-- ⚠ 이 마이그레이션은 <원장만> 세운다. 분산 자체는 아직 하지 않는다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   서버 = SRVR (행안부) · 배정 = ALTMNT (행안부) · 상태 = STTS (행안부) · 대기 = WTNG (행안부)
--   건수 = NOCS (행안부) · 점검 = CHCK (행안부) · 실패 = FAIL (행안부) · 주소 = ADDR (행안부)
--   유형 = TYPE (행안부) · 사유 = RSN (행안부) · 일시 = DT (행안부) · 명 = NM (행안부)
--   아이디 = ID (행안부) · 코드 = CD (행안부) · 일련번호 = SN (행안부)
--   AI = AI (사업표준 — 행안부 <미등록> 개념이라 보충. 우선순위상 행안부에 있으면 그것을 썼다)
--
--   ★ <노드> 와 <부하> 는 양쪽 사전 어디에도 없다. 신규 등록 대신 등록된 말로 바꿔 적었다 —
--     노드 -> SRVR(서버) · 부하 -> WTNG_NOCS(대기건수). 후자는 우회가 아니라 <더 정확한 서술>이다:
--     우리가 실제로 재는 것은 큐 길이다.
--
--   도메인(타입·크기)도 정본을 따른다.
--     서버명       = SRVR_NM        / 명V100        -> varchar(100)
--     대기건수     = WTNG_NOCS      / 수N10         -> numeric(10)
--     실패건수     = FAIL_NOCS      / 수N10         -> numeric(10)
--     점검일시     = CHCK_DT        / 연월일시분초D -> timestamp
--     배정일시     = ALTMNT_DT      / 연월일시분초D -> timestamp
--     배정사유     = ALTMNT_RSN     / 내용V4000     -> varchar(4000)
--     서버주소     = 주소 분류      / 주소V200      -> varchar(200)
--     상태·유형코드 = 코드 분류      / 코드V20       -> varchar(20)  (이 저장소의 기존 코드 컬럼과 동폭)
--     서버아이디   = 명 분류        / 명V20         -> varchar(20)  (아래 형식 제약과 폭이 같다)
--     일련번호     = 일련번호B20    -> bigint       (이 저장소의 _SN 컬럼 전부가 bigint 다)
--
-- ----------------------------------------------------------------------------
-- * ★ srvr_id 형식 제약 — 하이픈·대문자를 DB 가 막는다
-- ----------------------------------------------------------------------------
--   이 값은 뒤에서 서킷브레이커 이름과 메트릭 라벨로 조립된다(용도 축 - 노드 축). 실제 장비
--   호스트명(klid-ai-gpu-01 꼴)을 그대로 쓰면 조립 결과에서 <어디서 갈리는지 파싱으로 복원되지
--   않아> 라벨이 조용히 어긋난다. 조용한 어긋남은 한참 뒤 대시보드에서야 드러난다.
--
--   그래서 <입구에서> 막는다. 실제 호스트명은 srvr_nm 에 따로 둔다.
--   이 제약은 3중 강제의 첫 겹이다 — (1)여기 (2)기동 가드 (3)관리 화면 입력 검증.
--   ★ 정규식 문자열의 단일 진실원은 애플리케이션의 식별자 정책이며, 통합 시험이 그 값과 이
--     제약 정의를 <대조>한다. 두 벌이 되면 한쪽만 통과하는 값이 조용히 생긴다.
--
--   ⚠ 형식 제약이 있어도 기동 가드를 함께 두는 이유: 제약을 우회해 들어온 행(운영 SQL·제약
--     도입 이전 데이터)을 <읽는 쪽>이 막아야 하기 때문이다. 어느 한 겹도 다른 겹을 대체하지 않는다.
--
-- ----------------------------------------------------------------------------
-- * ★ raw_sn 유니크 — 「같은 영상은 같은 노드로」의 근거
-- ----------------------------------------------------------------------------
--   추적기는 노드 프로세스의 로컬 메모리에 있어, 한 영상의 프레임이 두 노드로 흩어지면 추적이
--   끊긴다. 배정 행이 <영상당 하나> 라는 것이 그 보장의 전부다.
--
--   그리고 2노드 Active-Active 라 같은 영상의 배정이 <동시에> 일어날 수 있다. 유니크 제약이
--   중복을 막고, 애플리케이션은 충돌 시 예외가 아니라 <이긴 쪽의 배정을 그대로 읽는다> —
--   진 쪽도 하려던 일이 이미 끝나 있을 뿐이라 실패가 아니다.
--
-- ----------------------------------------------------------------------------
-- * ★ 마지막 가용 노드 보호는 <조건부 UPDATE + 잠금 조회>가 함께 있어야 성립한다
-- ----------------------------------------------------------------------------
--   가용 노드가 0이 되면 AI 기능 전체가 멈춘다. 그래서 마지막 하나는 내리지 못하게 한다.
--
--   조회 후 UPDATE 로 만들면 두 관리자가 <서로 다른> 노드를 동시에 내릴 때 둘 다 통과한다.
--   그런데 조건부 UPDATE 만으로도 부족하다 — 서로 다른 행을 건드리면 행 잠금이 부딪히지 않고,
--   각자의 스냅샷에서 "가용 2" 를 보기 때문이다(읽기 커밋 격리에서 실제로 재현된다).
--
--   그래서 애플리케이션 쿼리는 UPDATE 앞에 <가용 노드 전부를 잠그는 조회>를 CTE 로 붙인다.
--   먼저 온 쪽이 두 행을 모두 잠그고, 뒤에 온 쪽은 그 잠금에서 기다렸다가 <갱신된 행으로 조건을
--   다시 평가>해 "가용 1" 을 보고 스스로 물러난다. 통합 시험이 이 경합을 실제 스레드로 재현한다.
--
--   ⇒ 이 표에 그것을 강제하는 제약은 없다(SQL 로 표현할 수 없다). 규칙의 자리는 그 쿼리 하나이며,
--     조회 후 UPDATE 로 되돌리지 말 것.
--
-- ----------------------------------------------------------------------------
-- * 외래키 방향과 삭제 정책
-- ----------------------------------------------------------------------------
--   배정 -> 원장 : ON DELETE RESTRICT. 배정이 딸린 노드를 지우면 그 영상들이 갈 곳을 잃는다.
--     노드를 내리는 정상 동선은 삭제가 아니라 <정비 지정 후 잔여 배정 0 확인> 이다.
--
--   ★ raw_sn 에는 물리 외래키를 걸지 않는다. 배정은 <어느 노드로 보냈는가> 의 기록이고, 추적
--     불연속의 원인을 나중에 가릴 유일한 근거라 영상보다 오래 살아남을 수 있다.
--
-- ----------------------------------------------------------------------------
-- * 시드를 넣지 않는다 (부트스트랩이 확정 동작이다)
-- ----------------------------------------------------------------------------
--   환경마다 추론 서버 주소가 다른데 마이그레이션은 애플리케이션 설정을 읽지 못한다. 그래서 이
--   파일은 <빈 표> 를 만들고 끝나고, 기동 시 원장이 비어 있으면 애플리케이션이 기존 설정값으로
--   노드 하나를 세운다. 기존 배포는 이 변경 이후에도 <설정값 그대로> 동작한다.
--
-- ----------------------------------------------------------------------------
-- * 멱등 · 두 경로 수렴
-- ----------------------------------------------------------------------------
--   CREATE ... IF NOT EXISTS + <제약은 카탈로그 조회 후 없을 때만 추가> 라 두 번 돌아도 안전하다.
--   ★ 카탈로그 조회는 conrelid = to_regclass(...) 로 <스코프> 한다. 스코프 없는 조회는 다른
--     스키마의 동명 객체를 보고 조작 대상과 조회 대상이 어긋난다(이 저장소에서 실제로 깨졌다).
--   · 신규 설치(빈 DB) : V1 이 이 표 없이 스키마를 만들고 여기서 추가된다.
--   · 기존 DB          : V22 까지 적용된 상태에서 여기서 추가된다.
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   신규 테이블 생성뿐이라 기존 트래픽과 경합하지 않는다. 이 변경은 <하위호환> 이다 — 구 jar
--   노드는 이 표를 모르고 설정값으로 그대로 동작한다. 롤링 재기동으로 무중단 배포가 가능하다.
--
-- ★ 롤백 절차 — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   되돌리면 등록한 노드 목록과 영상 배정 기록이 사라진다(추적 불연속의 근거도 함께 사라진다).
--
--     DROP TABLE ls_ai_srvr_altmnt;
--     DROP TABLE ls_ai_srvr;
--     DROP SEQUENCE ls_ai_srvr_altmnt_seq;
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '23';
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 커넥션의 search_path(기본 klid_at)가
--     정하고, 카탈로그 조회는 to_regclass 로 스코프해 조회 대상과 조작 대상을 같게 만든다.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) ls_ai_srvr — AI 서버 원장
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_ai_srvr (
    srvr_id character varying(20) NOT NULL,
    srvr_nm character varying(100),
    srvr_addr character varying(200) NOT NULL,
    srvr_type_cd character varying(20) NOT NULL,
    srvr_stts_cd character varying(20) NOT NULL,
    wtng_nocs numeric(10) DEFAULT 0 NOT NULL,
    chck_dt timestamp without time zone,
    chck_fail_nocs numeric(10) DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ls_ai_srvr_pkey'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ls_ai_srvr_pkey PRIMARY KEY (srvr_id);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_srvr_id_format'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_srvr_id_format
            CHECK (srvr_id ~ '^[a-z0-9]{1,20}$');
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_stts_cd'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_stts_cd
            CHECK (srvr_stts_cd IN ('AVAILABLE', 'UNAVAILABLE', 'DRAINING', 'DISABLED'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_type_cd'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_type_cd
            CHECK (srvr_type_cd IN ('INFERENCE', 'TIMESERIES'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_nocs_nonneg'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_nocs_nonneg
            CHECK (wtng_nocs >= 0 AND chck_fail_nocs >= 0);
    END IF;
END $$;

COMMENT ON TABLE ls_ai_srvr IS
    'AI서버 — 추론 요청을 나눠 보낼 서버(노드) 목록의 단일 진실원. 기존 설정값은 원장이 비었을 때 최초 1회 씨앗으로만 쓰이며, 그 뒤로는 이 표가 이긴다(설정과 원장을 둘 다 진실원으로 두면 어느 쪽이 이기는지가 코드에 흩어진다). ★가용 상태인 행이 0이 되면 AI 기능 전체가 멈추므로 마지막 가용 노드의 강등은 애플리케이션이 거부한다 — 그 판정은 조회 후 UPDATE 가 아니라 가용 행 전체를 잠그는 조건부 UPDATE 여야 한다(서로 다른 행을 동시에 내리면 행 잠금이 부딪히지 않아 둘 다 통과한다).';
COMMENT ON COLUMN ls_ai_srvr.srvr_id IS
    'AI서버아이디 — ★소문자와 숫자만, 20자 이내(체크 제약). 이 값이 서킷브레이커 이름과 메트릭 라벨로 조립되는데, 하이픈이 섞이면 조립 결과에서 어디서 갈리는지 파싱으로 복원되지 않아 라벨이 조용히 어긋난다. 실제 장비 호스트명은 srvr_nm 에 따로 둔다.';
COMMENT ON COLUMN ls_ai_srvr.srvr_nm IS
    'AI서버명 — 실제 장비 호스트명 등 사람이 읽는 이름. 형식 제약이 없다(식별자 축과 표시 축을 갈라 둔 이유가 이것이다).';
COMMENT ON COLUMN ls_ai_srvr.srvr_addr IS
    'AI서버주소 — 호출 기준 주소(스킴 포함). ★내부 토폴로지라 로그·오류 응답에 전문을 싣지 않는다.';
COMMENT ON COLUMN ls_ai_srvr.srvr_type_cd IS
    'AI서버유형코드 — INFERENCE(추론 서버) | TIMESERIES(외부 시계열 분석). 두 축은 부하의 성질이 근본적으로 달라(전자는 동기 호출, 후자는 논블로킹 제출 + 콜백) 같은 목록에서 섞어 고르면 안 된다.';
COMMENT ON COLUMN ls_ai_srvr.srvr_stts_cd IS
    'AI서버상태코드 — AVAILABLE(가용) | UNAVAILABLE(이용불가) | DRAINING(정비중) | DISABLED(비활성). ★금지 전이 두 개가 있다: 이용불가→정비중(죽은 노드는 정비 대상이 아니다. 이미 배제됐다) · 정비중→이용불가(정비 중 상태점검 실패는 상태를 바꾸지 않고 기록만 한다). 전이 규칙은 애플리케이션 상태 enum 이 소유한다.';
COMMENT ON COLUMN ls_ai_srvr.wtng_nocs IS
    'AI서버대기건수 — 그 노드의 큐 길이. 「부하」는 표준용어에 없어 실제로 재는 값인 대기건수로 적었다. 상태점검 배치가 갱신하며 배정 시 최소인 노드를 고른다.';
COMMENT ON COLUMN ls_ai_srvr.chck_dt IS 'AI서버점검일시 — 마지막 상태점검 시각.';
COMMENT ON COLUMN ls_ai_srvr.chck_fail_nocs IS
    'AI서버점검실패건수 — 연속 실패 횟수. 한 번의 네트워크 흔들림으로 노드를 내리지 않기 위해 연속 실패를 센다(성공하면 0으로 되돌린다).';
COMMENT ON COLUMN ls_ai_srvr.reg_dt IS 'AI서버등록일시.';
COMMENT ON COLUMN ls_ai_srvr.mdfr_id IS
    'AI서버수정자아이디 — 노드를 마지막으로 바꾼 사람. 별도 감사 테이블을 두지 않고 이 두 컬럼과 로그로 충당한다(연동 서버 주소 설정과 같은 축).';
COMMENT ON COLUMN ls_ai_srvr.mdfcn_dt IS 'AI서버수정일시.';

-- ---------------------------------------------------------------------------
-- 2) ls_ai_srvr_altmnt — 영상별 노드 배정
-- ---------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ls_ai_srvr_altmnt_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE IF NOT EXISTS ls_ai_srvr_altmnt (
    altmnt_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    srvr_id character varying(20) NOT NULL,
    altmnt_dt timestamp without time zone NOT NULL,
    altmnt_rsn character varying(4000)
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_altmnt') AND conname = 'ls_ai_srvr_altmnt_pkey'
    ) THEN
        ALTER TABLE ls_ai_srvr_altmnt ADD CONSTRAINT ls_ai_srvr_altmnt_pkey PRIMARY KEY (altmnt_sn);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_altmnt') AND conname = 'uk_ls_ai_srvr_altmnt_raw_sn'
    ) THEN
        ALTER TABLE ls_ai_srvr_altmnt ADD CONSTRAINT uk_ls_ai_srvr_altmnt_raw_sn UNIQUE (raw_sn);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_altmnt') AND conname = 'fk_ls_ai_srvr_altmnt_srvr'
    ) THEN
        ALTER TABLE ls_ai_srvr_altmnt ADD CONSTRAINT fk_ls_ai_srvr_altmnt_srvr
            FOREIGN KEY (srvr_id) REFERENCES ls_ai_srvr (srvr_id) ON DELETE RESTRICT;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS ix_ls_ai_srvr_altmnt_srvr_id ON ls_ai_srvr_altmnt (srvr_id);

COMMENT ON TABLE ls_ai_srvr_altmnt IS
    'AI서버배정 — 영상을 어느 노드로 보내기로 했는지의 기록. ★영상당 한 건(raw_sn 유니크)인 것이 「같은 영상의 프레임은 같은 노드로 간다」는 보장의 전부다. 추적기는 노드 프로세스의 로컬 메모리에 있어 프레임이 두 노드로 흩어지면 추적이 끊긴다. 2노드가 같은 영상을 동시에 배정하면 진 쪽은 예외를 받는 대신 이긴 쪽의 배정을 그대로 읽는다(하려던 일이 이미 끝나 있을 뿐이라 실패가 아니다). ⚠ 배정 대상은 배치 축(영상 단위)뿐이다 — 상호작용 요청은 식별자가 요청 1건짜리라 기록해도 재사용되지 않고 쓰레기 행만 쌓인다.';
COMMENT ON COLUMN ls_ai_srvr_altmnt.altmnt_sn IS 'AI서버배정일련번호.';
COMMENT ON COLUMN ls_ai_srvr_altmnt.raw_sn IS
    'AI서버배정원시데이터일련번호 — 배정 대상 영상. ★유니크다. 물리 외래키는 걸지 않는다 — 이 행은 추적 불연속의 원인을 나중에 가릴 근거라 영상보다 오래 살아남을 수 있다.';
COMMENT ON COLUMN ls_ai_srvr_altmnt.srvr_id IS 'AI서버배정AI서버아이디 — 배정된 노드.';
COMMENT ON COLUMN ls_ai_srvr_altmnt.altmnt_dt IS 'AI서버배정일시.';
COMMENT ON COLUMN ls_ai_srvr_altmnt.altmnt_rsn IS
    'AI서버배정사유 — 노드 이탈로 다시 배정했을 때만 채운다. ★이 값이 있으면 추적 불연속의 원인이 재배정이고, 없는데 추적이 끊겼으면 추적기 만료·노드 재기동·캐시 축출 셋 중 하나다. 그 대조가 오진을 막는 유일한 수단이라, 기록은 재배정과 같은 트랜잭션에서 남긴다(비동기로 빼면 「기록이 없다」와 「아직 기록이 안 됐다」가 구분되지 않는다).';
