-- =====================================================================
-- v1(MariaDB) → v2(PostgreSQL) 영상·라벨 이관 : 스테이징 적재 + 변환 + 적재
-- 실행: psql ... -v dir=/path/to/out \
--        -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 \
--        -v asgn_off=200000000 -v issue_off=300000000 -v aug_off=400000000 \
--        -f 02_load_transform.sql
-- 전제: 02 는 단일 트랜잭션. 실패 시 전체 롤백. 데이터 비종속(값 하드코딩 없음, 스테이징에서 유도).
-- =====================================================================
\set ON_ERROR_STOP on
\timing on
BEGIN;

-- [재실행용 cleanup] 의미 매핑/오프셋을 바꿔 다시 돌릴 때 아래 주석 해제(이전 이관분 제거).
--   순서 주의: 자식 → 부모 (FK 역순). 확장 스코프(상태/배정/이슈/증강)도 함께 제거.
-- DELETE FROM ls_issue_comment   WHERE issue_comment_sn >= :issue_off;
-- DELETE FROM ls_data_issue      WHERE data_issue_sn    >= :issue_off;
-- DELETE FROM ls_data_aug        WHERE data_aug_sn      >= :aug_off;
-- DELETE FROM ls_task_assignment WHERE assignment_id    >= :asgn_off;
-- DELETE FROM ls_raw_data_status WHERE raw_data_id      >= :raw_off;
-- DELETE FROM ls_data_lbl  WHERE lbl_sn >= :lbl_off;
-- DELETE FROM ls_data_src  WHERE src_sn >= :src_off;
-- DELETE FROM ls_data_raw  WHERE raw_sn >= :raw_off;
-- DELETE FROM ls_label     WHERE reg_id = 'MIGRATION';

-- 날짜 안전 캐스팅 헬퍼: 빈문자/NULL/ MySQL 제로데이트('0000-..')는 NULL 로
CREATE OR REPLACE FUNCTION pg_temp.mig_ts(t text) RETURNS timestamp AS $$
  SELECT CASE WHEN t IS NULL OR t = '' OR t LIKE '0000-%' THEN NULL ELSE t::timestamp END
$$ LANGUAGE sql IMMUTABLE;

-- ---------------------------------------------------------------------
-- 0. 스테이징 테이블 (전부 TEXT 로 받아 변환 단계에서 캐스팅 — 적재 견고성)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS stg_raw, stg_src, stg_lbl, stg_pjt_lbl, stg_stts, stg_issue, stg_aug,
                     mig_label_name_map, map_label, map_src, mig_src_canon, mig_target_raw,
                     map_assign CASCADE;

