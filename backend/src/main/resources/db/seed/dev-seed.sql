-- ============================================================
-- 개발/로컬 전용 시드 데이터 (klid_system)
-- ⚠ LOCAL/DEV ONLY — 절대 prd DB에 실행 금지
--
-- 목적: 실제 플로우(관제서버 영상 ingestion → 배치 파이프라인 → 라벨링 →
--       검수 → 비식별 신고)를 처음부터 실행하기 위한 마스터 데이터만 적재.
--
-- 남기는 것 (플로우 시작점):
--   - LS_ACNT_USER          사용자 5명 (REVIEWER 2 / WORKER 2 / PORTAL 1)
--   - LS_USER_ROLE          사용자-역할 매핑 (저작도구 소유 — 인가 판정의 단일 진실원)
--   - LS_LABEL              라벨 마스터 9건 (CVAT-Like 라벨 풀, 표시명 한글)
--   - LS_EVNT_TYPE          이벤트유형 마스터 16종 / LS_EVNT_CTGRY 카테고리 11종
--
-- 넣지 않는 것:
--   - LS_DATA_INGEST        관제가 직접 INSERT 하는 인입 원장이라 시드하지 않는다(§5-1 폐지 사유).
--                           파이프라인 시작점은 dev 업로드(POST /v1/dev/upload)로 만든다.
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
--   ※ 운영에서는 이 테이블을 시드하지 않는다 — 역할 클레임(/role-claim) 시점에 관제가
--     localStorage 로 인계한 값(userId·userNm)으로 <자동등록>된다(V169). dev 시드는 배정·검수
--     화면을 바로 볼 수 있도록 고정 사용자를 미리 심어 두는 것뿐이다.
INSERT INTO LS_ACNT_USER (USER_NO, USER_ID, USER_NM, USER_EML_ADDR, USE_YN, REG_DT) VALUES
    (1001, 'reviewer1', '김검수', 'reviewer1@cudo.co.kr', 'Y', '2026-02-01 09:00:00'),
    (1002, 'reviewer2', '이검수', 'reviewer2@cudo.co.kr', 'Y', '2026-02-01 09:00:00'),
    (2001, 'worker1',   '최라벨', 'worker1@cudo.co.kr',   'Y', '2026-02-05 09:00:00'),
    (2002, 'worker2',   '정작업', 'worker2@cudo.co.kr',   'Y', '2026-02-05 09:00:00'),
    (3001, 'portal1',   '홍길동', 'portal1@example.com',  'Y', '2026-03-01 09:00:00')
ON CONFLICT (USER_NO) DO UPDATE SET
    USER_NM       = EXCLUDED.USER_NM,
    USER_EML_ADDR = EXCLUDED.USER_EML_ADDR,
    USE_YN        = EXCLUDED.USE_YN;

-- 4) 사용자-역할 매핑 (LS_USER_ROLE) — 저작도구 소유, 인가 판정의 단일 진실원.
--   구 관제 권한 매핑 시드는 V165 로 테이블이 제거되어 함께 삭제됐다.
--   읽기/쓰기 경로가 LS_USER_ROLE 로 전환됐으므로 dev 사용자도 LS 역할만 시드한다.
--   USER_NO 단일 PK — ON CONFLICT DO UPDATE 로 역할 재적용 멱등.
INSERT INTO LS_USER_ROLE (USER_NO, ROLE_CD, REG_DT) VALUES
    (1001, 'REVIEWER',    '2026-02-01 09:00:00'),
    (1002, 'REVIEWER',    '2026-02-01 09:00:00'),
    (2001, 'WORKER',      '2026-02-05 09:00:00'),
    (2002, 'WORKER',      '2026-02-05 09:00:00'),
    (3001, 'PORTAL_USER', '2026-03-01 09:00:00')
ON CONFLICT (USER_NO) DO UPDATE SET ROLE_CD = EXCLUDED.ROLE_CD;

