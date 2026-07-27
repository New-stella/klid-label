-- V131: 웹훅 가드 공유 저장소 — 서명 nonce(replay 방지) + 인증 실패 카운터(rate limit).
--
-- 배경 (1차 전수 검증 2026-07-25):
--  - A-ISSUE-12: replay 방지가 timestamp 윈도우뿐이라 유효 콜백을 캡처하면 윈도우 내 무제한 재전송이 통과.
--  - A-ISSUE-14: rate limit 카운터가 JVM-local 이라 2노드 Active-Active 에서 임계가 2배, 재기동 시 소실.
-- 두 방어 모두 노드 간 공유 상태가 필요하므로, 신규 인프라 도입 없이 control DB(LS_WEBHOOK_IDEMPOTENCY 와
-- 동일 축)에 경량 테이블 2개를 둔다.
--
-- 표준용어·표준도메인 정합:
--   WHK(웹훅) · SIGN(서명) · HASH(해시) · USE(사용) · PATH(경로) · NM(명) · EXPD(만료) · DT(일시)
--   CALL(호출) · IP · ADDR(주소) · BGNG(시작) · FAIL(실패) · NMTM(횟수) · REG/MDFCN(등록/수정)
--   도메인: IP주소V45 → VARCHAR(45) / 명V200 → VARCHAR(200) / 수I11 → INTEGER / 연월일시분초D → TIMESTAMP
--   SIGN_HASH VARCHAR(64) 은 기존 SHA-256 hex 컬럼(LS_LABEL_VERSION.VERSION_HASH,
--   LS_DATASET_EXPORT.CONTENT_HASH)과 동일 폭.

-- ─────────────────────────────────────────────────────────────
-- 1) 서명 nonce — 1회성 소비 원장
--    필터는 트랜잭션 밖에서 동작하고, PostgreSQL 은 UNIQUE 위반이 트랜잭션 전체를 abort(25P02) 시킨다.
--    그래서 애플리케이션은 INSERT ... ON CONFLICT DO NOTHING + updateCount 판정으로 예외 경로 자체를 없앤다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS LS_WHK_SIGN_USE (
    SIGN_HASH    VARCHAR(64)  NOT NULL,   -- (경로, timestamp, 서명)의 SHA-256 hex
    WHK_PATH_NM  VARCHAR(200) NOT NULL,   -- 대상 웹훅 경로 (운영 추적용)
    EXPD_DT      TIMESTAMP    NOT NULL,   -- 만료일시 — 지난 행은 정리 대상
    REG_DT       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_LS_WHK_SIGN_USE PRIMARY KEY (SIGN_HASH)
);

CREATE INDEX IF NOT EXISTS IDX_LS_WHK_SIGN_USE_EXPD ON LS_WHK_SIGN_USE (EXPD_DT);

COMMENT ON TABLE  LS_WHK_SIGN_USE             IS '웹훅 서명 사용 원장 — 동일 서명 재전송(replay) 차단용 1회성 소비 기록.';
COMMENT ON COLUMN LS_WHK_SIGN_USE.SIGN_HASH   IS '서명 해시 — (경로, X-Timestamp, X-Signature) SHA-256 hex.';
COMMENT ON COLUMN LS_WHK_SIGN_USE.WHK_PATH_NM IS '웹훅 경로명 — 운영 추적용.';
COMMENT ON COLUMN LS_WHK_SIGN_USE.EXPD_DT     IS '만료일시 — timestamp 허용창을 덮는 보존기간. 경과 시 정리.';
COMMENT ON COLUMN LS_WHK_SIGN_USE.REG_DT      IS '등록일시.';

-- ─────────────────────────────────────────────────────────────
-- 2) 인증 실패 카운터 — 분 단위 고정 윈도우 집계 (노드 공유)
--    앱은 1차로 JVM-local 카운터를 쓰고(무인증 요청 1건당 DB 왕복이 곧 pre-auth DoS 표면),
--    2차로 본 테이블 집계를 확인해 시스템 전체 기준을 보장한다.
-- ─────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS LS_WHK_FAIL_NMTM (
    CALL_IP_ADDR VARCHAR(45) NOT NULL,    -- 호출 IP 주소 (IPv6 포함)
    BGNG_DT      TIMESTAMP   NOT NULL,    -- 집계 윈도우 시작일시 (분 단위 버킷)
    FAIL_NMTM    INTEGER     NOT NULL DEFAULT 0,  -- 누적 실패 횟수
    EXPD_DT      TIMESTAMP   NOT NULL,    -- 만료일시 — 지난 행은 정리 대상
    REG_DT       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MDFCN_DT     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_LS_WHK_FAIL_NMTM PRIMARY KEY (CALL_IP_ADDR, BGNG_DT)
);

CREATE INDEX IF NOT EXISTS IDX_LS_WHK_FAIL_NMTM_EXPD ON LS_WHK_FAIL_NMTM (EXPD_DT);

COMMENT ON TABLE  LS_WHK_FAIL_NMTM              IS '웹훅 인증 실패 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.CALL_IP_ADDR IS '호출 IP 주소 — 신뢰 프록시 기반으로 산출된 클라이언트 IP.';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.BGNG_DT      IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.FAIL_NMTM    IS '해당 윈도우의 누적 인증 실패 횟수.';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.EXPD_DT      IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.REG_DT       IS '등록일시.';
COMMENT ON COLUMN LS_WHK_FAIL_NMTM.MDFCN_DT     IS '수정일시.';
