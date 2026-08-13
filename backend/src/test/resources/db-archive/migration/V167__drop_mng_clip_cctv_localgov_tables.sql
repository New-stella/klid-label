-- =============================================================================
-- V167: 관제 공유 클립·CCTV·지자체 마스터 4종 제거
--       MNG_CLIP_EVNT_LST · MNG_CLIP_MASTER · MNG_RESOURCE_CCTV · MNG_EX_LOCAL_GOV
--
-- 배경(관제 2차 — 적재 주체 반전): 관제서버가 학습용 영상을 저작도구에 <직접 INSERT>하는 구조로
--   바뀌면서(LS_DATA_INGEST, V147), 저작도구가 남의 스키마를 조회해 영상 메타를 보강하던 경로가
--   모두 잔존 결합이 됐다. V166 이 인입에 EVNT_TYPE_CD·개인정보 3필드를 신설해 <대체 통로>를
--   먼저 만들었고, 이 마이그레이션이 구 통로를 걷어낸다.
--
--   조회 경로 전환(같은 커밋):
--     * CCTV명·좌표   MNG_RESOURCE_CCTV        → LS_DATA_INGEST.CCTV_NM / WGS84_LAT / WGS84_LOT
--     * 파일형식      MNG_CLIP_MASTER.FILE_FMT → LS_DATA_INGEST.FILE_FMT
--     * 지자체명      MNG_EX_LOCAL_GOV         → LS_DATA_INGEST.RGN_NM (단일 필드)
--     * 이벤트유형    MNG_CLIP_EVNT_LST 조인 해석(V166 과도기 폴백) → LS_DATA_INGEST.EVNT_TYPE_CD 단독
--   영상↔인입 연결 규칙(파생영상 ORGNL_RAW_SN 1단계 폴백 + LATERAL 단건 보장)은 Java 쪽
--   video/repository/IngestSourceLink 한 곳에만 있다(복제 금지).
--
-- 데이터 영향(유실 위험 없음):
--   * MNG_EX_LOCAL_GOV      실DB 0행 — 이 테이블로 채우던 응답 필드(sidoNm/sggNm/lclgv_nm)는
--                           <제거 전에도 이미 항상 null> 이었다(동작 회귀 0).
--   * MNG_RESOURCE_CCTV     내용은 dev-seed 시드값뿐(관제 실DB 에는 이 이름의 테이블이 없다 —
--                           관제 2차 전면 개명). 운영에서 기록을 남기는 주체가 없다.
--   * MNG_CLIP_MASTER       구 적재 소스(JOB_DMND_YN='Y' 스캔). 적재 주체 반전으로 스캔은 폐지됐고
--                           dev-seed 참조 데이터만 남아 있었다.
--   * MNG_CLIP_EVNT_LST     V63 이 만든 3컬럼 stub. V164 백필과 적재 과도기 폴백에서만 읽혔다.
--   같은 커밋에서 db/seed/dev-seed.sql · src/test/resources/db/test-data-video.sql 의 해당
--   INSERT/DELETE 도 함께 제거한다(누락 시 IT 가 시드 단계에서 전량 실패).
--
-- 소유권(★ "MNG_* 변경은 관제서버팀 선승인 필수" 규칙과의 관계): 4종 모두 이름만 MNG_ 접두일 뿐
--   <저작도구가 자기 스키마에 V2/V62/V63 으로 직접 CREATE 한 스텁>이다. 관제 2차 실DB 에는
--   MNG_ 접두 테이블이 0개이며(전면 개명), 저작도구는 관제 DB 를 조회하지 않는다. 따라서 이 DROP 은
--   공유 스키마 변경이 아니라 자체 소유 객체 정리이므로 관제서버팀 선승인 대상이 아니다.
--   데이터마트 뷰(V_COMPLETED_*)도 이 4종을 참조하지 않으므로 관제 연동 계약에 영향이 없다.
--
-- Flyway 순서 안전성(신규 설치 포함): 적용 순서가
--     V2(CREATE: CLIP_MASTER·RESOURCE_CCTV·EX_LOCAL_GOV)
--       → V62(CLIP_MASTER stub 교정) → V63(CREATE: CLIP_EVNT_LST)
--       → V164(백필 — CLIP_EVNT_LST JOIN) → V167(DROP, 이 파일)
--   이므로 <V164 실행 시점에는 MNG_CLIP_EVNT_LST 가 반드시 존재>한다. 빈 DB 신규 설치에서도
--   V164 가 "relation does not exist" 로 깨지지 않는다. V2/V62/V63/V164 는 이미 적용된 이력이라
--   내용을 수정할 수 없다(체크섬 불일치 → 2노드 기동 실패).
--
-- DROP 순서: 참조하는 쪽 → 참조받는 쪽.
--   MNG_CLIP_EVNT_LST(EVNT_ID 로 CLIP_MASTER 를 가리킴) → MNG_CLIP_MASTER
--   → MNG_RESOURCE_CCTV(CLIP_MASTER.VMS_CCTV_ID 가 가리킴) → MNG_EX_LOCAL_GOV(LCLGV_CD).
--   V2/V62/V63 기준으로 물리 FK 는 선언돼 있지 않으나, 환경에 수동 FK 가 추가돼 있어도 안전하도록
--   참조 방향과 일치시킨다.
--
-- 잠금 주의(2노드 Active-Active): DROP TABLE 은 ACCESS EXCLUSIVE 락을 잡는다. 4종 모두 행수가
--   0~수십 규모라 잠금 구간이 매우 짧고, 이 커밋 이후 런타임 참조가 0 이라 실질 영향은 없다.
--
-- 범위 한정(★): 이 마이그레이션은 위 <4개 테이블만> 건드린다. 인입 원장(LS_DATA_INGEST)·영상
--   (LS_DATA_RAW)·사용자 마스터(MNG_ACCT_USER)·이벤트유형 마스터(MNG_EX_EVNT_TYPE, MNG_EX_EVNT_TYPE_MAP)
--   는 절대 건드리지 않는다 — 뒤 두 종은 별도 Phase 에서 distinct 전환과 함께 제거할 대상이고,
--   여기서 섞으면 이벤트 필터가 통째로 죽는다. 회귀 가드: MngControlMasterTableRemovalTest.
--
-- ★ 롤백 절차 (Critical — 앱을 이 마이그레이션 적용 <이전> 버전으로 내릴 때 필수)
--   구버전 코드에는 @Table(name = "MNG_RESOURCE_CCTV") / @Table(name = "MNG_EX_LOCAL_GOV") 엔티티와
--   MNG_CLIP_EVNT_LST · MNG_CLIP_MASTER 를 읽는 native 쿼리가 존재한다. 스키마를 되돌리지 않고
--   구버전 jar 로 롤백하면 조회 시점에 "relation does not exist" 로 적재·목록·동결이 깨진다
--   (ddl-auto=validate 는 이 프로젝트에서 실제로 수행되지 않아 기동은 성공하고 <런타임에> 터진다 —
--   더 늦게 발견되므로 더 위험하다). Flyway 는 down-migration 을 수행하지 않으므로, 아래 재생성
--   SQL 을 <구버전 재설치 전에> DBA 가 수동 적용해야 한다. DDL 은 V2/V62/V63 원문과 컬럼 단위로
--   동일하다. (운영 절차 문서: deploy/onprem/docs/07-uninstall-rollback.md § 롤백)
--
--     -- 1) 테이블 재생성 (구버전 조회 경로 복구용) — 생성 순서는 DROP 의 역순.
--     CREATE TABLE IF NOT EXISTS public.MNG_EX_LOCAL_GOV (
--         LCLGV_CD        VARCHAR(32)     NOT NULL,
--         SIDO_NM         VARCHAR(64),
--         SGG_NM          VARCHAR(64),
--         USE_YN          VARCHAR(1)      NOT NULL DEFAULT 'Y',
--         PRIMARY KEY (LCLGV_CD)
--     );
--     CREATE TABLE IF NOT EXISTS public.MNG_RESOURCE_CCTV (
--         VMS_CCTV_ID     VARCHAR(64)     NOT NULL,
--         CCTV_NM         VARCHAR(255),
--         SHT_ADDR        VARCHAR(500),
--         OG_NM           VARCHAR(255),
--         WGS84_LAT       DECIMAL(10,7),
--         WGS84_LOT       DECIMAL(10,7),
--         RESOLUTION      VARCHAR(32),
--         USE_YN          VARCHAR(1)      NOT NULL DEFAULT 'Y',
--         PRIMARY KEY (VMS_CCTV_ID)
--     );
--     -- ※ MNG_CLIP_MASTER 는 <V62 교정 후 스키마>가 정본이다(V2 의 CLIP_SN 스텁이 아니다).
--     CREATE TABLE IF NOT EXISTS public.MNG_CLIP_MASTER (
--         EVNT_ID       VARCHAR(50)   NOT NULL,
--         CLIP_TYPE_CD  VARCHAR(20)   NOT NULL,
--         CLIP_ID       VARCHAR(50),
--         LCLGV_CD      VARCHAR(20),
--         FILE_NM       VARCHAR(256),
--         FILE_PATH     VARCHAR(1000),
--         FILE_FMT      VARCHAR(10),
--         VDO_LEN_SEC   INT,
--         CLIP_STTS_CD  VARCHAR(20),
--         CRT_DT        TIMESTAMP,
--         ULD_CMPT_DT   TIMESTAMP,
--         JOB_DMND_YN   VARCHAR(1),
--         VMS_CCTV_ID   VARCHAR(30),
--         PRIMARY KEY (EVNT_ID, CLIP_TYPE_CD)
--     );
--     CREATE INDEX IF NOT EXISTS IX_MNG_CLIP_MASTER_JOB_DMND
--         ON public.MNG_CLIP_MASTER (JOB_DMND_YN);
--     CREATE TABLE IF NOT EXISTS public.MNG_CLIP_EVNT_LST (
--         EVNT_ID       VARCHAR(50)  NOT NULL,
--         EVNT_TYPE_CD  VARCHAR(20)  NOT NULL,
--         SHT_DT        TIMESTAMP,
--         PRIMARY KEY (EVNT_ID, EVNT_TYPE_CD)
--     );
--     -- 2) ★백필 행 되돌리기 — 이 마이그레이션이 만든 행만 sentinel 로 식별해 지운다.
--     --    관제 실수신분은 ERR_MSG 에 이 접두가 없으므로 절대 지워지지 않는다.
--     --    ※ 이 DELETE 는 롤백 전용이 아니다 — 백필이 <1회성 테스트 편의> 조치이므로,
--     --      테스트 종료 후 정리(cleanup)에도 그대로 쓰는 정상 출구다.
--     --    (먼저 SELECT 로 건수를 확인한 뒤 DELETE 할 것)
--     SELECT COUNT(*) FROM LS_DATA_INGEST WHERE ERR_MSG LIKE '[V167-BACKFILL]%';
--     DELETE FROM LS_DATA_INGEST WHERE ERR_MSG LIKE '[V167-BACKFILL]%';
--     -- 3) 인덱스 제거(선택 — 남겨도 구버전 동작에 영향 없다)
--     DROP INDEX IF EXISTS IX_LS_DATA_INGEST_RAW_SN;
--     -- 4) Flyway 이력에서 이 버전 제거 — 남겨두면 구버전 앱이 "적용됐는데 파일이 없다"로 기동 실패한다.
--     DELETE FROM flyway_schema_history WHERE version = '167';
--
--   ※ 백필분은 복원 대상이 아니다(1회성 테스트 편의 조치) — 위 sentinel DELETE 로 지우면 끝이다.
--   ※ 데이터 복원은 dev/local 한정으로만 필요하다 — 4종의 내용은 전부 dev-seed 시드값이고
--     (MNG_EX_LOCAL_GOV 는 0행), 운영에서 이 테이블에 기록을 남기는 주체가 없다. 구버전 롤백 후
--     dev 시드를 재실행하면 그대로 복원된다.
--   ※ 롤백 후 다시 상위 버전으로 올릴 때는 이 마이그레이션이 그대로 재적용된다(IF EXISTS 로 멱등).
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 인입 역조회 인덱스 — 새 조회 경로(LATERAL 조인)가 타는 축.
--
--    조회 경로 전환으로 아래 5개 지점이 전부 LS_DATA_INGEST 를 RAW_SN 으로 역조회한다:
--      VideoRepository.findCctvNamesByRawSnsInternal / findEventInfoByRawSnsInternal
--      DatasetMetaSourceRepository.findSnapshotSource
--      Assignment/TaskBoard/Review 의 videoNameLike (EXISTS 서브쿼리)
--    그런데 V147 이 만든 인덱스는 폴링용 부분 인덱스(IX_LS_DATA_INGEST_POLL)와 클립 UK 뿐이라
--    RAW_SN 축에는 인덱스가 <없었다>. 이 테이블은 감사 목적 영구 보존이라 행이 무한 증가하므로
--    시간이 갈수록 악화되는 seq scan 이 된다.
--
--    실측(PostgreSQL 16, 영상 20만 / 인입 25만, 목록 1페이지=20건 조회):
--      인덱스 없음 : Seq Scan, Rows Removed by Filter 249,999 × 20 loops → 302.153 ms
--      이 인덱스   : Index Scan (Index Cond: RAW_SN = COALESCE(...))      →   0.090 ms
--    ★정렬(RCPTN_SN DESC)을 인덱스에 포함해야 LATERAL 의 "ORDER BY RCPTN_SN DESC LIMIT 1"
--      까지 인덱스로 해결되어 Sort 노드가 사라진다(실측에서 Sort 없음 확인).
--
--    부분 인덱스(WHERE RAW_SN IS NOT NULL)도 실측했다 — 조인 술어가 등치비교라 planner 가
--    NOT NULL 을 증명해 <실제로 사용되고>(0.096 ms) 크기도 20% 작다(6176kB vs 7712kB).
--    그럼에도 채택하지 않은 이유: 채택 전제인 "미적재(RAW_SN NULL) 행이 다수"가 성립하지 않는다
--    (local dev 인입 5행 중 1행, 246 dev 0행). 전체 인덱스는 향후 RAW_SN IS NULL 스윕 같은
--    다른 술어도 커버해 더 견고하다. 운영에서 NULL 비중이 커지면 부분 인덱스로 교체 가능하다.
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS IX_LS_DATA_INGEST_RAW_SN
    ON LS_DATA_INGEST (RAW_SN, RCPTN_SN DESC);

