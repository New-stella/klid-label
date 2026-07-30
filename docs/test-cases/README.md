# 학습데이터 저작도구 — 테스트 케이스 위키

> 전수 테스트 케이스 카탈로그
> 최초 도출 2026-07-25 · **최신화 2026-07-30(1회차)**
> 범위: BE 단위+통합 · FE 컴포넌트/화면 · ai-server · 외부 벤더 목업 계약 · E2E + 보안 전 계층
> 기준: 실제 구현 코드 + R1 요구/수용기준 양면 전수
> **검증 시 해당 클러스터 파일만 로드하세요** (토큰 절약)

## 최신화 이력

| 회차 | 일자 | 대상 변경 | 정정 | 신규 | 폐기 | 총 케이스 |
|:--:|------|------|--:|--:|--:|--:|
| — | 2026-07-25 | (최초 도출) | — | — | — | **1,376**(실측) |
| **1** | **2026-07-30** | 07-25 이후 38커밋(BE 332파일 · FE 73파일, Phase 1~10) | **942** | **531** | **15** | **1,907** |

> 회차별 상세(무엇을 왜 고쳤는지)는 **각 클러스터 파일 상단의 `## 변경 이력` 섹션**에 있습니다.
> - 07-25 최초 카탈로그의 `~1,210` 은 추정치였고 **표 행 실측은 1,376** 이었습니다(1,376 + 신규 531 = 1,907).
> - 정정 942건의 대부분은 **근거 `file:line` 드리프트 정정**이며, 기대결과·전제가 실제로 뒤바뀐 건수는 각 담당이 분류한 범위에서 B 32 · C 33 · H 21 건입니다(나머지 클러스터는 별도 분류하지 않음).

## 검증 순서 (권장 — 위에서부터 차례로)

> **A→H 순서가 곧 검증 순서**입니다. 의존성·데이터 흐름(토대 → 데이터 입구 → 작업 → 종결 → 파생 → 외부채널 → 추론 → 통합) 기준으로 정렬돼 있어, 앞 단계에서 토대 결함을 먼저 걸러냅니다.

| 순서 | 파일 | 도메인 | 케이스 수 | 왜 이 순서 | ID 프리픽스 |
|:--:|------|--------|:---:|------|------|
| **1** | [A-auth-common.md](A-auth-common.md) | 인증/권한/공통 인프라 | **233** | 모든 도메인이 의존하는 **토대**(인증·응답·예외·듀얼DS·기동 가드) | TC-AUTH / TC-AUTHZ / TC-CORS / TC-STREAM / TC-HMAC / TC-CLAIM / TC-TRACE / TC-RESP / TC-EXC / TC-DS / TC-CACHE / TC-SYSCFG / TC-PROF / TC-ACT / TC-RES / TC-ROLE / TC-COMMON / **TC-LOG · TC-SORT · TC-BLANK · TC-CFG · TC-HEALTH** |
| **2** | [B-batch-deidentify.md](B-batch-deidentify.md) | 배치 파이프라인/비식별화 | **330** | **데이터 입구**(적재→비식별→파이프라인). 동시성·PII 고위험 | TC-BATCH / TC-DEID / TC-VLM / TC-STREAM |
| **3** | [C-marking-labeling.md](C-marking-labeling.md) | 마킹/라벨링 | **275** | 적재된 데이터에 대한 **핵심 작업** | TC-MARK / TC-LABEL / TC-SAM2 / TC-KEYPOINT / TC-TRACK / TC-PRESET |
| **4** | [D-review-version-notify.md](D-review-version-notify.md) | 검수/버전관리/관제통지 | **197** | 작업을 닫는 **워크플로우 종결**(승인→스냅샷→export→통지) | TC-REVIEW / TC-ASSIGN / TC-VERSION / TC-DIFF / TC-NOTIFY / TC-MARTVIEW |
| **5** | [E-augment-resolution-export-meta.md](E-augment-resolution-export-meta.md) | 증강/해상도/Export/메타 | **247** | 검수 완료 후 나오는 **파생 산출물** + 외부 위탁 | TC-AUG / TC-RESL / TC-EXPORT / TC-META |
| **6** | [F-portal.md](F-portal.md) | 포털(외부 채널) | **169** | 내부 파이프라인과 **분리된 외부 채널** | TC-PORTAL / TC-PORTALUP / TC-TUS |
| **7** | [G-ai-server.md](G-ai-server.md) | ai-server + **외부 벤더 목업 계약** | **163** | BE와 계약으로만 연결된 **독립 추론 서버**(언제든 병행 가능) + 로컬·dev 검증이 전부 경유하는 목업 계약 | TC-AIYOLO / TC-AISAM2 / TC-AIVLM / TC-AICONTRACT / TC-AIINFRA / **TC-AIMOCK** |
| **8** | [H-frontend-e2e.md](H-frontend-e2e.md) | FE 화면/컴포넌트/E2E | **293** | 전 계층을 통합하는 **최상위**. BE 안정 후 E2E가 의미 있음 → 마지막 | TC-FE / TC-E2E / TC-A11Y |
| — | [UNCERTAINTIES.md](UNCERTAINTIES.md) | 확정 정책 + 확인 필요 항목 | — | 검증 내내 PASS/FAIL 판정 기준 | — |
| | **합계** | | **1,907** | | |

