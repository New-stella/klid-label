-- =============================================================================
-- V62: MNG_CLIP_MASTER 로컬/테스트 stub 을 실제 관제 스키마로 교정
--
-- 배경: V2 의 MNG_CLIP_MASTER stub 이 실제 klid_system 스키마와 어긋나 있었다
--   (틀린 컬럼: CLIP_SN / VMS_CLIP_ID / REG_DT). 실제 PK 는 복합키
--   (EVNT_ID, CLIP_TYPE_CD) 이고 파일경로는 FILE_PATH 다. JPA ddl-auto=validate
--   환경이라 교정된 MngClipMaster 엔티티와 정합하려면 stub 도 실제 스키마여야 한다.
--
-- 안전성(관제 소유 테이블 보호):
--   MNG_CLIP_MASTER 는 관제팀 소유다. 운영(dev/stg/prd) klid_system 에는 실제
--   테이블이 이미 존재하며 그 스키마에는 CLIP_SN 컬럼이 없다. 본 마이그레이션은
--   "CLIP_SN 컬럼이 존재할 때(=우리 stub 인 경우)에만" DROP & 재생성하므로,
--   실제 관제 테이블은 절대 건드리지 않는다(IF 분기로 skip). V2 는 이미 배포된
--   마이그레이션이라 수정 시 체크섬 충돌이 나므로 신규 V62 로 분리했다.
--
-- 스키마 한정(다중 스키마 오탐 차단):
--   본 프로젝트는 Flyway/JPA 에 별도 스키마 설정이 없어 모든 테이블이 PostgreSQL
--   기본 스키마 'public' 에 생성·검증된다(V2 stub·운영 관제 테이블 동일). 같은 DB 의
--   다른 스키마에 동명(mng_clip_master) 테이블이 존재해도 'public' 한정으로 CLIP_SN
--   가드가 오탐하지 않도록 information_schema 조회 및 DROP/CREATE 를 'public' 으로
--   한정한다(비한정 시 search_path 의존 → 오탐 위험). PG 식별자는 소문자 보관됨.
--
-- 모든 타입은 PostgreSQL 표준. 컬럼은 엔티티 매핑분 + 적재 조회 필터에 필요한 것.
-- =============================================================================

DO $$
BEGIN
    -- stub 식별: 우리 stub 에만 존재하는 CLIP_SN 컬럼이 있으면 교정 대상('public' 한정).
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'mng_clip_master'
          AND column_name = 'clip_sn'
    ) THEN
        DROP TABLE IF EXISTS public.MNG_CLIP_MASTER;

        CREATE TABLE public.MNG_CLIP_MASTER (
            EVNT_ID       VARCHAR(50)   NOT NULL,
            CLIP_TYPE_CD  VARCHAR(20)   NOT NULL,
            CLIP_ID       VARCHAR(50),
            LCLGV_CD      VARCHAR(20),
            FILE_NM       VARCHAR(256),
            FILE_PATH     VARCHAR(1000),
            FILE_FMT      VARCHAR(10),
            VDO_LEN_SEC   INT,
            CLIP_STTS_CD  VARCHAR(20),
            CRT_DT        TIMESTAMP,
            ULD_CMPT_DT   TIMESTAMP,
            JOB_DMND_YN   VARCHAR(1),
            VMS_CCTV_ID   VARCHAR(30),
            PRIMARY KEY (EVNT_ID, CLIP_TYPE_CD)
        );

        -- 학습용 픽업 스캔(JOB_DMND_YN='Y' AND FILE_PATH 존재) 보조 인덱스.
        CREATE INDEX IF NOT EXISTS IX_MNG_CLIP_MASTER_JOB_DMND
            ON public.MNG_CLIP_MASTER (JOB_DMND_YN);
    END IF;
END $$;
