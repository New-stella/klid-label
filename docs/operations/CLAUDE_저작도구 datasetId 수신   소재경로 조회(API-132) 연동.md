# 저작도구 연동 — `datasetId` 수신 → 소재 경로 조회(API-132)

> ## ⚠️ 이 문서의 ①「datasetId 수신」 절은 폐기됐다 (2026-09-10)
>
> **질의 문자열(`?datasetId=`)이 아니라 «경로 변수»** 로 온다 —
> **`/workspace/authoring/portal/datasets/{datasetId}`**.
> 포털이 이 문서 이후(14:05 커밋)에 바꿨고, 이유는 **질의 문자열 방식을 실제로 시도했다가
> 저작도구 라우터에 걸리지 않아 사용자가 조용히 홈으로 떨어졌기** 때문이다.
>
> ⚠ **이 문서의 ① 절을 근거로 되돌리지 말 것.** 정본은 `INT-013` · `INT-014` 이고
> 경위는 `.claude/change-orders/CO-20260910-포털-소재조달-배선.md` §2-0 에 있다.
>
> ★ **②「소재 경로 조회」 절은 그대로 유효하다** — 포털 WAS 소스 실측으로 경로·응답 스키마·
> 오류 코드가 전부 일치함을 확인했다. 이 폐기는 ① 절 한정이다.

포털에서 저작도구로 진입할 때 `datasetId` 를 **URL 쿼리로 전달**합니다. 저작도구는 그 값을
읽어, 포털이 제공하는 **내부 전용 API(API-132)** 를 호출해 데이터셋 원본(배포 zip·영상)의
**NAS 절대경로**를 받아 마운트로 직접 엽니다.

```
[포털 진입] /workspace/authoring?datasetId=4704
      │  ① 저작도구가 window.location 에서 datasetId 읽음
      ▼
[저작도구 WAS] ──(x-api-key, 내부망)──▶ [포털 WAS]
      GET /api/internal/v1/datasets/4704/materials
      ◀──────── ② NAS 절대경로 목록 (files[].localPath) ────────
      │
      ▼  ③ 저작도구가 localPath 의 zip 을 «자기 관리 디렉토리»에 풀어서 사용
```

> ⛔ **원본 zip 은 읽기 전용입니다** — `localPath` 가 가리키는 배포 zip 은 포털이 소유·관리하는
> 원본입니다. **이동·수정·삭제·덮어쓰기 금지.** 저작도구는 zip 을 **자기 관리 디렉토리로 복사/압축
> 해제**해서 그 사본을 사용하세요. 원본을 손대면 포털의 배포본·무결성(checksum)이 깨집니다.

---

## ① datasetId 수신 — URL 쿼리 직접 읽기

포털 진입 시 브라우저 주소:
```
https<...>/workspace/authoring?datasetId=4704
```

- `/workspace/authoring` — 포털 SPA 라우트. 이 자리에 저작도구 Remote(`./PortalApp`)가 마운트됩니다.
- `datasetId=4704` — 대상 학습데이터 PK(`tb_datst.data_set_id`).

**전제 — 같은 window·같은 URL**: Module Federation 이라 저작도구는 iframe 도 별도 페이지도 아니고,
포털 SPA 안에 컴포넌트로 그 자리에 마운트됩니다. 따라서 `window.location` 을 포털과 공유하며,
저작도구 컴포넌트에서 현재 URL 의 쿼리스트링을 그대로 읽을 수 있습니다.

```js
// 순수 JS
const datasetId = new URLSearchParams(window.location.search).get('datasetId'); // "4704"

// react-router 사용 시
const [searchParams] = useSearchParams();
const datasetId = searchParams.get('datasetId');
```

- 포털은 `datasetId` 를 **prop 으로 주입하지 않습니다.** 저작도구가 URL 에서 직접 읽습니다.
- 파라미터명은 `datasetId`(대소문자 그대로).
- 값이 없거나 잘못된 경우의 처리(빈 화면 / 안내 / 목록 복귀 등)는 저작도구 화면 정책으로 정해 주세요.

> ※ `?datasetId=...` 는 SPA 클라이언트 라우팅 값이라 서버(Apache/WAS)로 가지 않습니다. 프론트에서 JS 로 읽는 값입니다.

---

## ② 소재 경로 조회 — API-132

읽은 `datasetId` 로 포털 내부 API 를 호출해 NAS 소재 경로를 받습니다.

| 항목 | 값 |
| --- | --- |
| Method | `GET` |
| Path | `/api/internal/v1/datasets/{datasetId}/materials` |
| 성격 | 내부 전용 · 서버간(S2S) · 저작도구 전용 |
| 응답 봉투 | **없음** — 평문 JSON 객체 (일반 공개 API 의 `ApiResponse` 봉투 아님) |

> ⚠️ **호출 주체는 저작도구 «WAS»** 입니다. 브라우저(프론트)에서 부르는 API 가 아닙니다 —
> 내부 전용이라 외부망에서는 Apache 가 `/api/internal/**` 를 차단해 도달 자체가 불가합니다.

- **호스트/포트** — 포털 내부 WAS 주소는 내부망 전용이라 이 문서에 적지 않습니다. **별도 안전 채널로 공유**합니다.
- `{datasetId}` — ①에서 URL 쿼리로 받은 값과 **동일한 PK**(`tb_datst.data_set_id`, int64).

### 요청 헤더
| 이름 | 필수 | 설명 |
| --- | --- | --- |
| `x-api-key` | ✅ | 서버간 API Key (저작도구 전용). **키 값은 별도 안전 채널로 공유** — 문서·코드·URL 노출 금지 |

