-- ============================================================================
-- V24: 노드 부하를 <용도별로> 가른다 — 자식 표 신설 + 부모의 단일 대기건수 제거  @design ADR-057
--
--   1) ls_ai_srvr_usg          AI서버용도 (신규 테이블 · 시드 없음)
--   2) ls_ai_srvr.wtng_nocs    제거 (자식으로 옮겼다)
--   3) ls_ai_srvr.chck_scs_nocs 추가 (연속 성공 = 복귀 판정 축)
--
-- V23 은 노드마다 대기건수 <하나>를 뒀다. 그런데 추론 서버는 장비 안에서 실행을 용도별로
-- 갈라 두었다 — 일괄 처리(배치)와 화면에서 쓰는 요청(상호작용)이 서로를 기다리지 않는다.
--
-- 노드를 고를 때 보는 값은 <그 요청 자신의 용도에 해당하는 대기 건수뿐>이다. 두 값을 하나로
-- 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어, 일괄 처리가 밀린
-- 장비를 화면 요청이 <피할 이유가 없는데도 피하게> 된다. 그래서 저장소도 장비마다 값 하나가
-- 아니라 <장비와 용도의 조합>으로 대기 건수를 둔다.
--
-- ⚠ 이 마이그레이션도 <저장 자리만> 만든다. 실제 분산은 아직 하지 않으며, 관측(상태점검·부하
--   조회)조차 설정으로 꺼둔 채 배포된다 — 아래 * 관측을 꺼둔 채 들어가는 이유 참조.
--
-- ----------------------------------------------------------------------------
-- * 왜 부모 컬럼을 남기지 않고 지우는가
-- ----------------------------------------------------------------------------
--   자식으로 옮긴 값을 부모에 남겨 두면 <아무도 쓰지 않는 죽은 컬럼>이 된다. 그리고 죽은 채로
--   있지 않는다 — 다음 사람이 그 값을 "노드 전체 부하"로 읽어 두 용도를 다시 합치게 된다.
--   지금 지우는 편이 싸다. 읽는 코드가 아직 하나도 없기 때문이다(V23 이후 첫 변경이다).
--
--   ⚠ 되돌릴 수 없는 작업이다. 다만 이 컬럼은 <관측값>이라 보존 가치가 없다(다음 상태점검이
--     다시 채운다). 배정 기록(ls_ai_srvr_altmnt)과는 성질이 다르다 — 그쪽은 추적 불연속의
--     원인을 나중에 가릴 근거라 영상보다 오래 살아남아야 한다.
--
-- ----------------------------------------------------------------------------
-- * 왜 연속 성공을 세는 컬럼을 <추가> 하는가
-- ----------------------------------------------------------------------------
--   이용불가 노드의 복귀는 "연속 N회 성공"으로 판정한다. 그 카운터를 폴링 노드의 메모리에 두면
--   2노드 Active-Active 에서 <표본이 갈린다> — 클러스터링은 틱마다 어느 한 노드에서만 발화시키므로
--   두 노드가 각자 반쪽 카운터를 들게 되고, 재기동으로도 사라진다.
--
--   ★ 기존 chck_fail_nocs 를 "부호 있는 카운터"로 겸용하지 않는다. 한 컬럼에 두 축을 담으면
--     읽는 쪽마다 해석이 갈리고, 이 저장소는 이미 그 형태의 결함을 겪었다(생성 결과 축과 사람의
--     검수 결정 축을 한 컬럼에 담았다가 검수 워크플로 자체가 도달 불가가 됐다).
--
-- ----------------------------------------------------------------------------
-- * 처리건수를 함께 두는 이유 (대기건수만으로는 여유를 과소평가한다)
-- ----------------------------------------------------------------------------
--   용도마다 동시에 처리하는 건수가 하나여서, 대기가 없더라도 이미 하나를 처리하는 중이면 그
--   용도는 바쁘다. 대기 건수만 보면 <한가한 장비와 바쁜 장비가 똑같이 "없음"으로 보여> 요청의
--   절반을 바쁜 쪽으로 보내게 된다. 그래서 두 값을 함께 보관하고 <둘을 더한 값을 실효 부하>로 쓴다.
--
--   ⚠ 대기 건수는 상대가 잠그지 않고 세는 <근사값>이다. 정확한 수로 등식을 세우지 말 것.
--
-- ----------------------------------------------------------------------------
-- * 관측을 꺼둔 채 들어가는 이유 (설정 기본값 = 꺼짐)
-- ----------------------------------------------------------------------------
--   상태 점검과 부하 조회는 둘 다 <추론 서버가 요청을 받아 응답할 여유가 있어야> 성립한다.
--   실행을 용도별로 나누기 전에는 추론이 서버의 처리 흐름을 통째로 붙잡아 상태 점검조차 늦어지므로,
--   그 시기에 관측을 켜면 <바쁜 장비를 죽은 장비로 오판>한다. 장비가 하나뿐이면 마지막 하나를
--   지키는 장치가 막아 주지만, 둘 이상이면 먼저 판정된 하나는 그 장치에 걸리지 않아 <멀쩡한 장비를
--   하나 잃는다>. 그래서 부하 축만이 아니라 <상태 점검 축까지 함께> 꺼둔 상태로 들어간다.
--
--   그리고 부하를 알려주는 경로는 실행을 용도별로 나누는 변경과 <함께> 새로 생기므로 그 전에는
--   존재하지 않는다. 그 시기에 그 경로를 부르면 "없는 경로"라는 답이 돌아오는데, 이는 장비에
--   이상이 있다는 뜻이 아니라 <아직 부하를 알리지 않는다>는 뜻이므로 상태점검 실패로 세지 않는다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   용도 = USG (행안부) · 유형 = TYPE (행안부) · 처리 = PRCS (행안부) · 대기 = WTNG (행안부)
--   건수 = NOCS (행안부) · 점검 = CHCK (행안부) · 성공 = SCS (행안부) · 서버 = SRVR (행안부)
--   AI = AI (사업표준 — 행안부 미등록 개념이라 보충)
--
--   ★ <슬롯> 은 양쪽 사전 어디에도 없다. 신규 등록 대신 등록된 말로 바꿔 적었다 — 슬롯 -> USG(용도).
--     이 표가 담는 것이 실제로 "무슨 용도의 부하인가" 이므로 우회가 아니라 더 정확한 서술이다.
--
--   도메인(타입·크기)도 정본을 따른다.
--     용도유형코드   = USG_TYPE_CD    / 코드V20       -> varchar(20)
--     대기건수       = WTNG_NOCS      / 수N10         -> numeric(10)
--     처리건수       = PRCS_NOCS      / 수N10         -> numeric(10)
--     점검성공건수   = CHCK_SCS_NOCS  / 수N10         -> numeric(10)
--     점검일시       = CHCK_DT        / 연월일시분초D -> timestamp
--
-- ----------------------------------------------------------------------------
-- * 외래키 방향과 삭제 정책 — 배정과 <반대> 다
-- ----------------------------------------------------------------------------
--   부하 -> 원장 : ON DELETE CASCADE. 부하는 <관측값>이라 노드보다 오래 살아남을 이유가 없고,
--     남으면 같은 식별자로 노드를 다시 세웠을 때 <죽은 장비의 부하>가 새 장비의 값으로 되살아난다.
--
--   ⚠ 배정(ls_ai_srvr_altmnt)은 RESTRICT 다. 두 표의 정책이 다른 것은 의도이며, "일관성"을 이유로
--     맞추지 말 것 — 한쪽은 기록이고 다른 한쪽은 관측값이다.
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   신규 표 생성 + 신규 컬럼 추가는 경합하지 않는다. DROP COLUMN 도 ACCESS EXCLUSIVE 를 <순간만>
--   잡고 데이터를 다시 쓰지 않는다(PostgreSQL 은 컬럼을 논리적으로만 지운다).
--
--   ⚠ 이 변경은 하위호환이 <아니다>. 구 jar 는 ls_ai_srvr.wtng_nocs 를 엔티티에 매핑하고 있어
--     그 컬럼이 사라지면 기동 검증(ddl-auto=validate)에서 실패한다. 롤링 재기동이 아니라
--     <전 노드 교체 후> 이 마이그레이션이 적용되도록 배포 순서를 잡을 것. 관측이 꺼진 채 나가므로
--     기능 영향은 없다.
--
-- ★ 롤백 절차 — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   되돌리면 용도별 관측값이 사라진다(다음 상태점검이 다시 채우므로 손실이 아니다).
--
--     DROP TABLE ls_ai_srvr_usg;
--     ALTER TABLE ls_ai_srvr DROP COLUMN chck_scs_nocs;
--     ALTER TABLE ls_ai_srvr ADD COLUMN wtng_nocs numeric(10) DEFAULT 0 NOT NULL;
--     ALTER TABLE ls_ai_srvr DROP CONSTRAINT ck_ls_ai_srvr_nocs_nonneg;
--     ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_nocs_nonneg
--         CHECK (wtng_nocs >= 0 AND chck_fail_nocs >= 0);
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '24';
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 커넥션의 search_path 가 정하고,
--     카탈로그 조회는 to_regclass 로 스코프해 조회 대상과 조작 대상을 같게 만든다.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) ls_ai_srvr_usg — 노드 x 용도별 부하 관측값
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_ai_srvr_usg (
    srvr_id character varying(20) NOT NULL,
    usg_type_cd character varying(20) NOT NULL,
    wtng_nocs numeric(10) DEFAULT 0 NOT NULL,
    prcs_nocs numeric(10) DEFAULT 0 NOT NULL,
    chck_dt timestamp without time zone,
    reg_dt timestamp without time zone NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_usg') AND conname = 'ls_ai_srvr_usg_pkey'
    ) THEN
        ALTER TABLE ls_ai_srvr_usg ADD CONSTRAINT ls_ai_srvr_usg_pkey
            PRIMARY KEY (srvr_id, usg_type_cd);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_usg') AND conname = 'fk_ls_ai_srvr_usg_srvr'
    ) THEN
        ALTER TABLE ls_ai_srvr_usg ADD CONSTRAINT fk_ls_ai_srvr_usg_srvr
            FOREIGN KEY (srvr_id) REFERENCES ls_ai_srvr (srvr_id) ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_usg') AND conname = 'ck_ls_ai_srvr_usg_type_cd'
    ) THEN
        ALTER TABLE ls_ai_srvr_usg ADD CONSTRAINT ck_ls_ai_srvr_usg_type_cd
            CHECK (usg_type_cd IN ('BATCH', 'INTERACTIVE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr_usg') AND conname = 'ck_ls_ai_srvr_usg_nocs_nonneg'
    ) THEN
        ALTER TABLE ls_ai_srvr_usg ADD CONSTRAINT ck_ls_ai_srvr_usg_nocs_nonneg
            CHECK (wtng_nocs >= 0 AND prcs_nocs >= 0);
    END IF;
