# KLID-AI 데이터베이스 설계서 분석

> **원본 문서**: KLID-AI-데이터베이스 설계서 V1.4_20251208.hwp  
> **프로젝트명**: AI 기반 지자체 CCTV 관제지원시스템 구축  
> **문서 버전**: V1.4 (최종 수정일: 2025.12.08)  
> **분석 작성일**: 2026.04.14

---

## 제개정 이력

| 버전 | 작성일 | 작성자 | 주요 변경 내용 |
|:---:|--------|--------|--------------|
| 1.0 | 2025.08.08 | 이주화, 강현우 | 초안 작성 |
| 1.2 | 2025.11.15 | 이주화 | 감리 보완 사항 반영 |
| 1.4 | 2025.12.08 | 강현우 | 현행화 (최종버전) |

---

## 1. 데이터베이스 기본 정보

| 항목 | 내용 |
|------|------|
| **DB 식별자** | KLID-AI-DB-001 |
| **DBMS** | **MySQL 8.0 이상** |
| **스토리지 엔진** | **InnoDB** (`.ibd` 파일 포맷) |
| **파일 형식** | 테이블별 `.ibd` 개별 파일 |
| **주요 목적** | AI 학습 데이터 제작 및 저작도구 시스템 관리 |
| **전체 테이블 수** | **29개** |

---

## 2. 전체 테이블 목록

| 테이블 ID | 테이블명 | 설명 | 예상 용량 |
|----------|---------|------|----------|
| KLID-AI-TS-001 | **LS_ATCH_FILE** | 첨부파일 관리 | 2.25MB |
| KLID-AI-TS-003 | **LS_DATA_ISSUE** | 학습 데이터 이슈 관리 | - |
| KLID-AI-TS-005 | **LS_DATA_LBL_HSTRY** | 데이터 라벨링 이력 | **9,660MB** |
| KLID-AI-TS-007 | **LS_DATA_META** | 학습 데이터 추가 메타데이터 | 548MB |
| KLID-AI-TS-009 | **LS_DATA_META_HSTRY** | 메타데이터 변경 이력 | 2,192건 |
| KLID-AI-TS-011 | **LS_DATA_SRC** | 학습용 원천(원시) 데이터 | **1,741MB** |
| KLID-AI-TS-013 | **LS_DATA_SRC_HSTRY** | 원천 데이터 변경 이력 | 1,708MB |
| KLID-AI-TS-015 | **LS_DATA_RAW_HSTRY** | 데이터 원시 이력 | - |
| KLID-AI-TS-017 | **LS_NTC_BBS** | 공지사항 게시판 | - |
| KLID-AI-TS-019 | **LS_PJT** | 학습 프로젝트 마스터 | - |
| KLID-AI-TS-021 | **LS_PJT_DATA_MPNG** | 프로젝트-데이터 매핑 | - |
| KLID-AI-TS-023 | **LS_PJT_DATA_STATS** | 프로젝트 데이터 통계 | 49MB |
| KLID-AI-TS-025 | **LS_PJT_DATA_STTS** | 프로젝트 데이터 상태 추적 | **537MB** |
| KLID-AI-TS-027 | **LS_PJT_DDLN** | 프로젝트 마감 이력 | - |
| KLID-AI-TS-029 | **LS_PJT_JOB_STATS** | 프로젝트 작업 통계 | - |
| - | **LS_PJT_LBL** | 프로젝트 라벨 설정 | - |
| - | **LS_PJT_META** | 프로젝트 메타데이터 | - |
| - | **LS_PJT_STG_IGI** | 프로젝트 단계별 검수 설정 | - |
| - | **LS_PJT_STG_PRC** | 프로젝트 단계별 가공 처리 | **272MB** |
| - | **LS_PJT_USER_AUTHRT** | 프로젝트 사용자 권한 매핑 | - |
| - | **LS_PJT_USER_AUTHRT_HSTRY** | 프로젝트 사용자 권한 이력 | - |
| - | **LS_USER_MENU** | 사용자 메뉴 구조 | 5KB |
| - | **LS_USER_ROLE** | 사용자 역할/권한 | - |
| - | **LS_DATA_AUG** | AI 데이터 자동 증강 | - |

