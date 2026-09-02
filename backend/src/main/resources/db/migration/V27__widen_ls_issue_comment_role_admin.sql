-- ============================================================================
-- V27 — 이슈 댓글 작성자 역할 허용값에 관리자(ADMIN)를 넣는다
--
-- 관리자가 검수 화면에서 이슈 스레드에 댓글을 달면 <500 이 났다>. 원인은 한 층만 뒤처진
-- 허용목록이다 — 관리자 역할이 생기고 <관리자가 검수자 권한을 계층으로 물려받게> 되면서
-- 관리자가 검수자용 창구를 그대로 통과하는데, 이 표의 CHECK 제약만 옛 두 값으로 남아
-- 저장 시점에 제약 위반이 터졌다.
--
-- ★ 나머지 층은 <전부 이미 관리자를 전제하고 있었다>. 그래서 이건 새 기능이 아니라
--   뒤처진 한 층을 맞추는 일이다:
--     - 창구 인가   : 계층 덕분에 관리자가 통과한다(창구 자신은 검수자·작업자로 열려 있다)
--     - 서비스      : 작성자 역할을 인증 주체에서 그대로 읽어 넣는다(관리자면 ADMIN 이 들어간다)
--     - 응답 계약   : 작성자 역할 열거가 이미 작업자·검수자·관리자 셋이다
--     - 화면        : 작성자 역할 표시 이름표에 관리자가 이미 있다
--     - 설계(ERD)   : 이 마이그레이션에 앞서 허용값에 관리자를 먼저 넣었다
--     - 이 CHECK    : <여기만> 두 값이었다
--
-- ★ 방향은 <넓히기>다. 관리자가 댓글을 다는 것은 막아야 할 동작이 아니다 — 관리자를
--   막으려면 그 창구에 예외를 명시해야 하고, 명시가 없으면 관리자는 통과한다는 것이
--   역할 계층의 확정 규칙이다. 인가를 좁혀서 고치지 말 것.
--
-- ⚠ 이 마이그레이션은 <허용값만> 넓힌다. 컬럼 타입(varchar(20))·NOT NULL·기존 행은 그대로다.
--   'ADMIN' 은 5자라 폭에 여유가 있다. 이미 저장된 작업자·검수자 댓글은 전부 그대로 유효하다.
--
-- ⚠ 제약을 <없애는> 것이 아니다 — 셋 밖의 값(예: 포털 회원)은 여전히 거부된다.
--   이 표의 작성자 역할은 내부 채널 세 역할만 가질 수 있다.
--
-- 되돌리기:
--   ALTER TABLE ls_issue_comment DROP CONSTRAINT ck_ls_issue_comment_role;
--   ALTER TABLE ls_issue_comment ADD CONSTRAINT ck_ls_issue_comment_role
--       CHECK (author_role_cd IN ('WORKER', 'REVIEWER'));
--   ⚠ 되돌리기 전에 author_role_cd = 'ADMIN' 인 행을 먼저 처리해야 한다 — 남아 있으면
--     제약 재생성 자체가 실패한다.
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_issue_comment')
          AND conname = 'ck_ls_issue_comment_role'
    ) THEN
        ALTER TABLE ls_issue_comment DROP CONSTRAINT ck_ls_issue_comment_role;
    END IF;
END $$;

ALTER TABLE ls_issue_comment
    ADD CONSTRAINT ck_ls_issue_comment_role
    CHECK (author_role_cd IN ('ADMIN', 'WORKER', 'REVIEWER'));

COMMENT ON COLUMN ls_issue_comment.author_role_cd IS
    '작성자역할 — 작성 시점 역할이며 표시와 상태 전이 판정에 쓴다. 허용값은 관리자(ADMIN)·라벨링 작업자(WORKER)·검수자(REVIEWER) 셋이다. ★관리자가 들어 있는 이유(V27): 관리자는 검수자 권한을 계층으로 물려받아 이 창구에 그대로 들어오므로 작성자 역할로 관리자도 저장된다. 관리자가 실제로 쓰지 않는 값처럼 보인다고 이 목록에서 빼지 말 것 — 빼면 관리자가 댓글을 다는 순간 제약 위반으로 실패한다. 셋 밖의 값(포털 회원 등)은 계속 거부된다.';
