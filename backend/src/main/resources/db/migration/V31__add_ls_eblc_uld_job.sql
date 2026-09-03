-- ============================================================================
-- V31: 마킹이 끝난 영상을 폴더째 받아 일괄로 올리는 작업 원장  @design ERD-032 @design ADR-053
--
--   1) ls_eblc_uld_job        일괄업로드작업   (신규 테이블 · 시드 없음)
--   2) ls_eblc_uld_job_artcl  작업 항목        (신규 테이블 · 시드 없음)
--
-- 외부에서 이벤트 마킹까지 끝낸 영상 묶음을 백 건 단위로 받는다. 요청은 곧바로 반환하고 실제
-- 적재는 뒤에서 항목별로 진행하므로, <어디까지 되었는지와 무엇이 왜 실패했는지> 를 담아 둘
-- 자리가 필요하다. 이 두 표가 그 자리다.
--
-- ⚠ 이 마이그레이션은 <원장만> 세운다. 훑기·적재·예약 마킹 활성화는 애플리케이션이 한다.
--
-- ----------------------------------------------------------------------------
-- * ★ ls_marking 은 이 마이그레이션의 대상이 아니다 (실측 확인)
-- ----------------------------------------------------------------------------
--   외부 마킹은 예약 상태(RESERVED)로 담기지만 <DDL 이 필요 없다>.
--     (1) stts_cd 에 CHECK 제약이 없다 — 값 목록을 DB 가 강제하지 않는다.
--     (2) 활성 마킹 부분 유니크가 이미 그 값을 배제한다:
--         uk_ls_marking_raw_actvtn ... WHERE stts_cd IN ('PENDING','VLM_REQUESTED')
--         RESERVED 는 그 목록 밖이라 영상당 활성 마킹 1건 제약을 <점유하지 않는다>.
--         이것이 ADR-052 가 「업로드 시점 즉시 활성화」를 기각한 근거이며, 비식별이 실패해도
--         사람이 그 영상을 다시 마킹할 수 있는 이유다.
--     (3) varchar(16) 에 8자가 들어간다.
--   ⇒ 상태값 추가는 애플리케이션 상수로 끝난다. 여기서 제약을 새로 걸면 오히려 위 (2)의
--     성질을 깨뜨릴 위험만 생긴다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   일괄 = EBLC · 업로드 = ULD · 작업 = JOB · 항목 = ARTCL · 원본 = ORGNL · 폴더 = FLDR
--   경로 = PATH · 명 = NM · 상태 = STTS · 코드 = CD · 대상 = TRGT · 성공 = SCS · 실패 = FAIL
--   건수 = NOCS · 요구 = DMND · 내용 = CN · 시작 = BGNG · 완료 = CMPTN · 일시 = DT
--   등록 = REG · 수정 = MDFCN · 아이디 = ID · 일련번호 = SN · 마크 = MARK · 영상 = VDO
--   파일 = FILE · 사유 = RSN · 재시도 = RTRY · 횟수 = NMTM · 원시 = RAW
--
--   ★ 물리명은 ERD-032 가 이미 확정한 값을 <그대로> 쓴다. 여기서 다듬지 않는다 —
--     설계와 스키마가 갈리면 엔티티 매핑이 조용히 어긋난다.
--
--   도메인(타입·크기)도 정본을 따른다.
--     폴더경로명   = ORGNL_FLDR_PATH_NM / 경로V300      -> varchar(300)
--     파일경로명   = *_FILE_PATH_NM     / 경로V300      -> varchar(300)
--     상태코드     = *_STTS_CD          / 코드V20       -> varchar(20)
--     건수         = *_NOCS             / 수N10 상당    -> integer
--     실패사유     = FAIL_RSN           / 내용V4000     -> varchar(4000)
--     재시도횟수   = RTRY_NMTM          / 수            -> integer
--     요구내용     = DMND_CN            / 내용(장문)    -> text
--     등록아이디   = REG_ID             / 아이디V30     -> varchar(30)
--     일시         = *_DT               / 연월일시분초D -> timestamp
--     일련번호     = *_SN               / 일련번호B20   -> bigint
--
-- ----------------------------------------------------------------------------
-- * ★ uk_leuja_job_mark — 같은 작업 안에서 같은 마킹 문서를 두 번 담지 않는다
-- ----------------------------------------------------------------------------
--   훑기가 같은 문서를 두 번 집거나 요청이 중복 전송되면 한 문서가 두 항목이 되고, 그러면
--   같은 영상을 두 번 적재하려 든다. 작업 식별자 + 마킹 문서 경로를 유니크로 묶어 입구에서 막는다.
--   ⚠ 이것은 <작업 안> 의 중복만 막는다. 서로 다른 작업이 같은 문서를 담는 것은 정당한
--     재시도이며, 그때의 중복 적재는 영상 식별자(vms_clip_id) 유니크가 막는다.
--
-- ----------------------------------------------------------------------------
-- * ★ FK 두 개의 ON DELETE 가 서로 다르다 — ERD-032 가 그렇게 정했고 근거가 있다
-- ----------------------------------------------------------------------------
--   fk_leuja_job (항목 -> 작업)   : ON DELETE CASCADE
--   fk_leuja_raw (항목 -> 영상)   : ON DELETE SET NULL
--
--   작업이 지워지면 그 항목들은 존재 이유가 없다. 항목은 <작업의 부품> 이라 부모 없이 남을 자리가
--   없으므로 함께 지운다.
--
--   반면 영상이 지워져도 <그 영상을 적재하려 했다는 기록> 은 남아야 한다. 이 표는 영상에 종속된
--   데이터가 아니라 <작업 이력> 이고, 영상보다 오래 살아야 하는 성질이다. 링크만 끊고 항목은 남긴다.
--
--   ⚠⚠ 여기에 CASCADE 를 걸면 두 가지가 조용히 깨진다. 실제로 이 파일의 초안이 그렇게 썼다가
--     설계 대조에서 잡혔다.
--       ① 영상을 지우는 순간 그 항목의 fail_rsn(무엇이 왜 실패했는지)이 <감사 기록째> 사라진다.
--       ② 부모의 scs_nocs / fail_nocs 는 그대로 남는데 항목 행만 없어져, 진행률의 분자와 분모가
--          어긋난다. 집계와 건별 목록이 서로 다른 말을 하게 된다.
--
--   ⚠ 「ls_data_raw 를 참조하는 표는 baseline 에서 전부 CASCADE 다」는 관찰은 <사실이지만 근거가
--     아니다>. 그 표들(라벨·프레임·메타·처리로그)은 전부 <영상에 종속된 데이터> 라 영상이 사라지면
--     의미가 없어지는 것들이다. 이 표는 성격이 다르다. 개수가 많다고 관례가 되는 것이 아니다.

