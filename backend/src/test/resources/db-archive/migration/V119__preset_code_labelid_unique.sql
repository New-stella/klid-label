-- =============================================================================
-- V119: 프리셋 labelId 중복 저장 방지 부분 유니크 인덱스 (동시성/무결성).
--
-- 배경: V118 로 LBL_CD 가 nullable 이 되면서(labelId 기반 신규 행은 LBL_CD=NULL) 기존
--   UNIQUE(PRESET_ID, LBL_CD)(UK_LS_LABEL_PRESET_CODE)는 PostgreSQL 의 "NULL 은 distinct"
--   특성으로 labelId 행에 대해 무력화됐다. 이 상태에서 동일 프리셋에 대한 동시
--   PUT /v1/manage/presets/{id} 두 건이 겹치면 같은 labelId 가 중복 저장될 수 있다
--   (인메모리 dedup 은 단일 트랜잭션 내에서만 유효). 라벨링 화면 중복 노출로 이어진다.
--
-- 변경: (PRESET_ID, LBL_ID) 부분 유니크 인덱스 추가 — labelId 연결 행에 한해 프리셋당
--   같은 labelId 는 1건만 허용한다. LBL_ID IS NULL 인 미연결 레거시 행은 대상에서 제외되어
--   기존 UNIQUE(PRESET_ID, LBL_CD) 규칙을 그대로 유지한다.
--   동시 갱신 경합에서 패자의 INSERT 는 DB 가 원자적으로 거부하며(race-safe),
--   응용 GlobalExceptionHandler 가 CONFLICT(409) 로 변환한다.
--
-- 전제: LBL_ID backfill(V117)은 코드→라벨명 1:1 이름 매칭이므로 정상 데이터엔
--   (PRESET_ID, LBL_ID) 중복이 존재하지 않아야 한다. 중복 행이 있으면 아래 인덱스 생성이
--   실패하므로, 실패 시 backfill 데이터 점검(같은 라벨명 다중 매칭 여부)이 필요하다.
--
-- 멱등: IF NOT EXISTS. PostgreSQL 표준 partial index. unquoted 식별자이므로 실제 저장 제약명은
--   소문자(uk_ls_label_preset_code_lblid)로 관측된다.
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_LABEL_PRESET_CODE_LBLID
    ON LS_LABEL_PRESET_CODE (PRESET_ID, LBL_ID)
    WHERE LBL_ID IS NOT NULL;
