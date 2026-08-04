-- ============================================================
-- 개발/로컬 전용 시드 데이터 (klid_system)
-- ⚠ LOCAL/DEV ONLY — 절대 prd DB에 실행 금지
--
-- 목적: 실제 플로우(관제서버 영상 ingestion → 배치 파이프라인 → 라벨링 →
--       검수 → 비식별 신고)를 처음부터 실행하기 위한 마스터 데이터만 적재.
--
-- 남기는 것 (플로우 시작점):
--   - MNG_ACCT_USER         사용자 5명 (REVIEWER 2 / WORKER 2 / PORTAL 1)
--   - LS_USER_ROLE          사용자-역할 매핑 (저작도구 소유 — 인가 판정의 단일 진실원)
--   - MNG_RESOURCE_CCTV     CCTV 마스터 13건 (오토라벨 테스트·영상 ingestion 매칭)
--   - LS_LABEL              라벨 마스터 13건 (CVAT-Like 라벨 풀)
--   - LS_DATA_INGEST        관제 인입 미처리(PENDING) 3건 — ★파이프라인 시작점(§5-1)
--                           ※ RAW_FILE_PATH_NM 의 실파일을 먼저 만들어야 적재된다(§5-1 주석 참조)
--   - MNG_CLIP_MASTER/_EVNT_LST  참조 데이터로만 유지(§5-2) — 적재 유발 목적 아님
--
-- 지우는 것: ★없다. 이 시드는 <추가만> 한다 (아래 §0 참조).
--
-- 멱등성: 모든 INSERT 가 ON CONFLICT DO NOTHING / DO UPDATE 다(있으면 재생성하지 않는다).
--   → 재실행 안전. 업무 진행 결과와 운영 데이터는 건드리지 않음.
--
-- 시드 ID 정책:
--   - REVIEWER : 1001 (DevTokenService 기본값), 1002 (검수 배정용)
--   - WORKER   : 2001 (DevTokenService 기본값), 2002 (작업 배정 다양성)
--   - PORTAL   : 3001 (DevTokenService 기본값)
-- ============================================================

-- ============================================================
-- 0) ★★ 이 시드에는 DELETE 가 없다 — 되돌리지 말 것 (2026-08-04, 데이터 소실 사고 수정)
--
-- 구 동작: "시드 ID 범위 DELETE 후 INSERT" 로 멱등을 흉내냈다. 그중
--     DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'
--   는 V146 이 세운 자식 FK(ON DELETE CASCADE)를 타고 <그 영상 위에 쌓인 사용자 작업 결과>
--   (LS_DATA_SRC 프레임 · 상태 · 배정 · 비식별 로그 …)까지 통째로 지웠다.
--   게다가 뒤이은 INSERT 가 실패하면(실제 사례: LS_LABEL 유니크 충돌) 삭제만 커밋된 채
--   복구 INSERT 가 실행되지 않아 <재기동 = 작업 데이터 소실> 이 됐다.
--   실측 사고: 로컬에서 프레임 23건 전량 소실 + 라벨 57건이 죽은 SRC_SN 을 가리키는 고아로 잔존.
--
-- 새 규칙:
--   · 시드는 마스터/시작점 행을 <없을 때만> 넣는다(ON CONFLICT DO NOTHING / DO UPDATE).
--   · 업무 진행 결과(LS_DATA_RAW / LS_DATA_SRC / LS_DATA_LBL / 상태 / 배정 / 비식별 …)는
--     읽지도 지우지도 않는다. "존재하면 skip" 이 유일한 멱등 수단이다.
--   · 파이프라인을 다시 돌리고 싶으면 <재기동>이 아니라 재큐 API 를 쓴다:
--       POST /v1/control-ingests/{rcptnSn}/requeue        (단건)
--       POST /v1/control-ingests/requeue  {"limit":100}   (일괄)
--     완전 초기화가 필요하면 로컬 DB 볼륨을 지우고 다시 띄운다(명시적 파괴 행위여야 한다).
--   · DevSeedRunner 가 이 스크립트 전체를 <단일 트랜잭션>으로 실행한다 — 어느 구문이 실패해도
--     전부 롤백되어 부분 적용 상태가 남지 않는다.
-- ============================================================

