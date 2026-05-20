# ccarch 업로드 자료 — 학습데이터 저작도구 서브시스템 전용

> **본 폴더의 관점**: 저작도구는 사업 전체("AI 기반 지방정부 CCTV 관제지원시스템 구축(2차)")의 **한 서브시스템**이다. 본 폴더는 **저작도구가 직접 호출하거나 호출받는 부분만** 다룬다. 사업 전체 그림(타 서브시스템·외부 본체 모듈·사업 추진 체계 등)은 별도 폴더 `docs/ccarch/`에 있으며 본 폴더에서 다시 등록하지 않는다.
>
> RFP 원본 PDF는 `docs/requirements/[붙임2]  제안요청서(수정)_260303.pdf` 그대로 보존.

## 본 도구의 범위

저작도구는 다음 3개 모듈을 포함한다 — 모두 본 사업 책임 범위.

- **Backend (Spring Boot)** — 인증/DB/오케스트레이션, 라벨 CRUD, 검수, 버전관리, 외부 연동
- **ai-server (Python FastAPI)** — Stateless 추론 전용 마이크로서비스 (**YOLO/SAM2만 본격 운영**). 본 도구 모노레포 내부 모듈이지만 별도 서비스로 분리 배포 (GPU 자원 격리)
- **Frontend (React + Vite)** — 관제·포털 두 채널의 사용자 UI (포털 사용자에게는 저작도구 기능인 간편 라벨링만 제공)

ai-server 는 본 도구가 책임지는 영역이므로 외부 시스템이 아닌 **내부 컴포넌트**(`08-components.md`)로 다룬다.

**VLM 시계열 분석은 외부 서비스** — 본 도구가 외부 VLM 서비스에 비동기 작업을 위탁하고 결과를 수신해 REVIEWER 가 검토한다. 영상 단위 일반 메타는 본 도구 책임 외(영상 인입 경로에서 함께 적재됨).

**외부 시스템 연동은 원칙적으로 비동기** — Deidentify / VLM / 생성형 AI 는 작업 위탁 + 결과 수신 채널 + idempotency / dead-letter / 재등록 큐 표준 적용. 구체 transport(큐·콜백·폴링)는 운영 환경 결정.

**예외**: Gitea 라벨 커밋만 **동기 REST 호출** (timeout 70s + CircuitBreaker + Retry). 라벨 저장 후 커밋 hash 즉시 응답이 필요한 흐름이라 동기로 유지하며, CircuitBreaker open 같은 장애 시에만 fallback 큐로 비동기 재시도(stg/prd).

## 저작도구의 서브시스템 위치

```
                       ┌──────────────────────────────┐
                       │  관제서버 / 포털 서버          │  ◀── JWT 발급 + 사용자 인계
                       │  (외부 시스템 — 인증 채널)   │
                       └─────┬────────────────┬───────┘
                             │ JWT            │ JWT
                             ▼                ▼
              ┌──────────────────────────────────────────┐
              │   학습데이터 저작도구 (본 폴더 범위)       │
              │  ┌──────────────────────────────────┐    │
              │  │ Backend (Spring Boot)           │    │
              │  │   REST API · Quartz Batch · JPA │    │
              │  └────────────┬─────────────────────┘    │
              │               │ HTTP                     │
              │               ▼                          │
              │  ┌──────────────────────────────────┐    │
              │  │ ai-server (FastAPI Stateless)   │    │
              │  │   YOLO · SAM2                    │    │
              │  └──────────────────────────────────┘    │
              │  ┌──────────────────────────────────┐    │
              │  │ Frontend (React + Vite)          │    │
              │  │   관제 채널 + 포털 진입 라벨링 UI │    │
              │  └──────────────────────────────────┘    │
              └──┬────────┬────────┬───────────────────┘
   호출(outbound) │        │        │
                 ▼        ▼        ▼
        Deidentify  Gitea  외부 VLM 서비스
        솔루션      (버전  (시계열)
        (SW 직접구매) 저장소)

   호출(inbound) ┌─────────────────────────────────────────────┐
                │ 관제서버 / 포털 서버 (JWT 발급 + 사용자 인계) │
                │ 외부 생성형 AI 시스템 (증강 결과 인계)         │
                └─────────────────────────────────────────────┘

   DB 공유:  klid_system  ◀── 본 도구 + 관제서버 양쪽이 같은 MariaDB 인스턴스에 접근
                              관제서버는 본 도구가 적재한 학습데이터를 DB에서 직접 조회하여 외부로 전달

   외부 책임으로 본 도구 DB 에 영상이 적재됨 (LS_DATA_RAW PENDING) → 본 도구는 그 뒤부터 처리
```

