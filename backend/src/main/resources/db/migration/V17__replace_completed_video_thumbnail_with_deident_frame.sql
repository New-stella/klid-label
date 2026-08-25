-- =============================================================================
-- V17: 완료 영상 뷰의 대표 이미지(썸네일) 조달원 교체
--      관제 인입값 pass-through  →  저작도구 비식별 첫 프레임 절대경로
--
-- 1) V_COMPLETED_VIDEO 뷰 DROP           (컬럼 DROP 보다 반드시 먼저 — 의존성)
-- 2) LS_DATA_INGEST.THMB_FILE_PATH_NM 제거
-- 3) V_COMPLETED_VIDEO 재생성            (출력 31컬럼 · 마지막 컬럼만 조달원 교체)
-- 4) COMMENT ON VIEW 재선언              (DROP 으로 코멘트가 소멸하므로)
--
-- 설계 진실원: LogiCraft ERD-012 v41 · INT-010 v11 · EXTSYS-005 v13 · INTSPEC-004 v10.
--   @design ERD-012  @design INT-010
-- 우리 소유 LS_* 테이블 + 우리 소유 뷰. PostgreSQL 표준 문법.
-- 스키마 무한정(unqualified) 식별자 — search_path(klid_at) 를 따른다. 'public.' 리터럴 금지.
--
-- -----------------------------------------------------------------------------
-- 왜 바꾸는가
--
-- 구 방식(관제 인입값 pass-through)은 저작도구가 통제할 수 없는 전제 셋에 기댔다.
--   (1) 관제가 값을 보내야만 썸네일이 존재한다 — 컬럼이 nullable 이고 미송신을 허용하므로
--       관제가 안 보내면 그 영상의 대표 이미지는 영구히 비고 저작도구는 채울 수단이 없다.
--   (2) 그 경로가 가리키는 파일을 저작도구가 소유하지 않는다 — 받은 문자열일 뿐이라
--       실재 여부·수명을 보장하지 못한다.
--   (3) 비식별 보장이 없다 — 관제 썸네일은 원천 영상에서 뽑은 것이라 마스킹 전 화면일 수 있다.
--       저작도구 산출 학습데이터는 전부 비식별 기준인데 대표 이미지만 원천 기준이면
--       한 산출물 안에서 기준이 갈린다.
--
-- 반면 저작도구는 검수 완료 영상 전부에 대해 비식별 프레임을 이미 보유한다. 프레임 추출이
-- 비식별 저장소 하위에 쓰고 그 <절대경로>를 LS_DATA_SRC.DE_IDNTF_SRC_FILE_PATH_NM 에 적재하므로,
-- 그 값을 그대로 읽으면 전제 셋이 모두 해소된다.
--
-- -----------------------------------------------------------------------------
-- 실행 순서 주의 (Critical)
--
-- 뷰가 LS_DATA_INGEST.THMB_FILE_PATH_NM 을 참조하므로 <뷰를 먼저 지워야> 컬럼을 지울 수 있다.
-- 순서를 뒤집으면 'cannot drop column ... because other objects depend on it' 으로 실패한다.
--
-- 계약면 주의: 출력 31컬럼의 <이름·순서·시맨틱은 전부 불변>. 마지막 THMB_FILE_PATH_NM 만
--   조달원과 타입이 바뀐다(varchar(500) -> varchar(1000)). 타입이 바뀌므로 CREATE OR REPLACE
--   VIEW 로는 재정의할 수 없어 DROP + CREATE 로 간다(선례 V85/V95/V174 동일).
--
-- 관제 협의 대상: 관제가 인입 원장의 THMB_FILE_PATH_NM 에 값을 넣고 있었다면 이 마이그레이션
--   이후 <영상 인입 INSERT 전체가 실패>한다. 배포 전 통보가 선행돼야 한다. 상세 EXTSYS-005.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) 뷰 DROP (컬럼 DROP 의 선행 조건)
--
DROP VIEW IF EXISTS v_completed_video;

