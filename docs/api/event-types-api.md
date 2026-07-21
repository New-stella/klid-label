# 이벤트 타입(이벤트명) 조회 API 사용 가이드

> 관제 마스터 코드(`MNG_EX_EVNT_TYPE` + `MNG_EX_EVNT_TYPE_MAP`, READ 전용) 기반으로
> **이벤트명(이벤트 유형)** 목록·라벨을 제공하는 API. 필터 드롭다운·라벨 뱃지 표시에 공통 사용된다.

- **도메인**: `eventType`
- **BE 컨트롤러**: `backend/.../eventtype/controller/EventTypeController.java`
- **BE 서비스**: `backend/.../eventtype/service/EventTypeService.java`
- **FE API**: `frontend/src/features/eventType/api.ts`
- **FE 훅**: `frontend/src/features/eventType/hooks.ts`
- **인증**: `bearerAuth` 필수. 인증된 **내부 사용자(REVIEWER/WORKER)** 접근 가능. 포털 채널(`PORTAL_USER`)은 채널 격리로 차단.

---

## 1. 엔드포인트 요약

| # | 용도 | Method / URL | 반환 |
|:-:|------|--------------|------|
| ① | **이벤트명 목록 조회** (필터 드롭다운) | `GET /api/v1/event-types` | `EventTypeOption[]` (카테고리 9종) |
| ② | 코드→한글 라벨 맵 (뱃지 표시) | `GET /api/v1/event-types/labels` | `Record<코드, 한글명>` |

> `apiClient` baseURL이 `/api`, 컨트롤러 `@RequestMapping("/v1/event-types")` → 실제 외부 URL은
> `/api/v1/event-types`, `/api/v1/event-types/labels`.
> 응답은 `ApiResponse<T>` 래퍼로 감싸지며, FE `apiClient` 응답 인터셉터가 언랩해 `.data`만 반환한다.

**"이벤트명을 받아오는 API"에 해당하는 것은 ①** — 선택 UI에 노출되는 한글 이벤트명 목록을 반환한다.

---

## 2. ① 이벤트명 목록 조회 — `GET /api/v1/event-types`

수집대상(`CLCT_YN='Y'`)이고 ignore 대분류(`08`, 배회)가 아닌 코드를 **카테고리(`EVNT_CLS_CD`+`EVNT_CTGRY_CD`)로 dedup**한 필터 옵션 목록(9종).

### 응답 타입 — `EventTypeOption`

```ts
interface EventTypeOption {
  categoryKey: string;   // 카테고리 키 = EVNT_CLS_CD + EVNT_CTGRY_CD (예 "010001"). 드롭다운 value
  label: string;         // 카테고리 한글명 (예 "침수(범람)"). 드롭다운 표시
  memberCodes: string[]; // 이 카테고리에 속한 수집대상 상세 EV-코드 목록 (예 EV01000101/102/103)
}
```

### 응답 예시 (언랩 후 `.data`)

```json
[
  {
    "categoryKey": "010001",
    "label": "침수(범람)",
    "memberCodes": ["EV01000101", "EV01000102", "EV01000103"]
  },
  {
    "categoryKey": "020002",
    "label": "화재",
    "memberCodes": ["EV02000201"]
  }
]
```

### FE 사용 (권장 — React Query 훅)

```tsx
import { useEventTypes } from '@/features/eventType/hooks';

function VideoFilters() {
  const { data: options, isLoading } = useEventTypes();
  // options: EventTypeOption[]

  if (isLoading) return <Skeleton />;

  return (
    <select>
      {options?.map((opt) => (
        <option key={opt.categoryKey} value={opt.categoryKey}>
          {opt.label}
        </option>
      ))}
    </select>
  );
}
```

- 훅은 `staleTime: Infinity` — 이벤트 타입은 near-immutable(관제 마스터 기반)이라 **한 세션 1회만 페치**한다.
- queryKey: `EVENT_TYPE_KEYS.options()` → `['eventTypes', 'options']`

### FE 사용 (직접 함수 호출)