> **인증 방식** — `x-api-key` 사용 **확정**(저작도구 합의 완료). 추후 저작도구 JWT(서명키·검증
> 규격) 계약이 성립하면 `Authorization: Bearer <JWT>` 로 전환할 수 있으나, 그 전까지는 x-api-key 입니다.

### 요청 예시
```bash
curl -sS \
  -H "x-api-key: <별도 공유된 저작도구 전용 키>" \
  "https://<포털 내부 WAS 호스트>/api/internal/v1/datasets/4704/materials"
```

### 200 응답 — NAS 절대경로 목록
```json
{
  "datasetId": 4704,
  "code": "DS-2026-000123",
  "version": "v1.2",
  "variant": null,
  "repoRootDir": "/nas-storage/data/dataset/repo",
  "files": [
    {
      "fileDv": "DEPLOYMENT_ZIP",
      "fileId": 90011,
      "fileName": "dataset_4704_v1.2.zip",
      "fileSize": 1048576000,
      "checksum": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "localPath": "/nas-storage/data/dataset/repo/4704/v1.2/dataset_4704_v1.2.zip"
    },
    {
      "fileDv": "DATASET_VIDEO",
      "fileId": 90012,
      "fileName": "clip_0001.mp4",
      "fileSize": 524288000,
      "checksum": null,
      "localPath": "/nas-storage/data/dataset/repo/4704/v1.2/videos/clip_0001.mp4"
    }
  ]
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `datasetId` | integer | 데이터셋 PK |
| `code` | string | 배포 코드 (`tb_datst` 멱등키) |
| `version` | string \| null | 배포 버전 `vMAJOR.MINOR`. 없으면 null |
| `variant` | string \| null | 변형 식별자(예 `PRVC01` 비식별본). 없으면 null |
| `repoRootDir` | string | 데이터셋 저장소 NAS 루트. `localPath` 는 이 아래에 위치 |
| `files[]` | array | 소재 원본 파일 목록 — 배포 zip 1건 + 영상 0..N. **파생물(썸네일·미리보기)은 제외** |
| `files[].fileDv` | enum | `DEPLOYMENT_ZIP`(배포 zip) / `DATASET_VIDEO`(내부 학습데이터 영상) |
| `files[].fileId` | integer | 파일 PK (`tb_datst_file.file_id`) |
| `files[].fileName` | string | 파일명 |
| `files[].fileSize` | integer | 파일 크기(바이트) |
| `files[].checksum` | string \| null | 관제지원 신고 SHA-256(hex 64). 없으면 null(구 데이터) |
| `files[].localPath` | string | **★ NAS 절대경로 — 저작도구가 마운트로 직접 열 실경로.** 내부 전용, 외부 노출 절대 금지 |

### 오류 (평문 객체 — 봉투 없음)
| status | code | 상황 |
| --- | --- | --- |
| 401 | `UNAUTHORIZED` | API Key 미제시·불일치, 또는 외부망 접근(Apache 차단) |
| 404 | `RESOURCE_NOT_FOUND` | `datasetId` 에 해당하는 데이터셋 없음 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "path": "/api/internal/v1/datasets/4704/materials",
  "message": "데이터셋을 찾을 수 없습니다.",
  "timestamp": "2026-09-10T05:00:00Z"
}
```
> `path` 는 404 응답에만 포함됩니다. 401/500 은 `code`·`message`·`timestamp` 입니다.

---

## 연동 시 확인·주의

1. **URL → API 동일성** — ①에서 URL 쿼리로 받은 `datasetId` 와 ②API 경로 파라미터는 같은 PK 여야 합니다.
2. **내부망에서만 호출** — 저작도구 WAS → 포털 WAS 서버간 호출입니다. 프론트/외부망에서 부르면 401(Apache 차단).
3. **`localPath` 는 마운트 실경로** — 포털·저작도구가 **동일 NAS 를 동일 경로로 마운트**합니다(확정). `localPath` 를 그대로 열면 됩니다.
4. **원본 zip 은 읽기 전용, 사본을 풀어서 사용** — `localPath` 의 배포 zip 은 포털 소유 원본입니다. **이동·수정·삭제 금지.** 실제 사용 시 저작도구가 관리하는 디렉토리에 **복사/압축 해제**해서 그 사본으로 작업하세요. 원본을 건드리면 포털 배포본·checksum 무결성이 깨집니다.
4. **`checksum` null 허용** — 구 데이터는 null 일 수 있습니다. 무결성 검증 시 null 분기.
5. **키 관리** — `x-api-key` 값은 로그·URL·소스에 남기지 마세요(포털도 접근로그에서 마스킹 처리).
6. **파생물 제외** — 응답 `files[]` 는 원본(zip·영상)만입니다.

## 확정 사항 (저작도구 확인 완료)

1. ✅ **datasetId 직접 읽기** — 저작도구 `./PortalApp` 이 `window.location.search` 에서 `datasetId` 를 직접 읽습니다. (포털 prop 주입 없음)
2. ✅ **네트워크** — 저작도구 WAS 가 위 엔드포인트를 `x-api-key` 로 호출 가능한 내부망 구성입니다.
3. ✅ **동일 NAS 경로** — 포털·저작도구가 **동일 NAS 를 동일 경로로 마운트**합니다. (`localPath` 그대로 사용 — 재매핑 불필요)
4. ✅ **인증 = x-api-key 확정** — 우선 `x-api-key` 로 착수 확정. (추후 JWT 전환은 별도 협의 시)
