-- =============================================================================
-- V169: 저작도구 소유 사용자 마스터(LS_ACNT_USER) 신설 + 관제 사용자 마스터(MNG_ACCT_USER) 제거
--       ★ 이것으로 MNG_* 9종 제거가 완료된다 (V162·V165·V167·V168·V169).
--
-- 배경(관제 데이터 참조 전면 제거 — Phase 5): 지금까지 사용자 마스터는 "관제가 채워 준다"는 전제
--   아래 READ 전용(@Immutable)으로 조회만 했다. 그러나 실측상 <아무도 채우지 않았다> — 관제 2차
--   실DB 에는 MNG_ 접두 테이블이 0개이고, 저작도구 코드에도 쓰기 경로가 0이었다. 즉 dev 시드로
--   심어 둔 5행이 전부였고, 신규 사용자는 DBA 가 손으로 넣지 않으면 역할 클레임이 404 로 막혔다.
--
--   신규 규칙(사용자 확정 2026-08-04):
--     * 사용자 정보는 관제가 <브라우저 localStorage 로 인계>한다(userId·userNm). 저작도구는
--       <역할 클레임 시점에 그 값으로 자동등록>한다(원자 upsert). 즉 이 테이블을 채우는 주체는
--       이제 저작도구 자신이며, 그래서 소유가 LS_ 로 넘어온다(@Immutable 해제).
--     * userNo 는 <JWT sub 에서만> 취한다 — 요청 바디의 값을 신뢰하지 않는다(CWE-639 IDOR).
--       userId·userNm 은 표시용이라 위조해도 자기 행의 표시 이름만 바뀐다.
--
-- 표준용어·표준도메인 근거 (신규 객체 — 사전 우선순위: 행안부 공통표준 우선, 없을 때만 사업표준):
--   LS_ACNT_USER                     LS_(저작도구 소유 접두) + 계정 ACNT + 사용자 USER
--     ★구 이름의 'ACCT' 는 공통표준단어·사업표준단어 <어디에도 없다>. 공통표준단어의 계정은
--       ACNT(Account, 5차) 다. 관제 소유 컬럼명을 무비판 승계하면 신규 테이블에 비표준 약어를
--       새로 심는 셈이라(=감리 지적) 승계하지 않는다. V168 에서 EVNT_CLS_CD 의 'CLS'(종목 Class)
--       를 승계하지 않고 CLSF(분류)로 간 것과 같은 판단이다.
--   USER_NO       BIGINT             공통표준용어 <사용자번호 USER_NO>. 표준도메인은 번호V10
--                                    (VARCHAR 10)이나 ★타입은 BIGINT 를 유지한다 — LS_USER_ROLE ·
--                                    LS_TASK_ASSIGNMENT · LS_TASK_ASSIGN_HISTORY · LS_TASK_EVENT_LOG
--                                    등 저작도구 소유 컬럼이 전부 BIGINT 이고 JWT sub 도 수치라,
--                                    이 테이블만 문자로 바꾸면 조인·비교가 전부 깨진다. 문서화된
--                                    이탈이며 물리명은 표준을 그대로 따른다.
--   USER_ID       VARCHAR(20)        공통표준용어 <사용자아이디 USER_ID> / 도메인 명V20.
--                                    구 컬럼은 VARCHAR(64) 였다(비표준 크기). 이관값 최대 길이는 9.
--   USER_NM       VARCHAR(100)       공통표준용어 <사용자명 USER_NM> / 도메인 명V100.
--                                    구 컬럼은 VARCHAR(128) 이었다(비표준 크기). 이관값 최대 6.
--   USER_EML_ADDR VARCHAR(320)       공통표준용어 <이메일주소 EML_ADDR> / 도메인 주소V320 에
--                                    사용자(USER) 수식. ★구 이름 USER_EMAIL 의 'EMAIL' 은 공통표준
--                                    단어 <이메일(EML)> 의 금칙어로 명시돼 있어 승계 불가.
--                                    255 → 320 확대라 이관 시 잘림이 없다.
--   USE_YN        CHAR(1)            공통표준용어 <사용여부 USE_YN> / 도메인 여부C1(CHAR(1), Y/N).
--                                    구 컬럼은 VARCHAR(1) 이었다.
--   REG_DT        TIMESTAMP          공통표준용어 <등록일시 REG_DT> / 도메인 연월일시분초D.
--   MDFCN_DT      TIMESTAMP          공통표준용어 <수정일시 MDFCN_DT> / 도메인 연월일시분초D.
--                                    ★구 이름 UPD_DT 의 'UPD' 는 표준단어가 아니다(수정=MDFCN).
--                                    이 프로젝트도 V107/V108 이후 신규 테이블은 MDFCN_DT 를 쓴다.
--
-- NULL 정책(의도적 — "지어내지 않는다"):
--   * USER_ID   nullable — 관제가 인계값을 안 보내면 <미수신(null)> 이다. userNo 를 아이디처럼
--     지어 넣으면 진짜 아이디와 구분이 사라진다.
--   * USER_NM   NOT NULL DEFAULT '' — 표시명은 <없으면 빈 문자열> 이 이 프로젝트의 기존 관례다
--     (ReviewResponse.workerName = 조회 실패 시 ""). 게다가 소비측이 Collectors.toMap(userNo,
--     userNm) 으로 이름 맵을 만드는데 ★toMap 은 null 값에 NPE 를 던진다 — nullable 로 두면
--     자동등록 직후 목록·통계 화면이 통째로 500 이 된다.
--   * USER_EML_ADDR nullable — 관제 인계 키에 이메일이 없다. 이관된 기존 5행만 값을 갖는다.
--
-- 제약 정책: USER_ID 에 UNIQUE 를 <걸지 않는다>. 자동등록은 USER_NO 로 upsert 하는데, 표시용
--   아이디에 유니크가 걸리면 관제가 같은 아이디를 다른 userNo 로 보내는 순간 제약 위반이 나고
--   PostgreSQL 이 <클레임 트랜잭션 전체를 abort> 시켜 역할 부여 자체가 실패한다. 표시 필드 때문에
--   인증 동선을 막는 교환은 하지 않는다(구 MNG_ACCT_USER 에도 유니크가 없었다).
--
-- 데이터 영향: 기존 MNG_ACCT_USER 전 행을 이관한다(dev/246 실측 5행). LEFT() 로 감싸는 이유는
--   축소 컬럼(64→20, 128→100)에 예상 밖 장문이 있어도 <마이그레이션이 실패하지 않게> 하기 위함이다
--   — 여기서 실패하면 2노드가 모두 기동하지 못한다. 실측값은 최대 9자라 실제 잘림은 없다.
--   같은 커밋에서 db/seed/dev-seed.sql · src/test/resources/db/test-data.sql 의 INSERT 도 함께
--   교체한다(누락 시 시드/IT 가 전량 실패).
--
-- 소유권(★ "MNG_* 변경은 관제서버팀 선승인 필수" 규칙과의 관계): MNG_ACCT_USER 는 이름만 MNG_
--   접두일 뿐 <저작도구가 자기 스키마에 V1 로 직접 CREATE 한 테이블>이다. 관제 2차 실DB 에는 MNG_
--   접두 테이블이 0개이며 저작도구는 관제 DB 를 조회하지 않는다. 따라서 이 DROP 은 공유 스키마
--   변경이 아니라 자체 소유 객체 정리다. 데이터마트 뷰(V_COMPLETED_*)도 이 테이블을 참조하지 않는다.
--
-- Flyway 순서 안전성(신규 설치 포함): V1(CREATE) → V75(권한 이관, 참조만) → V169(이관·DROP, 이 파일).
--   즉 이관 시점에 원본이 반드시 존재한다. V1/V75 는 이미 적용된 이력이라 내용을 수정할 수 없다
--   (체크섬 불일치 → 2노드 기동 실패).
--
-- 잠금 주의(2노드 Active-Active): DROP TABLE 은 ACCESS EXCLUSIVE 락을 잡는다. 수 행 규모라 잠금
--   구간이 매우 짧고, 이 커밋 이후 런타임 참조가 0 이라 실질 영향이 없다.
--
-- 범위 한정(★): 이 마이그레이션의 DROP 대상은 <MNG_ACCT_USER 하나뿐>이다. 역할(LS_USER_ROLE)·
--   배정·영상 테이블은 절대 건드리지 않는다. 회귀 가드: MngAcctUserTableRemovalTest.
--
-- ★ 롤백 절차 (Critical — 앱을 이 마이그레이션 적용 <이전> 버전으로 내릴 때 필수)
--   구버전 코드에는 @Table(name = "MNG_ACCT_USER") 엔티티(MngAcctUser)와 그것을 조인하는 QueryDSL
--   쿼리(배정·검수·작업목록 필터)가 존재한다. 스키마를 되돌리지 않고 구버전 jar 로 롤백하면 사용자
--   조회·배정·검수 목록이 런타임에 "relation does not exist" 로 깨진다(ddl-auto=validate 는 이
--   프로젝트에서 실제로 수행되지 않아 기동은 성공하고 <나중에> 터진다).
--   Flyway 는 down-migration 을 수행하지 않으므로 아래 SQL 을 <구버전 재설치 전에> DBA 가 수동
--   적용해야 한다. DDL 은 V1__phase2_base_schema.sql 의 원문(§3.1)과 <컬럼·타입·NULL 허용·DEFAULT
--   절까지> 동일하다 — 이 파일 이후 어떤 마이그레이션도 MNG_ACCT_USER 를 변경하지 않았다.
--   ※ 아래 2)의 복원 INSERT 는 모든 컬럼에 명시값을 넣으므로 DEFAULT 절이 없어도 절차는 성립하지만,
--     DBA 가 이 블록을 <원본 DDL 로 신뢰해 그대로 적용>하는 문서라 원문과 어긋나면 안 된다.
--   (운영 절차 문서: deploy/onprem/docs/07-uninstall-rollback.md § 롤백)
--
--     -- 1) 테이블 재생성 (구버전 조회 경로 복구용) — V1 원문과 DEFAULT 절까지 동일
--     CREATE TABLE IF NOT EXISTS public.MNG_ACCT_USER (
--         USER_NO     BIGINT          NOT NULL,
--         USER_ID     VARCHAR(64)     NOT NULL,
--         USER_NM     VARCHAR(128)    NOT NULL,
--         USER_EMAIL  VARCHAR(255),
--         USE_YN      VARCHAR(1)      NOT NULL DEFAULT 'Y',
--         REG_DT      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
--         UPD_DT      TIMESTAMP,
--         PRIMARY KEY (USER_NO)
--     );
--     -- 2) 데이터 복원 — 신규 테이블이 진실원이므로 <역방향으로> 되돌린다.
--     --    USER_ID 가 null 인 행(관제 미수신)은 구 스키마의 NOT NULL 을 만족하지 못하므로
--     --    userNo 문자열로 채운다(구버전 화면 표시용이며 되돌린 뒤 DBA 가 정정한다).
--     INSERT INTO public.MNG_ACCT_USER
--            (USER_NO, USER_ID, USER_NM, USER_EMAIL, USE_YN, REG_DT, UPD_DT)
--     SELECT USER_NO, COALESCE(USER_ID, CAST(USER_NO AS VARCHAR)), USER_NM,
--            USER_EML_ADDR, USE_YN, REG_DT, MDFCN_DT
--       FROM LS_ACNT_USER
--     ON CONFLICT (USER_NO) DO NOTHING;
--     -- 3) 신설 객체 제거(선택 — 남겨도 구버전 동작에 영향 없다)
--     DROP TABLE IF EXISTS LS_ACNT_USER;
--     -- 4) Flyway 이력에서 이 버전 제거 — 남겨두면 구버전 앱이 "적용됐는데 파일이 없다"로 기동 실패한다.
--     DELETE FROM flyway_schema_history WHERE version = '169';
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 저작도구 소유 사용자 마스터 신설.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS LS_ACNT_USER (
    USER_NO        BIGINT        NOT NULL,
    USER_ID        VARCHAR(20),
    USER_NM        VARCHAR(100)  NOT NULL DEFAULT '',
    USER_EML_ADDR  VARCHAR(320),
    USE_YN         CHAR(1)       NOT NULL DEFAULT 'Y',
    REG_DT         TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MDFCN_DT       TIMESTAMP,
    PRIMARY KEY (USER_NO)
);

