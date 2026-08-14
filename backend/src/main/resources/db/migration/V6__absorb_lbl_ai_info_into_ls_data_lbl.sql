-- ============================================================================
-- V6: LS_DATA_LBL_AI_INFO 를 LS_DATA_LBL 로 흡수하고 분리 테이블을 제거한다  @req R4
--
--   이관 컬럼 5 : LBL_SRC_CD · MDL_NM · MDL_VER · CONF_SCORE · AUTO_LBL_YN
--   버리는 컬럼 : DATA_RAW_SN · DATA_SRC_SN (LS_DATA_LBL → LS_DATA_SRC 로 이미 도달 가능한 사본)
--                 DATA_LBL_AI_INFO_SN(분리 테이블 자체 식별자) · REG_ID · REG_DT · MDFCN_ID · MDFCN_DT(감사)
--
-- ----------------------------------------------------------------------------
-- ★ 목적은 표 개수 줄이기가 아니라 조인 제거다
-- ----------------------------------------------------------------------------
--   라벨 응답(LabelResponse.Item)은 AUTO_LBL_YN · CONF_SCORE · LBL_SRC_CD 세 값을 <항상 함께>
--   내려준다. 그래서 라벨을 읽는 거의 모든 경로가 두 번째 조회(또는 LATERAL 조인)를 달고 다녔고,
--   보간 산출물 판정 · 오토라벨 멱등 판정 · 통계 자동생성 판정까지 전부 EXISTS 서브쿼리였다.
--   값이 라벨 행에 있으면 이 조회들이 컬럼 접근으로 바뀐다.
--
-- ----------------------------------------------------------------------------
-- ★ DEFAULT 를 걸지 않는다 (Critical — 걸면 두 가지가 구분 불가능해진다)
-- ----------------------------------------------------------------------------
--   흡수 전 의미론은 「AI 정보 행이 없다 = 사람이 그린 라벨」이었다. 그 부재를 흡수 후에 그대로
--   옮기는 표현은 <NULL> 이다. AUTO_LBL_YN 에 DEFAULT 'N' 을 걸면
--     · "AI 가 만들지 않았다"(행 부재)          → 'N'
--     · "AI 가 만들었는데 자동 플래그가 N 이다"(버전 롤백 복원 경로) → 'N'
--   두 사실이 같은 값이 되어 영구히 구분되지 않는다. 그래서 5개 컬럼 모두 NULL 허용 · DEFAULT 없음이다.
--   NULL→'N' 치환은 응답 조립 지점(LabelResponse.Item.from) 한 곳이 담당하며 DB 값을 오염시키지
--   않는다. 즉 DB 축(NULL 유지)과 응답 축('N' 노출)은 서로 다른 층에서 각각 보존된다.
--
-- ----------------------------------------------------------------------------
-- ★ 응답 <스키마>는 불변이다. 다만 <값>이 달라지는 경로가 하나 있다 (Critical — 정직한 서술)
-- ----------------------------------------------------------------------------
--   ⚠ 구 헤더는 "응답 축은 바뀌지 않는다 — 화면·외부 FE 계약은 종전대로 'N' 을 받는다"고 <단언>했다.
--     그 문장은 <거짓>이라 아래로 대체한다. 스키마와 값은 서로 다른 축이며 이 흡수는 둘 중 하나만 지킨다.
--
--   [불변] JSON 응답 <스키마> — record LabelResponse.Item 의 11필드(이름·타입·순서)가 그대로다.
--          바뀐 것은 그 값의 <조달처>(별도 테이블 조인 → 같은 행의 컬럼)뿐이다.
--
--   [불변] 값도 그대로인 경로 — <원래 AI 정보를 조달하던> 경로들이다. 조달처만 바뀌었으므로 값이 같다.
--          · 라벨링 화면 조회·저장   GET/PUT /v1/frames/{srcSn}/labels   (구: aiInfoMap 일괄 lookup)
--          · 검수 승인 버전 스냅샷   VersionService.commitApproved      (구: loadAiInfo)
--          · 회차 선택 작업본        StartVersionService                (구: findByDataLblSnIn)
--
--   [변경] 값이 달라지는 경로 <하나> — 검수 프레임 조회 GET /v1/reviews/{videoId}/frames
--          (ReviewService.listFrames). 이 경로는 흡수 전 Item.from(entity, null, null, ...) 로 AI 정보를
--          <아예 조달하지 않아> 자동 라벨도 전부 autoLblYn='N' · confScore 공란 · lblSrcCd null 을 냈다.
--          "그 라벨이 수동이어서"가 아니라 "값을 읽지 않아서" 나온 값이며, 흡수 후에는 라벨 행이 사실을
--          들고 있어 실제 값이 나간다. 검수 화면 표시가 "수동 라벨" → "자동 라벨"로 바뀐다.
--          ★ <잠복 결함의 우발적 수정이며 되돌리지 않는다> — 되돌리려면 정확한 값을 일부러 지워야 한다.
--
--   원본 NOT NULL 제약(LBL_SRC_CD · AUTO_LBL_YN)도 함께 옮기지 않는다. 그 제약은 "AI 정보 행이
--   존재한다면" 이라는 전제 위에서만 성립했는데, 흡수 후 그 전제는 라벨 행 전체로 넓어진다.
--
-- ----------------------------------------------------------------------------
-- ★ 고아 행은 중단 사유가 아니다 (제외 + 기록)
-- ----------------------------------------------------------------------------
--   LS_DATA_LBL_AI_INFO.DATA_LBL_SN 에는 FK 가 <없다>(실재하는 FK 는 DATA_RAW_SN → LS_DATA_RAW
--   하나뿐이다). 그래서 대응 라벨이 사라진 뒤에도 남은 고아 행이 실재한다.
--   실측(dev, 2026-08-13): AI 정보 8,093행 중 <15행>이 고아. 중복 DATA_LBL_SN 은 0건.
--
--   고아를 RAISE EXCEPTION 으로 중단시키면 그 DB 는 <영원히 기동하지 못한다> — 사전에 쌓인
--   쓰레기이지 이번 변경이 만든 결함이 아니기 때문이다. 이관에서 제외하고 건수만 NOTICE 로 남긴다.
--   ⚠ 반대로 <정상 행>이 하나라도 이관되지 않으면 fail-closed 로 중단한다(아래 ④).
--
-- ----------------------------------------------------------------------------
-- ★ 한 라벨에 AI 정보가 여러 행이면 <결정적 규칙>으로 1행을 고른다
-- ----------------------------------------------------------------------------
--   DATA_LBL_SN 에 UNIQUE 가 없어 다중 행이 <구조적으로 가능>하다(실측 dev 는 0건이지만 스키마가
--   허용하는 한 마이그레이션은 그 경우에도 결정적이어야 한다). 선택 규칙은 흡수 전 화면이 쓰던
--   LATERAL 정렬과 같은 축에 "자동 플래그 우선" 한 단계를 앞에 둔다:
--
--     ORDER BY (AUTO_LBL_YN = 'Y') DESC, MDFCN_DT DESC NULLS LAST, REG_DT DESC, DATA_LBL_AI_INFO_SN DESC
--
--   ⚠ 맨 앞 단계가 필요한 이유: 흡수 전 LATERAL 은 <AUTO_LBL_YN='Y' 인 행만> 후보로 봤다. 그 단계를
--     빼고 단순히 최신 행을 고르면, 'Y' 행과 그보다 최신인 'N' 행을 함께 가진 라벨에서 화면 값이
--     Y → N 으로 뒤집힌다. 같은 이유로 EXISTS(AUTO_LBL_YN='Y') 판정 경로(오토라벨 목록·삭제·통계·
--     보간 후보)도 흡수 전후 결과가 같아진다.
--   ⚠ 남는 비대칭: LBL_SRC_CD 는 흡수 전 EXISTS(LBL_SRC_CD='INTERPOLATE') 로 판정됐으므로, 한 라벨이
--     INTERPOLATE 행과 다른 출처 행을 <동시에> 가지고 후자가 선택되면 보간 판정이 달라질 수 있다.
--     실측 0건이며, 그 조합을 만들 수 있는 코드 경로도 없다(출처는 라벨 생성 시 1회 부여된다).
--     조용히 넘기지 않도록 다중 행 건수를 NOTICE 로 남긴다.
--
-- ----------------------------------------------------------------------------
-- ★ 인덱스 승계 (없으면 풀스캔이 된다)
-- ----------------------------------------------------------------------------
--   흡수 전에는 IDX_LS_DATA_LBL_AI_INFO_SRC_CD(LBL_SRC_CD) 가 보간 산출물 조회를 받쳤다. 그 필터가
--   LS_DATA_LBL 로 옮겨오므로 대응 인덱스를 새로 만든다 — IX_LS_DATA_LBL_LBL_SRC_CD.
--   ⚠ IDX_LS_DATA_LBL_AI_INFO_LBL(DATA_LBL_SN) 과 IDX_..._LBL_AUTO(DATA_LBL_SN, AUTO_LBL_YN) 는
--     승계하지 않는다 — 그 선두 컬럼이 흡수 후 LS_DATA_LBL 의 <기본키>라 PK 인덱스에 흡수된다.
--     IDX_..._SRC(DATA_RAW_SN, DATA_SRC_SN) 도 승계하지 않는다 — 두 컬럼 자체를 옮기지 않는다.
--
-- ----------------------------------------------------------------------------
-- ★ 데이터마트 뷰 4종은 정의가 바뀌지 않는다
-- ----------------------------------------------------------------------------
--   V_COMPLETED_VIDEO · V_COMPLETED_FRAME · V_COMPLETED_LABEL_CHANGE · V_COMPLETED_META 는
--   LS_DATA_LBL_AI_INFO 를 참조하지 않는다(실측 확인). LS_DATA_LBL 쪽은 <컬럼 추가>라 기존 뷰
--   정의에 영향이 없다. 관제 계약면은 이 마이그레이션의 영향 범위 밖이다.
--
-- ----------------------------------------------------------------------------
-- ★ 두 경로가 수동 개입 없이 같은 결과로 수렴한다
-- ----------------------------------------------------------------------------
--   · 신규 설치(빈 DB)  : V1 이 두 테이블을 만들고 여기서 흡수·제거된다. AI 정보가 0행이라
--     이관 UPDATE 가 0행이고 검증도 0 = 0 으로 통과한다.
--   · 기존 DB(로컬·dev) : V5 까지 적용된 상태에서 여기서 흡수·제거된다.
--   두 번 돌아도 안전하다 — 컬럼이 이미 있으면 ADD 를 건너뛰고, 분리 테이블이 이미 없으면
--   이관·검증·DROP 전체를 건너뛴 뒤 인덱스·주석만 보장한다. LS_DATA_LBL 자체가 없으면 완전 no-op 이다.
--   ⚠ Flyway 는 이 파일을 단일 트랜잭션으로 실행하므로 중간 실패 시 컬럼 추가까지 함께 롤백된다
--     (부분 적용 상태가 남지 않는다).
--
-- ★ 카탈로그 조회는 <현재 스키마로 스코프>를 건다 (V1 헤더 「규칙 1·2」)
--   to_regclass 는 search_path 로, information_schema 조회는 current_schema() 로 스코프한다.
--   'public' 리터럴은 쓰지 않는다 — klid_at 운영에서 전부 오판정이 된다(실사고 이력 V62·V63·V71).
--   식별자는 전부 <정적 리터럴>이라 동적 조립이 없다(format('%I') 가 필요한 지점이 없다 — CWE-89 표면 0).
--
-- ----------------------------------------------------------------------------
-- ★ 잠금과 배포 창 (2노드 Active-Active) — V5 보다 심각하다
-- ----------------------------------------------------------------------------
--   잠금 자체는 짧다 — ADD COLUMN(기본값 없음)은 카탈로그만 고치므로 순간이고, 이관 UPDATE 는
--   dev 기준 8천 행 규모다. DROP TABLE 은 ACCESS EXCLUSIVE 를 잡지만 대상 테이블이 사라지는 것이라
--   대기 대상이 남지 않는다.
--
--   ⚠⚠ 문제는 잠금 시간이 아니라 <전진 창>이며, V5 의 개명과 달리 이번엔 <테이블이 사라진다>.
--     노드 A 가 V6 를 적용한 순간부터, 아직 구 jar 인 노드 B 는 LS_DATA_LBL_AI_INFO 를 읽고 쓰므로
--     아래 경로가 전부 실패한다(오류 `relation "ls_data_lbl_ai_info" does not exist`):
--       · 라벨 조회 · 라벨 저장(full-replace) — 라벨링 화면 전면 500(사용자 대면)
--       · 검수 승인 스냅샷 · 버전 롤백        — 승인/롤백 트랜잭션 전체 500(사용자 대면)
--       · 배치 오토라벨(YOLO/SAM2)·트랙 보간   — 매 실행 실패
--       · 통계 화면(자동생성 비율)             — 조회 실패
--     반대 방향도 대칭으로 깨진다 — 신 jar 가 V6 적용 전 DB 를 보면 흡수 컬럼이 없어 같은 경로가
--     `column ... does not exist` 로 실패한다.
--
--   ★ V5 와의 결정적 차이 — <되돌리기가 대칭이 아니다>. V5 는 RENAME 이라 완전 역연산이 있었지만
--     이번엔 DROP TABLE 이라 아래 「롤백 절차」가 <손실을 동반하는 재구성>이다. 그래서 배포 선택지에서
--     롤링 재기동은 권장하지 않는다.
--
--   ★ 배포 절차는 <양쪽 노드 정지 후 배포>를 권장한다 (운영 정책 결정 — 배포 담당이 고른다).
--     (a) 양쪽 노드 정지 후 배포 : 창이 없다. 대가는 짧은 다운타임.  ← 권장
--     (b) 롤링 재기동           : 창 동안 라벨링·승인·롤백이 사용자 대면 500 이 된다. 데이터 유실은
--         없지만(실패가 전부 트랜잭션 롤백) 되돌릴 결심을 하면 (a) 보다 훨씬 비싸다.
--     절차는 deploy/onprem/docs/09-operations-runbook.md §4 참조.
--
-- ----------------------------------------------------------------------------
-- ★ 롤백 절차 (구버전 jar 로 내릴 때) — <완전 역연산이 아니다. 손실을 알고 실행하라>
-- ----------------------------------------------------------------------------
--   Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용한다. 아래를 실행한 뒤
--   DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '6'; 한다.
--
--   ⚠ <되돌릴 수 없는 것 3가지> — 아래 SQL 로도 복구되지 않는다.
--     ① 고아 행(실측 dev 15행) : 대응 라벨이 없어 흡수되지 않았고 DROP 과 함께 사라졌다.
--     ② 감사 컬럼 REG_ID · MDFCN_ID · REG_DT · MDFCN_DT : 흡수 대상이 아니라 값이 남아 있지 않다.
--        재구성 시 REG_ID 는 NULL, REG_DT 는 재구성 시각(now())이 된다 — <원래 시각이 아니다>.
--     ③ 원래 PK DATA_LBL_AI_INFO_SN : 새 IDENTITY 로 재발급되므로 옛 값과 다르다.
--     구버전 jar 의 동작에 이 셋이 필요하지는 않지만(모두 쓰기 전용 감사 축), 감사 추적은 끊긴다.
--
--     CREATE TABLE ls_data_lbl_ai_info (
--         data_lbl_ai_info_sn bigint NOT NULL,
--         data_lbl_sn bigint NOT NULL,
--         data_raw_sn bigint NOT NULL,
--         data_src_sn bigint NOT NULL,
--         lbl_src_cd character varying(20) NOT NULL,
--         mdl_nm character varying(100),
--         mdl_ver character varying(50),
--         conf_score numeric(6,5),
--         auto_lbl_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
--         reg_id character varying(30),
--         reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
--         mdfcn_id character varying(30),
--         mdfcn_dt timestamp without time zone
--     );
--     ALTER TABLE ls_data_lbl_ai_info ALTER COLUMN data_lbl_ai_info_sn
--         ADD GENERATED BY DEFAULT AS IDENTITY (
--         SEQUENCE NAME ls_data_lbl_ai_info_data_lbl_ai_info_sn_seq
--         START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1);
--     ALTER TABLE ONLY ls_data_lbl_ai_info
--         ADD CONSTRAINT ls_data_lbl_ai_info_pkey PRIMARY KEY (data_lbl_ai_info_sn);
--     ALTER TABLE ONLY ls_data_lbl_ai_info
--         ADD CONSTRAINT fk_ls_data_lbl_ai_info_raw FOREIGN KEY (data_raw_sn)
--         REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;
--     CREATE INDEX idx_ls_data_lbl_ai_info_lbl      ON ls_data_lbl_ai_info USING btree (data_lbl_sn);
--     CREATE INDEX idx_ls_data_lbl_ai_info_lbl_auto ON ls_data_lbl_ai_info USING btree (data_lbl_sn, auto_lbl_yn);
--     CREATE INDEX idx_ls_data_lbl_ai_info_src      ON ls_data_lbl_ai_info USING btree (data_raw_sn, data_src_sn);
--     CREATE INDEX idx_ls_data_lbl_ai_info_src_cd   ON ls_data_lbl_ai_info USING btree (lbl_src_cd);
--     -- 흡수 컬럼에서 되살린다. 출처(NOT NULL)가 없는 라벨은 애초에 AI 정보 행이 없던 라벨이라 제외한다.
--     INSERT INTO ls_data_lbl_ai_info
--            (data_lbl_sn, data_raw_sn, data_src_sn, lbl_src_cd, mdl_nm, mdl_ver, conf_score, auto_lbl_yn, reg_dt)
--     SELECT l.lbl_sn, s.raw_sn, l.src_sn, l.lbl_src_cd, l.mdl_nm, l.mdl_ver, l.conf_score,
--            COALESCE(l.auto_lbl_yn, 'N'), now()
--       FROM ls_data_lbl l
--       JOIN ls_data_src s ON s.src_sn = l.src_sn
--      WHERE l.lbl_src_cd IS NOT NULL;
--     DROP INDEX IF EXISTS ix_ls_data_lbl_lbl_src_cd;
--     ALTER TABLE ls_data_lbl DROP COLUMN IF EXISTS lbl_src_cd,
--                             DROP COLUMN IF EXISTS mdl_nm,
--                             DROP COLUMN IF EXISTS mdl_ver,
--                             DROP COLUMN IF EXISTS conf_score,
--                             DROP COLUMN IF EXISTS auto_lbl_yn;
-- ============================================================================

