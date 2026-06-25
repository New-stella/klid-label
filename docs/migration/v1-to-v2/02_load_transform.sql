-- =====================================================================
-- v1(MariaDB) → v2(PostgreSQL) 영상·라벨 이관 : 스테이징 적재 + 변환 + 적재
-- 실행: psql ... -v dir=/path/to/out -v raw_off=1000000 -v src_off=10000000 -v lbl_off=100000000 -f 02_load_transform.sql
-- 전제: 02 는 단일 트랜잭션. 실패 시 전체 롤백. 데이터 비종속(값 하드코딩 없음, 스테이징에서 유도).
-- =====================================================================
\set ON_ERROR_STOP on
\timing on
BEGIN;

-- [재실행용 cleanup] 의미 매핑/오프셋을 바꿔 다시 돌릴 때 아래 4줄 주석 해제(이전 이관분 제거).
--   순서 주의: 라벨 → 프레임 → 영상 → 라벨클래스 (FK 역순)
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
DROP TABLE IF EXISTS stg_raw, stg_src, stg_lbl, stg_pjt_lbl,
                     mig_label_name_map, map_label, map_src, mig_src_canon, mig_target_raw CASCADE;

CREATE TEMP TABLE stg_raw(
  data_raw_sn bigint, raw_file_path text, vms_cctv_id text, evnt_type_cd text,
  lclgv_cd text, prvc_yn text, de_idntf_yn text, sht_dt text, vdo_len text,
  reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_src(
  data_src_sn bigint, data_raw_sn bigint, frm_no int, src_file_path text,
  src_bkup_file_path text, reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_lbl(
  data_lbl_sn bigint, data_raw_sn bigint, data_src_sn bigint, lbl_sn bigint,
  trck_id text, point text, reg_dt text, mdfcn_dt text);

CREATE TEMP TABLE stg_pjt_lbl(
  lbl_sn bigint, lbl_id text, prc_type_cd text, lbl_nm text, lbl_colr text, sort_seq text);

-- TSV 적재 (헤더 없음, mysql --batch 이스케이프 ↔ FORMAT text 호환, NULL=\N)
\copy stg_raw     FROM :'dir'/raw.tsv      WITH (FORMAT text)
\copy stg_src     FROM :'dir'/src.tsv      WITH (FORMAT text)
\copy stg_lbl     FROM :'dir'/lbl.tsv      WITH (FORMAT text)
\copy stg_pjt_lbl FROM :'dir'/pjt_lbl.tsv  WITH (FORMAT text)

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
  prvc_type_cd, prvc_yn, de_ident_yn, raw_file_path_nm, sht_dt, vdo_len_sec,
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
       floor(NULLIF(r.vdo_len,'')::numeric)::int,   -- 소수 표기 방어
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
       CASE jsonb_typeof(l.point::jsonb)
         WHEN 'object' THEN   -- BBOX
           jsonb_build_array(
             jsonb_build_array((l.point::jsonb->>'x')::numeric, (l.point::jsonb->>'y')::numeric),
             jsonb_build_array(
               (l.point::jsonb->>'x')::numeric + (l.point::jsonb->>'width')::numeric,
               (l.point::jsonb->>'y')::numeric + (l.point::jsonb->>'height')::numeric)
           )::text
         WHEN 'array' THEN    -- POLYGON
           (SELECT jsonb_agg(jsonb_build_array((e->>'x')::numeric, (e->>'y')::numeric))
            FROM jsonb_array_elements(l.point::jsonb) e)::text
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

-- ---------------------------------------------------------------------
-- 6. IDENTITY 시퀀스 재동기화 (이관 PK 가 차지한 영역 다음으로)
-- ---------------------------------------------------------------------
SELECT setval(pg_get_serial_sequence('ls_label','lbl_id'),       (SELECT max(lbl_id)  FROM ls_label),       true);
SELECT setval(pg_get_serial_sequence('ls_data_raw','raw_sn'),    (SELECT max(raw_sn)  FROM ls_data_raw),    true);
SELECT setval(pg_get_serial_sequence('ls_data_src','src_sn'),    (SELECT max(src_sn)  FROM ls_data_src),    true);
SELECT setval(pg_get_serial_sequence('ls_data_lbl','lbl_sn'),    (SELECT max(lbl_sn)  FROM ls_data_lbl),    true);

COMMIT;
\echo '== 02_load_transform 완료. 다음: 03_verify.sql 로 검증 =='
