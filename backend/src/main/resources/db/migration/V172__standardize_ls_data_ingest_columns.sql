-- =============================================================================
-- V172: 관제 인입 LS_DATA_INGEST 표준용어·표준도메인 정합 (@req R1 · @req R2)
--
-- 목적: 인입 테이블의 <비표준 물리명 4건>과 <표준도메인 이탈 2건>을 정정한다.
--       이 테이블은 관제지원시스템이 직접 INSERT 하는 수신 창구이고, 규격서·관제 테이블과
--       물리명을 맞춰야 양측이 같은 이름으로 같은 값을 말한다. 이후 단계(뷰 재작성·완료 통지
--       페이로드)가 전부 이 이름을 전제한다.
--
-- ★ 표준 근거 (정본 CSV 대조 — docs/LogiCraft-사업용어-*/사업표준단어.csv 471건 ·
--   docs/LogiCraft-공공표준용어-*/공통표준단어.csv 3,284건. MCP 검색은 200건 캡이라 정본이 아니다)
--
--   [@req R1 — 물리명]
--   · PROC_STTS_CD → PRCS_STTS_CD
--       'PROC' 는 사업표준단어 미등록이고 공공표준에는 <프로세스>로 뜻이 다르다.
--       처리 = PRCS (사업·공공 양쪽 등록). 결정적으로 <같은 테이블의 PRCS_DT(처리일시)가 이미
--       그 약어를 쓰고 있어> 한 테이블 안에서 같은 단어가 두 약어로 갈려 있었다.
--   · NEXT_RTRY_DT → NXTM_RTY_DT
--       'NEXT' 는 사업·공공 양쪽 0건. 차기 = NXTM (공공표준단어, 7차). 재시도는 같은 테이블의
--       RTY_CNT 가 쓰는 RTY (사업표준단어)로 통일한다.
--       ※ V147 주석은 이 물리명을 '사업표준용어 등록분'으로 적었으나 단어 정본 대조 결과
--         NEXT 가 미등록이라 조합이 성립하지 않는다. 그 서술은 이 마이그레이션으로 폐기된다.
--   · RGN_NM → LCLGV_NM (VARCHAR(200) → VARCHAR(100))
--       'RGN' 사업표준 미등록. 지방자치단체명 = LCLGV_NM 은 사업·공공 양쪽 <용어>로 등록돼 있고
--       도메인은 명V100 이다(행정안전부 6차). 짝인 LCLGV_CD 가 이미 이 테이블에 있고, 관제
--       datasets.lclgv_nm 과 물리명·길이까지 일치한다.
--   · FRM_CNT → FRME_CNT
--       'FRM' 은 표준에서 <형식(Form)>이라 현행 물리명은 "형식수"로 읽힌다. 프레임 = FRME (공공 8차).
--
--   [@req R2 — 표준도메인]
--   · EVNT_CLSF_CD  VARCHAR(20) → CHAR(2)  : 표준도메인 코드C2 (2자리 문자)
--   · EVNT_CTGRY_CD VARCHAR(20) → CHAR(4)  : 표준도메인 코드C4 (4자리 문자)
--     실제 값도 2자·4자 고정이다(이벤트 카테고리 키 6자 = 분류 2 + 카테고리 4 — VideoQueryService).
--     값이 정확히 n 자라 CHAR 고정길이 패딩이 발생하지 않는다.
--     ※ 이 테이블만 정정한다. LS_EVNT_TYPE / LS_EVNT_CTGRY 의 동명 컬럼은 별도 라운드다.
--
-- ★ 절단 방어는 PostgreSQL 이 <원래> 한다 — 별도 가드를 두지 않는다.
--   길이를 줄이는 ALTER COLUMN TYPE 은 초과 값이 한 행이라도 있으면
--   'value too long for type ...' 로 <마이그레이션 자체가 실패>한다(fail-closed). 착수 전 실DB
--   실측(40행, evnt_clsf_cd·evnt_ctgry_cd·rgn_nm 전량 NULL)에서도 초과 0건이었다.
--
-- ⚠ 동명이표 주의 — 아래 두 컬럼은 <다른 테이블>이며 이번 대상이 아니다(전역 치환 금지).
--   · LS_DEIDENT_PROC_LOG.PROC_STTS_CD  — V_COMPLETED_VIDEO 뷰 본문이 이 이름으로 참조한다.
--   · LS_CONTROL_NOTIFY_FALLBACK.NEXT_RTRY_DT (V44, IDX_LCNF_STATUS 인덱스 키).
--   회귀 가드: V172IngestStandardTermMigrationIT.
--
-- 무손실 rename — 데이터 손실 0. 우리 소유 LS_* 테이블이라 관제팀 협의 불요.
-- =============================================================================

-- 1. 물리명 정정 (@req R1)
ALTER TABLE LS_DATA_INGEST RENAME COLUMN PROC_STTS_CD TO PRCS_STTS_CD;
ALTER TABLE LS_DATA_INGEST RENAME COLUMN NEXT_RTRY_DT TO NXTM_RTY_DT;
ALTER TABLE LS_DATA_INGEST RENAME COLUMN RGN_NM       TO LCLGV_NM;
ALTER TABLE LS_DATA_INGEST RENAME COLUMN FRM_CNT      TO FRME_CNT;

