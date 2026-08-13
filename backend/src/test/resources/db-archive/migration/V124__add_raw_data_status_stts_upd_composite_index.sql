-- =====================================================================
-- V124 — LS_RAW_DATA_STATUS (DATA_STTS_CD, UPD_DT DESC) 복합 인덱스
-- =====================================================================
-- 목적: 검수 목록 조회(ReviewRepository.searchByStatus)의 정렬 비용 제거.
--   쿼리 형태: WHERE DATA_STTS_CD IN (:whitelist) [AND DATA_STTS_CD = :status]
--             ORDER BY UPD_DT DESC
--   기존 단일 인덱스 IX_LS_RAW_DATA_STATUS_STTS(DATA_STTS_CD, V38)는 후보 행만
--   좁힐 뿐 ORDER BY UPD_DT DESC 를 별도 Sort 노드로 수행 → 데이터 증가 시
--   페이지당 대규모 정렬 비용. 복합 인덱스로 상태 필터 + 정렬을 인덱스 순서로
--   함께 해소한다(단일 status 조회는 Sort 스킵, 화이트리스트 조회도 부분범위 스캔).
--
-- 중복 인덱스 정리: 신규 복합 인덱스의 선행 컬럼(DATA_STTS_CD)이 기존 V38 단일
--   인덱스의 용도(WHERE DATA_STTS_CD =/IN, GROUP BY DATA_STTS_CD)를 완전히
--   포함하므로 V38 단일 인덱스는 중복이다. 상태 전이가 잦은(배치 단계마다 UPDATE)
--   테이블의 불필요한 쓰기 증폭을 피하려 함께 제거한다.
--
-- 멱등: IF NOT EXISTS / IF EXISTS 로 재실행 안전.
-- =====================================================================

CREATE INDEX IF NOT EXISTS IX_LS_RAW_DATA_STATUS_STTS_UPD
    ON LS_RAW_DATA_STATUS (DATA_STTS_CD, UPD_DT DESC);

DROP INDEX IF EXISTS IX_LS_RAW_DATA_STATUS_STTS;
