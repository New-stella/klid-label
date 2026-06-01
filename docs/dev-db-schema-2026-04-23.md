# klid_system DB 스키마

> 조회일: 2026-04-23
> DB: klid_system @ 192.168.102.101:13307

---

## 목차

- [CM_ — 공통 코드](#cm_--공통-코드)
  - [CM_CODE](#cm_code)
  - [CM_GROUP_CODE](#cm_group_code)
- [LS_ — 라벨링 시스템](#ls_--라벨링-시스템)
  - [LS_ATCH_FILE](#ls_atch_file)
  - [LS_AUTHRT_MPNG](#ls_authrt_mpng)
  - [LS_DATA_AUG](#ls_data_aug)
  - [LS_DATA_ISSUE](#ls_data_issue)
  - [LS_DATA_LBL](#ls_data_lbl)
  - [LS_DATA_LBL_HSTRY](#ls_data_lbl_hstry)
  - [LS_DATA_META](#ls_data_meta)
  - [LS_DATA_META_HSTRY](#ls_data_meta_hstry)
  - [LS_DATA_RAW](#ls_data_raw)
  - [LS_DATA_RAW_HSTRY](#ls_data_raw_hstry)
  - [LS_DATA_SET](#ls_data_set)
  - [LS_DATA_SRC](#ls_data_src)
  - [LS_DATA_SRC_HSTRY](#ls_data_src_hstry)
  - [LS_DATA_USER](#ls_data_user)
  - [LS_NTC_BBS](#ls_ntc_bbs)
  - [LS_PJT](#ls_pjt)
  - [LS_PJT_DATA_MPNG](#ls_pjt_data_mpng)
  - [LS_PJT_DATA_STATS](#ls_pjt_data_stats)
  - [LS_PJT_DATA_STTS](#ls_pjt_data_stts)
  - [LS_PJT_DDLN](#ls_pjt_ddln)
  - [LS_PJT_JOB_STATS](#ls_pjt_job_stats)
  - [LS_PJT_LBL](#ls_pjt_lbl)
  - [LS_PJT_META](#ls_pjt_meta)
  - [LS_PJT_STG](#ls_pjt_stg)
  - [LS_PJT_STG_IGI](#ls_pjt_stg_igi)
  - [LS_PJT_STG_PRC](#ls_pjt_stg_prc)
  - [LS_PJT_USER_AUTHRT](#ls_pjt_user_authrt)
  - [LS_PJT_USER_AUTHRT_HSTRY](#ls_pjt_user_authrt_hstry)
  - [LS_USER_MENU](#ls_user_menu)
  - [LS_USER_ROLE](#ls_user_role)
- [MNG_ACCT_ — 계정 관리](#mng_acct_--계정-관리)
  - [MNG_ACCT_AUTHRT](#mng_acct_authrt)
  - [MNG_ACCT_GPKI_CRTF](#mng_acct_gpki_crtf)
  - [MNG_ACCT_LGN_SESSION](#mng_acct_lgn_session)
  - [MNG_ACCT_MENU_AUTHRT](#mng_acct_menu_authrt)
  - [MNG_ACCT_USER](#mng_acct_user)
  - [MNG_ACCT_USER_AUTHRT](#mng_acct_user_authrt)
- [MNG_BSINS_ — 비즈니스 지원](#mng_bsins_--비즈니스-지원)
  - [MNG_BSINS_RPLY_BBS](#mng_bsins_rply_bbs)
  - [MNG_BSINS_RPLY_BBS_FILE](#mng_bsins_rply_bbs_file)
  - [MNG_BSINS_SPRT_BBS](#mng_bsins_sprt_bbs)
  - [MNG_BSINS_SPRT_BBS_FILE](#mng_bsins_sprt_bbs_file)
- [MNG_CLIP_ — 클립 관리](#mng_clip_--클립-관리)
  - [MNG_CLIP_DETAIL](#mng_clip_detail)
  - [MNG_CLIP_EVNT_HSTRY](#mng_clip_evnt_hstry)
  - [MNG_CLIP_EVNT_LST](#mng_clip_evnt_lst)
  - [MNG_CLIP_EVNT_STATISTICS_COLLECTION](#mng_clip_evnt_statistics_collection)
  - [MNG_CLIP_EVNT_STATISTICS_JOB](#mng_clip_evnt_statistics_job)
  - [MNG_CLIP_EVNT_STATISTICS_VIDEO](#mng_clip_evnt_statistics_video)
  - [MNG_CLIP_MASTER](#mng_clip_master)
  - [MNG_CLIP_SCHEDULE_QUE](#mng_clip_schedule_que)
  - [MNG_CLIP_THUMBNAIL](#mng_clip_thumbnail)
- [MNG_DATA_ — 데이터셋 관리](#mng_data_--데이터셋-관리)
  - [MNG_DATA_SET](#mng_data_set)
  - [MNG_DATA_SET_ATCH_FILE](#mng_data_set_atch_file)
  - [MNG_DATA_SET_CTGRY](#mng_data_set_ctgry)
  - [MNG_DATA_SET_DSTRB](#mng_data_set_dstrb)
  - [MNG_DATA_SET_DSTRB_DTL](#mng_data_set_dstrb_dtl)
  - [MNG_DATA_SET_EVNT_CD](#mng_data_set_evnt_cd)
  - [MNG_DATA_SET_ORGNL_VDO](#mng_data_set_orgnl_vdo)
- [MNG_EX_ — 외부 연계](#mng_ex_--외부-연계)
  - [MNG_EX_CLCT](#mng_ex_clct)
  - [MNG_EX_EVNT_TYPE](#mng_ex_evnt_type)
  - [MNG_EX_EVNT_TYPE_MAP](#mng_ex_evnt_type_map)
  - [MNG_EX_LOCAL_GOV](#mng_ex_local_gov)
  - [MNG_EX_SEND_SCHEDULE](#mng_ex_send_schedule)
  - [MNG_EX_SYSTEM_LOG](#mng_ex_system_log)
- [MNG_GOV_ — 기관 관리](#mng_gov_--기관-관리)
  - [MNG_GOV_OG](#mng_gov_og)
  - [MNG_GOV_OG_TMP](#mng_gov_og_tmp)
- [MNG_INGEST_ — 수집 관리](#mng_ingest_--수집-관리)
  - [MNG_INGEST_RAW_REQ_QUE](#mng_ingest_raw_req_que)
  - [MNG_INGEST_UPLD_CHUNK](#mng_ingest_upld_chunk)
  - [MNG_INGEST_UPLD_SESSION](#mng_ingest_upld_session)
- [MNG_RESOURCE_ — 리소스 관리](#mng_resource_--리소스-관리)
  - [MNG_RESOURCE_CCTV](#mng_resource_cctv)
  - [MNG_RESOURCE_CCTV_FAIL](#mng_resource_cctv_fail)
  - [MNG_RESOURCE_EXCEL_IMPORT_JOB](#mng_resource_excel_import_job)
  - [MNG_RESOURCE_POLE](#mng_resource_pole)
  - [MNG_RESOURCE_POLE_FAIL](#mng_resource_pole_fail)
  - [MNG_RESOURCE_STATS_NTN](#mng_resource_stats_ntn)
  - [MNG_RESOURCE_STATS_PXL](#mng_resource_stats_pxl)
  - [MNG_RESOURCE_STATS_STRG](#mng_resource_stats_strg)
  - [MNG_RESOURCE_STATS_TECH](#mng_resource_stats_tech)
  - [MNG_RESOURCE_STATS_YEAR](#mng_resource_stats_year)
- [MNG_SET_ — 설정/이력](#mng_set_--설정이력)
  - [MNG_SET_HSTRY_PRVC_ACCS](#mng_set_hstry_prvc_accs)
  - [MNG_SET_HSTRY_USER_DEL](#mng_set_hstry_user_del)
  - [MNG_SET_HSTRY_USER_ROLE](#mng_set_hstry_user_role)
- [MNG_ — 기타 (메뉴, 설정 등)](#mng_--기타-메뉴,-설정-등)
  - [MNG_MENU](#mng_menu)
  - [MNG_SGG](#mng_sgg)
  - [MNG_STNG](#mng_stng)
- [QRTZ_ — Quartz 스케줄러](#qrtz_--quartz-스케줄러)
  - [QRTZ_BLOB_TRIGGERS](#qrtz_blob_triggers)
  - [QRTZ_CALENDARS](#qrtz_calendars)
  - [QRTZ_CRON_TRIGGERS](#qrtz_cron_triggers)
  - [QRTZ_FIRED_TRIGGERS](#qrtz_fired_triggers)
  - [QRTZ_JOB_DETAILS](#qrtz_job_details)
  - [QRTZ_LOCKS](#qrtz_locks)
  - [QRTZ_PAUSED_TRIGGER_GRPS](#qrtz_paused_trigger_grps)
  - [QRTZ_SCHEDULER_STATE](#qrtz_scheduler_state)
  - [QRTZ_SIMPLE_TRIGGERS](#qrtz_simple_triggers)
  - [QRTZ_SIMPROP_TRIGGERS](#qrtz_simprop_triggers)
  - [QRTZ_TRIGGERS](#qrtz_triggers)

---

## 테이블 그룹 분류

테이블 prefix 기준으로 그룹화:

| 그룹 | 설명 | 테이블 수 |
|------|------|:--------:|
| **CM_** | 공통 코드 | 2 |
| **LS_** | 라벨링 시스템 | 30 |
| **MNG_ACCT_** | 계정 관리 | 6 |
| **MNG_BSINS_** | 비즈니스 지원 | 4 |
| **MNG_CLIP_** | 클립 관리 | 9 |
| **MNG_DATA_** | 데이터셋 관리 | 7 |
| **MNG_EX_** | 외부 연계 | 6 |
| **MNG_GOV_** | 기관 관리 | 2 |
| **MNG_INGEST_** | 수집 관리 | 3 |
| **MNG_RESOURCE_** | 리소스 관리 | 10 |
| **MNG_SET_** | 설정/이력 | 3 |
| **MNG_** | 기타 (메뉴, 설정 등) | 3 |
| **QRTZ_** | Quartz 스케줄러 | 11 |

---

## CM_ — 공통 코드

### CM_CODE
> 공통 코드 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| GROUP_CD | varchar(20) | ✗ | 🔑 PK | NULL | 그룹 코드 |
| CM_CD | varchar(20) | ✗ | 🔑 PK | NULL | 코드 명 |
| CM_CD_EXPLN | varchar(4000) | ✗ |  | '' | 코드 설명 |
| USE_YN | varchar(1) | ✗ |  | 'Y' | 사용 여부 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 생성 일자 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정 일자 |
| CRTR_ID | varchar(30) | ✗ |  | '' | 등록자 ID |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자 ID |
| SORT_SEQ | int(11) | ✗ |  | 1 | 정렬 순서 |

### CM_GROUP_CODE
> 그룹 코드 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| GROUP_CD | varchar(20) | ✗ | 🔑 PK | NULL | 그룹 코드 |
| GROUP_NM | varchar(200) | ✗ |  | '' | 그룹 명 |
| GROUP_EXPLN | varchar(4000) | ✗ |  | '' | 그룹 코드 설명 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 생성 일자 |
| MDFCN_DT | datetime | ✗ |  | current_timestamp() | 수정 일자 |
| CRTR_ID | varchar(30) | ✗ |  | '' | 등록자 ID |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자 ID |

---

## LS_ — 라벨링 시스템

### LS_ATCH_FILE
> 첨부파일

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| ATCH_FILE_ID | varchar(36) | ✗ | 🔑 PK | NULL | 첨부파일아이디 |
| NTC_ID | varchar(36) | ✓ |  | NULL | 공지아이디 |
| ORGNL_FILE_NM | varchar(200) | ✗ |  | NULL | 원본파일명 |
| ATCH_FILE_NM | varchar(200) | ✗ |  | NULL | 첨부파일명 |
| ATCH_FILE_PATH | varchar(1000) | ✗ |  | NULL | 첨부파일경로 |
| ATCH_FILE_SZ | bigint(20) | ✗ |  | NULL | 첨부파일크기 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_AUTHRT_MPNG
> 사용자유형매핑

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_CD | varchar(20) | ✗ | 🔑 PK | NULL | 권한코드 |
| USER_TYPE_CD | varchar(20) | ✓ |  | NULL | 사용자유형코드 |

### LS_DATA_AUG
> 데이터 증강

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_AUG_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터증강일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ |  | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✗ |  | NULL | 데이터원천일련번호 |
| PJT_AUG_OPT_CD | varchar(20) | ✗ |  | NULL | 증강옵션코드 |
| AUG_FILE_PATH | varchar(500) | ✓ |  | NULL | 증강이미지파일경로 |
| FILE_SZ | bigint(20) | ✓ |  | NULL | 파일크기 |
| WDTH | int(11) | ✓ |  | NULL | 너비 |
| HGT | int(11) | ✓ |  | NULL | 높이 |
| AUG_PROC_STTS_CD | varchar(20) | ✗ |  | NULL | 증강처리상태코드 |
| ERROR_CD | varchar(50) | ✓ |  | NULL | 에러코드 |
| ERROR_MSG | varchar(1000) | ✓ |  | NULL | 에러메세지 |
| RTRY_CNT | int(11) | ✓ |  | NULL | 재시도수 |
| LAST_TRY_DT | datetime | ✓ |  | NULL | 최종시도시각 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| BRGT_OFST | float | ✓ |  | NULL | 밝기 보정값(-255~+255) |
| DRKN_OFST | float | ✓ |  | NULL | 어둡기 보정값(-255~+255) |

### LS_DATA_ISSUE
> 데이터이슈

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_ISSUE_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터이슈일련번호 |
| UP_DATA_ISSUE_SN | bigint(20) | ✓ |  | NULL | 상위데이터이슈일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원천일련번호 |
| ISSUE_TYPE_CD | varchar(20) | ✗ |  | NULL | 이슈유형코드 |
| ISSUE_DTL_CD | varchar(20) | ✓ |  | NULL | 이슈상세코드 |
| RJCT_DTL_CD | varchar(20) | ✓ |  | NULL | 반려상세코드 |
| MNGR_RJCT_DTL_CD | varchar(20) | ✓ |  | NULL | 관리자반려상세코드 |
| ISSUE_CN | varchar(4000) | ✓ |  | NULL | 이슈내용 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_LBL
> 데이터라벨

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_LBL_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터라벨일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원천일련번호 |
| LBL_SN | bigint(20) | ✗ | 🔗 FK | NULL | 라벨일련번호 |
| TRCK_USE_YN | varchar(1) | ✗ |  | NULL | 트랙사용여부 |
| TRCK_FRM_YN | varchar(1) | ✓ |  | NULL | 트랙프레임여부 |
| TRCK_ID | varchar(30) | ✓ |  | NULL | 트랙아이디 |
| POINT | text | ✗ |  | NULL | 포인트 |
| ATRB | text | ✓ |  | NULL | 속성 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_LBL_HSTRY
> 데이터라벨이력

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_LBL_HSTRY_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터라벨이력일련번호 |
| ACTION_TYPE_CD | varchar(20) | ✓ |  | NULL | 행위유형 |
| ACTION_DT | datetime(3) | ✓ | 🔗 FK | NULL | 행위일시 |
| DATA_LBL_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터라벨일련번호 |
| PJT_SN | bigint(20) | ✓ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✓ |  | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✓ | 🔗 FK | NULL | 데이터원천일련번호 |
| LBL_SN | bigint(20) | ✓ |  | NULL | 라벨일련번호 |
| TRCK_USE_YN | varchar(1) | ✓ |  | NULL | 트랙사용여부 |
| TRCK_FRM_YN | varchar(1) | ✓ |  | NULL | 트랙프레임여부 |
| TRCK_ID | varchar(30) | ✓ |  | NULL | 트랙아이디 |
| POINT | text | ✓ |  | NULL | 포인트 |
| ATRB | text | ✓ |  | NULL | 속성 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_META
> 데이터메타

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_META_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터메타일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원천일련번호 |
| META_SN | bigint(20) | ✗ | 🔗 FK | NULL | 메타일련번호 |
| ATRB | varchar(4000) | ✓ |  | NULL | 속성 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_META_HSTRY
> 데이터메타이력

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_META_HSTRY_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터메타이력일련번호 |
| ACTION_TYPE_CD | varchar(20) | ✓ |  | NULL | 행위유형 |
| ACTION_DT | datetime(3) | ✓ | 🔗 FK | NULL | 행위일시 |
| DATA_META_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터메타일련번호 |
| PJT_SN | bigint(20) | ✓ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✓ | 🔗 FK | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✓ | 🔗 FK | NULL | 데이터원천일련번호 |
| META_SN | bigint(20) | ✓ |  | NULL | 메타일련번호 |
| ATRB | varchar(4000) | ✓ |  | NULL | 속성 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_RAW
> 데이터원시

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_RAW_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원시일련번호 |
| PLFM_ID | varchar(36) | ✗ | 🔗 FK | NULL | 플랫폼아이디 |
| RAW_FILE_NM | varchar(200) | ✗ |  | NULL | 원시파일이름 |
| RAW_FILE_PATH | varchar(1000) | ✗ |  | NULL | 원시파일경로 |
| RAW_DATA_TYPE_CD | varchar(20) | ✗ |  | NULL | 원시데이터유형코드 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일파일경로 |
| FILE_FMT | varchar(10) | ✗ |  | NULL | 파일형식 |
| SHT_DT | datetime | ✓ |  | NULL | 촬영일자 |
| SHT_PRSN | varchar(200) | ✓ |  | NULL | 촬영자 |
| SHT_EQPMNT | varchar(200) | ✓ |  | NULL | 촬영장비 |
| SHT_LC | varchar(500) | ✓ |  | NULL | 촬영지역명 |
| LCNS | varchar(200) | ✓ |  | NULL | 라이선스 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일크기 |
| VDO_LEN | int(11) | ✗ |  | NULL | 영상길이 |
| VDO_CDC | varchar(30) | ✓ |  | NULL | 영상코덱 |
| FPS | int(11) | ✗ |  | NULL | 프레임재생속도 |
| FRM_CNT | int(11) | ✗ |  | NULL | 프레임수 |
| ASPRT_RT | varchar(10) | ✓ |  | NULL | 종횡비 |
| WDTH | int(11) | ✗ |  | NULL | 너비 |
| HGT | int(11) | ✗ |  | NULL | 높이 |
| RSLTN | varchar(20) | ✓ |  | NULL | 해상도 |
| BIT_RATE | varchar(10) | ✓ |  | NULL | 비트레이트 |
| LCLGV_CD | varchar(20) | ✓ |  | NULL | 기관코드 |
| STDG_CD | varchar(10) | ✓ |  | NULL | 법정동코드 |
| SESN_CD | varchar(20) | ✓ |  | NULL | 계절코드 |
| WTHR_CD | varchar(20) | ✓ |  | NULL | 날씨코드 |
| HR_TYPE_CD | varchar(20) | ✓ |  | NULL | 시간유형코드 |
| EVNT_TYPE_CD | varchar(20) | ✓ |  | NULL | 이벤트유형코드 |
| EVNT_NM | varchar(200) | ✓ |  | NULL | 이벤트명 |
| EVNT_BGNG_DT | datetime | ✓ |  | NULL | 이벤트시작시간 |
| EVNT_END_DT | datetime | ✓ |  | NULL | 이벤트종료시간 |
| PRVC_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| DE_IDNTF_YN | varchar(1) | ✓ |  | NULL | 비식별여부 |
| AI_CRT_YN | varchar(1) | ✗ |  | NULL | AI생성여부 |
| USER_REG_YN | varchar(1) | ✗ |  | NULL | 사용자등록여부 |
| MNTR_CN | varchar(4000) | ✓ |  | NULL | 관제일지 |
| WGS84_LAT | decimal(10,7) | ✓ |  | NULL | WGS84위도 |
| WGS84_LOT | decimal(10,7) | ✓ |  | NULL | WGS84경도 |
| CLCT_PATH | varchar(1000) | ✓ |  | NULL | 수집경로 |
| CLCT_SRC | varchar(300) | ✓ |  | NULL | 수집출처 |
| CCTV_NM | varchar(300) | ✓ |  | NULL | CCTV명 |
| VMS_CCTV_ID | varchar(30) | ✓ |  | NULL | VMS CCTV ID |
| SHT_PLC | varchar(500) | ✓ |  | NULL | 촬영지 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| USE_YN | varchar(1) | ✗ |  | 'Y' | 사용여부 |

### LS_DATA_RAW_HSTRY
> 데이터원시이력

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_RAW_HSTRY_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원시이력일련번호 |
| ACTION_TYPE_CD | varchar(20) | ✓ |  | NULL | 행위유형 |
| ACTION_DT | datetime(3) | ✓ |  | NULL | 행위일시 |
| DATA_RAW_SN | bigint(20) | ✓ |  | NULL | 데이터원시일련번호 |
| PLFM_ID | varchar(36) | ✗ |  | NULL | 플랫폼아이디 |
| RAW_FILE_NM | varchar(200) | ✗ |  | NULL | 원시파일이름 |
| RAW_FILE_PATH | varchar(1000) | ✗ |  | NULL | 원시파일경로 |
| RAW_DATA_TYPE_CD | varchar(20) | ✗ |  | NULL | 원시데이터유형코드 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일파일경로 |
| FILE_FMT | varchar(10) | ✗ |  | NULL | 파일형식 |
| SHT_DT | datetime | ✓ |  | NULL | 촬영일자 |
| SHT_PRSN | varchar(200) | ✓ |  | NULL | 촬영자 |
| SHT_EQPMNT | varchar(200) | ✓ |  | NULL | 촬영장비 |
| SHT_LC | varchar(500) | ✓ |  | NULL | 촬영지역명 |
| LCNS | varchar(200) | ✓ |  | NULL | 라이선스 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일크기 |
| VDO_LEN | int(11) | ✗ |  | NULL | 영상길이 |
| VDO_CDC | varchar(30) | ✓ |  | NULL | 영상코덱 |
| FPS | int(11) | ✗ |  | NULL | 프레임재생속도 |
| FRM_CNT | int(11) | ✗ |  | NULL | 프레임수 |
| ASPRT_RT | varchar(10) | ✓ |  | NULL | 종횡비 |
| WDTH | int(11) | ✗ |  | NULL | 너비 |
| HGT | int(11) | ✗ |  | NULL | 높이 |
| RSLTN | varchar(20) | ✓ |  | NULL | 해상도 |
| BIT_RATE | varchar(10) | ✓ |  | NULL | 비트레이트 |
| LCLGV_CD | varchar(20) | ✓ |  | NULL | 기관코드 |
| STDG_CD | varchar(10) | ✓ |  | NULL | 법정동코드 |
| SESN_CD | varchar(20) | ✓ |  | NULL | 계절코드 |
| WTHR_CD | varchar(20) | ✓ |  | NULL | 날씨코드 |
| HR_TYPE_CD | varchar(20) | ✓ |  | NULL | 시간유형코드 |
| EVNT_TYPE_CD | varchar(20) | ✓ |  | NULL | 이벤트유형코드 |
| EVNT_NM | varchar(200) | ✓ |  | NULL | 이벤트명 |
| EVNT_BGNG_DT | datetime | ✓ |  | NULL | 이벤트시작시간 |
| EVNT_END_DT | datetime | ✓ |  | NULL | 이벤트종료시간 |
| PRVC_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| DE_IDNTF_YN | varchar(1) | ✓ |  | NULL | 비식별여부 |
| AI_CRT_YN | varchar(1) | ✗ |  | NULL | AI생성여부 |
| USER_REG_YN | varchar(1) | ✗ |  | NULL | 사용자등록여부 |
| MNTR_CN | varchar(4000) | ✓ |  | NULL | 관제일지 |
| WGS84_LAT | decimal(10,7) | ✓ |  | NULL | WGS84위도 |
| WGS84_LOT | decimal(10,7) | ✓ |  | NULL | WGS84경도 |
| CLCT_PATH | varchar(1000) | ✓ |  | NULL | 수집경로 |
| CLCT_SRC | varchar(300) | ✓ |  | NULL | 수집출처 |
| CCTV_NM | varchar(300) | ✓ |  | NULL | CCTV명 |
| VMS_CCTV_ID | varchar(30) | ✓ |  | NULL | VMS CCTV ID |
| SHT_PLC | varchar(500) | ✓ |  | NULL | 촬영지 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| USE_YN | varchar(1) | ✓ |  | NULL | 사용유무 |

### LS_DATA_SET
> 데이터셋

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터셋추출일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ |  | NULL | 데이터원시일련번호 |
| DATA_SRC_SN | bigint(20) | ✗ |  | NULL | 데이터원천일련번호 |
| PJT_AUG_OPT_CD | varchar(20) | ✓ |  | NULL | 추출옵션코드 |
| LBL_IMG_EXTR_YN | varchar(1) | ✓ |  | NULL | 라벨링이미지추출여부 |
| DATA_AUG_SN | bigint(20) | ✓ |  | NULL | 데이터증강일련번호 |
| DATA_SET_FILE_PATH | varchar(500) | ✓ |  | NULL | 데이터셋파일경로 |
| DATA_SET_STTS_CD | varchar(20) | ✗ |  | NULL | 데이터셋추출처리상태코드 |
| ERROR_CD | varchar(50) | ✓ |  | NULL | 에러코드 |
| ERROR_MSG | varchar(1000) | ✓ |  | NULL | 에러메시지 |
| RTRY_CNT | int(11) | ✓ |  | 0 | 재시도수 |
| LAST_TRY_DT | datetime | ✓ |  | NULL | 최종시도일시 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | current_timestamp() | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_SRC
> 데이터원천

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SRC_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원천일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔗 FK | NULL | 데이터원시일련번호 |
| PLFM_ID | varchar(36) | ✗ | 🔗 FK | NULL | 플랫폼아이디 |
| DATA_STTS_CD | varchar(20) | ✗ |  | NULL | 데이터상태코드 |
| DATA_DTL_STTS_CD | varchar(20) | ✗ |  | NULL | 데이터상세상태코드 |
| SRC_FILE_NM | varchar(300) | ✗ |  | NULL | 원천파일이름 |
| SRC_FILE_PATH | varchar(1000) | ✗ |  | NULL | 원천파일경로 |
| SRC_BKUP_FILE_PATH | varchar(1000) | ✓ |  | NULL | 원천백업파일경로 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일파일경로 |
| STP_CYCL | int(11) | ✗ |  | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✗ |  | NULL | 검수차수 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일크기 |
| WDTH | int(11) | ✗ |  | NULL | 너비 |
| HGT | int(11) | ✗ |  | NULL | 높이 |
| RSLTN | varchar(20) | ✓ |  | NULL | 해상도 |
| FILE_FMT | varchar(10) | ✓ |  | NULL | 파일형식 |
| FRM_NO | int(11) | ✗ |  | NULL | 프레임번호 |
| PRVC_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| DE_IDNTF_YN | varchar(1) | ✓ |  | NULL | 비식별여부 |
| IMG_EXPLN | varchar(4000) | ✓ |  | NULL | 이미지설명 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_SRC_HSTRY
> 데이터원천이력

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SRC_HSTRY_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원천이력일련번호 |
| ACTION_TYPE_CD | varchar(20) | ✓ |  | NULL | 행위유형 |
| ACTION_DT | datetime(3) | ✓ |  | NULL | 행위일시 |
| DATA_SRC_SN | bigint(20) | ✗ |  | NULL | 데이터원천일련번호 |
| PJT_SN | char(18) | ✓ |  | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ |  | NULL | 데이터원시일련번호 |
| PLFM_ID | varchar(36) | ✗ |  | NULL | 플랫폼아이디 |
| DATA_STTS_CD | varchar(20) | ✗ |  | NULL | 데이터상태코드 |
| DATA_DTL_STTS_CD | varchar(20) | ✗ |  | NULL | 데이터상세상태코드 |
| SRC_FILE_NM | varchar(300) | ✗ |  | NULL | 원천파일이름 |
| SRC_FILE_PATH | varchar(1000) | ✗ |  | NULL | 원천파일경로 |
| SRC_BKUP_FILE_PATH | varchar(1000) | ✓ |  | NULL | 원천백업파일경로 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일파일경로 |
| STP_CYCL | int(11) | ✗ |  | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✗ |  | NULL | 검수차수 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일크기 |
| WDTH | int(11) | ✗ |  | NULL | 너비 |
| HGT | int(11) | ✗ |  | NULL | 높이 |
| RSLTN | varchar(20) | ✓ |  | NULL | 해상도 |
| FILE_FMT | varchar(10) | ✓ |  | NULL | 파일형식 |
| FRM_NO | int(11) | ✗ |  | NULL | 프레임번호 |
| PRVC_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| DE_IDNTF_YN | varchar(1) | ✓ |  | NULL | 비식별여부 |
| IMG_EXPLN | varchar(4000) | ✓ |  | NULL | 이미지설명 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_DATA_USER
> 데이터사용자

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_ULD_ID | varchar(36) | ✗ | 🔑 PK | NULL | 데이타업로드아이디 |
| JOB_ID | varchar(36) | ✓ |  | NULL | 작업아이디 |
| RAW_DATA_TYPE_CD | varchar(20) | ✗ |  | NULL | 원시데이터유형코드 |
| ULD_DATA_STTS_CD | varchar(20) | ✗ |  | NULL | 업로드데이터상태코드 |
| DATA_FILE_PATH | varchar(1000) | ✗ |  | NULL | 데이터파일경로 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일파일경로 |
| EVNT_TYPE_CD | varchar(20) | ✓ |  | NULL | 이벤트유형코드 |
| SESN_CD | varchar(20) | ✓ |  | NULL | 계절코드 |
| WTHR_CD | varchar(20) | ✓ |  | NULL | 날씨코드 |
| HR_TYPE_CD | varchar(20) | ✓ |  | NULL | 시간유형코드 |
| PROMPT_CN | varchar(4000) | ✓ |  | NULL | 프롬프트내용 |
| PROMPT_IMG_USE_YN | varchar(1) | ✓ |  | NULL | 프롬프트이미지사용여부 |
| PROMPT_IMG_FILE_PATH | varchar(1000) | ✓ |  | NULL | 프롬프트이미지파일경로 |
| META_DATA_ATRB | varchar(4000) | ✓ |  | NULL | 메타데이터속성 |
| AI_CRT_YN | varchar(1) | ✗ |  | NULL | AI생성여부 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| SHT_DT | datetime | ✓ |  | NULL | 촬영일자 |
| LCLGV_CD | varchar(20) | ✓ |  | NULL | 기관코드 |
| EVNT_NM | varchar(200) | ✓ |  | NULL | 이벤트명 |
| CTGRY_NM | varchar(100) | ✓ |  | NULL | 이벤트 부제 |
| PRVC_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| DE_IDNTF_YN | varchar(1) | ✓ |  | NULL | 비식별여부 |
| WGS84_LAT | decimal(10,7) | ✓ |  | NULL | WGS84위도 |
| WGS84_LOT | decimal(10,7) | ✓ |  | NULL | WGS84경도 |
| CCTV_NM | varchar(300) | ✓ |  | NULL | CCTV명 |
| VMS_CCTV_ID | varchar(30) | ✓ |  | NULL | VMS CCTV ID |
| SHT_PLC | varchar(500) | ✓ |  | NULL | 촬영지 |
| STDG_CD | varchar(10) | ✓ |  | NULL | 법정동코드 |

### LS_NTC_BBS
> 공지게시판

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| NTC_ID | varchar(36) | ✗ | 🔑 PK | NULL | 공지아이디 |
| NTC_TTL | varchar(300) | ✗ |  | NULL | 공지제목 |
| NTC_CN | varchar(4000) | ✗ |  | NULL | 공지내용 |
| UPEND_FIX_YN | varchar(1) | ✗ |  | NULL | 상단고정여부 |
| INQ_CNT | int(11) | ✗ |  | NULL | 조회수 |
| PBLCN_YN | varchar(1) | ✓ |  | NULL | 발행여부 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT
> 프로젝트

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PJT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 프로젝트일련번호 |
| PJT_ID | varchar(50) | ✗ | UNI | NULL | 프로젝트아이디 |
| PJT_NM | varchar(200) | ✗ |  | NULL | 프로젝트이름 |
| PJT_CN | varchar(4000) | ✓ |  | NULL | 프로젝트내용 |
| PJT_HLP_CN | varchar(4000) | ✓ |  | NULL | 프로젝트도움말 |
| PJT_STTS_CD | varchar(20) | ✗ |  | NULL | 프로젝트상태코드 |
| PJT_DATA_TYPE_CD | varchar(20) | ✗ |  | NULL | 프로젝트데이터유형코드 |
| EVNT_CD | varchar(200) | ✓ |  | NULL | 이벤트코드 |
| GOAL_CNT | bigint(20) | ✗ |  | NULL | 목표수량 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| DDLN_YN | varchar(1) | ✗ |  | NULL | 마감여부 |
| DDLN_DT | datetime | ✓ |  | NULL | 마감일시 |
| TOT_YN | varchar(1) | ✓ |  | NULL | 집계여부 |
| API_TRSM_YN | varchar(1) | ✓ |  | NULL | API전송여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| PJT_AUG_STTS_CD | varchar(20) | ✓ |  | NULL | 증강상태코드 |
| PJT_AUG_REQ_DT | datetime | ✓ |  | NULL | 증강요청시각 |
| PJT_AUG_START_DT | datetime | ✓ |  | NULL | 배치실제시작시각 |
| PJT_AUG_END_DT | datetime | ✓ |  | NULL | 증강종료시각 |
| PJT_DSET_STTS_CD | varchar(20) | ✓ |  | NULL | 데이터셋추출상태 |
| PJT_DSET_REQ_DT | datetime | ✓ |  | NULL | 데이터셋추출요청시각 |
| PJT_DSET_START_DT | datetime | ✓ |  | NULL | 데이터셋추출시작시간 |
| PJT_DSET_END_DT | datetime | ✓ |  | NULL | 데이터셋추출종료시각 |

### LS_PJT_DATA_MPNG
> 프로젝트데이터매핑

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 권한일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원시일련번호 |

### LS_PJT_DATA_STATS
> 프로젝트데이터통계

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PJT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 프로젝트일련번호 |
| STATS_BLN_YM | varchar(7) | ✗ | 🔑 PK | NULL | 통계귀속연월 |
| STATS_BLN_DAY | varchar(2) | ✗ | 🔑 PK | NULL | 통계귀속일 |
| STATS_CRTR_DT | datetime | ✓ |  | NULL | 통계기준일시 |
| PJT_DATA_STTS_CD | varchar(20) | ✗ | 🔑 PK | NULL | 프로젝트데이터상태코드 |
| STP_CYCL | int(11) | ✗ | 🔑 PK | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✗ | 🔑 PK | NULL | 검수차수 |
| VDO_CNT | int(11) | ✓ |  | NULL | 영상수 |
| FRM_CNT | int(11) | ✓ |  | NULL | 프레임수 |
| YM_LAST_YN | varchar(1) | ✓ |  | NULL | 연월최종여부 |

### LS_PJT_DATA_STTS
> 프로젝트데이터상태

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PJT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 프로젝트일련번호 |
| DATA_RAW_SN | bigint(20) | ✗ | 🔑 PK | NULL | 데이터원시일련번호 |
| PJT_PATH_SN | int(11) | ✓ |  | NULL | 프로젝트경로일련번호 |
| STP_CYCL | int(11) | ✗ |  | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✗ |  | NULL | 검수차수 |
| PJT_DATA_STTS_CD | varchar(20) | ✗ |  | NULL | 프로젝트데이터상태코드 |
| FPS | int(11) | ✗ |  | NULL | 프레임재생속도 |
| TRSF_FRM_CNT | int(11) | ✓ |  | NULL | 변환프레임수 |
| TRSF_RSLT_CN | varchar(4000) | ✓ |  | NULL | 변환결과내용 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| FPS_TYPE_CD | varchar(20) | ✓ |  | NULL | FPS유형 |
| RSLTN | varchar(20) | ✓ |  | NULL | 해상도 |

### LS_PJT_DDLN
> 프로젝트마감

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PJT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 프로젝트일련번호 |
| VER | varchar(20) | ✗ | 🔑 PK | NULL | 버전 |
| VER_EXPLN | varchar(4000) | ✓ |  | NULL | 버전설명 |
| EVNT_CD | varchar(200) | ✓ |  | NULL | 이벤트코드 |
| OG_CD_LIST | varchar(500) | ✓ |  | NULL | 기관코드목록 |
| ANONY_INCL_YN | varchar(1) | ✓ |  | NULL | 익명포함여부 |
| ANONY_CNT | bigint(20) | ✓ |  | NULL | 익명건수 |
| PSDO_INCL_YN | varchar(1) | ✓ |  | NULL | 가명포함여부 |
| PSDO_CNT | bigint(20) | ✓ |  | NULL | 가명건수 |
| PRVC_INCL_YN | varchar(1) | ✓ |  | NULL | 개인정보포함여부 |
| PRVC_CNT | bigint(20) | ✓ |  | NULL | 개인정보포함건수 |
| WHOL_CNT | bigint(20) | ✓ |  | NULL | 전체건수 |
| WHOL_CPCT | bigint(20) | ✓ |  | NULL | 전체용량 |
| ETBL_YR | varchar(50) | ✓ |  | NULL | 데이터구축연도 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_JOB_STATS
> 프로젝트작업통계

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 권한일련번호 |
| STATS_BLN_YM | varchar(7) | ✗ | 🔑 PK | NULL | 통계귀속연월 |
| STATS_BLN_DAY | varchar(2) | ✗ | 🔑 PK | NULL | 통계귀속일 |
| STATS_CRTR_DT | datetime | ✓ |  | NULL | 통계기준일시 |
| PJT_DATA_STTS_CD | varchar(20) | ✗ | 🔑 PK | NULL | 프로젝트데이터상태코드 |
| VDO_CNT | int(11) | ✓ |  | NULL | 영상수 |
| FRM_CNT | int(11) | ✓ |  | NULL | 프레임수 |

### LS_PJT_LBL
> 프로젝트라벨

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| LBL_SN | bigint(20) | ✗ | 🔑 PK | NULL | 라벨일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| LBL_ID | varchar(30) | ✗ | 🔗 FK | NULL | 라벨아이디 |
| PRC_TYPE_CD | varchar(20) | ✗ |  | NULL | 가공유형코드 |
| UP_LBL_SN | bigint(20) | ✓ | 🔗 FK | NULL | 상위라벨일련번호 |
| LBL_NM | varchar(200) | ✗ |  | NULL | 라벨명 |
| LBL_COLR | varchar(7) | ✗ |  | NULL | 라벨색상 |
| LBL_CN | varchar(4000) | ✓ |  | NULL | 라벨내용 |
| ATRB_INFO | varchar(4000) | ✓ |  | NULL | 속성정보 |
| SORT_SEQ | int(11) | ✓ |  | NULL | 정렬순서 |
| BSC_RLT_CRD_X | int(11) | ✓ |  | NULL | 기본상대좌표X |
| BSC_RLT_CRD_Y | int(11) | ✓ |  | NULL | 기본상대좌표Y |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_META
> 프로젝트메타

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| META_SN | bigint(20) | ✗ | 🔑 PK | NULL | 메타일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| META_ID | varchar(30) | ✗ | 🔗 FK | NULL | 메타아이디 |
| META_TYPE_CD | varchar(20) | ✗ |  | NULL | 메타유형코드 |
| META_CN | varchar(4000) | ✗ |  | NULL | 메타내용 |
| ATRB_INFO | varchar(4000) | ✓ |  | NULL | 속성정보 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |
| META_NM | varchar(200) | ✗ |  | '' | 메타명 |

### LS_PJT_STG
> 프로젝트스테이지

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STG_SN | bigint(20) | ✗ | 🔑 PK | NULL | 스테이지일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| STP_CYCL | int(11) | ✓ |  | NULL | 단계차수 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_STG_IGI
> 프로젝트스테이지검수

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STG_SN | bigint(20) | ✗ | 🔑 PK | NULL | 스테이지일련번호 |
| IGI_SEQ | int(11) | ✗ | 🔑 PK | NULL | 검수순번 |
| CLCLN_UNTPRC | int(11) | ✓ |  | NULL | 정산단가 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_STG_PRC
> 프로젝트스테이지가공

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STG_SN | bigint(20) | ✗ | 🔑 PK | NULL | 스테이지일련번호 |
| CLCLN_UNTPRC | int(11) | ✓ |  | NULL | 정산단가 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_USER_AUTHRT
> 프로젝트사용자권한

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_SN | bigint(20) | ✗ | 🔑 PK | NULL | 권한일련번호 |
| PJT_SN | bigint(20) | ✗ | 🔗 FK | NULL | 프로젝트일련번호 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| TASK_TYPE_CD | varchar(20) | ✗ |  | NULL | 업무유형코드 |
| STP_CYCL | int(11) | ✓ |  | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✓ |  | NULL | 검수차수 |
| AUTHRT_CN | varchar(4000) | ✓ |  | NULL | 권한내용 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_PJT_USER_AUTHRT_HSTRY
> 프로젝트사용자권한이력

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_HIST_SN | bigint(20) | ✗ | 🔑 PK | NULL | 권한이력일련번호 |
| AUTHRT_SN | bigint(20) | ✗ |  | NULL | 권한일련번호 |
| ACTION_TYPE_CD | varchar(20) | ✓ |  | NULL | 행위유형 |
| ACTION_DT | datetime(3) | ✓ |  | NULL | 행위일시 |
| PJT_SN | bigint(20) | ✓ |  | NULL | 프로젝트일련번호 |
| USER_ID | varchar(30) | ✓ |  | NULL | 사용자아이디 |
| TASK_TYPE_CD | varchar(20) | ✗ |  | NULL | 업무유형코드 |
| STP_CYCL | int(11) | ✓ |  | NULL | 단계차수 |
| IGI_CYCL | int(11) | ✓ |  | NULL | 검수차수 |
| AUTHRT_CN | varchar(4000) | ✓ |  | NULL | 권한내용 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_USER_MENU
> 사용자메뉴

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| MENU_SN | bigint(20) | ✗ | 🔑 PK | NULL | 메뉴일련번호 |
| UP_MENU_SN | bigint(20) | ✓ | 🔗 FK | NULL | 상위메뉴일련번호 |
| MENU_NM | varchar(200) | ✗ |  | NULL | 메뉴명 |
| MENU_CN | varchar(2000) | ✓ |  | NULL | 메뉴내용 |
| MENU_URL | varchar(2000) | ✓ |  | NULL | 메뉴URL |
| SORT_SEQ | int(11) | ✗ |  | NULL | 정렬순서 |
| LVL | int(11) | ✗ |  | NULL | 계층 |
| USE_YN | varchar(1) | ✗ |  | NULL | 사용여부 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### LS_USER_ROLE
> 사용자역할

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| MENU_SN | bigint(20) | ✗ | 🔑 PK | NULL | 메뉴일련번호 |
| USER_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 사용자유형코드 |
| TASK_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 업무유형코드 |
| REG_ID | varchar(30) | ✓ |  | NULL | 등록아이디 |
| REG_DT | datetime | ✓ |  | NULL | 등록일시 |
| MDFCN_ID | varchar(30) | ✓ |  | NULL | 수정아이디 |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

---

## MNG_ACCT_ — 계정 관리

### MNG_ACCT_AUTHRT
> 사용자 권한 매핑 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_CD | varchar(20) | ✗ | 🔑 PK | NULL | 권한 코드 |
| AUTHRT_NM | varchar(100) | ✗ |  | '' | 권한 이름 |
| AUTHRT_CN | varchar(4000) | ✗ |  | '' | 권한 내용 |

### MNG_ACCT_GPKI_CRTF
> GPKI 인증서 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CRTF_SN | varchar(100) | ✗ | 🔑 PK | NULL | 인증서 일련번호 |
| ISSUER_CA | varchar(30) | ✗ | 🔑 PK | NULL | 사용자 아이디 |
| USER_ID | varchar(30) | ✗ | 🔗 FK | NULL | USER 아이디 |
| CRTF_REG_DT | datetime | ✓ |  | current_timestamp() | 인증서 등록일시 |

### MNG_ACCT_LGN_SESSION
> 사용자 로그인 세션 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SESS_ID | varchar(255) | ✗ | 🔑 PK | NULL | SESS 아이디 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| LAST_ACTV_DT | datetime | ✓ |  | NULL | 마지막활동일시 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 생성일시 |
| EXPD_DT | datetime | ✓ |  | NULL | 만료시간 |
| ACTV_STTS | varchar(1) | ✗ |  | NULL | ACTV 상태 |
| ACCS_IP | varchar(30) | ✓ |  | NULL | 접속IP |
| USER_AGENT | varchar(500) | ✓ |  | NULL | 사용자에이전트 |

### MNG_ACCT_MENU_AUTHRT
> 사용자 권한별 메뉴 매핑 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| AUTHRT_CD | varchar(20) | ✗ | 🔑 PK | NULL | 권한 코드 |
| MENU_CD | varchar(2) | ✗ | 🔑 PK | NULL | MENU 코드 |
| ACCESS_YN | varchar(1) | ✗ |  | 'N' | 접근 가능 여부 |
| MDFR_ID | varchar(30) | ✗ |  | '' | MDFR 아이디 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | MDFCN 일시 |
| AUTHRT_GRANT_DT | datetime(6) | ✗ |  | NULL | AUTHRT_GRANT 일시 |

### MNG_ACCT_USER
> 사용자 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| USER_ID | varchar(30) | ✗ | 🔑 PK | NULL | 사용자아이디PSWD_INIT_REQ_YN |
| USER_NM | varchar(200) | ✗ |  | '' | 사용자이름 |
| REG_STTS_CD | varchar(20) | ✗ |  | '' | 가입상태코드 (01: 승인 대기, 02: 승인, 03: 반려, 04: 잠금, 04: 탈퇴) |
| LAST_STTS_CHG_DT | datetime | ✗ |  | current_timestamp() | LAST_STTS_CHG 일시 |
| LOCK_REASON_CD | varchar(20) | ✓ |  | NULL | 01: 로그인 실패 , 02: 접속 ＩＰ 불일치, 03: 장기 미접속 |
| LOCK_DT | datetime | ✓ |  | NULL | LOCK 일시 |
| USER_AFFL_OG_DIV_CD | varchar(20) | ✗ |  | '' | 소속기관구분코드 (01: 일반, 02: 지방자치단체) |
| USER_OG_NM | varchar(200) | ✓ |  | NULL | 소속기관명 |
| USER_OG_CD | varchar(10) | ✓ |  | NULL | 소속지자체코드 |
| SIDO_NM | varchar(20) | ✓ |  | NULL | 시도명 |
| SIDO_CD | varchar(10) | ✓ |  | NULL | 시도코드 |
| SGG_NM | varchar(20) | ✓ |  | NULL | 시군구명 |
| SGG_CD | varchar(10) | ✓ |  | NULL | 시군구코드 |
| USER_DP_NM | varchar(200) | ✓ |  | NULL | 소속부서명 |
| USER_EXT_NO | varchar(20) | ✓ |  | NULL | 사내번호 |
| PSWD_INIT_DMND_YN | varchar(1) | ✗ |  | 'N' | 비밀번호초기화요청여부 |
| PSWD_INIT_DMND_DT | datetime | ✓ |  | NULL | 비밀번호초기화요청시간 |
| PSWD_INIT_REQ_YN | varchar(1) | ✗ |  | 'N' | 비밀번호재설정유도여부 |
| LAST_LGN_DT | datetime | ✓ |  | NULL | 최종로그인일시 |
| LAST_PSWD_CHG_DT | datetime | ✗ |  | current_timestamp() | 최종비밀번호변경일시 |
| LGN_FAIL_NOCS | int(11) | ✗ |  | 0 | 로그인실패건수 |
| USER_EXPLN | varchar(4000) | ✓ |  | NULL | 사용자설명 |
| USER_REG_DT | datetime | ✗ |  | current_timestamp() | 사용자등록일시 |
| AUTZR_ID | varchar(30) | ✓ |  | NULL | 승인자아이디 |
| APRV_DT | datetime | ✓ |  | NULL | 승인일시 |
| APRV_IMPS_RSN | varchar(4000) | ✓ |  | NULL | 승인불가사유 |
| USER_PSWD | varchar(256) | ✗ |  | '' | 사용자비밀번호 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자아이디 |
| MDFCN_DT | datetime | ✗ |  | current_timestamp() | 수정일시 |
| IP_ADDRESS | varchar(30) | ✓ |  | '' | IP_ADDRESS |

### MNG_ACCT_USER_AUTHRT
> 사용자 권한 매핑 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| USER_ID | varchar(30) | ✗ | 🔑 PK | NULL | 사용자 아이디 |
| AUTHRT_CD | varchar(20) | ✗ | 🔑 PK | NULL | 권한 코드 |
| AUTHRT_GRANT_DT | datetime | ✗ |  | current_timestamp() | 권한 부여 일시 |

---

## MNG_BSINS_ — 비즈니스 지원

### MNG_BSINS_RPLY_BBS
> 회신목록게시판 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PST_ID | bigint(20) | ✗ | 🔑 PK | NULL | 게시물아이디 |
| PST_TTL | varchar(256) | ✗ |  | '' | 게시물제목 |
| PST_CN | longtext | ✗ |  | '' | 게시물내용 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |
| USER_ID | varchar(30) | ✗ |  | '' | 사용자아이디 |

### MNG_BSINS_RPLY_BBS_FILE
> 회신목록게시판 첨부파일 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| FILE_ID | bigint(20) | ✗ | 🔑 PK | NULL | 파일아이디 |
| PST_ID | bigint(20) | ✗ | 🔗 FK | NULL | 게시물아이디 |
| FILE_NM | varchar(256) | ✗ |  | '' | 파일이름 |
| FILE_EXT | varchar(10) | ✗ |  | NULL | 파일확장자 |
| FILE_PATH | varchar(1000) | ✗ |  | '' | 파일경로 |
| FILE_SZ | bigint(20) | ✗ |  | 0 | 파일크기 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

### MNG_BSINS_SPRT_BBS
> 업무지원게시판 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| PST_ID | bigint(20) | ✗ | 🔑 PK | NULL | 게시물아이디 |
| DOC_NO | varchar(100) | ✓ |  | NULL | 문서번호 |
| PST_TTL | varchar(256) | ✗ |  | '' | 게시물제목 |
| PST_CN | longtext | ✗ |  | '' | 게시물내용 |
| INQ_CNT | int(11) | ✗ |  | 0 | 조회수 |
| UPEND_FIX_YN | varchar(1) | ✗ |  | 'N' | 상단고정여부 |
| RPLY_NEED_YN | varchar(1) | ✗ |  | 'N' | 회신필요여부 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |
| USER_ID | varchar(30) | ✗ |  | '' | 사용자아이디 |

### MNG_BSINS_SPRT_BBS_FILE
> 업무지원게시판 첨부파일 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| FILE_ID | bigint(20) | ✗ | 🔑 PK | NULL | 파일아이디 |
| PST_ID | bigint(20) | ✗ | 🔗 FK | NULL | 게시물아이디 |
| FILE_NM | varchar(256) | ✗ |  | '' | 파일이름 |
| FILE_EXT | varchar(10) | ✗ |  | NULL | 파일확장자 |
| FILE_PATH | varchar(1000) | ✗ |  | '' | 파일경로 |
| FILE_SZ | bigint(20) | ✗ |  | 0 | 파일크기 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

---

## MNG_CLIP_ — 클립 관리

### MNG_CLIP_DETAIL
> 원본 영상 상세 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| EVNT_ID | varchar(50) | ✗ | 🔑 PK | NULL | 이벤트식별자 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성 일자 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정 일시 |
| VD_CDC | varchar(20) | ✗ |  | NULL | 영상 코덱 |
| FPS | int(11) | ✗ |  | 0 | 프레임 재생 속도 |
| FRM_CNT | int(11) | ✗ |  | 0 | 프레임 수 |
| ASPRT_RT | varchar(10) | ✗ |  | NULL | 종횡비 |
| WDTH | int(11) | ✗ |  | 0 | 너비 |
| HGT | int(11) | ✗ |  | 0 | 높이 |
| RSLTN | varchar(20) | ✗ |  | NULL | 해상도 |
| BIT_RATE | varchar(10) | ✗ |  | NULL | 비트값 |
| RCPTN_STTS | varchar(20) | ✗ | 🔗 FK | NULL | 수신 상태 |

### MNG_CLIP_EVNT_HSTRY
> 클립 영상 이력 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| HSTRY_ID | bigint(20) | ✗ | 🔑 PK | NULL | 이력식별자 |
| EVNT_ID | varchar(50) | ✗ |  | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ |  | NULL | 클립 구분 형식 |
| ACTION_TYPE_CD | varchar(20) | ✗ |  | NULL | 행위유형 |
| ACTION_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 행위일시 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| ERR_MSG | varchar(255) | ✗ |  | NULL | 오류 메시지 |
| LCLGV_CD | varchar(20) | ✗ |  | NULL | 지자체 코드 |
| EVNT_TYPE_CD | varchar(20) | ✗ |  | '' | 이벤트 코드 |
| EVNT_NM | varchar(200) | ✗ |  | '' | 이벤트명 |
| EVNT_SRC | varchar(20) | ✓ |  | '' | 이벤트 출처 |
| EVNT_BGNG_DT | datetime | ✓ |  | NULL | 이벤트 시작 시간 |
| EVNT_END_DT | datetime | ✓ |  | NULL | 이벤트 종료 시간 |
| MNTR_CN | varchar(4000) | ✗ |  | '' | 관제 일지 |
| CCTV_NM | varchar(300) | ✓ |  | '' | cctv 명 |

### MNG_CLIP_EVNT_LST
> 이벤트 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| EVNT_ID | varchar(50) | ✗ | 🔑 PK | NULL | 이벤트식별자 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔑 PK | '' | 이벤트 코드 |
| EVNT_NM | varchar(200) | ✗ |  | '' | 이벤트명 |
| EVNT_SRC | varchar(20) | ✓ |  | '' | 이벤트 출처 |
| EVNT_BGNG_DT | datetime | ✓ | 🔗 FK | NULL | 이벤트 시작 시간 |
| EVNT_END_DT | datetime | ✓ |  | NULL | 이벤트 종료 시간 |
| IDNTF_YN | varchar(1) | ✗ |  | 'N' | 비 식별화 처리 여부 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성 일자 |
| MDFCN_DT | datetime | ✓ |  | NULL | 업데이트 일자 |
| MNTR_CN | varchar(4000) | ✗ |  | '' | 관제 일지 |
| INCL_CLIP_CNT | int(11) | ✗ |  | 0 | 이벤트에 속한 영상 개수 |
| USE_STTS_CD | varchar(20) | ✗ | 🔗 FK | '' | 사용 상태, 미사용 처리를 위한 코드 |
| WGS84LAT | decimal(10,7) | ✓ |  | 0.0000000 | 위도 |
| WGS84LOT | decimal(10,7) | ✓ |  | 0.0000000 | 경도 |
| CLCT_PATH | varchar(255) | ✓ |  | '' | 수집 경로 |
| CLCT_SRC | varchar(255) | ✓ |  | '' | 수집 출처 |
| CCTV_NM | varchar(300) | ✓ |  | '' | cctv 명 |
| SESN_CD | varchar(10) | ✓ |  | '' | 계절 코드 |
| WTHR_CD | varchar(10) | ✓ |  | '' | 날씨 코드 |
| HR_TYPE_CD | varchar(10) | ✓ |  | '' | 시간 유형 코드 |
| SHT_DT | datetime | ✓ |  | NULL | 촬영 일자 |
| SHT_PRSN | varchar(200) | ✓ |  | '' | 촬영자 |
| SHT_EQMT | varchar(200) | ✓ |  | '' | 촬영장비 |
| SHT_LC | varchar(500) | ✓ |  | '' | 촬영 지역 |
| SHT_PLC | varchar(255) | ✓ |  | '' | 촬영지 |
| LCNS | varchar(200) | ✓ |  | '' | 라이선스 |
| PRVC_TYPE_CD | varchar(20) | ✗ |  | '' | 개인정보 유형 |

### MNG_CLIP_EVNT_STATISTICS_COLLECTION
> 지자체별 이벤트 통계

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STAT_ID | bigint(20) | ✗ | 🔑 PK | NULL | 통계 아이디 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도 코드 |
| CLCT_SRC | varchar(30) | ✗ |  | NULL | 수집 출처 |
| SGG_CD | varchar(10) | ✗ |  | NULL | 시군구코드 |
| STAT_YMD | varchar(8) | ✗ | 🔗 FK | NULL | 통계 일자 |
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔗 FK | NULL | 이벤트 코드 |
| EVNT_COLLECT_COUNT | bigint(20) | ✗ |  | 0 | 이벤트 실제 수집 수 |
| EVNT_UNCOLLECT_COUNT | bigint(20) | ✗ |  | 0 | 이벤트 미수집 수 |
| V_COLLECT_SUCC_COUNT | bigint(20) | ✗ |  | 0 | 영상 수집 성공 수 |
| V_COLLECT_FAIL_COUNT | bigint(20) | ✗ |  | 0 | 영상 수집 실패 수 |
| C_TRANS_SUCC_COUNT | bigint(20) | ✗ |  | 0 | 원본 전송 성공 수 |
| C_TRANS_FAIL_COUNT | bigint(20) | ✗ |  | 0 | 원본 전송 실패 수 |
| L_TRANS_SUCC_COUNT | bigint(20) | ✗ |  | 0 | 미리보기 전송 성공 수 |
| L_TRANS_FAIL_COUNT | bigint(20) | ✗ |  | 0 | 미리보기 전송 실패 수 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

### MNG_CLIP_EVNT_STATISTICS_JOB
> 이벤트별 작업 통계

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STAT_ID | bigint(20) | ✗ | 🔑 PK | NULL | 통계 아이디 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도명 |
| SGG_CD | varchar(10) | ✗ |  | NULL | 시군구코드 |
| STAT_YMD | varchar(8) | ✗ | 🔗 FK | NULL | 통계 일자 |
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔗 FK | NULL | 이벤트 코드 |
| ORGNL_DMND_CNT | int(11) | ✗ |  | 0 | 원본 요청 건수 |
| ORGNL_RCPTN_CNT | int(11) | ✗ |  | 0 | 원본 수신 건수 |
| LRN_VDO_CNT | int(11) | ✗ |  | 0 | 학습 영상 건수 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

### MNG_CLIP_EVNT_STATISTICS_VIDEO
> 클립 영상 송수신 통계

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STAT_ID | bigint(20) | ✗ | 🔑 PK | NULL | 통계 아이디 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | NULL | 시군구코드 |
| STAT_YMD | varchar(8) | ✗ | 🔗 FK | NULL | 통계 일자 |
| TRANS_CNT | int(11) | ✓ |  | 0 | 중계서버 전송 건수 |
| RCPTN_CNT | int(11) | ✓ |  | 0 | 관제지원시스템 수신 건수 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

### MNG_CLIP_MASTER
> 클립 영상 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| EVNT_ID | varchar(50) | ✗ | 🔑 PK | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 클립 구분 형식 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| CLIP_ID | varchar(50) | ✗ |  | NULL | 클립 ID |
| FILE_NM | varchar(256) | ✗ |  | NULL | 파일 이름 |
| FILE_PATH | varchar(1000) | ✗ |  | NULL | 파일 경로 |
| FILE_SZ | bigint(20) | ✗ |  | 0 | 파일 크기 |
| FILE_FMT | varchar(10) | ✗ |  | NULL | 파일 형식 |
| VDO_LEN_SEC | int(11) | ✗ |  | 0 | 영상 길이(초) |
| CLIP_STTS_CD | varchar(20) | ✗ |  | NULL | 클립 상태 코드 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성 일자 |
| ULD_CMPT_DT | datetime | ✓ |  | NULL | 업로드 완료 일시 |
| CMPT_DT | datetime | ✓ |  | NULL | 완료 일시 |
| ULD_SESS_ID | varchar(50) | ✗ |  | NULL | 업로드 세션 ID |
| JOB_DMND_PRNMNT_YN | varchar(1) | ✗ |  | 'N' | 작업 요청 예정 |
| JOB_DMND_YN | varchar(1) | ✗ |  | 'N' | 작업 요청 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일 파일 경로 |
| CRT_TYPE | int(1) | ✗ |  | 0 | 생성 타입
0 : 중계서버 생성
1 : 수동 생성 |
| VMS_CCTV_ID | varchar(30) | ✗ |  | '' | VMS_CCTV_번호 |
| CTGRY_NM | varchar(100) | ✗ |  | '' | 카테고리명 |

### MNG_CLIP_SCHEDULE_QUE
> 클립 후처리 작업 QUE

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| ULD_SESS_ID | varchar(50) | ✗ | 🔑 PK | NULL | 업로드 세션 ID |
| EVNT_ID | varchar(50) | ✗ | 🔗 FK | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ |  | NULL | 클립 구분 형식 |
| FILE_PATH | varchar(1000) | ✗ |  | NULL | 저장 경로 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일 크기 |
| FILE_FMT | varchar(10) | ✗ |  | NULL | 파일 형식 |
| JOB_STTS | varchar(20) | ✗ | 🔗 FK | 'PENDING' | 작업 상태 |
| JOB_TYPE | varchar(20) | ✗ | 🔗 FK | NULL | 작업 유형 |
| JOB_OOP | int(11) | ✗ |  | 5 | 작업 우선순위 |
| JOB_BGNG_DT | datetime | ✓ | 🔗 FK | NULL | 작업 시작일시 |
| RTY_CNT | int(11) | ✗ |  | 0 | 재시도횟수 |
| ERR_MSG | varchar(255) | ✗ |  | '' | 오류 메시지 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성일자 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |
| SYS_TYPE | varchar(20) | ✓ |  | '' | 시스템　타입 |

### MNG_CLIP_THUMBNAIL
> 클립 영상 썸네일 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| EVNT_ID | varchar(50) | ✗ | 🔑 PK | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 클립 구분 형식 |
| THMB_FILE_PATH | varchar(1000) | ✗ |  | NULL | 썸네일 파일 경로 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성 일자 |
| CMPTN_DT | datetime | ✓ |  | NULL | 완료 일시 |
| THMB_STTS_CD | varchar(20) | ✗ | 🔗 FK | NULL | 썸네일 상태 코드 |
| THMB_STTS_MSG | varchar(255) | ✗ |  | NULL | 썸네일 상태 메세지 |
| THMB_TIME_TICKS | varchar(255) | ✗ |  | NULL | 썸네일 타임틱 |

---

## MNG_DATA_ — 데이터셋 관리

### MNG_DATA_SET
> 데이터셋 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 ID |
| PJT_ID | varchar(50) | ✗ |  | '' | 프로젝트 ID |
| DATA_SET_TYPE | varchar(20) | ✗ | 🔗 FK | '' | 데이터 셋  타입 ( INTERNAL : 내부, EXTERNAL : 외부, RAW : 원천 ) |
| DATA_SET_STTS_CD | varchar(20) | ✗ |  | '' | 데이터 셋 작업 진행 상태 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성일자 |
| MDFCN_DT | datetime | ✗ |  | current_timestamp() | 수정일자 |
| DATA_SET_NM | varchar(100) | ✗ | 🔗 FK | '' | 데이터셋 이름 |
| DATA_SET_EXPLN | varchar(4000) | ✗ |  | '' | 데이터셋 설명 |
| VER | varchar(20) | ✗ | 🔗 FK | '' | 버전 |
| VER_EXPLN | varchar(4000) | ✗ |  | '' | 버전 설명 |
| IMG_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 데이터 이미지 존재 여부 ( N : 미존재, Y : 존재 ) |
| VDO_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 데이터 영상 존재 여부 ( N : 미존재, Y : 존재 ) |
| ANONY_INCL_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 개인정보 유형-익명 포함 여부 |
| PSDO_INCL_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 개인정보 유형-가명 포함 여부 |
| PRVC_INCL_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 개인정보 유형-개인정보 포함 여부 |
| EVNT_CLS_CD | varchar(2) | ✗ |  | '' | 이벤트 코드 (대분류) |
| EVNT_CTGRY_CD | varchar(4) | ✗ | 🔗 FK | '' | 이벤트 코드 (중분류) |
| GEN_AI_YN | varchar(1) | ✗ | 🔗 FK | 'N' | 생성형 AI 여부 ( N : No, Y : Yes ) |
| AI_MDL_INCL_YN | varchar(1) | ✗ |  | 'N' | AI모델이 존재하는지 여부 ( N: 미존재, Y : 존재 ) |
| AI_MDL_EXPLN | varchar(4000) | ✗ |  | '' | AI 모델 설명 |
| DATA_TYPE | varchar(20) | ✗ |  | '' | 데이터 형식 |
| DATA_SRC | varchar(1000) | ✗ |  | '' | 데이터 출처 |
| LBL_TYPE | varchar(256) | ✗ |  | '' | 라벨링 유형 |
| LBL_FMT | varchar(256) | ✗ |  | '' | 라벨링 형식 |
| DATA_ETBL_YR | varchar(1000) | ✗ |  | '' | 데이터 구축 연도 |
| DATA_ETBL_NOCS | int(11) | ✗ |  | 0 | 데이터 구축 건수 |
| DATA_ETBL_CPCT | bigint(20) | ✗ | 🔗 FK | 0 | 데이터 구축 용량 |
| DATA_SET_GROUP_ID | varchar(50) | ✗ |  | '' | 데이터 셋 그룹 ID |
| DATA_SHP | longtext | ✗ |  | '' | 어노테이션 포맷/데이터 구조 |
| USER_ID | varchar(30) | ✗ |  | '' | 등록 유저 ID |
| USER_NM | varchar(200) | ✗ |  | '' | 등록 유저 명 |

### MNG_DATA_SET_ATCH_FILE
> 데이터셋 첨부파일 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_ATCH_FILE_ID | bigint(20) | ✗ | 🔑 PK | NULL | DATA_SET_ATCH_FILE 아이디 |
| DATA_SET_ID | bigint(20) | ✗ |  | NULL | 데이터 셋  ID |
| ATCH_FILE_NM | varchar(256) | ✗ |  | '' | 첨부파일명 |
| ATCH_FILE_CD | varchar(20) | ✗ |  | '' | 첨부파일 구분 |
| ATCH_FILE_PATH | varchar(4000) | ✗ |  | '' | 첨부파일경로 |
| ATCH_FILE_SZ | bigint(20) | ✗ |  | 0 | 파일크기 |
| ATCH_STTS_CD | varchar(20) | ✗ |  | '' | 첨부파일 처리 상태 코드 |
| ATCH_STTS_MSG | varchar(255) | ✗ |  | '' | 첨부파일 처리 상태 메시지 |
| SORT_SEQ | int(11) | ✗ |  | 0 | 정렬순서 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 등록일자 |
| PRVC_TYPE_CD | varchar(20) | ✗ |  | '' | 첨부파일 개인정보 유형 분류 |

### MNG_DATA_SET_CTGRY
> 데이터셋 내부학습 데이터 카테고리 내용 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CTGRY_ID | varchar(30) | ✗ | 🔑 PK | NULL | 카테고리 ID |
| DATA_SET_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 ID |
| CTGRY_NM | varchar(30) | ✗ |  | '' | 카테고리 명 |
| CTGRY_EXPLN | varchar(4000) | ✗ |  | '' | 카테고리 설명 |
| ANNO_TYPE | varchar(20) | ✗ |  | '' | 어노테이션 타입 |

### MNG_DATA_SET_DSTRB
> 데이터셋 배포 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_DSTRB_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 배포관리 ID |
| DSTRB_NM | varchar(100) | ✗ | 🔗 FK | '' | 배포 이름 |
| DSTRB_EXPLN | varchar(4000) | ✗ | 🔗 FK | '' | 배포 설명 |
| DSTRB_STATUS | varchar(20) | ✗ |  | '' | 배포 상태 |
| DSTRB_SZ | bigint(20) | ✗ | 🔗 FK | 0 | 배포 크기 |
| USER_ID | varchar(30) | ✗ | 🔗 FK | '' | 배포 작업자 ID |
| USER_NM | varchar(200) | ✗ |  | '' | 배포 작업자 명 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성일자 |

### MNG_DATA_SET_DSTRB_DTL
> 배포된 데이터셋 정보 아이디 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_DSTRB_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 배포관리 ID |
| DATA_SET_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 ID |

### MNG_DATA_SET_EVNT_CD
> 데이터 셋 이벤트 유형(소분류) 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 ID |
| DTL_EVNT | varchar(2) | ✗ | 🔑 PK | NULL | 이벤트 코드 (소분류) |
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 이벤트 코드 (상세) |
| SORT_SEQ | int(11) | ✗ |  | 0 | 정렬순서 |

### MNG_DATA_SET_ORGNL_VDO
> 데이터셋 원본 영상 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| DATA_SET_ID | bigint(20) | ✗ | 🔑 PK | NULL | 데이터 셋 ID |
| EVNT_ID | varchar(50) | ✗ | 🔑 PK | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 클립 구분 형식 |

---

## MNG_EX_ — 외부 연계

### MNG_EX_CLCT
> 수급 서버 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CLCT_ID | bigint(20) | ✗ | 🔑 PK | NULL | 수급 서버 아이디 |
| CLCT_EXPLN | varchar(4000) | ✗ |  | '' | 수급서버설명 |
| CLCT_SRVR_IP | varchar(30) | ✗ |  | '' | 수급 서버 IP |
| CLCT_SRVR_PORT | int(11) | ✗ |  | 0 | 수급 서버 PORT |
| SSL_USE_YN | varchar(1) | ✗ |  | 'N' | SSL 사용 여부 |
| RCNT_COMM_DT | datetime | ✓ |  | NULL | 최근통신일시 |
| CRTR_ID | varchar(30) | ✓ |  | NULL | 등록자ID |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자ID |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정일시 |

### MNG_EX_EVNT_TYPE
> 이벤트 유형 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔑 PK | NULL | 이벤트 코드 |
| EVNT_CLS_CD | varchar(2) | ✗ |  | '' | 이벤트 분류 코드 (대분류) |
| EVNT_CTGRY_CD | varchar(4) | ✗ |  | '' | 이벤트 유형 코드 (중분류) |
| CLCT_EVNT_NM | varchar(4000) | ✓ |  | NULL | 지자체 수집 이벤트 명 |
| CLCT_YN | varchar(2) | ✗ |  | 'Y' | 수집 여부 YN |
| MDFR_ID | varchar(30) | ✓ |  | '' | 수정자ID |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일자 |
| CRT_ID | varchar(30) | ✗ |  | '' | 등록자 ID |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| SMP_CYCL | time | ✓ |  | '00:10:00' | 샘플링추출주기 |
| MAX_CLCT_NOCS | int(11) | ✓ |  | 1000 | 최대수집건수 |
| CLCT_BGNG_HR | time | ✓ |  | '00:00:00' | 수집시작시간 |
| CLCT_END_HR | time | ✓ |  | '23:59:00' | 수집종료시간 |

### MNG_EX_EVNT_TYPE_MAP
> 이벤트 유형 타입 매핑 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CD_TYPE | varchar(2) | ✗ | 🔑 PK | '01' | 코드 타입(01: 대분류, 02: 중분류, 03: 소분류) |
| EVNT_CLS_CD | varchar(2) | ✗ | 🔑 PK | '' | 이벤트 분류 코드 (대분류) |
| EVNT_CTGRY_CD | varchar(4) | ✗ | 🔑 PK | '' | 이벤트 유형 코드 (중분류) |
| DTL_EVNT | varchar(2) | ✗ | 🔑 PK | '' | 세부 이벤트 (소분류) |
| EVNT_TYPE_CD | varchar(20) | ✗ | 🔑 PK | '' | 이벤트 코드 |
| EVNT_NM | varchar(4000) | ✓ |  | NULL | 이벤트 명 |
| USE_YN | varchar(2) | ✗ |  | 'Y' | 사용/미사용 코드 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자ID |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일자 |
| CRT_ID | varchar(30) | ✗ |  | '' | CRT 아이디 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |

### MNG_EX_LOCAL_GOV
> 중계 서버 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| LCLGV_CD | varchar(20) | ✗ | 🔑 PK | NULL | 지방자치단체코드 |
| LCLGV_EXPLN | varchar(4000) | ✗ |  | '' | 지방자치단체설명 (미사용) |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도 코드 |
| SIDO_NM | varchar(20) | ✗ |  | '' | 시도명 |
| SGG_CD | varchar(10) | ✗ |  | NULL | 시군구코드 |
| SGG_NM | varchar(20) | ✗ |  | '' | 시군구명 |
| RCNT_COMM_DT | datetime | ✓ |  | NULL | 최근통신일시 |
| DE_IDENT_YN | varchar(1) | ✗ |  | 'Y' | 비식별화여부 |
| DE_IDNTF_APLCN_DT | datetime | ✓ |  | NULL | 비식별화적용일시 |
| MIN_BW | int(11) | ✗ |  | 0 | 최소대역폭 |
| MAX_BW | int(11) | ✗ |  | 0 | 최대대역폭 |
| USE_RT | int(11) | ✗ |  | 0 | 사용률 |
| MAIN_LCLGV_YN | varchar(1) | ✗ |  | 'N' | 주요지방자치단체여부 |
| TRSM_SPD | int(11) | ✓ |  | NULL | 전송속도 |
| TRSM_SCHDL_UPD_DT | datetime | ✓ |  | NULL | 전송 재시작 설정 시간 (미사용) |
| TRSM_SCHDL_DSTRB_YN | varchar(1) | ✗ |  | 'N' | 전송 스케줄 배포 여부 (미사용) |
| REG_DT | datetime | ✗ |  | current_timestamp() | 생성 일자 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자 ID |
| MDFCN_DT | datetime | ✓ |  | NULL | 수정 일시 |
| ACCESS_TOKEN | varchar(1000) | ✓ |  | NULL | 액세스 토큰 |
| CRTR_ID | varchar(30) | ✗ |  | '' | 등록자 ID |
| VDO_PRVW_YN | varchar(1) | ✓ |  | 'N' | 영상 미리보기 여부 |
| EVNT_VDO_TRSM_YN | varchar(1) | ✓ |  | 'N' | 이벤트 영상 전송 여부 |
| DIR_VDO_TRSM_YN | varchar(1) | ✓ |  | 'N' | 정제 영상 전송 여부 |
| VDO_RNG_SLCT_MON_PLFM | int(11) | ✗ |  | 4 | 발생일시 전후 영상 확보 범위(선별관제플랫폼) |
| VDO_RNG_INTG_PLFM | int(11) | ✗ |  | 10 | 발생일시 전후 영상 확보 범위(통합플랫폼) |
| VDO_RNG_MON_LOG | int(11) | ✗ |  | 10 | 발생일시 전후 영상 확보 범위(관제일지) |
| RELY_SRVR_IP | varchar(30) | ✓ |  | '' | RELY_SRVR_IP |
| RELY_SRVR_PORT | int(11) | ✓ |  | 0 | RELY_SRVR_PORT |

### MNG_EX_SEND_SCHEDULE
> 전송 스케줄 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| TRSM_SCHDL_ID | bigint(20) | ✗ | 🔑 PK | NULL | 전송스케줄ID |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | '' | 지자체 코드 |
| TRSM_DOW | varchar(20) | ✗ |  | '' | 전송요일 |
| TRSM_SCHDL_BGNG_HR | varchar(10) | ✗ |  | '00:00' | 전송시작시간 |
| TRSM_SCHDL_END_HR | varchar(10) | ✗ |  | '00:00' | 전송종료시간 |
| USE_YN | varchar(1) | ✗ |  | 'N' | 사용여부 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자ID |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_EX_SYSTEM_LOG
> 중계 서버 시스템/어플리케이션 로그 정보 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| LOG_ID | bigint(20) | ✗ | 🔑 PK | NULL | 로그 ID |
| LCLGV_CD | varchar(20) | ✗ |  | NULL | 지자체 코드 |
| LOG_TYPE | varchar(20) | ✗ |  | '01' | 로그 구분 (01: 시스템로그, 02: 어플리케이션로그) |
| LOG_FILE_NM | varchar(200) | ✓ |  | NULL | 로그 파일 이름 |
| ORIG_FILE_NM | varchar(200) | ✓ |  | NULL | 원본 파일 이름 |
| FILE_PATH | varchar(500) | ✓ |  | NULL | 파일 경로 |
| FILE_SIZE | bigint(20) | ✓ |  | NULL | 파일 크기 |
| LOG_DT | datetime | ✓ |  | NULL | 로그 생성일 |
| UPLOAD_DT | datetime | ✓ |  | NULL | 로그 등록일 |
| UPLOAD_IP | varchar(30) | ✓ |  | NULL | 등록 IP |

---

## MNG_GOV_ — 기관 관리

### MNG_GOV_OG
> 기관코드 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| OG_CD | varchar(20) | ✗ | 🔑 PK | NULL | 기관코드 |
| OG_NM | varchar(200) | ✗ |  | '' | 기관명 |

### MNG_GOV_OG_TMP
> 기관코드 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| OG_CD | varchar(20) | ✗ | 🔑 PK | NULL | 기관코드 |
| OG_NM | varchar(200) | ✗ |  | '' | 기관명 |
| TYPE | varchar(2) | ✓ |  | NULL | 유형 |

---

## MNG_INGEST_ — 수집 관리

### MNG_INGEST_RAW_REQ_QUE
> 원본 영상 요청 QUE 스케줄

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| QUE_ID | bigint(20) | ✗ | 🔑 PK | NULL | 큐 아이디 |
| EVNT_ID | varchar(50) | ✗ | 🔗 FK | NULL | 이벤트식별자 |
| CLIP_TYPE_CD | varchar(20) | ✗ |  | NULL | 클립 구분 형식 |
| LCLGV_CD | varchar(20) | ✗ |  | NULL | 지자체 코드 |
| BGNG_HR | time | ✓ |  | NULL | 작업 시작 시간 |
| END_HR | time | ✓ |  | NULL | 작업 종료 시간 |
| DMND_STTS_CD | varchar(20) | ✓ | 🔗 FK | NULL | 요청 상태 코드 |
| DMND_STTS_MSG | varchar(255) | ✓ |  | NULL | 요청 상태 메시지 |
| DMND_DT | datetime | ✗ |  | NULL | 요청 일시 |
| CMPT_DT | datetime | ✓ | 🔗 FK | NULL | 완료 일시 |
| RTY_CNT | int(11) | ✓ |  | 0 | 재시도 횟수 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |

### MNG_INGEST_UPLD_CHUNK
> 파일 업로드 청크 정보

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| ULD_ID | varchar(50) | ✗ | 🔑 PK | NULL | 업로드 아이디 |
| CHUNK_FILE_ID | varchar(50) | ✗ | 🔑 PK | NULL | 청크파일 아이디 |
| OFFSET | bigint(20) | ✗ |  | 0 | offset |
| CHUNK_FILE_SZ | bigint(20) | ✗ |  | 0 | 청크 파일 크기 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 생성 일시 |
| CMPT_DT | datetime | ✓ |  | current_timestamp() | 완료 일시 |
| CHUNK_FILE_NM | varchar(255) | ✗ |  | NULL | 청크 파일명 |
| CHK_SUM | varchar(255) | ✗ |  | NULL | 체크섬 |

### MNG_INGEST_UPLD_SESSION
> 파일 업로드 세션 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| ULD_ID | varchar(50) | ✗ | 🔑 PK | NULL | 업로드 아이디 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | NULL | 지자체 코드 |
| TOTAL_FILE_SZ | bigint(20) | ✗ |  | 0 | 총 파일 크기 |
| ULD_FILE_SZ | bigint(20) | ✗ |  | 0 | 업로드 파일 크기 |
| ULD_STTS_CD | varchar(20) | ✗ | 🔗 FK | NULL | 업로드 상태 코드 |
| ULD_STTS_MSG | varchar(255) | ✗ |  | NULL | 업로드 상태 메세지 |
| CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 생성 일시 |
| CHK_RCPTN_DT | datetime | ✓ |  | NULL | 청크 수신 일시 |
| EXPD_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 만료 일시 |
| CMPT_DT | datetime | ✓ |  | NULL | 완료 일시 |
| JOB_PATH | varchar(255) | ✗ |  | NULL | 작업 경로 |
| CHK_SUM | varchar(255) | ✓ |  | NULL | 체크섬 |
| FILE_FMT | varchar(10) | ✗ |  | NULL | 파일형식 |
| ULD_SPD | int(11) | ✗ |  | 0 | 업로드 속도 |
| ULD_TYPE | int(1) | ✗ |  | 0 | 파일 업로드 타입 0:중계서버 업로드 1:수동 업로드 2:직접 업로드 |

---

## MNG_RESOURCE_ — 리소스 관리

### MNG_RESOURCE_CCTV
> CCTV 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CCTV_ID | bigint(20) | ✗ | 🔑 PK | NULL | CCTV아이디 |
| CCTV_MNG_NO | varchar(50) | ✗ |  | '' | CCTV관리번호 |
| CCTV_NM | varchar(300) | ✗ |  | '' | CCTV명 |
| OG_CD | varchar(20) | ✗ |  | NULL | 기관코드 |
| OG_NM | varchar(200) | ✗ |  | '' | 기관명 |
| OG_TELNO | varchar(15) | ✗ |  | '' | 기관전화번호 |
| POLE_ID | bigint(20) | ✗ | 🔗 FK | NULL | 폴대아이디 |
| CCTV_NO | varchar(2) | ✗ |  | '' | CCTV번호 |
| INSTL_PRPS_LIV_CP_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-생활방범 |
| INSTL_PRPS_VEH_CP_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-차량방범 |
| INSTL_PRPS_CHD_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-어린이보호 |
| INSTL_PRPS_PARK_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-도시공원놀이터 |
| INSTL_PRPS_GBG_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-쓰레기투기 |
| INSTL_PRPS_FAC_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-시설관리 |
| INSTL_PRPS_FIR_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-화재 |
| INSTL_PRPS_TRF_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-교통단속 |
| INSTL_PRPS_TRF_INFO_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-교통정보수집 |
| INSTL_PRPS_DIS_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-재난안전 |
| INSTL_PRPS_ETC_YN | varchar(1) | ✗ |  | 'N' | 설치목적여부-기타목적 |
| CCTV_SHP_CD | varchar(10) | ✗ |  | '' | CCTV형태코드 |
| WGS84_LAT | decimal(10,7) | ✗ |  | 0.0000000 | 위도(WGS84) |
| WGS84_LOT | decimal(10,7) | ✗ |  | 0.0000000 | 경도(WGS84) |
| CCTV_PXL | int(11) | ✗ |  | 0 | CCTV화소(M) |
| CAM_RSLTN | varchar(20) | ✗ |  | '' | 카메라해상도 |
| CCTV_INSTL_YMD | varchar(8) | ✗ |  | '' | CCTV설치년월 |
| CCTV_MNFTR_NTN | varchar(30) | ✗ |  | '' | CCTV제조국 |
| CCTV_MNFTR_CO | varchar(30) | ✗ |  | '' | CCTV제조사 |
| CCTV_MDL_NM | varchar(50) | ✗ |  | '' | CCTV모델명 |
| USE_YN | varchar(1) | ✗ |  | '' | 사용여부 |
| STRG_PRD | int(11) | ✗ |  | 0 | 저장기간(일) |
| STRG_MTHD_CD | varchar(20) | ✗ |  | '' | 저장방법코드 |
| INTL_MON_TYPE_CD | varchar(20) | ✗ |  | '' | 지능형관제유형코드 |
| CCTV_HGT | decimal(4,1) | ✓ |  | 0.0 | 높이(m) |
| MAIN_SURV_PAN_ANG | int(11) | ✓ |  | 0 | 주감시방향값(도) |
| MAIN_SURV_TILT_ANG | int(11) | ✓ |  | 0 | 주감시상하향값(도) |
| INTNL_CCTV_MNG_NO | varchar(50) | ✓ |  | NULL | 자체CCTV관리번호 |
| MNG_DEPT_NM | varchar(30) | ✓ |  | NULL | 관리부서명 |
| CCTV_DTL_KND_CD | varchar(20) | ✓ |  | NULL | CCTV세부종류코드 |
| CCTV_FST_REG_YMD | varchar(8) | ✓ |  | NULL | CCTV최초등록일시 |
| CCTV_SHT_HR | int(11) | ✓ |  | NULL | 촬영시간 |
| CCTV_IP_ADDR | varchar(20) | ✓ |  | NULL | CCTVIP주소 |
| ACCS_URL | varchar(50) | ✓ |  | NULL | 접속URL |
| ACCS_ID | varchar(20) | ✓ |  | NULL | ID |
| ACCS_PW | varchar(20) | ✓ |  | NULL | PW |
| SHT_ADDR | varchar(50) | ✓ |  | NULL | 촬영장소 |
| CCTV_ZOOM_MAG | varchar(5) | ✓ |  | NULL | CCTV줌배율 |
| IR_YN | varchar(1) | ✓ |  | NULL | 적외선유무 |
| IR_INT | varchar(50) | ✓ |  | NULL | 적외선강도 |
| RM_PWR_CTRL_DEV_YN | varchar(1) | ✓ |  | NULL | 원격전원제어장치유무 |
| STD_CCTV_MNG_NO | varchar(35) | ✗ | 🔗 FK | NULL | 표준 CCTV관리번호 |
| SIDO_NM | varchar(20) | ✗ | 🔗 FK | '' | 시도명 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도 코드 |
| SGG_NM | varchar(20) | ✗ | 🔗 FK | '' | 시군구명 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| USER_SIDO_CD | varchar(10) | ✗ |  | '' | 업로드한 사용자의 시도코드 |
| USER_SIDO_NM | varchar(20) | ✗ |  | '' | 업로드한 사용자의 시도이름 |
| USER_SGG_CD | varchar(10) | ✗ |  | '' | 업로드한 사용자의 시군구코드 |
| USER_SGG_NM | varchar(20) | ✗ |  | '' | 업로드한 사용자의 시군구이름 |
| HDONG_NM | varchar(10) | ✗ |  | '' | 행정동명 |
| BDONG_NM | varchar(10) | ✗ |  | '' | 법정동명 |
| LNM_ADDR | varchar(100) | ✗ |  | '' | 지번주소 |
| RDNM_ADDR | varchar(100) | ✗ | 🔑 PK | '' | 도로명주소 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ | 🔗 FK | current_timestamp() | 수정일시 |
| SCHOOL_CD | varchar(2) | ✓ |  | '00' | SCHOOL 코드 |

### MNG_RESOURCE_CCTV_FAIL
> CCTV업로드 실패 행 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| CCTV_ERR_ID | bigint(20) | ✗ | 🔑 PK | NULL | CCTV업로드실패목록아이디 |
| JOB_ID | varchar(50) | ✗ | 🔗 FK | NULL | 작업 고유 ID (UUID) |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| FILE_NM | varchar(256) | ✗ |  | '' | 파일이름 |
| ERR_POLE_SN | varchar(30) | ✓ |  | NULL | 폴대일련번호 |
| ERR_CCTV_MNG_NO | varchar(50) | ✓ |  | NULL | CCTV관리번호 |
| ERR_CCTV_NM | varchar(300) | ✓ |  | NULL | CCTV명 |
| ERR_OG_CD | varchar(30) | ✓ |  | NULL | 기관코드 |
| ERR_OG_TELNO | varchar(30) | ✓ |  | NULL | 기관전화번호 |
| ERR_CCTV_NO | varchar(30) | ✓ |  | NULL | CCTV번호 |
| ERR_INSTL_PRPS_LIV_CP_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-생활방범 |
| ERR_INSTL_PRPS_VEH_CP_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-차량방범 |
| ERR_INSTL_PRPS_CHD_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-어린이보호 |
| ERR_INSTL_PRPS_PARK_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-도시공원놀이터 |
| ERR_INSTL_PRPS_GBG_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-쓰레기투기 |
| ERR_INSTL_PRPS_FAC_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-시설관리 |
| ERR_INSTL_PRPS_FIR_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-화재 |
| ERR_INSTL_PRPS_TRF_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-교통단속 |
| ERR_INSTL_PRPS_TRF_INFO_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-교통정보수집 |
| ERR_INSTL_PRPS_DIS_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-재난안전 |
| ERR_INSTL_PRPS_ETC_YN | varchar(30) | ✓ |  | NULL | 설치목적여부-기타목적 |
| ERR_CCTV_SHP | varchar(30) | ✓ |  | NULL | CCTV형태 |
| ERR_CAM_RSLTN | varchar(30) | ✓ |  | NULL | 카메라해상도 |
| ERR_CCTV_INSTL_YMD | varchar(30) | ✓ |  | NULL | CCTV설치년월 |
| ERR_CCTV_MNFTR_NTN | varchar(30) | ✓ |  | NULL | CCTV제조국 |
| ERR_CCTV_MNFTR_CO | varchar(30) | ✓ |  | NULL | CCTV제조사 |
| ERR_CCTV_MDL_NM | varchar(50) | ✓ |  | NULL | CCTV모델명 |
| ERR_USE_YN | varchar(30) | ✓ |  | NULL | 사용여부 |
| ERR_STRG_PRD | varchar(30) | ✓ |  | NULL | 저장기간(일) |
| ERR_STRG_MTHD | varchar(30) | ✓ |  | NULL | 저장방법 |
| ERR_INTL_MON_TYPE | varchar(30) | ✓ |  | NULL | 지능형관제유형 |
| ERR_CCTV_HGT | varchar(30) | ✓ |  | NULL | 높이(m) |
| ERR_OG_NM | varchar(30) | ✓ |  | NULL | 기관명 |
| ERR_MAIN_SURV_PAN_ANG | varchar(30) | ✓ |  | NULL | 주감시방향값(도) |
| ERR_MAIN_SURV_TILT_ANG | varchar(30) | ✓ |  | NULL | 주감시상하향값(도) |
| ERR_INTNL_CCTV_MNG_NO | varchar(50) | ✓ |  | NULL | 자체CCTV관리번호 |
| ERR_MNG_DEPT_NM | varchar(30) | ✓ |  | NULL | 관리부서명 |
| ERR_CCTV_DTL_KND | varchar(30) | ✓ |  | NULL | CCTV세부종류 |
| ERR_CCTV_FST_REG_YMD | varchar(30) | ✓ |  | NULL | CCTV최초등록일시 |
| ERR_CCTV_SHT_HR | varchar(30) | ✓ |  | NULL | 촬영시간 |
| ERR_CCTV_IP_ADDR | varchar(30) | ✓ |  | NULL | CCTVIP주소 |
| ERR_ACCS_URL | varchar(30) | ✓ |  | NULL | 접속URL |
| ERR_ACCS_ID | varchar(30) | ✓ |  | NULL | ID |
| ERR_ACCS_PW | varchar(30) | ✓ |  | NULL | PW |
| ERR_SHT_ADDR | varchar(50) | ✓ |  | NULL | 촬영장소 |
| ERR_CCTV_ZOOM_MAG | varchar(30) | ✓ |  | NULL | CCTV줌배율 |
| ERR_IR_YN | varchar(30) | ✓ |  | NULL | 적외선유무 |
| ERR_IR_INT | varchar(50) | ✓ |  | NULL | 적외선강도 |
| ERR_RM_PWR_CTRL_DEV_YN | varchar(30) | ✓ |  | NULL | 원격전원제어장치유무 |
| FAIL_REASON | varchar(4000) | ✗ |  | NULL | 실패 사유 |

### MNG_RESOURCE_EXCEL_IMPORT_JOB
> 엑셀업로드 작업 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| JOB_ID | varchar(50) | ✗ | 🔑 PK | NULL | 작업아이디 |
| USER_ID | varchar(30) | ✗ | 🔗 FK | '0' | 사용자아이디 |
| FILE_NM | varchar(256) | ✗ |  | '' | 파일이름 |
| FILE_EXT | varchar(10) | ✗ |  | NULL | 파일확장자 |
| FILE_SZ | bigint(20) | ✗ |  | NULL | 파일크기 |
| JOB_STTS | varchar(20) | ✗ |  | 'PENDING' | 작업상태 (PENDING, PROCESSING, COMPLETED, ERROR, PARTIAL_ERROR, CANCELLED) |
| TOTAL_POLE_ROWS | int(11) | ✓ |  | 0 | 폴대전체행의수 |
| TOTAL_CCTV_ROWS | int(11) | ✓ |  | 0 | CCTV전체행의수 |
| SUCCESS_POLE_ROWS | int(11) | ✓ |  | 0 | 폴대성공한행의수 |
| SUCCESS_CCTV_ROWS | int(11) | ✓ |  | 0 | CCTV성공한행의수 |
| ERR_POLE_ROWS | int(11) | ✓ |  | 0 | 폴대오류가발생한행의수 |
| ERR_CCTV_ROWS | int(11) | ✓ |  | 0 | CCTV오류가발생한행의수 |
| JOB_BGNG_DT | datetime | ✓ |  | NULL | 작업시작일시 |
| JOB_END_DT | datetime | ✓ |  | NULL | 작업종료일시 |
| ERR_MSG_POLE | longtext | ✓ |  | '' | 폴대오류메시지 |
| ERR_MSG_CCTV | longtext | ✓ |  | NULL | CCTV오류메시지 |
| LCLGV_CD | varchar(20) | ✗ | 🔗 FK | '' | 지방자치단체코드 |
| USER_SIDO_CD | varchar(20) | ✗ |  | '' | 사용자 시도 코드 |
| USER_SGG_CD | varchar(20) | ✗ |  | '' | 사용자 시군구 코드 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_RESOURCE_POLE
> 폴대 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| POLE_ID | bigint(20) | ✗ | 🔑 PK | NULL | 폴대아이디 |
| POLE_MNG_NO | varchar(30) | ✗ | 🔗 FK | NULL | 폴대관리번호 |
| POLE_SN | varchar(20) | ✗ |  | NULL | 폴대일련번호 |
| OG_CD | varchar(20) | ✗ |  | NULL | 기관코드 |
| POLE_TYPE_CD | varchar(15) | ✗ |  | '' | 폴대유형코드 |
| WGS84_LAT | decimal(10,7) | ✗ |  | 0.0000000 | WGS84위도 |
| WGS84_LOT | decimal(10,7) | ✗ |  | 0.0000000 | WGS84경도 |
| CMA_CNTOM | int(11) | ✗ |  | 0 | 카메라개수 |
| ENCL_YN | varchar(1) | ✗ |  | '' | 함체유무 |
| COMM_NET_TYPE_CD | varchar(20) | ✗ |  | '' | 통신망유형코드 |
| CMCL_NET_SCRTY_MSRS | varchar(10) | ✗ |  | '' | 상용망보안방안 |
| COMM_SPD | varchar(30) | ✗ |  | '' | 통신속도(Mbps) |
| TLCMCO_NM | varchar(30) | ✓ |  | NULL | 통신사명 |
| LINE_NO | varchar(50) | ✓ |  | NULL | 회선번호 |
| ACTV_YMD | varchar(8) | ✓ |  | NULL | 개통일 |
| KEPCO_CUST_TELNO | varchar(50) | ✓ |  | NULL | 한전고객번호 |
| PAY_NO | varchar(50) | ✓ |  | NULL | 납부번호 |
| MSINS_NO | varchar(50) | ✓ |  | NULL | 계량기번호 |
| CCTV_INFBD_TYPE | varchar(30) | ✓ |  | NULL | CCTV안내판종류 |
| EMGBELL_YN | varchar(1) | ✓ |  | NULL | 비상벨유무 |
| EMGBELL_SHP | varchar(30) | ✓ |  | NULL | 비상벨형태 |
| EMGBELL_TYPE | varchar(30) | ✓ |  | NULL | 비상벨유형 |
| EMGBELL_MNFTR_CO | varchar(30) | ✓ |  | NULL | 비상벨 제조사 |
| EMGBELL_MDL_NM | varchar(50) | ✓ |  | NULL | 비상벨 모델명 |
| EMGBELL_IP_ADDR | varchar(50) | ✓ |  | NULL | 비상벨 IP주소 |
| EMGBELL_INSTL_YMD | varchar(8) | ✓ |  | NULL | 비상벨설치년월일 |
| EMGBELL_INFBD_YN | varchar(1) | ✓ |  | NULL | 비상벨안내판유무 |
| EDB_YN | varchar(1) | ✓ |  | NULL | 전광판유무 |
| EDB_TYPE | varchar(30) | ✓ |  | NULL | 전광판유형 |
| EDB_MNFTR_CO | varchar(30) | ✓ |  | NULL | 전광판제조사 |
| EDB_IP_ADDR | varchar(50) | ✓ |  | NULL | 전광판IP주소 |
| EDB__INSTL_YMD | varchar(10) | ✓ |  | NULL | 전광판설치년월일 |
| LPJ_YN | varchar(1) | ✓ |  | NULL | 로고젝터유무 |
| LPJ_MNFTR_CO | varchar(30) | ✓ |  | NULL | 로고젝터제조사 |
| LPJ_MDL_NM | varchar(30) | ✓ |  | NULL | 로고젝터모델명 |
| LPJ__INSTL_YMD | varchar(8) | ✓ |  | NULL | 로고젝터설치년월일 |
| SPKR_YN | varchar(1) | ✓ |  | NULL | 스피커유무 |
| SPKR__INSTL_YMD | varchar(10) | ✓ |  | NULL | 스피커설치년월일 |
| ETC_INFBD_YN | varchar(1) | ✓ |  | NULL | 기타안내판유무 |
| ETC_INFBD_TYPE | varchar(30) | ✓ |  | NULL | 기타안내판유형 |
| ETC_EQPMNT_1 | varchar(30) | ✓ |  | NULL | 기타장비1 |
| ETC_EQPMNT_2 | varchar(30) | ✓ |  | NULL | 기타장비2 |
| ETC_EQPMNT_3 | varchar(30) | ✓ |  | NULL | 기타장비3 |
| SIDO_CD | varchar(10) | ✗ |  | '' | SIDO 코드 |
| SIDO_NM | varchar(20) | ✗ | 🔗 FK | '' | 시도명 |
| SGG_CD | varchar(10) | ✗ |  | '' | SGG 코드 |
| USER_SIDO_CD | varchar(10) | ✗ |  | '' | 업로드한 사용자의 시도코드 |
| USER_SIDO_NM | varchar(20) | ✗ |  | '' | 업로드한 사용자의 시도이름 |
| USER_SGG_CD | varchar(10) | ✗ |  | '' | 업로드한 사용자의 시군구코드 |
| USER_SGG_NM | varchar(20) | ✗ |  | '' | 업로드한 사용자의 시군구이름 |
| SGG_NM | varchar(20) | ✗ |  | '' | 시군구명 |
| HDONG_NM | varchar(10) | ✗ |  | '' | 행정동명 |
| BDONG_NM | varchar(10) | ✗ |  | '' | 법정동명 |
| LNM_ADDR | varchar(100) | ✗ |  | '' | 지번주소 |
| RDNM_ADDR | varchar(100) | ✗ |  | '' | 도로명주소 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ | 🔗 FK | current_timestamp() | 수정일시 |

### MNG_RESOURCE_POLE_FAIL
> 폴대 업로드 실패 행 관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| POLE_ERR_ID | bigint(20) | ✗ | 🔑 PK | NULL | 폴대업로드실패목록아이디 |
| JOB_ID | varchar(50) | ✗ | 🔗 FK | NULL | 작업아이디 |
| USER_ID | varchar(30) | ✗ |  | NULL | 사용자아이디 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| FILE_NM | varchar(256) | ✗ |  | '' | 파일이름 |
| ERR_OG_CD | varchar(30) | ✓ |  | NULL | 기관코드 |
| ERR_POLE_SN | varchar(30) | ✓ |  | NULL | 폴대일련번호 |
| ERR_POLE_TYPE | varchar(30) | ✓ |  | NULL | 폴대유형 |
| ERR_WGS84_LAT | varchar(30) | ✓ |  | NULL | WGS84위도 |
| ERR_WGS84_LOT | varchar(30) | ✓ |  | NULL | WGS84경도 |
| ERR_CMA_CNTOM | varchar(30) | ✓ |  | NULL | 카메라개수 |
| ERR_ENCL_YN | varchar(30) | ✓ |  | NULL | 함체유무 |
| ERR_COMM_NET_TYPE | varchar(30) | ✓ |  | NULL | 통신망유형 |
| ERR_CMCL_NET_SCRTY_MSRS | varchar(30) | ✓ |  | NULL | 상용망보안방안 |
| ERR_COMM_SPD | varchar(30) | ✓ |  | NULL | 통신속도(Mbps) |
| ERR_TLCMCO_NM | varchar(30) | ✓ |  | NULL | 통신사명 |
| ERR_LINE_NO | varchar(50) | ✓ |  | NULL | 회선번호 |
| ERR_KEPCO_CUST_TELNO | varchar(30) | ✓ |  | NULL | 한전고객번호 |
| ERR_PAY_NO | varchar(30) | ✓ |  | NULL | 납부번호 |
| ERR_MSINS_NO | varchar(30) | ✓ |  | NULL | 계량기번호 |
| ERR_CCTV_INFBD_TYPE | varchar(30) | ✓ |  | NULL | CCTV안내판종류 |
| ERR_EMGBELL_YN | varchar(30) | ✓ |  | NULL | 비상벨유무 |
| ERR_EMGBELL_SHP | varchar(30) | ✓ |  | NULL | 비상벨형태 |
| ERR_EMGBELL_TYPE | varchar(30) | ✓ |  | NULL | 비상벨유형 |
| ERR_EMGBELL_MNFTR_CO | varchar(30) | ✓ |  | NULL | 비상벨 제조사 |
| ERR_EMGBELL_MDL_NM | varchar(50) | ✓ |  | NULL | 비상벨 모델명 |
| ERR_EMGBELL_IP_ADDR | varchar(30) | ✓ |  | NULL | 비상벨 IP주소 |
| ERR_EMGBELL_INSTL_YMD | varchar(30) | ✓ |  | NULL | 비상벨설치년월일 |
| ERR_EMGBELL_INFBD_YN | varchar(30) | ✓ |  | NULL | 비상벨안내판유무 |
| ERR_EDB_YN | varchar(30) | ✓ |  | NULL | 전광판유무 |
| ERR_EDB_TYPE | varchar(30) | ✓ |  | NULL | 전광판유형 |
| ERR_EDB_MNFTR_CO | varchar(30) | ✓ |  | NULL | 전광판제조사 |
| ERR_EDB_IP_ADDR | varchar(30) | ✓ |  | NULL | 전광판IP주소 |
| ERR_EDB__INSTL_YMD | varchar(30) | ✓ |  | NULL | 전광판설치년월일 |
| ERR_LPJ_YN | varchar(30) | ✓ |  | NULL | 로고젝터유무 |
| ERR_LPJ_MNFTR_CO | varchar(30) | ✓ |  | NULL | 로고젝터제조사 |
| ERR_LPJ_MDL_NM | varchar(30) | ✓ |  | NULL | 로고젝터모델명 |
| ERR_LPJ__INSTL_YMD | varchar(30) | ✓ |  | NULL | 로고젝터설치년월일 |
| ERR_SPKR_YN | varchar(30) | ✓ |  | NULL | 스피커유무 |
| ERR_SPKR__INSTL_YMD | varchar(30) | ✓ |  | NULL | 스피커설치년월일 |
| ERR_ETC_INFBD_YN | varchar(30) | ✓ |  | NULL | 기타안내판유무 |
| ERR_ETC_INFBD_TYPE | varchar(30) | ✓ |  | NULL | 기타안내판유형 |
| ERR_ETC_EQPMNT_1 | varchar(30) | ✓ |  | NULL | 기타장비1 |
| ERR_ETC_EQPMNT_2 | varchar(30) | ✓ |  | NULL | 기타장비2 |
| ERR_ETC_EQPMNT_3 | varchar(30) | ✓ |  | NULL | 기타장비3 |
| FAIL_REASON | varchar(1000) | ✗ |  | NULL | 실패 사유 |

### MNG_RESOURCE_STATS_NTN
> 제조국별 CCTV 통계관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STATS_NTN_ID | bigint(20) | ✗ | 🔑 PK | NULL | 제조국별통계아이디 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| INSTL_YR | varchar(4) | ✗ |  | '' | 설치연도 |
| KOR_COM | int(11) | ✗ |  | 0 | 국산대수 |
| CHN_COM | int(11) | ✗ |  | 0 | 중국산대수 |
| SWE_COM | int(11) | ✗ |  | 0 | 스웨덴산대수 |
| USA_COM | int(11) | ✗ |  | 0 | 미국산대수 |
| JPN_COM | int(11) | ✗ |  | 0 | 일본산대수 |
| ETC_COM | int(11) | ✗ |  | 0 | 기타제조국대수 |
| UNK_COM | int(11) | ✗ |  | 0 | 제조국확인불가대수 |
| REG_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_RESOURCE_STATS_PXL
> 화소별 CCTV 통계관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STATS_PXL_ID | bigint(20) | ✗ | 🔑 PK | NULL | 화소별통계아이디 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| PXL_RNG_CD | varchar(20) | ✗ |  | '' | 화소범위코드 |
| INSTL_PRPS_LIV_CP_CNTOM | int(11) | ✗ |  | 0 | 생활방범용CCTV대수 |
| INSTL_PRPS_VEH_CP_CNTOM | int(11) | ✗ |  | 0 | 차량방범용CCTV대수 |
| INSTL_PRPS_CHD_CNTOM | int(11) | ✗ |  | 0 | 어린이보호용CCTV대수 |
| INSTL_PRPS_PARK_CNTOM | int(11) | ✗ |  | 0 | 도시공원놀이터용CCTV대수 |
| INSTL_PRPS_GBG_CNTOM | int(11) | ✗ |  | 0 | 쓰레기투기용CCTV대수 |
| INSTL_PRPS_FAC_CNTOM | int(11) | ✗ |  | 0 | 시설관리용CCTV대수 |
| INSTL_PRPS_FIR_CNTOM | int(11) | ✗ |  | 0 | 화재용CCTV대수 |
| INSTL_PRPS_DIS_CNTOM | int(11) | ✗ |  | 0 | 재난안전용CCTV대수 |
| INSTL_PRPS_TRF_CNTOM | int(11) | ✗ |  | 0 | 교통단속용CCTV대수 |
| INSTL_PRPS_TRF_INFO_CNTOM | int(11) | ✗ |  | 0 | 교통정보수집용CCTV대수 |
| INSTL_PRPS_ETC_CNTOM | int(11) | ✗ |  | 0 | 기타목적용CCTV대수 |
| REG_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_RESOURCE_STATS_STRG
> 영상저장현황 CCTV 통계관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STATS_STRG_ID | bigint(20) | ✗ | 🔑 PK | NULL | 영상저장통계아이디 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| STRG_MTHD_ORGNL_COM | int(11) | ✗ |  | 0 | 저장방법이원본인CCTV대수 |
| STRG_MTHD_UNDER_2MP_UNDER_30FPS | int(11) | ✗ |  | 0 | 저장방법이200만화소미만인CCTV대수 |
| STRG_MTHD_UNDER_2MP_OVER_30FPS | int(11) | ✗ |  | 0 | 저장방법이200만화소이상인CCTV대수 |
| STRG_MTHD_OVER_2MP_UNDER_30FPS | int(11) | ✗ |  | 0 | STRG_MTHD_OVER_2MP_UNDER_30FPS |
| STRG_MTHD_OVER_2MP_OVER_30FPS | int(11) | ✗ |  | 0 | STRG_MTHD_OVER_2MP_OVER_30FPS |
| STRG_MTHD_ETC_COM | int(11) | ✗ |  | 0 | 기타저장대수 |
| STRG_MTHD_NOT_SAVED_COM | int(11) | ✓ |  | NULL | 미저장대수 |
| STRG_PRD | int(11) | ✓ |  | NULL | 저장기간 |
| REG_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_RESOURCE_STATS_TECH
> 최신기술 적용현황 CCTV 통계관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STATS_TECH_ID | bigint(20) | ✗ | 🔑 PK | NULL | 최신기술통계아이디 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| INTL_MON_TYPE_CD | varchar(20) | ✗ |  | '' | 지능형관제유형코드 |
| INSTL_PRPS_LIV_CP_CNTOM | int(11) | ✗ |  | 0 | 생활방범용CCTV대수 |
| INSTL_PRPS_VEH_CP_CNTOM | int(11) | ✗ |  | 0 | 차량방범용CCTV대수 |
| INSTL_PRPS_CHD_CNTOM | int(11) | ✗ |  | 0 | 어린이보호용CCTV대수 |
| INSTL_PRPS_PARK_CNTOM | int(11) | ✗ |  | 0 | 도시공원놀이터용CCTV대수 |
| INSTL_PRPS_GBG_CNTOM | int(11) | ✗ |  | 0 | 쓰레기투기용CCTV대수 |
| INSTL_PRPS_FAC_CNTOM | int(11) | ✗ |  | 0 | 시설관리용CCTV대수 |
| INSTL_PRPS_FIR_CNTOM | int(11) | ✗ |  | 0 | 화재용CCTV대수 |
| INSTL_PRPS_DIS_CNTOM | int(11) | ✗ |  | 0 | 재난안전용CCTV대수 |
| INSTL_PRPS_TRF_CNTOM | int(11) | ✗ |  | 0 | 교통단속용CCTV대수 |
| INSTL_PRPS_TRF_INFO_CNTOM | int(11) | ✗ |  | 0 | 교통정보수집용CCTV대수 |
| INSTL_PRPS_ETC_CNTOM | int(11) | ✗ |  | 0 | 기타목적용CCTV대수 |
| REG_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

### MNG_RESOURCE_STATS_YEAR
> 년도별 CCTV 통계관리

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STATS_YR_ID | bigint(20) | ✗ | 🔑 PK | NULL | 설치년도별통계아이디 |
| SIDO_CD | varchar(10) | ✗ |  | '' | 시도코드 |
| SGG_CD | varchar(10) | ✗ |  | '' | 시군구코드 |
| INSTL_YR | varchar(4) | ✗ |  | '' | 설치연도 |
| INSTL_PRPS_LIV_CP_CNTOM | int(11) | ✗ |  | 0 | 생활방범용CCTV대수 |
| INSTL_PRPS_VEH_CP_CNTOM | int(11) | ✗ |  | 0 | 차량방범용CCTV대수 |
| INSTL_PRPS_CHD_CNTOM | int(11) | ✗ |  | 0 | 어린이보호용CCTV대수 |
| INSTL_PRPS_PARK_CNTOM | int(11) | ✗ |  | 0 | 도시공원놀이터용CCTV대수 |
| INSTL_PRPS_GBG_CNTOM | int(11) | ✗ |  | 0 | 쓰레기투기용CCTV대수 |
| INSTL_PRPS_FAC_CNTOM | int(11) | ✗ |  | 0 | 시설관리용CCTV대수 |
| INSTL_PRPS_FIR_CNTOM | int(11) | ✗ |  | 0 | 화재용CCTV대수 |
| INSTL_PRPS_DIS_CNTOM | int(11) | ✗ |  | 0 | 재난안전용CCTV대수 |
| INSTL_PRPS_TRF_CNTOM | int(11) | ✗ |  | 0 | 교통단속용CCTV대수 |
| INSTL_PRPS_TRF_INFO_CNTOM | int(11) | ✗ |  | 0 | 교통정보수집용CCTV대수 |
| INSTL_PRPS_ETC_CNTOM | int(11) | ✗ |  | 0 | 기타목적용CCTV대수 |
| REG_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFCN_DT | datetime | ✓ |  | current_timestamp() | 수정일시 |

---

## MNG_SET_ — 설정/이력

### MNG_SET_HSTRY_PRVC_ACCS
> 개인정보접속기록 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| HSTRY_ID | bigint(20) | ✗ | 🔑 PK | NULL | 이력ID |
| HSTRY_CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 이력생성일시 |
| SYS_TYPE | varchar(20) | ✗ |  | '' | 시스템 유형(관제지원:KLID, 지자체:GOV) |
| LCLGV_CD | varchar(20) | ✗ |  | '' | 지자체 코드 |
| USER_ID | varchar(30) | ✗ | 🔗 FK | '' | 사용자ID |
| ACTION_TYPE | varchar(30) | ✗ | 🔗 FK | '' | 작업유형 |
| ACTION_DTL | varchar(200) | ✓ |  | NULL | 작업상세 |
| INFO_IDNTF | varchar(50) | ✓ |  | NULL | 정보식별자 |
| IP_ADDRESS | varchar(30) | ✗ | 🔗 FK | '' | IP주소 |

### MNG_SET_HSTRY_USER_DEL
> 개인정보파기기록 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| HSTRY_ID | bigint(20) | ✗ | 🔑 PK | NULL | 이력ID |
| HSTRY_CRT_DT | datetime | ✗ |  | current_timestamp() | 이력생성일시 |
| USER_ID | varchar(30) | ✗ |  | '' | 사용자ID |
| ACTION_TYPE | varchar(30) | ✗ |  | '' | 작업유형 |
| USER_REG_DT | datetime | ✓ |  | NULL | 사용자등록일시 |
| USER_DEL_DT | datetime | ✓ |  | NULL | 사용자삭제일시 |

### MNG_SET_HSTRY_USER_ROLE
> 사용자 권한 기록 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| HSTRY_ID | bigint(20) | ✗ | 🔑 PK | NULL | 이력ID |
| HSTRY_CRT_DT | datetime | ✗ | 🔗 FK | current_timestamp() | 이력생성일시 |
| SYS_TYPE | varchar(30) | ✗ |  | NULL | SYS 유형 |
| LCLGV_CD | varchar(20) | ✗ |  | '' | 지자체 코드 |
| USER_ID | varchar(30) | ✗ | 🔗 FK | '' | 사용자ID |
| ACTION_TYPE | varchar(30) | ✗ | 🔗 FK | '' | 작업유형 |
| ACTION_DTL | varchar(200) | ✓ |  | NULL | 작업상세 |
| INFO_IDNTF | varchar(30) | ✓ |  | NULL | 정보식별자 |
| IP_ADDRESS | varchar(30) | ✗ | 🔗 FK | '' | IP주소 |

---

## MNG_ — 기타 (메뉴, 설정 등)

### MNG_MENU
> 메뉴 리스트 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| MENU_CD | varchar(20) | ✗ | 🔑 PK | NULL | 메뉴 코드 |
| MENU_NM | varchar(200) | ✗ |  | NULL | 메뉴 이름 |

### MNG_SGG
> 시도/시군구 지역 코드 매핑 정보 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SGG_CD | varchar(10) | ✗ | 🔑 PK | NULL | 시군구코드 |
| SIDO_CD | varchar(10) | ✗ | 🔑 PK | '' | 시도명 |
| SGG_NM | varchar(20) | ✗ |  | '' | 시군구명 |
| SIDO_NM | varchar(20) | ✗ |  | NULL | SIDO 명 |
| STDG_CD | varchar(45) | ✓ | UNI | NULL | STDG 코드 |

### MNG_STNG
> 시스템 설정 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| STNG_KEY | varchar(100) | ✗ | 🔑 PK | NULL | STNG_KEY |
| STNG_VALUE | varchar(4000) | ✗ |  | NULL | STNG_VALUE |
| STNG_EXPLN | varchar(4000) | ✗ |  | NULL | 설명설명 |
| CRTR_ID | varchar(30) | ✗ |  | '' | 등록자아이디 |
| CRT_DT | datetime | ✗ |  | current_timestamp() | 등록일시 |
| MDFR_ID | varchar(30) | ✗ |  | '' | 수정자아이디 |
| MDFGN_DT | datetime | ✗ |  | current_timestamp() | 수정일시 |

---

## QRTZ_ — Quartz 스케줄러

### QRTZ_BLOB_TRIGGERS
> BLOB 데이터 기반 트리거 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_NAME | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |
| BLOB_DATA | blob | ✓ |  | NULL | BLOB_DATA |

### QRTZ_CALENDARS
> 스케줄링 제외 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| CALENDAR_NAME | varchar(200) | ✗ | 🔑 PK | NULL | CALENDAR_NAME |
| CALENDAR | blob | ✗ |  | NULL | CALENDAR |

### QRTZ_CRON_TRIGGERS
> 크론 트리거 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_NAME | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |
| CRON_EXPRESSION | varchar(200) | ✗ |  | NULL | CRON_EXPRESSION |
| TIME_ZONE_ID | varchar(80) | ✓ |  | NULL | TIME_ZONE 아이디 |

### QRTZ_FIRED_TRIGGERS
> 현재 실행중인 트리거 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| ENTRY_ID | varchar(95) | ✗ | 🔑 PK | NULL | ENTRY 아이디 |
| TRIGGER_NAME | varchar(200) | ✗ |  | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ |  | NULL | TRIGGER_GROUP |
| INSTANCE_NAME | varchar(200) | ✗ |  | NULL | INSTANCE_NAME |
| FIRED_TIME | bigint(13) | ✗ |  | NULL | FIRED_TIME |
| SCHED_TIME | bigint(13) | ✗ |  | NULL | SCHED_TIME |
| PRIORITY | int(11) | ✗ |  | NULL | PRIORITY |
| STATE | varchar(16) | ✗ |  | NULL | STATE |
| JOB_NAME | varchar(200) | ✓ |  | NULL | JOB_NAME |
| JOB_GROUP | varchar(200) | ✓ |  | NULL | JOB_GROUP |
| IS_NONCONCURRENT | varchar(1) | ✓ |  | NULL | IS_NONCONCURRENT |
| REQUESTS_RECOVERY | varchar(1) | ✓ |  | NULL | REQUESTS_RECOVERY |

### QRTZ_JOB_DETAILS
> 작업 상세 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| JOB_NAME | varchar(200) | ✗ | 🔑 PK | NULL | JOB_NAME |
| JOB_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | JOB_GROUP |
| DESCRIPTION | varchar(250) | ✓ |  | NULL | DESCRIPTION |
| JOB_CLASS_NAME | varchar(250) | ✗ |  | NULL | JOB_CLASS_NAME |
| IS_DURABLE | varchar(1) | ✗ |  | NULL | IS_DURABLE |
| IS_NONCONCURRENT | varchar(1) | ✗ |  | NULL | IS_NONCONCURRENT |
| IS_UPDATE_DATA | varchar(1) | ✗ |  | NULL | IS_UPDATE_DATA |
| REQUESTS_RECOVERY | varchar(1) | ✗ |  | NULL | REQUESTS_RECOVERY |
| JOB_DATA | blob | ✓ |  | NULL | JOB_DATA |

### QRTZ_LOCKS

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| LOCK_NAME | varchar(40) | ✗ | 🔑 PK | NULL | LOCK_NAME |

### QRTZ_PAUSED_TRIGGER_GRPS
> 일시 정지된 트리거 그룹 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |

### QRTZ_SCHEDULER_STATE
> 스케줄러 인스턴스 상태 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| INSTANCE_NAME | varchar(200) | ✗ | 🔑 PK | NULL | INSTANCE_NAME |
| LAST_CHECKIN_TIME | bigint(13) | ✗ |  | NULL | LAST_CHECKIN_TIME |
| CHECKIN_INTERVAL | bigint(13) | ✗ |  | NULL | CHECKIN_INTERVAL |

### QRTZ_SIMPLE_TRIGGERS
> 심플 트리거 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_NAME | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |
| REPEAT_COUNT | bigint(7) | ✗ |  | NULL | REPEAT_COUNT |
| REPEAT_INTERVAL | bigint(12) | ✗ |  | NULL | REPEAT_INTERVAL |
| TIMES_TRIGGERED | bigint(10) | ✗ |  | NULL | TIMES_TRIGGERED |

### QRTZ_SIMPROP_TRIGGERS
> 특정 속성 트리거 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_NAME | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |
| STR_PROP_1 | varchar(512) | ✓ |  | NULL | STR_PROP_1 |
| STR_PROP_2 | varchar(512) | ✓ |  | NULL | STR_PROP_2 |
| STR_PROP_3 | varchar(512) | ✓ |  | NULL | STR_PROP_3 |
| INT_PROP_1 | int(11) | ✓ |  | NULL | INT_PROP_1 |
| INT_PROP_2 | int(11) | ✓ |  | NULL | INT_PROP_2 |
| LONG_PROP_1 | bigint(20) | ✓ |  | NULL | LONG_PROP_1 |
| LONG_PROP_2 | bigint(20) | ✓ |  | NULL | LONG_PROP_2 |
| DEC_PROP_1 | decimal(13,4) | ✓ |  | NULL | DEC_PROP_1 |
| DEC_PROP_2 | decimal(13,4) | ✓ |  | NULL | DEC_PROP_2 |
| BOOL_PROP_1 | varchar(1) | ✓ |  | NULL | BOOL_PROP_1 |
| BOOL_PROP_2 | varchar(1) | ✓ |  | NULL | BOOL_PROP_2 |

### QRTZ_TRIGGERS
> 트리거 기본 정보 관리 테이블

| 컬럼명 | 타입 | NULL | KEY | 기본값 | 설명 |
|--------|------|:----:|:---:|--------|------|
| SCHED_NAME | varchar(120) | ✗ | 🔑 PK | NULL | SCHED_NAME |
| TRIGGER_NAME | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_NAME |
| TRIGGER_GROUP | varchar(200) | ✗ | 🔑 PK | NULL | TRIGGER_GROUP |
| JOB_NAME | varchar(200) | ✗ |  | NULL | JOB_NAME |
| JOB_GROUP | varchar(200) | ✗ |  | NULL | JOB_GROUP |
| DESCRIPTION | varchar(250) | ✓ |  | NULL | DESCRIPTION |
| NEXT_FIRE_TIME | bigint(13) | ✓ |  | NULL | NEXT_FIRE_TIME |
| PREV_FIRE_TIME | bigint(13) | ✓ |  | NULL | PREV_FIRE_TIME |
| PRIORITY | int(11) | ✓ |  | NULL | PRIORITY |
| TRIGGER_STATE | varchar(16) | ✗ |  | NULL | TRIGGER_STATE |
| TRIGGER_TYPE | varchar(8) | ✗ |  | NULL | TRIGGER 유형 |
| START_TIME | bigint(13) | ✗ |  | NULL | START_TIME |
| END_TIME | bigint(13) | ✓ |  | NULL | END_TIME |
| CALENDAR_NAME | varchar(200) | ✓ |  | NULL | CALENDAR_NAME |
| MISFIRE_INSTR | smallint(2) | ✓ |  | NULL | MISFIRE_INSTR |
| JOB_DATA | blob | ✓ |  | NULL | JOB_DATA |

---
