-- ============================================================================
-- V10: LS_ISSUE_COMMENT.DATA_ISSUE_SN 에 FK(ON DELETE RESTRICT)를 건다  @req R2  @design ERD-023
--
--   fk_ls_issue_comment_issue : LS_ISSUE_COMMENT(DATA_ISSUE_SN) -> LS_DATA_ISSUE(DATA_ISSUE_SN)
--                               ON DELETE RESTRICT
--
-- 설계 진실원(ERD-023)은 이 컬럼에 <references + on_delete: restrict> 를 명시하는데, 실제 DB 에는
-- 인덱스(ix_ls_issue_comment_issue)만 있고 FK 가 없다. 즉 설계에 있는 참조 무결성이 구현에서만
-- 빠져 있는 상태이며, 여기서 그 사양을 DB 에 반영한다.
--
-- ----------------------------------------------------------------------------
-- * 무엇이 실제로 깨져 있었나 — 조용한 고아
-- ----------------------------------------------------------------------------
--   부모 LS_DATA_ISSUE 는 DATA_RAW_SN 이 NOT NULL 이고 fk_ls_data_issue_raw 가 ON DELETE CASCADE 다.
--   따라서 <영상이 지워지면 이슈도 함께 사라진다>. 그런데 댓글에는 FK 가 없으므로 그 순간 댓글은
--   <존재하지 않는 이슈를 가리킨 채> 남는다. FK 위반으로 시끄럽게 실패하는 것이 아니라 조용히
--   남으므로 아무도 모른다 — 이 스키마가 반복해서 밟은 실패 클래스다(LS_DATA_LBL·LS_DATA_SRC_HSTRY 등).
--
--   ⚠ "이 저장소는 원래 FK 를 안 단다" 는 변호는 성립하지 않는다 — V1 베이스라인에만 FK 가 46개 있다.
--     이 컬럼은 <설계가 걸라고 적었는데 빠진> 것이지 정책적으로 뺀 것이 아니다.
--
-- ----------------------------------------------------------------------------
-- * 왜 CASCADE 가 아니라 RESTRICT 인가
-- ----------------------------------------------------------------------------
--   설계(ERD-023)가 restrict 로 규정했다. 뜻은 <댓글이 달린 이슈는 그냥 지울 수 없다>이며,
--   지우려는 쪽이 댓글을 먼저 걷어내도록 강제해 "대화 기록만 소리 없이 증발" 하는 것을 막는다.
--   편의를 이유로 CASCADE 로 바꾸지 말 것 — 그러면 이 FK 는 고아만 막고 <기록 소실은 그대로> 허용한다.
--
--   ⚠ RESTRICT 는 상위 CASCADE 를 <타고 내려오지 않는다>. LS_DATA_RAW 를 지우면 CASCADE 로
--     LS_DATA_ISSUE 가 지워지려 하는데, 그 이슈에 댓글이 남아 있으면 그 시점에 RESTRICT 가 걸려
--     <RAW 삭제 자체가 실패>한다. 그래서 이 FK 는 코드 변경과 <반드시 짝>이다 — 파생영상 폐기 스윕
--     (AugmentDiscardPurgeTxService.DELETE_ORDER)에 LS_ISSUE_COMMENT 선삭제 단계를 함께 넣는다.
--     넣지 않고 이 FK 만 걸면 폐기 스윕이 전량 FK 위반으로 실패한다.
--
-- ----------------------------------------------------------------------------
-- * 고아 행을 먼저 지운다 (이 파일이 단순 ALTER 가 아닌 이유)
-- ----------------------------------------------------------------------------
--   기존 DB(로컬·dev)에는 위 경위로 생긴 고아 댓글이 <이미 있을 수 있다>. 그 상태에서 FK 를 걸면
--   ALTER 가 검증 단계에서 실패하고 마이그레이션 전체가 멈춘다(= 앱 기동 불가). 그래서 FK 를 걸기
--   <전에> 부모가 없는 댓글을 삭제한다.
--
--   ★ 삭제되는 행이 있을 수 있다 — 그 댓글이 가리키던 이슈는 <이미 사라진> 상태이므로 화면·API 어디에도
--     노출되지 않고(조회는 전부 DATA_ISSUE_SN 로 들어간다) 복원할 부모도 없다. 즉 되살릴 수 없는 잔재를
--     걷어내는 것이지 살아있는 기록을 지우는 것이 아니다. 삭제 건수는 NOTICE 로 남긴다.
--
--   ⚠ 삭제 <내용>은 로그에 싣지 않는다 — 댓글 본문은 사람이 쓴 자유 텍스트라 PII 가 섞일 수 있다
--     (CWE-359/532). 건수만 남긴다.
--
--   ⚠ 신규 설치(빈 DB)에서는 0건이다. 두 번 돌아도 안전하다(고아 재발 없음 + FK 존재 시 no-op).
--
-- ----------------------------------------------------------------------------
-- * 두 경로가 수동 개입 없이 같은 결과로 수렴한다
-- ----------------------------------------------------------------------------
--   · 신규 설치(빈 DB)  : V1 이 FK 없이 테이블을 만들고 여기서 FK 가 붙는다.
--     V1__baseline.sql 은 이미 적용된 이력이라 고치지 않는다(체크섬 불일치 = 전 노드 기동 실패).
--   · 기존 DB(로컬·dev) : V9 까지 적용된 상태에서 고아 정리 후 FK 가 붙는다.
--   · 온프렘            : Flyway 를 쓰지 않고 deploy/onprem/db/schema.sql 을 로드하므로, 그 정본에도
--                          같은 FK 가 반영돼 있어야 한다(별도 반영).
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 1·2」)
--   to_regclass 는 search_path 로, 제약 조회는 conrelid = to_regclass(...) 로 스코프한다.
--   'public' 리터럴은 쓰지 않는다 — klid_at 운영에서 전부 오판정이 된다(실사고 이력 V62·V63·V71).
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   ADD CONSTRAINT ... FOREIGN KEY 는 양쪽 테이블에 SHARE ROW EXCLUSIVE 잠금을 잡고 기존 행을
--   1회 검증한다. 두 테이블 모두 소형(이슈 스레드 원장)이라 순간이다.
--
--   ★ 이 변경은 <읽기·쓰기 계약을 바꾸지 않는다> — 컬럼·타입·이름이 그대로라 V8·V9 같은 전진 창
--     (구 jar 가 SQL 을 못 만드는 구간)이 <없다>. 댓글 등록·조회는 구/신 jar 모두 정상이다.
--
--   ⚠ 단 하나 달라지는 것: <구 jar 로 파생영상 폐기 스윕이 돌면 실패할 수 있다>. 구 jar 의
--     DELETE_ORDER 에는 LS_ISSUE_COMMENT 선삭제가 없어, 댓글이 달린 이슈를 가진 파생영상을 지울 때
--     RAW 삭제가 FK 위반으로 막힌다. 결과는 <트랜잭션 롤백 + 다음 tick 재시도>이며 부분 삭제·데이터
--     손실이 아니다(그 스윕은 원래 클레임·롤백 구조다). 신 jar 배포로 자동 해소된다.
--     ⇒ 배포 순서 권장: <DDL 과 앱 배포를 같은 창에서> 처리한다(스키마만 먼저 올려 오래 두지 않는다).
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   FK 를 남겨도 조회·등록은 정상이나 위 폐기 스윕 실패가 계속되므로, 구 jar 로 오래 머무를 때만
--   아래를 실행한 뒤 DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '10'; 한다.
--   (삭제된 고아 행은 되돌아오지 않는다 — 부모가 없어 되살릴 대상 자체가 없다.)
--
--     ALTER TABLE ls_issue_comment DROP CONSTRAINT fk_ls_issue_comment_issue;
--
-- ----------------------------------------------------------------------------
-- * 이번에 손대지 않는 것 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   · 기존 인덱스 ix_ls_issue_comment_issue — FK 의 검색 성능 근거이므로 <그대로 둔다>.
--     PostgreSQL 은 참조하는 쪽에 인덱스를 자동 생성하지 않으므로 이 인덱스가 곧 그 역할이다.
--   · LS_ISSUE_COMMENT.AUTHOR_NO — 사용자 원장으로의 FK 는 이 라운드의 축이 아니다(별건).
--   · 다른 테이블의 FK 누락 — 이 라운드는 ERD-023 이 명시한 이 한 건만 닫는다.
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 klid_at 이며, 카탈로그 조회는
--     to_regclass 로 스코프하고 식별자는 비한정으로 둔다 — 그래야 조회 대상과 조작 대상이 반드시
--     같은 스키마가 된다.
-- ============================================================================

