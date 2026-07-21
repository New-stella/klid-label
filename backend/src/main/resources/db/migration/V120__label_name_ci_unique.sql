-- =============================================================================
-- V120: 라벨 마스터 이름(LS_LABEL.LBL_NM) 대소문자/공백 무시 유일성 (활성 한정).
--
-- 배경(잠복 결함의 뿌리 닫기): 프리셋↔라벨마스터 연동이 마스터 라벨명을 조인 축으로
--   쓰면서 두 결함이 드러났다 —
--     (1) 대소문자 근사중복('person' vs 'Person')이 활성 상태로 공존 → 오토라벨 매핑에서
--         findLabelIdByName 이 다중 결과를 만나 크래시.
--     (2) 앞뒤 공백('person ')이 저장돼 exact 조인이 어긋나 labelId 가 null 로 유실.
--   앱단(서비스 trim + 활성 CI 중복검사)만으로는 동시 생성 경합을 원자적으로 막지 못하므로
--   DB 부분 유니크 인덱스로 무결성을 강제한다.
--
-- 기존 제약(유지): V31 에서 UNIQUE(PJT_ID, LBL_NM) 로 생성, V34 에서 UNIQUE(LBL_NM)
--   (UK_LS_LABEL_NAME) 로 축소, V60 에서 컬럼 LABEL_NM→LBL_NM 리네임(제약은 컬럼을 따라감).
--   UK_LS_LABEL_NAME 은 exact(대소문자 구분·all rows) 유일 제약으로, 제거 시 exact-name
--   동작이 바뀌므로 본 스코프에서는 건드리지 않고 그대로 둔다. 아래 CI 인덱스는 활성 라벨에
--   한해 정규화(LOWER(TRIM)) 기준 유일성을 추가로 강제하는 보완 제약이다.
--
-- 확정 설계(사용자 승인):
--   - 유일 범위 = 활성(USE_YN='Y')만. soft-delete 된 이름은 재사용 허용 → 부분 인덱스.
--   - 기존 데이터 자동 변경 금지. 본 마이그레이션은 어떤 행도 UPDATE 하지 않는다.
--   - 안전 중단: 인덱스 생성 전, 활성 라벨 중 LOWER(TRIM(LBL_NM)) 기준 근사중복이 이미
--     존재하면 명확한 메시지(충돌 라벨명 나열)로 실패한다. 자동 병합/삭제 없음 — 수동 해소.
--
-- 멱등: precheck 는 read-only, 인덱스는 IF NOT EXISTS. PostgreSQL 표준(부분·함수형 인덱스).
--   unquoted 식별자이므로 실제 저장 인덱스명은 소문자(uk_ls_label_nm_ci)로 관측된다.
-- =============================================================================

-- (a) 안전 중단 precheck — 활성 근사중복이 이미 있으면 인덱스 생성이 어차피 실패하므로,
--     원인(충돌 라벨명)을 담은 명확한 메시지로 먼저 중단한다.
DO $$
DECLARE
    dup_names text;
BEGIN
    SELECT string_agg(g.norm_name, ', ')
      INTO dup_names
      FROM (
          SELECT LOWER(TRIM(LBL_NM)) AS norm_name
            FROM LS_LABEL
           WHERE USE_YN = 'Y'
           GROUP BY LOWER(TRIM(LBL_NM))
          HAVING COUNT(*) > 1
      ) g;

    IF dup_names IS NOT NULL THEN
        RAISE EXCEPTION 'V120 중단: 활성 라벨 중 대소문자/공백 무시 근사중복이 존재합니다. 자동 변경 없음 — 수동 해소 필요: %', dup_names;
    END IF;
END $$;

-- (b) 활성 라벨 한정 함수형 유니크 인덱스 — 대소문자+공백 무시 유일성 강제.
CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_LABEL_NM_CI
    ON LS_LABEL (LOWER(TRIM(LBL_NM)))
    WHERE USE_YN = 'Y';