DO $$
DECLARE
    ai_total     bigint;
    orphan_cnt   bigint;
    dup_extra    bigint;
    expected_cnt bigint;
    updated_cnt  bigint;
BEGIN
    -- LS_DATA_LBL 자체가 없는 DB → 완전 no-op.
    IF to_regclass('ls_data_lbl') IS NULL THEN
        RAISE NOTICE 'ls_data_lbl 이 없어 V6 를 건너뛴다.';
        RETURN;
    END IF;

    -- ------------------------------------------------------------------
    -- ① 흡수 컬럼 5개 추가 — NULL 허용, DEFAULT 없음(헤더 「DEFAULT 를 걸지 않는다」).
    --    타입·폭은 원본 정의 그대로다(코드값 V20 · 모델명 V100 · 버전 V50 · 신뢰도 numeric(6,5) · 여부 C1).
    -- ------------------------------------------------------------------
    ALTER TABLE ls_data_lbl
        ADD COLUMN IF NOT EXISTS lbl_src_cd  character varying(20),
        ADD COLUMN IF NOT EXISTS mdl_nm      character varying(100),
        ADD COLUMN IF NOT EXISTS mdl_ver     character varying(50),
        ADD COLUMN IF NOT EXISTS conf_score  numeric(6,5),
        ADD COLUMN IF NOT EXISTS auto_lbl_yn character(1);

    IF to_regclass('ls_data_lbl_ai_info') IS NOT NULL THEN

        -- --------------------------------------------------------------
        -- ② 이관 대상 집계 — 고아·다중행을 먼저 확정한다(값 자체는 세지 않는다).
        -- --------------------------------------------------------------
        SELECT count(*) INTO ai_total FROM ls_data_lbl_ai_info;

        SELECT count(*) INTO orphan_cnt
          FROM ls_data_lbl_ai_info ai
         WHERE NOT EXISTS (SELECT 1 FROM ls_data_lbl l WHERE l.lbl_sn = ai.data_lbl_sn);

        -- 이관되어야 할 <라벨 수>. 행 수가 아니라 DISTINCT 라벨 수인 이유는 DATA_LBL_SN 에 UNIQUE 가
        -- 없어 한 라벨이 여러 행을 가질 수 있기 때문이다(헤더 「결정적 규칙」).
        SELECT count(DISTINCT ai.data_lbl_sn) INTO expected_cnt
          FROM ls_data_lbl_ai_info ai
         WHERE EXISTS (SELECT 1 FROM ls_data_lbl l WHERE l.lbl_sn = ai.data_lbl_sn);

        dup_extra := (ai_total - orphan_cnt) - expected_cnt;

        IF orphan_cnt > 0 THEN
            -- 중단하지 않는다 — 사전에 쌓인 쓰레기이지 이번 변경의 결함이 아니다(헤더 참조).
            RAISE NOTICE 'AI 정보 고아 행 %건은 대응 라벨이 없어 이관에서 제외한다(전체 %건).',
                orphan_cnt, ai_total;
        END IF;
        IF dup_extra > 0 THEN
            RAISE NOTICE '한 라벨에 AI 정보가 여러 행인 경우가 있다 — 초과 %건은 결정적 규칙으로 1행만 채택한다.',
                dup_extra;
        END IF;

        -- --------------------------------------------------------------
        -- ③ 이관 — 라벨당 1행을 결정적으로 골라 흡수 컬럼에 옮긴다.
        --    DISTINCT ON 이 라벨당 정확히 1행을 남기므로 UPDATE 대상이 중복되지 않고, 조인이
        --    고아 행을 자동으로 배제한다(대응 라벨이 없으면 매칭되지 않는다).
        --    ⚠ LATERAL 은 쓸 수 없다 — UPDATE 의 FROM 절 LATERAL 은 갱신 대상 테이블 별칭을
        --      참조하지 못한다(`invalid reference to FROM-clause entry for table "l"`, 실측).
        --    ADD COLUMN 직후의 컬럼을 참조하므로 EXECUTE(동적 준비)로 실행한다 — 문자열 결합은
        --    없고 전부 정적 리터럴이다(CWE-89 표면 0).
        -- --------------------------------------------------------------
        EXECUTE $sql$
            UPDATE ls_data_lbl l
               SET lbl_src_cd  = pick.lbl_src_cd,
                   mdl_nm      = pick.mdl_nm,
                   mdl_ver     = pick.mdl_ver,
                   conf_score  = pick.conf_score,
                   auto_lbl_yn = pick.auto_lbl_yn
              FROM (
                   SELECT DISTINCT ON (ai.data_lbl_sn)
                          ai.data_lbl_sn, ai.lbl_src_cd, ai.mdl_nm, ai.mdl_ver,
                          ai.conf_score, ai.auto_lbl_yn
                     FROM ls_data_lbl_ai_info ai
                    ORDER BY ai.data_lbl_sn,
                             (ai.auto_lbl_yn = 'Y') DESC,
                             ai.mdfcn_dt DESC NULLS LAST,
                             ai.reg_dt DESC,
                             ai.data_lbl_ai_info_sn DESC
              ) pick
             WHERE pick.data_lbl_sn = l.lbl_sn
        $sql$;
        GET DIAGNOSTICS updated_cnt = ROW_COUNT;

        -- --------------------------------------------------------------
        -- ④ fail-closed — 정상 행이 하나라도 누락되면 중단한다.
        --    ⚠ 예외 메시지에는 <건수만> 싣는다. 식별자·값을 실으면 로그로 새어 나간다(CWE-209/117).
        -- --------------------------------------------------------------
        IF updated_cnt <> expected_cnt THEN
            RAISE EXCEPTION
                'AI 정보 이관 건수가 맞지 않는다 — 기대 %건, 실제 %건(전체 %건, 고아 %건). '
                '값이 조용히 누락된 상태로 분리 테이블을 지울 수 없어 중단한다.',
                expected_cnt, updated_cnt, ai_total, orphan_cnt;
        END IF;

        RAISE NOTICE 'AI 정보 이관 완료 — 라벨 %건(전체 %건, 고아 제외 %건).',
            updated_cnt, ai_total, orphan_cnt;

        -- --------------------------------------------------------------
        -- ⑤ 분리 테이블 제거. 참조하는 뷰·FK 가 없음을 확인했으므로 CASCADE 를 쓰지 않는다
        --    (CASCADE 는 우리가 모르는 의존 객체까지 조용히 지운다 — 있으면 여기서 실패해야 한다).
        -- --------------------------------------------------------------
        DROP TABLE ls_data_lbl_ai_info;
    ELSE
        RAISE NOTICE 'ls_data_lbl_ai_info 가 이미 없다 — 이관을 건너뛰고 인덱스·주석만 보장한다.';
    END IF;

    -- ------------------------------------------------------------------
    -- ⑥ 인덱스 승계 — 보간 산출물 조회(LBL_SRC_CD='INTERPOLATE')가 풀스캔이 되지 않게 한다.
    -- ------------------------------------------------------------------
    CREATE INDEX IF NOT EXISTS ix_ls_data_lbl_lbl_src_cd ON ls_data_lbl USING btree (lbl_src_cd);

    -- ------------------------------------------------------------------
    -- ⑦ 컬럼 주석 — 흡수 근거와 NULL 의미를 DB 에도 남긴다(멱등).
    -- ------------------------------------------------------------------
    COMMENT ON COLUMN ls_data_lbl.lbl_src_cd IS
        '라벨출처코드. YOLO / SAM2 / INTERPOLATE / VLM. NULL = AI 가 만들지 않은 라벨(사람이 그린 것).';
    COMMENT ON COLUMN ls_data_lbl.mdl_nm IS
        '모델명. 자동 라벨을 만든 추론 모델. 적재 경로가 아직 값을 채우지 않아 전량 NULL 이다.';
    COMMENT ON COLUMN ls_data_lbl.mdl_ver IS
        '모델버전. MDL_NM 과 같은 이유로 현재 전량 NULL 이다.';
    COMMENT ON COLUMN ls_data_lbl.conf_score IS
        '신뢰도점수 0.0~1.0. 수동 라벨과 보간 이전 라벨은 NULL.';
    COMMENT ON COLUMN ls_data_lbl.auto_lbl_yn IS
        '자동라벨여부 Y/N. NULL 은 "N" 이 아니라 <AI 정보가 없는 라벨>이라는 뜻이다 — DEFAULT 를 걸지 않는다. '
        '응답의 N 치환은 조립 지점이 담당하며 이 컬럼을 오염시키지 않는다.';
END $$;
