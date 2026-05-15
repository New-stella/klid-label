-- ============================================================
-- 개발/로컬 전용 시드 데이터 (klid_system)
-- ⚠ LOCAL/DEV ONLY — 절대 prd DB에 실행 금지
--
-- 목적: 기능 테스트에 필요한 최소 데이터 세트.
--
-- 멱등성: 시드 ID 범위만 DELETE 후 INSERT.
--   → 재실행 안전. 운영 데이터(다른 ID 범위)는 건드리지 않음.
--
-- 시드 ID 정책:
--   - REVIEWER : 1001 (DevTokenService 기본값), 1002 (검수 배정용)
--   - WORKER   : 2001 (DevTokenService 기본값), 2002 (작업 배정 다양성)
--   - PORTAL   : 3001 (DevTokenService 기본값)
--   - PJT_ID   : 1
--   - LS_DATA_RAW.RAW_SN : 9001~9033 (13건)
--     COMPLETED 5건: 9001~9005 — 라벨링/검수 테스트 베이스
--     PROCESSING 3건: 9016~9018 — 배치 처리 중 상태 테스트
--     PENDING   5건: 9026~9027, 9031~9033 — 배치 트리거 대상 (프레임 사전 적재)
--
-- 배치 트리거 시나리오:
--   POST /v1/dev/batch/trigger?rawSn=9031  → YOLO→SAM2→COMPLETED
--   POST /v1/dev/batch/trigger?rawSn=9032  → YOLO→SAM2→COMPLETED (PRVC 비식별 포함)
--   POST /v1/dev/batch/trigger/next        → PENDING 중 가장 오래된 것 순차 처리
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- 1) 정리 (자식 → 부모 순) — 9000번대 영상 / 1000~3000번대 사용자만 대상
--    저작도구 전용 신규 테이블도 함께 정리
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
DELETE FROM LS_PJT_DATA_STTS WHERE PJT_ID = 1;
DELETE FROM LS_PJT_USER_AUTHRT_HSTRY WHERE PJT_ID = 1;
DELETE FROM LS_PJT_USER_AUTHRT WHERE PJT_ID = 1;
DELETE FROM LS_PJT_DATA_MPNG WHERE PJT_ID = 1;
DELETE FROM LS_DATA_RAW WHERE RAW_SN BETWEEN 9001 AND 9999;
DELETE FROM LS_PJT WHERE PJT_ID = 1;
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

-- 5) 프로젝트
INSERT INTO LS_PJT (PJT_ID, PJT_NM, PJT_DESC, PJT_STTS_CD, USE_YN, REG_USER_NO, REG_DT) VALUES
    (1, 'CCTV 라벨링 메인', '관제서버 송신 영상 라벨링/검수 메인 프로젝트', 'ACTIVE', 'Y', 1001, '2026-02-01 09:00:00');

-- 5.5) CCTV 마스터 (MNG_RESOURCE_CCTV) — 영상 VMS_CCTV_ID 매칭용 한글 이름.
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

