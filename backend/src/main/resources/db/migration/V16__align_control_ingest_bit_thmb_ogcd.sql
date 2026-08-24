-- =============================================================================
-- V16: 관제 인입 규격 3건 정정 (관제 2026-08-24 질의 확정)
--
-- 1) LS_DATA_INGEST.BIT COMMENT 재지정  — 색심도 → 비트레이트(bps)
-- 2) LS_DATA_INGEST.THMB_FILE_PATH_NM 신설  — 썸네일 경로(관제 pass-through)
-- 3) LS_DATA_INGEST.OG_CD 재추가  — 관제 "실보유" 재확인 (V185 제거분 복원)
-- 4) V_COMPLETED_VIDEO 뷰 재정의  — THMB_FILE_PATH_NM 노출(완료 조회 채널)
--
-- 설계 진실원: LogiCraft ERD-012 v36 (영상·프레임 수집 ERD).  @design ERD-012
-- 우리 소유 LS_* 테이블(관제 MNG_* 아님) — 스키마 협의 불요. PostgreSQL 표준 문법.
-- 스키마 무한정(unqualified) 식별자 — search_path(klid_at) 를 따른다. 'public.' 리터럴 금지.
--
-- -----------------------------------------------------------------------------
-- 1) BIT 의미 재정의 (색심도 → 비트레이트)
--
-- 왜: 규격서·엔티티 Javadoc·마이그레이션 코멘트가 BIT 을 '색심도(24bit)'로 서술해 왔으나,
--     관제 실DB(PostgreSQL) 실측값은 '24bit' 표기가 아니라 2050627 같은 bps 정수였다
--     (2026-07-31 관측, 당시 결론 보류). 관제팀이 2026-08-24 이 값이 비트레이트임을 독립
--     확인해 재정의를 확정한다. 물리명 BIT·타입 varchar(20)은 유지 — 관제 합의 물리명이고
--     비트레이트 숫자 문자열도 20자에 충분히 수용된다(재명명 비용 회피).
--
-- 컬럼명 인용 주의: 'bit' 은 PostgreSQL 예약어(비트열 타입)라 baseline(V1) 이 pg_dump 산출로
--     "bit" 로 인용해 생성했다. COMMENT 대상도 동일하게 소문자 인용 "bit" 로 지목한다.
--
COMMENT ON COLUMN LS_DATA_INGEST."bit" IS '비트레이트(bps 단위 숫자, 관제 video.bit). 예 2050627. 색심도가 아니다 — 관제 실측값이 색심도 표기(''24bit'')가 아니라 bps 정수임이 확인됐다(2026-08-24 관제 재확인). PostgreSQL 에서 컬럼명으로는 예약어라 baseline 이 인용 생성했다.';

-- -----------------------------------------------------------------------------
-- 2) THMB_FILE_PATH_NM 신설 (썸네일 경로 — 관제 pass-through)
--
-- 왜: 관제가 저작도구 산출 학습데이터를 패키징할 때 썸네일이 필요한데 klid_at 스키마에
--     썸네일 계열 컬럼이 없어 원본 영상 테이블을 별도 조인해야 했다. 이 컬럼을 두고 아래 4)
--     에서 V_COMPLETED_VIDEO 로 노출하면 관제가 완료 조회 뷰 하나로 조달한다.
--     값은 관제 인입값 pass-through — 저작도구가 생성·가공하지 않는다. nullable(미송신 허용).
-- 표준용어: 썸네일=THMB(공통표준단어 6차) · 파일=FILE · 경로=PATH · 명=NM. 신규 등록 0건.
--     접미어는 같은 테이블 RAW_FILE_PATH_NM 과 통일(도메인 경로명V500).
--
ALTER TABLE LS_DATA_INGEST ADD COLUMN IF NOT EXISTS THMB_FILE_PATH_NM VARCHAR(500);
COMMENT ON COLUMN LS_DATA_INGEST.THMB_FILE_PATH_NM IS '썸네일 이미지 파일 경로명(관제 인입값 pass-through). 관제 학습데이터 패키징에 사용하며 저작도구는 생성·가공하지 않는다. V_COMPLETED_VIDEO 로 노출한다. nullable — 미송신 시 인입 실패하지 않는다.';

