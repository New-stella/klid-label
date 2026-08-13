-- =============================================================================
-- V146: LS_DATA_RAW 참조 무결성 강제 — 자식 테이블 FK 신설 (DB-ISSUE-01).
--
-- 배경(실측 결함): LS_DATA_RAW 를 참조하는 FK 가 LS_EVNT_ANNO 단 1건(NO ACTION)뿐이라,
--   영상 원본 행이 사라져도 자식(마킹·프레임·상태·비식별로그·작업배정 등)이 고아로 잔존했다.
--   실측 — rawSn 23/24/25 삭제 후 LS_MARKING 에 해당 rawSn 참조 2행 고아 확인. 고아는 조용히
--   집계·뷰·export 를 틀리게 만드는 유형이라 발견이 늦다.
--
-- 삭제 정책: ON DELETE CASCADE (사용자 확정).
--   RESTRICT 로 하면 실재하는 삭제 경로 두 곳이 깨진다 —
--     ① ResolutionPersistService.deleteFailedDerivativeRaw (실패한 해상도 파생본 정리)
--     ② TusUploadService (완료 경합 시 방금 INSERT 한 RAW 롤백)
--   CASCADE 면 자식까지 함께 지워져 고아가 <구조적으로 불가능>해진다.
--   예외 2건은 ON DELETE SET NULL — 아래 3) 참조(원장/세션은 살아남아야 한다).
--
-- 대상 판정(전수 조사 결과, RAW_SN 계열 컬럼 보유 테이블 전량 검토):
--   ▷ 포함 = LS_* 소유 테이블 중 "그 영상에 종속된 행" (아래 1) 목록)
--   ▷ 제외 = MNG_CLIP_SCHEDULE_QUE — 관제서버 소유(MNG_*) 스키마다. CLAUDE.md 상 MNG_* 변경은
--            관제서버팀 선승인 필수이므로 본 마이그레이션에서 건드리지 않는다.
--   ▷ 제외 = LS_DATA_RAW.ORGNL_RAW_SN(파생 계보 self-reference) / LS_DATASET_VIDEO_META.ORGNL_RAW_SN
--            (승인 시점 <동결> 스냅샷 값). 자식이 아니라 계보/동결값이며, 고아 발생 시 자동 복구
--            수단이 둘 다 위험하다 — NULL 로 만들면 파생본이 "원본" 으로 승격돼 비식별 신고 거부·
--            PII 정책(파생은 신고 체계 바깥)이 역전되고, 삭제하면 검수 완료된 파생 학습데이터가 사라진다.
--            별건으로 사람 판단이 필요하다.
--   ▷ 제외 = V_COMPLETED_* (뷰), LS_DATA_RAW 자신의 PK.
--
-- 관제 경계 구속 제약: "검수 완료·통지 건에 대한 관제 접근은 무조건 보장한다"(CLAUDE.md).
--   FK 추가 자체는 뷰 행을 줄이지 않지만, 아래 2) 고아 정리는 줄일 수 있다. 그래서 데이터마트 뷰
--   (V_COMPLETED_*)에 직접 공급되는 테이블은 <자동 삭제하지 않고 마이그레이션을 중단>한다(V143 선례).
--
-- 우리 소유 LS_* 테이블 — 관제 협의 불요. 신규 컬럼/테이블이 없으므로 표준용어·표준도메인 신규 판정
--   대상 없음(제약명만 추가하며 기존 FK_LS_EVNT_ANNO_RAW 와 동일한 FK_{테이블}_RAW 규칙을 따른다).
--
-- 잠금 주의(2노드 Active-Active): ADD CONSTRAINT 는 자식 테이블에 SHARE ROW EXCLUSIVE 를 잡고
--   전량 검증 스캔을 수행한다 — 배포 중 짧은 쓰기 차단이 발생할 수 있다(읽기는 영향 없음).
-- =============================================================================

