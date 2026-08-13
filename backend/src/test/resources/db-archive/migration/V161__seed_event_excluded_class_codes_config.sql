-- 이벤트 필터 옵션 제외 대분류 코드 설정 키 시드 (Phase 1 — 소스 상수 → 설정 화면 이관).
--
-- eventtype.excluded-class-codes : EventTypeService.filterOptions() 에서 제외할 관제 대분류
--   코드(EVNT_CLS_CD) 목록.
--   - STNG_TYPE_CD = JSON (문자열 배열)
--   - 기본값 ["08"] (배회) — 구 EventTypeService.IGNORE_CLASS_CD 상수와 동일 동작 유지
--   - 각 원소는 2자리 숫자(\d{2}), 최대 20개 (SystemConfigService 검증, CWE-20/770)
--   - SystemConfigService.getStringSet 으로 조회, REVIEWER 가 /v1/manage/configs 에서 편집
--   - 조회 실패 시 EventTypeService 가 기본값 08 로 폴백(fail-safe) — 시드 없어도 동작
--
-- 신규 컬럼/테이블 없음(기존 LS_SYSTEM_CONFIG INSERT 만) — 표준용어/표준도메인 검증 대상 아님.
-- 멱등(idempotent): ON CONFLICT DO NOTHING — 이미 존재하면 NOOP (PostgreSQL).
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.

INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID) VALUES
    ('eventtype.excluded-class-codes', '["08"]', 'JSON',
     '이벤트 필터 옵션에서 제외할 대분류 코드 목록(기본 08=배회)', 'SYSTEM')
ON CONFLICT (STNG_KEY) DO NOTHING;
