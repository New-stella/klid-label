# MCP 업로드 실행 계획 — 저작도구 서브시스템

> ccarch MCP 도구 (`mcp__ccarch__ccarch_create_node`, `mcp__ccarch__ccarch_create_link`, `mcp__ccarch__ccarch_get_node`) 호출 순서·idempotency·검증 가이드.
> 본 폴더(`docs/ccarch-authoring/`) 자료만 업로드 대상.

## 0. 사전 점검

1. `mcp__ccarch__ccarch_get_guide` — 노드/관계 타입 가이드 재확인 (10분 캐시, 응답이 클 수 있으므로 다음 두 도구 우선)
2. `mcp__ccarch__ccarch_list_node_types` — 사용 가능한 23개 노드 타입 목록 (빠른 검증용, get_guide 보다 가벼움)
3. `mcp__ccarch__ccarch_list_system_artifact_types` — 산출물 카탈로그 25종 목록 확인 (본 폴더는 REQUIREMENT_SPEC / USECASE_SPEC / REQUIREMENT_TRACEABILITY / CLASS_DESIGN / **ENTITY_RELATIONSHIP_MODEL** / **DATABASE_DESIGN** / COMPONENT_DESIGN / INTERFACE_DESIGN / ARCHITECTURE_DESIGN 대상)
4. `mcp__ccarch__ccarch_get_wiki_stats` — 베이스라인 노드/링크 수 기록
5. UUID v4 생성기 준비 (각 호출마다 새 idempotencyKey)

### 본 폴더에서 사용하는 노드 타입 (11종)

ccarch 가이드 v1 은 23개 노드 타입을 지원한다 (기존 13종 + 신규 10종: ACTOR/FEATURE/USECASE_DIAGRAM/SEQUENCE_DIAGRAM/DESIGN_CLASS_DIAGRAM/ERD/DESIGN_CLASS/SCREEN/SYSTEM_INTERFACE/DATABASE). 본 폴더는 **11개 타입**을 사용한다:

| 분류 | 타입 | 파일 | 건수 |
|---|---|---|---:|
| 추적성 시작점 | `SOURCE_REQUIREMENT` | 04-source-requirements.md | 7 |
| 추적성 시작점 | `ACTOR` | 01-external-systems.md + 02-actors.md | 10 |
| 추적성 시작점 | `FEATURE` | 03-features.md | 7 |
| 정제 요구사항 | `REQUIREMENT` | 05-requirements.md | 15 |
| 시나리오 | `USECASE` | 06-usecases.md | 16 |
| 도메인 모델 | `ENTITY` | 07-entities.md | 15 |
| 모듈 | `COMPONENT` | 08-components.md | 16 |
| 외부 계약 | `INTERFACE` | 09-interfaces.md | 4 |
| 상위 문서 | `ARCHITECTURE` | 10-architecture.md | 1 |
| **물리 DB** | `DATABASE` | **13-databases.md** | **1** |
| **ER 다이어그램** | `ERD` | **14-erd.md** | **1** |
| **합계** | | | **93** |

### 본 폴더에서 사용하지 않는 신규 노드 타입 (참고)

| 신규 타입 | 본 폴더 미사용 사유 |
|---|---|
| `SYSTEM_INTERFACE` | `REALIZES`/`DEPENDS_ON` 의 `allowedTargetTypes` 에 미포함 — 본 폴더 외부 계약 4건은 추적성 링크 호환을 위해 `INTERFACE` 로 유지 (artifactCatalog 의 `INTERFACE_DESIGN` 도 source=INTERFACE) |
| `SCREEN`, `DESIGN_CLASS`, `USECASE_DIAGRAM`, `SEQUENCE_DIAGRAM`, `DESIGN_CLASS_DIAGRAM` | 본 폴더는 추적성·외부 계약 + ERM/DB 중심. UI 화면설계서·UML 다이어그램·OO 설계 클래스는 별도 워크스페이스(`docs/ccarch/` 또는 추후 추가)에서 다룬다 |
| `TESTPLAN` / `TESTSCENARIO` / `TESTCASE` / `CODE` / `GUIDE` | 본 도구 구현·시험 단계 산출물은 본 폴더 범위 외 |

## 1. 등록 순서 — Target 먼저 → Source 다음 → Link 마지막