-- ----------------------------------------------------------------------------
-- * ★ idx_leuja_stts 가 두 컬럼인 이유
-- ----------------------------------------------------------------------------
--   (artcl_stts_cd, eblc_uld_job_artcl_sn) 이다. 처리기가 <다음에 집을 항목> 을 고를 때
--   상태로 거르고 일련번호로 정렬해 순서를 고정한다. 상태 하나만 걸면 정렬이 인덱스를 못 타고,
--   2노드가 같은 후보를 서로 다른 순서로 집어 경합이 늘어난다.
--
-- ----------------------------------------------------------------------------
-- * 되돌리기
-- ----------------------------------------------------------------------------
--     DROP TABLE IF EXISTS ls_eblc_uld_job_artcl;
--     DROP TABLE IF EXISTS ls_eblc_uld_job;
--   신규 표라 다른 표를 건드리지 않는다. 다만 <이미 적재 이력이 쌓였다면> 그 이력이 사라지므로
--   드롭 전에 보존 여부를 판단할 것.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 1) ls_eblc_uld_job — 일괄업로드작업
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ls_eblc_uld_job (
    eblc_uld_job_sn bigint NOT NULL,
    orgnl_fldr_path_nm character varying(300) NOT NULL,
    job_stts_cd character varying(20) DEFAULT 'RUNNING' NOT NULL,
    trgt_nocs integer DEFAULT 0 NOT NULL,
    scs_nocs integer DEFAULT 0 NOT NULL,
    fail_nocs integer DEFAULT 0 NOT NULL,
    dmnd_cn text,
    bgng_dt timestamp without time zone,
    cmptn_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_eblc_uld_job') AND conname = 'ls_eblc_uld_job_pkey'
    ) THEN
        ALTER TABLE ls_eblc_uld_job ADD CONSTRAINT ls_eblc_uld_job_pkey PRIMARY KEY (eblc_uld_job_sn);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('ls_eblc_uld_job')
          AND attname = 'eblc_uld_job_sn'
          AND attidentity <> ''
    ) THEN
        ALTER TABLE ls_eblc_uld_job ALTER COLUMN eblc_uld_job_sn ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME ls_eblc_uld_job_eblc_uld_job_sn_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_leuj_stts ON ls_eblc_uld_job USING btree (job_stts_cd);

