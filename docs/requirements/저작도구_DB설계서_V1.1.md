# 학습데이터 저작도구 DB 설계서

> **사업명** : AI 기반 지방정부 CCTV 관제지원시스템 구축(2차)  
> **수행기관** : 쿠도커뮤니케이션 | **담당** : 박찬기 | **Ver** : 1.1 | **작성일** : 2026-04-23  
> **관련 문서** : 저작도구_요구사항정의서_V1.1, 저작도구_서비스기능_아키텍처_구성도_V1.1

---

## 개정 이력

| 버전 | 작성일 | 변경 내용 |
|------|--------|-----------|
| V1.0 | 2026-04-21 | 최초 작성 (TB_ 독립 스키마 기준) |
| V1.1 | 2026-04-23 | 전면 재작성 — 기존 `klid_system` DB 공유 구조 확정. 재사용·컬럼 추가·신규 추가·미사용으로 재분류. 포털 신규 테이블(`LS_PORTAL_USER_VIDEO`, `LS_PORTAL_USER_STATS`) 추가 |
| V1.2 | 2026-04-28 | mock 필수 항목 지원 컬럼 추가 (`LBL_INTGRT_PCT`, `GITEA_CMT_HASH`) + 검수 코드값 보강 (`DATA_STTS_CD` 4종) + mock 필드 ↔ DB 매핑 가이드 추가 |
| V1.2.1 | 2026-04-28 | SFR 원문 정합 재검증: LS_DATA_RAW.FRM_CNT, THMBNL_FILE_PATH 컬럼 제거 (SFR-08 명시 없음) |
| V1.2.2 | 2026-04-28 | LS_SYSTEM_CONFIG 시스템 설정 영속화 테이블 추가 (FFmpeg / 배치 설정) |
| V1.2.3 | 2026-04-28 | LS_SYSTEM_CONFIG 시드에서 FFMPEG_RESOLUTION 제거 (원본 해상도 보존 정책) |

---

## 목차