CREATE TEMP TABLE stg_raw(
  data_raw_sn bigint, raw_file_path text, vms_cctv_id text, evnt_type_cd text,
  lclgv_cd text, prvc_yn text, de_idntf_yn text, sht_dt text, vdo_len text,
  reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_src(
  data_src_sn bigint, data_raw_sn bigint, frm_no int, src_file_path text,
  src_bkup_file_path text, reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_lbl(
  data_lbl_sn bigint, data_raw_sn bigint, data_src_sn bigint, lbl_sn bigint,
  trck_id text, point text, reg_dt text, mdfcn_dt text, reg_id text);

CREATE TEMP TABLE stg_pjt_lbl(
  lbl_sn bigint, lbl_id text, prc_type_cd text, lbl_nm text, lbl_colr text, sort_seq text);

-- 확장 스코프 스테이징
CREATE TEMP TABLE stg_stts(
  pjt_sn bigint, data_raw_sn bigint, pjt_data_stts_cd text,
  stp_cycl text, igi_cycl text, reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_issue(
  data_issue_sn bigint, up_data_issue_sn bigint, data_raw_sn bigint, data_src_sn bigint,
  issue_type_cd text, issue_dtl_cd text, rjct_dtl_cd text, issue_cn text,
  use_yn text, reg_id text, reg_dt text);

CREATE TEMP TABLE stg_aug(
  data_aug_sn bigint, data_raw_sn bigint, data_src_sn bigint, pjt_aug_opt_cd text,
  aug_proc_stts_cd text, aug_file_path text, reg_id text, reg_dt text);

-- TSV 적재 (헤더 없음, mysql --batch 이스케이프 ↔ FORMAT text 호환, NULL=\N)
\copy stg_raw     FROM :'dir'/raw.tsv      WITH (FORMAT text)
\copy stg_src     FROM :'dir'/src.tsv      WITH (FORMAT text)
\copy stg_lbl     FROM :'dir'/lbl.tsv      WITH (FORMAT text)
\copy stg_pjt_lbl FROM :'dir'/pjt_lbl.tsv  WITH (FORMAT text)
\copy stg_stts    FROM :'dir'/stts.tsv     WITH (FORMAT text)
\copy stg_issue   FROM :'dir'/issue.tsv    WITH (FORMAT text)
\copy stg_aug     FROM :'dir'/aug.tsv      WITH (FORMAT text)

-- ---------------------------------------------------------------------
-- 1. 라벨 클래스 의미 매핑 (GAP②) — 운영자 검토 영역
--    v1 LBL_ID(영문 식별자: water/person/fallen_person…) → v2 ls_label.lbl_nm
--    여기에 없는 v1 클래스는 '동일 이름'으로 간주, v2에 없으면 자동 신규 INSERT.
--    ※ 환경 데이터에 맞게 아래 매핑을 추가/수정한 뒤 실행할 것.
-- ---------------------------------------------------------------------
CREATE TEMP TABLE mig_label_name_map(v1_name text PRIMARY KEY, v2_name text NOT NULL);
INSERT INTO mig_label_name_map(v1_name, v2_name) VALUES
  ('fallen_person', 'fallen-person'),   -- 표기 통일(_→-)
  ('two_wheeler',   'motorbike');       -- 이륜차 → motorbike (운영 결정)
  -- 예) ('asphalt','asphalt') 는 생략 가능(동일명) — 미존재 시 자동 신규추가됨

-- v1 고유 클래스(이름+타입) → 목표 v2 lbl_nm 해석
CREATE TEMP TABLE map_label(v1_lbl_sn bigint PRIMARY KEY, v2_lbl_id bigint NOT NULL);

-- 1-a. v2 에 없는 클래스는 신규 INSERT (타입 v1 유지, 색상 보존). 이름+타입 기준 dedup.
WITH v1cls AS (
  SELECT DISTINCT
         COALESCE(m.v2_name, p.lbl_id)                AS target_nm,
         upper(p.prc_type_cd)                         AS lbl_type_cd,
         max(p.lbl_colr)                              AS colr_vl
  FROM stg_pjt_lbl p
  LEFT JOIN mig_label_name_map m ON m.v1_name = p.lbl_id
  GROUP BY COALESCE(m.v2_name, p.lbl_id), upper(p.prc_type_cd)
)
INSERT INTO ls_label(lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt)
SELECT v.target_nm, COALESCE(v.colr_vl, '#999999'), v.lbl_type_cd,
       (SELECT COALESCE(max(sort_seq),0) FROM ls_label) + row_number() OVER (ORDER BY v.target_nm),
       'Y', 'MIGRATION', now()
FROM v1cls v
WHERE NOT EXISTS (
  SELECT 1 FROM ls_label l
  WHERE l.lbl_nm = v.target_nm AND l.lbl_type_cd = v.lbl_type_cd
);

-- 1-b. v1 LBL_SN → v2 lbl_id 대응표 구축 (이름+타입으로 v2 행 결정)
INSERT INTO map_label(v1_lbl_sn, v2_lbl_id)
SELECT p.lbl_sn, l.lbl_id
FROM stg_pjt_lbl p
LEFT JOIN mig_label_name_map m ON m.v1_name = p.lbl_id
JOIN ls_label l
  ON l.lbl_nm = COALESCE(m.v2_name, p.lbl_id)
 AND l.lbl_type_cd = upper(p.prc_type_cd);

-- ---------------------------------------------------------------------
-- 2. 프레임 dedup 대응표 (GAP: 4개 영상이 프로젝트 중복 → (raw,frm) 중복 제거)
--    같은 (raw,frm) 의 v1 src 들은 1개의 v2 src 로 수렴, 라벨은 그 1개를 가리키게.
-- ---------------------------------------------------------------------
CREATE TEMP TABLE mig_src_canon AS
SELECT data_raw_sn, frm_no, min(data_src_sn) AS canon_src_sn
FROM stg_src GROUP BY data_raw_sn, frm_no;

CREATE TEMP TABLE map_src(v1_src_sn bigint PRIMARY KEY, v2_src_sn bigint NOT NULL);
INSERT INTO map_src(v1_src_sn, v2_src_sn)
SELECT s.data_src_sn, c.canon_src_sn + :src_off
FROM stg_src s JOIN mig_src_canon c USING(data_raw_sn, frm_no);

-- 대상 영상 집합 = 라벨 보유 ∩ stg_raw 존재 (C-4: 정합 불량 영상으로 인한 FK/고아 차단)
CREATE TEMP TABLE mig_target_raw AS
SELECT DISTINCT l.data_raw_sn
FROM stg_lbl l
WHERE l.data_raw_sn IN (SELECT data_raw_sn FROM stg_raw);

-- ---------------------------------------------------------------------
-- 3. 영상 적재 (대상 영상만). vms_clip_id = LEGACY-{sn} (GAP①), 메타 제외(GAP③)
-- ---------------------------------------------------------------------
INSERT INTO ls_data_raw(
  raw_sn, vms_clip_id, vms_cctv_id, evnt_type_cd, lclgv_cd,
  prvc_type_cd, prvc_yn, de_ident_yn, raw_file_path_nm, sht_dt, vdo_len_sec, vdo_len_ms,
  data_stts_cd, reg_dt)
SELECT r.data_raw_sn + :raw_off,
       'LEGACY-' || r.data_raw_sn,
       COALESCE(NULLIF(r.vms_cctv_id,''), 'UNKNOWN'),
       r.evnt_type_cd, r.lclgv_cd,
       'UNKNOWN',                                   -- prvc_type_cd: v1 미보유 → 기본값
       COALESCE(NULLIF(r.prvc_yn,''), 'N'),
       COALESCE(NULLIF(r.de_idntf_yn,''), 'N'),
       left(r.raw_file_path, 500),
       pg_temp.mig_ts(r.sht_dt),
       floor(NULLIF(r.vdo_len,'')::numeric / 1000)::int,  -- vdo_len_sec(초): VDO_LEN(ms) ÷1000 (실측 VDO_LEN/(FRM_CNT/FPS)=1000)
       floor(NULLIF(r.vdo_len,'')::numeric)::bigint,      -- vdo_len_ms: 원본 ms 보존(fidelity, V67 컬럼)
       'COMPLETED',                                 -- 배치단계: 이관본은 완료로 마감
       COALESCE(pg_temp.mig_ts(r.reg_dt), now())
FROM stg_raw r
WHERE r.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw);

-- ---------------------------------------------------------------------
-- 4. 프레임 적재 (canonical 1행/(raw,frm)). 대상 영상에 종속분만.
-- ---------------------------------------------------------------------
INSERT INTO ls_data_src(
  src_sn, raw_sn, frm_no, src_file_path_nm, de_idntf_src_file_path_nm, reg_dt)
SELECT c.canon_src_sn + :src_off,
       c.data_raw_sn + :raw_off,
       c.frm_no,
       left(s.src_file_path, 500),
       left(s.src_bkup_file_path, 1000),            -- H-2: varchar(1000) truncation
       COALESCE(pg_temp.mig_ts(s.reg_dt), now())
FROM mig_src_canon c
JOIN stg_src s ON s.data_src_sn = c.canon_src_sn
WHERE c.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw);

