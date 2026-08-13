-- Phase 1 (YOLO 정확도 개선): YOLO 추론 파라미터 3개 키 시드.
-- V11 의 LS_SYSTEM_CONFIG 화이트리스트 정책에 따라 운영 UI 에서 조정 가능.
--
-- 값 범위 (ConfigKeys.NUMBER_RANGE):
--   - YOLO_CONF_THRESHOLD : 25~80  (사용 시 /100.0 → 0.25~0.80)
--   - YOLO_IMGSZ          : 320~1920 (px)
--   - YOLO_IOU            : 30~80  (사용 시 /100.0 → 0.30~0.80)
--
-- 멱등(idempotent): INSERT IGNORE — 이미 존재하면 NOOP.
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.

INSERT INTO LS_SYSTEM_CONFIG (CONFIG_KEY, CONFIG_VL, CONFIG_TYPE_CD, EXPLN, MDFR_ID) VALUES
    ('YOLO_CONF_THRESHOLD', '40',   'NUMBER', 'YOLO 신뢰도 임계값 백분율 (25~80, 사용 시 /100)', 'SYSTEM'),
    ('YOLO_IMGSZ',          '1280', 'NUMBER', 'YOLO 추론 입력 해상도 px (320~1920)',              'SYSTEM'),
    ('YOLO_IOU',            '50',   'NUMBER', 'YOLO NMS IoU 임계값 백분율 (30~80, 사용 시 /100)', 'SYSTEM')
ON CONFLICT (CONFIG_KEY) DO NOTHING;
