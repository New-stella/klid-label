-- ============================================================================
-- V2: CM_CODE → LS_COM_CD 개명 (소유 접두 LS_ 통일 + 표준용어 약어 교정).
--
-- 배경 ①소유 접두: 이 테이블은 구 V3__cm_code_seed_data_stts.sql 이 만든 <저작도구 자체 소유> 코드
--   테이블인데 혼자만 LS_ 접두가 없어 소유 구분이 흐렸다. 저작도구 전용 테이블은 LS_* 라는
--   규칙(CLAUDE.md 「DB 정책」)에 맞춘다 — 관제 공유(MNG_*)와의 경계를 이름만으로 읽게 하는 것이 목적.
--
-- 배경 ②표준용어: 구 이름의 'CM'·'CODE' 는 <공통표준단어·사업표준단어 어디에도 없다>. 정본 CSV 대조
--   결과 공통 = COM, 코드 = CD 이므로 표준 조합은 LS_COM_CD 다. 접두만 붙여 LS_CM_CODE 로 두면
--   소유 문제만 고치고 비표준 물리명을 그대로 남기게 되어(감리 지적 대상) 한 번에 바로잡는다.
--   판정 근거: docs/LogiCraft-공공표준용어-*/공통표준단어.csv · docs/LogiCraft-사업용어-*/사업표준단어.csv
--   (검색 API 는 조회 상한 때문에 "미등록" 오판을 내므로 CSV 정본 grep 으로 판정한다 — CLAUDE.md 규칙).
--
-- ★ 이 파일이 <조건부>인 이유 — 두 경로가 수동 개입 없이 같은 결과로 수렴해야 한다
--   · 신규 설치(빈 DB): V1__baseline.sql 이 이미 LS_COM_CD 로 만든다 → 여기서는 아무것도 하지 않는다.
--   · 기존 DB(스쿼시 이전에 만들어진 로컬·dev): 이력을 베이스라인 1행으로 교체한 뒤 이 V2 만 적용되어
--     실제 개명을 수행한다.
--   조건 없이 무조건 RENAME 을 쓰면 신규 설치에서 "relation cm_code does not exist" 로 첫 기동이 깨진다.
--
-- ★ 카탈로그 조회는 반드시 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 2」)
--   to_regclass 는 search_path 로 해석되고, pg_constraint 검사는 conrelid 로 대상 테이블에 한정한다.
--   pg_constraint.conname 은 DB 전역에서 유일하지 않아, 이름만으로 EXISTS 를 보면 다른 스키마의
--   동명 제약을 보고 "이미 처리됨"으로 오판해 필요한 DDL 을 조용히 건너뛴다(구 V79·V110·V146 의 형태).
--
-- 데이터 영향 없음: RENAME 은 행을 건드리지 않는다(코드값 5건 그대로 유지).
-- 참조 무결성: 이 테이블을 가리키는 FK 는 없다(코드값 참조는 애플리케이션 상수로 관리).
--
-- 잠금(2노드 Active-Active): RENAME 은 ACCESS EXCLUSIVE 지만 5행짜리 코드 테이블이라 순간이다.
--
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용한다.
--     ALTER TABLE LS_COM_CD RENAME TO CM_CODE;
--     ALTER TABLE CM_CODE RENAME CONSTRAINT ls_com_cd_pkey TO cm_code_pkey;
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '2';
--   (구버전 코드에는 이 테이블의 JPA 매핑이 없어 ddl-auto=validate 기동은 깨지지 않지만,
--    구 마이그레이션이 CM_CODE 를 참조하므로 이름을 되돌려 둔다.)
-- ============================================================================

DO $$
BEGIN
    -- 개명 대상이 실재하고, 목적지 이름이 아직 비어 있을 때만 수행한다(멱등).
    IF to_regclass('cm_code') IS NOT NULL AND to_regclass('ls_com_cd') IS NULL THEN
        ALTER TABLE cm_code RENAME TO ls_com_cd;
    END IF;

    -- PK 제약명도 함께 옮긴다. 테이블만 바꾸면 제약·인덱스 이름이 cm_code_pkey 로 남아
    -- 신규 설치본(ls_com_cd_pkey)과 <같은 스키마인데 이름만 다른> 상태가 되어,
    -- 스키마 덤프 비교·향후 조건부 마이그레이션이 두 경로에서 갈린다.
    -- ※ 인덱스는 별도로 손대지 않는다 — PostgreSQL 은 제약을 개명하면 backing index 도 함께 개명한다.
    IF EXISTS (SELECT 1 FROM pg_constraint
                WHERE conname = 'cm_code_pkey'
                  AND conrelid = to_regclass('ls_com_cd')) THEN
        ALTER TABLE ls_com_cd RENAME CONSTRAINT cm_code_pkey TO ls_com_cd_pkey;
    END IF;
END $$;
