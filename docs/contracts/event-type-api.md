# 이벤트 타입 API 계약 (Phase 2)

관제 이벤트 코드(EV-코드)를 한글 라벨로 해석하고 필터 드롭다운 옵션을 제공하는 조회 API.
모든 응답은 `ApiResponse<T>` 표준 래퍼(`{ success, data, message, errorCode }`)로 감싼다.

- Base URL: `/api/v1/event-types` (Spring context-path `/api` + 컨트롤러 매핑 `/v1/event-types`)
- 인가: **인증된 내부 사용자(REVIEWER/WORKER)** — INTERNAL 채널 토큰 필요. 포털(PORTAL_USER) 채널 토큰은 채널 격리로 차단(403). 미인증 401.
- 입력 파라미터 없음(GET). 데이터는 near-immutable 이라 서버에서 6h 캐시(Caffeine `eventType`).
- 데이터 출처: 관제 공유(READ) `MNG_EX_EVNT_TYPE`(마스터) + `MNG_EX_EVNT_TYPE_MAP`(한글명 매핑).

---

## 1. GET /api/v1/event-types — 필터 옵션 목록

수집대상(`CLCT_YN='Y'`)이고 ignore 대분류(`EVNT_CLS_CD='08'`, 배회)가 아닌 코드를
카테고리(`EVNT_CLS_CD + EVNT_CTGRY_CD`) 단위로 dedup 한 옵션 목록. `categoryKey` 오름차순.

| 항목 | 값 |
|------|-----|
| Method / URL | `GET /api/v1/event-types` |
| 인가 | 인증된 내부 사용자(REVIEWER/WORKER) |
| 성공 코드 | 200 OK |

### Response 스키마

| 필드 | 타입 | 설명 |
|------|------|------|
| `data[].categoryKey` | string | 카테고리 키 = `EVNT_CLS_CD`+`EVNT_CTGRY_CD` (예 `"020002"`) |
| `data[].label` | string | 카테고리 한글명. 라벨행 부재 시 `categoryKey` 폴백 |
| `data[].memberCodes` | string[] | 해당 카테고리 수집대상 EV-코드 목록(오름차순) |

### Response 예시 (침수 3코드가 1옵션으로 dedup)

```json
{
  "success": true,
  "data": [
    { "categoryKey": "010001", "label": "침수(범람)", "memberCodes": ["EV01000101", "EV01000102", "EV01000103"] },
    { "categoryKey": "010002", "label": "산사태",      "memberCodes": ["EV01000201"] },
    { "categoryKey": "020001", "label": "화재",        "memberCodes": ["EV02000101", "EV02000102"] },
    { "categoryKey": "020002", "label": "쓰러짐",      "memberCodes": ["EV02000201"] },
    { "categoryKey": "020005", "label": "파손",        "memberCodes": ["EV02000501"] },
    { "categoryKey": "030001", "label": "교통사고",    "memberCodes": ["EV03000101", "EV03000102", "EV03000103"] },
    { "categoryKey": "050001", "label": "싸움",        "memberCodes": ["EV05000101"] },
    { "categoryKey": "050002", "label": "흉기소지",    "memberCodes": ["EV05000201"] },
    { "categoryKey": "050007", "label": "납치(유괴)",  "memberCodes": ["EV05000701"] }
  ],
  "message": null,
  "errorCode": null
}
```

> ignore 대분류 `08`(배회) 카테고리는 노출되지 않는다. 마스터가 비면 `data: []`.

---

## 2. GET /api/v1/event-types/labels — 코드→라벨 맵

전체 코드(수집/비수집 무관)의 `EV-코드 → 한글 라벨` 맵. 라벨이 도출되지 않는 코드는 맵에서 생략된다
(소비측은 미존재 키를 원문 코드로 폴백하면 된다 — 서버 `resolveLabel` 동일 정책).

| 항목 | 값 |
|------|-----|
| Method / URL | `GET /api/v1/event-types/labels` |
| 인가 | 인증된 내부 사용자(REVIEWER/WORKER) |
| 성공 코드 | 200 OK |

### Response 예시 (비수집 EV07000201 도 해석)

```json
{
  "success": true,
  "data": {
    "EV01000101": "침수(범람)",
    "EV02000201": "쓰러짐",
    "EV03000101": "교통사고",
    "EV05000701": "납치(유괴)",
    "EV07000201": "기타 상황"
  },
  "message": null,
  "errorCode": null
}
```

> 미등록/라벨없는 코드는 맵에 포함되지 않으며, 서버 측 `EventTypeService.resolveLabel(code)`는
> 맵에 없으면 입력 코드를 그대로 폴백 반환한다(예외 없음).

---

## 에러 응답

| 상황 | HTTP | errorCode |
|------|------|-----------|
| 토큰 없음/만료/위조 | 401 | `UNAUTHORIZED` |
| 포털(PORTAL_USER) 채널 토큰 | 403 | `FORBIDDEN` |