-- ---------------------------------------------------------------------
-- 5. 라벨 적재 — POINT 포맷 변환 + lbl_type_cd/lbl_nm 비정규화 복사
--    BBOX {x,y,width,height} → [[x,y],[x+w,y+h]]
--    POLYGON [{x,y},...]     → [[x,y],...]
-- ---------------------------------------------------------------------
INSERT INTO ls_data_lbl(
  lbl_sn, src_sn, lbl_type_cd, lbl_nm, point_cn, trck_id, lbl_id, reg_dt)
SELECT l.data_lbl_sn + :lbl_off,
       ms.v2_src_sn,
       cls.lbl_type_cd,
       cls.lbl_nm,
       CASE jsonb_typeof(NULLIF(l.point,'')::jsonb)   -- 빈문자열 '' 캐스팅 오류 방어(NULL→ELSE)
         WHEN 'object' THEN   -- BBOX
           jsonb_build_array(
             jsonb_build_array((NULLIF(l.point,'')::jsonb->>'x')::numeric, (NULLIF(l.point,'')::jsonb->>'y')::numeric),
             jsonb_build_array(
               (NULLIF(l.point,'')::jsonb->>'x')::numeric + (NULLIF(l.point,'')::jsonb->>'width')::numeric,
               (NULLIF(l.point,'')::jsonb->>'y')::numeric + (NULLIF(l.point,'')::jsonb->>'height')::numeric)
           )::text
         WHEN 'array' THEN    -- POLYGON
           (SELECT jsonb_agg(jsonb_build_array((e->>'x')::numeric, (e->>'y')::numeric))
            FROM jsonb_array_elements(NULLIF(l.point,'')::jsonb) e)::text
         ELSE NULL
       END,
       NULLIF(l.trck_id,''),
       ml.v2_lbl_id,                                 -- v2 라벨클래스 ID (대응표)
       COALESCE(pg_temp.mig_ts(l.reg_dt), now())
