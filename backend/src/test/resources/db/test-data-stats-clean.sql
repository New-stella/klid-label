-- StatsControllerTest 격리용 클린업 스크립트.
-- 공유 H2(DB_CLOSE_DELAY=-1) 사용으로 다른 @SpringBootTest 가 LS_DATA_RAW 에 남긴
-- EVT_FALL 등 이벤트 데이터가 누적되어 "데이터 없으면 0" 검증이 깨지는 회귀를 차단한다.
--
-- 자식 테이블도 함께 비워 FK 외 logical 참조(RAW_SN/RAW_DATA_ID) 잔재를 제거한다.

DELETE FROM LS_RAW_DATA_STATUS;
DELETE FROM LS_TASK_ASSIGN_HISTORY;
DELETE FROM LS_TASK_ASSIGNMENT;
-- LS_DATA_LBL_HSTRY 는 신규 DB 설계 (LS_LABEL_VERSION 으로 분리) 에서 더 이상 생성되지 않음.
DELETE FROM LS_DATA_LBL;
DELETE FROM LS_DATA_META;
DELETE FROM LS_DATA_SRC_HSTRY;
DELETE FROM LS_DATA_SRC;
DELETE FROM LS_DATA_RAW_HSTRY;
DELETE FROM LS_DATA_RAW;
DELETE FROM LS_CLIP_SCHEDULE_QUE;

-- ---------------------------------------------------------------------------
-- 관제 이벤트 타입 마스터/매핑 시드 (이벤트 분포 그리드 전제 데이터).
--
-- 이벤트 분포는 EventTypeService.filterOptions() 가 MNG_EX_EVNT_TYPE 에서 도출하는데,
-- 이 테이블은 마이그레이션이 스키마만 만들고 행은 넣지 않는다. 과거에는 같은 JVM 에서
-- 먼저 실행된 다른 테스트(dev-seed 를 적재하는 eventtype/dev 패키지)가 남긴 행에 의존해
-- 통과했고, 테스트를 패키지 단위로 분할 실행(세그먼트)하면 그 선행 클래스가 없어
-- 분포가 0건이 되어 깨졌다. 자기 픽스처로 전제 데이터를 직접 세운다.
--
-- 행 구성은 db/seed/dev-seed.sql §7/§7-1 과 동일하며 ON CONFLICT DO NOTHING 이라
-- dev-seed 가 같은 JVM 에서 함께 적재돼도 충돌하지 않는다(양쪽 모두 멱등).
-- 수집대상(CLCT_YN='Y') 14종 + 비수집 폴백 1종(EV07000201).
-- ---------------------------------------------------------------------------
INSERT INTO MNG_EX_EVNT_TYPE (EVNT_TYPE_CD, EVNT_CLS_CD, EVNT_CTGRY_CD, CLCT_EVNT_NM, CLCT_YN) VALUES
    ('EV01000101', '01', '0001', '범람,수위,위험수위,호우,홍수', 'Y'),
    ('EV01000102', '01', '0001', '', 'Y'),
    ('EV01000103', '01', '0001', '', 'Y'),
    ('EV01000201', '01', '0002', '산사태', 'Y'),
    ('EV02000101', '02', '0001', '산불발생,산불', 'Y'),
    ('EV02000102', '02', '0001', '화재', 'Y'),
    ('EV02000201', '02', '0002', '쓰러짐', 'Y'),
    ('EV02000501', '02', '0005', '파손', 'Y'),
    ('EV03000101', '03', '0001', '교통사고,차량 사고', 'Y'),
    ('EV03000102', '03', '0001', '', 'Y'),
    ('EV03000103', '03', '0001', '', 'Y'),
    ('EV05000101', '05', '0001', '싸움,폭력', 'Y'),
    ('EV05000201', '05', '0002', '흉기소지', 'Y'),
    ('EV05000701', '05', '0007', '납치,납치감금', 'Y'),
    ('EV07000201', '07', '0002', '기타 상황', 'N')
ON CONFLICT (EVNT_TYPE_CD) DO NOTHING;

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
