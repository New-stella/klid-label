-- =============================================================================
-- V147: 관제 인입 테이블 LS_DATA_INGEST 신설 (Phase 1 — 적재 주체 반전)
--
-- 목적: 관제서버가 학습용 영상 메타를 <저작도구 테이블에 직접 INSERT> 한다.
--       저작도구 배치가 관제 MNG_CLIP_MASTER 를 스캔해 적재하던 전제가 뒤집혔고,
--       관제 2차 DB(PostgreSQL klid)에는 우리가 참조하던 MNG_* 가 한 개도 없다.
--       그 수신 창구가 이 테이블이다.
--
-- 설계 정본: `.cc-design.md` §4-1 (컬럼 물리명·타입·길이 그대로. 임의 변경 금지)
--       총 37컬럼 = 관제 수신 29 + 저작도구 운영 8.
--       (운영 8 = 구 7 + NEXT_RTRY_DT — 설계 §6-0-1-a ㉢ backoff 도입분. 아래 표준용어 근거 참조)
--
-- 설계 원칙(§2 R1~R6):
--   R3  우리 쪽 조인 0 — 관제가 v2_video_assets ⋈ v2_video_metadata ⋈ resource_cctvs
--       조인 결과를 <평면 1행>으로 넣어준다. CCTV 제원(OG_CD/CCTV_NM/CCTV_HGT/
--       MAIN_SURV_PAN_ANG)이 영상마다 중복 저장되는 것은 의도된 설계다 — 조인 참조하면
--       카메라 교체·방위각 재설정 시 과거 영상의 어노테이션이 현재 제원으로 오염된다.
--   R6  인입에는 <관제가 보내는 값만> 둔다. 수동입력(촬영환경 3필드·개인정보 3필드)·
--       VLM 산출(vd_description)·워크플로 컬럼(DATA_STTS_CD/DE_IDENT_YN/PRVC_TYPE_CD)은
--       제외한다. 인입에 두면 같은 의미가 두 군데 생기고, 워크플로 컬럼은 관제의 잘못된
--       값이 우리 상태머신을 직접 오염시킨다(§4-3).
--
-- 표준용어 준거(정본: docs/표준용어 CSV 4종 + LogiCraft program_glossary — 설계 §3):
--   신규 등록 용어 없음(§2 R5). 아래는 전부 기존 등록분 재사용이며 도메인도 등록값 채택.
--   용어·도메인) 수신일련번호=RCPTN_SN 일련번호N22(gov) → BIGINT
--                수신일시=RCPTN_DT · 처리일시=PRCS_DT · 촬영일시=SHT_DT  연월일시분초D → TIMESTAMP
--                다음재시도일시=NEXT_RTRY_DT 연월일시분초D → TIMESTAMP
--                  ※ 사업표준용어 등록분('다음재시도일시 / NEXT_RTRY_DT', 출처 KLID-저작도구 ERD-021).
--                    신규 등록 0 원칙(§2 R5)을 지켜 기존 등록 용어를 그대로 재사용한다. 선존
--                    LS_CONTROL_NOTIFY_FALLBACK.NEXT_RTRY_DT(V44, TIMESTAMP)와 물리명·타입·의미
--                    (=PENDING + NEXT_RTRY_DT <= now 폴링)가 동일해 사내 정합도 맞는다.
--                처리상태코드=PROC_STTS_CD · 영상코덱=VDO_CDC · 기관코드=OG_CD  코드V20 → VARCHAR(20)
--                원시영상일련번호=RAW_SN N19 → BIGINT (적재 결과 역추적)
--                재시도횟수=RTY_CNT N10 → INTEGER  (RTY_NOCS 아님 — KLID-BM 정본이 RTY_CNT)
--                에러메시지=ERR_MSG · 관제일지=MNTR_CN  내용V4000 → VARCHAR(4000)
--                VMS클립아이디=VMS_CLIP_ID V128 · VMS_CCTV아이디=VMS_CCTV_ID V64
--                동영상파일명=VDO_FILE_NM 명V300 · CCTV명=CCTV_NM 명V300
--                원시파일경로명=RAW_FILE_PATH_NM V500 · 지역명=RGN_NM 명V200 · 이벤트명=EVNT_NM 명V200
--                지방자치단체코드=LCLGV_CD 코드V20 → VARCHAR(20)
--                  ※ RGN_NM(지역 표기명)·OG_CD(기관코드)와 <서로 다른 값>이다. 셋을 대체·통합하지 않는다.
--                    선존 LS_DATA_RAW.LCLGV_CD 도 V107 에서 코드V20 으로 정렬돼 있어 사내 정합도 맞는다.
--                출처유형=SRC_TYPE V20 (기존 등록분. 폐기안 VDO_SRC_CD 아님)
--                영상길이초=VDO_LEN_SEC · 프레임수=FRM_CNT · 너비=WDTH · 세로=VRTC  수N10 → NUMERIC(10)
--                  ※ 가로/세로 사업표준 짝은 WDTH/VRTC 다. 미등록 VDO_WDTH/VDO_HGT 는 폐기안(§9).
--                WGS84위도=WGS84_LAT · WGS84경도=WGS84_LOT  좌표D10 → DECIMAL(10,7)
--                CCTV높이=CCTV_HGT 수D5 → DECIMAL(4,1)
--                주감시방향값(도)=MAIN_SURV_PAN_ANG 수I11 → INTEGER
--                이벤트아이디=EVNT_ID 식별자V50 → VARCHAR(50)  (값이 'ABA_0001' 식별자형이라
--                  EVNT_TYPE_CD 가 아니다 — §9 폐기안)
--                파일형식=FILE_FMT · 프레임재생속도=FPS · 종횡비=ASPRT_RT
--                해상도=RESL · 비트값=BIT · 화소=PXL  (§8 도메인 지정 반영)
--                파일크기=FILE_SZ 수B20(사업)/수N14(공공) → BIGINT (관제 수신 바이트 수. 아래 [해소됨] 참조)
--
--   [검증 실시 2026-07-31 — docs/표준용어 CSV 4종 대조. 아래는 확인된 근거값]
--     · 일련번호B20 = BIGINT(사업)            → RCPTN_SN BIGINT            일치
--     · 원시영상일련번호 RAW_SN = N19          → BIGINT(19자리 수용)         일치
--     · 좌표D10 = DECIMAL(정수3, 소수7)        → WGS84_LAT/LOT DECIMAL(10,7) 일치(선존 LS_DATA_RAW 동일)
--     · 수D5   = DECIMAL(길이4, 소수1)         → CCTV_HGT DECIMAL(4,1)      일치
--     · 수I11  = INTEGER                      → MAIN_SURV_PAN_ANG INTEGER  일치
--     · 수N10  = NUMERIC(10)                  → VDO_LEN_SEC/FRM_CNT/WDTH/VRTC 일치
--     · 식별자V50 = VARCHAR(50)               → EVNT_ID                    일치
--     · CCTV명 CCTV_NM = 명V300(사업)          → VARCHAR(300)               일치
--       ※ 공공 공통표준용어의 CCTV명은 명V100 이나 사업표준용어 등록분(명V300)이 우선이며,
--         실측 cctv_nm max 66 이라 어느 쪽이든 절삭 없음.
--     · 동영상파일명 VDO_FILE_NM = 명V300(공공) → VARCHAR(300)              일치
--       ※ 사업표준용어에는 같은 약어의 '영상파일명 명V100' 도 있으나, 관제 실측 file_nm
--         max 113 자라 100 이면 <절삭>된다. 절삭되지 않는 등록 도메인(명V300)을 채택한다.
--     · 해상도=RESL 은 사업표준<단어> 등록분(공공은 RSL). 선존 LS_DATASET_VIDEO_META.RESL 은
--       VARCHAR(32) 로 드리프트가 있으나 이번 신설은 명V20 정본을 따른다(기존 드리프트 미러 금지).
--     · FILE_FMT/RESL/BIT/PXL 은 용어사전 <도메인 미지정> 상태다(§8 실행항목 #1 — 저작도구 담당).
--       설계 확정값(코드V20/명V20 = VARCHAR(20))으로 선반영하며, 사전 등록은 별도 트랙이다.
--
--   [잔여 확인 필요 — 설계 확정값을 그대로 따르되 사실만 기록. 임의 변경하지 않는다]
--     · RTY_CNT : 사업표준용어 '재시도횟수 RTY_CNT' 의 등록 타입은 N/10(=NUMERIC(10))인데
--       설계 §4-1 은 INTEGER 로 확정했다(주석은 N10 표기). 재시도 카운터 값역에서 실질 차이는
--       없으나 감리 관점 드리프트 소지가 있어 명시해 둔다. 또 선존 저작도구 테이블들은 같은
--       의미에 공공 조합형 RTRY_NMTM(V60 개명)을 쓰고 있어 물리명 축도 이원화돼 있다.
--   [해소됨 2026-07-31 — FILE_SZ 는 수치(BIGINT)로 정정. 위 VARCHAR(20) 안은 폐기]
--     · FILE_SZ 는 등록 표준용어이고 도메인이 공공 수N14 · 사업 수B20 으로 <둘 다 수치>다.
--       등록 물리명을 쓰면서 타입만 문자열로 이탈하면 표준 위반이다. 근거 3건이 모두 수치를 가리킨다.
--         1) 어노테이션 스펙의 filesize 는 Type = number 다('4800KB' 는 작성예시(출력 표기)일 뿐이며
--            스펙 자체가 Type 과 예시가 어긋나는 자기모순임이 이미 기록돼 있다 — 예시가 아니라 Type 을 따른다).
--         2) 관제 실컬럼 v2_video_assets.file_sz = bigint(바이트).
--         3) 사업표준도메인 수B20 = BIGINT.
--       '4800KB' 표기는 <export 직렬화 단계>에서 만든다. 인입은 관제 원본 단위(바이트)를 그대로 받는다.
--       이는 앞서 VDO_LEN VARCHAR(6)('10M') → VDO_LEN_SEC NUMERIC(10)(초) 로 되돌린 것과 동일한 판단이며,
--       선존 LS_DATASET_VIDEO_META.FILE_SZ · LS_PORTAL_ULD.FILE_SZ 가 이미 BIGINT 라 사내 정합도 맞는다.
--
-- ★ BIT 컬럼명 — PostgreSQL 에서 <인용 불필요>(실측 확인).
--   BIT 는 col_name_keyword(“함수·타입명으로는 못 쓰지만 컬럼명으로는 쓸 수 있는” 부류)라
--   CREATE/INSERT/SELECT 모두 무인용으로 파싱된다. 오히려 "BIT" 로 큰따옴표 인용하면 대문자
--   식별자가 고정돼 이후 모든 참조에 인용이 강제되고 나머지 컬럼(무인용→소문자 폴딩)과
--   규칙이 갈린다. 컬럼명 자체는 표준용어라 변경 금지이므로 <무인용 그대로> 둔다.
--   ※ 의미 주의 — 어노테이션 스펙의 bit 은 색심도('24bit')이며 비트레이트가 아니다(§10).
--
-- DEFAULT 판단(관제가 INSERT 주체라는 점 반영):
--   · PROC_STTS_CD DEFAULT 'PENDING' — 관제는 우리 처리 상태를 모른다. 넣지 않아도 폴링 대상이
--     되어야 하므로 기본값을 준다. 관제가 이 컬럼을 안다는 전제를 두지 않는다.
--   · RTY_CNT DEFAULT 0 · RCPTN_DT DEFAULT CURRENT_TIMESTAMP — 동일 사유(우리 운영 컬럼).
--   · SRC_TYPE 은 <DEFAULT 를 두지 않는다>. 출처유형은 관제만 아는 값이고, 기본값을 주면 관제가
--     빠뜨렸을 때 조용히 틀린 출처로 적재된다(§4-2 의 경계축은 "누가 만들었나"라 오판 비용이 크다).
--     누락은 NOT NULL 위반으로 즉시 드러나는 편이 낫다(fail-closed).
--
-- 인입 행은 <영구 보존>한다(감사 추적). 삭제하면 "분명 넣었다" 분쟁에 대조 근거가 없고
--   중복 INSERT 감지도 못 한다(§6-1). 그래서 RAW_SN 에 FK 를 걸지 않는다 — 영상 행이 정리돼도
--   수신 기록 자체는 남아야 하며, 선존 LS_CONTROL_NOTIFY_FALLBACK·LS_MON_NOTI_ACML 와 동일 방침이다.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. 멱등(IF NOT EXISTS).
-- =============================================================================

