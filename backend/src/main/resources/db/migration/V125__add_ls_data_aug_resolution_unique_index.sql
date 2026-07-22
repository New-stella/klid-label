-- =============================================================================
-- V125: 해상도 파생 증강행 동시성 부분 유니크 인덱스 (RQ-SFR-06-03 증강 저장모델 통합).
--
-- 배경: 해상도 변경(POST /v1/videos/{rawSn}/resolution)의 파생영상 적재를 구
--   LS_RESOLUTION_EXPORT(UK(DATA_RAW_SN, GOAL_RESL_CD)) 대신 증강 테이블 LS_DATA_AUG 로
--   통합한다(증강 이력에 노출). 이때 동일 원본(대표프레임 SRC_SN) + 동일 해상도 프리셋
--   (AUG_TYPE_CD='RESL_*') 재요청/동시요청이 이중 파생을 만들지 않도록 DB 레벨에서 직렬화해야 한다.
--
-- 변경: (SRC_SN, AUG_TYPE_CD) 부분 유니크 인덱스 — 해상도 파생 코드(RESL_ 접두)에 한해
--   같은 원본에 같은 프리셋은 1건만 허용한다. 외부 증강 3종(WINTER/NIGHT/RAIN) 및 레거시
--   단일 'RESOLUTION' 행은 접두가 달라 대상에서 제외되므로(NULL/미대상 distinct) 기존 적재에
--   영향이 없다. 동시 예약 경합에서 패자의 INSERT 는 DB 가 원자적으로 거부하며(race-safe),
--   응용 ResolutionReservationPersister 가 CONFLICT(409) 로 흡수한다.
--
-- 스키마: 새 테이블/컬럼 신설 없음(인덱스만). AUG_TYPE_CD 는 기존 VARCHAR(20) — 값 확장뿐이라
--   컬럼 타입/크기 변경 없음(표준도메인 코드값 VARCHAR(20) 유지). 인덱스명 UK_LS_DATA_AUG_RESL
--   (RESL=해상도 사업표준약어).
--
-- 멱등: IF NOT EXISTS. PostgreSQL 표준 partial index. LIKE 이스케이프('RESL\_%')로 'RESL_' 리터럴
--   접두만 매칭한다(unquoted 식별자이므로 실제 저장 제약명은 소문자로 관측된다).
-- =============================================================================

CREATE UNIQUE INDEX IF NOT EXISTS UK_LS_DATA_AUG_RESL
    ON LS_DATA_AUG (SRC_SN, AUG_TYPE_CD)
    WHERE AUG_TYPE_CD LIKE 'RESL\_%';
