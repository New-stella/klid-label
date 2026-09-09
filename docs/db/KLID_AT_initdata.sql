-- ============================================================================
-- 학습데이터 저작도구 — 코드 및 초기 데이터 생성 스크립트 (klid_at)
--
-- ★ KLID_AT_schema.sql 을 먼저 실행한 뒤 이 파일을 실행한다. 순서를 바꾸면 대상 표가 없어 실패한다.
--
--   psql -d <데이터베이스> -f KLID_AT_schema.sql
--   psql -d <데이터베이스> -f KLID_AT_initdata.sql
--
-- 담긴 것 — 66행 (공통코드·시스템 설정 기본값·스케줄러 잠금 행 등)
--
-- 이 데이터가 없으면 스키마는 서도 앱이 돌지 않는다. 그래서 별도 산출물로 낸다.
-- 사람이 한 줄씩 읽고 고를 수 있도록 INSERT 로 낸다(대량 적재용 형식이 아니다).
--
-- ⚠ 수기 편집 금지(생성물). 값을 바꾸려면 스키마 변경 파일의 시드를 고치고 다시 생성한다.
--
-- 생성 시각: 2026-09-09 17:44:28+0900
-- ============================================================================

--
-- PostgreSQL database dump
--

\restrict jeIc9HXsIBbJu53rxECMBkj3UNX6g1jyiYqS0Kq58BT66dWV2uxCuOTttRe50Is

-- Dumped from database version 16.13
-- Dumped by pg_dump version 16.13 (Homebrew)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Data for Name: ls_acnt_user; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_ai_srvr; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_ai_srvr_altmnt; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_ai_srvr_usg; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_raw; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_auth_work_lock; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_authrt_grant_atmpt; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_bat_rty_wtng; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_batch_proc_log; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_clip_schedule_que; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_control_notify_fallback; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug_dscd; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug_job; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug_job_file; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug_lbl_map; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_aug_rvw; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_ingest; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_issue; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_label; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (1, '사람', '#E74C3C', 'BBOX', 1, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'person');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (2, '자동차', '#3498DB', 'BBOX', 2, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'car');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (3, '자전거', '#9B59B6', 'BBOX', 3, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'bicycle');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (4, '오토바이', '#1ABC9C', 'BBOX', 4, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'motorcycle');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (5, '버스', '#F39C12', 'BBOX', 5, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'bus');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (6, '트럭', '#34495E', 'BBOX', 6, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, 'truck');
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (7, '화재', '#FF5733', 'POLYGON', 8, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, NULL);
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (8, '연기', '#7F8C8D', 'POLYGON', 9, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, NULL);
INSERT INTO klid_at.ls_label (lbl_id, lbl_nm, colr_vl, lbl_type_cd, sort_seq, use_yn, reg_id, reg_dt, mdfcn_id, mdfcn_dt, dtct_type_cd) VALUES (9, '침수', '#2980B9', 'POLYGON', 10, 'Y', 'SYSTEM', '2026-09-09 08:44:26.895678', NULL, NULL, NULL);


