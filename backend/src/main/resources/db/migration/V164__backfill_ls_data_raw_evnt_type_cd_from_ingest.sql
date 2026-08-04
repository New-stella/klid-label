-- =============================================================================
-- V164: LS_DATA_RAW.EVNT_TYPE_CD 백필 (관제 인입 EVNT_ID → MNG_CLIP_EVNT_LST 해석)
--
-- 배경 (2026-08-04 자동마킹 전량 실패 수정)
--   적재 경로(TrainingVideoIngestTx)가 EVNT_TYPE_CD 를 <항상 null> 로 넣고 있었는데,
--   마킹 프리컨디션(MarkingGuards#requirePreconditions)은 이 값이 비면 400 으로 막는다.
--     {"errorCode":"INVALID_INPUT","message":"이벤트 유형이 지정되지 않은 영상은 마킹할 수 없습니다."}
--   적재 주체가 관제 인입으로 반전된 뒤 이 경로가 주 경로라 신규 영상 전량이 마킹 불가였다.
--   코드 수정은 <신규 적재분>만 고친다 — 이미 적재된 행은 null 인 채 계속 막히므로 여기서 채운다.
--
-- 왜 Flyway 마이그레이션인가 (구현 형태 근거)
--   ① 1회성 소임이다. 기동 시 보정 러너나 관리 API 로 만들면 소임이 끝난 뒤에도 코드가 영구
--      잔존하고, 이 저장소는 그렇게 남은 백필 배치를 나중에 다시 제거한 이력이 있다
--      (resolution-backfill). 마이그레이션은 적용 후 재실행되지 않아 잔존 비용이 0 이다.
--   ② 이 저장소에 데이터 백필 마이그레이션 선례가 있다(V126 등).
--   ③ 실행 시점이 스키마 이력에 남아 감사 추적이 된다.
--
-- 멱등성
--   대상을 <EVNT_TYPE_CD 가 비어 있는 행> 으로 한정하므로 재실행해도 2회차부터 0행이다.
--   이미 값이 있는 행은 절대 덮어쓰지 않는다(운영자·다른 경로가 넣은 값을 파괴하지 않는다).
--
-- 다중 매칭은 채우지 않는다
--   MNG_CLIP_EVNT_LST 의 PK 는 (EVNT_ID, EVNT_TYPE_CD) 복합키라 한 이벤트에 유형이 여러 개
--   달릴 수 있다. 아무거나 고르면 관제 완료통지 계약 필드·목록 필터·통계 버킷·export 메타가
--   <틀린 값으로 확정>된다 — null 보다 나쁘다. HAVING COUNT(DISTINCT ...) = 1 로 확정 가능한
--   건만 채우고, 모호한 건은 비워 둔 채 마킹 가드가 막게 한다(적재 경로 판정과 동일 규칙).
--
-- 신규 컬럼·테이블 없음
--   LS_DATA_RAW.EVNT_TYPE_CD 는 이미 존재한다(VARCHAR(20) — 소스 MNG_CLIP_EVNT_LST.EVNT_TYPE_CD
--   와 동일 길이라 절단 위험 없음). MNG_* 는 공유(READ) 스키마라 읽기만 하고 변경하지 않는다.
--
-- 연결 축은 LS_DATA_INGEST.RAW_SN 이다
--   적재 성공 시 markDone(rawSn) 이 채우는 역추적 컬럼이라 영상↔인입이 1:1 로 확정된다.
--   VMS_CLIP_ID 문자열 재매칭은 UK 가 보장하는 같은 관계를 돌아가는 길일 뿐이다.
--
-- ★ 단일 문장으로 유지할 것 — 테스트(V164EvntTypeBackfillIT)가 이 파일 원본을 그대로 읽어
--   재실행하며 멱등성을 검증한다. 문장을 늘리면 그 검증이 깨진다.
-- =============================================================================

UPDATE LS_DATA_RAW r
   SET EVNT_TYPE_CD = resolved.evnt_type_cd
  FROM (
        SELECT i.RAW_SN                  AS raw_sn,
               MIN(e.EVNT_TYPE_CD)       AS evnt_type_cd
          FROM LS_DATA_INGEST i
          JOIN MNG_CLIP_EVNT_LST e
            ON e.EVNT_ID = i.EVNT_ID
         WHERE i.RAW_SN IS NOT NULL
           AND i.EVNT_ID IS NOT NULL
           AND e.EVNT_TYPE_CD IS NOT NULL
         GROUP BY i.RAW_SN
        HAVING COUNT(DISTINCT e.EVNT_TYPE_CD) = 1
       ) resolved
 WHERE r.RAW_SN = resolved.raw_sn
   AND (r.EVNT_TYPE_CD IS NULL OR btrim(r.EVNT_TYPE_CD) = '');
