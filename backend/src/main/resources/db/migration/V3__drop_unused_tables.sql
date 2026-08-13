-- ============================================================================
-- V3: 사용처가 0 인 테이블 3종 제거 — LS_DEADLINE · LS_META · LS_RAW_DATA_ENROLLMENT
--
-- ★ 왜 지우나 — 셋 다 <엔티티 클래스만 있고 아무도 쓰지 않는다>
--   구 V36 이 1차 스키마에서 이름만 바꿔 복제해 온 테이블들이다(LS_PJT_DDLN→LS_DEADLINE,
--   LS_PJT_META→LS_META, LS_PJT_DATA_MPNG→LS_RAW_DATA_ENROLLMENT). 그 뒤 저작도구 도메인이
--   재설계되면서 어느 것도 실제 기능에 연결되지 않았고, 다음이 전수 확인됐다(2026-08-13):
--     · 리포지토리 0건 — 세 엔티티를 참조하는 JpaRepository·QueryDSL 클래스가 없다.
--     · 코드 참조 0건 — backend/src(main+test) 전체에서 각 엔티티명은 <자기 클래스 선언 1줄>이 전부.
--     · 네이티브/JPQL 0건 — 테이블명 문자열이 쿼리·@Query 어디에도 없다.
--     · 화면·배치·배포 스크립트 0건 — frontend/src·deploy/*.sh 참조 없음.
--     · 다른 테이블이 이들을 <참조하지 않는다> — V1 에서 정의를 덜어낸 뒤 남은 참조가 0 이었다
--       (LS_RAW_DATA_ENROLLMENT 가 LS_DATA_RAW 를 가리키는 <자식 방향> FK 하나뿐이었다).
--     · dev 실측 행수 0 / 0 / 0.
--   즉 스키마 유지비만 내고 있었고, 신규 설치는 매번 <만들었다 지우는> 대상이 될 뻔했다.
--   V1__baseline.sql 에서 정의를 함께 덜어냈으므로 신규 설치는 애초에 만들지 않는다.
--
-- ★ 되살리려면 어디를 보나 — 원문이 그대로 보존돼 있다
--   · 생성 DDL 원문 : backend/src/test/resources/db-archive/migration/V36__create_new_ls_tables.sql
--   · 1차 데이터 이관: backend/src/test/resources/db-archive/migration/V37__migrate_data_to_new_tables.sql
--   · 컬럼 표준화   : 같은 경로 V60(META_VAL→META_VL) · V85(YN 컬럼 CHAR(1))
--   · 스쿼시 직전 형상: 이 커밋의 부모 리비전 V1__baseline.sql (git)
--   · 설계 명세     : docs/design/KLID_AT_데이터베이스설계서.md · docs/design-full/D9-데이터베이스설계서.md
--     (감리 산출물이라 이 커밋에서 손대지 않았다 — LogiCraft ITEM 선반영 후 별건으로 정합한다.)
--
-- ★ 두 경로가 수동 개입 없이 같은 결과로 수렴한다
--   · 신규 설치(빈 DB): V1 이 애초에 만들지 않으므로 to_regclass 가 NULL → <완전 no-op>.
--   · 기존 DB(로컬·dev): 스쿼시 이전에 만들어져 실재하므로 여기서 DROP 된다.
--   두 번 돌아도 안전하다(두 번째부터는 대상이 없어 no-op).
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 2」)
--   to_regclass 는 search_path 로 해석되므로 klid_at 운영에서도 그대로 동작하고, 다른 스키마의
--   동명 테이블을 보고 오판하지 않는다. 이름만으로 EXISTS 를 보고 DDL 을 건너뛰는 형태
--   (구 V79·V110·V146)는 쓰지 않는다. DROP TABLE 은 딸린 PK·FK·인덱스를 함께 정리하므로
--   제약을 개별 조회해 지우지 않는다(CASCADE 도 쓰지 않는다 — 아래 참조).
--
-- ★ 왜 <행이 있으면 실패>시키나 (fail-closed, 구 V126 과 같은 방식)
--   세 테이블은 전 환경에서 0 행이라는 근거로 제거하는 것이다. 그 전제가 깨진 DB 가 있다면
--   그건 우리가 모르는 사용처가 생겼다는 뜻이므로, 조용히 지워 <비가역 데이터 손실>을 내는 대신
--   기동을 멈추고 사람이 판단하게 한다. 삭제는 열기보다 위험하다 — 잘못 열면 유출이지만
--   잘못 지우면 되돌릴 수 없다.
--
-- ★ CASCADE 를 쓰지 않는 이유
--   지금은 이들을 참조하는 객체가 없다는 것이 확인됐다. 그런데도 CASCADE 를 붙이면, 나중에
--   누군가 뷰나 FK 를 붙였을 때 그것까지 <말없이 함께> 지운다. 의존 객체가 생겼다면 DROP 이
--   실패하는 편이 옳다(그 실패가 곧 "확인하라"는 신호다).
--
-- 잠금(2노드 Active-Active): DROP 은 ACCESS EXCLUSIVE 지만 0 행 테이블이라 순간이며,
--   어떤 코드도 이 테이블들을 읽지 않으므로 대기하는 트랜잭션이 없다.
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   구버전 코드에도 이 셋을 <읽는 경로가 없어> 테이블이 없어도 기능은 동작한다. 다만 구버전
--   엔티티가 남아 있어 ddl-auto=validate 가 실동작하는 환경이라면 기동이 막힐 수 있으므로,
--   그때는 위 db-archive V36 의 CREATE TABLE 3종을 그대로 실행해 다시 만든 뒤
--   DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '3'; 한다. (데이터는 0 행이라 복원 대상 없음)
-- ============================================================================

DO $$
DECLARE
    tbl  text;
    cnt  bigint;
BEGIN
    FOREACH tbl IN ARRAY ARRAY['ls_deadline', 'ls_meta', 'ls_raw_data_enrollment']
    LOOP
        -- 현재 스키마 기준으로만 실재를 판정한다(search_path 해석). 없으면 신규 설치이므로 no-op.
        IF to_regclass(tbl) IS NULL THEN
            CONTINUE;
        END IF;

        -- 전제(0 행)가 깨졌으면 지우지 말고 멈춘다.
        EXECUTE format('SELECT count(*) FROM %I', tbl) INTO cnt;
        IF cnt > 0 THEN
            RAISE EXCEPTION
                '% 에 % 행이 있다 — 사용처 0 을 전제로 한 제거이므로 중단한다. '
                '데이터를 확인하고 보존이 필요하면 백업 후 수동으로 비운 뒤 재기동하라.',
                tbl, cnt;
        END IF;

        EXECUTE format('DROP TABLE %I', tbl);
        RAISE NOTICE '사용처 0 테이블 제거: %', tbl;
    END LOOP;
END $$;
