-- ============================================================
-- TEST-ONLY 시드 — 역할 분리 Phase 3 인가 정합화.
--
-- 본 파일은 src/test/resources 에만 존재하므로 운영 빌드 Flyway 에는 포함되지 않는다
-- (테스트 클래스패스에서만 db/migration 으로 병합되어 적용). 운영 마이그레이션과 무관.
--
-- 배경: Phase 3 이후 INTERNAL 토큰의 인가 역할은 JWT role 클레임이 아니라
--   LS_USER_ROLE(USER_NO→ROLE_CD) 조회로 해석된다. @Sql("/db/test-data.sql") 을 적재하지
--   않는 @SpringBootTest(@WebMvcTest 류 MockMvc 통합테스트)들은 토큰 sub(숫자)에 대응하는
--   LS_USER_ROLE 시드가 없으면 fail-closed 로 403 이 되어 깨진다. 이를 막기 위해 전체 테스트
--   컨텍스트가 공유하는 PostgreSQL 컨테이너에 canonical 역할 매핑을 1회 시드한다.
--
-- 매핑(테스트 전반에서 불변 — 동일 userNo 는 항상 동일 역할):
--   1    → REVIEWER
--   2    → WORKER
--   100  → WORKER
--   101  → WORKER
--   200  → WORKER   (test-data.sql 의 USE_YN='N' 워커와 동일 번호)
--   1001 → REVIEWER (LabelMaster/LabelAttr 컨트롤러 테스트)
--   2001 → WORKER   (LabelMaster/LabelAttr 컨트롤러 테스트)
--   770002 → LEARN_MANAGER (관제 고유 역할 — 저작도구 Role enum 에 없음. enum 불일치 fail-closed 403 검증용)
--
-- USER_NO 단일 PK + ON CONFLICT DO NOTHING 으로 멱등. LS_ACNT_USER FK 미설정(ID 참조)이라
-- MNG 행 없이도 인가 역할 해석에 충분하다.
-- ============================================================

INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES
  (1,    'REVIEWER', CURRENT_TIMESTAMP),
  (2,    'WORKER',   CURRENT_TIMESTAMP),
  (100,  'WORKER',   CURRENT_TIMESTAMP),
  (101,  'WORKER',   CURRENT_TIMESTAMP),
  (200,  'WORKER',   CURRENT_TIMESTAMP),
  (1001, 'REVIEWER', CURRENT_TIMESTAMP),
  (2001, 'WORKER',   CURRENT_TIMESTAMP),
  (770002, 'LEARN_MANAGER', CURRENT_TIMESTAMP)
ON CONFLICT (USER_NO) DO NOTHING;
