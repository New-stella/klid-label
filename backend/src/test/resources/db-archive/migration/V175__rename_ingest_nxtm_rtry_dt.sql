-- =============================================================================
-- V175: 인입 차기재시도일시 NXTM_RTY_DT → NXTM_RTRY_DT (@req R1 재판정 — 표준 우선순위 오적용 정정)
--
-- ★ 왜 V172 직후에 같은 컬럼을 또 바꾸는가 (이 주석이 없으면 다음 세션이 "왜 두 번 바꿨나"로 되돌아온다)
--
--   표준 우선순위가 <① 행안부 공통표준 → ② 사업표준> 로 확정됐는데(2026-08-05 사용자),
--   V172 는 그 반대 순서로 판정해 재시도를 <사업 전용 약어 RTY> 로 바꿔 놓았다.
--   정본 CSV 재대조 결과:
--       재시도 = RTRY (공통표준단어, 행안부)   ← 1순위. 채택.
--       재시도 = RTY  (사업표준단어)           ← 2순위. V172 가 잘못 채택했던 값.
--       차기   = NXTM (공통표준단어)           ← 불변(이 부분은 V172 가 맞았다)
--   즉 V147 원래 이름 NEXT_RTRY_DT 의 'RTRY' 부분은 <처음부터 옳았고>, V172 가 'NEXT'(양쪽 미등록)를
--   고치면서 멀쩡한 RTRY 까지 사업 전용어로 바꾼 것이다. 이번에 RTRY 만 되돌린다.
--   최종 물리명 NXTM_RTRY_DT = 차기(NXTM) + 재시도(RTRY) + 일시(DT) — <전부 행안부 공통표준>.
--
-- ⚠ 범위 한정 — 기존 RTY 자산은 건드리지 않는다 (의도된 일시 공존)
--   RTY 는 이미 3테이블 7컬럼과 <테이블명 LS_BAT_RTY_WTNG> 에 정착해 있다
--   (RTY_CNT · RTY_DT · RTY_NMTM · MAX_RTY_NMTM · RTY_PRNMNT_DT · BAT_RTY_SN).
--   전면 통일은 테이블명 rename 까지 포함해 관제 연동 범위를 크게 벗어나고 이 변경의 리뷰를 불가능하게
--   만든다. 그래서 <이번 라운드에서 우리가 만든 NXTM_RTY_DT 하나만> 정정한다.
--   → 그 결과 같은 테이블 LS_DATA_INGEST 안에 RTRY(신규)와 RTY_CNT(기존)가 <일시 공존>한다.
--     이것은 결함이 아니라 <의도된 상태>이며, 기존 RTY 자산 통일은 <별도 라운드 백로그>다.
--     여기서 RTY_CNT 를 함께 바꾸지 말 것 — 범위 밖이다.
--
-- ★ 부분 인덱스는 반드시 재생성한다 — 이름이 정의에 박혀 있다.
--   IX_LS_DATA_INGEST_POLL 은 INCLUDE (NXTM_RTY_DT) 로 컬럼을 싣는다. PostgreSQL 이 RENAME COLUMN 을
--   조용히 추종하긴 하지만, V172 와 같은 방침으로 <명시적으로> 같은 형상임을 이 파일에서 확정한다
--   (추종에만 의존하면 이후 정의가 어디에 있는지 알 수 없다). 형상은 V147/V172 와 동일하다:
--   키 (RCPTN_DT, RCPTN_SN) · INCLUDE 차기재시도일시 · 술어 PRCS_STTS_CD='PENDING'.
--     · 술어에 now 를 넣을 수 없어(immutable 아님) 차기 재시도 시각은 INCLUDE 로 싣는다.
--     · 상태는 부분 인덱스 술어로 둬 완료분이 영구 누적돼도 인덱스가 미처리 건수에 비례한다.
--
-- ⚠ 동명이표 주의 — 전역 치환 금지. LS_CONTROL_NOTIFY_FALLBACK.NEXT_RTRY_DT(V44, IDX_LCNF_STATUS
--   인덱스 키)는 <다른 테이블>이며 이번 대상이 아니다. 회귀 가드: V172IngestStandardTermMigrationIT.
--
-- 무손실 rename — 데이터 손실 0. 우리 소유 LS_* 테이블이라 관제팀 협의 불요. PostgreSQL 표준 문법.
-- =============================================================================

-- 1. 물리명 정정 — 재시도 RTY(사업) → RTRY(행안부 공통)
ALTER TABLE LS_DATA_INGEST RENAME COLUMN NXTM_RTY_DT TO NXTM_RTRY_DT;

-- 2. 폴링 부분 인덱스 재생성 — INCLUDE 에 컬럼명이 박혀 있다(형상은 V147/V172 와 동일).
DROP INDEX IF EXISTS IX_LS_DATA_INGEST_POLL;
CREATE INDEX IF NOT EXISTS IX_LS_DATA_INGEST_POLL
    ON LS_DATA_INGEST (RCPTN_DT, RCPTN_SN) INCLUDE (NXTM_RTRY_DT)
    WHERE PRCS_STTS_CD = 'PENDING';

-- 3. 컬럼 설명 갱신 — 의미는 V147 정본을 보존하고 물리명 참조만 새 이름으로 맞춘다.
COMMENT ON COLUMN LS_DATA_INGEST.NXTM_RTRY_DT IS '차기 재시도 예정 일시. 폴링 후보는 PENDING AND (이 값 NULL OR <= 현재)다 — 파일 미도착 관측 시 이 값을 뒤로 밀어(backoff) 고착 행이 FIFO 앞자리를 잠식하지 못하게 한다(설계 §6-0-1-a ㉢). 재큐 시 NULL 로 비운다. (V175 개명 — 구 NXTM_RTY_DT. 표준 우선순위 ①행안부→②사업 재판정으로 재시도=RTRY(공통표준단어) 채택. 같은 테이블 RTY_CNT 는 기존 자산이라 이번 범위 밖 — 별도 라운드)';