FROM stg_lbl l
JOIN map_src   ms ON ms.v1_src_sn = l.data_src_sn
JOIN map_label ml ON ml.v1_lbl_sn = l.lbl_sn
JOIN ls_label  cls ON cls.lbl_id = ml.v2_lbl_id
WHERE l.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw);  -- C-4: 대상 영상 라벨만

-- ---------------------------------------------------------------------
-- 5-b. 손실 fail-closed 가드 (스테이징이 살아있는 트랜잭션 내부에서만 가능)
--   대상 영상의 라벨이 ① 라벨클래스 미매핑 ② 프레임(src) 미매핑 으로 누락되면 ABORT.
-- ---------------------------------------------------------------------
DO $$
DECLARE v_cls bigint; v_src bigint;
BEGIN
  SELECT count(*) INTO v_cls FROM stg_lbl l
   WHERE l.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw)
     AND NOT EXISTS (SELECT 1 FROM map_label ml WHERE ml.v1_lbl_sn = l.lbl_sn);
  SELECT count(*) INTO v_src FROM stg_lbl l
   WHERE l.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw)
     AND NOT EXISTS (SELECT 1 FROM map_src ms WHERE ms.v1_src_sn = l.data_src_sn);
  IF v_cls > 0 THEN
    RAISE EXCEPTION '라벨 클래스 미매핑 % 건 — mig_label_name_map / LS_PJT_LBL 정합 검토 필요(전체 롤백)', v_cls;
  END IF;
  IF v_src > 0 THEN
    RAISE EXCEPTION '라벨이 참조하는 프레임(src) 미매핑 % 건 — LS_DATA_SRC export 누락 의심(전체 롤백)', v_src;
  END IF;
END $$;

