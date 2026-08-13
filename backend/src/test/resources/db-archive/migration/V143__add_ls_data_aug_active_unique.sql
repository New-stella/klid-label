-- =============================================================================
-- V143: LS_DATA_AUG 활성 증강 (대표프레임 × 종류) 1건 강제 — 부분 유니크 인덱스.
--
-- 배경(실측 결함): AugmentRequestService.request 에 중복 가드가 없어 같은 (RAW_SN, AUG_TYPE_CD)를
--   반복/동시 요청하면 매번 PENDING 행이 생기고, 콜백마다 AugmentResultService.createAugmentedVideo
--   가 <새 파생 RAW>를 만든다(반려해도 파생 RAW 행은 남는다). 즉 "요청 1회 = 파생영상 1건" 계약이
--   깨져 파생 트리·스토리지·검수 큐가 무제한 오염된다. 입구(서비스 가드)와 DB(본 인덱스) 양쪽에서 막는다.
--
-- 정책: "활성" = 중복 금지 대상 = AUG_PROC_STTS_CD IN ('PENDING','ACCEPTED').
--   - PENDING  : 요청/생성 in-flight. 재요청은 곧바로 이중 파생이 된다.
--   - ACCEPTED : 채택된 파생본이 이미 존재. 같은 종류를 또 만들 이유가 없다(트리만 불어난다).
--   - REJECTED : 제외(종결). 반려 후 재요청은 정당한 운영 동선이다 — V142 가 종결 마킹을 유니크
--                대상에서 제외한 것과 같은 성격의 판단.
--   ※ 정의의 단일 원천은 LsDataAug.ACTIVE_STATUSES 이며 아래 술어와 반드시 일치해야 한다.
--   ※ 잔존 위험(문서화): 반려→재요청을 반복하면 파생 RAW 는 계속 누적된다. 다만 매 회차가 REVIEWER 의
--     명시적 반려 결정(감사 기록 동반)을 요구하므로 무인 폭주는 성립하지 않는다.
--
-- 해상도 파생(RESL_ 접두)은 대상에서 제외한다 — 상태와 무관하게 1건만 허용하는 전용 부분 유니크
--   UK_LS_DATA_AUG_RESL(V125)이 이미 있고, 그쪽이 더 강한 제약이다(중복 적용 시 의미만 흐려진다).
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 부분 유니크 인덱스 표준 문법.
-- 컬럼/테이블 신설이 없으므로 표준용어·표준도메인 신규 판정 대상 없음(인덱스명만 추가:
--   활성=ACTVTN — 공통표준단어 '활성/ACTVTN'. V142 UK_LS_MARKING_RAW_ACTVTN 과 동일 약어).
-- =============================================================================

-- 0) ★ 사람 판단이 필요한 충돌은 <조용히 고치지 않고 중단>한다 (2026-07-29, 감리 대응 원칙).
--    같은 (SRC_SN, AUG_TYPE_CD)에 ACCEPTED 가 2건 이상이면, 둘 중 하나를 REJECTED 로 내리는 것은
--    <이미 확정된 검수 승인 결정을 마이그레이션이 말없이 뒤집는> 행위다(어느 파생본을 버릴지는
--    데이터·운영 맥락을 아는 사람만 정할 수 있다). 그래서 강등하지 않고 예외로 중단한다.
--    Flyway 는 스크립트를 트랜잭션으로 실행하므로 중단 시 DB 는 원상태로 남고 기동이 실패한다
--    → 운영자가 중복 ACCEPTED 를 정리한 뒤 재기동한다.
--    dev 실측 0건 · 정상 동선에서는 발생하지 않으므로 무영향이다(stg/prd 는 미확인 → 이 가드가 필요).
DO $$
DECLARE
    conflict_cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO conflict_cnt
      FROM (SELECT SRC_SN, AUG_TYPE_CD
              FROM LS_DATA_AUG
             WHERE AUG_PROC_STTS_CD = 'ACCEPTED'
               AND AUG_TYPE_CD NOT LIKE 'RESL\_%'
             GROUP BY SRC_SN, AUG_TYPE_CD
            HAVING COUNT(*) > 1) d;
    IF conflict_cnt > 0 THEN
        RAISE EXCEPTION 'V143 중단: 같은 (SRC_SN, AUG_TYPE_CD)에 ACCEPTED 증강이 2건 이상인 조합이 %건 있습니다. '
                        '검수 승인된 파생본을 자동 강등하지 않습니다 — 운영자가 남길 1건을 결정해 나머지를 '
                        '정리(REJECTED 전이)한 뒤 다시 기동하세요.', conflict_cnt;
    END IF;