### Phase A. 추적성 시작점 (target 후보)
1. **SOURCE_REQUIREMENT 7건** ← `04-source-requirements.md`
2. **ACTOR 사람·내부 4건** ← `02-actors.md`
3. **ACTOR 외부 시스템 6건** ← `01-external-systems.md`
4. **FEATURE 7건** ← `03-features.md`

### Phase B. 도메인 노드
5. **REQUIREMENT 15건** ← `05-requirements.md`
6. **ENTITY 15건** ← `07-entities.md` *(attrs ERROR 필드 5종 산출물 충족: classId/entityId/tableId/primaryKey/primaryKeyColumns/attributes/columns)*
7. **DATABASE 1건** ← `13-databases.md` *(klid_system 공유 DB — tables 자식 배열에 본 도구 사용 LS_*/MNG_*/QRTZ_* 명시)*

### Phase C. 시나리오 / 모듈 / 계약
8. **USECASE 16건** ← `06-usecases.md`
9. **COMPONENT 16건** ← `08-components.md`
10. **INTERFACE 4건** ← `09-interfaces.md` (외부 시스템 계약만)

### Phase D. 상위 문서
11. **ERD 1건** ← `14-erd.md` *(저작도구 도메인 ERD — Mermaid ER source 보관)*
12. **ARCHITECTURE 1건** ← `10-architecture.md`

### Phase E. 링크 (양 끝 노드 모두 존재한 후 일괄)
13. DERIVES_FROM (REQUIREMENT → SOURCE_REQUIREMENT) — 15건
14. DERIVES_FROM (USECASE → REQUIREMENT) — 약 20건
15. PERFORMED_BY (USECASE → ACTOR) — 약 20건
16. BELONGS_TO (USECASE → FEATURE) — 16건
17. REALIZES (USECASE → COMPONENT|INTERFACE) — 약 30건 (INTERFACE 는 외부 계약 시나리오에만)
18. DEPENDS_ON (COMPONENT → COMPONENT|INTERFACE|ENTITY) — 약 30건 (내부 COMPONENT 간 + 외부 INTERFACE 4개 + ENTITY)
19. REFERS_TO (ARCHITECTURE → DATABASE | ERD) — 2건
20. REFERS_TO (ERD → DATABASE) — 1건
21. REFERS_TO (ARCHITECTURE → 기타) — 핵심 노드 참조

## 2. 호출 템플릿

### 노드 등록 (예: SOURCE_REQUIREMENT)
```jsonc
mcp__ccarch__ccarch_create_node({
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-08] 학습데이터 저작도구 고도화 기능 구현",
  "content": "...",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.32", "priority": "HIGH"},
  "idempotencyKey": "uuid-v4-here",
  "reason": "초기 등록 — 본 폴더 v1"   // 권장: wiki_revisions 스냅샷에 보존됨
})
```

응답의 `node_id` 를 `11-traceability.md` 의 ID 맵에 채워 보관. `_handle` 슬러그(예: `sr-sfr-08`)는 본 폴더 내부 식별용이며 ccarch 등록 시에는 보내지 않는다.

> **노드 수정 시 reason 필수 권장**: 동일 노드를 update 호출할 때 `reason: "내용 보정: SFR-08 우선순위 HIGH→MED"` 같은 메시지를 함께 보내면 `ccarch_list_revisions` / `ccarch_get_revision_diff` 로 감사 추적이 가능하다.

### ENTITY 등록 시 attrs ERROR 필드 누락 방지 (Critical)

`ENTITY` 1건은 5종 산출물(CLASS_DESIGN / ENTITY_RELATIONSHIP_MODEL / DATABASE_DESIGN / DATABASE_TABLE / DATA_MIGRATION_DESIGN)의 source 가 된다. **ERROR severity 필드 누락 시 산출물 추출이 차단된다.** 본 폴더는 다음을 ENTITY 마다 채워 둠 (07-entities.md 참조).

| 산출물 | 충족 키 (ERROR) | 충족 키 (WARN/INFO 권장) |
|---|---|---|
| CLASS_DESIGN | `attrs.classId`, `node.title` | `attrs.attributes`, `attrs.operations` |
| ENTITY_RELATIONSHIP_MODEL | `attrs.entityId`, `node.title`, `attrs.attributes`, `attrs.primaryKey` | `attrs.relationships`, `attrs.notNullConstraints`, `attrs.synonym` |
| DATABASE_DESIGN | `attrs.tableId`, `node.title`, `attrs.columns`, `attrs.primaryKeyColumns` | `attrs.capacity`, `attrs.maxRows`, `attrs.retentionPeriod` |
| DATABASE_TABLE | `attrs.scriptId`, `node.title`, `attrs.databaseId`, `attrs.tableId` | `attrs.indexId`, `attrs.installLocation` |
| DATA_MIGRATION_DESIGN | `attrs.purpose`, `attrs.targetSystems`, `attrs.targetData`, `attrs.dataMapping` | `attrs.organization`, `attrs.tasks`, `attrs.systemSchedule` |

