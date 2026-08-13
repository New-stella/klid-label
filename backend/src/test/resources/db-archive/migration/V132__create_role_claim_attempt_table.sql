-- V132: 권한 자가부여(role-claim) 시도 횟수 공유 원장 — 무차별 대입(CWE-307) 억제용.
--
-- 배경 (1차 전수 검증 2026-07-25, A-ISSUE-18):
--  - 자가부여 rate limit 이 호출자(sub) 단위 JVM-local 카운터라 2노드 Active-Active 에서 임계가 2배가 되고
--    재기동 시 소실된다.
--  - 계정 축만 있어 무권한 계정 A/B/C 를 번갈아 쓰면 실효 시도량이 선형 증폭된다(엔드포인트 전역 축 부재).
-- 두 방어 모두 노드 간 공유 상태가 필요하므로, 신규 인프라 없이 control DB 에 경량 테이블 1개를 둔다
-- (LS_WHK_FAIL_NMTM 과 동일 축·동일 패턴).
--
-- 표준용어·표준도메인 정합:
--   시도=ATMPT · 구분=SE · 코드=CD · 식별자=IDNTFR · 시작=BGNG · 일시=DT · 횟수=NMTM
--   만료=EXPD · 등록=REG · 수정=MDFCN · 권한=AUTHRT · 부여=GRANT
--   도메인: 코드V20 → VARCHAR(20) / 식별자V36 → VARCHAR(36) / 수I11 → INTEGER / 연월일시분초D → TIMESTAMP
CREATE TABLE IF NOT EXISTS LS_AUTHRT_GRANT_ATMPT (
    ATMPT_SE_CD  VARCHAR(20) NOT NULL,           -- 시도 구분 코드: ACCOUNT(계정 축) / GLOBAL(엔드포인트 전역 축)
    ATMPT_IDNTFR VARCHAR(36) NOT NULL,           -- 시도 식별자: 계정 축=요청자 sub, 전역 축='GLOBAL' 고정
    BGNG_DT      TIMESTAMP   NOT NULL,           -- 집계 윈도우 시작일시 (분 단위 버킷)
    ATMPT_NMTM   INTEGER     NOT NULL DEFAULT 0, -- 해당 윈도우 누적 시도 횟수
    EXPD_DT      TIMESTAMP   NOT NULL,           -- 만료일시 — 지난 행은 정리 대상
    REG_DT       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    MDFCN_DT     TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_LS_AUTHRT_GRANT_ATMPT PRIMARY KEY (ATMPT_SE_CD, ATMPT_IDNTFR, BGNG_DT)
);

CREATE INDEX IF NOT EXISTS IDX_LS_AUTHRT_GRANT_ATMPT_EXPD ON LS_AUTHRT_GRANT_ATMPT (EXPD_DT);

COMMENT ON TABLE  LS_AUTHRT_GRANT_ATMPT              IS '권한 자가부여 시도 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.ATMPT_SE_CD  IS '시도 구분 코드 — ACCOUNT(계정별) / GLOBAL(엔드포인트 전역).';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.ATMPT_IDNTFR IS '시도 식별자 — 계정 축은 요청자 sub, 전역 축은 GLOBAL 고정값.';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.BGNG_DT      IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.ATMPT_NMTM   IS '해당 윈도우의 누적 자가부여 시도 횟수.';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.EXPD_DT      IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.REG_DT       IS '등록일시.';
COMMENT ON COLUMN LS_AUTHRT_GRANT_ATMPT.MDFCN_DT     IS '수정일시.';