CREATE TABLE IF NOT EXISTS LS_DATA_INGEST (
    -- ---- 저작도구 운영 (8) — 인입 행의 처리 상태 ----
    RCPTN_SN           BIGINT        NOT NULL GENERATED BY DEFAULT AS IDENTITY,
    RCPTN_DT           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PROC_STTS_CD       VARCHAR(20)   NOT NULL DEFAULT 'PENDING',
    RAW_SN             BIGINT,
    RTY_CNT            INTEGER       NOT NULL DEFAULT 0,
    PRCS_DT            TIMESTAMP,
    NEXT_RTRY_DT       TIMESTAMP,
    ERR_MSG            VARCHAR(4000),

    -- ---- 관제 수신 (29) ----
    VMS_CLIP_ID        VARCHAR(128)  NOT NULL,
    VMS_CCTV_ID        VARCHAR(64)   NOT NULL,
    VDO_FILE_NM        VARCHAR(300)  NOT NULL,
    RAW_FILE_PATH_NM   VARCHAR(500)  NOT NULL,
    SRC_TYPE           VARCHAR(20)   NOT NULL,
    SHT_DT             TIMESTAMP,
    FILE_FMT           VARCHAR(20),
    VDO_CDC            VARCHAR(20),
    FILE_SZ            BIGINT,
    RGN_NM             VARCHAR(200),
    VDO_LEN_SEC        NUMERIC(10),
    FPS                VARCHAR(10),
    FRM_CNT            NUMERIC(10),
    ASPRT_RT           VARCHAR(20),
    WDTH               NUMERIC(10),
    VRTC               NUMERIC(10),
    RESL               VARCHAR(20),
    BIT                VARCHAR(20),
    PXL                VARCHAR(20),
    WGS84_LAT          DECIMAL(10,7),
    WGS84_LOT          DECIMAL(10,7),
    OG_CD              VARCHAR(20),
    CCTV_NM            VARCHAR(300),
    CCTV_HGT           DECIMAL(4,1),
    MAIN_SURV_PAN_ANG  INTEGER,
    EVNT_ID            VARCHAR(50),
    EVNT_NM            VARCHAR(200),
    MNTR_CN            VARCHAR(4000),
    LCLGV_CD           VARCHAR(20),

    CONSTRAINT PK_LS_DATA_INGEST PRIMARY KEY (RCPTN_SN),
    -- 관제 재송신·중복 INSERT 방어. 영상 1건(클립)당 인입 1행.
    CONSTRAINT UK_LS_DATA_INGEST_CLIP UNIQUE (VMS_CLIP_ID)
);

