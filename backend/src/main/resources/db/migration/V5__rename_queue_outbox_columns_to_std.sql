-- ============================================================================
-- V5: 배치 큐 · 메타복제 발신함의 비표준 컬럼 11종을 표준용어 조합으로 개명한다  @req R2
--
--   · LS_CLIP_SCHEDULE_QUE (7) : JOB_TYPE · STATUS · RETRY_COUNT · REGISTERED_AT ·
--                                STARTED_AT · COMPLETED_AT · LAST_ERROR
--   · LS_META_REPL_OUTBOX  (4) : PAYLOAD · STATUS · RETRY_CNT · PROC_DT
--
-- 이 두 테이블만 <영문 서술형> 컬럼명이 남아 있었다. 같은 개념을 이미 표준 조합으로 쓰고 있는
-- 형제 테이블(LS_BAT_RTY_WTNG · LS_CONTROL_NOTIFY_FALLBACK)과 형태를 맞춘다 — 그쪽은
-- STTS_CD VARCHAR(16) · RTRY_NMTM INTEGER · LAST_ERR_MSG_CN VARCHAR(2000) 이다.
-- 같은 개념에 이름이 둘이면 조회·설계서·감리 어느 쪽에서도 같은 것을 두 번 찾게 된다.
--
-- ----------------------------------------------------------------------------
-- ★ 표준용어 근거 (규칙 3 — 우선순위 ①행안부 공통표준 → ②사업표준 → ③신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 했다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   행안부 공통표준단어 : 작업 JOB · 유형 TYPE · 상태 STTS · 코드 CD · 재시도 RTRY · 횟수 NMTM ·
--                        등록 REG · 시작 BGNG · 완료 CMPTN · 최종 LAST · 오류 ERR · 메시지 MSG ·
--                        내용 CN · 일시 DT · 처리 PRCS
--   사업표준용어        : 페이로드내용 PAYLOAD_CN (아래 ⚠⚠ 참조)
--
--   | 현재            | 개명              | 근거                                              |
--   |-----------------|-------------------|---------------------------------------------------|
--   | JOB_TYPE(32)    | JOB_TYPE_CD(20)   | 미등록 조합 → 사업표준도메인 <코드V20>            |
--   | STATUS(16)      | STTS_CD(16)       | 공통표준용어 상태코드 = V/16 (폭 그대로)          |
--   | RETRY_COUNT     | RTRY_NMTM         | 공통표준용어 재시도횟수                           |
--   | REGISTERED_AT   | REG_DT            | 공통 등록일시                                     |
--   | STARTED_AT      | BGNG_DT           | 공통 시작일시                                     |
--   | COMPLETED_AT    | CMPTN_DT          | 공통 완료일시                                     |
--   | LAST_ERROR(2000)| LAST_ERR_MSG_CN   | 공통표준용어 마지막오류메시지내용 = V/2000        |
--   | PAYLOAD         | PAYLOAD_CN        | 사업표준용어 페이로드내용. 타입 text 유지(폭 무관) |
--   | STATUS(20)      | STTS_CD(16)       | 상태코드 표준 폭이 16 이라 <폭도 함께> 맞춘다     |
--   | RETRY_CNT       | RTRY_NMTM         | 공통표준용어 재시도횟수                           |
--   | PROC_DT         | PRCS_DT           | 처리는 PRCS 다. PROC 는 <프로세스> 라 뜻이 달라진다 |
--
--   ⚠⚠ PAYLOAD_CN 채택 근거 (검토 후 확정 — PYLD_CN 안은 폐기)
--     ① 행안부 공통표준에는 페이로드/PAYLOAD 가 <단어·용어 양쪽 다 0건>이다. 즉 이 개념에는
--        「행안부 1순위」 조항이 개입하지 않고 사업표준만 해당한다.
--     ② 사업표준<용어>에 「페이로드내용 = PAYLOAD_CN」이 등록돼 있고, 그 출처가
--        <KLID-저작도구 ERD-021> — 우리 프로젝트가 등록한 값이다. 남의 사전을 우회하는 상황이
--        아니라 우리가 정한 것을 우리가 어기는 상황이 된다.
--     ③ 이 스키마의 payload 계열 컬럼 4개가 <전부> PAYLOAD 형태다
--        (REQ_PAYLOAD_CN · RESP_PAYLOAD_CN · PAYLOAD_CN · LBL_PAYLOAD). PYLD 를 쓰는 컬럼은 0 이라
--        PYLD_CN 으로 가면 스키마 전체에서 유일한 예외가 된다.
--     ④ 결정적 — 직계 형제가 LS_CONTROL_NOTIFY_FALLBACK.PAYLOAD_CN 이다. 이 파일은 나머지
--        3종(STTS_CD·RTRY_NMTM·LAST_ERR_MSG_CN)의 형태 근거로 <바로 그 테이블>을 인용했다.
--        3종은 따르고 4번째만 벗어나면 같은 마이그레이션 안에서 자기모순이다.
--     ⑤ 우선순위 규칙의 해석 단위는 <등록된 용어>이지 낱말 재조합이 아니다(규칙이 든 예
--        DATST_NM 도 용어 단위 채택이다). 「단어 조합」은 <등록된 용어가 없을 때> 쓰는 규칙이다.
--
--     ⚠ 다만 <사전 자체의 갈림은 여전히 미결>이다 — 사업표준<단어>에는 페이로드가 PYLD 로만
--       등록돼 있어 PAYLOAD 는 단어 사전에 없다. 그 정합은 <별건>이며, 만약 사전이 PYLD 쪽으로
--       확정되면 이 컬럼 하나가 아니라 <PAYLOAD 계열 5개 컬럼>(위 4개 + 이 컬럼)을 한 라운드로
--       묶어 바꿔야 한다. 우리 컬럼명은 확정, 사전 정합은 별건 — 두 축을 섞지 말 것.
--
--     ⚠ 타입은 text 를 유지한다(폭 축소 대상 아님). 등록 용어의 도메인은 V/4000 이지만
--       형제 3개가 전부 text 이고, 이 값은 영상 메타 스냅샷 전문이라 4000 을 넘을 수 있다.
--
--   ⚠ 재시도는 RTRY(행안부)이지 RTY(사업)가 아니다. 이 저장소는 우선순위를 거꾸로 적용해
--     LS_DATA_INGEST 에서 이미 맞던 RTRY 를 RTY 로 바꿨다가 되돌린 이력이 있다(구 V172→V175).
--   ⚠ PROC_DT 를 그대로 두면 "프로세스일시" 라는 다른 뜻이 된다. 같은 저장소가
--     LS_DATA_INGEST.PROC_STTS_CD → PRCS_STTS_CD 로 이미 같은 교정을 했다.
--
-- ----------------------------------------------------------------------------
-- ★ 이번에 손대지 않는 것 (빠뜨린 것이 아니다 — 함께 바꾸면 깨진다)
-- ----------------------------------------------------------------------------
--   · LS_META_REPL_OUTBOX.OUTBOX_SN — OUTBOX 는 <테이블명>에서 온 이름이다. 테이블명을 두고
--     PK 만 바꾸면 이름이 서로 어긋난다. 테이블명 축과 함께 갈 항목이다.
--   · SNPSHT_HASH — SNPSHT 는 미등록(표준은 SNPSH)이지만 같은 이름이 LS_DATASET_VIDEO_META 와
--     <포털 DB 복제본>(db/portal, 별도 물리 DB·별도 Flyway location)에까지 걸쳐 있다.
--     한쪽만 바꾸면 메타 복제가 조용히 깨진다.
--   · 테이블명 자체 · 인덱스/제약/시퀀스 이름 — 컬럼 축이 아니다(별건).
--     인덱스 이름에 남는 status 토큰(IX_LS_CLIP_SCHEDULE_QUE_STATUS ·
--     IDX_LS_META_REPL_OUTBOX_STATUS)은 그래서 그대로다. 인덱스 <정의>는 RENAME 을 자동 추종한다.
--
-- ----------------------------------------------------------------------------
-- ★ 왜 RENAME 인가 — 재생성 금지
-- ----------------------------------------------------------------------------
--   LS_META_REPL_OUTBOX 는 dev 에 미완 복제 이벤트가 실재한다. 그 행이 사라지면 포털 메타가
--   <영구 stale> 이 된다(승인 트랜잭션이 이미 커밋돼 재발행 트리거가 없다). DROP+ADD 나 테이블
--   재생성은 쓰지 않는다. RENAME COLUMN 은 PK·FK·인덱스·기본값·IDENTITY 를 모두 따라오게 한다.
--
-- ----------------------------------------------------------------------------
-- ★ 폭 축소 2건은 fail-closed 로 먼저 센다 (조용한 절단 금지)
-- ----------------------------------------------------------------------------
--   JOB_TYPE 32→20 · 아웃박스 STATUS 20→16. ALTER TYPE 직전에 실제 최장값을 세어 목표 폭을
--   넘는 행이 하나라도 있으면 RAISE EXCEPTION 으로 중단한다. PostgreSQL 은 varchar 축소 시
--   초과분을 자르지 않고 오류를 내지만, 그 오류만으로는 <무엇이 몇 건> 걸렸는지 알 수 없다.
--   DO 블록은 원자적이라 중단 시 앞서 수행한 개명도 함께 롤백된다(부분 적용 없음).
--   ⚠ 예외 메시지에는 건수와 최장 길이만 싣는다 — 값 자체를 실으면 로그로 새어 나간다(CWE-209/117).
--
-- ----------------------------------------------------------------------------
-- ★ 두 경로가 수동 개입 없이 같은 결과로 수렴한다
-- ----------------------------------------------------------------------------
--   · 신규 설치(빈 DB)  : V1 이 <옛 이름으로> 두 테이블을 만들고 여기서 개명된다.
--     V1__baseline.sql 은 이미 적용된 이력이라 고치지 않는다(체크섬 불일치 = 전 노드 기동 실패).
--     그래서 신규 설치도 기존 DB 와 <같은 순서>를 탄다.
--   · 기존 DB(로컬·dev) : V4 까지 적용된 상태에서 여기서 개명된다.
--   두 번 돌아도 안전하다 — 옛 이름이 없으면 개명을 건너뛰고, 폭이 이미 목표값이면 ALTER 도 건너뛴다.
--   테이블 자체가 없으면(to_regclass NULL) 완전 no-op 이다.
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 2」)
--   to_regclass 는 search_path 로, information_schema 조회는 current_schema() 로 스코프한다.
--   'public' 리터럴은 쓰지 않는다 — klid_at 운영에서 전부 오판정이 된다(실사고 이력 V62·V63·V71).
--   동적 SQL 의 식별자는 전부 format('%I') 로 인용한다.
--
-- ----------------------------------------------------------------------------
-- ★ 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   잠금 자체는 짧다 — RENAME COLUMN 은 카탈로그만 고치므로 순간이고, ALTER TYPE(varchar 축소)은
--   테이블 재작성이라 ACCESS EXCLUSIVE 를 잡지만 두 테이블 모두 소형이다(배치 큐는 처리 후 소진,
--   아웃박스는 승인 영상 1:1).
--
--   ⚠⚠ 문제는 잠금 시간이 아니라 <전진 창>이다 — 이 개명은 <하위호환이 아니다>.
--     노드 A 가 V5 를 적용한 순간부터, 아직 구 jar 인 노드 B 는 옛 컬럼명으로 SQL 을 만들므로
--     아래 4경로가 전부 실패한다(오류 `column "payload"/"status" does not exist`):
--       · 검수 승인 — ReviewService.approve → DatasetVideoMetaSnapshotService.materialize 의
--         outbox INSERT · supersedePending. <같은 트랜잭션이라 승인 전체가 500>(사용자 대면)
--       · 영상 인입 — LabelingBatchQueueService.enqueue. 인입 트랜잭션 안이라 <인입째 롤백>
--       · 배치 큐 폴링 — BatchQuartzJob. 매 tick 실패
--       · 포털 메타 복제 — MetaReplicationWorker. 매 tick 실패
--     이 창은 롤백 방향(아래 「롤백 절차」)과 <같은 원인>이며 방향만 반대다.
--
--   ★ 데이터는 잃지 않는다 — 실패가 전부 트랜잭션 롤백이라 부분 기록이 남지 않고, 이미 발행된
--     outbox 행은 PENDING 으로 남아 신 jar 노드가 이어 처리한다. 두 노드 재기동이 끝나면
--     자기치유된다. 잃는 것은 그 창 동안의 <가용성>이다.
--
--   ★ 배포 선택지는 둘이며 <운영 정책 결정>이다 — 배포 담당이 고른다.
--     (a) 양쪽 노드 정지 후 배포 : 창이 없다. 대가는 짧은 다운타임.
--         (런북 §2-5-2 ② 가 파괴적 절차에 쓰는 `# 2노드면 양쪽 모두` 표기와 같은 방식)
--     (b) 롤링 재기동           : 무중단이지만 창 동안 <검수 승인이 사용자 대면 500> 이 된다.
--     사고 영향이 사용자 대면이라 <(a) 를 권장>하되, 무중단 요구가 우선이면 (b) 도 성립한다
--     (자기치유되고 데이터 유실이 없으므로). 절차는 09-operations-runbook.md §4 참조.
--
--   ⚠ 창 자체를 없애려면 expand-contract(신컬럼 추가 → 이중쓰기 → 구컬럼 제거)가 있으나
--     릴리스 2회가 필요한 별개 설계다. 이 파일은 그 방식을 쓰지 않는다 — 무중단이 필수 요건이
--     되면 그 길이 있다는 것만 남긴다.
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   구버전 엔티티는 옛 컬럼명으로 매핑하므로 개명을 되돌리지 않으면 두 테이블의 읽기·쓰기가 전부
--   깨진다(배치 큐 폴링 · 포털 메타 복제). 아래를 그대로 실행한 뒤
--   DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '5'; 한다.
--
--     ALTER TABLE ls_clip_schedule_que ALTER COLUMN job_type_cd TYPE character varying(32);
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN job_type_cd     TO job_type;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN stts_cd         TO status;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN rtry_nmtm       TO retry_count;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN reg_dt          TO registered_at;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN bgng_dt         TO started_at;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN cmptn_dt        TO completed_at;
--     ALTER TABLE ls_clip_schedule_que RENAME COLUMN last_err_msg_cn TO last_error;
--     ALTER TABLE ls_meta_repl_outbox  ALTER COLUMN stts_cd TYPE character varying(20);
--     ALTER TABLE ls_meta_repl_outbox  RENAME COLUMN payload_cn TO payload;
--     ALTER TABLE ls_meta_repl_outbox  RENAME COLUMN stts_cd   TO status;
--     ALTER TABLE ls_meta_repl_outbox  RENAME COLUMN rtry_nmtm TO retry_cnt;
--     ALTER TABLE ls_meta_repl_outbox  RENAME COLUMN prcs_dt   TO proc_dt;
--
--   ⚠ 폭 확대(20→32 · 16→20)를 <개명보다 먼저> 두었다. 순서를 바꿔도 결과는 같지만, 확대는
--     데이터 손실이 없으므로 먼저 해 두는 편이 중간 실패에 안전하다.
-- ============================================================================

