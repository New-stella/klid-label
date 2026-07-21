# `GET /v1/augments` 응답 변경 가이드 (연동 팀 전달용)

> 대상 API: **`GET /api/v1/augments`** (증강 최근 요청 이력 조회)
> 변경 커밋: `172f053` (main `4ad5411`) · 반영일: 2026-07-10 · 배포: cudo_246
> 변경 성격: **경로·메서드 불변, 응답 본문(shape) 변경 = Breaking Change**

---

## 0. 한 줄 요약

`GET /v1/augments` 의 **URL·HTTP 메서드·쿼리 파라미터는 그대로**입니다. **응답 `data` 의 구조만** `증강 1행(영상×유형) 단위` → **`영상 1건(잡) 단위 그룹`** 으로 바뀌었습니다. 응답을 파싱하는 소비자는 아래 필드 매핑대로 수정이 필요합니다.

---

## 1. 안 바뀐 것 (그대로 사용)

| 항목 | 값 |
|---|---|
| 경로 | `GET /api/v1/augments` (context-path `/api` + 매핑 `/v1/augments`) |
| 메서드 | `GET` |
| 인증 | JWT Bearer, 역할 `REVIEWER` 또는 `WORKER` |
| 쿼리 파라미터 | `srcSn`(선택), `page`(선택, 기본 0), `size`(선택, 기본 20, 최대 100) |
| 공통 래퍼 | `ApiResponse<T>` = `{ success, data, message, errorCode }` |
| 에러 | `400`(size>100, INVALID_INPUT) · `401`(UNAUTHORIZED) · `403`(FORBIDDEN) |

> 경로 `/v1/augments` 는 최초 도입(커밋 `b10be4c`)부터 지금까지 한 번도 바뀐 적 없습니다.

---

## 2. 바뀐 것 — 응답 `data` 구조

### 2-1. data 래핑 형태

| 요청 | 수정 전 | 수정 후 |
|---|---|---|
| `?srcSn=123` (지정) | **bare 배열** `List<AugmentSummaryResponse>` | `Page<AugmentJobResponse>` |
| `?page=0&size=20` (srcSn 미지정) | `Page<AugmentSummaryResponse>` | `Page<AugmentJobResponse>` |

- **핵심 변화 ①**: `srcSn` 지정 응답이 이제 **배열이 아니라 Spring Page 객체**입니다 → `data.content` 로 읽어야 합니다.
- **핵심 변화 ②**: 항목 단위가 `증강 1행` → **`영상 1건(잡)`** 으로 그룹핑됩니다. (영상 1건 = 카드 1개)

### 2-2. 항목 필드 매핑 (`AugmentSummaryResponse` → `AugmentJobResponse`)

| 수정 전 필드 | 타입 | 수정 후 필드 | 타입 | 비고 |
|---|---|---|---|---|
| `dataAugSn` | int64 | — | | ❌ **제거** (증강행 PK — 3-2 주의 참고) |
| `srcSn` | int64 | `videoId` / `jobId` | int64 | `jobId == videoId == 원본영상 RAW_SN` |
| `augTypeCd` | string(단일) | `types` | string[] | 영상별 유형 **배열**, distinct, 정렬(WINTER→NIGHT→RAIN→RESOLUTION) |
| `augProcSttsCd` | string(원시코드) | `status` | enum | **집계값** `REQUESTED`/`IN_PROGRESS`/`COMPLETED`/`FAILED` (3-1 규칙) |
| `registeredAt` | date-time | `requestedAt` | date-time | 그룹 `MIN(REG_DT)` |
| `decisionAt` | date-time? | `completedAt` | date-time? | `COMPLETED` 시 `MAX(RVW_DT)`, 아니면 `null` |
| `lblIntgrtPct` | number? | — | | ❌ **제거** |
| `rejectReason` | string? | — | | ❌ **제거** |
| `decisionUserNo` | string? | — | | ❌ **제거** |
| — | | `cctvName` | string | 🆕 CCTV 명(항상 non-null, 매핑 부재 시 `"(이름 없음)"`) |
| — | | `videoCount` | int | 🆕 잡 내 영상 수(영상 단위 그룹이라 항상 `1`) |

---

## 3. 소비자가 반드시 알아야 할 것

### 3-1. `status` 집계 규칙 (원시 상태코드 아님)
영상 그룹 내 여러 증강행을 아래 우선순위로 하나의 `status` 로 집계합니다.

| 조건 | status |
|---|---|
| 그룹에 dead-letter(처리 포기) 1건이라도 있음 | `FAILED` |
| 전부 종료(ACCEPTED/REJECTED) | `COMPLETED` |
| 일부만 종료 | `IN_PROGRESS` |
| 전부 미처리(PENDING) | `REQUESTED` |

### 3-2. ⚠️ 리스트에서 사라진 per-row 필드 (연동 영향 체크 필수)
`dataAugSn` · `lblIntgrtPct` · `rejectReason` · `decisionUserNo` 는 **이 목록 응답에서 빠졌습니다.**

- 특히 **`dataAugSn`** 은 `POST /v1/augments/{id}/accept|reject` 의 `{id}` 값입니다. 이 목록만으로는 더 이상 `dataAugSn` 을 얻을 수 없습니다.
- 귀 프로젝트가 이 목록에서 위 필드(증강행 단위 상세/채택·반려)를 사용 중이라면 **이 엔드포인트로는 충족되지 않습니다.** → 별도의 per-row 상세 조회 엔드포인트 신설이 필요하니 저작도구팀에 요청 바랍니다. (프론트 화면 정합을 위해 본 목록을 원복하지는 않습니다.)

