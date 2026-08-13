-- ============================================================================
-- V4: 사용처가 0 인 테이블 4종 제거 (2회차)
--     LS_COM_CD · LS_DATA_META_HSTRY · LS_DATA_RAW_HSTRY · LS_TASK_ASSIGN_HISTORY
--
-- V3 가 <엔티티만 있고 아무도 안 쓰는> 3종을 걷어냈다면, 이번 4종은 성격이 조금씩 다르다.
-- 공통점은 하나 — <프로덕션 read 경로가 0> 이다. 아래에 테이블별 근거를 따로 적는다(2026-08-13 전수 확인).
--
-- ----------------------------------------------------------------------------
-- ① LS_COM_CD — 두 번째 진실원이라 지운다 (행이 5건 <있는데도> 지우는 유일한 대상)
-- ----------------------------------------------------------------------------
--   엔티티 클래스가 <아예 없고>, 조회 코드·API·화면 참조도 0 이다(코드에 남은 것은 주석 2줄뿐).
--   시드 5행(DATA_STTS_CD = PENDING·IN_REVIEW·APPROVED·REJECTED·BATCH_QUEUED)은 자바 상수
--   LsRawDataStatus.STTS_* 와 같은 값을 <다른 곳에> 한 벌 더 적어 둔 것이다. 아무도 읽지 않으므로
--   두 벌이 갈려도 조용하고, 갈린 뒤에 누군가 이 테이블을 "코드 마스터"로 신뢰하면 그때 사고가 난다.
--   상태 코드의 단일 진실원은 상태 머신을 강제하는 자바 상수 쪽이다.
--   ⚠ 그래서 이 테이블만은 <행을 지우는 것이 의도>다. 다른 셋의 "0 행" 전제와 성격이 다르므로
--     아래 검증도 "행이 있으면 중단" 이 아니라 <시드와 정확히 일치하는지>를 본다.
--
-- ----------------------------------------------------------------------------
-- ② LS_DATA_META_HSTRY · ③ LS_DATA_RAW_HSTRY — 리포지토리는 있는데 주입처가 0
-- ----------------------------------------------------------------------------
--   LsDataMetaHstryRepository / LsDataRawHstryRepository 를 <주입하거나 호출하는 클래스가 0건>이다.
--   엔티티의 정적 팩토리(record / recordIngest)도 호출부가 없다. 즉 행이 생길 경로 자체가 없고,
--   dev 실측 행수도 0 / 0 이다. 메타 변경 이력은 LS_DATA_META_REVIEW·LS_TASK_EVENT_LOG 가,
--   영상 상태 변경은 LS_BATCH_PROC_LOG·LS_TASK_EVENT_LOG 가 이미 담당한다.
--
-- ----------------------------------------------------------------------------
-- ④ LS_TASK_ASSIGN_HISTORY — 완전한 이중 쓰기 (여기가 이번 라운드의 핵심)
-- ----------------------------------------------------------------------------
--   재배정 1회가 두 테이블에 <같은 사실>을 각각 적고 있었다:
--     · LS_TASK_ASSIGN_HISTORY  (authrt_seq, raw_data_id, prev_user_no, new_user_no, chg_user_no)
--     · LS_TASK_EVENT_LOG       (raw_data_id, 'REASSIGN', actor_user_no, subject_user_no, prev_user_no)
--   그런데 배정 이력 조회 API(GET /v1/assignments/{id}/history)는 <이벤트 로그만> 읽는다. 앞의
--   테이블을 읽는 프로덕션 경로는 0 이고, read 는 테스트 3곳뿐이었다. 이벤트 로그는 배정·재배정·
--   검수 제출·승인·반려를 <한 축>으로 모으므로 화면이 필요로 하는 시간순 통합 이력을 그쪽이 낸다.
--   dev 실측 17행은 전부 대응하는 REASSIGN 이벤트 로그를 갖고 있다(아래 검증이 그것을 확인한다).
--
-- ============================================================================
-- ★ 두 경로가 수동 개입 없이 같은 결과로 수렴한다
-- ============================================================================
--   · 신규 설치(빈 DB) : V1 에서 정의(테이블 4 + 시퀀스 3 + PK 4 + 인덱스 3 + FK 2 + LS_COM_CD 시드
--     5행, 171줄)를 덜어냈으므로 <애초에 만들지 않는다>. 따라서 여기서는 to_regclass 가 전부 NULL 이라
--     <완전 no-op> 이다 — NOTICE 조차 찍히지 않는다(V3 와 동일한 형태).
--   · 기존 DB(로컬·dev) : 스쿼시 이전에 만들어져 실재하므로 여기서 DROP 된다.
--   두 번 돌아도 안전하다(두 번째부터는 to_regclass 가 NULL 이라 대상이 없다).
--
--   ⚠ V1 을 고친 것은 「절대 수정하지 않는다」의 <기록된 예외 2건>이다. 근거는 그 파일 헤더에 있고,
--     핵심은 "체크섬 검증을 받는 DB 가 아직 없다"는 것이다(기존 DB 이력은 BASELINE + checksum NULL
--     이라 Flyway 가 버전 1 이하를 건너뛴다 — dev 이력 직접 조회로 재확인). 반면 <V2 는 type=SQL +
--     실체크섬>이라 수정하면 전 노드 기동이 실패한다. 절대 손대지 말 것.
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 2」)
--   to_regclass 는 search_path 로 해석되므로 klid_at 운영에서 그대로 동작하고, 다른 스키마의 동명
--   테이블을 보고 오판하지 않는다. 이름만으로 EXISTS 를 보고 DDL 을 건너뛰는 형태(구 V79·V110·V146)는
--   쓰지 않는다. DROP TABLE 은 딸린 PK·FK·인덱스·IDENTITY 시퀀스를 함께 정리하므로 개별로 지우지 않는다.
--
-- ★ 왜 <전제가 깨지면 실패>시키나 (fail-closed, V3 와 같은 방식)
--   제거 근거는 전부 "행이 없다 / 증적이 이미 다른 테이블에 있다"는 전제다. 그 전제가 깨진 DB 가
--   있다면 그건 우리가 모르는 사용처가 생겼다는 뜻이므로, 조용히 지워 <비가역 데이터 손실>을 내는
--   대신 기동을 멈추고 사람이 판단하게 한다. 삭제는 열기보다 위험하다 — 잘못 열면 유출이지만
--   잘못 지우면 되돌릴 수 없다. DO 블록은 원자적이라 중단 시 부분 삭제도 남지 않는다.
--
-- ★ CASCADE 를 쓰지 않는 이유
--   지금은 이들을 참조하는 객체가 없다는 것이 확인됐다. 그런데도 CASCADE 를 붙이면 나중에 누군가
--   뷰나 FK 를 붙였을 때 그것까지 <말없이 함께> 지운다. 의존 객체가 생겼다면 DROP 이 실패하는 편이
--   옳다(그 실패가 곧 "확인하라"는 신호다).
--
-- 잠금(2노드 Active-Active): DROP 은 ACCESS EXCLUSIVE 지만 최대 17행짜리 테이블이고 어떤 코드도
--   읽지 않으므로 대기하는 트랜잭션이 없다.
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   구버전 코드는 LS_TASK_ASSIGN_HISTORY 에 <쓰기>를 하므로(AssignmentService.reassign) 테이블이
--   없으면 재배정이 실패한다. 나머지 셋은 구버전에도 읽고 쓰는 경로가 없어 없어도 동작한다.
--
--   ⚠⚠ 아래 파일명은 <아카이브의 독자 번호 체계>다 — 현행 V1~V4 와 <무관>하며 이름이 겹친다.
--     아카이브(backend/src/test/resources/db-archive/migration/)에는 구 180개가 V0~V185 번호로
--     들어 있어 <현행 배포본의 V1~V4 와 정면으로 겹친다>. 예컨대 아래 "V4__phase5_data_src_lbl_meta.sql"
--     은 지금 읽고 있는 이 파일(V4__drop_unused_tables_round2.sql)과 <전혀 다른 파일>이다.
--     반드시 <파일명 전체>로 찾을 것. 번호만 보고 열면 정반대 동작을 하는 파일을 연다.
--
--   되살리는 법 (경로는 전부 backend/src/test/resources/db-archive/migration/ 아래):
--     · CREATE TABLE + 인덱스 원문 — 각 테이블의 <최초 생성> 파일. 인덱스도 같은 파일에 있다.
--         - LS_COM_CD              → V3__cm_code_seed_data_stts.sql
--                                    (당시 이름 CM_CODE. 현행 V2 가 LS_COM_CD 로 개명했다)
--         - LS_DATA_META_HSTRY     → V4__phase5_data_src_lbl_meta.sql   (+ IX_..._META 동봉)
--         - LS_DATA_RAW_HSTRY      → V2__phase3_video_queue_quartz.sql  (+ IX_..._RAW 동봉)
--         - LS_TASK_ASSIGN_HISTORY → V36__create_new_ls_tables.sql      (+ IX_..._AUTHRT 동봉)
--     · 그 뒤에 붙은 컬럼 변경 1건 — 최초 생성 파일만 실행하면 <구 폭으로 되살아난다>:
--         - V107__align_code_columns_to_std_varchar20.sql
--           (LS_TASK_ASSIGN_HISTORY.TASK_TYPE_CD → VARCHAR(20). 나머지 셋은 변경분 없음)
--     · 시드 5행(LS_COM_CD) — 두 파일에 나뉘어 있다. 하나만 실행하면 4행만 돌아온다:
--         - V3__cm_code_seed_data_stts.sql  … PENDING·IN_REVIEW·APPROVED·REJECTED (4행)
--         - V50__seed_batch_queued_status.sql … BATCH_QUEUED (1행)
--     · 1차 데이터 이관 원문 : V37__migrate_data_to_new_tables.sql
--         (구 LS_PJT_* 에서 LS_TASK_ASSIGN_HISTORY 로 옮겨 담은 INSERT. 스키마가 아니라 <데이터>라
--          되살릴 필요는 대개 없지만, 그 시절 이관분을 추적할 때 여기를 본다)
--     · FK 재생성          : V146__add_ls_data_raw_child_fk.sql
--         (ls_data_raw_hstry.raw_sn · ls_task_assign_history.raw_data_id, 둘 다 ON DELETE CASCADE)
--     · 최종 형상 대조용    : 이 커밋의 <부모 리비전> V1__baseline.sql (git) — 위 조각들을 합친
--                            결과가 그 파일의 정의와 같아야 한다. 이 커밋에서 그 정의를 덜어냈다.
--     · 그 뒤 DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '4';
--   ⚠ 데이터 복원은 별개다 — LS_TASK_ASSIGN_HISTORY 17행은 DROP 으로 사라지므로, 되살리려면
--     LS_TASK_EVENT_LOG 의 REASSIGN 행에서 재구성하거나 사전 백업본에서 되돌려야 한다.
--     (아래 검증이 "그 재구성이 가능함" 을 DROP 전에 확인한다.)
-- ============================================================================

