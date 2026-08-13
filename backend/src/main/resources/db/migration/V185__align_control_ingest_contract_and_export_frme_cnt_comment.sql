-- =============================================================================
-- V185: 관제 인입 규격 변경 2건 반영 + 산출 프레임수 COMMENT 재지정
--
-- 1) LS_DATA_INGEST.OG_CD 제거            — 관제 "현행 미사용 값, 공급 불가" (2026-08-12 확정)
-- 2) VMS_CCTV_ID NOT NULL 해제 (2테이블)  — 관제 "CCTV 식별자가 없는 영상이 존재" (2026-08-12 확정)
-- 3) LS_DATASET_EXPORT.FRME_CNT COMMENT   — 산정 방식이 바뀌어 V173 의 서술이 사실과 달라졌다
--
-- 설계 진실원: LogiCraft ERD-012(영상·프레임 수집 ERD, v23 반영 완료).  @design ERD-012
-- 신규 컬럼이 없다 — DROP / ALTER(NOT NULL 해제) / COMMENT 뿐이라 표준용어·표준도메인 신규 등록 0건.
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 스키마 협의 불요. PostgreSQL 표준 문법. 멱등(IF EXISTS).
--
-- -----------------------------------------------------------------------------
-- 1) OG_CD(기관코드) 제거
--
-- 왜: 관제서버팀 회신(2026-08-12) — <관제 현행에서 쓰지 않는 값이라 공급할 수 없다>. 수신 통로는
--     있는데 관제가 영원히 채우지 않는 컬럼은 "미송신"과 "값 없음"이 구분되지 않는 채로 남고,
--     화면·업로드 폼이 그 자리를 계속 그린다(사용자가 채워도 아무 데도 쓰이지 않는다).
--     저작도구는 관제 수신 컬럼을 <관제가 실제로 보내는 것>만 보유한다.
--
-- 무엇이 사라지지 않나 (Critical — 함께 지우지 말 것):
--   · 학습데이터 export JSON 의 og_cd 필드는 <유지>한다. 그 필드는 원래부터 값을 싣지 않고
--     null 고정이며(NiaVideo/VideoMetaMapper), 관제와 합의된 산출 포맷이다. 산출물 영향 0.
--   · LS_DATA_INGEST.LCLGV_CD(지방자치단체코드)·LCLGV_NM(지방자치단체명)은 <서로 다른 값>이라
--     그대로 둔다. V147/V172 주석이 이 컬럼을 "OG_CD 와 다른 값"이라고 서술했는데, 비교 대상이
--     사라졌으므로 아래에서 COMMENT 를 함께 정정한다(없는 컬럼을 가리키는 주석을 남기지 않는다).
--
-- 적재값 소실: 이 컬럼에 값이 있는 행이 있으면 DROP 과 함께 소실된다(복구 불가). 관제는 이 값을
--     보낸 적이 없고 내부 dev 업로드 입력분만 존재할 수 있다 — 보존이 필요하면 DROP 전에 복사할 것.
--
-- -----------------------------------------------------------------------------
-- 2) VMS_CCTV_ID NOT NULL 해제 (LS_DATA_INGEST · LS_DATA_RAW)
--
-- 왜: 관제서버팀 회신(2026-08-12) — <CCTV 식별자가 없는 영상(수동 업로드 등)이 존재>한다.
--     관제는 대신 CCTV_NM 에 대체 표기(수동 업로드 파일명 등)를 채워 보내기로 했다.
--
-- 왜 2테이블 모두인가: 인입만 풀면 적재(LS_DATA_INGEST → LS_DATA_RAW)에서 NOT NULL 위반으로
--     터진다. 지금까지 적재 사전 가드가 VMS_CCTV_ID 공백 행을 <스킵>해 온 이유가 바로 이
--     제약이었고, 그 가드도 같은 변경에서 걷어낸다(TrainingVideoIngestTx). 한쪽만 풀면
--     "인입 행은 받되 원시영상에서 걸러지는" 반쪽 수용이 되어 관제 요구가 미충족된다.
--
-- 잃는 것(인지·수용): 값이 비면 화면 영상명이 CCTV명 → CCTV ID 폴백조차 못 하고 비게 된다.
--     그래서 애플리케이션이 3순위 폴백(영상 #{RAW_SN})으로 식별 가능한 표기를 보장한다
--     (판정 단일 원천 CctvDisplayNamePolicy — 화면이 빈칸/null 을 그리지 않는다).
--
-- 데이터마트 뷰 영향 없음: V_COMPLETED_VIDEO 는 V174 재작성에서 이 컬럼을 출력에서 뺐다.
--     동결 스냅샷(LS_DATASET_VIDEO_META.VMS_CCTV_ID)은 이미 nullable 이라 손대지 않는다.
--
-- 인덱스: IX_LS_DATA_RAW_CCTV(VMS_CCTV_ID) 는 그대로 둔다 — btree 는 NULL 을 담을 수 있고
--     동등 조회는 NULL 행을 어차피 찾지 않는다.
--
-- -----------------------------------------------------------------------------
-- 3) LS_DATASET_EXPORT.FRME_CNT COMMENT 재지정
--
-- 왜: 산정 규칙을 <원본벌+비식별벌 합계> → <실제 프레임 수>로 바꿨는데, V173 이 남긴 컬럼
--     COMMENT 가 여전히 합계를 말해 DB 를 직접 들여다보는 사람에게 틀린 사실을 알린다.
--     적용된 마이그레이션(V173)은 고칠 수 없으므로 여기서 COMMENT 만 다시 지정한다(DDL 변경 아님).
--     이 값은 관제 datasets.img_nocs · dataset_versions.data_etbl_nocs 로 그대로 나가므로
--     2배로 부푼 서술을 남겨두면 관제 쪽 해석까지 오염된다.
--
-- -----------------------------------------------------------------------------
-- 롤백 SQL (V162 규약 — 스키마 변경에는 롤백 전문을 동반한다):
--   ALTER TABLE LS_DATA_INGEST ADD COLUMN IF NOT EXISTS OG_CD VARCHAR(20);
--   COMMENT ON COLUMN LS_DATA_INGEST.OG_CD IS '기관코드(관제 resource_cctvs 조인). 촬영 시점 값 고정 목적의 의도된 중복 저장.';
--   -- ※ 되살려도 값은 비어 있다(DROP 시점에 소실).
--   UPDATE LS_DATA_INGEST SET VMS_CCTV_ID = '(UNKNOWN)' WHERE VMS_CCTV_ID IS NULL;
--   UPDATE LS_DATA_RAW    SET VMS_CCTV_ID = '(UNKNOWN)' WHERE VMS_CCTV_ID IS NULL;
--   ALTER TABLE LS_DATA_INGEST ALTER COLUMN VMS_CCTV_ID SET NOT NULL;
--   ALTER TABLE LS_DATA_RAW    ALTER COLUMN VMS_CCTV_ID SET NOT NULL;
--   -- ※ NOT NULL 복원은 NULL 행을 먼저 채워야 성립한다. 위 대용값은 <지어낸 값>이라
--   --   롤백이 불가피할 때만 쓰고, 채운 행 목록을 별도로 남길 것.
--   COMMENT ON COLUMN LS_DATASET_EXPORT.FRME_CNT IS '산출 프레임수(원본벌+비식별벌 합계). (V173 개명 — 구 FRAME_CNT. 프레임=FRME 표준단어, LS_DATA_INGEST.FRME_CNT 와 약어 통일)';
--   -- ※ 애플리케이션 롤백도 함께 필요하다 — 엔티티(LsDataIngest.ogCd 부재 등)와 어긋나면
--   --   ddl-auto=validate 가 기동을 막는다.
-- =============================================================================

