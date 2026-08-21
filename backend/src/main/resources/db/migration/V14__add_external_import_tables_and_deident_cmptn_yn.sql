-- ============================================================================
-- V14: 외부 산출물 이관 도메인의 저장소를 만든다  @design DOMAIN-017 · ERD-031 · ERD-015
--
--   1) ls_otsd_ctgry_mpng          외부 분류 대응 (신규 테이블)
--   2) ls_otsd_datst_trnsf_hstry   이관 이력     (신규 테이블)
--   3) ls_raw_data_status.de_idntf_cmptn_yn  비식별화완료여부 (컬럼 추가)
--
-- 외부에서 이미 라벨링이 끝난 산출물 묶음을 저작도구로 가져와 검수를 거쳐 학습데이터로 편입시키는
-- 경로가 신설된다. 그 경로는 확정 정책 세 곳의 예외 위에 놓인다(ADR-048) — 관제 수신 원장을 거치지
-- 않고 직접 적재하고, 적재가 비식별 선두 단계를 자동으로 시작시키지 않으며, 작업자 제출 없이 검수
-- 대기로 들어간다.
--
-- ----------------------------------------------------------------------------
-- * 표준용어 근거 (규칙 3 — 우선순위 (1)행안부 공통표준 -> (2)사업표준 -> (3)신규)
-- ----------------------------------------------------------------------------
--   판정은 docs/ 아래 CSV 정본 전수 대조로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--
--   외부 = OTSD (행안부 공통표준단어)    ⚠ 사업표준의 OBND 가 아니다 — 우선순위상 행안부가 이긴다.
--   카테고리 = CTGRY (사업표준단어 — 행안부 미등록 개념이라 보충)
--   매핑 = MPNG (행안부) · 데이터셋 = DATST (행안부) · 이관 = TRNSF (행안부) · 이력 = HSTRY (행안부)
--   수정자아이디 = MDFR_ID / 도메인 식별자V30 (사업표준용어)
--     ⚠ 이 저장소에는 mdfr_id 와 mdfcn_id 가 혼재하나 <신규 컬럼은 정식 MDFR_ID> 를 쓴다.
--       기존 mdfcn_id 12곳의 정정은 이 변경의 범위가 아니다(별도 백로그).
--   비식별화완료여부 = DE_IDNTF_CMPTN_YN / 공공도메인 여부C1 (사업표준용어 등록분)
--     ⚠ <신규 등록이 필요 없다>. 감사가 "조합어 미등재 -> 공유 사업사전 신규 등록 필요" 로 본
--       항목인데, 정본 대조 결과 이미 등재돼 있었다. 사업 사전은 관제서버·포털이 공유하는 자산이라
--       쓰기를 피할 수 있는 것이 이득이다.
--     ⚠ 그 등재 용어의 설명은 다른 주제(포털 회원탈퇴 이력)로 적혀 있다. 물리명·도메인이 일치하므로
--       사용에는 문제가 없고, 그 사전 행은 타 프로젝트 소유라 우리가 고칠 대상이 아니다.
--
--   컬럼 폭도 표준도메인을 따른다 — 감사가 지적한 두 건을 정본대로 좁혔다.
--     원본폴더경로명 varchar(300)  : 행안부 폴더경로명(FLDR_PATH_NM) 도메인 명V300.
--                                   명 계열에 1000 은 없다(내용V1000 은 "내용" 분류라 다른 축).
--     외부카테고리코드 varchar(20) : 코드 계열 가변길이 최대가 코드V20. 100자 코드 도메인은
--                                   공통·사업 어디에도 없다. 외부 분류 식별 문자열의 실물 최대는 17자다.
--
-- ----------------------------------------------------------------------------
-- * de_idntf_cmptn_yn — 왜 비식별 여부 값(ls_data_raw.de_ident_yn)에 싣지 않는가
-- ----------------------------------------------------------------------------
--   원본이라고 지정해 가져온 영상은 비식별이 끝나기 전까지 <검수 승인만> 막혀야 한다.
--   그런데 비식별 여부 값 가운데 <비식별 누락 신고 상태>는 라벨 조회·프레임 이미지·영상 스트리밍·
--   학습데이터 산출물 생성을 함께 닫는다. 승인만 막으려는 의도를 그 축에 실으면 의도보다 넓게 닫히고,
--   이 경로에는 라벨과 프레임이 <이미 들어와 있어> 그것을 못 보면 검수 자체가 성립하지 않는다.
--   그래서 검수 워크플로 상태 표의 별도 값으로 표현한다(ADR-048).
--
--   ★ 기본값이 'Y' 인 이유 — 이 경로와 무관한 기존 전 행이 이 값 때문에 승인이 막히면 안 된다.
--     이 경로로 <원본이라고 지정해> 들어온 영상만 'N' 으로 시작한다. 그래서 백필이 필요 없다.
--
--   ★ 같은 표의 revlt_yn(재검토여부)과 같은 형태(char(1) NOT NULL DEFAULT)를 따른다.
--
-- ----------------------------------------------------------------------------
-- * 대응 테이블 — 왜 종류 단위이며 유니크가 (종류, 외부코드) 인가
-- ----------------------------------------------------------------------------
--   외부 산출물이 쓰는 분류 이름은 저작도구 라벨 체계와 다르다. 대응은 <종류 단위>이며 개별 항목마다
--   정하지 않는다 — 같은 분류가 나올 때마다 다시 정해야 한다면 규모가 큰 산출물에서는 쓸 수 없다.
--   한 외부 분류는 라벨 또는 이벤트 유형 <하나>에만 대응하므로 (대응종류, 외부카테고리코드) 유일.
--
--   ⚠ 라벨 축과 이벤트유형 축을 <한 테이블에> 둔 것은 의도다. 두 축의 수명·조회 경로·확정 동선이
--     같고(미확정이 남으면 적재를 막는 판정도 하나), 나누면 그 판정이 두 곳으로 갈린다.
--     대응종류코드가 어느 축인지 가르고, 그 축의 참조 컬럼만 채운다(반대쪽은 NULL).
--
-- ----------------------------------------------------------------------------
-- * FK 방향과 삭제 정책
-- ----------------------------------------------------------------------------
--   대응 -> 라벨/이벤트유형 : ON DELETE RESTRICT. 대응이 걸린 마스터를 지우면 이관이 조용히
--     엉뚱한 분류로 저장되기 시작하므로 <삭제를 막는다>.
--   이관 이력 -> 영상 : ON DELETE SET NULL. 이력은 <언제 누가 무엇을 가져왔는가>의 감사 기록이라
--     영상이 지워져도 남아야 한다. CASCADE 로 지우면 중복 반입 거부의 근거가 함께 사라진다.
--
-- ----------------------------------------------------------------------------
-- * 멱등 · 두 경로 수렴
-- ----------------------------------------------------------------------------
--   IF NOT EXISTS 로 두 번 돌아도 안전하다.
--   · 신규 설치(빈 DB) : V1 이 이 객체들 없이 스키마를 만들고 여기서 추가된다(no-op 이 아니다).
--     V1__baseline.sql 은 이미 적용된 이력이라 고치지 않는다(체크섬 불일치 = 전 노드 기동 실패).
--   · 기존 DB          : V13 까지 적용된 상태에서 여기서 추가된다.
--
-- ----------------------------------------------------------------------------
-- * 잠금과 배포 창 (2노드 Active-Active)
-- ----------------------------------------------------------------------------
--   신규 테이블 생성은 기존 트래픽과 경합하지 않는다.
--   ls_raw_data_status 의 ADD COLUMN 은 <상수 DEFAULT>라 PostgreSQL 11+ 에서 카탈로그만 고치므로
--   테이블 재작성이 없고 잠금은 순간이다.
--
--   ★ 이 변경은 <하위호환>이다. 구 jar 노드는 새 컬럼·새 테이블을 모르는 SQL 을 만들 뿐이고
--     (엔티티 매핑에 없으므로 SELECT/INSERT 목록에 나타나지 않는다) 새 jar 노드만 쓰고 읽는다.
--     따라서 롤링 재기동으로 무중단 배포가 가능하다.
--
-- ★ 롤백 절차 — Flyway 는 down-migration 을 하지 않으므로 DBA 가 수동 적용.
--   되돌리면 <이관 이력과 확정된 분류 대응이 함께 사라진다>. 이관된 영상 자체는 ls_data_raw 에
--   남지만 어느 폴더에서 왔는지 알 수 없게 되고, 중복 반입 거부가 성립하지 않는다.
--
--     ALTER TABLE ls_raw_data_status DROP COLUMN de_idntf_cmptn_yn;
--     DROP TABLE ls_otsd_datst_trnsf_hstry;
--     DROP TABLE ls_otsd_ctgry_mpng;
--     DELETE FROM FLYWAY_SCHEMA_HISTORY WHERE VERSION = '14';
--
-- ----------------------------------------------------------------------------
-- * 이번에 손대지 않는 것 (빠뜨린 것이 아니다)
-- ----------------------------------------------------------------------------
--   · ls_data_raw 의 src_type 에 IMPORTED 를 넣는 <제약>은 만들지 않는다. 그 컬럼은 원래
--     체크 제약이 없고 허용값 판정을 애플리케이션이 한다(인입 수신값 fail-closed). DB 제약을
--     여기서만 새로 만들면 판정이 두 곳으로 갈린다.
--   · 데이터마트 뷰(v_completed_*) — 이관 영상도 기존 뷰를 그대로 타므로 스키마 변경이 없다.
--   · 기존 행 백필 — 위 기본값 설명 참조. 백필할 사실이 존재하지 않는다.
--
-- ----------------------------------------------------------------------------
-- * 작성 규칙 (이 저장소에서 실제로 깨진 적이 있는 것들)
-- ----------------------------------------------------------------------------
--   · 달러-중괄호 플레이스홀더 표기를 <주석에도> 쓰지 않는다. Flyway 가 placeholder 로 읽어
--     "No value provided for placeholder" 로 파일 전체가 적용되지 않는다.
--   · 스키마 리터럴(public 등)을 박지 않는다. 대상 스키마는 커넥션의 search_path(기본 klid_at)가
--     정하고, 카탈로그 조회는 current_schema() 로 스코프해 조회 대상과 조작 대상을 같게 만든다.
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1) 외부 분류 대응
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_otsd_ctgry_mpng (
    mpng_sn bigint NOT NULL,
    mpng_knd_cd character varying(20) NOT NULL,
    otsd_ctgry_cd character varying(20) NOT NULL,
    otsd_ctgry_nm character varying(200),
    lbl_id bigint,
    evnt_type_cd character varying(20),
    use_yn character(1) DEFAULT 'Y' NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('ls_otsd_ctgry_mpng')
          AND attname = 'mpng_sn' AND attidentity <> ''
    ) THEN
        ALTER TABLE ls_otsd_ctgry_mpng ALTER COLUMN mpng_sn ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME ls_otsd_ctgry_mpng_mpng_sn_seq
            START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
        );
    END IF;
