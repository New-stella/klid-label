-- ============================================================
-- 개발/로컬 전용 시드 데이터 (klid_system)
-- ⚠ LOCAL/DEV ONLY — 절대 prd DB에 실행 금지
--
-- 목적: 실제 플로우(관제서버 영상 ingestion → 배치 파이프라인 → 라벨링 →
--       검수 → 비식별 신고)를 처음부터 실행하기 위한 마스터 데이터만 적재.
--
-- 남기는 것 (플로우 시작점):
--   - MNG_ACCT_AUTHRT       권한 코드 마스터 (REVIEWER / WORKER / PORTAL_USER)
--   - MNG_ACCT_USER         사용자 5명 (REVIEWER 2 / WORKER 2 / PORTAL 1)
--   - MNG_ACCT_USER_AUTHRT  사용자-권한 매핑
--   - MNG_RESOURCE_CCTV     CCTV 마스터 13건 (오토라벨 테스트·영상 ingestion 매칭)
--   - LS_LABEL              라벨 마스터 13건 (CVAT-Like 라벨 풀)
--
-- 지우는 것 (업무 진행 중간 결과 — 플로우 실행으로 생성):
--   - LS_DATA_RAW / LS_DATA_SRC / LS_DATA_LBL / LS_DATA_LBL_AI_INFO
--   - LS_RAW_DATA_ENROLLMENT / LS_RAW_DATA_STATUS / LS_TASK_ASSIGNMENT(LABELER·REVIEWER 배정)
--   - LS_DEIDENT_PROC_LOG / LS_DEIDENT_REPORT
--   - LS_LABEL_VERSION / LS_DATA_META_REVIEW / LS_DATA_AUG_RVW / LS_AUTH_WORK_LOCK
--
-- 멱등성: 시드 ID 범위만 DELETE 후 INSERT.
--   → 재실행 안전. 운영 데이터(다른 ID 범위)는 건드리지 않음.
--
-- 시드 ID 정책:
--   - REVIEWER : 1001 (DevTokenService 기본값), 1002 (검수 배정용)
--   - WORKER   : 2001 (DevTokenService 기본값), 2002 (작업 배정 다양성)
--   - PORTAL   : 3001 (DevTokenService 기본값)
-- ============================================================

-- (PostgreSQL — FK 비활성화 불필요: 아래 DELETE가 자식 → 부모 순서를 보장)

-- 1) 정리 (자식 → 부모 순) — 9000번대 영상 / 1000~3000번대 사용자 + 플로우 결과물 일괄
DELETE FROM LS_DATA_LBL_AI_INFO
    WHERE DATA_SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 9001 AND 9999);
DELETE FROM LS_DATA_LBL WHERE SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 9001 AND 9999);
DELETE FROM LS_LABEL_VERSION WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_DATA_META_REVIEW WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_DATA_AUG_RVW WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_AUTH_WORK_LOCK WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_DEIDENT_REPORT WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_DEIDENT_PROC_LOG WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_RAW_DATA_STATUS WHERE RAW_DATA_ID BETWEEN 9001 AND 9999;
DELETE FROM LS_TASK_ASSIGN_HISTORY WHERE RAW_DATA_ID BETWEEN 9001 AND 9999;
DELETE FROM LS_TASK_ASSIGNMENT WHERE RAW_DATA_ID BETWEEN 9001 AND 9999;
DELETE FROM LS_RAW_DATA_ENROLLMENT WHERE RAW_DATA_ID BETWEEN 9001 AND 9999;
DELETE FROM LS_DATA_RAW WHERE RAW_SN BETWEEN 9001 AND 9999;
-- 관제 학습용 픽업 시드(DEV-CLIP-*)로 적재된 LS_DATA_RAW 도 재적재 멱등을 위해 정리
--   (scan 트리거가 CLIP_ID 멱등키로 중복 차단하지만, 시드 재실행 시 깨끗한 상태에서 다시 픽업 가능하게).
DELETE FROM LS_DATA_RAW WHERE VMS_CLIP_ID LIKE 'DEV-CLIP-%';
-- 관제 공유 클립 stub 시드(DEV-EVT-*) 정리 — 자식(EVNT_LST) → 부모(MASTER) 순.
DELETE FROM MNG_CLIP_EVNT_LST WHERE EVNT_ID LIKE 'DEV-EVT-%';
DELETE FROM MNG_CLIP_MASTER WHERE EVNT_ID LIKE 'DEV-EVT-%';
-- LS_LABEL 은 보존 대상 마스터(주석 §남기는 것) — 강제 DELETE 금지.
--   기존 LS_DATA_LBL(다른 RAW_SN 범위)이 lbl_id 를 참조하면 FK 위반으로 시드 전체가 중단된다.
--   재적재 멱등은 아래 INSERT 의 ON CONFLICT (LBL_NM) DO NOTHING 으로 보장한다.
DELETE FROM MNG_ACCT_USER_AUTHRT WHERE USER_NO BETWEEN 1000 AND 9999;
DELETE FROM MNG_ACCT_USER WHERE USER_NO BETWEEN 1000 AND 9999;

