-- Phase 2 (CVAT-Like 라벨 풀 포팅): LS_DATA_LBL → LS_LABEL FK 연결.
-- 신규 LABEL_ID nullable 컬럼 + FK + 인덱스, 그리고 기존 row 의 LABEL 텍스트 ↔ LS_LABEL.NAME
-- best-effort 매칭(case-insensitive). PJT_ID=1 단일 프로젝트 가정 (현 시점 시드 기준).
--
-- 기존 LABEL(varchar 255) 컬럼은 호환을 위해 유지 (Entity 에서 @Deprecated 표기).
-- Phase 4 이후 호출자 정리 완료 시 별도 마이그레이션으로 NOT NULL 강제/제거 검토.
--
-- H2/MariaDB 공통:
--  - ADD COLUMN ... AFTER 는 MariaDB 만 지원 → AFTER 절 미사용 (H2 호환).
--  - UPDATE ... FROM JOIN 은 MariaDB 미지원 → 서브쿼리 형태.
--  - LIMIT 는 H2 + MariaDB 양쪽 지원.

ALTER TABLE LS_DATA_LBL ADD COLUMN IF NOT EXISTS LABEL_ID BIGINT NULL;

-- FK + 인덱스 — IF NOT EXISTS 미지원 DB(MariaDB)도 있으므로 ADD CONSTRAINT 는 단순 ADD.
-- 운영 DB 에 이미 존재하는 경우 Flyway repair / 수동 적용으로 대응.
ALTER TABLE LS_DATA_LBL
    ADD CONSTRAINT FK_LS_DATA_LBL_LABEL
    FOREIGN KEY (LABEL_ID) REFERENCES LS_LABEL(LABEL_ID);

CREATE INDEX IF NOT EXISTS IDX_LS_DATA_LBL_LABEL_ID ON LS_DATA_LBL(LABEL_ID);

-- 기존 row best-effort 매칭 (case-insensitive). 매칭 실패 시 NULL 유지.
-- PJT_ID=1 + USE_YN='Y' 라벨에 한정. 동일 이름 다중 행 방어 → LIMIT 1.
UPDATE LS_DATA_LBL ld
SET ld.LABEL_ID = (
    SELECT l.LABEL_ID FROM LS_LABEL l
    WHERE LOWER(l.NAME) = LOWER(ld.LABEL)
      AND l.PJT_ID = 1
      AND l.USE_YN = 'Y'
    LIMIT 1
)
WHERE ld.LABEL_ID IS NULL AND ld.LABEL IS NOT NULL;
