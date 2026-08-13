-- =============================================================================
-- V148: LS_DATA_RAW 에 SRC_TYPE(출처유형)·AUG_TYPE_CD(증강유형코드) 추가 (Phase 1)
--
-- 설계 정본: `.cc-design.md` §4-2-1. 컬럼 물리명·타입·길이 그대로.
--
-- 왜 인입 테이블만으로 부족한가:
--   AugTypeParser(VMS_CLIP_ID 마커 역파싱) 제거의 호출처 4곳(TaskBoardService·AssignmentService·
--   AugmentResultViewService·VideoResolutionService)이 전부 LS_DATA_RAW 를 조회한다. 출처 판별
--   컬럼이 인입 테이블에만 있으면 그 4곳이 인입을 다시 조인해야 하는데,
--   <파생영상(증강·해상도)은 저작도구가 만들어 인입 행이 아예 없다>. 그래서 작업 테이블 자신이
--   출처를 보유해야 한다.
--
-- 왜 AUG_TYPE_CD 까지 함께 추가하는가:
--   호출처 4곳이 필요로 하는 값은 'augmented 인가'가 아니라 <종류>(WINTER/NIGHT/RAIN/
--   RESL_1080P/RESL_720P/RESL_480P)다. augmented 여부는 이미 ORGNL_RAW_SN != null 로 판정한다.
--   SRC_TYPE 은 5값이라 종류를 구분하지 못하고, LS_DATA_AUG 에는 <파생 RAW 연결 컬럼이 없어>
--   (SRC_SN=원본 프레임 + AUG_TYPE_CD 뿐) 파생 RAW → 종류 역참조가 불가능하다. 그래서 파생 RAW 가
--   자기 종류를 직접 보유한다. 4곳이 이미 LsDataRaw 를 손에 쥐고 있어 조인이 늘지 않는다
--   (LS_DATA_AUG.DERIV_RAW_SN 안은 4곳 모두 조인 추가 + N+1 위험이라 폐기).
--
-- 표준용어 준거(정본: docs/표준용어 CSV 4종 — 2026-07-31 대조 실시). 신규 등록 용어 없음:
--   · 출처유형   = SRC_TYPE    VARCHAR(20)  (사업표준용어 등록분, V20. "학습데이터 출처 유형")
--   · 증강유형코드 = AUG_TYPE_CD VARCHAR(20)  (사업표준용어 등록분, V20.
--                   선존 LS_DATA_AUG.AUG_TYPE_CD 와 <동일 물리명·동일 타입·동일 값 체계>)
--
-- ★ 둘 다 nullable 로 추가한다(설계 §4-2-1 명시).
--   기존 행이 존재하는 상태에서 NOT NULL 을 즉시 부여하면 마이그레이션이 실패한다.
--   AUG_TYPE_CD 는 원본 영상이 NULL 이므로 <영구히> nullable 이며, SRC_TYPE 의 NOT NULL 승격은
--   기존 행 백필(설계 §4-2-1 — AugTypeParser 정규화 규칙과 동일 결과) 이후 별도로 검토한다.
--   ※ 이 마이그레이션은 <컬럼 추가만> 한다. 백필은 파서 제거와 짝을 이뤄야 하고(제거 <전에>
--     파서 결과와 대조 검증이 필요) 이번 범위가 아니다 — 따라서 기존 행은 전부 NULL 로 남는다.
--
-- 값 체계:
--   SRC_TYPE    = ORIGINAL | RELAY | USER_ULD | GENERATED | AUGMENTED   (설계 §4-2)
--                 관제 인입분은 LS_DATA_INGEST.SRC_TYPE 을 그대로 복사한다.
--                 저작도구 파생 생성 경로(증강 콜백·해상도 파생)는 AUGMENTED 를 직접 채운다.
--   AUG_TYPE_CD = WINTER | NIGHT | RAIN | RESL_1080P | RESL_720P | RESL_480P (원본은 NULL)
--
-- 데이터 보존: ADD COLUMN(nullable, DEFAULT 없음)만 수행한다. PostgreSQL 11+ 에서 기본값 없는
--   컬럼 추가는 테이블 재작성 없는 카탈로그 변경이라 기존 행이 그대로 보존되고 잠금도 짧다.
--   DROP/TRUNCATE/UPDATE 없음.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
-- =============================================================================

ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS SRC_TYPE    VARCHAR(20);
ALTER TABLE LS_DATA_RAW ADD COLUMN IF NOT EXISTS AUG_TYPE_CD VARCHAR(20);

COMMENT ON COLUMN LS_DATA_RAW.SRC_TYPE    IS '출처유형(ORIGINAL/RELAY/USER_ULD/GENERATED/AUGMENTED). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. 관제 인입분은 LS_DATA_INGEST.SRC_TYPE 복사. 백필 전 기존 행은 NULL.';
COMMENT ON COLUMN LS_DATA_RAW.AUG_TYPE_CD IS '증강유형코드(WINTER/NIGHT/RAIN/RESL_1080P/RESL_720P/RESL_480P). 파생영상이 자기 종류를 직접 보유해 VMS_CLIP_ID 마커 역파싱을 대체한다. 원본 영상은 NULL.';