-- -----------------------------------------------------------------------------
-- 2) 인입 원장의 썸네일 경로 컬럼 제거
--
-- 뷰가 더 이상 읽지 않아 소비자가 0 이 된다. 관제 수신 원장이므로 값은 관제가 넣던 것이며,
-- DROP 시점에 그 값은 소실된다(V185 의 OG_CD 제거 때와 같은 성질 — 과거 복구 불가).
--
ALTER TABLE LS_DATA_INGEST DROP COLUMN IF EXISTS THMB_FILE_PATH_NM;

-- -----------------------------------------------------------------------------
-- 3) 뷰 재생성 — 대표 이미지를 비식별 첫 프레임에서 조달
--
-- 신규 LATERAL t: 그 영상의 프레임 중 아래 넷을 모두 만족하는 1행.
--   ㉠ FRM_NO 최소  ㉡ 폐기 아님  ㉢ 비식별 경로가 비어있지 않음  ㉣ 비식별 경로 != 원본 경로
--
--   · LIMIT 1 이라 영상 1건 = 1 row 불변식이 유지된다(일반 JOIN 으로 바꾸면 프레임 수만큼 행이 분다).
--
--   · ★㉡㉣ 두 술어는 자매 뷰 V_COMPLETED_FRAME 의 게이트와 <문자 그대로 동치>여야 한다.
--     그 뷰의 게이트는 <둘>이다 — 폐기 제외(㉡)와 V133 비식별 경로 불변식(㉣).
--     ㉣ 를 빠뜨리면 "비식별 경로에 원본 경로가 그대로 적힌" 레거시 행이 통과해,
--     V_COMPLETED_FRAME 에는 프레임이 0건인 승인 영상인데 V_COMPLETED_VIDEO 의 대표 이미지로는
--     <원본(마스킹 전) 프레임 경로>가 관제 계약면에 나간다(CWE-359). 실 DB 로 실증된 결함이라
--     "폐기 술어만 맞추면 된다"고 읽지 말 것 — 자매 뷰의 술어를 <전수로> 세서 맞춘다.
--
--   · 원본 프레임(SRC_FILE_PATH_NM) 폴백을 두지 않는다 — 폴백하면 마스킹 전 화면이 대표 이미지로
--     외부에 나간다(CWE-359). 유효 프레임이 하나도 없으면 NULL 이 정답이다.
--   · 파생영상은 자기 RAW_SN 하위에 자기 비식별 프레임을 갖고 있어 자기 것에서 조달한다.
--     구 동작(부모 인입 행의 썸네일 상속)은 폐기다.
-- 인입 LATERAL i 에서는 ig.thmb_file_path_nm 만 뺐고 나머지 조달 컬럼은 불변이다.
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
    m.de_ident_yn AS de_idntf_yn,
    t.thmb_file_path_nm
   FROM (((((((ls_dataset_video_meta m
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
     LEFT JOIN LATERAL ( SELECT fr.de_idntf_src_file_path_nm AS thmb_file_path_nm
           FROM ls_data_src fr
          WHERE ((fr.raw_sn = m.raw_sn) AND (fr.de_idntf_src_file_path_nm IS NOT NULL)
                 AND (btrim((fr.de_idntf_src_file_path_nm)::text) <> ''::text)
                 AND NOT ((fr.src_file_path_nm IS NOT NULL)
                          AND (btrim((fr.src_file_path_nm)::text) <> ''::text)
                          AND (btrim((fr.de_idntf_src_file_path_nm)::text) <> ''::text)
                          AND ((fr.de_idntf_src_file_path_nm)::text = (fr.src_file_path_nm)::text))
                 AND (COALESCE(fr.dscd_yn, 'N'::bpchar) <> 'Y'::bpchar))
          ORDER BY fr.frm_no
         LIMIT 1) t ON (true))
  WHERE ((m.active_yn = 'Y'::bpchar) AND ((s.data_stts_cd)::text = 'APPROVED'::text));

-- -----------------------------------------------------------------------------
-- 4) 뷰 코멘트 재선언 (DROP 으로 소멸했으므로)
--
COMMENT ON VIEW v_completed_video IS '데이터마트 적재용 — 검수 승인(APPROVED) 영상 1건 = 1 row, 31컬럼(규격서 §5-1). 관제가 datasets·dataset_versions 를 채우고 산출 폴더·비식별 영상을 픽업하는 계약면이다. 비식별 누락 신고 구간(DE_IDNTF_YN=''F'')에도 행을 감추거나 경로를 비우지 않는다(확정 정책) — 관제가 DE_IDNTF_YN 으로 자체 판단한다. THMB_FILE_PATH_NM 은 저작도구 비식별 첫 프레임의 절대경로이며 관제 인입값 pass-through 가 아니다(V17). 선택 규칙은 FRM_NO 최소 · 폐기 아님 · 비식별 경로가 비어있지 않음 · 비식별 경로가 원본 경로와 같지 않음(V_COMPLETED_FRAME 게이트와 동치)이며, 만족하는 프레임이 없으면 NULL 이고 원본 프레임 경로로 폴백하지 않는다.';

-- =============================================================================
-- 롤백 SQL (V162 규약 — 스키마 변경에는 롤백 전문 동반):
--
--   -- (1) 뷰를 먼저 지운다 (신규 뷰는 LS_DATA_SRC 만 참조하므로 컬럼 복원과 순서 무관하지만,
--   --     재생성 시 THMB 컬럼이 있어야 하므로 지우고 -> 컬럼 복원 -> V16 형상 재생성 순서를 지킨다)
--   DROP VIEW IF EXISTS v_completed_video;
--
--   -- (2) 인입 컬럼 복원
--   ALTER TABLE LS_DATA_INGEST ADD COLUMN IF NOT EXISTS THMB_FILE_PATH_NM VARCHAR(500);
--   COMMENT ON COLUMN LS_DATA_INGEST.THMB_FILE_PATH_NM IS '썸네일 이미지 파일 경로명(관제 인입값 pass-through). 관제 학습데이터 패키징에 사용하며 저작도구는 생성·가공하지 않는다. V_COMPLETED_VIDEO 로 노출한다. nullable — 미송신 시 인입 실패하지 않는다.';
--
--   -- (3) V16 형상 뷰 재생성 — 본문이 90여 줄이라 여기 인라인하지 않고 참조로 둔다.
--   --     V16__align_control_ingest_bit_thmb_ogcd.sql 에서 아래 <두 마커 사이를 그대로> 잘라 실행한다:
--   --       시작 마커: CREATE OR REPLACE VIEW v_completed_video AS
--   --       끝   마커: APPROVED'::text));
--   --     (그 본문이 THMB 를 인입 LATERAL i 에서 조달하는 형상이다. 잘라낼 범위를 눈대중으로
--   --      잡으면 LATERAL 괄호가 어긋나 롤백이 구문 오류로 죽으므로 마커를 지킬 것.)
--
--   -- (4) COMMENT ON VIEW 를 V16 이전 문구로 되돌린다 — 참조가 아니라 <전문>이다(V1 baseline 원문).
--   --     참조로 두면 롤백하는 사람이 V1 3,500여 줄에서 문구를 찾아야 한다.
--   COMMENT ON VIEW v_completed_video IS '데이터마트 적재용 — 검수 승인(APPROVED) 영상 1건 = 1 row, 30컬럼(V174, 규격서 §5-1). 관제가 datasets·dataset_versions 를 채우고 산출 폴더·비식별 영상을 픽업하는 계약면이다. 비식별 누락 신고 구간(DE_IDNTF_YN=''F'')에도 행을 감추거나 경로를 비우지 않는다(확정 정책) — 관제가 DE_IDNTF_YN 으로 자체 판단한다.';
--   -- (위 한 줄은 V1__baseline.sql 원문 그대로다. ''F'' 의 2겹 따옴표도 원문대로이니 줄이지 말 것.)
--
--   -- ※ 애플리케이션 롤백(LsDataIngest.thmbFilePathNm 필드 복원)도 함께 필요 —
--   --   엔티티와 어긋나면 ddl-auto=validate 가 기동을 막는다.
--   -- ⚠ DROP 으로 소실된 인입 썸네일 값은 <복구되지 않는다>. 신규 인입 행부터 다시 채워진다.
-- =============================================================================