-- 5-1) (폐지) 관제 인입 픽업 후보 (LS_DATA_INGEST) — 시드하지 않는다.
--   구 동작: DEV-CLIP-9101~9103 3건을 PENDING 으로 심어 dev 파이프라인 시작점을 만들었다.
--
--   ★폐지 사유 (2026-08-19 사용자 확정): LS_DATA_INGEST 는 <관제가 직접 INSERT 하는 인입 원장>이다
--     (적재 주체 반전). 저작도구 DB 가 관제 DB 와 같은 서버·같은 스키마 축에 놓이면서, 시드가 넣는
--     가짜 3건이 관제 실인입과 같은 테이블에 섞이게 됐다. 게다가 그 3건은
--       · EVNT_TYPE_CD='INTRUSION' — LS_EVNT_TYPE 마스터(EV0*)에 없는 비규격 코드
--       · VMS_CCTV_ID='CCTV-001~003' — 관제 CCTV 원장의 실제 cctv_id 가 아님
--       · RAW_FILE_PATH_NM 이 <상대경로> — 형상에 따라 허용 루트 밖이면 즉시 FAILED 종결
--     라, 남겨두면 관제 쪽 데이터를 오염시키기만 하고 dev 드라이브에도 매번 손질이 필요했다.
--
--   dev 파이프라인을 돌리려면 시드가 아니라 아래를 쓴다:
--     · dev 업로드 화면 / POST /v1/dev/upload   — 실파일과 함께 인입 행을 만든다(권장)
--     · POST /v1/control-ingests/{rcptnSn}/requeue      (단건 재큐)
--     · POST /v1/control-ingests/requeue  {"limit":100} (일괄 재큐)
--   ⚠ 인입 행은 삭제 금지 + UK(VMS_CLIP_ID) 라 재INSERT 도 불가하므로 재큐가 유일한 회수 통로다.

-- 6) 라벨 마스터 (LS_LABEL) — CVAT-Like 라벨 풀 포팅 Phase 1
--   DTCT_TYPE_CD: AI(COCO) 검출 클래스 매핑(V129). COCO 80종에 대응하는 이동체 라벨만 채운다.
--   유지 라벨(9종): 매핑 6종(사람/자동차/자전거/오토바이/버스/트럭) + 미매핑 이벤트 3종(화재/연기/침수).
--   ★표시명은 한글이고 AI 검출 매칭은 DTCT_TYPE_CD(COCO 영문 클래스명)가 단일 진실원이다 —
--     둘은 1:1 대응하지 않으므로 라벨명을 바꿔도 오토라벨링 매칭은 흔들리지 않는다.
--     ⚠ 다만 학습데이터 export JSON 의 categories[].name 은 이 라벨명 그대로 나간다(CategoryMapper).
--   정리(soft-delete) 라벨: animal/fallen-person/vehicle-accident/object 는 COCO 미대응·불용 → 아래 6-2 에서 비활성(신규 설치엔 애초 미삽입).
INSERT INTO LS_LABEL (LBL_NM, COLR_VL, LBL_TYPE_CD, SORT_SEQ, USE_YN, REG_ID, REG_DT, DTCT_TYPE_CD) VALUES
    ('사람',             '#E74C3C', 'BBOX',    1,  'Y', 'seed', '2026-05-15 00:00:00', 'person'),
    ('자동차',           '#3498DB', 'BBOX',    2,  'Y', 'seed', '2026-05-15 00:00:00', 'car'),
    ('자전거',           '#9B59B6', 'BBOX',    3,  'Y', 'seed', '2026-05-15 00:00:00', 'bicycle'),
    ('오토바이',         '#1ABC9C', 'BBOX',    4,  'Y', 'seed', '2026-05-15 00:00:00', 'motorcycle'),
    ('버스',             '#F39C12', 'BBOX',    5,  'Y', 'seed', '2026-05-15 00:00:00', 'bus'),
    ('트럭',             '#34495E', 'BBOX',    6,  'Y', 'seed', '2026-05-15 00:00:00', 'truck'),
    ('화재',             '#FF5733', 'POLYGON', 8,  'Y', 'seed', '2026-05-15 00:00:00', NULL),
    ('연기',             '#7F8C8D', 'POLYGON', 9,  'Y', 'seed', '2026-05-15 00:00:00', NULL),
    ('침수',             '#2980B9', 'POLYGON', 10, 'Y', 'seed', '2026-05-15 00:00:00', NULL)
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
--   ★매칭 이름은 한글(현행)과 영문(구 시드 DB) 을 <함께> 둔다. 라벨명을 한글로 바꾸면서 영문만
--   남기면 이 백필이 기존 dev/local DB 에서 통째로 무력화되고, 한글만 남기면 아직 영문명인 DB 가
--   영영 미매핑으로 남는다. 어느 쪽이 매칭되든 결과 coco 값은 같다.
UPDATE LS_LABEL t SET DTCT_TYPE_CD = m.coco
FROM (VALUES
    ('사람', 'person'),   ('자동차', 'car'),  ('자전거', 'bicycle'),
    ('오토바이', 'motorcycle'), ('버스', 'bus'), ('트럭', 'truck'),
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

-- ★ 아래 7 · 7-1 · 라벨 마스터의 시드 내용은 이제 V15 마이그레이션이 진실원이다.
--   그 전까지 이 파일에만 있어서 온프렘 배포 스냅샷에 실리지 않았다(상용 라벨·이벤트 0건).
--   여기 남은 INSERT 는 V15 적용 뒤 ON CONFLICT 로 물러나는 no-op 이다.
--   ⚠ 내용을 바꿀 일이 생기면 V15 를 고치는 것이 아니라 <새 마이그레이션>을 더한다
--     (적용된 파일 수정은 체크섬 불일치 = 전 노드 기동 실패). 이 파일만 고치면
--     상용과 갈린다.
-- 7) 이벤트유형 마스터 (LS_EVNT_TYPE, V168) — 저작도구 소유.
--   ★관제 수신 유형명(EVNT_NM)은 <비운다>. 관제 마스터에는 유형별 이름이 애초에 없었고
--     사람이 읽는 이름은 카테고리 레벨에만 있었다(아래 7-1). 그래서 표시명은 카테고리명으로
--     폴백되며, EV01000101/102/103 이 전부 '침수(범람)' 으로 보이는 것은 <정상 상태>다.
--     관제가 특정 유형에 이름을 보내기 시작하면 그 유형만 갈라진다(점진 전환).
--   운영자 표시명(OPTR_INDCT_NM)도 비어 있다 — 관리 화면에서 지정하는 값이다.
--   수집대상 15종 + 비수집 폴백 1종(EV07000201). PK 단일 → ON CONFLICT DO NOTHING(멱등).
INSERT INTO LS_EVNT_TYPE (EVNT_TYPE_CD, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) VALUES
    ('EV01000101', '01', '0001', 'Y'),
    ('EV01000102', '01', '0001', 'Y'),
    ('EV01000103', '01', '0001', 'Y'),
    ('EV01000201', '01', '0002', 'Y'),
    ('EV02000101', '02', '0001', 'Y'),
    ('EV02000102', '02', '0001', 'Y'),
    ('EV02000201', '02', '0002', 'Y'),
    ('EV02000501', '02', '0005', 'Y'),
    ('EV03000101', '03', '0001', 'Y'),
    ('EV03000102', '03', '0001', 'Y'),
    ('EV03000103', '03', '0001', 'Y'),
    ('EV05000101', '05', '0001', 'Y'),
    ('EV05000201', '05', '0002', 'Y'),
    ('EV05000701', '05', '0007', 'Y'),
    ('EV08000101', '08', '0001', 'Y'),
    ('EV07000201', '07', '0002', 'N')
ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;