-- 6) 영상 13건 (LS_DATA_RAW)
--   COMPLETED 5건  (9001~9005) : 라벨링/검수 테스트 베이스. 9001은 오토라벨 파이프라인 기본 rawSn.
--   PROCESSING 3건 (9016~9018): 배치 처리 중 상태 테스트
--   PENDING    5건 (9026~9027, 9031~9033): 배치 트리거 대상 (프레임 사전 적재됨)
--     9026: PRVC — 비식별 필요, 시드 최초 PENDING
--     9027: PRVC — 비식별 필요
--     9031: ANONY — 비식별 불필요, 배치 트리거 기본 테스트용
--     9032: PRVC  — 비식별 필요 경로 포함, 비식별 비즈니스 규칙 검증용
--     9033: PSDO  — 가명처리 영상 검증용
INSERT INTO LS_DATA_RAW (RAW_SN, VMS_CLIP_ID, VMS_CCTV_ID, EVNT_TYPE_CD, LCLGV_CD, PRVC_TYPE_CD, PRVC_YN, DE_IDNTF_YN, FILE_PATH, CAPTURED_AT, DURATION_SEC, DATA_STTS_CD, REG_DT, UPD_DT) VALUES
    -- COMPLETED (5건)
    (9001, 'SEED-CLIP-9001', 'CCTV-001', 'EVT_FALL',     '11680', 'PRVC',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9001.mp4', '2026-02-15 10:30:00',  60, 'COMPLETED', '2026-02-15 10:35:00', '2026-04-20 14:00:00'),
    (9002, 'SEED-CLIP-9002', 'CCTV-002', 'EVT_VIOLENCE', '11650', 'PRVC',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9002.mp4', '2026-02-16 11:00:00', 120, 'COMPLETED', '2026-02-16 11:05:00', '2026-04-21 10:00:00'),
    (9003, 'SEED-CLIP-9003', 'CCTV-003', 'EVT_ACCIDENT', '11710', 'PSDO',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9003.mp4', '2026-02-17 09:15:00',  90, 'COMPLETED', '2026-02-17 09:20:00', '2026-04-22 09:00:00'),
    (9004, 'SEED-CLIP-9004', 'CCTV-004', 'EVT_ABNORMAL', '11110', 'ANONY', 'N', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9004.mp4', '2026-02-18 14:00:00', 180, 'COMPLETED', '2026-02-18 14:05:00', '2026-04-23 11:00:00'),
    (9005, 'SEED-CLIP-9005', 'CCTV-005', 'EVT_FLOOD',    '11440', 'PRVC',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9005.mp4', '2026-02-19 16:30:00',  45, 'COMPLETED', '2026-02-19 16:35:00', '2026-04-24 12:00:00'),
    -- PROCESSING (3건)
    (9016, 'SEED-CLIP-9016', 'CCTV-016', 'EVT_ABNORMAL', '11680', 'ANONY', 'N', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9016.mp4', '2026-04-15 10:00:00', 100, 'PROCESSING', '2026-04-15 10:05:00', '2026-05-07 09:00:00'),
    (9017, 'SEED-CLIP-9017', 'CCTV-017', 'EVT_FLOOD',    '11650', 'PRVC',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9017.mp4', '2026-04-16 11:00:00', 220, 'PROCESSING', '2026-04-16 11:05:00', '2026-05-07 09:30:00'),
    (9018, 'SEED-CLIP-9018', 'CCTV-018', 'EVT_FIRE',     '11710', 'PSDO',  'Y', 'Y', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9018.mp4', '2026-04-17 14:30:00', 175, 'PROCESSING', '2026-04-17 14:35:00', '2026-05-07 10:00:00'),
    -- PENDING (5건) — 배치 트리거 대상
    (9026, 'SEED-CLIP-9026', 'CCTV-026', 'EVT_VIOLENCE', '11230', 'PRVC',  'Y', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9026.mp4', '2026-05-01 11:00:00', 165, 'PENDING', '2026-05-01 11:05:00', NULL),
    (9027, 'SEED-CLIP-9027', 'CCTV-027', 'EVT_ACCIDENT', '11290', 'PRVC',  'Y', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9027.mp4', '2026-05-02 14:00:00',  60, 'PENDING', '2026-05-02 14:05:00', NULL),
    -- 추가 PENDING (배치 트리거 시나리오별)
    (9031, 'SEED-CLIP-9031', 'CCTV-031', 'EVT_FALL',     '11680', 'ANONY', 'N', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9031.mp4', '2026-05-10 09:00:00',  90, 'PENDING', '2026-05-10 09:05:00', NULL),
    (9032, 'SEED-CLIP-9032', 'CCTV-032', 'EVT_VIOLENCE', '11650', 'PRVC',  'Y', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9032.mp4', '2026-05-10 10:00:00', 120, 'PENDING', '2026-05-10 10:05:00', NULL),
    (9033, 'SEED-CLIP-9033', 'CCTV-033', 'EVT_ACCIDENT', '11710', 'PSDO',  'Y', 'N', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9033.mp4', '2026-05-10 11:00:00',  75, 'PENDING', '2026-05-10 11:05:00', NULL);

-- 7) 프로젝트-영상 매핑 (13건 전체)
INSERT INTO LS_PJT_DATA_MPNG (PJT_ID, RAW_DATA_ID, REG_DT)
SELECT 1, RAW_SN, REG_DT FROM LS_DATA_RAW WHERE RAW_SN IN (9001,9002,9003,9004,9005,9016,9017,9018,9026,9027,9031,9032,9033);

-- 8) 프로젝트-영상 검수 상태
--   APPROVED 3건 (9001~9003) — 검수 완료
--   IN_REVIEW 2건 (9004~9005) — 검수 진행 중 (COMPLETED 영상)
--   IN_REVIEW 3건 (9016~9018) — 라벨링 진행 중 (PROCESSING 영상)
--   PENDING   5건 (9026~9027, 9031~9033) — 배치 대기
INSERT INTO LS_PJT_DATA_STTS (PJT_ID, RAW_DATA_ID, DATA_STTS_CD, STP_CYCL, IGI_CYCL, UPD_DT, VERSION) VALUES
    (1, 9001, 'APPROVED',  1, 0, '2026-04-20 14:00:00', 1),
    (1, 9002, 'APPROVED',  1, 0, '2026-04-21 10:00:00', 1),
    (1, 9003, 'APPROVED',  1, 0, '2026-04-22 09:00:00', 1),
    (1, 9004, 'IN_REVIEW', 1, 1, '2026-04-23 11:00:00', 0),
    (1, 9005, 'IN_REVIEW', 1, 1, '2026-04-24 12:00:00', 0),
    (1, 9016, 'IN_REVIEW', 0, 0, '2026-05-07 09:00:00', 0),
    (1, 9017, 'IN_REVIEW', 0, 0, '2026-05-07 09:30:00', 0),
    (1, 9018, 'IN_REVIEW', 0, 0, '2026-05-07 10:00:00', 0),
    (1, 9026, 'PENDING',   0, 0, '2026-05-01 11:05:00', 0),
    (1, 9027, 'PENDING',   0, 0, '2026-05-02 14:05:00', 0),
    (1, 9031, 'PENDING',   0, 0, '2026-05-10 09:05:00', 0),
    (1, 9032, 'PENDING',   0, 0, '2026-05-10 10:05:00', 0),
    (1, 9033, 'PENDING',   0, 0, '2026-05-10 11:05:00', 0);

-- 9) 작업 배정 (LS_PJT_USER_AUTHRT)
--   LABELER 배정: COMPLETED 5건 + PROCESSING 3건
--   REVIEWER 배정: 모든 LABELER 배정 영상에 1002 매칭 (검수자 표시 데모 데이터)
--   PENDING 5건은 배정 없음 (배치 완료 후 REVIEWER가 배정)
INSERT INTO LS_PJT_USER_AUTHRT (PJT_ID, USER_NO, RAW_DATA_ID, TASK_TYPE_CD, REG_USER_NO, REG_DT) VALUES
    -- LABELER
    (1, 2001, 9001, 'LABELER', 1001, '2026-02-15 11:00:00'),
    (1, 2002, 9002, 'LABELER', 1001, '2026-02-16 12:00:00'),
    (1, 2001, 9003, 'LABELER', 1001, '2026-02-17 10:00:00'),
    (1, 2002, 9004, 'LABELER', 1001, '2026-02-18 15:00:00'),
    (1, 2001, 9005, 'LABELER', 1001, '2026-02-19 17:00:00'),
    (1, 2001, 9016, 'LABELER', 1001, '2026-04-15 11:00:00'),
    (1, 2002, 9017, 'LABELER', 1001, '2026-04-16 12:00:00'),
    (1, 2001, 9018, 'LABELER', 1001, '2026-04-17 15:00:00'),
    -- REVIEWER 배정 (모든 LABELER 배정 영상에 1002 매칭)
    (1, 1002, 9001, 'REVIEWER', 1001, '2026-04-20 13:00:00'),
    (1, 1002, 9002, 'REVIEWER', 1001, '2026-04-21 09:00:00'),
    (1, 1002, 9003, 'REVIEWER', 1001, '2026-04-22 08:00:00'),
    (1, 1002, 9004, 'REVIEWER', 1001, '2026-04-23 10:00:00'),
    (1, 1002, 9005, 'REVIEWER', 1001, '2026-04-24 09:00:00'),
    (1, 1002, 9016, 'REVIEWER', 1001, '2026-04-25 09:00:00'),
    (1, 1002, 9017, 'REVIEWER', 1001, '2026-04-26 09:00:00'),
    (1, 1002, 9018, 'REVIEWER', 1001, '2026-04-27 09:00:00');

-- 10) 키프레임 (LS_DATA_SRC)
--   COMPLETED 5건 + PROCESSING 3건 × 5프레임 = 40건
--   PENDING   5건 × 5프레임 = 25건  ← 사전 적재: 배치 트리거 시 FRAME_EXTRACT 건너뜀
--   FILE_PATH: storage.raw-path 기준 상대 경로 'seed/{rawSn}/frame_{N}.jpg'
--   SRC_BKUP_FILE_PATH: PRVC/PSDO 영상의 비식별 프레임 경로 (ANONY는 NULL)
--   SeedImageRunner 가 동일 경로에 placeholder JPEG 를 생성한다.
INSERT INTO LS_DATA_SRC (RAW_SN, FRAME_NO, FILE_PATH, SRC_BKUP_FILE_PATH, CAPTURED_AT, REG_DT)
SELECT r.RAW_SN, fn.frame_no,
       CONCAT('seed/', r.RAW_SN, '/frame_', fn.frame_no, '.jpg'),
       CASE WHEN r.PRVC_TYPE_CD IN ('PRVC','PSDO')
            THEN CONCAT('seed/deid/', r.RAW_SN, '/frame_', fn.frame_no, '.jpg')
            ELSE NULL END,
       r.CAPTURED_AT,
       r.REG_DT
FROM LS_DATA_RAW r
CROSS JOIN (
    SELECT 1 AS frame_no UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
) fn
WHERE r.RAW_SN IN (9001,9002,9003,9004,9005,9016,9017,9018,9026,9027,9031,9032,9033);

-- 11) 라벨 (LS_DATA_LBL) — APPROVED 3건의 첫 프레임에 자동 BBOX 라벨
--   V21 분리 이후: AUTO_LBL_YN/CONF_SCORE 는 LS_DATA_LBL_AI_INFO 로 이관됨.
INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LABEL, POINTS_JSON, REG_USER_NO, REG_DT, UPD_DT)
SELECT s.SRC_SN, 'BBOX',
       CASE r.EVNT_TYPE_CD
           WHEN 'EVT_FALL'     THEN 'person'
           WHEN 'EVT_VIOLENCE' THEN 'person'
           WHEN 'EVT_ACCIDENT' THEN 'car'
           ELSE 'object'
       END,
       '[{"x":120,"y":80},{"x":340,"y":280}]',
       NULL,
       r.REG_DT,
       NULL
FROM LS_DATA_SRC s
JOIN LS_DATA_RAW r ON s.RAW_SN = r.RAW_SN
WHERE s.RAW_SN IN (9001,9002,9003) AND s.FRAME_NO = 1;

-- 12) AI 라벨 출처 정보 (LS_DATA_LBL_AI_INFO)
--   바로 위 11)에서 적재한 자동 라벨 3건 (9001/9002/9003 프레임1) 에 YOLO 추론 메타 기록.
--   이후 13)에서 추가될 수동 라벨이 같은 SRC_SN 에 들어와도 이 시점엔 자동 라벨만 존재하므로 LBL_SN 매칭이 명확.
INSERT INTO LS_DATA_LBL_AI_INFO
    (DATA_LBL_SN, PJT_SN, DATA_RAW_SN, DATA_SRC_SN, LBL_SRC_CD, MODEL_NM, MODEL_VER, CONF_SCORE, AUTO_LBL_YN, REG_DT)
SELECT
    l.LBL_SN,
    1              AS PJT_SN,
    s.RAW_SN,
    s.SRC_SN,
    'YOLO'         AS LBL_SRC_CD,
    'yolov8m'      AS MODEL_NM,
    'v8m-20260201' AS MODEL_VER,
    0.8500         AS CONF_SCORE,
    'Y'            AS AUTO_LBL_YN,
    l.REG_DT
FROM LS_DATA_LBL l
JOIN LS_DATA_SRC s ON l.SRC_SN = s.SRC_SN
WHERE s.RAW_SN IN (9001, 9002, 9003)
  AND s.FRAME_NO = 1;

-- 13) 사람 검토 후 추가 수동 라벨 (9001 프레임1) — AI_INFO row 없음 = 수동
INSERT INTO LS_DATA_LBL (SRC_SN, LBL_TYPE_CD, LABEL, POINTS_JSON, REG_USER_NO, REG_DT, UPD_DT)
SELECT s.SRC_SN, 'BBOX', 'person',
       '[{"x":50,"y":40},{"x":180,"y":200}]',
       2001, r.REG_DT, r.UPD_DT
FROM LS_DATA_SRC s
JOIN LS_DATA_RAW r ON s.RAW_SN = r.RAW_SN
WHERE s.RAW_SN = 9001 AND s.FRAME_NO = 1;

-- 13) 비식별 처리 이력 (LS_DEIDENT_PROC_LOG)
--   DE_IDNTF_YN='Y' 인 PRVC/PSDO 영상의 비식별 API 성공 이력.
--   COMPLETED: 9001(PRVC), 9002(PRVC), 9003(PSDO), 9005(PRVC)
--   PROCESSING: 9017(PRVC), 9018(PSDO) — 배치 진행 중이나 비식별은 완료
--   ANONY: 9004, 9016 — 비식별 불필요, 이력 없음
--   PENDING: 9026, 9027, 9031~9033 — 미처리, 이력 없음
INSERT INTO LS_DEIDENT_PROC_LOG
    (DATA_RAW_SN, REQ_ID, ORGN_FILE_PATH, DE_IDNTF_FILE_PATH, PROC_STTS_CD, REQ_DT, RES_DT, REG_ID, REG_DT)
VALUES
    (9001, 'SEED-REQ-9001', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9001.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9001_deid.mp4',
     'SUCCEEDED', '2026-02-15 10:36:00', '2026-02-15 10:38:00', 'batch', '2026-02-15 10:36:00'),
    (9002, 'SEED-REQ-9002', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9002.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9002_deid.mp4',
     'SUCCEEDED', '2026-02-16 11:06:00', '2026-02-16 11:09:00', 'batch', '2026-02-16 11:06:00'),
    (9003, 'SEED-REQ-9003', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9003.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9003_deid.mp4',
     'SUCCEEDED', '2026-02-17 09:21:00', '2026-02-17 09:23:00', 'batch', '2026-02-17 09:21:00'),
    (9005, 'SEED-REQ-9005', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9005.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9005_deid.mp4',
     'SUCCEEDED', '2026-02-19 16:36:00', '2026-02-19 16:38:00', 'batch', '2026-02-19 16:36:00'),
    (9017, 'SEED-REQ-9017', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9017.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9017_deid.mp4',
     'SUCCEEDED', '2026-04-16 11:06:00', '2026-04-16 11:10:00', 'batch', '2026-04-16 11:06:00'),
    (9018, 'SEED-REQ-9018', '/Users/ck/Documents/workspace/klid-la/backend/storage/raw/seed/9018.mp4',
     '/Users/ck/Documents/workspace/klid-la/backend/storage/deidentified/seed/9018_deid.mp4',
     'SUCCEEDED', '2026-04-17 14:36:00', '2026-04-17 14:40:00', 'batch', '2026-04-17 14:36:00');

-- 14) 비식별 누락 신고 (LS_DEIDENT_REPORT) — 데모용 시나리오
--   9017: PROCESSING 중인 PRVC 영상에 라벨러(2001)가 신고 → OPEN 상태
--         LS_AUTH_WORK_LOCK 잠금 없이 신고만 존재하는 시나리오 (경계 케이스)
INSERT INTO LS_DEIDENT_REPORT
    (DATA_RAW_SN, REPORTER_NO, REASON, REPORT_STTS_CD, REPORT_DT, REG_ID, REG_DT)
VALUES
    (9017, 2001, '우측 하단 인물 얼굴 비식별 처리 누락', 'OPEN', '2026-05-07 10:15:00', '2001', '2026-05-07 10:15:00');

SET FOREIGN_KEY_CHECKS = 1;

-- 검증용 SELECT
SELECT '=== SEED COMPLETE ===' AS marker;
SELECT 'MNG_ACCT_USER'          AS t, COUNT(*) AS n FROM MNG_ACCT_USER          WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'MNG_ACCT_USER_AUTHRT',    COUNT(*) FROM MNG_ACCT_USER_AUTHRT    WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'LS_PJT',                 COUNT(*) FROM LS_PJT                 WHERE PJT_ID = 1
UNION ALL SELECT 'LS_PJT_USER_AUTHRT',     COUNT(*) FROM LS_PJT_USER_AUTHRT     WHERE PJT_ID = 1
UNION ALL SELECT 'LS_DATA_RAW',            COUNT(*) FROM LS_DATA_RAW            WHERE RAW_SN BETWEEN 9001 AND 9999
UNION ALL SELECT 'LS_PJT_DATA_STTS',       COUNT(*) FROM LS_PJT_DATA_STTS       WHERE PJT_ID = 1
UNION ALL SELECT 'LS_DATA_SRC',            COUNT(*) FROM LS_DATA_SRC            WHERE RAW_SN BETWEEN 9001 AND 9999
UNION ALL SELECT 'LS_DATA_LBL',            COUNT(*) FROM LS_DATA_LBL            WHERE SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 9001 AND 9999)
UNION ALL SELECT 'LS_DATA_LBL_AI_INFO',    COUNT(*) FROM LS_DATA_LBL_AI_INFO    WHERE DATA_SRC_SN IN (SELECT SRC_SN FROM LS_DATA_SRC WHERE RAW_SN BETWEEN 9001 AND 9999)
UNION ALL SELECT 'LS_DEIDENT_PROC_LOG',    COUNT(*) FROM LS_DEIDENT_PROC_LOG    WHERE DATA_RAW_SN BETWEEN 9001 AND 9999
UNION ALL SELECT 'LS_DEIDENT_REPORT',      COUNT(*) FROM LS_DEIDENT_REPORT      WHERE DATA_RAW_SN BETWEEN 9001 AND 9999;
-- 예상: LS_DATA_RAW=13, LS_DATA_SRC=65(13×5), LS_DATA_LBL=4
--       LS_DATA_LBL_AI_INFO=3(auto라벨), LS_DEIDENT_PROC_LOG=6, LS_DEIDENT_REPORT=1
