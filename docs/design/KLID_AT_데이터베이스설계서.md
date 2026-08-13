# D9 데이터베이스 설계서

> **ID 표기 규칙(공통)**: 본 산출물의 모든 ID(KLID-AT-UC/CO/DC/DCD/SD/SC/AC/EN/ERD/TB/II/IC/IF/UCD/ACT 등)는 **전체 형식 `KLID-AT-XX-NNN`** 으로 표기한다. 끝부분만(예: `UC-001`) 약식 표기 금지. 범위는 시작 ID만 전체형으로(예: `KLID-AT-UC-001~013`). ※ 요구사항(RQ-SFR-NN-NN)·시스템시험(KLID-ST-NNN) 등 타 체계 ID는 각 체계 원형 유지.

## 작성 목적
> 최종적으로 설계된 테이블과 인덱스를 데이터베이스 공간에 매핑시키고 저장공간 등의 물리 모델을 기술한다.

## 작성 방법
> 부서에서 운영하는 데이터베이스 목록을 작성하고, 데이터베이스의 물리적 상세내용을 작성한다.

## 산출물 양식

### 제.개정 이력

| 날짜 | 버전 | 작성자 | 승인자 | 내용 |
|------|------|--------|--------|------|
| 2026-08-06 | 1.0 | - | - | LogiCraft 그래프 기반 생성 (/cc-doc-gen) |
| 2026-08-13 | 1.1 | - | - | 관제 왕복 종결(2026-08-12) 반영 — `LS_DATA_INGEST.OG_CD` 컬럼 제거, `VMS_CCTV_ID`(`LS_DATA_INGEST`·`LS_DATA_RAW`) NULL 허용, `LS_DATASET_EXPORT.FRME_CNT` 산정 기준 설명 정정(실제 프레임 수) |

### 헤더

| D9 | 데이터베이스 설계서 |
|-------|----------|
| 시스템명 | AI 기반 지방정부 CCTV 관제지원시스템(2차) | 서브시스템명 | 학습데이터 저작도구 |
| 단계명  | 설계       | 작성일자   | 2026-08-06 | 버전 | 1.0 |

---

### 1. 데이터베이스 목록

| 데이터베이스 ID | 명칭 | 주관부서 | 비고 |
|-----------|------|--------|------|
| KLID-AT-DB-001 | 학습데이터 저작도구 데이터베이스 (klid_system / klid_at 스키마) | 저작도구 | PostgreSQL, 문자셋 UTF-8. 저작도구가 소유·구성하는 63개 테이블의 물리 설계 대상 |
| - | 관제지원시스템 공유 스키마 (영상·CCTV·계정·공통코드) | 관제지원시스템 | 공유(READ) — 물리 설계 비대상. 저작도구는 참조·검증만 수행 |
| - | 배치 스케줄러 운영 스키마 | 인프라 | 공유(READ) — 물리 설계 비대상. 스케줄러 제품이 정의·제공하며 2노드 이중화 잠금 테이블 포함 |

> **물리 설계 대상 범위**: 저작도구가 직접 소유하는 63개 테이블(`KLID-AT-TB-001~063`)이며, D8 엔티티 관계 모형 설계서의 엔티티 63건(`KLID-AT-EN-001~063`)과 **1:1·번호 정렬**로 대응한다. 공유 스키마는 위 목록에 "공유(READ)" 비고로만 표기하고 §2 데이터베이스 정의·§3 테이블 명세에서는 다루지 않는다.

> **신규/변경 구분(1차 대비)**: 본 설계서의 물리 설계 대상 63개 테이블 및 전 컬럼은 2차 사업 **신규 구축분**이다. 저작도구 데이터베이스는 PostgreSQL로 신설되어 전 테이블이 신규 생성되며, 1차에서 재사용·이관한 물리 테이블은 없다. 공유 스키마는 소유 주체가 달라 본 구분 대상에서 제외한다.

### 2. 데이터베이스 정의

> **물리 개념 적응 표기(PostgreSQL)**: 본 데이터베이스는 PostgreSQL이므로 타 DBMS의 물리 개념을 다음 등가물로 적응 표기한다 — **Storage Group → `pg_default`**(기본 테이블스페이스), **Bufferpool → `shared_buffers`**(인스턴스 공유 버퍼), **인덱스 BP → `shared_buffers`**(PostgreSQL은 객체별 버퍼풀을 분리 지정하지 않고 인덱스도 동일 공유 버퍼를 사용), **TS(테이블 스페이스) → `pg_default` 단일 논리 테이블 스페이스**.

| 데이터베이스 ID | KLID-AT-DB-001 | 데이터베이스명 | klid_at (klid_system) | Storage Group | pg_default | |
|-----------|---|---------|---|-------------|---|---|
| Bufferpool | shared_buffers | | 인덱스 BP | shared_buffers | |

> **TS 용량 산정**: TS 용량 = Σ(테이블별 최대건수 × 평균 row bytes). 63개 테이블 합산 추정 **≈ 1.8GB**(데이터 기준), 인덱스 포함 **≈ 2.2GB**. 용량 기여 상위는 데이터라벨(최대 1,000,000행 ≈ 700MB) · 데이터라벨AI정보(1,000,000행 ≈ 250MB) · 포털업로드라벨(500,000행 ≈ 75MB) · 프레임원천(100,000행 ≈ 70MB) · 라벨버전 스냅샷(20,000행 ≈ 60MB) 순이다. 인덱스 용량은 테이블별 PK·UNIQUE·보조 인덱스 합산으로 산정한다.
>
> **합치 확인**: 물리 설계 대상 **63개** = §2 데이터베이스 정의 테이블 매핑 **63개** = §3 테이블 명세 **63개**(`KLID-AT-TB-001~063`) = D8 엔티티 **63건**(`KLID-AT-EN-001~063`). 인덱스 ID는 테이블 번호에 동조해 `KLID-AT-ID-001~063`을 부여한다(테이블 1개 = 인덱스 집합 1개).