DO $$
DECLARE
    spec     text[];
    tbl      text;
    old_col  text;
    new_col  text;
    cur_len  integer;
    tgt_len  integer;
    over_cnt bigint;
    max_len  integer;
BEGIN
    -- ------------------------------------------------------------------
    -- ① 개명 11건 — 옛 이름이 남아 있을 때만 바꾼다(두 번째 실행은 no-op).
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_clip_schedule_que', 'job_type',      'job_type_cd'],
        ['ls_clip_schedule_que', 'status',        'stts_cd'],
        ['ls_clip_schedule_que', 'retry_count',   'rtry_nmtm'],
        ['ls_clip_schedule_que', 'registered_at', 'reg_dt'],
        ['ls_clip_schedule_que', 'started_at',    'bgng_dt'],
        ['ls_clip_schedule_que', 'completed_at',  'cmptn_dt'],
        ['ls_clip_schedule_que', 'last_error',    'last_err_msg_cn'],
        ['ls_meta_repl_outbox',  'payload',       'payload_cn'],
        ['ls_meta_repl_outbox',  'status',        'stts_cd'],
        ['ls_meta_repl_outbox',  'retry_cnt',     'rtry_nmtm'],
        ['ls_meta_repl_outbox',  'proc_dt',       'prcs_dt']
    ]
    LOOP
        tbl     := spec[1];
        old_col := spec[2];
        new_col := spec[3];

        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;   -- 테이블 자체가 없다 → no-op
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = tbl AND column_name = old_col) THEN
            CONTINUE;   -- 이미 개명됨(재실행) → no-op
        END IF;

        -- 옛 이름과 새 이름이 <동시에> 있으면 우리가 모르는 손질이 들어간 DB 다. 조용히
        -- 덮어쓰면 어느 쪽이 진짜 데이터인지 알 수 없게 되므로 사람이 판단하게 한다.
        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = tbl AND column_name = new_col) THEN
            RAISE EXCEPTION
                '%.% 와 %.% 가 동시에 존재한다 — 개명 대상과 결과가 함께 있는 상태라 중단한다. '
                '어느 쪽이 실제 데이터인지 확인하고 정리한 뒤 재기동하라.',
                tbl, old_col, tbl, new_col;
        END IF;

        EXECUTE format('ALTER TABLE %I RENAME COLUMN %I TO %I', tbl, old_col, new_col);
        RAISE NOTICE '표준용어 개명: %.% → %', tbl, old_col, new_col;
    END LOOP;

    -- ------------------------------------------------------------------
    -- ② 폭 축소 2건 — 목표 폭을 넘는 값이 하나라도 있으면 중단(fail-closed).
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_clip_schedule_que', 'job_type_cd', '20'],
        ['ls_meta_repl_outbox',  'stts_cd',     '16']
    ]
    LOOP
        tbl     := spec[1];
        new_col := spec[2];
        tgt_len := spec[3]::int;

        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;
        END IF;

        SELECT character_maximum_length INTO cur_len
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND table_name = tbl AND column_name = new_col;

        IF cur_len IS NULL OR cur_len <= tgt_len THEN
            CONTINUE;   -- 컬럼 부재 또는 이미 목표 폭 → no-op(테이블 재작성을 일으키지 않는다)
        END IF;

        EXECUTE format('SELECT count(*), coalesce(max(length(%I)), 0) FROM %I WHERE length(%I) > %s',
                       new_col, tbl, new_col, tgt_len)
           INTO over_cnt, max_len;

        IF over_cnt > 0 THEN
            RAISE EXCEPTION
                '%.% 를 %자로 줄일 수 없다 — 초과 행 %건(최장 %자). 조용히 자르면 값이 훼손되므로 '
                '중단한다. 해당 행을 확인해 정리한 뒤 재기동하라.',
                tbl, new_col, tgt_len, over_cnt, max_len;
        END IF;

        EXECUTE format('ALTER TABLE %I ALTER COLUMN %I TYPE character varying(%s)',
                       tbl, new_col, tgt_len);
        RAISE NOTICE '표준도메인 폭 정합: %.% → varchar(%)', tbl, new_col, tgt_len;
    END LOOP;

    -- ------------------------------------------------------------------
    -- ③ 컬럼 주석 — 표준용어 근거를 DB 에도 남긴다(멱등, 컬럼이 있을 때만).
    -- ------------------------------------------------------------------
    FOREACH spec SLICE 1 IN ARRAY ARRAY[
        ['ls_clip_schedule_que', 'job_type_cd',
         '작업유형코드. 라벨링 배치 파이프라인 입구 작업 종류(LABELING_BATCH). 표준도메인 코드V20.'],
        ['ls_clip_schedule_que', 'stts_cd',
         '상태코드. PENDING → IN_PROGRESS → DONE/FAILED. 형제 큐(LS_BAT_RTY_WTNG 등)와 같은 형태다.'],
        ['ls_clip_schedule_que', 'rtry_nmtm',
         '재시도횟수. 재시도는 행안부 공통표준 RTRY 다(사업 RTY 가 아니다).'],
        ['ls_clip_schedule_que', 'reg_dt',       '등록일시. 큐 적재 시각이며 폴링 정렬 기준이다.'],
        ['ls_clip_schedule_que', 'bgng_dt',      '시작일시. 워커가 작업을 집어 IN_PROGRESS 로 전이한 시각.'],
        ['ls_clip_schedule_que', 'cmptn_dt',     '완료일시.'],
        ['ls_clip_schedule_que', 'last_err_msg_cn',
         '마지막오류메시지내용. 값은 정화 후 적재한다 — 원문을 그대로 넣지 않는다(CWE-117/209).'],
        ['ls_meta_repl_outbox',  'payload_cn',
         '페이로드내용. 포털로 보낼 동결 메타 스냅샷 JSON. 로그·예외 메시지에 본문을 싣지 않는다.'],
        ['ls_meta_repl_outbox',  'stts_cd',
         '상태코드. PENDING → DONE/DEAD, 더 최신 스냅샷이 발행되면 SUPERSEDED.'],
        ['ls_meta_repl_outbox',  'rtry_nmtm',    '재시도횟수. 상한 초과 시 DEAD(사장큐)로 격리한다.'],
        ['ls_meta_repl_outbox',  'prcs_dt',
         '처리일시. 처리는 PRCS 다 — PROC 는 프로세스라 뜻이 달라진다.']
    ]
    LOOP
        tbl     := spec[1];
        new_col := spec[2];

        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;
        END IF;

        IF EXISTS (SELECT 1 FROM information_schema.columns
                    WHERE table_schema = current_schema()
                      AND table_name = tbl AND column_name = new_col) THEN
            EXECUTE format('COMMENT ON COLUMN %I.%I IS %L', tbl, new_col, spec[3]);
        END IF;
    END LOOP;
END $$;
