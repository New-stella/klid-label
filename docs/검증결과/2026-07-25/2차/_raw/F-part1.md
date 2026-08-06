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
