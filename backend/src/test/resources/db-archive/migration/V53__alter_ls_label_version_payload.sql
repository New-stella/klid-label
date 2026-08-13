-- Phase 5 — 라벨 버전관리 Gitea → DB 스냅샷 전면 전환.
--
-- 배경: 운영 환경에 Gitea 를 올릴 수 없어 라벨 버전/이력을 DB 에만 저장한다.
-- LS_LABEL_VERSION 이 더 이상 Gitea 커밋을 참조하지 않고, 라벨 전체 JSON 스냅샷을
-- 직접 보관한다. diff/rollback 은 모두 이 스냅샷을 기반으로 BE 에서 계산한다.
--
-- 변경
--  1) LABEL_PAYLOAD TEXT  — 라벨 전체 JSON 스냅샷 (LabelResponse 직렬화 결과).
--  2) VERSION_HASH VARCHAR(64) — payload 의 SHA-256(hex). 같은 페이로드 중복 식별.
--  3) GITEA_CMT_HASH 컬럼 + UK 제거.
--  4) VERSION_HASH UNIQUE 범위 = (DATA_SRC_SN, VERSION_HASH) 복합.
--     - 전역 UNIQUE 가 아닌 복합으로 둔 근거: 서로 다른 프레임(srcSn) 이 우연히 동일한
--       페이로드(예: 빈 라벨 {"items":[]}) 를 가질 수 있어 전역 UNIQUE 는 오탐 충돌을 유발.
--       프레임 내에서만 동일 스냅샷을 식별하면 정책이 명확하고 멱등 재커밋 처리도 안전하다.
--  5) LS_GITEA_FALLBACK_QUEUE 테이블 제거 (Gitea fallback 큐 폐기).
--
-- PostgreSQL 문법.

ALTER TABLE LS_LABEL_VERSION ADD COLUMN IF NOT EXISTS LABEL_PAYLOAD TEXT;
ALTER TABLE LS_LABEL_VERSION ADD COLUMN IF NOT EXISTS VERSION_HASH VARCHAR(64);

-- 기존 GITEA_CMT_HASH 기반 UNIQUE 제약 제거 후 컬럼 제거.
ALTER TABLE LS_LABEL_VERSION DROP CONSTRAINT IF EXISTS UK_LS_LABEL_VERSION_HASH;
ALTER TABLE LS_LABEL_VERSION DROP COLUMN IF EXISTS GITEA_CMT_HASH;

-- VERSION_HASH 복합 UNIQUE — 같은 프레임 내 동일 스냅샷 중복 차단 (멱등 재커밋 식별).
ALTER TABLE LS_LABEL_VERSION
    ADD CONSTRAINT UK_LS_LABEL_VERSION_SRC_HASH UNIQUE (DATA_SRC_SN, VERSION_HASH);

-- Gitea fallback 영속 큐 폐기.
DROP TABLE IF EXISTS LS_GITEA_FALLBACK_QUEUE;
