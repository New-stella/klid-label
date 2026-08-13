-- V159: 배치 skip 경로가 남긴 <고아 활성 마킹>을 종결(SKIPPED)해 영상 재마킹 잠금을 해제한다 (B-ISSUE-41).
--
-- 배경(실측 결함): 마킹은 커밋된 뒤 AFTER_COMMIT 브릿지(MarkingBatchBridge)가 배치 트리거 여부를
--   판단한다. 브릿지가 정당하게 skip 하면(검수 소유 작업 상태 등) 그 마킹은 배치에 소비될 일이 없는데,
--   PENDING → VLM_* 전이는 <오직 VLM 단계>에서만 일어나고 그 단계는 배치가 돌아야 도달한다. 결과적으로
--   skip 된 마킹은 아무도 종결시키지 않는 영구 고아가 되고, 활성 마킹 유일성(V142 UK_LS_MARKING_RAW_ACTVTN)
--   때문에 그 영상은 <다시는 마킹할 수 없다>(재마킹 409 / batch retry 도 stage 가 FAILED 가 아니라 409).
--   즉 복구 API 가 전무했다. 코드 수정(브릿지 skip 분기 → SKIPPED 종결)은 신규 발생을 막을 뿐이므로,
--   이미 고착된 기존 행을 여기서 1회 정리한다.
--
-- 정리 범위(보수적): 작업 상태(LS_RAW_DATA_STATUS.DATA_STTS_CD)가 <검수 소유 상태>
--   (PENDING/IN_REVIEW/APPROVED/REJECTED — BatchTransitionService.REVIEW_OWNED_STATUSES)인 영상의
--   PENDING 마킹만 종결한다. 그 상태에서는 배치 진입 가드가 파이프라인을 확정적으로 차단하므로
--   "지금 배치가 돌고 있어서 곧 소비될 마킹" 이 존재할 수 없다(배치 실행 중이면 작업 상태는 PROCESSING).
--   * VLM_REQUESTED(위탁 진행 중)는 건드리지 않는다 — 콜백이 종결시킬 수 있다.
--   * 배치 단계(LS_DATA_RAW.DATA_STTS_CD)만 근거로 하는 정리는 하지 않는다 — PROCESSING 은 실제 실행 중과
--     구분되지 않아 진행 중인 사이클을 지울 위험이 있다.
--
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 협의 불요. 컬럼/테이블 신설 없음(값 UPDATE 만) →
--   표준용어·표준도메인 신규 판정 대상 없음. 'SKIPPED' 는 LsMarking.STATUS_SKIPPED 와 동일 값이며
--   STTS_CD VARCHAR(16) 도메인 안에 들어간다. 재실행해도 대상이 없어 멱등이다.
UPDATE LS_MARKING m
   SET STTS_CD = 'SKIPPED',
       MDFCN_DT = CURRENT_TIMESTAMP
  FROM LS_RAW_DATA_STATUS s
 WHERE s.RAW_DATA_ID = m.RAW_SN
   AND m.STTS_CD = 'PENDING'
   AND s.DATA_STTS_CD IN ('PENDING', 'IN_REVIEW', 'APPROVED', 'REJECTED');
