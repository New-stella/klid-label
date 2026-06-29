-- ============================================================
-- V74 — LS_DEIDENT_PROC_LOG (DATA_RAW_SN, PROC_LOG_SN) 복합 인덱스 추가
--
-- 영상 목록 비식별 상태 파생(VideoQueryService.lookupDeidentInfos)은 rawSn 집합에 대해
-- 각 DATA_RAW_SN 의 최신 PROC_LOG_SN(= MAX) 1행을 IN 절로 조회한다. 기존 단일 인덱스
-- IDX_LS_DEIDENT_PROC_LOG_RAW(DATA_RAW_SN) 만으로는 MAX(PROC_LOG_SN) 도출 시 heap 접근이
-- 발생하므로, (DATA_RAW_SN, PROC_LOG_SN) 복합 인덱스로 Index-Only Scan 을 가능케 한다.
--
-- LS_* 저작도구 전용 테이블이라 관제팀 협의 없이 추가 안전. 기존 단일 인덱스는
-- PROC_STTS_CD 기반 조회 등 다른 경로를 위해 유지한다(중복 제거 대상 아님).
-- IF NOT EXISTS 로 멱등 — 이미 존재하는 환경에서도 안전하게 재실행 가능.
-- ============================================================

CREATE INDEX IF NOT EXISTS IDX_LS_DEIDENT_PROC_LOG_RAW_LOG
    ON LS_DEIDENT_PROC_LOG (DATA_RAW_SN, PROC_LOG_SN);