-- =====================================================================
-- 확장 스코프(프로젝트→영상 단위): 상태(A)·배정(B)·이슈(D)·증강(C)
--   공통: PJT_SN 제거 후 영상(raw_sn) 단위로 수렴. 대상=mig_target_raw(이관 영상)만.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 7. 검수/완료 상태 (A) — LS_PJT_DATA_STTS → ls_raw_data_status
--    (pjt,raw) → 영상 단위 수렴: 동일 영상이 여러 PJT면 '가장 진행된' 상태 채택(우선순위).
--    상태매핑 v1→v2: DONE/REVIEW→APPROVED, PROCESS→IN_REVIEW, ASSIGN→ASSIGNED,
--                    REJECT→REJECTED, FAIL→FAILED, 그 외(WAIT/PENDING/PROC)→PENDING.
--    데이터마트 View 는 DATA_STTS_CD='APPROVED' 만 노출 → 이 적재가 검수완료 노출을 만든다.
-- ---------------------------------------------------------------------
WITH mapped AS (
  SELECT s.data_raw_sn,
         CASE upper(s.pjt_data_stts_cd)
           WHEN 'DONE'    THEN 'APPROVED'  WHEN 'REVIEW' THEN 'APPROVED'
           WHEN 'PROCESS' THEN 'IN_REVIEW' WHEN 'ASSIGN' THEN 'ASSIGNED'
           WHEN 'REJECT'  THEN 'REJECTED'  WHEN 'FAIL'   THEN 'FAILED'
           ELSE 'PENDING'
         END AS v2_stts,
         COALESCE(NULLIF(s.stp_cycl,'')::int, 0) AS stp_cycl,
         COALESCE(NULLIF(s.igi_cycl,'')::int, 0) AS igi_cycl,
         COALESCE(pg_temp.mig_ts(s.mdfcn_dt), pg_temp.mig_ts(s.reg_dt), now()) AS upd_dt
  FROM stg_stts s
  WHERE s.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw)
), ranked AS (
  SELECT m.*,
         row_number() OVER (
           PARTITION BY m.data_raw_sn
           ORDER BY CASE m.v2_stts WHEN 'APPROVED' THEN 6 WHEN 'IN_REVIEW' THEN 5
                    WHEN 'ASSIGNED' THEN 4 WHEN 'REJECTED' THEN 3 WHEN 'PENDING' THEN 2
                    WHEN 'FAILED' THEN 1 ELSE 0 END DESC, m.upd_dt DESC) AS rn
  FROM mapped m
)
INSERT INTO ls_raw_data_status(raw_data_id, data_stts_cd, stp_cycl, igi_cycl, upd_dt, ver)
SELECT r.data_raw_sn + :raw_off, r.v2_stts, r.stp_cycl, r.igi_cycl, r.upd_dt, 1
FROM ranked r
WHERE r.rn = 1
  AND NOT EXISTS (SELECT 1 FROM ls_raw_data_status x WHERE x.raw_data_id = r.data_raw_sn + :raw_off);

-- ---------------------------------------------------------------------
-- 8. 작업자 배정 (B) — 라벨 작성자(LS_DATA_LBL.REG_ID)에서 영상별 LABELER 도출
--    V1 은 영상별 배정이 없고 프로젝트 멤버십(LS_PJT_USER_AUTHRT)만 있어 naive 확장 시
--    cartesian 오배정(~67K). → 실제 라벨을 만든 사람을 그 영상 LABELER 로 배정.
--    user_id(varchar) → mng_acct_user.user_no(bigint) 매핑. 미매칭은 JOIN 제외(soft skip+보고).
-- ---------------------------------------------------------------------
CREATE TEMP TABLE map_assign AS
SELECT l.data_raw_sn, l.reg_id, min(pg_temp.mig_ts(l.reg_dt)) AS first_dt
FROM stg_lbl l
WHERE l.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw)
  AND NULLIF(l.reg_id,'') IS NOT NULL
GROUP BY l.data_raw_sn, l.reg_id;

INSERT INTO ls_task_assignment(assignment_id, user_no, raw_data_id, task_type_cd, reg_user_no, reg_dt)
SELECT :asgn_off + row_number() OVER (ORDER BY a.data_raw_sn, mu.user_no),
       mu.user_no, a.data_raw_sn + :raw_off, 'LABELER',
       mu.user_no,                                       -- reg_user_no: 이관 시 자기 배정으로 기록
       COALESCE(a.first_dt, now())