### 3-3. `srcSn` 파라미터 ↔ `videoId` 도메인 차이
- 요청 `srcSn` 은 `LS_DATA_SRC.SRC_SN`(대표 프레임 ID)입니다.
- 응답 `videoId`(=`jobId`) 는 `RAW_SN`(원본 영상 PK)입니다. **둘은 다른 값**이니, 응답의 `videoId` 를 그대로 `srcSn` 필터로 재사용하지 마세요.

### 3-4. 페이징 정렬
`MIN(REG_DT) DESC, SRC_SN DESC` (동률 시 결정적) — 페이지 경계 중복/누락 없음.

---

## 4. 예시 (Before / After)

### 4-1. Before — 수정 전

`GET /api/v1/augments?page=0&size=6`
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "dataAugSn": 10,
        "srcSn": 1,
        "augTypeCd": "WINTER",
        "augProcSttsCd": "PENDING",
        "lblIntgrtPct": null,
        "rejectReason": null,
        "decisionUserNo": null,
        "decisionAt": null,
        "registeredAt": "2026-07-10T10:00:00"
      },
      { "dataAugSn": 11, "srcSn": 1, "augTypeCd": "NIGHT", "augProcSttsCd": "PENDING", "registeredAt": "2026-07-10T10:00:00", "lblIntgrtPct": null, "rejectReason": null, "decisionUserNo": null, "decisionAt": null }
    ],
    "number": 0, "size": 6, "totalPages": 1, "totalElements": 2
  },
  "message": null,
  "errorCode": null
}
```
`GET /api/v1/augments?srcSn=1` (수정 전 — **bare 배열**)
```json
{
  "success": true,
  "data": [
    { "dataAugSn": 10, "srcSn": 1, "augTypeCd": "WINTER", "augProcSttsCd": "PENDING", "registeredAt": "2026-07-10T10:00:00", "lblIntgrtPct": null, "rejectReason": null, "decisionUserNo": null, "decisionAt": null }
  ],
  "message": null, "errorCode": null
}
```

### 4-2. After — 수정 후

`GET /api/v1/augments?page=0&size=6`
```json
{
  "success": true,
  "data": {
    "content": [
      {
        "jobId": 1,
        "videoId": 1,
        "cctvName": "강남대로 사거리",
        "types": ["WINTER", "NIGHT"],
        "status": "REQUESTED",
        "requestedAt": "2026-07-10T10:00:00",
        "completedAt": null,
        "videoCount": 1
      }
    ],
    "number": 0, "size": 6, "totalPages": 1, "totalElements": 1
  },
  "message": null,
  "errorCode": null
}
```
`GET /api/v1/augments?srcSn=1` (수정 후 — **Page 객체**, `data.content` 로 접근)
```json
{
  "success": true,
  "data": {
    "content": [
      { "jobId": 1, "videoId": 1, "cctvName": "강남대로 사거리", "types": ["WINTER", "NIGHT"], "status": "REQUESTED", "requestedAt": "2026-07-10T10:00:00", "completedAt": null, "videoCount": 1 }
    ],
    "number": 0, "size": 20, "totalPages": 1, "totalElements": 1
  },
  "message": null, "errorCode": null
}
```

> 관찰: 수정 전 `WINTER`/`NIGHT` **2행**이 수정 후 영상 1건의 `types: ["WINTER","NIGHT"]` **1개 잡**으로 합쳐집니다.

---

## 5. 소비자 마이그레이션 체크리스트

- [ ] `srcSn` 지정 호출 시 응답을 **배열이 아니라 `data.content`** 로 파싱하도록 변경
- [ ] 항목 필드명 교체: `srcSn→videoId`, `augTypeCd(단일)→types[](배열)`, `augProcSttsCd→status(집계 enum)`, `registeredAt→requestedAt`, `decisionAt→completedAt`
- [ ] `status` 를 원시 코드가 아닌 4-값 enum(`REQUESTED/IN_PROGRESS/COMPLETED/FAILED`)으로 처리
- [ ] `types` 를 배열로 처리 (레거시 `RESOLUTION` 값 포함 가능)
- [ ] **(중요)** `dataAugSn`/`lblIntgrtPct`/`rejectReason`/`decisionUserNo` 의존 여부 점검 → 의존 시 저작도구팀에 per-row 상세 API 요청

---

## 6. 변경 없는 관련 엔드포인트 (그대로 사용)

| 엔드포인트 | 응답 | 상태 |
|---|---|---|
| `POST /v1/augments/request` | `RequestAugmentResponse{jobId, requestedAt, videoCount, typeCount}` | 불변 |
| `GET /v1/augments/{jobId}/result` | 결과 묶음 | 불변 |
| `POST /v1/augments/{id}/accept` | `AugmentSummaryResponse` (`{id}`=`dataAugSn`) | 불변 |
| `POST /v1/augments/{id}/reject` | `AugmentSummaryResponse` (`{id}`=`dataAugSn`) | 불변 |

> `AugmentSummaryResponse`(per-row) DTO 자체는 삭제되지 않고 accept/reject 응답에 계속 사용됩니다.
