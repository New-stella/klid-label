-- =============================================================================
-- V149: LS_DATA_RAW.SRC_TYPE·AUG_TYPE_CD 백필 (Phase 4 — 과거 행)
--
-- 설계 정본: `.cc-design.md` §4-2-1. V148 이 추가한 두 컬럼(nullable)을 기존 행에 채운다.
--
-- 판정 규칙의 정본은 Java `video/util/AugTypeParser` 다. 이 마이그레이션은 그 규칙을 SQL 로 <1:1 이식>한
-- 것이며, 결과가 어긋나면 파서 제거(Phase 5) 후 화면 표시가 파서 시절과 달라진다. 그래서 백필 로직을
-- 자연스러운 SQL 로 "새로 짜지" 않고, 파서의 제어 흐름을 그대로 옮긴 PL/pgSQL 함수로 표현한다
-- (CASE/정규식 조합으로 흩어 쓰면 아래 5개 규칙 중 하나가 조용히 어긋난다 — 실제로 어긋나기 쉬운 지점을
--  각 규칙 옆에 적어 둔다). 대조는 `V149BackfillParityIT` 가 <파서 호출 결과>와 전 행 비교로 고정한다.
--
-- 이식한 규칙 5가지 (AugTypeParser 와 1:1):
--   ① 대소문자 — 파서는 Locale.ROOT 로 <전체 대문자화 후> 매칭한다. 여기서는 upper(x COLLATE "C") 로
--      DB 로케일에 의존하지 않는 ASCII 대문자화를 쓴다(로케일 의존 upper 는 예: tr_TR 에서 'i'→'İ' 라
--      'winter' 가 'WINTER' 로 올라가지 않아 판정이 갈린다).
--   ② 가장 오른쪽 마커 채택 — PostgreSQL 에는 "마지막 부분일치 위치" 함수가 없다. REVERSE+POSITION 길이
--      역산은 오프바이원이 흔해 쓰지 않고, 앞에서부터 훑어 마지막 히트를 남기는 루프 함수로 명시한다.
--      우선순위(_AUG_ 가 이기는 조건, _RESL_ vs 구형 _RES_ 의 마커 길이 선택)도 파서와 동일 비교식이다.
--   ③ 마커 <뒤> 세그먼트에서만 토큰 판별 — 원본 파일명에 우연히 든 토큰(winter-park…, road480p…)으로
--      오탐하지 않기 위함. 전체 문자열 스캔 폴백을 두지 않는다.
--   ④ 폴백 금지 — 오른쪽 마커가 _AUG_ 로 이기면 그 세그먼트에서 WINTER/NIGHT/RAIN 을 못 찾아도
--      <왼쪽 _RESL_ 로 되돌아가지 않고 즉시 NULL> 이다. "AUG 실패 시 RESL 재시도" 같은 자연스러운 폴백을
--      넣으면 파서와 갈린다.
--   ⑤ 해상도 코드 첫 일치 — 세그먼트 안에서 정규식 <첫 일치(왼쪽부터)> 를 채택한다("긴 코드 우선"도
--      "오른쪽 우선"도 아니다). 세 코드는 첫 글자가 서로 달라(1/7/4) POSIX 최장일치와 결과가 같다.
--
-- 이중 접두(_RESL_RESL_480P_)·구형 접두(_RES_RES_480P_)는 실데이터(cudo_246) 드리프트이며, 마커 뒤에서
-- 코드 토큰만 뽑는 위 규칙으로 자연히 흡수된다.
--
-- ★ 두 컬럼의 판정축은 서로 다르다 (설계 §4-2-1 ★정정 2026-08-01)
--   · SRC_TYPE    = ORGNL_RAW_SN IS NOT NULL → 'AUGMENTED', 아니면 'ORIGINAL'
--   · AUG_TYPE_CD = 마커 파싱 결과(미매칭 NULL)
--   clipId 파싱만으로 SRC_TYPE 을 정하면 두 방향으로 틀린다:
--     ① 파싱 불가한 파생행(레거시 '_AUG_RESOLUTION_' 등)이 'ORIGINAL' 로 적재된다.
--     ② 원본 파일명에 마커가 우연히 섞인 <원본>이 'AUGMENTED' 로 적재된다.
--   파생 여부의 정의 자체가 "부모가 있는가"(ORGNL_RAW_SN)이므로 그것이 진실원이다. 종류(AUG_TYPE_CD)는
--   그 축으로 알 수 없어 마커 파싱을 유지한다 — 두 컬럼이 서로를 검증하지 않으므로
--   'ORIGINAL' + AUG_TYPE_CD 비어있지 않음(원본명 오탐) 조합이 나올 수 있고, 그것은 모순이 아니다.
--   지금은 SRC_TYPE 소비처가 없어 화면 영향 0 이지만, 이 컬럼이 출처 판별의 단일 원천이 될 예정이라
--   소비자가 생기기 전에 정정한다.
--
-- 대상 한정 `WHERE SRC_TYPE IS NULL`:
--   이미 채워진 행(관제 인입 적재분의 SRC_TYPE, 파생 생성 경로가 직접 채운 AUGMENTED+종류)을 <덮지 않는다>.
--   같은 릴리스에서 쓰기측 배선(createFromAugment/createFromResolution)이 함께 나가므로, 이 가드가 없으면
--   재적용 시 인입값(RELAY 등)이 ORIGINAL 로 뭉개진다. 부수 효과로 재실행이 안전한 no-op 이 된다.
--   VMS_CLIP_ID 원문은 건드리지 않으므로, 정정이 필요하면 두 컬럼을 NULL 로 되돌린 뒤 이 로직을 재적용하면
--   같은 결과를 재계산할 수 있다.
--
-- 보조 함수는 백필 전용이라 <같은 마이그레이션 안에서 즉시 DROP> 한다(런타임 스키마에 남기지 않는다).
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 표준 문법. UPDATE 만 수행(행 수 불변).
-- =============================================================================

