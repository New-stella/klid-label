-- 관제서버 outbound 통지 발송 결과 관찰용 컬럼 추가.
-- STTS_CD(큐 처리 상태: PENDING/RETRYING/SUCCEEDED/DEAD_LETTER)와 별개로,
-- SEND_RSLT_CD 는 순수 발송 결과(SUCCESS/FAILED)를 기록한다.
--   - 즉시 발송 성공: STTS_CD='SUCCEEDED', SEND_RSLT_CD='SUCCESS' (신규 관찰 행)
--   - 즉시 발송 실패: STTS_CD='PENDING',   SEND_RSLT_CD='FAILED'  (폴백 큐 진입)
--   - 재시도 성공:    STTS_CD='SUCCEEDED', SEND_RSLT_CD='SUCCESS'
--   - 재시도 최종실패: STTS_CD='DEAD_LETTER', SEND_RSLT_CD='FAILED'
-- 기존 행(발송 결과 미기록)은 NULL 로 남긴다 — 무손실·가역.

ALTER TABLE LS_CONTROL_NOTIFY_FALLBACK
    ADD COLUMN SEND_RSLT_CD VARCHAR(16);                            -- 발송 결과 코드 (SUCCESS/FAILED, nullable=과거행)

COMMENT ON COLUMN LS_CONTROL_NOTIFY_FALLBACK.SEND_RSLT_CD IS '발송 결과 코드 - SUCCESS/FAILED (STTS_CD=큐 처리상태와 분리)';