END $$;

-- 1) 기존 활성 중복 정리 — 인덱스 생성이 실패하지 않도록 선행한다.
--    0) 가드를 통과했으므로 조합당 ACCEPTED 는 최대 1건이다. 여기서는 <PENDING 만> 종결로 내린다
--    (아래 WHERE 절이 ACCEPTED 강등을 구조적으로 불가능하게 한다 — 다층 방어).
--    <데이터 삭제는 하지 않는다> — 행과 그 검수 이력(LS_DATA_AUG_RVW)은 그대로 보존되고 상태만
--    종결로 전이된다(V142 가 고아 마킹을 VLM_FAILED 로 내린 것과 동일 방식).
--
--    남길 1건의 선정 순서(결정론적):
--      ① ACCEPTED 우선 — 이미 검수 승인된 파생본을 강등해 리뷰 결정을 뒤집지 않는다.
--      ② 그다음 최신(REG_DT DESC, DATA_AUG_SN DESC) — 콜백/조회가 최신 요청을 살아 있는 건으로 본다.
--
--    ※ 강등된 PENDING 은 <in-flight> 일 수 있다. 그 요청의 성공 콜백이 뒤늦게 도착하면 non-PENDING
--      앵커에 막혀 결과가 폐기되는데(200/applied=false), 그 사실이 조용히 묻히지 않도록
--      AugmentResultService 가 "success result discarded — aug already terminal(REJECTED)" WARN 을 남긴다.
WITH ranked AS (
    SELECT DATA_AUG_SN,
           ROW_NUMBER() OVER (
               PARTITION BY SRC_SN, AUG_TYPE_CD
               ORDER BY CASE WHEN AUG_PROC_STTS_CD = 'ACCEPTED' THEN 0 ELSE 1 END,
                        REG_DT DESC,
                        DATA_AUG_SN DESC
           ) AS rn
      FROM LS_DATA_AUG
     WHERE AUG_PROC_STTS_CD IN ('PENDING', 'ACCEPTED')
       AND AUG_TYPE_CD NOT LIKE 'RESL\_%'
)
UPDATE LS_DATA_AUG a
   SET AUG_PROC_STTS_CD = 'REJECTED'
  FROM ranked r
 WHERE a.DATA_AUG_SN = r.DATA_AUG_SN
   AND r.rn > 1
   AND a.AUG_PROC_STTS_CD = 'PENDING';  -- ACCEPTED 강등 금지 — 위 0) 가드와 이중 방어

-- 2) 부분 유니크 인덱스 — 동시 요청의 최종 방어(서비스 사전 조회는 1선일 뿐이다).
--    동시 트랜잭션은 서로의 미커밋 행을 보지 못하므로 사전 조회만으로는 전부 통과한다.
--    LIKE 이스케이프('RESL\_%')로 'RESL_' 리터럴 접두만 제외한다.
CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_DATA_AUG_ACTVTN
    ON LS_DATA_AUG (SRC_SN, AUG_TYPE_CD)
 WHERE AUG_PROC_STTS_CD IN ('PENDING', 'ACCEPTED')
   AND AUG_TYPE_CD NOT LIKE 'RESL\_%';
