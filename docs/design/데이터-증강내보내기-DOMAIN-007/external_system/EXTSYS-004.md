---
logicraft_item: EXTSYS-004
type: external_system
version: 8
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:02:44.960Z
status: CHANGED
prev_version: 7
content_hash: 44cc660e229f925468c2eea939d77e8f2e709bb9596c5f1e8275c88ccb2be2f0
stale: false
raw: ./_raw/EXTSYS-004.json
links:
  provided_by_backward: ["[[INT-006]]", "[[INT-008]]"]
---

# 외부 생성형 AI(증강) 시스템

## kind

other

## status

active

## vendor

딥러닝 (관제 정본 EXTSYS-002 기재 — 저작도구 계약 미확정)

## brownfield

### status

new

### decided_by

ADR-004

### change_kind

- component-replace

### diff_summary

1차 내부 증강 → 2차 외부 생성형 AI 연동(결과 검수)

## owner_team

저작도구 연동팀

## criticality

medium

## description

날씨·계절·시간 증강(WINTER/NIGHT/RAIN) 영상을 생성하는 외부 시스템. 생성·영상합성 모델 본체는 외부 책임(범위 외)이며, 저작도구는 위탁 요청(INT-008)·결과 콜백 수신(INT-006)·결과 검수만 담당한다.

## ★★ 위탁·콜백 구성
- 외부 위탁(`authoring.augment.external.mode=http`, 공통 기본값)은 **실제 HTTP 호출**이다 — `POST {base}/api/genai/jobs`, Resilience4j(`augmentClient`) 재시도/서킷 적용.
- 콜백 수신부는 `POST /v1/genai/callback` 이다.
- 구 콜백 경로 `/v1/aug/callback`(HMAC 서명 필수) + 내부 self-fill 콜백 시뮬레이터는 **폐기**되었다.
- **job_id 발급 주체가 반전되었다** — 이전에는 저작도구가 발급해 전달했으나, 현재는 **벤더가 202 응답(`GenAiJobAcceptedResponse`)으로 발급**한 `job_id` 를 이후 모든 상관관계 키로 사용한다(명세서 v1.1 §4.1 그대로 반영).

## 환경별 실배선 현황
| 환경 | authoring.augment.external.mode | 실질 |
|---|---|---|
| local | 오버라이드 없음(공통 기본값 `http` 상속) | **실배선** — mock-server(:9400) 대상 |
| dev | `noop`(명시 오버라이드) | 미연동 |
| stg | `noop`(명시 오버라이드) | 미연동 |
| prd | `noop`(명시 오버라이드, 주석에 "실연동 시 http+base-url 전환" 명시) | 미연동 |

⚠ **미연동 모드에서는 요청을 접수하지 않는다 (ADR-049)** — 연동이 설정되지 않았으면 요청 시점에 거부한다. 구 동작은 접수만 하고 외부로 보내지 않아 요청이 진행 중으로 보이다가 한참 뒤 만료 회수로 실패 종결됐고, 그 환경에서 증강을 쓸 수 없다는 사실을 요청자가 즉시 알 수 없었다. 미전송 동작 자체는 그대로이며 접수 단계 거부만 더한 것이다.

## 벤더 계약
관제지원시스템이 확정한 **「생성형 AI API 연동명세서 v1.1」**(관제 LogiCraft `EXTSYS-002`, 근거 `ADR-101`, 2026-07-24)을 그대로 준용한다(§4.1·§4.2 정합). 다만 **저작도구 자체 명의의 규격서를 벤더/관제로부터 별도 수령한 이력은 없다** — 관제 확정 문서를 준용해 구현한 상태이며, 공식 규격서 확정 여부는 재확인 대상.

## 위탁·콜백 요지 (INT-008/INT-006 요약, 상세는 각 ITEM 참조)
- 위탁: `request_channel=AUTHORING` 고정, `operation_type=AUGMENT`, `generation_mode=I2I`(이미지→이미지) 고정. 입력파일 최대 100장/job(초과 시 청크 분할, `authoring.augment.external.max-input-files` 기본 100).
- 콜백: 인증 없음(명세서 v1.1 이 무서명 규격) — IP allowlist(미설정 시 fail-closed)+rate limit/size cap+request_id 게이트 3계층으로 대체 방어.

## 범위 외 — 해상도 변경
해상도 변경(SFR-06-03)은 본 외부 시스템 범위가 아니다 — 저작도구 내부 수행이며 `LS_DATA_AUG` 에 `AUG_TYPE_CD=RESL_1080P/RESL_720P/RESL_480P` 로 통합 저장(ADR-018).

## 검증 환경
로컬 목 서버 `mock-server`(:9400)가 명세서 v1.1 계약으로 외부 생성형 AI 를 연기한다.

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

- DOMAIN-007

## data_sensitivity

internal

## shared_with_projects

_(empty)_
