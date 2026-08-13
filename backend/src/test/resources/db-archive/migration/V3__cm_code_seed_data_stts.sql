-- ⚠ 운영 klid_system 적용 시 관제서버팀 사전 승인 필수 (CM_CODE 4건 INSERT)
-- DB설계서_FINAL.md §4.4 — 검수 워크플로우 코드값 보강 (PENDING/IN_REVIEW/APPROVED/REJECTED).
-- PostgreSQL: ON CONFLICT DO NOTHING 으로 멱등 보장 (이미 존재하면 NOOP).

CREATE TABLE IF NOT EXISTS CM_CODE (
    GROUP_CODE  VARCHAR(64)     NOT NULL,
    CODE        VARCHAR(64)     NOT NULL,
    CODE_NM     VARCHAR(255)    NOT NULL,
    CODE_DC     VARCHAR(500),
    USE_YN      VARCHAR(1)      NOT NULL DEFAULT 'Y',
    SORT_ORDR   INT             NOT NULL DEFAULT 0,
    REG_DT      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (GROUP_CODE, CODE)
);

INSERT INTO CM_CODE (GROUP_CODE, CODE, CODE_NM, CODE_DC, USE_YN, SORT_ORDR) VALUES
    ('DATA_STTS_CD', 'PENDING',   '검수 대기', '라벨링 완료 후 검수 대기', 'Y', 10),
    ('DATA_STTS_CD', 'IN_REVIEW', '검수중',   'REVIEWER가 검수 진행 중',  'Y', 20),
    ('DATA_STTS_CD', 'APPROVED',  '검수 승인', 'REVIEWER 승인 완료',       'Y', 30),
    ('DATA_STTS_CD', 'REJECTED',  '검수 반려', 'REVIEWER 반려',             'Y', 40)
ON CONFLICT (GROUP_CODE, CODE) DO NOTHING;