> 연간 데이터 발생 기준: 라벨 이력 360만 건 / 원천 데이터 120만 건

---

## 3. 주요 테이블 컬럼 정의

### 3.1 LS_DATA_SRC — 원천(원시) 데이터 ★핵심★

> CCTV 클립 영상의 모든 메타데이터를 관리하는 핵심 테이블

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `DATA_RAW_SN` | bigint | **PK** | 데이터 원시 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `PLFM_ID` | varchar | FK | 플랫폼 ID |
| `RAW_FILE_NM` | varchar(1000) | | 원시 파일명 |
| `RAW_DATA_TYPE_CD` | varchar | | 데이터 유형 코드 |
| `FILE_FMT` | varchar | | 파일 포맷 |
| `SHT_DT` | datetime | | 촬영 일자 |
| `SHT_PRSN` | varchar | | 촬영 인원/장비 |
| `SHT_LC` | varchar | | 촬영 위치 |
| `VDO_CDC` | varchar(10) | | **비디오 코덱** |
| `FRM_CNT` | int | | **프레임 수** |
| `WDTH` | int | | 너비 (pixel) |
| `HGT` | int | | 높이 (pixel) |
| `RSLTN` | varchar | | **해상도** |
| `LCLGV_CD` | varchar | | **법정동 코드** |
| `SESN_CD` | varchar | | **계절 코드** |
| `WTHR_CD` | varchar | | **날씨 코드** |
| `HR_TYPE_CD` | varchar | | **시간 유형 코드** (주간/야간 등) |
| `EVNT_TYPE_CD` | varchar | | **이벤트 유형 코드** |
| `EVNT_NM` | varchar | | 이벤트명 |
| `EVNT_END_DT` | datetime | | 이벤트 종료 시간 |
| `DE_IDNTF_YN` | varchar(1) | | **개인정보 포함 여부** (Y/N) |
| `AI_CRT_YN` | varchar(1) | | **AI 생성 여부** (Y/N) |
| `USER_REG_YN` | varchar(1) | | 사용자 등록 여부 |
| `WGS84_LAT` | decimal(10,7) | | **WGS84 위도** |
| `WGS84_LOT` | decimal(10,7) | | **WGS84 경도** |
| `CLCT_SRC` | varchar | | 수집 경로 |
| `VMS_CCTV_ID` | varchar(300) | | **VMS CCTV ID** |
| `STP_CYCL` | varchar(500) | | 보관 주기 |
| `DATA_STTS_CD` | varchar | | 데이터 상태 코드 |
| `PRVC_YN` | varchar(1) | | **비식별 여부** (Y/N) |
| `PROMPT_CN` | varchar | | 프롬프트 내용 (AI 생성용) |

---

### 3.2 LS_PJT — 학습 프로젝트 마스터

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `PJT_SN` | bigint | **PK** | 프로젝트 일련번호 |
| `PJT_ID` | varchar | | 프로젝트 ID |
| `PJT_NM` | varchar | | 프로젝트명 |
| `PJT_CN` | longtext | | 프로젝트 설명 |
| `PJT_STTS_CD` | varchar | | 프로젝트 상태 코드 |
| `EVNT_CD` | varchar | | 이벤트 코드 |
| `GOAL_QTY` | int | | 목표 수량 |
| `VER` | varchar | | 버전 |
| `DDLN_YN` | varchar(1) | | 마감 여부 |
| `DDLN_DT` | datetime | | 마감 일시 |
| `PJT_AUG_STTS_CD` | varchar | | **증강 상태 코드** |
| `PJT_AUG_START_DT` | datetime | | 증강 시작 시각 |
| `PJT_DSET_STTS_CD` | varchar | | 데이터셋 상태 코드 |
| `PJT_DSET_START_DT` | datetime | | 데이터셋 추출 시작 시각 |

---

### 3.3 LS_DATA_LBL_HSTRY — 데이터 라벨링 이력 ★최대 용량★