> 순서는 권장일 뿐 강제는 아닙니다. 특정 도메인만 급하면 그 클러스터부터 지정해도 됩니다. G(ai-server)는 독립적이라 어느 시점에나 끼워 넣을 수 있습니다.

## 표 컬럼 규약

| 컬럼 | 의미 |
|------|------|
| ID | 케이스 고유 식별자 |
| 케이스명 | 한글 서술형. 끝의 `(신규)` = 2026-07-30 회차 추가분 |
| 전제 | 테스트 사전 조건 |
| 입력/조건 | 투입 입력·시나리오 |
| 기대결과 | 검증할 단언 |
| 계층 | unit / integration / component / e2e / a11y / security |
| 우선순위 | H(High)·M(Medium)·L(Low) 또는 P0(Critical)~P2 |
| 근거 | file:line (클릭 가능) |

### 폐기 케이스 표기

정책 변경으로 무효가 된 케이스는 **행을 삭제하지 않고** `~~취소선~~` + 기대결과 칸에 `**[폐기 {일자}]** 사유 + 대체 케이스`로 남깁니다.
1차 검증 결과 문서(`docs/검증결과/`)가 TC ID를 참조하므로 추적성을 위해 보존합니다. **폐기 케이스는 검증 대상이 아닙니다.**

## 검증 실행 방법

| 방법 | 진입점 | 용도 |
|------|--------|------|
| 슬래시 커맨드 | `/verify-tc {클러스터} [회차]` (예: `/verify-tc B`, `/verify-tc C 2차`) | 이 저장소 안에서 실행 (`.claude/commands/verify-tc.md`) |
| **이식용 프롬프트** | **[VERIFY-PROMPT.md](VERIFY-PROMPT.md)** 내용을 복사해 새 세션에 붙여넣기 | **다른 컴퓨터·다른 환경**에서 동일하게 실행 (자립형 — 커맨드·memory 의존 없음) |

검증 기준: **목업서버 기동 + 배치 실구동한 정상 시나리오 위에서 실동작 판정**. 외부 연동은 전부 `mock-server`(:9400) 경유하며, 본 프로그램이 외부 응답 없이 값을 자체 생성하면(self-fill) 결함입니다. 결과는 `docs/검증결과/{날짜}/{회차}/` 에 회차별로 쌓입니다.

## 검증 시 참고

- **판정 기준은 [UNCERTAINTIES.md](UNCERTAINTIES.md) 의 "확정 정책(★)" 절을 먼저 읽으세요.** 그중 3건은 **결함으로 재보고하면 안 되는 확정 정책**입니다 — ①신고 게이트 판정 범위 = 자기 rawSn 행 하나(조상/자손 전파는 도입 후 철회) ②목록 정렬 strict(400) vs lenient(200 폴백) 비대칭 ③좌표 검증 2축(사용자 저장=400 거부 / AI 검출=clamp).
- 다수 케이스는 기존 테스트로 **부분 커버**되어 있습니다 → 신규 작성 전 중복 확인 권장.
  테스트 자산(2026-07-30 실측): BE `*Test.java`/`*IT.java` **516 파일** · FE `*.test.*` **290 파일** · E2E `e2e/specs/*.spec.ts` **11 파일** · ai-server `tests/` **13 파일** · mock-server **9 파일**.
- 최신 기준선: **BE `cleanTest` 4,196 tests / 실패 0 / skip 5**(라이브 목서버 IT) · **FE 1,674 tests**.
- 우선순위 P0/H(보안·상태전이·동시성)부터 검증 착수 권장.
