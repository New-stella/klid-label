-- =============================================================================
-- V136: LS_DATASET_EXPORT 에 재시도 횟수/일시 컬럼 추가 (D-ISSUE-04(b) DEV_FIX H7①/H7③).
--
-- [결함 1 — 무한 재시도] 실패 export 회수 잡이 시도 횟수를 "마지막 성공 이후 쌓인 FAILED 행 수"로
--   셌는데, 재시도가 항상 FAILED 행을 만들지는 않는다:
--     · 프레임/활성 메타 부재(NO_INPUT) → export 레코드를 INSERT 하지 않고 early return
--     · 버전 채번 재시도 소진 → 레코드 자체가 없음
--     · @Async 러너가 예외를 삼킴 → 아무 흔적도 남지 않음
--   이 유형에 걸린 영상은 카운트가 영원히 고정돼 max-attempts 가 무효가 되고 주기(기본 15분)마다
--   무한 재시도됐다. → <b>시도 자체를 기록하는 컬럼</b>을 두어 산출 결과와 무관하게 카운트가 오른다.
--
-- [결함 2 — 중복 산출] "Quartz 클러스터가 매 tick 을 한 노드만 실행한다"는 전제로 중복을 막았으나
--   실제 설정은 org.quartz.jobStore.isClustered 기본 false(프로파일 override 0건, onprem 템플릿도 false)라
--   2노드 Active-Active 에서 두 노드가 같은 영상을 동시에 재산출해 서로 다른 버전 폴더를 만들 수 있었다.
--   → RTY_DT(재시도 일시)를 이용한 <b>DB 레벨 조건부 UPDATE 클레임</b>으로, 클러스터링 설정과 무관하게
--     같은 영상을 한 노드만 집어가게 한다(Quartz 클러스터링 활성화는 Phase 9 소관 — 켜지 않음).
--
-- [표준용어·표준도메인]
--   RTY_NMTM = 재시도(RTY, 사업표준단어) + 횟수(NMTM, 공통표준단어 형식단어 '수') — 리포 선례
--              LS_BAT_RTY_WTNG.RTY_NMTM(V116) 과 동일 물리명·동일 타입(INT).
--              타입 INT = 표준도메인 수I11(INTEGER). NOT NULL DEFAULT 0.
--   RTY_DT   = 재시도(RTY) + 일시(DT, 공통/사업표준단어 형식단어 '연월일시분초') — 도메인
--              연월일시분초D → TIMESTAMP(리포 관례: REG_DT 와 동일 타입). NULL 허용(미시도).
--
-- 멱등: IF NOT EXISTS. 기존 행은 DEFAULT 0 / NULL 로 채워져 "아직 재시도된 적 없음" 을 뜻한다
--       (구 동작에서 이미 여러 번 재시도된 영상도 카운트가 0 부터 시작하지만, 이후로는 상한이 실제로 걸린다).
--
-- PostgreSQL 표준 문법.
-- =============================================================================

ALTER TABLE LS_DATASET_EXPORT ADD COLUMN IF NOT EXISTS RTY_NMTM INT NOT NULL DEFAULT 0;
ALTER TABLE LS_DATASET_EXPORT ADD COLUMN IF NOT EXISTS RTY_DT   TIMESTAMP;

COMMENT ON COLUMN LS_DATASET_EXPORT.RTY_NMTM IS '재시도횟수 - 이 산출 행을 기준으로 회수 잡이 재산출을 트리거한 누적 횟수(산출 결과와 무관하게 증가)';
COMMENT ON COLUMN LS_DATASET_EXPORT.RTY_DT   IS '재시도일시 - 회수 잡이 마지막으로 재산출을 트리거(클레임)한 시각. 동시 노드 중복 클레임 차단 키';
