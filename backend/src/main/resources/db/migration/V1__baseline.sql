-- ============================================================================
-- V1__baseline.sql — 저작도구 스키마 단일 베이스라인 (구 V0~V185 180개 파일의 스쿼시본)
--
-- 무엇을 접었나: 2026-08-13 시점까지 누적된 마이그레이션 180개(V0~V185, 결번 9·10·49·150·151·152)를
--   실제 적용 결과 그대로 덤프해 한 파일로 접었다. 누적 CREATE TABLE 102종 중 25종은 뒤에서 DROP 된
--   순수 잔재였고, 신규 설치는 그 25종을 만들었다 지우는 왕복을 매번 반복하고 있었다.
--
-- 왜 지금인가: stg/prd/온프렘 <미배포> 상태라 이력 수술 대상이 로컬·dev 둘뿐이고, 온프렘은 Flyway 를
--   쓰지 않는다(설치 시 deploy/onprem/db/schema.sql 1회 로드). 배포가 늘어난 뒤에는 같은 작업의
--   비용과 위험이 급격히 커진다.
--
-- 동일성 보증: 이 파일은 손으로 쓴 것이 아니라 <180개를 클린 DB 에 전량 적용한 뒤 pg_dump 한 결과>다.
--   따라서 컬럼·제약·인덱스·뷰·COMMENT·시드가 정의상 일치한다. 커밋 시 양쪽 덤프를 기계 비교해
--   "cm_code → ls_com_cd 개명분 외 차이 0" 을 확인했다.
--   ⚠ 그 뒤 <기록된 예외 2건>(아래 「운영 규칙」)으로 미사용 테이블 7종의 정의를 덜어냈으므로,
--     이 파일은 이제 그 시점 덤프와 <그만큼> 다르다. 덜어낸 목록은 예외 항목에 적혀 있다.
--
-- ============================================================================
-- 이 스쿼시로 <사라진 것>과 그 대체 (읽고 넘어갈 것)
-- ============================================================================
--
-- 1) 옛 180개 파일 본문은 삭제되지 않았다 — backend/src/test/resources/db-archive/migration/ 에
--    원문 그대로 보존한다. Flyway 는 이 경로를 보지 않으므로(locations=classpath:db/migration)
--    실행되지 않지만, 각 파일의 배경·판단 근거·롤백 절차 주석과 그것을 읽는 회귀 테스트
--    (V164EvntTypeBackfillIT · V167CctvBackfillIT · V149BackfillParityIT · 아키텍처 제거 가드 등
--     21개 테스트 클래스)가 그대로 살아 있다. git 이력에도 남는다.
--
-- 2) 컬럼·테이블 단위 설계 근거는 이 파일에 그대로 있다 — COMMENT ON 187건이 덤프에 포함됐다.
--
-- 3) 아래 셋은 <앞으로의 마이그레이션 작성에 직접 필요한> 판단 근거라 여기에 옮겨 적는다.
--
-- ----------------------------------------------------------------------------
-- ★ 규칙 1 — DDL 에 스키마명을 박지 않는다 (비한정 + DB_SCHEMA 설정)
-- ----------------------------------------------------------------------------
--   이 파일은 pg_dump 결과에서 klid_at. 한정을 <의도적으로 제거>한 비한정 DDL 이다. 앱은
--   커넥션 currentSchema / Flyway schemas·default-schema / Quartz tablePrefix / hibernate
--   default_schema 네 지점이 모두 같은 DB_SCHEMA 설정(기본 klid_at)을 읽는다. DDL 에 스키마명을 박으면
--   그 설정이 <두 번째 진실원>이 되어 조용히 갈린다.
--   과거 실사고: 일부 마이그레이션이 카탈로그 조회 술어에 'public' 리터럴을 박아 두었다. 그때는
--   스키마 설정이 없어 항상 public 이었으므로 통과했지만, klid_at 전환에서 전부 오판정이 됐다.
--
-- ----------------------------------------------------------------------------
-- ★ 규칙 2 — 카탈로그 조회는 반드시 스코프를 건다 (조건부 마이그레이션의 함정)
-- ----------------------------------------------------------------------------
--   pg_constraint.conname · pg_indexes.indexname 등은 <DB 전역에서 유일하지 않다>. 네임스페이스
--   한정 없이 이름만으로 EXISTS 를 판정하면 다른 스키마의 동명 객체를 보고 "이미 있다"고 오판해
--   필요한 DDL 을 조용히 건너뛴다. 실제로 이 저장소의 V79·V110·V146 이 그 형태였다.
--
--   올바른 형태 두 가지 (구 V162 · V100 이 정답 역할을 했다):
--     · 제약 : WHERE conname = '...' AND conrelid = to_regclass('<테이블명>')
--              — conrelid 는 search_path 로 해석되므로 klid_at 등 비-public 운영에서도 그대로 동작한다.
--     · 인덱스/기타 : WHERE schemaname = current_schema()   (하드코딩 'public' 금지)
--     · 테이블 존재 : to_regclass('<테이블명>') IS NOT NULL  (역시 search_path 기반)
--   아래 V2 도 이 규칙을 따른다.
--
-- ----------------------------------------------------------------------------
-- ★ 규칙 3 — 새 물리명은 표준용어·표준도메인 대조 후에 만든다
-- ----------------------------------------------------------------------------
--   우선순위 ① 행안부 공통표준 → ② 사업표준 → ③ 둘 다 없을 때만 신규 등록.
--   판정은 반드시 docs/ 아래 CSV 정본 grep 으로 한다(검색 API 는 상한 때문에 "미등록" 오판을 낸다).
--   물리명뿐 아니라 <타입·길이>도 표준도메인을 따른다(코드값 = VARCHAR(20) 등).
--   상세는 CLAUDE.md 「표준용어·표준도메인 준수」 절.
--
-- ============================================================================
-- 운영 규칙
-- ============================================================================
--   · 신규 마이그레이션은 <V5 부터> 시작한다. V1(베이스라인)·V2(개명)·V3·V4(사용처 0 테이블 제거)는
--     재사용 금지.
--   · 이 파일은 이미 적용된 이력이므로 <내용을 절대 수정하지 않는다>(체크섬 불일치 = 전 노드 기동 실패).
--     스키마를 바꾸려면 새 버전 파일을 추가한다.
--   · 스쿼시 이전에 만들어진 기존 DB(로컬·dev)는 이력 180행을 베이스라인 1행으로 교체해야 한다.
--     절차: deploy/onprem/docs/09-operations-runbook.md 「Flyway 스쿼시 — 기존 DB 이력 이관」.
--   · 온프렘 설치본 schema.sql 은 이 파일이 바뀔 때마다 deploy/onprem/scripts/gen-schema-sql.sh
--     로 재생성한다.
--
-- ----------------------------------------------------------------------------
-- ★ 위 「절대 수정하지 않는다」의 <기록된 예외 1건> — 2026-08-13 미사용 테이블 3종 제거
-- ----------------------------------------------------------------------------
--   LS_DEADLINE · LS_META · LS_RAW_DATA_ENROLLMENT 의 CREATE TABLE·PK·FK 정의를 이 파일에서
--   덜어냈다(66줄). 셋 다 엔티티 클래스만 있고 리포지토리·쿼리·화면·배치 어디에서도 참조가
--   없었으며 dev 행수도 0 이었다. 신규 설치가 <만들었다 지우는 왕복>을 하지 않도록 정의 자체를
--   지웠고, 이미 만들어진 DB 는 V3__drop_unused_tables.sql 이 DROP 한다.
--
--   왜 이 시점에만 허용되나 — 체크섬 검증을 받는 DB 가 아직 없다:
--     · 신규 설치 = 이 파일을 처음 적용하므로 비교 대상 자체가 없다.
--     · 기존 DB(로컬·dev) = 이력이 <BASELINE 타입 + checksum NULL> 1행이라 Flyway 가 버전 1
--       이하를 통째로 건너뛴다(§2-5-2 가 그렇게 넣는다). 검증 대상이 아니다.
--     · stg/prd/온프렘 = 미배포. 온프렘은 Flyway 를 쓰지 않는다.
--   ⚠ 위 셋 중 어느 것도 아닌 DB, 즉 <스쿼시 이후 이 파일을 SQL 타입으로 실제 적용한 DB> 가
--     생긴 뒤에는 이 예외가 성립하지 않는다. 그때부터는 반드시 새 버전 파일로만 바꾼다.
--
-- ----------------------------------------------------------------------------
-- ★ <기록된 예외 2건> — 2026-08-13 미사용 테이블 4종 제거(2회차)
-- ----------------------------------------------------------------------------
--   LS_COM_CD · LS_DATA_META_HSTRY · LS_DATA_RAW_HSTRY · LS_TASK_ASSIGN_HISTORY 의 정의를
--   덜어냈다(171줄) — CREATE TABLE 4 + IDENTITY 시퀀스 3 + PK 4 + 인덱스 3 + FK 2, 그리고
--   <LS_COM_CD 시드 5행>. 넷 다 프로덕션 read 경로가 0 이고, 제거 근거는
--   V4__drop_unused_tables_round2.sql 헤더에 테이블별로 적어 두었다.
--   이미 만들어진 DB 는 그 V4 가 DROP 한다.
--
--   ★ 예외 1건과 <다른 점>: 이번엔 <데이터가 있는> 테이블을 지운다(LS_COM_CD 시드 5행).
--     그 5행은 LsRawDataStatus 자바 상수와 같은 값을 한 벌 더 들고 있던 <두 번째 진실원>이고
--     아무도 읽지 않으므로, 신규 설치가 애초에 만들지 않는 것이 옳다. 나머지 셋은 0 행이다.
--
--   허용 근거는 예외 1건과 동일하며, dev 이력을 직접 조회해 재확인했다(2026-08-13):
--     flyway_schema_history rank 1 = version 1 / type=BASELINE / checksum=NULL → 검증 대상 아님.
--     반면 rank 2 = version 2 / type=SQL / checksum=-1397119750 이라 <V2 는 여전히 수정 금지>다.
-- ============================================================================

--
-- Name: ls_acnt_user; Type: TABLE
--

CREATE TABLE ls_acnt_user (
    user_no bigint NOT NULL,
    user_id character varying(20),
    user_nm character varying(100) DEFAULT ''::character varying NOT NULL,
    user_eml_addr character varying(320),
    use_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);


--
-- Name: TABLE ls_acnt_user; Type: COMMENT
--

COMMENT ON TABLE ls_acnt_user IS '사용자 마스터(저작도구 소유). 역할 클레임 시점에 관제가 브라우저 localStorage 로 인계한 값(userId·userNm)으로 자동 등록·갱신된다. 인가 역할은 이 테이블이 아니라 LS_USER_ROLE 이 단일 진실원이다.';


--
-- Name: COLUMN ls_acnt_user.user_no; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.user_no IS '사용자번호(PK). ★JWT subject(sub)에서만 취한다 — 요청 바디의 값을 신뢰하지 않는다. LS_USER_ROLE.USER_NO 등과 같은 값이다.';


--
-- Name: COLUMN ls_acnt_user.user_id; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.user_id IS '사용자아이디(표시용). 관제 인계값이며 미수신이면 null 이다(지어내지 않는다). 인증·인가 판정에 쓰지 않는다.';


--
-- Name: COLUMN ls_acnt_user.user_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.user_nm IS '사용자명(표시용). 관제 인계값이며 미수신이면 빈 문자열이다. 배정·검수·통계 화면의 이름 표시 원천이다.';


--
-- Name: COLUMN ls_acnt_user.user_eml_addr; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.user_eml_addr IS '사용자이메일주소. 관제 인계 키에 없으므로 자동등록 사용자는 null 이고, 구 관제 마스터에서 이관된 행만 값을 갖는다.';


--
-- Name: COLUMN ls_acnt_user.use_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.use_yn IS '사용여부(Y/N). 작업자 목록·배정 후보에서 비활성 사용자를 제외하는 필터축이다. ★자동등록은 이 값을 갱신하지 않는다 — 운영자가 비활성화한 사용자가 재클레임으로 되살아나면 안 된다.';


--
-- Name: COLUMN ls_acnt_user.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_acnt_user.mdfcn_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_acnt_user.mdfcn_dt IS '수정일시. 자동등록이 표시 정보를 실제로 갱신했을 때만 변한다(값이 같으면 갱신하지 않는다).';


--
-- Name: ls_auth_work_lock; Type: TABLE
--