COMMENT ON TABLE ls_eblc_uld_job IS
    '마킹이 끝난 영상 묶음을 폴더째 받아 일괄로 올리는 작업. 요청은 곧바로 반환하고 실제 적재는 뒤에서 항목별로 진행하므로, 진행과 결과를 여기 담는다.';
COMMENT ON COLUMN ls_eblc_uld_job.eblc_uld_job_sn IS '일괄업로드작업일련번호. 자동 증가.';
COMMENT ON COLUMN ls_eblc_uld_job.orgnl_fldr_path_nm IS '훑은 폴더의 위치. 허용된 저장소 범위 안이어야 한다.';
COMMENT ON COLUMN ls_eblc_uld_job.job_stts_cd IS
    '작업상태코드. RUNNING(진행중) → COMPLETED(완료) | FAILED(실패) | CANCELED(취소). COMPLETED 는 모든 항목이 끝나고 실패가 없는 경우, FAILED 는 모든 항목이 끝났으나 실패가 남은 경우, CANCELED 는 사람이 중단시킨 경우다.';
COMMENT ON COLUMN ls_eblc_uld_job.trgt_nocs IS '대상건수. 작업을 만들 때 정해지고 이후 바뀌지 않는다. 진행률의 분모다.';
COMMENT ON COLUMN ls_eblc_uld_job.scs_nocs IS '성공건수. 적재에 성공한 항목 수.';
COMMENT ON COLUMN ls_eblc_uld_job.fail_nocs IS '실패건수. 실패했거나 건너뛴 항목 수.';
COMMENT ON COLUMN ls_eblc_uld_job.dmnd_cn IS '요구내용. 마킹 문서에서 얻을 수 없어 사람이 지정한 값(이벤트 유형·지자체 코드·카메라 식별자·개인정보 유형·촬영일시)을 담는다. 항목마다 같은 값이 붙는다.';
COMMENT ON COLUMN ls_eblc_uld_job.bgng_dt IS '시작일시. 첫 항목의 처리를 시작한 시각.';
COMMENT ON COLUMN ls_eblc_uld_job.cmptn_dt IS '완료일시. 마지막 항목이 끝나 작업이 종결된 시각.';
COMMENT ON COLUMN ls_eblc_uld_job.reg_id IS '등록아이디. 이 작업을 실행한 사람.';
COMMENT ON COLUMN ls_eblc_uld_job.reg_dt IS '등록일시.';
COMMENT ON COLUMN ls_eblc_uld_job.mdfcn_dt IS '수정일시.';


