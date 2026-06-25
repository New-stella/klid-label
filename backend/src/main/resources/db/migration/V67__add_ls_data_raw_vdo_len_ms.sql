-- v1→v2 이관 충실도(fidelity): 원본 영상 길이(밀리초) 보존용 컬럼 추가.
-- 배경:
--   - v2 LS_DATA_RAW.VDO_LEN_SEC 는 '초' 계약 — 기존 데이터(9~113초)·UI 길이표시·데이터마트·"≥30초" 판정이 모두 초로 해석한다.
--   - v1 LS_DATA_RAW.VDO_LEN 은 '밀리초' 이다(실측 역산: VDO_LEN / (FRM_CNT/FPS) = 1000, 1993/2006건).
--   - 따라서 이관 시 VDO_LEN_SEC 에는 ÷1000 한 '초'를 넣고, 원본 ms 정밀도는 본 컬럼에 그대로 보존한다(감사/재계산용).
-- 영향: nullable 추가만 — 기존 컬럼/제약/네이티브 적재 경로(TrainingVideoIngestTx)에 무영향.
--   네이티브 적재는 본 컬럼을 채우지 않으며(null=네이티브/미상), 엔티티 미매핑이어도 ddl-auto=validate 는 추가 컬럼을 무시한다.
ALTER TABLE LS_DATA_RAW ADD COLUMN VDO_LEN_MS BIGINT NULL;  -- 원본 영상 길이(ms). v1 이관본만 채움. null=네이티브/미상
