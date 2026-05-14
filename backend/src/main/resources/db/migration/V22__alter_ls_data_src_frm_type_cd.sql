-- ============================================================
-- Phase 2: 프레임 출처 구분 (RAW: 원본 영상, DEID: 비식별 영상).
--   - V2 정책 "영상 2벌 보관" — 원본/비식별 영상 양쪽에서 프레임 추출 후 LS_DATA_SRC 에 별도 row 로 적재.
--   - 기존 row 는 DEFAULT 'RAW' 로 자동 분류 (V1 호환).
--   - UK 갱신: (RAW_SN, FRAME_NO) → (RAW_SN, FRAME_NO, FRM_TYPE_CD)
--     · 동일 (RAW_SN, FRAME_NO) 에 RAW/DEID 각각 1 row 가능.
--     · 기존 row 들은 자연스럽게 (RAW_SN, FRAME_NO, 'RAW') 로 호환 — UK 충돌 없음.
-- klid_system 공유 DB 영향: 단일 컬럼 ADD + UK 재정의 + 인덱스 1건 (관제서버팀 통보 필요).
-- ============================================================
ALTER TABLE LS_DATA_SRC
    ADD COLUMN IF NOT EXISTS FRM_TYPE_CD VARCHAR(8) NOT NULL DEFAULT 'RAW';

-- 기존 UK 제거 후 신규 UK 추가. H2 / MariaDB 양쪽에서 'IF EXISTS' / 'IF NOT EXISTS' 지원.
ALTER TABLE LS_DATA_SRC DROP CONSTRAINT IF EXISTS UK_LS_DATA_SRC_RAW_FRAME;
ALTER TABLE LS_DATA_SRC ADD CONSTRAINT UK_LS_DATA_SRC_RAW_FRAME_TYPE UNIQUE (RAW_SN, FRAME_NO, FRM_TYPE_CD);

CREATE INDEX IF NOT EXISTS IX_LS_DATA_SRC_RAW_FRM ON LS_DATA_SRC (RAW_SN, FRM_TYPE_CD);