> 모든 라벨링 작업 내역을 기록. **연간 9,660MB** (360만 건) 예상

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `DATA_LBL_HSTRY_SN` | bigint | **PK** | 데이터 라벨 이력 일련번호 |
| `DATA_LBL_SN` | bigint | FK | 데이터 라벨 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `DATA_SRC_SN` | bigint | FK | 원천 데이터 일련번호 |
| `LBL_SN` | bigint | FK | 라벨 일련번호 |
| `ACTION_DT` | datetime | | 행위 일시 |
| `ACTION_TYPE_CD` | varchar | | 행위 유형 코드 |
| `TRCK_USE_YN` | varchar(1) | | **트랙 사용 여부** |
| `TRCK_FRM_YN` | varchar(1) | | **트랙 프레임 여부** |
| `TRCK_ID` | varchar | | **트랙 ID** |
| `ATRB` | varchar(4000) | | **속성 정보** (JSON 형태) |
| `SORT_SEQ` | int | | 순서 |
| `REG_ID` | varchar(30) | | 등록 사용자 ID |
| `REG_DT` | datetime | | 등록 일시 |
| `MDFCN_ID` | varchar(30) | | 수정 사용자 ID |
| `MDFCN_DT` | datetime | | 수정 일시 |

---

### 3.4 LS_PJT_LBL — 프로젝트 라벨 설정

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `PJT_LBL_SN` | bigint | **PK** | 프로젝트 라벨 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `LBL_ID` | varchar | | 라벨 ID |
| `PRC_TYPE_CD` | varchar | | 처리 유형 코드 (바운딩박스/폴리곤/스켈레톤 등) |
| `UP_LBL_SN` | bigint | | 상위 라벨 일련번호 (계층 구조) |
| `LBL_NM` | varchar | | 라벨명 |
| `LBL_CLR` | varchar(7) | | 라벨 색상 (HEX) |
| `LBL_CN` | longtext | | 라벨 내용/설명 |
| `META_ID` | varchar | | 메타 ID |
| `META_NM` | varchar | | 메타 명칭 |
| `META_CN` | longtext | | 메타 내용 |
| `BSC_RLT_CRD_X` | decimal | | 기본 상대 좌표 X (스켈레톤 키포인트) |
| `BSC_RLT_CRD_Y` | decimal | | 기본 상대 좌표 Y (스켈레톤 키포인트) |

---

### 3.5 LS_PJT_STG_PRC — 프로젝트 단계별 가공 처리

> 272MB / 각 단계의 가공 작업 처리 현황 추적

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `STG_SN` | bigint | **PK** | 스테이지 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `TASK_TYPE_CD` | varchar | | 작업 유형 코드 |
| `PJT_DATA_STTS_CD` | varchar | | 프로젝트 데이터 상태 코드 |
| `TRSF_FRM_CNT` | int | | 변환 프레임 수 |
| `FPS_TYPE_CD` | varchar | | FPS 유형 코드 |

---

### 3.6 LS_PJT_STG_IGI — 프로젝트 단계별 검수 설정

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `STG_SN` | bigint | **PK** | 스테이지 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `IGI_SEQ` | int | | 검수 순서 (1차, 2차...) |
| `CLCLN_UNTPRC` | decimal | | 정산 단가 |

---

### 3.7 LS_DATA_AUG — AI 데이터 자동 증강

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `DATA_AUG_SN` | bigint | **PK** | 데이터 증강 일련번호 |
| `DATA_RAW_SN` | bigint | FK | 원천 데이터 일련번호 |
| `PJT_AUG_OPT_CD` | varchar | | 증강 옵션 코드 |
| `AUG_IMG_FILE_PATH` | varchar | | 증강 이미지 파일 경로 |
| `AUG_STTS_CD` | varchar | | 증강 처리 상태 코드 |
| `ERROR_CD` | varchar | | 에러 코드 |
| `ERROR_MSG` | varchar(4000) | | 에러 메시지 |
| `RTRY_CNT` | int | | **재시도 횟수** |
| `LAST_TRY_DT` | datetime | | 최종 시도 일시 |
| `BRGT_OFST` | float | | **밝기 보정값** |

---

### 3.8 LS_PJT_DATA_STTS — 프로젝트 데이터 상태 추적

