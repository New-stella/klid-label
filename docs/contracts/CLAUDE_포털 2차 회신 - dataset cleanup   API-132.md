# 포털 → 저작도구 2차 회신 : dataset cleanup + 소재 조회(API-132)

> 작성일: 2026-09-09
> 대상: 저작도구 회신 「portal-dataset-cleanup-trigger-회신-20260909」
> 회신 주체: 포털 개발팀

저작도구의 계약 수용·구현·검증(9,238건) 확인했습니다. 감사합니다. 아래는 저작도구가 되물은
2건에 대한 회신입니다.

---

## 1. cleanup 트리거 — 우리 쪽 상태

- 저작도구가 확정한 사항(x-api-key·재시도 분기·필드 상한 20/20·멱등·호출조건)은 **포털 구현과
  모두 일치**합니다. 우리 쪽 코드 변경 없습니다.
- 실동작은 저작도구 회신 대기 3건(**① x-api-key 값 · ② base-url · ③ TLS**)에 달려 있습니다.
  ①·②가 오면 포털은 **설정만 교체**합니다(코드 무변경):
  - x-api-key → `portal.ingest.authoring-cleanup-api-key` (yml)
  - base-url → 운영설정 `tb_oper_stng.author_base_url` (재배포 없이 DB 수정)
- ⇒ **연결 시험은 ①·② 받은 뒤** 진행하겠습니다(그전 호출은 401/연결거부가 정상).

---

## 2. §7 회신 — **일일 저작 집계 창구도 `x-api-key` 로 연동합니다** ✅

같은 경로 접두(`/v1/portal-system/`)를 쓸 **일일 저작 집계 창구**도 **cleanup 트리거와 동일하게
`x-api-key` 헤더**로 인증합니다.

- 인증 수단이 **동일**하므로, 저작도구는 **접두 `/v1/portal-system/` 전체를 한 매처로** 덮으시면
  됩니다(창구마다 매처를 가르실 필요 없음).
- 키 값도 **cleanup 트리거와 같은 저작도구 발급 키**를 씁니다(축 하나 = 키 하나).

> ⇒ 저작도구의 "같은 키라면 접두 전체 한 매처" 경로로 진행하시면 됩니다.

---

## 3. API-132 — 데이터셋 소재 경로 조회 (저작도구가 «포털을 호출»하는 창구)

저작도구가 §9 에서 요청한 **신규 데이터마트 조달 창구의 응답 규격**입니다.
버전업 후 저작도구가 새 데이터마트 원본(zip·영상)의 **NAS 로컬 절대경로**를 받아가는 입구입니다.
파일 바이트는 싣지 않습니다 — 경로만 주고, 저작도구가 그 경로로 **공유 스토리지를 직접 읽습니다**.

> ⚠ 이 창구는 **cleanup 트리거와 방향이 반대**입니다. cleanup 은 포털→저작도구(포털이 호출),
> API-132 는 **저작도구→포털(저작도구가 호출)** 입니다.

### 3.1 창구

| 항목 | 값 |
|---|---|
| 메서드·경로 | **`GET {포털-내부-base}/api/internal/v1/datasets/{datasetId}/materials`** |
| 방향 | 저작도구 → 포털 (저작도구가 호출) |
| 인증 | **`x-api-key` 헤더** + **출발지 IP 화이트리스트**(Apache). `/api/internal/**` 전 경로 공통 보호 |
| ⚠ 이 키는 «포털 인바운드 키» | cleanup 트리거의 키(포털→저작도구)와 **다른 축**입니다 — 저작도구가 포털을 부를 때 쓰는 키(관제지원 수신과 같은 인바운드 계통). 별도 공유 |
| 요청 본문 | 없음 (GET) |

- **경로 변수**: `datasetId` — 정수(Long). 숫자가 아니면 404.
- ⚠ `{포털-내부-base}` 및 화이트리스트 등록 IP 는 인프라 확정 후 별도 공유합니다.

### 3.2 응답 (성공 200)

★ **시스템간 계약이라 포털 표준 봉투(`{code,message,data}`)를 쓰지 않습니다** — 아래 평문 객체
그대로입니다(API-098/099/100 과 동일 규약).

