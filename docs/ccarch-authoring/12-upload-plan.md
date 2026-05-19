# MCP 업로드 실행 계획 — 저작도구 서브시스템

> ccarch MCP 도구 (`mcp__ccarch__ccarch_create_node`, `mcp__ccarch__ccarch_create_link`, `mcp__ccarch__ccarch_get_node`) 호출 순서·idempotency·검증 가이드.
> 본 폴더(`docs/ccarch-authoring/`) 자료만 업로드 대상.

## 0. 사전 점검

1. `mcp__ccarch__ccarch_get_guide` — 노드/관계 타입 가이드 재확인
2. `mcp__ccarch__ccarch_get_wiki_stats` — 베이스라인 노드/링크 수 기록
3. UUID v4 생성기 준비 (각 호출마다 새 idempotencyKey)

## 1. 등록 순서 — Target 먼저 → Source 다음 → Link 마지막

### Phase A. 추적성 시작점 (target 후보)
1. **SOURCE_REQUIREMENT 7건** ← `04-source-requirements.md`
2. **ACTOR 사람·내부 4건** ← `02-actors.md`
3. **ACTOR 외부 시스템 6건** ← `01-external-systems.md`
4. **FEATURE 7건** ← `03-features.md`

### Phase B. 도메인 노드
5. **REQUIREMENT 15건** ← `05-requirements.md`
6. **ENTITY 15건** ← `07-entities.md`

### Phase C. 시나리오 / 모듈 / 계약
7. **USECASE 16건** ← `06-usecases.md`
8. **COMPONENT 16건** ← `08-components.md`
9. **INTERFACE 4건** ← `09-interfaces.md` (외부 시스템 계약만)

### Phase D. 상위 문서
10. **ARCHITECTURE 1건** ← `10-architecture.md`

### Phase E. 링크 (양 끝 노드 모두 존재한 후 일괄)
11. DERIVES_FROM (REQUIREMENT → SOURCE_REQUIREMENT) — 15건
12. DERIVES_FROM (USECASE → REQUIREMENT) — 약 20건
13. PERFORMED_BY (USECASE → ACTOR) — 약 20건
14. BELONGS_TO (USECASE → FEATURE) — 16건
15. REALIZES (USECASE → COMPONENT|INTERFACE) — 약 30건 (INTERFACE 는 외부 계약 시나리오에만)
16. DEPENDS_ON (COMPONENT → COMPONENT|INTERFACE|ENTITY) — 약 30건 (내부 COMPONENT 간 + 외부 INTERFACE 4개 + ENTITY)
17. REFERS_TO (ARCHITECTURE → 기타) — 핵심 노드 참조

## 2. 호출 템플릿

### 노드 등록 (예: SOURCE_REQUIREMENT)
```jsonc
mcp__ccarch__ccarch_create_node({
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-08] 학습데이터 저작도구 고도화 기능 구현",
  "content": "...",
  "attrs": {"sourceDoc": "제안요청서(수정)_260303.pdf", "sourcePage": "p.32", "priority": "HIGH"},
  "idempotencyKey": "uuid-v4-here"
})
```

응답의 `node_id` 를 `11-traceability.md` 의 ID 맵에 채워 보관. `_handle` 슬러그(예: `sr-sfr-08`)는 본 폴더 내부 식별용이며 ccarch 등록 시에는 보내지 않는다.

### 링크 등록 (예: DERIVES_FROM)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "DERIVES_FROM",
  "fromNodeId": "<req-version-gitea 의 node_id>",
  "toNodeId":   "<sr-sfr-08 의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

## 3. 검증 절차

1. `mcp__ccarch__ccarch_get_wiki_stats` 다시 호출 — 노드 +76 (SR 7 + ACTOR 10 + FEATURE 7 + REQ 15 + ENT 15 + UC 16 + COMP 16 + IF 4 + ARCH 1) / 링크 +130 내외 증가 확인
2. `mcp__ccarch__ccarch_search_nodes` 로 핵심 노드 샘플 조회
3. `mcp__ccarch__ccarch_get_node` 로 추적성 체인 끝점(uc-label-edit, comp-label-canvas 등) 백링크 확인
4. 누락 발견 시 `_handle` ↔ `node_id` 매핑 갱신 후 보완

## 4. 회복 (재시도/롤백)

- **idempotencyKey 가 같으면** 24시간 내 재호출 안전 (기존 결과 반환)
- **새 키로 호출 시** 중복 생성됨 → 동일 노드 의도면 같은 키 재사용
- **중간 실패 시** 이미 등록된 노드의 매핑을 `11-traceability.md` 에 갱신 후 다음 단계 진행
- 노드/링크 삭제 API 는 가이드에 명시되지 않음 → 운영자가 admin 도구로 워크스페이스 청소

## 5. 실행 체크리스트

```
[ ] 0-1. ccarch_get_guide + workspace 통계 baseline 기록
[ ] 0-2. UUID v4 생성기 준비

[ ] A-1. SOURCE_REQUIREMENT 7건 등록 → node_id 매핑
[ ] A-2. ACTOR 사람·내부 4건 등록
[ ] A-3. ACTOR 외부 시스템 6건 등록
[ ] A-4. FEATURE 7건 등록

[ ] B-1. REQUIREMENT 15건 등록
[ ] B-2. ENTITY 15건 등록

[ ] C-1. USECASE 16건 등록
[ ] C-2. COMPONENT 16건 등록
[ ] C-3. INTERFACE 4건 등록 (외부 계약만)

[ ] D-1. ARCHITECTURE 1건 등록

[ ] E-1. DERIVES_FROM (REQ→SR) 15건
[ ] E-2. DERIVES_FROM (UC→REQ) ~20건
[ ] E-3. PERFORMED_BY (UC→ACTOR) ~20건
[ ] E-4. BELONGS_TO (UC→FEATURE) 16건
[ ] E-5. REALIZES (UC→COMP|IF) ~30건
[ ] E-6. DEPENDS_ON (COMP→COMP|IF|ENT) ~30건
[ ] E-7. REFERS_TO (ARCH→기타) 핵심 노드

[ ] F. wiki_stats 재조회 + search_nodes 샘플 검증
```

## 6. 자동화 권장

본 폴더의 12개 마크다운 파일에서 ```json``` 블록만 추출 → 노드 등록 호출 자동화하는 간단 스크립트(`scripts/ccarch-upload-authoring.sh` 또는 .ts/.py)로 표준화하면 운영 부담이 크게 줄어든다. `11-traceability.md` 의 매트릭스도 표 파싱으로 링크 호출에 활용 가능.
