-- V59: TUS 1.0 재개 가능 업로드 세션 테이블 (CVAT 포팅 Phase 3 — 관리 화면 대용량 영상 적재)
-- 신규 미배포 LS_* 전용 테이블 → CREATE TABLE 직접 작성 (PostgreSQL 표준 문법)
-- 행안부 공통표준: 신규 도메인 — TUS 프로토콜 헤더 매핑 컬럼명 사용.
--
-- 동시성/멱등 방어:
--   * UPLOAD_OFFSET (예약어 OFFSET 회피) — 조건부 UPDATE(WHERE UPLOAD_OFFSET=expected) 로 동시 PATCH 직렬화.
--   * VERSION — JPA @Version 낙관적 잠금 (HIGH-1 동시 PATCH 오프셋 충돌 409).
--   * STATUS 원자적 전이(WHERE STATUS='IN_PROGRESS') — 완료 중복(마지막 청크 재전송) 멱등 보장 (HIGH-3).
--   * EXPIRES_AT — TTL(+24h) + Quartz 정리 잡 + 만료 HEAD/PATCH 410 (HIGH-5).
CREATE TABLE IF NOT EXISTS LS_TUS_UPLOAD (
    UPLOAD_ID     UUID         NOT NULL,                          -- TUS 업로드 세션 식별자 (PK, Location 헤더에 노출)
    USER_NO       VARCHAR(64)  NOT NULL,                          -- 세션 소유자 (토큰 sub) — HEAD/PATCH/DELETE 본인 검증 (HIGH-8)
    UPLOAD_LENGTH BIGINT       NOT NULL,                          -- 전체 파일 크기 (Upload-Length)
    UPLOAD_OFFSET BIGINT       NOT NULL DEFAULT 0,                -- 현재까지 기록된 바이트 (Upload-Offset)
    STATUS        VARCHAR(16)  NOT NULL DEFAULT 'IN_PROGRESS',    -- IN_PROGRESS / COMPLETED / EXPIRED
    FILE_PATH     VARCHAR(500) NOT NULL,                          -- 임시 파일 절대경로 (UUID 강제 — filename 은 표시용만, HIGH-7)
    FILE_NAME     VARCHAR(255),                                  -- 표시용 원본 파일명 (Upload-Metadata filename)
    VMS_CLIP_ID   VARCHAR(64),                                   -- 완료 시 LS_DATA_RAW 합류 메타
    CCTV_ID       VARCHAR(64),
    EVENT_TYPE_CD VARCHAR(32),
    LOCAL_GOV_CD  VARCHAR(10),
    PRVC_TYPE_CD  VARCHAR(8),
    CAPTURED_AT   TIMESTAMP,
    RAW_SN        BIGINT,                                        -- 완료 시 생성된 LS_DATA_RAW.RAW_SN (멱등 응답용)
    EXPIRES_AT    TIMESTAMP    NOT NULL,                          -- 만료 시각 (생성 +24h)
    VERSION       BIGINT       NOT NULL DEFAULT 0,                -- 낙관적 잠금 (@Version)
    REG_DT        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MDFCN_DT      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (UPLOAD_ID)
);

-- 사용자별 진행 중 세션 상한(3개) 조회 + 만료 정리 잡 스캔용 인덱스.
CREATE INDEX IF NOT EXISTS IDX_LTU_USER_STATUS ON LS_TUS_UPLOAD (USER_NO, STATUS);
CREATE INDEX IF NOT EXISTS IDX_LTU_EXPIRES ON LS_TUS_UPLOAD (STATUS, EXPIRES_AT);