-- 폴링 배치(ControlTrainingVideoScanJob)의 후보 스캔 전용 부분 인덱스.
-- 인입 행을 영구 보존하므로 처리 완료분이 계속 쌓인다. 전체 인덱스면 미처리 소수를 찾는 데
-- 완료분까지 싣게 되므로, 술어를 PENDING 으로 좁혀 인덱스 크기를 미처리 건수에 비례시킨다.
--
-- ★ 폴링 술어와 인덱스를 <일치>시킨다 (설계 §6-0-1-a ㉢).
--   실제 술어는  PROC_STTS_CD='PENDING' AND (NEXT_RTRY_DT IS NULL OR NEXT_RTRY_DT <= now)
--                ORDER BY RCPTN_DT, RCPTN_SN  LIMIT n  이다.
--   · 부분 인덱스 술어에는 PROC_STTS_CD 만 넣는다 — now 는 immutable 이 아니라 인덱스 술어에
--     넣을 수 없다(넣으면 CREATE INDEX 자체가 실패한다). 대신 NEXT_RTRY_DT 를 INCLUDE 로 실어
--     힙 방문 없이 인덱스에서 걸러낸다(고착 행이 많을수록 이득이 크다).
--   · 키를 (RCPTN_DT, RCPTN_SN) 으로 두어 정렬을 인덱스 순서로 만족시킨다. PROC_STTS_CD 는
--     부분 인덱스 술어로 이미 상수라 선두 키로 둘 이유가 없다(구 정의의 잉여 컬럼 제거).
CREATE INDEX IF NOT EXISTS IX_LS_DATA_INGEST_POLL
    ON LS_DATA_INGEST (RCPTN_DT, RCPTN_SN) INCLUDE (NEXT_RTRY_DT)
    WHERE PROC_STTS_CD = 'PENDING';

