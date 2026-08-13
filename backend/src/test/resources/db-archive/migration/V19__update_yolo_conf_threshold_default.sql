-- Phase 1 (YOLO 정확도 개선): YOLO_CONF_THRESHOLD 기본값을 40 → 25 (=0.25) 하향.
-- 작은 객체 검출율 향상이 목적. 운영자가 이미 다른 값으로 수정했으면 보존 (멱등).
--
-- 정책:
--   - V17 에서 시드한 기본값 40 만 25 로 교체
--   - 운영자가 40 이외 값으로 수정한 경우 그대로 유지 (덮어쓰지 않음)
--   - LS_SYSTEM_CONFIG 의 화이트리스트 범위(25~80) 내 유효값
--
-- LS_* 접두사 저작도구 전용 테이블 변경이므로 관제서버팀 협의 면제.

UPDATE LS_SYSTEM_CONFIG
   SET CONFIG_VL = '25',
       MDFR_ID = 'SYSTEM'
 WHERE CONFIG_KEY = 'YOLO_CONF_THRESHOLD'
   AND CONFIG_VL = '40';