-- 2) (삭제됨) 권한 코드 마스터 시드 — V165 로 MNG_ACCT_AUTHRT 테이블 자체가 제거됐다.
--    저작도구 역할 코드(REVIEWER/WORKER/PORTAL_USER)는 별도 마스터 테이블 없이
--    LS_USER_ROLE.ROLE_CD 화이트리스트로만 관리한다(아래 §4).

-- 3) 사용자 (5명)
--   1001 = DevTokenService.DEFAULT_USER_NO_REVIEWER (REVIEWER 기본)
--   2001 = DevTokenService WORKER 기본
--   3001 = DevTokenService PORTAL 기본
INSERT INTO MNG_ACCT_USER (USER_NO, USER_ID, USER_NM, USER_EMAIL, USE_YN, REG_DT) VALUES
    (1001, 'reviewer1', '김검수', 'reviewer1@cudo.co.kr', 'Y', '2026-02-01 09:00:00'),
    (1002, 'reviewer2', '이검수', 'reviewer2@cudo.co.kr', 'Y', '2026-02-01 09:00:00'),
    (2001, 'worker1',   '최라벨', 'worker1@cudo.co.kr',   'Y', '2026-02-05 09:00:00'),
    (2002, 'worker2',   '정작업', 'worker2@cudo.co.kr',   'Y', '2026-02-05 09:00:00'),
    (3001, 'portal1',   '홍길동', 'portal1@example.com',  'Y', '2026-03-01 09:00:00')
ON CONFLICT (USER_NO) DO UPDATE SET
    USER_NM    = EXCLUDED.USER_NM,
    USER_EMAIL = EXCLUDED.USER_EMAIL,
    USE_YN     = EXCLUDED.USE_YN;

-- 4) 사용자-역할 매핑 (LS_USER_ROLE) — 저작도구 소유, 인가 판정의 단일 진실원.
--   구 관제 매핑(MNG_ACCT_USER_AUTHRT) 시드는 V165 로 테이블이 제거되어 함께 삭제됐다.
--   읽기/쓰기 경로가 LS_USER_ROLE 로 전환됐으므로 dev 사용자도 LS 역할만 시드한다.
--   USER_NO 단일 PK — ON CONFLICT DO UPDATE 로 역할 재적용 멱등.
INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES
    (1001, 'REVIEWER',    '2026-02-01 09:00:00'),
    (1002, 'REVIEWER',    '2026-02-01 09:00:00'),
    (2001, 'WORKER',      '2026-02-05 09:00:00'),
    (2002, 'WORKER',      '2026-02-05 09:00:00'),
    (3001, 'PORTAL_USER', '2026-03-01 09:00:00')
ON CONFLICT (USER_NO) DO UPDATE SET ROLE_CD = EXCLUDED.ROLE_CD;

-- 5) CCTV 마스터 (MNG_RESOURCE_CCTV) — 영상 VMS_CCTV_ID 매칭용 한글 이름.
--   AssignmentResponse.cctvName 표시 및 작업/검수 목록의 "CCTV-{지자체}-{NN}" 노출.
--   ON CONFLICT DO NOTHING — 시드 재실행 시 PK 충돌 회피 (이미 존재하면 무시).
INSERT INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES
    ('CCTV-001', 'CCTV-강남구-001', 'Y'),
    ('CCTV-002', 'CCTV-강남구-002', 'Y'),
    ('CCTV-003', 'CCTV-강남구-003', 'Y'),
    ('CCTV-004', 'CCTV-강남구-004', 'Y'),
    ('CCTV-005', 'CCTV-강남구-005', 'Y'),
    ('CCTV-016', 'CCTV-서초구-016', 'Y'),
    ('CCTV-017', 'CCTV-서초구-017', 'Y'),
    ('CCTV-018', 'CCTV-서초구-018', 'Y'),
    ('CCTV-026', 'CCTV-송파구-026', 'Y'),
    ('CCTV-027', 'CCTV-송파구-027', 'Y'),
    ('CCTV-031', 'CCTV-마포구-031', 'Y'),
    ('CCTV-032', 'CCTV-마포구-032', 'Y'),
    ('CCTV-033', 'CCTV-마포구-033', 'Y')
ON CONFLICT (VMS_CCTV_ID) DO NOTHING;

