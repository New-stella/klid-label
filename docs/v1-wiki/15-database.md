# 15. 데이터베이스

> 출처: 데이터베이스설계서 V1.4, 통합설계서 §14 — **둘 다 PDF 원문 미포함.** `sources/KLID-AI-데이터베이스설계서_V1_4_분석.md`(진실원 아님, 보조) 기준
> 관련: [05 프로젝트](05-project-management.md) · [07 라벨링](07-labeling-tools.md) · [11 증강](11-augmentation-export.md)
>
> ⚠ **원문 미확인(2026-08-19)** — 이번에 확보한 1차 PDF 3종(화면정의서 v0.7·관리자매뉴얼 Rev.1.0·UI설계서 D2 V1.1)에는 **테이블·컬럼·DBMS 정보가 전혀 없다.** `LS_DATA_SRC`·`LS_PJT`·`MySQL`·`InnoDB`·"테이블" 등을 키워드로 3개 파일 전문을 검색했으나 0건이다(원문은 화면 스토리보드·조작 매뉴얼이라 DB 스키마를 다루지 않는다). 이 15장 전체는 데이터베이스설계서 V1.4의 원본 HWP를 직접 열람하기 전까지 **`sources/` 분석본(보조, 진실원 아님) 그대로**이며, 아래 표·컬럼·용량 수치를 1차 PDF 근거로 오인하지 말 것. 근거: `01-UIUX설계서.txt`·`02-관리자매뉴얼.txt`·`03-UI설계서V1.1.txt` 전문 검색(`grep -a`)

## 15.1 기본 정보

| 항목 | 내용 |
|------|------|
| DB 식별자 | `KLID-AI-DB-001` |
| DBMS | **MySQL 8.0 이상** |
| 스토리지 엔진 | InnoDB (`.ibd` 테이블별 개별 파일) |
| 전체 테이블 수 | **29개** |
| 연간 발생 기준 | 라벨 이력 360만 건/년, 원천 데이터 120만 건/년 |

## 15.2 전체 테이블 목록

| 테이블 ID | 테이블명 | 설명 | 예상 용량 |
|----------|---------|------|----------|
| TS-001 | `LS_ATCH_FILE` | 첨부파일 관리 | 2.25MB |
| TS-003 | `LS_DATA_ISSUE` | 학습 데이터 이슈 관리 | - |
| TS-005 | `LS_DATA_LBL_HSTRY` | 데이터 라벨링 이력 ★최대★ | **9,660MB** |
| TS-007 | `LS_DATA_META` | 학습 데이터 추가 메타데이터 | 548MB |
| TS-009 | `LS_DATA_META_HSTRY` | 메타데이터 변경 이력 | - |
| TS-011 | `LS_DATA_SRC` | 학습용 원천(원시) 데이터 ★핵심★ | **1,741MB** |
| TS-013 | `LS_DATA_SRC_HSTRY` | 원천 데이터 변경 이력 | 1,708MB |
| TS-015 | `LS_DATA_RAW_HSTRY` | 데이터 원시 이력 | - |
| TS-017 | `LS_NTC_BBS` | 공지사항 게시판 | - |
| TS-019 | `LS_PJT` | 학습 프로젝트 마스터 | - |
| TS-021 | `LS_PJT_DATA_MPNG` | 프로젝트-데이터 매핑 | - |
| TS-023 | `LS_PJT_DATA_STATS` | 프로젝트 데이터 통계 | 49MB |
| TS-025 | `LS_PJT_DATA_STTS` | 프로젝트 데이터 상태 추적 | **537MB** |
| TS-027 | `LS_PJT_DDLN` | 프로젝트 마감 이력 | - |
| TS-029 | `LS_PJT_JOB_STATS` | 프로젝트 작업 통계 | - |
| - | `LS_PJT_LBL` | 프로젝트 라벨 설정 | - |
| - | `LS_PJT_META` | 프로젝트 메타데이터 | - |
| - | `LS_PJT_STG_IGI` | 프로젝트 단계별 검수 설정 | - |
| - | `LS_PJT_STG_PRC` | 프로젝트 단계별 가공 처리 | **272MB** |
| - | `LS_PJT_USER_AUTHRT` | 프로젝트 사용자 권한 매핑 | - |
| - | `LS_PJT_USER_AUTHRT_HSTRY` | 프로젝트 사용자 권한 이력 | - |
| - | `LS_USER_MENU` | 사용자 메뉴 구조 (계층형) | 5KB |
| - | `LS_USER_ROLE` | 사용자 역할/권한 | - |
| - | `LS_DATA_AUG` | AI 데이터 자동 증강 | - |