DO $$
DECLARE
    tbl  text;
    cnt  bigint;
BEGIN
    -- ------------------------------------------------------------------
    -- ②③ 0 행 전제 — 행이 1건이라도 있으면 지우지 않는다.
    -- ------------------------------------------------------------------
    FOREACH tbl IN ARRAY ARRAY['ls_data_meta_hstry', 'ls_data_raw_hstry']
    LOOP
        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;
        END IF;

        EXECUTE format('SELECT count(*) FROM %I', tbl) INTO cnt;
        IF cnt > 0 THEN
            RAISE EXCEPTION
                '% 에 % 행이 있다 — 행이 생길 코드 경로가 없다는 전제로 제거하는 것이므로 중단한다. '
                '어떤 경로가 이 행을 넣었는지 확인하고, 보존이 필요하면 백업 후 수동으로 비운 뒤 재기동하라.',
                tbl, cnt;
        END IF;

        EXECUTE format('DROP TABLE %I', tbl);
        RAISE NOTICE '사용처 0 테이블 제거: %', tbl;
    END LOOP;

    -- ------------------------------------------------------------------
    -- ① LS_COM_CD — 시드 5행은 <의도적으로> 함께 사라진다. 그 밖의 행이 있으면
    --    누군가 이 테이블을 실제 코드 마스터로 쓰기 시작한 것이므로 중단한다.
    -- ------------------------------------------------------------------
    IF to_regclass('ls_com_cd') IS NOT NULL THEN
        -- 정적 SQL 이어도 안전하다: PL/pgSQL 은 <실행 시점>에 계획을 세우므로, 이 분기에 들어오지
        -- 않는 두 번째 실행에서는 없어진 테이블을 참조해도 오류가 나지 않는다.
        SELECT count(*) INTO cnt
          FROM ls_com_cd
         WHERE group_code <> 'DATA_STTS_CD'
            OR code NOT IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED', 'BATCH_QUEUED');
        IF cnt > 0 THEN
            RAISE EXCEPTION
                'ls_com_cd 에 시드(DATA_STTS_CD 5행) 밖의 행이 % 건 있다 — 아무도 읽지 않는다는 전제로 '
                '제거하는 것이므로 중단한다. 그 행을 읽는 경로가 생겼는지 확인하라.', cnt;
        END IF;

        DROP TABLE ls_com_cd;
        RAISE NOTICE '사용처 0 테이블 제거: ls_com_cd (상태코드 시드 5행은 LsRawDataStatus 상수로 일원화)';
    END IF;

    -- ------------------------------------------------------------------
    -- ④ LS_TASK_ASSIGN_HISTORY — 같은 사실이 LS_TASK_EVENT_LOG 에 <이미> 있는지 행 단위로 확인하고
    --    지운다. 대응 REASSIGN 행이 하나라도 없으면 그 재배정 사실이 통째로 사라지므로 중단한다.
    --    대응 규칙: (raw_data_id, prev_user_no, new_user_no) → (raw_data_id, prev_user_no, subject_user_no)
    --    ※ authrt_seq·chg_dt 는 이벤트 로그에 대응 컬럼이 없어 대조 축에 넣지 않는다. 같은 영상에서
    --      같은 작업자 쌍으로 두 번 재배정한 경우는 1:1 이 아니라 다:1 로 매칭되지만, 판정 목적은
    --      "그 재배정 사실이 이벤트 로그에도 남아 있는가" 이므로 그것으로 충분하다.
    -- ------------------------------------------------------------------
    IF to_regclass('ls_task_assign_history') IS NOT NULL THEN
        IF to_regclass('ls_task_event_log') IS NULL THEN
            RAISE EXCEPTION
                'ls_task_event_log 가 없어 ls_task_assign_history 의 증적 이관을 확인할 수 없다 — 중단한다.';
        END IF;

        SELECT count(*) INTO cnt
          FROM ls_task_assign_history h
         WHERE NOT EXISTS (
                   SELECT 1 FROM ls_task_event_log e
                    WHERE e.evnt_type_cd    = 'REASSIGN'
                      AND e.raw_data_id     = h.raw_data_id
                      AND e.prev_user_no    = h.prev_user_no
                      AND e.subject_user_no = h.new_user_no);
        IF cnt > 0 THEN
            RAISE EXCEPTION
                'ls_task_assign_history 의 % 행이 대응하는 REASSIGN 이벤트 로그를 갖고 있지 않다 — '
                '지우면 그 재배정 사실이 어디에도 남지 않으므로 중단한다. '
                'LS_TASK_EVENT_LOG 로 증적을 옮긴 뒤 재기동하라.', cnt;
        END IF;

        DROP TABLE ls_task_assign_history;
        RAISE NOTICE '사용처 0 테이블 제거: ls_task_assign_history (증적은 LS_TASK_EVENT_LOG 가 승계)';
    END IF;
END $$;