-- 7-1) 이벤트카테고리 마스터 (LS_EVNT_CTGRY, V168) — 표시명 폴백의 원천.
--   구 관제 매핑(MNG_EX_EVNT_TYPE_MAP, CD_TYPE='02') 카테고리명행의 이관 결과와 같은 내용이다.
--   복합 PK(대분류, 카테고리) → ON CONFLICT DO NOTHING(멱등).
INSERT INTO LS_EVNT_CTGRY (EVNT_CLSF_CD, EVNT_CTGRY_CD, EVNT_CTGRY_NM) VALUES
    ('01', '0001', '침수(범람)'),
    ('01', '0002', '산사태'),
    ('02', '0001', '화재'),
    ('02', '0002', '쓰러짐'),
    ('02', '0005', '파손'),
    ('03', '0001', '교통사고'),
    ('05', '0001', '싸움'),
    ('05', '0002', '흉기소지'),
    ('05', '0007', '납치(유괴)'),
    ('07', '0002', '기타 상황'),
    ('08', '0001', '배회')
ON CONFLICT (EVNT_CLSF_CD, EVNT_CTGRY_CD) DO NOTHING;

-- 검증용 SELECT — 마스터 데이터만
SELECT '=== SEED COMPLETE ===' AS marker;
SELECT 'LS_ACNT_USER'           AS t, COUNT(*) AS n FROM LS_ACNT_USER          WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'LS_USER_ROLE',          COUNT(*) FROM LS_USER_ROLE          WHERE USER_NO BETWEEN 1000 AND 9999
UNION ALL SELECT 'LS_EVNT_TYPE(Y)',      COUNT(*) FROM LS_EVNT_TYPE          WHERE CLCT_YN = 'Y'
UNION ALL SELECT 'LS_EVNT_CTGRY',        COUNT(*) FROM LS_EVNT_CTGRY
UNION ALL SELECT 'LS_LABEL',              COUNT(*) FROM LS_LABEL              WHERE USE_YN = 'Y';
-- (LS_LABEL 컬럼: LBL_NM/COLR_VL/LBL_TYPE_CD/SORT_SEQ 표준화 적용됨)
-- 예상(신규 설치): LS_ACNT_USER=5, LS_USER_ROLE=5, LS_LABEL=9, LS_EVNT_TYPE(Y)=15, LS_EVNT_CTGRY=11