> 537MB / 원시 데이터가 프로젝트에 할당될 때마다 레코드 증가

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `PJT_DATA_STTS_SN` | bigint | **PK** | 프로젝트 경로 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `DATA_RAW_SN` | bigint | FK | 원천 데이터 일련번호 |
| `PJT_DATA_STTS_CD` | varchar | | 프로젝트 데이터 상태 코드 |
| `TRSF_FRM_CNT` | int | | 변환 프레임 수 |
| `FPS_TYPE_CD` | varchar | | FPS 유형 코드 |

---

### 3.9 LS_PJT_DATA_STATS — 프로젝트 데이터 통계

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `PJT_DATA_STAT_SN` | bigint | **PK** | 통계 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `DATA_SET_SN` | bigint | FK | 데이터셋 일련번호 |
| `STATS_BLN_YM` | varchar(2) | | 통계 귀속 연월 |
| `STATS_CRTR_DT` | datetime | | 통계 생성 일시 |
| `VDO_CNT` | int | | 영상 수 |
| `FRM_CNT` | int | | 프레임 수 |
| `YM_LAST_YN` | varchar(1) | | 당월 최종 여부 |
| `DATA_SET_STTS_CD` | varchar | | 데이터셋 상태 코드 |
| `ERROR_CD` | varchar(50) | | 에러 코드 |
| `ERROR_MSG` | varchar(4000) | | 에러 메시지 |
| `LAST_TRY_DT` | datetime | | 최종 시도 일시 |

---

### 3.10 LS_DATA_ISSUE — 데이터 이슈 관리

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `DATA_ISSUE_SN` | bigint | **PK** | 데이터 이슈 일련번호 |
| `UP_DATA_ISSUE_SN` | bigint | | 상위 이슈 일련번호 |
| `PJT_SN` | bigint | FK | 프로젝트 일련번호 |
| `DATA_RAW_SN` | varchar | FK | 원천 데이터 일련번호 |
| `ISSUE_TYPE_CD` | varchar(20) | | 이슈 유형 코드 |
| `RJCT_DTL_CD` | varchar | | 관리자 반려 상세 코드 |
| `ISSUE_CN` | longtext | | 이슈 내용 |

---

### 3.11 LS_ATCH_FILE — 첨부파일 관리

| 컬럼명 | 타입 | PK/FK | 설명 |
|--------|------|-------|------|
| `ATCH_FILE_ID` | varchar(36) | **PK** | 첨부파일 ID |
| `ORGNL_FILE_NM` | varchar(200) | | 원본 파일명 |
| `ATCH_FILE_PATH` | varchar | | 파일 경로 |
| `FILE_SIZE` | bigint | | 파일 크기 |
| `USE_YN` | varchar(30) | | 사용 여부 |
| `REG_DT` | datetime | | 등록 일시 |
| `MDFCN_DT` | datetime | | 수정 일시 |

---

### 3.12 LS_USER_ROLE — 사용자 역할/권한

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| `ROLE_CD` | varchar | 역할 코드 (PK) |
| `ROLE_NM` | varchar | 역할명 |
| `USE_YN` | varchar(1) | 사용 여부 |

---

### 3.13 LS_USER_MENU — 사용자 메뉴 구조

> 계층형 메뉴 구조 관리

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| `MENU_SN` | bigint | 메뉴 일련번호 (PK) |
| `UP_MENU_SN` | bigint | 상위 메뉴 일련번호 (자기참조 FK) |
| `MENU_NM` | varchar | 메뉴명 |
| `MENU_CN` | varchar | 메뉴 설명 |
| `MENU_URL` | varchar(2000) | 메뉴 URL |
| `MENU_DEPTH` | int | 계층 (Depth) |

---

### 3.14 LS_PJT_USER_AUTHRT — 프로젝트 사용자 권한 매핑

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| `PJT_SN` | bigint | 프로젝트 일련번호 (FK) |
| `USER_ID` | varchar | 사용자 ID |
| `AUTHRT_CD` | varchar | 권한 코드 (작업자/검수자/담당자 등) |
| `STG_SN` | bigint | 스테이지 일련번호 |

---

### 3.15 LS_PJT_DDLN — 프로젝트 마감 이력

| 컬럼명 | 타입 | 설명 |
|--------|------|------|
| `DDLN_SN` | bigint | 마감 이력 일련번호 (PK) |
| `PJT_SN` | bigint | 프로젝트 일련번호 (FK) |
| `VER` | varchar | 버전 |
| `VER_CN` | varchar | 버전 설명 |
| `DDLN_DT` | datetime | 마감 일시 |

