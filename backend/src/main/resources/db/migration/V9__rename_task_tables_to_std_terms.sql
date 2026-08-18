-- ============================================================================
-- V9: 작업 배정·이벤트 로그 테이블 2종의 물리명을 표준용어 조합으로 개명한다  @req R2
--
--   LS_TASK_ASSIGNMENT  ->  LS_TASK_ALTMNT      (배정 = ALTMNT)
--   LS_TASK_EVENT_LOG   ->  LS_TASK_EVNT_LOG    (이벤트 = EVNT)
--
-- 설계 진실원(LogiCraft ITEM)은 이미 새 물리명으로 확정됐고, 여기서는 그 사양을 DB 에 반영한다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (규칙 3 — 우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   | 토큰       | 행안부 공통표준단어 | 사업표준단어   | 채택   | 사유                          |
--   |------------|---------------------|----------------|--------|-------------------------------|
--   | ASSIGNMENT | 미등록              | 미등록         | ALTMNT | 배정 = ALTMNT (행안부, 1순위) |
--   | EVENT      | 미등록              | 미등록         | EVNT   | 이벤트 = EVNT (사업, 2순위)   |
--   | TASK       | 업무 = TASK         | 업무 = TASK    | 유지   | <이미 표준 등록 약어>다       |
--   | LOG        | 로그 = LOG          | 로그 = LOG     | 유지   | 이미 표준                     |
--
--   즉 두 테이블명에서 비표준인 토큰은 ASSIGNMENT · EVENT <둘뿐>이고, 나머지 토큰은 손댈 이유가 없다.
--   ASSIGNMENT/EVENT 는 영문 서술형 그대로라 <어느 사전에도 등록돼 있지 않다> — V5 가 정리한
--   STATUS/PAYLOAD/RETRY_COUNT 계열과 같은 부류이며, 감리 지적 대상이다.
--
--   ⚠ 배정을 ALOT(할당)로 쓰지 않는다 — 행안부에 할당 = ALOT 이 <따로> 등록돼 있어 뜻이 갈린다.
--     이 테이블이 담는 것은 REVIEWER 가 WORKER 에게 <몫을 나누어 정한> 배정이므로 ALTMNT 다.
--   ⚠ EVNT 는 행안부에도 있으나 그쪽 뜻은 <행사>다. 우리 뜻(이벤트)에 해당하는 등록은 사업표준이며,
--     행안부에 <우리 개념이 없어> 2순위가 적용된 것이다(있는 개념을 덮어쓰는 것이 아니다).
--
-- ----------------------------------------------------------------------------
-- * 컬럼은 하나도 개명하지 않는다 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   ASSIGNMENT_ID · ACTOR_USER_NO · SUBJECT_USER_NO · PREV_USER_NO 는 전부 <사업표준용어 등록분>이라
--   이미 정합이다. 우선순위 규칙의 해석 단위는 <등록된 용어>이지 낱말 재조합이 아니며(V5 헤더가
--   PAYLOAD_CN 에서 같은 판정을 했다), 「단어 조합」은 <등록된 용어가 없을 때> 쓰는 규칙이다.
--   테이블명만 용어 등록으로 보호되지 않아 단어 축 판정을 받는다.
--
-- ----------------------------------------------------------------------------
-- * 자바 식별자는 바꾸지 않는다 (인지·수용한 표기 드리프트)
-- ----------------------------------------------------------------------------
--   엔티티 클래스 LsTaskAssignment · LsTaskEventLog, 필드명, Q 클래스, API 경로, 응답 필드, FE 타입은
--   전부 그대로다. 바뀌는 것은 <물리명 문자열>뿐이다. 결과적으로 클래스명과 테이블명의 표기가 갈리는데,
--   클래스 다이어그램은 자바 축이라 별개이고 클래스 개명은 Q 클래스·임포트까지 번져 <범위가 다르다>.
--   임의로 확대하지 말 것.
--
-- ----------------------------------------------------------------------------
-- * ALTER TABLE ... RENAME TO 는 부속 객체 이름을 따라오게 하지 않는다 (이 파일의 핵심)
-- ----------------------------------------------------------------------------
--   테이블만 바꾸면 시퀀스·제약·인덱스는 <옛 이름 그대로 남는다>. 정의는 자동 추종하므로 동작은
--   멀쩡하지만, 카탈로그에는 옛 이름이 남아 다음 사람이 "왜 이 인덱스만 이름이 다르지" 로 되돌아온다.
--   그래서 아래 13개 객체를 <전부 명시적으로> 개명한다(테이블 2 · 시퀀스 2 · PK 2 · UNIQUE 1 ·
--   INDEX 4 · FK 2).
--
--   ★ 시퀀스 하나는 단순 접두 치환이 아니다 — ls_task_event_log_event_seq_seq 는 <존재하지 않는
--     event_seq 컬럼>을 이름에 달고 있다(과거 컬럼 개명이 시퀀스를 빠뜨린 잔재다). 실제 컬럼은
--     evnt_id 이므로 이번에 ls_task_evnt_log_evnt_id_seq 로 <바로잡는다>. 그 잔재의 존재가 곧
--     "테이블만 바꾸면 부속 객체가 뒤처진다"는 이 절의 증거다.
--
-- ----------------------------------------------------------------------------
-- * 왜 RENAME 인가 — 재생성·DROP+CREATE 금지
-- ----------------------------------------------------------------------------
--   LS_TASK_EVENT_LOG 는 배정·재배정·검수 승인·반려·개인정보 선언 변경의 <행 단위 감사 원장>이다.
--   그 행이 사라지면 ①재배정 증적이 어디에도 남지 않고(V4 가 LS_TASK_ASSIGN_HISTORY 를 지울 때
--   근거로 삼은 것이 바로 이 테이블이다) ②비식별 신고 차단 판정(hasEverApproved)의 fail-closed
--   보조축인 EVENT_APPROVE 이력이 사라져 <승인 이력이 있는 영상에 신고가 통과>한다.
--   LS_TASK_ASSIGNMENT 가 사라지면 작업 배정이 통째로 풀린다.
--   RENAME 은 카탈로그만 고치므로 값·PK·인덱스·FK·컬럼 순서·IDENTITY 채번 위치를 전부 보존한다.
--
-- ----------------------------------------------------------------------------
-- * fail-closed — 옛 이름과 새 이름이 동시에 있으면 중단한다
-- ----------------------------------------------------------------------------
--   두 이름이 동시에 존재하면 수기 조작이나 실패한 부분 적용의 흔적이며, 그대로 두면 애플리케이션은
--   새 이름만 보고 <옛 테이블에 남은 감사 행은 아무도 읽지 않는 채로> 남는다. RENAME 자체도 오류로
--   실패하지만 그 원문만으로는 무엇을 조치해야 하는지 알 수 없으므로, 사람이 바로 판단할 수 있는
--   사유로 중단시킨다.
--
--   ⚠ 사유에는 <테이블 이름>만 싣는다. 원장 값(사용자 번호·사유 문구는 PII 인접이다)은 싣지 않는다
--     (CWE-209/359).
--
-- ----------------------------------------------------------------------------
-- * 두 경로가 수동 개입 없이 같은 결과로 수렴한다
-- ----------------------------------------------------------------------------
--   · 신규 설치(빈 DB)  : V1 이 <옛 이름으로> 두 테이블을 만들고 여기서 개명된다.
--     V1__baseline.sql 은 이미 적용된 이력이라 고치지 않는다(체크섬 불일치 = 전 노드 기동 실패).
--     그래서 신규 설치도 기존 DB 와 <같은 순서>를 탄다(no-op 이 아니다).
--   · 기존 DB(로컬·dev) : V8 까지 적용된 상태에서 여기서 개명된다.
--   두 번 돌아도 안전하다 — 이미 새 이름이면 객체마다 no-op 이고, 테이블 자체가 없으면 완전 no-op 이다.
--
--   ⚠ V4 는 옛 이름(ls_task_event_log)으로 증적 이관을 확인한다. V4 가 <먼저> 돌고 V9 가 나중에 도는
--     순서라 실제 배포에서는 문제가 없다. 다만 V4 를 <수동으로 재실행>하면 그 판정이 성립하지 않는다
--     (그 재실행은 V4DropUnusedTablesIT 가 스크래치 픽스처로 재현한다).
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 1·2」)
--   pg_class 조회는 current_schema() 로, 제약 조회는 conrelid = to_regclass(...) 로 스코프한다.
--   'public' 리터럴은 쓰지 않는다 — klid_at 운영에서 전부 오판정이 된다(실사고 이력 V62·V63·V71).
--   동적 SQL 의 식별자는 전부 format('%I') 로 인용한다.
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   잠금은 순간이다 — RENAME 은 카탈로그만 고치므로 테이블 재작성이 없다.
--
--   ⚠ 문제는 잠금 시간이 아니라 <전진 창>이다. 이 개명은 <하위호환이 아니다>. 스키마에 V9 가 적용된
--     뒤에도 아직 구 jar 인 노드는 옛 테이블명으로 SQL 을 만들므로 아래 경로가 실패한다
--     (오류 relation "ls_task_assignment" does not exist).
--
--   ⚠ <영향 범위가 V5·V8 보다 넓다> — 두 테이블은 배정·검수·통계·영상 목록·감사에 걸쳐 쓰인다.
--     (1) 작업 배정·재배정 — 배정 INSERT/UPDATE 와 이벤트 적재가 같은 트랜잭션이라 <배정 전체가 500>
--     (2) 검수 제출·승인·반려 — 상태 전이와 함께 이벤트를 적재하므로 <검수 전체가 500>(사용자 대면)
--     (3) 작업 목록·검수 목록·영상 목록 — 배정 조인/EXISTS 가 깨져 <목록 조회 500>
--     (4) 통계 — 작업자별 집계가 배정을 조인하므로 실패
--     (5) 개인정보 선언 변경 감사 — 감사 INSERT 실패로 <해당 PUT 전체가 롤백>
--
--   ★ 데이터는 잃지 않는다 — 실패는 전부 트랜잭션 롤백이라 부분 기록이 남지 않는다. 다만 V5·V8 과
--     달리 <자기치유되는 미결 큐가 없다> — 실패한 배정·검수는 사용자가 다시 시도해야 한다.
--
--   ⚠ <창을 여는 것은 Flyway 가 아니다 — 2노드와 Flyway 는 이 배포 형상에서 상호배타다.>
--     온프렘 2노드 이중화는 SPRING_FLYWAY_ENABLED=false 라 <어느 노드도 V9 를 적용하지 않는다>
--     (스키마는 deploy/onprem/db/schema.sql 로드). 반대로 Flyway 가 켜진 구성은 단일 노드다.
--     2노드에서 창을 여는 것은 <DBA 의 수동 DDL> 이며, 그 적용 시점과 노드 재기동 순서를 맞추는 것이
--     배포 절차의 핵심이다. 수동 적용 시점에 따라 <두 노드가 동시에 구 jar 인 구간>이 생길 수 있다.
--
--   ★ 배포 선택지는 둘이며 <운영 정책 결정>이다 — 배포 담당이 고른다.
--     (a) 양쪽 노드 정지 후 DDL 적용 후 배포 : 창이 없다. 대가는 짧은 다운타임. <강력 권장>
--     (b) 롤링 재기동                        : 무중단이지만 창 동안 배정·검수가 사용자 대면 500 이다.
--     V5 보다 영향 경로가 넓고 회수 큐도 없으므로 (a) 를 <강력 권장>한다.
--     절차는 09-operations-runbook.md 의 「V9 배포 시 주의」 절 참조.
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   구버전 엔티티는 옛 테이블명으로 매핑하므로 개명을 되돌리지 않으면 위 5경로가 계속 깨진다.
--   아래를 <위에서 아래 순서로> 실행한 뒤 DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '9'; 한다.
--   (시퀀스는 원래 이름으로 되돌린다 — 구버전에서 다시 V9 를 적용해도 같은 결과가 되게.)
--
--     ALTER TABLE ls_task_altmnt   RENAME CONSTRAINT ls_task_altmnt_pkey TO ls_task_assignment_pkey;
--     ALTER TABLE ls_task_altmnt   RENAME CONSTRAINT uk_ls_task_altmnt   TO uk_ls_task_assignment;
--     ALTER TABLE ls_task_altmnt   RENAME CONSTRAINT fk_ls_task_altmnt_raw TO fk_ls_task_assignment_raw;
--     ALTER TABLE ls_task_evnt_log RENAME CONSTRAINT ls_task_evnt_log_pkey TO ls_task_event_log_pkey;
--     ALTER TABLE ls_task_evnt_log RENAME CONSTRAINT fk_ls_task_evnt_log_raw TO fk_ls_task_event_log_raw;
--     ALTER INDEX ix_ls_task_altmnt_raw       RENAME TO ix_ls_task_assignment_raw;
--     ALTER INDEX ix_ls_task_altmnt_user      RENAME TO ix_ls_task_assignment_user;
--     ALTER INDEX ix_ls_task_evnt_log_actor   RENAME TO ix_ls_task_event_log_actor;
--     ALTER INDEX ix_ls_task_evnt_log_raw     RENAME TO ix_ls_task_event_log_raw;
--     ALTER SEQUENCE ls_task_altmnt_assignment_id_seq RENAME TO ls_task_assignment_assignment_id_seq;
--     ALTER SEQUENCE ls_task_evnt_log_evnt_id_seq     RENAME TO ls_task_event_log_event_seq_seq;
--     ALTER TABLE ls_task_altmnt   RENAME TO ls_task_assignment;
--     ALTER TABLE ls_task_evnt_log RENAME TO ls_task_event_log;
--
-- ----------------------------------------------------------------------------
-- * 이번에 손대지 않는 것 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   · 두 테이블의 컬럼 — 전부 등록 용어라 이미 정합이다(위 절 참조).
--   · LS_TASK_ASSIGN_HISTORY — V4 가 <이미 DROP 한> 테이블이다. 되살리지 않는다.
--     (철자가 HSTRY 가 아니라 HISTORY 라 grep 이 어긋나기 쉽다.)
--   · 다른 테이블의 영문 서술형 잔재 — 이 라운드의 축이 아니다(별건).
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 klid_at 이며, 카탈로그 조회는
--     current_schema() / to_regclass 로 스코프하고 식별자는 비한정으로 둔다 — 그래야 조회 대상과
--     조작 대상이 반드시 같은 스키마가 된다.
-- ============================================================================