| TS ID | TS 용량 | 테이블 ID | 테이블 명 | 인덱스 ID | 인덱스 용량 | 비고 |
|-------|--------|---------|---------|---------|---------|------|
| KLID-AT-TS-001 | ≈ 1.8GB (Σ 최대건수×평균 row) | KLID-AT-TB-001 | LS_DATA_RAW | KLID-AT-ID-001 | ≈ 1.5MB | PK + 클립식별자 UNIQUE + 상태·원본참조·CCTV 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-002 | LS_DATA_SRC | KLID-AT-ID-002 | ≈ 8MB | PK + (영상,프레임번호) UNIQUE + 영상 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-003 | LS_DATA_RAW_HSTRY | KLID-AT-ID-003 | ≈ 2MB | PK + 영상 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-004 | LS_DATA_SRC_HSTRY | KLID-AT-ID-004 | ≈ 6MB | PK + 프레임 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-005 | LS_DATA_INGEST | KLID-AT-ID-005 | ≈ 2MB | PK + 클립식별자 UNIQUE + 미처리 폴링 부분 인덱스 + 영상 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-006 | LS_DATA_LBL | KLID-AT-ID-006 | ≈ 60MB | PK + 프레임·라벨마스터·트랙·라벨출처 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-008 | LS_DATA_LBL_ATTR_VAL | KLID-AT-ID-008 | ≈ 30MB | PK + (라벨,속성) UNIQUE + 라벨 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-009 | LS_DATA_LBL_HSTRY | KLID-AT-ID-009 | ≈ 8MB | PK + (프레임,등록일시 역순) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-010 | LS_DATA_META | KLID-AT-ID-010 | ≈ 2MB | PK + (영상,메타키) UNIQUE + 멱등키·외부작업 UNIQUE + 영상 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-011 | LS_DATA_META_HSTRY | KLID-AT-ID-011 | ≈ 2MB | PK + 메타 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-012 | LS_DATA_META_REVIEW | KLID-AT-ID-012 | ≈ 2MB | PK + (메타,메타유형) UNIQUE + 메타·대상·상태 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-013 | LS_EVNT_ANNO | KLID-AT-ID-013 | ≈ 1MB | PK + 영상 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-014 | LS_EVNT_ANNO_REVIEW | KLID-AT-ID-014 | ≈ 1MB | PK + 어노테이션·(메타유형,검수상태) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-015 | LS_LABEL | KLID-AT-ID-015 | ≈ 0.1MB | PK + 활성 라벨명 UNIQUE(대소문자 무시) + 활성 탐지클래스 UNIQUE + 사용여부 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-016 | LS_LABEL_ATTR | KLID-AT-ID-016 | ≈ 0.1MB | PK + (라벨,속성명) UNIQUE + (라벨,사용여부,정렬순서) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-017 | LS_LABEL_PRESET | KLID-AT-ID-017 | ≈ 0.02MB | PK + 프리셋명 UNIQUE + 이벤트유형 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-018 | LS_LABEL_PRESET_CODE | KLID-AT-ID-018 | ≈ 0.1MB | PK + (프리셋,라벨코드) UNIQUE + (프리셋,라벨) 부분 UNIQUE + 프리셋·라벨 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-019 | LS_LABEL_VERSION | KLID-AT-ID-019 | ≈ 6MB | PK + (프레임,버전해시) UNIQUE + (영상,프레임,활성여부) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-020 | LS_MARKING | KLID-AT-ID-020 | ≈ 1MB | PK + 영상 인덱스 + 진행 중 마킹 부분 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-021 | LS_DEIDENT_PROC_LOG | KLID-AT-ID-021 | ≈ 1MB | PK + 영상·처리상태·진행조회 인덱스 + 외부작업 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-022 | LS_DEIDENT_REPORT | KLID-AT-ID-022 | ≈ 0.5MB | PK + 영상·(신고상태,신고일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-023 | LS_RAW_DATA_ENROLLMENT | KLID-AT-ID-023 | ≈ 0.3MB | PK(영상 식별자) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-024 | LS_RAW_DATA_STATUS | KLID-AT-ID-024 | ≈ 0.5MB | PK(영상 식별자) + (상태,수정일시 역순) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-025 | LS_DATA_ISSUE | KLID-AT-ID-025 | ≈ 1MB | PK + 영상·프레임·상위이슈·작성자 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-026 | LS_TASK_ASSIGNMENT | KLID-AT-ID-026 | ≈ 1MB | PK + (영상,사용자,작업유형) UNIQUE + 영상·사용자 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-027 | LS_TASK_ASSIGN_HISTORY | KLID-AT-ID-027 | ≈ 1MB | PK + 배정 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-028 | LS_TASK_EVENT_LOG | KLID-AT-ID-028 | ≈ 4MB | PK + (영상,발생일시)·행위자 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-029 | LS_ISSUE_COMMENT | KLID-AT-ID-029 | ≈ 1MB | PK + 이슈·작성자 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-030 | LS_DATA_AUG | KLID-AT-ID-030 | ≈ 1.5MB | PK + 멱등키·외부작업 UNIQUE + 대상·상태·파생영상 인덱스 + 해상도 부분 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-031 | LS_DATA_AUG_RVW | KLID-AT-ID-031 | ≈ 1MB | PK + 증강·대상·검수상태 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-032 | LS_DATA_AUG_LBL_MAP | KLID-AT-ID-032 | ≈ 8MB | PK + 증강·원본라벨·결과라벨 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-033 | LS_DATA_AUG_DSCD | KLID-AT-ID-033 | ≈ 1.5MB | PK + 증강·파생영상 인덱스 + 스윕·파일삭제 부분 인덱스 + 활성 폐기 부분 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-034 | LS_DATA_AUG_JOB | KLID-AT-ID-034 | ≈ 4MB | PK + 멱등키 UNIQUE + (증강,순번)·외부작업 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-035 | LS_DATA_AUG_JOB_FILE | KLID-AT-ID-035 | ≈ 20MB | PK + (위탁,파일순번) UNIQUE + 프레임 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-036 | LS_EVNT_TYPE | KLID-AT-ID-036 | ≈ 0.05MB | PK(이벤트유형코드) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-037 | LS_EVNT_CTGRY | KLID-AT-ID-037 | ≈ 0.01MB | PK(분류코드,카테고리코드) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-038 | LS_DATASET_EXPORT | KLID-AT-ID-038 | ≈ 2MB | PK + (영상,산출버전) UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-039 | LS_DATASET_VIDEO_META | KLID-AT-ID-039 | ≈ 3MB | PK + (영상,스냅샷해시) UNIQUE + 활성 스냅샷 부분 UNIQUE + 검수완료일시 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-040 | LS_META_REPL_OUTBOX | KLID-AT-ID-040 | ≈ 0.6MB | PK + (상태코드,등록일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-041 | LS_CONTROL_NOTIFY_FALLBACK | KLID-AT-ID-041 | ≈ 1MB | PK + 멱등키 UNIQUE + (상태,다음재시도일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-042 | LS_WEBHOOK_IDEMPOTENCY | KLID-AT-ID-042 | ≈ 0.5MB | PK(멱등키) + (채널,상태)·외부작업 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-043 | LS_MON_NOTI_ACML | KLID-AT-ID-043 | ≈ 2MB | PK + 열린 창 부분 UNIQUE + (상태,등록일시)·(상태,수정일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-044 | LS_WHK_FAIL_NMTM | KLID-AT-ID-044 | ≈ 0.3MB | PK(호출주소,시작일시) + 만료일시 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-045 | LS_WHK_SIGN_USE | KLID-AT-ID-045 | ≈ 0.6MB | PK(서명해시) + 만료일시 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-046 | LS_BATCH_PROC_LOG | KLID-AT-ID-046 | ≈ 8MB | PK + 작업·영상·프레임·(단계,상태) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-047 | LS_AUTH_WORK_LOCK | KLID-AT-ID-047 | ≈ 0.5MB | PK + 잠금식별자 UNIQUE + 대상·(상태,만료일시) 인덱스 + 영상 활성 잠금 부분 UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-048 | LS_DEADLINE | KLID-AT-ID-048 | ≈ 0.01MB | PK(마감일련번호) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-049 | LS_CLIP_SCHEDULE_QUE | KLID-AT-ID-049 | ≈ 2MB | PK + 영상·(상태코드,작업유형코드) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-050 | LS_BAT_RTY_WTNG | KLID-AT-ID-050 | ≈ 1MB | PK + 영상 UNIQUE + (상태,재시도예정일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-051 | LS_TUS_UPLOAD | KLID-AT-ID-051 | ≈ 0.1MB | PK(업로드 식별자) + (상태,만료일시)·(사용자,상태) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-052 | LS_PORTAL_USER_LABEL | KLID-AT-ID-052 | ≈ 6MB | PK + (사용자,영상)·(사용자,프레임) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-053 | LS_PORTAL_ULD | KLID-AT-ID-053 | ≈ 3MB | PK + (사용자,등록일시)·(상태,수정일시) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-054 | LS_PORTAL_ULD_FRME | KLID-AT-ID-054 | ≈ 25MB | PK + (업로드,프레임번호) UNIQUE |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-055 | LS_PORTAL_ULD_LBL | KLID-AT-ID-055 | ≈ 30MB | PK + (프레임,사용자)·(사용자,업로드) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-056 | LS_PORTAL_TUS_ULD | KLID-AT-ID-056 | ≈ 0.1MB | PK(업로드 식별자) + 진행 중 세션 부분 인덱스(만료일시·사용자) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-057 | LS_ACNT_USER | KLID-AT-ID-057 | ≈ 0.02MB | PK(사용자번호) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-058 | LS_USER_ROLE | KLID-AT-ID-058 | ≈ 0.02MB | PK(사용자번호) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-059 | LS_AUTHRT_GRANT_ATMPT | KLID-AT-ID-059 | ≈ 0.3MB | PK(구분,식별자,시작일시) + 만료일시 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-060 | LS_NOTICE | KLID-AT-ID-060 | ≈ 0.1MB | PK + (발행상태,상단고정,등록일시 역순) 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-061 | LS_NOTICE_ATTACH | KLID-AT-ID-061 | ≈ 0.1MB | PK + 게시글 인덱스 |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-062 | LS_SYSTEM_CONFIG | KLID-AT-ID-062 | ≈ 0.01MB | PK(설정키) |
| KLID-AT-TS-001 | 〃 | KLID-AT-TB-063 | LS_META | KLID-AT-ID-063 | ≈ 0.01MB | PK(메타키) |

> **TS ID·TS 용량 표기**: 63개 테이블은 모두 단일 논리 테이블 스페이스 `KLID-AT-TS-001`(PostgreSQL `pg_default`)에 속한다. TS 용량 `≈ 1.8GB`는 **테이블 스페이스 전체 합산값**이며 개별 테이블 용량이 아니다. 첫 행에만 총량을 표기하고 이하 행은 `〃`(상동)으로 동일 TS·동일 합산 용량임을 나타낸다. 테이블별 용량은 §3 각 테이블 명세의 `용량` 칸을 참고한다.

### 3. 테이블 명세

> **⟳ 테이블별로 1개씩 반복 작성한다.**
> **공유 스키마 안내**: 관제지원시스템 공유 스키마와 배치 스케줄러 운영 스키마는 인프라·관제지원시스템이 제공하고 저작도구는 읽기 위주 참조(검증)만 하므로 **물리 설계 비대상**이다. §1 데이터베이스 목록에 "공유(READ)"로만 표기하고 본 절에는 테이블 블록을 작성하지 않는다.
>
> **컬럼표 표기 규칙**: `컬럼명`은 논리 업무 한글명, `컬럼ID`는 물리 영문 컬럼명이다. `Not Null`만 `Y`/`N`로 이분 표기하고, `PK`·`FK`는 해당하는 경우에만 `Y`, `IDX`는 인덱스에 포함되는 컬럼에만 해당 테이블의 인덱스 ID를 표기하며 그 외는 공란으로 둔다. 참조 무결성 제약이 선언되지 않은 참조는 `제약조건` 칸에 `논리 FK(→대상)`로 명시한다.

<!-- hwpx:ignore-start -->
### LS_DATA_RAW (KLID-AT-TB-001)
- 사용: [[KLID_AT_엔티티관계모형설계서#원시영상 (KLID-AT-EN-001)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-001 — LS_DATA_RAW

| 테이블ID | KLID-AT-TB-001 | 테이블명 | LS_DATA_RAW |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-001 (원시영상) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력·감사 적재) |
| 테이블 설명 | 라벨링 대상 원시 영상 메타를 영상 단위로 보관하는 작업 단위 기준 테이블. 관제 클립 식별자를 유일 키로 두어 동일 클립 재수신 시 단일 행을 갱신하고, 개인정보 유형 코드로 비식별 대상 여부를 판정한다. 증강·해상도 파생영상은 원본 영상을 자기참조로 가리킨다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 원본 삭제 금지 | 20,000 | ≈ 12MB (20,000 × 600B) | 영상 5,000 + 파생분 ~15,000 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 원시영상일련번호 | RAW_SN | BIGINT | Y | Y |  | KLID-AT-ID-001 | IDENTITY 자동증가 | - |
| VMS클립아이디 | VMS_CLIP_ID | VARCHAR(128) | Y |  |  | KLID-AT-ID-001 | - | UNIQUE · 재수신 시 갱신 기준 키 |
| VMS_CCTV아이디 | VMS_CCTV_ID | VARCHAR(64) | N |  |  | KLID-AT-ID-001 | - | 2026-08-13 NULL 허용(관제 확정) — CCTV 식별자 없는 영상(수동 업로드 등) 존재 인정 |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | N |  | Y |  | - | 논리 FK(→LS_EVNT_TYPE) |
| 지방자치단체코드 | LCLGV_CD | VARCHAR(20) | N |  |  |  | - | - |
| 개인정보유형코드 | PRVC_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | 개인정보 유형 3종(비식별불요/개인정보/가명) |
| 개인정보포함여부 | PRVC_YN | CHAR(1) | Y |  |  |  | 'N' | 개인정보 유형에서 파생 |
| 비식별여부 | DE_IDENT_YN | CHAR(1) | Y |  |  |  | 'N' | 성공/실패·신고/미수행 |
| 원시파일경로명 | RAW_FILE_PATH_NM | VARCHAR(500) | Y |  |  |  | - | 원본 보존(불변) |
| 촬영일시 | SHT_DT | TIMESTAMP | N |  |  |  | - | - |
| 영상길이초 | VDO_LEN_SEC | INTEGER | N |  |  |  | - | - |
| 데이터상태코드 | DATA_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-001 | 'PENDING' | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 원본원시영상일련번호 | ORGNL_RAW_SN | BIGINT | N |  | Y | KLID-AT-ID-001 | - | 논리 FK(→LS_DATA_RAW, 자기참조) |
| 영상길이밀리초 | VDO_LEN_MS | BIGINT | N |  |  |  | - | - |
| 날씨명 | WTHR_NM | VARCHAR(20) | N |  |  |  | - | - |
| 주야구분코드 | DAY_NGT_CD | VARCHAR(20) | N |  |  |  | - | - |
| 계절코드 | SESN_CD | VARCHAR(20) | N |  |  |  | - | - |
| 출처유형코드 | SRC_TYPE | VARCHAR(20) | N |  |  |  | - | 수집/생성/파생 구분 |
| 증강유형코드 | AUG_TYPE_CD | VARCHAR(20) | N |  |  |  | - | 외부 증강 3종 + 해상도 파생 3종 |
| 영상익명정보포함여부 | ANONY_INCL_YN | CHAR(1) | N |  |  |  | - | - |
| 영상가명정보포함여부 | PSDO_INCL_YN | CHAR(1) | N |  |  |  | - | - |
| 영상개인정보포함여부 | PRVC_INCL_YN | CHAR(1) | N |  |  |  | - | 영상 단위 판정(프레임 축과 입도 상이) |

<!-- hwpx:ignore-start -->
### LS_DATA_SRC (KLID-AT-TB-002)
- 사용: [[KLID_AT_엔티티관계모형설계서#프레임원천 (KLID-AT-EN-002)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-002 — LS_DATA_SRC

| 테이블ID | KLID-AT-TB-002 | 테이블명 | LS_DATA_SRC |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-002 (프레임원천) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력·감사 적재) |
| 테이블 설명 | 영상에서 추출한 키프레임 테이블. 원본 프레임 경로와 비식별 프레임 경로를 같은 행에서 짝으로 관리하며, 영상·프레임번호 조합 UNIQUE로 중복 추출을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 600 | 영구 — 학습데이터 원천 | 100,000 | ≈ 70MB (100,000 × 700B) | 이미지 프레임 10만장 목표 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 프레임원천일련번호 | SRC_SN | BIGINT | Y | Y |  | KLID-AT-ID-002 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-002 | - | 복합 UNIQUE(RAW_SN,FRM_NO) · FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 프레임번호 | FRM_NO | BIGINT | Y |  |  | KLID-AT-ID-002 | - | 복합 UNIQUE(RAW_SN,FRM_NO) |
| 원천파일경로명 | SRC_FILE_PATH_NM | VARCHAR(500) | N |  |  |  | - | - |
| 비식별원천파일경로명 | DE_IDNTF_SRC_FILE_PATH_NM | VARCHAR(1000) | N |  |  |  | - | - |
| 촬영일시 | SHT_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | UPD_DT | TIMESTAMP | N |  |  |  | - | - |
| 영상프레임번호 | VDO_FRM_NO | BIGINT | N |  |  |  | - | - |
| 프레임설명 | FRM_EXPLN | VARCHAR(1000) | N |  |  |  | - | - |
| 프레임익명정보포함여부 | ANONY_INCL_YN | CHAR(1) | N |  |  |  | - | - |
| 프레임가명정보포함여부 | PSDO_INCL_YN | CHAR(1) | N |  |  |  | - | - |
| 프레임개인정보포함여부 | PRVC_INCL_YN | CHAR(1) | N |  |  |  | - | - |
| 라벨버전 | LBL_VER | BIGINT | Y |  |  |  | 0 | 라벨 변경 감지용 증가 버전 |

<!-- hwpx:ignore-start -->
### LS_DATA_RAW_HSTRY (KLID-AT-TB-003)
- 사용: [[KLID_AT_엔티티관계모형설계서#원시영상이력 (KLID-AT-EN-003)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-003 — LS_DATA_RAW_HSTRY

| 테이블ID | KLID-AT-TB-003 | 테이블명 | LS_DATA_RAW_HSTRY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-003 (원시영상이력) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 영상 변경 이력 테이블. 적재(신규·갱신)·상태 전이 등 변경 사유와 변경 전후 상태를 영상 단위로 누적 기록한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 60 | 영구 — 라이프사이클 추적 | 60,000 | ≈ 6MB (60,000 × 100B) | 영상당 평균 3건 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이력일련번호 | HSTRY_SEQ | BIGINT | Y | Y |  | KLID-AT-ID-003 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-003 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 변경유형코드 | CHG_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 이전상태코드 | PREV_STTS_CD | VARCHAR(32) | N |  |  |  | - | - |
| 변경상태코드 | NEW_STTS_CD | VARCHAR(32) | N |  |  |  | - | - |
| 변경사용자번호 | CHG_USER_NO | BIGINT | N |  |  |  | - | - |
| 변경일시 | CHG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_SRC_HSTRY (KLID-AT-TB-004)
- 사용: [[KLID_AT_엔티티관계모형설계서#프레임원천이력 (KLID-AT-EN-004)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-004 — LS_DATA_SRC_HSTRY

| 테이블ID | KLID-AT-TB-004 | 테이블명 | LS_DATA_SRC_HSTRY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-004 (프레임원천이력) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 프레임 변경 이력 테이블. 생성·비식별 경로 연결 등 변경 사유를 프레임 단위로 누적 기록한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 1,200 | 영구 — 라이프사이클 추적 | 200,000 | ≈ 18MB (200,000 × 90B) | 프레임당 평균 2건 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이력일련번호 | HSTRY_SEQ | BIGINT | Y | Y |  | KLID-AT-ID-004 | IDENTITY 자동증가 | - |
| 프레임원천일련번호 | SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-004 | - | 논리 FK(→LS_DATA_SRC) |
| 변경유형코드 | CHG_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 변경사용자번호 | CHG_USER_NO | BIGINT | N |  |  |  | - | - |
| 변경일시 | CHG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_INGEST (KLID-AT-TB-005)
- 사용: [[KLID_AT_엔티티관계모형설계서#관제인입 (KLID-AT-EN-005)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-005 — LS_DATA_INGEST

| 테이블ID | KLID-AT-TB-005 | 테이블명 | LS_DATA_INGEST |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-005 (관제인입) |
| 트리거 구성 | 없음 (관제지원시스템 직접 적재 · 저작도구는 읽기·상태 갱신) |
| 테이블 설명 | 관제지원시스템이 학습용 영상 메타를 저작도구에 직접 적재하는 수신 원장 테이블. 주기 배치가 미처리 행을 집어 원시영상으로 적재하며 수신 기록은 적재 이후에도 보존한다. 클립 식별자 UNIQUE로 중복 수신을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 수신 원장 보존 | 20,000 | ≈ 20MB (20,000 × 1,000B) | 영상 1건당 수신 1행 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 수신일련번호 | RCPTN_SN | BIGINT | Y | Y |  | KLID-AT-ID-005 | IDENTITY 자동증가 | - |
| 수신일시 | RCPTN_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-005 | CURRENT_TIMESTAMP | - |
| 처리상태코드 | PRCS_STTS_CD | VARCHAR(20) | Y |  |  |  | 'PENDING' | - |
| 원시영상일련번호 | RAW_SN | BIGINT | N |  | Y | KLID-AT-ID-005 | - | 논리 FK(→LS_DATA_RAW) |
| 재시도횟수 | RTY_CNT | INTEGER | Y |  |  |  | 0 | - |
| 처리일시 | PRCS_DT | TIMESTAMP | N |  |  |  | - | - |
| 다음재시도일시 | NXTM_RTRY_DT | TIMESTAMP | N |  |  |  | - | - |
| 에러메시지 | ERR_MSG | VARCHAR(4000) | N |  |  |  | - | - |
| VMS클립아이디 | VMS_CLIP_ID | VARCHAR(128) | Y |  |  | KLID-AT-ID-005 | - | UNIQUE · 중복 수신 차단 |
| VMS_CCTV아이디 | VMS_CCTV_ID | VARCHAR(64) | N |  |  |  | - | 2026-08-13 NULL 허용(관제 확정) — CCTV 식별자 없는 영상 존재 인정. 관제가 `CCTV_NM` 에 대체 표기를 채워 보낸다 |
| 동영상파일명 | VDO_FILE_NM | VARCHAR(300) | Y |  |  |  | - | - |
| 원시파일경로명 | RAW_FILE_PATH_NM | VARCHAR(500) | Y |  |  |  | - | - |
| 출처유형코드 | SRC_TYPE | VARCHAR(20) | Y |  |  |  | - | 기본값 미부여(누락 시 적재 거부) |
| 촬영일시 | SHT_DT | TIMESTAMP | N |  |  |  | - | - |
| 파일형식 | FILE_FMT | VARCHAR(20) | N |  |  |  | - | - |
| 영상코덱 | VDO_CDC | VARCHAR(20) | N |  |  |  | - | - |
| 파일크기 | FILE_SZ | BIGINT | N |  |  |  | - | - |
| 지방자치단체명 | LCLGV_NM | VARCHAR(100) | N |  |  |  | - | - |
| 영상길이초 | VDO_LEN_SEC | NUMERIC(10,0) | N |  |  |  | - | - |
| 프레임재생속도 | FPS | VARCHAR(10) | N |  |  |  | - | - |
| 프레임수 | FRME_CNT | NUMERIC(10,0) | N |  |  |  | - | - |
| 종횡비 | ASPRT_RT | VARCHAR(20) | N |  |  |  | - | - |
| 가로길이 | WDTH | NUMERIC(10,0) | N |  |  |  | - | - |
| 세로길이 | VRTC | NUMERIC(10,0) | N |  |  |  | - | - |
| 해상도 | RESL | VARCHAR(20) | N |  |  |  | - | - |
| 색심도값 | BIT | VARCHAR(20) | N |  |  |  | - | - |
| 화소값 | PXL | VARCHAR(20) | N |  |  |  | - | - |
| WGS84위도 | WGS84_LAT | NUMERIC(10,7) | N |  |  |  | - | - |
| WGS84경도 | WGS84_LOT | NUMERIC(10,7) | N |  |  |  | - | - |
| CCTV명 | CCTV_NM | VARCHAR(300) | N |  |  |  | - | - |
| CCTV높이 | CCTV_HGT | NUMERIC(4,1) | N |  |  |  | - | - |
| 주감시방향값 | MAIN_SURV_PAN_ANG | INTEGER | N |  |  |  | - | - |
| 이벤트아이디 | EVNT_ID | VARCHAR(50) | N |  |  |  | - | - |
| 이벤트명 | EVNT_NM | VARCHAR(200) | N |  |  |  | - | - |
| 관제일지내용 | MNTR_CN | VARCHAR(4000) | N |  |  |  | - | - |
| 지방자치단체코드 | LCLGV_CD | VARCHAR(20) | N |  |  |  | - | - |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | N |  | Y |  | - | 논리 FK(→LS_EVNT_TYPE) |
| 원천익명정보포함여부 | ANONY_INCL_YN | CHAR(1) | N |  |  |  | 'N' | - |
| 원천가명정보포함여부 | PSDO_INCL_YN | CHAR(1) | N |  |  |  | 'N' | - |
| 원천개인정보포함여부 | PRVC_INCL_YN | CHAR(1) | N |  |  |  | 'Y' | - |
| 이벤트분류코드 | EVNT_CLSF_CD | CHAR(2) | N |  |  |  | - | - |
| 이벤트카테고리코드 | EVNT_CTGRY_CD | CHAR(4) | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_LBL (KLID-AT-TB-006)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터라벨 (KLID-AT-EN-006)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-006 — LS_DATA_LBL

| 테이블ID | KLID-AT-TB-006 | 테이블명 | LS_DATA_LBL |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-006 (데이터라벨) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력·감사 적재) |
| 테이블 설명 | 현재 라벨 좌표·분류를 보관하는 테이블. 프레임 단위로 도형 유형(사각형·다각형·영역분할·추적)별 좌표를 보관하고 라벨 마스터를 참조한다. 자동 생성 출처·모델·신뢰도도 같은 행에 보관한다. 본 데이터베이스 최대 적재량 테이블이다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 6,000 | 영구 — 학습데이터 좌표 | 1,000,000 | ≈ 700MB (1,000,000 × 700B) | 프레임당 평균 10객체 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 라벨일련번호 | LBL_SN | BIGINT | Y | Y |  | KLID-AT-ID-006 | IDENTITY 자동증가 | - |
| 원천일련번호 | SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-006 | - | 논리 FK(→LS_DATA_SRC) |
| 라벨유형코드 | LBL_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | 사각형/다각형/영역분할/추적 |
| 라벨명 | LBL_NM | VARCHAR(80) | Y |  |  |  | - | - |
| 좌표내용 | POINT_CN | TEXT | N |  |  |  | - | - |
| 트랙아이디 | TRCK_ID | VARCHAR(30) | N |  |  | KLID-AT-ID-006 | - | - |
| 등록사용자번호 | REG_USER_NO | BIGINT | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 라벨아이디 | LBL_ID | BIGINT | N |  | Y | KLID-AT-ID-006 | - | FK 제약(→LS_LABEL) |
| 라벨출처코드 | LBL_SRC_CD | VARCHAR(20) | N |  |  | KLID-AT-ID-006 | - | 객체탐지/영역분할/트랙보간/시계열메타. NULL=자동 생성 아님 |
| 모델명 | MDL_NM | VARCHAR(100) | N |  |  |  | - | - |
| 모델버전 | MDL_VER | VARCHAR(50) | N |  |  |  | - | - |
| 신뢰도점수 | CONF_SCORE | NUMERIC(6,5) | N |  |  |  | - | 0.0~1.0 |
| 자동라벨여부 | AUTO_LBL_YN | CHAR(1) | N |  |  |  | - | NULL=자동 생성 아님. 기본값을 두지 않는다 |

<!-- hwpx:ignore-start -->
### LS_DATA_LBL_ATTR_VAL (KLID-AT-TB-008)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터라벨속성값 (KLID-AT-EN-008)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-008 — LS_DATA_LBL_ATTR_VAL

| 테이블ID | KLID-AT-TB-008 | 테이블명 | LS_DATA_LBL_ATTR_VAL |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-008 (데이터라벨속성값) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 라벨링된 객체별 속성값 저장 테이블. 라벨 속성 정의에 대응하는 실제 입력값을 객체 단위로 보관하며 (라벨, 속성) 조합 UNIQUE로 중복을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 3,000 | 영구 — 라벨 속성 | 500,000 | ≈ 60MB (500,000 × 120B) | 라벨당 평균 0.5속성 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 속성값아이디 | ATRB_VL_ID | BIGINT | Y | Y |  | KLID-AT-ID-008 | IDENTITY 자동증가 | - |
| 라벨일련번호 | LBL_SN | BIGINT | Y |  | Y | KLID-AT-ID-008 | - | 복합 UNIQUE(LBL_SN,ATRB_ID) · FK 제약(→LS_DATA_LBL) |
| 속성아이디 | ATRB_ID | BIGINT | Y |  | Y | KLID-AT-ID-008 | - | 복합 UNIQUE(LBL_SN,ATRB_ID) · FK 제약(→LS_LABEL_ATTR) |
| 속성값 | ATRB_VL | VARCHAR(1000) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_LBL_HSTRY (KLID-AT-TB-009)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨이력 (KLID-AT-EN-009)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-009 — LS_DATA_LBL_HSTRY

| 테이블ID | KLID-AT-TB-009 | 테이블명 | LS_DATA_LBL_HSTRY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-009 (라벨이력) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 라벨 저장 이벤트 단위 변경 이력 테이블. 프레임 단위 저장 1회마다 추가·수정·삭제 건수와 변경 상세를 누적 기록한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 300 | 영구 — 라벨 변경 추적 | 200,000 | ≈ 60MB (200,000 × 300B) | 프레임당 평균 2회 저장 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 라벨이력일련번호 | LBL_HSTRY_SN | BIGINT | Y | Y |  | KLID-AT-ID-009 | IDENTITY 자동증가 | - |
| 원천일련번호 | SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-009 | - | 논리 FK(→LS_DATA_SRC) |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-009 | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 추가건수 | ADD_CNT | INTEGER | Y |  |  |  | 0 | - |
| 수정건수 | MDFCN_CNT | INTEGER | Y |  |  |  | 0 | - |
| 삭제건수 | DEL_CNT | INTEGER | Y |  |  |  | 0 | - |
| 변경상세내용 | CHG_DTL_CN | TEXT | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_META (KLID-AT-TB-010)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터메타 (KLID-AT-EN-010)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-010 — LS_DATA_META

| 테이블ID | KLID-AT-TB-010 | 테이블명 | LS_DATA_META |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-010 (데이터메타) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 영상 단위 메타 항목의 키-값 저장소 테이블. 외부 시계열 메타 분석 결과와 기술 메타를 함께 보관하며 비동기 위탁 추적 항목(멱등키·외부 작업 식별자·재시도)을 포함한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 300 | 영구 — 시계열 메타 | 50,000 | ≈ 12MB (50,000 × 250B) | 영상당 평균 10키 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 메타일련번호 | META_SN | BIGINT | Y | Y |  | KLID-AT-ID-010 | IDENTITY 자동증가 | - |
| 원시일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-010 | - | 복합 UNIQUE(RAW_SN,META_KEY) · FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 메타키 | META_KEY | VARCHAR(64) | Y |  |  | KLID-AT-ID-010 | - | 복합 UNIQUE(RAW_SN,META_KEY) |
| 메타값 | META_VL | VARCHAR(2000) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 멱등키 | IDMP_KEY | VARCHAR(128) | N |  |  | KLID-AT-ID-010 | - | UNIQUE |
| 외부작업아이디 | OTSD_JOB_ID | VARCHAR(200) | N |  |  | KLID-AT-ID-010 | - | UNIQUE |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 영구실패시점 | DEAD_LETTER_AT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_META_HSTRY (KLID-AT-TB-011)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터메타이력 (KLID-AT-EN-011)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-011 — LS_DATA_META_HSTRY

| 테이블ID | KLID-AT-TB-011 | 테이블명 | LS_DATA_META_HSTRY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-011 (데이터메타이력) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 메타 값 변경 이력 테이블. 이전값·신규값과 변경자를 누적 기록한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 100 | 영구 — 메타 변경 추적 | 50,000 | ≈ 12MB (50,000 × 250B) | 메타당 평균 1건 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이력일련번호 | HSTRY_SEQ | BIGINT | Y | Y |  | KLID-AT-ID-011 | IDENTITY 자동증가 | - |
| 메타일련번호 | META_SN | BIGINT | Y |  | Y | KLID-AT-ID-011 | - | 논리 FK(→LS_DATA_META) |
| 이전값 | PREV_VL | VARCHAR(2000) | N |  |  |  | - | - |
| 신규값 | NEW_VL | VARCHAR(2000) | N |  |  |  | - | - |
| 변경사용자번호 | CHG_USER_NO | BIGINT | N |  |  |  | - | - |
| 변경일시 | CHG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_META_REVIEW (KLID-AT-TB-012)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터메타검토 (KLID-AT-EN-012)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-012 — LS_DATA_META_REVIEW

| 테이블ID | KLID-AT-TB-012 | 테이블명 | LS_DATA_META_REVIEW |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-012 (데이터메타검토) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 외부·자동 생성 메타의 검토 상태 테이블. 값 자체는 메타 테이블이 보관하고 본 테이블은 검토 상태·검토자·반려 사유를 분리 관리한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 200 | 영구 — 검토 이력 | 30,000 | ≈ 9MB (30,000 × 300B) | 메타 검토 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 데이터메타검수일련번호 | DATA_META_REVIEW_SN | BIGINT | Y | Y |  | KLID-AT-ID-012 | IDENTITY 자동증가 | - |
| 데이터메타일련번호 | DATA_META_SN | BIGINT | Y |  | Y | KLID-AT-ID-012 | - | 복합 UNIQUE(DATA_META_SN,META_TYPE_CD) · 논리 FK(→LS_DATA_META) |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-012 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 데이터원천일련번호 | DATA_SRC_SN | BIGINT | N |  | Y | KLID-AT-ID-012 | - | 논리 FK(→LS_DATA_SRC) |
| 메타유형코드 | META_TYPE_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-012 | - | 복합 UNIQUE(DATA_META_SN,META_TYPE_CD) |
| 생성시스템코드 | SRC_SYS_CD | VARCHAR(20) | N |  |  |  | - | - |
| 검수상태코드 | RVW_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-012 | - | 자동생성/검토대기/승인/반려 |
| 검수아이디 | RVW_ID | VARCHAR(30) | N |  |  |  | - | - |
| 검수일시 | RVW_DT | TIMESTAMP | N |  |  |  | - | - |
| 반려사유 | RJCT_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_EVNT_ANNO (KLID-AT-TB-013)
- 사용: [[KLID_AT_엔티티관계모형설계서#이벤트어노테이션 (KLID-AT-EN-013)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-013 — LS_EVNT_ANNO

| 테이블ID | KLID-AT-TB-013 | 테이블명 | LS_EVNT_ANNO |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-013 (이벤트어노테이션) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 단위 이벤트 어노테이션(사건 분류·설명·질의응답·근거 객체) 테이블. 영상 1건당 1행을 유지하며 문서형 컬럼으로 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 시계열 메타 본문 | 20,000 | ≈ 20MB (20,000 × 1,000B) | 영상 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이벤트어노테이션일련번호 | EVNT_ANNO_SN | BIGINT | Y | Y |  | KLID-AT-ID-013 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-013 | - | UNIQUE · FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 영상 1건당 1행 |
| 어노테이션내용 | ANNO_CN | JSONB | Y |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_EVNT_ANNO_REVIEW (KLID-AT-TB-014)
- 사용: [[KLID_AT_엔티티관계모형설계서#이벤트어노테이션검토 (KLID-AT-EN-014)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-014 — LS_EVNT_ANNO_REVIEW

| 테이블ID | KLID-AT-TB-014 | 테이블명 | LS_EVNT_ANNO_REVIEW |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-014 (이벤트어노테이션검토) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 이벤트 어노테이션의 검토 상태·검토자·반려 사유 테이블. 낙관적 잠금 컬럼으로 동시 검토 경합을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 검토 이력 | 20,000 | ≈ 6MB (20,000 × 300B) | 어노테이션 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 검수일련번호 | RVW_SN | BIGINT | Y | Y |  | KLID-AT-ID-014 | IDENTITY 자동증가 | - |
| 이벤트어노테이션일련번호 | EVNT_ANNO_SN | BIGINT | Y |  | Y | KLID-AT-ID-014 | - | FK 제약(→LS_EVNT_ANNO) |
| 검수상태코드 | RVW_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-014 | - | 자동생성/검토대기/승인/반려 |
| 메타유형코드 | META_TYPE_CD | VARCHAR(20) | N |  |  | KLID-AT-ID-014 | - | - |
| 검수아이디 | RVW_ID | VARCHAR(30) | N |  |  |  | - | - |
| 검수일시 | RVW_DT | TIMESTAMP | N |  |  |  | - | - |
| 반려사유 | RJCT_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | 낙관적 잠금 |

<!-- hwpx:ignore-start -->
### LS_LABEL (KLID-AT-TB-015)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨마스터 (KLID-AT-EN-015)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-015 — LS_LABEL

| 테이블ID | KLID-AT-TB-015 | 테이블명 | LS_LABEL |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-015 (라벨마스터) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 저작도구 전역 단일 라벨 풀의 마스터 테이블. 라벨명·색상·도형 유형과 자동 탐지 클래스 매핑을 정의하며 삭제는 사용여부 비활성 처리만 허용한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 50 | 0 | 영구 — 코드성 마스터 | 500 | ≈ 0.05MB (500 × 100B) | 시드 후 거의 불변 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 라벨아이디 | LBL_ID | BIGINT | Y | Y |  | KLID-AT-ID-015 | IDENTITY 자동증가 | - |
| 라벨명 | LBL_NM | VARCHAR(80) | Y |  |  | KLID-AT-ID-015 | - | 활성 라벨명 UNIQUE(대소문자·여백 무시, 부분 UNIQUE) |
| 색상값 | COLR_VL | VARCHAR(7) | Y |  |  |  | - | 색상 표기 형식 준수 |
| 라벨유형코드 | LBL_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 정렬순서 | SORT_SEQ | INTEGER | Y |  |  | KLID-AT-ID-015 | 0 | - |
| 사용여부 | USE_YN | CHAR(1) | Y |  |  | KLID-AT-ID-015 | 'Y' | 사용여부(논리 삭제) |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 검출유형코드 | DTCT_TYPE_CD | VARCHAR(20) | N |  |  | KLID-AT-ID-015 | - | 활성 라벨 1:1 매핑(부분 UNIQUE) |

<!-- hwpx:ignore-start -->
### LS_LABEL_ATTR (KLID-AT-TB-016)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨속성정의 (KLID-AT-EN-016)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-016 — LS_LABEL_ATTR

| 테이블ID | KLID-AT-TB-016 | 테이블명 | LS_LABEL_ATTR |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-016 (라벨속성정의) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 라벨별 속성 정의 테이블. 라벨링 시 부여할 수 있는 속성과 입력 위젯 유형·선택 옵션·기본값·프레임별 가변 여부를 정의한다. 객체별 실제 값은 라벨속성값 테이블에 저장한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 30 | 0 | 영구 — 코드성 마스터 | 1,000 | ≈ 0.1MB (1,000 × 120B) | 라벨당 평균 2속성 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 속성아이디 | ATRB_ID | BIGINT | Y | Y |  | KLID-AT-ID-016 | IDENTITY 자동증가 | - |
| 라벨아이디 | LBL_ID | BIGINT | Y |  | Y | KLID-AT-ID-016 | - | 복합 UNIQUE(LBL_ID,ATRB_NM) · FK 제약(→LS_LABEL) |
| 속성명 | ATRB_NM | VARCHAR(100) | Y |  |  | KLID-AT-ID-016 | - | 복합 UNIQUE(LBL_ID,ATRB_NM) |
| 입력유형코드 | INPUT_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 옵션목록내용 | VALUES_CN | VARCHAR(1000) | N |  |  |  | - | - |
| 기본값 | DFLT_VL | VARCHAR(255) | N |  |  |  | - | - |
| 가변여부 | MUTABLE_YN | CHAR(1) | Y |  |  |  | 'Y' | - |
| 정렬순서 | SORT_SEQ | INTEGER | Y |  |  | KLID-AT-ID-016 | 0 | - |
| 사용여부 | USE_YN | CHAR(1) | Y |  |  | KLID-AT-ID-016 | 'Y' | 사용여부(논리 삭제) |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_LABEL_PRESET (KLID-AT-TB-017)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨프리셋 (KLID-AT-EN-017)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-017 — LS_LABEL_PRESET

| 테이블ID | KLID-AT-TB-017 | 테이블명 | LS_LABEL_PRESET |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-017 (라벨프리셋) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 라벨링 프리셋 테이블. 자주 쓰는 라벨 묶음을 정의하고 선택적으로 이벤트 유형 1건에 1:1 매핑한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 10 | 0 | 영구 — 코드성 마스터 | 200 | ≈ 0.02MB (200 × 100B) | 시드 후 거의 불변 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 프리셋아이디 | PRESET_ID | BIGINT | Y | Y |  | KLID-AT-ID-017 | IDENTITY 자동증가 | - |
| 프리셋명 | PRESET_NM | VARCHAR(64) | Y |  |  | KLID-AT-ID-017 | - | UNIQUE |
| 설명 | EXPLN | VARCHAR(500) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | N |  | Y | KLID-AT-ID-017 | - | UNIQUE · 논리 FK(→LS_EVNT_TYPE) |

<!-- hwpx:ignore-start -->
### LS_LABEL_PRESET_CODE (KLID-AT-TB-018)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨프리셋코드 (KLID-AT-EN-018)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-018 — LS_LABEL_PRESET_CODE

| 테이블ID | KLID-AT-TB-018 | 테이블명 | LS_LABEL_PRESET_CODE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-018 (라벨프리셋코드) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 프리셋에 포함된 라벨 항목 테이블. 라벨명·도형 형태를 복제 보관하지 않고 라벨 마스터를 단일 진실원으로 참조하며, 상위 프리셋을 통해서만 변경한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 50 | 0 | 영구 — 코드성 마스터 | 1,000 | ≈ 0.1MB (1,000 × 80B) | 프리셋당 평균 5항목 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 코드일련번호 | CD_SN | BIGINT | Y | Y |  | KLID-AT-ID-018 | IDENTITY 자동증가 | - |
| 프리셋아이디 | PRESET_ID | BIGINT | Y |  | Y | KLID-AT-ID-018 | - | 복합 UNIQUE(PRESET_ID,LBL_CD) · FK 제약(→LS_LABEL_PRESET, 삭제 연쇄) |
| 라벨코드 | LBL_CD | VARCHAR(32) | N |  |  | KLID-AT-ID-018 | - | 복합 UNIQUE(PRESET_ID,LBL_CD) |
| 정렬순서 | SORT_SEQ | INTEGER | Y |  |  |  | 0 | - |
| 라벨아이디 | LBL_ID | BIGINT | N |  | Y | KLID-AT-ID-018 | - | FK 제약(→LS_LABEL) · 프리셋·라벨 부분 UNIQUE(라벨 연결 행) |

<!-- hwpx:ignore-start -->
### LS_LABEL_VERSION (KLID-AT-TB-019)
- 사용: [[KLID_AT_엔티티관계모형설계서#라벨버전 (KLID-AT-EN-019)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-019 — LS_LABEL_VERSION

| 테이블ID | KLID-AT-TB-019 | 테이블명 | LS_LABEL_VERSION |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-019 (라벨버전) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 검수 승인 시점의 프레임 단위 라벨 전체 스냅샷 테이블. 외부 형상관리도구를 사용하지 않고 직렬화 스냅샷을 보관하며, 버전 해시로 동일 스냅샷을 멱등 식별한다. 비교·복구는 두 스냅샷의 비교로 계산한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 학습데이터 버전 | 20,000 | ≈ 60MB (20,000 × 3,000B) | 스냅샷 평균 3KB |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 라벨버전일련번호 | LBL_VERSION_SN | BIGINT | Y | Y |  | KLID-AT-ID-019 | IDENTITY 자동증가 | - |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-019 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 소스프레임일련번호 | DATA_SRC_SN | BIGINT | N |  | Y | KLID-AT-ID-019 | - | 복합 UNIQUE(DATA_SRC_SN,VERSION_HASH) · 논리 FK(→LS_DATA_SRC) |
| 버전번호 | VER_NO | INTEGER | Y |  |  |  | - | - |
| 저장사유코드 | SAVE_REASON_CD | VARCHAR(20) | N |  |  |  | - | - |
| 활성여부 | ACTVTN_YN | CHAR(1) | Y |  |  | KLID-AT-ID-019 | 'Y' | 현재 활성 스냅샷 표시 |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 라벨페이로드 | LBL_PAYLOAD | TEXT | N |  |  |  | - | - |
| 버전해시 | VERSION_HASH | VARCHAR(64) | N |  |  | KLID-AT-ID-019 | - | 복합 UNIQUE(DATA_SRC_SN,VERSION_HASH) |

<!-- hwpx:ignore-start -->
### LS_MARKING (KLID-AT-TB-020)
- 사용: [[KLID_AT_엔티티관계모형설계서#영상마킹 (KLID-AT-EN-020)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-020 — LS_MARKING

| 테이블ID | KLID-AT-TB-020 | 테이블명 | LS_MARKING |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-020 (영상마킹) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상별 자동·수동 이벤트 식별 마킹 테이블. 자동은 프레임 간격 기준, 수동은 작업자 지정 시점 배열로 마킹 결과를 보관하며 상태는 대기→요청됨→완료로 전이한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 60 | 영구 — 마킹 이력 | 15,000 | ≈ 6MB (15,000 × 400B) | 영상당 평균 3마킹 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 마킹일련번호 | MARKING_SN | BIGINT | Y | Y |  | KLID-AT-ID-020 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-020 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 진행 중 마킹 영상당 1건(부분 UNIQUE) |
| 이벤트명 | EVNT_NM | VARCHAR(200) | Y |  |  |  | - | - |
| 마킹모드코드 | MARK_MODE_CD | VARCHAR(16) | Y |  |  |  | - | 자동(프레임간격)/수동(작업자 지정) |
| 프레임간격수 | FRME_INTV_NOCS | INTEGER | N |  |  |  | - | 자동 모드일 때 1 이상 필수 |
| 영상파일경로명 | VIDEO_FILE_PATH_NM | VARCHAR(500) | Y |  |  |  | - | - |
| 마킹내용 | MARK_CN | TEXT | Y |  |  |  | - | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  |  | 'PENDING' | - |
| 등록사용자번호 | REG_USER_NO | BIGINT | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 프레임재생속도 | FPS | DOUBLE PRECISION | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DEIDENT_PROC_LOG (KLID-AT-TB-021)
- 사용: [[KLID_AT_엔티티관계모형설계서#비식별처리로그 (KLID-AT-EN-021)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-021 — LS_DEIDENT_PROC_LOG

| 테이블ID | KLID-AT-TB-021 | 테이블명 | LS_DEIDENT_PROC_LOG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-021 (비식별처리로그) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 단위 외부 비식별 솔루션 위탁 처리 이력 테이블. 요청·진행조회·결과 회수 단계와 원본·비식별 파일 경로, 외부 작업 식별자를 보관하며 외부 작업 식별자 UNIQUE로 동일 위탁 재인계 시 단일 행 갱신을 보장한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 비식별 처리 이력 | 20,000 | ≈ 16MB (20,000 × 800B) | 영상 단위 1행 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 비식별처리로그일련번호 | PROC_LOG_SN | BIGINT | Y | Y |  | KLID-AT-ID-021 | IDENTITY 자동증가 | - |
| 원천영상식별자 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-021 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 내부요청식별자 | REQ_ID | VARCHAR(64) | N |  |  |  | - | - |
| 원본파일경로명 | ORGNL_FILE_PATH_NM | VARCHAR(1000) | Y |  |  |  | - | - |
| 비식별파일경로명 | DE_IDNTF_FILE_PATH_NM | VARCHAR(1000) | N |  |  |  | - | - |
| 처리상태코드 | PROC_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-021 | - | - |
| 요청일시 | REQ_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 응답일시 | RSPNS_DT | TIMESTAMP | N |  |  |  | - | - |
| 오류코드 | ERR_CD | VARCHAR(50) | N |  |  |  | - | - |
| 오류메시지내용 | ERR_MSG_CN | VARCHAR(4000) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 외부작업아이디 | OTSD_JOB_ID | VARCHAR(200) | N |  |  | KLID-AT-ID-021 | - | 동일 위탁 갱신 기준 |
| 비식별화프로젝트아이디 | DE_IDNTF_PJT_ID | BIGINT | N |  |  |  | - | - |
| 비식별화데이터셋아이디 | DE_IDNTF_DATST_ID | BIGINT | N |  |  |  | - | - |
| 폴링상태코드 | POLL_STTS_CD | VARCHAR(20) | N |  |  | KLID-AT-ID-021 | - | - |
| 최종폴링일시 | POLL_LAST_DT | TIMESTAMP | N |  |  |  | - | - |
| 폴링시도횟수 | POLL_ATMPT_CNT | INTEGER | N |  |  |  | 0 | - |
| 요청종류코드 | REQ_KND_CD | VARCHAR(20) | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DEIDENT_REPORT (KLID-AT-TB-022)
- 사용: [[KLID_AT_엔티티관계모형설계서#비식별누락신고 (KLID-AT-EN-022)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-022 — LS_DEIDENT_REPORT

| 테이블ID | KLID-AT-TB-022 | 테이블명 | LS_DEIDENT_REPORT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-022 (비식별누락신고) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 작업자가 접수한 비식별 누락 신고 테이블. 신고자·사유·신고 상태·신고 단계와 해소 일시를 보관하며 시스템 처리 이력은 비식별처리로그로 분리한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 5 | 영구 — 개인정보 사고 이력 | 5,000 | ≈ 2.5MB (5,000 × 500B) | 누락 신고 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 비식별신고일련번호 | DEIDENT_REPORT_SN | BIGINT | Y | Y |  | KLID-AT-ID-022 | IDENTITY 자동증가 | - |
| 원천영상식별자 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-022 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 신고자번호 | REPORTER_NO | BIGINT | N |  |  |  | - | - |
| 신고사유 | RSN | VARCHAR(1000) | N |  |  |  | - | - |
| 신고상태코드 | REPORT_STTS_CD | VARCHAR(16) | N |  |  | KLID-AT-ID-022 | - | - |
| 신고일시 | DCLR_DT | TIMESTAMP | N |  |  | KLID-AT-ID-022 | - | - |
| 해소일시 | RESOLVED_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 신고단계코드 | DCLR_STP_CD | VARCHAR(20) | N |  |  |  | - | 마킹 단계/라벨링 단계 구분 |

<!-- hwpx:ignore-start -->
### LS_RAW_DATA_ENROLLMENT (KLID-AT-TB-023)
- 사용: [[KLID_AT_엔티티관계모형설계서#영상적재등록 (KLID-AT-EN-023)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-023 — LS_RAW_DATA_ENROLLMENT

| 테이블ID | KLID-AT-TB-023 | 테이블명 | LS_RAW_DATA_ENROLLMENT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-023 (영상적재등록) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 적재 등록 매핑 테이블. 영상 1건당 1행으로 적재 등록 사실을 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 적재 등록 | 20,000 | ≈ 0.6MB (20,000 × 30B) | 영상 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 원본영상아이디 | RAW_DATA_ID | BIGINT | Y | Y | Y | KLID-AT-ID-023 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_RAW_DATA_STATUS (KLID-AT-TB-024)
- 사용: [[KLID_AT_엔티티관계모형설계서#영상진행상태 (KLID-AT-EN-024)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-024 — LS_RAW_DATA_STATUS

| 테이블ID | KLID-AT-TB-024 | 테이블명 | LS_RAW_DATA_STATUS |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-024 (영상진행상태) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상별 작업·검수 진행 상태 테이블. 영상 1건당 1행이며 워크플로우 상태 전이의 단일 출처다. 낙관적 잠금 컬럼으로 동시 승인 경합을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 작업 상태 | 20,000 | ≈ 1MB (20,000 × 50B) | 영상 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 원본영상아이디 | RAW_DATA_ID | BIGINT | Y | Y | Y | KLID-AT-ID-024 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 영상적재등록과 1:1 대응 |
| 데이터상태코드 | DATA_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-024 | 'PENDING' | - |
| 단계주기 | STP_CYCL | INTEGER | Y |  |  |  | 0 | - |
| 검수주기 | IGI_CYCL | INTEGER | Y |  |  |  | 0 | - |
| 수정일시 | UPD_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-024 | CURRENT_TIMESTAMP | - |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | 낙관적 잠금 |

<!-- hwpx:ignore-start -->
### LS_DATA_ISSUE (KLID-AT-TB-025)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터이슈 (KLID-AT-EN-025)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-025 — LS_DATA_ISSUE

| 테이블ID | KLID-AT-TB-025 | 테이블명 | LS_DATA_ISSUE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-025 (데이터이슈) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 단위 이슈 테이블. 검수 반려 이력과 작업자 문의를 통합 보관한다. 직전 반려 자기참조로 재반려 계층을 구성하고 낙관적 잠금 컬럼으로 충돌을 방어하며, 유형·상태는 검사 제약으로 허용값을 한정한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 20 | 영구 — 검수·문의 이력 | 20,000 | ≈ 6MB (20,000 × 300B) | 영상당 평균 1건 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 데이터이슈일련번호 | DATA_ISSUE_SN | BIGINT | Y | Y |  | KLID-AT-ID-025 | IDENTITY 자동증가 | - |
| 상위데이터이슈일련번호 | UP_DATA_ISSUE_SN | BIGINT | N |  | Y | KLID-AT-ID-025 | - | 논리 FK(→LS_DATA_ISSUE, 자기참조) |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-025 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 이슈사유내용 | ISSUE_RSN | VARCHAR(1000) | N |  |  |  | - | - |
| 작성자사용자번호 | REPORTED_USER_NO | VARCHAR(50) | N |  |  | KLID-AT-ID-025 | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 이슈유형코드 | ISSUE_TYPE_CD | VARCHAR(20) | Y |  |  |  | 'REJECTION' | CHECK(반려/문의) |
| 이슈상태코드 | ISSUE_STTS_CD | VARCHAR(20) | Y |  |  |  | 'OPEN' | CHECK(접수/답변/해소) |
| 프레임원천일련번호 | SRC_SN | BIGINT | N |  | Y | KLID-AT-ID-025 | - | 논리 FK(→LS_DATA_SRC) |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | 낙관적 잠금 |

<!-- hwpx:ignore-start -->
### LS_TASK_ASSIGNMENT (KLID-AT-TB-026)
- 사용: [[KLID_AT_엔티티관계모형설계서#작업배정 (KLID-AT-EN-026)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-026 — LS_TASK_ASSIGNMENT

| 테이블ID | KLID-AT-TB-026 | 테이블명 | LS_TASK_ASSIGNMENT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-026 (작업배정) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 단위 작업자·검수자 배정 테이블. (영상, 사용자, 작업유형) UNIQUE로 중복 배정을 차단하고 낙관적 잠금 컬럼을 보유한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 40 | 영구 — 배정 현황 | 40,000 | ≈ 4MB (40,000 × 100B) | 영상당 작업자·검수자 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 배정아이디 | ASSIGNMENT_ID | BIGINT | Y | Y |  | KLID-AT-ID-026 | IDENTITY 자동증가 | - |
| 배정대상사용자번호 | USER_NO | BIGINT | Y |  |  | KLID-AT-ID-026 | - | 복합 UNIQUE(RAW_DATA_ID,USER_NO,TASK_TYPE_CD) |
| 원본영상아이디 | RAW_DATA_ID | BIGINT | Y |  | Y | KLID-AT-ID-026 | - | 복합 UNIQUE(RAW_DATA_ID,USER_NO,TASK_TYPE_CD) · FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 작업유형코드 | TASK_TYPE_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-026 | - | 복합 UNIQUE(RAW_DATA_ID,USER_NO,TASK_TYPE_CD) |
| 등록자사용자번호 | REG_USER_NO | BIGINT | Y |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | 낙관적 잠금 |

<!-- hwpx:ignore-start -->
### LS_TASK_ASSIGN_HISTORY (KLID-AT-TB-027)
- 사용: [[KLID_AT_엔티티관계모형설계서#작업배정이력 (KLID-AT-EN-027)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-027 — LS_TASK_ASSIGN_HISTORY

| 테이블ID | KLID-AT-TB-027 | 테이블명 | LS_TASK_ASSIGN_HISTORY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-027 (작업배정이력) |
| 트리거 구성 | 없음 (애플리케이션 레벨 이력 적재) |
| 테이블 설명 | 재배정 이력 테이블. 작업자 변경 시 이전·신규 사용자와 변경자를 기록한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 10 | 영구 — 재배정 이력 | 10,000 | ≈ 1MB (10,000 × 100B) | 재배정 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이력식별자 | HSTRY_SEQ | BIGINT | Y | Y |  | KLID-AT-ID-027 | IDENTITY 자동증가 | - |
| 배정식별자 | AUTHRT_SEQ | BIGINT | Y |  | Y | KLID-AT-ID-027 | - | 논리 FK(→LS_TASK_ASSIGNMENT) |
| 원본영상아이디 | RAW_DATA_ID | BIGINT | Y |  | Y |  | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 이전사용자번호 | PREV_USER_NO | BIGINT | Y |  |  |  | - | - |
| 신규사용자번호 | NEW_USER_NO | BIGINT | Y |  |  |  | - | - |
| 작업유형코드 | TASK_TYPE_CD | VARCHAR(20) | Y |  |  |  | - | - |
| 변경자사용자번호 | CHG_USER_NO | BIGINT | Y |  |  |  | - | - |
| 변경일시 | CHG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_TASK_EVENT_LOG (KLID-AT-TB-028)
- 사용: [[KLID_AT_엔티티관계모형설계서#작업이벤트로그 (KLID-AT-EN-028)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-028 — LS_TASK_EVENT_LOG

| 테이블ID | KLID-AT-TB-028 | 테이블명 | LS_TASK_EVENT_LOG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-028 (작업이벤트로그) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 단위 작업 라이프사이클 이벤트 누적 로그 테이블. 배정·재배정·검수 제출·승인·반려를 단일 테이블에 시간순 누적해 통합 타임라인을 제공한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 100 | 영구 — 작업 타임라인 | 100,000 | ≈ 12MB (100,000 × 120B) | 영상당 평균 5이벤트 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이벤트아이디 | EVNT_ID | BIGINT | Y | Y |  | KLID-AT-ID-028 | IDENTITY 자동증가 | - |
| 원본영상아이디 | RAW_DATA_ID | BIGINT | Y |  | Y | KLID-AT-ID-028 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | Y |  |  |  | - | - |
| 행위자사용자번호 | ACTOR_USER_NO | BIGINT | Y |  |  | KLID-AT-ID-028 | - | - |
| 대상사용자번호 | SUBJECT_USER_NO | BIGINT | N |  |  |  | - | - |
| 이전사용자번호 | PREV_USER_NO | BIGINT | N |  |  |  | - | - |
| 사유 | RSN | VARCHAR(500) | N |  |  |  | - | - |
| 발생일시 | OCRN_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-028 | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_ISSUE_COMMENT (KLID-AT-TB-029)
- 사용: [[KLID_AT_엔티티관계모형설계서#이슈댓글 (KLID-AT-EN-029)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-029 — LS_ISSUE_COMMENT

| 테이블ID | KLID-AT-TB-029 | 테이블명 | LS_ISSUE_COMMENT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-029 (이슈댓글) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 이슈 스레드 양방향 댓글 테이블. 작성자 역할은 검사 제약으로 한정하며 검수자 댓글 시 문의 이슈가 자동 전이된다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 40 | 영구 — 스레드 이력 | 40,000 | ≈ 8MB (40,000 × 200B) | 이슈당 평균 2댓글 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 댓글일련번호 | CMNT_SN | BIGINT | Y | Y |  | KLID-AT-ID-029 | IDENTITY 자동증가 | - |
| 데이터이슈일련번호 | DATA_ISSUE_SN | BIGINT | Y |  | Y | KLID-AT-ID-029 | - | 논리 FK(→LS_DATA_ISSUE) |
| 작성자번호 | AUTHOR_NO | VARCHAR(50) | Y |  |  | KLID-AT-ID-029 | - | - |
| 작성자역할 | AUTHOR_ROLE_CD | VARCHAR(20) | Y |  |  |  | - | CHECK(라벨링작업자/검수자) |
| 댓글내용 | CMNT_CN | VARCHAR(4000) | Y |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG (KLID-AT-TB-030)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터증강 (KLID-AT-EN-030)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-030 — LS_DATA_AUG

| 테이블ID | KLID-AT-TB-030 | 테이블명 | LS_DATA_AUG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-030 (데이터증강) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 증강·해상도 파생 요청 1건을 보관하는 테이블. 증강 유형과 생성 결과 상태, 요청 시 생성 조건 원문, 생성된 파생영상 식별자를 보관한다. 위탁 멱등키·외부 작업 식별자로 비동기 인계 중복을 차단하며, 해상도 파생만 부분 UNIQUE로 중복 생성을 막는다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 20 | 영구 — 증강 산출 | 20,000 | ≈ 4MB (20,000 × 200B) | 영상당 외부 증강 3종 + 해상도 3종 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 데이터증강일련번호 | DATA_AUG_SN | BIGINT | Y | Y |  | KLID-AT-ID-030 | IDENTITY 자동증가 | - |
| 원천일련번호 | SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-030 | - | 논리 FK(→LS_DATA_RAW) |
| 증강유형코드 | AUG_TYPE_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-030 | - | 외부 증강 3종 + 해상도 파생 3종 · 해상도 파생만 (영상,유형) 부분 UNIQUE |
| 증강처리상태코드 | AUG_PROC_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-030 | 'PENDING' | 생성 결과 축(검수 결정 축과 분리) |
| 라벨무결성비율 | LBL_INTGRT_PCT | NUMERIC(5,2) | N |  |  |  | - | - |
| 결정사용자번호 | DCSN_USER_NO | VARCHAR(50) | N |  |  |  | - | - |
| 결정일시 | DCSN_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-030 | CURRENT_TIMESTAMP | - |
| 등록사용자번호 | REG_USER_NO | VARCHAR(50) | N |  |  |  | - | - |
| 멱등키 | IDMP_KEY | VARCHAR(128) | N |  |  | KLID-AT-ID-030 | - | UNIQUE · 중복 인계 차단 |
| 외부작업아이디 | OTSD_JOB_ID | VARCHAR(200) | N |  |  | KLID-AT-ID-030 | - | UNIQUE |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 영구실패시점 | DEAD_LETTER_AT | TIMESTAMP | N |  |  |  | - | - |
| 프롬프트내용 | PROMPT_CN | VARCHAR(4000) | N |  |  |  | - | - |
| 신규원시일련번호 | NEW_RAW_SN | BIGINT | N |  | Y | KLID-AT-ID-030 | - | 논리 FK(→LS_DATA_RAW, 파생영상) |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG_RVW (KLID-AT-TB-031)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터증강검수 (KLID-AT-EN-031)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-031 — LS_DATA_AUG_RVW

| 테이블ID | KLID-AT-TB-031 | 테이블명 | LS_DATA_AUG_RVW |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-031 (데이터증강검수) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 증강 결과의 사용·폐기 결정 테이블. 생성 결과 상태(증강 테이블)와 별개 축으로 검수 상태·검수자·반려 사유·라벨 무결성 비율을 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 20 | 영구 — 증강 검수 이력 | 20,000 | ≈ 6MB (20,000 × 300B) | 증강 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 데이터증강검수일련번호 | DATA_AUG_RVW_SN | BIGINT | Y | Y |  | KLID-AT-ID-031 | IDENTITY 자동증가 | - |
| 데이터증강일련번호 | DATA_AUG_SN | BIGINT | Y |  | Y | KLID-AT-ID-031 | - | 논리 FK(→LS_DATA_AUG) |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-031 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 데이터원천일련번호 | DATA_SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-031 | - | 논리 FK(→LS_DATA_SRC) |
| 검수상태코드 | RVW_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-031 | - | 검수 결정 축(생성 결과와 분리) |
| 라벨무결성비율 | LBL_INTGRT_PCT | NUMERIC(5,2) | N |  |  |  | - | - |
| 반려사유 | RJCT_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 검수아이디 | RVW_ID | VARCHAR(30) | N |  |  |  | - | - |
| 검수일시 | RVW_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG_LBL_MAP (KLID-AT-TB-032)
- 사용: [[KLID_AT_엔티티관계모형설계서#데이터증강라벨매핑 (KLID-AT-EN-032)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-032 — LS_DATA_AUG_LBL_MAP

| 테이블ID | KLID-AT-TB-032 | 테이블명 | LS_DATA_AUG_LBL_MAP |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-032 (데이터증강라벨매핑) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 증강·해상도 파생 시 원본 라벨과 결과 라벨의 대응 관계 및 좌표 배율 재계산 정보를 저장하는 테이블. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 600 | 영구 — 증강 라벨 매핑 | 200,000 | ≈ 24MB (200,000 × 120B) | 파생 라벨 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 데이터증강라벨매핑일련번호 | DATA_AUG_LBL_MAP_SN | BIGINT | Y | Y |  | KLID-AT-ID-032 | IDENTITY 자동증가 | - |
| 데이터증강일련번호 | DATA_AUG_SN | BIGINT | Y |  | Y | KLID-AT-ID-032 | - | 논리 FK(→LS_DATA_AUG) |
| 원본데이터라벨일련번호 | ORGNL_DATA_LBL_SN | BIGINT | N |  | Y | KLID-AT-ID-032 | - | 논리 FK(→LS_DATA_LBL, 원본 라벨) |
| 데이터라벨일련번호 | DATA_LBL_SN | BIGINT | Y |  | Y | KLID-AT-ID-032 | - | 논리 FK(→LS_DATA_LBL) |
| 좌표재계산여부 | COORD_RECALC_YN | CHAR(1) | Y |  |  |  | 'N' | - |
| X축스케일비율 | SCALE_X | NUMERIC(10,6) | N |  |  |  | - | - |
| Y축스케일비율 | SCALE_Y | NUMERIC(10,6) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG_DSCD (KLID-AT-TB-033)
- 사용: [[KLID_AT_엔티티관계모형설계서#증강폐기 (KLID-AT-EN-033)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-033 — LS_DATA_AUG_DSCD

| 테이블ID | KLID-AT-TB-033 | 테이블명 | LS_DATA_AUG_DSCD |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-033 (증강폐기) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 미사용 파생영상 폐기 원장 테이블. 폐기(논리 삭제)·복구·실삭제 단계와 파일 삭제 결과·재시도를 기록하며, 활성 폐기 표식은 부분 UNIQUE로 1건만 허용한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 5 | 영구 — 폐기 원장 | 20,000 | ≈ 12MB (20,000 × 600B) | 반려된 파생 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 증강폐기일련번호 | DATA_AUG_DSCD_SN | BIGINT | Y | Y |  | KLID-AT-ID-033 | IDENTITY 자동증가 | - |
| 데이터증강일련번호 | DATA_AUG_SN | BIGINT | Y |  | Y | KLID-AT-ID-033 | - | 논리 FK(→LS_DATA_AUG) · 활성 폐기 1건(부분 UNIQUE) |
| 신규원시일련번호 | NEW_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-033 | - | 논리 FK(→LS_DATA_RAW, 파생영상) |
| 원본원시일련번호 | ORGNL_RAW_SN | BIGINT | N |  | Y |  | - | 논리 FK(→LS_DATA_RAW, 원본영상) |
| 폐기일시 | DSCD_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-033 | - | - |
| 폐기사유 | DSCD_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 복구일시 | RSTR_DT | TIMESTAMP | N |  |  |  | - | - |
| 복구사유 | RSTR_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 삭제처리일시 | DEL_PRCS_DT | TIMESTAMP | N |  |  |  | - | - |
| 삭제일시 | DEL_DT | TIMESTAMP | N |  |  | KLID-AT-ID-033 | - | - |
| 파일삭제일시 | FILE_DEL_DT | TIMESTAMP | N |  |  |  | - | - |
| 동영상파일경로명 | VDO_FILE_PATH | VARCHAR(1000) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 증강유형코드 | AUG_TYPE_CD | VARCHAR(20) | N |  |  |  | - | - |
| 프롬프트내용 | PROMPT_CN | VARCHAR(4000) | N |  |  |  | - | - |
| 파일삭제재시도횟수 | FILE_DEL_RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 파일삭제실패일시 | FILE_DEL_FAIL_DT | TIMESTAMP | N |  |  |  | - | - |
| 파일삭제실패사유 | FILE_DEL_FAIL_RSN | VARCHAR(4000) | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG_JOB (KLID-AT-TB-034)
- 사용: [[KLID_AT_엔티티관계모형설계서#증강위탁작업 (KLID-AT-EN-034)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-034 — LS_DATA_AUG_JOB

| 테이블ID | KLID-AT-TB-034 | 테이블명 | LS_DATA_AUG_JOB |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-034 (증강위탁작업) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 외부 증강 서비스 위탁 단위 테이블. 요청 1건이 여러 위탁으로 분할될 때 순번·멱등키·외부 작업 식별자·진행 상태를 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 40 | 영구 — 위탁 원장 | 60,000 | ≈ 12MB (60,000 × 200B) | 요청당 평균 3위탁 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 증강작업일련번호 | AUG_JOB_SN | BIGINT | Y | Y |  | KLID-AT-ID-034 | IDENTITY 자동증가 | - |
| 데이터증강일련번호 | DATA_AUG_SN | BIGINT | Y |  | Y | KLID-AT-ID-034 | - | FK 제약(→LS_DATA_AUG, 삭제 연쇄) |
| 작업순번 | JOB_SEQ | INTEGER | Y |  |  | KLID-AT-ID-034 | - | - |
| 멱등키 | IDMP_KEY | VARCHAR(128) | Y |  |  | KLID-AT-ID-034 | - | UNIQUE |
| 외부작업아이디 | OTSD_JOB_ID | VARCHAR(200) | N |  |  | KLID-AT-ID-034 | - | - |
| 작업상태코드 | JOB_STTS_CD | VARCHAR(20) | Y |  |  |  | - | - |
| 전체건수 | TOT_NOCS | INTEGER | Y |  |  |  | 0 | - |
| 오류코드 | ERR_CD | VARCHAR(50) | N |  |  |  | - | - |
| 오류메시지내용 | ERR_MSG_CN | VARCHAR(1000) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATA_AUG_JOB_FILE (KLID-AT-TB-035)
- 사용: [[KLID_AT_엔티티관계모형설계서#증강위탁작업파일 (KLID-AT-EN-035)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-035 — LS_DATA_AUG_JOB_FILE

| 테이블ID | KLID-AT-TB-035 | 테이블명 | LS_DATA_AUG_JOB_FILE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-035 (증강위탁작업파일) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 위탁에 실어 보낸 입력 프레임과 되돌아온 결과 파일의 순번 대응 테이블. (위탁, 파일순번) UNIQUE로 결과 파일 매칭을 보장한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 400 | 영구 — 위탁 파일 대응 | 400,000 | ≈ 40MB (400,000 × 100B) | 위탁당 평균 10프레임 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 증강작업파일일련번호 | AUG_JOB_FILE_SN | BIGINT | Y | Y |  | KLID-AT-ID-035 | IDENTITY 자동증가 | - |
| 증강작업일련번호 | AUG_JOB_SN | BIGINT | Y |  | Y | KLID-AT-ID-035 | - | 복합 UNIQUE(AUG_JOB_SN,FILE_SEQ) · FK 제약(→LS_DATA_AUG_JOB, 삭제 연쇄) |
| 파일순번 | FILE_SEQ | INTEGER | Y |  |  | KLID-AT-ID-035 | - | 복합 UNIQUE(AUG_JOB_SN,FILE_SEQ) |
| 원천일련번호 | SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-035 | - | 논리 FK(→LS_DATA_SRC) |
| 결과파일경로명 | RSLT_FILE_PATH_NM | VARCHAR(500) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_EVNT_TYPE (KLID-AT-TB-036)
- 사용: [[KLID_AT_엔티티관계모형설계서#이벤트유형마스터 (KLID-AT-EN-036)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-036 — LS_EVNT_TYPE

| 테이블ID | KLID-AT-TB-036 | 테이블명 | LS_EVNT_TYPE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-036 (이벤트유형마스터) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 영상 이벤트 유형의 단일 진실원 마스터 테이블. 관제 수신 명칭과 운영자 표시 명칭을 분리 보관하고 수집 여부로 노출을 제어한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 100 | 0 | 영구 — 코드성 마스터 | 1,000 | ≈ 0.1MB (1,000 × 120B) | 관제 수신 코드 기준 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | Y | Y |  | KLID-AT-ID-036 | - | 형식 강제 없음(비규격 코드 허용) |
| 이벤트유형명 | EVNT_NM | VARCHAR(200) | N |  |  |  | - | - |
| 운영자표시명 | OPTR_INDCT_NM | VARCHAR(200) | N |  |  |  | - | - |
| 이벤트분류코드 | EVNT_CLSF_CD | VARCHAR(20) | N |  | Y |  | - | 논리 FK(→LS_EVNT_CTGRY) |
| 이벤트카테고리코드 | EVNT_CTGRY_CD | VARCHAR(20) | N |  | Y |  | - | 논리 FK(→LS_EVNT_CTGRY) |
| 수집여부 | CLCT_YN | CHAR(1) | Y |  |  |  | 'Y' | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_EVNT_CTGRY (KLID-AT-TB-037)
- 사용: [[KLID_AT_엔티티관계모형설계서#이벤트카테고리마스터 (KLID-AT-EN-037)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-037 — LS_EVNT_CTGRY

| 테이블ID | KLID-AT-TB-037 | 테이블명 | LS_EVNT_CTGRY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-037 (이벤트카테고리마스터) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 이벤트 분류·카테고리 마스터 테이블. 유형에 고유 명칭이 없을 때 표시명 폴백의 근거가 된다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 30 | 0 | 영구 — 코드성 마스터 | 200 | ≈ 0.02MB (200 × 100B) | 분류·카테고리 복합 키 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 이벤트분류코드 | EVNT_CLSF_CD | VARCHAR(20) | Y | Y |  | KLID-AT-ID-037 | - | 분류·카테고리 복합 PK |
| 이벤트카테고리코드 | EVNT_CTGRY_CD | VARCHAR(20) | Y | Y |  | KLID-AT-ID-037 | - | 분류·카테고리 복합 PK |
| 이벤트카테고리명 | EVNT_CTGRY_NM | VARCHAR(200) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_DATASET_EXPORT (KLID-AT-TB-038)
- 사용: [[KLID_AT_엔티티관계모형설계서#학습데이터산출 (KLID-AT-EN-038)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-038 — LS_DATASET_EXPORT

| 테이블ID | KLID-AT-TB-038 | 테이블명 | LS_DATASET_EXPORT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-038 (학습데이터산출) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 검수 승인 영상의 학습데이터 산출 원장 테이블. 버전별 산출 경로·상태·프레임 수·산출 용량과 내용 해시를 보관하며 (영상, 산출버전) UNIQUE로 버전을 유일화한다. 전 버전을 보존하고 정리하지 않는다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 전 버전 보존 | 40,000 | ≈ 8MB (40,000 × 200B) | 영상당 재승인 시 버전 적층 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 산출일련번호 | OUTPUT_SN | BIGINT | Y | Y |  | KLID-AT-ID-038 | IDENTITY 자동증가 | - |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-038 | - | 복합 UNIQUE(DATA_RAW_SN,OUTPUT_VER_NO) · FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 산출버전번호 | OUTPUT_VER_NO | INTEGER | Y |  |  | KLID-AT-ID-038 | - | 복합 UNIQUE(DATA_RAW_SN,OUTPUT_VER_NO) |
| 산출경로명 | OUTPUT_PATH_NM | VARCHAR(500) | N |  |  |  | - | - |
| 산출상태코드 | OUTPUT_STTS_CD | VARCHAR(20) | Y |  |  |  | - | - |
| 프레임수 | FRME_CNT | INTEGER | N |  |  |  | - | 2026-08-13 정정 — **실제 프레임 수(N)**. 영상 유형(일반/파생)과 무관하게 항상 N(구 결함: 원본 벌+비식별 벌 산출 이미지 개수 합계 2N) |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 내용해시 | CONTENT_HASH | VARCHAR(64) | N |  |  |  | - | - |
| 재시도횟수 | RTY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 재시도일시 | RTY_DT | TIMESTAMP | N |  |  |  | - | - |
| 데이터구축용량 | DATA_ETBL_CPCT | BIGINT | N |  |  |  | - | 산출 폴더 총 바이트(원본·비식별 2벌 합산 — 정정 대상 아님) |

<!-- hwpx:ignore-start -->
### LS_DATASET_VIDEO_META (KLID-AT-TB-039)
- 사용: [[KLID_AT_엔티티관계모형설계서#학습데이터영상메타 (KLID-AT-EN-039)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-039 — LS_DATASET_VIDEO_META

| 테이블ID | KLID-AT-TB-039 | 테이블명 | LS_DATASET_VIDEO_META |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-039 (학습데이터영상메타) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 검수 승인 시점에 동결한 영상 메타 스냅샷 테이블. 영상 1건당 활성 스냅샷 1건을 부분 UNIQUE로 유지하고 재승인 시 새 스냅샷을 덧붙인다. 데이터마트 적재용 조회 뷰의 기준 데이터다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 동결 스냅샷 | 40,000 | ≈ 32MB (40,000 × 800B) | 영상당 승인 회차만큼 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 메타스냅샷일련번호 | META_SNPSHT_SN | BIGINT | Y | Y |  | KLID-AT-ID-039 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-039 | - | 복합 UNIQUE(RAW_SN,SNPSHT_HASH) · FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 스냅샷해시 | SNPSHT_HASH | VARCHAR(64) | Y |  |  | KLID-AT-ID-039 | - | 복합 UNIQUE(RAW_SN,SNPSHT_HASH) |
| 활성여부 | ACTIVE_YN | CHAR(1) | Y |  |  | KLID-AT-ID-039 | 'Y' | 영상당 활성 스냅샷 1건(부분 UNIQUE) |
| 원본원시영상일련번호 | ORGNL_RAW_SN | BIGINT | N |  | Y |  | - | 논리 FK(→LS_DATA_RAW, 원본영상) |
| VMS클립아이디 | VMS_CLIP_ID | VARCHAR(128) | N |  |  |  | - | - |
| VMS_CCTV아이디 | VMS_CCTV_ID | VARCHAR(64) | N |  |  |  | - | - |
| 원시파일경로명 | RAW_FILE_PATH_NM | VARCHAR(500) | N |  |  |  | - | - |
| 촬영일시 | SHT_DT | TIMESTAMP | N |  |  |  | - | - |
| 영상길이초 | VDO_LEN_SEC | INTEGER | N |  |  |  | - | - |
| 지방자치단체코드 | LCLGV_CD | VARCHAR(20) | N |  |  |  | - | - |
| 개인정보포함여부 | PRVC_YN | CHAR(1) | N |  |  |  | - | - |
| 개인정보유형코드 | PRVC_TYPE_CD | VARCHAR(16) | N |  |  |  | - | - |
| 비식별여부 | DE_IDENT_YN | CHAR(1) | N |  |  |  | - | - |
| 인공지능생성여부 | AI_CRT_YN | CHAR(1) | N |  |  |  | - | - |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | N |  |  |  | - | - |
| CCTV명 | CCTV_NM | VARCHAR(255) | N |  |  |  | - | - |
| WGS84위도 | WGS84_LAT | NUMERIC(10,7) | N |  |  |  | - | - |
| WGS84경도 | WGS84_LOT | NUMERIC(10,7) | N |  |  |  | - | - |
| 시도명 | SIDO_NM | VARCHAR(100) | N |  |  |  | - | - |
| 시군구명 | SGG_NM | VARCHAR(100) | N |  |  |  | - | - |
| 파일형식 | FILE_FMT | VARCHAR(32) | N |  |  |  | - | - |
| 이벤트명 | EVNT_NM | VARCHAR(255) | N |  |  |  | - | - |
| 영상코덱 | VDO_CDC | VARCHAR(20) | N |  |  |  | - | - |
| 프레임재생속도 | FPS | NUMERIC | N |  |  |  | - | - |
| 비트율 | BIT_RT | BIGINT | N |  |  |  | - | - |
| 종횡비 | ASPRT_RT | NUMERIC | N |  |  |  | - | - |
| 해상도 | RESL | VARCHAR(32) | N |  |  |  | - | - |
| 영상너비 | VDO_WDTH | INTEGER | N |  |  |  | - | - |
| 영상높이 | VDO_HGT | INTEGER | N |  |  |  | - | - |
| 파일크기 | FILE_SZ | BIGINT | N |  |  |  | - | - |
| 주야구분코드 | DAY_NGT_CD | VARCHAR(8) | N |  |  |  | - | - |
| 계절코드 | SESN_CD | VARCHAR(20) | N |  |  |  | - | - |
| 날씨명 | WTHR_NM | VARCHAR(32) | N |  |  |  | - | - |
| 검수완료일시 | RVW_CMPL_DT | TIMESTAMP | N |  |  | KLID-AT-ID-039 | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 이벤트어노테이션내용 | EVNT_ANNO_CN | JSONB | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_META_REPL_OUTBOX (KLID-AT-TB-040)
- 사용: [[KLID_AT_엔티티관계모형설계서#메타복제발신함 (KLID-AT-EN-040)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-040 — LS_META_REPL_OUTBOX

| 테이블ID | KLID-AT-TB-040 | 테이블명 | LS_META_REPL_OUTBOX |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-040 (메타복제발신함) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 동결된 영상 메타를 포털로 단방향 복제하기 위한 발신 대기 원장 테이블. 처리 상태·재시도 횟수로 미발신분을 회수한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 90 — 발신 완료분 90일 정리 | 10,000 | ≈ 10MB (10,000 × 1,000B) | 승인 영상 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 발신함일련번호 | OUTBOX_SN | BIGINT | Y | Y |  | KLID-AT-ID-040 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y |  | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 스냅샷해시 | SNPSHT_HASH | VARCHAR(64) | Y |  |  |  | - | - |
| 페이로드내용 | PAYLOAD_CN | TEXT | N |  |  |  | - | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-040 | 'PENDING' | - |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-040 | CURRENT_TIMESTAMP | - |
| 처리일시 | PRCS_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_CONTROL_NOTIFY_FALLBACK (KLID-AT-TB-041)
- 사용: [[KLID_AT_엔티티관계모형설계서#관제통지대체큐 (KLID-AT-EN-041)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-041 — LS_CONTROL_NOTIFY_FALLBACK

| 테이블ID | KLID-AT-TB-041 | 테이블명 | LS_CONTROL_NOTIFY_FALLBACK |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-041 (관제통지대체큐) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 검수 완료·검수 후 수정 단방향 통지의 영속 대기열 테이블. 전송 실패 시 지연 재시도하고 최대 횟수 초과 시 사장큐로 격리하며 멱등키 UNIQUE로 중복 통지를 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 60 | 영구 — 통지 원장 | 40,000 | ≈ 8MB (40,000 × 200B) | 영상당 평균 2통지 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 큐일련번호 | QUEUE_SN | BIGINT | Y | Y |  | KLID-AT-ID-041 | IDENTITY 자동증가 | - |
| 멱등키 | IDMP_KEY | VARCHAR(128) | Y |  |  | KLID-AT-ID-041 | - | UNIQUE · 중복 통지 차단 |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | Y |  |  |  | - | 검수완료/검수후수정 |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y |  | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 페이로드내용 | PAYLOAD_CN | TEXT | Y |  |  |  | - | - |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 최대재시도횟수 | MAX_RTRY_NMTM | INTEGER | Y |  |  |  | 5 | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-041 | 'PENDING' | - |
| 마지막오류메시지내용 | LAST_ERR_MSG_CN | VARCHAR(2000) | N |  |  |  | - | - |
| 다음재시도일시 | NEXT_RTRY_DT | TIMESTAMP | N |  |  | KLID-AT-ID-041 | - | - |
| 사장큐진입일시 | DLQ_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 발송결과코드 | SEND_RSLT_CD | VARCHAR(16) | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_WEBHOOK_IDEMPOTENCY (KLID-AT-TB-042)
- 사용: [[KLID_AT_엔티티관계모형설계서#웹훅멱등원장 (KLID-AT-EN-042)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-042 — LS_WEBHOOK_IDEMPOTENCY

| 테이블ID | KLID-AT-TB-042 | 테이블명 | LS_WEBHOOK_IDEMPOTENCY |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-042 (웹훅멱등원장) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 외부 위탁(비식별·시계열 메타·증강) 시 발급한 멱등키 원장 테이블. 재기동·다중 노드 환경에서 결과 중복 수신·중복 적재를 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 60 | 영구 — 멱등 원장 | 40,000 | ≈ 8MB (40,000 × 200B) | 위탁 호출 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 멱등키 | IDMP_KEY | VARCHAR(128) | Y | Y |  | KLID-AT-ID-042 | - | - |
| 채널코드 | CHNL_CD | VARCHAR(32) | Y |  |  | KLID-AT-ID-042 | - | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-042 | - | - |
| 외부작업아이디 | OTSD_JOB_ID | VARCHAR(200) | N |  |  | KLID-AT-ID-042 | - | - |
| 적용일시 | APLY_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 원시영상일련번호 | RAW_SN | BIGINT | N |  | Y |  | - | FK 제약(→LS_DATA_RAW, 삭제 시 NULL) |

<!-- hwpx:ignore-start -->
### LS_MON_NOTI_ACML (KLID-AT-TB-043)
- 사용: [[KLID_AT_엔티티관계모형설계서#통지누적 (KLID-AT-EN-043)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-043 — LS_MON_NOTI_ACML

| 테이블ID | KLID-AT-TB-043 | 테이블명 | LS_MON_NOTI_ACML |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-043 (통지누적) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 검수 후 수정 통지의 누적 창 테이블. 영상 단위로 변경 내역을 모았다가 1회로 합쳐 발송하며, 열린 창은 영상당 1건만 부분 UNIQUE로 허용한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 영구 — 통지 누적 이력 | 40,000 | ≈ 8MB (40,000 × 200B) | 영상당 수정 회차만큼 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 통지누적일련번호 | NOTI_ACML_SN | BIGINT | Y | Y |  | KLID-AT-ID-043 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-043 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 영상당 열린 누적 창 1건(부분 UNIQUE) |
| 상태코드 | STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-043 | 'PENDING' | - |
| 산출재처리여부 | EXPORT_RPRCS_YN | CHAR(1) | Y |  |  |  | 'N' | - |
| 변경상세내용 | CHG_DTL_CN | TEXT | Y |  |  |  | '{}' | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-043 | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-043 | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_WHK_FAIL_NMTM (KLID-AT-TB-044)
- 사용: [[KLID_AT_엔티티관계모형설계서#웹훅인증실패횟수 (KLID-AT-EN-044)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-044 — LS_WHK_FAIL_NMTM

| 테이블ID | KLID-AT-TB-044 | 테이블명 | LS_WHK_FAIL_NMTM |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-044 (웹훅인증실패횟수) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 결과 수신 경계의 인증 실패 횟수 집계 테이블. 호출 주소·시간 구간 단위로 임계 초과 호출을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 5 | 30 — 만료 경과분 정리 | 5,000 | ≈ 0.5MB (5,000 × 100B) | 호출 주소·시간 구간 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 호출IP주소 | CALL_IP_ADDR | VARCHAR(45) | Y | Y |  | KLID-AT-ID-044 | - | 호출주소·시작일시 복합 PK |
| 시작일시 | BGNG_DT | TIMESTAMP | Y | Y |  | KLID-AT-ID-044 | - | 호출주소·시작일시 복합 PK |
| 실패횟수 | FAIL_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 만료일시 | EXPD_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-044 | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_WHK_SIGN_USE (KLID-AT-TB-045)
- 사용: [[KLID_AT_엔티티관계모형설계서#웹훅서명사용 (KLID-AT-EN-045)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-045 — LS_WHK_SIGN_USE

| 테이블ID | KLID-AT-TB-045 | 테이블명 | LS_WHK_SIGN_USE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-045 (웹훅서명사용) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 결과 수신 서명의 1회 사용 원장 테이블. 동일 서명 재전송(재생 공격)을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 60 | 30 — 만료 경과분 정리 | 10,000 | ≈ 1MB (10,000 × 100B) | 수신 호출 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 서명해시 | SIGN_HASH | VARCHAR(64) | Y | Y |  | KLID-AT-ID-045 | - | - |
| 웹훅경로명 | WHK_PATH_NM | VARCHAR(200) | Y |  |  |  | - | - |
| 만료일시 | EXPD_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-045 | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_BATCH_PROC_LOG (KLID-AT-TB-046)
- 사용: [[KLID_AT_엔티티관계모형설계서#배치처리이력 (KLID-AT-EN-046)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-046 — LS_BATCH_PROC_LOG

| 테이블ID | KLID-AT-TB-046 | 테이블명 | LS_BATCH_PROC_LOG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-046 (배치처리이력) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 배치 파이프라인 단계별 처리 이력 테이블. 영상·프레임 단위로 단계·상태·재시도·오류와 외부 호출 요청·응답 내용을 적재하는 운영 로그다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 200 | 365 — 운영 로그 1년 | 100,000 | ≈ 40MB (100,000 × 400B) | 영상당 평균 7단계 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 배치처리이력일련번호 | BATCH_PROC_LOG_SN | BIGINT | Y | Y |  | KLID-AT-ID-046 | IDENTITY 자동증가 | - |
| 작업아이디 | JOB_ID | VARCHAR(64) | Y |  |  | KLID-AT-ID-046 | - | - |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | N |  | Y | KLID-AT-ID-046 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 소스데이터일련번호 | DATA_SRC_SN | BIGINT | N |  | Y | KLID-AT-ID-046 | - | 논리 FK(→LS_DATA_SRC) |
| 처리단계코드 | PROC_STEP_CD | VARCHAR(30) | Y |  |  | KLID-AT-ID-046 | - | - |
| 처리상태코드 | PROC_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-046 | - | - |
| 시작일시 | BGNG_DT | TIMESTAMP | N |  |  |  | - | - |
| 종료일시 | END_DT | TIMESTAMP | N |  |  |  | - | - |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 오류코드 | ERR_CD | VARCHAR(50) | N |  |  |  | - | - |
| 오류메시지내용 | ERR_MSG_CN | VARCHAR(4000) | N |  |  |  | - | - |
| 요청페이로드내용 | REQ_PAYLOAD_CN | TEXT | N |  |  |  | - | - |
| 응답페이로드내용 | RESP_PAYLOAD_CN | TEXT | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_AUTH_WORK_LOCK (KLID-AT-TB-047)
- 사용: [[KLID_AT_엔티티관계모형설계서#저작도구작업잠금 (KLID-AT-EN-047)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-047 — LS_AUTH_WORK_LOCK

| 테이블ID | KLID-AT-TB-047 | 테이블명 | LS_AUTH_WORK_LOCK |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-047 (저작도구작업잠금) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 재비식별 등 작업 수행 동안 영상·프레임을 잠그는 잠금 원장 테이블. 잠금 식별자 UNIQUE로 중복 잠금을 차단하고 만료 시각으로 자동 회수한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 5 | 영구 — 잠금 이력 | 5,000 | ≈ 1.5MB (5,000 × 300B) | 잠금 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 작업잠금일련번호 | WORK_LOCK_SN | BIGINT | Y | Y |  | KLID-AT-ID-047 | IDENTITY 자동증가 | - |
| 잠금대상코드 | LCK_TARGET_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-047 | - | - |
| 데이터원시일련번호 | DATA_RAW_SN | BIGINT | N |  | Y | KLID-AT-ID-047 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 영상 활성 잠금 1건(부분 UNIQUE) |
| 소스데이터일련번호 | DATA_SRC_SN | BIGINT | N |  | Y | KLID-AT-ID-047 | - | 논리 FK(→LS_DATA_SRC) |
| 잠금상태코드 | LCK_STTS_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-047 | - | - |
| 잠금아이디 | LCK_ID | VARCHAR(64) | Y |  |  | KLID-AT-ID-047 | - | UNIQUE |
| 잠금소유자아이디 | LOCK_OWNER_ID | VARCHAR(30) | N |  |  |  | - | - |
| 잠금일시 | LCK_DT | TIMESTAMP | Y |  |  |  | - | - |
| 만료일시 | EXPRY_DT | TIMESTAMP | N |  |  | KLID-AT-ID-047 | - | - |
| 해제일시 | RMV_DT | TIMESTAMP | N |  |  |  | - | - |
| 해제사유 | RMV_RSN | VARCHAR(4000) | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정아이디 | MDFCN_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_DEADLINE (KLID-AT-TB-048)
- 사용: [[KLID_AT_엔티티관계모형설계서#마감정의 (KLID-AT-EN-048)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-048 — LS_DEADLINE

| 테이블ID | KLID-AT-TB-048 | 테이블명 | LS_DEADLINE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-048 (마감정의) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 개인정보 유형(익명·가명·개인정보) 포함 여부별 작업 마감 일시를 보관하는 전역 운영 정책 테이블. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 0 | 영구 — 운영 정책 | 100 | ≈ 0.01MB (100 × 60B) | 정책 데이터 소량 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 마감일련번호 | DDLN_SEQ | BIGINT | Y | Y |  | KLID-AT-ID-048 | - | - |
| 마감일시 | DDLN_DT | TIMESTAMP | N |  |  |  | - | - |
| 익명포함여부 | ANONY_INCL_YN | CHAR(1) | Y |  |  |  | 'N' | - |
| 가명포함여부 | PSDO_INCL_YN | CHAR(1) | Y |  |  |  | 'N' | - |
| 개인정보포함여부 | PRVC_INCL_YN | CHAR(1) | Y |  |  |  | 'N' | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_CLIP_SCHEDULE_QUE (KLID-AT-TB-049)
- 사용: [[KLID_AT_엔티티관계모형설계서#클립스케줄큐 (KLID-AT-EN-049)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-049 — LS_CLIP_SCHEDULE_QUE

| 테이블ID | KLID-AT-TB-049 | 테이블명 | LS_CLIP_SCHEDULE_QUE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-049 (클립스케줄큐) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 라벨링 배치 작업 대기열 테이블. 영상 단위 작업 유형과 처리 상태·재시도 횟수·오류 메시지를 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 30 | 365 — 운영 큐 1년 | 30,000 | ≈ 3MB (30,000 × 100B) | 영상당 평균 1.5건 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 큐일련번호 | QUE_SN | BIGINT | Y | Y |  | KLID-AT-ID-049 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-049 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 작업유형코드 | JOB_TYPE_CD | VARCHAR(20) | Y |  |  | KLID-AT-ID-049 | - | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-049 | 'PENDING' | - |
| 재시도횟수 | RTRY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 시작일시 | BGNG_DT | TIMESTAMP | N |  |  |  | - | - |
| 완료일시 | CMPTN_DT | TIMESTAMP | N |  |  |  | - | - |
| 마지막오류메시지내용 | LAST_ERR_MSG_CN | VARCHAR(2000) | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_BAT_RTY_WTNG (KLID-AT-TB-050)
- 사용: [[KLID_AT_엔티티관계모형설계서#배치재시도대기 (KLID-AT-EN-050)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-050 — LS_BAT_RTY_WTNG

| 테이블ID | KLID-AT-TB-050 | 테이블명 | LS_BAT_RTY_WTNG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-050 (배치재시도대기) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 배치 실패 영상의 재시도 대기 원장 테이블. 영상당 1건 UNIQUE이며 재시도 예정 시각과 최대 횟수 초과 여부를 관리한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 3 | 영구 — 재시도 원장 | 20,000 | ≈ 2MB (20,000 × 100B) | 영상 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 배치재시도일련번호 | BAT_RTY_SN | BIGINT | Y | Y |  | KLID-AT-ID-050 | IDENTITY 자동증가 | - |
| 원시영상일련번호 | RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-050 | - | UNIQUE · FK 제약(→LS_DATA_RAW, 삭제 연쇄) · 영상당 1건 |
| 재시도횟수 | RTY_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 최대재시도횟수 | MAX_RTY_NMTM | INTEGER | Y |  |  |  | 3 | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-050 | 'PENDING' | - |
| 재시도예정일시 | RTY_PRNMNT_DT | TIMESTAMP | N |  |  | KLID-AT-ID-050 | - | - |
| 마지막오류메시지내용 | LAST_ERR_MSG_CN | VARCHAR(2000) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_TUS_UPLOAD (KLID-AT-TB-051)
- 사용: [[KLID_AT_엔티티관계모형설계서#대용량업로드세션 (KLID-AT-EN-051)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-051 — LS_TUS_UPLOAD

| 테이블ID | KLID-AT-TB-051 | 테이블명 | LS_TUS_UPLOAD |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-051 (대용량업로드세션) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 관리 화면 대용량 영상의 재개 가능 업로드 세션 테이블. 진행 오프셋·만료 시각과 적재 결과 영상 식별자를 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 10 | 30 — 만료 세션 정리 | 1,000 | ≈ 0.4MB (1,000 × 400B) | 만료분 주기 정리 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 업로드아이디 | ULD_ID | UUID | Y | Y |  | KLID-AT-ID-051 | - | - |
| 사용자번호 | USER_NO | VARCHAR(64) | Y |  |  | KLID-AT-ID-051 | - | - |
| 업로드길이 | ULD_LEN | BIGINT | Y |  |  |  | - | - |
| 업로드오프셋 | ULD_OFFSET | BIGINT | Y |  |  |  | 0 | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-051 | 'IN_PROGRESS' | - |
| 파일경로 | FILE_PATH | VARCHAR(500) | Y |  |  |  | - | - |
| 파일명 | FILE_NM | VARCHAR(255) | N |  |  |  | - | - |
| VMS클립아이디 | VMS_CLIP_ID | VARCHAR(128) | N |  |  |  | - | - |
| CCTV아이디 | CCTV_ID | VARCHAR(64) | N |  |  |  | - | - |
| 이벤트유형코드 | EVNT_TYPE_CD | VARCHAR(20) | N |  |  |  | - | - |
| 지방자치단체코드 | LCLGV_CD | VARCHAR(20) | N |  |  |  | - | - |
| 개인정보유형코드 | PRVC_TYPE_CD | VARCHAR(8) | N |  |  |  | - | - |
| 촬영일시 | SHT_DT | TIMESTAMP | N |  |  |  | - | - |
| 원시영상일련번호 | RAW_SN | BIGINT | N |  | Y |  | - | FK 제약(→LS_DATA_RAW, 삭제 시 NULL) |
| 만료일시 | EXPRY_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-051 | - | - |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_PORTAL_USER_LABEL (KLID-AT-TB-052)
- 사용: [[KLID_AT_엔티티관계모형설계서#포털사용자작업라벨 (KLID-AT-EN-052)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-052 — LS_PORTAL_USER_LABEL

| 테이블ID | KLID-AT-TB-052 | 테이블명 | LS_PORTAL_USER_LABEL |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-052 (포털사용자작업라벨) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 포털 사용자의 라벨 작업 데이터 테이블. 데이터마트 영상을 선택해 기존 라벨을 수정·저장하되 원본을 수정하지 않고 사용자별로 분리 적재하며, 포털 사용자 번호로 본인 데이터만 접근한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 500 | 영구 — 포털 작업 데이터 | 200,000 | ≈ 30MB (200,000 × 150B) | 포털 사용자 라벨 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 사용자라벨일련번호 | USER_LBL_SN | BIGINT | Y | Y |  | KLID-AT-ID-052 | IDENTITY 자동증가 | - |
| 포털사용자번호 | PORTAL_USER_NO | VARCHAR(100) | Y |  |  | KLID-AT-ID-052 | - | 본인 데이터 접근 제한 키 |
| 원천영상일련번호 | SRC_RAW_SN | BIGINT | Y |  | Y | KLID-AT-ID-052 | - | FK 제약(→LS_DATA_RAW, 삭제 연쇄) |
| 원천프레임일련번호 | SRC_DATA_SRC_SN | BIGINT | Y |  | Y | KLID-AT-ID-052 | - | 논리 FK(→LS_DATA_SRC) |
| 라벨유형코드 | LBL_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 라벨명 | LBL_NM | VARCHAR(80) | N |  |  |  | - | - |
| 좌표내용 | POINT_CN | TEXT | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_PORTAL_ULD (KLID-AT-TB-053)
- 사용: [[KLID_AT_엔티티관계모형설계서#포털업로드자산 (KLID-AT-EN-053)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-053 — LS_PORTAL_ULD

| 테이블ID | KLID-AT-TB-053 | 테이블명 | LS_PORTAL_ULD |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-053 (포털업로드자산) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 포털 사용자가 직접 업로드한 이미지·영상 자산과 처리 상태 테이블. 내부 파이프라인·데이터마트와 분리된 포털 전용 저장 경로다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 50 | 영구 — 포털 자산 | 50,000 | ≈ 15MB (50,000 × 300B) | 본인 자산 업로드 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 업로드일련번호 | ULD_SN | BIGINT | Y | Y |  | KLID-AT-ID-053 | IDENTITY 자동증가 | - |
| 포털사용자번호 | PORTAL_USER_NO | VARCHAR(100) | Y |  |  | KLID-AT-ID-053 | - | 본인 데이터 접근 제한 키 |
| 업로드유형코드 | ULD_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 원본파일명 | ORGNL_FILE_NM | VARCHAR(255) | N |  |  |  | - | - |
| 파일경로명 | FILE_PATH_NM | VARCHAR(500) | N |  |  |  | - | - |
| 파일크기 | FILE_SZ | BIGINT | N |  |  |  | - | - |
| 매체유형명 | MIME_TYPE_NM | VARCHAR(100) | N |  |  |  | - | - |
| 업로드상태코드 | ULD_STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-053 | - | - |
| 영상길이초 | VDO_LEN_SEC | DOUBLE PRECISION | N |  |  |  | - | - |
| 프레임재생속도 | FPS | DOUBLE PRECISION | N |  |  |  | - | - |
| 프레임수 | FRME_CNT | INTEGER | N |  |  |  | - | - |
| 실패사유내용 | FAIL_RSN_CN | TEXT | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-053 | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-053 | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_PORTAL_ULD_FRME (KLID-AT-TB-054)
- 사용: [[KLID_AT_엔티티관계모형설계서#포털업로드프레임 (KLID-AT-EN-054)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-054 — LS_PORTAL_ULD_FRME

| 테이블ID | KLID-AT-TB-054 | 테이블명 | LS_PORTAL_ULD_FRME |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-054 (포털업로드프레임) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 업로드 영상에서 고정 간격으로 추출한 프레임 테이블. (업로드, 프레임번호) UNIQUE로 중복 추출을 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 500 | 영구 — 포털 프레임 | 500,000 | ≈ 50MB (500,000 × 100B) | 영상당 최대 2,000프레임 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 업로드프레임일련번호 | ULD_FRME_SN | BIGINT | Y | Y |  | KLID-AT-ID-054 | 시퀀스 자동증가 | - |
| 업로드일련번호 | ULD_SN | BIGINT | Y |  | Y | KLID-AT-ID-054 | - | 복합 UNIQUE(ULD_SN,FRME_NO) · FK 제약(→LS_PORTAL_ULD, 삭제 연쇄) |
| 프레임번호 | FRME_NO | INTEGER | Y |  |  | KLID-AT-ID-054 | - | 복합 UNIQUE(ULD_SN,FRME_NO) |
| 파일경로명 | FILE_PATH_NM | VARCHAR(500) | Y |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_PORTAL_ULD_LBL (KLID-AT-TB-055)
- 사용: [[KLID_AT_엔티티관계모형설계서#포털업로드라벨 (KLID-AT-EN-055)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-055 — LS_PORTAL_ULD_LBL

| 테이블ID | KLID-AT-TB-055 | 테이블명 | LS_PORTAL_ULD_LBL |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-055 (포털업로드라벨) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 포털 업로드 자산에 대한 사용자 수동 라벨 테이블. 사각형·다각형 두 유형만 허용하며 자동 라벨링·검수 대상이 아니다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 1,000 | 영구 — 포털 라벨 | 500,000 | ≈ 75MB (500,000 × 150B) | 프레임당 평균 1객체 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 업로드라벨일련번호 | ULD_LBL_SN | BIGINT | Y | Y |  | KLID-AT-ID-055 | IDENTITY 자동증가 | - |
| 포털사용자번호 | PORTAL_USER_NO | VARCHAR(100) | Y |  |  | KLID-AT-ID-055 | - | 본인 데이터 접근 제한 키 |
| 업로드일련번호 | ULD_SN | BIGINT | Y |  | Y | KLID-AT-ID-055 | - | FK 제약(→LS_PORTAL_ULD, 삭제 연쇄) |
| 업로드프레임일련번호 | ULD_FRME_SN | BIGINT | Y |  | Y | KLID-AT-ID-055 | - | FK 제약(→LS_PORTAL_ULD_FRME, 삭제 연쇄) |
| 라벨유형코드 | LBL_TYPE_CD | VARCHAR(16) | Y |  |  |  | - | 사각형/다각형만 허용 |
| 라벨명 | LBL_NM | VARCHAR(80) | N |  |  |  | - | - |
| 좌표내용 | POINT_CN | TEXT | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_PORTAL_TUS_ULD (KLID-AT-TB-056)
- 사용: [[KLID_AT_엔티티관계모형설계서#포털업로드세션 (KLID-AT-EN-056)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-056 — LS_PORTAL_TUS_ULD

| 테이블ID | KLID-AT-TB-056 | 테이블명 | LS_PORTAL_TUS_ULD |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-056 (포털업로드세션) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 포털 영상 자산의 재개 가능 업로드 세션 테이블. 진행 오프셋·만료 시각과 결과 자산 식별자를 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 10 | 30 — 만료 세션 정리 | 1,000 | ≈ 0.3MB (1,000 × 300B) | 만료분 주기 정리 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 업로드아이디 | ULD_ID | UUID | Y | Y |  | KLID-AT-ID-056 | - | - |
| 포털사용자번호 | PORTAL_USER_NO | VARCHAR(100) | Y |  |  | KLID-AT-ID-056 | - | - |
| 업로드길이 | ULD_LEN | BIGINT | Y |  |  |  | - | - |
| 업로드오프셋 | ULD_OFFSET | BIGINT | Y |  |  |  | - | - |
| 상태코드 | STTS_CD | VARCHAR(16) | Y |  |  |  | - | - |
| 파일경로명 | FILE_PATH_NM | VARCHAR(500) | Y |  |  |  | - | - |
| 원본파일명 | ORGNL_FILE_NM | VARCHAR(255) | N |  |  |  | - | - |
| 업로드일련번호 | ULD_SN | BIGINT | N |  | Y |  | - | 논리 FK(→LS_PORTAL_ULD) |
| 만료일시 | EXPRY_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-056 | - | - |
| 버전 | VER | BIGINT | Y |  |  |  | 0 | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_ACNT_USER (KLID-AT-TB-057)
- 사용: [[KLID_AT_엔티티관계모형설계서#사용자계정 (KLID-AT-EN-057)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-057 — LS_ACNT_USER

| 테이블ID | KLID-AT-TB-057 | 테이블명 | LS_ACNT_USER |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-057 (사용자계정) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 저작도구 사용자 계정 테이블. 상위 시스템이 인계한 사용자 정보로 역할 확인 시점에 자동 등록되며 삭제는 사용여부 비활성 처리만 허용한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 1 | 영구 — 계정 마스터 | 500 | ≈ 0.1MB (500 × 200B) | 상위 시스템 인계 계정 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 사용자번호 | USER_NO | BIGINT | Y | Y |  | KLID-AT-ID-057 | - | - |
| 사용자아이디 | USER_ID | VARCHAR(20) | N |  |  |  | - | - |
| 사용자명 | USER_NM | VARCHAR(100) | Y |  |  |  | '' | - |
| 사용자이메일주소 | USER_EML_ADDR | VARCHAR(320) | N |  |  |  | - | - |
| 사용여부 | USE_YN | CHAR(1) | Y |  |  |  | 'Y' | 사용여부(논리 삭제) |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_USER_ROLE (KLID-AT-TB-058)
- 사용: [[KLID_AT_엔티티관계모형설계서#사용자역할 (KLID-AT-EN-058)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-058 — LS_USER_ROLE

| 테이블ID | KLID-AT-TB-058 | 테이블명 | LS_USER_ROLE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-058 (사용자역할) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 사용자별 저작도구 역할(검수자·라벨링 작업자·포털 회원) 테이블. 사용자당 1건을 보유한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 1 | 영구 — 권한 마스터 | 500 | ≈ 0.03MB (500 × 60B) | 사용자 1:1 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 사용자번호 | USER_NO | BIGINT | Y | Y | Y | KLID-AT-ID-058 | - | 논리 FK(→LS_ACNT_USER) |
| 역할코드 | ROLE_CD | VARCHAR(32) | Y |  |  |  | - | 검수자/라벨링작업자/포털회원 |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | UPD_DT | TIMESTAMP | N |  |  |  | - | - |

<!-- hwpx:ignore-start -->
### LS_AUTHRT_GRANT_ATMPT (KLID-AT-TB-059)
- 사용: [[KLID_AT_엔티티관계모형설계서#권한부여시도 (KLID-AT-EN-059)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-059 — LS_AUTHRT_GRANT_ATMPT

| 테이블ID | KLID-AT-TB-059 | 테이블명 | LS_AUTHRT_GRANT_ATMPT |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-059 (권한부여시도) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 역할 자가부여 시도 횟수 원장 테이블. 구분·식별자·시간 구간 단위로 임계 초과 시도를 차단한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 5 | 30 — 만료 경과분 정리 | 5,000 | ≈ 0.5MB (5,000 × 100B) | 시도 구간 단위 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 시도구분코드 | ATMPT_SE_CD | VARCHAR(20) | Y | Y |  | KLID-AT-ID-059 | - | 구분·식별자·시작일시 복합 PK |
| 시도식별자 | ATMPT_IDNTFR | VARCHAR(36) | Y | Y |  | KLID-AT-ID-059 | - | 구분·식별자·시작일시 복합 PK |
| 시작일시 | BGNG_DT | TIMESTAMP | Y | Y |  | KLID-AT-ID-059 | - | 구분·식별자·시작일시 복합 PK |
| 시도횟수 | ATMPT_NMTM | INTEGER | Y |  |  |  | 0 | - |
| 만료일시 | EXPD_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-059 | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_NOTICE (KLID-AT-TB-060)
- 사용: [[KLID_AT_엔티티관계모형설계서#공지게시글 (KLID-AT-EN-060)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-060 — LS_NOTICE

| 테이블ID | KLID-AT-TB-060 | 테이블명 | LS_NOTICE |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-060 (공지게시글) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 공지·가이드라인 게시글 본문 테이블. 작성 후 발행 상태로 전환해야 일반 사용자에게 노출된다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 1 | 영구 — 게시판 | 5,000 | ≈ 5MB (5,000 × 1,000B) | 본문 평균 1KB |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 게시글일련번호 | NOTICE_SN | BIGINT | Y | Y |  | KLID-AT-ID-060 | IDENTITY 자동증가 | - |
| 게시글제목 | NOTICE_TITLE | VARCHAR(200) | Y |  |  |  | - | - |
| 게시글내용 | NOTICE_CN | TEXT | Y |  |  |  | - | - |
| 상단고정여부 | UPEND_FIX_YN | CHAR(1) | Y |  |  | KLID-AT-ID-060 | 'N' | - |
| 발행상태코드 | PBLCN_STTS_CD | VARCHAR(16) | Y |  |  | KLID-AT-ID-060 | 'DRAFT' | 작성중/발행 |
| 발행일시 | PBLCN_DT | TIMESTAMP | N |  |  |  | - | - |
| 등록아이디 | REG_ID | VARCHAR(30) | N |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  | KLID-AT-ID-060 | CURRENT_TIMESTAMP | - |
| 수정자아이디 | MDFR_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_NOTICE_ATTACH (KLID-AT-TB-061)
- 사용: [[KLID_AT_엔티티관계모형설계서#공지첨부파일 (KLID-AT-EN-061)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-061 — LS_NOTICE_ATTACH

| 테이블ID | KLID-AT-TB-061 | 테이블명 | LS_NOTICE_ATTACH |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-061 (공지첨부파일) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 게시글 첨부파일 테이블. 원본 파일명과 저장 파일명을 분리 보관하며 저장 경로는 외부에 노출하지 않는다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 0 | 2 | 영구 — 게시판 첨부 | 10,000 | ≈ 1.5MB (10,000 × 150B) | 게시글당 평균 2첨부 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 첨부파일일련번호 | ATCH_FILE_SN | BIGINT | Y | Y |  | KLID-AT-ID-061 | IDENTITY 자동증가 | - |
| 게시글일련번호 | NOTICE_SN | BIGINT | Y |  | Y | KLID-AT-ID-061 | - | FK 제약(→LS_NOTICE, 삭제 연쇄) |
| 원본파일명 | ORGNL_FILE_NM | VARCHAR(300) | Y |  |  |  | - | - |
| 저장파일명 | STRG_FILE_NM | VARCHAR(300) | Y |  |  |  | - | - |
| 파일경로 | FILE_PATH | VARCHAR(1000) | Y |  |  |  | - | - |
| 파일크기 | FILE_SZ | BIGINT | Y |  |  |  | - | - |
| 등록일시 | REG_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_SYSTEM_CONFIG (KLID-AT-TB-062)
- 사용: [[KLID_AT_엔티티관계모형설계서#시스템설정 (KLID-AT-EN-062)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-062 — LS_SYSTEM_CONFIG

| 테이블ID | KLID-AT-TB-062 | 테이블명 | LS_SYSTEM_CONFIG |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-062 (시스템설정) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 운영 파라미터 키-값 저장소 테이블. 허용된 설정 키만 등록·갱신하며 값 유형에 따라 검증하고, 애플리케이션 캐시(유효기간 60초)로 조회한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 20 | 0 | 영구 — 설정 마스터 | 200 | ≈ 0.02MB (200 × 100B) | 허용 키만 등록 |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 설정키 | STNG_KEY | VARCHAR(100) | Y | Y |  | KLID-AT-ID-062 | - | 허용 설정 키만 등록 |
| 설정값 | STNG_VALUE | VARCHAR(4000) | N |  |  |  | - | - |
| 설정유형코드 | STNG_TYPE_CD | VARCHAR(20) | Y |  |  |  | - | - |
| 설명 | EXPLN | VARCHAR(500) | N |  |  |  | - | - |
| 수정자아이디 | MDFR_ID | VARCHAR(30) | N |  |  |  | - | - |
| 수정일시 | MDFCN_DT | TIMESTAMP | Y |  |  |  | CURRENT_TIMESTAMP | - |

<!-- hwpx:ignore-start -->
### LS_META (KLID-AT-TB-063)
- 사용: [[KLID_AT_엔티티관계모형설계서#저작도구전역메타 (KLID-AT-EN-063)]]
<!-- hwpx:ignore-end -->

#### KLID-AT-TB-063 — LS_META

| 테이블ID | KLID-AT-TB-063 | 테이블명 | LS_META |
|--------|---|-------|---|
| 데이터베이스명 | klid_at | TS명 | pg_default |
| 관련 엔티티 ID | KLID-AT-EN-063 (저작도구전역메타) |
| 트리거 구성 | 없음 |
| 테이블 설명 | 저작도구 전역 메타 키-값 저장소 테이블. 운영 메타를 키-값 형태로 보관한다. |

| 초기건수 | 증가량(일) | 보관주기 | 최대건수 | 용량 | 비고 |
|---------|---------|--------|---------|------|------|
| 5 | 0 | 영구 — 운영 메타 | 100 | ≈ 0.01MB (100 × 2,100B) | 메타값 최대 2KB |

| 컬럼명 | 컬럼ID | 타입 및 길이 | Not Null | PK | FK | IDX | 기본값 | 제약조건 |
|-------|--------|----------|---------|------|------|------|-------|--------|
| 메타키 | META_KEY | VARCHAR(64) | Y | Y |  | KLID-AT-ID-063 | - | - |
| 메타값 | META_VL | VARCHAR(2000) | N |  |  |  | - | - |

## 항목 설명

### 데이터베이스 목록

- **데이터베이스 ID**: 실제 물리적으로 구현하는 데이터베이스 ID를 부여하여 기입한다.
- **데이터베이스명칭**: 데이터베이스가 주로 관리하는 데이터를 대표하는 명칭을 기입한다.
- **주관부서**: 데이터베이스의 관리 책임을 가진 부서를 기입한다.
- **비고**: 기타 고려사항 등을 기입한다.

### 데이터베이스 정의

- **데이터베이스 ID**: 데이터베이스 ID를 기입한다.
- **데이터베이스명**: 데이터베이스 명칭을 기입한다.
- **Storage Group**: 데이터베이스내의 객체에서 사용할 Default Storage Group명을 기술한다.
- **Bufferpool**: 데이터베이스내의 테이블 스페이스에서 사용할 Default Bufferpool명을 기술한다.
- **인덱스 BP**: 데이터베이스내의 Index에서 사용할 Default Bufferpool명을 기술한다.
- **TS ID**: 테이블 스페이스의 식별자를 기술한다.
- **TS 용량**: 테이블 스페이스의 할당용량을 표시한다.
- **테이블 ID**: 테이블의 식별자를 기술한다.
- **테이블명**: 테이블 명칭을 기술한다.
- **인덱스 ID**: 인덱스 식별자를 기술한다.
- **인덱스 용량**: 인덱스의 저장공간의 크기를 산정하여 기술한다.
- **비고**: 예외사항 및 추가사항을 기술한다.

### 테이블 명세

- **테이블 ID**: 테이블의 식별자를 기술한다.
- **테이블명**: 테이블 명칭을 기술한다.
- **데이터베이스명**: 데이터베이스 명칭을 기입한다.
- **TS명**: 테이블 스페이스의 명칭을 기술한다.
- **관련 엔티티 ID**: 본 테이블에 대응하는 "엔티티 관계 모형 설계서"(D8)의 엔티티 ID를 기입한다(1:1).
- **트리거 구성**: 테이블에 트리거가 구성되어 있을 경우 트리거 로직을 기술한다.
- **테이블 설명**: 테이블의 목적 및 역할을 간략하게 기술한다.
- **초기건수**: 테이블이 최초 생성될 때 보유한 데이터의 건수를 기재한다.
- **증가량(일)**: 일정주기별 데이터 발생건수를 기술한다.
- **보관주기(일별)**: 해당 테이블내 데이터의 보관주기를 기술한다.
- **최대건수**: 테이블이 관리되는 기간(보관주기)내에 발생이 예상되는 최대 데이터 건수를 기술한다.
- **용량**: 데이터 보관 최대 건수와 데이터의 길이를 고려하여 산정한 데이터 용량을 기술한다.
- **비고**: 기타 고려사항 등을 기술한다.
- **컬럼명**: 테이블 컬럼의 내용과 특성을 인식할 있는 명칭을 기술한다.
- **컬럼 ID**: 테이블 컬럼 ID를 기술한다.
- **타입 및 길이**: 컬럼의 타입과 최대 허용 길이를 기술한다.
- **NOT NULL**: 필수항목 여부를 기술한다.
- **PK(Primary Key)**: 주키를 의미한다.
- **FK(Foreign Key)**: 외래키를 의미한다.
- **INX(Index)**: 인덱스를 의미한다.
- **기본값**: 속성의 기본값이 있는 경우에 그 값을 기재한다.
- **제약조건**: 속성의 특이한 제약조건이 있는 경우 기재한다.

## 작성 시 참고사항

> **ID 체계 (프로젝트 공식)**
> - 프로젝트 ID: `KLID`
> - 서브시스템 ID (저작도구): `AT`
> - 산출물 파일명: `KLID_AT_데이터베이스설계서_Rev {버전}`
> - 본 산출물에서 사용하는 ID:
>   - 데이터베이스 ID: `KLID-AT-DB-NNN`
>   - TS(테이블 스페이스) ID: `KLID-AT-TS-NNN`
>   - 테이블 ID: `KLID-AT-TB-NNN`
>   - 인덱스 ID: `KLID-AT-ID-NNN` (테이블 번호 동조)
>   - 관련 엔티티 ID: `KLID-AT-EN-NNN` (D8 참조)
>
> - 데이터베이스: `klid_system` 내 `klid_at` 스키마 (**PostgreSQL** — UTF-8, 표준 SQL DDL)
> - 2노드 이중화(Active-Active) 환경에서 단일 데이터베이스 인스턴스를 공유하며, 배치 스케줄러도 동일 데이터베이스의 공유 운영 스키마를 잠금 매개로 사용한다.

---

## 부록. 데이터 부재·표기 사유

### A. 범위 제외 — 공유 스키마 (물리 설계 비대상)

| 구분 | 대상 | 제외 사유 |
|------|------|-----------|
| 관제지원시스템 공유 스키마 | 관제 소유 영상·CCTV·계정·공통코드 | 관제지원시스템이 소유·구성하는 공유(READ) 스키마다. 저작도구는 참조만 하며 물리 설계 책임이 없고, 변경 시 관제지원시스템 담당 조직의 사전 승인이 필요하다. |
| 배치 스케줄러 운영 스키마 | 스케줄러 잡·트리거·잠금 테이블군 | 스케줄러 제품이 정의·제공하는 운영 스키마다. 저작도구가 설계하지 않으며 인프라 구성 산출물에서 다룬다. |

### B. 컬럼표 공란·`-` 표기

| 표기 위치 | 의미 |
|-----------|------|
| `PK`·`FK` 공란 | 해당 컬럼이 주키·외래키가 아님을 뜻한다(해당 시에만 `Y`). |
| `IDX` 공란 | 해당 컬럼이 어떤 인덱스에도 포함되지 않음을 뜻한다(포함 시 테이블 인덱스 ID 표기). |
| `기본값` `-` | 기본값을 정의하지 않은 컬럼이다. |
| `제약조건` `-` | 유일성·참조·검사 등 별도로 명시할 제약이 없는 컬럼이다. |

### C. 참조 무결성 표기 구분

- **FK 제약(→대상)**: 데이터베이스에 외래키 제약이 선언된 참조다. 삭제 규칙(`삭제 연쇄`·`삭제 시 NULL`)을 함께 표기한다.
- **논리 FK(→대상)**: 참조 관계는 성립하지만 물리 외래키 제약을 선언하지 않은 참조다. 대량 적재 성능과 배치 재처리 순서의 유연성을 위해 제약을 선언하지 않으며, 참조 무결성은 애플리케이션 계층에서 보장한다.
- D8 엔티티 관계 모형 설계서는 논리 모델 관점에서 두 경우를 모두 외래키(`FK=Y`)로 표기하고, 물리 저장 상세(제약 선언 여부·삭제 규칙·인덱스 정의)는 본 산출물에서 구분해 기술한다.

### D. 표준용어 정합 확인 필요 컬럼 (후속 정정 대상)

> 아래 컬럼은 물리 명칭이 표준용어 조합 규칙에 정합하지 않는다. 본 산출물은 확정된 현행 물리 모델을 기술하는 것이 목적이므로 명칭을 임의로 바꾸지 않고 현행값을 그대로 기술하며, 정정은 별도 표준 정합 작업에서 수행한다(D8 부록 D와 동일 범위).

| 테이블 | 컬럼ID | 확인 필요 사유 |
|--------|--------|----------------|
| KLID-AT-TB-043 LS_MON_NOTI_ACML | EXPORT_RPRCS_YN | 산출 개념의 표준 약어 확정이 필요하다. |