-- 5-1) ★관제 인입 픽업 후보 (LS_DATA_INGEST) — Phase 3 적재 소스 교체 반영.
--   적재 소스가 MNG_CLIP_MASTER.JOB_DMND_YN='Y' 스캔 → 관제가 직접 INSERT 하는 인입 테이블로
--   바뀌었다(관제 2차 적재 주체 반전). 아래 MNG_CLIP_* 시드만으로는 픽업이 <0건>이므로,
--   dev/local 수동 파이프라인 드라이브가 조용히 죽지 않도록 인입 행을 시드한다.
--   - VMS_CLIP_ID 'DEV-CLIP-' 접두 고정(멱등키 = LS_DATA_RAW.VMS_CLIP_ID).
--   - PROC_STTS_CD='PENDING' 이어야 폴링 후보다(부분 인덱스 IX_LS_DATA_INGEST_POLL 술어와 동일).
--   - ★멱등은 ON CONFLICT (VMS_CLIP_ID) DO NOTHING 으로만 한다(§0). 이미 적재된 인입 행을
--     PENDING 으로 <되돌리지> 않는다 — 되돌리면 그 위의 영상·프레임·라벨을 지워야 재적재가
--     되고, 그게 바로 이번 소실 사고였다. 다시 픽업시키려면 재큐 API 를 쓸 것(§0).
--   - VDO_LEN_SEC 는 <이미 초>다(구 MNG_CLIP_MASTER 의 ms 와 다르다 — ÷1000 변환 없음).
--
--   ⚠ ★실파일이 있어야 적재된다 (구 시드와 결정적으로 다른 점)
--     적재는 "파일 존재 + 허용 루트 하위" 검증을 통과해야 수행되고, 미도착이면 실패가 아니라
--     PENDING 복귀(다음 주기 재시도)라 <적재 0건>이 된다. 즉 아래 경로에 실제 파일이 없으면
--     scan 을 아무리 눌러도 아무 일도 일어나지 않는다. 시드 SQL 은 파일을 만들 수 없으므로
--     반드시 아래 수동 절차를 먼저 수행할 것.
--
--     [네이티브 bootRun(local, STORAGE_RAW_PATH 기본값 ./storage/raw)] — backend/ 에서:
--       mkdir -p ./storage/raw/seed
--       for i in 9101 9102 9103; do cp <아무_영상.mp4> ./storage/raw/seed/clip-$i.mp4; done
--     [로컬 도커(docker-compose.local.yml)] — 컨테이너 WORKDIR 이 /app 이고 STORAGE_RAW_PATH 가
--       /app/storage/raw 라, 아래 <상대경로>가 /app/storage/raw/seed/... 로 해석돼 그대로 통한다:
--       docker exec <be> sh -c 'mkdir -p /app/storage/raw/seed && cp <원본> /app/storage/raw/seed/clip-9101.mp4'
--
--   ⚠⚠ ★★ 경로가 허용 루트 <밖>이면 이제 조용한 0건이 아니라 FAILED 종결이다 (Phase 3 변경점)
--     아래 경로는 <상대경로>라 "프로세스 작업 디렉터리 기준"으로 해석된다. 그래서 기본 형상
--     두 가지(네이티브 bootRun: CWD=backend/ + raw-path ./storage/raw · 로컬 도커: WORKDIR=/app +
--     raw-path /app/storage/raw)에서는 항상 허용 루트 하위가 된다.
--     그러나 STORAGE_RAW_PATH / STORAGE_RAW_MOUNT_ROOTS 를 <다른 절대경로>(예: /nas-storage)로
--     바꿔 띄운 형상에서는 이 상대경로가 허용 루트 밖이 되어, 스캔이 이 3건을 즉시
--     FAILED(사유: 허용 저장 루트 밖)로 종결시킨다. 예전처럼 "미도착이라 조용히 0건" 이 아니다.
--       · 대처 1(권장): 아래 RAW_FILE_PATH_NM 을 그 형상의 허용 루트 하위 절대경로로 바꿔 재시드.
--       · 대처 2: 이미 FAILED 가 된 행은 REVIEWER 재큐 API 로 되살린다 —
--            POST /v1/control-ingests/{rcptnSn}/requeue        (단건)
--            POST /v1/control-ingests/requeue  {"limit":100}   (일괄)
--         (인입 행은 삭제 금지 + UK(VMS_CLIP_ID) 때문에 재INSERT 도 불가하므로 재큐가 유일한 통로다.)
--     ※ 내용은 아무 바이트여도 픽업·적재·이벤트 발행까지는 진행된다(이후 비식별/ffprobe 단계에서
--       실제 영상이 아니면 실패 처리 — 그건 정상 흐름이다).
INSERT INTO LS_DATA_INGEST
    (VMS_CLIP_ID, VMS_CCTV_ID, VDO_FILE_NM, RAW_FILE_PATH_NM, SRC_TYPE,
     RCPTN_DT, PROC_STTS_CD, VDO_LEN_SEC, LCLGV_CD, SHT_DT, FILE_FMT, EVNT_ID, EVNT_NM) VALUES
    ('DEV-CLIP-9101', 'CCTV-001', 'clip-9101.mp4', './storage/raw/seed/clip-9101.mp4', 'ORIGINAL',
     now(), 'PENDING', 30, '11110', now(), 'mp4', 'DEV-EVT-9101', '배회'),
    ('DEV-CLIP-9102', 'CCTV-002', 'clip-9102.mp4', './storage/raw/seed/clip-9102.mp4', 'ORIGINAL',
     now(), 'PENDING', 30, '11110', now(), 'mp4', 'DEV-EVT-9102', '배회'),
    ('DEV-CLIP-9103', 'CCTV-003', 'clip-9103.mp4', './storage/raw/seed/clip-9103.mp4', 'ORIGINAL',
     now(), 'PENDING', 30, '11110', now(), 'mp4', 'DEV-EVT-9103', '배회')