---

## 4. ERD 관계도

```
┌─────────────────────────────────────────────────────────────────┐
│                         LS_PJT (프로젝트)                        │
│                           PJT_SN (PK)                            │
└──┬─────────┬──────────┬──────────┬──────────┬──────────┬────────┘
   │         │          │          │          │          │
   ▼         ▼          ▼          ▼          ▼          ▼
LS_PJT_   LS_PJT_   LS_PJT_   LS_PJT_   LS_PJT_   LS_PJT_
DATA_MPNG  LBL      STG_IGI   STG_PRC  DATA_STTS   DDLN
(데이터매핑)(라벨설정)(검수단계)(가공단계)(데이터상태)(마감이력)
   │
   ▼
LS_DATA_SRC (원천 데이터 - 핵심)
DATA_RAW_SN (PK)
   │
   ├──► LS_DATA_SRC_HSTRY   (원천 데이터 변경 이력)
   ├──► LS_DATA_RAW_HSTRY   (원시 이력)
   ├──► LS_DATA_ISSUE        (이슈 관리)
   ├──► LS_DATA_LBL_HSTRY   (라벨링 이력 ★9,660MB★)
   ├──► LS_DATA_AUG          (AI 자동 증강)
   ├──► LS_DATA_META         (추가 메타데이터)
   └──► LS_DATA_META_HSTRY  (메타 변경 이력)

LS_USER_ROLE (역할/권한)
   └──► LS_USER_MENU         (메뉴 계층 구조)
   └──► LS_PJT_USER_AUTHRT   (프로젝트별 권한 매핑)
       └──► LS_PJT_USER_AUTHRT_HSTRY (권한 변경 이력)

LS_NTC_BBS  (공지사항)
LS_ATCH_FILE (첨부파일)
LS_PJT_DATA_STATS (통계)
LS_PJT_JOB_STATS  (작업 통계)
```

---

## 5. 인덱스 정의

### 일반 인덱스

| 인덱스명 | 대상 컬럼 | 용도 |
|---------|----------|------|
| `IDX_PJT_SN` | PJT_SN | 프로젝트별 데이터 조회 |
| `IDX_DATA_RAW_SN` | DATA_RAW_SN | 원천 데이터 조회 |
| `IDX_ACTION_DT` | ACTION_DT | 일시 기준 이력 조회 |
| `IDX_DATA_LBL_SN` | DATA_LBL_SN | 라벨 이력 조회 |
| `IDX_DATA_SRC_SN` | DATA_SRC_SN | 원천 데이터 소스 조회 |
| `IDX_DATA_META_SN` | DATA_META_SN | 메타데이터 조회 |
| `IDX_PLFM_ID` | PLFM_ID | 플랫폼별 조회 |
| `IDX_LBL_ID` | LBL_ID | 라벨 ID 조회 |
| `IDX_UP_LBL_SN` | UP_LBL_SN | 상위 라벨(계층) 조회 |
| `IDX_META_ID` | META_ID | 메타 ID 조회 |
| `IDX_UP_MENU_SN` | UP_MENU_SN | 상위 메뉴(계층) 조회 |

### 고유(UNIQUE) 인덱스

| 인덱스명 | 설명 |
|---------|------|
| `UK_AUG_UNIQUE` | 데이터 증강 중복 방지 |
| `UK_DATA_SET_UNIQUE` | 데이터셋 고유 식별 |

---

## 6. 데이터 타입 사용 현황

| 데이터 타입 | 주요 용도 |
|------------|---------|
| `bigint` | 일련번호(PK), 외래키(FK) |
| `varchar(n)` | 코드, 이름, 경로 등 가변 문자열 |
| `varchar(1)` | Y/N 플래그 (비식별여부, AI생성여부 등) |
| `longtext` | 긴 텍스트 (프로젝트 내용, 이슈 내용 등) |
| `datetime` | 날짜+시간 (등록일시, 수정일시 등) |
| `decimal(10,7)` | GPS 좌표 (위도, 경도) |
| `float` | 부동소수점 (밝기 보정값 등) |
| `int` | 정수 (프레임 수, 너비, 높이, 목표 수량 등) |