| 필드 | 타입 | 설명 |
|---|---|---|
| `datasetId` | number | 조회한 데이터셋 ID |
| `code` | string \| null | 데이터셋 코드(수신 원장 기준). 원장 없는 옛 데이터는 `null` |
| `version` | string \| null | 버전(`vMAJOR.MINOR`). 카탈로그 보유값 |
| `variant` | string \| null | 소재 구분(수신 원장). 옛 데이터는 `null` |
| `repoRootDir` | string | NAS 저장소 루트(운영설정 `dataset_repo_root_dir`) |
| `files` | array | 원본 소재 목록(`DEPLOYMENT_ZIP`·`DATASET_VIDEO`만. 썸네일·미리보기 파생물 제외) |
| `files[].fileId` | number | 파일 ID |
| `files[].fileDv` | string | 파일 구분 — `DEPLOYMENT_ZIP` \| `DATASET_VIDEO` |
| `files[].fileName` | string | 파일명 |
| `files[].localPath` | string | **NAS 로컬 절대경로**(저작도구가 직접 읽는 경로) |
| `files[].fileSize` | number \| null | 바이트 크기 |
| `files[].checksum` | string \| null | 체크섬(관제지원 제공값 복사) |

### 3.3 요청/응답 예시

**요청**
```
GET /api/internal/v1/datasets/779/materials
Host: <포털-내부-host>
x-api-key: <포털이 발급·공유한 인바운드 키>
```

**응답 (200)**
```json
{
  "datasetId": 779,
  "code": "DS-2026-001",
  "version": "v3.0",
  "variant": "ORIGINAL",
  "repoRootDir": "/nas-storage/data/portal/repo",
  "files": [
    {
      "fileId": 4011,
      "fileDv": "DEPLOYMENT_ZIP",
      "fileName": "images_json.zip",
      "localPath": "/nas-storage/data/portal/repo/dataset/DS-2026-001/v3.0/raw/images_json.zip",
      "fileSize": 18453,
      "checksum": "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b"
    },
    {
      "fileId": 4012,
      "fileDv": "DATASET_VIDEO",
      "fileName": "video.mp4",
      "localPath": "/nas-storage/data/portal/repo/dataset/DS-2026-001/v3.0/raw/video.mp4",
      "fileSize": 20480000,
      "checksum": "9f2c1b0a7e5d4c3b2a190f8e7d6c5b4a3928170615243342a1b0c9d8e7f60514"
    }
  ]
}
```

### 3.4 오류

| 코드 | 언제 |
|:-:|---|
| **404** | 없는 `datasetId` / 경로변수가 숫자가 아님 (RESOURCE_NOT_FOUND) |
| **401** | `x-api-key` 누락·불일치 |
| **403** | 출발지 IP 가 화이트리스트 밖 (Apache 차단) |

### 3.5 짚을 점

- `localPath` 는 **NAS 로컬 절대경로**입니다. 포털과 저작도구가 **같은 NAS 를 공유 마운트**해야
  저작도구가 그 경로를 읽을 수 있습니다(INT-002·ADR-016 전제).
- `code`·`variant` 는 수신 원장(`tb_dstrb_rcptn`)에만 있어 원장 없이 적재된 옛 데이터는 `null`
  일 수 있습니다. `version` 은 카탈로그 보유값이라 채워집니다.
- 소재는 **원본(zip·영상)만** 내려갑니다. 썸네일·미리보기 같은 파생물은 이 응답에 없습니다.

---

## 4. 정리 — 남은 것

| # | 항목 | 담당 |
|:-:|---|---|
| ① | cleanup x-api-key 값 | 저작도구 → 포털 (인프라 대기) |
| ② | cleanup base-url(dev/운영) | 저작도구 → 포털 (인프라 대기) |
| ③ | cleanup TLS | 저작도구 → 포털 (인프라 대기) |
| ④ | API-132 포털 인바운드 x-api-key·화이트리스트 IP·내부 base | **포털 → 저작도구** (인프라 확정 후 공유) |
| ⑤ | 일일 저작 집계 창구 인증 | **회신 완료 — x-api-key(§2)** |

★ ①②③이 오면 포털은 설정만 교체하면 cleanup 통보가 실동작합니다.
★ API-132 는 이미 구현·서빙 중이므로, ④(접속 파라미터) 공유만 맞추면 저작도구가 바로 호출 가능합니다.
