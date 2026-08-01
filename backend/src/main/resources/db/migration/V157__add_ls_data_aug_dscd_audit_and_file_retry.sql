-- =============================================================================
-- V157: LS_DATA_AUG_DSCD 보강 — ①폐기 감사 스냅샷(증강 종류·프롬프트) ②파일 정리 수렴(재시도 상한·포기)
--
-- ★ 스키마 변경은 V156 을 고치지 않고 신규 버전으로 올린다 — 적용된 마이그레이션의 <본문(DDL)>을
--   바꾸면 checksum 불일치로 기동이 깨지기 때문이다.
--   (사실 정정: V156 의 <주석>은 이후 DEV_FIX 에서 실제로 손봤다. V156·V157 이 아직 어떤 배포
--    환경에도 적용되지 않은 상태여서 가능했던 것이고, 한 번이라도 적용된 뒤라면 주석 수정도
--    checksum 을 깨므로 금지다. "V156 을 고치지 않았다" 는 구 서술은 사실과 달라 바로잡는다.)
--
-- ─────────────────────────────────────────────────────────────────────────────
-- 1) 감사 스냅샷 (AUG_TYPE_CD / PROMPT_CN)
--   비석은 DSCD_RSN(왜 버렸는가)·VDO_FILE_PATH 는 남기지만 <b>무엇을 버렸는가</b> 는 남기지 않았다.
--   실삭제는 LS_DATA_AUG 행 자체를 지우므로, 증강 종류와 생성 조건이 함께 사라져 감사가 반쪽이 된다.
--   특히 중복 증강 요청이 허용된 뒤(V153)로는 <b>같은 (영상 × 종류) 파생이 여러 건 공존</b>하고
--   결과물을 구분하는 유일한 축이 PROMPT_CN(요청 시 전송한 생성 조건 원문)이다 — 구속 정책.
--   ※ 백필하지 않는다(이 리포의 확정 방침). 기존 비석은 NULL 로 남고 신규 표식부터 채워진다.
--
-- 2) 파일 정리 수렴 (FILE_DEL_RTRY_NMTM / FILE_DEL_FAIL_DT / FILE_DEL_FAIL_RSN)
--   DB 는 지웠는데 파일이 남은 비석(DEL_DT IS NOT NULL AND FILE_DEL_DT IS NULL)은 매 tick 재시도된다.
--   그런데 파생 프레임 트리에 <b>우리가 의도적으로 지우지 않는</b> 항목(심링크·비정규 파일)이 있으면
--   정리는 <b>영원히</b> 완료되지 않는데 시도 상한도 종결 표시도 없었다. 재시도 큐는 DEL_DT 오름차순
--   이라 그런 비석이 <b>항상 앞자리를 점유</b>하고, batch-size 만큼 쌓이면 이후 비석의 파일 정리가
--   전면 정지한다(head-of-line blocking).
--   → 시도 횟수를 누적하고, 상한 초과 또는 "재시도해도 동일" 판정이면 FILE_DEL_FAIL_DT 로 <b>종결</b>해
--     큐에서 빼고 사람이 수동 정리한다(사유는 FILE_DEL_FAIL_RSN + WARN 감사 로그).
--   운영 조회) 사람 개입 대기 목록:
--     SELECT * FROM LS_DATA_AUG_DSCD
--      WHERE DEL_DT IS NOT NULL AND FILE_DEL_DT IS NULL AND FILE_DEL_FAIL_DT IS NOT NULL;
--
-- ─────────────────────────────────────────────────────────────────────────────
-- ★★ 종결분(데드레터) <재개 절차> — 두 컬럼을 <반드시 함께> 되돌린다
--   종결된 비석을 자동 재시도 큐로 되살리는 전용 API 는 없다(범위 밖). 수동 UPDATE 로 재개하되,
--   FILE_DEL_FAIL_DT 만 NULL 로 지우면 FILE_DEL_RTRY_NMTM 이 이미 상한(기본 5)에 도달해 있어
--   <첫 재시도 실패에 곧바로 다시 종결>된다(AugmentDiscardPurgeTxService.recordFileCleanupFailure
--   의 attempts >= max 판정). 그래서 시도 횟수도 함께 0 으로 되돌려야 한다.
--
--     UPDATE LS_DATA_AUG_DSCD
--        SET FILE_DEL_FAIL_DT   = NULL,   -- 재시도 큐 재진입 (IX_..._FILE_RTY 술어)
--            FILE_DEL_FAIL_RSN  = NULL,   -- 과거 사유 정리(선택)
--            FILE_DEL_RTRY_NMTM = 0       -- ★ 이것을 빠뜨리면 1회 만에 재종결된다
--      WHERE DATA_AUG_DSCD_SN = :dscdSn;
--
--   재개 전 점검) ①잔존 원인이 실제로 해소됐는가(심링크·비정규 항목 제거 / NAS 마운트 복구)
--               ②DEL_DT IS NOT NULL AND FILE_DEL_DT IS NULL 인가(=파일 정리만 남은 비석인가).
--   상한 자체를 늘려야 하는 환경(예: NAS 장애가 5시간을 넘는다)은
--   AUGMENT_DISCARD_FILE_CLEANUP_MAX_ATTEMPTS 로 조절한다(application.yml · .env.example).
--
-- ★ 표준용어·표준도메인 (물리명 + 타입 + 크기) — 조합 전에 복합용어 등록 여부부터 확인했다.
--   [등록된 복합용어 채택]
--     · 증강유형코드   AUG_TYPE_CD  (사업표준용어, 코드값 V20)            → VARCHAR(20)
--     · 프롬프트내용   PROMPT_CN    (사업표준용어, 내용V4000)             → VARCHAR(4000)
--     · 재시도횟수     RTRY_NMTM    (공통표준용어 7차, 수N10)             → INT   (리포 전역 동일 표기)
--     · 실패사유       FAIL_RSN     (공통표준용어 7차, 내용V4000)         → VARCHAR(4000)
--   [등록 복합용어가 없어 표준단어로 조합 — 각 단어 모두 표준 등재]
--     · FILE_DEL_RTRY_NMTM : 파일(FILE) + 삭제(DEL) + 재시도횟수(RTRY_NMTM)      → INT
--     · FILE_DEL_FAIL_DT   : 파일(FILE) + 삭제(DEL) + 실패(FAIL) + 일시(DT)      → TIMESTAMP
--                            (도메인 연월일시분초D — LGN_FAIL_DT 와 동형)
--     · FILE_DEL_FAIL_RSN  : 파일(FILE) + 삭제(DEL) + 실패사유(FAIL_RSN)         → VARCHAR(4000)
--
-- 우리 소유 LS_* 테이블 — 관제 협의 불요. 데이터마트 뷰(V_COMPLETED_*) 변경 없음.
-- =============================================================================

