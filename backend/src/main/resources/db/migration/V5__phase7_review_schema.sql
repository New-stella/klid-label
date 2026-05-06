-- Phase 7: 검수 워크플로우 (REVIEWER 승인/반려).
-- 운영 klid_system 적용 시 관제서버팀 사전 협의 필수 (DB 설계서 §5A.6 LS_DATA_ISSUE 신규).
-- H2 + MariaDB 호환을 위해 IF NOT EXISTS + 표준 SQL 타입만 사용.
--
-- ⚠ 협의 필요: LS_DATA_ISSUE 는 저작도구 신규 테이블이며 운영 DB 미존재.
--   적용 전 관제서버팀 / DBA 와 컬럼 정의 (특히 ISSUE_REASON 길이 1000) · INDEX 범위 합의 필요.
--
-- LS_PJT_DATA_STTS 의 낙관적 잠금용 VERSION 컬럼 추가 (CWE-362 동시성 보호).
-- 운영 DB 에는 ALTER TABLE 로 적용 → 기존 row 는 DEFAULT 0 으로 채워짐.

-- ============================================================
-- §5A.6 LS_DATA_ISSUE : 검수 반려 사유 (계층형 — UP_DATA_ISSUE_SN 자기참조)
--   - 좌표 컬럼 없음 (V1 §5A.6 명시)
--   - VIDEO_ID 는 LS_DATA_RAW.RAW_SN 을 참조 (영상 단위 반려)
-- ============================================================

CREATE TABLE IF NOT EXISTS LS_DATA_ISSUE (
    DATA_ISSUE_SN       BIGINT          NOT NULL AUTO_INCREMENT,
    UP_DATA_ISSUE_SN    BIGINT,
    VIDEO_ID            BIGINT          NOT NULL,
    ISSUE_REASON        VARCHAR(1000),
    REPORTED_USER_NO    VARCHAR(50),
    REGISTERED_AT       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (DATA_ISSUE_SN)
);

CREATE INDEX IF NOT EXISTS IX_LS_DATA_ISSUE_VIDEO ON LS_DATA_ISSUE (VIDEO_ID);
CREATE INDEX IF NOT EXISTS IX_LS_DATA_ISSUE_UP ON LS_DATA_ISSUE (UP_DATA_ISSUE_SN);
CREATE INDEX IF NOT EXISTS IX_LS_DATA_ISSUE_REPORTER ON LS_DATA_ISSUE (REPORTED_USER_NO);

-- ============================================================
-- LS_PJT_DATA_STTS : @Version (낙관적 잠금) 컬럼 추가
--   - 동시 두 REVIEWER 가 같은 영상을 승인 시도할 때 1건만 성공 (다른 1건 → 409 CONFLICT)
--   - H2/MariaDB 양쪽 ALTER TABLE 호환 ; 기본값 0 으로 기존 row 채움
-- ============================================================

ALTER TABLE LS_PJT_DATA_STTS ADD COLUMN IF NOT EXISTS VERSION BIGINT NOT NULL DEFAULT 0;
