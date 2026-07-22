-- =============================================================================
-- V125: 구 해상도 전용 테이블(LS_RESOLUTION_EXPORT / LS_RESOLUTION_LBL_MAP) 백필 후 제거.
--
-- 배경(Phase 1, RQ-SFR-06-03 증강 저장모델 통합): 해상도 파생 적재를 구
--   LS_RESOLUTION_EXPORT/LS_RESOLUTION_LBL_MAP 대신 증강 테이블
--   LS_DATA_AUG(AUG_TYPE_CD='RESL_*', ACCEPTED) + LS_DATA_AUG_LBL_MAP 으로 통합했다.
--   구 두 테이블은 더 이상 신규 적재를 받지 않으므로, 잔존 데이터를 새 저장모델로
--   이관한 뒤 forward-only 로 제거한다.
--
-- 운영 데이터가 0 이면 ①② 는 0행(no-op), ③ DROP 만 실행된다 — 정상.
--
-- 표준용어 훅 참고: 본 마이그레이션은 신규 컬럼/테이블/물리명을 만들지 않는다
--   (백필 INSERT ... SELECT + DROP TABLE 뿐). 대상 컬럼은 모두 기존 표준 물리명이다.
--
-- PostgreSQL 표준 문법. 테이블명은 다른 마이그레이션과 동일하게 datasource 기본 스키마 기준
--   (search_path)으로 unqualified 사용한다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- ① 백필: LS_RESOLUTION_EXPORT → LS_DATA_AUG (해상도 파생 증강행, 즉시 ACCEPTED)
--
--   - SRC_SN      = 부모 영상(export.DATA_RAW_SN)의 대표프레임 SRC_SN
--                   (LS_DATA_SRC 중 FRM_NO 최소, 동률 시 SRC_SN 최소 — Phase 1 firstFrame 규칙과 일치).
--   - AUG_TYPE_CD = REPLACE(export.GOAL_RESL_CD, 'RES_', 'RESL_')
--                   (구 값 'RES_1080P' → 'RESL_1080P' 표준용어 변환. 이미 'RESL_' 이면 REPLACE 무영향).
--   - AUG_PROC_STTS_CD = 'ACCEPTED', REG_DT/REG_USER_NO = export 값, RTRY_NMTM = 0,
--     IDMP_KEY/OTSD_JOB_ID = NULL(생략 — 콜백형 증강 전용 컬럼).
--   - 대표프레임이 없는 export 는 LATERAL ... LIMIT 1 조인으로 자연 제외된다(스킵).
--   - 중복 방지: Phase 1 이 이미 만든 동일 (SRC_SN, AUG_TYPE_CD='RESL_*') 행과 충돌하지 않도록
--     부분 유니크 인덱스 UK_LS_DATA_AUG_RESL(V124) 를 arbiter 로 ON CONFLICT DO NOTHING.
-- -----------------------------------------------------------------------------
INSERT INTO LS_DATA_AUG (SRC_SN, AUG_TYPE_CD, AUG_PROC_STTS_CD, REG_DT, REG_USER_NO, RTRY_NMTM)
SELECT rep.src_sn,
       REPLACE(e.GOAL_RESL_CD, 'RES_', 'RESL_'),
       'ACCEPTED',
       e.REG_DT,
       e.REG_ID,
       0
