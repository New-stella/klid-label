-- VLM describe 콜백 정합 (Phase 1) — request_id → rawSn 역조회 슬롯.
-- 벤더 확정 계약(IntelliVIX Video VLM API v2.0.1) describe 콜백은 바디에 request_id 만 전달하고
-- rawSn 이 없다. 위탁 요청 시 발급한 request_id(=IDMP_KEY)에 대상 영상 RAW_SN 을 함께 영속해
-- 결과 수신부가 역조회(resolveRawSn)할 수 있도록 컬럼을 추가한다. 매핑 없으면 NULL.
ALTER TABLE LS_WEBHOOK_IDEMPOTENCY
    ADD COLUMN IF NOT EXISTS RAW_SN BIGINT NULL;
