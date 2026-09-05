---
logicraft_item: INTSPEC-003
type: integration_spec
version: 13
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-05T00:43:41.834Z
status: CHANGED
prev_version: 12
content_hash: 811d33f0b40f40d7a2c7f97636c445feac4fd78efe74b4d2d85466c368878660
stale: true
raw: ./_raw/INTSPEC-003.json
links:
  references: ["[[INT-002]]"]
  references_backward: ["[[INT-002]]"]
---

# VLM 시계열 위탁 요청 규격 (KLID 연동 API v1.1.0 준수)

## status

draft

## version

1.1.0

## spec_kind

markdown

## attached_files

_(empty)_

## change_summary

KLID 연동 API v1.1.0 기준 이중 위탁 규격. endpoint 를 /v1/videovlm/verify 단일에서 /v1/videovlm-klid/describe(묘사·CoT) + /v1/videovlm-klid/describe-sub(추가 질문·VQA) 둘로 교체하고 각각 별도 request_id 를 발급한다. frame_policy 에서 추출 간격(framerate)을 없애고, 마킹 본문에서 프레임 인덱스를 얻으면 frame_selected mode + selected_frames(상한 600)로 지정하되 하나도 얻지 못하면 frame_interval mode 로 내린다. 위탁 전 status·events 사전 확인, 429 재시도 대상 분류, 503·415 처리, 분석 제한 900초를 추가한다.

## content_inline

# VLM 시계열 위탁 요청 규격 (저작도구 아웃바운드)

> 근거: 외부 벤더 확정 규격 『KLID 연동 API v1.1.0』(IntelliVIX Video VLM). 저작도구가 마킹 완료된 비식별 영상의 시계열 서술 생성을 벤더에 위탁한다.
> 짝 콜백 = INT-003 / INTSPEC-002.
> 위탁은 **묘사(CoT)** 와 **추가 질문(VQA)** 두 endpoint 로 **이중 위탁**한다. 이벤트 판정(verify) endpoint 는 연동하지 않는다.

## 1. 엔드포인트

| 용도 | Method | Path | 비고 |
|------|:---:|------|------|
| 묘사(CoT) 위탁 | POST | `/v1/videovlm-klid/describe` | 이벤트 관점에서 영상 상황(장소·날씨·상황)을 서술한다 |
| 추가 질문(VQA) 위탁 | POST | `/v1/videovlm-klid/describe-sub` | 이벤트 발생 여부와 근거를 서술한다 |
| 지원 이벤트 조회 | GET | `/v1/videovlm-klid/events` | `describe_events` / `describe_sub_events` 목록 |
| 서버 상태 조회 | GET | `/v1/videovlm-klid/status` | `ready` / `busy` / `loading` |

| 항목 | 값 |
|------|-----|
| base-url | `vlm.client.url` |
| Content-Type | application/json (위탁 2종). 다른 타입은 벤더가 `415` 로 거부한다 |
| 인증 | `vlm.client.token` 설정 시에만 `Authorization: Bearer <token>` 부착(조건부), 미설정 시 무헤더 |
| 타임아웃 | HTTP 클라이언트 `vlm.client.timeout-seconds` 기본 10s. 배치 단계는 응답을 기다리지 않고 제출만 개시한다(구 45초 블로킹 폐기 — 외부가 느려지면 배치 풀이 통째로 마른다) |

> ⚠ **구 규격 폐기**: 단일 위탁 endpoint `POST /v1/videovlm/verify`(『Video VLM API v2.0.1』 기준, 일치도+서술 1회 회신)는 더 이상 연동 대상이 아니다. 그 경로로 보내지 않는다.

## 2. 요청 바디 (describe · describe-sub 공통)

두 위탁의 요청 형식은 **완전히 같다**. 다른 것은 경로와 발급하는 `request_id` 뿐이다.

```json
{
  "request_id": "00000001",
  "event_type": "fall",
  "media": {
    "type": "video",
    "source_type": "path",
    "path": "/data/videos/deid_sample.mp4",
    "frame_policy": { "mode": "frame_selected", "selected_frames": [0, 10, 13, 970] }
  },
  "callback_url": "http://저작도구/v1/vlm/callback"
}
```

- 한 영상에 대해 `describe` 와 `describe-sub` 를 **각각 별도 `request_id` 로** 위탁한다. 콜백 바디에는 어느 API 의 결과인지 알려주는 식별자가 없으므로, 수신 측은 `request_id` 로 역조회해 위탁 종류를 구분한다.