-- 2) 권한 코드 마스터 (REVIEWER / WORKER / PORTAL_USER)
INSERT INTO MNG_ACCT_AUTHRT (AUTHRT_CD, AUTHRT_NM, USE_YN) VALUES
    ('REVIEWER',    '검수자',        'Y'),
    ('WORKER',      '라벨링 작업자', 'Y'),
    ('PORTAL_USER', '포털 회원',     'Y')
ON CONFLICT (AUTHRT_CD) DO UPDATE SET AUTHRT_NM = EXCLUDED.AUTHRT_NM;

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

-- 4) 사용자-권한 매핑
--   ON CONFLICT DO NOTHING — 복합 PK(USER_NO, AUTHRT_CD) 기준 멱등. 선행 DELETE 가 정리하므로
--   기능 영향 없으나 다른 시드 블록과 멱등 일관성 유지.
INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD, REG_DT) VALUES
    (1001, 'REVIEWER',    '2026-02-01 09:00:00'),
    (1002, 'REVIEWER',    '2026-02-01 09:00:00'),
    (2001, 'WORKER',      '2026-02-05 09:00:00'),
    (2002, 'WORKER',      '2026-02-05 09:00:00'),
    (3001, 'PORTAL_USER', '2026-03-01 09:00:00')
ON CONFLICT (USER_NO, AUTHRT_CD) DO NOTHING;

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

-- 5-1) 관제 학습용 픽업 후보 클립 (MNG_CLIP_MASTER / MNG_CLIP_EVNT_LST) — Phase 2 로컬 검증.
--   외부 관제 DB 없이도 POST /v1/dev/batch/scan 트리거가 JOB_DMND_YN='Y' 클립을 픽업해
--   LS_DATA_RAW 적재 + VideoIngestedEvent 발행 경로를 탈 수 있게 시드한다.
--   - CLIP_ID 는 'DEV-CLIP-' 접두사 고정(멱등키 = LS_DATA_RAW.VMS_CLIP_ID, 위 정리 블록 LIKE 삭제 대상).
--   - VMS_CCTV_ID 는 위 (5) MNG_RESOURCE_CCTV 시드값(CCTV-001~003) 참조 — 미존재 CCTV 매핑 방지.
--   - FILE_PATH 비공백(실파일 부재 허용 — 픽업·적재·이벤트 발행 검증 목적. 비식별 단계의 'F' 처리는 정상 흐름).
--   - VDO_LEN_SEC 는 ms 단위(30000ms→30s 변환 적재). EVNT_ID 당 EVNT_LST 1행만 두어 findFirstByEvntId 비결정성 회피.
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
INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_ID, REG_DT) VALUES
    ('person',           '#E74C3C', 'BBOX',    1,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('car',              '#3498DB', 'BBOX',    2,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('bicycle',          '#9B59B6', 'BBOX',    3,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('motorbike',        '#1ABC9C', 'BBOX',    4,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('bus',              '#F39C12', 'BBOX',    5,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('truck',            '#34495E', 'BBOX',    6,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('animal',           '#16A085', 'BBOX',    7,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('fire',             '#FF5733', 'POLYGON', 8,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('smoke',            '#7F8C8D', 'POLYGON', 9,  'Y', 'seed', '2026-05-15 00:00:00'),
    ('water',            '#2980B9', 'POLYGON', 10, 'Y', 'seed', '2026-05-15 00:00:00'),
    ('fallen-person',    '#C0392B', 'BBOX',    11, 'Y', 'seed', '2026-05-15 00:00:00'),
    ('vehicle-accident', '#D35400', 'BBOX',    12, 'Y', 'seed', '2026-05-15 00:00:00'),
    ('object',           '#95A5A6', 'BBOX',    13, 'Y', 'seed', '2026-05-15 00:00:00')
ON CONFLICT (LBL_NM) DO NOTHING;

-- 검증용 SELECT — 마스터 데이터만
SELECT '=== SEED COMPLETE ===' AS marker;
SELECT 'MNG_ACCT_AUTHRT'        AS t, COUNT(*) AS n FROM MNG_ACCT_AUTHRT        WHERE AUTHRT_CD IN ('REVIEWER','WORKER','PORTAL_USER')
UNION ALL SELECT 'MNG_ACCT_USER',         COUNT(*) FROM MNG_ACCT_USER         WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_ACCT_USER_AUTHRT',  COUNT(*) FROM MNG_ACCT_USER_AUTHRT  WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_RESOURCE_CCTV',     COUNT(*) FROM MNG_RESOURCE_CCTV     WHERE VMS_CCTV_ID LIKE 'CCTV-0%'
UNION ALL SELECT 'MNG_CLIP_MASTER(dev)',  COUNT(*) FROM MNG_CLIP_MASTER       WHERE EVNT_ID LIKE 'DEV-EVT-%'
UNION ALL SELECT 'LS_LABEL',              COUNT(*) FROM LS_LABEL              WHERE USE_YN = 'Y';
-- (LS_LABEL 컬럼: LBL_NM/COLR_VL/LBL_TYPE_CD/SORT_SEQ 표준화 적용됨)
-- 예상: MNG_ACCT_AUTHRT=3, MNG_ACCT_USER=5, MNG_ACCT_USER_AUTHRT=5, MNG_RESOURCE_CCTV=13, MNG_CLIP_MASTER(dev)=3, LS_LABEL=13