## 서브시스템 책임 경계 — Inbound / Outbound 매트릭스

**외부 시스템 연동은 원칙적으로 비동기**(작업 위탁 + 결과 수신 채널 + 요청 ID idempotency / dead-letter / 재등록 큐). 단 Gitea 라벨 커밋만 예외로 동기 REST.

| 방향 | 상대 시스템 | 본 도구가 노출/호출 | 패턴 |
|---|---|---|---|
| Inbound (호출 받음) | 관제서버 | `JwtAuthFilter`로 JWT 검증 + redirect 동작 | storage 공유, 동기 인증 |
| Inbound | 포털 서버 | 동일 (`JwtAuthFilter` + redirect) | storage 공유, 동기 인증 |
| Inbound (비동기 결과) | 외부 생성형 AI 시스템 | 증강 결과 인계 SPI (if-augment-result-handover) | 비동기 push 수신 |
| Outbound (비동기) | 외부 VLM 서비스 | 시계열 분석 위탁 (if-vlm-timeseries-spi) | 작업 등록 + 결과 수신 |
| Outbound (비동기) | Deidentify 솔루션 | `DeidentifyClient` 비식별 위탁 (if-deidentify-spi) | 작업 등록 + 결과 수신, 모든 영상 무조건 위탁 |
| **Outbound (동기 REST)** | **Gitea** | **`GiteaClient` 동기 커밋 (if-gitea-contents)** | **timeout 70s + CircuitBreaker + Retry, 장애 시에만 fallback 큐(stg/prd)** |
| DB 공유 | 관제서버 | klid_system MariaDB 공유 — 관제서버가 학습데이터 테이블 직접 조회 후 외부로 전달 | 본 도구는 적재만 |

학습데이터를 외부 학습데이터 시스템으로 전달하는 책임은 관제서버에 있으며, 관제서버는 본 도구의 API 가 아닌 공유 DB(klid_system)를 직접 조회하므로 본 도구 측 export API/SPI 는 두지 않는다.

## 책임지지 않는 것 (본 폴더에서 노드로 등록하지 않음)

본 도구가 호출하거나 호출받는 직접 인터페이스가 없는 시스템·기능은 ccarch 노드로 등록하지 않는다.

**영상·이벤트 인입 경로 (외부 책임)**
- 지자체 중계서버 / 1차 사업 외부 이벤트 시스템(선별관제·관제일지·스마트시티)
- VMS 유형별 표준 변환 모듈 / 망연계 솔루션
- 클립영상 중복 수집 방지 / VLM 영상 정제 / 중계시스템 통합관리 체계
- 본 도구는 LS_DATA_RAW 에 PENDING 으로 적재된 영상부터 처리한다.

**외부 시스템 본체 (분리발주 또는 별도 사업)**
- Deidentify 솔루션 본체 / Gitea 서버 본체 / 외부 생성형 AI 모델 본체 / 외부 VLM 모델 본체 / 외부 학습데이터 마트·검색·다운로드 본체

**관제지원시스템 자체 기능 (관제서버 책임)**
- AI CCTV 관제지원시스템 기능 개발 및 고도화(GIS 기반 CCTV 자원·사각지대·재난 통계, 모바일 공무원증 인증, 사용자별 IP 화이트리스트 등)
- 침수 탐지 시범운영 및 대시보드
- 일반 개인정보 보호조치(영향평가 수검 등) — 본 도구의 비식별 호출만 ccarch 범위 안

**외부 포털 자체 기능 (포털 서버 책임)**
- 회원가입·로그인·인증·마이페이지·탈퇴
- 영상 업로드 (TUS 등)
- 학습데이터 다운로드·D-day·만료 처리 UI
- 공지사항·FAQ·매뉴얼·사용 통계·다운로드 현황

**사업 추진 외 (분리발주 또는 사업 관리)**
- 사업 전체 추진 체계 / 추진 일정 / 산출물 카탈로그 / 조직 역할

## 파일 일람

