---
logicraft_item: INTSPEC-005
type: integration_spec
version: 2
domain: null
project_id: 4ece2c3f-8e99-46f5-9580-71108a76e578
synced_at: 2026-09-02T10:51:57.085Z
status: CHANGED
prev_version: 1
content_hash: 0ecc8774ee43e89562aa78ca2c11ffd03dca0dbdda22559f99b067a694847362
stale: false
raw: ./_raw/INTSPEC-005.json
links:
  references: ["[[INT-008]]"]
  references_backward: ["[[INT-008]]"]
---

# 생성형 AI 증강 위탁 요청 규격 (생성형 AI API 연동명세서 v1.3 준수)

## status

draft

## version

1.3

## spec_kind

markdown

## attached_files

_(empty)_

## change_summary

벤더 확정 계약 v1.3 에 맞춘 저작도구 송신 규격 신설. 작업 요청 본문이 최상위 mtdt 객체와 문자열 prompt 로 바뀌었고 구 prompt.condition 객체 형식은 계약에서 제외됐다. 이벤트 유형 2종과 세부 유형 코드, 생성 조건 허용 코드, V0 지원 조합, 오류 코드, 조회·취소 규격을 함께 담았다.

## content_inline

# 생성형 AI 증강 위탁 요청 규격 (저작도구 아웃바운드)

> 근거: 외부 벤더 확정 계약 『생성형 AI API 연동명세서 v1.3』(2026-08-12 갱신). 저작도구가 검수 대상 영상의 비식별 프레임을 외부 생성형 AI 에 넘겨 증강본 생성을 위탁하고, 그 작업의 상태·결과·취소를 조회한다.
> 짝 콜백 = INT-006 / INTSPEC-006.

## 1. 공통 규칙

| 항목 | 값 |
|------|-----|
| 공통 경로 | `/api/genai` |
| 호출 방식 | REST/JSON + Webhook 콜백 |
| 인증 | V0 에는 인증 계층이 없다. 그래서 `UNAUTHENTICATED`·`FORBIDDEN` 오류도 적용되지 않는다 |
| Content-Type | JSON 본문이 있는 요청에 `application/json`. 본문이 없는 조회에는 불요 |
| Idempotency-Key | 문자열 ≤64. **작업 요청(`POST /api/genai/jobs`)에서만 필수**이고 상태 조회·결과 조회·취소에는 보내지 않는다. 호출 측이 요청 단위 유일성을 보장하며 특정 생성 알고리즘을 강제하지 않는다(UUID v4 권장) |

동일 Idempotency-Key 재사용 시 중복 실행은 방지되지만, **기존 job_id 재반환과 오류 응답 중 어느 쪽으로 처리되는지는 본 버전 계약에 포함되지 않는다.** 따라서 서로 다른 Payload 에 같은 키를 쓰지 않는다. 재요청의 Payload 동일성 비교 대상에는 `mtdt` 와 `prompt` 가 모두 포함된다.

## 2. 엔드포인트

| 용도 | Method | Path | 정상 응답 |
|------|:------:|------|:---------:|
| 생성·증강 작업 요청 | POST | `/api/genai/jobs` | 202 Accepted |
| 작업 상태 조회 | GET | `/api/genai/jobs/{job_id}` | 200 OK |
| 작업 결과 조회 | GET | `/api/genai/jobs/{job_id}/results` | 200 OK |
| 작업 취소 | POST | `/api/genai/jobs/{job_id}/cancel` | 200 OK |

작업 상태 동기화(생성형 AI → 수신 시스템의 status-sync URL)는 V0 범위 밖이며 본 규격의 대상이 아니다.

