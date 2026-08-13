-- =============================================================================
-- V71: MNG_EX_EVNT_TYPE / MNG_EX_EVNT_TYPE_MAP 로컬/테스트 stub 을 실제 관제 스키마로 교정
--
-- 배경: V2 의 두 stub 이 실제 klid_system 스키마와 어긋나 있었다.
--   - MNG_EX_EVNT_TYPE     stub: (EVNT_TYPE_CD, CLCT_EVNT_NM, USE_YN)
--       실제: PK EVNT_TYPE_CD + EVNT_CLS_CD/EVNT_CTGRY_CD/CLCT_EVNT_NM/CLCT_YN (+ 수집 파라미터)
--   - MNG_EX_EVNT_TYPE_MAP stub: (EVNT_TYPE_CD PK, EVNT_NM, UP_EVNT_TYPE_CD, EVNT_LVL)
--       실제: 복합 PK(CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD) + EVNT_NM/USE_YN
--   JPA ddl-auto=validate 환경이라 교정된 엔티티(MngExEvntType/MngExEvntTypeMap)와 정합하려면
--   stub 도 실제 스키마여야 한다. 또한 MAP 의 카테고리명행/대분류명행은 EVNT_TYPE_CD 가 빈
--   문자열('')이라 단일 PK stub 으로는 dev-seed 다수 행을 적재할 수 없어 복합 PK 가 필요하다.
--
-- 안전성(관제 소유 테이블 보호) — V62 와 동일 가드 패턴:
--   두 테이블 모두 관제팀 소유다. 운영(dev/stg/prd) klid_system 에는 실제 테이블이 이미 존재하며
--   그 스키마에는 stub 전용 컬럼(MNG_EX_EVNT_TYPE.USE_YN / MNG_EX_EVNT_TYPE_MAP.EVNT_LVL)이
--   없다. 본 마이그레이션은 "stub 전용 컬럼이 존재할 때(=우리 stub 인 경우)에만" DROP & 재생성
--   하므로 실제 관제 테이블은 절대 건드리지 않는다(IF 분기로 skip). V2 는 이미 배포된 마이그레이션
--   이라 수정 시 체크섬 충돌이 나므로 신규 V71 로 분리했다.
--
-- 스키마 한정(다중 스키마 오탐 차단):
--   information_schema 조회를 저작도구가 쓰는 스키마 1개(current_schema())로 한정한다 —
--   Flyway 가 이 마이그레이션을 적용하는 대상 스키마이자 아래 DROP/CREATE 의 비한정 식별자가
--   해석되는 곳이며, 가드와 조작 대상은 반드시 동일해야 한다.
--   ★ 구 구현은 'public' 리터럴을 박아 두었다 — 그때는 스키마 설정이 없어 항상 public 이었기
--     때문이다. 스키마를 klid_at 으로 옮기면 그 가드가 영원히 거짓이 되어 교정이 건너뛰어지고,
--     뒤의 V168 이관이 구 stub 컬럼(USE_YN)만 있는 테이블을 읽게 된다. PG 식별자는 소문자 보관됨.
--
-- 모든 타입은 PostgreSQL 표준. 컬럼은 엔티티 매핑분 + 라벨 도출에 필요한 것만(미매핑 수집
-- 파라미터는 향후 enrich 여지로 생략).
-- =============================================================================

-- 1) MNG_EX_EVNT_TYPE — stub 식별: 우리 stub 에만 있는 USE_YN 컬럼(실제는 CLCT_YN).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'mng_ex_evnt_type'
          AND column_name = 'use_yn'
    ) THEN
        DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE;

        CREATE TABLE MNG_EX_EVNT_TYPE (
            EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
            EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
            EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
            CLCT_EVNT_NM   VARCHAR(4000),
            CLCT_YN        VARCHAR(2)    NOT NULL,
            PRIMARY KEY (EVNT_TYPE_CD)
        );

        -- 수집대상(CLCT_YN='Y') 필터 보조 인덱스.
        CREATE INDEX IF NOT EXISTS IX_MNG_EX_EVNT_TYPE_CLCT_YN
            ON MNG_EX_EVNT_TYPE (CLCT_YN);
    END IF;
END $$;

-- 2) MNG_EX_EVNT_TYPE_MAP — stub 식별: 우리 stub 에만 있는 EVNT_LVL 컬럼(실제 부재).
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'mng_ex_evnt_type_map'
          AND column_name = 'evnt_lvl'
    ) THEN
        DROP TABLE IF EXISTS MNG_EX_EVNT_TYPE_MAP;

        CREATE TABLE MNG_EX_EVNT_TYPE_MAP (
            CD_TYPE        VARCHAR(2)    NOT NULL,
            EVNT_CLS_CD    VARCHAR(2)    NOT NULL,
            EVNT_CTGRY_CD  VARCHAR(4)    NOT NULL,
            DTL_EVNT       VARCHAR(2)    NOT NULL,
            EVNT_TYPE_CD   VARCHAR(20)   NOT NULL,
            EVNT_NM        VARCHAR(4000),
            USE_YN         VARCHAR(2)    NOT NULL,
            PRIMARY KEY (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD)
        );

        -- CD_TYPE 별(대분류명/카테고리명행) 조회 보조 인덱스.
        CREATE INDEX IF NOT EXISTS IX_MNG_EX_EVNT_TYPE_MAP_CD_TYPE
            ON MNG_EX_EVNT_TYPE_MAP (CD_TYPE);
    END IF;
END $$;
