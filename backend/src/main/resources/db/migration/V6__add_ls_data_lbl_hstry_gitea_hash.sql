-- Phase 8: Gitea 자동 커밋 + diff/롤백 + 장애 fallback 큐.
-- DB 설계서 §3.6 LS_DATA_LBL_HSTRY (라벨 변경 이력) — GITEA_CMT_HASH 컬럼 추가.
--
-- ⚠ 협의 필요 (관제서버팀 / DBA):
--   - 운영 klid_system 의 LS_DATA_LBL_HSTRY 가 이미 존재하면 본 V6 의 ALTER 만 적용 필요.
--     아래 ALTER TABLE / CREATE INDEX IF NOT EXISTS 는 양쪽(존재/미존재) 모두에서 안전하게 동작.
--   - GITEA_CMT_HASH 길이 40 (Git SHA-1 hex). 향후 SHA-256 (Git 차세대) 전환 시 64 로 확장 협의 필요.
--   - LABELS_JSON_SNAPSHOT 은 롤백·복원용 (옵션이지만 보유). H2 는 CLOB, MariaDB 는 TEXT 매핑.
--
-- H2 + MariaDB 호환을 위해 CLOB 표준 SQL 타입 + IF NOT EXISTS 사용.

-- ============================================================
-- §3.6 LS_DATA_LBL_HSTRY : 라벨 변경 이력 + Gitea 커밋 추적
--   - LBL_SN  은 단일 라벨 변경 시 사용 (nullable — 영상/프레임 단위 일괄 커밋도 가능).
--   - SRC_SN  은 프레임 단위 묶음 커밋의 식별 (필수).
--   - GITEA_CMT_HASH 는 commit 성공 시 채워짐. 장애 시 재시도 큐에서 채워질 수 있음.
-- ============================================================

CREATE TABLE IF NOT EXISTS LS_DATA_LBL_HSTRY (
    LBL_HSTRY_SN          BIGINT          NOT NULL AUTO_INCREMENT,
    LBL_SN                BIGINT,
    SRC_SN                BIGINT          NOT NULL,
    GITEA_CMT_HASH        VARCHAR(40),
    LABELS_JSON_SNAPSHOT  LONGTEXT,
    REGISTERED_AT         TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    REGISTERED_USER_NO    VARCHAR(50),
    PRIMARY KEY (LBL_HSTRY_SN)
);

-- 운영 DB 에 컬럼이 누락된 경우를 위한 ALTER (이미 있으면 NOOP — H2/MariaDB 모두 IF NOT EXISTS 지원)
-- HIGH-6 fix: SRC_SN 은 CREATE TABLE 의 NOT NULL 제약과 일치시킴 (Entity LsDataLblHstry.srcSn nullable=false).
--   기존 row 보존을 위해 DEFAULT 0 지정 — 신규 row 는 application 레벨에서 srcSn null 검증 (Entity 생성자) 으로 차단.
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS GITEA_CMT_HASH VARCHAR(40);
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS LABELS_JSON_SNAPSHOT LONGTEXT;
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS REGISTERED_USER_NO VARCHAR(50);
ALTER TABLE LS_DATA_LBL_HSTRY ADD COLUMN IF NOT EXISTS SRC_SN BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS IDX_LS_DATA_LBL_HSTRY_GITEA ON LS_DATA_LBL_HSTRY (GITEA_CMT_HASH);
CREATE INDEX IF NOT EXISTS IDX_LS_DATA_LBL_HSTRY_SRC   ON LS_DATA_LBL_HSTRY (SRC_SN, REGISTERED_AT);