DO $$
DECLARE
    -- (테이블, 컬럼, 삭제규칙) — 삭제규칙은 'CASCADE' 또는 'SET NULL'.
    child_specs   TEXT[][] := ARRAY[
        -- ---- 배치/파이프라인 ----
        ['ls_bat_rty_wtng',            'raw_sn',      'CASCADE'],
        ['ls_batch_proc_log',          'data_raw_sn', 'CASCADE'],
        ['ls_data_lbl_ai_info',        'data_raw_sn', 'CASCADE'],
        ['ls_data_meta',               'raw_sn',      'CASCADE'],
        ['ls_data_src',                'raw_sn',      'CASCADE'],
        ['ls_deident_proc_log',        'data_raw_sn', 'CASCADE'],
        ['ls_marking',                 'raw_sn',      'CASCADE'],
        -- ---- 작업/검수 ----
        ['ls_auth_work_lock',          'data_raw_sn', 'CASCADE'],
        ['ls_data_issue',              'data_raw_sn', 'CASCADE'],
        ['ls_data_meta_review',        'data_raw_sn', 'CASCADE'],
        ['ls_deident_report',          'data_raw_sn', 'CASCADE'],
        ['ls_raw_data_enrollment',     'raw_data_id', 'CASCADE'],
        ['ls_raw_data_status',         'raw_data_id', 'CASCADE'],
        ['ls_task_assign_history',     'raw_data_id', 'CASCADE'],
        ['ls_task_assignment',         'raw_data_id', 'CASCADE'],
        ['ls_task_event_log',          'raw_data_id', 'CASCADE'],
        -- ---- 버전/증강/데이터셋 ----
        ['ls_data_aug_rvw',            'data_raw_sn', 'CASCADE'],
        ['ls_data_raw_hstry',          'raw_sn',      'CASCADE'],
        ['ls_dataset_export',          'data_raw_sn', 'CASCADE'],
        ['ls_dataset_video_meta',      'raw_sn',      'CASCADE'],
        ['ls_label_version',           'data_raw_sn', 'CASCADE'],
        -- ---- 관제 통지 ----
        ['ls_control_notify_fallback', 'raw_sn',      'CASCADE'],
        ['ls_meta_repl_outbox',        'raw_sn',      'CASCADE'],
        ['ls_mon_noti_acml',           'raw_sn',      'CASCADE'],
        -- ---- 포털 ----
        ['ls_portal_user_label',       'src_raw_sn',  'CASCADE'],
        -- ---- 원장/세션: 행은 살아남고 참조만 끊는다(아래 3) 참조) ----
        ['ls_tus_upload',              'raw_sn',      'SET NULL'],
        ['ls_webhook_idempotency',     'raw_sn',      'SET NULL']
    ];
    -- 데이터마트 뷰(V_COMPLETED_*)에 직접 공급되는 테이블 — 고아라도 자동 삭제하지 않고 중단한다.
    view_feed_tables TEXT[] := ARRAY[
        'ls_dataset_video_meta',   -- V_COMPLETED_VIDEO (영상 메타 동결본)
        'ls_raw_data_status',      -- V_COMPLETED_* 승인 게이트
        'ls_dataset_export',       -- V_COMPLETED_VIDEO.EXPORT_PATH_NM
        'ls_deident_proc_log',     -- V_COMPLETED_VIDEO.DE_IDNTF_FILE_PATH_NM (V138)
        'ls_data_src',             -- V_COMPLETED_FRAME
        'ls_data_meta',            -- V_COMPLETED_META
        'ls_data_meta_review'      -- V_COMPLETED_META 승인 게이트
    ];
    -- 예상 밖 규모 방어 — 한 테이블에서 이 건수를 넘는 고아가 나오면 "정상 운영의 잔여물" 이 아니다.
    max_orphans   CONSTANT BIGINT := 1000;
    tbl           TEXT;
    col           TEXT;
    rule          TEXT;
    cnt           BIGINT;
    fk_name       TEXT;
    i             INT;