| 파일 | 노드 타입 | 개수 | 비고 |
|---|---|---:|---|
| [01-external-systems.md](01-external-systems.md) | ACTOR (role=SYSTEM) | 6 | 본 도구가 호출/호출받는 외부 시스템만 |
| [02-actors.md](02-actors.md) | ACTOR (role=USER/SYSTEM) | 4 | 사람 사용자 3 + 내부 행위자 BATCH_SYSTEM 1 |
| [03-features.md](03-features.md) | FEATURE | 7 | 저작도구 직접 책임 기능군만 |
| [04-source-requirements.md](04-source-requirements.md) | SOURCE_REQUIREMENT | 7 | SFR 중 본 도구 직접 책임(SFR-03/06-07/08/09/15/16-17) + NFR 핵심 |
| [05-requirements.md](05-requirements.md) | REQUIREMENT | 15 | 입출력 경계 명확화된 정제 요구사항 |
| [06-usecases.md](06-usecases.md) | USECASE | 16 | 저작도구가 시작점 또는 종착점인 시나리오만 |
| [07-entities.md](07-entities.md) | ENTITY | 15 | 저작도구 책임 도메인 객체 |
| [08-components.md](08-components.md) | COMPONENT | 16 | Backend + ai-server + Frontend 내부 모듈 + 외부 호출 클라이언트 |
| [09-interfaces.md](09-interfaces.md) | INTERFACE | 4 | 외부 시스템과의 계약만 — Inbound SPI 1(증강 결과 인계) + Outbound 3(비식별/Gitea/외부 VLM). 본 도구 내부 호출(FE↔BE, BE↔ai-server)·JWT 인계(storage 공유)는 등록하지 않음 |
| [10-architecture.md](10-architecture.md) | ARCHITECTURE | 1 | 저작도구 서브시스템 ONLY |
| [11-traceability.md](11-traceability.md) | (링크) | — | 좁혀진 매트릭스 |
| [12-upload-plan.md](12-upload-plan.md) | (실행) | — | 본 폴더 한정 업로드 가이드 (Phase A~F 체크리스트 + 산출물 자동 추출 검증) |
| [13-databases.md](13-databases.md) | DATABASE | 1 | klid_system 공유 DB — 본 도구 사용 LS_*/MNG_*/QRTZ_* 테이블 + 핵심 인덱스 |
| [14-erd.md](14-erd.md) | ERD | 1 | 저작도구 도메인 ERD — Mermaid ER source 보관 (감리·발주처 제출용) |

## 사업 전체 자료(`docs/ccarch/`)와의 관계

- `docs/ccarch/`는 발주처·감리·외부 협력사가 사업 전체 추적성을 확인할 용도 (SYSTEM ARCHITECTURE 포함)
- `docs/ccarch-authoring/`는 **본 도구 개발팀 + 본 도구의 호출 파트너 시스템**과의 통합 설계용
- 두 폴더 모두 ccarch 에 업로드 가능하지만, 같은 workspace 라면 `_handle` 충돌이 없도록 본 폴더는 핸들에 `auth-` 또는 동일 슬러그 유지 + idempotencyKey 로 멱등 처리
- 권장: 본 폴더 자료만 운영 워크스페이스에 업로드하고, 전체 사업 자료는 별도 워크스페이스(또는 readonly 참고 폴더)로 분리. ccarch 의 `workspaceScope` 제약으로 cross-workspace 링크는 불가하므로 추적성이 두 자료군을 가로질러야 한다면 단일 워크스페이스 운영 필수

## ccarch MCP v1 가이드 변경 적응 (2026-05 기준)

ccarch 가이드가 22개 노드 타입 + `attrsSchemas` 자식 자동 ID + **25종** `artifactCatalog` + 5개 신규 read 도구로 확장되었다. 본 폴더는 추적성·외부 계약 + **ERM/DB 설계서**까지 다루며 **11개 타입**을 사용한다: `SOURCE_REQUIREMENT` / `REQUIREMENT` / `USECASE` / `ENTITY` / `COMPONENT` / `INTERFACE` / `ARCHITECTURE` / `ACTOR` / `FEATURE` / **`DATABASE`** / **`ERD`**.

| 신규 타입 | 본 폴더 적용 | 사유 / 위치 |
|---|---|---|
| `ACTOR` | ✅ 적용 (10건) | 사람·내부 4건 + 외부 시스템 6건 — 01/02 |
| `FEATURE` | ✅ 적용 (7건) | 저작도구 직접 책임 기능군 — 03 |
| **`DATABASE`** | ✅ **적용 (1건, 2026-05 추가)** | klid_system 공유 DB — 본 도구 사용 테이블/인덱스만. [13-databases.md](13-databases.md) |
| **`ERD`** | ✅ **적용 (1건, 2026-05 추가)** | 저작도구 도메인 ERD — Mermaid ER source 보관. [14-erd.md](14-erd.md) |
| `SYSTEM_INTERFACE` | ❌ 미적용 | `REALIZES`/`DEPENDS_ON` 의 `allowedTargetTypes` 에 미포함 — 외부 계약 4건은 추적성 링크 호환을 위해 `INTERFACE` 로 유지. artifactCatalog 의 `INTERFACE_DESIGN` 도 source=INTERFACE |
| `SCREEN`, `DESIGN_CLASS`, UML 다이어그램 4종 (UCD/SD/DCD) | ❌ 미적용 | 화면설계서·OO 설계 클래스·UML 다이어그램은 별도 워크스페이스 또는 추후 별도 폴더에서 다룬다 |
| `TESTPLAN`/`TESTSCENARIO`/`TESTCASE`/`CODE`/`GUIDE` | ❌ 미적용 | 본 도구 구현·시험 단계 산출물은 본 폴더 범위 외 |