-- 1) 기관코드 제거 (관제 공급 불가 확정)
ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS OG_CD;

-- 1-a) 사라진 컬럼을 가리키던 주변 COMMENT 정정 (없는 컬럼을 참조하는 서술을 남기지 않는다)
COMMENT ON COLUMN LS_DATA_INGEST.LCLGV_NM IS
    '지방자치단체명(관제 video.location). 동은 포함하지 않는다. 지방자치단체코드(LCLGV_CD)와 서로 다른 값이며 서로 대체·통합하지 않는다. (V172 개명 — 구 RGN_NM. 표준용어 LCLGV_NM·도메인 명V100, 관제 datasets.lclgv_nm 과 일치)';
COMMENT ON COLUMN LS_DATA_INGEST.LCLGV_CD IS
    '지방자치단체코드. LS_DATA_RAW.LCLGV_CD 의 원천이며 관제 완료통지 페이로드 lclgv_cd(required)가 이 값에서 나온다. 지방자치단체명(LCLGV_NM)과 서로 다른 값이다.';

-- 2) VMS CCTV 아이디 NULL 허용 — 관제에 CCTV 식별자 없는 영상이 존재(2026-08-12 확정)
ALTER TABLE LS_DATA_INGEST ALTER COLUMN VMS_CCTV_ID DROP NOT NULL;
ALTER TABLE LS_DATA_RAW    ALTER COLUMN VMS_CCTV_ID DROP NOT NULL;

COMMENT ON COLUMN LS_DATA_INGEST.VMS_CCTV_ID IS
    'VMS CCTV 아이디(관제 video.cctv_mng_no). NULL 허용 — CCTV 식별자가 없는 영상(수동 업로드 등)이 존재한다(2026-08-12 관제 확정). 그런 영상은 관제가 CCTV_NM 에 대체 표기를 채워 보낸다.';
COMMENT ON COLUMN LS_DATA_RAW.VMS_CCTV_ID IS
    'VMS CCTV 아이디 — 인입(LS_DATA_INGEST.VMS_CCTV_ID) 복사값. NULL 허용(2026-08-12 관제 확정). 화면 표시명은 CCTV명 → 이 값 → 영상 #{RAW_SN} 순으로 폴백한다.';

-- 3) 산출 프레임수 COMMENT 재지정 — 산정 방식 변경(합계 → 실제 프레임 수) 반영
COMMENT ON COLUMN LS_DATASET_EXPORT.FRME_CNT IS
    '산출 프레임 수 — 실제 프레임 수이며 원본벌+비식별벌 합계가 아니다. 관제 datasets.img_nocs · dataset_versions.data_etbl_nocs 에 그대로 실리고 그 정의가 "추출·라벨링 프레임 수"이기 때문이다(2벌 쓰기 건수를 합산하면 2배로 부풀고, 1벌만 산출하는 파생영상과 값의 축이 갈린다). (V185 재지정 — V173 이 남긴 "원본벌+비식별벌 합계" 서술은 산정 방식 변경으로 사실과 달라졌다)';