## 3. 동기 응답 (벤더 → 저작도구)

```json
{ "request_id": "00000001", "status": "accepted" }
```

- 접수만 의미한다. `accepted` 만 정상으로 인정하며, 결과 상세는 `callback_url`(INT-003, `/v1/vlm/callback`)로 별도 콜백된다.

## 4. 필드 정의

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| request_id | String | Y | 위탁 상관관계 키. 콜백 request_id 로 회신되어 RAW_SN 및 위탁 종류(describe / describe-sub) 매칭에 쓰인다 |
| event_type | String | Y | 검증 대상 이벤트 유형. 관제 인입 원장에서 조달한 값을 그대로 싣는다 |
| media.type | String | Y | image \| video (시계열은 video) |
| media.source_type | String | Y | path \| upload (공유 경로 접근은 path) |
| media.path | String | 조건부 | source_type=path 시 필수. 비식별 영상 경로 |
| media.frame_policy | Object | 조건부 | media.type=video 시 필수 |
| media.frame_policy.mode | String | Y | 저작도구는 항상 `frame_selected` 를 보낸다 |
| media.frame_policy.selected_frames | Integer[] | Y | 분석 대상 프레임 인덱스 배열. **상한 600**, 음수 불가 |
| callback_url | String | Y | 결과(INT-003) 회신 URL |

- ★ **`frame_policy` 에 추출 간격(framerate) 필드를 두지 않는다.** 연동 시스템이 지정하는 것은 `mode` 뿐이고, 프레임 추출 간격·장수의 세부값은 벤더 서버가 관리한다. 구 규격의 `framerate`(몇 프레임마다 한 장) 전송은 폐기됐다.
- ★ **`selected_frames` 상한은 600 이다.** 구 규격의 상한 8 과 그에 딸린 sliding window(window=stride=8) 분할 서술은 폐기됐다.

## 5. 위탁 전 사전 확인

- `GET /v1/videovlm-klid/status` 로 서버 처리 가능 여부(`ready` / `busy` / `loading`)를 확인한다. 미준비 상태는 `503` 으로 거부된다.
- `GET /v1/videovlm-klid/events` 로 해당 `event_type` 의 `describe` / `describe-sub` 가용성을 확인한 뒤 위탁한다.

## 6. 저작도구 매핑 / 제약

- **media.path**: 비식별 영상 경로(`LS_DEIDENT_PROC_LOG.DE_IDNTF_FILE_PATH_NM`). 원본 미전송.
- **request_id**: 위탁 발급 상관관계 키. 콜백 수신 시 웹훅 멱등 원장으로 rawSn 과 위탁 종류를 역매핑한다(INTSPEC-002 §4).
- **event_type**: 검증 대상 이벤트 유형. 조달처는 관제 인입 원장이며 저작도구가 매핑표를 만들지 않는다. 값이 없거나 우리가 아는 목록 밖이어도 위탁을 막지 않고 조달값을 그대로 실어 보내며, 수용 여부는 벤더 응답이 정한다.
- **selected_frames 조달**: 마킹 본문에서 인덱스를 얻으면 그것을 싣는다(수동이면 작업자가 지정한 프레임, 자동이면 간격으로 자동 선택된 프레임). 하나도 얻지 못하면 `frame_interval` mode 로 내리고 selected_frames 를 싣지 않는다 — 빈 목록은 규격 위반이라 거부된다. 마킹 이벤트 컨텍스트(이벤트명·마킹 시점 배열)는 전송하지 않는다 — 마킹 결과는 `frame_policy` 로만 반영된다.
- **질문 문구(미전송 — 벤더 회신 대기)**: 저작도구는 검증 이벤트 유형별 질문 문구를 보관하고 마킹이 고른 값을 함께 저장하지만, **현행 요청 본문에는 그 값을 실을 필드가 없어 전송하지 않는다.** 질문 문장은 이벤트별로 벤더 서버가 관리하며 연동 시스템이 지정할 수 없다. 그럼에도 보관하는 이유는 이후 연동 시스템이 질의를 지정하는 방향으로 규격이 바뀔 수 있다는 협의가 있어 그 전환을 미리 준비하기 때문이며, 벤더가 필드를 열면 보관값을 그대로 전송한다.
  - ★ **필드가 열릴 때 확정돼야 할 것 — 아래 넷은 확정된 규격이 아니라 벤더에게 물어야 할 미결 항목이다.** ①필드명·타입·위치(최상위인지 `frame_policy` 같은 하위 객체인지) ②`describe`·`describe-sub` 중 **어느 창구가 받는지** — 질문이 의미를 갖는 축은 추가 질문이고, 묘사는 장소·환경 서술이라 질문이 필요 없을 수 있다 ③미전송 시 벤더 서버의 기본 동작(자기 첫 번째 질문을 쓰는지, 거부인지) ④**문구를 보내는지 식별자를 보내는지** — 식별자면 벤더 채번 체계와 저작도구 보관 목록 사이의 매핑이 별도로 필요하다.
  - ⚠ **인지·수용한 잔여 위험**: 그 필드가 열리기 전까지, 첫 번째가 아닌 질문을 고르면 기록된 질문과 벤더가 실제로 쓴 질문이 달라진다. 이를 알고 수용했다(되돌리지 말 것).

