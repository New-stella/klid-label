-- FEAT-007 (SFR-08-03) 라벨링 정밀도 — 경계 세밀함 설정 키 시드.
--
-- POLYGON_SIMPLIFY_TOLERANCE : Douglas-Peucker 단순화 epsilon(px).
--   - CONFIG_TYPE = DECIMAL (소수 허용 — 기존 NUMBER 정수 키와 구분)
--   - 범위 0.0 ~ 50.0 (ConfigKeys.DECIMAL_RANGE), 기본값 1.0
--   - 값이 클수록 폴리곤 점이 더 많이 제거되어 경계가 거칠어짐(=세밀함 낮춤)
--   - SystemConfigService.getDouble 으로 조회, Sam2TrackService 가 적용
--
-- 인식 민감도는 기존 YOLO_CONF_THRESHOLD 키가 담당(V17/V19 시드) — 재사용.
--
-- 멱등(idempotent): ON CONFLICT DO NOTHING — 이미 존재하면 NOOP (PostgreSQL).
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.

INSERT INTO LS_SYSTEM_CONFIG (CONFIG_KEY, CONFIG_VALUE, CONFIG_TYPE, DESCRIPTION, UPDATED_BY) VALUES
    ('POLYGON_SIMPLIFY_TOLERANCE', '1.0', 'DECIMAL',
     '폴리곤 경계 단순화 epsilon px (0.0~50.0, Douglas-Peucker)', 'SYSTEM')
ON CONFLICT (CONFIG_KEY) DO NOTHING;