**현재 본 폴더 충족 범위**: 상위 3종(CLASS_DESIGN / ERM / DATABASE_DESIGN)의 ERROR 필드 전체 + 권장 WARN/INFO 일부. 하위 2종(DATABASE_TABLE / DATA_MIGRATION_DESIGN)은 추후 운영 환경 보강.

### 비-ENTITY 노드 attrs ERROR 필드 (참고)

ENTITY 외 다른 노드 타입도 가이드 `usedInArtifacts.fields.severity=ERROR` 가 정의되어 있다. 본 폴더는 모두 충족하도록 attrs 를 보강했다 (05/06/08/09/10).

| 노드 타입 | 산출물 | ERROR 필드 | 본 폴더 처리 |
|---|---|---|---|
| REQUIREMENT | REQUIREMENT_SPEC | `requirementId` (attrs), `requirementName` (title✅), `description` (bodyMd≈content), `category` (attrs✅) | 05-requirements.md — `attrs.requirementId` = _handle 슬러그 추가. description 은 content(=bodyMd) 매핑 가정 |
| USECASE | USECASE_SPEC | `usecaseId` (attrs), `usecaseName` (title✅), `primaryActors` (attrs), `mainScenario` (bodyMd≈content) | 06-usecases.md — `attrs.usecaseId` + `attrs.primaryActors` (string[]). 기존 `attrs.actor` 키는 제거. mainScenario 는 content 매핑 |
| COMPONENT | COMPONENT_DESIGN | `componentId` (attrs), `componentName` (title✅) | 08-components.md — `attrs.componentId` = _handle 슬러그 추가 |
| INTERFACE | INTERFACE_DESIGN | `interfaceNo` (attrs), `senderSystem` (attrs), `receiverSystem` (attrs) | 09-interfaces.md — 4건 모두 추가 (Inbound/Outbound 방향 명시) |
| INTERFACE | UI_DESIGN | `screenId`, `screenName`, `ioFields` | **추출 대상 외** — 본 폴더 INTERFACE 는 외부 시스템 계약(API/SPI)이라 UI 화면 의미 아님. F-3 참조 |
| ARCHITECTURE | ARCHITECTURE_DESIGN | `softwareArchitectureDiagram` (attrs), `architecturePattern` (attrs) | 10-architecture.md — Mermaid graph 다이어그램 + "Modular Monolith + Sidecar Inference" 패턴 명시 |

> **ID 정책**: 본 폴더 모든 *Id (requirementId/usecaseId/componentId/interfaceNo) 는 각 노드의 `_handle` 슬러그를 동일 값으로 재사용한다. 별도 외부 ID 체계를 도입하지 않아 추적성·중복 방지·검색 가독성을 모두 확보.

### 링크 등록 (예: DERIVES_FROM)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "DERIVES_FROM",
  "fromNodeId": "<req-version-gitea 의 node_id>",
  "toNodeId":   "<sr-sfr-08 의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

> **workspaceScope**: 모든 등록은 ServiceToken 에 바인딩된 단일 workspace 내에서만 동작한다. `fromNodeId`/`toNodeId` 양쪽이 같은 workspace 에 사전 존재해야 하며 cross-workspace 링크는 불가. 본 폴더와 `docs/ccarch/` 를 별도 워크스페이스에 업로드한다면 cross-link 가 불가하므로 단일 워크스페이스 운영을 권장.

## 3. 검증 절차