ALTER TABLE LS_DATA_AUG_DSCD ADD COLUMN IF NOT EXISTS AUG_TYPE_CD        VARCHAR(20)   NULL;
ALTER TABLE LS_DATA_AUG_DSCD ADD COLUMN IF NOT EXISTS PROMPT_CN          VARCHAR(4000) NULL;
ALTER TABLE LS_DATA_AUG_DSCD ADD COLUMN IF NOT EXISTS FILE_DEL_RTRY_NMTM INT           NOT NULL DEFAULT 0;
ALTER TABLE LS_DATA_AUG_DSCD ADD COLUMN IF NOT EXISTS FILE_DEL_FAIL_DT   TIMESTAMP     NULL;
ALTER TABLE LS_DATA_AUG_DSCD ADD COLUMN IF NOT EXISTS FILE_DEL_FAIL_RSN  VARCHAR(4000) NULL;

COMMENT ON COLUMN LS_DATA_AUG_DSCD.AUG_TYPE_CD        IS '증강유형코드 - 폐기 당시 증강 종류 스냅샷(LS_DATA_AUG 행은 실삭제로 사라진다)';
COMMENT ON COLUMN LS_DATA_AUG_DSCD.PROMPT_CN          IS '프롬프트내용 - 폐기 당시 생성 조건 스냅샷. 중복 증강 허용 이후 같은 (영상×종류) 파생을 구분하는 유일한 축';
COMMENT ON COLUMN LS_DATA_AUG_DSCD.FILE_DEL_RTRY_NMTM IS '파일삭제재시도횟수 - 파일 정리 시도 누적. 상한 초과 시 FILE_DEL_FAIL_DT 로 종결';
COMMENT ON COLUMN LS_DATA_AUG_DSCD.FILE_DEL_FAIL_DT   IS '파일삭제실패일시 - 자동 정리 포기(데드레터) 시각. 값이 있으면 재시도 큐에서 제외되고 사람이 수동 정리';
COMMENT ON COLUMN LS_DATA_AUG_DSCD.FILE_DEL_FAIL_RSN  IS '파일삭제실패사유 - 포기 사유(경로 원문 미포함)';

-- 재시도 큐 인덱스 재정의 — 종결(FILE_DEL_FAIL_DT)된 비석은 후보에서 제외한다.
-- 술어가 바뀌므로 기존 partial index 를 버리고 다시 만든다(V156 의 IX_..._FILE_RTY 대체).
DROP INDEX IF EXISTS IX_LS_DATA_AUG_DSCD_FILE_RTY;
CREATE INDEX IF NOT EXISTS IX_LS_DATA_AUG_DSCD_FILE_RTY
    ON LS_DATA_AUG_DSCD (DEL_DT)
    WHERE DEL_DT IS NOT NULL AND FILE_DEL_DT IS NULL AND FILE_DEL_FAIL_DT IS NULL;