ON CONFLICT (VMS_CLIP_ID) DO NOTHING;

-- 5-2) 관제 공유 클립 stub (MNG_CLIP_MASTER / MNG_CLIP_EVNT_LST) — <참조 데이터로만> 유지.
--   ★이 블록은 더 이상 적재를 유발하지 않는다(JOB_DMND_YN 스캔 폐지 — 위 5-1 이 대체).
--   그럼에도 남기는 이유: DatasetMetaSourceRepository 가 승인 export 메타의 FILE_FMT 를
--   MNG_CLIP_MASTER.CLIP_ID = LS_DATA_RAW.VMS_CLIP_ID 로 조회한다(Phase 6 에서 인입으로 교체 예정).
--   지우면 dev 에서 export 메타의 파일포맷이 조용히 null 이 된다.
--   - VMS_CCTV_ID 는 위 (5) MNG_RESOURCE_CCTV 시드값(CCTV-001~003) 참조 — 미존재 CCTV 매핑 방지.
--   - VDO_LEN_SEC 는 ms 단위(구 스키마 그대로). EVNT_ID 당 EVNT_LST 1행만 두어 비결정성 회피.
--   - 복합 PK (EVNT_ID, CLIP_TYPE_CD) ON CONFLICT DO NOTHING — 부팅 반복 멱등.
INSERT INTO MNG_CLIP_MASTER
    (EVNT_ID, CLIP_TYPE_CD, CLIP_ID, LCLGV_CD, FILE_NM, FILE_PATH, FILE_FMT,
     VDO_LEN_SEC, CLIP_STTS_CD, CRT_DT, JOB_DMND_YN, VMS_CCTV_ID) VALUES
    ('DEV-EVT-9101', 'ORIGINAL', 'DEV-CLIP-9101', '11110', 'clip-9101.mp4',
     './storage/raw/seed/clip-9101.mp4', 'mp4', 30000, 'mediainfo_complete', now(), 'Y', 'CCTV-001'),
    ('DEV-EVT-9102', 'ORIGINAL', 'DEV-CLIP-9102', '11110', 'clip-9102.mp4',
     './storage/raw/seed/clip-9102.mp4', 'mp4', 30000, 'mediainfo_complete', now(), 'Y', 'CCTV-002'),
    ('DEV-EVT-9103', 'ORIGINAL', 'DEV-CLIP-9103', '11110', 'clip-9103.mp4',
     './storage/raw/seed/clip-9103.mp4', 'mp4', 30000, 'mediainfo_complete', now(), 'Y', 'CCTV-003')
ON CONFLICT (EVNT_ID, CLIP_TYPE_CD) DO NOTHING;

INSERT INTO MNG_CLIP_EVNT_LST (EVNT_ID, EVNT_TYPE_CD, SHT_DT) VALUES
    ('DEV-EVT-9101', 'INTRUSION', now()),
    ('DEV-EVT-9102', 'INTRUSION', now()),
    ('DEV-EVT-9103', 'INTRUSION', now())
