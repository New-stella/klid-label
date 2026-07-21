# D8 엔티티 관계 모형 설계서 — ERD 상세 설명

> 대상: `docs/design/D8-엔티티관계모형설계서.md` (14 ERD · 40 엔티티, R1 핵심 범위본)
> 목적: 14개 도메인 ERD를 다이어그램·관계·핵심 컬럼·업무규칙 단위로 상세 해설한다.

---

## 0. 공통 읽기 규칙 (먼저 알아둘 것)

**관계 표기(Crow's Foot)**
- `||--o{` : **1 : 0..N** (실선, 물리 FK 존재) — "한 부모가 자식 0개 이상을 가진다"
- `||--||` : **1 : 1** (실선) — 예: 적재등록 ↔ 진행상태
- `||..o{` / `||..o|` : **논리 관계 (점선, 물리 FK 없음)** — 전역 정책·키값 저장소를 렌더 호환용으로만 앵커에 연결

**두 개의 앵커 식별자** — 모든 관계가 이 둘로 수렴한다.
- `LS_DATA_RAW.RAW_SN` — **원시영상 일련번호 = 작업 단위 식별자**. "영상 1건 = 작업 1건". 배정·마킹·비식별·검수·통지·증강·포털이 전부 여기에 매달린다.
- `LS_DATA_SRC.SRC_SN` — **프레임원천 일련번호 = 라벨 부착점**. 영상에서 뽑은 키프레임, 라벨 좌표가 여기 붙는다.

**컬럼 표기** — `PK`(주키) / `FK`(외래키) / `UK`(유니크). 명세상 FK는 대부분 "논리 FK"로, 물리 제약(FOREIGN KEY) 실설정 여부는 **D9 소관**이며 D8은 참조 방향만 규정한다.

---

## 1. KLID-AT-ERD-001 — 영상·프레임 수집

**목적**: 라벨링 대상 영상과 그 프레임을 적재하는 뿌리 도메인. 나머지 13개 ERD가 여기서 뻗어 나간다.

**엔티티**
- `LS_DATA_RAW` — 원시영상(작업 단위). `VMS_CLIP_ID`가 **UK**라 같은 클립 재수신 시 신규가 아니라 갱신. `PRVC_TYPE_CD`(ANONY/PRVC/PSDO)에서 `PRVC_YN`을 파생, `DE_IDENT_YN`(Y/F/N)이 비식별 상태, `DATA_STTS_CD`가 배치 단계 상태(PENDING→MARKING_READY→COMPLETED).
- `LS_DATA_SRC` — 프레임원천. 한 행에 **원본 경로(`SRC_FILE_PATH_NM`)와 비식별 경로(`DE_IDNTF_SRC_FILE_PATH_NM`)를 페어**로 보관. `(RAW_SN, FRM_NO)` 유니크.
- `LS_DATA_RAW_HSTRY` / `LS_DATA_SRC_HSTRY` — 영상·프레임 변경 이력(신규수집/재수신갱신/상태전이).
- `LS_RESOLUTION_EXPORT` — 해상도 변경 산출. 검수완료 원본의 프레임셋을 하위 해상도로 다운스케일한 **산출 1건당 1행**. `(DATA_RAW_SN, TARGET_RES_CD)` 유니크. **새 영상·라벨을 만들지 않는다**(증강과 구분되는 핵심).

**관계**
- `LS_DATA_RAW ||--o{ LS_DATA_SRC` : 영상 1건 → 프레임 N개 추출
- `LS_DATA_RAW ||--o{ LS_DATA_RAW` : **자기참조**(`PARENT_RAW_SN`) — 증강본이 원본 영상을 가리킴
- 영상/프레임 → 각 이력, 영상 → 해상도 산출

**설계 포인트**: `VMS_CLIP_ID` UK 기반 upsert(재수신 갱신), 원본 경로 불변 보존, 증강본은 별도 RAW_SN이되 부모를 자기참조로 추적.

---

## 2. KLID-AT-ERD-002 — 라벨링

**목적**: 프레임별 라벨 좌표와 영상 시계열 메타를 관리하는 핵심 산출 도메인.

**엔티티**
- `LS_DATA_LBL` — 프레임 라벨. `SRC_SN`(프레임)에 붙고, `LBL_TYPE_CD`(bbox/폴리곤/세그멘테이션), `LBL_ID`(라벨 마스터 참조), `TRCK_ID`(트랙 보간용 추적 ID).
- `LS_DATA_LBL_AI_INFO` — 라벨 AI 출처. `LBL_SRC_CD`(YOLO/SAM2/수동), `CONF_SCORE`(신뢰도). 오토라벨/수동 구분.
- `LS_DATA_LBL_ATTR_VAL` — 객체별 속성값. 라벨마다 `ATTR_ID`(속성정의)에 대응하는 `ATTR_VL`.
- `LS_DATA_META` — 영상 시계열 메타(VLM 등). `RAW_SN`에 붙는 키-값(`META_KEY`/`META_VL`).
- `LS_DATA_META_HSTRY` — 메타 변경 이력.
- `LS_DATA_META_REVIEW` — 메타 검토상태. `META_TYPE_CD`, `RVW_STTS_CD`(REVIEWER가 검토·승인).
- `LS_DATA_LBL_HSTRY` — 라벨 **저장 이벤트** 이력(D1 `LsDataLblHstry`/DC-093). **저장이벤트 재구조화(V114, 2026-07-21)**: 기존 '라벨 1건=1행'(라벨단위 `LBL_SN`·`CHG_KIND_CD`)에서 **'저장 이벤트=1행 + diff 페이로드'**(프레임 단위)로 전환했다. 한 번의 저장 행위(저장 클릭 1회, 프레임 단위)를 1행으로 묶어 종류별 건수 `ADD_CNT`/`MDFCN_CNT`/`DEL_CNT`(추가/수정/삭제)와 변경 상세 diff `CHG_DTL_CN`(TEXT, 항목별 `{lblSn, changeKind, labelName, before, after}` JSON 목록)를 기록한다. 직전 저장 대비 이전값→새값 diff이며 첫 저장은 전부 ADDED. 저장은 프레임 전체 교체라 요청에서 빠진 라벨은 실제 삭제(DELETED)되고, 무변경 저장은 이력을 만들지 않는다. 라벨 저장(`bulkUpsert`)·트랙 삭제·비식별 누락 신고 삭제 모두 같은 저장 이벤트 모델로 같은 트랜잭션에서 프레임당 기록된다. 조회는 `GET /v1/frames/{srcSn}/label-history`. 구 `LBL_SN`·`CHG_KIND_CD` 컬럼은 제거됐고, `SRC_SN`(프레임)은 존속하므로 조회 인덱스만 둔다(원본 라벨은 diff 페이로드의 `lblSn`으로만 식별 — 물리 FK 미설정, 라벨 삭제가 이력을 위반/cascade하지 않도록).

**관계**
- `LS_DATA_SRC ||--o{ LS_DATA_LBL` : 프레임 1개 → 라벨 N개
- 라벨 → AI출처 / 속성값
- `LS_DATA_SRC ||--o{ LS_DATA_LBL_HSTRY` : 프레임 → 라벨 저장 이벤트 이력 (`SRC_SN` 앵커 — 저장 이벤트=1행, 원본 라벨은 diff 페이로드 `lblSn`으로만 식별·FK 미설정)
- `LS_DATA_RAW ||--o{ LS_DATA_META` : 영상 → 메타 (라벨은 프레임, 메타는 영상 단위)
- 메타 → 이력 / 검토상태

**설계 포인트**: 라벨은 **프레임(SRC_SN)** 단위, 메타는 **영상(RAW_SN)** 단위로 부착점이 다르다. AI 출처·신뢰도를 별도 테이블로 빼 오토라벨 결과와 사람 수정본을 분리 추적.

---

## 3. KLID-AT-ERD-003 — 라벨 마스터·버전관리

**목적**: 라벨 정의(코드성 마스터)와 검수완료 시점의 라벨 스냅샷(학습데이터 버전)을 다룬다. 성격이 다른 두 축이 한 ERD에 있다.

**엔티티**
- `LS_LABEL` — 라벨 마스터. `LBL_NM` UK, 색상(`COLR_VL`), 타입, 사용여부(`USE_YN`).
- `LS_LABEL_ATTR` — 라벨 속성 정의. 라벨마다 입력 속성(`ATTR_NM`, `INPUT_TYPE_CD`).
- `LS_LABEL_PRESET` / `LS_LABEL_PRESET_CODE` — 이벤트 유형별 라벨 프리셋과 그 코드 목록(bbox/폴리곤 활성 플래그).
- `LS_LABEL_VERSION` — **영상 단위 라벨 전체 스냅샷**. `VERSION_HASH`(SHA-256)로 버전 식별, `ACTVTN_YN`으로 active 버전 표시.

**관계**
- `LS_LABEL ||--o{ LS_LABEL_ATTR` : 라벨 → 속성 정의
- `LS_LABEL_PRESET ||--o{ LS_LABEL_PRESET_CODE` : 프리셋 → 코드
- `LS_DATA_RAW ||--o{ LS_LABEL_VERSION` : 영상 → 스냅샷(재검수·재승인마다 누적)

**설계 포인트(가장 자주 오해)**: 버전은 **검수 승인(APPROVED) 시점에만** 생성된다. 작업 중 임시저장(`LS_DATA_LBL` upsert)은 버전이 아니다 → **2계층 분리**. 동일 페이로드는 같은 해시로 중복 식별.

---

## 4. KLID-AT-ERD-004 — 마킹

**목적**: 비식별 완료 영상에서 이벤트 시점을 마킹.

**엔티티**
- `LS_MARKING` — 영상 이벤트 마킹. `EVNT_NM`(이벤트명), `MARK_MODE_CD`(자동/수동), `FRME_INTV_NOCS`(자동 마킹 프레임 간격), `STTS_CD`(진행상태).

**관계**
- `LS_DATA_RAW ||--o{ LS_MARKING` : 영상 1건 → 마킹 N개

**설계 포인트**: 마킹은 **비식별 영상**을 대상으로 한다. 자동=프레임 간격 기반, 수동=단축키. 마킹 완료가 잔여 배치(VLM→프레임추출→오토라벨링)의 트리거.

---

## 5. KLID-AT-ERD-005 — 비식별

**목적**: 개인정보 비식별 처리 이력과 누락 신고.

**엔티티**
- `LS_DEIDENT_PROC_LOG` — 비식별 처리 이력. `OTSD_JOB_ID` UK(외부 위탁 작업 ID), `PROC_STTS_CD`, KPST 연동 필드(`KPST_PRJ_ID`, `KPST_DATASET_ID`), `POLL_STTS_CD`(폴링 상태). 비식별 영상 파일 경로도 이 로그에 적재된다.
- `LS_DEIDENT_REPORT` — 비식별 누락 신고. `REPORTER_NO`(신고자), `REPORT_STTS_CD`(OPEN→RESOLVED).

**관계**
- `LS_DATA_RAW ||--o{ LS_DEIDENT_PROC_LOG` : 영상 → 처리 이력
- `LS_DATA_RAW ||--o{ LS_DEIDENT_REPORT` : 영상 → 누락 신고

**설계 포인트**: 비식별은 파이프라인 선두(적재 직후 자동). 실패·신고 시 `DE_IDENT_YN='F'` + 작업락, 자동 재비식별 큐 없이 **외부 솔루션 수동 재처리** 후 resolve. 외부 위탁 멱등은 `OTSD_JOB_ID` UK로 보장.

---

## 6. KLID-AT-ERD-006 — 검수·영상상태

**목적**: 적재 영상의 작업/검수 워크플로우 상태 머신과 반려.

**엔티티**
- `LS_RAW_DATA_ENROLLMENT` — 영상 적재 등록(적재 사실).
- `LS_RAW_DATA_STATUS` — 영상 진행상태. `DATA_STTS_CD`(작업/검수 상태), `VER`(낙관적 잠금 버전).
- `LS_DATA_ISSUE` — 영상 단위 반려. `UP_DATA_ISSUE_SN` **자기참조**(재반려 계층), `ISSUE_RSN`.

**관계**
- `LS_RAW_DATA_ENROLLMENT ||--|| LS_RAW_DATA_STATUS` : **1:1** — 적재 1건에 진행상태 1개
- `LS_RAW_DATA_STATUS ||--o{ LS_DATA_ISSUE` : 상태 → 반려 N건
- `LS_DATA_ISSUE ||--o{ LS_DATA_ISSUE` : 재반려 계층(자기참조)

**설계 포인트**: **두 상태 테이블의 책임 분리**가 핵심. `LS_DATA_RAW.DATA_STTS_CD`(배치 단계)와 `LS_RAW_DATA_STATUS.DATA_STTS_CD`(작업/검수 워크플로우)는 별개다. `COMPLETED`는 `ReviewService.approve`(검수 승인)에서만 전이. `VER`로 동시 편집 충돌 방지. (※ 이슈 상세는 ERD-008에서 확장)

---

## 7. KLID-AT-ERD-007 — 작업 배정

**목적**: REVIEWER→WORKER 영상 단위 배정과 재배정 이력.

**엔티티**
- `LS_TASK_ASSIGNMENT` — 작업 배정. `USER_NO`(배정 대상), `RAW_DATA_ID`(영상), `TASK_TYPE_CD`(예: LABELER).
- `LS_TASK_ASSIGN_HISTORY` — 재배정 이력. `PREV_USER_NO`→`NEW_USER_NO`.
- `LS_TASK_EVENT_LOG` — 작업 이벤트 로그. `EVNT_TYPE_CD`, `ACTOR_USER_NO`(행위자).

**관계**
- `LS_DATA_RAW ||--o{ LS_TASK_ASSIGNMENT` : 영상 → 배정
- `LS_TASK_ASSIGNMENT ||--o{ LS_TASK_ASSIGN_HISTORY` : 배정 → 재배정 이력
- `LS_DATA_RAW ||--o{ LS_TASK_EVENT_LOG` : 영상 → 이벤트 로그

**설계 포인트**: 역할 단일화(ADMIN 권한이 REVIEWER에 통합). 배정 이력 조회·재배정도 REVIEWER 권한. 프로젝트 단위 없이 **영상 단위 배정**.

---

## 8. KLID-AT-ERD-008 — 이슈 스레드

**목적**: ERD-006의 반려 이슈를 스레드·상태머신·프레임 참조·낙관적 잠금으로 확장.

**엔티티**
- `LS_DATA_ISSUE`(확장형) — `ISSUE_TYPE_CD`(이슈 유형), `ISSUE_STTS_CD`(상태머신), `SRC_SN`(특정 프레임 참조), `VER`(낙관적 잠금), `UP_DATA_ISSUE_SN`(재반려 계층 자기참조).
- `LS_ISSUE_COMMENT` — 이슈 댓글. `AUTHOR_NO`/`AUTHOR_ROLE_CD`(작성자·역할), `CMNT_CN`.

**관계**
- `LS_DATA_ISSUE ||--o{ LS_ISSUE_COMMENT` : 이슈 → 댓글
- `LS_DATA_ISSUE ||--o{ LS_DATA_ISSUE` : 재반려 계층

**설계 포인트**: ERD-006이 "반려 이력" 뷰라면 여기서는 "이슈 협업" 뷰. 같은 `LS_DATA_ISSUE`를 두 관점으로 그린 것이며, `SRC_SN`으로 프레임 단위 이슈 지정, 댓글로 WORKER↔REVIEWER 소통.

---

## 9. KLID-AT-ERD-009 — 데이터 증강

**목적**: 외부 증강(WINTER/NIGHT/RAIN) 결과 수신·검수·라벨 매핑.

**엔티티**
- `LS_DATA_AUG` — 데이터 증강. `SRC_SN`(원본 프레임), `AUG_TYPE_CD`(증강 종류), `AUG_PROC_STTS_CD`, `IDMP_KEY` UK(멱등).
- `LS_DATA_AUG_RVW` — 증강 결과 검수. `RVW_STTS_CD`, `LBL_INTGRT_PCT`(라벨 정합률).
- `LS_DATA_AUG_LBL_MAP` — 증강 라벨 매핑. `ORGNL_DATA_LBL_SN`(원본 라벨), `COORD_RECALC_YN`(좌표 재계산 여부).

**관계**
- `LS_DATA_RAW ||--o{ LS_DATA_AUG` : 원본 영상 → 증강
- 증강 → 검수 / 라벨 매핑

**설계 포인트**: 증강은 **새 영상(RAW_SN) 생성**(ERD-001의 `PARENT_RAW_SN`으로 원본 참조). 외부 3종은 해상도 동일이라 좌표 그대로 복사(`COORD_RECALC_YN='N'`). `IDMP_KEY`로 중복 수신 방지. 새 영상은 미검수(PENDING)로 시작.

---

## 10. KLID-AT-ERD-010 — 배치·작업 인프라

**목적**: 파이프라인 처리 이력, 작업 잠금, 마감 정책.

**엔티티**
- `LS_BATCH_PROC_LOG` — 배치 처리 이력. `JOB_ID`, `PROC_STEP_CD`(단계), `PROC_STTS_CD`.
- `LS_AUTH_WORK_LOCK` — 작업 잠금. `LOCK_TARGET_CD`, `LOCK_STTS_CD`, `LOCK_ID` UK.
- `LS_DEADLINE` — 비식별 유형별 마감 정의. `DDLN_DT`, `ANONY/PSDO/PRVC_INCL_YN`.

**관계**
- `LS_DATA_RAW ||--o{ LS_BATCH_PROC_LOG` : 영상 → 처리 이력
- `LS_DATA_RAW ||--o{ LS_AUTH_WORK_LOCK` : 영상 → 작업 잠금
- `LS_DATA_RAW ||..o{ LS_DEADLINE` : **점선(논리)** — 마감은 전역 정책이라 영상 종속 아님

**설계 포인트**: `LS_DEADLINE`은 **특정 영상에 종속하지 않는 전역 정책 엔티티**. 물리 FK 없이 렌더 호환용 점선만 표기. 작업 잠금은 비식별 신고·동시 편집 시 사용.

---

## 11. KLID-AT-ERD-011 — 관제 통지

**목적**: 검수 완료/수정 시 관제서버로의 단방향 통지와 멱등.

**엔티티**
- `LS_CONTROL_NOTIFY_FALLBACK` — 영상 단위 통지 큐(재등록/dead-letter). `IDMP_KEY` UK, `EVNT_TYPE_CD`(TASK_COMPLETED/TASK_MODIFIED), `STTS_CD`.
- `LS_WEBHOOK_IDEMPOTENCY` — 외부 위탁(비식별/시계열/증강) 멱등 원장. `IDMP_KEY` PK, `CHNL_CD`(채널), `OTSD_JOB_ID`.

**관계**
- `LS_DATA_RAW ||--o{ LS_CONTROL_NOTIFY_FALLBACK` : 영상 → 통지 큐
- `LS_DATA_RAW ||..o{ LS_WEBHOOK_IDEMPOTENCY` : **점선(논리)** — 전역 멱등 원장, 영상 종속 아님

**설계 포인트**: 통지는 **단방향 outbound + 요약만**(라벨 본문 미포함). 관제서버는 통지 수신 후 저작도구 API/View로 상세 조회(pull). 양방향 M2M 인증은 deprecated. 멱등 키로 중복 통지 방지.

---

## 12. KLID-AT-ERD-012 — 포털

**목적**: 포털 사용자별 작업 라벨(외부 채널, 단방향).

**엔티티**
- `LS_PORTAL_USER_LABEL` — 포털 사용자 작업 라벨. `PORTAL_USER_NO`(사용자), `SRC_RAW_SN`/`SRC_DATA_SRC_SN`(원본 영상·프레임), `LBL_TYPE_CD`, `POINT_CN`(좌표).

**관계**
- `LS_DATA_RAW ||--o{ LS_PORTAL_USER_LABEL` : 영상 → 포털 사용자 라벨

**설계 포인트**: 포털 저장은 **사용자별 별도 적재**로, 원본·데이터마트를 수정하지 않는다(단방향). 오토라벨·VLM·버전관리·검수·업로드 미제공(ADR-013). 다운로드는 사용자 작업 데이터 기준.

---

## 13. KLID-AT-ERD-013 — 게시판

**목적**: 공지 게시글과 첨부.

**엔티티**
- `LS_NOTICE` — 공지 게시글. `NOTICE_TITLE`, `NOTICE_CN`, `PUB_STTS_CD`(게시 상태), `UPEND_FIX_YN`(상단 고정).
- `LS_NOTICE_ATTACH` — 첨부파일. `ORGNL_FILE_NM`(원본명), `STORE_FILE_NM`(저장명).

**관계**
- `LS_NOTICE ||--o{ LS_NOTICE_ATTACH` : 게시글 → 첨부 N개

**설계 포인트**: 첨부는 원본 파일명과 저장 파일명을 분리 보관(경로 조작·중복 방지). 상단 고정 플래그로 공지 노출 제어.

---

## 14. KLID-AT-ERD-014 — 시스템 설정

**목적**: 전역 키-값 설정 저장소.

**엔티티**
- `LS_SYSTEM_CONFIG` — 시스템 설정. `STNG_KEY` PK, `STNG_VALUE`, `STNG_TYPE_CD`(값 타입), `MDFR_ID`(수정자).
- `LS_META` — 저작도구 전역 메타. `META_KEY` PK, `META_VL`.

**관계**
- `LS_SYSTEM_CONFIG ||..o{ LS_META` : **점선(논리)** — 두 독립 키-값 저장소, 물리 FK 없이 동일 설정 도메인의 논리 그룹으로만 표기

**설계 포인트**: 둘 다 독립 키-값 저장소. 설정은 Caffeine 로컬 캐시(TTL 60s)로 조회 부하 완화. `MDFR_ID`로 설정 변경 감사.

---

## 15. 한눈에 보는 관계 요약

| ERD | 부모 | 자식 | 카디널리티 | 비고 |
|-----|------|------|:---:|------|
| 001 | LS_DATA_RAW | LS_DATA_SRC | 1:N | 프레임 추출 |
| 001 | LS_DATA_RAW | LS_DATA_RAW | 1:N | 증강 자기참조 |
| 002 | LS_DATA_SRC | LS_DATA_LBL | 1:N | 라벨은 프레임 단위 |
| 002 | LS_DATA_RAW | LS_DATA_META | 1:N | 메타는 영상 단위 |
| 003 | LS_DATA_RAW | LS_LABEL_VERSION | 1:N | 승인 시 스냅샷 |
| 004 | LS_DATA_RAW | LS_MARKING | 1:N | 비식별 영상 대상 |
| 005 | LS_DATA_RAW | LS_DEIDENT_PROC_LOG | 1:N | 외부 위탁 멱등 |
| 006 | LS_RAW_DATA_ENROLLMENT | LS_RAW_DATA_STATUS | **1:1** | 상태 머신 |
| 006/008 | LS_DATA_ISSUE | LS_DATA_ISSUE | 1:N | 재반려 자기참조 |
| 007 | LS_DATA_RAW | LS_TASK_ASSIGNMENT | 1:N | 영상 단위 배정 |
| 009 | LS_DATA_RAW | LS_DATA_AUG | 1:N | 증강=새 영상 |
| 010 | LS_DATA_RAW | LS_DEADLINE | **논리(점선)** | 전역 정책 |
| 011 | LS_DATA_RAW | LS_WEBHOOK_IDEMPOTENCY | **논리(점선)** | 전역 원장 |
| 012 | LS_DATA_RAW | LS_PORTAL_USER_LABEL | 1:N | 단방향 |
| 013 | LS_NOTICE | LS_NOTICE_ATTACH | 1:N | 게시판 |
| 014 | LS_SYSTEM_CONFIG | LS_META | **논리(점선)** | 독립 키-값 |

## 16. §3 공유 스키마 경계 (물리 설계 비대상)

- `MNG_CLIP_MASTER` / `MNG_CLIP_EVNT_LST` — 관제서버 소유 공유 스키마. 저작도구는 학습용 클립 픽업 시 **READ만**(`ddl-auto=validate`).
- D8/D9 물리 설계 대상 **아님**. §3에 참조 사실만 기술, 진실원은 관제 공유 클립 ERD에서 관리. 변경 시 관제서버팀 선승인 필수.

---

## 부록. design vs design-full 차이 (참고)

이 문서는 `docs/design`(R1 핵심 범위, **14 ERD / 40 엔티티**) 기준이다. `docs/design-full`은 여기에 **ERD-015 영상 업로드 세션(LS_TUS_UPLOAD)**, **ERD-016 데이터마트 적재 View 5종(V_COMPLETED_*)** 을 더한 전체 개발범위본(**16 ERD / 45 엔티티**)이다. 데이터마트 소비 인터페이스나 TUS 업로드 세션까지 설명해야 하면 design-full 및 `D8-ERD-설명문.md`를 함께 참조한다.