1. [DB 연동 구조](#1-db-연동-구조)
2. [테이블 전체 분류](#2-테이블-전체-분류)
3. [재사용 테이블](#3-재사용-테이블)
4. [기존 테이블 컬럼 추가](#4-기존-테이블-컬럼-추가)
   - 4.1 `LS_DATA_LBL` — 오토라벨링 정보 및 증강 이미지 라벨 연결
   - 4.2 `LS_DATA_AUG` — 라벨 무결성 점수 (신규)
   - 4.3 `LS_DATA_LBL_HSTRY` — Gitea 커밋 해시 (신규)
   - 4.4 `CM_CODE` — 검수 워크플로우 코드값 보강 (신규)
5. [신규 추가 테이블](#5-신규-추가-테이블)
   - 5.1 포털 신규 테이블 (`LS_PORTAL_USER_VIDEO`, `LS_PORTAL_USER_STATS`)
   - 5.2 `LS_SYSTEM_CONFIG` — 시스템 설정 키-값 영속화
6. [미사용 테이블](#6-미사용-테이블)
7. [mock 필드 ↔ DB 매핑 가이드](#7-mock-필드--db-매핑-가이드)

---

## 1. DB 연동 구조

### 1.1 기본 방침

저작도구는 전용 독립 DB 인스턴스를 갖지 않는다. 기존 `klid_system` DB를 공유하며, 저작도구에서 필요한 기능은 기존 테이블을 재사용하거나 컬럼을 추가하는 방식으로 구현한다. 신규 테이블은 기존 구조로 표현이 불가한 기능에 한해 최소한으로 추가한다.

### 1.2 채널별 DB 연동

| 채널 | 연동 DB | 비고 |
|------|---------|------|
| 내부 채널 (관제서버 연동) | `klid_system` (관제서버 DB) | 저작도구 기능 전체 |
| 외부 채널 (포털 연동) | 포털 DB | 포털 사용자 업로드·라벨링 범위 (포털팀 협의 필요) |

### 1.3 DBMS 환경

| 항목 | 내용 |
|------|------|
| DBMS | MariaDB 10.11.13 (LTS) |
| DB | `klid_system` @ 192.168.102.101:13307 |
| 문자셋 | `utf8mb4` |
| 콜레이션 | `utf8mb4_unicode_ci` |
| 스토리지 엔진 | InnoDB |
| DB Proxy | MaxScale 24.02.5 (Master-Slave Read/Write Splitting) |
| 배치 스케줄러 | Quartz (`QRTZ_*` 11개 테이블 — 기존 운영 중) |

---

## 2. 테이블 전체 분류

| 구분 | 테이블 수 | 설명 |
|------|:---------:|------|
| **재사용** | 31개 | 기존 테이블을 그대로 활용 |
| **컬럼 추가** | 3개 | `LS_DATA_LBL` (3컬럼), `LS_DATA_AUG` (1컬럼 신규), `LS_DATA_LBL_HSTRY` (1컬럼 신규) |
| **신규 추가** | 3개 | `LS_PORTAL_USER_VIDEO` (필수), `LS_PORTAL_USER_STATS` (옵션), `LS_SYSTEM_CONFIG` (시스템 설정) |
| **미사용** | 해당없음 | 저작도구 범위 외 테이블 (관제서버·외부연계 등) |

---

## 3. 재사용 테이블

기존 `klid_system` DB의 테이블을 **변경 없이** 저작도구에서 직접 사용하는 테이블 목록이다.

### 3.1 사용자·인증

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `MNG_ACCT_USER` | 사용자 | 내부 사용자(ADMIN/WORKER/REVIEWER) 정보 조회 |
| `MNG_ACCT_AUTHRT` | 권한 | 역할 코드 및 권한 정의 조회 |
| `MNG_ACCT_USER_AUTHRT` | 사용자-권한 매핑 | RBAC 권한 확인 |
| `MNG_ACCT_GPKI_CRTF` | GPKI 인증서 | 내부 사용자 인증서 조회 (세션 인계 검증) |
| `MNG_ACCT_LGN_SESSION` | 로그인 세션 | 세션 유효성 확인 |
| `MNG_ACCT_MENU_AUTHRT` | 권한별 메뉴 | 저작도구 메뉴 접근 권한 제어 |

### 3.2 공통 코드

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `CM_CODE` | 공통 코드 | 상태코드·이벤트코드·날씨코드 등 전체 공통 코드 조회 |
| `CM_GROUP_CODE` | 그룹 코드 | 코드 그룹 분류 조회 |

### 3.3 원시 데이터·프레임

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `LS_DATA_RAW` | 원시 영상 | 관제서버로부터 수신한 라벨링 대상 영상 목록. `PRVC_YN`·`DE_IDNTF_YN`·`EVNT_TYPE_CD`·`SESN_CD`·`WTHR_CD` 등 기존 컬럼 활용 |
| `LS_DATA_SRC` | 프레임 (원천 데이터) | FFmpeg 추출 프레임. `FRM_NO`·`SRC_FILE_PATH`(원본 이미지)·`SRC_BKUP_FILE_PATH`(비식별 이미지) 활용 |
| `LS_DATA_RAW_HSTRY` | 원시 영상 이력 | 원시 영상 변경 이력 추적 |
| `LS_DATA_SRC_HSTRY` | 프레임 이력 | 프레임 변경 이력 추적 |

> **참고**: `LS_DATA_SRC.SRC_BKUP_FILE_PATH`를 비식별 처리 이미지 경로로 활용. `DE_IDNTF_YN = 'Y'`인 경우 비식별 처리 완료를 의미.

### 3.4 라벨링·어노테이션

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `LS_DATA_LBL` | 데이터 라벨 | 어노테이션 저장. `POINT`(좌표), `ATRB`(속성), `TRCK_ID`(SAM2 트랙) 활용 |
| `LS_DATA_LBL_HSTRY` | 라벨 이력 | 라벨 수정 이력. Gitea 자동 커밋 시 커밋 해시 참조 (`GITEA_CMT_HASH` 컬럼 — §4 참조) |
| `LS_DATA_META` | 메타데이터 | 프레임 단위 메타정보 저장 |
| `LS_DATA_META_HSTRY` | 메타데이터 이력 | 메타 수정 이력 추적 |
| `LS_DATA_ISSUE` | 데이터 이슈 | 검수 반려 사유·이슈 항목 관리 (`UP_DATA_ISSUE_SN`으로 계층 구조 지원) |

### 3.5 프로젝트·작업

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `LS_PJT` | 프로젝트 | 라벨링 작업 단위. 배치 처리 상태 관리 (컬럼 추가 필요 → 4장) |
| `LS_PJT_DATA_STTS` | 프로젝트 데이터 상태 | 영상별 작업 진행 상태 관리. `DATA_STTS_CD`·`STP_CYCL`(단계차수)·`IGI_CYCL`(검수차수) 활용 |
| `LS_PJT_USER_AUTHRT` | 프로젝트 사용자 권한 | 검수자의 작업자 배정. `TASK_TYPE_CD`로 라벨러/검수자 구분 |
| `LS_PJT_USER_AUTHRT_HSTRY` | 배정 이력 | 작업자 변경 이력 추적 |
| `LS_PJT_LBL` | 프로젝트 라벨 | 라벨 프리셋 정의. `LBL_NM`·`LBL_COLR`·`ATRB_INFO` 활용 |
| `LS_PJT_META` | 프로젝트 메타 | 프로젝트별 메타 항목 정의 |
| `LS_PJT_DDLN` | 프로젝트 마감·버전 | 데이터셋 버전 관리. `ANONY_INCL_YN`·`PSDO_INCL_YN`·`PRVC_INCL_YN` 개인정보 분류 포함 |
| `LS_PJT_DATA_MPNG` | 프로젝트 데이터 매핑 | 프로젝트-원시데이터 연결 |

### 3.6 통계·대시보드

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `LS_PJT_DATA_STATS` | 프로젝트 데이터 통계 | 월별/일별 영상·프레임 수 집계 |
| `LS_PJT_JOB_STATS` | 작업자 통계 | 작업자별 일별 작업량 집계 |

### 3.7 데이터 증강

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `LS_DATA_AUG` | 데이터 증강 | 증강 요청·결과 관리. `PJT_AUG_OPT_CD`(증강 유형), `AUG_PROC_STTS_CD`(처리 상태), `AUG_FILE_PATH`(결과 경로) 활용 |
| `LS_DATA_SET` | 데이터셋 | 학습데이터셋 추출 관리 |

### 3.8 데이터마트·배포

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `MNG_DATA_SET` | 데이터셋 (마트) | 데이터마트 등록·버전 관리. `ANONY_INCL_YN`·`PSDO_INCL_YN`·`PRVC_INCL_YN` 개인정보 유형 분류 포함 |
| `MNG_DATA_SET_DSTRB` | 데이터셋 배포 | 다운로드 이력 관리 |
| `MNG_DATA_SET_DSTRB_DTL` | 배포 상세 | 배포된 데이터셋 항목 |
| `MNG_DATA_SET_ATCH_FILE` | 데이터셋 첨부파일 | 데이터셋 파일 관리 (`PRVC_TYPE_CD` 이미 존재) |

### 3.9 배치·스케줄러

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `MNG_CLIP_SCHEDULE_QUE` | 배치 작업 큐 | 라벨링 배치 파이프라인 큐. `JOB_TYPE = 'LABELING_BATCH'` 값 추가하여 활용 |
| `QRTZ_*` (11개) | Quartz 스케줄러 | 배치 Job 스케줄링·실행 관리. 기존 운영 중인 인프라 그대로 활용 |

### 3.10 영상·CCTV 메타 조회 (저작 대상 영상 목록)

저작 대상 영상 목록 화면에서 영상의 CCTV 정보·이벤트 유형명·지자체명을 표시하기 위해 필요한 테이블이다. `LS_DATA_RAW`에는 코드값(`EVNT_TYPE_CD`, `LCLGV_CD`, `VMS_CCTV_ID`)만 저장되어 있어 아래 마스터 테이블과 JOIN하여 명칭을 조회한다.

| 테이블 | 한글명 | 저작도구 활용 내용 |
|--------|--------|------------------|
| `MNG_CLIP_MASTER` | 클립 영상 마스터 | 관제서버가 수집한 클립 원본 정보. `JOB_DMND_YN`(작업요청여부)으로 라벨링 대상 판별 여부 확인. `VMS_CCTV_ID`로 CCTV 연결 |
| `MNG_RESOURCE_CCTV` | CCTV 관리 | `VMS_CCTV_ID`로 JOIN → CCTV명·설치위치(`SHT_ADDR`)·위경도(`WGS84_LAT/LOT`)·기관명(`OG_NM`)·카메라 해상도 등 상세 메타 조회 |
| `MNG_EX_EVNT_TYPE` | 이벤트 유형 | `EVNT_TYPE_CD`로 JOIN → 이벤트 유형명(`CLCT_EVNT_NM`) 표시 |
| `MNG_EX_EVNT_TYPE_MAP` | 이벤트 유형 매핑 | 이벤트 대분류·중분류·소분류 계층 구조 및 이벤트명(`EVNT_NM`) 표시 |
| `MNG_EX_LOCAL_GOV` | 지방자치단체 | `LCLGV_CD`로 JOIN → 시도명(`SIDO_NM`)·시군구명(`SGG_NM`) 표시 |

---

## 4. 기존 테이블 컬럼 추가

### 4.1 `LS_DATA_LBL` — 오토라벨링 정보 및 증강 이미지 라벨 연결

| 추가 컬럼 | 타입 | NULL | 기본값 | 설명 |
|-----------|------|:----:|--------|------|
| `AUTO_LBL_YN` | varchar(1) | ✗ | `'N'` | 오토라벨링(YOLO/SAM2) 자동 생성 여부 |
| `CONF_SCORE` | decimal(5,4) | ✓ | NULL | 오토라벨링·VLM 객체 검증 신뢰도 (0.0000 ~ 1.0000) |
| `DATA_AUG_SN` | bigint(20) | ✓ | NULL | 증강 데이터 일련번호 (FK → `LS_DATA_AUG`). NULL이면 원본 이미지 라벨, 값이 있으면 해당 증강 이미지 라벨 |

**추가 이유**

- `AUTO_LBL_YN` / `CONF_SCORE`: YOLO/SAM2 자동 생성 라벨과 작업자 수동 라벨을 구분하고, VLM 객체 검증 신뢰도를 저장하기 위함.
- `DATA_AUG_SN`: 관제서버에서 원본 라벨링 정보를 조회할 때 원본 이미지 라벨과 증강 이미지 라벨을 함께 확인할 수 있어야 한다. `DATA_SRC_SN`으로 프레임을 특정하고 `DATA_AUG_SN` NULL 여부로 원본/증강을 구분한다.

```sql
-- 원본 + 증강 라벨 전체 조회
SELECT * FROM LS_DATA_LBL WHERE DATA_SRC_SN = ? AND PJT_SN = ?

-- 원본 라벨만
WHERE DATA_SRC_SN = ? AND DATA_AUG_SN IS NULL

-- 증강 라벨만 (증강 유형별 조회 가능)
WHERE DATA_SRC_SN = ? AND DATA_AUG_SN IS NOT NULL
```

> **변경 영향**: 본 컬럼 추가는 관제서버팀과 사전 협의가 필요하다. Flyway 마이그레이션 작성 시 `klid_system` 공유 테이블이므로 ALTER 시점 조정 필요.

---

### 4.2 `LS_DATA_AUG` — 라벨 무결성 점수 (신규) ★

SFR-07 "라벨 무결성 유지" 요구사항에 따라 증강 전후 라벨 보존 검증 결과를 저장한다.

| 추가 컬럼 | 타입 | NULL | 기본값 | 설명 |
|-----------|------|:----:|--------|------|
| `LBL_INTGRT_PCT` | DECIMAL(5,2) | ✓ | NULL | 라벨 무결성 점수 (0.00 ~ 100.00). 증강 후 라벨 좌표·카테고리 보존율 |

**추가 이유**

- SFR-07 ② "라벨 무결성 유지" 명시 요구사항. 증강 전후 라벨 좌표/카테고리를 비교하여 보존율을 계산(별도 검증 모듈)하고 본 컬럼에 저장한다.
- 검증 로직: 증강 전 라벨 집합 ↔ 증강 후 라벨 집합을 비교 → 보존율(%) 산출 → `LBL_INTGRT_PCT` UPDATE.

```sql
ALTER TABLE LS_DATA_AUG
    ADD COLUMN LBL_INTGRT_PCT DECIMAL(5,2) NULL COMMENT '라벨 무결성 점수 (0.00~100.00)';
```

> **변경 영향**: 본 컬럼 추가는 관제서버팀과 사전 협의가 필요하다. Flyway 마이그레이션 작성 시 `klid_system` 공유 테이블이므로 ALTER 시점 조정 필요.

---

### 4.3 `LS_DATA_LBL_HSTRY` — Gitea 커밋 해시 (신규) ★

§3.4에서 "(GITEA_CMT_HASH 컬럼 — §4 참조)"로 안내한 컬럼의 정식 정의이다. V1.1 기준 §4에 정의가 누락되어 있던 모순을 V1.2에서 해소한다. (§4.3으로 재번호)

| 추가 컬럼 | 타입 | NULL | 기본값 | 설명 |
|-----------|------|:----:|--------|------|
| `GITEA_CMT_HASH` | VARCHAR(40) | ✓ | NULL | Gitea 자동 커밋의 SHA-1 해시 (40자). `GiteaClient.commit` 응답 해시 저장 |

**추가 이유**

- SFR-09 라벨 버전관리. 라벨 저장 이벤트 발생 시 `GiteaClient.commit` 호출 후 반환된 커밋 해시를 `LS_DATA_LBL_HSTRY`에 기록한다.
- diff 비교 및 롤백 기능 구현 시 해당 해시로 Gitea API를 직접 호출하여 이전 버전 복원.

**인덱스**

| 인덱스명 | 컬럼 | 목적 |
|----------|------|------|
| `IDX_LS_DATA_LBL_HSTRY_GITEA` | `GITEA_CMT_HASH` | 커밋 해시 역조회 (롤백·diff 요청 시) |

```sql
ALTER TABLE LS_DATA_LBL_HSTRY
    ADD COLUMN GITEA_CMT_HASH VARCHAR(40) NULL COMMENT 'Gitea 커밋 SHA-1 해시 (40자)';

CREATE INDEX IDX_LS_DATA_LBL_HSTRY_GITEA ON LS_DATA_LBL_HSTRY (GITEA_CMT_HASH);
```

> **변경 영향**: 본 컬럼 추가는 관제서버팀과 사전 협의가 필요하다. Flyway 마이그레이션 작성 시 `klid_system` 공유 테이블이므로 ALTER 시점 조정 필요.

---

### 4.4 `CM_CODE` — 검수 워크플로우 코드값 보강 (신규) ★

DDL 변경 없이 데이터 INSERT만으로 적용 가능한 변경이다. SFR-09 검수 워크플로우 4단계를 커버한다. 코드값 정의 누락 시 mock의 `status: IN_REVIEW` 등 값 매핑이 불가하다.

**`CM_GROUP_CODE`: `DATA_STTS_CD` (데이터 상태 코드)**

> 그룹 코드(`DATA_STTS_CD`)가 이미 존재하면 코드값 INSERT만 수행한다. 미존재 시 그룹 코드도 함께 INSERT.

| GROUP_CODE | CODE | NAME | 설명 | 사용처 |
|------------|------|------|------|--------|
| `DATA_STTS_CD` | `PENDING` | 검수 대기 | 라벨링 완료 후 검수 대기 상태 | `ReviewDto.status` |
| `DATA_STTS_CD` | `IN_REVIEW` | 검수중 | REVIEWER가 검수 진행 중 | `ReviewDto.status` |
| `DATA_STTS_CD` | `APPROVED` | 검수 승인 | REVIEWER 승인 완료 | `ReviewDto.status` |
| `DATA_STTS_CD` | `REJECTED` | 검수 반려 | REVIEWER 반려 (`LS_DATA_ISSUE` 연계) | `ReviewDto.status` |

**참조 흐름**: 라벨링 완료 → `PENDING` → REVIEWER 검수 시작 → `IN_REVIEW` → 승인: `APPROVED` / 반려: `REJECTED` + `LS_DATA_ISSUE` INSERT

```sql
-- DATA_STTS_CD 그룹이 없으면 먼저 INSERT (기존재 시 생략)
-- INSERT INTO CM_GROUP_CODE (GROUP_CODE, GROUP_NM, ...) VALUES ('DATA_STTS_CD', '데이터 상태 코드', ...);

INSERT INTO CM_CODE (GROUP_CODE, CODE, CODE_NM, CODE_DC, USE_YN, SORT_ORDR) VALUES
    ('DATA_STTS_CD', 'PENDING',   '검수 대기', '라벨링 완료 후 검수 대기', 'Y', 10),
    ('DATA_STTS_CD', 'IN_REVIEW', '검수중',   'REVIEWER가 검수 진행 중',  'Y', 20),
    ('DATA_STTS_CD', 'APPROVED',  '검수 승인', 'REVIEWER 승인 완료',       'Y', 30),
    ('DATA_STTS_CD', 'REJECTED',  '검수 반려', 'REVIEWER 반려',             'Y', 40);
```

> **변경 영향**: 본 코드값 보강은 관제서버팀과 사전 협의가 필요하다. Flyway 마이그레이션 작성 시 `klid_system` 공유 테이블이므로 INSERT 시점 조정 필요.

---

## 5. 신규 추가 테이블

기존 `klid_system` DB 구조만으로 대응이 어려운 포털 영역에 한해 신규 테이블을 추가한다.  
포털 도메인은 V1.1에서 별도 채널로 명시적으로 허용된 신규 추가 범위이다.

### 5.1 포털 신규 테이블

> **참고**: 포털 영역은 저작도구 V1.1에서 별도로 추가되는 도메인으로, `klid_system` DB의 신규 테이블로 관리한다. 포털 서버 측 사용자 마스터(`MNG_PORTAL_USER` 등 가칭)와 `PORTAL_USER_NO`로 연결되며, 통합은 V1.2 이후 확정 예정.

#### 5.1.1 `LS_PORTAL_USER_VIDEO` — 포털 사용자 업로드 영상

포털 사용자가 업로드한 영상 메타와 다운로드 만료일을 관리한다.  
저작도구 본 영상(`LS_DATA_RAW`)과 분리 — 포털은 체험·테스트 목적이라 정책상 별개 테이블로 운영한다.

| 컬럼명 | 타입 | NOT NULL | 기본값 | 설명 |
|--------|------|:--------:|--------|------|
| `PORTAL_USER_VIDEO_NO` | BIGINT | ✓ | AUTO_INCREMENT | 포털 영상 일련번호 (PK) |
| `PORTAL_USER_NO` | BIGINT | ✓ | — | 포털 사용자 번호 (FK → 포털 사용자 마스터, 포털 DB) |
| `VIDEO_NM` | VARCHAR(255) | ✓ | — | 영상명 (사용자 업로드 파일명) |
| `FILE_PATH` | VARCHAR(500) | ✓ | — | 저장 경로 (포털 업로드 NAS 경로) |
| `THUMBNAIL_PATH` | VARCHAR(500) | ✗ | NULL | 썸네일 경로 (FFmpeg 첫 프레임 추출 결과) |
| `DOWNLOAD_DDLN_DT` | DATETIME | ✓ | — | 다운로드 만료일. 업로드일 + 30일(기본). 영상별 개별 부여 |
| `EVNT_TYPE_CD` | VARCHAR(20) | ✗ | NULL | 이벤트 유형 코드 (`CM_CODE` 참조, 선택) |
| `WTHR_CD` | VARCHAR(20) | ✗ | NULL | 날씨 코드 (`CM_CODE` 참조, 선택) |
| `SESN_CD` | VARCHAR(20) | ✗ | NULL | 계절 코드 (`CM_CODE` 참조, 선택) |
| `CRTN_DT` | DATETIME | ✓ | CURRENT_TIMESTAMP | 생성일 (업로드일) |
| `MDFY_DT` | DATETIME | ✗ | NULL | 수정일 (ON UPDATE CURRENT_TIMESTAMP) |

**인덱스**

| 인덱스명 | 컬럼 | 목적 |
|----------|------|------|
| `PK_PORTAL_USER_VIDEO` | `PORTAL_USER_VIDEO_NO` | 기본키 |
| `IDX_PORTAL_USER_VIDEO_USER` | `PORTAL_USER_NO` | 사용자별 영상 목록 조회 |
| `IDX_PORTAL_USER_VIDEO_DDLN` | `DOWNLOAD_DDLN_DT` | 만료 임박 알림 배치 쿼리 |

**DDL (참고용)**

```sql
CREATE TABLE LS_PORTAL_USER_VIDEO (
    PORTAL_USER_VIDEO_NO  BIGINT        NOT NULL AUTO_INCREMENT COMMENT '포털 영상 일련번호',
    PORTAL_USER_NO        BIGINT        NOT NULL                COMMENT '포털 사용자 번호',
    VIDEO_NM              VARCHAR(255)  NOT NULL                COMMENT '영상명',
    FILE_PATH             VARCHAR(500)  NOT NULL                COMMENT '저장 경로',
    THUMBNAIL_PATH        VARCHAR(500)  NULL                    COMMENT '썸네일 경로',
    DOWNLOAD_DDLN_DT      DATETIME      NOT NULL                COMMENT '다운로드 만료일 (업로드일+30일 기본)',
    EVNT_TYPE_CD          VARCHAR(20)   NULL                    COMMENT '이벤트 유형 코드',
    WTHR_CD               VARCHAR(20)   NULL                    COMMENT '날씨 코드',
    SESN_CD               VARCHAR(20)   NULL                    COMMENT '계절 코드',
    CRTN_DT               DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일',
    MDFY_DT               DATETIME      NULL     ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일',
    PRIMARY KEY (PORTAL_USER_VIDEO_NO),
    INDEX IDX_PORTAL_USER_VIDEO_USER (PORTAL_USER_NO),
    INDEX IDX_PORTAL_USER_VIDEO_DDLN (DOWNLOAD_DDLN_DT)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='포털 사용자 업로드 영상';
```

> **Flyway 적용 필수**: `klid_system` 신규 테이블이므로 관제서버팀 선승인 후 마이그레이션 스크립트 작성.

---

#### 5.1.2 `LS_PORTAL_USER_STATS` — 포털 사용자 통계 (옵션)

`uploadCount` / `labeledCount`는 `LS_PORTAL_USER_VIDEO`에서 GROUP BY 실시간 집계로 충분하다.  
사용자 수 1만 명 초과 시 성능 이슈가 발생할 경우 아래 캐시 테이블 도입을 검토한다.

| 컬럼명 | 타입 | NOT NULL | 기본값 | 설명 |
|--------|------|:--------:|--------|------|
| `PORTAL_USER_NO` | BIGINT | ✓ | — | 포털 사용자 번호 (PK) |
| `UPLOAD_CNT` | INT | ✓ | 0 | 업로드 건수 (캐시) |
| `LABELED_CNT` | INT | ✓ | 0 | 라벨링 완료 건수 (캐시) |
| `LAST_AGGR_DT` | DATETIME | ✗ | NULL | 마지막 집계 시각 |

> **권장**: 초기 구현은 `LS_PORTAL_USER_VIDEO` GROUP BY 실시간 집계로 진행. 캐시 테이블은 사용자 수 및 쿼리 부하 모니터링 후 V1.2에서 도입 여부 결정.

---

> **mock 정합성**: `mock/src/api/types.ts`의 `VideoDto.downloadDeadline`, `PortalUserDto.uploadCount`, `PortalUserDto.labeledCount` 필드와 매핑됨.

---

### 5.2 `LS_SYSTEM_CONFIG` — 시스템 설정 키-값 영속화

운영자가 동적으로 조정하는 시스템 설정을 `application.yml` 외부에서 영속화한다.  
FFmpeg 스레드 수·출력 FPS·해상도, 배치 처리 주기·동시 처리 수 등 운영 중 변경이 필요한 항목을 Key-Value 패턴으로 단일 테이블에 관리한다.

| 컬럼 | 타입 | NULL | 기본값 | 설명 | 비고 |
|------|------|:----:|--------|------|------|
| `CONFIG_KEY` | VARCHAR(50) | NO | — | 설정 키 (예: `FFMPEG_THREADS`) | PK |
| `CONFIG_VALUE` | VARCHAR(500) | NO | — | 설정 값 (문자열 직렬화) | NUMBER/STRING/JSON 모두 문자열 저장 |
| `CONFIG_TYPE` | VARCHAR(20) | NO | `'STRING'` | 값 타입 (`NUMBER`/`STRING`/`JSON`/`BOOLEAN`) | 백엔드 파싱 분기 |
| `DESCRIPTION` | VARCHAR(255) | YES | NULL | 설정 설명 | 운영자 안내용 |
| `UPDATED_BY` | BIGINT | YES | NULL | 마지막 수정자 `USER_NO` | `MNG_ACCT_USER` FK |
| `UPDATED_AT` | DATETIME | NO | `CURRENT_TIMESTAMP` | 마지막 수정 시각 | ON UPDATE 자동 갱신 |

**인덱스**: PK(`CONFIG_KEY`)만 — 키 단일 조회 외 쿼리 없음.

**초기 시드 데이터**

| CONFIG_KEY | CONFIG_VALUE | CONFIG_TYPE | DESCRIPTION |
|------------|--------------|-------------|-------------|
| `FFMPEG_THREADS` | `4` | `NUMBER` | FFmpeg 프레임 추출 동시 스레드 수 (1~16) |
| `FFMPEG_OUTPUT_FPS` | `30` | `NUMBER` | 추출 프레임 FPS (1~60) |
| `BATCH_INTERVAL_SEC` | `60` | `NUMBER` | 배치 처리 주기 초 (Quartz interval) |
| `BATCH_CONCURRENCY` | `1` | `NUMBER` | 배치 동시 처리 수 (1=직렬, 2+=병렬) |

**DDL (참고용)**

```sql
CREATE TABLE LS_SYSTEM_CONFIG (
  CONFIG_KEY      VARCHAR(50)  NOT NULL                                    COMMENT '설정 키',
  CONFIG_VALUE    VARCHAR(500) NOT NULL                                    COMMENT '설정 값 (문자열 직렬화)',
  CONFIG_TYPE     VARCHAR(20)  NOT NULL DEFAULT 'STRING'                   COMMENT '값 타입 (NUMBER/STRING/JSON/BOOLEAN)',
  DESCRIPTION     VARCHAR(255)                                             COMMENT '설정 설명 (운영자 안내용)',
  UPDATED_BY      BIGINT                                                   COMMENT '마지막 수정자 USER_NO',
  UPDATED_AT      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                               ON UPDATE CURRENT_TIMESTAMP                 COMMENT '마지막 수정 시각',
  PRIMARY KEY (CONFIG_KEY)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='시스템 설정 키-값 영속화';

INSERT INTO LS_SYSTEM_CONFIG (CONFIG_KEY, CONFIG_VALUE, CONFIG_TYPE, DESCRIPTION) VALUES
  ('FFMPEG_THREADS',     '4',  'NUMBER', 'FFmpeg 프레임 추출 동시 스레드 수 (1~16)'),
  ('FFMPEG_OUTPUT_FPS',  '30', 'NUMBER', '추출 프레임 FPS (1~60)'),
  ('BATCH_INTERVAL_SEC', '60', 'NUMBER', '배치 처리 주기(초) (Quartz interval)'),
  ('BATCH_CONCURRENCY',  '1',  'NUMBER', '배치 동시 처리 수 (1=직렬, 2+=병렬)');
```

> **해상도 정책**: FFmpeg 출력 해상도는 시스템 일률 설정 대신 **원본 해상도 보존**을 원칙으로 한다. 학습데이터 좌표 정밀도 보장 + CCTV별 해상도 차이 대응. CCTV별 해상도는 `MNG_RESOURCE_CCTV.RESOLUTION` 마스터를 참조한다 (별도 컬럼 추가 없음).

> **변경 영향**: 본 테이블은 신규 추가로 기존 데이터 영향 없음. `application.yml`의 `ffmpeg.*`, `batch.*` 설정과 중복될 경우 DB 우선 로드 정책 권장 (Spring `@ConfigurationProperties` + DB fallback).

> **mock 정합성**: `mock/src/api/types.ts`의 `SystemSettings.ffmpegConfig`, `batchConfig` 필드와 매핑됨. `externalSystems`는 mock 시뮬레이션이며 실제는 actuator 호출.

---

## 6. 미사용 테이블

저작도구 기능과 관련 없는 기존 테이블 그룹이다. 저작도구에서 해당 테이블을 읽거나 쓰지 않는다.

| 테이블 그룹 | 설명 | 미사용 이유 |
|-------------|------|-------------|
| `MNG_BSINS_*` (4개) | 비즈니스 지원 게시판 | 고객사 Q&A 게시판 — 저작도구 범위 외 |
| `MNG_CLIP_EVNT_*` (5개), `MNG_CLIP_THUMBNAIL` | 클립 이벤트 통계·썸네일 | 관제서버 이벤트 통계 영역 — 저작도구 미사용 |
| `MNG_EX_CLCT`, `MNG_EX_SEND_SCHEDULE`, `MNG_EX_SYSTEM_LOG` | 수급 서버·전송 스케줄·시스템 로그 | 관제서버 외부연계 운영 영역 |
| `MNG_GOV_*` (2개) | 기관 관리 | 기관코드 관리 — 관제서버 영역 |
| `MNG_INGEST_*` (3개) | 수집 관리 | 영상 수집·업로드 세션 — 관제서버 영역 |
| `MNG_RESOURCE_*` (`MNG_RESOURCE_CCTV` 제외, 9개) | Pole·스토리지·통계 등 | CCTV 외 리소스 관리 — 관제서버 영역 |
| `MNG_SET_*` (3개) | 설정·이력 | 사용자 삭제 이력·개인정보 접근 이력 — 관제서버 영역 |
| `MNG_MENU` | 메뉴 관리 | 관제서버 메뉴 관리 — 저작도구는 `LS_USER_MENU` 사용 |
| `MNG_SGG`, `MNG_STNG` | 시군구·설정 | 관제서버 전용 |
| `LS_NTC_BBS`, `LS_ATCH_FILE` | 공지·첨부파일 | 포털 공지사항은 포털 서버 담당. 저작도구 미구현 |

---

## 7. mock 필드 ↔ DB 매핑 가이드

본 절은 `mock/src/api/types.ts`의 DTO 필드와 실제 DB 컬럼 매핑을 명시한다. 백엔드 구현 시 본 표를 기준으로 조회/저장 로직을 작성한다.

| mock 필드 | DB 컬럼 | 비고 |
|-----------|---------|------|
| `VideoDto.eventType` | `LS_DATA_RAW.EVNT_TYPE_CD` → `CM_CODE.CODE_NM` JOIN | 코드 → 명칭 변환 |
| `VideoDto.weather` | `LS_DATA_RAW.WTHR_CD` → `CM_CODE.CODE_NM` | 동일 |
| `VideoDto.season` | `LS_DATA_RAW.SESN_CD` → `CM_CODE.CODE_NM` | 동일 |
| `AugmentJob.labelIntegrity` | `LS_DATA_AUG.LBL_INTGRT_PCT` | 신규 컬럼 (§4.2) |
| `ReviewDto.status` | `LS_PJT_DATA_STTS.DATA_STTS_CD` (`PENDING` / `IN_REVIEW` / `APPROVED` / `REJECTED`) | 코드값 §4.4에 신규 정의 |
| `HistoryCommit.hash` | `LS_DATA_LBL_HSTRY.GITEA_CMT_HASH` | 신규 컬럼 (§4.3) |
| `MartDataset.eventType` / `weather` / `season` | `LS_DATA_RAW` GROUP BY | 데이터셋 구성 영상의 대표값 |
| `VideoDto.downloadDeadline` | `LS_PORTAL_USER_VIDEO.DOWNLOAD_DDLN_DT` | 포털 영역 (§5) |
| `PortalUserDto.uploadCount` | `LS_PORTAL_USER_VIDEO` GROUP BY 또는 `LS_PORTAL_USER_STATS.UPLOAD_CNT` | 포털 영역 (§5) |
| `PortalUserDto.labeledCount` | `LS_PORTAL_USER_STATS.LABELED_CNT` 또는 GROUP BY | 포털 영역 (§5) |
| `SystemSettings.ffmpegConfig.*` | `LS_SYSTEM_CONFIG` (`FFMPEG_*` keys) | 영속화 (§5.2 신규, resolution 제외 — 원본 보존 정책) |
| `SystemSettings.batchConfig.*` | `LS_SYSTEM_CONFIG` (`BATCH_*` keys) | 영속화 (§5.2 신규) |
| `SystemSettings.externalSystems` | (DB 미저장) | 실시간 actuator/health 조회 |

---

*문서 버전: V1.2.3 | 작성일: 2026-04-28 | 참조 DB: klid_system @ 192.168.102.101:13307 (조회일: 2026-04-23)*
