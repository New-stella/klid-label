# 16. 포털 (외부 채널)

> 출처: CLAUDE.md(포털), R2 KLID-AT-ACT-003, ADR-013, 코드(`portal/`, `frontend portal/`)
> 관련: [03 인증·권한](03-auth-roles.md) · [04 화면·IA](04-screens-ia.md)

화면: 포털 홈(데이터마트 영상 선택 `/portal`), `KLID-AT-SC-029`(포털 라벨링 `/portal/label/:id`). 코드: `portal/`(7 파일).

## 16.1 데이터 소스

```
관제서버 → 데이터마트 → 포털 DB 적재 (관제서버 책임)
        ↓
포털 라벨 화면은 저작도구 DB(control)에서 Load
```

> ⚠ **구 서술 폐기(2026-08-16 코드 실측)** — *"저작도구는 포털 DB에서 Load (PortalDataSourceConfig
> 듀얼 데이터소스)"* 는 **사실과 다르다.** `PortalLabelService` 는 `controlTransactionManager` 로
> 묶이고 그것이 쓰는 리포지토리(`LsDataLblRepository`·`LsDataSrcRepository`·`LsPortalUserLabelRepository`·
> `LsRawDataStatusRepository`·`VideoRepository`)는 **전부 control(저작도구) 데이터소스**다.
> `@PortalRepo` 를 쓰는 것은 **메타 복제 축 하나뿐**이고(`PortalDatasetVideoMetaRepository`·
> `PortalMetaReplicaWriter`·`MetaReplicationWorker`) 그 방향은 **저작도구 → 포털 DB 쓰기**다(단방향
> at-least-once 복제). 즉 포털 DB 는 저작도구가 **읽는 곳이 아니라 내보내는 곳**이다. 그 서술대로
> 이해하면 포털 화면의 조회 경로를 엉뚱한 데이터소스에서 찾게 된다.

- 관제서버가 제공한 데이터마트를 포털에 등록 → 포털 사용자가 영상 선택 → 기존 저장 라벨/메타 Load → 라벨링 화면 표시

## 16.2 저장 정책 (단방향)

- **저장 시 원본·데이터마트 미수정** — 사용자별 작업 데이터로 `LS_PORTAL_USER_LABEL`에 **별도 적재**
- 데이터마트에 정합/반영 안 됨 (단방향)
- 다운로드는 사용자 작업 데이터 기준 — **보존기간 안에서만**(2026-08-17, 아래 16.4a·16.6 참조)
- 기여도 점수 없음

> ⚠ **구 정책 폐기(2026-08-17, 사용자 확정)** — *"포털 데이터마트 다운로드는 포털 자체 책임 · 다운로드
> 기간 제한 미해소"*(구 V1.5)는 **뒤집혔다**. 저작도구가 데이터마트 작업 데이터 ZIP 다운로드를
> 직접 구현하고, "본인 데이터 기간 내" 제약은 **보존기간 만료 자동 삭제**로 실현된다 — 보존기간이
> 지나면 다운로드할 데이터 자체가 사라진다(별도의 다운로드 시점 검증 로직이 아니다). 상세는 16.4a·16.6.

## 16.3 포털 범위 (ADR-013)

| 기능 | 제공 |
|------|:----:|
| 데이터마트 영상 선택 | ✓ |
| 기존 라벨 확인·수정·저장 (BBOX/POLYGON 수동) | ✓ |
| 본인 데이터 다운로드 | ✓ — 업로드 자산 원본/export(F-7) + **데이터마트 작업 데이터 ZIP**(2026-08-17 신설, 16.4a) |
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
>   `ToolBar.test.tsx`(구 `DarkToolbar.test.tsx`)·`useLabelingShortcuts.test.tsx`·`ShortcutCheatSheet.test.tsx`·
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

## 16.4a 데이터마트 작업 데이터 ZIP 다운로드 (2026-08-17 신설, API-203)

> ★확정(2026-08-17, 사용자 확정) — 구 V1.5 "포털 다운로드는 포털 자체 책임" 정책 폐기.
> `PortalDatamartDownloadController`(`download`) → `PortalDatamartDownloadTxService`(`plan`,
> DB 단계) → `PortalDatamartDownloadService`(`download`, 파일·ZIP 스트리밍 단계) 3빈 구성이며,
> **DB 트랜잭션과 파일 I/O 를 빈 자체로 분리**한다(영상이 포함되면 응답이 GB 급이라 커넥션을 쥔 채
> NAS I/O 를 하면 커넥션 기아가 난다 — 프레임 이미지 서빙과 동일 규약).

