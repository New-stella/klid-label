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

SET FOREIGN_KEY_CHECKS = 0;

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
DELETE FROM LS_LABEL WHERE NAME IN
    ('person','car','bicycle','motorbike','bus','truck','animal',
     'fire','smoke','water','fallen-person','vehicle-accident','object');
DELETE FROM MNG_ACCT_USER_AUTHRT WHERE USER_NO BETWEEN 1000 AND 9999;
DELETE FROM MNG_ACCT_USER WHERE USER_NO BETWEEN 1000 AND 9999;

-- 2) 권한 코드 마스터 (REVIEWER / WORKER / PORTAL_USER)
INSERT INTO MNG_ACCT_AUTHRT (AUTHRT_CD, AUTHRT_NM, USE_YN) VALUES
    ('REVIEWER',    '검수자',        'Y'),
    ('WORKER',      '라벨링 작업자', 'Y'),
    ('PORTAL_USER', '포털 회원',     'Y')
ON DUPLICATE KEY UPDATE AUTHRT_NM = VALUES(AUTHRT_NM);

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
ON DUPLICATE KEY UPDATE
    USER_NM    = VALUES(USER_NM),
    USER_EMAIL = VALUES(USER_EMAIL),
    USE_YN     = VALUES(USE_YN);

-- 4) 사용자-권한 매핑
INSERT INTO MNG_ACCT_USER_AUTHRT (USER_NO, AUTHRT_CD, REG_DT) VALUES
    (1001, 'REVIEWER',    '2026-02-01 09:00:00'),
    (1002, 'REVIEWER',    '2026-02-01 09:00:00'),
    (2001, 'WORKER',      '2026-02-05 09:00:00'),
    (2002, 'WORKER',      '2026-02-05 09:00:00'),
    (3001, 'PORTAL_USER', '2026-03-01 09:00:00');

-- 5) CCTV 마스터 (MNG_RESOURCE_CCTV) — 영상 VMS_CCTV_ID 매칭용 한글 이름.
--   AssignmentResponse.cctvName 표시 및 작업/검수 목록의 "CCTV-{지자체}-{NN}" 노출.
--   INSERT IGNORE — 시드 재실행 시 PK 충돌 회피 (이미 존재하면 무시).
INSERT IGNORE INTO MNG_RESOURCE_CCTV (VMS_CCTV_ID, CCTV_NM, USE_YN) VALUES
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
    ('CCTV-033', 'CCTV-마포구-033', 'Y');

-- 6) 라벨 마스터 (LS_LABEL) — CVAT-Like 라벨 풀 포팅 Phase 1
INSERT INTO LS_LABEL (NAME, COLOR, TYPE, SORT_NO, USE_YN, REG_ID, REG_DT) VALUES
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
    ('object',           '#95A5A6', 'BBOX',    13, 'Y', 'seed', '2026-05-15 00:00:00');

SET FOREIGN_KEY_CHECKS = 1;

-- 검증용 SELECT — 마스터 데이터만
SELECT '=== SEED COMPLETE ===' AS marker;
SELECT 'MNG_ACCT_AUTHRT'        AS t, COUNT(*) AS n FROM MNG_ACCT_AUTHRT        WHERE AUTHRT_CD IN ('REVIEWER','WORKER','PORTAL_USER')
UNION ALL SELECT 'MNG_ACCT_USER',         COUNT(*) FROM MNG_ACCT_USER         WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_ACCT_USER_AUTHRT',  COUNT(*) FROM MNG_ACCT_USER_AUTHRT  WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_RESOURCE_CCTV',     COUNT(*) FROM MNG_RESOURCE_CCTV     WHERE VMS_CCTV_ID LIKE 'CCTV-0%'
UNION ALL SELECT 'LS_LABEL',              COUNT(*) FROM LS_LABEL              WHERE USE_YN = 'Y';
-- 예상: MNG_ACCT_AUTHRT=3, MNG_ACCT_USER=5, MNG_ACCT_USER_AUTHRT=5, MNG_RESOURCE_CCTV=13, LS_LABEL=13
