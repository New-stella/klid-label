# MCP 업로드 실행 계획

> ccarch MCP 도구 (`mcp__ccarch__ccarch_create_node`, `mcp__ccarch__ccarch_create_link`, `mcp__ccarch__ccarch_get_node`) 호출 순서·idempotency·검증 가이드.
> 본 사업 워크스페이스 ServiceToken이 활성화된 세션에서 실행한다.

## 0. 사전 점검

1. `mcp__ccarch__ccarch_get_guide` 호출 → 노드 타입 12+2종, 관계 6+2종 가이드 확인 (이미 완료)
2. `mcp__ccarch__ccarch_get_wiki_stats` 호출 → 현재 워크스페이스 노드/링크 수 확인 (멱등성 베이스라인)
3. UUID v4 생성기 준비 — 각 호출마다 새 `idempotencyKey` 전달 (24시간 내 동일 키 재호출은 기존 결과 반환)

## 1. 등록 순서 — Target 먼저 → Source 다음 → Link 마지막

ccarch 컨벤션: 링크는 fromNodeId/toNodeId 양쪽 노드가 같은 workspace에 사전 존재해야 한다.

### Phase A. 추적성 시작점 (target 후보)
1. **SOURCE_REQUIREMENT 25건** ← `03-source-requirements.md`
2. **ACTOR 4건** ← `01-actors.md`
3. **FEATURE 10건** ← `02-features.md`

### Phase B. 도메인 노드 (target 후보 + source)
4. **REQUIREMENT 30건** ← `04-requirements.md` (DERIVES_FROM → SOURCE_REQUIREMENT 곧 연결 가능)
5. **ENTITY 15건** ← `06-entities.md`

### Phase C. 시나리오 / 모듈 / 계약
6. **USECASE 22건** ← `05-usecases.md`
7. **COMPONENT 14~20건** ← `07-components.md`
8. **INTERFACE 12건** ← `08-interfaces.md`

### Phase D. 상위 문서
9. **ARCHITECTURE 2건** ← `09-architecture.md`

### Phase E. 링크 (양 끝 노드 모두 존재한 후 일괄)
10. DERIVES_FROM 링크 (REQUIREMENT → SOURCE_REQUIREMENT)
11. DERIVES_FROM 링크 (USECASE → REQUIREMENT)
12. PERFORMED_BY 링크 (USECASE → ACTOR)
13. BELONGS_TO 링크 (USECASE → FEATURE)
14. REALIZES 링크 (USECASE/REQUIREMENT → COMPONENT|INTERFACE)
15. DEPENDS_ON 링크 (COMPONENT → COMPONENT|INTERFACE|ENTITY)
16. REFERS_TO 링크 (ARCHITECTURE → 기타)

## 2. 노드 등록 호출 템플릿

각 파일의 JSON 블록을 그대로 `mcp__ccarch__ccarch_create_node` 입력으로 사용한다. 단 `_handle`은 우리 내부 슬러그이므로 호출 시 제거하고, 응답 `node_id`를 `10-traceability.md`의 ID 맵에 채워 넣는다.

### 예시 — SOURCE_REQUIREMENT 등록
```jsonc
mcp__ccarch__ccarch_create_node({
  "type": "SOURCE_REQUIREMENT",
  "title": "[SFR-01] VLM 기반 수집 영상 수집·정제 기능 개발",
  "content": "...",
  "attrs": {
    "sourceDoc": "제안요청서(수정)_260303.pdf",
    "sourcePage": "p.28",
    "priority": "HIGH",
    "originalText": "..."
  },
  "idempotencyKey": "uuid-v4-here"
})
```

응답에서 `node_id`(또는 동등 식별자)를 받아 본 폴더 내 `_handle: "sr-sfr-01"` 항목에 매핑하여 보관한다.

### 예시 — REQUIREMENT 등록
```jsonc
mcp__ccarch__ccarch_create_node({
  "type": "REQUIREMENT",
  "title": "VLM 1차 필터로 클립영상-메타 정합성을 검증한다",
  "content": "...",
  "attrs": {"priority": "HIGH", "category": "FUNCTIONAL"},
  "idempotencyKey": "uuid-v4-here"
})
```

### 예시 — USECASE 등록
```jsonc
mcp__ccarch__ccarch_create_node({
  "type": "USECASE",
  "title": "VLM 1차 필터로 영상-메타 일치도 검증 후 수집",
  "content": "...",
  "attrs": {"actor": "BATCH_SYSTEM"},
  "idempotencyKey": "uuid-v4-here"
})
```

## 3. 링크 등록 호출 템플릿

`10-traceability.md`의 매트릭스를 한 줄씩 처리한다.

### 예시 — DERIVES_FROM (REQUIREMENT → SOURCE_REQUIREMENT)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "DERIVES_FROM",
  "fromNodeId": "<req-vlm-filter의 node_id>",
  "toNodeId":   "<sr-sfr-01의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