1. `mcp__ccarch__ccarch_get_wiki_stats` 다시 호출 — 노드 **+93** (SR 7 + ACTOR 10 + FEATURE 7 + REQ 15 + ENT 15 + **DB 1** + UC 16 + COMP 16 + IF 4 + **ERD 1** + ARCH 1) / 링크 +133 내외 증가 확인
2. `mcp__ccarch__ccarch_search_nodes` 로 핵심 노드 샘플 조회
3. `mcp__ccarch__ccarch_get_node` 로 추적성 체인 끝점(uc-label-edit, comp-label-canvas, ent-data-raw, db-klid-system, erd-authoring) 백링크 확인
4. `mcp__ccarch__ccarch_list_revisions` 로 등록 노드 reason 이력 점검 → 누락된 reason 은 update 호출로 보강
5. (수정 발생 시) `mcp__ccarch__ccarch_get_revision_diff` 로 변경 전후 비교 — 감리·발주처 보고용 근거
6. **산출물 자동 추출 검증** — `mcp__ccarch__ccarch_get_system_artifact` 로 다음을 호출:
   - `INTERFACE_DESIGN` (sourceNodeType=INTERFACE) — 4건 INTERFACE 모두 포함되는지
   - `COMPONENT_DESIGN` (sourceNodeType=COMPONENT) — 16건 COMPONENT 모두 포함되는지
   - `ARCHITECTURE_DESIGN` (sourceNodeType=ARCHITECTURE) — 1건 포함되는지
   - **`CLASS_DESIGN` (sourceNodeType=ENTITY)** — 15건 ENTITY 모두 포함되는지 / classId 충족 확인
   - **`ENTITY_RELATIONSHIP_MODEL` (sourceNodeType=ENTITY)** — 15건 ENTITY 모두 포함 / entityId·attributes·primaryKey 충족 확인 / relationships(WARN) 누락 경고 점검
   - **`DATABASE_DESIGN` (sourceNodeType=ENTITY)** — 15건 ENTITY 모두 포함 / tableId·columns·primaryKeyColumns 충족 확인
   - `REQUIREMENT_SPEC` / `USECASE_SPEC` / `REQUIREMENT_TRACEABILITY` — 분석 단계 3종
   - 미충족 ERROR 필드가 보고되면 해당 ENTITY 의 attrs 보강 후 update 재호출

   **본 폴더에서 추출 대상이 아닌 산출물 (참고)**
   - `UI_DESIGN` (sourceNodeType=INTERFACE) — **추출 대상 아님**. 본 폴더의 INTERFACE 4건은 외부 시스템과의 **계약(API/SPI)** 이며 UI 화면이 아니다. UI_DESIGN 은 SCREEN 노드 또는 화면 의미의 INTERFACE 가 등록된 별도 워크스페이스(`docs/ccarch/` 또는 추후 추가)에서 추출한다. 본 폴더에서 `get_system_artifact("UI_DESIGN")` 호출 시 4건이 들어가지만 의미 없는 데이터이므로 호출하지 않는다.
   - `DATABASE_TABLE` / `DATA_MIGRATION_DESIGN` (sourceNodeType=ENTITY) — 본 폴더 ENTITY attrs 에 `scriptId` / `databaseId` / `purpose` / `targetSystems` 등이 미보강이므로 ERROR 보고가 예상됨. 운영 전환 시점에 별도 보강.
   - 시험 단계 산출물 (OVERALL_TEST_PLAN / SYSTEM/INTEGRATION/UNIT/ACCEPTANCE_TEST_* / USER_MANUAL / OPERATOR_MANUAL / SYSTEM_INSTALL_RESULT / PROGRAM_CODE) — 본 폴더 범위 외 (TESTPLAN/TESTSCENARIO/TESTCASE/CODE/GUIDE 노드 미등록).

   **DATABASE / ERD 노드의 위치 (Critical 해명)**
   - DATABASE / ERD 노드는 가이드 v1 의 `usedInArtifacts` 에 산출물 source 로 등록되어 있지 않다. 즉 **자체로는 시스템 산출물을 직접 생성하지 않는다.**
   - 본 폴더에서 두 노드를 등록하는 이유는 **(a) 물리 DB 컨텍스트 보관** (DATABASE — 13-databases.md), **(b) 도메인 ERD 다이어그램 source 보관** (ERD — 14-erd.md, 발주처 제출용 SVG/PNG 추출 소스) 이며, ARCHITECTURE 노드에서 `REFERS_TO` 로 연결되어 아키텍처 설계서(ARCHITECTURE_DESIGN 산출물)의 부록·참조 자료로 노출된다.
   - ENTITY → DATABASE / ERD 간 명시 link 는 가이드 매트릭스에 없다. 두 노드와 ENTITY 의 매칭은 attrs 의 자연 키(테이블명 일치, scope 명시)로 유지하며 `REFERS_TO` 남발을 피한다 (잡음 방지).