```ts
import { getEventTypes } from '@/features/eventType/api';

const options = await getEventTypes(); // Promise<EventTypeOption[]>
```

### 실제 소비처

- `frontend/src/features/video/components/VideoFilters.tsx` — 영상 목록 필터
- `frontend/src/features/preset/components/PresetEditModal.tsx`, `frontend/src/pages/manage/PresetListPage.tsx` — 프리셋
- `frontend/src/features/upload/components/TusUploadPanel.tsx` — 업로드 시 이벤트 유형 지정

---

## 3. ② 코드→한글 라벨 맵 — `GET /api/v1/event-types/labels`

전체 EV-코드(수집/비수집 무관)를 카테고리 한글명으로 해석한 맵. **단일 코드 → 라벨 변환**(뱃지 표시 등)에 사용한다. 목록이 아니다.

### 응답 타입 — `EventTypeLabelMap`

```ts
type EventTypeLabelMap = Record<string, string>; // { "EV01000101": "침수(범람)", ... }
```

미등록 코드는 맵에 없으므로 **소비측에서 원문 코드로 폴백**한다.

### FE 사용

```tsx
import { useEventTypeLabels } from '@/features/eventType/hooks';

function EventTypeBadge({ code }: { code: string }) {
  const { data: labelMap } = useEventTypeLabels();
  const label = labelMap?.[code] ?? code; // 미등록 시 원문 폴백
  return <span className="badge">{label}</span>;
}
```

- queryKey: `EVENT_TYPE_KEYS.labels()` → `['eventTypes', 'labels']`, `staleTime: Infinity`
- 소비처: `frontend/src/components/common/EventTypeBadge.tsx`, 헬퍼 `frontend/src/lib/eventTypeLabel.ts`

---

## 4. ⚠️ 마킹 단계 주의점 (중요)

**마킹 화면에서는 사용자가 이벤트명을 선택하지 않는다.** API-047 계약 변경으로 이벤트명(`eventName`)은
마킹 요청에 포함되지 않고, **서버가 영상의 `evntTypeCd`에서 자동 소싱**한다.

- `MarkingResponse.eventName`으로만 응답에 내려온다 — `frontend/src/features/marking/types.ts:32`
- 즉 마킹 화면은 위 `GET /event-types` 드롭다운을 쓰지 않으며, 이벤트명 조회용 별도 호출도 없다.

따라서 "마킹 시 표시되는 이벤트명"이 필요하면 **영상 상세 / 마킹 응답의 `eventName` 필드**를 쓰고,
"필터/프리셋/업로드에서 이벤트명을 고르는 목록"이 필요하면 **`GET /api/v1/event-types`**(①)를 쓴다.

---

## 5. 백엔드 참고 (서버 로직)

- `EventTypeService.filterOptions()` — ① 필터 옵션 생성. `CLCT_YN='Y'` AND 대분류≠`08` dedup, `categoryKey` 오름차순.
- `EventTypeService.codeLabelMap()` — ② 코드→라벨 맵.
- 두 조회 모두 `@Cacheable(CACHE_EVENT_TYPE)` 장수명 캐시 (관제 코드 near-immutable, 별도 무효화 없음 / TTL·앱 수명).
- 카테고리 라벨은 `MNG_EX_EVNT_TYPE_MAP` 의 `CD_TYPE='02'` 카테고리명행(`DTL_EVNT`·`EVNT_TYPE_CD` 모두 빈 값)에서 도출.
- N+1 회피: 카테고리명행을 `findByCdType('02')` 로 1회 로드해 in-memory 매칭.

### 내부 전용 헬퍼 (컨트롤러 미노출)

`EventTypeService`는 API로 노출되지 않는 내부 헬퍼도 제공한다:

- `resolveLabel(evntTypeCd)` — 코드 1건 라벨 해석(미등록/null/blank 원문 폴백)
- `categoryKeyOf(evntTypeCd)` — 상세 EV-코드 → 카테고리 키 변환(프리셋 매칭용)
- `validCategoryKeys()` — 유효 카테고리 키 집합(프리셋 `eventTypeCd` 검증용)