END $$;

ALTER TABLE ls_otsd_ctgry_mpng DROP CONSTRAINT IF EXISTS ls_otsd_ctgry_mpng_pkey;
ALTER TABLE ls_otsd_ctgry_mpng ADD CONSTRAINT ls_otsd_ctgry_mpng_pkey PRIMARY KEY (mpng_sn);

ALTER TABLE ls_otsd_ctgry_mpng DROP CONSTRAINT IF EXISTS fk_ls_otsd_ctgry_mpng_lbl;
ALTER TABLE ls_otsd_ctgry_mpng ADD CONSTRAINT fk_ls_otsd_ctgry_mpng_lbl
    FOREIGN KEY (lbl_id) REFERENCES ls_label(lbl_id) ON DELETE RESTRICT;

ALTER TABLE ls_otsd_ctgry_mpng DROP CONSTRAINT IF EXISTS fk_ls_otsd_ctgry_mpng_evnt_type;
ALTER TABLE ls_otsd_ctgry_mpng ADD CONSTRAINT fk_ls_otsd_ctgry_mpng_evnt_type
    FOREIGN KEY (evnt_type_cd) REFERENCES ls_evnt_type(evnt_type_cd) ON DELETE RESTRICT;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ls_otsd_ctgry_mpng
    ON ls_otsd_ctgry_mpng USING btree (mpng_knd_cd, otsd_ctgry_cd);

