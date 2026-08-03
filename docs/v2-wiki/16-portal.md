# 16. 포털 (외부 채널)

> 출처: CLAUDE.md(포털), R2 KLID-AT-ACT-003, ADR-013, 코드(`portal/`, `frontend portal/`)
> 관련: [03 인증·권한](03-auth-roles.md) · [04 화면·IA](04-screens-ia.md)

화면: 포털 홈(데이터마트 영상 선택 `/portal`), `KLID-AT-SC-029`(포털 라벨링 `/portal/label/:id`). 코드: `portal/`(7 파일).

## 16.1 데이터 소스

```
관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임)
        ↓
저작도구는 포털 DB에서 Load (PortalDataSourceConfig 듀얼 데이터소스)
```

- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면 표시

## 16.2 저장 정책 (단방향)

- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 **별도 적재**
- 데이터마트에 정합/반영 안 됨 (단방향)
- 다운로드는 사용자 작업 데이터 기준
- 기여도 점수 없음

## 16.3 포털 범위 (ADR-013)

| 기능 | 제공 |
|------|:----:|
| 데이터마트 영상 선택 | ✓ |
| 기존 라벨 확인·수정·저장 (BBOX/POLYGON 수동) | ✓ |
| 본인 데이터 다운로드 | ✓ |
| **SAM2 인터랙티브 분할·자동추적** | ✗ (2026-08-02 제거 — ADR-013 정합) |
| **키포인트(SKELETON) 도구** | ✗ (2026-08-02 제거 — ADR-013 정합) |
| YOLO 파이프라인 오토라벨 | ✗ |
| **트랙 rename/머지 (Phase 10)** | ✗ (포털 라벨은 트랙 데이터모델 부재 — 프레임별 단건) |
| VLM·버전관리·검수 | ✗ |
| 업로드 | ✗ (단, 포털 **본인 자산** 업로드는 별도 경로 — 아래 참조) |

> **★ 포털 SAM2 제거 (2026-08-02 확정, 구속)**: 구 "Phase 9 (ADR-013 override)"로 포털에 열려 있던
> SAM2 인터랙티브 분할·자동추적·키포인트를 **전면 제거**했다. ADR-013 정본이 "포털은 오토라벨링·SAM2·
> VLM·버전관리·검수 미제공"을 명시하는데 구현만 override 상태로 남아 정책과 코드가 어긋나 있었다.
> - **BE**: `PortalSam2Controller`·`PortalSam2Service` 삭제 → `POST /v1/portal/frames/{srcSn}/sam2-segment`·
>   `sam2-track` 은 **404**(핸들러 부재). `portalSam2` Bulkhead 빈·RateLimiter config 도 함께 제거.
> - **FE**: `PORTAL_HIDDEN_TOOLS = [SAM_SEGMENT, TRACK, KEYPOINT]` — 도구바 버튼·키보드 단축키
>   (G / Shift+T / K)·단축키 도움말이 모두 이 단일 소스로 게이팅된다. FE 게이팅은 **UX 편의이며
>   신뢰 경계가 아니다** — devtools 로 채널 상태를 조작해도 서버에 엔드포인트가 없어 무의미하다.
> - **키포인트(SKELETON)는 서버 기능까지 제거 (2026-08-03 보정)**: 위 FE 게이팅만으로는 `lblTypeCd='SKELETON'`
>   저장·조회 round-trip 이 서버에 그대로 살아 있었고(도구만 숨겨진 상태), 애초에 `lblTypeCd` **allowlist 가
>   없어** 16자 이하 임의 문자열이 그대로 `LBL_TYPE_CD` 에 적재됐다. 이제 `POST /v1/portal/user-labels` 는
>   **BBOX\|POLYGON 만** 허용하고(그 외 400) 조회 경로는 삼중값 SKELETON 을 파싱 실패로 스킵한다
>   (레거시 적재 row 도 예외 없이 무시 — 삭제 마이그레이션은 별건). 회귀 가드: `PortalKeypointRemovedTest`.
> - **FE 죽은 코드 정리 (2026-08-03)**: 삭제된 포털 SAM2 경로를 향하던 `portalMode` 분기
>   (`requestSam2Segment`/`requestSam2Track`/`sam2TrackAllChunks`/`useSam2Segment`/`useSam2Track`/
>   `Sam2TrackTool`/`CanvasShell`)를 제거해 내부 경로만 호출하도록 단순화했다(도달 불가 코드 + 현재
>   정책과 반대되는 주석 잔존 제거). 포털 라벨 저장 직렬화도 BBOX/POLYGON 외 형태를 전송 대상에서 제외한다.
> - **내부(INTERNAL) 채널은 무변경** — SAM2 분할/추적은 SFR-08-01(VOS) 핵심 기능이라 그대로 제공한다
>   (`/v1/frames/{id}/sam2-*`, PORTAL 토큰은 채널 격리로 403).
> - 회귀 가드: BE `PortalSam2RemovedTest`(404 + 핸들러 매핑 0건 + 내부 매핑 잔존), FE
>   `DarkToolbar.test.tsx`·`useLabelingShortcuts.portalGating.test.tsx`·`ShortcutCheatSheet.test.tsx`·
>   `LabelingPagePortalRestrictions.test.tsx`.

