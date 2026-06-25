-- ============================================================
-- V66 : LS_DATA_LBL.POINT_CN 좌표 포맷 정규화 백필 (평탄/객체배열 → nested)
--
--   목적   : Phase 1 에서 오토라벨 write 경로를 nested [[x,y],...] 로 통일했다. 이 백필은
--            기존 DB 에 남은 평탄 [x1,y1,x2,y2] / 객체배열 [{"x":..,"y":..}] POINT_CN 을
--            nested [[x,y],...] 로 일괄 변환하여 포맷 혼재를 제거한다. 그러면 관제 노출 View
--            (V_COMPLETED_LABEL)가 항상 일관된 nested 를 내보낸다.
--   대상   : LS_DATA_LBL 전용 (저작도구 소유 LS_* — 자체 Flyway 관리). 포털 사용자 라벨 등
--            다른 POINT_CN 컬럼은 손대지 않는다.
--   멱등성 : 이미 nested(첫 원소가 array)·NULL·빈배열([])·malformed(홀수 평탄)는 WHERE 가드로
--            제외되어 무변경. 재실행 시 변경 행 0.
--   파괴금지: 아래 행은 전부 변환 대상에서 제외(원본 보존, 절대 실패 안 함) —
--            ① 비-JSON/빈문자열('', 'none', '[bad' 등 jsonb 파싱 불가)
--            ② 비숫자·비객체 배열(["a","b"], [null,null], [true,false])
--            ③ 홀수 평탄([1,2,3])
--            POINT_CN 은 TEXT 라 위 비-JSON 값이 실재할 수 있다. 캐스팅 전에 안전 파싱 CTE 로
--            유효 JSON 배열 행만 선별하여 ::jsonb 캐스팅이 전체 트랜잭션을 깨뜨리지 않게 한다.
--   순서   : 객체배열 변환은 WITH ORDINALITY + jsonb_agg(... ORDER BY ord) 로 원소 순서를
--            보장한다(다점 폴리곤 좌표 형상 보존). 평탄 변환은 generate_series 인덱스 순서.
--   문법   : PostgreSQL 표준 jsonb (POINT_CN 컬럼 타입은 TEXT).
-- ============================================================

-- 안전 파싱: 정규식으로 'JSON 배열 형태로 시작' 하는 행만 우선 거른 뒤,
-- 그 안에서도 객체별 변환 분기를 CASE 로 처리한다. 비-JSON/비숫자/홀수는 전부 제외.
WITH candidate AS (
    -- 1차 가드: JSON 배열 형태(`[...`)로 시작하는 행만 통과시켜 ::jsonb 캐스팅 실패를 차단.
    --           빈문자열·none·{객체}·숫자 단독 등은 정규식에서 탈락 → 변환 제외(원본 보존).
    SELECT lbl_sn, (point_cn::jsonb) AS pj
    FROM ls_data_lbl
    WHERE point_cn IS NOT NULL
      AND btrim(point_cn) <> ''
      AND point_cn ~ '^\s*\['                         -- 배열로 시작하는 텍스트만 캐스팅 시도
), normalizable AS (
    SELECT lbl_sn, pj
    FROM candidate
    WHERE jsonb_typeof(pj) = 'array'
      AND jsonb_array_length(pj) > 0
      AND jsonb_typeof(pj->0) <> 'array'              -- 멱등: 이미 nested 제외
      AND jsonb_typeof(pj->0) IN ('number', 'object') -- number/object 만 대상(문자열·불리언·null 배열 제외=보존)
      AND (jsonb_typeof(pj->0) <> 'number'            -- flat 홀수 방어(변환 제외=원본 보존)
           OR jsonb_array_length(pj) % 2 = 0)
), converted AS (
    SELECT lbl_sn, (
        CASE
            WHEN jsonb_typeof(pj->0) = 'number' THEN   -- [x1,y1,...] → [[x,y],...]
                (SELECT jsonb_agg(jsonb_build_array(pj->i, pj->(i + 1)))
                 FROM generate_series(0, jsonb_array_length(pj) - 2, 2) AS i)
            WHEN jsonb_typeof(pj->0) = 'object' THEN    -- [{x,y},...] → [[x,y],...] (원소 순서 보존)
                (SELECT jsonb_agg(jsonb_build_array(e.val->'x', e.val->'y') ORDER BY e.ord)
                 FROM jsonb_array_elements(pj) WITH ORDINALITY AS e(val, ord))
        END) AS nested
    FROM normalizable
)
UPDATE ls_data_lbl AS t
SET point_cn = c.nested::text
FROM converted AS c
WHERE t.lbl_sn = c.lbl_sn
  AND c.nested IS NOT NULL;                            -- 방어: 변환 결과 NULL 이면 원본 보존
