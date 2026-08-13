-- V133 — 해상도 파생 PII 격리(1차 검증 E-ISSUE-21/22/41, D-ISSUE-46) 스키마·뷰 정정.
--
-- 이 마이그레이션은 <스키마·뷰 변경만> 담당한다. 기존 파생 4건(rawSn 15/16/18/19)의
-- 실제 파일 이관 + 경로 UPDATE 는 SQL 로 하지 않는다 — Flyway 는 파일을 옮길 수 없어
-- 경로 문자열만 바꾸면 스트리밍/export 가 즉시 404 로 깨진다(B-ISSUE-61 재발).
-- 파일 이관은 별도 Java 배치(ResolutionBackfillService, POST /api/v1/videos/resolution-backfill)가
-- Copy → Verify → DB UPDATE 커밋 → 원본 삭제 순서로 멱등 수행한다.

-- ---------------------------------------------------------------------------
-- 1) LS_DATA_SRC.SRC_FILE_PATH_NM 을 NULL 허용으로 완화 (E-ISSUE-41 정책 A)
--    해상도 파생영상은 "비식별본 복사 + 프레임 리스케일" 이라 원본 픽셀이 실재하지 않는다.
--    구현은 유일한 산출물(비식별 리스케일 프레임)을 원본/비식별 두 컬럼에 동일 저장해
--    ①export 2벌 바이트 동일 ②orgnl 벌 anonymity="N" 오표기 ③마트 뷰 "두 경로 상이" 불변식 파손
--    을 유발했다. 파생 프레임은 원본 경로를 <null(원본 부재)> 로 남긴다.
--    ※ 컬럼 물리명·타입·길이(VARCHAR(500))는 변경 없음 — 표준용어/표준도메인 영향 없음.
ALTER TABLE LS_DATA_SRC ALTER COLUMN SRC_FILE_PATH_NM DROP NOT NULL;

-- ---------------------------------------------------------------------------
-- 2) V_COMPLETED_FRAME — 비식별 경로 불변식 게이트 (D-ISSUE-46, fail-closed)
--    관제가 DEIDENTIFIED_PATH 로 픽업하는 파일은 반드시 비식별본이어야 한다. 파생영상 생성
--    경로의 결함으로 원본 경로가 그대로 비식별 컬럼에 복제된 행(original_path = deidentified_path)을
--    뷰에서 제외해, 결함이 데이터마트로 전파되지 않게 한다.
--
--    [게이트 범위 — 결함 형태로 한정 (M-1)]
--    D-ISSUE-46 의 결함 형태는 "원본 경로를 비식별 경로로 노출"(두 값이 <동일>)이다.
--    DE_IDNTF_SRC_FILE_PATH_NM IS NULL 은 PII 노출이 아니라 <데이터 결측>이므로 게이트 대상이 아니다.
--    NULL 까지 배제하면 ①증강 파생(WINTER/NIGHT/RAIN — AugmentExtractPersist 가 비식별 경로를 null 로
--    적재) 전량과 ②비식별 실패로 RAW only 추출된 영상(FfmpegFrameExtractor) 전량이 데이터마트에서
--    통째로 사라진다. 결측은 종전대로 노출하여 관제가 결측을 인지하게 둔다.
--
--    ORIGINAL_PATH IS NULL(파생영상, 정책 A) 도 "원본 부재"가 정상이므로 통과시킨다 —
--    이 경우 원본을 비식별로 오인할 여지가 없다.
--
--    빈 문자열('')은 코드(isBlank)와 동일하게 <결측>으로 취급한다 — SQL 이 IS NOT NULL 만 보면
--    코드와 판정이 갈리는 구멍이 생긴다.
--
--    [계약 영향] 이 게이트로 뷰에서 빠지는 rawSn 목록은
--    POST /api/v1/videos/resolution-backfill?dryRun=true 응답(viewExcludedRawSns)으로 조회할 수 있다.
--    출력 컬럼(이름·순서)은 V104/V107 정의와 1:1 동일 — 계약 변경 없음.
CREATE OR REPLACE VIEW V_COMPLETED_FRAME AS
SELECT
    src.SRC_SN,
    src.RAW_SN,
    src.FRM_NO                     AS FRAME_NO,
    src.SRC_FILE_PATH_NM          AS ORIGINAL_PATH,
    src.DE_IDNTF_SRC_FILE_PATH_NM AS DEIDENTIFIED_PATH,
    src.SHT_DT                    AS CAPTURED_AT,
    src.REG_DT,
    src.UPD_DT,
    src.FRM_EXPLN                 AS DESCRIPTION
FROM LS_DATA_SRC src
WHERE EXISTS (
    SELECT 1
      FROM LS_RAW_DATA_STATUS s
     WHERE s.RAW_DATA_ID  = src.RAW_SN
       AND s.DATA_STTS_CD = 'APPROVED'
)
  -- 원본 경로와 비식별 경로가 <동일>한 행만 제외(= 비식별 미적용 프레임을 비식별로 노출하는 결함).
  -- 어느 한쪽이 결측(NULL 또는 공백)인 행은 결함이 아니므로 통과시킨다.
  AND NOT (
        src.SRC_FILE_PATH_NM IS NOT NULL
    AND TRIM(src.SRC_FILE_PATH_NM) <> ''
    AND src.DE_IDNTF_SRC_FILE_PATH_NM IS NOT NULL
    AND TRIM(src.DE_IDNTF_SRC_FILE_PATH_NM) <> ''
    AND src.DE_IDNTF_SRC_FILE_PATH_NM = src.SRC_FILE_PATH_NM
  );