FROM LS_RESOLUTION_EXPORT e
CROSS JOIN LATERAL (
    SELECT s.SRC_SN AS src_sn
    FROM LS_DATA_SRC s
    WHERE s.RAW_SN = e.DATA_RAW_SN
    ORDER BY s.FRM_NO ASC, s.SRC_SN ASC
    LIMIT 1
) rep
ON CONFLICT (SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL\_%' DO NOTHING;

-- -----------------------------------------------------------------------------
-- ② 백필: LS_RESOLUTION_LBL_MAP → LS_DATA_AUG_LBL_MAP
--
--   각 lbl_map 행을 ① 에서 만든 대응 aug 행(DATA_AUG_SN)에 연결해 이관한다.
--   - RESL_EXPORT_SN 으로 export 조인 → 대표프레임 SRC_SN + 변환된 AUG_TYPE_CD 산출 →
--     LS_DATA_AUG a ON a.SRC_SN=rep.src_sn AND a.AUG_TYPE_CD=REPLACE(...) 로 DATA_AUG_SN 확보.
--   - ORGNL_DATA_LBL_SN/DATA_LBL_SN/COORD_RECALC_YN/SCALE_X/SCALE_Y/REG_ID/REG_DT 는 그대로 복사.
--   - 대응 aug 행이 없으면(대표프레임 부재로 ① 스킵된 경우) INNER JOIN 으로 자연 제외된다.
-- -----------------------------------------------------------------------------
INSERT INTO LS_DATA_AUG_LBL_MAP
    (DATA_AUG_SN, ORGNL_DATA_LBL_SN, DATA_LBL_SN, COORD_RECALC_YN, SCALE_X, SCALE_Y, REG_ID, REG_DT)
SELECT a.DATA_AUG_SN,
       m.ORGNL_DATA_LBL_SN,
       m.DATA_LBL_SN,
       m.COORD_RECALC_YN,
       m.SCALE_X,
       m.SCALE_Y,
       m.REG_ID,
       m.REG_DT
FROM LS_RESOLUTION_LBL_MAP m
JOIN LS_RESOLUTION_EXPORT e ON e.RESL_EXPORT_SN = m.RESL_EXPORT_SN
CROSS JOIN LATERAL (
    SELECT s.SRC_SN AS src_sn
    FROM LS_DATA_SRC s
    WHERE s.RAW_SN = e.DATA_RAW_SN
    ORDER BY s.FRM_NO ASC, s.SRC_SN ASC
    LIMIT 1
) rep
JOIN LS_DATA_AUG a
    ON a.SRC_SN = rep.src_sn
   AND a.AUG_TYPE_CD = REPLACE(e.GOAL_RESL_CD, 'RES_', 'RESL_');

-- -----------------------------------------------------------------------------
-- ③ 비가역 DROP 전 fail-closed 데이터 손실 가드 + 감사 로그.
--
--   ②(INNER JOIN)는 대표프레임이 없는 export(=부모 RAW 에 LS_DATA_SRC 프레임 0건)를 자연 제외한다.
--   그 export 에 라벨매핑(LS_RESOLUTION_LBL_MAP)이 걸려 있으면 이관되지 못한 채 아래 DROP 으로
--   영구 소실된다(무소음 손실). 이런 소실 대상이 1건이라도 있으면 RAISE EXCEPTION 으로 마이그레이션을
--   중단하고 Flyway 단일 트랜잭션 원자성에 의해 ①② 백필과 DROP 전체가 롤백된다(fail-closed).
--
--   판정: lbl_map 총건 - 이관 가능(대표프레임 보유 export 소속) 건 = 소실 건. > 0 이면 중단.
--   라벨매핑이 없는 no-frame export 는 소실 라벨 0 이라 통과(정상). 운영 데이터 0 도 통과.
--   감사 NOTICE 는 항상 로깅하여 "0 no-op" 과 "실제 이관" 을 운영 로그에서 구분한다.
--   정적 SQL(사용자 입력 없음), 로그에 PII/파일경로 미포함(건수·export SN 만).
-- -----------------------------------------------------------------------------
DO $$
DECLARE
    v_export_total        BIGINT;
    v_lblmap_total        BIGINT;
    v_backfilled_aug      BIGINT;
    v_backfilled_lblmap   BIGINT;
    v_lost_map_cnt        BIGINT;
    v_lost_exports        TEXT;
BEGIN
    SELECT COUNT(*) INTO v_export_total FROM LS_RESOLUTION_EXPORT;
    SELECT COUNT(*) INTO v_lblmap_total FROM LS_RESOLUTION_LBL_MAP;

    -- 대표프레임을 보유해 ① 백필 대상이 된 export 수(= 백필된 해상도 파생 aug 후보 건).
    SELECT COUNT(*) INTO v_backfilled_aug
    FROM LS_RESOLUTION_EXPORT e
    WHERE EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = e.DATA_RAW_SN);

    -- 대표프레임 보유 export 에 걸려 ② 이관된 라벨매핑 건.
    SELECT COUNT(*) INTO v_backfilled_lblmap
    FROM LS_RESOLUTION_LBL_MAP m
    JOIN LS_RESOLUTION_EXPORT e ON e.RESL_EXPORT_SN = m.RESL_EXPORT_SN
    WHERE EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = e.DATA_RAW_SN);

    -- 입력 = 이관 보존 검증: 이관되지 못한(대표프레임 없는 export 소속) 라벨매핑 = 소실 건.
    v_lost_map_cnt := v_lblmap_total - v_backfilled_lblmap;

    RAISE NOTICE 'V125 backfill audit: export_total=%, lbl_map_total=%, backfilled_aug_resl=%, backfilled_aug_lbl_map=%',
        v_export_total, v_lblmap_total, v_backfilled_aug, v_backfilled_lblmap;

    IF v_lost_map_cnt > 0 THEN
        SELECT string_agg(DISTINCT e.RESL_EXPORT_SN::text, ',' ORDER BY e.RESL_EXPORT_SN::text)
        INTO v_lost_exports
        FROM LS_RESOLUTION_LBL_MAP m
        JOIN LS_RESOLUTION_EXPORT e ON e.RESL_EXPORT_SN = m.RESL_EXPORT_SN
        WHERE NOT EXISTS (SELECT 1 FROM LS_DATA_SRC s WHERE s.RAW_SN = e.DATA_RAW_SN);

        RAISE EXCEPTION '대표프레임 없는 export 의 라벨매핑 % 건이 이관 불가 — 수동 확인 후 재시도 (export SN: %)',
            v_lost_map_cnt, v_lost_exports;
    END IF;
END $$;

-- -----------------------------------------------------------------------------
-- ④ DROP — FK 참조 순서(lbl_map → export). 두 테이블 모두 명시 FK 제약은 없으나 안전 순서 유지.
--   위 가드를 통과(소실 0)한 경우에만 도달한다.
-- -----------------------------------------------------------------------------
DROP TABLE IF EXISTS LS_RESOLUTION_LBL_MAP;
DROP TABLE IF EXISTS LS_RESOLUTION_EXPORT;
