-- =============================================================================
-- V147: MNG_CLIP_MASTER / MNG_CLIP_EVNT_LST 로컬·테스트 stub 을 ERD-024 전체 컬럼으로 정합
--
-- 근거(정본): LogiCraft ERD-024 "관제 공유 클립 ERD" — 2026-06-10 관제 DB 직접 조회로 확정된
--   실제 관제 스키마다. 아래 컬럼의 물리명·타입·길이는 우리가 새로 짓는 이름이 아니라
--   **관제서버가 이미 보유한 실제 컬럼**이며, 본 마이그레이션은 그 실제 스키마에 우리 stub 을
--   맞추는 정합 작업이다(표준용어 신규 등록 대상 아님. 한 글자도 임의 변경 금지).
--
-- 목적: 관제가 주는 값 전부를 담을 컬럼을 먼저 확보한다. 본 마이그레이션 자체는 동작을 바꾸지
--   않으며(읽을 수 있는 컬럼만 늘어남), 후속 Phase 가 이 컬럼을 사용한다.
--
-- 추가 컬럼
--   MNG_CLIP_MASTER   (13 → 16)
--     FILE_SZ             BIGINT       파일 크기(byte)
--     JOB_DMND_PRNMNT_YN  VARCHAR(1)   작업요청 예정 여부
--     CRT_TYPE            INTEGER      생성 타입 (0=중계서버 생성, 1=수동 생성)
--   MNG_CLIP_EVNT_LST (3 → 12)
--     EVNT_NM             VARCHAR(200) 이벤트명
--     LCLGV_CD            VARCHAR(20)  지자체 코드
--     SESN_CD             VARCHAR(10)  계절 코드
--     WTHR_CD             VARCHAR(10)  날씨 코드
--     HR_TYPE_CD          VARCHAR(10)  시간 유형 코드
--     PRVC_TYPE_CD        VARCHAR(20)  개인정보 유형
--     IDNTF_YN            VARCHAR(1)   비식별화 처리 여부
--     CLCT_PATH           VARCHAR(255) 수집 경로
--     CLCT_SRC            VARCHAR(255) 수집 출처
--
-- 안전성(관제 소유 테이블 보호 — MNG_* 는 관제팀 소유):
--   운영(dev/stg/prd) klid_system 에는 **실제 관제 테이블**이 존재하고 위 컬럼은 거기에 **이미
--   있다**. 그래서 본 마이그레이션은 실 테이블에서 완전한 no-op 이 되도록 다음을 지킨다.
--     1) 컬럼 부재를 information_schema 로 먼저 확인한 뒤에만 ALTER 를 실행한다.
--        (`ADD COLUMN IF NOT EXISTS` 만 쓰면 이미 존재해도 ALTER TABLE 자체가 실행되어
--         테이블 소유자가 아닌 계정에서는 권한 오류가 난다 — PostgreSQL 은 IF NOT EXISTS
--         판정 전에 소유권을 검사한다. 실 관제 테이블은 우리 계정 소유가 아닐 수 있다.)
--        방어적으로 `ADD COLUMN IF NOT EXISTS` 도 함께 써서 동시 실행 경합에도 멱등하다.
--     2) 추가 컬럼은 **전부 nullable**(NOT NULL/DEFAULT 없음) — 기존 행이 있어도 실패하지 않는다.
--     3) 기존 컬럼의 타입·길이·제약·PK·인덱스는 **일절 건드리지 않는다**
--        (ALTER TYPE / DROP / RENAME 0건). 보호 컬럼 목록은
--        docs/관제팀-공유테이블-변경금지-가이드.md §3 참조.
--
-- 스키마 한정(다중 스키마 오탐 차단):
--   V62/V63 과 동일하게 'public' 로 한정한다. 본 프로젝트는 Flyway/JPA 에 별도 스키마 설정이
--   없어 모든 테이블이 기본 스키마 'public' 에 생성·검증된다. 비한정 시 search_path 의존으로
--   같은 DB 의 동명 테이블을 오탐할 수 있다. PG 식별자는 소문자로 보관된다.
--
-- 모든 타입은 PostgreSQL 표준이며 정적 DDL 만 사용한다(동적 SQL·문자열 결합 없음).
-- =============================================================================

DO $$
BEGIN
    -- ---------------------------------------------------------------------
    -- MNG_CLIP_MASTER (13 → 16)
    -- ---------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_master'
                     AND column_name = 'file_sz') THEN
        ALTER TABLE public.MNG_CLIP_MASTER ADD COLUMN IF NOT EXISTS FILE_SZ BIGINT;
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_master'
                     AND column_name = 'job_dmnd_prnmnt_yn') THEN
        ALTER TABLE public.MNG_CLIP_MASTER ADD COLUMN IF NOT EXISTS JOB_DMND_PRNMNT_YN VARCHAR(1);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_master'
                     AND column_name = 'crt_type') THEN
        ALTER TABLE public.MNG_CLIP_MASTER ADD COLUMN IF NOT EXISTS CRT_TYPE INTEGER;
    END IF;

    -- ---------------------------------------------------------------------
    -- MNG_CLIP_EVNT_LST (3 → 12)
    -- ---------------------------------------------------------------------
    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'evnt_nm') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS EVNT_NM VARCHAR(200);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'lclgv_cd') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS LCLGV_CD VARCHAR(20);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'sesn_cd') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS SESN_CD VARCHAR(10);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'wthr_cd') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS WTHR_CD VARCHAR(10);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'hr_type_cd') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS HR_TYPE_CD VARCHAR(10);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'prvc_type_cd') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS PRVC_TYPE_CD VARCHAR(20);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'idntf_yn') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS IDNTF_YN VARCHAR(1);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'clct_path') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS CLCT_PATH VARCHAR(255);
    END IF;

    IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                   WHERE table_schema = 'public' AND table_name = 'mng_clip_evnt_lst'
                     AND column_name = 'clct_src') THEN
        ALTER TABLE public.MNG_CLIP_EVNT_LST ADD COLUMN IF NOT EXISTS CLCT_SRC VARCHAR(255);
    END IF;
END $$;