END $$;

COMMENT ON TABLE ls_ai_srvr_usg IS
    'AI서버용도 — 노드 x 용도 조합의 부하 관측값. ★장비마다 값 하나를 두지 않는 것이 이 표의 존재 이유다: 두 용도는 장비 안에서 실행이 격리돼 있어 한쪽이 밀려 있다는 사실이 다른 쪽의 응답을 늦추지 않는다. 두 값을 합쳐 보면 장비 안에서 갈라 놓은 것을 부르는 쪽에서 다시 붙이는 셈이 되어, 일괄 처리가 밀린 장비를 화면 요청이 피할 이유가 없는데도 피하게 된다. 노드를 고를 때 보는 값은 그 요청 자신의 용도에 해당하는 행 하나뿐이다. ⚠ 여기 담기는 것은 상태점검 배치가 마지막으로 관측한 값이며, 상대가 잠그지 않고 세는 근사값이다 — 정확한 수로 등식을 세우지 말 것. 노드를 지우면 함께 사라진다(관측값이라 노드보다 오래 살 이유가 없고, 남으면 같은 식별자로 세운 새 장비에 죽은 장비의 부하가 되살아난다).';
COMMENT ON COLUMN ls_ai_srvr_usg.srvr_id IS
    'AI서버용도AI서버아이디 — 관측 대상 노드. 원장이 사라지면 이 행도 함께 사라진다(CASCADE).';
