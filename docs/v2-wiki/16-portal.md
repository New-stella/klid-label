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

## 16.3 포털 범위 (ADR-013, Phase 9 override)

| 기능 | 제공 |
|------|:----:|
| 데이터마트 영상 선택 | ✓ |
| 기존 라벨 확인·수정·저장 | ✓ |
| 본인 데이터 다운로드 | ✓ |
| **SAM2 인터랙티브 분할·자동추적 (Phase 9)** | ✓ (포털 전용 경로, persist 없이 좌표만) |
| **키포인트(SKELETON) 라벨 (Phase 9)** | ✓ (LS_PORTAL_USER_LABEL 단방향) |
| YOLO 파이프라인 오토라벨 | ✗ |
| **트랙 rename/머지 (Phase 10)** | ✗ (포털 라벨은 트랙 데이터모델 부재 — 프레임별 단건) |
| VLM·버전관리·검수 | ✗ |
| 업로드 | ✗ |

> **Phase 9 (ADR-013 override)**: 포털에 SAM2 인터랙티브 세그멘테이션·자동추적·키포인트를 허용한다.
> 단 **포털 전용 `/v1/portal/frames/**` 경로**로만 동작하며, 서버는 **DB에 저장하지 않고 좌표(폴리곤/마스크)만
> 반환**한다(내부 `LS_DATA_LBL` 불변 — 데이터마트 오염 차단). 포털 사용자의 저장은 **`LS_PORTAL_USER_LABEL`
> 단방향** 적재만 이뤄진다(원본·데이터마트 미수정). **YOLO 파이프라인 오토라벨만 계속 포털 미제공**
> (내부 `/v1/frames/{id}/autolabel` 은 PORTAL 채널 403). 내부 SAM2 경로(`/v1/frames/{id}/sam2-*`)는 서버에서
> 즉시 persist 하므로 포털에 개방하지 않는다(채널 경계 유지).

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
| `GET /v1/portal/datamart/labels?rawSn=` | 데이터마트 원본 라벨 Load (페이징) | 200 / 400(rawSn 누락·형식 오류) |
| `GET /v1/portal/user-labels?rawSn=` | 본인 작업 라벨 조회 (IDOR — 본인만) | 200 / 400(rawSn 누락) |
| `POST /v1/portal/user-labels` | 본인 작업 라벨 단건 저장. 원본 미수정 — `LS_PORTAL_USER_LABEL` 적재. 응답에 `points` 포함. **Phase 9**: `lblTypeCd='SKELETON'` 키포인트는 삼중값(17점 [x,y,v]) 전용 검증 후 저장(개수·형식 위반 400) | 201 / 400(빈 좌표·SKELETON 형식/개수 위반) |
| `POST /v1/portal/frames/{srcSn}/sam2-segment` | **Phase 9** — 포털 SAM2 클릭/박스 분할. **persist 없이 좌표만** 반환. APPROVED 영상만(IDOR 재검증). 포털 전용 `portalSam2` Bulkhead 자원 격리 | 200 / 400(points·box 배타/srcSn 불일치) / 403(비APPROVED·INTERNAL 채널) / 404 / 429(bulkhead 초과) |
| `POST /v1/portal/frames/{srcSn}/sam2-track` | **Phase 9** — 포털 SAM2 자동추적. **persist 없이 좌표만** 반환(내부 `LS_DATA_LBL` 불변). APPROVED 영상만(시작·후속 프레임 IDOR 재검증). Bulkhead 자원 격리 | 200 / 400(srcSn 불일치) / 403(비APPROVED·INTERNAL 채널) / 404 / 429(bulkhead 초과) |

보안 가드: 프레임 이미지/라벨 모두 본인(`portalUserNo`=token sub)·검수 완료 영상으로 한정(CWE-639),
경로 순회 차단(CWE-22, `FrameImageService.resolveSafe` 재사용), 비식별본 고정(원본 폴백 금지).
파라미터 누락/형식 오류는 400(GlobalExceptionHandler — `MissingServletRequestParameter`/`MethodArgumentTypeMismatch`).

## 16.5 UI 특성

- 반응형 웹 (PC/태블릿/모바일), **WCAG 2.1 AA 준수** (NFR-006)
- PortalLayout (모바일 친화, LNB 없음)
- 포털 라벨링 화면은 LabelingPage 재사용 + `portalMode` 분기: 데이터 경로(라벨/이미지/저장)는 포털 전용 API.
  **Phase 9** — SAM2 분할/자동추적·키포인트 도구는 노출하되 호출 URL을 `portalMode`면 `/v1/portal/frames/...` +
  포털 user-label 저장(`LS_PORTAL_USER_LABEL`)으로 분기(내부 `/v1/frames/...` 미호출). YOLO 오토라벨·검수제출·
  히스토리·VLM 메타·비식별 신고는 계속 미노출 (ADR-013). **Phase 10** — 트랙 rename/머지(연필 버튼)도
  미노출(트랙 데이터모델 부재 — 프레임별 단건)
- **포털 홈(PortalHomePage)**: `GET /v1/portal/datamart/videos` 로 데이터마트 노출 영상을 카드 목록(반응형 1~2열)으로
  렌더. 카드/"시작하기" 선택 시 `/portal/label/{firstSrcSn}` 으로 진입. 영상 0건이면 빈 상태 + "시작하기" `aria-disabled`
  (native disabled 미사용 — WCAG 2.1.1 키보드 포커스 순서 유지). 카드는 `<button>` 시맨틱으로 키보드 접근 가능

## 16.6 관련 데이터 (DB)

`LS_PORTAL_USER_LABEL` (V47) — `PORTAL_USER_NO`, 원본 `LS_DATA_LBL` 미수정. → [18](18-database.md).
