-- Phase 4 — 기존 행 IDEMPOTENCY_KEY 결정적 backfill (S-2 방어).
-- V42 에서 NULL 허용으로 컬럼 추가했으므로 기존 행은 NULL 상태.
-- 자연키 조합 (RAW_SN + META_KEY / SRC_SN + AUG_TYPE_CD + DATA_AUG_SN) 그대로 사용 — 자연키
-- 자체가 UNIQUE 이므로 충돌 0 보장. MD5/HASH 같은 DB 종속 함수 미사용 (H2/MariaDB 호환).
--
-- 주의
--  - 컬럼 길이 제약(VARCHAR(64)) 고려. 'legacy-meta-' + RAW_SN(BIGINT 최대 19자) + ':' + META_KEY(최대 64자) = 최대 96자.
--    META_KEY 가 길어 64자 한도를 넘어가는 경우 LEFT() 로 잘라서 보관 (충돌 가능성은 자연키
--    UNIQUE 가 보장하므로 단지 cosmetic — 정확한 식별이 필요하면 META_SN/DATA_AUG_SN 만으로도 충분).
--  - EXTERNAL_JOB_ID 는 외부 시스템 발급값이므로 backfill 대상 아님 (NULL 유지).
--  - WHERE IDEMPOTENCY_KEY IS NULL — idempotent 재실행 안전.

-- LS_DATA_META : META_SN (PK) 기반 — 절대적 UNIQUE 보장
UPDATE LS_DATA_META
   SET IDEMPOTENCY_KEY = CONCAT('legacy-meta-', META_SN, '-', RAW_SN, '-', LEFT(META_KEY, 30))
 WHERE IDEMPOTENCY_KEY IS NULL;

-- LS_DATA_AUG : DATA_AUG_SN (PK) 기반 — 절대적 UNIQUE 보장
UPDATE LS_DATA_AUG
   SET IDMP_KEY = CONCAT('legacy-aug-', DATA_AUG_SN, '-', SRC_SN, '-', AUG_TYPE_CD)
 WHERE IDMP_KEY IS NULL;