-- -----------------------------------------------------------------------------
-- 2) ★기존 영상의 CCTV 평면값 백필 — <1회성 테스트 편의 조치> (사용자 지시 2026-08-04)
--
--    ⚠⚠ 이것은 항구적 설계 결정이 아니다. "인입 원장에 백필해도 된다"는 승인된 패턴으로
--        일반화하지 말 것.
--
--    목적(사용자 원문 취지): <지금 테스트를 진행 중이라> 기존 테스트 데이터가 휘발되면 테스트가
--      불가능해진다. 그것을 막기 위한 <1회성> 조치다.
--
--    배경 실측(246 dev) LS_DATA_RAW 57행 / MNG_RESOURCE_CCTV 13행 / LS_DATA_INGEST <0행>.
--      기존 영상은 100% 마스터 조인으로 이름을 얻고 있었는데 인입이 비어 있어, 이 마이그레이션
--      직후 전부 VMS_CCTV_ID 폴백으로 떨어진다(화면 영상명이 "이름 → ID" 로 격하).
--      local dev 도 동일 양상(raw 4행 / cctv 13행 / ingest 5행).
--
--    ⚠ 성질상 인입 원장은 "관제가 보낸 것을 그대로 보관"하는 수신 기록인데, 이 백필은 관제가
--      보낸 적 없는 행을 만든다. 1회성이고 테스트 목적이라 감수하되, 아래 제약으로 범위를
--      묶고 무엇보다 <백필분을 관제 실수신분과 구분 가능>하게 만든다.
--
--    ★정리(cleanup) 경로가 곧 이 조치의 출구다: 테스트가 끝나 이 행들이 더 이상 필요 없어지면
--      아래 롤백 절차의 sentinel DELETE 로 <관제 실수신분은 건드리지 않고> 백필분만 지운다.
--      관제가 평면값을 실제로 보내기 시작하면 그 시점에 정리하는 것이 정상 동선이다.
--
--    ★백필 행 식별 = ERR_MSG 의 고정 sentinel '[V167-BACKFILL]' 접두.
--      - 왜 ERR_MSG 인가: 인입 8개 운영컬럼 중 유일한 자유 텍스트이고, <어떤 코드도 이 컬럼을
--        읽지 않는다>(write-only — markFailed 가 쓰고 requeue/revert 가 비울 뿐, 분기 조건에
--        쓰이는 곳이 0). 따라서 표식을 넣어도 런타임 동작이 바뀌지 않는다.
--      - 표식이 지워질 위험: requeue 2경로가 ERR_MSG=NULL 로 비우지만 <둘 다 술어가
--        PROC_STTS_CD='FAILED'> 다. 백필 행은 'DONE'(종결 성공)이라 그 경로에 닿지 않는다.
--      - 신규 컬럼을 만들지 않았다(표준용어 게이트 + 설계 반전 회피).
--
--    제약:
--      - DROP 문보다 <먼저> 실행한다(마스터가 살아 있어야 값을 읽는다).
--      - 원본만: ORGNL_RAW_SN IS NULL. 파생에 인입 행을 만들면 "파생은 자기 인입 행이 없다"는
--        불변식이 깨지고 개인정보 3필드 CASE 판정(IngestSourceLink)이 무의미해진다.
--        파생은 부모 폴백으로 이미 이름을 얻으므로 백필이 필요 없다.
--      - 멱등: NOT EXISTS(같은 RAW_SN 인입 행) + ON CONFLICT (VMS_CLIP_ID) DO NOTHING.
--        재실행·부분 실패 후 재개에서 중복이 생기지 않는다.
--      - UK 충돌: UK_LS_DATA_INGEST_CLIP(VMS_CLIP_ID) 이 있다. 같은 클립의 인입 행이 이미
--        있는데 RAW_SN 만 비어 있는 경우(미적재/실패 이력) DO NOTHING 으로 <건너뛴다> —
--        그 행이 진짜 관제 수신분이므로 위조본으로 덮지 않는다.
--      - ★개인정보 3필드(ANONY/PSDO/PRVC_INCL_YN)는 채우지 않는다. 마스터에 없던 값이라
--        지어내면 export 원천 판정이 근거 없는 값을 싣는다(과소/과대 신고).
--
--    NOT NULL 컬럼을 채운 근거 (지어낸 값 없음 — 전부 기존 행에서 도출):
--      VMS_CLIP_ID     ← r.VMS_CLIP_ID          (관제가 실제로 보냈던 멱등키 그대로)
--      VMS_CCTV_ID     ← r.VMS_CCTV_ID
--      RAW_FILE_PATH_NM← r.RAW_FILE_PATH_NM
--      VDO_FILE_NM     ← 위 경로의 basename <도출>. 경로가 '/' 로 끝나 빈 문자열이 되면
--                        VMS_CLIP_ID 로 폴백(실재 식별자 — 임의 문자열이 아니다).
--      SRC_TYPE        ← r.SRC_TYPE, 없으면 'ORIGINAL' <도출>. 대상이 ORGNL_RAW_SN IS NULL
--                        (=원본)로 한정돼 있어 도출이 성립한다.
--      RCPTN_DT        ← r.REG_DT. "관제가 보낸 수신 시각"을 우리는 모른다. 지어낸 현재시각
--                        대신 <그 영상이 우리 시스템에 들어온 시각>이라는 실측값을 쓴다.
--      PROC_STTS_CD    ← 'DONE'. 이미 적재된 영상이라 폴링 대상이 되면 안 된다(PENDING 이면
--                        폴러가 재적재를 시도한다).
-- -----------------------------------------------------------------------------
INSERT INTO LS_DATA_INGEST (
    RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
    RCPTN_DT, PRCS_DT, PROC_STTS_CD, ERR_MSG,
    CCTV_NM, WGS84_LAT, WGS84_LOT, RGN_NM, FILE_FMT,
    EVNT_TYPE_CD, SHT_DT, VDO_LEN_SEC, LCLGV_CD)
