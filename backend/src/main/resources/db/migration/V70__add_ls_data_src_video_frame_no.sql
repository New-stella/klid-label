-- LS_DATA_SRC: 실제 영상 내 프레임 위치 보존용 컬럼 추가.
-- 배경:
--   - 기존 FRM_NO 는 마킹 추출 순번(loop index i)만 담고 실제 영상 프레임 위치는 DB 에 없다.
--   - 재비식별 시 동일 프레임을 재추출하려면 영상 내 실제 디코더 0-base 위치가 필요하다.
--   - FRM_NO(추출순번)와 의미를 구분하기 위해 별도 컬럼 VDO_FRM_NO 로 분리한다.
-- 영향: nullable 추가만 — 기존 컬럼/제약/적재 경로에 무영향.
--   값 적재 경로는 배선 완료: 마킹 추출 시 FfmpegFrameExtractor 가 mark.frameIndex() 를,
--   증강 결과 적재 시 AugmentResultService 가 부모 프레임 값을 carry-over 하여 채운다.
--   해당 경로를 타지 않은 레거시/원본 추출 row 는 null 로 남는다(미상).
ALTER TABLE LS_DATA_SRC ADD COLUMN IF NOT EXISTS VDO_FRM_NO INTEGER NULL;  -- 실제 영상 내 0-base 프레임 위치. null=미배선/미상

COMMENT ON COLUMN LS_DATA_SRC.VDO_FRM_NO IS '실제 영상 내 디코더 0-base 프레임 위치. FRM_NO(추출순번)와 의미 구분 — 재비식별 재추출용';
