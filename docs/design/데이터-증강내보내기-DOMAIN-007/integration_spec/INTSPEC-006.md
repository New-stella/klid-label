---
logicraft_item: INTSPEC-006
type: integration_spec
version: 1
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-08-21T00:04:22.321Z
status: NEW
prev_version: null
content_hash: 7145f9696411ede53140dd7a5498967ae63c40828837c07f032e9ce792fd1327
stale: true
raw: ./_raw/INTSPEC-006.json
links:
  references: ["[[INT-006]]"]
  references_backward: ["[[INT-006]]"]
---

# 생성형 AI 증강 결과 콜백 수신 규격 (생성형 AI API 연동명세서 v1.3 준수)

## status

draft

## version

1.3

## spec_kind

markdown

## change_summary

벤더 확정 계약 v1.3 §4.5 에 맞춘 콜백 수신 규격 신설. 콜백은 종료 시점 1회 발사이고 재시도가 없으며, 결과 본문은 콜백이 아니라 결과 조회로 조달하는 것이 계약이다.

## content_inline

# 생성형 AI 증강 결과 콜백 수신 규격 (저작도구 인바운드)

> 근거: 외부 벤더 확정 계약 『생성형 AI API 연동명세서 v1.3』(2026-08-12 갱신) §4.5 최종 상태 Webhook. 저작도구가 위탁한 증강 작업의 종료를 외부 생성형 AI 가 콜백으로 통보한다.
> 짝 위탁 = INT-008 / INTSPEC-005.

## 1. 발사 계약

| 항목 | 값 |
|------|-----|
| 방향 | 생성형 AI → 저작도구 |
| Method / Path | POST `{callback_url}` — 위탁 시 저작도구가 전달한 고정 URL 그대로. URL 에 `{job_id}` 를 넣지 않는다 |
| 발사 시점 | 작업이 `SUCCEEDED` 또는 `FAILED` 로 **처음 종료되는 시점** |
| 발사 횟수 | **1회** |
| 재시도 | **없다.** V0 에서는 전송 실패 시 재시도하지 않고 상대 서버 로그에만 기록한다 |
| 인증 | 없다(웹훅에 서명·인증 헤더 자체가 규격에 없다) |
| 오류 통보 | 웹훅 전송 실패는 위탁 호출의 응답으로 반환되지 않는다 |

재시도가 없다는 것은 **콜백 유실이 곧 결과 유실**이라는 뜻이다. 따라서 콜백은 유일한 회수 수단이 아니며, 저작도구는 상태 조회(INTSPEC-005 §12)로도 종결을 회수할 수 있어야 한다.

## 2. 페이로드

```json
{
  "request_id": "3f2a5c1e-20260807-0001",
  "job_id": "ai-job-b2d132febc5c",
  "status": "SUCCEEDED",
  "progress": 100,
  "current_step": "COMPLETED",
  "completed_at": "2026-08-07T16:53:07+09:00",
  "error_code": null,
  "error_message": null,
  "updated_at": "2026-08-07T16:53:07+09:00",
  "warnings": []
}
```

| 필드 | 설명 |
|------|------|
| request_id | 저작도구가 위탁 시 발급한 요청 식별자의 echo. 이 값이 상관관계 키다 |
| job_id | 위탁 접수 응답(202)에서 외부가 발급한 작업 식별자 |
| status | 종료 상태(`SUCCEEDED` \| `FAILED`) |
| progress / current_step | 진행률과 진행 단계 |
| completed_at / updated_at | 종료·갱신 시각 |
| error_code / error_message | 실패 시 사유 |
| warnings[] | 생성 조건과 자유 텍스트가 충돌해 무시된 표현 등의 경고 |

`warnings[]` 처리 기준은 위탁 접수 응답·상태 조회·결과 조회·콜백에서 동일하게 유지된다.

## 3. 결과 본문의 조달 경로

콜백은 **상태 알림 용도**이며, 작업 결과와 미디어 메타데이터는 결과 조회(`GET /api/genai/jobs/{job_id}/results`)로 조회하는 것이 v1.3 계약이다. 따라서 산출물 목록을 콜백 본문에서 얻는 것을 전제로 설계하지 않는다.

## 4. 저작도구 수신 규약

| 항목 | 값 |
|------|-----|
| 수신 경로 | `POST /v1/genai/callback` |
| 서명 | 없다(무서명이 벤더 규격이다) |
| 대체 보호 1 | 송신 IP 허용목록 — 미설정이면 전면 차단(fail-closed). 위탁이 켜져 있는데 허용목록이 비어 있으면 기동 자체를 거부해 "위탁은 나가는데 결과는 전건 거부" 상태를 만들지 않는다 |
| 대체 보호 2 | 요청 속도 제한 + 본문 크기 상한 |
| 대체 보호 3 | 발급 게이트 — 저작도구가 실제로 발급한 요청 식별자에 해당하는 콜백만 처리한다 |
| 멱등 | 같은 페이로드가 중복 도착해도 결과가 한 번만 반영된다. 중복 흡수·보류는 응답의 반영 여부 필드로 구분해 회신한다 |
| 상태 반영 | 성공은 증강 결과를 채택 상태로, 실패는 반려 상태로 전이시키며 이미 종결된 건은 되돌리지 않는다 |
| 산출물 처리 | 증강 결과 프레임으로 새 파생영상을 등록하고 원본 라벨을 그대로 복사한 뒤 미검수 상태로 시작한다 |

해상도 변경 파생은 외부 위탁이 아니라 저작도구 내부 처리이므로 이 콜백의 대상이 아니다.

## 5. 본 버전에서 보장하지 않는 것

- `CANCELED` 상태의 콜백 발송 여부
- 콜백 본문에 `results[]` 가 포함되는지 여부


## effective_date

2026-08-12

## integration_point

INT-006
