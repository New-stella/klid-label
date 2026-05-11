-- LS_BATCH_PROC_LOG: 배치 파이프라인 단계별 처리 이력
-- RAW_SN PK (1 row per video) — upsert 방식으로 현재 단계 유지
CREATE TABLE IF NOT EXISTS LS_BATCH_PROC_LOG (
    RAW_SN      BIGINT          NOT NULL COMMENT '원시 영상 일련번호 (LS_DATA_RAW 참조)',
    STAGE_CD    VARCHAR(32)     NOT NULL COMMENT '배치 단계 코드 (BatchStage enum name)',
    STARTED_AT  DATETIME(6)     NOT NULL COMMENT '최초 단계 시작 시각',
    UPDATED_AT  DATETIME(6)     NOT NULL COMMENT '마지막 단계 전이 시각',
    RETRY_CNT   INT             NOT NULL DEFAULT 0 COMMENT '재시도 누적 횟수',
    ERR_MSG     VARCHAR(500)    NULL COMMENT '실패 시 예외 클래스명',
    CONSTRAINT PK_LS_BATCH_PROC_LOG PRIMARY KEY (RAW_SN)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='배치 파이프라인 단계 처리 로그';

CREATE INDEX IF NOT EXISTS IDX_BATCH_PROC_LOG_UPDATED
    ON LS_BATCH_PROC_LOG (UPDATED_AT DESC);