-- -----------------------------------------------------------------------------
-- 3) OG_CD 재추가 (관제 실보유 재확인)
--
-- 왜: V185 가 관제 "현행 미사용·공급 불가" 요청으로 제거했던 컬럼인데, 관제팀이 2026-08-24
--     "실제로는 보유한다"고 재확인해 되살린다. V185 롤백 SQL 과 동일한 정의·코멘트.
--     ⚠ V185 DROP 시점에 값이 소실됐다 — 재추가 이후 신규 인입 행부터 채워진다(과거 복구 불가).
--     export JSON 배선은 하지 않는다(NiaVideo.og_cd 는 종전대로 null 고정).
-- 표준용어: 기존 등록 용어 재사용(신규 0). LCLGV_CD·LCLGV_NM 과 서로 다른 값 — 통합 금지.
--
ALTER TABLE LS_DATA_INGEST ADD COLUMN IF NOT EXISTS OG_CD VARCHAR(20);
COMMENT ON COLUMN LS_DATA_INGEST.OG_CD IS '기관코드(관제 resource_cctvs 조인). 촬영 시점 값 고정 목적의 의도된 중복 저장. 지방자치단체코드(LCLGV_CD)·지방자치단체명(LCLGV_NM)과 서로 다른 값이며 셋을 대체·통합하지 않는다. 관제 재요청으로 재추가(2026-08-24, 구 V185 제거분 복원).';

-- -----------------------------------------------------------------------------
-- 4) V_COMPLETED_VIDEO 재정의 — THMB_FILE_PATH_NM 노출
--
-- 왜: 관제는 완료 통지(TASK_COMPLETED, 메타 10필드) 수신 후 RAW_SN 으로 이 뷰를 SELECT 해
--     상세를 조달한다. 썸네일은 통지 페이로드가 아니라 이 뷰로 전달한다(통지는 경로·본문 미포함
--     계약면). 인입 LATERAL(i) 이 COALESCE(orgnl_raw_sn, raw_sn) 로 조인하므로 파생영상은
--     부모 인입 행의 썸네일을 받는다(NULL 아님) — 모든 완료 영상에 썸네일 1개 보장.
--
-- 계약면 주의: 기존 출력 컬럼(30개)의 이름·타입·순서·시맨틱 전부 불변. THMB_FILE_PATH_NM 을
--     맨 끝에 '추가만' 한다 — CREATE OR REPLACE VIEW 는 후행 컬럼 추가를 허용한다.
--     기존 컬럼을 하나라도 바꾸면 REPLACE 가 거부되므로 baseline 정의를 그대로 복제한다.
--
CREATE OR REPLACE VIEW v_completed_video AS
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
    m.de_ident_yn AS de_idntf_yn,
    i.thmb_file_path_nm
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
            ig.prvc_incl_yn,
            ig.thmb_file_path_nm
           FROM ls_data_ingest ig
          WHERE (ig.raw_sn = COALESCE(r.orgnl_raw_sn, r.raw_sn))
          ORDER BY ig.rcptn_sn DESC
         LIMIT 1) i ON (true))
     LEFT JOIN LATERAL ( SELECT string_agg(DISTINCT (lb.lbl_type_cd)::text, ','::text ORDER BY (lb.lbl_type_cd)::text) AS lbl_type
           FROM (ls_data_lbl lb
             JOIN ls_data_src sc ON ((sc.src_sn = lb.src_sn)))
          WHERE (sc.raw_sn = m.raw_sn)) l ON (true))
  WHERE ((m.active_yn = 'Y'::bpchar) AND ((s.data_stts_cd)::text = 'APPROVED'::text));

-- =============================================================================
-- 롤백 SQL (V162 규약 — 스키마 변경에는 롤백 전문 동반):
--   -- 4) 뷰를 THMB 없는 baseline 형상으로 되돌린다(위 CREATE OR REPLACE 본문에서 마지막
--   --    SELECT 컬럼 i.thmb_file_path_nm 과 LATERAL i 의 ig.thmb_file_path_nm 두 줄 제거 후 재실행).
--   -- 3) OG_CD 제거
--   ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS OG_CD;
--   -- 2) THMB 제거
--   ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS THMB_FILE_PATH_NM;
--   -- 1) BIT COMMENT 를 색심도 서술로 복원
--   COMMENT ON COLUMN LS_DATA_INGEST."bit" IS '비트값 = 색심도 표기(예 24bit). 비트레이트가 아니다. PostgreSQL 에서 컬럼명으로는 무인용 사용 가능(인용 금지).';
--   -- ※ 애플리케이션 롤백(LsDataIngest.thmbFilePathNm·ogCd 필드 제거)도 함께 필요 —
--   --   엔티티와 어긋나면 ddl-auto=validate 가 기동을 막는다.
-- =============================================================================
