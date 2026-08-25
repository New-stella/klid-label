# LogiCraft stale 표식 전수 — 2026-08-25 실측 (기록만, 이번 라운드에서 손대지 않음)

활성 ITEM 중 `stale` 표식이 선 것 **273건**.

## 타입별

| 타입 | 건수 |
|---|---|
| `code_module` | 46 |
| `acceptance` | 43 |
| `domain_feature` | 28 |
| `screen_design` | 25 |
| `api_endpoint` | 22 |
| `diagram_sequence` | 20 |
| `use_case` | 20 |
| `screen_spec` | 19 |
| `class_diagram` | 10 |
| `nfr` | 8 |
| `diagram_c4_component` | 7 |
| `feature` | 6 |
| `domain` | 5 |
| `permission_role` | 3 |
| `test_scenario` | 3 |
| `adr` | 2 |
| `integration_point` | 2 |
| `integration_spec` | 2 |
| `app_shell` | 1 |
| `navigation_tree` | 1 |

## 성격 분리

- **파생 전파(구조적 소음)**: 231건 — 상위 ITEM 한 필드가 바뀌면 그것을 realize·verify·derive 하는 하위가 자동으로 stale 이 된다. 이 저장소가 이미 규칙으로 다룬 패턴이라 개별 판정 가치가 낮다.
- **그 밖**: 42건 — 아래 전문. 이쪽에 진짜 드리프트가 숨어 있을 수 있다.

## 파생 전파가 아닌 것 (우선 판정 후보)

| ITEM | 타입 | 사유 |
|---|---|---|
| `DOMAIN-001` | domain | DOMAIN-005의 data.brownfield 변경 — collaborates_with |
| `DOMAIN-006` | domain | DOMAIN-005의 data.brownfield 변경 — collaborates_with |
| `DOMAIN-007` | domain | DOMAIN-005의 data.brownfield 변경 — collaborates_with |
| `DOMAIN-010` | domain | DOMAIN-005의 data.brownfield 변경 — collaborates_with |
| `DOMAIN-011` | domain | INT-002의 data.implementation 변경 — depends_on |
| `FEAT-010` | feature | ADR-048의 3개 필드 변경 — based_on |
| `MOD-022` | code_module | MOD-004의 2개 필드 변경 — depends_on |
| `NFR-008` | nfr | DOMAIN-011의 data.description 변경 — applies_to |
| `NFR-009` | nfr | DOMAIN-005의 data.brownfield 변경 — applies_to |
| `NFR-010` | nfr | DOMAIN-005의 data.brownfield 변경 — applies_to |
| `NFR-011` | nfr | DOMAIN-011의 data.description 변경 — applies_to |
| `NFR-015` | nfr | DOMAIN-013의 2개 필드 변경 — applies_to |
| `NFR-017` | nfr | API-065의 data.request_body 변경 — applies_to |
| `NFR-018` | nfr | DOMAIN-011의 data.description 변경 — applies_to |
| `NFR-019` | nfr | DOMAIN-011의 data.description 변경 — applies_to |
| `SCREEN-010` | screen_spec | ROLE-001의 data.permissions 변경 — requires |
| `SCREEN-011` | screen_spec | API-042의 data.parameters 변경 — consumes |
| `SCREEN-022` | screen_spec | API-042의 data.parameters 변경 — consumes |
| `SCREEN-028` | screen_spec | API-203의 data.description 변경 — consumes |
| `SCREEN-032` | screen_spec | ROLE-001의 data.permissions 변경 — requires |
| `SCREEN-036` | screen_spec | API-097의 data.brownfield 변경 — consumes |
| `SCREEN-037` | screen_spec | API-108의 data.brownfield 변경 — consumes |
| `SD-001` | screen_design | SCREEN-018의 data.sections 변경 — designs |
| `SD-004` | screen_design | SCREEN-009의 data.sections 변경 — designs |
| `SD-005` | screen_design | SCREEN-019의 data.sections 변경 — designs |
| `SD-007` | screen_design | SCREEN-030의 data.brownfield 변경 — designs |
| `SD-008` | screen_design | SCREEN-036의 data.brownfield 변경 — designs |
| `SD-010` | screen_design | SCREEN-031의 data.consumes_apis 변경 — designs |
| `SD-013` | screen_design | SCREEN-008의 data.sections 변경 — designs |
| `SD-020` | screen_design | SCREEN-004의 2개 필드 변경 — designs |
| `SD-021` | screen_design | SCREEN-032의 data.realizes_use_cases 변경 — designs |
| `SD-023` | screen_design | SCREEN-038의 data.sections 변경 — designs |
| `SD-024` | screen_design | SCREEN-028의 data.sections 변경 — designs |
| `SD-028` | screen_design | SCREEN-022의 data.purpose 변경 — designs |
| `SD-029` | screen_design | SCREEN-023의 data.sections 변경 — designs |
| `SD-032` | screen_design | SCREEN-010의 data.realizes_use_cases 변경 — designs |
| `SD-033` | screen_design | SCREEN-027의 5개 필드 변경 — designs |
| `SEQ-005` | diagram_sequence | API-020의 data.responses 변경 — consumes |
| `SEQ-006` | diagram_sequence | API-093의 data.responses 변경 — consumes |
| `SEQ-008` | diagram_sequence | API-014의 data.responses 변경 — consumes |
| `SEQ-010` | diagram_sequence | API-014의 data.responses 변경 — consumes |
| `SEQ-011` | diagram_sequence | API-190의 data.responses 변경 — consumes |

## 왜 지금 손대지 않는가

사용자 확정(2026-08-25). 대부분이 파생 전파라 소득 대비 비용이 크고, 그 안에 섞인 진짜 드리프트는
**타입 단위가 아니라 「결정」 단위**로 훑어야 잡힌다(같은 결정이 여러 층에 복제되기 때문).
이 목록은 그 라운드를 시작할 때의 입력이다.

⚠ **이 목록은 그때그때 낡는다** — `update_item` 이 stale 을 자동 해제하므로,
다른 작업이 그 ITEM 을 건드리면 표식만 사라지고 원인은 남는다. 착수 시 재실측할 것.
