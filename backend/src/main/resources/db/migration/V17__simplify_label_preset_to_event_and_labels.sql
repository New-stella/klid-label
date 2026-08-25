-- ============================================================================
-- V17: 라벨 프리셋을 「이벤트 + 라벨」로 단순화한다  @design DOMAIN-010
--
--   1) 이벤트에 걸리지 않은 프리셋 삭제 (NOT NULL 전환의 선결 조건)
--   2) PRESET_NM  DROP  (UNIQUE uk_ls_label_preset_name 동반 소멸)
--   3) EXPLN      DROP
--   4) EVNT_TYPE_CD -> NOT NULL
--
-- ----------------------------------------------------------------------------
-- * 왜 이름과 설명을 없애는가
-- ----------------------------------------------------------------------------
--   프리셋은 이미 <이벤트별 1:1> 이다 — EVNT_TYPE_CD 에 UNIQUE(uk_ls_label_preset_evnt)
--   가 걸려 있어 한 이벤트에 프리셋은 하나뿐이다. 그런데 이름이 NOT NULL + UNIQUE 로
--   따로 있어 사람이 매번 지어 넣어야 했고, 그 값은 사실상 이벤트명의 중복이었다.
--   설명(EXPLN)은 채워도 읽는 화면이 없었다.
--
--   남는 식별자는 EVNT_TYPE_CD 하나이며, 사람이 읽는 이름은 이벤트유형 마스터의
--   표시명(EventTypeDisplayNamePolicy 4단 폴백)이 단일 진실원으로 제공한다.
--
-- ----------------------------------------------------------------------------
-- * 이벤트 미연결 행을 지우는 이유 (되살리지 말 것)
-- ----------------------------------------------------------------------------
--   EVNT_TYPE_CD 가 NULL 인 프리셋은 <어느 영상에도 매칭되지 않는다> — 프리셋 해석은
--   영상의 이벤트 코드를 필터 키로 바꿔 조회하므로, 키가 없는 행은 조회 대상이 될 수
--   없다. 즉 보존해도 오토라벨에 아무 기여를 하지 않는 죽은 행이다.
--   이런 행은 주로 <복제> 기능이 만들었는데(이름에 접미사를 붙이고 이벤트를 비운다)
--   그 기능도 이 변경에서 함께 폐지된다.
--
--   실측: dev 실제 DB 는 프리셋 전건이 이벤트에 연결돼 있어 삭제 대상 0건이다.
--         상용은 프리셋 시드가 없어 0건이다.
--
-- ----------------------------------------------------------------------------
-- * 되돌리기
-- ----------------------------------------------------------------------------
--   컬럼 DROP 은 되돌릴 수 없다. 다만 프리셋의 실질(이벤트 <-> 라벨 매핑)은
--   LS_LABEL_PRESET_CODE 에 그대로 남으므로, 되돌리려면 컬럼을 다시 만들고 이름을
--   새로 채우면 된다(옛 이름 자체는 복구되지 않는다).
--
-- ----------------------------------------------------------------------------
-- * 스키마 한정을 박지 않는다
-- ----------------------------------------------------------------------------
--   비한정 식별자만 쓴다 — 대상 스키마는 커넥션의 search_path 가 정한다.
--   카탈로그 조회도 current_schema() 로 스코프를 건다(고정 스키마명 금지).
-- ============================================================================

-- 1) 이벤트 미연결 프리셋 삭제 — 자식(코드) 먼저, 부모 나중.
DO $migration$
DECLARE
    orphan_cnt integer;
BEGIN
    SELECT count(*) INTO orphan_cnt
      FROM ls_label_preset
     WHERE evnt_type_cd IS NULL;

    IF orphan_cnt > 0 THEN
        DELETE FROM ls_label_preset_code
         WHERE preset_id IN (SELECT preset_id FROM ls_label_preset WHERE evnt_type_cd IS NULL);

        DELETE FROM ls_label_preset
         WHERE evnt_type_cd IS NULL;

        RAISE NOTICE 'V17: 이벤트 미연결 프리셋 %건을 삭제했습니다(매칭 대상이 없는 죽은 행).', orphan_cnt;
    ELSE
        RAISE NOTICE 'V17: 이벤트 미연결 프리셋이 없습니다 — 삭제 대상 0건.';
    END IF;
END
$migration$;

-- 2) 이름 컬럼 제거. UNIQUE(uk_ls_label_preset_name) 는 컬럼과 함께 소멸한다.
ALTER TABLE ls_label_preset DROP COLUMN IF EXISTS preset_nm;

-- 3) 설명 컬럼 제거.
ALTER TABLE ls_label_preset DROP COLUMN IF EXISTS expln;

-- 4) 이벤트 필수화 — 1) 이 선행되어야 성립한다.
ALTER TABLE ls_label_preset ALTER COLUMN evnt_type_cd SET NOT NULL;

-- 5) 테이블 주석 갱신 — 남은 축이 무엇인지 명시한다.
COMMENT ON TABLE ls_label_preset IS
    '라벨 프리셋 — 이벤트유형 1건에 대한 오토라벨 대상 라벨 세트. 식별자는 EVNT_TYPE_CD(유니크)이며 사람이 읽는 이름은 이벤트유형 마스터의 표시명이 제공한다(프리셋 자체는 이름을 갖지 않는다).';

COMMENT ON COLUMN ls_label_preset.evnt_type_cd IS
    '이벤트유형코드(유니크·필수). 프리셋의 유일한 식별 축이다. 영상의 이벤트 코드를 필터 키로 변환한 값과 대조해 오토라벨 대상 라벨을 정한다.';