ON CONFLICT (EVNT_ID, EVNT_TYPE_CD) DO NOTHING;

-- 6) 라벨 마스터 (LS_LABEL) — CVAT-Like 라벨 풀 포팅 Phase 1
--   DTCT_TYPE_CD: AI(COCO) 검출 클래스 매핑(V129). COCO 80종에 대응하는 이동체 라벨만 채운다.
--   유지 라벨(9종): 매핑 6종(person/car/bicycle/motorbike→motorcycle/bus/truck) + 미매핑 이벤트 3종(fire/smoke/water).
--   ⚠ motorbike 의 COCO 정규명은 'motorcycle'(라벨명과 다름) — 매핑값은 motorcycle.
--   정리(soft-delete) 라벨: animal/fallen-person/vehicle-accident/object 는 COCO 미대응·불용 → 아래 6-2 에서 비활성(신규 설치엔 애초 미삽입).
INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_ID, REG_DT, DTCT_TYPE_CD) VALUES
    ('person',           '#E74C3C', 'BBOX',    1,  'Y', 'seed', '2026-05-15 00:00:00', 'person'),
    ('car',              '#3498DB', 'BBOX',    2,  'Y', 'seed', '2026-05-15 00:00:00', 'car'),
    ('bicycle',          '#9B59B6', 'BBOX',    3,  'Y', 'seed', '2026-05-15 00:00:00', 'bicycle'),
    ('motorbike',        '#1ABC9C', 'BBOX',    4,  'Y', 'seed', '2026-05-15 00:00:00', 'motorcycle'),
    ('bus',              '#F39C12', 'BBOX',    5,  'Y', 'seed', '2026-05-15 00:00:00', 'bus'),
    ('truck',            '#34495E', 'BBOX',    6,  'Y', 'seed', '2026-05-15 00:00:00', 'truck'),
    ('fire',             '#FF5733', 'POLYGON', 8,  'Y', 'seed', '2026-05-15 00:00:00', NULL),
    ('smoke',            '#7F8C8D', 'POLYGON', 9,  'Y', 'seed', '2026-05-15 00:00:00', NULL),
    ('water',            '#2980B9', 'POLYGON', 10, 'Y', 'seed', '2026-05-15 00:00:00', NULL)
-- ★멱등: 충돌 대상을 <지정하지 않는다> = 모든 유니크 인덱스가 대상이다.
--   구 코드는 이름 인덱스만 추론 대상으로 지정했다(ON CONFLICT ((LOWER(TRIM(LBL_NM)))) WHERE USE_YN='Y').
--   그래서 사용자가 라벨 관리 화면에서 라벨명을 바꿔 <COCO 매핑만 겹치는> 상태(예: 'person' →
--   '사람', DTCT_TYPE_CD 는 그대로 'person')가 되면, 이름 충돌이 없어 실제 INSERT 로 진행하다가
--   UK_LS_LABEL_DTCT_TYPE(활성 라벨 1개 = COCO 클래스 1개) 위반으로 <시드 전체가 중단>됐다.
--   제약은 그대로 두고(단일 진실원 규칙 유지) 시드 쪽을 충돌 안전하게 만든다 — 사용자가 이미
--   그 COCO 클래스를 다른 라벨에 배정했으면 시드는 조용히 물러난다.
ON CONFLICT DO NOTHING;

-- 6-1) 라벨 마스터 COCO 매핑 멱등 채움 (기존 시드 DB 재기동 반영)
--   위 INSERT 는 ON CONFLICT DO NOTHING 이라 이미 시드된 행의 DTCT_TYPE_CD 를 갱신하지 않는다.
--   따라서 미매핑(NULL) 대상 라벨에만 COCO 매핑을 UPDATE 로 채운다(멱등 — 이미 매핑된 행은 스킵).
--   ★그 COCO 클래스를 이미 다른 활성 라벨이 점유했으면 건너뛴다(NOT EXISTS) — 위 INSERT 와 같은
--   이유다. 사용자가 라벨을 재구성해 'person' 이름 라벨과 'person' 매핑 라벨이 <서로 다른 행>이
--   되면, 이 UPDATE 가 UK_LS_LABEL_DTCT_TYPE 를 위반해 시드 전체를 중단시킨다.
UPDATE LS_LABEL t SET DTCT_TYPE_CD = m.coco
FROM (VALUES
    ('person', 'person'), ('car', 'car'), ('bicycle', 'bicycle'),
    ('motorbike', 'motorcycle'), ('bus', 'bus'), ('truck', 'truck')
) AS m(nm, coco)
WHERE LOWER(TRIM(t.LBL_NM)) = m.nm AND t.USE_YN = 'Y' AND t.DTCT_TYPE_CD IS NULL
  AND NOT EXISTS (
        SELECT 1 FROM LS_LABEL x
         WHERE x.USE_YN = 'Y' AND x.DTCT_TYPE_CD = m.coco);