DO $$
DECLARE
    child   CONSTANT text := 'ls_issue_comment';
    parent  CONSTANT text := 'ls_data_issue';
    fk_name CONSTANT text := 'fk_ls_issue_comment_issue';
    orphans integer;
BEGIN
    IF to_regclass(child) IS NULL OR to_regclass(parent) IS NULL THEN
        RAISE NOTICE '% 또는 % 가 없다 → no-op', child, parent;
        RETURN;
    END IF;

    -- 이미 걸려 있음 → 두 번째 실행은 아무것도 하지 않는다(고아 정리도 불필요 — FK 가 막고 있다).
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conrelid = to_regclass(child) AND conname = fk_name AND contype = 'f') THEN
        RAISE NOTICE '% 가 이미 존재한다 → no-op', fk_name;
        RETURN;
    END IF;

    -- (1) 고아 정리 — 부모 이슈가 이미 사라진 댓글. 이것을 남긴 채 FK 를 걸면 ALTER 가 검증에서
    --     실패해 마이그레이션 전체가 멈춘다. 본문은 로그에 싣지 않는다(CWE-359).
    DELETE FROM ls_issue_comment c
     WHERE NOT EXISTS (SELECT 1 FROM ls_data_issue i WHERE i.data_issue_sn = c.data_issue_sn);
    GET DIAGNOSTICS orphans = ROW_COUNT;
    IF orphans > 0 THEN
        RAISE NOTICE '고아 댓글 정리(부모 이슈 부재): % 건 — 되살릴 부모가 없어 복원 대상이 아니다',
            orphans;
    END IF;

    -- (2) FK 부착 — 설계(ERD-023)가 규정한 ON DELETE RESTRICT.
    EXECUTE format(
        'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (data_issue_sn) '
        'REFERENCES %I(data_issue_sn) ON DELETE RESTRICT',
        child, fk_name, parent);
    RAISE NOTICE '참조 무결성 반영: %.data_issue_sn -> %.data_issue_sn (RESTRICT)', child, parent;
END $$;
