-- LS_AUTH_WORK_LOCK: 동일 영상 활성(LOCKED) 작업락 1건 강제 — 동시 재비식별/신고 이중 위탁 차단(CWE-362).
-- 기존 UNIQUE 제약은 LOCK_ID(랜덤 UUID)에만 걸려 동일 영상 동시 요청(TOCTOU)을 막지 못했다.
-- PostgreSQL partial unique index 로 활성 락만 영상(DATA_RAW_SN) 단위 1건으로 제한한다.
-- 락은 일시적 레코드라 기존 중복 active 락 가능성은 낮음. 인덱스 생성 실패 시
-- 동일 (DATA_RAW_SN) 의 중복 LOCKED row 를 RELEASED 로 정리한 뒤 재적용한다.
CREATE UNIQUE INDEX IF NOT EXISTS UX_LS_AUTH_WORK_LOCK_RAW_ACTIVE
    ON LS_AUTH_WORK_LOCK (DATA_RAW_SN)
    WHERE LOCK_TARGET_CD = 'RAW' AND LOCK_STTS_CD = 'LOCKED';