-- ----------------------------------------------------------------------------
-- 2) ls_eblc_uld_job_artcl — 작업 항목 (마킹 문서 1건 = 항목 1건)
-- ----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ls_eblc_uld_job_artcl (
    eblc_uld_job_artcl_sn bigint NOT NULL,
    eblc_uld_job_sn bigint NOT NULL,
    mark_file_path_nm character varying(300) NOT NULL,
    vdo_file_path_nm character varying(300),
    artcl_stts_cd character varying(20) DEFAULT 'PENDING' NOT NULL,
    raw_sn bigint,
    fail_rsn character varying(4000),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    bgng_dt timestamp without time zone,
    cmptn_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_eblc_uld_job_artcl') AND conname = 'ls_eblc_uld_job_artcl_pkey'
    ) THEN
        ALTER TABLE ls_eblc_uld_job_artcl ADD CONSTRAINT ls_eblc_uld_job_artcl_pkey PRIMARY KEY (eblc_uld_job_artcl_sn);
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('ls_eblc_uld_job_artcl')
          AND attname = 'eblc_uld_job_artcl_sn'
          AND attidentity <> ''
    ) THEN
        ALTER TABLE ls_eblc_uld_job_artcl ALTER COLUMN eblc_uld_job_artcl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME ls_eblc_uld_job_artcl_eblc_uld_job_artcl_sn_seq
            START WITH 1
            INCREMENT BY 1
            NO MINVALUE
            NO MAXVALUE
            CACHE 1
        );
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_eblc_uld_job_artcl') AND conname = 'fk_leuja_job'
    ) THEN
        ALTER TABLE ls_eblc_uld_job_artcl ADD CONSTRAINT fk_leuja_job
            FOREIGN KEY (eblc_uld_job_sn) REFERENCES ls_eblc_uld_job (eblc_uld_job_sn) ON DELETE CASCADE;
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = to_regclass('ls_eblc_uld_job_artcl') AND conname = 'fk_leuja_raw'
    ) THEN
        ALTER TABLE ls_eblc_uld_job_artcl ADD CONSTRAINT fk_leuja_raw
            FOREIGN KEY (raw_sn) REFERENCES ls_data_raw (raw_sn) ON DELETE SET NULL;
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_leuja_job ON ls_eblc_uld_job_artcl USING btree (eblc_uld_job_sn);
CREATE INDEX IF NOT EXISTS idx_leuja_stts ON ls_eblc_uld_job_artcl USING btree (artcl_stts_cd, eblc_uld_job_artcl_sn);
CREATE UNIQUE INDEX IF NOT EXISTS uk_leuja_job_mark ON ls_eblc_uld_job_artcl USING btree (eblc_uld_job_sn, mark_file_path_nm);

COMMENT ON TABLE ls_eblc_uld_job_artcl IS
    '일괄업로드작업의 항목. 마킹 문서 한 건이 항목 한 건이다. 적재는 항목마다 따로 트랜잭션을 가지므로 한 건이 실패해도 나머지가 이어진다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.eblc_uld_job_artcl_sn IS '일괄업로드작업항목일련번호. 자동 증가.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.eblc_uld_job_sn IS '소속 일괄업로드작업일련번호.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.mark_file_path_nm IS '마킹 문서의 위치.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.vdo_file_path_nm IS
    '짝지은 영상 파일의 위치. 짝을 찾지 못하면 비어 있다. 마킹 문서가 함께 담은 원래 경로는 다른 체계에서 만들어진 값이라 위치로 쓰지 않는다 — 짝짓기는 문서가 적어 둔 파일 이름으로 한다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.artcl_stts_cd IS
    '항목상태코드. PENDING(대기) → PROCESSING(처리중) → SUCCESS(성공) | FAILED(실패) | SKIPPED(건너뜀). 건너뜀은 짝을 찾지 못했거나 적재할 수 없는 상태여서 처리하지 않은 것이며 실패와 구분한다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.raw_sn IS '적재에 성공해 만들어진 영상의 일련번호. 성공하기 전에는 비어 있다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.fail_rsn IS
    '실패했거나 건너뛴 사유. 사람이 읽고 무엇을 고쳐야 하는지 알 수 있는 문장이며 내부 구조를 드러내지 않는다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.rtry_nmtm IS
    '재시도횟수. 처리 도중 서버가 다시 뜨면 처리 중이던 항목을 다시 집을 수 있게 되돌리는데, 정해진 횟수를 넘게 집힌 항목은 실패로 마감해 영원히 맴돌지 않게 한다.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.bgng_dt IS '시작일시.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.cmptn_dt IS '완료일시.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.reg_dt IS '등록일시.';
COMMENT ON COLUMN ls_eblc_uld_job_artcl.mdfcn_dt IS '수정일시.';
