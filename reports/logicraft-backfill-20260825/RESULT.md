# LogiCraft 설계 부채 백필 — 2026-08-25

## 결과

| 모드 | 대상 | 처리 |
|---|---|---|
| A. CO 부채 | **0건** | `MASTER.md` 의 `설계반영` 이 전부 🎨 또는 해당없음 — dispatch 가 선반영하므로 정상 |
| C. IMPREC 갭 | 313건 중 근거 보유 **49건** | **채움**(264건은 `@design` 근거가 없어 제외 — 추정 금지) |
| B①. 코드엔 태그·설계는 planned | **12건** | **채움**(전건 코드 실물 확인 후) |
| B②. stale 표식 | 273건 | **기록만** — `stale-inventory.md` (사용자 확정) |

프로젝트 전체 IMPREC 보유: **38 → 97** (+59).

## 채운 59건

| 타입 | 건수 |
|---|---|
| `acceptance` | 20 |
| `api_endpoint` | 20 |
| `screen_spec` | 8 |
| `erd` | 5 |
| `domain_feature` | 3 |
| `permission_role` | 1 |
| `diagram_sequence` | 1 |
| `use_case` | 1 |

전문: `AC-001`, `AC-016`, `AC-041`, `AC-042`, `AC-044`, `AC-045`, `AC-046`, `AC-047`, `AC-048`, `AC-049`, `AC-050`, `AC-051`, `AC-052`, `AC-053`, `AC-054`, `AC-055`, `AC-071`, `AC-072`, `AC-073`, `AC-075`, `API-024`, `API-028`, `API-056`, `API-068`, `API-123`, `API-152`, `API-167`, `API-177`, `API-185`, `API-195`, `API-196`, `API-199`, `API-205`, `API-206`, `API-207`, `API-208`, `API-209`, `API-210`, `API-211`, `API-215`, `DFEAT-048`, `DFEAT-057`, `DFEAT-059`, `ERD-012`, `ERD-015`, `ERD-021`, `ERD-027`, `ERD-031`, `ROLE-003`, `SCREEN-001`, `SCREEN-002`, `SCREEN-003`, `SCREEN-004`, `SCREEN-019`, `SCREEN-027`, `SCREEN-034`, `SCREEN-039`, `SEQ-001`, `UC-035`

## 채우지 못한 2건 — 타입이 추적을 지원하지 않는다

`DEPLOY-001`(deployment_spec) · `INT-007`(integration_point) → 서버가 **`E_NOT_TRACKABLE`** 로 거부한다.
코드 근거는 둘 다 있으나(`ServletInitializer` · `TaskCompletedPayload`) 그 타입에는 추적 기록을 붙일 수 없다.
⚠ 이 둘을 「미구현」으로 읽지 말 것 — **기록할 자리가 없는 것**이지 구현이 없는 것이 아니다.

## 근거 규칙

- 판정은 **`@design` 태그 + 그 태그를 도입한 커밋**(`git log -S`)이다. 태그가 없으면 채우지 않았다.
- 태그는 「주장」이라 **12건은 코드를 열어 실물을 확인**했다. 그중 둘이 약해 따로 검증했다 —
  `ERD-021`(시험에만 태그 → 테이블·엔티티 실재 확인) · `API-195`(다른 클래스가 참조만 → 컨트롤러·서비스·DTO 확인).

## 쓰기 전에 붙잡아 둔 stale (14건)

`create_implementation_record` 는 **stale 을 자동 해제**한다. 그 표식이 이 축과 무관한 미해결 전파를
가리키고 있었으면 원인은 그대로인데 신호만 사라진다. 아래가 지워진 표식이다.

| ITEM | 지워진 stale 사유 |
|---|---|
| `API-195` | REQ-009의 data.constraints 변경이 implements로 2-hop 전파 |
| `API-205` | AC-048의 2개 필드 변경 — verifies |
| `API-206` | AC-048의 2개 필드 변경 — verifies |
| `API-209` | AC-043의 3개 필드 변경 — verifies |
| `API-210` | AC-043의 3개 필드 변경 — verifies |
| `API-215` | AC-046의 3개 필드 변경 — verifies |
| `DFEAT-048` | AC-016의 2개 필드 변경 — verifies |
| `DFEAT-057` | AC-048의 2개 필드 변경 — verifies |
| `DFEAT-059` | AC-044의 3개 필드 변경 — verifies |
| `ROLE-003` | SCREEN-034의 data.sections 변경 — granted_on |
| `SCREEN-034` | UC-027의 data.covered_by_acceptances 변경 — realizes |
| `SCREEN-039` | AC-047의 3개 필드 변경 — covered_by |
| `SEQ-001` | INT-002의 data.implementation 변경 — references |
| `UC-035` | AC-048의 2개 필드 변경 — covered_by |

## 이번 라운드가 남긴 함정

**도구가 성공을 보고했는데 2건이 반영되지 않았다.** LogiCraft MCP 는 오류를 JSON-RPC `error` 가 아니라
**`result.isError`** 안에 넣는다. `error` 키만 검사한 배치 스크립트는 그것을 **성공으로 셌다**.
서버에서 다시 내려받아 대조하지 않았으면 「61건 완료」로 잘못 보고할 뻔했다.

⇒ MCP 를 스크립트로 부를 때는 **`result.isError` 를 반드시 함께 검사**하고, 그와 별개로 **읽기 대조**를 한다.