-- 6-2) 불용 라벨 정리 (soft-delete) — 기존 시드 DB 재기동 반영
--   COCO 미대응·불용 라벨(animal/fallen-person/vehicle-accident/object)을 USE_YN='N' 으로 비활성화한다.
--   ⚠ hard-delete 금지: 이 라벨들은 기존 라벨링 데이터(LS_DATA_LBL.LBL_ID FK)가 참조할 수 있어
--     삭제 시 FK 위반. soft-delete 로 라벨링 이력을 보존하고 목록·AI 탐지 후보(활성만 노출)에서만 제외한다.
--   멱등: 이미 USE_YN='N' 이면 대상 0. 신규 설치는 애초 미삽입이라 대상 0.
--   ★대상은 <시드가 만든 행>(REG_ID='seed')뿐이다(§0) — 사용자가 같은 이름으로 직접 만든 라벨을
--     재기동 때마다 조용히 비활성화하면 안 된다. 구 시드도 이 4종을 REG_ID='seed' 로 넣었으므로
--     기존 dev DB 정리 효과는 그대로다.
UPDATE LS_LABEL SET USE_YN = 'N', MDFCN_ID = 'seed', MDFCN_DT = '2026-05-15 00:00:00'
WHERE LOWER(TRIM(LBL_NM)) IN ('animal', 'fallen-person', 'vehicle-accident', 'object')
  AND USE_YN = 'Y'
  AND REG_ID = 'seed';

-- 7) 관제 이벤트 타입 마스터 (MNG_EX_EVNT_TYPE) — 실 klid_system 조회로 확정한 실데이터.
--   라벨 도출 전환(EVT_* enum → EV* 관제코드)의 토대. CLCT_EVNT_NM 은 수집 키워드(라벨 아님).
--   수집대상(CLCT_YN='Y') 14종 — EV08000101(배회, ignore 대분류 08)은 제외.
--   + 폴백 테스트용 비수집 1종 EV07000201(CLCT_YN='N', 카테고리 '기타 상황').
--   복합 아님(PK=EVNT_TYPE_CD) ON CONFLICT DO NOTHING — 부팅 반복 멱등.
INSERT INTO MNG_EX_EVNT_TYPE (EVNT_TYPE_CD, EVNT_CLS_CD, EVNT_CTGRY_CD, CLCT_EVNT_NM, CLCT_YN) VALUES
    ('EV01000101', '01', '0001', '범람,수위,위험수위,호우,홍수', 'Y'),
    ('EV01000102', '01', '0001', '', 'Y'),
    ('EV01000103', '01', '0001', '', 'Y'),
    ('EV01000201', '01', '0002', '산사태', 'Y'),
    ('EV02000101', '02', '0001', '산불발생,산불', 'Y'),
    ('EV02000102', '02', '0001', '화재,차량화재,기타화재,고층건물,일반화재(일반주택,근린생활시설 등),차량화재(일반도로),차량화재(터널 및 지하도로),연기,불꽃,폭발', 'Y'),
    ('EV02000201', '02', '0002', '쓰러짐', 'Y'),
    ('EV02000501', '02', '0005', '파손', 'Y'),
    ('EV03000101', '03', '0001', '교통사고,차량 사고', 'Y'),
    ('EV03000102', '03', '0001', '', 'Y'),
    ('EV03000103', '03', '0001', '', 'Y'),
    ('EV05000101', '05', '0001', '싸움,아동학대(기타),교제폭력,폭력,강력범죄,통보(폭력)', 'Y'),
    ('EV05000201', '05', '0002', '흉기소지', 'Y'),
    ('EV05000701', '05', '0007', '납치,납치감금,실종(실종아동 등)', 'Y'),
    ('EV07000201', '07', '0002', '기타 상황(비수집 폴백 테스트용 — 실 키워드 다수, seed 는 대표값)', 'N')
ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;