## 3. 작업 요청 본문 — `POST /api/genai/jobs`

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| request_id | string(64) | Y | 호출 측 요청 식별자 |
| request_channel | string(20) | Y | `CONTROL` \| `PORTAL` \| `AUTHORING` |
| request_user_id | string(64) | N | 요청 사용자 식별자 |
| evnt_type | string(20) | Y | `FLOOD` \| `WILDFIRE` |
| evnt_subtype | string(20) | N | `evnt_type` 과 같은 최상위 필드. 침수 세부 유형에만 적용하며 미선택 시 `null` 또는 미전달 |
| operation_type | string(20) | Y | V0 지원값 `GENERATE` \| `AUGMENT` |
| generation_mode | string(20) | Y | V0 지원값 `T2I` \| `I2I` \| `T2V`. `I2V`·`V2V` 는 미지원 |
| input_files[] | array(object) | 조건부 | `I2I` 에서 1건 이상 필수. `T2I`·`T2V` 에서는 미전달 |
| mtdt | object | Y | 최상위 구조화 생성 조건 |
| prompt | string(1000) | N | 최상위 자유 텍스트 상세 지시문(UTF-8) |
| model_version_id | string(64) | N | 모델 버전 식별자 |
| parameter_set_id | string(64) | N | 파라미터 세트 식별자 |
| callback_url | string(500) | N | 사전에 정의된 고정 콜백 URL. URL 에 `{job_id}` 를 넣지 않는다 |

### 3.1 본문 구조 규칙 (v1.3)

- `mtdt` 와 `prompt` 는 본문 **최상위에서 서로 독립된 필드**로 전달한다.
- 최상위 `condition` 은 사용하지 않고 `mtdt` 로 전달한다.
- `prompt` 값으로 객체를 전달하지 않는다. **구 `prompt.condition`·`prompt.text` 객체 형식은 v1.3 표준 계약에 포함되지 않는다.**
- 구 형식의 일시적 호환 수용 여부는 배포 서버의 전환 정책으로 별도 관리되며 v1.3 연동 계약으로는 보장되지 않는다.
- 데이터 저장·로깅·감사 이력에서도 `mtdt` 와 `prompt` 를 분리해 보존한다.

## 4. V0 지원 요청 조합

| operation_type | generation_mode | input_files[] | 처리 내용 |
|---|---|:---:|---|
| GENERATE | T2I | N | 프롬프트·생성 조건 기반 이미지 생성 |
| AUGMENT | I2I | Y, 1건 이상 | 원본 이미지 기반 이미지 증강 |
| GENERATE | T2V | N | 프롬프트·생성 조건 기반 영상 생성 |
| - | I2V / V2V | - | V0 미지원 |

## 5. `input_files[]` 하위 필드

| 필드 | 타입 | 필수 | 설명 |
|------|------|:---:|------|
| sequence | integer | Y | 1부터 시작하며 같은 요청 안에서 중복할 수 없다 |
| file_path | string(500) | Y | 생성형 AI 가 접근할 수 있는 NAS 절대경로 |
| checksum | string(100) | N | SHA-256 체크섬. `null` 또는 미전달 시 생성형 AI 측에서 계산할 수 있다 |
| source_file_id | string(64) | N | 호출 측 원천 파일 식별자 |

입력 이미지의 최대 건수·허용 MIME/확장자·파일 크기 상한은 본 버전 계약에서 확정되지 않았다.

## 6. `evnt_subtype` 허용 코드

| 코드 | 설명 | 적용 조건 |
|------|------|-----------|
| ROAD_FLOOD | 도로 침수 | `evnt_type=FLOOD` |
| RIVER_OVERFLOW | 하천 범람 | `evnt_type=FLOOD` |
| UNDERPASS_FLOOD | 지하차도 침수 | `evnt_type=FLOOD` |
| URBAN_INUNDATION | 도심 침수 | `evnt_type=FLOOD` |
| OTHER | 위 코드에 해당하지 않는 기타 침수 유형 | `evnt_type=FLOOD` |

`evnt_type=WILDFIRE` 에는 정의된 세부 코드가 없으므로 `evnt_subtype` 을 `null` 로 전달하거나 생략한다. 산불 상황의 세부 조건은 `evnt_subtype` 이 아니라 `mtdt` 와 `prompt` 로 전달한다(시간대·날씨·지형·심각도는 `mtdt`, 세부 생성 요청은 `prompt`). 산불용 세부 코드가 필요하면 벤더에 요청해 반영 여부를 협의한다.

