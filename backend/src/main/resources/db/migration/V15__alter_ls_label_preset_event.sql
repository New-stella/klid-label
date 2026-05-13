-- LS_LABEL_PRESET 에 이벤트 타입 매핑 컬럼 추가 (V1.8 — 이벤트 1:1 프리셋 매핑)
-- MariaDB UNIQUE: NULL 다수 row 허용 + 비-NULL 은 1개 → 미매핑 프리셋 N개 + 매핑된 프리셋 이벤트별 1개.
-- 호환성: H2 MySQL 모드는 한 ALTER 문에서 다중 절(ADD COLUMN + ADD CONSTRAINT) 을 지원하지 않으므로 분리한다.
ALTER TABLE LS_LABEL_PRESET
    ADD COLUMN EVNT_TYPE_CD VARCHAR(32) NULL COMMENT '매핑 이벤트 타입 (EVT_FALL 등). null=미매핑';

ALTER TABLE LS_LABEL_PRESET
    ADD CONSTRAINT UK_LS_LABEL_PRESET_EVNT UNIQUE (EVNT_TYPE_CD);
