-- V142: LS_MARKING 활성(미종결) 마킹 영상당 1건 강제 — 부분 유니크 인덱스 (B-ISSUE-22).
--
-- 배경(실측 결함): MarkingService.create 에 중복 가드가 없어 같은 RAW_SN 에 동시 3요청이 오면
--   ls_marking 3행(전부 201)이 생기는데, VlmTimeseriesStep 은 최신 마킹 1건만 VLM 에 위탁한다.
--   나머지는 영원히 PENDING 인 고아 행으로 남는다. 기존 인덱스는 PK + IDX_LM_RAW(비유니크)뿐이라
--   DB 차원의 방어가 전무했고, 서비스 사전 조회만으로는 동시 요청(서로의 미커밋 행 미관측)을 못 막는다.
--
-- 정책: "활성" = 미종결 = STTS_CD IN ('PENDING','VLM_REQUESTED').
--   종결 상태(VLM_COMPLETED / VLM_FAILED)는 제외한다 — 종결 마킹만 남은 영상의 재마킹은
--   새 배치 사이클을 여는 정당한 동선이며, 그때 생성되는 마킹은 최신 행이라 실제로 위탁된다(고아 아님).
--   ※ 정의의 단일 원천은 LsMarking.ACTIVE_STATUSES 이며 아래 술어와 반드시 일치해야 한다.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. PostgreSQL 부분 유니크 인덱스 표준 문법.
-- 컬럼/테이블 신설이 없으므로 표준용어·표준도메인 신규 판정 대상 없음(인덱스명만 추가:
--   활성=ACTVTN(사업표준단어) 약어 사용).

-- 1) 기존 고아 정리 — 인덱스 생성이 실패하지 않도록 선행한다.
--    영상별 활성 마킹이 2건 이상이면 최신 1건(배치가 실제로 위탁하는 행 = REG_DT DESC, MARKING_SN DESC
--    기준 첫 행)만 남기고 나머지는 VLM_FAILED(종결)로 내린다. 위탁된 적 없는 고아를 종결 처리하는 것이며
--    데이터 삭제는 하지 않는다(이력 보존).
--    ※ 아래 정렬은 배치 소비 정렬(LsMarkingRepository.findByRawSnOrderByRegDtDescMarkingSnDesc
--      = ORDER BY REG_DT DESC, MARKING_SN DESC)과 <문자 그대로 일치>해야 한다. REG_DT 동률(동시 요청으로
--      같은 밀리초에 생성 — 본 결함의 발생 원인 그 자체)에서 두 정렬이 갈리면 "남긴 행"과 "위탁되는 행"이
--      어긋나 종결 처리된 고아가 위탁된다. 순서를 바꾸려면 반드시 양쪽을 함께 바꾼다.
WITH ranked AS (
    SELECT MARKING_SN,
           ROW_NUMBER() OVER (
               PARTITION BY RAW_SN
               ORDER BY REG_DT DESC, MARKING_SN DESC
           ) AS rn
      FROM LS_MARKING
     WHERE STTS_CD IN ('PENDING', 'VLM_REQUESTED')
)
UPDATE LS_MARKING m
   SET STTS_CD = 'VLM_FAILED',
       MDFCN_DT = CURRENT_TIMESTAMP
  FROM ranked r
 WHERE m.MARKING_SN = r.MARKING_SN
   AND r.rn > 1;

-- 2) 부분 유니크 인덱스 — 동시 요청의 최종 방어(서비스 가드는 1선일 뿐이다).
CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_MARKING_RAW_ACTVTN
    ON LS_MARKING (RAW_SN)
 WHERE STTS_CD IN ('PENDING', 'VLM_REQUESTED');