## 7. `mtdt` 하위 필드 및 허용 코드

| 필드 | 타입 | 필수 | Nullable | 허용 코드 |
|------|------|:---:|:---:|------|
| time | string | N | Y | `DAWN` 새벽 \| `DAY` 낮 \| `DUSK` 황혼 \| `NIGHT` 밤 |
| season | string | N | Y | `SPRING` 봄 \| `SUMMER` 여름 \| `AUTUMN` 가을 \| `WINTER` 겨울 |
| weather | string | N | Y | `CLEAR` 맑음 \| `CLOUDY` 흐림 \| `RAIN` 비 \| `SNOW` 눈 \| `FOG` 안개 \| `WINDY` 바람 |
| terrain | string | N | Y | `ROAD` 도로 \| `UNDERPASS` 지하차도 \| `RIVER` 하천 \| `URBAN` 도심 \| `RESIDENTIAL` 주거지역 \| `RURAL` 시골 \| `MOUNTAIN` 산지 \| `FOREST` 숲 |
| severity | string | N | Y | `LOW` 낮음 \| `MEDIUM` 보통 \| `HIGH` 높음 |

`mtdt` 객체 자체는 반드시 전달한다. 다만 빈 객체 또는 모든 하위 필드가 `null` 인 요청을 서버가 허용하는지는 계약에서 확정되지 않았으므로, 호출 측은 **최소 1개 이상의 유효한 조건값을 전달하는 것을 원칙**으로 한다.

## 8. `prompt` 적용 규칙

- `prompt` 는 선택값이며 **문자열로만** 전달한다. 객체·배열은 400 `INVALID_PARAMETER` 대상이다.
- UTF-8 기준 최대 1,000자이고 내부 모델 입력은 최대 512 tokens 다. 제한을 초과하면 임의로 자르지 않고 400 `INVALID_PARAMETER` 를 반환한다.
- `prompt` 와 `mtdt` 가 충돌하면 **`mtdt` 를 우선**하고 무시된 표현을 `warnings[]` 에 기록한다. 충돌 감지는 키워드 휴리스틱 기반이라 패러프레이즈는 감지되지 않을 수 있고, 경고 문구의 세부 표현은 배포 서버 구현에 따라 달라질 수 있다.

## 9. 작업 상태와 취소 가능 여부

| 상태 | 설명 |
|------|------|
| RECEIVED | 작업 접수, 워커 처리 전 |
| RUNNING | 워커 처리 중 |
| SUCCEEDED | 생성 완료 |
| FAILED | 모델 실행 또는 결과 저장 실패 |
| CANCELED | 취소 완료 |

| generation_mode → 백엔드 | RECEIVED 취소 | RUNNING 취소 | 취소 성공 최종 상태 |
|---|---|---|---|
| T2I / I2I → FLUX.2 | 가능 | 불가, 409 `STATE_CONFLICT` | CANCELED |
| T2V → VACE | 가능 | 가능 | CANCELED |

영상 생성 백엔드의 RUNNING 취소와 프로세스 그룹 종료 동작은 개발계 환경 기준이다.

## 10. 공통 오류 응답

```json
{
  "result_code": "INVALID_PARAMETER",
  "message": "요청 파라미터 형식이 올바르지 않습니다.",
  "request_id": "ctl-req-20260807-0001"
}
```