SELECT
    r.RAW_SN,
    r.VMS_CLIP_ID,
    r.VMS_CCTV_ID,
    COALESCE(NULLIF(regexp_replace(r.RAW_FILE_PATH_NM, '^.*/', ''), ''), r.VMS_CLIP_ID),
    r.RAW_FILE_PATH_NM,
    COALESCE(r.SRC_TYPE, 'ORIGINAL'),
    r.REG_DT,
    r.REG_DT,
    'DONE',
    '[V167-BACKFILL] 관제 수신분 아님 — 조회 경로 전환 시 기존 영상의 표시용 평면값을 관제 공유 마스터에서 이관한 행이다. 개인정보 3필드는 원천이 없어 비워 둔다.',
    c.CCTV_NM,
    c.WGS84_LAT,
    c.WGS84_LOT,
    NULLIF(TRIM(COALESCE(g.SIDO_NM, '') || ' ' || COALESCE(g.SGG_NM, '')), ''),
    cm.FILE_FMT,
    r.EVNT_TYPE_CD,
    r.SHT_DT,
    r.VDO_LEN_SEC,
    r.LCLGV_CD
FROM LS_DATA_RAW r
LEFT JOIN MNG_RESOURCE_CCTV c ON c.VMS_CCTV_ID = r.VMS_CCTV_ID
LEFT JOIN MNG_EX_LOCAL_GOV  g ON g.LCLGV_CD    = r.LCLGV_CD
LEFT JOIN LATERAL (
    SELECT m.FILE_FMT
      FROM MNG_CLIP_MASTER m
     WHERE m.CLIP_ID = r.VMS_CLIP_ID
     ORDER BY m.EVNT_ID DESC, m.CLIP_TYPE_CD DESC
     LIMIT 1
) cm ON TRUE
WHERE r.ORGNL_RAW_SN IS NULL
  AND NOT EXISTS (SELECT 1 FROM LS_DATA_INGEST i WHERE i.RAW_SN = r.RAW_SN)