COMMENT ON COLUMN ls_ai_srvr_usg.usg_type_cd IS
    'AI서버용도유형코드 — BATCH(일괄 처리) | INTERACTIVE(화면에서 쓰는 요청). 「슬롯」은 표준용어에 없어 이 표가 실제로 담는 것인 용도로 적었다. ⚠ 셋째 용도가 생기면 상대가 알려주기로 했다 — 그 전까지 모르는 값은 저장하지 않고(체크 제약) 조회 시에도 무시한다(모르는 값 하나 때문에 조회 전체를 실패로 만들지 않는다).';
COMMENT ON COLUMN ls_ai_srvr_usg.wtng_nocs IS
    'AI서버용도대기건수 — 그 용도의 큐 길이. 실효 부하는 이 값 단독이 아니라 처리건수와의 합이다(아래 참조).';
COMMENT ON COLUMN ls_ai_srvr_usg.prcs_nocs IS
    'AI서버용도처리건수 — 그 용도가 지금 처리 중인 건수(0 또는 1 — 용도별 동시 처리가 하나다). ★대기 건수만 보면 한가한 장비와 이미 하나를 잡고 있는 장비가 똑같이 「없음」으로 보여 요청의 절반을 바쁜 쪽으로 보내게 된다. 그래서 이 값을 함께 두고 대기건수와의 합을 실효 부하로 쓴다.';