| Method · URL | 설명 | 응답 |
|---|---|---|
| `GET /v1/portal/datamart/videos/{rawSn}/download` | **작업 데이터 ZIP 다운로드**. 본인 저장 라벨 기준으로 `{rawSn}/labels.json`(프레임별 라벨, 본인 저장분 우선) + `{rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg`(비식별 프레임 이미지) + `{rawSn}/video.{ext}`(비식별 영상, **있을 때만**) 를 ZIP 하나로 스트리밍. **원본(비식별 이전) 영상·이미지는 어떤 경우에도 담기지 않는다**(원본 폴백 금지). 다른 사용자가 저장한 라벨은 포함되지 않는다(본인만). `Cache-Control: no-store` | 200(ZIP) / 401(토큰 미상) / 403(데이터마트 미노출 — 미승인·미존재 rawSn 동일 처리, 존재 여부 오라클 차단) / 410(본인 저장 라벨 0건 — 신규 미작업 또는 보존기간 만료 삭제) / 412(비식별 누락 신고 구간) / 429(요청량 초과) |

**판정 순서**(오라클 누출 방지 — `PortalDatamartDownloadTxService.plan` 이 고정 순서로 평가):
①인증(PORTAL_USER) → ②속도 제한(429, per-user 분당 3회 — `portalDatamartDownload` RateLimiter config,
형제 제한기와 동일하게 **노드별 in-memory** 라 2노드 Active-Active 배포에서 실질 한도는 2배) →
③데이터마트 노출(403) → ④비식별 누락 신고(412) → ⑤본인 저장 라벨 0건(410) → ⑥200 ZIP.
**③이 ④보다 먼저다** — 뒤집으면 데이터마트에 노출되지도 않은 영상의 신고 상태가 응답으로 새어나간다(CWE-209).

경로 판정기는 축마다 다르다 — 프레임 이미지는 `StorageSubtreePolicy.verifyDeidentifiedFile`
(`frames/deid/**`·`videos/**` 서브트리), 비식별 영상은 `VideoArtifactRootResolver.resolveRealPathUnder`
+ `readableDeidVideoBases`(co-locate 위치 포함). 어느 축이든 판정이 돌려준 **실경로**로만 열고
(`FrameImageService.openNoFollow`, `NOFOLLOW_LINKS`), 검증 실패 파일은 사유 코드만 로그로 남기고
조용히 빠진다(전건 거부 아님).

> ⚠ **화면 배선은 이번 범위 밖이다.** 위 API 는 백엔드만 신설됐고, 포털 홈(SCREEN-028)에 이 API 를
> 호출하는 다운로드 버튼은 **아직 없다**(16.5 의 `PortalHomePage` 서술·FE 회귀 테스트 모두 무변경).
> 화면 배선은 별도 라운드에서 진행한다.

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

## 16.6 보존기간 · 자동 삭제 (2026-08-17 신설, DFEAT-055)

> ★확정(2026-08-17, 사용자 확정) — "본인 데이터 기간 내" 제약은 **다운로드 시점 검증이 아니라
> 보존기간 만료 후 데이터 자체를 지우는 방식**으로 구현됐다. 판정 단일 지점은
> `PortalRetentionPolicy`, 삭제 배치는 `PortalRetentionSweepJob`(오케스트레이션) +
> `PortalRetentionSweepTxService`(트랜잭션 경계) 2빈 구성(자기호출로 인한 `@Scheduled` 프록시 우회
> 방지 — `PortalUploadSweepJob`과 동일 패턴).

### 축과 기준점

| 축 | 대상 | 기준점 | 설정 키 | 기본값 |
|---|---|---|---|---|
| 데이터마트 라벨 | 포털 사용자가 데이터마트 영상에 저장한 라벨(`LS_PORTAL_USER_LABEL`) | 그 (사용자, 영상) 저장 라벨의 `MAX(REG_DT)` | `portal.datamart.retention-days` | 7일 |
| 업로드 자산 — READY | 정상 처리 완료된 본인 업로드 자산 | 등록일과 그 자산 라벨 최종 저장일 중 **늦은 쪽**(재작업 시 기준점이 밀린다) | `portal.upload.retention-days` | 7일 |
| 업로드 자산 — FAILED | 처리 실패한 본인 업로드 자산 | FAILED 전이 시각(`MDFCN_DT`) | `portal.upload.failed-retention-days` | 1일 |
| 업로드 자산 — PROCESSING·UPLOADED | 처리 중인 자산 | — | — | **만료 없음**(삭제 후보 쿼리 자체가 상태 리터럴로 스코프돼 구조적으로 후보가 될 수 없다) |