| HTTP | result_code | 적용 예시 |
|:---:|---|---|
| 400 | REQUIRED_FIELD_MISSING | Idempotency-Key, `mtdt` 또는 기타 필수 본문 누락 |
| 400 | UNSUPPORTED_EVENT_TYPE | `FLOOD`·`WILDFIRE` 외 `evnt_type` |
| 400 | INVALID_PARAMETER | 미지원 조합, I2I 입력 파일 누락, `mtdt` 형식·허용 코드 오류, `prompt` 객체 전달, `prompt` 제한 초과 |
| 404 | JOB_NOT_FOUND | 존재하지 않는 job_id 조회·취소 |
| 404 | RESULT_NOT_FOUND | SUCCEEDED 이전 결과 조회 |
| 409 | STATE_CONFLICT | 완료 상태 재취소, 이미지 생성 백엔드의 RUNNING 취소 |
| 500 | MODEL_EXECUTION_FAILED | 모델 실행 실패 |
| 500 | INTERNAL_SERVER_ERROR | 분류되지 않은 서버 오류 |

## 11. 요청·응답 예시

증강 위탁(I2I) 요청:

```json
{
  "request_id": "3f2a5c1e-20260807-0001",
  "request_channel": "AUTHORING",
  "request_user_id": "reviewer-01",
  "evnt_type": "FLOOD",
  "evnt_subtype": "ROAD_FLOOD",
  "operation_type": "AUGMENT",
  "generation_mode": "I2I",
  "input_files": [
    { "sequence": 1, "file_path": "/nas-storage/frames/deid/1001/frame-0001.jpg", "checksum": null, "source_file_id": "9a186425-20260803-0001" }
  ],
  "mtdt": { "time": "NIGHT", "season": "SUMMER", "weather": "RAIN", "terrain": null, "severity": null },
  "prompt": "원본 카메라 시점과 도로 구조를 유지하고 비 오는 여름철 야간 도로 침수 장면으로 변경해줘.",
  "callback_url": "https://저작도구/v1/genai/callback"
}
```

접수 응답(202):

```json
{ "request_id": "3f2a5c1e-20260807-0001", "job_id": "ai-job-b2d132febc5c", "status": "RECEIVED", "received_at": "2026-08-07T16:52:40+09:00", "warnings": [] }
```

## 12. 조회·취소

**상태 조회** `GET /api/genai/jobs/{job_id}` → 200. 본문: `request_id`·`job_id`·`status`·`progress`·`current_step`·`received_at`·`started_at`·`completed_at`·`error_code`·`error_message`·`updated_at`·`warnings[]`.

**결과 조회** `GET /api/genai/jobs/{job_id}/results` → 200. `SUCCEEDED` 가 아니면 404 `RESULT_NOT_FOUND`. 본문의 `results[]` 각 항목은 `generated_data_id`·`media_type`·`output_file_path`·`checksum`·`media_metadata` 로 구성되고, `media_metadata` 는 확장 가능한 객체다(확인되는 필드는 `mime_type`·`width`·`height`·`duration_seconds`).

`output_file_path` 는 **개발계 환경의 컨테이너 내부 경로 기준 표시값이라 수신 시스템에서 직접 사용할 수 없다.** 공유 NAS 또는 오브젝트 스토리지 연동 전까지 결과 전달 경로는 테스트용이다.

**취소** `POST /api/genai/jobs/{job_id}/cancel` → 200. 요청 본문은 `reason`(string(500), 선택)·`requested_by`(string(64), 필수)이고, 응답은 `request_id`·`job_id`·`status`(=`CANCELED`)·`canceled_at` 이다.

## 13. 저작도구 송신 고정값

| 항목 | 값 |
|------|-----|
| request_channel | `AUTHORING` 고정 |
| operation_type | `AUGMENT` 고정 |
| generation_mode | `I2I` 고정 — 증강은 영상을 재생성하지 않고 프레임 이미지만 변환한다 |
| evnt_type | 요청자가 고르지 않고 서버가 중립값 `ETC` 로 고정 송신한다 |
| evnt_subtype | 항상 전송하지 않는다(키 자체를 보내지 않는다) |
| Idempotency-Key | 우리가 발급한 `request_id` 와 같은 값을 항상 부착한다(계약상 작업 요청에서 필수) |
| input_files[].file_path | 비식별 프레임 경로만 싣는다. 원본(비-비식별) 경로는 전송하지 않는다 |
| 분할 위탁 | 입력 파일이 상한을 넘으면 한 증강 요청을 여러 job 으로 청크 분할하며, 분할된 모든 청크가 같은 생성 조건을 싣는다 |
| callback_url | 필수 전송(생략하지 않는다). 고정 URL 이며 `{job_id}` 를 넣지 않는다 |
| 응답 검증 | `request_id` echo 일치 + `status=RECEIVED` + `job_id` 비어 있지 않음. 하나라도 어긋나면 외부 연동 오류로 끊는다 |
| 4xx | 재시도하지 않는다(요청이 이미 상대에 도달했을 수 있는 결정적 오류를 재시도하면 중복 위탁이 된다) |