### 신규 read 도구 5종 활용

| 신규 read 도구 | 본 폴더 활용 |
|---|---|
| `ccarch_list_node_types` | 사전 점검 (가이드 캐시 대신 빠른 22개 타입 검증) |
| `ccarch_list_system_artifact_types` | 사전 점검 — 25종 산출물 카탈로그 확인 (본 폴더는 9종 충족) |
| `ccarch_get_system_artifact` | 업로드 후 9종 산출물 자동 생성 확인 — 분석 3종(REQUIREMENT_SPEC/USECASE_SPEC/REQUIREMENT_TRACEABILITY) + 설계 6종(**CLASS_DESIGN** / **ENTITY_RELATIONSHIP_MODEL** / **DATABASE_DESIGN** / COMPONENT_DESIGN / INTERFACE_DESIGN / ARCHITECTURE_DESIGN) |
| `ccarch_list_revisions` | 노드 update 시 `reason` 누락 여부 점검 |
| `ccarch_get_revision_diff` | 감리·발주처 제출용 변경 근거 (revision A vs B 차이) |

### ENTITY → 5종 산출물 자동 추출 매핑 (Critical)

ENTITY 노드 1건은 가이드의 `usedInArtifacts` 에 따라 5종 산출물의 source 가 된다. `severity=ERROR` 필드 누락 시 추출 차단되므로 [07-entities.md](07-entities.md) 의 15개 ENTITY 는 아래 attrs 키를 모두 채워 두었다.

| 산출물 | 본 폴더 충족 상태 | 비고 |
|---|---|---|
| `CLASS_DESIGN` (전체 클래스 설계서) | ✅ ERROR 충족 (`classId` / `node.title`) + WARN 충족 (`attributes`, `operations` 일부) | INFO 필드(`sequenceDiagram` / `classDiagram` PlantUML) 는 본 폴더 범위 외 |
| **`ENTITY_RELATIONSHIP_MODEL`** (엔티티 관계 모형 기술서) | ✅ ERROR 4종 충족 (`entityId` / `node.title` / `attributes` / `primaryKey`) + WARN 충족 (`relationships`, `notNullConstraints`) + INFO 일부 (`synonym`) | 시각화 본체는 ERD 노드(14-erd.md)에 별도 보관 |
| **`DATABASE_DESIGN`** (데이터베이스 설계서) | ✅ ERROR 4종 충족 (`tableId` / `node.title` / `columns` / `primaryKeyColumns`) | WARN(`capacity` / `maxRows` / `retentionPeriod`) 는 운영 환경 결정 후 보강 |
| `DATABASE_TABLE` (DB 생성 스크립트) | ⚠️ 미충족 — `scriptId` / `databaseId` 미보유 | Flyway V0~V42 별 매핑은 별도 작업 |
| `DATA_MIGRATION_DESIGN` (데이터 전환 설계서) | ⚠️ 미충족 — `purpose` / `targetSystems` / `targetData` / `dataMapping` 미보유 | 본 도구 신규 테이블 0건 정책이라 마이그레이션 범위 협소 — 운영 전환 시점 보강 |

### workspaceScope (단일 워크스페이스 운영 권장)

ccarch 의 `workspaceScope` 제약으로 cross-workspace 링크는 불가하다. 본 폴더(`docs/ccarch-authoring/`)와 사업 전체 자료(`docs/ccarch/`)를 별도 워크스페이스에 업로드하면 추적성 링크가 두 자료군을 가로지를 수 없으므로, **단일 워크스페이스 운영을 권장**한다. `_handle` 슬러그는 본 폴더 내부 식별용이며 등록 시 보내지 않는다 (idempotencyKey 로 멱등 처리).

자세한 호출 순서·템플릿은 [12-upload-plan.md](12-upload-plan.md) 참조.