- **하한 1, 상한 3650**(`ConfigKeys.NUMBER_RANGE`) — 0/음수는 저장 시점에 거부된다. 0 은 "오늘 것까지
  지운다", 음수는 미래 시각이 커트라인이 되어 전량이 대상이 된다. 복구 수단이 없는 파괴적 배치라
  값 자체를 입구에서 막는다.
- **FAILED 를 READY 와 별도 키로 둔 것은 의도**다 — 실패 자산은 사용자가 다시 올리면 되는 잔여물이라
  정상 자산과 같은 기간을 붙잡아 둘 이유가 없다.
- **설정이 없으면 폴백하지 않고 그 축을 건너뛴다**(다른 설정 소비자의 fail-safe 폴백 관례와 **다른**
  의도적 이탈) — 파괴적 기능이 fail-open 하면 "설정을 못 읽어 아무도 지시하지 않은 기본값으로 사용자
  데이터를 지운다"가 성립한다. 시드(`V11__seed_portal_retention_config.sql`, 7/7/1)가 필수인 이유다 —
  「폴백 금지」와 「시드」는 세트다.

### 삭제 절차

- **데이터마트 라벨**: 파일이 없어 (사용자, 영상) 그룹마다 **조건부 DELETE 1회**로 끝난다. 2노드
  Active-Active 에서 중복 실행돼도 두 번째 노드는 0행으로 멱등하다.
- **업로드 자산**: **파일 먼저, DB 나중** 순서로 삭제한다. 뒤집으면 DB 가 먼저 사라져 어느 파일을
  지워야 하는지 알 수 없어져 고아 파일이 영구히 남는다. 파일 경로 판정(`PortalStoragePathGuard`)이
  `OK` 가 아니면(서브트리 밖·해석 불가) 그 자산을 **통째로 건너뛴다** — 파일이 남았는데 DB 만 지우면
  더 나쁘므로 DB 행도 남기고 다음 회차에 재후보한다. **`LS_PORTAL_ULD_FRME`·`LS_PORTAL_ULD_LBL`은
  DB FK `ON DELETE CASCADE`** 로 자산 행 삭제 시 함께 정리된다.
- DB 삭제는 **조건부 UPDATE/DELETE**(스캔 시점과 같은 커트라인으로 재확인)로 2노드 동시 실행을
  멱등화한다 — 별도 분산 락을 새로 만들지 않는다.
- 삭제는 **비가역**이다 — 복구 API·배치는 없다.

### 만료 예정 시각 고지 — 조회 시점 파생값(AC-033)

- `GET /v1/portal/datamart/videos` 응답의 `myLabelExpiresAt`, `GET /v1/portal/uploads`·
  `GET /v1/portal/uploads/{uldSn}` 응답의 `expiresAt` 로 노출한다.
- **엔티티 컬럼이 아니라 조회 시점에 설정값으로 계산되는 파생값**이다 — 보존기간을 7일에서 14일로
  바꾸면 **이미 저장된 라벨의 만료 예정도 다음 조회부터 즉시** 달라진다(마이그레이션·백필 불필요).
  설정값 자체는 `SystemConfigService` 의 Caffeine 캐시(TTL 60s)를 타지만 설정 갱신 시 캐시를
  전체 비우므로 즉시 반영이 성립한다.
- 저장 라벨/자산이 없거나 보존기간 설정이 없으면 그 필드만 `null`(목록 조회 자체는 정상 200 — 조회
  경로에서는 설정 부재 예외를 그대로 올리지 않는다. 삭제 배치는 반대로 그 회차를 건너뛴다 — 둘 다
  "설정이 없으면 아무 일도 일어나지 않는다"로 일관된다).

## 16.7 관련 데이터 (DB)

`LS_PORTAL_USER_LABEL` (V47) — `PORTAL_USER_NO`, 원본 `LS_DATA_LBL` 미수정. → [18](18-database.md).

**보존기간 설정 3키**(`LS_SYSTEM_CONFIG`, V11 시드) — `portal.datamart.retention-days`(7) ·
`portal.upload.retention-days`(7) · `portal.upload.failed-retention-days`(1). NUMBER 도메인,
허용 범위 [1, 3650].
