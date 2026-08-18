---
logicraft_item: EXTSYS-003
type: external_system
version: 8
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-16T14:48:55.728Z
status: NEW
prev_version: null
content_hash: ec8b1f1f24332d5a21b03318c3a853ae70924decf90ef80a383fa71c020efb07
stale: false
raw: ./_raw/EXTSYS-003.json
links:
  provided_by_backward: ["[[INT-004]]", "[[INT-005]]"]
---

# KPST (외부 비식별 솔루션)

## kind

regulatory

## status

active

## vendor

발주기관 제공

## brownfield

### status

new

### decided_by

ADR-006

### change_kind

- component-replace

### diff_summary

캔버스 수동 블러 → 외부 비식별 솔루션 연동

## owner_team

저작도구 연동팀

## criticality

critical

## description

영상 비식별화(개인정보 마스킹)를 수행하는 외부 솔루션. 비식별화는 **파이프라인 선두 단계**로 영상 적재 직후 자동 트리거되며, 성공해야 마킹·라벨링 단계로 진입한다(전체 영상 비식별 정책).

## 연동 2종 (상세는 INT-004 / INT-005)
| 구분 | 경로 | 비고 |
|---|---|---|
| 위탁 (INT-004) | `POST /project` | 응답 `prj_id` 가 이후 폴링 키 |
| 폴링 (INT-005) | `GET /retrieve_progress` (**GET 이면서 바디에 JSON**) | KPST 는 콜백을 제공하지 않아 **폴링이 유일한 완료 감지 수단** |
| 정리 | `POST /delete_project_id` | 재위탁 전 stale export 제거 |

## 인증
**없음**. `https` 스킴일 때만 자체 CA(`ca.crt`)로 서버 인증서를 검증하며(클라이언트 인증서 없음), `http` 내부망 운영 시엔 평문이다 — 망 분리가 사실상의 방어선.

## 활성화 실태 (2026-07-27 실측)
`kpst.deid.enabled` 기본 true. 다만 **mock-mode**(`authoring.integration.deidentify.mock-mode`)가 갈라다:
- **local·dev**: mock-mode `true` → KPST 실사용 안 함
- **stg·prd**: mock-mode `false` → **실제 KPST 폴링 활성**
- **prd 에서 mock-mode=true 면 부트 거부**(`assertMockAllowedProfile()`, allowlist=local/dev/stg) — 운영에서 가짜 비식별로 넣는 사고를 fail-closed 로 차단

※ 로컬 검증은 내부 mock 모드가 아니라 **목 서버(:9400)를 바라보게 배선**하는 것이 운영 원칙이다.

## ★ 운영상 중요 사실
1. **결과 파일명 규칙** — 응답 `fileName` 은 산출물명이 아니라 **원본 입력파일의 절대경로**이고, 실제 결과물은 `{stem}-mask{ext}`. 2026-07-21 실서버 curl 실측으로 확정됐으며, 과거 이를 오해해 완료 건을 'F' 로 오종결하던 결함이 수정됐다
2. **완료 판정 가드** — 도착 파일이 존재하고 0바이트 초과해야만 `deIdntfYn='Y'`. 빈 파일을 성공으로 오판하면 비식별 안 된 영상이 라벨링으로 흘러들어가므로 **PII 방어선**
3. **procState 해석** — `2`=완료, `3/4/99`=터미널 실패 즉시 'F', 그 외는 진행중
4. **dead-letter 없음** — 타임아웃(180분)·터미널 실패는 'F' 마킹만 하고 자동 재시도가 없다. **외부 솔루션으로 수동 재비식별 후 resolve** 하는 것이 확정된 운영 정책(자동 재비식별 큐는 폐기됨)

## environments

_(empty)_

## implementation

### status

implemented

### modules

_(empty)_

### records

_(empty)_

### progress

100

### subtasks

_(empty)_

## compliance_tags

- isms

## used_by_domains

- DOMAIN-012

## data_sensitivity

pii

## shared_with_projects

_(empty)_