COMMENT ON COLUMN ls_ai_srvr_usg.chck_dt IS
    'AI서버용도점검일시 — ★우리가 관측한 시각이다. 상대가 응답에 실어 주는 관측 시각은 상대 장비의 시계라, 우리 시계에서 빼면 시계 오차가 그대로 지연으로 잡힌다. 신선도는 우리 폴링 주기로 판단한다.';
COMMENT ON COLUMN ls_ai_srvr_usg.reg_dt IS 'AI서버용도등록일시.';
COMMENT ON COLUMN ls_ai_srvr_usg.mdfr_id IS 'AI서버용도수정자아이디.';
COMMENT ON COLUMN ls_ai_srvr_usg.mdfcn_dt IS 'AI서버용도수정일시.';

-- ---------------------------------------------------------------------------
-- 2) ls_ai_srvr — 단일 대기건수 제거 + 연속 성공 카운터 추가
-- ---------------------------------------------------------------------------
-- 제약을 먼저 떼어낸다. 컬럼을 지우면 이 제약도 함께 사라지지만, 남은 항(chck_fail_nocs >= 0)을
-- 다시 세워야 하므로 순서를 명시한다.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_nocs_nonneg'
    ) THEN
        ALTER TABLE ls_ai_srvr DROP CONSTRAINT ck_ls_ai_srvr_nocs_nonneg;
    END IF;
END $$;

ALTER TABLE ls_ai_srvr DROP COLUMN IF EXISTS wtng_nocs;

ALTER TABLE ls_ai_srvr ADD COLUMN IF NOT EXISTS chck_scs_nocs numeric(10) DEFAULT 0 NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_ai_srvr') AND conname = 'ck_ls_ai_srvr_nocs_nonneg'
    ) THEN
        ALTER TABLE ls_ai_srvr ADD CONSTRAINT ck_ls_ai_srvr_nocs_nonneg
            CHECK (chck_fail_nocs >= 0 AND chck_scs_nocs >= 0);
    END IF;
END $$;

COMMENT ON COLUMN ls_ai_srvr.chck_scs_nocs IS
    'AI서버점검성공건수 — 연속 성공 횟수. 이용불가 노드의 복귀 판정 축이며, 실패가 한 번이라도 끼면 0으로 되돌아간다(흔들리는 노드를 되살렸다 다시 내리는 왕복을 막는다). ★기존 점검실패건수를 부호 있는 카운터로 겸용하지 않고 컬럼을 따로 둔 이유는, 한 컬럼에 두 축을 담으면 읽는 쪽마다 해석이 갈리기 때문이다. 폴링 노드의 메모리에 두지 않는 이유는 2노드가 틱을 나눠 갖고 재기동으로도 사라지기 때문이다.';