`evnt_type`·`evnt_subtype`·`mtdt` 각 항목에 어떤 값을 싣는지는 ADR-059 로 확정됐다. 이벤트 유형은 요청자가 고르지 않고 서버가 중립값 `ETC` 로 고정 송신한다. 세부 유형은 항상 전송하지 않는다(키 자체를 보내지 않는다). 우리 증강은 이미 이벤트가 기록된 영상의 프레임 이미지만 바꾸는 것이라 어떤 장면을 만들지 정할 필요가 없고, 그 값은 배경에 장면을 만들어 넣는 외부 규격의 축이기 때문이다. 세부 유형은 침수 전용이라 중립값에서는 성립하지 않는다. `mtdt` 는 요청 본문에서 받은 생성 조건(시간대·계절·날씨·지형·심각도)을 그대로 싣는다.

## 14. 개발계 배포본 실측 (2026-08-18)

규격서 v1.3 은 계약이고, 아래는 그 계약을 아직 따라오지 못한 **개발계 배포본의 현재 상태**다. 규격서 스스로 "구 형식의 일시적 호환 수용 여부는 배포 서버의 전환 정책으로 별도 관리한다"고 밝히고 있으므로 둘의 차이는 결함이 아니라 전환 진행 상태다.

- 배포본이 스스로 밝히는 규격 버전은 `1.1` 이고, 작업 요청 스키마는 **`prompt` 를 필수 객체**로 요구한다(`condition` 객체 필수 + `text` 문자열 선택). 최상위 `mtdt` 필드는 스키마에 없다.
- 따라서 배포본에는 **v1.3 본문(최상위 `mtdt` + 문자열 `prompt`)이 그대로 통하지 않는다.** 전환 완료 시점은 벤더 확인 대상이다.
- `evnt_type` 은 배포본 스키마에서 길이 20 문자열이며 값 제한은 스키마가 아니라 서버 로직에서 판정된다.
- `operation_type` 은 배포본 스키마에 `GENERATE`·`AUGMENT`·`TRANSFORM`, `generation_mode` 는 `T2I`·`T2V`·`I2I`·`I2V` 가 열거돼 있다. 규격서의 V0 지원값은 그보다 좁으므로 **스키마 통과가 곧 지원을 뜻하지 않는다.**
- 네 엔드포인트(작업 요청·상태 조회·결과 조회·취소)의 경로는 규격서와 일치한다.
- ⚠ `ETC` 는 벤더와 협의가 끝난 값이나 우리가 가진 규격서 판본에는 아직 없다 — 그 판본은 이벤트 유형 허용 코드를 침수·산불 둘로 적고 그 밖의 값에 오류 코드를 규정한다. 개정판을 받으면 값과 오류 코드를 대조한다. 그때까지는 문서가 아니라 합의가 근거다.

## 15. 본 버전에서 보장하지 않는 것

- 동일 Idempotency-Key 재사용 시의 처리 방식(기존 job_id 재반환인지 오류 응답인지)
- 입력 이미지의 최대 건수·허용 MIME/확장자·파일 크기 상한
- 빈 `mtdt`(빈 객체 또는 전 필드 `null`)의 수용 여부
- 결과 파일의 전달 경로(공유 NAS 또는 오브젝트 스토리지 연동 전까지 테스트용)


## effective_date

2026-08-12

## integration_point

INT-008