FROM map_assign a
JOIN mng_acct_user mu ON mu.user_id = a.reg_id
WHERE NOT EXISTS (
  SELECT 1 FROM ls_task_assignment t
   WHERE t.raw_data_id = a.data_raw_sn + :raw_off AND t.user_no = mu.user_no AND t.task_type_cd='LABELER');

-- 배정 미매칭 작성자 보고 (데이터 손실 아님 — 메타. NOTICE 로 운영자에게 노출)
DO $$
DECLARE v_unmatched int;
BEGIN
  SELECT count(DISTINCT a.reg_id) INTO v_unmatched
  FROM map_assign a LEFT JOIN mng_acct_user mu ON mu.user_id = a.reg_id
  WHERE mu.user_no IS NULL;
  IF v_unmatched > 0 THEN
    RAISE NOTICE '[배정] mng_acct_user 미등록 작성자 % 명 — 해당 영상은 배정 누락(상태/라벨은 정상 이관). user_id 정합 후 재실행 시 보강 가능', v_unmatched;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 9. 이슈 (D) — LS_DATA_ISSUE → ls_data_issue(루트) + ls_issue_comment(답글)
--    PJT 제거, raw/src 대응표 재연결, 대상 영상의 이슈만. 스레드: UP IS NULL=루트, NOT NULL=답글.
--    issue_stts_cd 는 v1 USE_YN 에서 유도(Y=OPEN, N=RESOLVED). 상세코드는 issue_rsn 에 접미.
-- ---------------------------------------------------------------------
-- 9-a. 루트 이슈
INSERT INTO ls_data_issue(data_issue_sn, up_data_issue_sn, data_raw_sn, src_sn,
                          issue_type_cd, issue_stts_cd, issue_rsn, reported_user_no, reg_dt, ver)
SELECT i.data_issue_sn + :issue_off, NULL, i.data_raw_sn + :raw_off,
       ms.v2_src_sn,                                     -- 프레임 미매칭 시 NULL(LEFT JOIN)
       -- v2 CHECK: issue_type_cd ∈ (REJECTION,INQUIRY). v1 REJECT→REJECTION, ISSUE/그외→INQUIRY
       CASE upper(COALESCE(NULLIF(i.issue_type_cd,''),'ISSUE')) WHEN 'REJECT' THEN 'REJECTION' ELSE 'INQUIRY' END,
       -- v2 CHECK: issue_stts_cd ∈ (OPEN,ANSWERED,RESOLVED). USE_YN Y=OPEN, N=RESOLVED
       CASE WHEN upper(COALESCE(i.use_yn,'Y'))='Y' THEN 'OPEN' ELSE 'RESOLVED' END,
       left(concat_ws(' / ', NULLIF(i.issue_cn,''), NULLIF(i.rjct_dtl_cd,''), NULLIF(i.issue_dtl_cd,'')), 1000),
       left(NULLIF(i.reg_id,''), 50),
       COALESCE(pg_temp.mig_ts(i.reg_dt), now()), 1
FROM stg_issue i
LEFT JOIN map_src ms ON ms.v1_src_sn = i.data_src_sn
WHERE i.up_data_issue_sn IS NULL
  AND i.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw);

-- 9-b. 답글 → ls_issue_comment (부모 루트가 이관됐을 때만)
INSERT INTO ls_issue_comment(issue_comment_sn, data_issue_sn, author_no, author_role_cd, cmnt_cn, reg_dt)
SELECT i.data_issue_sn + :issue_off, i.up_data_issue_sn + :issue_off,
       left(COALESCE(NULLIF(i.reg_id,''),'UNKNOWN'), 50),
       'WORKER',                                         -- v1 미보유 → 기본 역할
       left(COALESCE(NULLIF(i.issue_cn,''),'(빈 댓글)'), 1000),
       COALESCE(pg_temp.mig_ts(i.reg_dt), now())