> 표는 DB설계서 V1.4 기준 24행. 원본 "29개" 집계에는 이력/매핑 테이블이 추가 포함된다.

## 15.3 ERD 핵심 관계

```
LS_PJT (프로젝트, PJT_SN PK)
  ├── LS_PJT_DATA_MPNG  (데이터 매핑)
  ├── LS_PJT_LBL        (라벨 설정)
  ├── LS_PJT_META       (메타 설정)
  ├── LS_PJT_STG_IGI    (검수 단계 설정)
  ├── LS_PJT_STG_PRC    (가공 단계 처리)
  ├── LS_PJT_DATA_STTS  (데이터 상태)
  ├── LS_PJT_DATA_STATS (통계)
  ├── LS_PJT_DDLN       (마감 이력)
  └── LS_PJT_USER_AUTHRT (권한) → LS_PJT_USER_AUTHRT_HSTRY
          ↓
LS_DATA_SRC (원천 데이터 — 핵심, DATA_RAW_SN PK)
  ├── LS_DATA_SRC_HSTRY  (원천 변경 이력)
  ├── LS_DATA_RAW_HSTRY  (원시 이력)
  ├── LS_DATA_ISSUE      (이슈)
  ├── LS_DATA_LBL_HSTRY  (라벨링 이력 ★9,660MB★)
  ├── LS_DATA_AUG        (AI 증강)
  ├── LS_DATA_META       (추가 메타)
  └── LS_DATA_META_HSTRY (메타 변경 이력)

LS_USER_ROLE → LS_USER_MENU (메뉴 계층) / LS_PJT_USER_AUTHRT
LS_NTC_BBS · LS_ATCH_FILE · LS_PJT_JOB_STATS (독립/보조)
```

## 15.4 핵심 테이블 컬럼

### LS_DATA_SRC — 원천(원시) 데이터 ★핵심★
CCTV 클립 영상의 모든 메타데이터 관리.

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `DATA_RAW_SN` | bigint PK | 원시 일련번호 |
| `PJT_SN` | bigint FK | 프로젝트 |
| `RAW_FILE_NM` | varchar(1000) | 원시 파일명 |
| `RAW_DATA_TYPE_CD` / `FILE_FMT` | varchar | 데이터 유형 / 파일 포맷 |
| `SHT_DT` / `SHT_PRSN` / `SHT_LC` | datetime/varchar | 촬영 일자/인원/위치 |
| `VDO_CDC` | varchar(10) | 비디오 코덱 |
| `FRM_CNT` | int | 프레임 수 |
| `WDTH` / `HGT` / `RSLTN` | int/varchar | 너비/높이/해상도 |
| `LCLGV_CD` | varchar | 법정동 코드 |
| `SESN_CD` / `WTHR_CD` / `HR_TYPE_CD` | varchar | 계절/날씨/시간 유형 코드 |
| `EVNT_TYPE_CD` / `EVNT_NM` / `EVNT_END_DT` | varchar/datetime | 이벤트 유형/명/종료시각 |
| `DE_IDNTF_YN` / `PRVC_YN` / `AI_CRT_YN` | varchar(1) | 개인정보 포함 / 비식별 / AI 생성 |
| `USER_REG_YN` | varchar(1) | 사용자 등록 여부 |
| `WGS84_LAT` / `WGS84_LOT` | decimal(10,7) | WGS84 위/경도 |
| `CLCT_SRC` / `VMS_CCTV_ID` | varchar | 수집 경로 / VMS CCTV ID |
| `STP_CYCL` | varchar(500) | 보관 주기 |
| `DATA_STTS_CD` | varchar | 데이터 상태 코드 |
| `PROMPT_CN` | varchar | AI 생성 프롬프트 |

