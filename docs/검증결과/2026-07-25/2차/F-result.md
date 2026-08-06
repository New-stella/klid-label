# F 클러스터 (포털 외부 채널) — 2차 검증 결과

> 169건 · 기준 실동작(PORTAL_USER 실토큰 + 실파일 업로드 + TUS 실세션) · 2026-07-31


---

# F 클러스터 part1 (F-1~F-4) 2차 검증 결과

> 대상: `docs/test-cases/F-portal.md` 의 **F-1(11) · F-2(19) · F-3(11) · F-4(19) = 60건**
> 환경: 로컬 도커 스택 `backend :18081`(HEAD `ca3c712b` 재빌드본) · `ai-server :19300` · `mock-server :9400` · `postgres :5432`(DB `klid_system`, 스키마 `public`)
> 검증 일시: 2026-07-31 04:00~04:20 KST · 폐기(`~~취소선~~`) 행 **0건**(이 4개 섹션에는 없음)
> 포털 테이블 실재 확인: `ls_portal_uld` · `ls_portal_uld_frme` · `ls_portal_uld_lbl` · `ls_portal_tus_uld` · **`ls_portal_user_label`** 5종 모두 존재 → BLOCKED 없음

## 집계

| 구분 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|---:|---:|---:|---:|---:|---:|---:|
| F-1 채널·역할 게이팅 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| F-2 데이터마트 Load | 19 | 18 | 0 | 1 | 0 | 0 | 0 |
| F-3 사용자 라벨 저장 | 11 | 11 | 0 | 0 | 0 | 0 | 0 |
| F-4 포털 SAM2(정책위반) | 19 | 16 | 3 | 0 | 0 | 0 | 0 |
| **합계** | **60** | **56** | **3** | **1** | **0** | **0** | **0** |

이슈 **8건** — HIGH 2 / MEDIUM 3 / LOW 3
(그중 카탈로그 미도출 신규 결함 1건 = `F-ISSUE-01`, 확정 정책 위반 1건 = `F-ISSUE-03`)

**self-fill 0건** — 포털 SAM2 는 `klid-ai-server` 실왕복(`POST /infer/sam2/segment` 200, `mock=false`, polygon 1139점 / score 0.9027)을 실측했고, 자체 좌표 생성 경로는 발견되지 않았다.

---

## ★채널 격리 실측표 (토큰역할 / 경로 / 기대 / 실측)

토큰은 `POST /v1/dev/tokens`(REVIEWER/WORKER/PORTAL_USER) + `JWT_SECRET` 로 직접 서명한 변종 4종(sub 3002/3003/3004, `PORTAL_USER`+`INTERNAL`, sub 없음, sub 공백) 사용.

