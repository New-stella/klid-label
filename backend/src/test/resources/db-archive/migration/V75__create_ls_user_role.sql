-- ============================================================
-- V75 — LS_USER_ROLE 신설 + 기존 저작도구 역할 이관 (역할 분리 리팩토링 Phase 1)
--
-- 배경: 저작도구 고유 역할(REVIEWER/WORKER/PORTAL_USER)이 관제 소유 공유 테이블
--   MNG_ACCT_USER_AUTHRT 에 저장돼 있다. 저작도구가 자신의 역할 모델을 독립적으로
--   소유·진화시킬 수 있도록 저작도구 전용 LS_USER_ROLE 로 분리한다. 본 Phase 는
--   테이블/엔티티/리포 + 데이터 이관까지만 수행하며, 읽기/쓰기 경로 전환은 후속
--   Phase 2~3 에서 진행한다(이중 기록 구간 없이 단계적 전환).
--
-- 설계:
--   * USER_NO 단일 PK — MNG_ACCT_USER.USER_NO 를 ID 로만 참조(FK 미설정,
--     Aggregate 간 ID 참조 원칙). 사용자당 저작도구 역할 1개를 보관한다.
--   * ROLE_CD 화이트리스트(REVIEWER/WORKER/PORTAL_USER) — 관제 고유 역할
--     (LEARN_MANAGER 등)은 이관 대상에서 제외한다.
--   * MNG_* 스키마는 읽기만 — 변경(ALTER/DROP) 없음.
--
-- 멱등: CREATE TABLE IF NOT EXISTS + INSERT ... ON CONFLICT DO NOTHING 으로
--   재실행해도 중복/오류 없이 안전하다.
-- ============================================================

CREATE TABLE IF NOT EXISTS LS_USER_ROLE (
    USER_NO  BIGINT       NOT NULL,                          -- 사용자 번호 (MNG_ACCT_USER.USER_NO 참조, ID only)
    ROLE_CD  VARCHAR(32)  NOT NULL,                           -- 저작도구 역할: REVIEWER | WORKER | PORTAL_USER
    REG_DT   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP, -- 등록 일시
    UPD_DT   TIMESTAMP,                                       -- 역할 변경 일시 (nullable)
    PRIMARY KEY (USER_NO)
);

-- 기존 저작도구 역할만 이관 — 관제 고유 역할(LEARN_MANAGER 등)은 IN 절 화이트리스트로 제외.
-- 동일 USER_NO 에 복수 저작도구 역할이 있으면 DISTINCT ON + 우선순위 ORDER BY 로
-- 권한이 가장 강한 역할(REVIEWER > WORKER > PORTAL_USER)을 결정적으로 보존한다
-- (물리 저장 순서에 의존하지 않음 — correct-by-construction).
INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT)
SELECT DISTINCT ON (USER_NO) USER_NO, AUTHRT_CD, REG_DT
  FROM MNG_ACCT_USER_AUTHRT
 WHERE AUTHRT_CD IN ('REVIEWER', 'WORKER', 'PORTAL_USER')
 ORDER BY USER_NO,
          CASE AUTHRT_CD WHEN 'REVIEWER' THEN 1 WHEN 'WORKER' THEN 2 WHEN 'PORTAL_USER' THEN 3 END
ON CONFLICT (USER_NO) DO NOTHING;