FROM stg_issue i
WHERE i.up_data_issue_sn IS NOT NULL
  AND i.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw)
  AND EXISTS (SELECT 1 FROM ls_data_issue r
               WHERE r.data_issue_sn = i.up_data_issue_sn + :issue_off
                 AND r.data_raw_sn   = i.data_raw_sn + :raw_off);   -- 답글-부모 영상 소속 일치까지 검증

-- ---------------------------------------------------------------------
-- 10. 증강 레거시 (C) — LS_DATA_AUG → ls_data_aug (GAP④)
--     v1 내부 증강(BRIGHT/DARK/LR…) ≠ v2 외부 생성형(WINTER/NIGHT/RAIN). 타입코드 보존 적재.
--     ⚠ v2 ls_data_aug 에 파일경로 컬럼 부재 → AUG_FILE_PATH 손실(증강 발생 사실만 보존).
--     프레임(src) 매칭분만 적재(고아 차단).
-- ---------------------------------------------------------------------
INSERT INTO ls_data_aug(data_aug_sn, src_sn, aug_type_cd, aug_proc_stts_cd, reg_dt, reg_user_no, rtry_nmtm)
SELECT g.data_aug_sn + :aug_off, ms.v2_src_sn,
       left(upper(g.pjt_aug_opt_cd), 20),
       left(COALESCE(NULLIF(upper(g.aug_proc_stts_cd),''),'UNKNOWN'), 20),  -- 빈값에 SUCCESS 강제 금지(의미왜곡 방지)
       COALESCE(pg_temp.mig_ts(g.reg_dt), now()),
       left(NULLIF(g.reg_id,''), 50), 0
FROM stg_aug g
JOIN map_src ms ON ms.v1_src_sn = g.data_src_sn
WHERE g.data_raw_sn IN (SELECT data_raw_sn FROM mig_target_raw);

-- ---------------------------------------------------------------------
-- 11. IDENTITY 시퀀스 재동기화 (이관 PK 가 차지한 영역 다음으로)
-- ---------------------------------------------------------------------
-- (빈 테이블 시 setval(seq,NULL) 미문서화 동작 방어 — 아래 DO 블록과 동일하게 WHERE m IS NOT NULL)
SELECT setval(pg_get_serial_sequence('ls_label','lbl_id'),    m, true) FROM (SELECT max(lbl_id) AS m FROM ls_label)   t WHERE m IS NOT NULL;
SELECT setval(pg_get_serial_sequence('ls_data_raw','raw_sn'), m, true) FROM (SELECT max(raw_sn) AS m FROM ls_data_raw) t WHERE m IS NOT NULL;
SELECT setval(pg_get_serial_sequence('ls_data_src','src_sn'), m, true) FROM (SELECT max(src_sn) AS m FROM ls_data_src) t WHERE m IS NOT NULL;
SELECT setval(pg_get_serial_sequence('ls_data_lbl','lbl_sn'), m, true) FROM (SELECT max(lbl_sn) AS m FROM ls_data_lbl) t WHERE m IS NOT NULL;

-- 확장 스코프 테이블 (serial/IDENTITY 인 것만 — 시퀀스 없으면 건너뜀, 빈 테이블이면 setval 미실행)
DO $$
DECLARE r record; seq text;
BEGIN
  FOR r IN SELECT * FROM (VALUES
      ('ls_task_assignment','assignment_id'),
      ('ls_data_issue','data_issue_sn'),
      ('ls_issue_comment','issue_comment_sn'),
      ('ls_data_aug','data_aug_sn')
    ) AS v(tbl,col)
  LOOP
    seq := pg_get_serial_sequence(r.tbl, r.col);
    IF seq IS NOT NULL THEN
      EXECUTE format('SELECT setval(%L, m, true) FROM (SELECT max(%I) m FROM %I) t WHERE m IS NOT NULL',
                     seq, r.col, r.tbl);
    END IF;
  END LOOP;
END $$;

COMMIT;
\echo '== 02_load_transform 완료. 다음: 03_verify.sql 로 검증 =='