ON CONFLICT (VMS_CLIP_ID) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 3) 관제 공유 이벤트 리스트 제거 (EVNT_ID 로 클립 마스터를 참조하던 쪽 먼저).
--    IF EXISTS — 이미 제거된 DB 에서는 no-op (멱등).
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_CLIP_EVNT_LST;

-- -----------------------------------------------------------------------------
-- 4) 관제 공유 클립 마스터 제거 (구 적재 소스 — 적재 주체 반전으로 스캔 폐지됨).
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_CLIP_MASTER;

-- -----------------------------------------------------------------------------
-- 5) CCTV 마스터 제거 (클립 마스터가 VMS_CCTV_ID 로 참조하던 쪽).
--    CCTV 명·좌표는 이제 LS_DATA_INGEST 평면값에서 온다.
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_RESOURCE_CCTV;

-- -----------------------------------------------------------------------------
-- 6) 지자체 마스터 제거 (실DB 0행 — 이 테이블이 채우던 값은 이미 항상 null 이었다).
--    지자체명은 이제 LS_DATA_INGEST.RGN_NM 단일 필드에서 온다.
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS MNG_EX_LOCAL_GOV;

-- -----------------------------------------------------------------------------
-- 7) 인입 이벤트유형코드 컬럼 주석 갱신 — V166 이 남긴 <과도기 폴백> 서술을 지운다.
--    V166 파일 자체는 이미 적용된 이력이라 고칠 수 없지만(체크섬), DB 의 주석은 이 문장으로
--    덮어써 최신 사실만 남긴다. 폴백이 사라진 지금 "EVNT_ID → 공유 이벤트리스트 해석으로
--    폴백한다"는 서술을 그대로 두면 운영자가 없는 경로를 찾게 된다.
-- -----------------------------------------------------------------------------
COMMENT ON COLUMN LS_DATA_INGEST.EVNT_TYPE_CD IS
    '이벤트유형코드(관제 수신). LS_DATA_RAW.EVNT_TYPE_CD 의 원천이며 마킹 진입 프리컨디션 값이다. EVNT_ID(식별자, 예 ABA_0001)와 서로 다른 값이다 — 대체·통합하지 않는다. ★이 컬럼이 유일한 조달처다: 관제가 채우지 않으면 null 로 적재되고 그 영상은 마킹 단계에서 차단된다(대용값을 넣지 않는다).';