### 예시 — PERFORMED_BY (USECASE → ACTOR)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "PERFORMED_BY",
  "fromNodeId": "<uc-label-edit의 node_id>",
  "toNodeId":   "<actor-worker의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

### 예시 — BELONGS_TO (USECASE → FEATURE)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "BELONGS_TO",
  "fromNodeId": "<uc-label-edit의 node_id>",
  "toNodeId":   "<feat-labeling-authoring의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

### 예시 — REALIZES (USECASE → COMPONENT)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "REALIZES",
  "fromNodeId": "<uc-label-edit의 node_id>",
  "toNodeId":   "<comp-label-canvas의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

### 예시 — DEPENDS_ON (COMPONENT → INTERFACE)
```jsonc
mcp__ccarch__ccarch_create_link({
  "type": "DEPENDS_ON",
  "fromNodeId": "<comp-ai-server-client의 node_id>",
  "toNodeId":   "<if-ai-server-spi의 node_id>",
  "idempotencyKey": "uuid-v4-here"
})
```

## 4. 검증 절차 (등록 후)

1. `mcp__ccarch__ccarch_get_wiki_stats` 다시 호출 → 노드 +110건(SR 25 + ACTOR 4 + FEATURE 10 + REQ 30 + ENT 15 + USECASE 22 + COMPONENT 14~20 + INTERFACE 12 + ARCH 2) / 링크 +200+건 증가 확인
2. `mcp__ccarch__ccarch_search_nodes` 로 핵심 노드 5개 정도 샘플 조회
3. `mcp__ccarch__ccarch_get_node` 로 추적성 체인 끝점(uc-label-edit, comp-label-canvas 등) 상세 + 백링크 확인
4. 누락 링크는 `_handle` 슬러그 기반으로 재조회 후 보완

## 5. 회복 (재시도/롤백)

- **일부 실패**: idempotencyKey가 같으면 24시간 내 재호출은 안전. 새 키로 호출하면 중복 생성됨 → 주의.
- **전부 롤백**: 노드/링크 삭제 API는 가이드에 명시되지 않음. 운영자가 admin 도구로 워크스페이스 청소.
- **중간 실패 시**: 이미 등록된 노드 `_handle ↔ node_id` 매핑을 `10-traceability.md`에 갱신 후 다음 단계 진행.

## 6. 실행 순서 체크리스트

```
[ ] 0-1. ccarch_get_guide 호출 + workspace 통계 baseline
[ ] 0-2. UUID v4 생성기 준비 (Python uuid4 등)

[ ] A-1. SOURCE_REQUIREMENT 25건 등록 → node_id 매핑
[ ] A-2. ACTOR 4건 등록
[ ] A-3. FEATURE 10건 등록

[ ] B-1. REQUIREMENT 30건 등록
[ ] B-2. ENTITY 15건 등록

[ ] C-1. USECASE 22건 등록
[ ] C-2. COMPONENT 14~20건 등록
[ ] C-3. INTERFACE 12건 등록

[ ] D-1. ARCHITECTURE 2건 등록

[ ] E-1. DERIVES_FROM (REQ→SR) ~ 35건
[ ] E-2. DERIVES_FROM (UC→REQ) ~ 25건
[ ] E-3. PERFORMED_BY (UC→ACTOR) 22건
[ ] E-4. BELONGS_TO (UC→FEATURE) 22건
[ ] E-5. REALIZES (UC→COMP|IF) ~ 30건
[ ] E-6. DEPENDS_ON (COMP→COMP|IF|ENT) ~ 25건
[ ] E-7. REFERS_TO (ARCH→기타) ~ 15건

[ ] F. wiki_stats 재조회 + search_nodes 샘플 검증
```

## 7. 운영 팁

- 한 번에 다 올리지 말고 Phase 단위로 끊어서 등록하면 실패 시 회복이 쉽다.
- 등록 직후 `mcp__ccarch__ccarch_get_node`로 1~2건만 즉시 검증해서 schema/필수필드 위반 여부 빠르게 확인.
- 노드 수정 시 `reason` 필드를 같이 보내면 감사 추적 용이 (wiki_revisions 스냅샷).
- 자기참조(fromNodeId == toNodeId)는 거절되므로 ARCHITECTURE 셀프 REFERS_TO는 만들지 않는다.
- 멱등성 보장을 위해 본 폴더의 각 노드별 슬러그(`_handle`)와 그에 매핑된 idempotencyKey 목록을 별도 파일(`_idem-keys.csv`)로 보관 권장.

## 8. 자동화 스크립트 권장 (선택)

본 폴더의 11개 마크다운 파일에서 ```json``` 블록만 추출 → 노드 등록 호출 자동화하는 간단 스크립트를 작성하면 운영 부담이 크게 줄어든다. 핸드오프 시 `scripts/ccarch-upload.sh` (또는 .ts/.py)로 표준화 가능.
