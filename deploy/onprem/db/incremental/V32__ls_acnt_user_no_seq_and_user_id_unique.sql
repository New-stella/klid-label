-- =============================================================================
-- V32: 관제 인계 진입자 userNo 자동발급 시퀀스 + USER_ID 안정키(부분 유니크)
--
-- 배경 (CO-20260905): 관제 인계 토큰은 문자열 로그인ID(예 sub="admin", userId="admin")로
--   식별하고 숫자 userNo 를 싣지 않는다. 우리 인가는 숫자 USER_NO 로 도는데 LS_ACNT_USER 에
--   해당 USER_ID 행이 없으면 userNo 해석이 0건이라 무권한이고, role-claim 부트스트랩도 막힌다.
--   상용은 사용자 시드가 없어(DevSeedRunner=@Profile("local")) 관제 채널이 통째로 잠긴다.
--   → 진입 순간 관제 사용자에게 우리 userNo 를 발급해 로컬 식별 레코드를 만든다(V169 의 "역할
--     클레임 시점 자동등록" 연장 — 회원가입 화면이 아니라 투명 프로비저닝).
--
-- 표준용어·표준도메인 근거:
--   ls_acnt_user_no_seq  LS_(저작도구 소유) + 계정 ACNT + 사용자 USER + 번호 NO + 시퀀스 _seq
--                        (기존 관례 ls_ai_srvr_altmnt_seq · ls_portal_uld_frme_seq 와 동일 접미)
--   uk_ls_acnt_user_user_id  UK_(유니크키 접두, 기존 UK_LS_DATA_AUG_RESL 관례) + 대상 컬럼
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) userNo 발급 시퀀스.
--    ★ 관제가 sub 에 싣는 숫자 userNo·dev 시드(≤ 9001)와 겹치지 않게 <높은 disjoint 범위>에서
--      시작한다(9,000,000,000). 관제 숫자 userNo 는 소규모 serial 이라 이 범위와 충돌하지 않는다.
--    IF NOT EXISTS — 재실행 안전(멱등).
-- -----------------------------------------------------------------------------
CREATE SEQUENCE IF NOT EXISTS ls_acnt_user_no_seq
    START WITH 9000000000
    INCREMENT BY 1
    NO CYCLE;

COMMENT ON SEQUENCE ls_acnt_user_no_seq IS '관제 인계 진입자(비숫자 sub) userNo 자동발급 시퀀스. 관제 숫자 userNo·dev 시드와 겹치지 않는 높은 범위에서 발급한다. 자동증가 발급 후 LS_ACNT_USER 로 원자 upsert.';

-- -----------------------------------------------------------------------------
-- 2) USER_ID 부분 유니크 인덱스 (WHERE user_id IS NOT NULL).
--    ★ 재진입·2노드 동시진입에 같은 사용자가 두 행으로 갈리는 것을 막는다 — 갈리면 이후 조회가
--      다중매칭 fail-closed 로 <다시 잠긴다>. 자동발급 upsert 의 ON CONFLICT(user_id) 원자성 근거.
--    ⚠ V169 가 의도적으로 안 걸었던 제약을 되돌린다(그땐 관제가 userNo 를 줬는데, 이제 우리가
--      발급하니 USER_ID 가 안정 키가 되어야 한다). USER_ID nullable 은 유지 — 부분 인덱스라
--      다수 NULL 은 허용된다(표시값 미수신 행).
--    ⚠ 기존 데이터에 중복 USER_ID 가 있으면 이 인덱스 생성이 <실패>한다(fail-closed) — 상용은
--      빈 테이블, dev 시드는 distinct(reviewer1/worker1/portal1/admin1)라 무해하다. 이관된 행이
--      있는 DB 는 적용 전에 `SELECT user_id, COUNT(*) FROM ls_acnt_user WHERE user_id IS NOT NULL
--      GROUP BY user_id HAVING COUNT(*)>1;` 로 사전점검할 것.
--    IF NOT EXISTS — 재실행 안전(멱등).
-- -----------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS uk_ls_acnt_user_user_id
    ON ls_acnt_user (user_id)
    WHERE user_id IS NOT NULL;