COMMENT ON TABLE ls_otsd_ctgry_mpng IS
    '외부카테고리대응 — 외부 산출물이 쓰는 분류 이름을 저작도구 라벨 체계·이벤트 유형에 잇는다. 대응은 종류 단위이며 개별 항목마다 정하지 않는다. 처음 보는 분류는 이름이 비슷한 후보를 제시하고 사람이 확인해 확정하며, 자동으로 확정하지 않는다 — 짐작으로 연결하면 다른 분류로 저장되고 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다. 대응이 정해지지 않은 분류가 하나라도 남으면 적재하지 않는다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.mpng_sn IS '대응일련번호 — 대응 한 건을 가리킨다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.mpng_knd_cd IS '대응종류코드 — LABEL(라벨) / EVNT_TYPE(이벤트유형). 이 값이 어느 축인지 가르고 그 축의 참조 컬럼만 채운다(반대쪽은 NULL).';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.otsd_ctgry_cd IS '외부카테고리코드 — 외부 산출물이 준 분류 식별 문자열 원문. 저작도구가 정한 코드가 아니라 외부 체계의 값이다. 폭은 표준 코드 도메인을 따르며 넘는 값은 입구에서 거부한다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.otsd_ctgry_nm IS '외부카테고리명 — 외부 산출물이 준 표시 이름. 처음 보는 분류에 후보를 제시할 때 근거로 쓰고, 나중에 어떤 근거로 연결했는지 되짚는 데 쓴다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.lbl_id IS '라벨아이디 — 연결된 저작도구 라벨. 대응종류가 이벤트유형이면 비어 있다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.evnt_type_cd IS '이벤트유형코드 — 연결된 이벤트 유형. 대응종류가 라벨이면 비어 있다.';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.use_yn IS '사용여부 — 해제된 대응을 지우지 않고 표시로 남긴다(과거 이관이 어느 대응으로 적재됐는지 판독 가능하게).';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.reg_id IS '등록자아이디';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.reg_dt IS '등록일시';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.mdfr_id IS '수정자아이디';