CREATE TABLE ls_auth_work_lock (
    work_lock_sn bigint NOT NULL,
    lck_target_cd character varying(20) NOT NULL,
    data_raw_sn bigint,
    data_src_sn bigint,
    lck_stts_cd character varying(20) NOT NULL,
    lck_id character varying(64) NOT NULL,
    lock_owner_id character varying(30),
    lck_dt timestamp without time zone NOT NULL,
    expry_dt timestamp without time zone,
    rmv_dt timestamp without time zone,
    rmv_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_auth_work_lock_work_lock_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_auth_work_lock ALTER COLUMN work_lock_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_auth_work_lock_work_lock_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_authrt_grant_atmpt; Type: TABLE
--

CREATE TABLE ls_authrt_grant_atmpt (
    atmpt_se_cd character varying(20) NOT NULL,
    atmpt_idntfr character varying(36) NOT NULL,
    bgng_dt timestamp without time zone NOT NULL,
    atmpt_nmtm integer DEFAULT 0 NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_authrt_grant_atmpt; Type: COMMENT
--

COMMENT ON TABLE ls_authrt_grant_atmpt IS '권한 자가부여 시도 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_se_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.atmpt_se_cd IS '시도 구분 코드 — ACCOUNT(계정별) / GLOBAL(엔드포인트 전역).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_idntfr; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.atmpt_idntfr IS '시도 식별자 — 계정 축은 요청자 sub, 전역 축은 GLOBAL 고정값.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.bgng_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.bgng_dt IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.atmpt_nmtm; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.atmpt_nmtm IS '해당 윈도우의 누적 자가부여 시도 횟수.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.expd_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.expd_dt IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';


--
-- Name: COLUMN ls_authrt_grant_atmpt.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_authrt_grant_atmpt.mdfcn_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_authrt_grant_atmpt.mdfcn_dt IS '수정일시.';


--
-- Name: ls_bat_rty_wtng; Type: TABLE
--

CREATE TABLE ls_bat_rty_wtng (
    bat_rty_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    rty_nmtm integer DEFAULT 0 NOT NULL,
    max_rty_nmtm integer DEFAULT 3 NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    rty_prnmnt_dt timestamp without time zone,
    last_err_msg_cn character varying(2000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_bat_rty_wtng_bat_rty_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_bat_rty_wtng ALTER COLUMN bat_rty_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_bat_rty_wtng_bat_rty_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_batch_proc_log; Type: TABLE
--

CREATE TABLE ls_batch_proc_log (
    batch_proc_log_sn bigint NOT NULL,
    job_id character varying(64) NOT NULL,
    data_raw_sn bigint,
    data_src_sn bigint,
    proc_step_cd character varying(30) NOT NULL,
    proc_stts_cd character varying(20) NOT NULL,
    bgng_dt timestamp without time zone,
    end_dt timestamp without time zone,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    err_cd character varying(50),
    err_msg_cn character varying(4000),
    req_payload_cn text,
    resp_payload_cn text,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_batch_proc_log_batch_proc_log_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_batch_proc_log ALTER COLUMN batch_proc_log_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_batch_proc_log_batch_proc_log_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_clip_schedule_que; Type: TABLE
--

CREATE TABLE ls_clip_schedule_que (
    que_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    job_type character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    registered_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    started_at timestamp without time zone,
    completed_at timestamp without time zone,
    last_error character varying(2000)
);


--
-- Name: ls_clip_schedule_que_que_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_clip_schedule_que ALTER COLUMN que_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_clip_schedule_que_que_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_control_notify_fallback; Type: TABLE
--

CREATE TABLE ls_control_notify_fallback (
    queue_sn bigint NOT NULL,
    idmp_key character varying(128) NOT NULL,
    evnt_type_cd character varying(20) NOT NULL,
    raw_sn bigint NOT NULL,
    payload_cn text NOT NULL,
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    max_rtry_nmtm integer DEFAULT 5 NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    last_err_msg_cn character varying(2000),
    next_rtry_dt timestamp without time zone,
    dlq_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    send_rslt_cd character varying(16)
);


--
-- Name: COLUMN ls_control_notify_fallback.send_rslt_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_control_notify_fallback.send_rslt_cd IS '발송 결과 코드 - SUCCESS/FAILED (STTS_CD=큐 처리상태와 분리)';


--
-- Name: ls_control_notify_fallback_queue_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_control_notify_fallback ALTER COLUMN queue_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_control_notify_fallback_queue_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug; Type: TABLE
--

CREATE TABLE ls_data_aug (
    data_aug_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    aug_type_cd character varying(20) NOT NULL,
    aug_proc_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    lbl_intgrt_pct numeric(5,2),
    dcsn_user_no character varying(50),
    dcsn_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reg_user_no character varying(50),
    idmp_key character varying(128),
    otsd_job_id character varying(200),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    dead_letter_at timestamp without time zone,
    prompt_cn character varying(4000),
    new_raw_sn bigint
);


--
-- Name: COLUMN ls_data_aug.aug_proc_stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug.aug_proc_stts_cd IS '증강 처리/검수 상태 코드. PENDING(요청·처리중) / ACCEPTED(검수 승인 또는 해상도 파생 생성 완료) / REJECTED(검수 반려 또는 처리 실패 롤업) / CANCELED(사용자 취소 종결, V154 신설). 외부 처리 축(LS_DATA_AUG_JOB.JOB_STTS_CD = RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED)과는 다른 코드 공간이므로 두 축을 섞어 판정하지 말 것. 처리 실패 판정은 이 컬럼이 아니라 DEAD_LETTER_AT(처리 실패 전용 마커)로 한다.';


--
-- Name: COLUMN ls_data_aug.prompt_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug.prompt_cn IS '외부 증강 위탁 시 전송한 prompt(요청 조건 5필드: time/season/weather/terrain/severity) JSON 원문. V153 이전 요청 및 해상도 파생(RESL_*)은 NULL.';


--
-- Name: COLUMN ls_data_aug.new_raw_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug.new_raw_sn IS '신규원시일련번호 - 이 증강/해상도 요청이 생성한 파생 영상(LS_DATA_RAW.RAW_SN). 생성 전/실패 시 NULL. V155 이전 행은 백필하지 않아 NULL(등재 게이트 그랜드퍼더링 대상). FK 미설정 - V146 계보 링크 제외 규칙 준수';


--
-- Name: ls_data_aug_data_aug_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug ALTER COLUMN data_aug_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_data_aug_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_dscd; Type: TABLE
--

CREATE TABLE ls_data_aug_dscd (
    data_aug_dscd_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    new_raw_sn bigint NOT NULL,
    orgnl_raw_sn bigint,
    dscd_dt timestamp without time zone NOT NULL,
    dscd_rsn character varying(4000),
    rstr_dt timestamp without time zone,
    rstr_rsn character varying(4000),
    del_prcs_dt timestamp without time zone,
    del_dt timestamp without time zone,
    file_del_dt timestamp without time zone,
    vdo_file_path character varying(1000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    aug_type_cd character varying(20),
    prompt_cn character varying(4000),
    file_del_rtry_nmtm integer DEFAULT 0 NOT NULL,
    file_del_fail_dt timestamp without time zone,
    file_del_fail_rsn character varying(4000)
);


--
-- Name: TABLE ls_data_aug_dscd; Type: COMMENT
--

COMMENT ON TABLE ls_data_aug_dscd IS '증강 파생영상 폐기 원장 - 반려 시 표식, 유예 경과 후 실삭제, 유예 내 복구. 실삭제 후에도 비석으로 존속(감사 추적 + 파일 삭제 재시도 단서)';


--
-- Name: COLUMN ls_data_aug_dscd.data_aug_dscd_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.data_aug_dscd_sn IS '데이터증강폐기일련번호 - PK';


--
-- Name: COLUMN ls_data_aug_dscd.data_aug_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.data_aug_sn IS '데이터증강일련번호 - 폐기 대상 증강 요청(LS_DATA_AUG). FK 미설정(실삭제가 비석을 지우지 않게)';


--
-- Name: COLUMN ls_data_aug_dscd.new_raw_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.new_raw_sn IS '신규원시일련번호 - 폐기 대상 파생 영상(LS_DATA_RAW). NOT NULL - 매핑 없는 그랜드퍼더링 증강은 실삭제 대상이 아니라 애초에 행을 만들지 않는다';


--
-- Name: COLUMN ls_data_aug_dscd.orgnl_raw_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.orgnl_raw_sn IS '원본원시일련번호 - 파생의 부모 영상. 감사(원본 오삭제 사후 검출) + 파생 비디오 경로 재구성 단서';


--
-- Name: COLUMN ls_data_aug_dscd.dscd_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.dscd_dt IS '폐기일시 - 소프트 삭제 표식 시각(= 유예 기산점)';


--
-- Name: COLUMN ls_data_aug_dscd.dscd_rsn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.dscd_rsn IS '폐기사유 - 검수 반려 사유 스냅샷(복구 시 검수행에서 지워지므로 여기 보존)';


--
-- Name: COLUMN ls_data_aug_dscd.rstr_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.rstr_dt IS '복구일시 - 값이 있으면 폐기 취소됨(실삭제 대상 아님)';


--
-- Name: COLUMN ls_data_aug_dscd.rstr_rsn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.rstr_rsn IS '복구사유 - 되돌린 이유(누가=MDFCN_ID, 언제=RSTR_DT)';


--
-- Name: COLUMN ls_data_aug_dscd.del_prcs_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.del_prcs_dt IS '삭제처리일시 - 실삭제 배치의 원자 클레임 마커(2노드 중복 집행 방지). 오래된 클레임은 재클레임 허용';


--
-- Name: COLUMN ls_data_aug_dscd.del_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.del_dt IS '삭제일시 - DB 행 실삭제 커밋 시각';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.file_del_dt IS '파일삭제일시 - 생성 파일까지 정리 완료 시각. DEL_DT 가 있고 이 값이 NULL 이면 파일 삭제 재시도 대상';


--
-- Name: COLUMN ls_data_aug_dscd.vdo_file_path; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.vdo_file_path IS '영상파일경로 - 삭제 직전 기록한 파생 비디오 경로(RAW 행이 사라진 뒤 재시도용 단서)';


--
-- Name: COLUMN ls_data_aug_dscd.aug_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.aug_type_cd IS '증강유형코드 - 폐기 당시 증강 종류 스냅샷(LS_DATA_AUG 행은 실삭제로 사라진다)';


--
-- Name: COLUMN ls_data_aug_dscd.prompt_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.prompt_cn IS '프롬프트내용 - 폐기 당시 생성 조건 스냅샷. 중복 증강 허용 이후 같은 (영상×종류) 파생을 구분하는 유일한 축';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_rtry_nmtm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.file_del_rtry_nmtm IS '파일삭제재시도횟수 - 파일 정리 시도 누적. 상한 초과 시 FILE_DEL_FAIL_DT 로 종결';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_fail_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.file_del_fail_dt IS '파일삭제실패일시 - 자동 정리 포기(데드레터) 시각. 값이 있으면 재시도 큐에서 제외되고 사람이 수동 정리';


--
-- Name: COLUMN ls_data_aug_dscd.file_del_fail_rsn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_dscd.file_del_fail_rsn IS '파일삭제실패사유 - 포기 사유(경로 원문 미포함)';


--
-- Name: ls_data_aug_dscd_data_aug_dscd_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug_dscd ALTER COLUMN data_aug_dscd_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_dscd_data_aug_dscd_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_job; Type: TABLE
--

CREATE TABLE ls_data_aug_job (
    aug_job_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    job_seq integer NOT NULL,
    idmp_key character varying(128) NOT NULL,
    otsd_job_id character varying(200),
    job_stts_cd character varying(20) NOT NULL,
    tot_nocs integer DEFAULT 0 NOT NULL,
    err_cd character varying(50),
    err_msg_cn character varying(1000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_data_aug_job; Type: COMMENT
--

COMMENT ON TABLE ls_data_aug_job IS '증강 외부 위탁 작업 — LS_DATA_AUG 1행이 input_files 100 상한으로 분할 위탁된 job 단위.';


--
-- Name: COLUMN ls_data_aug_job.data_aug_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.data_aug_sn IS '증강 결과 식별자(LS_DATA_AUG FK).';


--
-- Name: COLUMN ls_data_aug_job.job_seq; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.job_seq IS '분할 위탁 순서(1부터). 프레임 250장이면 1,2,3.';


--
-- Name: COLUMN ls_data_aug_job.idmp_key; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.idmp_key IS '저작도구가 발급한 request_id(=Idempotency-Key). 외부 job_id 가 아니다.';


--
-- Name: COLUMN ls_data_aug_job.otsd_job_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.otsd_job_id IS '외부 시스템이 202 응답으로 발급한 job_id. 접수 전/실패 시 NULL.';


--
-- Name: COLUMN ls_data_aug_job.job_stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.job_stts_cd IS '외부 작업 상태(RECEIVED/RUNNING/SUCCEEDED/FAILED/CANCELED). 검수 결과 축과 별개.';


--
-- Name: COLUMN ls_data_aug_job.tot_nocs; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.tot_nocs IS '이 job 으로 위탁한 입력 파일 총건수(최대 100).';


--
-- Name: COLUMN ls_data_aug_job.err_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.err_cd IS '실패 오류 코드. 위탁 실패를 조용히 삼키지 않기 위한 사유 기록.';


--
-- Name: COLUMN ls_data_aug_job.err_msg_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job.err_msg_cn IS '실패 오류 메시지(요약). 절대경로/시크릿/스택트레이스 미포함.';


--
-- Name: ls_data_aug_job_aug_job_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug_job ALTER COLUMN aug_job_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_job_aug_job_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_job_file; Type: TABLE
--

CREATE TABLE ls_data_aug_job_file (
    aug_job_file_sn bigint NOT NULL,
    aug_job_sn bigint NOT NULL,
    file_seq integer NOT NULL,
    src_sn bigint NOT NULL,
    rslt_file_path_nm character varying(500),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_data_aug_job_file; Type: COMMENT
--

COMMENT ON TABLE ls_data_aug_job_file IS '증강 위탁 파일 매핑 — 위탁 순서↔프레임(SRC_SN) 대응과 외부 산출 경로.';


--
-- Name: COLUMN ls_data_aug_job_file.aug_job_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job_file.aug_job_sn IS '위탁 작업 식별자(LS_DATA_AUG_JOB FK).';


--
-- Name: COLUMN ls_data_aug_job_file.file_seq; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job_file.file_seq IS '위탁 입력 순서(input_files[].sequence). 증강 1건 전체에서 1부터 증가.';


--
-- Name: COLUMN ls_data_aug_job_file.src_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job_file.src_sn IS '위탁한 비식별 프레임(LS_DATA_SRC) 식별자. 결과를 되붙일 대상.';


--
-- Name: COLUMN ls_data_aug_job_file.rslt_file_path_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_aug_job_file.rslt_file_path_nm IS '외부가 반환한 증강 산출 이미지 경로. 수신 전 NULL, 건수 불일치 시 미적재.';


--
-- Name: ls_data_aug_job_file_aug_job_file_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug_job_file ALTER COLUMN aug_job_file_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_job_file_aug_job_file_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_lbl_map; Type: TABLE
--

CREATE TABLE ls_data_aug_lbl_map (
    data_aug_lbl_map_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    orgnl_data_lbl_sn bigint,
    data_lbl_sn bigint NOT NULL,
    coord_recalc_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    scale_x numeric(10,6),
    scale_y numeric(10,6),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug_lbl_map ALTER COLUMN data_aug_lbl_map_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_aug_rvw; Type: TABLE
--

CREATE TABLE ls_data_aug_rvw (
    data_aug_rvw_sn bigint NOT NULL,
    data_aug_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint NOT NULL,
    rvw_stts_cd character varying(20) NOT NULL,
    lbl_intgrt_pct numeric(5,2),
    rjct_rsn character varying(4000),
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_aug_rvw_data_aug_rvw_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_aug_rvw ALTER COLUMN data_aug_rvw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_aug_rvw_data_aug_rvw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_ingest; Type: TABLE
--

CREATE TABLE ls_data_ingest (
    rcptn_sn bigint NOT NULL,
    rcptn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    prcs_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    raw_sn bigint,
    rty_cnt integer DEFAULT 0 NOT NULL,
    prcs_dt timestamp without time zone,
    nxtm_rtry_dt timestamp without time zone,
    err_msg character varying(4000),
    vms_clip_id character varying(128) NOT NULL,
    vms_cctv_id character varying(64),
    vdo_file_nm character varying(300) NOT NULL,
    raw_file_path_nm character varying(500) NOT NULL,
    src_type character varying(20) NOT NULL,
    sht_dt timestamp without time zone,
    file_fmt character varying(20),
    vdo_cdc character varying(20),
    file_sz bigint,
    lclgv_nm character varying(100),
    vdo_len_sec numeric(10,0),
    fps character varying(10),
    frme_cnt numeric(10,0),
    asprt_rt character varying(20),
    wdth numeric(10,0),
    vrtc numeric(10,0),
    resl character varying(20),
    "bit" character varying(20),
    pxl character varying(20),
    wgs84_lat numeric(10,7),
    wgs84_lot numeric(10,7),
    cctv_nm character varying(300),
    cctv_hgt numeric(4,1),
    main_surv_pan_ang integer,
    evnt_id character varying(50),
    evnt_nm character varying(200),
    mntr_cn character varying(4000),
    lclgv_cd character varying(20),
    evnt_type_cd character varying(20),
    anony_incl_yn character(1) DEFAULT 'N'::bpchar,
    psdo_incl_yn character(1) DEFAULT 'N'::bpchar,
    prvc_incl_yn character(1) DEFAULT 'Y'::bpchar,
    evnt_clsf_cd character(2),
    evnt_ctgry_cd character(4),
    vrfc_evnt_type_cd character varying(20)
);


--
-- Name: TABLE ls_data_ingest; Type: COMMENT
--

COMMENT ON TABLE ls_data_ingest IS '관제 인입 — 관제서버가 학습용 영상 메타를 직접 INSERT 하는 수신 창구. 행은 감사 추적을 위해 영구 보존한다.';


--
-- Name: COLUMN ls_data_ingest.rcptn_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.rcptn_sn IS '수신일련번호(PK). 관제가 미지정 시 IDENTITY 발급.';


--
-- Name: COLUMN ls_data_ingest.rcptn_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.rcptn_dt IS '수신일시. 폴링 순서(FIFO) 기준.';


--
-- Name: COLUMN ls_data_ingest.prcs_stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.prcs_stts_cd IS '저작도구 처리상태코드. PENDING(미처리) 기본. 관제는 이 컬럼을 채우지 않아도 된다. (V172 개명 — 구 PROC_STTS_CD. 처리=PRCS 표준단어, 같은 테이블 PRCS_DT 와 약어 통일)';


--
-- Name: COLUMN ls_data_ingest.raw_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.raw_sn IS '적재 결과 영상 식별자(LS_DATA_RAW.RAW_SN). 적재 전 NULL. FK 는 걸지 않는다 — 영상 정리 후에도 수신 기록은 남아야 한다.';


--
-- Name: COLUMN ls_data_ingest.rty_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.rty_cnt IS '적재 재시도 횟수. 파일 미도착은 실패가 아니라 미처리로 두고 다음 주기에 재시도한다.';


--
-- Name: COLUMN ls_data_ingest.prcs_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.prcs_dt IS '처리일시 — 저작도구가 이 행을 처리한 시각. 종결(적재 완료/실패) 시각이자, 미처리 상태에서는 <최초 파일 미도착 관측 시각>(대기 예산 앵커)이다. 대기 상한은 관제가 준 RCPTN_DT 가 아니라 이 값 기준으로 잰다(설계 §6-0-1-a ㉠ — RCPTN_DT 는 INSERT 주체가 관제라 과거 시각이 들어오면 도착 즉시 종결된다). 재큐 시 NULL 로 비워 예산을 리셋한다.';


--
-- Name: COLUMN ls_data_ingest.nxtm_rtry_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.nxtm_rtry_dt IS '차기 재시도 예정 일시. 폴링 후보는 PENDING AND (이 값 NULL OR <= 현재)다 — 파일 미도착 관측 시 이 값을 뒤로 밀어(backoff) 고착 행이 FIFO 앞자리를 잠식하지 못하게 한다(설계 §6-0-1-a ㉢). 재큐 시 NULL 로 비운다. (V175 개명 — 구 NXTM_RTY_DT. 표준 우선순위 ①행안부→②사업 재판정으로 재시도=RTRY(공통표준단어) 채택. 같은 테이블 RTY_CNT 는 기존 자산이라 이번 범위 밖 — 별도 라운드)';


--
-- Name: COLUMN ls_data_ingest.err_msg; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.err_msg IS '적재 실패 사유(요약). 절대경로/시크릿/스택트레이스 미포함.';


--
-- Name: COLUMN ls_data_ingest.vms_clip_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vms_clip_id IS 'VMS 클립 아이디(관제 video.id). 중복 INSERT 방어 UK.';


--
-- Name: COLUMN ls_data_ingest.vms_cctv_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vms_cctv_id IS 'VMS CCTV 아이디(관제 video.cctv_mng_no). NULL 허용 — CCTV 식별자가 없는 영상(수동 업로드 등)이 존재한다(2026-08-12 관제 확정). 그런 영상은 관제가 CCTV_NM 에 대체 표기를 채워 보낸다.';


--
-- Name: COLUMN ls_data_ingest.vdo_file_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vdo_file_nm IS '동영상 파일명(관제 video.filename).';


--
-- Name: COLUMN ls_data_ingest.raw_file_path_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.raw_file_path_nm IS '원시 파일 경로명 — 관제 NAS 실경로(strg_uri).';


--
-- Name: COLUMN ls_data_ingest.src_type; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.src_type IS '출처유형(RELAY/USER_ULD/GENERATED/ORIGINAL). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. AUGMENTED 는 저작도구 파생이라 인입으로 오지 않는다.';


--
-- Name: COLUMN ls_data_ingest.sht_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.sht_dt IS '촬영일시(관제 video.date_created).';


--
-- Name: COLUMN ls_data_ingest.file_fmt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.file_fmt IS '파일형식(관제 video.type).';


--
-- Name: COLUMN ls_data_ingest.vdo_cdc; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vdo_cdc IS '영상코덱(관제 video.format).';


--
-- Name: COLUMN ls_data_ingest.file_sz; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.file_sz IS '파일크기 — 관제 수신 바이트 수(관제 v2_video_assets.file_sz, bigint). 어노테이션 작성예시의 ''4800KB'' 표기는 export 직렬화 단계에서 생성한다(인입은 원본 단위 그대로 받는다).';


--
-- Name: COLUMN ls_data_ingest.lclgv_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.lclgv_nm IS '지방자치단체명(관제 video.location). 동은 포함하지 않는다. 지방자치단체코드(LCLGV_CD)와 서로 다른 값이며 서로 대체·통합하지 않는다. (V172 개명 — 구 RGN_NM. 표준용어 LCLGV_NM·도메인 명V100, 관제 datasets.lclgv_nm 과 일치)';


--
-- Name: COLUMN ls_data_ingest.vdo_len_sec; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vdo_len_sec IS '영상길이(초). 관제 원본은 소수 2자리이나 등록 도메인 수N10 에 맞춰 정수 초로 수신한다. 밀리초 정밀도는 LS_DATA_RAW.VDO_LEN_MS 담당.';


--
-- Name: COLUMN ls_data_ingest.fps; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.fps IS '프레임재생속도(관제 video.fps).';


--
-- Name: COLUMN ls_data_ingest.frme_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.frme_cnt IS '프레임수(관제 video.frames). (V172 개명 — 구 FRM_CNT. FRM 은 표준에서 형식(Form)이라 프레임=FRME 로 정정)';


--
-- Name: COLUMN ls_data_ingest.asprt_rt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.asprt_rt IS '종횡비(관제 video.aspect_ratio). 예 4:3.';


--
-- Name: COLUMN ls_data_ingest.wdth; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.wdth IS '영상 너비(px, 관제 video.width).';


--
-- Name: COLUMN ls_data_ingest.vrtc; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vrtc IS '영상 세로(px, 관제 video.height).';


--
-- Name: COLUMN ls_data_ingest.resl; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.resl IS '해상도 표기(관제 video.resolution). 어노테이션 resolution/pixel 두 등급 표기의 파생 원천.';


--
-- Name: COLUMN ls_data_ingest."bit"; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest."bit" IS '비트값 = 색심도 표기(관제 video.bit). 예 24bit. 비트레이트가 아니다. PostgreSQL 예약 여부 — 컬럼명으로는 무인용 사용 가능(인용 금지).';


--
-- Name: COLUMN ls_data_ingest.pxl; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.pxl IS '화소 표기(관제 video.pixel). 예 4K.';


--
-- Name: COLUMN ls_data_ingest.wgs84_lat; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.wgs84_lat IS 'WGS84 위도(관제 video.coordinates 분해).';


--
-- Name: COLUMN ls_data_ingest.wgs84_lot; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.wgs84_lot IS 'WGS84 경도(관제 video.coordinates 분해).';


--
-- Name: COLUMN ls_data_ingest.cctv_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.cctv_nm IS 'CCTV명(관제 resource_cctvs 조인). 촬영 시점 값 고정.';


--
-- Name: COLUMN ls_data_ingest.cctv_hgt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.cctv_hgt IS 'CCTV 설치 높이(m). 촬영 시점 값 고정 — 카메라 교체 시 과거 영상 오염 방지.';


--
-- Name: COLUMN ls_data_ingest.main_surv_pan_ang; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.main_surv_pan_ang IS '주감시방향값(도, 관제 cctv_azimuth). 촬영 시점 값 고정 — 방위각 재설정 시 과거 영상 오염 방지.';


--
-- Name: COLUMN ls_data_ingest.evnt_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.evnt_id IS '이벤트 아이디(관제 video.event_id). 예 ABA_0001. 이벤트유형코드가 아니다.';


--
-- Name: COLUMN ls_data_ingest.evnt_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.evnt_nm IS '이벤트명(관제 video.event_name).';


--
-- Name: COLUMN ls_data_ingest.mntr_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.mntr_cn IS '관제일지 내용(관제 video.event_log).';


--
-- Name: COLUMN ls_data_ingest.lclgv_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.lclgv_cd IS '지방자치단체코드. LS_DATA_RAW.LCLGV_CD 의 원천이며 관제 완료통지 페이로드 lclgv_cd(required)가 이 값에서 나온다. 지방자치단체명(LCLGV_NM)과 서로 다른 값이다.';


--
-- Name: COLUMN ls_data_ingest.evnt_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.evnt_type_cd IS '이벤트유형코드(관제 수신). LS_DATA_RAW.EVNT_TYPE_CD 의 원천이며 마킹 진입 프리컨디션 값이다. EVNT_ID(식별자, 예 ABA_0001)와 서로 다른 값이다 — 대체·통합하지 않는다. ★이 컬럼이 유일한 조달처다: 관제가 채우지 않으면 null 로 적재되고 그 영상은 마킹 단계에서 차단된다(대용값을 넣지 않는다).';


--
-- Name: COLUMN ls_data_ingest.anony_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.anony_incl_yn IS '원천 영상(비식별 처리 전)의 익명정보 포함여부(관제 수신, Y/N). 관제가 항상 송신하며 미지정 INSERT 는 DB DEFAULT ''N''(fail-closed)으로 채워진다(V170 — V166 의 "DEFAULT 없음" 서술 폐기). 저작도구가 사람 입력으로 관리하는 비식별 축(LS_DATA_RAW.ANONY_INCL_YN, V163)과 대상이 다른 별개 축이며 적재는 그 컬럼을 채우지 않는다. 파생영상은 원천 영상이 없으므로 이 값을 물려받지 않는다.';


--
-- Name: COLUMN ls_data_ingest.psdo_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.psdo_incl_yn IS '원천 영상의 가명정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''N''(V170). 비식별 축(LS_DATA_RAW.PSDO_INCL_YN, V163)과 별개.';


--
-- Name: COLUMN ls_data_ingest.prvc_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.prvc_incl_yn IS '원천 영상의 개인정보 포함여부(관제 수신, Y/N). 미지정 INSERT 는 DB DEFAULT ''Y''(fail-closed — 원천은 비식별 전이라 개인정보가 남아 있는 것이 기본 상태다. V170). 비식별 축(LS_DATA_RAW.PRVC_INCL_YN, V163)과 별개.';


--
-- Name: COLUMN ls_data_ingest.evnt_clsf_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.evnt_clsf_cd IS '이벤트분류코드(대분류, 관제 수신). LS_EVNT_TYPE.EVNT_CLSF_CD 의 원천이며 이벤트유형 제외 필터의 판정축이다. 코드에서 유도하지 않는다 — 관제가 안 보내면 NULL 이다. (V172 표준도메인 코드C2 정합)';


--
-- Name: COLUMN ls_data_ingest.evnt_ctgry_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.evnt_ctgry_cd IS '이벤트카테고리코드(관제 수신). 이벤트 3계층(대분류→카테고리→유형)의 중간 레벨이며 LS_EVNT_TYPE.EVNT_CTGRY_CD 의 원천이다. 관제가 안 보내면 NULL 이며 유도하지 않는다. (V172 표준도메인 코드C4 정합)';


--
-- Name: COLUMN ls_data_ingest.vrfc_evnt_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_ingest.vrfc_evnt_type_cd IS '검증이벤트유형코드(관제 수신). 외부 VLM 검증 API 요청의 event_type 조달처이며 허용값은 fire/fall/violence/flooding/car_accident/kidnapping 6종이다. 이벤트유형코드(EVNT_TYPE_CD, 예 EV01000101)와 서로 다른 값이다 — 대체·통합하거나 한쪽에서 유도하지 않는다. 관제 미송신 시 null 이며 서버가 보정하지 않는다(수신 원장). 허용값 검증은 DB CHECK 가 아니라 저작도구 쓰기 통로(400)와 소비 시점 fail-closed 재검증이 담당한다 — 관제 직접 INSERT 를 막지 않기 위함이다.';


--
-- Name: ls_data_ingest_rcptn_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_ingest ALTER COLUMN rcptn_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_ingest_rcptn_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_issue; Type: TABLE
--

CREATE TABLE ls_data_issue (
    data_issue_sn bigint NOT NULL,
    up_data_issue_sn bigint,
    data_raw_sn bigint NOT NULL,
    issue_rsn character varying(1000),
    reported_user_no character varying(50),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    issue_type_cd character varying(20) DEFAULT 'REJECTION'::character varying NOT NULL,
    issue_stts_cd character varying(20) DEFAULT 'OPEN'::character varying NOT NULL,
    src_sn bigint,
    ver bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_ls_data_issue_stts CHECK ((issue_stts_cd IN ('OPEN', 'ANSWERED', 'RESOLVED'))),
    CONSTRAINT ck_ls_data_issue_type CHECK ((issue_type_cd IN ('REJECTION', 'INQUIRY')))
);


--
-- Name: ls_data_issue_data_issue_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_issue ALTER COLUMN data_issue_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_issue_data_issue_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl; Type: TABLE
--

CREATE TABLE ls_data_lbl (
    lbl_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80) NOT NULL,
    point_cn text,
    trck_id character varying(30),
    reg_user_no bigint,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    lbl_id bigint
);


--
-- Name: ls_data_lbl_ai_info; Type: TABLE
--

CREATE TABLE ls_data_lbl_ai_info (
    data_lbl_ai_info_sn bigint NOT NULL,
    data_lbl_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint NOT NULL,
    lbl_src_cd character varying(20) NOT NULL,
    mdl_nm character varying(100),
    mdl_ver character varying(50),
    conf_score numeric(6,5),
    auto_lbl_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_lbl_ai_info_data_lbl_ai_info_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_lbl_ai_info ALTER COLUMN data_lbl_ai_info_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_lbl_ai_info_data_lbl_ai_info_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl_attr_val; Type: TABLE
--

CREATE TABLE ls_data_lbl_attr_val (
    atrb_vl_id bigint NOT NULL,
    lbl_sn bigint NOT NULL,
    atrb_id bigint NOT NULL,
    atrb_vl character varying(1000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_lbl_attr_val_attr_val_id_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_lbl_attr_val ALTER COLUMN atrb_vl_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_lbl_attr_val_attr_val_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl_hstry; Type: TABLE
--

CREATE TABLE ls_data_lbl_hstry (
    lbl_hstry_sn bigint NOT NULL,
    src_sn bigint NOT NULL,
    reg_dt timestamp without time zone NOT NULL,
    reg_id character varying(30),
    add_cnt integer DEFAULT 0 NOT NULL,
    mdfcn_cnt integer DEFAULT 0 NOT NULL,
    del_cnt integer DEFAULT 0 NOT NULL,
    chg_dtl_cn text
);


--
-- Name: TABLE ls_data_lbl_hstry; Type: COMMENT
--

COMMENT ON TABLE ls_data_lbl_hstry IS '라벨 저장 이벤트 이력 — 프레임 단위 1행(종류별 건수 + diff 페이로드)';


--
-- Name: COLUMN ls_data_lbl_hstry.src_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.src_sn IS '저장 이벤트가 발생한 프레임 LS_DATA_SRC.SRC_SN';


--
-- Name: COLUMN ls_data_lbl_hstry.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.reg_dt IS '저장 이벤트 기록 일시';


--
-- Name: COLUMN ls_data_lbl_hstry.reg_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.reg_id IS '작업자 식별자(감사) — 토큰/PII 미저장, 신고 경로는 NULL';


--
-- Name: COLUMN ls_data_lbl_hstry.add_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.add_cnt IS '이 저장 이벤트의 추가(ADDED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.mdfcn_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.mdfcn_cnt IS '이 저장 이벤트의 수정(UPDATED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.del_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.del_cnt IS '이 저장 이벤트의 삭제(DELETED) 라벨 건수';


--
-- Name: COLUMN ls_data_lbl_hstry.chg_dtl_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_lbl_hstry.chg_dtl_cn IS '변경 상세 diff — List<LabelChange> JSON 직렬화(TEXT)';


--
-- Name: ls_data_lbl_hstry_lbl_hstry_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_lbl_hstry ALTER COLUMN lbl_hstry_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_lbl_hstry_lbl_hstry_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_lbl_lbl_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_lbl ALTER COLUMN lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_lbl_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_meta; Type: TABLE
--

CREATE TABLE ls_data_meta (
    meta_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    meta_key character varying(64) NOT NULL,
    meta_vl character varying(2000),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    idmp_key character varying(128),
    otsd_job_id character varying(200),
    rtry_nmtm integer DEFAULT 0 NOT NULL,
    dead_letter_at timestamp without time zone
);


--
-- Name: ls_data_meta_meta_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_meta ALTER COLUMN meta_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_meta_meta_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_meta_review; Type: TABLE
--

CREATE TABLE ls_data_meta_review (
    data_meta_review_sn bigint NOT NULL,
    data_meta_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint,
    meta_type_cd character varying(20) NOT NULL,
    src_sys_cd character varying(20),
    rvw_stts_cd character varying(20) NOT NULL,
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    rjct_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_data_meta_review_data_meta_review_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_meta_review ALTER COLUMN data_meta_review_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_meta_review_data_meta_review_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_raw; Type: TABLE
--

CREATE TABLE ls_data_raw (
    raw_sn bigint NOT NULL,
    vms_clip_id character varying(128) NOT NULL,
    vms_cctv_id character varying(64),
    evnt_type_cd character varying(20),
    lclgv_cd character varying(20),
    prvc_type_cd character varying(16) NOT NULL,
    prvc_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    de_ident_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    raw_file_path_nm character varying(500) NOT NULL,
    sht_dt timestamp without time zone,
    vdo_len_sec integer,
    data_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone,
    orgnl_raw_sn bigint,
    vdo_len_ms bigint,
    wthr_nm character varying(20),
    day_ngt_cd character varying(20),
    sesn_cd character varying(20),
    src_type character varying(20),
    aug_type_cd character varying(20),
    anony_incl_yn character(1),
    psdo_incl_yn character(1),
    prvc_incl_yn character(1)
);


--
-- Name: COLUMN ls_data_raw.vms_cctv_id; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.vms_cctv_id IS 'VMS CCTV 아이디 — 인입(LS_DATA_INGEST.VMS_CCTV_ID) 복사값. NULL 허용(2026-08-12 관제 확정). 화면 표시명은 CCTV명 → 이 값 → 영상 #{RAW_SN} 순으로 폴백한다.';


--
-- Name: COLUMN ls_data_raw.wthr_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.wthr_nm IS '촬영 날씨(작업자 수동입력). NULL=미입력(미상). 자동 파생 원천이 없어 값이 있으면 항상 수동값이다.';


--
-- Name: COLUMN ls_data_raw.day_ngt_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.day_ngt_cd IS '촬영 시간대 코드 주간/야간(작업자 수동입력). NULL=미입력(미상). 동결·export·데이터마트 뷰는 NULL 을 그대로 싣는다 — 촬영일시 추정 안 함(E-ISSUE-42). 촬영일시 파생은 화면 프리필(조회 응답 source=DERIVED)에만 쓴다.';


--
-- Name: COLUMN ls_data_raw.sesn_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.sesn_cd IS '촬영 계절 코드(작업자 수동입력). NULL=미입력(미상). 동결·export·데이터마트 뷰는 NULL 을 그대로 싣는다 — 촬영일시 추정 안 함(E-ISSUE-42). 촬영일시 파생은 화면 프리필(조회 응답 source=DERIVED)에만 쓴다.';


--
-- Name: COLUMN ls_data_raw.src_type; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.src_type IS '출처유형(ORIGINAL/RELAY/USER_ULD/GENERATED/AUGMENTED). 경계축은 "관제가 만들었나 / 저작도구가 만들었나"이며 외부 위탁 여부가 아니다. 관제 인입분은 LS_DATA_INGEST.SRC_TYPE 복사. 백필 전 기존 행은 NULL.';


--
-- Name: COLUMN ls_data_raw.aug_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.aug_type_cd IS '증강유형코드(WINTER/NIGHT/RAIN/RESL_1080P/RESL_720P/RESL_480P). 파생영상이 자기 종류를 직접 보유해 VMS_CLIP_ID 마커 역파싱을 대체한다. 원본 영상은 NULL.';


--
-- Name: COLUMN ls_data_raw.anony_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.anony_incl_yn IS '영상 익명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 Y 프리필 대상).';


--
-- Name: COLUMN ls_data_raw.psdo_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.psdo_incl_yn IS '영상 가명정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';


--
-- Name: COLUMN ls_data_raw.prvc_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_raw.prvc_incl_yn IS '영상 개인정보 포함여부(검수자/작업자 수동입력). NULL=미입력(비식별 기본값 N 프리필 대상).';


--
-- Name: ls_data_raw_raw_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_raw ALTER COLUMN raw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_raw_raw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_src; Type: TABLE
--

CREATE TABLE ls_data_src (
    src_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    frm_no bigint NOT NULL,
    src_file_path_nm character varying(500),
    de_idntf_src_file_path_nm character varying(1000),
    sht_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    upd_dt timestamp without time zone,
    vdo_frm_no bigint,
    frm_expln character varying(1000),
    anony_incl_yn character(1),
    psdo_incl_yn character(1),
    prvc_incl_yn character(1),
    lbl_ver bigint DEFAULT 0 NOT NULL,
    dscd_yn character(1) DEFAULT 'N'::bpchar NOT NULL
);


--
-- Name: COLUMN ls_data_src.vdo_frm_no; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.vdo_frm_no IS '실제 영상 내 디코더 0-base 프레임 위치. FRM_NO(추출순번)와 의미 구분 — 재비식별 재추출용';


--
-- Name: COLUMN ls_data_src.frm_expln; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.frm_expln IS '프레임 설명(작업자 수기, NIA image.description 조달원). nullable.';


--
-- Name: COLUMN ls_data_src.anony_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.anony_incl_yn IS '프레임 익명정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.psdo_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.psdo_incl_yn IS '프레임 가명정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.prvc_incl_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.prvc_incl_yn IS '프레임 개인정보 포함여부(작업자 수동입력). NULL=미입력.';


--
-- Name: COLUMN ls_data_src.lbl_ver; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.lbl_ver IS '라벨버전 - 프레임 라벨셋 변경 시마다 +1 (동시 저장 lost update 차단용, 0부터 시작)';


--
-- Name: COLUMN ls_data_src.dscd_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_data_src.dscd_yn IS '폐기여부 — 이 프레임을 학습데이터 산출물에서 제외할지에 대한 사람의 판정. Y=폐기(산출 제외) / N=사용(기본값). 행을 삭제하지 않고 표시만 한다(복원 가능). 행안부 공통표준용어 DSCD_YN + 공통표준도메인 여부C1(CHAR(1), Y/N).';


--
-- Name: ls_data_src_hstry; Type: TABLE
--

CREATE TABLE ls_data_src_hstry (
    hstry_seq bigint NOT NULL,
    src_sn bigint NOT NULL,
    chg_type_cd character varying(16) NOT NULL,
    chg_user_no bigint,
    chg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_data_src_hstry_hstry_seq_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_src_hstry ALTER COLUMN hstry_seq ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_src_hstry_hstry_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_data_src_src_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_data_src ALTER COLUMN src_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_data_src_src_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_dataset_export; Type: TABLE
--

CREATE TABLE ls_dataset_export (
    output_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    output_ver_no integer NOT NULL,
    output_path_nm character varying(500),
    output_stts_cd character varying(20) NOT NULL,
    frme_cnt integer,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    content_hash character varying(64),
    rty_nmtm integer DEFAULT 0 NOT NULL,
    rty_dt timestamp without time zone,
    data_etbl_cpct bigint
);


--
-- Name: COLUMN ls_dataset_export.output_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.output_sn IS '산출물일련번호(PK). (V173 개명 — 구 EXPORT_SN. EXPORT 는 표준 미등록이라 산출물=OUTPUT 표준단어로 정정)';


--
-- Name: COLUMN ls_dataset_export.output_ver_no; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.output_ver_no IS '산출물버전번호(영상별 누적 건수+1). UK(DATA_RAW_SN, OUTPUT_VER_NO)로 동시 승인 중복 채번을 차단한다. (V173 개명 — 구 EXPORT_VER_NO)';


--
-- Name: COLUMN ls_dataset_export.output_path_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.output_path_nm IS '산출물경로명 — <영상 루트>({dirname(원본)}/{rawSn})다. 버전 루트가 아니다: 관제가 한 경로 아래에서 v1·v2 를 모두 보고 골라야 비교·복구가 성립한다. (V173 개명 — 구 EXPORT_PATH_NM)';


--
-- Name: COLUMN ls_dataset_export.output_stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.output_stts_cd IS '산출물상태코드. PENDING → SUCCEEDED|PARTIAL|FAILED. (V173 개명 — 구 EXPORT_STTS_CD)';


--
-- Name: COLUMN ls_dataset_export.frme_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.frme_cnt IS '산출 프레임 수 — 실제 프레임 수이며 원본벌+비식별벌 합계가 아니다. 관제 datasets.img_nocs · dataset_versions.data_etbl_nocs 에 그대로 실리고 그 정의가 "추출·라벨링 프레임 수"이기 때문이다(2벌 쓰기 건수를 합산하면 2배로 부풀고, 1벌만 산출하는 파생영상과 값의 축이 갈린다). (V185 재지정 — V173 이 남긴 "원본벌+비식별벌 합계" 서술은 산정 방식 변경으로 사실과 달라졌다)';


--
-- Name: COLUMN ls_dataset_export.rty_nmtm; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.rty_nmtm IS '재시도횟수 - 이 산출 행을 기준으로 회수 잡이 재산출을 트리거한 누적 횟수(산출 결과와 무관하게 증가)';


--
-- Name: COLUMN ls_dataset_export.rty_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.rty_dt IS '재시도일시 - 회수 잡이 마지막으로 재산출을 트리거(클레임)한 시각. 동시 노드 중복 클레임 차단 키';


--
-- Name: COLUMN ls_dataset_export.data_etbl_cpct; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_export.data_etbl_cpct IS '데이터구축용량 — 이 버전의 산출 폴더({영상루트}/v{n}) 총 바이트(관제 dataset_versions.data_etbl_cpct 공급). 산출 실패 시 NULL 이며 그때도 export 는 성공으로 종결한다. (V173 신설, @req R4)';


--
-- Name: ls_dataset_export_output_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_dataset_export ALTER COLUMN output_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_dataset_export_output_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_dataset_video_meta; Type: TABLE
--

CREATE TABLE ls_dataset_video_meta (
    meta_snpsht_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    snpsht_hash character varying(64) NOT NULL,
    active_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    orgnl_raw_sn bigint,
    vms_clip_id character varying(128),
    vms_cctv_id character varying(64),
    raw_file_path_nm character varying(500),
    sht_dt timestamp without time zone,
    vdo_len_sec integer,
    lclgv_cd character varying(20),
    prvc_yn character(1),
    prvc_type_cd character varying(16),
    de_ident_yn character(1),
    ai_crt_yn character(1),
    evnt_type_cd character varying(20),
    cctv_nm character varying(255),
    wgs84_lat numeric(10,7),
    wgs84_lot numeric(10,7),
    sido_nm character varying(100),
    sgg_nm character varying(100),
    file_fmt character varying(32),
    evnt_nm character varying(255),
    vdo_cdc character varying(20),
    fps numeric,
    bit_rt bigint,
    asprt_rt numeric,
    resl character varying(32),
    vdo_wdth integer,
    vdo_hgt integer,
    file_sz bigint,
    day_ngt_cd character varying(8),
    sesn_cd character varying(20),
    wthr_nm character varying(32),
    rvw_cmpl_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reg_id character varying(30),
    evnt_anno_cn jsonb
);


--
-- Name: COLUMN ls_dataset_video_meta.evnt_anno_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_dataset_video_meta.evnt_anno_cn IS '동결된 event_annotation payload 원문(jsonb). 승인 시점 APPROVED event_annotation 스냅샷, 미승인/부재 시 NULL';


--
-- Name: ls_dataset_video_meta_meta_snpsht_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_dataset_video_meta ALTER COLUMN meta_snpsht_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_dataset_video_meta_meta_snpsht_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_deident_proc_log; Type: TABLE
--

CREATE TABLE ls_deident_proc_log (
    proc_log_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    req_id character varying(64),
    orgnl_file_path_nm character varying(1000) NOT NULL,
    de_idntf_file_path_nm character varying(1000),
    proc_stts_cd character varying(20) NOT NULL,
    req_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    rspns_dt timestamp without time zone,
    err_cd character varying(50),
    err_msg_cn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    otsd_job_id character varying(200),
    de_idntf_pjt_id bigint,
    de_idntf_datst_id bigint,
    poll_stts_cd character varying(20),
    poll_last_dt timestamp without time zone,
    poll_atmpt_cnt integer DEFAULT 0,
    req_knd_cd character varying(20),
    face_dtct_cnt numeric(10,0),
    noplt_dtct_cnt numeric(10,0),
    frme_cnt numeric(10,0),
    prcs_bgng_dt timestamp without time zone,
    prcs_end_dt timestamp without time zone,
    rpt_file_path_nm character varying(1000)
);


--
-- Name: COLUMN ls_deident_proc_log.face_dtct_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.face_dtct_cnt IS '얼굴 검출 수 — KPST GET /retrieve_report 의 dsStatus[].faceCount(전체 검출 - 번호판). NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.noplt_dtct_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.noplt_dtct_cnt IS '번호판 검출 수 — KPST GET /retrieve_report 의 dsStatus[].lpCount(license plate). NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.frme_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.frme_cnt IS '비식별 처리 대상 총 프레임 수 — KPST GET /retrieve_report 의 dsStatus[].totalFrame. NULL=리포트 미조회/조회 실패.';


--
-- Name: COLUMN ls_deident_proc_log.prcs_bgng_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.prcs_bgng_dt IS '외부 솔루션의 비식별 처리 시작 일시 — dsStatus[].startTime. 해석 불가 값("None" 등)이면 NULL.';


--
-- Name: COLUMN ls_deident_proc_log.prcs_end_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.prcs_end_dt IS '외부 솔루션의 비식별 처리 종료 일시 — dsStatus[].endTime. 해석 불가 값("None" 등)이면 NULL.';


--
-- Name: COLUMN ls_deident_proc_log.rpt_file_path_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_proc_log.rpt_file_path_nm IS '리포트가 회신한 파일 경로 — dsStatus[].fileName. 실측 계약상 결과 파일명이 아니라 원본 입력파일의 절대경로다(비식별 산출물 경로는 DE_IDNTF_FILE_PATH_NM). 1000자 초과 시 절단 적재.';


--
-- Name: ls_deident_proc_log_proc_log_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_deident_proc_log ALTER COLUMN proc_log_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_deident_proc_log_proc_log_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_deident_report; Type: TABLE
--

CREATE TABLE ls_deident_report (
    deident_report_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    reporter_no bigint,
    rsn character varying(1000),
    report_stts_cd character varying(16),
    dclr_dt timestamp without time zone,
    resolved_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    dclr_stp_cd character varying(20)
);


--
-- Name: COLUMN ls_deident_report.dclr_stp_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_deident_report.dclr_stp_cd IS '신고 단계 코드 — MARKING(마킹 화면 신고, 해소 후 마킹부터 재개) / LABELING(라벨링 화면 신고, 해소 후 프레임만 재추출). NULL=단계 미상(컬럼 신설 이전 레거시 행, 재개 이벤트 미발행).';


--
-- Name: ls_deident_report_deident_report_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_deident_report ALTER COLUMN deident_report_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_deident_report_deident_report_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_anno; Type: TABLE
--

CREATE TABLE ls_evnt_anno (
    evnt_anno_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    anno_cn jsonb NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_evnt_anno_evnt_anno_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_evnt_anno ALTER COLUMN evnt_anno_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_evnt_anno_evnt_anno_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_anno_review; Type: TABLE
--

CREATE TABLE ls_evnt_anno_review (
    rvw_sn bigint NOT NULL,
    evnt_anno_sn bigint NOT NULL,
    rvw_stts_cd character varying(20) NOT NULL,
    meta_type_cd character varying(20),
    rvw_id character varying(30),
    rvw_dt timestamp without time zone,
    rjct_rsn character varying(4000),
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    ver bigint DEFAULT 0 NOT NULL
);


--
-- Name: ls_evnt_anno_review_rvw_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_evnt_anno_review ALTER COLUMN rvw_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_evnt_anno_review_rvw_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_evnt_ctgry; Type: TABLE
--

CREATE TABLE ls_evnt_ctgry (
    evnt_clsf_cd character varying(20) NOT NULL,
    evnt_ctgry_cd character varying(20) NOT NULL,
    evnt_ctgry_nm character varying(200),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_evnt_ctgry; Type: COMMENT
--

COMMENT ON TABLE ls_evnt_ctgry IS '이벤트카테고리 마스터(저작도구 소유). 구 관제 매핑(MNG_EX_EVNT_TYPE_MAP CD_TYPE=''02'') 10건의 이관처이며, 유형에 고유 이름이 없을 때 표시명이 여기로 폴백한다. 어노테이션(export)에는 나가지 않지만 원래 3계층 구조를 보존하는 값이다.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_clsf_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_ctgry.evnt_clsf_cd IS '이벤트분류코드(대분류) — 복합 PK 상위.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_ctgry_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_ctgry.evnt_ctgry_cd IS '이벤트카테고리코드 — 복합 PK 하위.';


--
-- Name: COLUMN ls_evnt_ctgry.evnt_ctgry_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_ctgry.evnt_ctgry_nm IS '이벤트카테고리명 — 표시명 4단 폴백의 3순위.';


--
-- Name: COLUMN ls_evnt_ctgry.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_ctgry.reg_dt IS '등록일시.';


--
-- Name: ls_evnt_type; Type: TABLE
--

CREATE TABLE ls_evnt_type (
    evnt_type_cd character varying(20) NOT NULL,
    evnt_nm character varying(200),
    optr_indct_nm character varying(200),
    evnt_clsf_cd character varying(20),
    evnt_ctgry_cd character varying(20),
    clct_yn character(1) DEFAULT 'Y'::bpchar NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_evnt_type; Type: COMMENT
--

COMMENT ON TABLE ls_evnt_type IS '이벤트유형 마스터(저작도구 소유). 관제 인입 소비 시점에 미등록 유형코드가 자동 등록되고, 관제 수신 칸(EVNT_NM)은 인입이 갱신한다. 운영자 칸(OPTR_INDCT_NM)은 관리 API 전용이라 관제가 덮어쓰지 않는다.';


--
-- Name: COLUMN ls_evnt_type.evnt_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.evnt_type_cd IS '이벤트유형코드(PK). LS_DATA_RAW.EVNT_TYPE_CD · export JSON event_id 와 같은 값이다.';


--
-- Name: COLUMN ls_evnt_type.evnt_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.evnt_nm IS '관제 수신 이벤트유형명. 관제 인입(LS_DATA_INGEST.EVNT_NM)이 값이 바뀔 때만 갱신한다. 관제 마스터에는 유형별 이름이 없었으므로 이관 시점에는 비어 있고, 관제가 보내기 시작하면 채워진다.';


--
-- Name: COLUMN ls_evnt_type.optr_indct_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.optr_indct_nm IS '운영자 표시명. 관리 API(PATCH /v1/manage/event-types) 전용이며 ★관제 인입은 절대 쓰지 않는다. 비우면 관제 수신명으로 자연 복귀한다(관제 원본 유실 없음).';


--
-- Name: COLUMN ls_evnt_type.evnt_clsf_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.evnt_clsf_cd IS '이벤트분류코드(대분류). 관제 인입값(LS_DATA_INGEST.EVNT_CLSF_CD)에서 온다. 제외 대분류 설정의 판정축이며 null 이면 제외되지 않는다(fail-open).';


--
-- Name: COLUMN ls_evnt_type.evnt_ctgry_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.evnt_ctgry_cd IS '이벤트카테고리코드 — 원래 3계층 구조(대분류→카테고리→유형)의 중간 레벨. LS_EVNT_CTGRY 조인 키이며, 유형명이 없을 때 표시명이 카테고리명으로 폴백하는 근거다.';


--
-- Name: COLUMN ls_evnt_type.clct_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.clct_yn IS '수집여부(Y/N). 필터 드롭다운 노출 여부. 자동등록 기본값은 Y 다(인입으로 실제 들어온 유형이므로). ★관제는 이 값을 보내지 않으므로 인입이 갱신하지 않는다 — 운영자가 숨긴 유형이 되살아나면 안 된다.';


--
-- Name: COLUMN ls_evnt_type.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_evnt_type.reg_dt IS '등록일시.';


--
-- Name: ls_issue_comment; Type: TABLE
--

CREATE TABLE ls_issue_comment (
    cmnt_sn bigint NOT NULL,
    data_issue_sn bigint NOT NULL,
    author_no character varying(50) NOT NULL,
    author_role_cd character varying(20) NOT NULL,
    cmnt_cn character varying(4000) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_ls_issue_comment_role CHECK ((author_role_cd IN ('WORKER', 'REVIEWER')))
);


--
-- Name: ls_issue_comment_issue_comment_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_issue_comment ALTER COLUMN cmnt_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_issue_comment_issue_comment_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label; Type: TABLE
--

CREATE TABLE ls_label (
    lbl_id bigint NOT NULL,
    lbl_nm character varying(80) NOT NULL,
    colr_vl character varying(7) NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    sort_seq integer DEFAULT 0 NOT NULL,
    use_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone,
    dtct_type_cd character varying(20)
);


--
-- Name: COLUMN ls_label.dtct_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_label.dtct_type_cd IS 'AI(COCO) 검출 클래스 매핑 — COCO 80 클래스명 저장(예: person/car/bus). NULL=미매핑(표시하되 검출 제외).';


--
-- Name: ls_label_attr; Type: TABLE
--

CREATE TABLE ls_label_attr (
    atrb_id bigint NOT NULL,
    lbl_id bigint NOT NULL,
    atrb_nm character varying(100) NOT NULL,
    input_type_cd character varying(16) NOT NULL,
    values_cn character varying(1000),
    dflt_vl character varying(255),
    mutable_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    sort_seq integer DEFAULT 0 NOT NULL,
    use_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_id character varying(30),
    mdfcn_dt timestamp without time zone
);


--
-- Name: ls_label_attr_attr_id_seq; Type: SEQUENCE
--

ALTER TABLE ls_label_attr ALTER COLUMN atrb_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_label_attr_attr_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_label_id_seq; Type: SEQUENCE
--

ALTER TABLE ls_label ALTER COLUMN lbl_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_label_label_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_preset; Type: TABLE
--

CREATE TABLE ls_label_preset (
    preset_id bigint NOT NULL,
    preset_nm character varying(64) NOT NULL,
    expln character varying(500),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    evnt_type_cd character varying(20)
);


--
-- Name: ls_label_preset_code; Type: TABLE
--

CREATE TABLE ls_label_preset_code (
    cd_sn bigint NOT NULL,
    preset_id bigint NOT NULL,
    lbl_cd character varying(32),
    sort_seq integer DEFAULT 0 NOT NULL,
    lbl_id bigint
);


--
-- Name: ls_label_preset_code_code_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_label_preset_code ALTER COLUMN cd_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_label_preset_code_code_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_preset_preset_id_seq; Type: SEQUENCE
--

ALTER TABLE ls_label_preset ALTER COLUMN preset_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_label_preset_preset_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_label_version; Type: TABLE
--

CREATE TABLE ls_label_version (
    lbl_version_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint,
    ver_no integer,
    save_reason_cd character varying(20),
    actvtn_yn character(1) DEFAULT 'Y'::character varying NOT NULL,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lbl_payload text,
    version_hash character varying(64)
);


--
-- Name: COLUMN ls_label_version.ver_no; Type: COMMENT
--

COMMENT ON COLUMN ls_label_version.ver_no IS '영상 단위 산출 버전 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 와 같은 번호이며 관제가 픽업하는 산출 폴더 v1·v2 와 일치한다. NULL=버전 번호를 알 수 없음. 구 의미(프레임별 승인 순번)는 폐기됐다 — 승인은 영상 단위인데 내용 무변경 프레임은 스냅샷이 생기지 않아 프레임마다 번호가 밀려 회차를 지목할 수 없었다(V181 에서 기존 값 전량 무효화).';


--
-- Name: ls_label_version_label_version_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_label_version ALTER COLUMN lbl_version_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_label_version_label_version_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_marking; Type: TABLE
--

CREATE TABLE ls_marking (
    marking_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    evnt_nm character varying(200) NOT NULL,
    mark_mode_cd character varying(16) NOT NULL,
    frme_intv_nocs integer,
    video_file_path_nm character varying(500) NOT NULL,
    mark_cn text NOT NULL,
    stts_cd character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    reg_user_no bigint,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    fps double precision
);


--
-- Name: ls_marking_marking_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_marking ALTER COLUMN marking_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_marking_marking_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_meta_repl_outbox; Type: TABLE
--

CREATE TABLE ls_meta_repl_outbox (
    outbox_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    snpsht_hash character varying(64) NOT NULL,
    payload text,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    retry_cnt integer DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    proc_dt timestamp without time zone
);


--
-- Name: ls_meta_repl_outbox_outbox_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_meta_repl_outbox ALTER COLUMN outbox_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_meta_repl_outbox_outbox_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_mon_noti_acml; Type: TABLE
--

CREATE TABLE ls_mon_noti_acml (
    noti_acml_sn bigint NOT NULL,
    raw_sn bigint NOT NULL,
    stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    export_rprcs_yn character(1) DEFAULT 'N'::bpchar NOT NULL,
    chg_dtl_cn text DEFAULT '{}'::text NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_mon_noti_acml; Type: COMMENT
--

COMMENT ON TABLE ls_mon_noti_acml IS '관제 수정 통지 디바운스 누적 — 영상 1건의 변경을 윈도우로 모아 1회만 flush(2노드 정합).';


--
-- Name: COLUMN ls_mon_noti_acml.raw_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.raw_sn IS '영상 단위 작업 식별자. 통지 단위가 영상 1건이므로 윈도우 키다.';


--
-- Name: COLUMN ls_mon_noti_acml.stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.stts_cd IS 'PENDING(축적 중) / FLUSHING(한 노드가 클레임해 발송 중). 발송 완료 시 행 삭제.';


--
-- Name: COLUMN ls_mon_noti_acml.export_rprcs_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.export_rprcs_yn IS 'export 폴더 전량 재생성 동반 여부(OR 누적). Y 면 재생성 후 통지, N 이면 즉시 통지.';


--
-- Name: COLUMN ls_mon_noti_acml.chg_dtl_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.chg_dtl_cn IS '누적 변경 상세 JSON {"frames":{srcSn:[변경종류..]},"video":[변경종류..]}. 라벨/메타 본문·PII 미포함.';


--
-- Name: COLUMN ls_mon_noti_acml.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.reg_dt IS '윈도우 열린 시각. 디바운스 만료 판정 기준.';


--
-- Name: COLUMN ls_mon_noti_acml.mdfcn_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_mon_noti_acml.mdfcn_dt IS '마지막 갱신 시각. FLUSHING 행에서는 클레임 임차 시작 시각(임차 만료 시 재클레임 기준).';


--
-- Name: ls_mon_noti_acml_noti_acml_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_mon_noti_acml ALTER COLUMN noti_acml_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_mon_noti_acml_noti_acml_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_notice; Type: TABLE
--

CREATE TABLE ls_notice (
    notice_sn bigint NOT NULL,
    notice_title character varying(200) NOT NULL,
    notice_cn text NOT NULL,
    upend_fix_yn character(1) DEFAULT 'N'::character varying NOT NULL,
    pblcn_stts_cd character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    pblcn_dt timestamp without time zone,
    reg_id character varying(30),
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_notice_attach; Type: TABLE
--

CREATE TABLE ls_notice_attach (
    atch_file_sn bigint NOT NULL,
    notice_sn bigint NOT NULL,
    orgnl_file_nm character varying(300) NOT NULL,
    strg_file_nm character varying(300) NOT NULL,
    file_path character varying(1000) NOT NULL,
    file_sz bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_notice_attach_attach_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_notice_attach ALTER COLUMN atch_file_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_notice_attach_attach_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_notice_notice_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_notice ALTER COLUMN notice_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_notice_notice_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_output_ver_snpsh; Type: TABLE
--

CREATE TABLE ls_output_ver_snpsh (
    output_ver_snpsh_sn bigint NOT NULL,
    data_raw_sn bigint NOT NULL,
    data_src_sn bigint NOT NULL,
    output_ver_no integer NOT NULL,
    lbl_ver_sn bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_output_ver_snpsh; Type: COMMENT
--

COMMENT ON TABLE ls_output_ver_snpsh IS '산출 회차 ↔ 라벨 버전 스냅샷 매핑 — 「산출 회차 N 의 프레임 F 내용은 이 스냅샷이었다」. 「시작 버전 선택」(R6)이 되돌릴 스냅샷을 고르는 판정의 단일 원천이다. LS_LABEL_VERSION.VER_NO 는 조회·표시용으로 남되 판정 원천이 아니다 — 한 스냅샷이 여러 회차의 내용일 수 있어(내용 무변경 회차는 스냅샷이 생기지 않는다) 컬럼 하나로는 1:N 을 담을 수 없다.';


--
-- Name: COLUMN ls_output_ver_snpsh.output_ver_no; Type: COMMENT
--

COMMENT ON COLUMN ls_output_ver_snpsh.output_ver_no IS '산출 회차 번호 — LS_DATASET_EXPORT.OUTPUT_VER_NO 및 관제가 픽업하는 산출 폴더 v1·v2 와 같다.';


--
-- Name: COLUMN ls_output_ver_snpsh.lbl_ver_sn; Type: COMMENT
--

COMMENT ON COLUMN ls_output_ver_snpsh.lbl_ver_sn IS '그 회차의 내용이 된 승인 스냅샷 PK(LS_LABEL_VERSION.LBL_VERSION_SN). 컬럼명은 표준 약어 VER 을 쓴다 — 참조 대상의 VERSION 표기는 선존 드리프트이며 미러하지 않는다.';


--
-- Name: ls_output_ver_snpsh_output_ver_snpsh_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_output_ver_snpsh ALTER COLUMN output_ver_snpsh_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_output_ver_snpsh_output_ver_snpsh_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_tus_uld; Type: TABLE
--

CREATE TABLE ls_portal_tus_uld (
    uld_id uuid NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_len bigint NOT NULL,
    uld_offset bigint NOT NULL,
    stts_cd character varying(16) NOT NULL,
    file_path_nm character varying(500) NOT NULL,
    orgnl_file_nm character varying(255),
    uld_sn bigint,
    expry_dt timestamp without time zone NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_portal_uld; Type: TABLE
--

CREATE TABLE ls_portal_uld (
    uld_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_type_cd character varying(16) NOT NULL,
    orgnl_file_nm character varying(255),
    file_path_nm character varying(500),
    file_sz bigint,
    mime_type_nm character varying(100),
    uld_stts_cd character varying(16) NOT NULL,
    vdo_len_sec double precision,
    fps double precision,
    frme_cnt integer,
    fail_rsn_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld; Type: COMMENT
--

COMMENT ON TABLE ls_portal_uld IS '포털 전용 업로드 원본(이미지/영상) — 데이터마트/원본과 무관한 포털 작업본.';


--
-- Name: COLUMN ls_portal_uld.uld_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld.uld_type_cd IS '업로드 유형: IMAGE | VIDEO.';


--
-- Name: COLUMN ls_portal_uld.uld_stts_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld.uld_stts_cd IS '업로드 상태: UPLOADED | PROCESSING | READY | FAILED.';


--
-- Name: COLUMN ls_portal_uld.frme_cnt; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld.frme_cnt IS '영상 추출 프레임 수(이미지는 null).';


--
-- Name: COLUMN ls_portal_uld.fail_rsn_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld.fail_rsn_cn IS '처리 실패 사유(내용). 상태 FAILED 시 기록.';


--
-- Name: ls_portal_uld_frme_seq; Type: SEQUENCE
--

CREATE SEQUENCE ls_portal_uld_frme_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ls_portal_uld_frme; Type: TABLE
--

CREATE TABLE ls_portal_uld_frme (
    uld_frme_sn bigint DEFAULT nextval('ls_portal_uld_frme_seq'::regclass) NOT NULL,
    uld_sn bigint NOT NULL,
    frme_no integer NOT NULL,
    file_path_nm character varying(500) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld_frme; Type: COMMENT
--

COMMENT ON TABLE ls_portal_uld_frme IS '포털 업로드 프레임(이미지=1행, 영상=추출 프레임 N행). 업로드 삭제 시 연쇄 삭제.';


--
-- Name: COLUMN ls_portal_uld_frme.frme_no; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld_frme.frme_no IS '프레임 순번(영상 추출 순서, 이미지는 0).';


--
-- Name: ls_portal_uld_lbl; Type: TABLE
--

CREATE TABLE ls_portal_uld_lbl (
    uld_lbl_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    uld_sn bigint NOT NULL,
    uld_frme_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80),
    point_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_portal_uld_lbl; Type: COMMENT
--

COMMENT ON TABLE ls_portal_uld_lbl IS '포털 업로드 프레임 위 수동 라벨(BBOX/POLYGON). 프레임 삭제 시 연쇄 삭제.';


--
-- Name: COLUMN ls_portal_uld_lbl.lbl_type_cd; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld_lbl.lbl_type_cd IS '라벨 유형: BBOX | POLYGON.';


--
-- Name: COLUMN ls_portal_uld_lbl.point_cn; Type: COMMENT
--

COMMENT ON COLUMN ls_portal_uld_lbl.point_cn IS '좌표 JSON 내용(BBOX=[x,y,w,h] / POLYGON=[[x,y],...]).';


--
-- Name: ls_portal_uld_lbl_uld_lbl_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_portal_uld_lbl ALTER COLUMN uld_lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_portal_uld_lbl_uld_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_uld_uld_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_portal_uld ALTER COLUMN uld_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_portal_uld_uld_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_portal_user_label; Type: TABLE
--

CREATE TABLE ls_portal_user_label (
    user_lbl_sn bigint NOT NULL,
    portal_user_no character varying(100) NOT NULL,
    src_raw_sn bigint NOT NULL,
    src_data_src_sn bigint NOT NULL,
    lbl_type_cd character varying(16) NOT NULL,
    lbl_nm character varying(80),
    point_cn text,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_portal_user_label_user_lbl_sn_seq; Type: SEQUENCE
--

ALTER TABLE ls_portal_user_label ALTER COLUMN user_lbl_sn ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_portal_user_label_user_lbl_sn_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_raw_data_status; Type: TABLE
--

CREATE TABLE ls_raw_data_status (
    raw_data_id bigint NOT NULL,
    data_stts_cd character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    stp_cycl integer DEFAULT 0 NOT NULL,
    igi_cycl integer DEFAULT 0 NOT NULL,
    upd_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    revlt_yn character(1) DEFAULT 'N'::bpchar NOT NULL
);


--
-- Name: COLUMN ls_raw_data_status.revlt_yn; Type: COMMENT
--

COMMENT ON COLUMN ls_raw_data_status.revlt_yn IS '재검토여부 — 검수 승인(APPROVED) 이후 라벨/메타가 수정되어 검수자의 재검토가 필요한가. Y=재검토 필요(재승인 대기) / N=재검토 불요(기본값). 행안부 공통표준용어 REVLT_YN + 공통표준도메인 여부C1(CHAR(1), Y/N).';


--
-- Name: ls_system_config; Type: TABLE
--

CREATE TABLE ls_system_config (
    stng_key character varying(100) NOT NULL,
    stng_value character varying(4000),
    stng_type_cd character varying(20) NOT NULL,
    expln character varying(500),
    mdfr_id character varying(30),
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_task_assignment; Type: TABLE
--

CREATE TABLE ls_task_assignment (
    assignment_id bigint NOT NULL,
    user_no bigint NOT NULL,
    raw_data_id bigint NOT NULL,
    task_type_cd character varying(20) NOT NULL,
    reg_user_no bigint NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    ver bigint DEFAULT 0 NOT NULL
);


--
-- Name: ls_task_assignment_assignment_id_seq; Type: SEQUENCE
--

ALTER TABLE ls_task_assignment ALTER COLUMN assignment_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_task_assignment_assignment_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_task_event_log; Type: TABLE
--

CREATE TABLE ls_task_event_log (
    evnt_id bigint NOT NULL,
    raw_data_id bigint NOT NULL,
    evnt_type_cd character varying(20) NOT NULL,
    actor_user_no bigint NOT NULL,
    subject_user_no bigint,
    prev_user_no bigint,
    rsn character varying(500),
    ocrn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_task_event_log_event_seq_seq; Type: SEQUENCE
--

ALTER TABLE ls_task_event_log ALTER COLUMN evnt_id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME ls_task_event_log_event_seq_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: ls_tus_upload; Type: TABLE
--

CREATE TABLE ls_tus_upload (
    uld_id uuid NOT NULL,
    user_no character varying(64) NOT NULL,
    uld_len bigint NOT NULL,
    uld_offset bigint DEFAULT 0 NOT NULL,
    stts_cd character varying(16) DEFAULT 'IN_PROGRESS'::character varying NOT NULL,
    file_path character varying(500) NOT NULL,
    file_nm character varying(255),
    vms_clip_id character varying(128),
    cctv_id character varying(64),
    evnt_type_cd character varying(20),
    lclgv_cd character varying(20),
    prvc_type_cd character varying(8),
    sht_dt timestamp without time zone,
    raw_sn bigint,
    expry_dt timestamp without time zone NOT NULL,
    ver bigint DEFAULT 0 NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: ls_user_role; Type: TABLE
--

CREATE TABLE ls_user_role (
    user_no bigint NOT NULL,
    role_cd character varying(32) NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    upd_dt timestamp without time zone
);


--
-- Name: ls_webhook_idempotency; Type: TABLE
--

CREATE TABLE ls_webhook_idempotency (
    idmp_key character varying(128) NOT NULL,
    chnl_cd character varying(32) NOT NULL,
    stts_cd character varying(16) NOT NULL,
    otsd_job_id character varying(200),
    aply_dt timestamp without time zone,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    raw_sn bigint
);


--
-- Name: ls_whk_fail_nmtm; Type: TABLE
--

CREATE TABLE ls_whk_fail_nmtm (
    call_ip_addr character varying(45) NOT NULL,
    bgng_dt timestamp without time zone NOT NULL,
    fail_nmtm integer DEFAULT 0 NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    mdfcn_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_whk_fail_nmtm; Type: COMMENT
--

COMMENT ON TABLE ls_whk_fail_nmtm IS '웹훅 인증 실패 횟수 — 노드 공유 rate limit 집계(분 단위 윈도우).';


--
-- Name: COLUMN ls_whk_fail_nmtm.call_ip_addr; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.call_ip_addr IS '호출 IP 주소 — 신뢰 프록시 기반으로 산출된 클라이언트 IP.';


--
-- Name: COLUMN ls_whk_fail_nmtm.bgng_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.bgng_dt IS '집계 윈도우 시작일시 — 분 단위로 절삭된 버킷 키.';


--
-- Name: COLUMN ls_whk_fail_nmtm.fail_nmtm; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.fail_nmtm IS '해당 윈도우의 누적 인증 실패 횟수.';


--
-- Name: COLUMN ls_whk_fail_nmtm.expd_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.expd_dt IS '만료일시 — 경과 시 정리(중복 실행 무해한 조건부 DELETE).';


--
-- Name: COLUMN ls_whk_fail_nmtm.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.reg_dt IS '등록일시.';


--
-- Name: COLUMN ls_whk_fail_nmtm.mdfcn_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_fail_nmtm.mdfcn_dt IS '수정일시.';


--
-- Name: ls_whk_sign_use; Type: TABLE
--

CREATE TABLE ls_whk_sign_use (
    sign_hash character varying(64) NOT NULL,
    whk_path_nm character varying(200) NOT NULL,
    expd_dt timestamp without time zone NOT NULL,
    reg_dt timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);


--
-- Name: TABLE ls_whk_sign_use; Type: COMMENT
--

COMMENT ON TABLE ls_whk_sign_use IS '웹훅 서명 사용 원장 — 동일 서명 재전송(replay) 차단용 1회성 소비 기록.';


--
-- Name: COLUMN ls_whk_sign_use.sign_hash; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_sign_use.sign_hash IS '서명 해시 — (경로, X-Timestamp, X-Signature) SHA-256 hex.';


--
-- Name: COLUMN ls_whk_sign_use.whk_path_nm; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_sign_use.whk_path_nm IS '웹훅 경로명 — 운영 추적용.';


--
-- Name: COLUMN ls_whk_sign_use.expd_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_sign_use.expd_dt IS '만료일시 — timestamp 허용창을 덮는 보존기간. 경과 시 정리.';


--
-- Name: COLUMN ls_whk_sign_use.reg_dt; Type: COMMENT
--

COMMENT ON COLUMN ls_whk_sign_use.reg_dt IS '등록일시.';


--
-- Name: qrtz_blob_triggers; Type: TABLE
--

CREATE TABLE qrtz_blob_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    blob_data bytea
);


--
-- Name: qrtz_calendars; Type: TABLE
--

CREATE TABLE qrtz_calendars (
    sched_name character varying(120) NOT NULL,
    calendar_name character varying(200) NOT NULL,
    calendar bytea NOT NULL
);


--
-- Name: qrtz_cron_triggers; Type: TABLE
--

CREATE TABLE qrtz_cron_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    cron_expression character varying(120) NOT NULL,
    time_zone_id character varying(80)
);


--
-- Name: qrtz_fired_triggers; Type: TABLE
--

CREATE TABLE qrtz_fired_triggers (
    sched_name character varying(120) NOT NULL,
    entry_id character varying(140) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    instance_name character varying(200) NOT NULL,
    fired_time bigint NOT NULL,
    sched_time bigint NOT NULL,
    priority integer NOT NULL,
    state character varying(16) NOT NULL,
    job_name character varying(200),
    job_group character varying(200),
    is_nonconcurrent boolean,
    requests_recovery boolean
);


--
-- Name: qrtz_job_details; Type: TABLE
--

CREATE TABLE qrtz_job_details (
    sched_name character varying(120) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    job_class_name character varying(250) NOT NULL,
    is_durable boolean NOT NULL,
    is_nonconcurrent boolean NOT NULL,
    is_update_data boolean NOT NULL,
    requests_recovery boolean NOT NULL,
    job_data bytea
);


--
-- Name: qrtz_locks; Type: TABLE
--

CREATE TABLE qrtz_locks (
    sched_name character varying(120) NOT NULL,
    lock_name character varying(40) NOT NULL
);


--
-- Name: qrtz_paused_trigger_grps; Type: TABLE
--

CREATE TABLE qrtz_paused_trigger_grps (
    sched_name character varying(120) NOT NULL,
    trigger_group character varying(200) NOT NULL
);


--
-- Name: qrtz_scheduler_state; Type: TABLE
--

CREATE TABLE qrtz_scheduler_state (
    sched_name character varying(120) NOT NULL,
    instance_name character varying(200) NOT NULL,
    last_checkin_time bigint NOT NULL,
    checkin_interval bigint NOT NULL
);


--
-- Name: qrtz_simple_triggers; Type: TABLE
--

CREATE TABLE qrtz_simple_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    repeat_count bigint NOT NULL,
    repeat_interval bigint NOT NULL,
    times_triggered bigint NOT NULL
);


--
-- Name: qrtz_simprop_triggers; Type: TABLE
--

CREATE TABLE qrtz_simprop_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    str_prop_1 character varying(512),
    str_prop_2 character varying(512),
    str_prop_3 character varying(512),
    int_prop_1 integer,
    int_prop_2 integer,
    long_prop_1 bigint,
    long_prop_2 bigint,
    dec_prop_1 numeric(13,4),
    dec_prop_2 numeric(13,4),
    bool_prop_1 boolean,
    bool_prop_2 boolean
);


--
-- Name: qrtz_triggers; Type: TABLE
--

CREATE TABLE qrtz_triggers (
    sched_name character varying(120) NOT NULL,
    trigger_name character varying(200) NOT NULL,
    trigger_group character varying(200) NOT NULL,
    job_name character varying(200) NOT NULL,
    job_group character varying(200) NOT NULL,
    description character varying(250),
    next_fire_time bigint,
    prev_fire_time bigint,
    priority integer,
    trigger_state character varying(16) NOT NULL,
    trigger_type character varying(8) NOT NULL,
    start_time bigint NOT NULL,
    end_time bigint,
    calendar_name character varying(200),
    misfire_instr smallint,
    job_data bytea
);


--
-- Name: v_completed_frame; Type: VIEW
--

CREATE VIEW v_completed_frame AS
 SELECT src_sn,
    raw_sn,
    frm_no AS frame_no,
    src_file_path_nm AS original_path,
    de_idntf_src_file_path_nm AS deidentified_path,
    sht_dt AS captured_at,
    reg_dt,
    upd_dt,
    frm_expln AS description
   FROM ls_data_src src
  WHERE ((EXISTS ( SELECT 1
           FROM ls_raw_data_status s
          WHERE ((s.raw_data_id = src.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))) AND (NOT ((src_file_path_nm IS NOT NULL) AND (TRIM(BOTH FROM src_file_path_nm) <> ''::text) AND (de_idntf_src_file_path_nm IS NOT NULL) AND (TRIM(BOTH FROM de_idntf_src_file_path_nm) <> ''::text) AND ((de_idntf_src_file_path_nm)::text = (src_file_path_nm)::text))) AND (COALESCE(dscd_yn, 'N'::bpchar) <> 'Y'::bpchar));


--
-- Name: VIEW v_completed_frame; Type: COMMENT
--

COMMENT ON VIEW v_completed_frame IS '검수완료 영상의 프레임 페어(원본/비식별) — 비식별 경로 불변식 게이트(V133), 폐기 프레임 제외(V182)';


--
-- Name: v_completed_label_change; Type: VIEW
--

CREATE VIEW v_completed_label_change AS
 SELECT h.lbl_hstry_sn,
    src.raw_sn,
    h.src_sn,
    h.add_cnt,
    h.mdfcn_cnt,
    h.del_cnt,
    h.reg_id,
    h.reg_dt
   FROM (ls_data_lbl_hstry h
     JOIN ls_data_src src ON ((src.src_sn = h.src_sn)))
  WHERE ((((COALESCE(h.add_cnt, 0) + COALESCE(h.mdfcn_cnt, 0)) + COALESCE(h.del_cnt, 0)) > 0) AND (COALESCE(src.dscd_yn, 'N'::bpchar) <> 'Y'::bpchar) AND (EXISTS ( SELECT 1
           FROM ls_raw_data_status s
          WHERE ((s.raw_data_id = src.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))));


--
-- Name: VIEW v_completed_label_change; Type: COMMENT
--

COMMENT ON VIEW v_completed_label_change IS '검수완료 영상의 라벨 저장이벤트 변경점 — 종류별 건수만 노출(V137), 변경 0건 행 제외(V139), 폐기 프레임 제외(V182)';


--
-- Name: v_completed_meta; Type: VIEW
--

CREATE VIEW v_completed_meta AS
 SELECT meta.meta_sn,
    meta.raw_sn,
    meta.meta_key,
    meta.meta_vl,
    meta.otsd_job_id AS external_job_id,
    mrev.data_meta_review_sn,
    mrev.meta_type_cd,
    mrev.src_sys_cd,
    mrev.rvw_stts_cd,
    mrev.rvw_id,
    mrev.rvw_dt AS reviewed_at
   FROM (ls_data_meta meta
     JOIN ls_data_meta_review mrev ON ((mrev.data_meta_sn = meta.meta_sn)))
  WHERE (((mrev.rvw_stts_cd)::text = 'APPROVED'::text) AND ((meta.meta_key)::text !~~ 'video.%'::text) AND (EXISTS ( SELECT 1
           FROM ls_raw_data_status s
          WHERE ((s.raw_data_id = meta.raw_sn) AND ((s.data_stts_cd)::text = 'APPROVED'::text)))));


--
-- Name: v_completed_video; Type: VIEW
--

CREATE VIEW v_completed_video AS
 SELECT m.raw_sn,
    m.orgnl_raw_sn,
    m.evnt_type_cd,
    i.evnt_clsf_cd,
    i.evnt_ctgry_cd,
    m.lclgv_cd,
    i.lclgv_nm,
    (
        CASE
            WHEN (r.src_type IN ('GENERATED', 'AUGMENTED')) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS gen_ai_yn,
    (((m.evnt_nm)::text || ' 데이터셋 구축'::text))::character varying(200) AS datst_nm,
    (((m.evnt_nm)::text || ' 데이터셋 구축'::text))::character varying(4000) AS datst_expln,
    m.vdo_len_sec,
    COALESCE(e.frme_cnt, 0) AS frme_cnt,
    m.rvw_cmpl_dt AS rvw_cmptn_dt,
    (
        CASE
            WHEN (COALESCE(e.frme_cnt, 0) > 0) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS img_yn,
    (
        CASE
            WHEN (d.de_idntf_file_path_nm IS NOT NULL) THEN 'Y'::text
            ELSE 'N'::text
        END)::character(1) AS vdo_yn,
    (COALESCE(NULLIF(btrim((r.anony_incl_yn)::text), ''::text), 'Y'::text))::character(1) AS anony_incl_yn,
    (COALESCE(NULLIF(btrim((r.psdo_incl_yn)::text), ''::text), 'N'::text))::character(1) AS psdo_incl_yn,
    (COALESCE(NULLIF(btrim((r.prvc_incl_yn)::text), ''::text), 'N'::text))::character(1) AS prvc_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.anony_incl_yn
            ELSE NULL::bpchar
        END AS src_anony_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.psdo_incl_yn
            ELSE NULL::bpchar
        END AS src_psdo_incl_yn,
        CASE
            WHEN (r.orgnl_raw_sn IS NULL) THEN i.prvc_incl_yn
            ELSE NULL::bpchar
        END AS src_prvc_incl_yn,
    to_char(m.rvw_cmpl_dt, 'YYYY'::text) AS data_etbl_yr,
    e.data_etbl_cpct,
    (l.lbl_type)::character varying(256) AS lbl_type,
    'NIA-COCO-JSON'::character varying(256) AS lbl_fmt,
    e.output_path_nm,
    e.output_stts_cd,
    d.de_idntf_file_path_nm,
    m.raw_file_path_nm AS orgnl_vdo_path_nm,
    m.de_ident_yn AS de_idntf_yn
   FROM ((((((ls_dataset_video_meta m
     JOIN ls_data_raw r ON ((r.raw_sn = m.raw_sn)))
     JOIN ls_raw_data_status s ON ((s.raw_data_id = m.raw_sn)))
     LEFT JOIN LATERAL ( SELECT ex.output_path_nm,
            ex.output_stts_cd,
            ex.frme_cnt,
            ex.data_etbl_cpct
           FROM ls_dataset_export ex
          WHERE ((ex.data_raw_sn = m.raw_sn) AND (ex.output_stts_cd IN ('SUCCEEDED', 'PARTIAL')))
          ORDER BY ex.output_ver_no DESC
         LIMIT 1) e ON (true))
     LEFT JOIN LATERAL ( SELECT pl.de_idntf_file_path_nm
           FROM ls_deident_proc_log pl
          WHERE ((pl.data_raw_sn = m.raw_sn) AND ((pl.proc_stts_cd)::text = 'SUCCEEDED'::text) AND (pl.de_idntf_file_path_nm IS NOT NULL))
          ORDER BY pl.req_dt DESC, pl.proc_log_sn DESC
         LIMIT 1) d ON (true))
     LEFT JOIN LATERAL ( SELECT ig.evnt_clsf_cd,
            ig.evnt_ctgry_cd,
            ig.lclgv_nm,
            ig.anony_incl_yn,
            ig.psdo_incl_yn,
            ig.prvc_incl_yn
           FROM ls_data_ingest ig
          WHERE (ig.raw_sn = COALESCE(r.orgnl_raw_sn, r.raw_sn))
          ORDER BY ig.rcptn_sn DESC
         LIMIT 1) i ON (true))
     LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT (lb.lbl_type_cd)::text, ','::text ORDER BY (lb.lbl_type_cd)::text) AS lbl_type
           FROM (ls_data_lbl lb
             JOIN ls_data_src sc ON ((sc.src_sn = lb.src_sn)))
          WHERE (sc.raw_sn = m.raw_sn)) l ON (true))
  WHERE ((m.active_yn = 'Y'::bpchar) AND ((s.data_stts_cd)::text = 'APPROVED'::text));


--
-- Name: VIEW v_completed_video; Type: COMMENT
--

COMMENT ON VIEW v_completed_video IS '데이터마트 적재용 — 검수 승인(APPROVED) 영상 1건 = 1 row, 30컬럼(V174, 규격서 §5-1). 관제가 datasets·dataset_versions 를 채우고 산출 폴더·비식별 영상을 픽업하는 계약면이다. 비식별 누락 신고 구간(DE_IDNTF_YN=''F'')에도 행을 감추거나 경로를 비우지 않는다(확정 정책) — 관제가 DE_IDNTF_YN 으로 자체 판단한다.';


--
-- Name: ls_acnt_user ls_acnt_user_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_acnt_user
    ADD CONSTRAINT ls_acnt_user_pkey PRIMARY KEY (user_no);


--
-- Name: ls_auth_work_lock ls_auth_work_lock_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_auth_work_lock
    ADD CONSTRAINT ls_auth_work_lock_pkey PRIMARY KEY (work_lock_sn);


--
-- Name: ls_bat_rty_wtng ls_bat_rty_wtng_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_bat_rty_wtng
    ADD CONSTRAINT ls_bat_rty_wtng_pkey PRIMARY KEY (bat_rty_sn);


--
-- Name: ls_batch_proc_log ls_batch_proc_log_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_batch_proc_log
    ADD CONSTRAINT ls_batch_proc_log_pkey PRIMARY KEY (batch_proc_log_sn);


--
-- Name: ls_clip_schedule_que ls_clip_schedule_que_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_clip_schedule_que
    ADD CONSTRAINT ls_clip_schedule_que_pkey PRIMARY KEY (que_sn);


--
-- Name: ls_control_notify_fallback ls_control_notify_fallback_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_control_notify_fallback
    ADD CONSTRAINT ls_control_notify_fallback_pkey PRIMARY KEY (queue_sn);


--
-- Name: ls_data_aug_dscd ls_data_aug_dscd_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_dscd
    ADD CONSTRAINT ls_data_aug_dscd_pkey PRIMARY KEY (data_aug_dscd_sn);


--
-- Name: ls_data_aug_job_file ls_data_aug_job_file_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job_file
    ADD CONSTRAINT ls_data_aug_job_file_pkey PRIMARY KEY (aug_job_file_sn);


--
-- Name: ls_data_aug_job ls_data_aug_job_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job
    ADD CONSTRAINT ls_data_aug_job_pkey PRIMARY KEY (aug_job_sn);


--
-- Name: ls_data_aug_lbl_map ls_data_aug_lbl_map_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_lbl_map
    ADD CONSTRAINT ls_data_aug_lbl_map_pkey PRIMARY KEY (data_aug_lbl_map_sn);


--
-- Name: ls_data_aug ls_data_aug_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug
    ADD CONSTRAINT ls_data_aug_pkey PRIMARY KEY (data_aug_sn);


--
-- Name: ls_data_aug_rvw ls_data_aug_rvw_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_rvw
    ADD CONSTRAINT ls_data_aug_rvw_pkey PRIMARY KEY (data_aug_rvw_sn);


--
-- Name: ls_data_issue ls_data_issue_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_issue
    ADD CONSTRAINT ls_data_issue_pkey PRIMARY KEY (data_issue_sn);


--
-- Name: ls_data_lbl_ai_info ls_data_lbl_ai_info_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_ai_info
    ADD CONSTRAINT ls_data_lbl_ai_info_pkey PRIMARY KEY (data_lbl_ai_info_sn);


--
-- Name: ls_data_lbl_attr_val ls_data_lbl_attr_val_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_attr_val
    ADD CONSTRAINT ls_data_lbl_attr_val_pkey PRIMARY KEY (atrb_vl_id);


--
-- Name: ls_data_lbl_hstry ls_data_lbl_hstry_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_hstry
    ADD CONSTRAINT ls_data_lbl_hstry_pkey PRIMARY KEY (lbl_hstry_sn);


--
-- Name: ls_data_lbl ls_data_lbl_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl
    ADD CONSTRAINT ls_data_lbl_pkey PRIMARY KEY (lbl_sn);


--
-- Name: ls_data_meta ls_data_meta_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta
    ADD CONSTRAINT ls_data_meta_pkey PRIMARY KEY (meta_sn);


--
-- Name: ls_data_meta_review ls_data_meta_review_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta_review
    ADD CONSTRAINT ls_data_meta_review_pkey PRIMARY KEY (data_meta_review_sn);


--
-- Name: ls_data_raw ls_data_raw_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_raw
    ADD CONSTRAINT ls_data_raw_pkey PRIMARY KEY (raw_sn);


--
-- Name: ls_data_src_hstry ls_data_src_hstry_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_src_hstry
    ADD CONSTRAINT ls_data_src_hstry_pkey PRIMARY KEY (hstry_seq);


--
-- Name: ls_data_src ls_data_src_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_src
    ADD CONSTRAINT ls_data_src_pkey PRIMARY KEY (src_sn);


--
-- Name: ls_dataset_export ls_dataset_export_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_export
    ADD CONSTRAINT ls_dataset_export_pkey PRIMARY KEY (output_sn);


--
-- Name: ls_dataset_video_meta ls_dataset_video_meta_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_video_meta
    ADD CONSTRAINT ls_dataset_video_meta_pkey PRIMARY KEY (meta_snpsht_sn);


--
-- Name: ls_deident_proc_log ls_deident_proc_log_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_deident_proc_log
    ADD CONSTRAINT ls_deident_proc_log_pkey PRIMARY KEY (proc_log_sn);


--
-- Name: ls_deident_report ls_deident_report_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_deident_report
    ADD CONSTRAINT ls_deident_report_pkey PRIMARY KEY (deident_report_sn);


--
-- Name: ls_evnt_anno ls_evnt_anno_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_anno
    ADD CONSTRAINT ls_evnt_anno_pkey PRIMARY KEY (evnt_anno_sn);


--
-- Name: ls_evnt_anno_review ls_evnt_anno_review_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_anno_review
    ADD CONSTRAINT ls_evnt_anno_review_pkey PRIMARY KEY (rvw_sn);


--
-- Name: ls_evnt_ctgry ls_evnt_ctgry_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_ctgry
    ADD CONSTRAINT ls_evnt_ctgry_pkey PRIMARY KEY (evnt_clsf_cd, evnt_ctgry_cd);


--
-- Name: ls_evnt_type ls_evnt_type_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_type
    ADD CONSTRAINT ls_evnt_type_pkey PRIMARY KEY (evnt_type_cd);


--
-- Name: ls_issue_comment ls_issue_comment_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_issue_comment
    ADD CONSTRAINT ls_issue_comment_pkey PRIMARY KEY (cmnt_sn);


--
-- Name: ls_label_attr ls_label_attr_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_attr
    ADD CONSTRAINT ls_label_attr_pkey PRIMARY KEY (atrb_id);


--
-- Name: ls_label ls_label_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label
    ADD CONSTRAINT ls_label_pkey PRIMARY KEY (lbl_id);


--
-- Name: ls_label_preset_code ls_label_preset_code_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset_code
    ADD CONSTRAINT ls_label_preset_code_pkey PRIMARY KEY (cd_sn);


--
-- Name: ls_label_preset ls_label_preset_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset
    ADD CONSTRAINT ls_label_preset_pkey PRIMARY KEY (preset_id);


--
-- Name: ls_label_version ls_label_version_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_version
    ADD CONSTRAINT ls_label_version_pkey PRIMARY KEY (lbl_version_sn);


--
-- Name: ls_marking ls_marking_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_marking
    ADD CONSTRAINT ls_marking_pkey PRIMARY KEY (marking_sn);


--
-- Name: ls_meta_repl_outbox ls_meta_repl_outbox_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_meta_repl_outbox
    ADD CONSTRAINT ls_meta_repl_outbox_pkey PRIMARY KEY (outbox_sn);


--
-- Name: ls_mon_noti_acml ls_mon_noti_acml_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_mon_noti_acml
    ADD CONSTRAINT ls_mon_noti_acml_pkey PRIMARY KEY (noti_acml_sn);


--
-- Name: ls_notice_attach ls_notice_attach_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_notice_attach
    ADD CONSTRAINT ls_notice_attach_pkey PRIMARY KEY (atch_file_sn);


--
-- Name: ls_notice ls_notice_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_notice
    ADD CONSTRAINT ls_notice_pkey PRIMARY KEY (notice_sn);


--
-- Name: ls_output_ver_snpsh ls_output_ver_snpsh_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_output_ver_snpsh
    ADD CONSTRAINT ls_output_ver_snpsh_pkey PRIMARY KEY (output_ver_snpsh_sn);


--
-- Name: ls_portal_tus_uld ls_portal_tus_uld_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_tus_uld
    ADD CONSTRAINT ls_portal_tus_uld_pkey PRIMARY KEY (uld_id);


--
-- Name: ls_portal_uld_frme ls_portal_uld_frme_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_frme
    ADD CONSTRAINT ls_portal_uld_frme_pkey PRIMARY KEY (uld_frme_sn);


--
-- Name: ls_portal_uld_lbl ls_portal_uld_lbl_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_lbl
    ADD CONSTRAINT ls_portal_uld_lbl_pkey PRIMARY KEY (uld_lbl_sn);


--
-- Name: ls_portal_uld ls_portal_uld_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld
    ADD CONSTRAINT ls_portal_uld_pkey PRIMARY KEY (uld_sn);


--
-- Name: ls_portal_user_label ls_portal_user_label_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_user_label
    ADD CONSTRAINT ls_portal_user_label_pkey PRIMARY KEY (user_lbl_sn);


--
-- Name: ls_raw_data_status ls_raw_data_status_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_raw_data_status
    ADD CONSTRAINT ls_raw_data_status_pkey PRIMARY KEY (raw_data_id);


--
-- Name: ls_system_config ls_system_config_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_system_config
    ADD CONSTRAINT ls_system_config_pkey PRIMARY KEY (stng_key);


--
-- Name: ls_task_assignment ls_task_assignment_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_task_assignment
    ADD CONSTRAINT ls_task_assignment_pkey PRIMARY KEY (assignment_id);


--
-- Name: ls_task_event_log ls_task_event_log_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_task_event_log
    ADD CONSTRAINT ls_task_event_log_pkey PRIMARY KEY (evnt_id);


--
-- Name: ls_tus_upload ls_tus_upload_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_tus_upload
    ADD CONSTRAINT ls_tus_upload_pkey PRIMARY KEY (uld_id);


--
-- Name: ls_user_role ls_user_role_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_user_role
    ADD CONSTRAINT ls_user_role_pkey PRIMARY KEY (user_no);


--
-- Name: ls_webhook_idempotency ls_webhook_idempotency_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_webhook_idempotency
    ADD CONSTRAINT ls_webhook_idempotency_pkey PRIMARY KEY (idmp_key);


--
-- Name: ls_authrt_grant_atmpt pk_ls_authrt_grant_atmpt; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_authrt_grant_atmpt
    ADD CONSTRAINT pk_ls_authrt_grant_atmpt PRIMARY KEY (atmpt_se_cd, atmpt_idntfr, bgng_dt);


--
-- Name: ls_data_ingest pk_ls_data_ingest; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_ingest
    ADD CONSTRAINT pk_ls_data_ingest PRIMARY KEY (rcptn_sn);


--
-- Name: ls_whk_fail_nmtm pk_ls_whk_fail_nmtm; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_whk_fail_nmtm
    ADD CONSTRAINT pk_ls_whk_fail_nmtm PRIMARY KEY (call_ip_addr, bgng_dt);


--
-- Name: ls_whk_sign_use pk_ls_whk_sign_use; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_whk_sign_use
    ADD CONSTRAINT pk_ls_whk_sign_use PRIMARY KEY (sign_hash);


--
-- Name: qrtz_blob_triggers qrtz_blob_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_blob_triggers
    ADD CONSTRAINT qrtz_blob_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_calendars qrtz_calendars_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_calendars
    ADD CONSTRAINT qrtz_calendars_pkey PRIMARY KEY (sched_name, calendar_name);


--
-- Name: qrtz_cron_triggers qrtz_cron_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_cron_triggers
    ADD CONSTRAINT qrtz_cron_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_fired_triggers qrtz_fired_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_fired_triggers
    ADD CONSTRAINT qrtz_fired_triggers_pkey PRIMARY KEY (sched_name, entry_id);


--
-- Name: qrtz_job_details qrtz_job_details_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_job_details
    ADD CONSTRAINT qrtz_job_details_pkey PRIMARY KEY (sched_name, job_name, job_group);


--
-- Name: qrtz_locks qrtz_locks_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_locks
    ADD CONSTRAINT qrtz_locks_pkey PRIMARY KEY (sched_name, lock_name);


--
-- Name: qrtz_paused_trigger_grps qrtz_paused_trigger_grps_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_paused_trigger_grps
    ADD CONSTRAINT qrtz_paused_trigger_grps_pkey PRIMARY KEY (sched_name, trigger_group);


--
-- Name: qrtz_scheduler_state qrtz_scheduler_state_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_scheduler_state
    ADD CONSTRAINT qrtz_scheduler_state_pkey PRIMARY KEY (sched_name, instance_name);


--
-- Name: qrtz_simple_triggers qrtz_simple_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_simple_triggers
    ADD CONSTRAINT qrtz_simple_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers qrtz_simprop_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_simprop_triggers
    ADD CONSTRAINT qrtz_simprop_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers qrtz_triggers_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY qrtz_triggers
    ADD CONSTRAINT qrtz_triggers_pkey PRIMARY KEY (sched_name, trigger_name, trigger_group);


--
-- Name: ls_data_aug uk_aug_external_job_id; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug
    ADD CONSTRAINT uk_aug_external_job_id UNIQUE (otsd_job_id);


--
-- Name: ls_data_aug uk_aug_idempotency_key; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug
    ADD CONSTRAINT uk_aug_idempotency_key UNIQUE (idmp_key);


--
-- Name: ls_bat_rty_wtng uk_lbrw_raw_sn; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_bat_rty_wtng
    ADD CONSTRAINT uk_lbrw_raw_sn UNIQUE (raw_sn);


--
-- Name: ls_control_notify_fallback uk_lcnf_idempotency; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_control_notify_fallback
    ADD CONSTRAINT uk_lcnf_idempotency UNIQUE (idmp_key);


--
-- Name: ls_data_aug_job uk_ldaj_idmp_key; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job
    ADD CONSTRAINT uk_ldaj_idmp_key UNIQUE (idmp_key);


--
-- Name: ls_data_aug_job_file uk_ldajf_job_file_seq; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job_file
    ADD CONSTRAINT uk_ldajf_job_file_seq UNIQUE (aug_job_sn, file_seq);


--
-- Name: ls_auth_work_lock uk_ls_auth_work_lock_id; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_auth_work_lock
    ADD CONSTRAINT uk_ls_auth_work_lock_id UNIQUE (lck_id);


--
-- Name: ls_data_ingest uk_ls_data_ingest_clip; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_ingest
    ADD CONSTRAINT uk_ls_data_ingest_clip UNIQUE (vms_clip_id);


--
-- Name: ls_data_lbl_attr_val uk_ls_data_lbl_attr_val; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_attr_val
    ADD CONSTRAINT uk_ls_data_lbl_attr_val UNIQUE (lbl_sn, atrb_id);


--
-- Name: ls_data_meta uk_ls_data_meta_raw_key; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta
    ADD CONSTRAINT uk_ls_data_meta_raw_key UNIQUE (raw_sn, meta_key);


--
-- Name: ls_data_raw uk_ls_data_raw_vms_clip; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_raw
    ADD CONSTRAINT uk_ls_data_raw_vms_clip UNIQUE (vms_clip_id);


--
-- Name: ls_data_src uk_ls_data_src_raw_frame; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_src
    ADD CONSTRAINT uk_ls_data_src_raw_frame UNIQUE (raw_sn, frm_no);


--
-- Name: ls_dataset_export uk_ls_dataset_export; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_export
    ADD CONSTRAINT uk_ls_dataset_export UNIQUE (data_raw_sn, output_ver_no);


--
-- Name: ls_dataset_video_meta uk_ls_dataset_video_meta; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_video_meta
    ADD CONSTRAINT uk_ls_dataset_video_meta UNIQUE (raw_sn, snpsht_hash);


--
-- Name: ls_evnt_anno uk_ls_evnt_anno_raw; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_anno
    ADD CONSTRAINT uk_ls_evnt_anno_raw UNIQUE (raw_sn);


--
-- Name: ls_label_attr uk_ls_label_attr_name; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_attr
    ADD CONSTRAINT uk_ls_label_attr_name UNIQUE (lbl_id, atrb_nm);


--
-- Name: ls_label_preset_code uk_ls_label_preset_code; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset_code
    ADD CONSTRAINT uk_ls_label_preset_code UNIQUE (preset_id, lbl_cd);


--
-- Name: ls_label_preset uk_ls_label_preset_evnt; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset
    ADD CONSTRAINT uk_ls_label_preset_evnt UNIQUE (evnt_type_cd);


--
-- Name: ls_label_preset uk_ls_label_preset_name; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset
    ADD CONSTRAINT uk_ls_label_preset_name UNIQUE (preset_nm);


--
-- Name: ls_label_version uk_ls_label_version_src_hash; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_label_version
    ADD CONSTRAINT uk_ls_label_version_src_hash UNIQUE (data_src_sn, version_hash);


--
-- Name: ls_output_ver_snpsh uk_ls_output_ver_snpsh; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_output_ver_snpsh
    ADD CONSTRAINT uk_ls_output_ver_snpsh UNIQUE (data_raw_sn, data_src_sn, output_ver_no);


--
-- Name: ls_portal_uld_frme uk_ls_portal_uld_frme; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_frme
    ADD CONSTRAINT uk_ls_portal_uld_frme UNIQUE (uld_sn, frme_no);


--
-- Name: ls_task_assignment uk_ls_task_assignment; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_task_assignment
    ADD CONSTRAINT uk_ls_task_assignment UNIQUE (raw_data_id, user_no, task_type_cd);


--
-- Name: ls_data_meta uk_meta_external_job_id; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta
    ADD CONSTRAINT uk_meta_external_job_id UNIQUE (otsd_job_id);


--
-- Name: ls_data_meta uk_meta_idempotency_key; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta
    ADD CONSTRAINT uk_meta_idempotency_key UNIQUE (idmp_key);


--
-- Name: ls_data_meta_review uq_ls_data_meta_review_meta_type; Type: CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta_review
    ADD CONSTRAINT uq_ls_data_meta_review_meta_type UNIQUE (data_meta_sn, meta_type_cd);


--
-- Name: idx_lbrw_status; Type: INDEX
--

CREATE INDEX idx_lbrw_status ON ls_bat_rty_wtng USING btree (stts_cd, rty_prnmnt_dt);


--
-- Name: idx_lcnf_status; Type: INDEX
--

CREATE INDEX idx_lcnf_status ON ls_control_notify_fallback USING btree (stts_cd, next_rtry_dt);


--
-- Name: idx_ldaj_aug_seq; Type: INDEX
--

CREATE INDEX idx_ldaj_aug_seq ON ls_data_aug_job USING btree (data_aug_sn, job_seq);


--
-- Name: idx_ldaj_otsd_job_id; Type: INDEX
--

CREATE INDEX idx_ldaj_otsd_job_id ON ls_data_aug_job USING btree (otsd_job_id);


--
-- Name: idx_ldajf_src_sn; Type: INDEX
--

CREATE INDEX idx_ldajf_src_sn ON ls_data_aug_job_file USING btree (src_sn);


--
-- Name: idx_ldlh_src; Type: INDEX
--

CREATE INDEX idx_ldlh_src ON ls_data_lbl_hstry USING btree (src_sn, reg_dt DESC);


--
-- Name: idx_ldr_orgnl; Type: INDEX
--

CREATE INDEX idx_ldr_orgnl ON ls_data_raw USING btree (orgnl_raw_sn);


--
-- Name: idx_lm_raw; Type: INDEX
--

CREATE INDEX idx_lm_raw ON ls_marking USING btree (raw_sn);


--
-- Name: idx_lmna_stts_mdfcn; Type: INDEX
--

CREATE INDEX idx_lmna_stts_mdfcn ON ls_mon_noti_acml USING btree (stts_cd, mdfcn_dt);


--
-- Name: idx_lmna_stts_reg; Type: INDEX
--

CREATE INDEX idx_lmna_stts_reg ON ls_mon_noti_acml USING btree (stts_cd, reg_dt);


--
-- Name: idx_lnt_pub; Type: INDEX
--

CREATE INDEX idx_lnt_pub ON ls_notice USING btree (pblcn_stts_cd, upend_fix_yn, reg_dt DESC);


--
-- Name: idx_lnta_notice; Type: INDEX
--

CREATE INDEX idx_lnta_notice ON ls_notice_attach USING btree (notice_sn);


--
-- Name: idx_lptu_expry; Type: INDEX
--

CREATE INDEX idx_lptu_expry ON ls_portal_tus_uld USING btree (expry_dt) WHERE ((stts_cd)::text = 'IN_PROGRESS'::text);


--
-- Name: idx_lptu_user_stts; Type: INDEX
--

CREATE INDEX idx_lptu_user_stts ON ls_portal_tus_uld USING btree (portal_user_no) WHERE ((stts_cd)::text = 'IN_PROGRESS'::text);


--
-- Name: idx_lpul_src; Type: INDEX
--

CREATE INDEX idx_lpul_src ON ls_portal_user_label USING btree (portal_user_no, src_data_src_sn);


--
-- Name: idx_lpul_user; Type: INDEX
--

CREATE INDEX idx_lpul_user ON ls_portal_user_label USING btree (portal_user_no, src_raw_sn);


--
-- Name: idx_ls_auth_work_lock_stts; Type: INDEX
--

CREATE INDEX idx_ls_auth_work_lock_stts ON ls_auth_work_lock USING btree (lck_stts_cd, expry_dt);


--
-- Name: idx_ls_auth_work_lock_target; Type: INDEX
--

CREATE INDEX idx_ls_auth_work_lock_target ON ls_auth_work_lock USING btree (lck_target_cd, data_raw_sn, data_src_sn);


--
-- Name: idx_ls_authrt_grant_atmpt_expd; Type: INDEX
--

CREATE INDEX idx_ls_authrt_grant_atmpt_expd ON ls_authrt_grant_atmpt USING btree (expd_dt);


--
-- Name: idx_ls_batch_proc_log_job; Type: INDEX
--

CREATE INDEX idx_ls_batch_proc_log_job ON ls_batch_proc_log USING btree (job_id);


--
-- Name: idx_ls_batch_proc_log_raw; Type: INDEX
--

CREATE INDEX idx_ls_batch_proc_log_raw ON ls_batch_proc_log USING btree (data_raw_sn);


--
-- Name: idx_ls_batch_proc_log_src; Type: INDEX
--

CREATE INDEX idx_ls_batch_proc_log_src ON ls_batch_proc_log USING btree (data_src_sn);


--
-- Name: idx_ls_batch_proc_log_step_stts; Type: INDEX
--

CREATE INDEX idx_ls_batch_proc_log_step_stts ON ls_batch_proc_log USING btree (proc_step_cd, proc_stts_cd);


--
-- Name: idx_ls_data_aug_lbl_map_aug; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_lbl_map_aug ON ls_data_aug_lbl_map USING btree (data_aug_sn);


--
-- Name: idx_ls_data_aug_lbl_map_lbl; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_lbl_map_lbl ON ls_data_aug_lbl_map USING btree (data_lbl_sn);


--
-- Name: idx_ls_data_aug_lbl_map_orgnl; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_lbl_map_orgnl ON ls_data_aug_lbl_map USING btree (orgnl_data_lbl_sn);


--
-- Name: idx_ls_data_aug_rvw_aug; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_rvw_aug ON ls_data_aug_rvw USING btree (data_aug_sn);


--
-- Name: idx_ls_data_aug_rvw_stts; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_rvw_stts ON ls_data_aug_rvw USING btree (rvw_stts_cd);


--
-- Name: idx_ls_data_aug_rvw_target; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_rvw_target ON ls_data_aug_rvw USING btree (data_raw_sn, data_src_sn);


--
-- Name: idx_ls_data_aug_src; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_src ON ls_data_aug USING btree (src_sn, aug_type_cd);


--
-- Name: idx_ls_data_aug_src_regdt; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_src_regdt ON ls_data_aug USING btree (src_sn, reg_dt);


--
-- Name: idx_ls_data_aug_stts; Type: INDEX
--

CREATE INDEX idx_ls_data_aug_stts ON ls_data_aug USING btree (aug_proc_stts_cd, reg_dt);


--
-- Name: idx_ls_data_lbl_ai_info_lbl; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_ai_info_lbl ON ls_data_lbl_ai_info USING btree (data_lbl_sn);


--
-- Name: idx_ls_data_lbl_ai_info_lbl_auto; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_ai_info_lbl_auto ON ls_data_lbl_ai_info USING btree (data_lbl_sn, auto_lbl_yn);


--
-- Name: idx_ls_data_lbl_ai_info_src; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_ai_info_src ON ls_data_lbl_ai_info USING btree (data_raw_sn, data_src_sn);


--
-- Name: idx_ls_data_lbl_ai_info_src_cd; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_ai_info_src_cd ON ls_data_lbl_ai_info USING btree (lbl_src_cd);


--
-- Name: idx_ls_data_lbl_attr_val_lbl; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_attr_val_lbl ON ls_data_lbl_attr_val USING btree (lbl_sn);


--
-- Name: idx_ls_data_lbl_label_id; Type: INDEX
--

CREATE INDEX idx_ls_data_lbl_label_id ON ls_data_lbl USING btree (lbl_id);


--
-- Name: idx_ls_data_meta_review_meta; Type: INDEX
--

CREATE INDEX idx_ls_data_meta_review_meta ON ls_data_meta_review USING btree (data_meta_sn);


--
-- Name: idx_ls_data_meta_review_stts; Type: INDEX
--

CREATE INDEX idx_ls_data_meta_review_stts ON ls_data_meta_review USING btree (meta_type_cd, rvw_stts_cd);


--
-- Name: idx_ls_data_meta_review_target; Type: INDEX
--

CREATE INDEX idx_ls_data_meta_review_target ON ls_data_meta_review USING btree (data_raw_sn, data_src_sn);


--
-- Name: idx_ls_dataset_video_meta_raw_active; Type: INDEX
--

CREATE INDEX idx_ls_dataset_video_meta_raw_active ON ls_dataset_video_meta USING btree (raw_sn, active_yn);


--
-- Name: idx_ls_dataset_video_meta_rvw_cmpl; Type: INDEX
--

CREATE INDEX idx_ls_dataset_video_meta_rvw_cmpl ON ls_dataset_video_meta USING btree (rvw_cmpl_dt);


--
-- Name: idx_ls_deident_proc_log_raw; Type: INDEX
--

CREATE INDEX idx_ls_deident_proc_log_raw ON ls_deident_proc_log USING btree (data_raw_sn);


--
-- Name: idx_ls_deident_proc_log_raw_log; Type: INDEX
--

CREATE INDEX idx_ls_deident_proc_log_raw_log ON ls_deident_proc_log USING btree (data_raw_sn, proc_log_sn);


--
-- Name: idx_ls_deident_proc_log_stts; Type: INDEX
--

CREATE INDEX idx_ls_deident_proc_log_stts ON ls_deident_proc_log USING btree (proc_stts_cd);


--
-- Name: idx_ls_deident_report_raw; Type: INDEX
--

CREATE INDEX idx_ls_deident_report_raw ON ls_deident_report USING btree (data_raw_sn);


--
-- Name: idx_ls_deident_report_report; Type: INDEX
--

CREATE INDEX idx_ls_deident_report_report ON ls_deident_report USING btree (report_stts_cd, dclr_dt);


--
-- Name: idx_ls_evnt_anno_review_anno; Type: INDEX
--

CREATE INDEX idx_ls_evnt_anno_review_anno ON ls_evnt_anno_review USING btree (evnt_anno_sn);


--
-- Name: idx_ls_evnt_anno_review_stts; Type: INDEX
--

CREATE INDEX idx_ls_evnt_anno_review_stts ON ls_evnt_anno_review USING btree (meta_type_cd, rvw_stts_cd);


--
-- Name: idx_ls_label_attr; Type: INDEX
--

CREATE INDEX idx_ls_label_attr ON ls_label_attr USING btree (lbl_id, use_yn, sort_seq);


--
-- Name: idx_ls_label_preset_code_label; Type: INDEX
--

CREATE INDEX idx_ls_label_preset_code_label ON ls_label_preset_code USING btree (lbl_id);


--
-- Name: idx_ls_label_preset_code_preset; Type: INDEX
--

CREATE INDEX idx_ls_label_preset_code_preset ON ls_label_preset_code USING btree (preset_id);


--
-- Name: idx_ls_label_use; Type: INDEX
--

CREATE INDEX idx_ls_label_use ON ls_label USING btree (use_yn, sort_seq);


--
-- Name: idx_ls_label_version_target; Type: INDEX
--

CREATE INDEX idx_ls_label_version_target ON ls_label_version USING btree (data_raw_sn, data_src_sn, actvtn_yn);


--
-- Name: idx_ls_meta_repl_outbox_status; Type: INDEX
--

CREATE INDEX idx_ls_meta_repl_outbox_status ON ls_meta_repl_outbox USING btree (status, reg_dt);


--
-- Name: idx_ls_output_ver_snpsh_lbl_ver; Type: INDEX
--

CREATE INDEX idx_ls_output_ver_snpsh_lbl_ver ON ls_output_ver_snpsh USING btree (lbl_ver_sn);


--
-- Name: idx_ls_portal_uld_lbl_frme; Type: INDEX
--

CREATE INDEX idx_ls_portal_uld_lbl_frme ON ls_portal_uld_lbl USING btree (uld_frme_sn, portal_user_no);


--
-- Name: idx_ls_portal_uld_lbl_user_uld; Type: INDEX
--

CREATE INDEX idx_ls_portal_uld_lbl_user_uld ON ls_portal_uld_lbl USING btree (portal_user_no, uld_sn);


--
-- Name: idx_ls_portal_uld_stts_mdfcn; Type: INDEX
--

CREATE INDEX idx_ls_portal_uld_stts_mdfcn ON ls_portal_uld USING btree (uld_stts_cd, mdfcn_dt);


--
-- Name: idx_ls_portal_uld_user_reg; Type: INDEX
--

CREATE INDEX idx_ls_portal_uld_user_reg ON ls_portal_uld USING btree (portal_user_no, reg_dt);


--
-- Name: idx_ls_webhook_idempotency_ch_state; Type: INDEX
--

CREATE INDEX idx_ls_webhook_idempotency_ch_state ON ls_webhook_idempotency USING btree (chnl_cd, stts_cd);


--
-- Name: idx_ls_webhook_idempotency_ext_job; Type: INDEX
--

CREATE INDEX idx_ls_webhook_idempotency_ext_job ON ls_webhook_idempotency USING btree (otsd_job_id);


--
-- Name: idx_ls_whk_fail_nmtm_expd; Type: INDEX
--

CREATE INDEX idx_ls_whk_fail_nmtm_expd ON ls_whk_fail_nmtm USING btree (expd_dt);


--
-- Name: idx_ls_whk_sign_use_expd; Type: INDEX
--

CREATE INDEX idx_ls_whk_sign_use_expd ON ls_whk_sign_use USING btree (expd_dt);


--
-- Name: idx_ltu_expires; Type: INDEX
--

CREATE INDEX idx_ltu_expires ON ls_tus_upload USING btree (stts_cd, expry_dt);


--
-- Name: idx_ltu_user_status; Type: INDEX
--

CREATE INDEX idx_ltu_user_status ON ls_tus_upload USING btree (user_no, stts_cd);


--
-- Name: ix_ldpl_poll_stts; Type: INDEX
--

CREATE INDEX ix_ldpl_poll_stts ON ls_deident_proc_log USING btree (poll_stts_cd);


--
-- Name: ix_ls_clip_schedule_que_raw; Type: INDEX
--

CREATE INDEX ix_ls_clip_schedule_que_raw ON ls_clip_schedule_que USING btree (raw_sn);


--
-- Name: ix_ls_clip_schedule_que_status; Type: INDEX
--

CREATE INDEX ix_ls_clip_schedule_que_status ON ls_clip_schedule_que USING btree (status, job_type);


--
-- Name: ix_ls_data_aug_dscd_file_rty; Type: INDEX
--

CREATE INDEX ix_ls_data_aug_dscd_file_rty ON ls_data_aug_dscd USING btree (del_dt) WHERE ((del_dt IS NOT NULL) AND (file_del_dt IS NULL) AND (file_del_fail_dt IS NULL));


--
-- Name: ix_ls_data_aug_dscd_lookup; Type: INDEX
--

CREATE INDEX ix_ls_data_aug_dscd_lookup ON ls_data_aug_dscd USING btree (data_aug_sn, data_aug_dscd_sn DESC);


--
-- Name: ix_ls_data_aug_dscd_new_raw; Type: INDEX
--

CREATE INDEX ix_ls_data_aug_dscd_new_raw ON ls_data_aug_dscd USING btree (new_raw_sn);


--
-- Name: ix_ls_data_aug_dscd_sweep; Type: INDEX
--

CREATE INDEX ix_ls_data_aug_dscd_sweep ON ls_data_aug_dscd USING btree (dscd_dt) WHERE ((rstr_dt IS NULL) AND (del_dt IS NULL));


--
-- Name: ix_ls_data_aug_new_raw_sn; Type: INDEX
--

CREATE INDEX ix_ls_data_aug_new_raw_sn ON ls_data_aug USING btree (new_raw_sn) WHERE (new_raw_sn IS NOT NULL);


--
-- Name: ix_ls_data_ingest_poll; Type: INDEX
--

CREATE INDEX ix_ls_data_ingest_poll ON ls_data_ingest USING btree (rcptn_dt, rcptn_sn) INCLUDE (nxtm_rtry_dt) WHERE ((prcs_stts_cd)::text = 'PENDING'::text);


--
-- Name: ix_ls_data_ingest_raw_sn; Type: INDEX
--

CREATE INDEX ix_ls_data_ingest_raw_sn ON ls_data_ingest USING btree (raw_sn, rcptn_sn DESC);


--
-- Name: ix_ls_data_issue_reporter; Type: INDEX
--

CREATE INDEX ix_ls_data_issue_reporter ON ls_data_issue USING btree (reported_user_no);


--
-- Name: ix_ls_data_issue_src; Type: INDEX
--

CREATE INDEX ix_ls_data_issue_src ON ls_data_issue USING btree (src_sn) WHERE (src_sn IS NOT NULL);


--
-- Name: ix_ls_data_issue_up; Type: INDEX
--

CREATE INDEX ix_ls_data_issue_up ON ls_data_issue USING btree (up_data_issue_sn);


--
-- Name: ix_ls_data_issue_video; Type: INDEX
--

CREATE INDEX ix_ls_data_issue_video ON ls_data_issue USING btree (data_raw_sn);


--
-- Name: ix_ls_data_lbl_src; Type: INDEX
--

CREATE INDEX ix_ls_data_lbl_src ON ls_data_lbl USING btree (src_sn);


--
-- Name: ix_ls_data_lbl_trck_id; Type: INDEX
--

CREATE INDEX ix_ls_data_lbl_trck_id ON ls_data_lbl USING btree (trck_id);


--
-- Name: ix_ls_data_meta_raw; Type: INDEX
--

CREATE INDEX ix_ls_data_meta_raw ON ls_data_meta USING btree (raw_sn);


--
-- Name: ix_ls_data_raw_cctv; Type: INDEX
--

CREATE INDEX ix_ls_data_raw_cctv ON ls_data_raw USING btree (vms_cctv_id);


--
-- Name: ix_ls_data_raw_stts; Type: INDEX
--

CREATE INDEX ix_ls_data_raw_stts ON ls_data_raw USING btree (data_stts_cd);


--
-- Name: ix_ls_data_src_hstry_src; Type: INDEX
--

CREATE INDEX ix_ls_data_src_hstry_src ON ls_data_src_hstry USING btree (src_sn);


--
-- Name: ix_ls_data_src_raw; Type: INDEX
--

CREATE INDEX ix_ls_data_src_raw ON ls_data_src USING btree (raw_sn);


--
-- Name: ix_ls_issue_comment_author; Type: INDEX
--

CREATE INDEX ix_ls_issue_comment_author ON ls_issue_comment USING btree (author_no);


--
-- Name: ix_ls_issue_comment_issue; Type: INDEX
--

CREATE INDEX ix_ls_issue_comment_issue ON ls_issue_comment USING btree (data_issue_sn);


--
-- Name: ix_ls_raw_data_status_stts_upd; Type: INDEX
--

CREATE INDEX ix_ls_raw_data_status_stts_upd ON ls_raw_data_status USING btree (data_stts_cd, upd_dt DESC);


--
-- Name: ix_ls_task_assignment_raw; Type: INDEX
--

CREATE INDEX ix_ls_task_assignment_raw ON ls_task_assignment USING btree (raw_data_id);


--
-- Name: ix_ls_task_assignment_user; Type: INDEX
--

CREATE INDEX ix_ls_task_assignment_user ON ls_task_assignment USING btree (user_no, task_type_cd);


--
-- Name: ix_ls_task_event_log_actor; Type: INDEX
--

CREATE INDEX ix_ls_task_event_log_actor ON ls_task_event_log USING btree (actor_user_no);


--
-- Name: ix_ls_task_event_log_raw; Type: INDEX
--

CREATE INDEX ix_ls_task_event_log_raw ON ls_task_event_log USING btree (raw_data_id, ocrn_dt);


--
-- Name: uk_lmna_raw_pending; Type: INDEX
--

CREATE UNIQUE INDEX uk_lmna_raw_pending ON ls_mon_noti_acml USING btree (raw_sn) WHERE ((stts_cd)::text = 'PENDING'::text);


--
-- Name: uk_ls_data_aug_dscd_actvtn; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_data_aug_dscd_actvtn ON ls_data_aug_dscd USING btree (data_aug_sn) WHERE ((rstr_dt IS NULL) AND (del_dt IS NULL));


--
-- Name: uk_ls_data_aug_resl; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_data_aug_resl ON ls_data_aug USING btree (src_sn, aug_type_cd) WHERE ((aug_type_cd)::text ~~ 'RESL\_%'::text);


--
-- Name: uk_ls_dataset_video_meta_raw_active; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_dataset_video_meta_raw_active ON ls_dataset_video_meta USING btree (raw_sn) WHERE (active_yn = 'Y'::bpchar);


--
-- Name: uk_ls_deident_proc_log_ext_job; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_deident_proc_log_ext_job ON ls_deident_proc_log USING btree (otsd_job_id);


--
-- Name: uk_ls_label_dtct_type; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_label_dtct_type ON ls_label USING btree (dtct_type_cd) WHERE ((use_yn = 'Y'::bpchar) AND (dtct_type_cd IS NOT NULL));


--
-- Name: uk_ls_label_nm_ci; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_label_nm_ci ON ls_label USING btree (lower(TRIM(BOTH FROM lbl_nm))) WHERE (use_yn = 'Y'::bpchar);


--
-- Name: uk_ls_label_preset_code_lblid; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_label_preset_code_lblid ON ls_label_preset_code USING btree (preset_id, lbl_id) WHERE (lbl_id IS NOT NULL);


--
-- Name: uk_ls_marking_raw_actvtn; Type: INDEX
--

CREATE UNIQUE INDEX uk_ls_marking_raw_actvtn ON ls_marking USING btree (raw_sn) WHERE (stts_cd IN ('PENDING', 'VLM_REQUESTED'));


--
-- Name: ux_ls_auth_work_lock_raw_active; Type: INDEX
--

CREATE UNIQUE INDEX ux_ls_auth_work_lock_raw_active ON ls_auth_work_lock USING btree (data_raw_sn) WHERE (((lck_target_cd)::text = 'RAW'::text) AND ((lck_stts_cd)::text = 'LOCKED'::text));


--
-- Name: ls_data_aug_job fk_ldaj_data_aug; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job
    ADD CONSTRAINT fk_ldaj_data_aug FOREIGN KEY (data_aug_sn) REFERENCES ls_data_aug(data_aug_sn) ON DELETE CASCADE;


--
-- Name: ls_data_aug_job_file fk_ldajf_aug_job; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_job_file
    ADD CONSTRAINT fk_ldajf_aug_job FOREIGN KEY (aug_job_sn) REFERENCES ls_data_aug_job(aug_job_sn) ON DELETE CASCADE;


--
-- Name: ls_notice_attach fk_lnta_notice; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_notice_attach
    ADD CONSTRAINT fk_lnta_notice FOREIGN KEY (notice_sn) REFERENCES ls_notice(notice_sn) ON DELETE CASCADE;


--
-- Name: ls_auth_work_lock fk_ls_auth_work_lock_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_auth_work_lock
    ADD CONSTRAINT fk_ls_auth_work_lock_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_bat_rty_wtng fk_ls_bat_rty_wtng_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_bat_rty_wtng
    ADD CONSTRAINT fk_ls_bat_rty_wtng_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_batch_proc_log fk_ls_batch_proc_log_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_batch_proc_log
    ADD CONSTRAINT fk_ls_batch_proc_log_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_clip_schedule_que fk_ls_clip_schedule_que_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_clip_schedule_que
    ADD CONSTRAINT fk_ls_clip_schedule_que_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_control_notify_fallback fk_ls_control_notify_fallback_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_control_notify_fallback
    ADD CONSTRAINT fk_ls_control_notify_fallback_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_aug_rvw fk_ls_data_aug_rvw_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_aug_rvw
    ADD CONSTRAINT fk_ls_data_aug_rvw_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_issue fk_ls_data_issue_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_issue
    ADD CONSTRAINT fk_ls_data_issue_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_lbl_ai_info fk_ls_data_lbl_ai_info_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_ai_info
    ADD CONSTRAINT fk_ls_data_lbl_ai_info_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_lbl_attr_val fk_ls_data_lbl_attr_attr; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_attr_val
    ADD CONSTRAINT fk_ls_data_lbl_attr_attr FOREIGN KEY (atrb_id) REFERENCES ls_label_attr(atrb_id);


--
-- Name: ls_data_lbl_attr_val fk_ls_data_lbl_attr_lbl; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl_attr_val
    ADD CONSTRAINT fk_ls_data_lbl_attr_lbl FOREIGN KEY (lbl_sn) REFERENCES ls_data_lbl(lbl_sn);


--
-- Name: ls_data_lbl fk_ls_data_lbl_label; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_lbl
    ADD CONSTRAINT fk_ls_data_lbl_label FOREIGN KEY (lbl_id) REFERENCES ls_label(lbl_id);


--
-- Name: ls_data_meta fk_ls_data_meta_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta
    ADD CONSTRAINT fk_ls_data_meta_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_meta_review fk_ls_data_meta_review_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_meta_review
    ADD CONSTRAINT fk_ls_data_meta_review_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_data_src fk_ls_data_src_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_data_src
    ADD CONSTRAINT fk_ls_data_src_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_dataset_export fk_ls_dataset_export_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_export
    ADD CONSTRAINT fk_ls_dataset_export_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_dataset_video_meta fk_ls_dataset_video_meta_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_dataset_video_meta
    ADD CONSTRAINT fk_ls_dataset_video_meta_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_deident_proc_log fk_ls_deident_proc_log_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_deident_proc_log
    ADD CONSTRAINT fk_ls_deident_proc_log_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_deident_report fk_ls_deident_report_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_deident_report
    ADD CONSTRAINT fk_ls_deident_report_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_evnt_anno fk_ls_evnt_anno_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_anno
    ADD CONSTRAINT fk_ls_evnt_anno_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_evnt_anno_review fk_ls_evnt_anno_review_anno; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_evnt_anno_review
    ADD CONSTRAINT fk_ls_evnt_anno_review_anno FOREIGN KEY (evnt_anno_sn) REFERENCES ls_evnt_anno(evnt_anno_sn);


--
-- Name: ls_label_attr fk_ls_label_attr_label; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_label_attr
    ADD CONSTRAINT fk_ls_label_attr_label FOREIGN KEY (lbl_id) REFERENCES ls_label(lbl_id);


--
-- Name: ls_label_preset_code fk_ls_label_preset_code_label; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset_code
    ADD CONSTRAINT fk_ls_label_preset_code_label FOREIGN KEY (lbl_id) REFERENCES ls_label(lbl_id);


--
-- Name: ls_label_preset_code fk_ls_label_preset_code_preset; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_label_preset_code
    ADD CONSTRAINT fk_ls_label_preset_code_preset FOREIGN KEY (preset_id) REFERENCES ls_label_preset(preset_id) ON DELETE CASCADE;


--
-- Name: ls_label_version fk_ls_label_version_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_label_version
    ADD CONSTRAINT fk_ls_label_version_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_marking fk_ls_marking_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_marking
    ADD CONSTRAINT fk_ls_marking_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_meta_repl_outbox fk_ls_meta_repl_outbox_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_meta_repl_outbox
    ADD CONSTRAINT fk_ls_meta_repl_outbox_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_mon_noti_acml fk_ls_mon_noti_acml_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_mon_noti_acml
    ADD CONSTRAINT fk_ls_mon_noti_acml_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_output_ver_snpsh fk_ls_output_ver_snpsh_lbl_ver; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_output_ver_snpsh
    ADD CONSTRAINT fk_ls_output_ver_snpsh_lbl_ver FOREIGN KEY (lbl_ver_sn) REFERENCES ls_label_version(lbl_version_sn) ON DELETE CASCADE;


--
-- Name: ls_output_ver_snpsh fk_ls_output_ver_snpsh_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_output_ver_snpsh
    ADD CONSTRAINT fk_ls_output_ver_snpsh_raw FOREIGN KEY (data_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_frme fk_ls_portal_uld_frme_uld; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_frme
    ADD CONSTRAINT fk_ls_portal_uld_frme_uld FOREIGN KEY (uld_sn) REFERENCES ls_portal_uld(uld_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_lbl fk_ls_portal_uld_lbl_frme; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_lbl
    ADD CONSTRAINT fk_ls_portal_uld_lbl_frme FOREIGN KEY (uld_frme_sn) REFERENCES ls_portal_uld_frme(uld_frme_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_uld_lbl fk_ls_portal_uld_lbl_uld; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_portal_uld_lbl
    ADD CONSTRAINT fk_ls_portal_uld_lbl_uld FOREIGN KEY (uld_sn) REFERENCES ls_portal_uld(uld_sn) ON DELETE CASCADE;


--
-- Name: ls_portal_user_label fk_ls_portal_user_label_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_portal_user_label
    ADD CONSTRAINT fk_ls_portal_user_label_raw FOREIGN KEY (src_raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_raw_data_status fk_ls_raw_data_status_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_raw_data_status
    ADD CONSTRAINT fk_ls_raw_data_status_raw FOREIGN KEY (raw_data_id) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_task_assignment fk_ls_task_assignment_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_task_assignment
    ADD CONSTRAINT fk_ls_task_assignment_raw FOREIGN KEY (raw_data_id) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_task_event_log fk_ls_task_event_log_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_task_event_log
    ADD CONSTRAINT fk_ls_task_event_log_raw FOREIGN KEY (raw_data_id) REFERENCES ls_data_raw(raw_sn) ON DELETE CASCADE;


--
-- Name: ls_tus_upload fk_ls_tus_upload_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_tus_upload
    ADD CONSTRAINT fk_ls_tus_upload_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: ls_webhook_idempotency fk_ls_webhook_idempotency_raw; Type: FK CONSTRAINT
--

ALTER TABLE ONLY ls_webhook_idempotency
    ADD CONSTRAINT fk_ls_webhook_idempotency_raw FOREIGN KEY (raw_sn) REFERENCES ls_data_raw(raw_sn) ON DELETE SET NULL;


--
-- Name: qrtz_blob_triggers fk_qrtz_blob_triggers; Type: FK CONSTRAINT
--

ALTER TABLE ONLY qrtz_blob_triggers
    ADD CONSTRAINT fk_qrtz_blob_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_cron_triggers fk_qrtz_cron_triggers; Type: FK CONSTRAINT
--

ALTER TABLE ONLY qrtz_cron_triggers
    ADD CONSTRAINT fk_qrtz_cron_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simple_triggers fk_qrtz_simple_triggers; Type: FK CONSTRAINT
--

ALTER TABLE ONLY qrtz_simple_triggers
    ADD CONSTRAINT fk_qrtz_simple_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_simprop_triggers fk_qrtz_simprop_triggers; Type: FK CONSTRAINT
--

ALTER TABLE ONLY qrtz_simprop_triggers
    ADD CONSTRAINT fk_qrtz_simprop_triggers FOREIGN KEY (sched_name, trigger_name, trigger_group) REFERENCES qrtz_triggers(sched_name, trigger_name, trigger_group);


--
-- Name: qrtz_triggers fk_qrtz_triggers_job_details; Type: FK CONSTRAINT
--

ALTER TABLE ONLY qrtz_triggers
    ADD CONSTRAINT fk_qrtz_triggers_job_details FOREIGN KEY (sched_name, job_name, job_group) REFERENCES qrtz_job_details(sched_name, job_name, job_group);


-- ============================================================================
-- 시드 데이터 — 180개 마이그레이션 적용 결과에 실제로 존재하던 전량에서 <제거분을 뺀> 2테이블 14행.
--   ls_system_config 12 / qrtz_locks 2. 그 밖의 테이블은 시드가 없다
--   (라벨 마스터 LS_LABEL 등은 운영자가 화면에서 등록하는 축이라 마이그레이션 시드가 없다).
--   ⚠ 구 ls_com_cd 5행(DATA_STTS_CD 상태코드)은 <기록된 예외 2건>으로 함께 덜어냈다 — 아무도 읽지
--     않는데 LsRawDataStatus 자바 상수와 같은 값을 한 벌 더 들고 있던 두 번째 진실원이었다.
-- ============================================================================

-- ls_system_config (12행)
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('BATCH_INTERVAL_SEC', '60', 'NUMBER', '배치 트리거 간격 (초, 10~3600)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('BATCH_CONCURRENCY', '1', 'NUMBER', '동시 배치 잡 수 (1=직렬, 1~10)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('YOLO_IMGSZ', '1280', 'NUMBER', 'YOLO 추론 입력 해상도 px (320~1920)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('YOLO_IOU', '50', 'NUMBER', 'YOLO NMS IoU 임계값 백분율 (30~80, 사용 시 /100)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('YOLO_CONF_THRESHOLD', '25', 'NUMBER', 'YOLO 신뢰도 임계값 백분율 (25~80, 사용 시 /100)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('POLYGON_SIMPLIFY_TOLERANCE', '1.0', 'DECIMAL', '폴리곤 경계 단순화 epsilon px (0.0~50.0, Douglas-Peucker)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('portal.upload.frame-interval-sec', '5', 'NUMBER', '포털 업로드 영상 프레임 추출 간격(초, 1~600)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('autolabel.polygon.max-boxes', '20', 'NUMBER', '폴리곤 오토라벨 SAM 분할 박스 상한 (1~100)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('eventtype.excluded-class-codes', '["08"]', 'JSON', '이벤트 필터 옵션에서 제외할 대분류 코드 목록(기본 08=배회)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('kpst.deid.masking-type', '0', 'NUMBER', '비식별 마스킹 방식 (0 색상 / 2 모자이크 / 3 블러)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('kpst.deid.masking-range', '1.0', 'DECIMAL', '비식별 마스킹 영역 배율 (0.5~2.0)', 'SYSTEM');
INSERT INTO ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id) VALUES ('kpst.deid.db-save', '0', 'NUMBER', '비식별 처리 프레임 저장 여부 (0 저장 안 함 / 1 저장)', 'SYSTEM');

-- qrtz_locks (2행)
INSERT INTO qrtz_locks (sched_name, lock_name) VALUES ('KlidAuthoringScheduler', 'TRIGGER_ACCESS');
INSERT INTO qrtz_locks (sched_name, lock_name) VALUES ('KlidAuthoringScheduler', 'STATE_ACCESS');
