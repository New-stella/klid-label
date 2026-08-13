-- V134 — LS_TASK_ASSIGNMENT 낙관적 잠금 컬럼(VER) 추가 (D-ISSUE-02).
--
-- [결함] 재배정(PATCH /v1/assignments/{id})은 기존 row 를 UPDATE 하므로
--        UK_LS_TASK_ASSIGNMENT(RAW_DATA_ID, USER_NO, TASK_TYPE_CD) 위반이 발생하지 않아
--        AssignmentService 의 DataIntegrityViolationException → CONFLICT 방어가 발화하지 않았고,
--        "현재 배정된 작업자와 동일" 가드도 동시 요청이 모두 커밋 전 값을 읽어 통과했다.
--        실측: 4병렬 재배정 → 4건 전부 200, LS_TASK_ASSIGN_HISTORY 4행·LS_TASK_EVENT_LOG 4행 중복.
--
-- [수정] @Version 낙관적 잠금으로 DB 가 동시 UPDATE 를 직렬화한다. 앱은 2노드 Active-Active
--        배포라 JVM 락(synchronized/ReentrantLock)은 방어가 되지 않으므로 DB 수준으로만 해결한다.
--
-- [표준용어·표준도메인]
--   물리명 VER  = 표준단어 '버전'(사업표준단어·공통표준단어 양쪽 등재, 영문약어 VER).
--   타입   BIGINT NOT NULL DEFAULT 0 = 리포 내 확정 선례와 동일
--          (LS_RAW_DATA_STATUS · LS_PORTAL_TUS_ULD · LS_DATA_ISSUE · LS_EVNT_ANNO_REVIEW · LS_TUS_UPLOAD).
--
-- 멱등: IF NOT EXISTS + 기존 행 백필(NULL → 0). Flyway 재실행/부분 적용 상태에서도 안전.

ALTER TABLE LS_TASK_ASSIGNMENT
    ADD COLUMN IF NOT EXISTS VER BIGINT NOT NULL DEFAULT 0;

-- 기존 행 백필 — 컬럼 추가 시 DEFAULT 0 이 채워지지만, 과거에 NULL 허용으로 선반영된 환경까지 방어한다.
UPDATE LS_TASK_ASSIGNMENT SET VER = 0 WHERE VER IS NULL;