---

## 7. 설계 특징 분석

### 7.1 이력(History) 중심 설계
모든 주요 테이블에 대응하는 이력 테이블(`_HSTRY`)을 별도로 운영하여 감사 추적(Audit Trail) 지원.

| 원본 테이블 | 이력 테이블 |
|-----------|-----------|
| LS_DATA_SRC | LS_DATA_SRC_HSTRY, LS_DATA_RAW_HSTRY |
| LS_DATA_META | LS_DATA_META_HSTRY |
| LS_DATA_LBL | LS_DATA_LBL_HSTRY |
| LS_PJT_USER_AUTHRT | LS_PJT_USER_AUTHRT_HSTRY |

### 7.2 코드(Code) 기반 표준화
상태, 유형, 분류 등 대부분의 분류 항목을 `_CD` 접미사의 코드 컬럼으로 관리하여 코드 테이블 기반 표준화 적용.

주요 코드 컬럼 예시:
- `SESN_CD` — 계절 코드 (봄/여름/가을/겨울)
- `WTHR_CD` — 날씨 코드 (맑음/흐림/비/눈 등)
- `HR_TYPE_CD` — 시간 유형 코드 (주간/야간/새벽 등)
- `EVNT_TYPE_CD` — 이벤트 유형 코드 (침수/화재/교통 등)
- `AUG_STTS_CD` — AI 증강 처리 상태 코드

### 7.3 개인정보 보호 / 규제 준수 컬럼
```
DE_IDNTF_YN   — 개인정보 포함 여부
PRVC_YN       — 비식별(가명처리) 여부
AI_CRT_YN     — AI 생성 여부 (합성 데이터 식별)
```

### 7.4 AI 증강 에러 처리
`LS_DATA_AUG` 테이블에 에러 코드(`ERROR_CD`), 에러 메시지(`ERROR_MSG`), 재시도 횟수(`RTRY_CNT`), 최종 시도 일시(`LAST_TRY_DT`) 컬럼 포함 → 자동 재처리 메커니즘 지원.

### 7.5 논리적 삭제 (Soft Delete)
물리 삭제 대신 `USE_YN` 컬럼으로 논리적 비활성화 처리.

### 7.6 계층형 구조 지원
- `LS_USER_MENU`: `UP_MENU_SN`으로 메뉴 트리 구조
- `LS_PJT_LBL`: `UP_LBL_SN`으로 라벨 계층 구조
- `LS_DATA_ISSUE`: `UP_DATA_ISSUE_SN`으로 이슈 계층 구조

---

## 8. 데이터 용량 추정 (연간)

| 테이블 | 연간 발생 건수 기준 | 예상 용량 |
|--------|-----------------|----------|
| LS_DATA_LBL_HSTRY | 360만 건/년 | **9,660MB** |
| LS_DATA_SRC | 120만 건/년 | **1,741MB** |
| LS_DATA_SRC_HSTRY | 120만 건/년 | **1,708MB** |
| LS_DATA_META | 20만 건/년 | 548MB |
| LS_PJT_DATA_STTS | 프로젝트 할당 기준 | **537MB** |
| LS_PJT_STG_PRC | - | 272MB |
| LS_PJT_DATA_STATS | 프로젝트 10개 동시 기준 | 49MB |
| LS_ATCH_FILE | - | 2.25MB |
| LS_USER_MENU | 고정 | 5KB |

---

## 요약

| 항목 | 내용 |
|------|------|
| **DBMS** | MySQL 8.0 이상 (InnoDB 엔진) |
| **테이블 수** | 29개 |
| **최대 용량 테이블** | LS_DATA_LBL_HSTRY (9,660MB/년) |
| **핵심 테이블** | LS_DATA_SRC (원천 데이터), LS_PJT (프로젝트) |
| **설계 패턴** | 이력 추적, 코드 기반 표준화, 논리적 삭제, 계층형 구조 |
| **특수 기능** | GPS 좌표 저장, AI 증강 에러 재시도, 개인정보 추적 |

---

*본 문서는 KLID-AI-데이터베이스 설계서 V1.4 (2025.12.08) 원본 HWP 파일에서 추출·정리한 내용입니다.*
