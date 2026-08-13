-- =============================================================================
-- V72: 라벨 프리셋(LS_LABEL_PRESET)의 구 EVT_* 이벤트 코드 → 관제 카테고리 키 전환
--
-- 배경: Phase 1~4 에서 이벤트 타입 체계가 SoT 구 6종(EVT_FALL/EVT_VIOLENCE/...)에서
--   관제 마스터(MNG_EX_EVNT_TYPE) 기반 카테고리 키(EVNT_CLS_CD+EVNT_CTGRY_CD)로 전환되었다.
--   프리셋은 categoryKey 단위로 저장(매칭 시 영상 상세 EV-코드 → categoryKey 로 변환 후 비교)
--   하므로, 기존에 EVT_* 로 저장된 LS_LABEL_PRESET.EVNT_TYPE_CD 행을 categoryKey 로 정정한다.
--
-- 매핑 근거 (확정 — Phase 5 작업 범위):
--   | 구 EVT_*        | 한글            | categoryKey | 대표 EV-코드 |
--   |-----------------|-----------------|-------------|--------------|
--   | EVT_FALL        | 쓰러짐          | 020002      | EV02000201   |
--   | EVT_VIOLENCE    | 폭력→싸움       | 050001      | EV05000101   |
--   | EVT_ACCIDENT    | 교통사고        | 030001      | EV03000101   |
--   | EVT_ABNORMAL    | 이상행동→납치   | 050007      | EV05000701   |
--   | EVT_FLOOD       | 침수            | 010001      | EV01000101   |
--   | EVT_FIRE        | 산불            | 020001      | EV02000101   |
--   (categoryKey = EVNT_CLS_CD(2) + EVNT_CTGRY_CD(4). 프리셋은 categoryKey 저장,
--    영상 LS_DATA_RAW 는 상세 EV-코드 저장.)
--
-- 멱등성: 각 UPDATE 는 WHERE EVNT_TYPE_CD='EVT_*' 로 한정한다. 재실행 시 이미 categoryKey 로
--   전환된 행은 매칭 0건이라 no-op 다. 운영(prd) 에 EVT_* 프리셋이 없으면 전부 no-op 다.
--
-- UNIQUE 제약 주의: LS_LABEL_PRESET 에는 UK_LS_LABEL_PRESET_EVNT UNIQUE(EVNT_TYPE_CD) 가
--   있다(V15). 두 EVT_* 프리셋이 같은 categoryKey 로 전환되면 UNIQUE 충돌이 날 수 있으나,
--   위 매핑은 모두 1:1(서로 다른 categoryKey) 이고 운영/현행 dev 도 이벤트당 프리셋 1건
--   가정이라 충돌이 발생하지 않는다(현 dev 엔 EVT_ABNORMAL 1건뿐). 만약 동일 categoryKey
--   프리셋이 2건 이상 존재하는 예외 환경이면 본 UPDATE 가 UNIQUE 위반으로 실패하므로,
--   그 경우 수동 정리 후 재적용한다(데이터 손실 방지 — 자동 머지/삭제하지 않는다).
--
-- PostgreSQL 표준 문법. 비파괴(컬럼/테이블 변경 없음, 값 정정만).
-- =============================================================================

-- 1) LS_LABEL_PRESET.EVNT_TYPE_CD : EVT_* → categoryKey (6종 1:1, 멱등)
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '020002' WHERE EVNT_TYPE_CD = 'EVT_FALL';
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '050001' WHERE EVNT_TYPE_CD = 'EVT_VIOLENCE';
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '030001' WHERE EVNT_TYPE_CD = 'EVT_ACCIDENT';
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '050007' WHERE EVNT_TYPE_CD = 'EVT_ABNORMAL';
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '010001' WHERE EVNT_TYPE_CD = 'EVT_FLOOD';
UPDATE LS_LABEL_PRESET SET EVNT_TYPE_CD = '020001' WHERE EVNT_TYPE_CD = 'EVT_FIRE';

-- 2) (dev 정합) LS_DATA_RAW.EVNT_TYPE_CD : EVT_* → 대표 상세 EV-코드
--    영상은 상세 EV-코드를 저장해야 오토라벨 프리셋 매칭(EV-코드→categoryKey→프리셋) 흐름이
--    정상 동작한다. 운영(prd) LS_DATA_RAW 는 관제 적재 경로라 EVT_* 가 없어 no-op 이고,
--    dev 시드/구 업로드로 들어간 EVT_* 표시값만 대표 EV-코드로 정상화한다. 멱등(WHERE 한정).
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV02000201' WHERE EVNT_TYPE_CD = 'EVT_FALL';
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV05000101' WHERE EVNT_TYPE_CD = 'EVT_VIOLENCE';
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV03000101' WHERE EVNT_TYPE_CD = 'EVT_ACCIDENT';
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV05000701' WHERE EVNT_TYPE_CD = 'EVT_ABNORMAL';
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV01000101' WHERE EVNT_TYPE_CD = 'EVT_FLOOD';
UPDATE LS_DATA_RAW SET EVNT_TYPE_CD = 'EV02000101' WHERE EVNT_TYPE_CD = 'EVT_FIRE';