--
-- Data for Name: ls_data_lbl; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_label_attr; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_lbl_attr_val; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_lbl_hstry; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_meta; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_meta_review; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_src; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_data_src_hstry; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_dataset_export; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_dataset_video_meta; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_deident_proc_log; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_deident_report; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_eblc_uld_job; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_eblc_uld_job_artcl; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_evnt_anno; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_evnt_anno_review; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_evnt_ctgry; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('01', '0001', '침수(범람)', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('01', '0002', '산사태', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('02', '0001', '화재', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('02', '0002', '쓰러짐', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('02', '0005', '파손', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('03', '0001', '교통사고', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('05', '0001', '싸움', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('05', '0002', '흉기소지', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('05', '0007', '납치(유괴)', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('07', '0002', '기타 상황', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_ctgry (evnt_clsf_cd, evnt_ctgry_cd, evnt_ctgry_nm, reg_dt) VALUES ('08', '0001', '배회', '2026-09-09 08:44:26.895678');


--
-- Data for Name: ls_evnt_type; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV01000101', NULL, NULL, '01', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV01000102', NULL, NULL, '01', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV01000103', NULL, NULL, '01', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV01000201', NULL, NULL, '01', '0002', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV02000101', NULL, NULL, '02', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV02000102', NULL, NULL, '02', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV02000201', NULL, NULL, '02', '0002', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV02000501', NULL, NULL, '02', '0005', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV03000101', NULL, NULL, '03', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV03000102', NULL, NULL, '03', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV03000103', NULL, NULL, '03', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV05000101', NULL, NULL, '05', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV05000201', NULL, NULL, '05', '0002', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV05000701', NULL, NULL, '05', '0007', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV08000101', NULL, NULL, '08', '0001', 'Y', '2026-09-09 08:44:26.895678');
INSERT INTO klid_at.ls_evnt_type (evnt_type_cd, evnt_nm, optr_indct_nm, evnt_clsf_cd, evnt_ctgry_cd, clct_yn, reg_dt) VALUES ('EV07000201', NULL, NULL, '07', '0002', 'N', '2026-09-09 08:44:26.895678');


--
-- Data for Name: ls_issue_comment; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_label_preset; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_label_preset_code; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_label_version; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_marking; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_meta_repl_outbox; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_mngr_pswd; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_mon_noti_acml; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_notice; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_notice_attach; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_otsd_ctgry_mpng; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_otsd_datst_trnsf_hstry; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_output_ver_snpsh; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_tus_uld; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_uld; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_uld_frme; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_uld_lbl; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_user_evnt_anno; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_user_label; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_portal_user_meta; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_raw_data_status; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_system_config; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('BATCH_INTERVAL_SEC', '60', 'NUMBER', '배치 트리거 간격 (초, 10~3600)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('BATCH_CONCURRENCY', '1', 'NUMBER', '동시 배치 잡 수 (1=직렬, 1~10)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('YOLO_IOU', '50', 'NUMBER', 'YOLO NMS IoU 임계값 백분율 (30~80, 사용 시 /100)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('YOLO_CONF_THRESHOLD', '25', 'NUMBER', 'YOLO 신뢰도 임계값 백분율 (25~80, 사용 시 /100)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('POLYGON_SIMPLIFY_TOLERANCE', '1.0', 'DECIMAL', '폴리곤 경계 단순화 epsilon px (0.0~50.0, Douglas-Peucker)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('portal.upload.frame-interval-sec', '5', 'NUMBER', '포털 업로드 영상 프레임 추출 간격(초, 1~600)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('autolabel.polygon.max-boxes', '20', 'NUMBER', '폴리곤 오토라벨 SAM 분할 박스 상한 (1~100)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('eventtype.excluded-class-codes', '["08"]', 'JSON', '이벤트 필터 옵션에서 제외할 대분류 코드 목록(기본 08=배회)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('kpst.deid.masking-type', '0', 'NUMBER', '비식별 마스킹 방식 (0 색상 / 2 모자이크 / 3 블러)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('kpst.deid.masking-range', '1.0', 'DECIMAL', '비식별 마스킹 영역 배율 (0.5~2.0)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('kpst.deid.db-save', '0', 'NUMBER', '비식별 처리 프레임 저장 여부 (0 저장 안 함 / 1 저장)', 'SYSTEM', '2026-09-09 08:44:25.984229');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('portal.datamart.retention-days', '7', 'NUMBER', '포털 데이터마트 라벨 보존일수 (1~3650)', 'SYSTEM', '2026-09-09 08:44:26.814726');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('portal.upload.retention-days', '7', 'NUMBER', '포털 업로드 자산 보존일수 (1~3650)', 'SYSTEM', '2026-09-09 08:44:26.814726');
INSERT INTO klid_at.ls_system_config (stng_key, stng_value, stng_type_cd, expln, mdfr_id, mdfcn_dt) VALUES ('portal.upload.failed-retention-days', '1', 'NUMBER', '포털 업로드 실패 자산 보존일수 (1~3650)', 'SYSTEM', '2026-09-09 08:44:26.814726');


--
-- Data for Name: ls_task_altmnt; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_task_evnt_log; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_tus_upload; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_user_role; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_vrfc_evnt_type; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('fire', '화재', '불꽃 등 화재 상황', 1, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('smoke', '연기', '연기 등 화재 상황', 2, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('fall', '쓰러짐', '사람이 쓰러지거나 바닥에 누워 있는 상황', 3, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('violence', '폭력', '폭행, 몸싸움, 물리적 충돌 상황', 4, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('flooding', '침수', '물이 차오르거나 공간이 물에 잠긴 상황', 5, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('car_accident', '교통사고', '차량 충돌, 전복, 사고 정황', 6, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_type (vrfc_evnt_type_cd, vrfc_evnt_type_nm, vrfc_evnt_type_expln, sort_seq, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES ('kidnapping', '납치', '강제로 끌고 가거나 납치로 의심되는 상황', 7, NULL, '2026-09-09 08:44:26.944538', NULL, NULL);


--
-- Data for Name: ls_vrfc_evnt_qstn; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (1, 'fire', 1, '영상에서 ''화염이 보이는 불'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (2, 'smoke', 1, '영상에서 ''특정 지점에서 피어올라 확산되는 연기'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (3, 'fall', 1, '영상에서 ''사람이 바닥에 쓰러지거나 쓰러져 있음'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (4, 'violence', 1, '영상에서 ''신체적 충돌을 동반한 싸움'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (5, 'flooding', 1, '영상에서 ''평소 물이 없던 공간이 물에 잠기는 침수'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (6, 'car_accident', 1, '영상에서 ''차량 충돌을 동반한 교통사고'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);
INSERT INTO klid_at.ls_vrfc_evnt_qstn (vrfc_evnt_qstn_sn, vrfc_evnt_type_cd, sort_seq, qstn_cn, reg_id, reg_dt, mdfr_id, mdfcn_dt) VALUES (7, 'kidnapping', 1, '영상에서 ''저항하는 사람을 강제로 데려가는 강제 이동'' 이벤트가 발생하였는지와, 이를 뒷받침하는 근거는 무엇인가?', NULL, '2026-09-09 08:44:26.944538', NULL, NULL);


--
-- Data for Name: ls_webhook_idempotency; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_whk_fail_nmtm; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: ls_whk_sign_use; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_job_details; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_blob_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_calendars; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_cron_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_fired_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_locks; Type: TABLE DATA; Schema: klid_at; Owner: -
--

INSERT INTO klid_at.qrtz_locks (sched_name, lock_name) VALUES ('KlidAuthoringScheduler', 'TRIGGER_ACCESS');
INSERT INTO klid_at.qrtz_locks (sched_name, lock_name) VALUES ('KlidAuthoringScheduler', 'STATE_ACCESS');


--
-- Data for Name: qrtz_paused_trigger_grps; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_scheduler_state; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_simple_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Data for Name: qrtz_simprop_triggers; Type: TABLE DATA; Schema: klid_at; Owner: -
--



--
-- Name: ls_acnt_user_no_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_acnt_user_no_seq', 9000000000, false);


--
-- Name: ls_ai_srvr_altmnt_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_ai_srvr_altmnt_seq', 1, false);


--
-- Name: ls_auth_work_lock_work_lock_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_auth_work_lock_work_lock_sn_seq', 1, false);


--
-- Name: ls_bat_rty_wtng_bat_rty_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_bat_rty_wtng_bat_rty_sn_seq', 1, false);


--
-- Name: ls_batch_proc_log_batch_proc_log_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_batch_proc_log_batch_proc_log_sn_seq', 1, false);


--
-- Name: ls_clip_schedule_que_que_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_clip_schedule_que_que_sn_seq', 1, false);


--
-- Name: ls_control_notify_fallback_queue_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_control_notify_fallback_queue_sn_seq', 1, false);


--
-- Name: ls_data_aug_data_aug_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_data_aug_sn_seq', 1, false);


--
-- Name: ls_data_aug_dscd_data_aug_dscd_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_dscd_data_aug_dscd_sn_seq', 1, false);


--
-- Name: ls_data_aug_job_aug_job_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_job_aug_job_sn_seq', 1, false);


--
-- Name: ls_data_aug_job_file_aug_job_file_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_job_file_aug_job_file_sn_seq', 1, false);


--
-- Name: ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_lbl_map_data_aug_lbl_map_sn_seq', 1, false);


--
-- Name: ls_data_aug_rvw_data_aug_rvw_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_aug_rvw_data_aug_rvw_sn_seq', 1, false);


--
-- Name: ls_data_ingest_rcptn_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_ingest_rcptn_sn_seq', 1, false);


--
-- Name: ls_data_issue_data_issue_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_issue_data_issue_sn_seq', 1, false);


--
-- Name: ls_data_lbl_attr_val_attr_val_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_lbl_attr_val_attr_val_id_seq', 1, false);


--
-- Name: ls_data_lbl_hstry_lbl_hstry_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_lbl_hstry_lbl_hstry_sn_seq', 1, false);


--
-- Name: ls_data_lbl_lbl_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_lbl_lbl_sn_seq', 1, false);


--
-- Name: ls_data_meta_meta_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_meta_meta_sn_seq', 1, false);


--
-- Name: ls_data_meta_review_data_meta_review_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_meta_review_data_meta_review_sn_seq', 1, false);


--
-- Name: ls_data_raw_raw_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_raw_raw_sn_seq', 1, false);


--
-- Name: ls_data_src_hstry_hstry_seq_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_src_hstry_hstry_seq_seq', 1, false);


--
-- Name: ls_data_src_src_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_data_src_src_sn_seq', 1, false);


--
-- Name: ls_dataset_export_output_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_dataset_export_output_sn_seq', 1, false);


--
-- Name: ls_dataset_video_meta_meta_snpsht_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_dataset_video_meta_meta_snpsht_sn_seq', 1, false);


--
-- Name: ls_deident_proc_log_proc_log_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_deident_proc_log_proc_log_sn_seq', 1, false);


--
-- Name: ls_deident_report_deident_report_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_deident_report_deident_report_sn_seq', 1, false);


--
-- Name: ls_eblc_uld_job_artcl_eblc_uld_job_artcl_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_eblc_uld_job_artcl_eblc_uld_job_artcl_sn_seq', 1, false);


--
-- Name: ls_eblc_uld_job_eblc_uld_job_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_eblc_uld_job_eblc_uld_job_sn_seq', 1, false);


--
-- Name: ls_evnt_anno_evnt_anno_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_evnt_anno_evnt_anno_sn_seq', 1, false);


--
-- Name: ls_evnt_anno_review_rvw_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_evnt_anno_review_rvw_sn_seq', 1, false);


--
-- Name: ls_issue_comment_issue_comment_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_issue_comment_issue_comment_sn_seq', 1, false);


--
-- Name: ls_label_attr_attr_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_label_attr_attr_id_seq', 1, false);


--
-- Name: ls_label_label_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_label_label_id_seq', 9, true);


--
-- Name: ls_label_preset_code_code_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_label_preset_code_code_sn_seq', 1, false);


--
-- Name: ls_label_preset_preset_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_label_preset_preset_id_seq', 1, false);


--
-- Name: ls_label_version_label_version_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_label_version_label_version_sn_seq', 1, false);


--
-- Name: ls_marking_marking_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_marking_marking_sn_seq', 1, false);


--
-- Name: ls_meta_repl_outbox_outbox_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_meta_repl_outbox_outbox_sn_seq', 1, false);


--
-- Name: ls_mon_noti_acml_noti_acml_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_mon_noti_acml_noti_acml_sn_seq', 1, false);


--
-- Name: ls_notice_attach_attach_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_notice_attach_attach_sn_seq', 1, false);


--
-- Name: ls_notice_notice_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_notice_notice_sn_seq', 1, false);


--
-- Name: ls_otsd_ctgry_mpng_mpng_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_otsd_ctgry_mpng_mpng_sn_seq', 1, false);


--
-- Name: ls_otsd_datst_trnsf_hstry_trnsf_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_otsd_datst_trnsf_hstry_trnsf_sn_seq', 1, false);


--
-- Name: ls_output_ver_snpsh_output_ver_snpsh_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_output_ver_snpsh_output_ver_snpsh_sn_seq', 1, false);


--
-- Name: ls_portal_uld_frme_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_uld_frme_seq', 1, false);


--
-- Name: ls_portal_uld_lbl_uld_lbl_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_uld_lbl_uld_lbl_sn_seq', 1, false);


--
-- Name: ls_portal_uld_uld_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_uld_uld_sn_seq', 1, false);


--
-- Name: ls_portal_user_evnt_anno_user_evnt_anno_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_user_evnt_anno_user_evnt_anno_sn_seq', 1, false);


--
-- Name: ls_portal_user_label_user_lbl_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_user_label_user_lbl_sn_seq', 1, false);


--
-- Name: ls_portal_user_meta_user_meta_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_portal_user_meta_user_meta_sn_seq', 1, false);


--
-- Name: ls_task_altmnt_assignment_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_task_altmnt_assignment_id_seq', 1, false);


--
-- Name: ls_task_evnt_log_evnt_id_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_task_evnt_log_evnt_id_seq', 1, false);


--
-- Name: ls_vrfc_evnt_qstn_vrfc_evnt_qstn_sn_seq; Type: SEQUENCE SET; Schema: klid_at; Owner: -
--

SELECT pg_catalog.setval('klid_at.ls_vrfc_evnt_qstn_vrfc_evnt_qstn_sn_seq', 7, true);


--
-- PostgreSQL database dump complete
--

\unrestrict jeIc9HXsIBbJu53rxECMBkj3UNX6g1jyiYqS0Kq58BT66dWV2uxCuOTttRe50Is

