-- =============================================================================
-- V63: MNG_CLIP_EVNT_LST 로컬/테스트 stub 생성 (관제 이벤트 리스트)
--
-- 배경: 관제 학습용 적재에서 EVNT_TYPE_CD/촬영 일자(SHT_DT)를 도출하기 위해
--   MNG_CLIP_MASTER.EVNT_ID → MNG_CLIP_EVNT_LST.EVNT_ID 조인이 필요하다.
--   JPA ddl-auto=validate 환경이라 신규 MngClipEvntLst 엔티티(읽기 전용)와 정합하려면
--   로컬/테스트 DB 에 매핑 컬럼을 가진 stub 테이블이 있어야 한다.
--
-- 매핑 컬럼(3개) — 실제 klid_system MNG_CLIP_EVNT_LST 은 28컬럼이나 이번엔 3개만 매핑:
--   복합 PK (EVNT_ID, EVNT_TYPE_CD) + SHT_DT. validate 는 매핑 컬럼만 검사하므로
--   stub 은 매핑 컬럼만 있으면 충분하다(미매핑 컬럼은 향후 enrich 여지로 남김).
--
-- 안전성(관제 소유 테이블 보호):
--   MNG_CLIP_EVNT_LST 는 관제팀 소유다. 운영(dev/stg/prd) klid_system 에는 실제
--   테이블(28컬럼)이 이미 존재한다. CREATE TABLE IF NOT EXISTS 는 기존 테이블이
--   있으면 no-op 이므로 실제 관제 테이블을 절대 변경하지 않는다(컬럼 추가/삭제 없음).
--   따라서 로컬에서는 stub 이 생기고, 운영에서는 기존 실제 테이블이 그대로 유지된다.
--
-- 스키마 한정(다중 스키마 오탐 차단):
--   저작도구가 쓰는 스키마 1개에만 stub 을 만든다. 그 스키마는 Flyway 가 이 마이그레이션을
--   적용하는 대상 스키마이며, 비한정 식별자가 해석되는 곳과 동일하다.
--   ★ 구 구현은 'public' 리터럴을 박아 두었다 — 그때는 스키마 설정이 없어 항상 public
--     이었기 때문이다. 스키마를 klid_at 으로 옮기면 stub 이 public 에만 생겨, 뒤의 V164
--     백필이 "relation mng_clip_evnt_lst does not exist" 로 실패한다(신규 DB 재적용 실측).
--
-- 모든 타입은 PostgreSQL 표준. Flyway 체크섬 충돌 방지를 위해 신규 V 파일로 분리.
-- =============================================================================

CREATE TABLE IF NOT EXISTS MNG_CLIP_EVNT_LST (
    EVNT_ID       VARCHAR(50)  NOT NULL,
    EVNT_TYPE_CD  VARCHAR(20)  NOT NULL,
    SHT_DT        TIMESTAMP,
    PRIMARY KEY (EVNT_ID, EVNT_TYPE_CD)
);
