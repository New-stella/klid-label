---
logicraft_item: EXTSYS-002
type: external_system
version: 18
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-08T04:11:54.281Z
status: CHANGED
prev_version: 17
content_hash: 1b28d7b0e4f7940aca4ab4cc05d10f88ba7dc644449b96bad7c9c47579b3b0bf
stale: false
raw: ./_raw/EXTSYS-002.json
links:
  attaches: ["[[FILE-041]]"]
  based_on: ["[[ADR-046]]"]
  provided_by_backward: ["[[INT-002]]", "[[INT-003]]"]
---

# 외부 VLM 시계열 메타 서비스

## kind

other

## status

active

## vendor

IntelliVIX (KLID 연동 API v1.2.0)

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

타 시스템

## criticality

high

## description

영상의 시계열 메타(구간별 설명)를 생성하는 외부 VLM 서비스. **VLM 모델 본체·학습·프롬프트 관리는 외부 책임(범위 외)**이고, 저작도구는 ①위탁 호출(INT-002) ②결과 콜백 수신(INT-003) ③수신한 메타를 `LS_DATA_META` 에 적재하고 REVIEWER 가 라벨링 캔버스 화면(SCREEN-005) 우측 시계열 메타 패널에서 검토·수정하는 것까지를 담당한다.

## 벤더 계약
**IntelliVIX / KLID 연동 API v1.2.0**(개정일 2026-09-07)이 근거 규격이다. ⚠ 규격서 파일명 접미가 `_pre` 라 사전배포판일 수 있다 — 개정이력에 승인자까지 적혀 확정본으로 보이나 **벤더 확답은 대기 중**이다.

## 연동 endpoint (KLID 연동 API v1.2.0)
벤더는 분석 창구 넷(`verify`·`describe`·`describe-sub`·`custom`)과 조회 창구 둘을 제공한다. 그중 저작도구가 실제로 호출하는 것은 아래가 전부다.

| 방향 | 경로 | 용도 |
|---|---|---|
| outbound (INT-002) | `POST /v1/videovlm-klid/describe` | 묘사(CoT) — 이벤트 관점 영상 상황 서술 |
| outbound (INT-002) | `POST /v1/videovlm-klid/custom` | 추가 질문 — 요청에 담은 프롬프트로 분석(v1.2.0 신설) |
| outbound (INT-002) | `GET /v1/videovlm-klid/events` | 지원 이벤트 목록 조회 |
| outbound (INT-002) | `GET /v1/videovlm-klid/status` | 서버 처리 가능 상태 조회(위탁 전 확인) |
| inbound (INT-003) | 비동기 콜백 수신 | 분석 결과를 콜백으로 수신. 상세 계약은 INT-003 이 소유한다 |

- **판정 창구 `POST /v1/videovlm-klid/verify` 는 연동 대상이 아니며 이번 개정으로도 바뀌지 않는다**(ADR-051). 규격 §2.1 이 인증 대상을 분석 요청 넷으로 적은 것은 벤더 쪽 적용 범위이지 우리 연동면이 아니다 — `custom` 을 여는 것이 `verify` 를 열 근거가 되지 않는다.
- **[폐기] 추가 질문 창구 `POST /v1/videovlm-klid/describe-sub`** — 벤더가 그 대신 `custom` 을 쓰라고 안내해 우리 연동면에 **두지 않는다**. 벤더는 이 창구를 계속 제공하며, 폐기한 것은 우리 쪽 사용이다.

## 인증 — 방향별 비대칭
- **위탁(out)**: 서버에 API 키가 설정된 환경이면 분석 요청에 **`X-API-Key: <발급받은 키>` 헤더가 필수**다(v1.2.0 §2.1 신설 — v1.1.0 에는 인증 절 자체가 없었다). **`Bearer ` 접두를 쓰지 않는다.** 헤더가 없거나 값이 일치하지 않으면 **401 이고, 그때 콜백은 전송되지 않는다** — 위탁이 아무 신호 없이 사라지므로 401 은 그 자리에서 실패로 종결시켜야 한다. 조회용 GET(`events`·`status`)은 키 없이 호출할 수 있다. ⚠ **키 사용 여부와 값은 규격 밖에서 별도로 전달**되며 아직 수령 전이다(확인 대기 — 추정해 채우지 말 것).
- **콜백(in)**: 벤더가 **무서명 콜백** 규격이라 HMAC 을 적용할 수 없다 → 대체 3계층(① IP allowlist `webhook.vlm.allowed-ip-cidrs` ② rate limit + 본문 크기 상한 ③ `request_id` 발급 게이트). 증강 콜백(EXTSYS-004/INT-006)이 무서명(그쪽 명세서 v1.3) + IP allowlist(미설정시 fail-closed) 3계층인 것과 유사한 방식이며 각 벤더 규격 차이에 따른 의도된 설계다.

## 처리 제한 (v1.2.0 §2.3)
- 업로드 영상 **4GB** / 이미지 **64MB** / `prompt` **4,000자** / `selected_frames` **600개**
- 동시 처리 **32건** 초과 시 429
- 분석 제한 **900초** — 기준은 **추론 대기·수행 구간**이라 프레임 추출을 마치고 추론 대기열에 오른 뒤부터 잰다. 프레임 추출 시간은 여기에 더해진다.
- 콜백 전송 시도 최대 3회 · 각 5초

