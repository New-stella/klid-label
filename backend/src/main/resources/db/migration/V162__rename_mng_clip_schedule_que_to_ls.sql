-- =============================================================================
-- V162: MNG_CLIP_SCHEDULE_QUE → LS_CLIP_SCHEDULE_QUE 개명 + LS_DATA_RAW 참조 FK 보강.
--
-- 배경(실측 결함): 이 테이블은 이름만 MNG_ 접두라 <관제서버 소유(공유·읽기전용)>로 보이지만, 실제로는
--   저작도구가 V2__phase3_video_queue_quartz.sql 에서 직접 CREATE 한 <자체 소유 배치 큐>다
--   (라벨링 배치 파이프라인 입구 — 영상 수신 직후 1건 INSERT). Flyway 가 @Primary 단일 데이터소스
--   (klid_at 스키마 전체)를 관리하며 이 테이블도 그 관리 대상이다 — 외부 미러링이 아니다.
--
-- 그 오인이 실제 결함을 냈다: V146__add_ls_data_raw_child_fk.sql(LS_DATA_RAW 자식 FK 전수 보강, DB-ISSUE-01)이
--   이름만 보고 "관제서버 소유(MNG_*) 스키마다. MNG_* 변경은 관제서버팀 선승인 필수" 로 판단해 이 테이블을
--   FK 대상에서 <제외>했다(V146 주석 17~19행). 결과적으로 RAW_SN 은 지금 참조무결성 보호가 없어, 영상 원본
--   행이 삭제돼도 큐 행이 고아로 잔존한다 — V146 이 다른 자식 27개에서 구조적으로 막은 바로 그 문제다.
--
-- 개명 근거(표준용어): "클립"(CLIP)은 사업표준단어 #204 에 등재되어 있고 이미 VMS_CLIP_ID 에서 쓰인다.
--   신규 컬럼·테이블 생성이 아니라 소유 접두(LS_)만 바로잡는 개명이므로 표준도메인 신규 판정 대상은 없다.
--
-- 삭제 정책: ON DELETE CASCADE — V146 의 배치/파이프라인 자식(ls_bat_rty_wtng·ls_batch_proc_log·
--   ls_marking 등)과 동일하다. 영상 원본이 사라지면 그 영상을 처리하려던 큐 작업도 의미가 없다.
--
-- 관제 영향 없음: 관제서버는 이 테이블을 읽지 않는다(데이터마트 뷰 V_COMPLETED_* 비공급). 따라서
--   "검수 완료·통지 건에 대한 관제 접근 보장" 구속 제약과 무관하다.
--
-- 잠금 주의(2노드 Active-Active): RENAME 은 ACCESS EXCLUSIVE, ADD CONSTRAINT 는 SHARE ROW EXCLUSIVE +
--   전량 검증 스캔이라 배포 중 짧은 차단이 발생할 수 있다. 큐 테이블은 소규모라 영향이 짧다.
--
-- ★ 롤백 절차 (Critical — 앱을 이 마이그레이션 적용 <이전> 버전으로 내릴 때 필수)
--   구버전 코드의 엔티티는 @Table(name = "MNG_CLIP_SCHEDULE_QUE") 이고 앱은 ddl-auto=validate 로
--   기동한다(application.yml). 따라서 스키마를 되돌리지 않고 구버전 jar 로 롤백하면 기동 시점 검증에서
--   "테이블 없음"으로 <2노드 모두 기동 실패>한다. Flyway 는 down-migration 을 수행하지 않으므로
--   아래 rename-back SQL 을 <구버전 재설치 전에> DBA 가 수동 적용해야 한다.
--   (운영 절차 문서: deploy/onprem/docs/07-uninstall-rollback.md § 롤백)
--
--     -- 1) FK 제거: 구버전에는 이 제약이 없었고, 부모 삭제 시 CASCADE 동작이 달라진다.
--     ALTER TABLE LS_CLIP_SCHEDULE_QUE DROP CONSTRAINT IF EXISTS FK_LS_CLIP_SCHEDULE_QUE_RAW;
--     -- 2) 부속 객체 역개명(PK 제약 · IDENTITY 시퀀스 · 인덱스)
--     ALTER TABLE LS_CLIP_SCHEDULE_QUE
--         RENAME CONSTRAINT ls_clip_schedule_que_pkey TO mng_clip_schedule_que_pkey;
--     ALTER SEQUENCE ls_clip_schedule_que_que_sn_seq RENAME TO mng_clip_schedule_que_que_sn_seq;
--     ALTER INDEX IF EXISTS IX_LS_CLIP_SCHEDULE_QUE_STATUS RENAME TO IX_MNG_CLIP_SCHEDULE_QUE_STATUS;
--     ALTER INDEX IF EXISTS IX_LS_CLIP_SCHEDULE_QUE_RAW    RENAME TO IX_MNG_CLIP_SCHEDULE_QUE_RAW;
--     -- 3) 테이블 역개명 (데이터 유실 없음)
--     ALTER TABLE LS_CLIP_SCHEDULE_QUE RENAME TO MNG_CLIP_SCHEDULE_QUE;
--     -- 4) Flyway 이력에서 이 버전 제거 — 남겨두면 구버전 앱이 "적용됐는데 파일이 없다"로 기동 실패한다.
--     DELETE FROM flyway_schema_history WHERE version = '162';
--
--   ※ 롤백 후 다시 상위 버전으로 올릴 때는 이 마이그레이션이 그대로 재적용된다(1~4단계 모두 멱등).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 테이블 개명 (데이터 유실 없음 — RENAME 은 행을 건드리지 않는다).
--    이미 개명된 DB 에서는 no-op (멱등).
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    IF to_regclass('mng_clip_schedule_que') IS NOT NULL
       AND to_regclass('ls_clip_schedule_que') IS NULL THEN
        ALTER TABLE MNG_CLIP_SCHEDULE_QUE RENAME TO LS_CLIP_SCHEDULE_QUE;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 2) 부속 객체 개명 — PostgreSQL 은 테이블을 RENAME 해도 인덱스/제약/시퀀스 이름을 따라 바꾸지 않는다.
