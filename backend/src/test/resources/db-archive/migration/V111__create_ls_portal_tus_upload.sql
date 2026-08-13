-- =============================================================================
-- V108: 포털 전용 TUS 1.0 재개 가능 업로드 세션 (LS_PORTAL_TUS_ULD)
--
-- 목적: 포털 사용자가 대용량 영상을 청크 단위(PATCH)로 재개 가능하게 업로드하는 세션 상태.
--       관제(내부) LS_TUS_UPLOAD 와 구조를 준용하되 별개 테이블로 완전 분리한다
--       (포털 채널 격리 · 비식별 파이프라인 미연결).
--
-- 표준 물리명(glossary 확정): 업로드=ULD, 길이=LEN, 상태=STTS, 코드=CD, 시각=DT, 순번=SN.
--       예약어 OFFSET 회피 위해 ULD_OFFSET 사용(LS_TUS_UPLOAD 관례 동일).
--
-- 동시성/멱등:
--   - VER(@Version) 낙관적 잠금 — 동시 PATCH 오프셋 충돌 차단.
--   - STTS_CD 원자 전이(IN_PROGRESS → COMPLETED) — 완료 중복 멱등.
--   - EXPRY_DT TTL — 만료 세션 스윕 대상.
--
-- 소유권/격리: PORTAL_USER_NO 소유자 스코프. 리포지토리는 소유자 스코프 파생 쿼리만 노출.
--
-- PostgreSQL 표준 문법 (MariaDB 고유 문법 미사용).
-- =============================================================================

CREATE TABLE IF NOT EXISTS LS_PORTAL_TUS_ULD (
    ULD_ID         UUID         NOT NULL,
    PORTAL_USER_NO VARCHAR(100) NOT NULL,
    ULD_LEN        BIGINT       NOT NULL,          -- 전체 길이(byte, Upload-Length)
    ULD_OFFSET     BIGINT       NOT NULL,          -- 누적 오프셋(byte)
    STTS_CD        VARCHAR(16)  NOT NULL,          -- IN_PROGRESS | COMPLETED | CANCELLED
    FILE_PATH_NM   VARCHAR(500) NOT NULL,          -- 임시 파일 저장 경로(UUID 강제)
    ORGNL_FILE_NM  VARCHAR(255),                   -- 사용자 업로드 원본 파일명(표시용)
    ULD_SN         BIGINT,                          -- 완료 시 생성된 LS_PORTAL_ULD.ULD_SN
    EXPRY_DT       TIMESTAMP    NOT NULL,          -- TTL 만료 시각
    VER            BIGINT       NOT NULL DEFAULT 0,
    REG_DT         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MDFCN_DT       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (ULD_ID)
);

-- 동시 진행 세션 상한 검증(countByPortalUserNoAndSttsCd, STTS_CD='IN_PROGRESS') 스캔용.
--   진행 중 세션만 조회하므로 부분 인덱스로 인덱스 크기·유지비용을 낮춘다(완료/취소 행 제외).
CREATE INDEX IF NOT EXISTS IDX_LPTU_USER_STTS
    ON LS_PORTAL_TUS_ULD (PORTAL_USER_NO)
    WHERE STTS_CD = 'IN_PROGRESS';

-- 만료 세션 스윕(findExpired: STTS_CD='IN_PROGRESS' AND EXPRY_DT < now) 스캔용.
--   진행 중 세션만 만료 대상이므로 부분 인덱스로 좁힌다(PostgreSQL partial index).
CREATE INDEX IF NOT EXISTS IDX_LPTU_EXPRY
    ON LS_PORTAL_TUS_ULD (EXPRY_DT)
    WHERE STTS_CD = 'IN_PROGRESS';