### LS_DATA_LBL_HSTRY — 라벨링 이력 ★최대 용량(9,660MB/년)★

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `DATA_LBL_HSTRY_SN` | bigint PK | 이력 일련번호 |
| `DATA_LBL_SN` / `PJT_SN` / `DATA_SRC_SN` / `LBL_SN` | bigint FK | 라벨/프로젝트/원천/라벨 |
| `ACTION_DT` / `ACTION_TYPE_CD` | datetime/varchar | 행위 일시 / 유형 |
| `TRCK_USE_YN` / `TRCK_FRM_YN` / `TRCK_ID` | varchar | 트랙 사용/프레임/ID |
| `ATRB` | varchar(4000) | 속성 정보 (JSON) |
| `SORT_SEQ` | int | 순서 |
| `REG_ID/DT` · `MDFCN_ID/DT` | | 등록/수정자·일시 |

### LS_PJT — 프로젝트 마스터

| 컬럼 | 타입 | 설명 |
|------|------|------|
| `PJT_SN` | bigint PK | 프로젝트 일련번호 |
| `PJT_ID` / `PJT_NM` / `PJT_CN` | varchar/longtext | ID / 명 / 설명 |
| `PJT_STTS_CD` / `EVNT_CD` / `GOAL_QTY` / `VER` | | 상태/이벤트/목표수량/버전 |
| `DDLN_YN` / `DDLN_DT` | varchar(1)/datetime | 마감 여부/일시 |
| `PJT_AUG_STTS_CD` / `PJT_AUG_START_DT` | | 증강 상태/시작시각 |
| `PJT_DSET_STTS_CD` / `PJT_DSET_START_DT` | | 데이터셋 상태/추출 시작 |

### LS_PJT_LBL — 라벨 설정

`PJT_LBL_SN`(PK) · `PJT_SN`(FK) · `LBL_ID` · `PRC_TYPE_CD`(바운딩박스/폴리곤/스켈레톤) · `UP_LBL_SN`(계층) · `LBL_NM` · `LBL_CLR`(HEX) · `LBL_CN` · `META_ID/NM/CN` · `BSC_RLT_CRD_X/Y`(스켈레톤 기본 좌표)

### LS_DATA_AUG — AI 증강
`DATA_AUG_SN`(PK) · `DATA_RAW_SN`(FK) · `PJT_AUG_OPT_CD`(증강 옵션) · `AUG_IMG_FILE_PATH` · `AUG_STTS_CD` · `ERROR_CD/MSG` · `RTRY_CNT` · `LAST_TRY_DT` · `BRGT_OFST`(밝기 보정)

### 기타 핵심
- **LS_PJT_STG_PRC**(가공): `STG_SN`(PK)·`TASK_TYPE_CD`·`PJT_DATA_STTS_CD`·`TRSF_FRM_CNT`·`FPS_TYPE_CD`
- **LS_PJT_STG_IGI**(검수): `STG_SN`(PK)·`IGI_SEQ`(1차/2차)·`CLCLN_UNTPRC`(정산 단가)
- **LS_PJT_DATA_STTS**(상태, 537MB): `PJT_DATA_STTS_SN`(PK)·`PJT_DATA_STTS_CD`·`TRSF_FRM_CNT`·`FPS_TYPE_CD`
- **LS_DATA_ISSUE**: `DATA_ISSUE_SN`(PK)·`UP_DATA_ISSUE_SN`(계층)·`ISSUE_TYPE_CD`·`RJCT_DTL_CD`(반려 상세)·`ISSUE_CN`
- **LS_USER_MENU**: `MENU_SN`(PK)·`UP_MENU_SN`(계층)·`MENU_URL`·`MENU_DEPTH`
- **LS_PJT_DDLN**: `DDLN_SN`(PK)·`VER`·`VER_CN`·`DDLN_DT`
- **LS_ATCH_FILE**: `ATCH_FILE_ID`(varchar36 PK)·`ORGNL_FILE_NM`·`FILE_SIZE`·`USE_YN`