COMMENT ON COLUMN ls_otsd_ctgry_mpng.mdfcn_dt IS '수정일시';

-- ----------------------------------------------------------------------------
-- 2) 이관 이력
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS ls_otsd_datst_trnsf_hstry (
    trnsf_sn bigint NOT NULL,
    orgnl_fldr_path_nm character varying(300) NOT NULL,
    orgnl_fldr_nm character varying(300),
    otsd_datst_id character varying(50),
    raw_sn bigint,
    trnsf_stts_cd character varying(20) NOT NULL,
    frme_cnt integer,
    lbl_cnt integer,
    fail_rsn character varying(1000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_attribute
        WHERE attrelid = to_regclass('ls_otsd_datst_trnsf_hstry')
          AND attname = 'trnsf_sn' AND attidentity <> ''
    ) THEN
        ALTER TABLE ls_otsd_datst_trnsf_hstry ALTER COLUMN trnsf_sn ADD GENERATED BY DEFAULT AS IDENTITY (
            SEQUENCE NAME ls_otsd_datst_trnsf_hstry_trnsf_sn_seq
            START WITH 1 INCREMENT BY 1 NO MINVALUE NO MAXVALUE CACHE 1
        );
    END IF;
END $$;

ALTER TABLE ls_otsd_datst_trnsf_hstry DROP CONSTRAINT IF EXISTS ls_otsd_datst_trnsf_hstry_pkey;
ALTER TABLE ls_otsd_datst_trnsf_hstry ADD CONSTRAINT ls_otsd_datst_trnsf_hstry_pkey PRIMARY KEY (trnsf_sn);

ALTER TABLE ls_otsd_datst_trnsf_hstry DROP CONSTRAINT IF EXISTS fk_ls_otsd_datst_trnsf_hstry_raw;
ALTER TABLE ls_otsd_datst_trnsf_hstry ADD CONSTRAINT fk_ls_otsd_datst_trnsf_hstry_raw
    FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS ix_ls_otsd_datst_trnsf_hstry_raw
    ON ls_otsd_datst_trnsf_hstry USING btree (raw_sn);
CREATE INDEX IF NOT EXISTS ix_ls_otsd_datst_trnsf_hstry_fldr
    ON ls_otsd_datst_trnsf_hstry USING btree (orgnl_fldr_nm, otsd_datst_id);

COMMENT ON TABLE ls_otsd_datst_trnsf_hstry IS
    '외부데이터셋이관이력 — 언제 누가 어떤 폴더를 가져와 몇 건이 들어왔는지의 감사 기록. 같은 산출물을 두 번 가져오려 할 때 거부의 근거가 된다. 영상이 지워져도 이력은 남는다(영상 참조는 SET NULL).';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.trnsf_sn IS '이관일련번호 — 이관 한 건을 가리킨다.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.orgnl_fldr_path_nm IS '원본폴더경로명 — 가져온 산출물 폴더의 위치. 허용된 저장소 범위 밖이면 입구에서 거부한다.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.orgnl_fldr_nm IS '원본폴더명 — 폴더 식별자. 외부데이터셋아이디와 함께 같은 산출물인지 판정하는 축이다.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.otsd_datst_id IS '외부데이터셋아이디 — 산출물 문서가 밝힌 데이터셋 식별자.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.raw_sn IS '원시영상일련번호 — 이 이관으로 만들어진 영상. 영상이 지워지면 NULL 이 되고 이력 자체는 남는다.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.trnsf_stts_cd IS '이관상태코드 — PROCESSING(진행중) / SUCCESS(성공) / FAILED(실패).';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.frme_cnt IS '프레임건수 — 실제로 적재된 프레임 수. 문서가 선언한 수가 아니라 실제 파일 기준이다.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.lbl_cnt IS '라벨건수 — 실제로 적재된 라벨 수.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.fail_rsn IS '실패사유 — 이관이 실패한 경우의 사유.';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.reg_id IS '등록자아이디';
COMMENT ON COLUMN ls_otsd_datst_trnsf_hstry.reg_dt IS '등록일시';

-- ----------------------------------------------------------------------------
-- 3) 승인 보류를 표현하는 값
-- ----------------------------------------------------------------------------
ALTER TABLE ls_raw_data_status
    ADD COLUMN IF NOT EXISTS de_idntf_cmptn_yn character(1) DEFAULT 'Y' NOT NULL;

COMMENT ON COLUMN ls_raw_data_status.de_idntf_cmptn_yn IS
    '비식별화완료여부 — 값이 N 인 동안 그 영상의 검수 승인이 거부된다. 기본값이 Y 인 이유는 외부 산출물 이관 경로와 무관한 기존 영상이 이 값 때문에 승인이 막히면 안 되기 때문이며, 그 경로로 원본이라고 지정해 들어온 영상만 N 으로 시작한다. N 에서 Y 로 바뀌는 계기는 두 갈래다 — 가져올 때 영상 파일을 함께 준 원본이면 저작도구가 비식별 단계를 태우고 그 비식별이 성공으로 기록되면 Y 가 되고, 영상 파일이 없어 프레임만 가져온 경우에는 외부에서 비식별한 산출물을 받아 기록하는 별도 행위로 Y 가 된다. 그 기록 행위는 비식별 산출물이 실제로 존재하는지 확인한 뒤에만 성립한다. 이 값은 검수 승인 하나만 막는다 — 라벨 조회·프레임 이미지·영상 스트리밍·학습데이터 산출물 생성을 닫지 않으며, 그 통로들을 함께 닫는 것은 비식별 여부 값 가운데 누락 신고 상태로 별개 축이다.';