> **Phase 10(축소) — 포털 트랙 rename/머지 미제공**: 포털 라벨은 **트랙 데이터모델이 없다**
> (`LS_PORTAL_USER_LABEL`에 trackId 컬럼 부재, `/v1/portal/frames/{srcSn}/labels` 로더가 trackId 를 null
> 로 스트리핑, SAM2 자동추적의 trackId 는 FE 세션 opaque 로 미저장 = 프레임별 단건). 따라서 트랙 단위
> rename/머지가 데이터모델상 불가하므로 **포털에서 트랙 번호 변경(연필) UI 를 숨긴다**. 내부 전용
> `mergeTracks`(`POST /v1/videos/{rawSn}/tracks/merge`, `@PreAuthorize(REVIEWER,WORKER)` + `/v1/**`
> `CHANNEL_INTERNAL` 매처)는 PORTAL 채널 403 이므로 절대 호출하지 않는다(FE 이중 안전: `ObjectClassTree`
> `portalMode` 로 버튼 숨김 + `handleRenameTrack` 조기 return). 내부(REVIEWER/WORKER) rename/머지는 무변경.

## 16.4 포털 라벨링 API (PORTAL_USER 전용 — PORTAL 채널 토큰만, R16)

> 채널 격리: `/v1/portal/**` 는 `ROLE_PORTAL_USER` + `CHANNEL_PORTAL` 동시 충족만 허용. 내부 전용
> API(`/v1/frames/**`)는 `CHANNEL_INTERNAL` 강제이므로 포털 토큰은 403 — 포털 라벨링은 아래 포털 전용
> 엔드포인트만 사용한다.

