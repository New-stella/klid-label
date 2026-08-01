-- =============================================================================
-- V155: LS_DATA_AUG.NEW_RAW_SN 신설 — 증강/해상도 <b>요청 행 ↔ 그 요청이 만든 파생 영상</b> 매핑.
--
-- 배경(실측 결함): 증강 행(LS_DATA_AUG)과 그 결과로 생성된 파생 영상(LS_DATA_RAW.ORGNL_RAW_SN 보유)을
--   잇는 컬럼이 없었다. 그래서
--     ① 결과 조회(AugmentResultItemResponse.derivativeRawSn)가 항상 null 이었고
--        (유형만으로 짝지으면 같은 종류 파생이 여러 건일 때 <다른 요청의 파생본>을 가리킨다 —
--         같은 (영상 × 종류) 반복 요청이 허용된 2026-07-31 이후로는 확실히 틀린다),
--     ② "이 파생 영상이 사람 검수(LS_DATA_AUG_RVW)를 통과했는가" 를 물을 수 없었다.
--   ②는 파생영상 등재 게이팅의 전제라, 이 컬럼 없이는 게이트 자체가 성립하지 않는다.
--
-- 표준용어·표준도메인: 사업표준용어에 <b>복합용어 「신규원시일련번호 / NEW_RAW_SN」(데이터타입 N,
--   길이 19)</b> 로 이미 등록돼 있다. 단어를 새로 조합하지 않고 등록값을 그대로 채택했다.
--   물리 타입은 리포 내 모든 *_SN 관행과 동일한 BIGINT(=N(19)).
--
-- NULL 허용: 요청 시점에는 파생 영상이 아직 없다(증강은 외부 콜백 이후, 해상도는 예약 직후 INSERT).
--   생성에 실패한 요청은 영원히 NULL 로 남는다.
--
-- ★ 백필하지 않는다 (의도된 결정):
--   기존 행을 사후 복원할 유일한 단서는 LS_DATA_RAW.VMS_CLIP_ID 의 파생 마커인데, 그 접미는
--   dataAugSn 이 아니라 <생성 시각(timestamp)> 이다(해상도 경로는 애초에 증강 행 식별자를 인코딩한
--   적이 없다). 시각 기반 역추정은 같은 (영상 × 종류) 파생이 여러 건일 때 <엉뚱한 행>을 가리키므로,
--   "틀린 매핑" 보다 "매핑 없음(NULL)" 이 낫다. 등재 게이트는 NEW_RAW_SN IS NULL 인 기존 파생을
--   <그랜드퍼더링>으로 통과시키므로(신규 생성 경로 2곳이 모두 같은 트랜잭션에서 값을 채운다)
--   백필이 없어도 기존 작업 흐름이 끊기지 않는다.
--
-- ★ FK 를 걸지 않는다 (V146 의 판정 기준을 그대로 따른 결과):
--   V146 은 LS_DATA_RAW 자식 테이블 27개에 FK(ON DELETE CASCADE)를 세우면서 <계보(lineage) 링크>인
--   LS_DATA_RAW.ORGNL_RAW_SN 과 LS_DATASET_VIDEO_META.ORGNL_RAW_SN 은 <명시적으로 제외>했다 —
--   "자식이 아니라 계보/동결값이며 고아 발생 시 자동 복구 수단이 위험하다" 는 이유다.
--   NEW_RAW_SN 은 ORGNL_RAW_SN 의 <반대 방향 같은 계보 링크>라 같은 판정이 적용된다. 구체적으로:
--     · CASCADE 로 걸면 — 실패한 파생 RAW 정리(ResolutionPersistService.deleteFailedDerivativeRaw)가
--       <증강 요청 이력 자체를 삭제>한다. 요청·프롬프트·실패 사실이 사라져 감사 추적이 끊긴다.
--     · RESTRICT/NO ACTION 으로 걸면 — 그 정리 경로가 FK 위반으로 깨진다(V146 이 CASCADE 를 고른
--       바로 그 이유). 예약 aug 해제가 라벨맵 참조로 skip 되는 분기가 실재해, aug 행이 남은 채
--       파생 RAW 만 지우는 조합이 성립한다.
--   FK 부재로 남을 수 있는 것은 "이미 삭제된 파생 RAW_SN 을 가리키는 NEW_RAW_SN" 뿐인데, 게이트는
--   LS_DATA_RAW 를 기준으로 판정하므로(행이 없으면 판정 대상 자체가 없다) 오작동하지 않는다.
--
-- 우리 소유 LS_* 테이블 — 관제 협의 불요. 관제 계약(V_COMPLETED_*) 변경 없음.
-- =============================================================================

ALTER TABLE LS_DATA_AUG
    ADD COLUMN IF NOT EXISTS NEW_RAW_SN BIGINT NULL;

COMMENT ON COLUMN LS_DATA_AUG.NEW_RAW_SN IS
    '신규원시일련번호 - 이 증강/해상도 요청이 생성한 파생 영상(LS_DATA_RAW.RAW_SN). 생성 전/실패 시 NULL. V155 이전 행은 백필하지 않아 NULL(등재 게이트 그랜드퍼더링 대상). FK 미설정 - V146 계보 링크 제외 규칙 준수';

-- 등재 게이트는 파생 영상 1건마다 "그 영상을 만든 증강 행" 을 상관 EXISTS 로 되짚는다
-- (WHERE a.NEW_RAW_SN = r.RAW_SN). 목록·집계가 페이지마다 이 서브쿼리를 돌리므로 인덱스가 없으면
-- LS_DATA_AUG 전량 스캔이 된다. 값이 있는 행(=파생을 만든 요청)만 대상이라 부분 인덱스로 둔다.
CREATE INDEX IF NOT EXISTS IX_LS_DATA_AUG_NEW_RAW_SN
    ON LS_DATA_AUG (NEW_RAW_SN)
    WHERE NEW_RAW_SN IS NOT NULL;
