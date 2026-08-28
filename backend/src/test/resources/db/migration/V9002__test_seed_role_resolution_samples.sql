-- ============================================================
-- TEST-ONLY 시드 — ADR-055(진입 시 작업자 자동 등록) 이후의 역할 해석 표본 고정.
--
-- 본 파일은 src/test/resources 에만 존재하므로 운영 빌드 Flyway 에는 포함되지 않는다.
--
-- 배경: 자동 등록(AC-126)이 생기면서 <시드에 없는 숫자 sub> 는 더 이상 "역할 없음" 을 뜻하지
--   않는다 — 첫 요청에 WORKER 로 등록되기 때문이다. 그런데 여러 회귀 테스트가 그 성질에
--   기대어 표본을 골라 두었고, 그 테스트들이 실제로 지키는 것은 <매처가 role=null 을 막는가>
--   이지 <미배정 사용자가 계속 존재하는가> 가 아니다. 표본을 명시적으로 심어 의도를 보존한다.
--
--   770001 → 'LEARN_MANAGER' (관제 고유 역할 — 저작도구 Role enum 에 없음)
--     UserRoleResolver 가 enum 밖 코드를 fail-closed 로 null 처리하고, 자동 등록은
--     ON CONFLICT DO NOTHING 이라 이 행을 덮지 않는다. 두 성질이 <함께> 성립해야만 이 표본이
--     role=null 로 유지된다 — 하나라도 깨지면 그 테스트들이 RED 가 되며 그것이 의도된 신호다.
--     (덮어쓰기 금지는 AC-126 의 절반이다: 관리자가 지정한 역할이 되돌려지면 안 된다.)
--
--   424242 → 'WORKER' ("역할은 있으나 그 영상에 배정되지 않은 작업자" 표본)
--     자동 등록에 맡기면 LS_ACNT_USER 행까지 생겨 작업자 목록·사용자 목록 픽스처를 오염시킨다.
--     역할만 심어 두면 등록기가 아예 호출되지 않아 마스터 행이 생기지 않는다.
--
-- USER_NO 단일 PK + ON CONFLICT DO NOTHING 으로 멱등. LS_ACNT_USER FK 미설정(ID 참조)이라
-- 마스터 행 없이도 역할 해석에 충분하다.
-- ⚠ test-data.sql 이 LS_USER_ROLE 을 통째로 DELETE 후 재삽입하므로, 그쪽 superset 에도 같은
--   두 행이 있어야 한다(한쪽만 고치면 @Sql 적재 테스트에서만 조용히 깨진다).
-- ============================================================

INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES
  (770001, 'LEARN_MANAGER', CURRENT_TIMESTAMP),
  (424242, 'WORKER',        CURRENT_TIMESTAMP)
ON CONFLICT (USER_NO) DO NOTHING;