DO $$
DECLARE
    spec   text[];
    old_nm text;
    new_nm text;
    tbl    text;
    has_o  boolean;
    has_n  boolean;
BEGIN
    -- ------------------------------------------------------------------
    -- (1) fail-closed — 옛 이름과 새 이름 테이블이 동시에 있으면 중단한다.
    --     개명 <전에> 두 짝을 모두 검사한다. 하나를 먼저 바꾸고 두 번째에서 멈추면
    --     (DO 블록이 원자적이라 롤백되긴 해도) 사유가 어느 짝의 것인지 헷갈린다.
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_task_assignment', 'ls_task_altmnt'],
        ['ls_task_event_log',  'ls_task_evnt_log']
    ]
    LOOP
        old_nm := spec[1];
        new_nm := spec[2];

        SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname = old_nm),
               EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname = new_nm)
          INTO has_o, has_n;

        IF has_o AND has_n THEN
            RAISE EXCEPTION
                '% 와 % 가 동시에 존재한다 — 개명 대상과 결과가 함께 있어 어느 쪽이 정본인지 알 수 '
                '없으므로 중단한다. 옛 테이블(%)에 남은 행을 새 테이블(%)로 옮겨 확인한 뒤 옛 '
                '테이블을 제거하고 재기동하라.',
                old_nm, new_nm, old_nm, new_nm;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- (2) 테이블 개명 — 옛 이름이 남아 있을 때만 바꾼다(두 번째 실행은 no-op).
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_task_assignment', 'ls_task_altmnt'],
        ['ls_task_event_log',  'ls_task_evnt_log']
    ]
    LOOP
        old_nm := spec[1];
        new_nm := spec[2];

        IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname = old_nm) THEN
            EXECUTE format('ALTER TABLE %I RENAME TO %I', old_nm, new_nm);
            RAISE NOTICE '표준용어 개명(테이블): % -> %', old_nm, new_nm;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- (3) IDENTITY 시퀀스 개명 — RENAME TO 가 따라오게 하지 않는 대표 객체다.
    --     두 번째 짝은 접두 치환이 아니라 <존재하지 않는 컬럼명(event_seq) 잔재>를
    --     실제 컬럼명(evnt_id)으로 바로잡는 것이다.
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_task_assignment_assignment_id_seq', 'ls_task_altmnt_assignment_id_seq'],
        ['ls_task_event_log_event_seq_seq',      'ls_task_evnt_log_evnt_id_seq']
    ]
    LOOP
        old_nm := spec[1];
        new_nm := spec[2];

        SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'S' AND c.relname = old_nm),
               EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'S' AND c.relname = new_nm)
          INTO has_o, has_n;

        IF has_o AND NOT has_n THEN
            EXECUTE format('ALTER SEQUENCE %I RENAME TO %I', old_nm, new_nm);
            RAISE NOTICE '표준용어 개명(시퀀스): % -> %', old_nm, new_nm;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- (4) 제약 개명(PK · UNIQUE · FK) — 소속 테이블은 (2) 이후이므로 <새 이름>으로 지목한다.
    --     RENAME CONSTRAINT 는 PK/UNIQUE 를 뒷받침하는 인덱스 이름도 함께 바꾼다.
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_task_altmnt',   'ls_task_assignment_pkey',   'ls_task_altmnt_pkey'],
        ['ls_task_altmnt',   'uk_ls_task_assignment',     'uk_ls_task_altmnt'],
        ['ls_task_altmnt',   'fk_ls_task_assignment_raw', 'fk_ls_task_altmnt_raw'],
        ['ls_task_evnt_log', 'ls_task_event_log_pkey',    'ls_task_evnt_log_pkey'],
        ['ls_task_evnt_log', 'fk_ls_task_event_log_raw',  'fk_ls_task_evnt_log_raw']
    ]
    LOOP
        tbl    := spec[1];
        old_nm := spec[2];
        new_nm := spec[3];

        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'r' AND c.relname = tbl) THEN
            CONTINUE;
        END IF;

        SELECT EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conrelid = to_regclass(tbl) AND conname = old_nm),
               EXISTS (SELECT 1 FROM pg_constraint
                        WHERE conrelid = to_regclass(tbl) AND conname = new_nm)
          INTO has_o, has_n;

        IF has_o AND NOT has_n THEN
            EXECUTE format('ALTER TABLE %I RENAME CONSTRAINT %I TO %I', tbl, old_nm, new_nm);
            RAISE NOTICE '표준용어 개명(제약): %.% -> %', tbl, old_nm, new_nm;
        END IF;
    END LOOP;

    -- ------------------------------------------------------------------
    -- (5) 인덱스 개명 — 제약이 뒷받침하지 않는 순수 인덱스 4종.
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ix_ls_task_assignment_raw',  'ix_ls_task_altmnt_raw'],
        ['ix_ls_task_assignment_user', 'ix_ls_task_altmnt_user'],
        ['ix_ls_task_event_log_actor', 'ix_ls_task_evnt_log_actor'],
        ['ix_ls_task_event_log_raw',   'ix_ls_task_evnt_log_raw']
    ]
    LOOP
        old_nm := spec[1];
        new_nm := spec[2];

        SELECT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'i' AND c.relname = old_nm),
               EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = current_schema() AND c.relkind = 'i' AND c.relname = new_nm)
          INTO has_o, has_n;

        IF has_o AND NOT has_n THEN
            EXECUTE format('ALTER INDEX %I RENAME TO %I', old_nm, new_nm);
            RAISE NOTICE '표준용어 개명(인덱스): % -> %', old_nm, new_nm;
        END IF;
    END LOOP;
END $$;