--    남겨두면 \d 출력에 MNG_ 이름이 그대로 보여 "관제 소유" 오인이 재발한다.
-- -----------------------------------------------------------------------------
ALTER INDEX IF EXISTS IX_MNG_CLIP_SCHEDULE_QUE_STATUS RENAME TO IX_LS_CLIP_SCHEDULE_QUE_STATUS;
ALTER INDEX IF EXISTS IX_MNG_CLIP_SCHEDULE_QUE_RAW    RENAME TO IX_LS_CLIP_SCHEDULE_QUE_RAW;

DO $$
BEGIN
    -- conrelid 로 <이 테이블에 붙은> 제약만 본다 — pg_constraint.conname 은 DB 전역 유일이 아니라
    -- 다른 스키마의 동명 제약을 오탐할 수 있다(현재 배포는 단일 스키마라 실害 없으나 방어적으로 한정).
    -- 하드코딩 스키마명 대신 conrelid 를 쓰는 이유: 이 파일의 다른 검사(to_regclass)와 동일하게
    -- search_path 를 따라가야 klid_at 등 비-public 스키마 운영에서도 그대로 동작한다.
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conname = 'mng_clip_schedule_que_pkey'
                  AND conrelid = to_regclass('ls_clip_schedule_que')) THEN
        ALTER TABLE LS_CLIP_SCHEDULE_QUE
            RENAME CONSTRAINT mng_clip_schedule_que_pkey TO ls_clip_schedule_que_pkey;
    END IF;
    -- IDENTITY 시퀀스는 OID 로 연결되므로 이름을 바꿔도 컬럼 기본값이 끊기지 않는다.
    IF to_regclass('mng_clip_schedule_que_que_sn_seq') IS NOT NULL THEN
        ALTER SEQUENCE mng_clip_schedule_que_que_sn_seq RENAME TO ls_clip_schedule_que_que_sn_seq;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 3) FK 추가 전 고아 사전검증 — 고아가 있으면 <데이터를 훼손하지 않고> 마이그레이션을 중단한다.
--    V146 은 소량 고아를 자동 삭제했으나, 이 테이블은 처리 대기/진행 중 작업이 담긴 <작업 큐>라
--    조용한 삭제가 배치 유실로 이어진다. 운영자가 원인을 확인한 뒤 정리하도록 실패시킨다.
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count
      FROM LS_CLIP_SCHEDULE_QUE q
     WHERE NOT EXISTS (SELECT 1 FROM LS_DATA_RAW r WHERE r.RAW_SN = q.RAW_SN);

    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V162 중단: LS_CLIP_SCHEDULE_QUE 에 LS_DATA_RAW 를 참조하지 못하는 고아 행이 %건 '
            '있습니다. 배치 큐는 처리 대기/진행 중 작업이 담기므로 자동 삭제하지 않습니다 — 원인(영상 삭제/이관)을 '
            '확인하고 해당 행을 정리한 뒤 다시 기동하세요.', orphan_count;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- 4) FK 생성 (멱등 — 이미 있으면 건너뛴다). 제약명은 V146 의 FK_{테이블}_RAW 규칙을 따른다.
-- -----------------------------------------------------------------------------
DO $$
BEGIN
    -- 위 PK 검사와 동일하게 conrelid 로 대상 테이블에 한정한다(동명 제약 오탐 → FK 미생성 방지).
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                    WHERE conname = 'fk_ls_clip_schedule_que_raw'
                      AND conrelid = to_regclass('ls_clip_schedule_que')) THEN
        ALTER TABLE LS_CLIP_SCHEDULE_QUE
            ADD CONSTRAINT FK_LS_CLIP_SCHEDULE_QUE_RAW FOREIGN KEY (RAW_SN)
            REFERENCES LS_DATA_RAW (RAW_SN) ON DELETE CASCADE;
    END IF;
END $$;