COMMENT ON TABLE  LS_ACNT_USER               IS '사용자 마스터(저작도구 소유). 역할 클레임 시점에 관제가 브라우저 localStorage 로 인계한 값(userId·userNm)으로 자동 등록·갱신된다. 인가 역할은 이 테이블이 아니라 LS_USER_ROLE 이 단일 진실원이다.';
COMMENT ON COLUMN LS_ACNT_USER.USER_NO       IS '사용자번호(PK). ★JWT subject(sub)에서만 취한다 — 요청 바디의 값을 신뢰하지 않는다. LS_USER_ROLE.USER_NO 등과 같은 값이다.';
COMMENT ON COLUMN LS_ACNT_USER.USER_ID       IS '사용자아이디(표시용). 관제 인계값이며 미수신이면 null 이다(지어내지 않는다). 인증·인가 판정에 쓰지 않는다.';
COMMENT ON COLUMN LS_ACNT_USER.USER_NM       IS '사용자명(표시용). 관제 인계값이며 미수신이면 빈 문자열이다. 배정·검수·통계 화면의 이름 표시 원천이다.';
COMMENT ON COLUMN LS_ACNT_USER.USER_EML_ADDR IS '사용자이메일주소. 관제 인계 키에 없으므로 자동등록 사용자는 null 이고, 구 관제 마스터에서 이관된 행만 값을 갖는다.';
COMMENT ON COLUMN LS_ACNT_USER.USE_YN        IS '사용여부(Y/N). 작업자 목록·배정 후보에서 비활성 사용자를 제외하는 필터축이다. ★자동등록은 이 값을 갱신하지 않는다 — 운영자가 비활성화한 사용자가 재클레임으로 되살아나면 안 된다.';
COMMENT ON COLUMN LS_ACNT_USER.REG_DT        IS '등록일시.';
COMMENT ON COLUMN LS_ACNT_USER.MDFCN_DT      IS '수정일시. 자동등록이 표시 정보를 실제로 갱신했을 때만 변한다(값이 같으면 갱신하지 않는다).';

-- -----------------------------------------------------------------------------
-- 2) 이관 — 관제 사용자 마스터 전 행.
--
--    ON CONFLICT DO NOTHING: 부분 실패 후 재개나 재실행에서 <이미 자동등록으로 갱신된 이름>을
--    옛 값으로 되돌리지 않는다(멱등).
-- -----------------------------------------------------------------------------
INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT, MDFCN_DT)
SELECT
    u.USER_NO,
    LEFT(NULLIF(TRIM(COALESCE(u.USER_ID, '')), ''), 20),
    LEFT(COALESCE(u.USER_NM, ''), 100),
    LEFT(NULLIF(TRIM(COALESCE(u.USER_EMAIL, '')), ''), 320),
    CASE WHEN UPPER(TRIM(COALESCE(u.USE_YN, ''))) = 'N' THEN 'N' ELSE 'Y' END,
    COALESCE(u.REG_DT, CURRENT_TIMESTAMP),
    u.UPD_DT
FROM MNG_ACCT_USER u
ON CONFLICT (USER_NO) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3) 관제 사용자 마스터 제거 — MNG_* 9종 제거 완료.
--    IF EXISTS — 이미 제거된 DB 에서는 no-op (멱등).
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_ACCT_USER;