-- 2. 표준도메인 정합 (@req R1 길이 · @req R2 코드 도메인)
ALTER TABLE LS_DATA_INGEST ALTER COLUMN LCLGV_NM      TYPE VARCHAR(100);
ALTER TABLE LS_DATA_INGEST ALTER COLUMN EVNT_CLSF_CD  TYPE CHAR(2);
ALTER TABLE LS_DATA_INGEST ALTER COLUMN EVNT_CTGRY_CD TYPE CHAR(4);

-- 3. 폴링 부분 인덱스 재생성 — 술어와 INCLUDE 에 컬럼명이 박혀 있다.
--    PostgreSQL 은 RENAME COLUMN 시 인덱스 정의를 조용히 추종하지만, V147 정의와 <명시적으로>
--    같은 형상임을 이 파일에서 확정한다(추종에 의존하면 이후 정의가 어디에 있는지 알 수 없다).
--    형상은 V147 과 동일: 키 (RCPTN_DT, RCPTN_SN) · INCLUDE 차기재시도일시 · 술어 PENDING.
--    · 술어에 now 를 넣을 수 없어(immutable 아님) 차기 재시도 시각은 INCLUDE 로 싣는다 —
--      고착 행을 힙 방문 없이 인덱스에서 걸러낸다.
--    · 상태는 부분 인덱스 술어로 둔다 — 완료분이 영구 누적돼도 인덱스 크기가 미처리에 비례한다.
DROP INDEX IF EXISTS IX_LS_DATA_INGEST_POLL;
CREATE INDEX IF NOT EXISTS IX_LS_DATA_INGEST_POLL
    ON LS_DATA_INGEST (RCPTN_DT, RCPTN_SN) INCLUDE (NXTM_RTY_DT)
    WHERE PRCS_STTS_CD = 'PENDING';

-- 4. 컬럼 설명 갱신 — 의미는 V147 정본을 보존하고 물리명 참조만 새 이름으로 맞춘다.
COMMENT ON COLUMN LS_DATA_INGEST.PRCS_STTS_CD IS '저작도구 처리상태코드. PENDING(미처리) 기본. 관제는 이 컬럼을 채우지 않아도 된다. (V172 개명 — 구 PROC_STTS_CD. 처리=PRCS 표준단어, 같은 테이블 PRCS_DT 와 약어 통일)';
COMMENT ON COLUMN LS_DATA_INGEST.NXTM_RTY_DT  IS '차기 재시도 예정 일시. 폴링 후보는 PENDING AND (이 값 NULL OR <= 현재)다 — 파일 미도착 관측 시 이 값을 뒤로 밀어(backoff) 고착 행이 FIFO 앞자리를 잠식하지 못하게 한다(설계 §6-0-1-a ㉢). 재큐 시 NULL 로 비운다. (V172 개명 — 구 NEXT_RTRY_DT. 차기=NXTM·재시도=RTY 표준단어)';
COMMENT ON COLUMN LS_DATA_INGEST.LCLGV_NM     IS '지방자치단체명(관제 video.location). 동은 포함하지 않는다. 지방자치단체코드(LCLGV_CD)·기관코드(OG_CD)와 서로 다른 값이며 셋을 대체·통합하지 않는다. (V172 개명 — 구 RGN_NM. 표준용어 LCLGV_NM·도메인 명V100, 관제 datasets.lclgv_nm 과 일치)';
COMMENT ON COLUMN LS_DATA_INGEST.FRME_CNT     IS '프레임수(관제 video.frames). (V172 개명 — 구 FRM_CNT. FRM 은 표준에서 형식(Form)이라 프레임=FRME 로 정정)';
COMMENT ON COLUMN LS_DATA_INGEST.LCLGV_CD     IS '지방자치단체코드. LS_DATA_RAW.LCLGV_CD 의 원천이며 관제 완료통지 페이로드 lclgv_cd(required)가 이 값에서 나온다. 지방자치단체명(LCLGV_NM)·기관코드(OG_CD)와 서로 다른 값이다.';
COMMENT ON COLUMN LS_DATA_INGEST.EVNT_CLSF_CD IS '이벤트분류코드(대분류, 관제 수신). LS_EVNT_TYPE.EVNT_CLSF_CD 의 원천이며 이벤트유형 제외 필터의 판정축이다. 코드에서 유도하지 않는다 — 관제가 안 보내면 NULL 이다. (V172 표준도메인 코드C2 정합)';
COMMENT ON COLUMN LS_DATA_INGEST.EVNT_CTGRY_CD IS '이벤트카테고리코드(관제 수신). 이벤트 3계층(대분류→카테고리→유형)의 중간 레벨이며 LS_EVNT_TYPE.EVNT_CTGRY_CD 의 원천이다. 관제가 안 보내면 NULL 이며 유도하지 않는다. (V172 표준도메인 코드C4 정합)';
