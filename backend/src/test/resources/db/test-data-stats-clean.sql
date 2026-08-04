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
-- 이벤트유형·카테고리 마스터 시드 (이벤트 분포 그리드 전제 데이터) — V168 구조.
--
-- 이벤트 분포는 EventTypeService.filterOptions() 가 LS_EVNT_TYPE(+LS_EVNT_CTGRY 표시명 폴백)
-- 에서 도출하는데, 마이그레이션은 스키마와 <이관분>만 만들고(빈 DB 면 이관 원본도 비어 있다)
-- 테스트용 행은 넣지 않는다. 패키지 단위 분할 실행 시 선행 클래스가 없어 분포가 0건이 되므로
-- 자기 픽스처로 전제 데이터를 직접 세운다.
--
-- 행 구성은 db/seed/dev-seed.sql §7/§7-1 과 동일하며 ON CONFLICT DO NOTHING 이라 dev-seed 가
-- 같은 JVM 에서 함께 적재돼도 충돌하지 않는다(양쪽 모두 멱등).
-- ---------------------------------------------------------------------------
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
