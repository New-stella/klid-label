-- blocker#2: NIA image.description(프레임 단위 자연어 설명, 작업자 수기) 저장 컬럼.
-- 프레임=SRC 1:1 단일 텍스트. 표준용어 조합 FRM(프레임) + EXPLN(설명, program id248/gov 기등록).
-- nullable — 설명 미입력/삭제 허용.
ALTER TABLE LS_DATA_SRC ADD COLUMN IF NOT EXISTS FRM_EXPLN VARCHAR(1000);

COMMENT ON COLUMN LS_DATA_SRC.FRM_EXPLN IS '프레임 설명(작업자 수기, NIA image.description 조달원). nullable.';