## 15.5 인덱스

**일반**: `IDX_PJT_SN`, `IDX_DATA_RAW_SN`, `IDX_ACTION_DT`, `IDX_DATA_LBL_SN`, `IDX_DATA_SRC_SN`, `IDX_DATA_META_SN`, `IDX_PLFM_ID`, `IDX_LBL_ID`, `IDX_UP_LBL_SN`, `IDX_META_ID`, `IDX_UP_MENU_SN`
**고유(UNIQUE)**: `UK_AUG_UNIQUE`(증강 중복 방지), `UK_DATA_SET_UNIQUE`(데이터셋 고유 식별)

## 15.6 설계 패턴

| 패턴 | 설명 |
|------|------|
| 이력(HSTRY) 중심 | 주요 테이블마다 `_HSTRY` 대응 테이블로 감사 추적 |
| 코드(_CD) 기반 표준화 | 상태·유형을 코드 컬럼으로 관리 (SESN/WTHR/HR_TYPE/EVNT_TYPE/AUG_STTS) |
| 논리적 삭제 | 물리 삭제 대신 `USE_YN` 비활성화 |
| 계층형 구조 | `UP_LBL_SN`, `UP_MENU_SN`, `UP_DATA_ISSUE_SN` 트리 |
| GPS 좌표 | `decimal(10,7)` WGS84 위/경도 |
| AI 증강 재처리 | `RTRY_CNT`, `LAST_TRY_DT` 자동 재시도 |
| 개인정보 추적 | `DE_IDNTF_YN`, `PRVC_YN`, `AI_CRT_YN` 3종 플래그 |

## 15.7 용량 추정 (연간)

| 테이블 | 기준 | 용량 |
|--------|------|------|
| LS_DATA_LBL_HSTRY | 360만 건/년 | **9,660MB** |
| LS_DATA_SRC | 120만 건/년 | 1,741MB |
| LS_DATA_SRC_HSTRY | 120만 건/년 | 1,708MB |
| LS_DATA_META | 20만 건/년 | 548MB |
| LS_PJT_DATA_STTS | 프로젝트 할당 기준 | 537MB |
| LS_PJT_STG_PRC | - | 272MB |
| LS_PJT_DATA_STATS | 10개 동시 기준 | 49MB |

> **v2 참고**: v2는 **PostgreSQL** + `klid_at` 스키마. 저작도구 전용 **LS_*** 테이블은 자체 소유(자체 Flyway 관리, 현재 57개). 테이블명·컬럼이 v1과 다르며(예: v2는 `LS_DATA_RAW.RAW_SN`, `LS_LABEL_VERSION`, `LS_RAW_DATA_STATUS`, `LS_TASK_ALTMNT`), 데이터마트 적재용 `V_COMPLETED_*` View 4종을 제공한다. v1의 MySQL/InnoDB·프로젝트 중심 스키마와 직접 매핑되지 않으니 v2 마이그레이션(`backend/src/main/resources/db/migration/`) 기준으로 확인할 것.
>
> ⚠ **구 서술 폐기(2026-08-19 코드 실측)** — *"관제서버 **MNG_*** 9개는 `ddl-auto=validate` 참조"* 는 사실과 다르다. `MNG_*` 테이블은 **전량 DROP** 됐다(V167 등) — JPA 매핑·SQL 참조 각 **0건**이며 회귀 가드 `MngControlMasterTableRemovalTest`가 이 상태를 고정한다. 관제 적재는 **관제가 저작도구 소유 `LS_DATA_INGEST`에 직접 INSERT → 저작도구 주기 배치가 폴링**하는 방식으로 반전됐다(ADR-042). `QRTZ_*`(Quartz JobStore, 11개)는 이 정정과 무관하게 계속 생존한다 — 둘을 묶어 "공유 테이블이 없다"고 읽지 말 것. 근거: `MngControlMasterTableRemovalTest` · `reports/wiki-align-20260819/CROSSAXIS.md` §B.