## 7. 전송 항목

호출 주체는 시계열 위탁 배치 단계다. 이 규격이 정하는 전송 항목은 다음과 같다:

| 항목 | 규격 |
|------|------|
| endpoint | `/v1/videovlm-klid/describe` · `/v1/videovlm-klid/describe-sub` (**이중 위탁**) |
| 상관관계 | `request_id`(바디) — 위탁 2건에 각각 별도 발급 |
| 영상 지정 | `media{type,source_type,path}` |
| 프레임 정책 | `frame_policy{mode=frame_selected, selected_frames}` — 추출 간격 필드 없음 |
| 이벤트 유형 | `event_type` 전송 |
| 질문 문구 | **미전송** — 요청 본문에 실을 자리가 없다(§6). 벤더가 필드를 열면 보관값을 그대로 싣는다 |
| callback_url | **필수 전송**(생략하지 않는다) |
| 동기 응답 | `request_id`+`status(accepted)` — `accepted` 만 정상으로 인정한다 |
| 사전 확인 | `GET …/status` · `GET …/events` |
| 인증 | `vlm.client.token` 설정 시에만 조건부 Bearer(HMAC 아님) |

## 8. ★ 활성화 — 환경별 (INT-002 참조)

연동 여부를 설정으로 켜고 끄던 토글은 폐지됐다. 연동 주소가 주입되어 있지 않으면 위탁은 **실패**하며 조용히 건너뛰지 않는다. 벤더 미연동 구간의 운영은 검수자가 사유를 남기고 누르는 **시계열 묶음 스킵**(단건·일괄)으로 처리한다.

| 환경 | 연동 대상 |
|---|---|
| local | 목업 벤더 서버 |
| dev | 목업 벤더 서버 |
| stg | 실제 연동 주소 주입 필요 |
| prd | 실제 연동 주소 주입 필요 — 콜백 허용 출처 설정이 선행돼야 한다 |

## 9. ★ 신고 구간 위탁 보류

비식별 신고(`DE_IDNTF_YN='F'`) 구간에서는 시계열 위탁 배치 단계가 위탁 전송 자체를 그 단계 안에서 **보류(SKIPPED)** 한다 — 실패가 아니라 보류이며 `LS_BATCH_PROC_LOG` 에 사유가 적재된다. 신고 해소 시 `DeidentGateReopened`(EVT-007) 발행을 계기로 보류분을 재위탁한다. 위탁 2종은 같은 조건으로 함께 보류·재위탁된다.

## 10. 신뢰성

- Resilience4j Retry `vlmClient` 3회 / 1s / ×2 backoff. **429(동시 처리 한도 32건 초과)는 재시도 대상**이며, 그 외 4xx 는 비재시도 오류로 분류해 재시도·서킷 집계에서 제외한다.
- `503`(서버 미준비) · `415`(지원하지 않는 Content-Type) 는 벤더가 정의한 거부 응답으로 처리한다.
- CircuitBreaker `vlmClient` — failure-rate 50% / sliding-window 10 / min-calls 5 / open 30s.
- 벤더 분석 제한은 **900초**이며, 초과 시 결과가 아니라 실패 콜백으로 회신된다.
- 배치는 응답을 기다리지 않고 제출만 개시한다(논블로킹 제출). 응답 신호가 전혀 없어도 미결 스위퍼가 회수한다.


## effective_date

2026-08-13

## integration_point

INT-002