BEGIN
    -- -----------------------------------------------------------------------
    -- 1) 고아 실태 조사 + 사람 판단이 필요한 경우 중단 (조용한 대량 삭제 금지)
    -- -----------------------------------------------------------------------
    FOR i IN 1 .. array_length(child_specs, 1) LOOP
        tbl := child_specs[i][1];
        col := child_specs[i][2];
        EXECUTE format(
            'SELECT count(*) FROM %I c WHERE c.%I IS NOT NULL '
            'AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = c.%I)', tbl, col, col)
            INTO cnt;
        IF cnt > 0 THEN
            RAISE NOTICE 'V146 고아 발견: %.% = %건', tbl, col, cnt;
            IF cnt > max_orphans THEN
                RAISE EXCEPTION 'V146 중단: %.% 의 고아가 %건으로 임계(%건)를 넘습니다. '
                    '정상 운영의 잔여물로 보기 어려우므로 자동 정리하지 않습니다 — 원인(대량 삭제/이관)을 '
                    '확인한 뒤 다시 기동하세요.', tbl, col, cnt, max_orphans;
            END IF;
            IF tbl = ANY (view_feed_tables) THEN
                RAISE EXCEPTION 'V146 중단: %.% 에 고아가 %건 있습니다. 이 테이블은 데이터마트 뷰'
                    '(V_COMPLETED_*)에 직접 공급되므로 자동 삭제 시 관제서버가 보던 행이 예고 없이 '
                    '사라집니다("검수 완료·통지 건 관제 접근 보장" 구속 제약). 운영자가 관제와 협의해 '
                    '정리 여부를 결정한 뒤 다시 기동하세요.', tbl, col, cnt;
            END IF;
        END IF;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- 2) 남은 고아 정리 — FK 는 고아가 있으면 생성 자체가 실패하므로 선행이 필요하다.
    --    위 1) 을 통과한 테이블만 대상이므로 (뷰 비공급 + 소량) 삭제가 안전하다.
    --    SET NULL 대상은 삭제가 아니라 참조만 끊는다.
    -- -----------------------------------------------------------------------
    FOR i IN 1 .. array_length(child_specs, 1) LOOP
        tbl  := child_specs[i][1];
        col  := child_specs[i][2];
        rule := child_specs[i][3];
        IF rule = 'SET NULL' THEN
            EXECUTE format(
                'UPDATE %I c SET %I = NULL WHERE c.%I IS NOT NULL '
                'AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = c.%I)', tbl, col, col, col);
        ELSE
            EXECUTE format(
                'DELETE FROM %I c WHERE c.%I IS NOT NULL '
                'AND NOT EXISTS (SELECT 1 FROM ls_data_raw r WHERE r.raw_sn = c.%I)', tbl, col, col);
        END IF;
        GET DIAGNOSTICS cnt = ROW_COUNT;
        IF cnt > 0 THEN
            RAISE NOTICE 'V146 고아 정리: %.% -> % %건', tbl, col, rule, cnt;
        END IF;
    END LOOP;

    -- -----------------------------------------------------------------------
    -- 3) FK 생성 (멱등 — 이미 있으면 건너뛴다).
    --    SET NULL 2건의 근거:
    --      · LS_WEBHOOK_IDEMPOTENCY : 웹훅 재전송 방지 원장. 행이 사라지면 같은 키의 콜백이 재적용된다.
    --      · LS_TUS_UPLOAD          : 업로드 세션(멱등 응답용). RAW_SN 은 결과 참조일 뿐 세션 자체가
    --                                 아니며, 두 컬럼 모두 nullable 이라 SET NULL 이 성립한다.
    -- -----------------------------------------------------------------------
    FOR i IN 1 .. array_length(child_specs, 1) LOOP
        tbl     := child_specs[i][1];
        col     := child_specs[i][2];
        rule    := child_specs[i][3];
        fk_name := 'fk_' || tbl || '_raw';
        IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = fk_name) THEN
            EXECUTE format(
                'ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY (%I) '
                'REFERENCES ls_data_raw (raw_sn) ON DELETE %s', tbl, fk_name, col, rule);
        END IF;
    END LOOP;
END $$;

-- -----------------------------------------------------------------------------
-- 4) 기존 FK_LS_EVNT_ANNO_RAW(NO ACTION) 를 CASCADE 로 통일한다.
--    NO ACTION 이 하나라도 남아 있으면 그 자식 행이 존재할 때 부모 삭제가 <차단>되어, 위 삭제 경로
--    (실패 파생본 정리 / TUS 롤백)가 예고 없이 실패한다. 정책을 CASCADE 로 확정한 이상 예외를 두지 않는다.
-- -----------------------------------------------------------------------------
ALTER TABLE LS_EVNT_ANNO DROP CONSTRAINT IF EXISTS FK_LS_EVNT_ANNO_RAW;
ALTER TABLE LS_EVNT_ANNO
    ADD CONSTRAINT FK_LS_EVNT_ANNO_RAW FOREIGN KEY (RAW_SN)
    REFERENCES LS_DATA_RAW (RAW_SN) ON DELETE CASCADE;