-- ---------------------------------------------------------------------------
-- 규칙 ② — Java String.lastIndexOf 등가(1-based, 없으면 0).
-- 앞에서부터 훑으며 마지막 히트 위치를 남긴다(겹치는 일치도 Java 와 동일하게 인식).
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ls_v149_last_index_of(p_text TEXT, p_sub TEXT)
RETURNS INTEGER
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_last INTEGER := 0;
    v_off  INTEGER := 1;
    v_hit  INTEGER;
BEGIN
    IF p_text IS NULL OR p_sub IS NULL OR p_sub = '' THEN
        RETURN 0;
    END IF;
    LOOP
        v_hit := strpos(substr(p_text, v_off), p_sub);
        EXIT WHEN v_hit = 0;
        v_last := v_off + v_hit - 1;
        v_off  := v_last + 1;
    END LOOP;
    RETURN v_last;
END;
$$;

-- ---------------------------------------------------------------------------
-- AugTypeParser.parse 의 SQL 이식본. 반환값은 FE 계약값
-- (WINTER|NIGHT|RAIN|RESL_1080P|RESL_720P|RESL_480P) 또는 NULL.
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION ls_v149_parse_aug_type(p_clip TEXT)
RETURNS VARCHAR(20)
LANGUAGE plpgsql
IMMUTABLE
AS $$
DECLARE
    v_upper  TEXT;
    v_aug    INTEGER;
    v_resl   INTEGER;
    v_legacy INTEGER;
    v_res    INTEGER;
    v_len    INTEGER;
    v_seg    TEXT;
    v_code   TEXT;
BEGIN
    IF p_clip IS NULL OR btrim(p_clip) = '' THEN
        RETURN NULL;
    END IF;

    v_upper  := upper(p_clip COLLATE "C");
    v_aug    := ls_v149_last_index_of(v_upper, '_AUG_');
    v_resl   := ls_v149_last_index_of(v_upper, '_RESL_');
    v_legacy := ls_v149_last_index_of(v_upper, '_RES_');
    v_res    := GREATEST(v_resl, v_legacy);

    IF v_aug > 0 AND v_aug > v_res THEN
        v_seg := substr(v_upper, v_aug + 5);
        IF strpos(v_seg, 'WINTER') > 0 THEN
            RETURN 'WINTER';
        END IF;
        IF strpos(v_seg, 'NIGHT') > 0 THEN
            RETURN 'NIGHT';
        END IF;
        IF strpos(v_seg, 'RAIN') > 0 THEN
            RETURN 'RAIN';
        END IF;
        RETURN NULL;
    END IF;

    IF v_res > 0 THEN
        v_len  := CASE WHEN v_resl >= v_legacy THEN 6 ELSE 5 END;
        v_seg  := substr(v_upper, v_res + v_len);
        v_code := substring(v_seg from '(1080P|720P|480P)');
        IF v_code IS NULL THEN
            RETURN NULL;
        END IF;
        RETURN 'RESL_' || v_code;
    END IF;

    RETURN NULL;
END;
$$;

UPDATE LS_DATA_RAW
   SET AUG_TYPE_CD = ls_v149_parse_aug_type(VMS_CLIP_ID),
       SRC_TYPE    = CASE WHEN ORGNL_RAW_SN IS NOT NULL
                          THEN 'AUGMENTED'
                          ELSE 'ORIGINAL'
                     END
 WHERE SRC_TYPE IS NULL;

DROP FUNCTION IF EXISTS ls_v149_parse_aug_type(TEXT);
DROP FUNCTION IF EXISTS ls_v149_last_index_of(TEXT, TEXT);
