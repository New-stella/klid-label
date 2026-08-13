-- 폴리곤 오토라벨(온라인) — SAM 분할 대상 YOLO 박스 개수 상한 설정 키 시드.
--
-- autolabel.polygon.max-boxes : POLYGON 오토라벨 시 SAM box-prompt 분할을 시도하는 박스 개수 상한.
--   - STNG_TYPE_CD = NUMBER (정수)
--   - 범위 1 ~ 100 (ConfigKeys.NUMBER_RANGE), 기본값 20
--   - YOLO 가 상한 초과 검출 시 상한까지만 SAM 분할 → 처리 예산/자원 소모 제한(HIGH #1/MED #5, CWE-770/400)
--   - SystemConfigService.getInt 로 조회, AutolabelOnlineService(폴리곤 경로)가 적용
--   - 조회 실패 시 서비스가 기본값 20 으로 폴백(fail-safe) — 시드 없어도 동작
--
-- 컬럼명 STNG_* 은 V61(용어 표준 rename: CONFIG_KEY→STNG_KEY 등) 이후 물리명 정합.
-- 멱등(idempotent): ON CONFLICT DO NOTHING — 이미 존재하면 NOOP (PostgreSQL).
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.

INSERT INTO LS_SYSTEM_CONFIG (STNG_KEY, STNG_VALUE, STNG_TYPE_CD, EXPLN, MDFR_ID) VALUES
    ('autolabel.polygon.max-boxes', '20', 'NUMBER',
     '폴리곤 오토라벨 SAM 분할 박스 상한 (1~100)', 'SYSTEM')
ON CONFLICT (STNG_KEY) DO NOTHING;