COMMENT ON TABLE  LS_DATA_INGEST                    IS '관제 인입 — 관제서버가 학습용 영상 메타를 직접 INSERT 하는 수신 창구. 행은 감사 추적을 위해 영구 보존한다.';
COMMENT ON COLUMN LS_DATA_INGEST.RCPTN_SN           IS '수신일련번호(PK). 관제가 미지정 시 IDENTITY 발급.';
COMMENT ON COLUMN LS_DATA_INGEST.RCPTN_DT           IS '수신일시. 폴링 순서(FIFO) 기준.';
COMMENT ON COLUMN LS_DATA_INGEST.PROC_STTS_CD       IS '저작도구 처리상태코드. PENDING(미처리) 기본. 관제는 이 컬럼을 채우지 않아도 된다.';
COMMENT ON COLUMN LS_DATA_INGEST.RAW_SN             IS '적재 결과 영상 식별자(LS_DATA_RAW.RAW_SN). 적재 전 NULL. FK 는 걸지 않는다 — 영상 정리 후에도 수신 기록은 남아야 한다.';
COMMENT ON COLUMN LS_DATA_INGEST.RTY_CNT            IS '적재 재시도 횟수. 파일 미도착은 실패가 아니라 미처리로 두고 다음 주기에 재시도한다.';
COMMENT ON COLUMN LS_DATA_INGEST.PRCS_DT            IS '처리일시 — 저작도구가 이 행을 처리한 시각. 종결(적재 완료/실패) 시각이자, 미처리 상태에서는 <최초 파일 미도착 관측 시각>(대기 예산 앵커)이다. 대기 상한은 관제가 준 RCPTN_DT 가 아니라 이 값 기준으로 잰다(설계 §6-0-1-a ㉠ — RCPTN_DT 는 INSERT 주체가 관제라 과거 시각이 들어오면 도착 즉시 종결된다). 재큐 시 NULL 로 비워 예산을 리셋한다.';
COMMENT ON COLUMN LS_DATA_INGEST.NEXT_RTRY_DT       IS '다음 재시도 예정 일시. 폴링 후보는 PENDING AND (이 값 NULL OR <= 현재)다 — 파일 미도착 관측 시 이 값을 뒤로 밀어(backoff) 고착 행이 FIFO 앞자리를 잠식하지 못하게 한다(설계 §6-0-1-a ㉢). 재큐 시 NULL 로 비운다.';
COMMENT ON COLUMN LS_DATA_INGEST.ERR_MSG            IS '적재 실패 사유(요약). 절대경로/시크릿/스택트레이스 미포함.';
COMMENT ON COLUMN LS_DATA_INGEST.VMS_CLIP_ID        IS 'VMS 클립 아이디(관제 video.id). 중복 INSERT 방어 UK.';
COMMENT ON COLUMN LS_DATA_INGEST.VMS_CCTV_ID        IS 'VMS CCTV 아이디(관제 video.cctv_mng_no).';
COMMENT ON COLUMN LS_DATA_INGEST.VDO_FILE_NM        IS '동영상 파일명(관제 video.filename).';
COMMENT ON COLUMN LS_DATA_INGEST.RAW_FILE_PATH_NM   IS '원시 파일 경로명 — 관제 NAS 실경로(strg_uri).';
COMMENT ON COLUMN LS_DATA_INGEST.SRC_TYPE           IS '출처유형(RELAY/USER_ULD/GENERATED/ORIGINAL). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. AUGMENTED 는 저작도구 파생이라 인입으로 오지 않는다.';
COMMENT ON COLUMN LS_DATA_INGEST.SHT_DT             IS '촬영일시(관제 video.date_created).';
COMMENT ON COLUMN LS_DATA_INGEST.FILE_FMT           IS '파일형식(관제 video.type).';
COMMENT ON COLUMN LS_DATA_INGEST.VDO_CDC            IS '영상코덱(관제 video.format).';
COMMENT ON COLUMN LS_DATA_INGEST.FILE_SZ            IS '파일크기 — 관제 수신 바이트 수(관제 v2_video_assets.file_sz, bigint). 어노테이션 작성예시의 ''4800KB'' 표기는 export 직렬화 단계에서 생성한다(인입은 원본 단위 그대로 받는다).';
COMMENT ON COLUMN LS_DATA_INGEST.RGN_NM             IS '지역명(관제 video.location). 동은 포함하지 않는다.';
COMMENT ON COLUMN LS_DATA_INGEST.VDO_LEN_SEC        IS '영상길이(초). 관제 원본은 소수 2자리이나 등록 도메인 수N10 에 맞춰 정수 초로 수신한다. 밀리초 정밀도는 LS_DATA_RAW.VDO_LEN_MS 담당.';
COMMENT ON COLUMN LS_DATA_INGEST.FPS                IS '프레임재생속도(관제 video.fps).';
COMMENT ON COLUMN LS_DATA_INGEST.FRM_CNT            IS '프레임수(관제 video.frames).';
COMMENT ON COLUMN LS_DATA_INGEST.ASPRT_RT           IS '종횡비(관제 video.aspect_ratio). 예 4:3.';
COMMENT ON COLUMN LS_DATA_INGEST.WDTH               IS '영상 너비(px, 관제 video.width).';
COMMENT ON COLUMN LS_DATA_INGEST.VRTC               IS '영상 세로(px, 관제 video.height).';
COMMENT ON COLUMN LS_DATA_INGEST.RESL               IS '해상도 표기(관제 video.resolution). 어노테이션 resolution/pixel 두 등급 표기의 파생 원천.';
COMMENT ON COLUMN LS_DATA_INGEST.BIT                IS '비트값 = 색심도 표기(관제 video.bit). 예 24bit. 비트레이트가 아니다. PostgreSQL 예약 여부 — 컬럼명으로는 무인용 사용 가능(인용 금지).';
COMMENT ON COLUMN LS_DATA_INGEST.PXL                IS '화소 표기(관제 video.pixel). 예 4K.';
COMMENT ON COLUMN LS_DATA_INGEST.WGS84_LAT          IS 'WGS84 위도(관제 video.coordinates 분해).';
COMMENT ON COLUMN LS_DATA_INGEST.WGS84_LOT          IS 'WGS84 경도(관제 video.coordinates 분해).';
COMMENT ON COLUMN LS_DATA_INGEST.OG_CD              IS '기관코드(관제 resource_cctvs 조인). 촬영 시점 값 고정 목적의 의도된 중복 저장.';
COMMENT ON COLUMN LS_DATA_INGEST.CCTV_NM            IS 'CCTV명(관제 resource_cctvs 조인). 촬영 시점 값 고정.';
COMMENT ON COLUMN LS_DATA_INGEST.CCTV_HGT           IS 'CCTV 설치 높이(m). 촬영 시점 값 고정 — 카메라 교체 시 과거 영상 오염 방지.';
COMMENT ON COLUMN LS_DATA_INGEST.MAIN_SURV_PAN_ANG  IS '주감시방향값(도, 관제 cctv_azimuth). 촬영 시점 값 고정 — 방위각 재설정 시 과거 영상 오염 방지.';
COMMENT ON COLUMN LS_DATA_INGEST.EVNT_ID            IS '이벤트 아이디(관제 video.event_id). 예 ABA_0001. 이벤트유형코드가 아니다.';
COMMENT ON COLUMN LS_DATA_INGEST.EVNT_NM            IS '이벤트명(관제 video.event_name).';
COMMENT ON COLUMN LS_DATA_INGEST.MNTR_CN            IS '관제일지 내용(관제 video.event_log).';
COMMENT ON COLUMN LS_DATA_INGEST.LCLGV_CD           IS '지방자치단체코드. LS_DATA_RAW.LCLGV_CD 의 원천이며 관제 완료통지 페이로드 lclgv_cd(required)가 이 값에서 나온다. 지역명(RGN_NM)·기관코드(OG_CD)와 서로 다른 값이다.';
