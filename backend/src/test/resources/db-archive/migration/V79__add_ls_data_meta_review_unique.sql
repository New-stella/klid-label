-- LS_DATA_META_REVIEW: (DATA_META_SN, META_TYPE_CD) UNIQUE 제약 추가 — DEV_FIX #3 (CWE-362).
--
-- 동일 request_id 동시 VLM 콜백/재수신 시 하나의 META(=DATA_META_SN)에 대해 검수 행이 중복 PENDING 으로
-- 쌓이는 TOCTOU 를 DB 레벨에서 최종 차단한다. 서비스 계층은 비관적 락 직렬화 + 신규 meta 만 검수큐 진입으로
-- 1차 방어하고, 본 제약은 defense-in-depth. 하나의 META 는 유형별(VLM/EXTERNAL) 검수 1건만 존재해야 한다.
--
-- 기존 데이터에 중복이 있을 수 있으므로 제약 추가 전에 (DATA_META_SN, META_TYPE_CD) 그룹당 1건만 남긴다.
-- ⚠ 단순히 "최소 SN 보존" 은 검수 상태(RVW_STTS_CD)를 무시해, 그룹에 종결상태(APPROVED/REJECTED)와
--   오래된 PENDING 이 섞이면 검수 완료 이력을 삭제하고 PENDING 을 남길 수 있다 → 데이터마트
--   V_COMPLETED_META(RVW_STTS_CD='APPROVED') 소스가 소실되는 검수 판정 회귀. (DEV_FIX 2차 #2)
-- 따라서 그룹당 "가장 유의미한 최종 상태" 를 보존한다:
--   ① 상태 우선순위(APPROVED > REJECTED > PENDING > AUTO_GENERATED — 종결/데이터마트 소스 우선)
--   ② RVW_DT DESC(최근 검수)  ③ DATA_META_REVIEW_SN DESC
-- ROW_NUMBER 로 그룹당 1위만 남기고 나머지를 삭제한다. 재실행 시 그룹당 1건이라 rn>1 이 비어 멱등하다.

DELETE FROM LS_DATA_META_REVIEW
WHERE DATA_META_REVIEW_SN IN (
    SELECT DATA_META_REVIEW_SN
    FROM (
        SELECT DATA_META_REVIEW_SN,
               ROW_NUMBER() OVER (
                   PARTITION BY DATA_META_SN, META_TYPE_CD
                   ORDER BY
                       CASE RVW_STTS_CD
                           WHEN 'APPROVED' THEN 4
                           WHEN 'REJECTED' THEN 3
                           WHEN 'PENDING' THEN 2
                           WHEN 'AUTO_GENERATED' THEN 1
                           ELSE 0
                       END DESC,
                       RVW_DT DESC NULLS LAST,
                       DATA_META_REVIEW_SN DESC
               ) AS rn
        FROM LS_DATA_META_REVIEW
    ) ranked
    WHERE ranked.rn > 1
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'uq_ls_data_meta_review_meta_type'
    ) THEN
        ALTER TABLE LS_DATA_META_REVIEW
            ADD CONSTRAINT uq_ls_data_meta_review_meta_type
            UNIQUE (DATA_META_SN, META_TYPE_CD);
    END IF;
END $$;