## 허용 확장자 (v1.2.0 §2.5)
영상 `.mp4 .avi .mov .mkv .webm` / 이미지 `.jpg .jpeg .png .bmp .webp`. 그 밖의 확장자는 경로 전달·업로드 어느 방식이든 400 이다.

## 오류 코드 (v1.2.0 §2.10)
400 · **401**(키를 쓰는 환경의 분석 요청에 한함) · 415 · 429 · 503. **정의되지 않은 `event_type` 인 경우에 한해** 응답 본문에 코드가 함께 실린다(`{"code": 40001, "detail": "..."}`) — 그 밖의 오류는 `detail` 만 온다.

## ★★ 목적지 조달과 활성화 상태 — 장비 원장이 진실원이다 (ADR-049·ADR-057)
위탁 목적지는 **장비 원장에서 고른 시계열 유형 장비의 주소**다. 배포 설정값은 그 유형의 장비가 하나도 없을 때 **최초 1회 씨앗**으로 원장에 첫 행을 심는 데만 쓰이며, 이미 있으면 값이 달라도 덮어쓰지 않는다. **원장에 행이 없을 때 설정값으로 대신 호출하는 폴백은 두지 않는다** — 두면 진실원이 둘이 된다.

연동 여부를 설정으로 켜고 끄던 토글은 폐지됐다. **그 유형에 쓸 수 있는 후보가 하나도 없으면 위탁을 거부**하며 조용히 건너뛰지 않는다. 사유는 가리지 않는다 — 장비 식별자 형식 위반이든 상태점검 실패로 그 유형의 장비가 전부 이용불가가 된 것이든 같다. 구 동작은 토글이 꺼져 있으면 외부 호출 없이 즉시 건너뛴 것으로 처리하고 아무 기록도 남기지 않았는데, 그 기본값이 비활성이라 시계열이 꺼진 채로 납품될 수 있었다.

⚠ **상태점검도 위 표의 상태 조회 창구를 읽는다.** 사전 확인은 게이트가 아니고, 상태점검은 연속 실패 임계에 누적돼 후보에서 뺀다. 상태 값 매핑은 INTSPEC-003 이 정한다. 두 축을 섞지 말 것.

⚠ **구 서술 폐기(2026-09-08)** — *"연동 주소가 주입되어 있지 않으면 위탁은 실패한다"* 는 목적지를 단일 설정값으로 읽은 서술이라 더 이상 사실이 아니다. 거부를 정하는 것은 설정값의 유무가 아니라 원장에 쓸 수 있는 장비가 있는가이다.

벤더 미연동 구간의 운영은 **검수자가 사유를 남기고 누르는 시계열 묶음 스킵**으로 처리한다(단건·일괄 모두 제공). 누가 언제 왜 건너뛰었는지가 배치 이력에 남고, 벤더 연동 시점에 해제·재수행으로 되돌릴 수 있다.

| 환경 | 연동 대상 | 비고 |
|---|---|---|
| local | 목업 벤더 서버 | |
| dev | 목업 벤더 서버 | |
| stg | 실제 벤더 장비를 원장에 등록해야 한다 | |
| prd | 실제 벤더 장비를 원장에 등록해야 한다 | 콜백 허용 출처 설정이 선행돼야 한다 |
## 신뢰성
- 타임아웃 기본 10s. 배치는 응답을 기다리지 않고 제출만 개시한다(구 45초 블로킹 폐기), Retry `vlmClient` 3/1s/×2(**4xx 재시도 제외**), CB 50%/10/5/open 30s
- 멱등: 웹훅 멱등 원장(`request_id` 기준, `PROCESSED` 재수신 skip) + 비관적 락
- ⚠ **dead-letter 부재** — 실패는 `VLM_FAILED` 종결 마킹만 하고 자동 재요청 잡이 없다. 관제 통지(EXTSYS-005)가 fallback 큐+백오프+DEAD_LETTER 를 갖춘 것과 대비되는 미비점으로, 운영 시 수동 재처리 경로가 필요하다.

## 검증 환경
로컬 목 서버(:9400)가 VLM 연동 endpoint(콜백 포함)를 연기한다. 목 설정 함정: base-url 프로퍼티명은 `vlm.client.url` 이며 `vlm.client.base-url` 이 아니다.

## environments

### dev

- **notes**: 사업자가 제공한 개발 검증용 주소. 경로 접두 /v1/videovlm-klid 는 모든 창구에 공통이다. 조회 창구(status·events)는 인증 키 없이 부를 수 있고 분석 요청은 키가 설정된 환경에서만 헤더가 필요하다. 운영 주소는 미수신 — 지어내 채우지 말 것.
- **base_url**: http://211.170.82.250:28000

## attached_files

- FILE-041

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

### module_paths

_(empty)_

## compliance_tags

_(empty)_

## used_by_domains

_(empty)_

## data_sensitivity

internal

## shared_with_projects

_(empty)_