| Method · URL | 설명 | 응답 |
|---|---|---|
| `GET /v1/portal/datamart/videos?page=&size=` | **포털 홈 영상 목록**. 데이터마트 노출(검수 완료=APPROVED) 영상만 페이징. 응답 1행: `rawSn`/`title`(=VMS_CLIP_ID)/`eventName`/`frameCount`/`firstSrcSn`(라벨링 진입용 첫 프레임)/`approvedAt`. **프레임 0건 영상은 진입 불가하므로 제외**, 미승인 영상은 쿼리 게이트(`findAllWithReviewStatus(null, APPROVED, …)`)로 미포함 | 200(Page) / 403(INTERNAL 채널) / 401(토큰 미상) |
| `GET /v1/portal/frames/{srcSn}/labels` | 프레임 단위 라벨 Load. datamart 원본 + 본인 user-label 병합(본인 작업분 우선). `videoId`/`siblings`/`labels` 포함 | 200 / 404(프레임 없음) |
| `GET /v1/portal/frames/{srcSn}/image` | 프레임 **비식별** 이미지 바이너리. 데이터마트 노출(검수 완료=APPROVED) 영상만 | 200(image/*) / 403(미승인 영상) / 404(프레임·비식별파일 없음) |
| `GET /v1/portal/datamart/labels?rawSn=` | 데이터마트 원본 라벨 Load (페이징). 프레임 라벨 Load 와 **동일 게이트** — 데이터마트 노출(검수 완료=APPROVED) 영상만, 비식별 누락 신고 구간은 차단. **미존재 rawSn 도 403**(존재 여부 오라클 차단) | 200 / 400(rawSn 누락·형식 오류) / 401(토큰 미상) / 403(미승인·미존재 영상) / 412(비식별 신고 구간) |
| `GET /v1/portal/user-labels?rawSn=` | 본인 작업 라벨 조회 (IDOR — 본인만). 프레임/데이터마트 라벨 Load 와 **동일 게이트**(APPROVED + 비식별 신고 구간 차단) — 본인이 저장한 사본이라도 좌표는 원본과 같은 PII 위치정보라 한 경로만 열어두면 같은 데이터가 다른 URL 로 새어나간다 | 200 / 400(rawSn 누락) / 401(토큰 미상) / 403(미승인·미존재 영상) / 412(비식별 신고 구간) |
| `POST /v1/portal/user-labels` | 본인 작업 라벨 단건 저장. 원본 미수정 — `LS_PORTAL_USER_LABEL` 적재. 응답에 `points` 포함. **`lblTypeCd` allowlist = `BBOX`\|`POLYGON` 만**(그 외 400, fail-closed — 2026-08-03). 조회 경로와 **동일 게이트**(APPROVED 403 + 비식별 신고 구간 412). **좌표 개수 상한**: BBOX 정확히 2점 / POLYGON 3~200점(형제 `PortalUploadLabelService` 상수 재사용). **본문 크기 2층 방어**: ①`PortalLabelBodySizeFilter` 가 파싱 전에 `portal.upload.max-label-body-bytes`(기본 2MB) 초과 413 · `Content-Length` 부재(chunked) 411 ②`points` 문자열 길이 65,536자 상한(@Valid 400). **per-user 속도 제한**(`portalUserLabel` config, 300회/분) 초과 시 429 | 201 / 400(허용외 lblTypeCd·빈 좌표·좌표 개수 위반·points 길이 초과) / 403(미승인 영상) / 411(chunked) / 412(비식별 신고 구간) / 413(본문 초과) / 429(요청량 초과) |
| ~~`POST /v1/portal/frames/{srcSn}/sam2-segment`~~ | **제거됨 (2026-08-02)** — ADR-013 정합. 핸들러 부재 | 404 |
| ~~`POST /v1/portal/frames/{srcSn}/sam2-track`~~ | **제거됨 (2026-08-02)** — ADR-013 정합. 핸들러 부재 | 404 |

보안 가드: 프레임 이미지/라벨 모두 본인(`portalUserNo`=token sub)·검수 완료 영상으로 한정(CWE-639),
경로 순회 차단(CWE-22, `FrameImageService.resolveSafe` 재사용), 비식별본 고정(원본 폴백 금지).
파라미터 누락/형식 오류는 400(GlobalExceptionHandler — `MissingServletRequestParameter`/`MethodArgumentTypeMismatch`).

## 16.5 UI 특성

- 반응형 웹 (PC/태블릿/모바일), **WCAG 2.1 AA 준수** (NFR-006)
- PortalLayout (모바일 친화, LNB 없음)
- 포털 라벨링 화면은 LabelingPage 재사용 + `portalMode` 분기: 데이터 경로(라벨/이미지/저장)는 포털 전용 API.
  라벨 저장은 포털 user-label(`LS_PORTAL_USER_LABEL`) 단방향(내부 `/v1/frames/...` 미호출).
  **SAM2 분할/자동추적·키포인트 도구는 미노출**(2026-08-02 제거 — 도구바 버튼·단축키 G/Shift+T/K·
  단축키 도움말 모두 `PORTAL_HIDDEN_TOOLS` 단일 소스로 게이팅). YOLO 오토라벨·검수제출·히스토리·
  VLM 메타·비식별 신고도 계속 미노출 (ADR-013). **Phase 10** — 트랙 rename/머지(연필 버튼)도
  미노출(트랙 데이터모델 부재 — 프레임별 단건)
- **포털 홈(PortalHomePage)**: `GET /v1/portal/datamart/videos` 로 데이터마트 노출 영상을 카드 목록(반응형 1~2열)으로
  렌더. 카드/"시작하기" 선택 시 `/portal/label/{firstSrcSn}` 으로 진입. 영상 0건이면 빈 상태 + "시작하기" `aria-disabled`
  (native disabled 미사용 — WCAG 2.1.1 키보드 포커스 순서 유지). 카드는 `<button>` 시맨틱으로 키보드 접근 가능

## 16.6 관련 데이터 (DB)

`LS_PORTAL_USER_LABEL` (V47) — `PORTAL_USER_NO`, 원본 `LS_DATA_LBL` 미수정. → [18](18-database.md).