7. 누락 발견 시 `_handle` ↔ `node_id` 매핑 갱신 후 보완

## 4. 회복 (재시도/롤백)

- **idempotencyKey 가 같으면** 24시간 내 재호출 안전 (기존 결과 반환)
- **새 키로 호출 시** 중복 생성됨 → 동일 노드 의도면 같은 키 재사용
- **중간 실패 시** 이미 등록된 노드의 매핑을 `11-traceability.md` 에 갱신 후 다음 단계 진행
- 노드/링크 삭제 API 는 가이드에 명시되지 않음 → 운영자가 admin 도구로 워크스페이스 청소

## 5. 실행 체크리스트

```
[ ] 0-1. ccarch_get_guide + list_node_types + list_system_artifact_types 베이스라인 확인
[ ] 0-2. ccarch_get_wiki_stats 베이스라인 노드/링크 수 기록
[ ] 0-3. UUID v4 생성기 준비 (idempotencyKey 용)

[ ] A-1. SOURCE_REQUIREMENT 7건 등록 → node_id 매핑
[ ] A-2. ACTOR 사람·내부 4건 등록
[ ] A-3. ACTOR 외부 시스템 6건 등록
[ ] A-4. FEATURE 7건 등록

[ ] B-1. REQUIREMENT 15건 등록
[ ] B-2. ENTITY 15건 등록 (attrs ERROR 5종 키 모두 채움)
[ ] B-3. DATABASE 1건 등록 (klid_system / tables 자식 배열)

[ ] C-1. USECASE 16건 등록
[ ] C-2. COMPONENT 16건 등록
[ ] C-3. INTERFACE 4건 등록 (외부 계약만)

[ ] D-1. ERD 1건 등록 (Mermaid ER source 포함)
[ ] D-2. ARCHITECTURE 1건 등록

[ ] E-1. DERIVES_FROM (REQ→SR) 15건
[ ] E-2. DERIVES_FROM (UC→REQ) ~20건
[ ] E-3. PERFORMED_BY (UC→ACTOR) ~20건
[ ] E-4. BELONGS_TO (UC→FEATURE) 16건
[ ] E-5. REALIZES (UC→COMP|IF) ~30건
[ ] E-6. DEPENDS_ON (COMP→COMP|IF|ENT) ~30건
[ ] E-7. REFERS_TO (ARCH→DB) 1건
[ ] E-8. REFERS_TO (ARCH→ERD) 1건
[ ] E-9. REFERS_TO (ERD→DB) 1건
[ ] E-10. REFERS_TO (ARCH→기타) 핵심 노드

[ ] F-1. wiki_stats 재조회 + search_nodes 샘플 검증
[ ] F-2. list_revisions 로 reason 누락 점검 (없으면 보강)
[ ] F-3. get_system_artifact — INTERFACE_DESIGN / COMPONENT_DESIGN / ARCHITECTURE_DESIGN 산출물 자동 생성 결과 확인
[ ] F-4. get_system_artifact — **CLASS_DESIGN / ENTITY_RELATIONSHIP_MODEL / DATABASE_DESIGN** 산출물 자동 생성 결과 확인 — ERROR 필드 누락 시 attrs 보강 후 update 재호출
[ ] F-5. get_system_artifact — REQUIREMENT_SPEC / USECASE_SPEC / REQUIREMENT_TRACEABILITY 분석 3종 확인
```

## 6. 자동화 권장

본 폴더의 14개 마크다운 파일에서 ```json``` 블록만 추출 → 노드 등록 호출 자동화하는 간단 스크립트(`scripts/ccarch-upload-authoring.sh` 또는 .ts/.py)로 표준화하면 운영 부담이 크게 줄어든다. `11-traceability.md` 의 매트릭스도 표 파싱으로 링크 호출에 활용 가능.

업로드 후 산출물 자동화도 함께 운영하면 좋다 — `ccarch_list_system_artifact_types` 로 본 폴더가 채울 수 있는 산출물(분석 3종 + 설계 6종)을 확인하고, `ccarch_get_system_artifact` 결과를 발주처 제출 산출물 폴더에 배치하는 파이프라인을 구성한다. 특히 **CLASS_DESIGN / ENTITY_RELATIONSHIP_MODEL / DATABASE_DESIGN 3종은 ENTITY 노드 1회 등록만으로 자동 생성**되므로 ENTITY attrs 충실도를 우선 확보한다.
