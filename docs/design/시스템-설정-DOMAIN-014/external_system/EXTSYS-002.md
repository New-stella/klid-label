---
logicraft_item: EXTSYS-002
type: external_system
version: 11
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-25T10:53:48.342Z
status: NEW
prev_version: null
content_hash: 46422d3339171db5a4254c9b1f2994354b12e582d8052ee560e8e13871b89ee1
stale: false
raw: ./_raw/EXTSYS-002.json
links:
  based_on: ["[[ADR-046]]"]
  provided_by_backward: ["[[INT-002]]", "[[INT-003]]"]
---

# 외부 VLM 시계열 메타 서비스

## kind

other

## status

active

## vendor

IntelliVIX (KLID 연동 API v1.1.0)

## brownfield

### status

new

### decided_by

ADR-046

### change_kind

- component-add

### diff_summary

고도화 신규 — 외부 VLM 시계열 위탁 연동(콜백 수신)

## owner_team

저작도구 연동팀

## criticality

high

## description

영상의 시계열 메타(구간별 설명)를 생성하는 외부 VLM 서비스. **VLM 모델 본체·학습·프롬프트 관리는 외부 책임(범위 외)**이고, 저작도구는 ①위탁 호출(INT-002) ②결과 콜백 수신(INT-003) ③수신한 메타를 `LS_DATA_META` 에 적재하고 REVIEWER 가 라벨링 캔버스 화면(SCREEN-005) 우측 시계열 메타 패널에서 검토·수정하는 것까지를 담당한다.

## 벤더 계약
**IntelliVIX / KLID 연동 API v1.1.0** — 벤더 문서 기반 확정 계약이다.

## 연동 endpoint (KLID 연동 API v1.1.0)
저작도구가 연동하는 대상 API 는 아래와 같다. 이벤트 판정 endpoint(`POST /v1/videovlm-klid/verify`)는 연동 대상이 아니다.

| 방향 | 경로 | 용도 |
|---|---|---|
| outbound (INT-002) | `POST /v1/videovlm-klid/describe` | 묘사(CoT) — 이벤트 관점 영상 상황 서술 |
| outbound (INT-002) | `POST /v1/videovlm-klid/describe-sub` | 추가 질문(VQA) — 이벤트 발생 여부·근거 서술 |
| outbound (INT-002) | `GET /v1/videovlm-klid/events` | 지원 이벤트 목록 조회 |
| outbound (INT-002) | `GET /v1/videovlm-klid/status` | 서버 처리 가능 상태 조회 |
| inbound (INT-003) | 비동기 콜백 수신 | 분석 결과를 콜백으로 수신. 상세 계약은 INT-003 이 소유한다 |

## 인증 체계 — 방향별 비대칭
- **위탁(out)**: `vlm.client.token` 설정 시에만 `Authorization: Bearer` 부착(조건부)
- **콜백(in)**: 벤더가 **무서명 콜백** 규격이라 HMAC 을 적용할 수 없다 → 대체 3계층(① IP allowlist `webhook.vlm.allowed-ip-cidrs` ② rate limit + 본문 크기 상한 ③ `request_id` 발급 게이트). 증강 콜백(EXTSYS-004/INT-006)이 무서명(명세서 v1.1) + IP allowlist(미설정시 fail-closed) 3계층인 것과 유사한 방식이며 각 벤더 규격 차이에 따른 의도된 설계다.

## ★★ 활성화 상태 — 설정 토글은 폐지됐다 (ADR-049)
연동 여부를 설정으로 켜고 끄던 토글은 폐지됐다. 연동 주소가 주입되어 있지 않으면 위탁은 **실패**하며 조용히 건너뛰지 않는다. 구 동작은 토글이 꺼져 있으면 외부 호출 없이 즉시 건너뛴 것으로 처리하고 아무 기록도 남기지 않았는데, 그 기본값이 비활성이라 시계열이 꺼진 채로 납품될 수 있었다.

벤더 미연동 구간의 운영은 **검수자가 사유를 남기고 누르는 시계열 묶음 스킵**으로 처리한다(단건·일괄 모두 제공). 누가 언제 왜 건너뛰었는지가 배치 이력에 남고, 벤더 연동 시점에 해제·재수행으로 되돌릴 수 있다.

| 환경 | 연동 대상 | 비고 |
|---|---|---|
| local | 목업 벤더 서버 | |
| dev | 목업 벤더 서버 | |
| stg | 실제 연동 주소 주입 필요 | |
| prd | 실제 연동 주소 주입 필요 | 콜백 허용 출처 설정이 선행돼야 한다 |
## 신뢰성
- 타임아웃 기본 10s. 배치는 응답을 기다리지 않고 제출만 개시한다(구 45초 블로킹 폐기), Retry `vlmClient` 3/1s/×2(**4xx 재시도 제외**), CB 50%/10/5/open 30s
- 멱등: 웹훅 멱등 원장(`request_id` 기준, `PROCESSED` 재수신 skip) + 비관적 락
- ⚠ **dead-letter 부재** — 실패는 `VLM_FAILED` 종결 마킹만 하고 자동 재요청 잡이 없다. 관제 통지(EXTSYS-005)가 fallback 큐+백오프+DEAD_LETTER 를 갖춘 것과 대비되는 미비점으로, 운영 시 수동 재처리 경로가 필요하다.

## 검증 환경
로컬 목 서버(:9400)가 VLM 연동 endpoint(콜백 포함)를 연기한다. 목 설정 함정: base-url 프로퍼티명은 `vlm.client.url` 이며 `vlm.client.base-url` 이 아니다.

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

_(empty)_

## used_by_domains

_(empty)_

## data_sensitivity

internal

## shared_with_projects

_(empty)_