| 토큰(role/channel) | 경로 | 기대 | 실측 | 판정 |
|---|---|:--:|:--:|:--:|
| PORTAL_USER / PORTAL | `GET /v1/portal/datamart/videos` | 200 | **200** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/portal/user-labels?rawSn=` | 200 | **200** | ○ |
| WORKER / INTERNAL | `GET /v1/portal/datamart/videos` | 403 | **403** | ○ |
| WORKER / INTERNAL | `GET /v1/portal/uploads` | 403 | **403** | ○ |
| WORKER / INTERNAL | `POST /v1/portal/frames/1/sam2-segment` | 403 | **403** | ○ |
| WORKER / INTERNAL | `POST /v1/portal/user-labels` | 403 | **403** | ○ |
| REVIEWER / INTERNAL | `GET /v1/portal/datamart/videos` | 403 | **403** | ○ |
| REVIEWER / INTERNAL | `GET /v1/portal/uploads` | 403 | **403** | ○ |
| REVIEWER / INTERNAL | `POST /v1/portal/frames/1/sam2-segment` | 403 | **403** | ○ |
| **PORTAL_USER / INTERNAL**(자체서명) | `GET /v1/portal/**` | 403(allOf) | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/videos` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/frames/1/labels` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/frames/1/image` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/frames/1/labels` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/frames/1/sam2-track` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/frames/1/sam2-segment` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/labels/1/deident-report` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/reviews` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/reviews/1/approve` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/tasks/board` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/manage/users` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/manage/labels` | **200**(authenticated 예외) | **200** | ○ |
| PORTAL_USER / PORTAL | `PUT·POST·DELETE /v1/manage/labels/1` | 403 | **403** ×3 | ○ |
| PORTAL_USER / PORTAL | `GET /v1/notices` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/system/configs` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/videos/126/stream` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `GET /v1/augments` | 403 | **403** | ○ |
| PORTAL_USER / PORTAL | `POST /v1/videos/126/resolution` | 403 | **403** | ○ |
| sub 없음 / sub 공백 (PORTAL) | 포털 4경로 전부 | 401 | **401** ×8 | ○ |
| 무토큰 | `/v1/portal/datamart/videos`·`/datamart/labels`·`/frames/130/image`·`/uploads` | 401 | **401** ×4 | ○ |

**양방향 누수 0건.** 부가 확인: `SecurityConfig:90` 의 `"/v1/portal/auth/**" permitAll` 은 매핑된 컨트롤러가 없어(`grep` 0건, 실호출 404) 인증 없는 포털 표면을 만들지 않는다(사표(dead) 매처).

---

## ★신고 게이트 포털 경로 실측 (경로 / 코드 / Cache-Control)

신고 구간 재현: `ls_data_raw.de_ident_yn` 을 **rawSn=152(APPROVED·1프레임 srcSn=130)** 에서 `'Y'→'F'` 로 잠깐 전환 → 실측 → 즉시 `'Y'` 원복(최종 확인 완료). `DeidentReportGate` 는 캐시 없이 `DE_IDNTF_YN` 단일 컬럼 projection 을 매 요청 조회하므로 즉시 반영된다.
⚠ **rawSn=133 은 지시대로 신고 OPEN(`'F'`) 그대로 유지**했고 resolve 하지 않았다.

| 경로 | 정상('Y') | **신고('F')** | 신고 시 Cache-Control | ai-server 호출 | 판정 |
|---|:--:|:--:|---|:--:|:--:|
| `GET /v1/portal/frames/130/labels` | 200 | **412** PRECONDITION_FAILED | (JSON, no-store) | — | ○ |
| `GET /v1/portal/frames/130/image` | 200 | **412** | `no-cache, no-store, max-age=0, must-revalidate` | — | ○ |
| `POST /v1/portal/frames/130/sam2-segment` | 200(실추론) | **412** "비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다." | — | **0건**(ai-server 로그에 `POST /infer/sam2/segment` 1건만 = 정상 호출분) | ○ |
| `POST /v1/portal/frames/130/sam2-track` | 200(실추론) | **412**(동일 게이트, `encodeDeidentifiedFrameForInference` 진입 즉시) | — | 0건 | ○ |
| **`GET /v1/portal/datamart/labels?rawSn=`** | 200 | **200 (미차단)** | — | — | **✗ F-ISSUE-01** |
| `GET /v1/portal/user-labels?rawSn=` | 200 | 200(본인 작업분만) | — | — | △ F-ISSUE-07 |
| `POST /v1/portal/user-labels` | 201 | 201(미차단, 본인 작업분 적재) | — | — | △ F-ISSUE-07 |
| `GET /v1/portal/datamart/videos` (목록) | 포함 | 포함 | — | — | ○(정책상 차단 범위 아님) |

**정상 응답 헤더 실측**(`GET /v1/portal/frames/{127,130,131,65}/image`, 200):
`Cache-Control: no-store` · `X-Content-Type-Options: nosniff` · `Content-Type: image/jpeg` · `Content-Disposition: inline; filename="frame_{srcSn}"`
→ 구 `private, max-age=300` 잔존 **없음**(07-28 보정 반영 확인).

**신고 게이트 판정 범위(★확정 정책 ★1) 실측** — 부모 rawSn=146 을 `'F'` 로 전환:
`/v1/frames/114/labels`(부모) **200→412** / `/v1/frames/153/labels`(파생 159, `ORGNL_RAW_SN=146`) **200→200** / `/v1/frames/153/deid-image` **200→200** → 조상 전파 없음 확인, 원복 완료.

**비식별본 전용 서빙 실증** — `GET /v1/portal/frames/130/image` 응답 md5 `e0df599d…` = 컨테이너 내 `…/deidentified/frames/deid/152/frame-0.jpg` md5 **동일**, 원본 `…/raw/frames/raw/152/frame-0.jpg`(`fae1cc00…`) 와 **불일치**.

---

## ★F-4 포털 SAM2 정책 위반 판정 (실호출 결과 / 전송 이미지 종류 / 심각도 근거)

### 실호출 결과 — **엔드포인트는 실제로 살아 있고 200 을 반환한다**

| 호출 | 결과 |
|---|---|
| `POST /v1/portal/frames/130/sam2-segment` (points) | **200**, `polygon` 1139점 · `score=0.9027518630027771` · `t=2.77s` |
| `POST /v1/portal/frames/130/sam2-segment` (box) | **200**, polygon 반환 |
| `POST /v1/portal/frames/131/sam2-track` (nextSrcSns=[132,133]) | **200**, `tracked` 2건 좌표 반환 · `t=5.21s` |
| ai-server 실경유 | `docker logs klid-ai-server` 에 `172.18.0.5 - "POST /infer/sam2/segment HTTP/1.1" 200 OK` — backend 컨테이너 IP 발신, **실추론**(`mock=false` → 빈 폴리곤이 아님) |
| DB 저장 | `ls_data_lbl` 총건수 저장 전후 **628 → 628 불변**(persist 없음, 좌표만 반환) |

→ **CLAUDE.md / ADR-013 의 "포털은 오토라벨링(YOLO/SAM2) 미제공" 과 정면 충돌.** FE 도 `LabelingPagePortalRestrictions.test.tsx:109-124` 가 "AI 분할 / AI 추적 / 스켈레톤 버튼이 **노출된다**"를 회귀 가드로 고정하고 있어 UI 표면까지 확정 노출이다.
→ **TC-PORTAL-060 · 061 · 072 = FAIL(정책 위반).** 정상기능 케이스로 두지 않는다.

### 전송 이미지 종류 — **비식별본만** (원본 폴백 없음)

| 근거 | 내용 |
|---|---|
| [실동작] 결정적 판별 | `srcSn=36`(rawSn=20012 **APPROVED**, `SRC_FILE_PATH_NM` **존재** / `DE_IDNTF_SRC_FILE_PATH_NM` **NULL**) 에 대해 `sam2-segment`·`sam2-track` 모두 **404 "비식별 프레임이 존재하지 않습니다."** → 원본 경로를 대체 사용하지 않음이 실증됨(구 `encodeToBase64(SRC_FILE_PATH_NM)` 였다면 원본으로 200 또는 500 이 났을 자리) |
| [실동작] 게이트 | 신고 구간에서 412 + ai-server 호출 0건(파일 읽기 전 차단) |
| [정적] 단일 진입점 | `PortalSam2Service.java:115,152,167` → `FrameImageEncoder#encodeDeidentifiedFrameForInference`(`FrameImageEncoder.java:148-152`) → `requireNotUnderDeidentReport` → `resolveDeidentifiedFrame`(`155-171`, deid 경로만·`StorageSubtreePolicy.verifyDeidentifiedFile`) → private `encode(Path)`(`190`) |
| [정적] 쌍둥이 삭제 | `grep -rn "encodeToBase64" backend/src` → **주석 4곳뿐, 메서드 정의·호출 0건.** `FrameImageEncoder` public 메서드는 `resolveFrameImageForInference`·`encodeDeidentifiedFrameForInference`·`encodeFrame` 3개이며 문자열 오버로드 부재 |

### 심각도 근거

| 축 | 07-25 1차 | **07-31 2차** |
|---|---|---|
| 정책 위반(미제공 기능 노출) | 유지 | **유지 — HIGH** (외부 채널에 내부 AI 추론 자원·기능이 계약 밖으로 열림) |
| PII 유출(원본 픽셀 외부 전송) | CRITICAL 급 | **해소 — 비식별본 전용 + 신고 412 + 원본 폴백 404 실증** |
| 데이터마트 오염(persist) | — | **없음 — `ls_data_lbl` 불변 실증** |
| 자원 잠식 | — | **격리됨** — bulkhead 4(실측 8병렬 → 200×4 / 429×4), per-user rate limit 30/1m(실측 31번째부터 429) |

→ **최종 심각도 HIGH**(구 CRITICAL 에서 하향). 잔여 위험은 "외부 채널이 내부 GPU/CPU 추론을 호출할 수 있다"는 **계약·자원 축**이며, PII 축은 닫혔다.

---

## F-1 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTAL-001 | PORTAL_USER+PORTAL 채널로 /v1/portal/** 허용 | PASS | [실동작] `GET /v1/portal/datamart/videos` 200 · `/user-labels` 200 · `/frames/130/labels` 200 · `/frames/130/image` 200 / [정적] `SecurityConfig.java:134-135` / 테스트 `PortalLabelControllerTest:datamartVideos_portalToken_ok` | |
| TC-PORTAL-002 | INTERNAL 채널(WORKER)로 /v1/portal/** 403 | PASS | [실동작] WORKER 토큰 `/portal/datamart/videos`·`/portal/uploads`·`/portal/frames/1/sam2-segment`·`/portal/user-labels` 전부 403 / 테스트 `PortalLabelControllerTest:datamartVideos_internalChannel_forbidden` | |
| TC-PORTAL-003 | REVIEWER(INTERNAL)로 포털 API 403 | PASS | [실동작] REVIEWER 토큰 `/portal/uploads` 403 · `/portal/datamart/videos` 403 · `/portal/frames/1/sam2-segment` 403 | |
| TC-PORTAL-004 | PORTAL_USER이나 channel≠PORTAL → 403 | PASS | [실동작] `JWT_SECRET` 자체서명 `{role:PORTAL_USER, channel:INTERNAL}` → **403**(dev 토큰 API 는 이 조합 자체를 400 으로 거부하므로 자체서명으로 검증) / [정적] `SecurityConfig.java:135` `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` | allOf 두 조건 동시 강제 확인 |
| TC-PORTAL-005 | PORTAL_USER가 내부 /v1/frames/** 차단 | PASS | [실동작] `POST /v1/frames/1/sam2-track` 403 · `/sam2-segment` 403 · `GET /v1/frames/1/labels` 403 · `/image` 403 · `POST /v1/labels/1/deident-report` 403 / [정적] 하위 catch-all `SecurityConfig.java:147-153` / 테스트 `PortalLabelControllerTest:portalTokenBlockedFromInternalSam2AndAutolabel` | `/v1/frames/**` 전용 매처 부재는 카탈로그 주석대로 |
| TC-PORTAL-006 | PORTAL_USER가 /v1/manage/** 차단 | PASS | [실동작] `PUT`·`POST`·`DELETE /v1/manage/labels/1` 403 ×3 · `GET /v1/manage/users` 403 / [정적] `SecurityConfig.java:127` | GET 예외(126행)는 TC-008 |
| TC-PORTAL-007 | PORTAL_USER가 /v1/notices 차단 | PASS | [실동작] `GET /v1/notices` 403 / [정적] `SecurityConfig.java:132` | |
| TC-PORTAL-008 | 라벨 마스터 조회(GET)는 PORTAL_USER 허용 | PASS | [실동작] `GET /v1/manage/labels` **200** / [정적] `SecurityConfig.java:126`(GET 전용 authenticated, REVIEWER 매처보다 앞) | |
| TC-PORTAL-009 | 토큰 sub 없음 → 401 | PASS | [실동작] sub 미포함·sub 공백 자체서명 토큰 × 포털 4경로 = **401 ×8** / [정적] `PortalLabelController.java:127-131`·`PortalUploadController.java:142-146`(카탈로그 138-143 → 실제 142-146, 드리프트) | |
| TC-PORTAL-010 | FE 채널가드: PORTAL_USER의 내부 화면 → forbidden | PASS | [정적] `portalGuard.test.tsx:69-77` `PORTAL_USER로_video_completed_접근시_forbidden` — 2차 FE baseline 1691 tests 전건 GREEN(`test-baseline.md §2`) | |
| TC-PORTAL-011 | FE 채널가드: INTERNAL의 /portal → forbidden | PASS | [정적] `portalGuard.test.tsx:79-91` `INTERNAL_사용자가_portal_접근시_forbidden` + `:93-101` `REVIEWER도_portal_접근시_forbidden` | 카탈로그 `79-101` 은 2개 it 블록을 합친 범위 |

## F-2 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTAL-020 | 데이터마트 목록은 APPROVED만 노출 | PASS | [실동작] DB APPROVED 10건(126·132·136·146·151·152·153·155·156·20012) 중 목록 응답은 **APPROVED 8건만**(0프레임 2건 제외), ASSIGNED 43·PENDING 2·REJECTED 2 는 **전건 미노출** / [정적] `PortalLabelService.java:135-136` INNER JOIN 쿼리 게이트 / 테스트 `PortalDatamartVideosServiceTest:listDatamartVideos_pendingExcludedByGate` | |
| TC-PORTAL-021 | 프레임 0건 영상 목록 제외 | PARTIAL | [실동작] 151·155(0프레임) 제외 확인. **단 필터가 페이징 뒤에 적용** → `size=2` 요청에 content 1건, `size=5` 에 3건 / `totalElements` 가 size 에 따라 10↔8 로 흔들림 | **F-ISSUE-02** |
| TC-PORTAL-022 | 목록 N+1 회피(IN 조회) | PASS | [정적] `PortalLabelService.java:144-146` `findFirstSrcSnGroupedByRawSn`/`countByRawSnsGrouped`/`findAllById` 각 1회 / 테스트 `PortalDatamartVideosServiceTest:listDatamartVideos_approvedOnly_enriched` | 카탈로그 143-145 → 실제 144-146 |
| TC-PORTAL-023 | 라벨 Load 페이징 clamp | PASS | [실동작] `size=99999`→전건(≤100 클램프와 모순 없음, 대상 최대 49건) · `size=0`→1건 · `size=-5`→1건 · `page=-1`→page0 과 동일 결과 / [정적] `PortalLabelService.java:105-106` | |
| TC-PORTAL-024 | rawSn null 라벨 Load → 400 | PASS | [실동작] `GET /v1/portal/datamart/labels`(rawSn 생략) → **400 INVALID_INPUT** "필수 파라미터가 누락되었습니다: rawSn" / [정적] `:102-104` / 테스트 `PortalLabelControllerTest:datamartLabels_missingRawSn_400` | |
| TC-PORTAL-025 | 프레임 라벨 Load: 본인 user-label 우선 | PASS | [실동작] srcSn=94(rawSn=136 APPROVED, 원본 9건) — user-label 1건 저장 후 응답 `n=1 labels=['MY-WORK']`, 삭제 후 `n=9 ['car','person','truck']` 복귀 / [정적] `:285-293` | |
| TC-PORTAL-026 | 프레임 라벨 Load: 없으면 datamart 원본 | PASS | [실동작] 위와 동일 실험의 전/후 상태 + 타 사용자(3001) 관점에서 동일 프레임 `n=9` 원본 반환 / [정적] `:294-301` | |
| TC-PORTAL-027 | 미승인 영상 프레임 라벨 Load → 403 | PASS | [실동작] srcSn=80(rawSn=133 ASSIGNED) → **403 FORBIDDEN** / [정적] `:257-260` / 테스트 `PortalFrameLabelsServiceTest:loadFrameLabels_notApproved_forbidden` | |
| TC-PORTAL-028 | 비존재 srcSn Load → 404 | PASS | [실동작] srcSn=999999 → **404** "프레임을 찾을 수 없습니다." / [정적] `:251-252` | |
| TC-PORTAL-029 | 프레임 이미지 서빙: APPROVED 비식별만 | PASS | [실동작] srcSn=127/130/131/65 → 200, `Cache-Control: no-store`·`nosniff`·`image/jpeg`. **응답 md5 == deid 파일 md5, raw 파일과 불일치** / [정적] `:430-501` / 테스트 `PortalFrameImageServiceTest:serveFrameImage_approvedVideo_returnsDeidImage` | 카탈로그 414-460 → 실제 430-501 |
| TC-PORTAL-030 | 프레임 이미지 서빙: 미승인 → 403 | PASS | [실동작] srcSn=80 → 403 / [정적] `:436-439` | |
| TC-PORTAL-031 | deid 경로 부재 시 원본 폴백 금지 → 404 | PASS | [실동작] srcSn=36(APPROVED, deid NULL·원본경로 존재) → **404** "비식별 프레임이 존재하지 않습니다." / [정적] `:446-450` / 테스트 `PortalFrameImageServiceTest:serveFrameImage_noDeidPath_notFound` | |
| TC-PORTAL-032 | 프레임 이미지 Path Traversal 차단 | PASS | [실동작] deid 경로를 `…/deid/152/../../../../raw/frames/raw/152/frame-0.jpg` 로 임시 치환 → **403** "허용되지 않은 이미지 경로입니다."(원복 완료) / [정적] `:457-459` `StorageSubtreePolicy.verifyDeidentifiedFile` | |
| TC-PORTAL-033 | baseDir=deidentified-path(raw 회귀 방지) | PASS | [실동작] `/etc/passwd` → 403 · `…/raw/frames/raw/152/frame-0.jpg`(raw 서브트리 절대경로) → 403 · **deid 서브트리 안에 둔 원본 심링크(`link.jpg`) → 403**(CWE-59 realpath 판정 실증, 링크 제거 완료) / [정적] `:85-97,457-459` / 테스트 `PortalFrameImageServiceTest:serveFrameImage_symlinkToRawFrame_forbidden`·`serveFrameImage_rawFrameSubtree_forbidden` | |
| TC-PORTAL-034 | 파일 부재 시 내부경로 비노출 404 | PASS | [실동작] 존재하지 않는 파일 → 404 "이미지 파일이 존재하지 않습니다." · 디렉터리 지정 → 404. 응답 본문에 내부 경로 문자열 **없음** / [정적] `:460-471`(verdict 별 404/403 분기, 로그는 verdict 코드만) | |
| TC-PORTAL-035 | 신고 구간 프레임 라벨 Load → 412 | PASS | [실동작] rawSn=152 `'Y'→'F'` 전환 시 `GET /v1/portal/frames/130/labels` **200→412**, 원복 후 200 / [정적] `:262-267` `accessGuard.requireNotUnderDeidentReport` | APPROVED 유지 상태에서 412 확인 = 게이트 독립 성립 |
| TC-PORTAL-036 | 신고 구간 프레임 이미지 서빙 → 412 | PASS | [실동작] 동일 전환에서 `GET /v1/portal/frames/130/image` **200→412** / [정적] `:441-443`(deid 경로 해석·파일 open **이전**) / 테스트 `PortalFrameImageCacheControlTest:portalFrameImageBlockedUnderDeidentReport` | |
| TC-PORTAL-037 | 프레임 이미지 응답 캐시는 no-store | PASS | [실동작] 200 응답 4건 전부 `Cache-Control: no-store`, `max-age` 문자열 **부재** / [정적] `:493-497` `CacheControl.noStore()` / 테스트 `PortalFrameImageCacheControlTest:portalFrameImageIsNotCached` | 카탈로그 452-456 → 실제 493-497 |
| TC-PORTAL-038 | 신고 게이트는 자기 rawSn 행만 판정 | PASS | [실동작] 부모 146 `'Y'→'F'`: 부모 프레임(srcSn=114) labels **412**, 파생 159(`ORGNL_RAW_SN=146`) 프레임(srcSn=153) labels **200**·deid-image **200** → 조상 전파 없음(원복 완료). 포털 경로는 파생이 APPROVED 가 아니라 진입 불가하여 동일 게이트 컴포넌트(`DeidentReportGate`)를 내부 경로로 실측 / [정적] `DeidentReportGate.java:23-37`(ORGNL_RAW_SN 미참조) / 테스트 `AiInferenceDeidentReportGateTest:segmentNotBlockedByOriginReport`·`portalTrackBlockedWhenReported` | 확정 정책 ★1 — 결함 아님 |

## F-3 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTAL-040 | 사용자 라벨 저장은 LS_PORTAL_USER_LABEL만 | PASS | [실동작] 저장 전후 `ls_data_lbl` 총건수 **628 → 628**, `ls_label_version` **36 → 36**, `max(ls_data_lbl.mdfcn_dt)` 불변. srcSn=94 원본 라벨 9건도 저장 후 그대로 9건. 응답 **201** + `ls_portal_user_label` 만 증가 / [정적] `PortalLabelService.java:218-220` / 테스트 `PortalUserLabelServiceTest:saveUserLabel_savesToPortalTable`·`PortalSam2NoPersistIntegrationTest:portalTrackDoesNotPersistInternalLabels` | 단방향 확인. 부가 발견 **F-ISSUE-06** |
| TC-PORTAL-041 | 미승인 sourceRawSn 저장 시도 → 403 | PASS | [실동작] rawSn=133(ASSIGNED·신고중) 403 · rawSn=135(ASSIGNED) 403 · rawSn=999999(부재) 403 / [정적] `:203-206` `isExposedToDatamart` fail-closed / 테스트 `PortalLabelServiceKeypointTest:saveUserLabelNotApprovedForbidden` | |
| TC-PORTAL-042 | SKELETON 저장: 17점 삼중값 통과 | PASS | [실동작] `[[10,10,2]]×17` → **201**, 응답 points 삼중값 보존 / [정적] `:209-210` type-route | |
| TC-PORTAL-043 | SKELETON 개수≠17 → 400 | PASS | [실동작] 16점 → **400** "SKELETON 키포인트는 정확히 17 개여야 합니다." / [정적] `:325-328` / 테스트 `PortalUserLabelServiceTest:saveUserLabel_skeleton_wrongCount_rejected` | 카탈로그 324-327 → 실제 325-328 |
| TC-PORTAL-044 | SKELETON v 범위 밖 → 400 | PASS | [실동작] v=3 → **400** "가시성 v 는 0/1/2 중 하나여야 합니다." / [정적] `:329-332` | |
| TC-PORTAL-045 | SKELETON NaN/Infinity → 400 | PASS | [실동작] x=NaN → **400** "키포인트 좌표 형식이 올바르지 않습니다."(JSON 파서 단계 fail-closed) · 음수 → **400** "좌표는 0 이상이어야 합니다." / [정적] `:334-340` | NaN 은 파싱 단계에서 먼저 걸림 — 어느 경로든 400 |
| TC-PORTAL-046 | 비SKELETON 빈 좌표('[]','[[]]') 거부 → 400 | PASS | [실동작] `"[]"`·`"[[]]"`·`"{broken"` 모두 **400** "points 좌표가 비어있습니다." / [정적] `:211-217` / 테스트 `PortalUserLabelServiceTest:saveUserLabel_emptyPoints_rejected` | |
| TC-PORTAL-047 | points @NotBlank NULL/공백 → 400 | PASS | [실동작] `null`·`"   "` → **400** "points: points 는 필수입니다."(DTO 단) / [정적] `PortalUserLabelRequest.java:17` | |
| TC-PORTAL-048 | 본인 작업 라벨 조회 IDOR(token sub) | PASS | [실동작] 3001 이 45건 보유한 상태에서 **3002 조회 결과 0건**, 3002 관점 `frames/130/labels` 도 0건(3001 작업분 미노출). 역방향도 동일. `PUT`/`DELETE /v1/portal/user-labels/{id}` 는 **엔드포인트 자체가 없음(404)** → 타인 라벨 수정·삭제 표면 부재 / [정적] `:226-235` `findByPortalUserNoAnd…(actor.sub())` — 사용자 식별자를 **요청 바디·파라미터에서 받지 않음** / 테스트 `PortalUserLabelServiceTest:listMyLabels_differentUser_returnsEmpty` | CWE-639 방어 확인 |
| TC-PORTAL-049 | 빈 user-label row Load 제외(stale 방어) | PASS | [실동작] `point_cn` NULL / `'[]'` row 를 DB 직접 INSERT 후 `frames/130/labels` → **labels=0**(빈 항목 제외), 500 미발생. 정리 완료 / [정적] `:285-287` | |
| TC-PORTAL-050 | 손상 좌표 JSON fail-secure | PASS | [실동작] `point_cn='{oops'`(BBOX) · `'[[1,2]]'`(SKELETON 형식위반) row INSERT 후 Load → **200, labels=0**, 500 없음. 백엔드 로그 `label points parse skipped` WARN / [정적] `:352-388` | |

## F-4 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTAL-060 | 포털 SAM2 분할 엔드포인트 존재 자체가 정책 위반 | FAIL | [실동작] `POST /v1/portal/frames/130/sam2-segment` → **200**(polygon 1139점, score 0.90, ai-server 실경유) / [정적] `PortalSam2Service.java:108-134`·`PortalSam2Controller.java:66-78` | **F-ISSUE-03** — CLAUDE.md/ADR-013 "포털 오토라벨링 미제공" 위반 |
| TC-PORTAL-061 | 포털 SAM2 추적 엔드포인트 존재 자체가 정책 위반 | FAIL | [실동작] `POST /v1/portal/frames/131/sam2-track` → **200**(tracked 2건) / [정적] `PortalSam2Service.java:142-190`·`PortalSam2Controller.java:53-61` | **F-ISSUE-03** |
| TC-PORTAL-062 | (구현 유지 시) 미승인 영상 SAM2 → 403 | PASS | [실동작] srcSn=80(rawSn=133) segment → **403** / [정적] `:218-226` / 테스트 `PortalSam2ServiceTest:segment_notApproved_forbidden`·`track_nextFrameNotApproved_forbidden` | |
| TC-PORTAL-063 | (구현 유지 시) 비존재 프레임 → 404 | PASS | [실동작] srcSn=999999 → **404** / [정적] `:219-220` / 테스트 `PortalSam2ServiceTest:segment_notFound` | |
| TC-PORTAL-064 | (구현 유지 시) path≠body srcSn → 400 | PASS | [실동작] path 130 / body 131 → segment **400**, track **400** "path 의 srcSn 과 body 의 srcSn 이 다릅니다." / [정적] `PortalSam2Controller.java:81-85` | CWE-345 |
| TC-PORTAL-065 | (구현 유지 시) mock 응답 자동적용 차단 | PASS | [정적] `PortalSam2Service.java:125-128` `aiRes.mock()` → `Sam2SegmentResponse.empty()` + 컨트롤러 `:75-77` `MOCK_UNAVAILABLE_MESSAGE` / 테스트 `PortalSam2ServiceTest:segment_mock_returnsEmptyPolygon`·`Sam2SegmentMockMessageWiringTest` | 현 환경 ai-server 는 실모델 로드 상태(`mock=false`)라 실동작 재현 불가 — 정적+테스트 근거 |
| TC-PORTAL-066 | (구현 유지 시) bulkhead 초과 → 429 | PASS | [실동작] 단일 사용자 **8병렬** 실추론 → **200 ×4 / 429 ×4**(`max-concurrent-calls: 4`, `max-wait-duration: 0` 과 정확히 일치) / [정적] `:196-204`·`application.yml:599-601` / 테스트 `PortalSam2ServiceTest:bulkheadRejectsExcessConcurrent` | |
| TC-PORTAL-067 | (구현 유지 시) per-user rate limit → 429 | PASS | [실동작] 단일 사용자 36연속 요청 → **1~30번째 통과 / 31번째부터 429 ×6**(`limit-for-period: 30`, `limit-refresh-period: 1m`) / [정적] `:267-275`·`application.yml:608-611` / 테스트 `PortalSam2ServiceTest:perUserRateLimitExceeded` | |
| TC-PORTAL-068 | (구현 유지 시) track wall-clock 예산 초과 → 429 | PASS | [정적] `:154-163` `TRACK_WALL_CLOCK_BUDGET=60s`, 루프 진입 시 `System.nanoTime() > deadline` → `TOO_MANY_REQUESTS` | 60s 를 실제로 넘기는 추론 부하를 만들 수 없어 실동작 미재현. 부가 발견 **F-ISSUE-08**(전용 테스트 부재 + 최대 초과폭) |
| TC-PORTAL-069 | (구현 유지 시) ai 응답 음수/NaN → 400 | PASS | [실동작] **동일 검증기**(`validatePolygon`)를 타는 요청측 `prevPolygon` 으로 대리 실증 — 음수 `[[-1,1],…]` → 400 "폴리곤 좌표는 0 이상이어야 합니다.", 3튜플 → 400 "폴리곤 좌표는 [x, y] 두 값이어야 합니다." / [정적] `:129,177` 이 ai 응답에도 같은 `validatePolygon(:236-254)` 적용(NaN/Infinity `Double.isFinite`) | ai 서버가 정상 좌표만 내보내 응답측 직접 재현 불가 |
| TC-PORTAL-070 | (구현 유지 시) ai 빈/실패 → 502 | PASS | [실동작] deid 경로를 비이미지 파일(텍스트)로 임시 치환 → ai-server 디코드 실패 → **502 EXTERNAL_API_ERROR** "SAM2 segment 호출에 실패했습니다."(내부 예외 원문 비노출, 원복 완료) / [정적] `:120-122,174-176,207-211` | |
| TC-PORTAL-071 | (구현 유지 시) trackId 로그 CRLF 정제 | PASS | [실동작] `trackId="aa\r\nINJECTED-LOG-LINE"` 전송 → 백엔드 로그가 **단일 라인** `trackId=aaINJECTED-LOG-LINE`(CR/LF 제거, 로그 라인 분리 없음) / [정적] `:186-188` `LogSanitizer.sanitize` / 테스트 `PortalSam2ServiceTest:trackIdCrlfSanitizedInLog` | CWE-117 |
| TC-PORTAL-072 | FE 포털 도구바: AI분할/추적/스켈레톤 노출 여부 | FAIL | [정적] `LabelingPagePortalRestrictions.test.tsx:109-124` 가 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 **존재**를 회귀 가드로 고정(= 노출 확정). `AI 탐지`(YOLO)만 숨김 | **F-ISSUE-03** — 정책상 SAM2 도 미노출이어야 함 |
| TC-PORTAL-073 | FE 포털 라벨링: 검수제출 버튼 미렌더 | PASS | [정적] `LabelingPagePortalRestrictions.test.tsx:86-91` `submit-review-button` null 단언 · FE baseline 전건 GREEN | |
| TC-PORTAL-074 | FE 포털 라벨링: VLM/시계열 메타 탭 미노출 | PASS | [정적] `LabelingPagePortalRestrictions.test.tsx:93-105` `시계열 메타`·`VLM` 텍스트/탭 null 단언 ×4 | |
| TC-PORTAL-075 | 포털 SAM2 분할은 비식별 프레임만 전송(원본 폴백 금지) | PASS | [실동작] srcSn=36(APPROVED·deid NULL·**원본 경로 존재**) → segment/track **404 "비식별 프레임이 존재하지 않습니다."** = 원본 경로 미사용 실증. 서빙 md5 대조로 deid≠raw 도 확인 / [정적] `PortalSam2Service.java:113-116,151-152,167` → `FrameImageEncoder.java:148-152,155-171` / 테스트 `AiInferenceDeidentReportGateTest:portalSegmentSendsDeidentifiedPixelsNotOriginal` | |
| TC-PORTAL-076 | 신고 구간 포털 SAM2 분할 412 + ai 호출 0건 | PASS | [실동작] rawSn=152 `'F'` 전환 시 segment **412** PRECONDITION_FAILED, 동 구간 ai-server 로그에 `/infer/sam2/segment` **추가 0건** / [정적] `:115` → `FrameImageEncoder:149` `requireNotUnderDeidentReport`(파일 read 이전) / 테스트 `AiInferenceDeidentReportGateTest:portalSegmentBlockedWhenVideoReported` | |
| TC-PORTAL-077 | 신고 구간 포털 SAM2 추적 412 + ai 호출 0건 | PASS | [실동작] 동일 전환에서 track **412**, ai-server 호출 0건 / [정적] `:152,167` — 시작 프레임 + **후속 프레임마다** 재인코딩 → 프레임별 게이트 재판정 / 테스트 `AiInferenceDeidentReportGateTest:portalTrackBlockedWhenReported` | |
| TC-PORTAL-078 | 게이트 없는 `encodeToBase64(String)` 오버로드 부재 | PASS | [정적] `grep -rn "encodeToBase64" backend/src frontend/src` → **주석 4곳(설명문)뿐, 메서드 선언·호출 0건**. `FrameImageEncoder` public 표면 = `resolveFrameImageForInference(:133)`·`encodeDeidentifiedFrameForInference(:148)`·`encodeFrame(:178)` 3개, 실제 read 는 private `encode(Path)(:190)` 로 수렴 / 테스트 `FrameImageEncoderTest`(:25 주석에 폐지 명시) | |

---

## 근거 드리프트

`PortalLabelService.java` 는 07-30 이후 주석·게이트 추가로 **전 구간이 +1 ~ +45행 밀렸다**. 카탈로그 근거 라인이 실제 코드와 어긋난 항목:

| TC | 카탈로그 근거 | 실제 위치 | 비고 |
|----|------|------|------|
| TC-PORTAL-020 | `PortalLabelService.java:130-161` | `:131-162` | `listDatamartVideos` 본체 |
| TC-PORTAL-021 | `:147-157` | `:148-158` | 0프레임 필터 |
| TC-PORTAL-022 | `:143-145` | `:144-146` | N+1 batch lookup |
| TC-PORTAL-023 | `:104-108` | `:105-106` | clamp 2줄 |
| TC-PORTAL-024 | `:101-103` | `:102-104` | rawSn null |
| TC-PORTAL-025 | `:284-292` | `:285-293` | user-label 우선 |
| TC-PORTAL-026 | `:293-300` | `:294-301` | datamart 폴백 |
| TC-PORTAL-027 | `:256-259` | `:257-260` | APPROVED 게이트 |
| TC-PORTAL-028 | `:250-251` | `:251-252` | 404 |
| TC-PORTAL-029 | `:414-460` | `:430-501` | 414 는 javadoc, 메서드는 430 부터 |
| TC-PORTAL-030 | `:420-423` | `:436-439` | |
| TC-PORTAL-031 | `:430-434` | `:446-450` | |
| TC-PORTAL-032 | `:438-439` | `:457-459` | `verifyDeidentifiedFile` 호출부 |
| TC-PORTAL-033 | `:85-96,438-439` | `:85-97,457-459` | |
| TC-PORTAL-034 | `:440-443` | `:460-471` | verdict 분기 |
| TC-PORTAL-035 | `:261-266` | `:262-267` | 게이트 호출은 267 |
| TC-PORTAL-036 | `:425-427` | `:441-443` | |
| TC-PORTAL-037 | `:452-456` | `:493-497` | `CacheControl.noStore()` |
| TC-PORTAL-040 | `:196-223` | `:196-224` | |
| TC-PORTAL-041 | `:202-205` | `:203-206` | |
| TC-PORTAL-042 | `:208-209` | `:209-210` | |
| TC-PORTAL-043 | `:324-327` | `:325-328` | |
| TC-PORTAL-045 | `:333-336` | `:334-337` | |
| TC-PORTAL-046 | `:211-216` | `:211-217` | |
| TC-PORTAL-048 | `:226-234` | `:226-235` | |
| TC-PORTAL-049 | `:284-286` | `:285-287` | |
| TC-PORTAL-050 | `:375-387` | `:376-388` | |
| TC-PORTAL-009 | `PortalUploadController.java:138-143` | `:142-146` | `requireActor` 본체 |
| TC-PORTAL-011 | `portalGuard.test.tsx:79-101` | `:79-91`(+`:93-101` 은 별개 it) | 2개 it 을 한 범위로 표기 |
| TC-PORTAL-078 | `FrameImageEncoder.java:172-197` | `:182-197`(주석) / `:190`(`encode(Path)`) | 172-181 은 `encodeFrame` |

**드리프트 없음(정확)**: `SecurityConfig.java:126-127·132·134-135·147-153` · `PortalSam2Service.java` 전 인용(108-134·142-190·113-116·115·120-122·125-128·150-167·154-163·174-176·186-188·196-204·218-226·219-220·236-254·267-275) · `PortalSam2Controller.java:81-84` · `PortalUserLabelRequest.java:17` · `FrameImageEncoder.java:148-152` · `LabelingPagePortalRestrictions.test.tsx:86-91·93-105·109-124` · `portalGuard.test.tsx:69-77` · `DeidentReportGate.java:23-37`.

---

## 이슈 상세

### [F-ISSUE-01] TC-PORTAL-023/024 인접 — `GET /v1/portal/datamart/labels` 가 APPROVED 게이트·비식별 신고 게이트를 **둘 다** 통과시킨다 (형제 엔드포인트 미배선)

- **심각도**: HIGH
- **기대 동작(기대효과)**: 포털은 **외부 채널**이므로 데이터마트 노출(`LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`) 영상의 데이터만 볼 수 있어야 한다(`PortalLabelService` 의 다른 3경로와 동일). 또한 비식별 누락 신고 구간(`DE_IDNTF_YN='F'`)에서는 **라벨 좌표가 PII 위치 특정 정보**이므로 412 로 차단돼야 한다(CLAUDE.md "차단 범위 ④ 포털 프레임 라벨").
- **현재 동작(이슈 내용)**: `PortalLabelService.loadDatamartLabels` 에 **어떤 게이트도 없다**. `rawSn` 을 그대로 받아 내부 파이프라인 라벨 테이블(`LS_DATA_LBL`)을 전량 반환한다.

  `backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalLabelService.java:99-116`
  ```java
  public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
      if (rawSn == null) { throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다."); }
      int clampedSize = Math.min(Math.max(size, 1), 100);
      int clampedPage = Math.max(page, 0);
      List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);   // ← isExposedToDatamart 없음
      ...                                                          // ← accessGuard.requireNotUnderDeidentReport 없음
  ```
  같은 클래스의 형제 3경로는 전부 배선돼 있다 — `loadFrameLabels`(`:257-260` APPROVED + `:267` 신고), `serveFrameImage`(`:436-439` + `:443`), `saveUserLabel`(`:203-206` APPROVED). **이 한 메서드만 빠졌다.**

  [실동작] 실측:
  | rawSn | 상태 | `de_ident_yn` | 응답 | 반환 라벨 |
  |---|---|:--:|:--:|---:|
  | 133 | **ASSIGNED**(미승인) | **F**(신고 OPEN) | **200** | **28건** |
  | 135 | ASSIGNED | Y | 200 | 3건 |
  | 143 | ASSIGNED | Y | 200 | 14건 |
  | 126 | APPROVED | Y | 200 | 22건 |

  rawSn=133 은 **미승인 + 신고 OPEN** 이므로 두 게이트 모두에 걸려야 하는데 좌표가 그대로 나간다. 같은 rawSn 의 프레임 단위 경로(`GET /v1/portal/frames/80/labels`)는 정상적으로 403 이다 — 즉 **정책은 있고 이 엔드포인트만 정책 밖에 있다.**
- **재현/확인 경로**:
  ```bash
  PT=$(curl -s -X POST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  # 미승인 + 신고 OPEN 영상의 내부 라벨이 외부 채널로 200 반환
  curl -s -H "Authorization: Bearer $PT" \
    'localhost:18081/api/v1/portal/datamart/labels?rawSn=133&page=0&size=100' | jq '.data | length'   # → 28
  # 대조: 같은 영상 프레임 경로는 403
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $PT" \
    localhost:18081/api/v1/portal/frames/80/labels                                                    # → 403
  ```
  ```sql
  SELECT raw_sn, de_ident_yn FROM ls_data_raw WHERE raw_sn=133;                    -- 133 | F
  SELECT data_stts_cd FROM ls_raw_data_status WHERE raw_data_id=133;               -- ASSIGNED
  ```
- **영향**: **CWE-862(Missing Authorization)** + **CWE-639(IDOR — rawSn 열거만으로 임의 영상 접근)** + **CWE-359(PII)**. 외부 채널 사용자가 rawSn 을 1씩 증가시키며 내부 파이프라인 전 영상의 라벨 좌표·프레임 ID(`srcSn`)·트랙 ID 를 수집할 수 있다. 좌표는 마스킹 실패 위치를 특정하므로 신고 구간에서는 특히 위험하다. 이 프로젝트의 **반복 실패 모드("상태 차단 게이트를 호출처마다 배선하면 반드시 샌다")의 재발**이며, 2차에서 이미 발견된 `privacy-meta`·`description`·`reviews/{videoId}/frames` 와 동형이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `loadDatamartLabels` 진입부에 형제 경로와 **동일 순서**로 ①`if (!isExposedToDatamart(rawSn)) throw FORBIDDEN` ②`accessGuard.requireNotUnderDeidentReport(rawSn)` 를 추가한다. 근본 대책으로는 포털 데이터 접근 경로가 4개(+SAM2 2개)로 늘어난 만큼 "rawSn/srcSn 을 받는 포털 진입점" 을 공통 가드(예: `PortalDatamartAccessGuard.requireAccessible(rawSn)`)로 수렴시켜 **메서드마다 재배선하지 않게** 하고, 전 포털 엔드포인트를 파라미터화한 회귀 IT(미승인·신고 조합)로 고정한다.

### [F-ISSUE-02] TC-PORTAL-021 — 데이터마트 목록의 "프레임 0건 제외" 가 **페이징 이후**에 적용돼 페이지에 구멍이 나고 `totalElements` 가 페이지 크기에 따라 달라진다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 프레임 0건 영상을 제외한 결과가 요청한 `size` 만큼 채워지고, `totalElements` 는 어떤 `size` 로 조회하든 동일해야 한다.
- **현재 동작(이슈 내용)**: DB 쿼리로 페이지를 먼저 자른 뒤 그 페이지 안에서만 0프레임 행을 걸러낸다.

  `PortalLabelService.java:135-161`
  ```java
  Page<LsDataRaw> page = videoRepository.findAllWithReviewStatus(null, STTS_APPROVED, pageable); // 페이징 먼저
  ...
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // 페이지 안에서만 제외
          .map(...).toList();
  return new PageImpl<>(content, pageable, page.getTotalElements());
  ```
  [실동작] APPROVED 10건(그중 151·155 가 0프레임) 기준 실측:
  | 요청 | 반환 content | totalElements | totalPages |
  |---|---:|---:|---:|
  | `page=0&size=2` | **1** | 10 | 5 |
  | `page=0&size=5` | **3** | 10 | 2 |
  | `page=0&size=20` | 8 | **8** | 1 |
  | `page=0&size=50` | 8 | **8** | 1 |
  | `page=1&size=5` | 5 | 10 | 2 |

  `size=2` 요청에 1건만, `size=5` 에 3건만 온다(페이지 구멍). 또 마지막 페이지에서는 `PageImpl` 이 total 을 `offset + content.size()` 로 재계산하므로 `totalElements` 가 10 이 아니라 **8** 이 된다 — 코드 주석 `:160` 의 "totalElements 는 원본(게이트 후) 기준 유지" 라는 의도와도 어긋난다.
- **재현/확인 경로**:
  ```bash
  for s in 2 5 20 50; do
    curl -s -H "Authorization: Bearer $PT" \
      "localhost:18081/api/v1/portal/datamart/videos?page=0&size=$s" \
      | jq -c '{size:'"$s"', n:(.data.content|length), total:.data.totalElements}'
  done
  ```
- **영향**: 포털 홈 목록의 무한스크롤/페이지네이션이 "빈 페이지" 또는 "덜 찬 페이지"를 만들고, 총 건수 표기가 조회 크기에 따라 달라진다. 보안 영향은 없으나 외부 채널 첫 화면의 사용성·정합성 결함이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 0프레임 제외 조건을 **DB 쿼리로 밀어 넣는다**(`AND EXISTS (SELECT 1 FROM LsDataSrc s2 WHERE s2.rawSn = v.rawSn)`) — 그러면 페이징·총건수가 자연히 정합한다. 어쩔 수 없이 앱단 필터를 유지한다면 `PageImpl` 대신 total 을 별도 count 쿼리로 명시 주입해 마지막 페이지 재계산을 피한다.

### [F-ISSUE-03] TC-PORTAL-060 / 061 / 072 — 포털 채널에 SAM2 분할·추적이 노출돼 있다(정책 위반)

- **심각도**: HIGH (구 CRITICAL 에서 하향 — 아래 "완화된 축" 참조)
- **기대 동작(기대효과)**: `CLAUDE.md` "포털 (외부 채널)" 절 + ADR-013 — **오토라벨링(YOLO/SAM2)·VLM·버전관리·검수 미제공.** 포털 사용자는 데이터마트 영상 선택 + 기존 라벨 확인·수정·저장 + 본인 자산 수동 라벨링(BBOX/POLYGON)만 가능해야 한다. 따라서 포털 SAM2 엔드포인트와 FE 도구바 버튼은 **존재하지 않아야** 한다.
- **현재 동작(이슈 내용)**: 전용 컨트롤러·서비스가 살아 있고 실제로 추론이 나간다.
  - `backend/src/main/java/kr/co/cudo/authoring/portal/controller/PortalSam2Controller.java:43,53-78`
    ```java
    @RequestMapping("/v1/portal/frames")
    @PostMapping("/{srcSn}/sam2-track")   @PreAuthorize("hasRole('PORTAL_USER')")
    @PostMapping("/{srcSn}/sam2-segment") @PreAuthorize("hasRole('PORTAL_USER')")
    ```
  - `PortalSam2Service.java:108-190` — `aiServerClient.segment/track` 실호출.
  - FE `frontend/src/features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:109-124` 는 노출을 **회귀 가드로 고정**한다:
    ```ts
    expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();   // YOLO만 숨김
    ```
  - [실동작] `POST /v1/portal/frames/130/sam2-segment` → **200**, polygon 1139점 / score 0.9027 / 2.77s, `docker logs klid-ai-server` 에 backend 컨테이너 IP(`172.18.0.5`) 발신 `POST /infer/sam2/segment 200 OK`. `sam2-track` 도 200(tracked 2건).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
    -d '{"srcSn":130,"points":[[100,100]]}' \
    localhost:18081/api/v1/portal/frames/130/sam2-segment | jq '{n:(.data.polygon|length), score:.data.score}'
  # → {"n":1139,"score":0.9027518630027771}
  docker logs klid-ai-server --since 5m | grep infer/sam2
  ```
- **영향**: ①**계약 위반** — 사업 산출물(요구사항정의서·ADR-013)에 없는 기능이 외부 채널에 열려 있어 범위·감리 정합이 깨진다. ②**자원 축** — 외부 사용자가 내부 추론 서버(CPU/GPU)를 직접 구동시킨다(격리는 되어 있으나 소비는 실제로 발생). ③FE 테스트가 노출을 고정하고 있어 "실수로 열린 것"이 아니라 **되돌리려면 테스트까지 함께 뒤집어야 하는 상태**다.
  **완화된 축(07-30 반영)** — PII 유출(CWE-359)은 닫혔다: 전송 픽셀이 비식별본 전용이고(원본 폴백 시 404 실증), 신고 구간은 412 로 ai 호출 0건이며, 결과는 DB 에 저장되지 않아 데이터마트 오염도 없다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 정책을 유지한다면 `PortalSam2Controller`/`PortalSam2Service`/포털 도구바 3버튼을 제거하고 FE 회귀 가드를 "미노출" 단언으로 반전한다. 반대로 이 기능을 유지하기로 한다면 **ADR-013 을 개정해 "포털 SAM2 는 제공(단 persist 없음·비식별본 전용)" 을 명문화**하고 `CLAUDE.md` "오토라벨링(YOLO/SAM2) 미제공" 문장을 "YOLO 파이프라인 오토라벨만 미제공" 으로 정정해야 한다. **둘 중 하나를 사용자가 결정하기 전에는 코드도 문서도 건드리지 않는다** — 현재는 코드와 정본 문서가 서로 다른 말을 하는 상태 자체가 결함이다.

### [F-ISSUE-04] UNCERTAINTIES #12 — 데이터마트 저장/조회·이미지 서빙에 rate limit 이 없다(부재 확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 외부 채널 엔드포인트는 요청량 상한이 있어야 한다(포털 SAM2 30/1m, 포털 업로드 60/1m 과 동일 수준의 방어).
- **현재 동작(이슈 내용)**: `PortalLabelService` 가 담당하는 5개 경로 어디에도 `RateLimiter`·`Bulkhead` 획득 코드가 없다(`PortalLabelService.java` 전문에 `RateLimiter`/`Bulkhead` import 0건). `application.yml:602-619` 의 `ratelimiter.configs` 에는 `portalSam2`·`portalUpload` 두 개만 있다.
  [실동작] 단일 사용자 연타 실측 — **429 0건**:
  | 경로 | 요청 수 | 응답 |
  |---|---:|---|
  | `GET /v1/portal/datamart/videos` | 60 | 200 ×60 |
  | `GET /v1/portal/user-labels?rawSn=` | 60 | 200 ×60 |
  | `GET /v1/portal/frames/130/image` | 60 | 200 ×60 (매건 파일 I/O) |
  | `GET /v1/portal/datamart/labels?rawSn=` | 60 | 200 ×60 |
  | `GET /v1/portal/frames/130/labels` | 60 | 200 ×60 |
  | `POST /v1/portal/user-labels` | 40 | **201 ×40**(DB 행 40개 생성) |
  대조군으로 같은 사용자의 포털 SAM2 는 31번째 요청부터 429 였다 — **동일 채널 안에서 방어 수준이 갈린다.**
- **재현/확인 경로**:
  ```bash
  for i in $(seq 1 60); do curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $PT" \
    localhost:18081/api/v1/portal/frames/130/image; done | sort | uniq -c   # → 60 200
  ```
- **영향**: **CWE-770(Unrestricted Resource Consumption)** / OWASP API4:2023. 이미지 서빙은 매 요청 NAS 파일 I/O 를, 저장은 무제한 DB INSERT 를 유발한다. 외부 채널이라 인증된 계정 1개만 있으면 트래픽·스토리지를 밀어 넣을 수 있다. `datamart/labels` 는 F-ISSUE-01 과 결합하면 **전 영상 라벨 대량 수집**의 실행 수단이 된다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `portalSam2`/`portalUpload` 와 동일 패턴(per-user `RateLimiterRegistry` lazy 생성 + `timeout-duration: 0`)으로 `portalDatamart` config 를 추가하고, 읽기(목록·라벨·이미지)와 쓰기(user-labels 저장)에 서로 다른 상한을 둔다. 개별 서비스 메서드에 흩뿌리지 말고 `/v1/portal/**` 공통 인터셉터/필터 한 곳에서 적용해 **또 다른 형제 경로 누락(F-ISSUE-01 과 동형)** 을 예방한다.

### [F-ISSUE-05] UNCERTAINTIES #11 — "본인 데이터 기간 내 다운로드" 의 기간 제한 로직이 없다(부재 확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 포털 절 — "본인 데이터 **기간 내** 다운로드". 즉 다운로드 대상에 유효기간이 있고, 기간이 지난 데이터는 거부돼야 한다.
- **현재 동작(이슈 내용)**: 포털 패키지 전체(`backend/src/main/java/kr/co/cudo/authoring/portal/**`)에서 기간 판정 로직은 **TUS 업로드 세션 TTL 하나뿐**이다 — `LsPortalTusUpload.java:72,98,102-103`(`expiresAt = now.plusHours(TTL_HOURS)`, `isExpired`), `LsPortalTusUploadRepository.java:54-55,68`, `PortalUploadSweepJob`. 이는 **미완료 업로드 세션 정리**용이며 다운로드 권한과 무관하다.
  또한 F-1~F-4 범위(데이터마트 라벨링)에는 **다운로드 엔드포인트가 아예 없다** — `PortalLabelController` 의 6개 매핑 중 다운로드/export 성격은 0개다. 다운로드는 포털 **업로드 자산** 쪽(`PortalUploadLabelController.java:82` `GET /{uldSn}/export`, `:92` `GET /{uldSn}/file`, F-7 범위)에만 존재하고, 그 두 경로에도 기간 조건은 없다(소유자 검증만).
- **재현/확인 경로**:
  ```bash
  grep -rniE "retention|expire|validUntil|기간|다운로드 기간" backend/src/main/java/kr/co/cudo/authoring/portal
  # → LsPortalTusUpload / LsPortalTusUploadRepository / PortalUploadSweepJob (업로드 세션 TTL) 만 매칭
  grep -rn "Mapping" backend/src/main/java/kr/co/cudo/authoring/portal/controller/PortalLabelController.java
  # → 다운로드/export 매핑 0건
  ```
- **영향**: 요구 문구("기간 내")가 구현으로 실현되지 않았다. 데이터 보관·파기 정책이 코드로 강제되지 않으므로 개인정보 보유기간 관점의 감리 지적 소지가 있다. 보안 즉시 위험은 낮다(소유자 검증은 있음).
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 먼저 **"기간" 의 기준(다운로드 가능 기간인지 데이터 보관 기간인지, 기산점이 저장일인지 승인일인지, 일수)을 사용자·발주처와 확정**해야 한다. 확정 후 `LS_SYSTEM_CONFIG` 설정키로 일수를 두고 다운로드 진입부에서 `regDt + N일 < now` 이면 410/403 으로 거부하는 방식이 자연스럽다. 데이터마트 작업 데이터 다운로드 경로가 필요한지 여부도 함께 확정 대상이다.

### [F-ISSUE-06] TC-PORTAL-040 부가 발견 — 사용자 라벨 저장 시 `sourceSrcSn` 이 `sourceRawSn` 소속인지 검증하지 않는다

- **심각도**: LOW
- **기대 동작(기대효과)**: `LS_PORTAL_USER_LABEL(SRC_RAW_SN, SRC_DATA_SRC_SN)` 은 같은 영상의 프레임을 가리켜야 한다(참조 무결성).
- **현재 동작(이슈 내용)**: APPROVED 게이트는 `sourceRawSn` 만 보고, `sourceSrcSn` 은 존재 여부조차 확인하지 않은 채 그대로 적재한다.

  `PortalLabelService.java:203-220`
  ```java
  if (!isExposedToDatamart(req.sourceRawSn())) { ... throw FORBIDDEN; }   // rawSn 만 검사
  ...
  userLabelRepository.save(LsPortalUserLabel.create(
          actor.sub(), req.sourceRawSn(), req.sourceSrcSn(), ...));       // srcSn 소속 미검증
  ```
  [실동작] `{"sourceRawSn":152(APPROVED), "sourceSrcSn":80(rawSn=133 소속·미승인·신고중)}` → **201 저장 성공**. DB 스키마상 FK 는 `SRC_RAW_SN → LS_DATA_RAW` 하나뿐이고 `SRC_DATA_SRC_SN` 에는 FK 가 없다(`\d ls_portal_user_label` 실측).
- **재현/확인 경로**:
  ```bash
  curl -s -X POST -H "Authorization: Bearer $PT" -H 'Content-Type: application/json' \
    -d '{"sourceRawSn":152,"sourceSrcSn":80,"lblTypeCd":"BBOX","label":"crossref","points":"[[1,1],[2,2]]"}' \
    localhost:18081/api/v1/portal/user-labels        # → 201
  ```
- **영향**: 읽기 누수는 없다 — `loadFrameLabels(80)` 은 실제 소속 영상(133)으로 APPROVED 게이트를 걸어 403 이고, `listMyLabels(152)` 는 본인 데이터만 돌려준다. 남는 것은 **참조 무결성 오염**(존재하지 않는 srcSn 도 저장 가능)과, 향후 이 테이블을 srcSn 기준으로 조인·집계·export 하는 기능이 생길 때의 잠재 오류다. `LS_DATA_SRC` 참조 FK 가 DB 전체 0건인 기존 지적(`B-ISSUE-101`)과 같은 계열이다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 저장 진입부에서 `srcRepository.findById(sourceSrcSn)` 로 프레임을 읽어 `frame.getRawSn().equals(req.sourceRawSn())` 를 확인하고 불일치·부재면 400/404 로 거부한다(APPROVED 판정도 프레임에서 역산한 rawSn 으로 하면 축이 하나로 준다). DB 측에는 `SRC_DATA_SRC_SN → LS_DATA_SRC` FK 추가를 검토한다.

### [F-ISSUE-07] TC-PORTAL-048 부가 발견 — `GET·POST /v1/portal/user-labels` 는 신고 구간에서도 동작한다

- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간(`DE_IDNTF_YN='F'`)에서는 그 영상에 얽힌 좌표 열람·작업을 멈추는 것이 일관적이다(내부 경로는 조회 412 + 저장 409 작업락).
- **현재 동작(이슈 내용)**: `listMyLabels`(`PortalLabelService.java:226-235`)에는 APPROVED 게이트도 신고 게이트도 없고, `saveUserLabel`(`:198-224`)에는 APPROVED 게이트만 있고 신고 게이트가 없다.
  [실동작] rawSn=152 를 `'F'` 로 둔 상태에서 `GET /v1/portal/user-labels?rawSn=152` → **200**, `POST /v1/portal/user-labels` → **201**(같은 순간 `frames/130/labels`·`/image` 는 412).
- **재현/확인 경로**: `ls_data_raw.de_ident_yn` 을 `'F'` 로 둔 뒤 위 두 요청 수행(본 검증에서는 152 로 수행 후 `'Y'` 원복).
- **영향**: 낮다 — 반환·저장되는 데이터가 **그 사용자가 직접 그린 좌표**이지 내부 파이프라인 라벨이 아니다(datamart 원본 병합은 게이트가 걸린 `loadFrameLabels` 에서만 일어난다). 다만 신고 구간에 새 작업을 계속 쌓게 되어, 재비식별 후 좌표가 어긋난 작업본이 남는다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: F-ISSUE-01 의 공통 가드로 수렴시킬 때 함께 판단한다 — 조회는 "본인 데이터라 허용" 으로 명시적으로 남기고 저장만 412 로 막는 선택도 가능하므로, **정책을 먼저 확정**하고 그 결정을 `CLAUDE.md` 차단 범위 목록에 명문화한 뒤 배선한다.

### [F-ISSUE-08] TC-PORTAL-068 부가 발견 — track wall-clock 예산이 루프 진입 시점에만 판정돼 최대 초과폭이 예산의 2배를 넘고, 전용 테스트가 없다

- **심각도**: LOW
- **기대 동작(기대효과)**: `TRACK_WALL_CLOCK_BUDGET`(60s)을 넘기면 조기 종료해 Tomcat 스레드 장기 점유를 막는다.
- **현재 동작(이슈 내용)**: 판정이 **프레임 루프의 맨 앞**에서만 이뤄지고, 한 프레임의 블로킹 상한은 별도로 70s 다.

  `PortalSam2Service.java:155-173,196-200`
  ```java
  long deadlineNanos = System.nanoTime() + TRACK_WALL_CLOCK_BUDGET.toNanos();   // 60s
  for (Long nextSrcSn : req.nextSrcSns()) {
      if (System.nanoTime() > deadlineNanos) { ... throw TOO_MANY_REQUESTS; }   // 진입 시점만
      ...
      var aiRes = callWithBulkhead(aiServerClient.track(aiReq), "track", nextSrcSn);  // block 70s
  ```
  59.9s 경과 시점에 시작한 프레임이 70s 를 쓰면 총 **약 130s** 까지 스레드가 점유된다(예산 60s 의 2배 초과).
  또 `PortalSam2ServiceTest` 에 wall-clock 예산 전용 테스트가 없다(`grep -n "wall\|budget\|TRACK_WALL" …PortalSam2ServiceTest.java` → 0건). 커버된 429 는 bulkhead·rate limit 두 종류뿐이다.
- **재현/확인 경로**: 정적 확인(위 grep). 60s 초과 추론 부하를 인위적으로 만들 수 없어 실동작 미재현.
- **영향**: 낮다 — 상위에 bulkhead 4 + per-user 30/1m 이 있어 동시 점유 스레드 수 자체가 제한된다. 다만 "예산 60s" 라는 이름과 실효 상한(≈130s)이 달라 운영 시 오해를 부른다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 남은 예산을 per-frame block timeout 에 반영하거나(`block(min(70s, 남은예산))`), 예산 초과 판정을 프레임 완료 직후에도 수행한다. 어느 쪽이든 예산 초과 시 429 를 반환하는 단위 테스트를 `PortalSam2ServiceTest` 에 추가해 회귀를 고정한다.

---

## 검증 중 변경한 데이터 — 전부 원복 확인

| 대상 | 변경 | 원복 확인 |
|---|---|---|
| `ls_data_raw.de_ident_yn` rawSn=152 | `Y→F→Y` | ✅ `152 de_ident=Y` |
| `ls_data_raw.de_ident_yn` rawSn=146 | `Y→F→Y` | ✅ `146 de_ident=Y` |
| `ls_data_src.de_idntf_src_file_path_nm` srcSn=130 | 6회 임시 치환 | ✅ `/app/storage/deidentified/frames/deid/152/frame-0.jpg` 복원, 200 서빙 재확인 |
| 컨테이너 파일 `…/deid/152/link.jpg`(심링크)·`bad.jpg` | 생성 | ✅ 삭제, 디렉터리에 `frame-0.jpg` 만 잔존 |
| `ls_portal_user_label` | 테스트 행 49건 생성 | ✅ 전량 삭제, **count=0**(검증 시작 시점과 동일) |
| rawSn 126 · 133 | **변경 없음** — 133 은 신고 OPEN(`F`) 유지, resolve 하지 않음 | ✅ `126 de_ident=Y` / `133 de_ident=F` |
| backend 컨테이너 | **재기동하지 않음** | ✅ `Up About an hour` 유지 |

---

# F 클러스터 part2 (F-5~F-7) 2차 검증 결과

> 대상: `docs/test-cases/F-portal.md` 의 F-5(18) · F-6(13) · F-7(19) = **50건**
> 환경: 로컬 도커 스택 `localhost:18081`(backend 이미지 = HEAD `ca3c712b` 재빌드본, `SPRING_PROFILES_ACTIVE=local`) · PG `klid-postgres/klid_system`(스키마 `public`)
> 실행일 2026-07-31 · 폐기(`~~취소선~~`) 행 **0건**(F-5~F-7 구간에는 폐기 케이스가 없다)
> 대전제 준수: 포털 업로드 경로는 외부 연동이 없는 자기완결 경로(ADR-013 분리)라 목서버 경유 대상 자체가 없음 — 대신 **실제 파일을 실제로 업로드/서빙/삭제**해 DB 행 + 디스크 파일 양쪽으로 확증. self-fill 0건.

## 집계

| 구분 | 건수 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|------|---:|---:|---:|---:|---:|---:|---:|
| F-5 포털 이미지 업로드 | 18 | 17 | 1 | 0 | 0 | 0 | 0 |
| F-6 자산 조회/서빙/삭제 | 13 | 13 | 0 | 0 | 0 | 0 | 0 |
| F-7 업로드 라벨 CRUD | 19 | 19 | 0 | 0 | 0 | 0 | 0 |
| **합계** | **50** | **49** | **1** | 0 | 0 | 0 | 0 |

- 이슈 4건 — HIGH 1(A-ISSUE-21 중복) / MEDIUM 1 / LOW 2
- **실동작 검증 비중**: 50건 중 48건을 실제 HTTP 왕복으로 확증. 정적 전용은 TC-PORTALUP-007·008(prd 프로파일 한정 동작, local 스택에서 재현 불가) 2건.
- 근거 드리프트 **43건**(카탈로그 `file:line` 이 현재 소스와 어긋남). 특히 F-7 표 하단 각주 *"PortalUploadLabelService/Controller 는 2026-07-25 이후 무변경 — 라인 재확인만 하고 값은 유지"* 는 **사실과 다르다**(19건 중 18건 드리프트).

### 검증 중 생성/변조한 데이터 (타 에이전트 영향 없음)

- `ls_portal_uld` 120행 / `ls_portal_uld_frme` 120행 신규 생성(포털 사용자 `3001`·`3002`·`3009`). 전부 `LS_PORTAL_*` 전용 테이블이며 **내부 파이프라인(`ls_data_raw`/`ls_data_src`)·데이터마트 뷰와 완전 분리**돼 있어 타 클러스터 검증에 간섭하지 않음.
- **`rawSn` 126·133 무손상 확인** — 검증 종료 시점 `126=COMPLETED/de_ident_yn=Y`, `133=COMPLETED/de_ident_yn=F` 그대로. 포털 업로드 경로는 이 테이블을 건드리지 않는다.
- 검증용으로 만든 root 소유 `lockdir`·심링크(`evil-link.jpeg`)는 **전량 제거 완료**(스토리지 루트에 `images/` 만 잔존).

---

## ★파일 검증 우회 실측

`POST /v1/portal/uploads/images` (PORTAL_USER `3001`). "저장여부" = 요청 후 `/app/storage/raw/portal/images` 파일 수 증가 + `ls_portal_uld` 행 증가 여부.

| # | 공격입력 | 기대 | 실측 | 저장여부 | 차단 지점 |
|--:|---------|------|------|:---:|------|
| 1 | `fake.jpg` — 내용은 `#!/bin/sh` 셸 스크립트 | 400 | **400** `지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용).` | 미저장 | 매직바이트 `detect()` |
| 2 | `elf.jpg` — `\x7fELF` 헤더(ELF 바이너리) | 400 | **400** 동일 | 미저장 | 매직바이트 |
| 3 | `mismatch.png` — 실제 JPEG 바이트에 `.png` 확장자 | 400 | **400** `확장자와 실제 이미지 형식이 일치하지 않습니다.` | 미저장 | `matchesExtension` |
| 4 | `trunc.jpg` — JPEG SOI 만 있고 EOI(`FF D9`) 없음 | 400 | **400** `손상되었거나 완전하지 않은 이미지 파일입니다.` | 미저장 | `endsWithJpegEoi` (tail 판독) |
| 5 | `anim.gif` — GIF89a | 400 | **400** `허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]` | 미저장 | 확장자 allowlist |
| 6 | `x.svg` — `<svg onload=alert(1)>` (저장형 XSS 벡터) | 400 | **400** 동일 | 미저장 | 확장자 allowlist |
| 7 | `empty.jpg` — 0바이트 | 400 | **400** `빈 파일입니다.` | 미저장 | `file.isEmpty()` |
| 8 | MIME 헤더 위조 — 스크립트 본문에 `Content-Type: image/jpeg` 파트 헤더 부착 | 400 | **400** (요청 MIME 무시, 매직바이트로 판정) | 미저장 | 매직바이트 |
| 9 | 이중 확장자 `a.php.jpg` (본문은 정상 JPEG) | 201(정상 취급) | **201**, 디스크 저장명 `88c7cf67-….jpeg` | 저장(UUID) | 해당없음 — **최종 확장자만 해석 + UUID 강제라 무해** |
| 10 | 대문자 확장자 `UPPER.JPG` | 201 | **201**, 저장명 `db751adc-….jpeg` | 저장(UUID) | `toLowerCase(Locale.ROOT)` 정규화 |
| 11 | 경로순회 파일명 `../../../../etc/passwd.jpg` | UUID 저장 | **201**, `file_path_nm=/app/storage/raw/portal/images/6d04121c-….jpeg` | 저장(UUID) | 저장명 UUID 강제 — 원본명은 표시용 컬럼에만 |
| 12 | 널바이트 파일명 `evil.jpg\0.php` | UUID 저장 | **201**, `orgnl_file_nm='evil.jpg'`(널 이후 절단), 저장명 UUID | 저장(UUID) | 동상 |
| 13 | 유니코드 우회 `x.jpg%E2%80%8B`(zero-width space 부착) | 400 | **400** `허용되지 않는 확장자입니다` | 미저장 | `^[a-z0-9]{1,8}$` 확장자 정규식 |
| 14 | 21MB JPEG (20MB 상한 +1MB) | 400 | **400** `이미지 크기가 허용 한도를 초과했습니다.` | 미저장 | `maxImageSizeBytes` |
| 15 | 22MB JPEG (카탈로그 TC-007 입력) | local: 파싱 통과 후 서비스 400 | **400** (413 아님 — local multipart 한도 500MB) | 미저장 | `maxImageSizeBytes` |
| 16 | 정확히 20,971,520B (경계값) | 201 | **201** | 저장 | — |
| 17 | 51장 | 400 | **400** `요청당 최대 50 개까지 업로드할 수 있습니다.` | 미저장 | `maxImagesPerRequest` |
| 18 | 50장 (경계값) | 201 | **201**, 50건 저장 | 저장 | — |
| 19 | `files` 파트 자체 미첨부 | 400 | **500** `INTERNAL_ERROR` + `MissingServletRequestPartException` **전체 스택트레이스** | 미저장 | ⚠ **핸들러 부재 → `Exception.class` 종착** (F-ISSUE-21) |
| 20 | XSS 파일명 `<script>alert(1)</script>.jpg` | 201 + 이스케이프 | **201**, JSON 문자열로 그대로 직렬화(HTML 렌더 없음, FE 이스케이프 책임 — 코드 주석과 일치) | 저장(UUID) | 해당없음 |
| 21 | 이모지 200개 파일명(surrogate pair, 404 UTF-16 unit) | 255자 절단 | **201**, DB 저장값 128자 — **말미 surrogate 가 반쪽만 남아 `?`(U+FFFD 치환) 로 저장** | 저장 | ⚠ F-ISSUE-22 (LOW) |

**결론**: 매직바이트 · 확장자 allowlist · 확장자↔시그니처 정합 · JPEG EOI · 크기 · 개수 · 저장명 UUID 강제까지 **7중 방어가 모두 실효**하며 우회 성공 0건. 유일한 결함은 `files` 파트 누락 시 응답코드/로그(F-ISSUE-21).

---

## ★IDOR 실측

포털 사용자 `3001`(자산 uldSn 1~118 / frmeSn 1~118) 이 **포털 사용자 `3002` 의 자산**(uldSn 119·120, frmeSn 119·120)에 접근 시도. 부재 리소스(`999999`)와 **응답이 동일한지**(자원 열거 차단)까지 확인.

| # | 경로 | 타인 자산 | 기대 | 실측 |
|--:|------|------|------|------|
| 1 | `GET /v1/portal/uploads/{uldSn}` | 119 | 403 | **403** `본인 자산이 아니거나 존재하지 않습니다.` |
| 2 | `GET /v1/portal/uploads/999999` (부재) | — | 403(부재와 동일) | **403** — 메시지·코드 완전 동일(열거 불가) |
| 3 | `GET /v1/portal/uploads/{uldSn}/frames` | 119 | 403 | **403** |
| 4 | `GET /v1/portal/uploads/frames/{uldFrmeSn}/image` | 119 | 403 | **403** |
| 5 | `DELETE /v1/portal/uploads/{uldSn}` | 119 | 403 | **403** (행·파일 무손상 확인) |
| 6 | `PUT /v1/portal/uploads/frames/{uldFrmeSn}/labels` | 119 | 403 | **403** |
| 7 | `GET /v1/portal/uploads/frames/{uldFrmeSn}/labels` | 119 | 403 | **403** |
| 8 | `GET /v1/portal/uploads/frames/999999/labels` (부재) | — | 403 | **403** — 동일 응답 |
| 9 | `GET /v1/portal/uploads/{uldSn}/export` | 119 | 403 | **403** |
| 10 | `GET /v1/portal/uploads/{uldSn}/file` | 119 | 403 | **403** |

**사용자 식별 출처**: 전 엔드포인트가 `@AuthenticationPrincipal TokenClaims actor` → `actor.sub()` 로 **토큰에서만** 소유자를 얻는다(`PortalUploadController:138-143`, `PortalUploadLabelController:101-106`). **요청 파라미터·헤더로 사용자를 지정하는 경로 0건**(grep 확증). 서비스는 전부 소유자 스코프 리포지토리(`findByUldSnAndPortalUserNo` / `findByUldFrmeSnAndOwner` / `deleteAllByUldFrmeSnAndPortalUserNo`)만 호출한다.

### 채널 격리 실측 (내부 토큰 → 포털 업로드 경로)

| 토큰 | 경로 | 실측 |
|------|------|------|
| WORKER(INTERNAL, sub=2001) | `POST /images` · `GET /` · `GET /frames/{n}/image` · `PUT …/labels` · `GET …/export` · `GET …/file` · `DELETE /{n}` | **전부 403** `권한이 없습니다.` |
| REVIEWER(INTERNAL, sub=1001) | `GET /` · `GET …/export` | **전부 403** |
| 무토큰 | `GET /` | **401** `인증이 필요합니다.` |

---

## F-5 결과표 — 포털 이미지 업로드 (18건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-001 | 정상 다중 이미지 업로드 → 201, UUID·READY | PASS | `[실동작]` jpg+png 2장 → **201**, `uldSttsCd=READY`·`frmeCnt=1`·`frmeSn` 반환. DB `ls_portal_uld` 2행 + `ls_portal_uld_frme` 2행, 디스크 `a2883fa2-….jpeg`/`dcba3872-….png` 실재(133B/73B). `[정적]` `PortalUploadService.java:83-152` `[테스트]` `PortalUploadServiceTest#이미지_다중_업로드_성공시_READY상태와_파일기록`, `PortalUploadControllerTest#이미지_다중_업로드_성공시_201과_READY_상태` | `markReady(null,null,1)` 로 업로드 즉시 READY(추출 단계 없음) |
| TC-PORTALUP-002 | 이미지 수 상한(50) 초과 → 400 | PASS | `[실동작]` 51장 → **400** `요청당 최대 50 개까지 업로드할 수 있습니다.` `[정적]` `:90-93` `[테스트]` `PortalUploadServiceTest#요청당_개수_초과시_저장_0건` | |
| TC-PORTALUP-003 | 정확히 50장 경계 → 성공 | PASS | `[실동작]` 50장 → **201**, 응답 배열 50건 + DB 50행 | |
| TC-PORTALUP-004 | 빈 목록 → 400 | FAIL | `[실동작]` `files` 파트 미첨부 → **500** `INTERNAL_ERROR`. 로그: `MissingServletRequestPartException: Required part 'files' is not present.` + 전체 스택 `[정적]` 서비스 분기 `:87-89` 는 존재하나 `@RequestParam("files")`(required) 가 선행 차단해 **HTTP 로는 도달 불가(dead branch)** | **F-ISSUE-21** — A-ISSUE-21 과 동일 원인·동일 재현 경로(집계 시 병합 권장). 참고로 *빈 파트*(`filename=` 공백) 는 400 `빈 파일입니다.` 로 정상 |
| TC-PORTALUP-005 | 개당 20MB 초과 → 400 | PASS | `[실동작]` 21MB·22MB 두 건 모두 **400** `이미지 크기가 허용 한도를 초과했습니다.` `[정적]` `:300-303`, `PortalUploadProperties.java:40`(`@DefaultValue("20971520")`), `application.yml:438` | 프로파일 무관 서비스 상수 확인 |
| TC-PORTALUP-006 | 20MB 경계값 → 성공 | PASS | `[실동작]` 정확히 20,971,520B JPEG → **201**, `fileSz=20971520` | |
| TC-PORTALUP-007 | multipart max-file-size 초과 → 413(prd 한정) | PASS | `[실동작]` local 22MB → 파싱 통과 후 **서비스 400**(413 아님) — 카탈로그 정정 서술과 일치 `[정적]` `application-prd.yml:36-39` `21MB/1100MB`, `application.yml:14-30` 공통 `500MB/1200MB` `[테스트]` `ConfigProfileDriftGuardTest#prdMultipartLimitsStayTight`(`:43-58`), `#nonProdMultipartLimitsComeFromCommon`(`:67-80`) | prd 413 자체는 **실동작 미실증** — 실행 스택이 `SPRING_PROFILES_ACTIVE=local`. 프로파일 전환은 backend 재기동을 요구해 금지 지시에 따라 미수행 |
| TC-PORTALUP-008 | multipart max-request-size 초과 → 거부(prd 한정) | PASS | `[정적]` `application-prd.yml:39` `1100MB` / 공통 `1200MB`, 산식(50×21MB=1050MB+헤드룸) 주석 일치 `[테스트]` 동상 | **실동작 미수행** — 1.2GB 전송은 공유 스택 임시파일 스풀에 부하를 주어 동시 검증 중인 타 에이전트를 위협(A-part3 TC-EXC-016 과 동일 판단) |
| TC-PORTALUP-009 | 확장자 allowlist 밖(gif/svg) → 400 | PASS | `[실동작]` `.gif`·`.svg` 둘 다 **400** `허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]` `[정적]` `:304-309` `[테스트]` `ImageMagicByteValidatorTest#SVG_XML_시그니처는_거부`,`#GIF_BMP_WEBP_HTML은_거부` | 확장자에서 1차, 매직바이트에서 2차 — 이중 차단 |
| TC-PORTALUP-010 | 매직바이트 미탐지 → 400 | PASS | `[실동작]` 셸 스크립트/ELF 본문 + `.jpg` → **400** `지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용).` `[정적]` `:310-315` | |
| TC-PORTALUP-011 | 확장자↔시그니처 불일치 → 400 | PASS | `[실동작]` JPEG 본문 + `.png` → **400** `확장자와 실제 이미지 형식이 일치하지 않습니다.` 요청 파트 MIME 위조(`type=image/jpeg`)도 무시됨 `[정적]` `:316-320` + `ImageMagicByteValidator#matchesExtension` | |
| TC-PORTALUP-012 | truncated JPEG(EOI 없음) → 400 | PASS | `[실동작]` SOI 만 있는 20B JPEG → **400** `손상되었거나 완전하지 않은 이미지 파일입니다.` `[정적]` `:321-327`(tail 2B 판독) `[테스트]` `#헤더만_유효한_truncated_파일_400`(Service·Controller 양쪽) | PNG 는 `detect()` 가 IHDR 청크까지 검사(별도 tail 불필요) |
| TC-PORTALUP-013 | all-or-nothing: 1장 실패 시 전체 미저장 | PASS | `[실동작]` ①정상 49 + 위조 1(**마지막**) → 400, 디스크 파일수 57→**57**(불변) ②위조 1(**첫번째**) + 정상 49 → 400, 57→**57**. 응답 `data` 에 실패 인덱스·사유만 노출(`[50] mismatch.png: …`) `[정적]` `:95-109`(디스크 쓰기 **이전** 전량 사전검증) | 위조 위치와 무관하게 0건 저장 확인 |
| TC-PORTALUP-014 | write 후 DB INSERT 실패 시 보상 삭제 | PASS | `[실동작]` `setval('ls_portal_uld_uld_sn_seq',1,false)` 로 PK 충돌 유도 → **500** `이미지 저장에 실패했습니다.`, 디스크 파일수 120→**120**(보상 삭제 성공), 기존 행 무손상. 로그 `upload failed userNo=3001 causeType=DataIntegrityViolationException` `[정적]` `:138-147` `[테스트]` `#DB_저장_실패시_기록된_파일이_남지_않음` | 시퀀스는 검증 후 원값 복원 |
| TC-PORTALUP-015 | 디스크 write IOException 시 롤백 | PASS | `[실동작]` `chmod 555 images/` → **500** `이미지 저장에 실패했습니다.`, `ls_portal_uld` 118→**118**(행 0 증가). 로그 `write failed causeType=AccessDeniedException` `[정적]` `writeToDisk :365-372` + `rollbackFiles :374-385` `[테스트]` `#다건_배치중_후행_write실패시_선행파일도_롤백` | 검증 후 권한 755 복원 |
| TC-PORTALUP-016 | 저장 파일명 UUID 강제(path traversal 없음) | PASS | `[실동작]` 파일명 `../../../../etc/passwd.jpg` · `evil.jpg\0.php` 투입 → 저장 경로 전부 `/app/storage/raw/portal/images/{UUID}.{jpeg\|png}`, 스토리지 루트 하위에 `images/` 외 디렉터리 0개 `[정적]` `:119-120`(`UUID.randomUUID()+"."+format` → `resolveSafe`) `[테스트]` `#경로조작_파일명_업로드시_저장경로가_베이스_밖으로_나가지_않음` | 확장자는 **매직바이트 확정 포맷**에서 파생(원본 확장자 미사용) |
| TC-PORTALUP-017 | 원본명 255자 초과 truncate | PASS | `[실동작]` 304자 파일명 → `orgnlFileNm` **255자**로 절단 저장(`varchar(255)` 초과 없음) `[정적]` `truncate :454-459` | ⚠ **F-ISSUE-22(LOW)** — surrogate pair 를 UTF-16 unit 기준으로 자르면 반쪽 surrogate 가 남아 `?` 로 치환됨(이모지 200개 실측: DB 128자, 말미 `ascii=63`) |
| TC-PORTALUP-018 | 업로드 per-user rate limit 초과 → 429 | PASS | `[실동작]` 신규 사용자 `3009` 로 65회 연속 업로드 → **1~60회 201 / 61~65회 429**(정확히 60에서 전환) `[정적]` `PortalUploadController.java:157-165`, `application.yml:616-619`(`limit-for-period:60`,`refresh:1m`,`timeout:0`) `[테스트]` `PortalUploadControllerRateLimitTest#업로드_요청량_초과시_429` | per-user 이름(`portalUpload-{userNo}`) 격리 — `3001` 의 잔여 예산이 `3009` 소진에 영향받지 않음도 함께 확인 |

---

## F-6 결과표 — 포털 자산 조회/서빙/삭제 (13건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-020 | 본인 자산 목록 페이징 | PASS | `[실동작]` `3001` 조회 → `totalElements=58`, `content` 전건 본인 자산. `3002` 자산(uldSn 119·120)·`3009` 자산(60건) **미노출** `[정적]` `:157-172`(`findAllByPortalUserNo`) | `@PageableDefault(size=20)` |
| TC-PORTALUP-021 | type 필터 IMAGE/VIDEO | PASS | `[실동작]` `type=IMAGE`→58건 전부 IMAGE / `type=VIDEO`→1건 전부 VIDEO(검증용 VIDEO 행 임시 삽입 후 삭제) / 무필터→59건. `type=image`(소문자)도 정상 동작(`toUpperCase` 정규화) `[정적]` `:164-171` `[테스트]` `#목록조회_type필터_IMAGE만_반환` | 필터가 실제로 **분리**함을 양방향으로 확인(빈 결과만으로 판정하지 않음) |
| TC-PORTALUP-022 | 미지원 type → 400 | PASS | `[실동작]` `?type=FOO` → **400** `지원하지 않는 type 입니다. 허용: IMAGE, VIDEO` `[정적]` `:165-169`(조용한 빈 결과 대신 명시 거부) `[테스트]` `#미지원_type_필터시_400` | |
| TC-PORTALUP-023 | 페이지 크기 하드캡(100) | PASS | `[실동작]` 목록 `?size=500` → 응답 `size=100`, `totalPages=1`. 프레임 목록 `?size=500` → `size=100` `[정적]` `PortalUploadController.java:146-151` `[테스트]` `#size_100초과시_100으로_캡` | 목록·프레임 **두 경로 모두** 확인 |
| TC-PORTALUP-024 | 타 사용자 자산 상세 → 403 | PASS | `[실동작]` `3001`→uldSn 119 **403**. 부재 `999999` 도 **동일 403·동일 메시지**(자원 열거 차단) `[정적]` `:175-182` `[테스트]` `#타사용자_자산_상세_조회시_403` | 404/403 분기가 없어 존재 여부 오라클 부재 |
| TC-PORTALUP-025 | 타 사용자 프레임 목록 → 403 | PASS | `[실동작]` **403** `[정적]` `:185-192`(소유권 사전검증 + 소유자 스코프 조인 쿼리 이중) `[테스트]` `#타사용자_프레임목록_조회시_403` | |
| TC-PORTALUP-026 | 타 사용자 프레임 이미지 → 403(IDOR) | PASS | `[실동작]` frmeSn 119 **403** / 본인 frmeSn 1 **200**(실제 JPEG 바이트 수신) `[정적]` `:205-211`(`findByUldFrmeSnAndOwner` + 상위 ULD 소유권 재확인) `[테스트]` `#타사용자_프레임_이미지_조회시_403` | |
| TC-PORTALUP-027 | 프레임 이미지 서빙: DB MIME+nosniff, 신고 게이트 대상 아님 | PASS | `[실동작]` jpg 프레임→`Content-Type: image/jpeg`, png 프레임→`image/png`, 양쪽 `X-Content-Type-Options: nosniff` + `Content-Disposition: inline; filename="frame_{n}"`. DB MIME 을 `text/html` 로 변조하면 **`application/octet-stream`** 으로 fail-closed `[정적]` `:204-236` — `DeidentReportGate`·`DE_IDNTF_YN` **참조 0건**(grep), Javadoc `:198-202` 이 비대상 사유 명시 `[테스트]` `PortalFrameImageCacheControlTest` 가 자기 스코프를 `/v1/portal/frames/{srcSn}/image`(내부 파이프라인)로 한정하고 `:48` 주석으로 `/v1/portal/uploads/frames/**` 제외를 명시 | 응답에 `Cache-Control: no-store` 가 붙지만 이는 **Spring Security 전역 기본 헤더**이며 게이트 통일 규약과 무관(엔드포인트가 스스로 부여하는 코드 없음) |
| TC-PORTALUP-028 | 이미지 서빙 Path Traversal 차단 | PASS | `[실동작]` DB `file_path_nm` 을 ①`/etc/passwd`(절대경로 base 밖) ②`…/images/../../../../etc/passwd`(`..` 조합) 으로 변조 → 둘 다 **403** `허용되지 않은 경로입니다.` ③base 안에 `/etc/passwd` 를 가리키는 **심링크** 배치 → **403** `허용되지 않은 이미지 경로입니다.`(realpath 재검증). PathVariable 에 `abc` → 400, 경로 세그먼트 조작 → 403 `[정적]` `resolveSafe :414-424` + `realWithinBase :245-257` + `FrameImageService.openNoFollow`(NOFOLLOW_LINKS) `[테스트]` `FileServingLinkFollowGuardTest` | lexical 검증 → realpath 봉쇄 → NOFOLLOW open 3단이 모두 살아 있음(CWE-22/59/367) |
| TC-PORTALUP-029 | 자산 삭제: 파일 먼저 삭제 후 DB CASCADE | PASS | `[실동작]` uldSn 3 → **204**. 삭제 전 디스크 실재 확인 → 삭제 후 `No such file`. `ls_portal_uld` 0행 · `ls_portal_uld_frme` 0행(CASCADE) `[정적]` `:265-292`(파일 → DB 순서) `[테스트]` `#삭제시_파일과_DB행이_함께_제거됨`, `#삭제시_파일과_프레임_라벨_행이_함께_제거됨` | |
| TC-PORTALUP-030 | PROCESSING 중 삭제 → 409 | PASS | `[실동작]` 상태를 `PROCESSING` 으로 두고 DELETE → **409** `프레임 추출이 진행 중인 자산은 삭제할 수 없습니다.` `[정적]` `:273-276` `[테스트]` `PortalUploadProcessingDeleteTest#PROCESSING_자산_삭제시_409` | |
| TC-PORTALUP-031 | 파일 삭제 IOException 시 DB 행 보존 5xx | PASS | `[실동작]` root 소유 `chmod 555` 디렉터리에 파일 배치 후 DELETE → **500** `파일 삭제에 실패했습니다. 잠시 후 다시 시도하세요.`, `ls_portal_uld` 행 **1건 잔존**, 파일도 잔존(원본 무유실) `[정적]` `deleteFileOrThrow :400-408` `[테스트]` `#파일삭제_실패시_DB행_보존` | 재시도 가능 상태로 정확히 남음 |
| TC-PORTALUP-032 | 타 사용자 자산 삭제 → 403 | PASS | `[실동작]` `3001`→uldSn 119 **403**, 대상 행·파일 무손상 `[정적]` `:267-269` `[테스트]` `#타사용자_삭제시_403` | |

---

## F-7 결과표 — 포털 업로드 라벨 CRUD (19건)

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-040 | 라벨 전체교체(PUT) 멱등, 빈 배열=전체 삭제 | PASS | `[실동작]` BBOX+POLYGON 2건 저장→GET 2건 반환. 이어서 `[]` PUT → **200** `data:[]`, DB `ls_portal_uld_lbl` **500→0**. 본문 자체를 생략(`Content-Length:0`)해도 200 + 전체 삭제(`@RequestBody(required=false)` + `labels==null → List.of()`) `[정적]` `:93-133` `[테스트]` `PortalUploadLabelServiceTest#라벨_전체교체_저장은_멱등`,`#빈_배열_PUT은_전체_삭제` | ⚠ 카탈로그 '알려진 함정'대로 **요청 본문이 raw 배열**(`[{...}]`)이며 래핑 객체가 아님 — 실측 확인 |
| TC-PORTALUP-041 | 검증 실패 시 DELETE 미실행(기존 유지) | PASS | `[실동작]` 기존 500건 보유 상태에서 `[정상1건, SEGMENT 1건]` PUT → **400**, DB 여전히 **500건** `[정적]` `:99-107`(전량 검증이 락·DELETE 이전) `[테스트]` `#검증_실패시_기존_라벨_유지` | |
| TC-PORTALUP-042 | lblTypeCd allowlist(BBOX/POLYGON)만 | PASS | `[실동작]` `SEGMENT`→**400**, `SKELETON`→**400** `[1] 허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON`. 소문자 `bbox`→200(정규화 후 `BBOX` 저장) `[정적]` `:265-270` fail-closed `[테스트]` `#BBOX_POLYGON_외_타입_400`,`#소문자_타입도_정규화되어_저장` | 포털 미제공(SAM2·KEYPOINT/SKELETON) 타입이 실제로 거부됨 |
| TC-PORTALUP-043 | 라벨 배열 상한 500 초과 → 400 | PASS | `[실동작]` 501건 → **400** `replaceLabels.labels: 프레임당 라벨은 최대 500 개까지 허용됩니다.`(컨트롤러 `@Size` 경유) / 500건 → **200** `[정적]` `PortalUploadLabelController.java:63` + `PortalUploadLabelService.java:100-103` **이중 강제** `[테스트]` `#라벨_500개_초과_400` | |
| TC-PORTALUP-044 | BBOX 좌표 2점 아님 → 400 | PASS | `[실동작]` 3점 → **400** `[1] BBOX 는 정확히 2 점이어야 합니다.` `[정적]` `:281-284` `[테스트]` `#BBOX_점2개_아니면_400` | |
| TC-PORTALUP-045 | POLYGON 3~200점 경계 → 400 | PASS | `[실동작]` 2점→**400** / 201점→**400** `[1] POLYGON 은 3~200 점이어야 합니다.` / 200점→**200**(경계 통과) `[정적]` `:285-290` `[테스트]` `#POLYGON_3점미만_또는_200점초과_400` | |
| TC-PORTALUP-046 | 좌표 NaN/Infinity → 400 | PASS | `[실동작]` `"NaN"` → **400**, `1e999`(JSON→Infinity) → **400** `[1] 좌표는 유한한 숫자여야 합니다.` `[정적]` `:300-303`(`Double.isFinite`) `[테스트]` `#좌표_Infinity면_400` | |
| TC-PORTALUP-047 | label 80자 초과 → 400 | PASS | `[실동작]` 81자→**400** `replaceLabels.labels[0].label: label 은 80자 이하여야 합니다.` / 80자→**200** `[정적]` 컨트롤러 `@Valid`(DTO `@Size`) 선행 + 서비스 `:271-275` 이중 `[테스트]` `#label_80자초과_400` | 컨트롤러 검증이 먼저 걸려 메시지 형식이 서비스와 다름(기능 동일) |
| TC-PORTALUP-048 | READY 외 상태 라벨 PUT → 409 | PASS | `[실동작]` `PROCESSING`·`FAILED`·`UPLOADED` **3상태 모두 409** `라벨링 가능한(READY) 자산이 아닙니다. 현재 상태: {s}` `[정적]` `:116-119` `[테스트]` `#READY_아닌_자산_라벨링_409` | 라벨 **조회**(GET)는 비READY 에서도 200 — 서비스 코드에 조회 가드 없음(설계대로, 읽기는 상태 무관) |
| TC-PORTALUP-049 | 동시 PUT 프레임 락 직렬화 | PASS | `[실동작]` 동일 프레임에 A(BBOX×100)/B(POLYGON×100) **10요청 병렬** → 전부 200, 최종 DB **BBOX 100건 단독**(총 100). 두 요청 집합이 섞인 흔적 0 `[정적]` `:110-111` `findByUldFrmeSnAndOwnerForUpdate`(비관적 락) `[테스트]` `PortalUploadLabelConcurrencyIT#동시_PUT_경합시_최종상태는_단일_요청_집합` | 부분 병합·중복 잔존 없음 |
| TC-PORTALUP-050 | 타 사용자 프레임 라벨 PUT → 403 | PASS | `[실동작]` **403** `[정적]` `:110-111` 소유자 스코프 락 조회 `[테스트]` `#타사용자_프레임_라벨_PUT시_403` | |
| TC-PORTALUP-051 | 라벨 PUT 본문 2MB 초과 → 413(파싱 전 조기) | PASS | `[실동작]` 2,173,152B 본문 → **413** `라벨 본문은 2048KB 를 초과할 수 없습니다.`(`PAYLOAD_TOO_LARGE`). 라벨 개수는 500 이하였으므로 **개수 검증이 아니라 바디 크기 필터**가 잡은 것이 확정 `[정적]` `PortalLabelBodySizeFilter.java:70-75` `[테스트]` `PortalLabelBodySizeFilterTest#본문_상한초과_413` | |
| TC-PORTALUP-052 | 라벨 PUT chunked/Content-Length 부재 → 411 | PASS | `[실동작]` `Transfer-Encoding: chunked` → **411** `Content-Length 헤더가 필요합니다. (chunked 전송은 허용되지 않습니다)` `[정적]` `:64-69` `[테스트]` `#Content_Length_부재_chunked_411` | size-cap 우회 경로 봉쇄 확인 |
| TC-PORTALUP-053 | body size 필터 라벨 PUT 경로만 적용 | PASS | `[실동작]` chunked 를 붙여도 필터가 **개입하지 않음**을 4경로로 확인 — `PUT /v1/portal/uploads/1`→405, `PUT /v1/portal/user-labels`→405, `POST …/labels`(비 PUT)→405, `PUT /v1/frames/1/labels`(내부)→400, `PUT …/frames/1/2/labels`(세그먼트 2개)→404. **411 은 한 건도 없음** `[정적]` `shouldNotFilter :51-57` + 정규식 `^/v1/portal/uploads/frames/[^/]+/labels$` `[테스트]` `#GET_라벨_경로는_필터_미적용`,`#타_경로_PUT은_필터_미적용` | 필터가 dispatcher 앞단이라 411 부재 = 미적용의 직접 증거 |
| TC-PORTALUP-054 | 라벨 목록 조회(소유자 스코프), 타인/부재 403 | PASS | `[실동작]` 타인 frmeSn 119 → **403**, 부재 999999 → **동일 403** `[정적]` `:138-144` `[테스트]` `#타사용자_라벨_GET_403`,`#라벨_조회는_소유자_스코프_프레임_검증후_반환` | |
| TC-PORTALUP-055 | export JSON: 고정명+라벨 N+1 회피 | PASS | `[실동작]` **200** `Content-Disposition: attachment; filename="portal-upload-1-labels.json"` + `Content-Type: application/json` + `nosniff`. 본문에 자산 메타 8필드 + `frames[].labels[]` 중첩 좌표 포함(pretty) `[정적]` `:153-202` — `findAllByUldSnAndPortalUserNo` **업로드 단위 1회 조회** 후 `LinkedHashMap` 그룹핑(프레임별 반복 조회 없음) `[테스트]` `#export_JSON에_자산메타_프레임_라벨_모두_포함`,`#export_JSON_구조_검증_프레임_라벨_좌표_중첩배열`,`#export는_포털_리포지토리만_사용` | 파일명에 사용자 입력 미포함(서버 생성 고정명) |
| TC-PORTALUP-056 | 원본 다운로드 Content-Disposition CRLF 인젝션 차단 | PASS | `[실동작]` `orgnl_file_nm` 을 `evil\r\nX-Injected: 1\r\n\r\n<script>.jpg` 로 변조 후 다운로드 → 응답 헤더에 `X-Injected` **미출현**, `filename*=UTF-8''evilX-Injected%3A%201%3Cscript%3E.jpg`(CR/LF 제거 후 URL 인코딩) + ASCII fallback `filename="download.jpg"`. 한글·따옴표 파일명도 `%ED%95%9C…` 로 안전 인코딩 `[정적]` `attachmentDisposition :336-344` + `sanitizeFileName :346-360`(제어문자·`"`·`\`·`/` 제거) `[테스트]` `#원본_다운로드_Content_Disposition에_개행_포함_파일명_무해화`,`#파일명이_전부_제어문자면_download_확장자_고정명` | |
| TC-PORTALUP-057 | 원본 다운로드: 경로 미확정/부재 → 404 | PASS | `[실동작]` `file_path_nm=NULL` → **404** `원본 파일이 존재하지 않습니다.` / 존재하지 않는 경로 → **404**(동일 메시지) / base 밖 `/etc/passwd` → **403** `허용되지 않은 경로입니다.` `[정적]` `:217-228` + `resolveSafe :397-407` `[테스트]` `#원본_파일경로_없으면_404`,`#원본_파일_부재시_404`,`#원본_다운로드_경로탐색_차단` | 다운로드 **기간 제한은 부재**(F-ISSUE-23 / UNCERTAINTIES #11) |
| TC-PORTALUP-058 | 타 사용자 export/다운로드 → 403 | PASS | `[실동작]` `3001`→uldSn 119 의 `/export`·`/file` **둘 다 403** `[정적]` `:155-157`, `:214-215` `[테스트]` `#타사용자_export_403`,`#타사용자_원본_다운로드_403` | |

---

## 근거 드리프트

카탈로그 `근거(file:line)` 이 현재 소스(HEAD `ca3c712b`)와 어긋난 항목. **판정에는 영향 없음**(대상 코드를 grep 으로 재특정해 검증). 총 **43건**.

### PortalUploadService.java (F-5·F-6, 24건)

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 001 | `:82-151` | `:83-152` | `uploadImages` |
| 002·003 | `:89-92` | `:90-93` | 개수 상한 |
| 004 | `:86-88` | `:87-89` | 빈 목록 분기 |
| 005 | `:274-277` | `:300-303` | `maxImageSizeBytes` 검증 |
| 006 | `:274` | `:300` | 동상 |
| 009 | `:278-283` | `:304-309` | 확장자 allowlist |
| 010 | `:284-289` | `:310-315` | 매직바이트 detect |
| 011 | `:290-294` | `:316-320` | 확장자↔시그니처 |
| 012 | `:295-301` | `:321-327` | JPEG EOI |
| 013 | `:94-108` | `:95-109` | 사전검증 루프 |
| 014 | `:137-146` | `:138-147` | 보상 삭제 catch |
| **015** | `:339-359` | `:365-372`(`writeToDisk`) + `:374-385`(`rollbackFiles`) | ⚠ **대상 메서드 자체가 다름** — 카탈로그 범위는 현재 `readTail`(`:343-363`) |
| 016 | `:117-127` | `:117-128` | UUID 저장명 + `resolveSafe` |
| 017 | `:428-433` | `:454-459` | `truncate` |
| 020 | `:156-171` | `:157-172` | `listUploads` |
| 021 | `:164-170` | `:165-171` | type 필터 분기 |
| 022 | `:163-168` | `:164-169` | 동상 |
| 024 | `:174-181` | `:175-182` | `getUpload` |
| 025 | `:184-191` | `:185-192` | `listFrames` |
| 026 | `:204-210` | `:205-211` | `serveFrameImage` 진입 |
| 027 | `:193-231` | `:194-236` | `serveFrameImage` 전체 |
| **028** | `:213,388-398` | `:214`(호출) + `:414-424`(`resolveSafe`) + `:245-257`(`realWithinBase`) | ⚠ `388-398` 은 현재 `fileNameOf`/`addTarget` 구간 |
| 029 | `:239-266` | `:265-292` | `deleteUpload` |
| 030 | `:245-250` | `:273-276` | PROCESSING 가드 |
| 031 | `:374-382` | `:400-408` | `deleteFileOrThrow` |
| 032 | `:242-243` | `:267-269` | 소유권 검증 |

### PortalUploadLabelService.java (F-7, 15건) — ★각주 반증

> 카탈로그 F-7 표 하단 각주: *"PortalUploadLabelService.java·PortalUploadLabelController.java 모두 2026-07-25 이후 무변경(`git log --since=2026-07-25` 0건) — 라인 재확인만 하고 값은 유지."*
> **실측 결과 15건이 드리프트**한다. "무변경"이 사실이라면 라인이 어긋날 수 없으므로, 각주의 전제(파일 무변경) 또는 원 라인값 자체가 부정확하다. 어느 쪽이든 **각주를 근거로 라인 재확인을 생략하면 안 된다.**

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 040 | `:92-132` | `:93-133` | `replaceLabels` |
| 041 | `:98-106` | `:99-107` | 사전검증 |
| 042 | `:250-255` | `:265-270` | 타입 allowlist |
| 044 | `:266-269` | `:281-284` | BBOX 점수 |
| 045 | `:270-275` | `:285-290` | POLYGON 점수 |
| 046 | `:285-288` | `:300-303` | `isFinite` |
| 047 | `:257-260` | `:271-275` | label 길이 |
| 048 | `:115-118` | `:116-119` | READY 가드 |
| 049 | `:108-109` | `:110-111` | 비관적 락 |
| 050 | `:109-110` | `:110-111` | 동상 |
| 054 | `:137-143` | `:138-144` | `listLabels` |
| 055 | `:152-201` | `:153-202` | `exportLabels` |
| 056 | `:321-345` | `:336-344`(`attachmentDisposition`) + `:346-360`(`sanitizeFileName`) | 헤더 무해화 |
| 057 | `:216-227` | `:217-228` | 경로 부재 404 |
| 058 | `:154-156` | `:155-157` | export 소유권 |

### PortalLabelBodySizeFilter.java (3건)

| TC | 카탈로그 | 실제 | 대상 |
|----|------|------|------|
| 051 | `:73-78` | `:70-75` | 413 분기 |
| 052 | `:66-72` | `:64-69` | 411 분기 |
| 053 | `:54-60` | `:51-57` | `shouldNotFilter` |

### 라인 정확 일치 (드리프트 아님)

`PortalUploadController.java:145-151`(TC-023 `capped`) · `PortalUploadController.java:157-165`(TC-018 `acquireUploadPermit`) · `PortalUploadLabelController.java:63`(TC-043 `@Size`) · `PortalUploadProperties.java:40`(TC-005 `maxImageSizeBytes`) · `application-prd.yml:37-39` · `ConfigProfileDriftGuardTest.java:43-58`.

---

## 이슈 상세

### [F-ISSUE-21] TC-PORTALUP-004 — `files` 파트 누락 시 400 이어야 할 요청이 500 + 전체 스택트레이스로 떨어진다 (A-ISSUE-21 중복)

- **심각도**: HIGH
- **중복 고지**: `A-part3.md` / `A-result.md` 의 **A-ISSUE-21** 이 **동일 원인 · 동일 재현 경로**(`POST /v1/portal/uploads/images`, multipart 에 `files` 파트 없음 → 500)를 이미 등재했다. 본 항목은 F 클러스터 TC 판정 근거로서만 기록하며, `ISSUES.md` 누적 시 **A-ISSUE-21 로 병합**하는 것을 권장한다(신규 결함 아님).
- **기대 동작(기대효과)**: 필수 multipart 파트가 없는 요청은 **클라이언트 오류(400 `INVALID_INPUT`)** 로 응답하고, 로그는 WARN + 예외 클래스명·파트명만 남겨야 한다. 서비스에는 이미 그 의도가 코드로 존재한다 — `PortalUploadService.java:87-89`

  ```java
  if (files == null || files.isEmpty()) {
      throw new CustomException(ErrorCode.INVALID_INPUT, "업로드할 이미지가 없습니다.");
  }
  ```

- **현재 동작(이슈 내용)**: 컨트롤러가 `@RequestParam("files") List<MultipartFile> files`(required 기본값 `true`)로 받으므로, 파트 부재는 서비스 진입 **이전에** `MissingServletRequestPartException` 으로 끊긴다. `GlobalExceptionHandler` 에는 `MissingServletRequestParameterException` 핸들러(`:76`)만 있고 **`MissingServletRequestPartException` 핸들러가 없어** `@ExceptionHandler(Exception.class)`(`:273`)로 떨어진다.

  실측 응답:
  ```json
  {"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}   // HTTP 500
  ```
  실측 로그(ERROR + 전체 스택):
  ```
  ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.
      at org.springframework.web.method.annotation.RequestParamMethodArgumentResolver.handleMissingValueInternal(...)
  ```
  같은 계열로 `Content-Type` 이 multipart 가 아닌 요청은 `MultipartException: Current request is not a multipart request` → 역시 **500**.

  부수 효과로 서비스의 빈 목록 분기(`:87-89`)는 **HTTP 로 도달할 수 없는 dead branch** 가 된다(단위 테스트 `#빈_목록_업로드시_400` 은 서비스를 직접 호출하므로 통과한다 — 테스트 GREEN 이 런타임을 보증하지 못하는 사례).

- **재현/확인 경로**:
  ```bash
  TOKEN=$(...)   # PORTAL_USER
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/images \
       -H "Authorization: Bearer $TOKEN" -F "dummy=1"
  # → HTTP/1.1 500, errorCode=INTERNAL_ERROR
  docker logs klid-backend --since 1m | grep MissingServletRequestPart
  ```
- **영향**: ①**CWE-209/CWE-779** — 클라이언트 오류에 서버 스택트레이스를 ERROR 레벨로 적재. 조작된 요청을 반복하면 로그 볼륨 증폭(CWE-770). ②FE 가 입력 누락을 **서버 장애로 오인**(재시도·알림 오발). ③운영 5xx 알림 오탐으로 실장애 탐지 저하.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `GlobalExceptionHandler` 에 405 핸들러(`:261-271`)와 동형으로 `MissingServletRequestPartException` → 400 `INVALID_INPUT`(파트명만 노출) 핸들러를 추가하고, 겸해 `MultipartException`(비-multipart 요청) → 400 을 함께 처리한다. 로그는 WARN + 예외 클래스명·파트명만(경로 원문 금지). 회귀 가드는 A-ISSUE-21 제안대로 "`Exception.class` 종착지에 도달하는 표준 MVC 예외가 없다"를 고정하는 테스트로 통합한다.

---

### [F-ISSUE-22] TC-PORTALUP-017(부가) — 원본 파일명 255자 절단이 UTF-16 단위라 surrogate pair 를 쪼개고 치환문자를 남긴다

- **심각도**: LOW
- **기대 동작(기대효과)**: `LS_PORTAL_ULD.ORGNL_FILE_NM varchar(255)` 에 안전하게 담기도록 절단하되, **문자(코드포인트) 경계를 보존**해 저장된 표시용 파일명이 원본의 앞부분과 시각적으로 동일해야 한다.
- **현재 동작(이슈 내용)**: `PortalUploadService.java:454-459`

  ```java
  private static String truncate(String name) {
      if (name == null) { return null; }
      return name.length() > ORGNL_FILE_NM_MAX ? name.substring(0, ORGNL_FILE_NM_MAX) : name;
  }
  ```
  `String.length()`/`substring` 은 **UTF-16 코드유닛** 기준이다. BMP 밖 문자(이모지·일부 CJK 확장·희귀 문자)는 2유닛이므로, 절단 지점이 surrogate pair 한가운데면 **고아 high surrogate** 가 남는다. 실측(이모지 `U+1F600` 200개 + `.jpg`, 총 404 유닛):

  ```
  uld_sn | length | octet_length | right(orgnl_file_nm,3) | ascii(right(...,1))
  121    | 128    | 509          | 😀😀?                  | 63
  ```
  마지막 문자가 `?`(치환) 로 저장됐다. **예외는 발생하지 않고 조용히 손상**된다(JDBC/PG UTF-8 인코딩 단계에서 치환).

  ※ 컬럼 초과(=INSERT 실패)는 발생하지 않는다 — UTF-16 유닛 수 ≥ 코드포인트 수이므로 255유닛 절단 결과는 항상 255자 이하다. 즉 **가용성 문제는 없고 표시 정확도 문제**다.

- **재현/확인 경로**:
  ```bash
  EMO=$(python3 -c "print('\U0001F600'*200+'.jpg')")
  curl -X POST .../v1/portal/uploads/images -H "Authorization: Bearer $P" -F "files=@ok.jpg;filename=$EMO"
  psql -c "select length(orgnl_file_nm), ascii(right(orgnl_file_nm,1)) from ls_portal_uld where uld_sn=121"
  # → 128 | 63   (마지막이 '?')
  ```
- **영향**: 표시용 파일명 말미 1문자 손상. 보안 영향 없음(저장 경로는 UUID 강제, 이 값은 어떤 경로 조합에도 쓰이지 않는다). 다운로드 `Content-Disposition` 의 `filename*` 에 치환문자가 실려 나갈 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 코드포인트 경계 보존 절단으로 교체 — 예: `name.codePoints().limit(ORGNL_FILE_NM_MAX).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString()`, 또는 `BreakIterator`/`offsetByCodePoints` 사용. 회귀 테스트는 "surrogate pair 경계에서 절단해도 lone surrogate 가 남지 않는다"를 고정.

---

### [F-ISSUE-23] TC-PORTALUP-057·058(관련) — 포털 다운로드/export 에 "본인 데이터 기간 내" 기간 제한이 전혀 구현돼 있지 않다 (UNCERTAINTIES #11 미해소 재확인)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `CLAUDE.md` 「포털(외부 채널)」 — *"본인 데이터 **기간 내** 다운로드"*. 포털 사용자가 자신의 자산·라벨을 내려받을 수 있는 기간에 상한이 있어야 하며, 기간 경과분은 다운로드가 거부(또는 자산이 정리)되어야 한다.
- **현재 동작(이슈 내용)**: 기간 판정 로직·기준 컬럼·설정 키가 **모두 부재**하다.
  - `LS_PORTAL_ULD` 스키마 실측 컬럼 14개 — `uld_sn, portal_user_no, uld_type_cd, orgnl_file_nm, file_path_nm, file_sz, mime_type_nm, uld_stts_cd, vdo_len_sec, fps, frme_cnt, fail_rsn_cn, reg_dt, mdfcn_dt`. **만료/보존기한 컬럼 0개.**
  - `PortalUploadLabelService.downloadFile`(`:211-256`)·`exportLabels`(`:153-202`) 의 가드는 **소유권 + 경로 안전성 + 파일 실재** 3가지뿐이며 시간축 판정이 없다.
  - `portal/` 패키지 내 `expire*` 참조는 전부 **TUS 업로드 세션 만료**(`PortalUploadSweepJob:71`, `PortalVideoUploadService:133`, `PortalVideoUploadTxService:77`)로 **다운로드 기간과 무관**한 별개 개념이다.
  - `PortalUploadProperties`(11필드)에 보존기간 설정 키 없음.
- **재현/확인 경로**:
  ```bash
  # reg_dt 를 과거로 밀어도 다운로드/export 가 그대로 200
  psql -c "update ls_portal_uld set reg_dt = now() - interval '10 years' where uld_sn=1"
  curl -i .../v1/portal/uploads/1/file   -H "Authorization: Bearer $P"   # → 200
  curl -i .../v1/portal/uploads/1/export -H "Authorization: Bearer $P"   # → 200
  # 스키마·코드 확인
  psql -c "\d ls_portal_uld" | grep -iE "expir|retent|valid"    # → 0건
  grep -rn "retention\|보존기간\|downloadableUntil" backend/src/main/java/kr/co/cudo/authoring/portal/  # → 0건
  ```
- **영향**: 요구사항 미충족(기능 갭). 보안 관점으로는 데이터 최소보관 원칙 미이행 — 포털 사용자 업로드 자산이 무기한 서버에 남고 무기한 재다운로드 가능하다. 개인 자산이 삭제 요청 없이 영구 축적되면 스토리지 증가와 함께 개인정보 보관기간 준수 이슈로 번질 수 있다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: 이 항목은 **정책 확정이 선행**돼야 한다(기간의 기준: `reg_dt` 기준 N일인가 / 마지막 접근 기준인가 / 데이터마트 다운로드와 업로드 자산에 같은 규칙을 쓰는가). 확정 후 ①`PortalUploadProperties` 에 보존기간 키 추가 ②`downloadFile`/`exportLabels` 진입부에 기간 게이트(410 Gone 또는 403) ③기존 정리 스윕(`PortalUploadSweepJob`)에 만료 자산 정리 스텝 추가 순으로 배선하는 것이 기존 구조와 정합한다. **정책 미확정 상태에서 임의 기간을 코드에 박지 말 것.**

---

### [F-ISSUE-24] TC-PORTALUP-018(부가) — rate limit 이 업로드에만 있고 라벨 PUT·export·원본 다운로드는 무제한이다

- **심각도**: LOW
- **기대 동작(기대효과)**: 자원 소비형 포털 엔드포인트(대용량 JSON 파싱·파일 스트리밍·DB 벌크 DELETE+INSERT)에도 per-user 요청량 제한을 두어 단일 사용자가 자원을 독점하지 못하게 한다(OWASP API4:2023 / CWE-770).
- **현재 동작(이슈 내용)**: `RateLimiter` 획득은 `PortalUploadController.acquireUploadPermit`(`:157-165`) **한 곳뿐**이며 `POST /images` 에서만 호출된다. 나머지 포털 업로드 계열 엔드포인트에는 어떤 제한도 없다.

  실측(동일 사용자 70연타):
  | 엔드포인트 | 70회 중 429 |
  |---|---:|
  | `POST /v1/portal/uploads/images` | **10회** (60 초과분 전부) |
  | `GET /v1/portal/uploads/{uldSn}/export` | **0회** |
  | `PUT /v1/portal/uploads/frames/{n}/labels` | **0회** |

  라벨 PUT 은 요청당 최대 2MB 본문 파싱 + 500건 검증 + 벌크 DELETE + `saveAll` 500건을 수행하고, export 는 자산 전체 라벨을 메모리에 모아 pretty JSON 으로 직렬화한다 — 둘 다 업로드 못지않은 자원 소비 경로다.

- **재현/확인 경로**:
  ```bash
  for i in $(seq 1 70); do
    curl -s -o /dev/null -w "%{http_code} " "$B/v1/portal/uploads/1/export" -H "Authorization: Bearer $P1"
  done    # → 200 ×70, 429 없음
  ```
- **영향**: 단일 포털 사용자가 export/라벨 PUT 을 폭주시켜 커넥션·힙·CPU 를 점유할 수 있다. 다만 ①본문 상한(2MB) ②라벨 개수 상한(500) ③페이지 크기 하드캡(100) 이 이미 **요청당 비용**을 제한하고 있어 즉시 서비스 정지로 이어질 여지는 낮다 → LOW.
- **관련**: `UNCERTAINTIES #12`(데이터마트 저장/조회·이미지 서빙 rate limit 부재, 미해소 유지)와 **같은 축의 별개 표면**이다. #12 는 `PortalLabelService`(데이터마트 계열), 본 건은 `PortalUploadLabelService`(업로드 자산 계열)다.
- **수정 방향(제안)** ⚠ **구현하지 않는다**: `acquireUploadPermit` 과 동일한 per-user RateLimiter 패턴을 `PortalUploadLabelController` 의 4개 메서드에 config 만 달리해(예: `portalLabel` 120/1m, `portalDownload` 30/1m) 적용한다. 컨트롤러마다 복제하지 말고 공통 헬퍼(또는 `HandlerInterceptor`)로 뽑아 **획득 지점을 단일화**하는 편이 이후 표면 추가 시 누락을 막는다(게이트 배선을 호출처마다 복제하면 반드시 샌다는 기존 교훈과 동형).

---

## 부록 — 이번 회차 확정/재확인 사항

| 항목 | 상태 |
|------|------|
| **UNCERTAINTIES #5** (`portal.upload.frame-interval-sec`=5초) | F-5~F-7 구간에는 프레임 추출 케이스가 없어 **본 파트 판정에 미영향**(F-9 소관). `PortalUploadProperties` 에 해당 필드가 없고 `PortalFrameExtractRunner` 계열이 소유 — 스코프 확인만 완료 |
| **UNCERTAINTIES #11** (다운로드 기간 제한) | **미해소 재확인** — 스키마·코드·설정 3면 부재 실증(F-ISSUE-23) |
| **UNCERTAINTIES #12** (rate limit 부재) | 데이터마트 계열은 F-part1 소관. 업로드 자산 계열도 **업로드 외 전 경로 부재** 실증(F-ISSUE-24) |
| 포털 업로드 자산 ↔ 비식별 신고 게이트 분리 | **정책대로 확인** — `PortalUploadService`·`PortalUploadLabelService` 에 게이트 참조 0건, `PortalFrameImageCacheControlTest:48` 이 스코프 경계를 코드로 명시 |
| 라벨 PUT raw 배열(알려진 함정) | **실측 확인** — 요청 본문이 `[{...}]` 배열이며 래핑 객체 아님. `@RequestBody(required=false)` 라 본문 생략 시 전체 삭제로 동작 |
| backend 이미지 HEAD 뒤처짐(stack-bringup §2) | **본 파트 무영향** — 실행 이미지가 HEAD `ca3c712b` 재빌드본으로 갱신됐고(`.progress.md`), F-5~F-7 대상 4파일은 심링크/TOCTOU 하드닝(`46f47cee`)·경로순회(`476bc91a`) 수정이 이미 반영된 코드가 실동작으로 확인됨(심링크 탈출 403 실측) |

---

# F 클러스터 part3 (F-8~F-11) 2차 검증 결과

> 대상: `docs/test-cases/F-portal.md` §F-8(32) · §F-9(14) · §F-10·F-11(13) = **59건**
> 환경: 로컬 도커 스택 · backend `localhost:18081`(이미지 HEAD `ca3c712b` 대비 backend 커밋 11개 뒤처짐, `stack-bringup.md` §2) · PG `klid_system`/`public`
> 방식: **PORTAL_USER 토큰으로 실제 TUS 세션 생성→청크 전송→중단→재개→완료** 를 태우고 `LS_PORTAL_TUS_ULD`·`LS_PORTAL_ULD`·`LS_PORTAL_ULD_FRME` DB 행 + 컨테이너 실파일을 근거로 판정. 프레임 추출은 실제 ffmpeg 로 구동.
> 검증 일시: 2026-07-31 04:15~04:40 KST · 폐기(`~~취소선~~`) 행 **0건**(이 3개 섹션에는 없음)

## 집계

| 구분 | 건수 | PASS | PARTIAL | FAIL | BLOCKED | N/A | 확인필요 |
|---|---:|---:|---:|---:|---:|---:|---:|
| F-8 TUS 업로드 | 32 | 32 | 0 | 0 | 0 | 0 | 0 |
| F-9 프레임 추출 | 14 | 14 | 0 | 0 | 0 | 0 | 0 |
| F-10·F-11 상태전이·스윕·분리 | 13 | 11 | 2 | 0 | 0 | 0 | 0 |
| **합계** | **59** | **57** | **2** | **0** | **0** | **0** | **0** |

- 실동작 근거로 판정한 케이스 **44건**, 정적 대조 **15건**(순수함수·프록시 경계·2노드 등 로컬 단일노드에서 구동 불가한 항목).
- self-fill 의심 **0건**. 프레임 간격은 sysconfig 값을 **5→10→5 로 바꿔가며 결과 프레임 수가 3→2→(복원) 로 실제 변하는 것**을 확인해 하드코딩이 아님을 반증했다.
- 신규 이슈 **7건** — HIGH 1 · MEDIUM 4 · LOW 2.

## ★TUS 프로토콜 악용 실측 (공격 / 기대 / 실측 / 파일 상태)

세션 `07246fc8-…`(valid.mp4 30,817B) 및 파생 세션들로 실측. 전 항목 curl/raw socket 실행.

| # | 공격 | 기대 | 실측 | 디스크/DB 상태 |
|---|------|------|------|------|
| 1 | `Upload-Length: 5368709121`(5GB+1) | 413 | **413** `PAYLOAD_TOO_LARGE` | 세션 미생성 |
| 2 | `Upload-Length: 0` / `-1` | 400 | **400** ×2 | 세션 미생성 |
| 3 | `Upload-Length` 헤더 누락 | 400 | **400** | — |
| 4 | `Upload-Length: notanumber` | 400 | **400** `type mismatch param=Upload-Length` | — |
| 5 | 확장자 `.exe` / 메타 없음(확장자 미상) | 400 | **400** ×2 (`허용: [mp4, mov, avi]`) | 세션 미생성 |
| 6 | 파일명 `../../../etc/passwd.mp4` | UUID 강제 | **저장경로 `/app/storage/raw/portal/tus-video/07246fc8-….mp4`** | root 밖 파일 0건 |
| 7 | **파일명에 널바이트**(`a\0b.mp4`) | 400 | **500** ⛔ | **0바이트 고아 파일 잔존 + DB 행 0** → `F-ISSUE-41` |
| 8 | 타 사용자(3002) 세션 HEAD / PATCH / DELETE | 403 | **403 / 403 / 403** | 무변화 |
| 9 | 존재하지 않는 UUID HEAD / 비정형 UUID | 404 / 400 | **404 / 400** | — |
| 10 | 만료(`EXPRY_DT` 과거) 세션 HEAD / PATCH | 410 | **410 / 410** | 무변화 |
| 11 | `Upload-Offset` 헤더 누락 | 400 | **400** | — |
| 12 | `Upload-Offset: abc` | 400 | **400** `type mismatch` | — |
| 13 | `Upload-Offset: -5` / `999999`(length 초과) | 400 | **400 / 400** | offset 미전진 |
| 14 | `Upload-Offset: 5`(서버 0) 불일치 | 409 | **409** `CONFLICT` | offset 0 유지 |
| 15 | offset 20000 + 20000B(잔여 10,817B 초과) | 400 | **400** | 파일 20,000B 유지 |
| 16 | 17MB 단일 청크(상한 16,777,216) | 413 | **413** | 파일 0B 유지 |
| 17 | **동시 PATCH ×3 (전부 offset 0, 20,000B)** | 1건만 성공 | **204 ×1 / 409 ×2** | **파일 정확히 20,000B**(겹침·손상 0) |
| 18 | **마지막 청크 동시 ×3(완료 경합)** | ULD 1행·이벤트 1회 | **204 ×1 / 409 ×2** | `ls_portal_uld` **121→122**(1행만), `PortalFrameExtractBridge` 로그 1회 |
| 19 | **완료 검증 중 DELETE 경합**(race2) | 409 + ULD 보상 삭제 | **409** | **`uld_sn=130` 결번** — 생성 후 보상 삭제 확인 |
| 20 | 취소된 세션 PATCH | 409 | **409** | 파일 이미 삭제됨 |
| 21 | 완료 세션에 마지막 청크 재전송 | 멱등 | **204 + `Upload-Offset: 30817`** | **md5 동일**(`4e16816a…`), ULD 중복 0 |
| 22 | 완료 세션 DELETE | no-op | **204** | 영구 영상·프레임 3장 보존 |
| 23 | **`Content-Length` 를 실제보다 크게 선언**(실 20,000 / 선언 25,000) | 미완료 | 서버 대기 후 타임아웃 | **offset 0, 파일 0B**(truncate 복원) |
| 24 | **`Content-Length` 를 실제보다 작게 선언**(선언 100 / 전송 20,000) | 100B 만 기록 | **204 + `Upload-Offset: 100`** | **파일 정확히 100B**(초과분 미기록) |
| 25 | `Transfer-Encoding: chunked`(Content-Length 없음) | 400 | **400** | offset 무변화 |
| 26 | **위조 컨테이너**(텍스트를 .mp4 로) | 400+CANCELLED+삭제 | **400** `유효한 영상 컨테이너가 아닙니다` | `CANCELLED`, 파일 삭제, ULD 미생성 |
| 27 | 손상 mp4(헤더만 + garbage) | 400+CANCELLED | **400** `영상을 확인할 수 없습니다` | `CANCELLED`, 파일 삭제 |
| 28 | 오디오 전용 mp4 | 400+CANCELLED | **400** `비디오 스트림이 없는 파일입니다` | `CANCELLED`, 파일 삭제 |
| 29 | `Tus-Resumable: 0.2.2` | 412 | **412** | — |
| 30 | **`Tus-Resumable` 헤더 자체 누락** | (TUS 1.0 권고 412) | **통과(정상 처리)** | → `F-ISSUE-47` (LOW) |
| 31 | `Upload-Metadata` 1KB 초과 / 깨진 base64 | 413 / 400 | **413 / 400** | — |
| 32 | **PATCH `Content-Type: application/json`** | 415 | **500** ⛔ `INTERNAL_ERROR` | → `F-ISSUE-44` |
| 33 | 내부 토큰(WORKER)으로 POST | 403 | **403** | — |
| 34 | 무토큰 POST | 401 | **401** | — |
| 35 | 세션 생성 연타(동시 IN_PROGRESS 3 초과) | 429 | **429 ×6 연속** | 4번째부터 전부 429 |

**핵심**: 선언 크기 ≠ 실제 바이트 조합(23·24), 청크 겹침(17), 완료 경합(18·19) 어느 경로로도 **파일이 오염되거나 ULD 가 중복 생성되지 않았다.** 완료 검증(매직바이트+ffprobe)은 세 종류 위조 파일을 모두 거부했다. 결함은 **널바이트 파일명(7)** 과 **415 → 500(32)** 두 건.

## ★내부 파이프라인 분리 실측 (포털자산이 `LS_DATA_RAW`·`V_COMPLETED_*` 에 존재하는가)

포털 업로드 실행 후(영상 6건 신규 적재, `uld_sn` 124~132) 직접 SQL 로 확인.

| 검사 | SQL | 결과 | 판정 |
|---|---|---:|:--:|
| 포털 자산 총량 | `select count(*) from ls_portal_uld` | 122 | — |
| 포털 경로가 `LS_DATA_RAW` 에 샜는가 | `raw_file_path_nm like '%/portal/%'` | **0** | 분리 OK |
| 포털 경로가 `LS_DATA_SRC` 에 샜는가 | `src_file_path_nm` / `de_idntf_src_file_path_nm` `like '%/portal/%'` | **0** | 분리 OK |
| `V_COMPLETED_VIDEO` 노출 | `original_video_path`/`export_path_nm` `like '%/portal/%'` | **0** | 분리 OK |
| `V_COMPLETED_FRAME` 노출 | `original_path`/`deidentified_path` `like '%/portal/%'` | **0** | 분리 OK |
| 뷰 정의가 `LS_PORTAL_*` 를 참조하는가 | `pg_views.definition ilike '%ls_portal%'` | **0** | 분리 OK |
| 비식별 로그 증가 | `ls_deident_proc_log` count | 영상 6건 업로드 전후 **51 → 51** | 비식별 미적용 OK |
| 포털 사용자 라벨 저장이 내부 라벨을 건드리는가 | `POST /v1/portal/user-labels` 201 후 `ls_data_lbl` count | **655 → 655**, `ls_portal_user_label` 0 → 1 | 단방향 OK |
| 코드 의존 방향 (portal → 내부 파이프라인) | 업로드 경로 8개 파일의 `import kr.co.cudo.authoring.*` | 파이프라인 엔티티/리포 **0건** (공유 유틸 `TusChunkStore`·`VideoMagicByteValidator`·`FfmpegFrameExtractor.FrameWriter`·`SystemConfigService`·`FrameImageService` 만) | OK |
| 코드 의존 방향 (내부 → portal) | `/portal/` 밖에서 `LsPortalUld*` 참조 | **0건** | OK |
| 역방향 누수(내부 영상이 포털 목록에) | `GET /v1/portal/uploads` totalElements=66, 전부 `LS_PORTAL_ULD` 행 | 내부 `rawSn` 0건 | OK |
| 실행 자원 격리 | 추출 스레드명 | `portal-extract-1` (`AsyncConfig:210` core1/max2/queue20/CallerRuns) | OK |

> ⚠ 저장 루트는 **공유**다 — 포털 자산도 `STORAGE_RAW_PATH`(`/app/storage/raw`) 하위이나 `**/portal/**` 서브트리로 분리돼 내부 `frames/raw|deid/{rawSn}` 와 경로 충돌이 없다(내부는 `frames/{raw|deid}/…`, 포털은 `portal/frames/{uldSn}`). 실측상 교차 참조 0건.
> ⚠ 포털 SAM2·데이터마트 Load 서비스(`PortalSam2Service`·`PortalLabelService`)는 내부 엔티티를 참조하지만 이는 **F-2/F-4 의 데이터마트 Load 경로**이며 본 파트(업로드 자산 경로)와는 다른 축이다 — TC-PORTAL-090/091 의 판정 대상 아님.

## F-8 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-TUS-001 | OPTIONS: TUS 능력 광고(max 5GB) | PASS | [실동작] 204 + `Tus-Max-Size: 5368709120` · `Tus-Version/Tus-Resumable: 1.0.0` · `Tus-Extension: creation,termination`. `PortalTusUploadController.java:66-76` 일치 | 확장 목록에 `expiration` 미광고(24h TTL 은 있으나 미노출) — 정보성 |
| TC-TUS-002 | POST 세션 생성 → 201+Location | PASS | [실동작] 201 + `Location: /v1/portal/uploads/tus/07246fc8-3efc-4fac-a284-bbc47d8c59a9`, DB 행 `IN_PROGRESS`/`uld_offset=0`/`expry_dt=+24h` | |
| TC-TUS-003 | Upload-Length 누락 → 400 | PASS | [실동작] 400 `INVALID_INPUT "Upload-Length 헤더가 필요합니다."` (`:88-90`) | |
| TC-TUS-004 | Upload-Length 5GB 초과 → 413 | PASS | [실동작] 5368709121 → 413 `PAYLOAD_TOO_LARGE`, 한도 5368709120 메시지 (`PortalVideoUploadService.java:87-90`) | 5GB 실업로드 없이 헤더로 검증(지시 준수) |
| TC-TUS-005 | 확장자 allowlist 밖 → 400 | PASS | [실동작] `.exe` → 400, 메시지 `허용: [mp4, mov, avi]`. 메타 미첨부(확장자 미상)도 400 | |
| TC-TUS-006 | 동시 진행 세션 상한(3) 초과 → 429 | PASS | [실동작] 3건 생성 후 4번째 429 `TOO_MANY_REQUESTS`, 연타 6회 전부 429 | 만료 세션도 슬롯 점유 → `F-ISSUE-43` |
| TC-TUS-007 | 저장 파일명 UUID 강제(경로순회 차단) | PASS | [실동작] 메타 `../../../etc/passwd.mp4` → `file_path_nm=/app/storage/raw/portal/tus-video/{uuid}.mp4`, `orgnl_file_nm` 만 원문 보존 | |
| TC-TUS-008 | Upload-Metadata 1KB 초과 → 413 | PASS | [실동작] 1,409자 메타 → 413 (`:182-184`) | |
| TC-TUS-009 | Upload-Metadata base64 디코딩 실패 → 400 | PASS | [실동작] `!!!not-base64!!!` → 400 (`:194-200`) | |
| TC-TUS-010 | 지원 안 되는 Tus-Resumable → 412 | PASS | [실동작] `0.2.2` → 412 `PRECONDITION_FAILED` (`:163-168`) | 헤더 **누락** 시는 통과(관대) — `F-ISSUE-47` |
| TC-TUS-011 | HEAD offset 조회(재개) | PASS | [실동작] 204 + `Upload-Offset: 20000` / `Upload-Length: 30817` / `Cache-Control: no-store` | |
| TC-TUS-012 | 타 사용자 세션 HEAD/PATCH/DELETE → 403(IDOR) | PASS | [실동작] userNo 3002 토큰으로 3건 전부 403 `본인의 업로드 세션이 아닙니다.` | 미존재 UUID 는 404 로 갈려 존재 오라클 성립(경미, TC 기대와 일치하므로 이슈 미제기) |
| TC-TUS-013 | 만료(24h) 세션 HEAD → 410 | PASS | [실동작] `expry_dt` 를 과거로 조정 후 HEAD 410 / PATCH 410 `GONE` | DELETE 는 만료여도 204(취소 가능) — 합리적 |
| TC-TUS-014 | PATCH 청크 append offset 전진 | PASS | [실동작] 0→20000(204) → 중단 → HEAD 20000 → 20000→30817(204). **완성 파일 md5 가 원본과 동일**(`4e16816a39d8b215dbdccf22d1884e36`) | 재개 무결성 실증 |
| TC-TUS-015 | PATCH Upload-Offset 누락 → 400 | PASS | [실동작] 400 (`:130-132`) | |
| TC-TUS-016 | offset 불일치(재개 무결성) → 409 | PASS | [실동작] 서버 0 / 요청 5 → 409 (`PortalVideoUploadTxService.java:98-100`) | |
| TC-TUS-017 | offset 범위 밖(음수/length 초과) → 400 | PASS | [실동작] `-5`, `999999` 둘 다 400 (`:95-97`) | |
| TC-TUS-018 | 청크 크기 상한(16MB) 초과 → 413 | PASS | [실동작] 17,825,792B → 413, 한도 16777216 메시지 (`:91-94`) | |
| TC-TUS-019 | 청크 길이가 잔여 용량 초과 → 400 | PASS | [실동작] offset 20000 + 20000B(총 30817) → 400 (`:101-103`) | |
| TC-TUS-020 | 동시 PATCH 낙관적 락 충돌 → 409+truncate 복원 | PASS | [실동작] 3병렬 → 204×1 / 409×2, 파일 **정확히 20,000B**(겹침 0), 서버 offset 20000 | ⚠ 실경로는 **비관 락(`findByUldIdForUpdate`) 직렬화 후 offset 불일치 409** — 낙관락 분기(`:109-115`)는 미도달(근거 드리프트 참조) |
| TC-TUS-021 | 취소된 세션 PATCH → 409 | PASS | [실동작] DELETE 후 PATCH → 409 `취소된 업로드 세션입니다.` (`:81-83`) | |
| TC-TUS-022 | 완료 세션 마지막 청크 재전송 → 멱등 | PASS | [실동작] 204 + `Upload-Offset: 30817`, 파일 md5 불변, ULD 중복 0 (`:84-87`) | HTTP 응답에 `uldSn` 미노출(최초 완료 응답도 동일) — 계약상 클라이언트는 TUS 응답으로 자산 ID 를 알 수 없음(정보성) |
| TC-TUS-023 | 완료 검증: 매직바이트 불일치 → 거부(CANCELLED)+400 | PASS | [실동작] 텍스트 83B `.mp4` → 400, DB `CANCELLED`, 임시파일 삭제, ULD 미생성 (`PortalVideoUploadService.java:162-165`) | |
| TC-TUS-024 | 완료 검증: ffprobe 실패 → 400+CANCELLED | PASS | [실동작] 헤더 200B+garbage → 400 `영상을 확인할 수 없습니다`, `CANCELLED` (`:166-175`) | |
| TC-TUS-025 | 완료 검증: 비디오 스트림 없음 → 400 | PASS | [실동작] AAC 전용 mp4 → 400 `비디오 스트림이 없는 파일입니다(오디오 전용 등).` (`:176-179`) | |
| TC-TUS-026 | 완료 검증은 락/트랜잭션 밖(커넥션 점유 방지) | PASS | [정적] `appendChunk`(`:146-186`)에 `@Transactional` 없음 — 짧은 tx(`appendChunkTx`) 종료 후 매직바이트+ffprobe(최대 30s) 수행, 이후 별 빈 `finalizeCompleted`/`finalizeRejected` 재진입. self-invocation 없음 | |
| TC-TUS-027 | 완료 원자 전이(멱등): affectedRows==1만 이벤트 | PASS | [실동작] 마지막 청크 3병렬 → `ls_portal_uld` 121→**122**(1행), 완료 로그 1회, `PortalFrameExtractBridge` 1회 (`PortalVideoUploadTxService.java:132-157`) | |
| TC-TUS-028 | 검증 중 취소된 세션 완료 시도 → 409+ULD 보상 삭제 | PASS | [실동작] 최종 PATCH 와 DELETE 경합(4회 시도 중 1회 적중) → **409**, `uld_sn=130 결번`(INSERT 후 `uldRepository.delete` 보상) (`:141-152`) | |
| TC-TUS-029 | DELETE 세션 취소+임시파일 삭제 | PASS | [실동작] 204 → 컨테이너에서 해당 `.mp4` 소멸, DB `CANCELLED` (`PortalVideoUploadService.java:190-206`) | |
| TC-TUS-030 | 완료 세션 DELETE → no-op(영구 파일 삭제 금지) | PASS | [실동작] 204 반환하되 영구 영상 30,817B·프레임 3장 그대로 (`:198-201`) | |
| TC-TUS-031 | cancel도 행 잠금(PATCH와 직렬화) | PASS | [정적+실동작] `cancel` 이 `findByUldIdForUpdate`(PESSIMISTIC_WRITE) 사용(`:192-194`). race2 에서 cancel↔완료가 직렬화돼 409 로 귀결 | |
| TC-TUS-032 | TUS 저장 경로 Path Traversal 차단 | PASS | [실동작+정적] `resolveSafe`(`:210-218`) + 저장명 UUID 강제 이중. 경로형·유니코드 전각 파일명 모두 storage root 하위 UUID 로 귀결 | |

## F-9 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-060 | 영상 완료 후 AFTER_COMMIT → 비동기 추출 | PASS | [실동작] 로그: `[PortalTus] completed uldSn=124` → `[PortalFrameExtractBridge] … triggering frame extract` → `[portal-extract-1] ready uldSn=124 frames=3`. `PortalFrameExtractBridge` 는 `@TransactionalEventListener(AFTER_COMMIT)` | |
| TC-PORTALUP-061 | UPLOADED→PROCESSING 원자 전이 실패 → 중단 | PASS | [정적] `PortalFrameExtractRunner:86-91` → `beginProcessing` 이 `transitionToProcessing`(조건부 UPDATE `WHERE uldSttsCd='UPLOADED'`) 0행이면 `Optional.empty` → `skip` 로그 후 return. 테스트 `PortalFrameExtractRunnerTest#러너_진입시_PROCESSING_전이_및_삭제된_자산이면_중단` | |
| TC-PORTALUP-062 | 프레임 간격 sysconfig 스냅샷(기본 5초) | PASS | [실동작] DB `ls_system_config` = **5**. 12.0s/10fps 영상 → **3프레임(frame_no 0·1·2, 원본 프레임 0/50/100)**. **반증 대조**: 설정을 10 으로 바꾸고 동일 영상 재업로드 → **2프레임**, 다시 5 로 복원 → 24프레임/120s. 하드코딩 아님을 실증 | UNCERTAINTIES #5 확정(5초) 재확인 |
| TC-PORTALUP-063 | sysconfig 미설정/오류 시 5초 폴백 | PASS | [정적] `snapshotIntervalSec()`(`:185-193`) — null·≤0·RuntimeException 전부 `DEFAULT_INTERVAL_SEC=5`(`:47`) | |
| TC-PORTALUP-064 | 프레임 간격 config 경계 [1,600] | PASS | [실동작] `PUT /v1/manage/configs/portal.upload.frame-interval-sec` 에 `0`/`601`/`700` → 전부 **400** `값이 허용 범위를 벗어났습니다`. `ConfigKeys.java:71` = `{1, 600}` | |
| TC-PORTALUP-065 | maxFrames 2000 초과 → 균등 샘플링 | PASS | [정적] `computeFrameNumbers`(`:161-178`) — `candidates.size() > cap` 이면 `stride=totalFrames/cap` 로 `cap` 개 단조증가 재샘플링, `frameNo>=totalFrames` 시 break → 결과 ≤ 2000. 테스트 `프레임_수_상한_초과시_균등_샘플링으로_상한_이내` | 상한 적용 **전** 후보 리스트 전량 생성 → `F-ISSUE-45` |
| TC-PORTALUP-066 | maxFrames 경계: 정확히 2000 | PASS | [정적] `candidates.size() <= cap` 분기(`:161-163`)로 2000 은 그대로 반환(재샘플링 미진입) | |
| TC-PORTALUP-067 | 영상 길이<간격 → 최소 1프레임 보장 | PASS | [실동작] 0.5s/10fps 영상 업로드 → `uld_sn=126`, `frme_cnt=1`, `ls_portal_uld_frme` 1행(frme_no=0) (`:151-160`) | |
| TC-PORTALUP-068 | fps 미상 시 30 폴백 | PASS | [정적] `probe.fps() > 0 ? probe.fps() : DEFAULT_FPS`(`:102`) + `computeFrameNumbers` 내 `effFps`(`:148`). `PortalVideoProbeFfprobe.fpsOf` 는 파싱 불가 시 0 반환 | 실측 영상 전건 fps 정상(10.0)이라 폴백 미발화 — 정적 판정 |
| TC-PORTALUP-069 | 추출 성공 후 원자 커밋 READY+메타 | PASS | [실동작] `uld_sn=124/125/127/131/132` 전부 `READY` + `vdo_len_sec`·`fps`·`frme_cnt` 동시 확정. `transitionToReady` 는 `WHERE uldSttsCd='PROCESSING'` 조건부 | |
| TC-PORTALUP-070 | ffmpeg/probe 실패 → 부분 파일 정리+FAILED | PASS | [실동작] `frames/128` 을 **일반 파일로 선점**해 `Files.createDirectories` 실패 유도 → `uld_stts_cd=FAILED`, `fail_rsn_cn='프레임 추출 실패: FileAlreadyExistsException'`, `ls_portal_uld_frme` **0행**, PROCESSING 고착 없음 (`:133-138`) | **B-ISSUE-21/C-ISSUE-03 류 영구 고착 재현 안 됨** — 실패가 FAILED 로 정상 마감 |
| TC-PORTALUP-071 | 진행 중 자산 삭제 감지 → 중단+정리 | PASS | [실동작] 120s 영상 추출 중(98프레임 기록 시점) 상태를 FAILED 로 전이(스윕 모사) → `[PortalFrame] aborted — asset gone mid-extract uldSn=129`, **부분 파일 98장 + 디렉터리 삭제**, `ls_portal_uld_frme` 0행 (`:112-119`) | 부활 차단(`transitionToReady` 0행)도 함께 성립 |
| TC-PORTALUP-072 | 프레임 출력 경로 Path Traversal 차단 | PASS | [정적] `resolveSafeFramesDir`(`:195-201`) — 입력이 `Long uldSn` 이라 문자열 주입 자체가 불가, 추가로 `startsWith(storageRoot)` 가드 | 심층방어. 실측 경로 전건 `…/portal/frames/{uldSn}` |
| TC-PORTALUP-073 | @Async 예외 전파 금지(runner 흡수) | PASS | [정적] `runAsync`(`:71-82`) `catch (Exception)` → WARN 로그만. `markFailed` 는 `extract` 내부에서 선행 | ⚠ `OutOfMemoryError` 등 `Error` 는 미포착(`F-ISSUE-45` 참조) |

## F-10 / F-11 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|----|---------|:--:|------|------|
| TC-PORTALUP-080 | LsPortalUld 상태 전이 비즈니스 메서드만 | PASS | [정적] `LsPortalUld.java:114-138` — `markProcessing()`/`markReady(vdoLen,fps,frmeCnt)`/`markFailed(reason)` 만 존재, `@Setter` 없음. 테스트 `LsPortalUldStateTransitionTest` 2건 | |
| TC-PORTALUP-081 | 이미지 업로드는 즉시 READY | PASS | [정적] `PortalUploadService.java:129` `uld.markReady(null, null, 1)` + `LsPortalUldFrme.create(uldSn, IMAGE_FRAME_NO, …)` | 근거 라인 **128 → 129** 드리프트 |
| TC-PORTALUP-082 | 만료 TUS 세션 스윕: 임시파일+행 제거 | PARTIAL | [정적] `PortalUploadSweepJob:71-77` + `PortalUploadSweepTxService:45-56` 로직·테스트(`PortalUploadSweepJobTest`·`PortalUploadSweepIT`) 정상. **[실동작] 만료 세션 픽스처를 만들고 30분+ 대기했으나 스윕 미발화**(`docker logs` 에 `[PortalSweep]` 0건) — local 프로파일에서 `@EnableScheduling` 자체가 등록되지 않음 | `F-ISSUE-42` |
| TC-PORTALUP-083 | 고착 자산(30m 정지) → FAILED+프레임 정리 | PARTIAL | [정적] `PortalUploadSweepJob:83-89`·`cleanupFrameDir:92-97` 로직 정상. **[실동작] `mdfcn_dt` 2시간 과거 + PROCESSING 픽스처를 두고 대기했으나 미발화**(동일 원인) | `F-ISSUE-42` |
| TC-PORTALUP-084 | 스윕 프레임 디렉토리 정리 root 가드 | PASS | [정적] `PortalUploadSweepJob:92-97` — `storageRoot.resolve("frames").resolve(uldSn).normalize()` + `startsWith` 미충족 시 WARN 후 skip | |
| TC-PORTALUP-085 | 스윕 트랜잭션 경계 별 위임(프록시 우회 방지) | PASS | [정적] 잡(`:39`)이 `PortalUploadSweepTxService` 주입, 벌크 UPDATE/DELETE 는 전부 별 빈의 `@Transactional` 메서드(`:71-72`, `:83-84`). 파일 I/O 만 잡에서 tx 밖 수행 | 프로젝트 반복결함(self-invocation tx 유실) 회피 패턴 준수 |
| TC-PORTALUP-086 (신규) | 만료 TUS 세션 정리는 조건부 DELETE로 2노드 중복 실행 안전 | PASS | [실동작(SQL 모사)] 리포지토리와 동일한 `DELETE … WHERE uld_id=? AND stts_cd='IN_PROGRESS'` 를 연속 2회 실행 → **DELETE 1 / DELETE 0**(예외 없음). `LsPortalTusUploadRepository:57-68`, 파일 정리도 1행 반환 노드만 수행(`PortalUploadSweepTxService:49-54`) | 2노드 동시 기동은 단일 노드 로컬이라 모사 |
| TC-PORTALUP-087 (신규) | 고착 자산 FAILED 전이도 조건부 UPDATE로 2노드 중복 방지 | PASS | [실동작(SQL 모사)] `UPDATE … SET FAILED WHERE uld_sn=? AND uld_stts_cd IN ('UPLOADED','PROCESSING')` 연속 2회 → **UPDATE 1 / UPDATE 0**. `PortalUploadSweepTxService:67-84` | 검증 후 `uld_sn=126` 을 READY 로 원복 |
| TC-PORTAL-090 | 포털 업로드는 LS_PORTAL_* 전용, 내부 미참조 | PASS | [실동작+정적] 업로드 경로 8개 파일의 import 에 batch/video/label 엔티티·리포 **0건**(공유 유틸만). `PortalUploadLabelService:45,79-81` 은 포털 3종 리포지토리만 보유. SQL 상 포털 경로가 `LS_DATA_RAW`/`LS_DATA_SRC` 에 0건 | |
| TC-PORTAL-091 | 포털 업로드 영상 비식별 미적용 | PASS | [실동작] 영상 6건 업로드 전후 `ls_deident_proc_log` **51 → 51**(무증가), `DeidentifyClient`/`VideoIngestedEvent` 참조 0건. 완료 이벤트는 포털 전용 `PortalVideoUploadedEvent` 만(`PortalVideoUploadService.java:27-31`) | 본인 데이터 정책대로 원본 그대로 서빙 |
| TC-PORTAL-092 | 포털 사용자 라벨 저장이 LS_DATA_LBL 불변 | PASS | [실동작] `POST /v1/portal/user-labels`(rawSn 20012, BBOX) → 201, `ls_data_lbl` **655 → 655**, `ls_portal_user_label` 0 → 1 (`PortalLabelService.java:196-223`) | 단방향(데이터마트 미반영) 확인 |
| TC-PORTAL-093 | 포털 프레임 추출 풀 관제 배치와 격리 | PASS | [실동작] 추출 스레드명 `portal-extract-1`(관제 `batchAsyncExecutor` 아님). `AsyncConfig.java:210-222` core=1/max=2/queue=20/CallerRunsPolicy | 병렬 업로드 2건이 순차 처리되는 것도 관측(race3 PROCESSING / race4 UPLOADED 대기) |
| TC-PORTAL-094 | 포털 자산은 데이터마트 View 미노출 | PASS | [실동작] 4개 `V_COMPLETED_*` 뷰 정의에 `ls_portal` 참조 **0건**, `%/portal/%` 경로 조회 결과 **0행** | |

## 근거 드리프트

| TC | 카탈로그 근거 | 실제 | 영향 |
|---|---|---|---|
| TC-PORTALUP-081 | `PortalUploadService.java:128` | **`:129`** (`uld.markReady(null, null, 1)`) | 라인 1줄 이동. 판정 무영향 |
| TC-TUS-020 | `PortalVideoUploadTxService.java:109-115`(낙관적 락 충돌 → 409+truncate) | 코드는 존재하나 **실행 경로에서 미도달**. 같은 메서드 선두 `:72` 의 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)가 동시 PATCH 를 직렬화하므로, 후행 요청은 `@Version` 충돌 전에 `:98-100` offset 불일치(409)로 걸러진다. 실측 3병렬에서 `[PortalTus] concurrent PATCH conflict` 로그 0건 | **기대결과 문구 정정 필요** — "낙관적 락 충돌"이 아니라 "비관 락 직렬화 후 offset 불일치 409". 결과(409·파일 무손상)는 동일하므로 PASS 유지. `:109-115` 는 사실상 도달 불가 분기(잔존 심층방어) |

> 그 외 F-8·F-9·F-10/11 의 근거 `file:line` 은 **전건 일치**했다(카탈로그 F-8 각주의 "`PortalVideoUploadTxService` 전체 -1 이동 반영" 은 정확히 반영돼 있었다).

## 이슈 상세

### [F-ISSUE-41] TC-TUS-002/007 — Upload-Metadata 파일명의 널바이트가 500 + 정리되지 않는 고아 임시파일을 남기고, 동시세션 상한(429)까지 우회한다
- **심각도**: HIGH
- **기대 동작(기대효과)**: 파일명은 표시용 필드지만 사용자 입력이므로 **제어문자(특히 `\0`)를 거부하거나 제거**해 400 으로 마감해야 한다. 실패 시에도 `createEmptyFile` 로 이미 만든 임시파일은 보상 삭제돼야 한다(부분 실패 원자성).
- **현재 동작(이슈 내용)**:
  - `PortalTusUploadController.java:178-192 parseFileName` → `decodeBase64` 는 base64 디코딩만 하고 **제어문자 검사를 하지 않는다.**
  - `PortalVideoUploadService.java:104-118` 은 ① `TusChunkStore.createEmptyFile(absolutePath)`(디스크 쓰기) → ② `LsPortalTusUpload.create(..., truncate(cmd.fileName()))` → ③ `tusRepository.save(session)` 순서다. `ORGNL_FILE_NM` 에 `0x00` 이 실려 **PostgreSQL 이 INSERT 를 거부**한다.
  - 실측 로그: `SQL Error: 0, SQLState: 22021` / `ERROR: invalid byte sequence for encoding "UTF8": 0x00` → `GlobalExceptionHandler - unclassified data integrity violation constraint=null cause=PSQLException` → **HTTP 500** `{"errorCode":"INTERNAL_ERROR"}`.
  - **부작용 3종**:
    1. `/app/storage/raw/portal/tus-video/{uuid}.mp4` **0바이트 고아 파일이 남는다**(트랜잭션 롤백은 DB 만 되돌린다). 실측 2건(`cd6aebc1-…`, `73d0d1b4-…`) 확인.
    2. 스윕(`claimExpiredSessions`)은 **DB 행 기준**으로만 파일을 지우므로 이 고아 파일은 **영구히 정리되지 않는다**(CWE-459).
    3. DB 행이 안 생기므로 `countByPortalUserNoAndSttsCd(IN_PROGRESS)` 가 증가하지 않아 **동시세션 상한 3(429)이 전혀 걸리지 않는다** → 한 PORTAL_USER 가 이 요청을 무제한 반복해 파일/아이노드를 계속 증식시킬 수 있다(CWE-770). TUS 경로에는 별도 rate limiter 도 없다(`F-ISSUE-46`).
  - 성공 INFO 로그 `[PortalTus] session created uldId=… ext=mp4` 가 **커밋 실패 전에** 찍혀 운영 로그가 실제와 어긋난다.
- **재현/확인 경로**:
  ```bash
  TOKEN=... # PORTAL_USER
  MD=$(printf 'a\0b.mp4' | base64)   # YQBiLm1wNA==
  curl -i -X POST http://localhost:18081/api/v1/portal/uploads/tus \
    -H "Authorization: Bearer $TOKEN" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Length: 100" -H "Upload-Metadata: filename $MD"
  # → HTTP 500 {"errorCode":"INTERNAL_ERROR"}
  docker exec klid-backend ls -l /app/storage/raw/portal/tus-video/   # 0바이트 고아 파일 잔존
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select count(*) from ls_portal_tus_uld where orgnl_file_nm like 'a%';"   # 0
  ```
- **영향**: CWE-20(입력 검증 누락) · CWE-459(불완전 정리) · CWE-770(무제한 자원 소비 — 상한 우회) · CWE-209(운영 500). 실동작 확인. 저장소가 공유 NAS 이므로 아이노드 고갈은 **내부 파이프라인까지 동반 마비**시킬 수 있다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`parseFileName` 단계에서 `\0`·개행 등 제어문자 포함 시 400 으로 거부(또는 제거 후 저장). ②`createSession` 순서를 "DB 저장 → 파일 생성"으로 뒤집거나, INSERT 실패 시 `TusChunkStore.deleteQuietly(absolutePath, storageRoot)` 보상 삭제를 `catch` 에 추가. ③성공 로그를 커밋 이후로 이동.

### [F-ISSUE-42] TC-PORTALUP-082/083 — 포털 정리 스윕(`@Scheduled`)의 발화가 무관한 토글 `authoring.work-lock.sweep.enabled` 에 결합돼 있다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `PortalUploadSweepJob` 은 자기 도메인 설정(`portal.upload.sweep.*`)으로 발화 여부가 정해져야 한다. 만료 TUS 세션·고착 자산 정리는 기능 토글과 무관하게 항상 돌아야 하는 위생 잡이다.
- **현재 동작(이슈 내용)**:
  - 애플리케이션 전체에서 `@EnableScheduling` 은 **`auth/scheduler/WorkLockSweepConfig.java:14-17` 단 한 곳**뿐이고, 거기에 `@ConditionalOnProperty(name="authoring.work-lock.sweep.enabled", havingValue="true", matchIfMissing=false)` 가 붙어 있다.
  - `application-local.yml:68-70` 은 `authoring.work-lock.sweep.enabled: false` → **local 프로파일에서는 `@EnableScheduling` 이 등록되지 않아 `PortalUploadSweepJob.run()` 의 `@Scheduled` 가 영원히 발화하지 않는다.**
  - 실측: 컨테이너 기동 `2026-07-30T17:43:08Z`(프로파일 `local`), 초기지연 10분 + 주기 30분 기준으로 4회 이상 발화했어야 하나 `docker logs klid-backend | grep PortalSweep` **0건**. 만료 세션 픽스처(`EXPRY_DT` 2시간 과거, `IN_PROGRESS`)와 고착 자산 픽스처(`PROCESSING`, `MDFCN_DT` 2시간 과거)를 두고 30분+ 관측했으나 **둘 다 그대로 남았다.**
  - `application.yml:206-210` 주석은 이 결합을 **인지하고 있음**을 보여준다("…다른 `@Scheduled` 잡(Portal/TUS sweep)을 부수적으로 발화시키지 않는다"). 즉 의도된 부작용이지만, **포털 스윕 쪽에는 자기 토글이 없어** 운영자가 `WORK_LOCK_SWEEP_ENABLED=false` 로 두면 포털 정리가 조용히 죽는다(dev/stg/prd 기본값은 `true` 라 현재는 동작).
- **재현/확인 경로**:
  ```bash
  docker exec klid-backend env | grep SPRING_PROFILES_ACTIVE     # local
  grep -rn "EnableScheduling" backend/src/main/java              # WorkLockSweepConfig 1곳뿐
  sed -n '66,71p' backend/src/main/resources/application-local.yml   # work-lock.sweep.enabled: false
  docker logs klid-backend 2>&1 | grep -c PortalSweep            # 0
  ```
- **영향**: 프로젝트 반복 결함 패턴(`feature-toggle-coupling-defect-pattern`)의 재현. local/테스트에서 스윕 회귀가 **런타임으로는 절대 잡히지 않고**, 운영에서 무관한 토글 조작 한 번으로 고아 세션·임시파일·PROCESSING 영구 고착이 누적된다. CWE-459/CWE-770 의 완화 수단이 사라지는 것.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`PortalUploadSweepConfig`(`@ConditionalOnProperty("portal.upload.sweep.enabled")` + `@EnableScheduling`)를 도메인 국소로 신설해 자기 토글로 발화하게 하거나, ②`ControlNotifyDebouncer`(`:114`)처럼 전용 데몬 스케줄러를 잡이 직접 소유해 `@EnableScheduling` 비의존으로 전환. ③어느 쪽이든 "스케줄이 실제 등록됐는가"를 기동 로그/헬스에 드러낼 것.

### [F-ISSUE-43] TC-TUS-006/013 — 만료된 IN_PROGRESS 세션이 동시세션 슬롯을 계속 점유하고, 세션 목록 조회 API 가 없어 사용자가 최대 24시간 자기 잠금에 걸린다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 동시 진행 상한(3)은 **살아있는** 세션만 세야 한다. 만료된 세션은 상한 계산에서 빠지거나, 사용자가 스스로 정리할 수단이 있어야 한다.
- **현재 동작(이슈 내용)**:
  - `PortalVideoUploadService.java:97-102` 는 `tusRepository.countByPortalUserNoAndSttsCd(portalUserNo, IN_PROGRESS)` 만 세고 **`EXPRY_DT` 를 보지 않는다**(`LsPortalTusUploadRepository:26`).
  - 만료 세션은 `findExpired`+스윕이 지워야 정리되는데, 그 스윕이 30분 주기이고 로컬에선 아예 안 돈다(`F-ISSUE-42`). TTL 은 `LsPortalTusUpload.TTL_HOURS = 24`.
  - 결과: 브라우저 탭을 닫아 `uldId` 를 잃어버린 세션 3건이 쌓이면 **최대 24시간 + 스윕 주기 동안 신규 업로드가 전부 429** 가 된다. `PortalTusUploadController` 에는 **내 세션 목록 조회(GET) 엔드포인트가 없어** 사용자가 uldId 를 되찾아 DELETE 할 방법도 없다.
  - 실측: 사용자 3002 에 만료 세션 1건이 IN_PROGRESS 로 남아 있는 상태에서 신규 생성 → `201, 201, 429`. 만료 세션이 슬롯 1칸을 그대로 먹고 있음을 확인.
- **재현/확인 경로**:
  ```sql
  update ls_portal_tus_uld set expry_dt = now() - interval '2 hour' where uld_id='<세션>';
  ```
  ```bash
  # 같은 사용자로 세션 3개 추가 생성 → 3번째부터 429
  for i in 1 2 3; do curl -s -o /dev/null -w "%{http_code} " -X POST .../uploads/tus \
    -H "Authorization: Bearer $P2" -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1000" \
    -H "Upload-Metadata: filename $(printf lock$i.mp4|base64)"; done   # 201 201 429
  ```
- **영향**: 가용성(사용자 자기 잠금). 보안 결함은 아니나 포털 사용자 입장에서 복구 불가능한 막다른 상태다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①상한 카운트를 `stts_cd='IN_PROGRESS' AND expry_dt > now()` 로 좁히거나, ②세션 생성 시 자기 만료 세션을 lazy 정리, ③`GET /v1/portal/uploads/tus`(내 진행 중 세션 목록)를 추가해 재개·취소 동선을 열 것.

### [F-ISSUE-44] TC-TUS-014 — TUS PATCH 에 잘못된 Content-Type 을 보내면 415 가 아니라 500 + `INTERNAL_ERROR` 가 나간다 (A-ISSUE-21 의 포털 경로 재현)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 매칭 실패는 **415 Unsupported Media Type**.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 이 `GlobalExceptionHandler` 의 `Exception.class` fallback 으로 떨어져 **500** 을 반환하고, 로그에 `ERROR … unhandled exception` + 스택트레이스를 남긴다.
  ```
  2026-07-31 04:33:59.630 ERROR [0c43e8eabb47] GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/json' is not supported
  ```
  응답: `{"success":false,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` / HTTP 500.
- **재현/확인 경로**:
  ```bash
  curl -i -X PATCH .../v1/portal/uploads/tus/<uldId> -H "Authorization: Bearer $P1" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 100" -H "Content-Type: application/json" --data-binary @chunk
  # → HTTP 500
  ```
- **영향**: A 클러스터 `A-ISSUE-21`(415/406/multipart 4xx → 500 + 전체 스택)의 **동일 근본원인이 포털 TUS 경로에서도 재현**됨을 확증. 인증된 PORTAL_USER 가 헤더 한 줄로 ERROR 로그 + 스택트레이스를 무제한 생성 가능(CWE-770/CWE-209). 표준 tus 클라이언트 호환성도 깨진다.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** A-ISSUE-21 수정(`HttpMediaTypeNotSupportedException`·`HttpMediaTypeNotAcceptableException` 등 Spring MVC 표준 예외를 `@ExceptionHandler` 로 4xx 매핑)에 흡수시킬 것 — 포털 전용 수정은 불필요.

### [F-ISSUE-45] TC-PORTALUP-065 — `computeFrameNumbers` 가 `maxFrames` 상한을 적용하기 *전에* 후보 프레임 리스트를 전량 생성하고, 그 크기가 ffprobe 가 신고한 영상 길이에 비례한다(길이 무검증)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 상한(2000)이 **메모리 할당 자체를 제한**해야 한다. 컨테이너 헤더가 신고하는 duration 은 신뢰할 수 없는 사용자 입력이므로 상식적 범위로 클램프돼야 한다.
- **현재 동작(이슈 내용)**: `PortalFrameExtractRunner.java:151-163`
  ```java
  long totalFrames = Math.max(1L, Math.round(durationSec * effFps));
  long step        = Math.max(1L, Math.round(effInterval * effFps));
  List<Integer> candidates = new ArrayList<>();
  for (long n = 0; n < totalFrames; n += step) { candidates.add((int) n); }   // ← 상한 적용 전
  if (candidates.size() <= cap) { return candidates; }
  ```
  루프 반복 수 = `totalFrames / step` ≈ **`durationSec / intervalSec`** (fps 는 상쇄된다). `cap`(=2000)은 리스트를 **다 만든 뒤에** 적용된다.
  - `durationSec` 은 `PortalVideoProbeFfprobe.probeTask` 가 ffprobe `format.duration`/스트림 duration 을 **그대로** 돌려주며(`:88-95`) 어떤 상한 검증도 없다. 파일 크기와의 정합성 교차검증도 없다.
  - mp4 `mvhd/mdhd` duration 은 uint32 이므로 timescale=1 이면 최대 ≈4.29e9 초를 신고할 수 있다. 이 경우 후보 수 ≈ 8.6억 → `ArrayList<Integer>` OOM.
  - `runAsync` 의 `catch (Exception)`(`:78`)은 **`OutOfMemoryError` 를 잡지 못하며**, `portalExtractExecutor` 는 core=1 이라 이 스레드가 죽으면 포털 프레임 추출 전체가 영향을 받는다.
  - `(int) n` 캐스팅(`:156`)도 `totalFrames > Integer.MAX_VALUE` 에서 음수 프레임 번호를 만든다.
- **재현/확인 경로**: 관계식은 실측으로 확인했다 — 120s/interval 5 → 후보 24개, 12s/interval 10 → 2개, 12s/interval 5 → 3개(정확히 `duration/interval`). ⚠ **OOM PoC(mvhd duration 위조 컨테이너 업로드)는 공유 스택을 죽여 다른 검증 에이전트의 데이터를 파괴할 위험이 있어 의도적으로 실행하지 않았다.** 코드 경로상 도달 가능성은 위 라인으로 확정.
  ```bash
  # 관계식 확인(실행함)
  # interval 10 + 12s/10fps → frme_cnt=2 / interval 5 + 120s/10fps → frme_cnt=24
  ```
- **영향**: CWE-770(무제한 자원 소비) / CWE-789(입력값 기반 대용량 할당). 인증된 PORTAL_USER 가 **작은 크기의 크래프트 mp4 하나로** 백엔드 힙을 고갈시킬 수 있다. 5GB 상한·확장자 allowlist·매직바이트·ffprobe 검증을 전부 통과할 수 있는 경로다(모두 "실제 영상인가"만 보고 "길이가 말이 되는가"는 안 본다).
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①`candidates` 생성 루프에 `cap` 기반 조기 중단(또는 처음부터 `min(totalFrames/step, cap)` 개만 생성)을 넣고, ②`durationSec` 을 상식 상한(예: 24h)으로 클램프하며 초과 시 400/FAILED 로 마감, ③`(int)` 캐스팅 대신 long→int 안전 변환.

### [F-ISSUE-46] TC-TUS-002/029 — TUS 세션 생성에 rate limit 이 없고 CANCELLED/COMPLETED 세션 행은 스윕 대상이 아니라 `LS_PORTAL_TUS_ULD` 가 무한 증식한다
- **심각도**: LOW
- **기대 동작(기대효과)**: 세션 생성에 속도 제한이 있거나, 종결된 세션 행이 일정 기간 후 정리돼야 한다.
- **현재 동작(이슈 내용)**:
  - `/v1/portal/uploads/tus/**` 에는 rate limiter 가 없다 — 코드베이스에서 rate limit 을 갖는 곳은 `PortalUploadController`(이미지 업로드)·`PortalSam2Service`·`RoleClaimRateLimiter`·`WebhookRateLimiter` 뿐이고 TUS 컨트롤러/서비스에는 없다(grep 0건). 유일한 억제는 **동시 IN_PROGRESS 3건** 상한이다.
  - 그런데 `deleteExpiredInProgress`(`LsPortalTusUploadRepository:66-68`)와 `findExpired`(`:53-55`)는 **`stts_cd='IN_PROGRESS'` 인 행만** 지운다. `CANCELLED` 행은 어떤 경로로도 삭제되지 않는다(`COMPLETED` 는 영구 영상 링크라 보존이 타당).
  - 따라서 "3건 생성 → 3건 DELETE(취소) → 반복" 루프로 **행을 무제한 적재**할 수 있다(파일은 취소 시 지워지므로 디스크는 안전). 실측 세션 18건 중 CANCELLED 7건이 남아 있다.
  - 참고로 UNCERTAINTIES #12 는 "rate limit 은 업로드·SAM2·**TUS** 에만 존재"라고 적고 있으나 **TUS 에는 실제로 없다** — 문서 정정 대상.
- **재현/확인 경로**:
  ```bash
  grep -rln "RateLimit" backend/src/main/java | grep -i tus     # 0건
  docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "select stts_cd, count(*) from ls_portal_tus_uld group by stts_cd;"   # CANCELLED 누적
  ```
- **영향**: CWE-770(약함 — 행 크기가 작고 디스크는 무사). 장기적으로 `LS_PORTAL_TUS_ULD` 비대화 및 `idx_lptu_user_stts` 외 조회 성능 저하.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** ①TUS `POST` 에 사용자별 생성 rate limit 추가, ②스윕에 "종결(CANCELLED) 후 N일 경과 행 삭제" 절 추가, ③UNCERTAINTIES #12 의 "TUS 에도 rate limit 존재" 서술 정정.

### [F-ISSUE-47] TC-TUS-010 — `Tus-Resumable` 헤더가 아예 없는 요청을 그대로 처리한다(TUS 1.0 권고는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 OPTIONS 를 제외한 모든 요청에 `Tus-Resumable` 을 요구하며, 없으면 412 를 권고한다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController.java:163-168`
  ```java
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) { throw ...PRECONDITION_FAILED; }
  }
  ```
  `null` 이면 검사를 건너뛴다. 실측: 헤더 없이 보낸 POST/PATCH/HEAD/DELETE 가 모두 정상 처리됐다(본 검증의 여러 요청이 이 경로를 탔다).
- **재현/확인 경로**:
  ```bash
  curl -i -X POST .../v1/portal/uploads/tus -H "Authorization: Bearer $P1" \
    -H "Upload-Length: 30817" -H "Upload-Metadata: filename $(printf a.mp4|base64)"   # → 201
  ```
- **영향**: 프로토콜 준수 미달(보안 영향 없음). 버전 협상 없이 다른 TUS 버전 클라이언트가 조용히 붙어 향후 스펙 변경 시 무증상 오동작 가능.
- **수정 방향(제안)**: ⚠ **구현하지 않는다.** `tusResumable == null` 도 412 로 마감(OPTIONS 제외). FE 포털 TUS 클라이언트가 항상 헤더를 붙이는지 먼저 확인 후 적용할 것.

---

## 검증 중 생성/변경한 데이터 (다른 에이전트 참고)

- `LS_PORTAL_ULD` **uld_sn 124~132 신규**(126=short.mp4 READY 1프레임, 128=FAILED 재현용, 129=중단 재현용 FAILED, 130 결번=보상삭제, 131/132=READY 24프레임). `LS_PORTAL_TUS_ULD` 세션 다수(COMPLETED/CANCELLED).
- `LS_PORTAL_USER_LABEL` 1행(rawSn 20012, TC-PORTAL-092 검증용).
- `ls_system_config['portal.upload.frame-interval-sec']` 를 5 → 10 → 1 → **5 로 원복 완료**(현재 값 5, DB 확인함).
- `uld_sn=126` 을 스윕 픽스처로 PROCESSING/FAILED 로 바꿨다가 **READY 로 원복 완료**.
- **rawSn 126·133 은 건드리지 않았다.** `LS_DATA_RAW`/`LS_DATA_SRC`/`LS_DATA_LBL` 은 조회만 했고 변경 0건(라벨 655 → 655 불변 확인).
- 잔존 고아 임시파일 2건(`F-ISSUE-41` 증거)은 그대로 두었다: `/app/storage/raw/portal/tus-video/cd6aebc1-…mp4`, `73d0d1b4-…mp4`(각 0바이트, DB 행 없음).