-- 7-1) 관제 이벤트 타입 매핑 (MNG_EX_EVNT_TYPE_MAP) — 라벨(한글명) 소스.
--   CD_TYPE='02' 카테고리명행(DTL_EVNT/EVNT_TYPE_CD=''): 위 코드들의 (cls, ctgry) 카테고리 한글명.
--   CD_TYPE='01' 대분류명행(EVNT_CTGRY_CD/DTL_EVNT/EVNT_TYPE_CD=''): 대분류 한글명.
--   복합 PK(CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD) ON CONFLICT DO NOTHING.
INSERT INTO MNG_EX_EVNT_TYPE_MAP (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD, EVNT_NM, USE_YN) VALUES
    -- 대분류명행 (CD_TYPE='01')
    ('01', '01', '', '', '', '자연재난', 'Y'),
    ('01', '02', '', '', '', '생활안전', 'Y'),
    ('01', '03', '', '', '', '교통안전', 'Y'),
    ('01', '05', '', '', '', '범죄안전', 'Y'),
    ('01', '07', '', '', '', '기타', 'Y'),
    -- 카테고리명행 (CD_TYPE='02')
    ('02', '01', '0001', '', '', '침수(범람)', 'Y'),
    ('02', '01', '0002', '', '', '산사태', 'Y'),
    ('02', '02', '0001', '', '', '화재', 'Y'),
    ('02', '02', '0002', '', '', '쓰러짐', 'Y'),
    ('02', '02', '0005', '', '', '파손', 'Y'),
    ('02', '03', '0001', '', '', '교통사고', 'Y'),
    ('02', '05', '0001', '', '', '싸움', 'Y'),
    ('02', '05', '0002', '', '', '흉기소지', 'Y'),
    ('02', '05', '0007', '', '', '납치(유괴)', 'Y'),
    ('02', '07', '0002', '', '', '기타 상황', 'Y')
ON CONFLICT (CD_TYPE, EVNT_CLS_CD, EVNT_CTGRY_CD, DTL_EVNT, EVNT_TYPE_CD) DO NOTHING;

-- 검증용 SELECT — 마스터 데이터만
SELECT '=== SEED COMPLETE ===' AS marker;
SELECT 'MNG_ACCT_USER'          AS t, COUNT(*) AS n FROM MNG_ACCT_USER         WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'LS_USER_ROLE',          COUNT(*) FROM LS_USER_ROLE          WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_RESOURCE_CCTV',     COUNT(*) FROM MNG_RESOURCE_CCTV     WHERE VMS_CCTV_ID LIKE 'CCTV-0%'
UNION ALL SELECT 'LS_DATA_INGEST(dev)',   COUNT(*) FROM LS_DATA_INGEST        WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%'
UNION ALL SELECT 'MNG_CLIP_MASTER(dev)',  COUNT(*) FROM MNG_CLIP_MASTER       WHERE EVNT_ID LIKE 'DEV-EVT-%'
UNION ALL SELECT 'MNG_EX_EVNT_TYPE(Y)',   COUNT(*) FROM MNG_EX_EVNT_TYPE      WHERE CLCT_YN = 'Y'
UNION ALL SELECT 'MNG_EX_EVNT_TYPE_MAP',  COUNT(*) FROM MNG_EX_EVNT_TYPE_MAP  WHERE CD_TYPE IN ('01','02')
UNION ALL SELECT 'LS_LABEL',              COUNT(*) FROM LS_LABEL              WHERE USE_YN = 'Y';
-- (LS_LABEL 컬럼: LBL_NM/COLR_VL/LBL_TYPE_CD/SORT_SEQ 표준화 적용됨)
-- 예상: MNG_ACCT_USER=5, LS_USER_ROLE=5, MNG_RESOURCE_CCTV=13, LS_DATA_INGEST(dev)=3, MNG_CLIP_MASTER(dev)=3, LS_LABEL=13, MNG_EX_EVNT_TYPE(Y)=14, MNG_EX_EVNT_TYPE_MAP=15
-- ⚠ LS_DATA_INGEST(dev)=3 이어도 RAW_FILE_PATH_NM 의 실파일이 없으면 적재는 0건이다(위 5-1 수동 절차 참조).
