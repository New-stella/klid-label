-- Phase 12: 시스템 설정 (Observability + 운영 파라미터).
-- DB 설계서 §5.2 LS_SYSTEM_CONFIG, §5A.4 시스템 설정 정책.
--
-- ⚠ 협의 필요 (관제서버팀 / DBA):
--   - 운영 klid_system 에 LS_SYSTEM_CONFIG 가 없으면 신규 CREATE.
--   - 4개 키 시드(ON CONFLICT DO NOTHING) 는 IF NOT EXISTS 효과 (이미 존재하면 NOOP).
--   - 본 테이블은 저작도구 전용 운영 파라미터만 보유 — 다른 도메인 설정 추가 금지.
--
-- PostgreSQL: 표준 SQL 타입 + IF NOT EXISTS / ON CONFLICT DO NOTHING 사용.

-- ============================================================
-- LS_SYSTEM_CONFIG : 시스템 설정 키-값 저장소
--   - CONFIG_KEY     : PK (VARCHAR 100). 화이트리스트 4개 키만 등록·갱신 허용.
--   - CONFIG_VL      : 값 (VARCHAR 500). CONFIG_TYPE_CD 에 따라 검증. (행안부 표준단어 값=VL)
--   - CONFIG_TYPE_CD : NUMBER / STRING / JSON / BOOLEAN. (유형→코드화 *_CD)
--   - EXPLN          : 운영자용 설명. (행안부 표준단어 설명=EXPLN)
--   - MDFR_ID        : 마지막 갱신자 (REVIEWER 의 USER_NO 또는 'SYSTEM'). (수정자=MDFR)
--   - MDFCN_DT       : 갱신 시각. (수정일시=MDFCN_DT)
-- ============================================================

CREATE TABLE IF NOT EXISTS LS_SYSTEM_CONFIG (
    CONFIG_KEY      VARCHAR(100)    NOT NULL,
    CONFIG_VL       VARCHAR(500),
    CONFIG_TYPE_CD  VARCHAR(20)     NOT NULL,
    EXPLN           VARCHAR(500),
    MDFR_ID         VARCHAR(50),
    MDFCN_DT        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (CONFIG_KEY)
);

-- 4개 시드 (V1.4 §5A.4 — 편집 가능 키 화이트리스트)
INSERT INTO LS_SYSTEM_CONFIG (CONFIG_KEY, CONFIG_VL, CONFIG_TYPE_CD, EXPLN, MDFR_ID) VALUES
    ('FFMPEG_THREADS',     '2',  'NUMBER', 'FFmpeg 워커 수 (1~16)',          'SYSTEM'),
    ('FFMPEG_OUTPUT_FPS',  '1',  'NUMBER', 'FFmpeg 추출 FPS (분당, 1~30)',    'SYSTEM'),
    ('BATCH_INTERVAL_SEC', '60', 'NUMBER', '배치 트리거 간격 (초, 10~3600)',  'SYSTEM'),
    ('BATCH_CONCURRENCY',  '1',  'NUMBER', '동시 배치 잡 수 (1=직렬, 1~10)',  'SYSTEM')
ON CONFLICT (CONFIG_KEY) DO NOTHING;
