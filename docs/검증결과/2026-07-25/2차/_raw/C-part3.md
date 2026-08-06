# C 클러스터 part3 (TC-LABEL-74~149) 2차 검증 결과

- 검증 일시: 2026-07-31 03:30~03:55 KST · backend `localhost:18081`(이미지 HEAD `ca3c712b` 재빌드본) · ai-server `:19300` · mock-server `:9400` · postgres `:5432`(스키마 `public`)
- 참조 데이터: `pipeline-drive.md` §3 (rawSn 126 완주/APPROVED · 132 검수대기 · 133 신고 OPEN · 129/130/131 파생 · 라벨마스터 9종)
- 대상: `C-marking-labeling.md` §C-2 의 `TC-LABEL-74`~`TC-LABEL-149` **66건**
  (번호 결번 81~89 는 카탈로그 자체에 존재하지 않음. `~~TC-LABEL-99~~` 는 **폐기 케이스라 집계 제외** — 라벨 보존 정책 반전(b0647c4c)으로 신고 경로의 스냅샷/삭제 분기가 소멸했고 대체는 TC-LABEL-124)
- 파일 수정 0건(본 결과 파일 1개만 신규). 빌드/테스트 실행 0건. **backend 컨테이너 재기동 0건.**

## 집계

| 판정 | 건수 |
|---|---:|
| PASS | **65** |
| PARTIAL | **1** |
| FAIL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **계(폐기 1건 제외)** | **66** |

- 근거 유형: **[실동작] 45건 · [정적]+테스트커버 21건**(bulkhead·TOCTOU·심링크 교체 등 런타임 강제 불가 항목).
- 실동작 검증 중 발생시킨 데이터 변경은 **전건 원복**했다(§검증 중 개입 참조). rawSn **126 라벨 22건 불변**, rawSn **133 신고 OPEN·`DE_IDENT_YN='F'` 불변** 확인.
- 신규 이슈 **2건**(LOW 2) — `C-ISSUE-41`(PORTAL_USER dead grant · 케이스 기대값 불성립) · `C-ISSUE-42`(412 OpenAPI 미선언).
  ⚠ 번호는 임무 지시(part3 = 41번부터)에 따른 것으로, **1차(2026-07-25) ISSUES.md 의 동명 `C-ISSUE-41`(YOLO 좌표 clamp, 이미 해소)과는 별개**다.

## 1차 이슈 해소 대조

| 1차 이슈 | 내용 | 2차 실측 | 판정 |
|---|---|---|---|
| C-ISSUE-21 | 라벨 full-replace 동시 저장 lost update(무경고 삭제) | `labelVersion` 낙관적 토큰 + 프레임 행 비관적 락(`lockAndReadLabelVersion`) 도입. stale 토큰 저장 **409** 실측, 미첨부는 200 하위호환, 무변경은 버전 미증가 | **해소** (TC-LABEL-110/111/112) |
| C-ISSUE-22 | 좌표 이미지 경계 초과 저장 무검증 | `FrameBoundsResolver` 실측 + `validateWithinBounds`. 999999 → **400**(`이미지=1920x1080`), 경계값 1920/1080 → 200, 측정 실패 프레임은 상한만 skip + WARN | **해소** (TC-LABEL-113~118) |
| C-ISSUE-23 | R7 손상 좌표 JSON fail-safe 전용 테스트 부재 | `backend/src/test` 전수 grep `pointsEqual|normalizePoints` → **여전히 0건** | **미해소(이월)** — UNCERTAINTIES 이월 목록과 동일 |
| C-ISSUE-24 | TASK_MODIFIED 발행 런타임 관측 불가(`CONTROL_NOTIFY_ENABLED=false`) | 현 형상 `CONTROL_NOTIFY_ENABLED=true`. pipeline-drive §1 #17 에서 `TASK_MODIFIED` mock 도달 실측 | **해소(환경)** |
| C-ISSUE-25 | 비활성 마스터 참조 시 프레임 저장 전면 409 차단 | `isNewLabelAssignment` 로 **신규 부여에만** USE_YN 강제. 기존 참조 유지 재전송 **200** 실측 / 신규·변경 부여 **409** 실측 | **해소** (TC-LABEL-119/120) |
| C-ISSUE-41(1차) | 실모델 YOLO 경계 음수 좌표로 온라인 오토라벨 전량 400 | `DetectionBoxNormalizer` 공용 규칙(clamp + 퇴화 스킵 + 형식위반만 400). 실동작 BBOX 6건·POLYGON 6건 정상 반환, 400 0건 | **해소** (TC-LABEL-136~139) |

## ★프레임 이미지 서빙 4경로 실측

> 라벨링 캔버스 백지·원본 유출·판정기 이원화 3대 실패모드를 겨냥한 반증 실측.

| # | 경로 | 역할 | `raw` 파라미터 | 기대 | 실측 | 심링크 방어 |
|:-:|---|---|---|---|---|---|
| 1 | `GET /v1/frames/{srcSn}/image` | WORKER(배정) | 미지정 | DEID 서빙 | **200 / Content-Length 247,266 = 비식별 파일 크기**(원본은 226,485) · `no-store` · `nosniff` | `serveFrame` → deid 분기는 `StorageSubtreePolicy.verifyDeidentifiedFile` → `v.path()`(realpath) → `openNoFollow` |
| 1b | 〃 | WORKER | `raw=true` | **무시·DEID 강제** | **200 / 247,266 (DEID)** — 원본 미유출 | 〃 |
| 1c | 〃 | REVIEWER | `raw=true` | 원본 허용 | **200 / 226,485 (RAW)** | 원본 분기도 `toRealPath()` 재검증 후 그 실경로를 `openNoFollow` |
| 1d | 〃 (파생 프레임 srcSn=71, 480P) | REVIEWER | 미지정 | 파생도 DEID 로 200(백지 아님) | **200 / 7,773** | 〃 |
| 1e | 〃 (파생 프레임) | REVIEWER | `raw=true` | 원본 픽셀 부재 | **404** `이미지 경로가 비어있습니다` | — |
| 2 | `GET /v1/frames/{srcSn}/deid-image` | WORKER(배정) | 없음 | 비식별 전용 200 | **200 / 247,266** · `Content-Disposition: inline; filename="frame_deid_66.jpg"`(srcSn+MIME 파생만) · `no-store` · `nosniff` | 단일 판정기 + `openNoFollow` |
| 2b | 〃 (deid 경로 null, srcSn=25) | WORKER | — | **원본 폴백 없음 404** | **404** `비식별 이미지 파일이 존재하지 않습니다` (같은 프레임의 `SRC_FILE_PATH_NM` 은 채워져 있음) | — |
| 3 | `GET /v1/videos/{rawSn}/frames/{frameNo}/image` | REVIEWER | 미지정/`raw=true` | DEID 기본·REVIEWER 원본 | **200 / 247,266** · `no-store` / `raw=true` → **226,485** | `FrameImageService.serveFrame` **동일 함수** |
| 3b | 〃 | WORKER | `raw=true` | 무시·DEID | **200 / 247,266** | 〃 |
| 4 | `GET /v1/portal/frames/{srcSn}/image` (F 담당, 규약 정합만 확인) | — | — | 같은 판정기·open·no-store | `PortalLabelService:459` `verifyDeidentifiedFile` · `:482` `FrameImageService.openNoFollow` · `:497` `noStore()` | 동일 규약 재사용 |

**판정기 단일화 확증**: 경로 1·1b~1e·3·3b 는 컨트롤러가 판정을 복제 보유하지 않고 `FrameImageService.serveFrame` 한 곳으로 수렴(`FrameImageController:89`, `VideoController:303`), 경로 2·4 는 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 + `FrameImageService.openNoFollow`(public static) 재사용. 아키텍처 가드 테스트 `architecture/FileServingLinkFollowGuardTest — 서빙_클래스는_링크추종_open_API를_쓰지_않는다` 가 회귀를 고정한다.

**신고 게이트 평가 순서**(rawSn 133 = `'F'`): 미배정 WORKER → **403**(파생 68) / 배정 WORKER → **412**(80). 인가가 항상 먼저이며 게이트가 프레임 존재 오라클이 되지 않는다.

## 결과표

| ID | 케이스명 | 판정 | 근거 확인 | 비고 |
|---|---|:--:|---|---|
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | PASS | [정적] `AutolabelOnlineService.java:321-348`(박스별 try/catch)·`:370-387`(buildPolygonMessage, `anyExcluded = anyMock \|\| skipped>0`) + 테스트 `AutolabelPolygonServiceTest:238 일부_박스_SAM실패시_성공분만_반환하고_스킵을_로깅한다`(:253 에서 비-mock 실패도 `POLYGON_PARTIAL_MOCK_MESSAGE` 고지 단언) | [실동작] srcSn=66 POLYGON 6검출/6반환·skip 0 → 스킵 분기 미발화 |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | PASS | [정적] `:338-343`(TOO_MANY_REQUESTS 만 rethrow)·`:529-533` + 테스트 `AutolabelPolygonServiceTest:318 폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다` | 런타임 bulkhead 포화 강제 불가 |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금/신고 | PASS | [정적] `:312`(루프 내 `requireNotBlocked`)·`:352`(마감 재확인) + 테스트 `AutolabelPolygonServiceTest:303 폴리곤_배치중_작업락걸리면_409로_차단한다` · `AutolabelGateWiringTest 폴리곤_루프중_게이트가_있으면_배치_도중_신고시_남은_박스는_전송되지_않는다` | 좌표 미반환 확인은 테스트 단언 |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | PASS | [정적] `:499-503` + 테스트 `AutolabelOnlineServiceTest 온라인_AI경로_동시_초과요청시_bulkhead_거부_429_TOO_MANY_REQUESTS` | |
| TC-LABEL-78 | ai-server 호출 실패 502 | PASS | [정적] `:504-509`(`LogSanitizer` + 고정 문구 `YOLO 오토라벨 호출 실패`) + 테스트 `AutolabelOnlineServiceTest AI_실패시_502이며_inflight_락이_해제된다` | 스택·경로 미노출 |
| TC-LABEL-79 | AutolabelRequest conf 범위 | PASS | [실동작] `POST /v1/frames/66/autolabel {"confThreshold":0.9}` → **400** `인식 민감도는 0.80 이하여야 합니다` / `0.1` → **400** `0.25 이상` | `AutolabelRequest.java:39-40` |
| TC-LABEL-80 | classes 100개 초과 | PASS | [실동작] classes 101개 → **400** `클래스는 최대 100개까지 지정할 수 있습니다` | `AutolabelRequest.java:36` |
| TC-LABEL-90 | 신고 정상 — 라벨 보존 | PASS | [실동작] srcSn=53(rawSn 20029, 라벨 1건 사전 삽입) → **201** `rprtSn=6`. 라벨 **1→1 불변**, `LS_LABEL_VERSION` **0행**, `DE_IDENT_YN Y→F`, 작업락 LOCKED, 로그 `labelsPreserved=true privacyReset=1 privacyResetAudited=1` | 사후 전량 원복 |
| TC-LABEL-91 | 신고 reason 누락 | PASS | [실동작] `{"reason":""}` → **400**, `{}` → **400** (`reason: 신고 사유는 필수입니다`) | 서비스 백스톱 `:158-162` 정적 확인 |
| TC-LABEL-92 | 신고 reason 1000자 초과 | PASS | [실동작] 1001자 → **400** `신고 사유는 1000자 이하여야 합니다` | |
| TC-LABEL-93 | 신고 IDOR(srcSn 경로) | PASS | [실동작] WORKER(2001) → 미배정 srcSn=68 → **403** `본인에게 배정되지 않은 영상입니다` | `DeidentReportService.java:122` |
| TC-LABEL-94 | 신고 이미 잠금 | PASS | [실동작] srcSn=80·rawSn=133(잠김) → **409** `이미 비식별 재처리 중인 영상입니다`(두 진입점 동일) | 신고행 3건 불변 |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | PASS | [정적] `:178-180` `findByRawSnForUpdate` 로 부모 RAW 잠금 조회 + 테스트 `webhook/AugmentDeidentConcurrencyIT` | 동시 증강 콜백 직렬화 |
| TC-LABEL-96 | 신고 동시 락 유니크 409 | PASS | [정적] `:237-241` `DataIntegrityViolationException`→409 + 테스트 `DeidentReportServiceTest 동시_신고_unique_위반시_409` | |
| TC-LABEL-97 | 신고 개인정보 3필드 리셋 | PASS | [실동작] srcSn=53 `anony/psdo/prvc = Y,N,Y` → 신고 후 **전부 NULL**, 대상은 해당 rawSn 프레임만 | `:218-219` |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED(META_UPDATED) | PASS | [정적] `:230-233` `ChangeType.META_UPDATED` + 테스트 `DeidentReportServiceTest:408·:504` 가 `META_UPDATED` 단언(구 `LABEL_DELETED` 부재) | APPROVED 영상 실신고는 rawSn 126 파괴 위험으로 미수행 |
| TC-LABEL-100 | resolve 미인증 | PASS | [실동작] 토큰 없이 `POST /v1/deident-reports/3/resolve` → **401** | 필터 단계 차단 + 서비스 백스톱 `:354-356` |
| TC-LABEL-101 | resolve 신고 미존재 | PASS | [실동작] rprtSn=999999 → **404** `신고를 찾을 수 없습니다` | |
| TC-LABEL-102 | resolve IDOR | PASS | [실동작] WORKER(2001) → rprtSn=4(rawSn 144, 미배정) → **403** | `:361` verifyRawAccess |
| TC-LABEL-103 | resolve OPEN 아님 | PASS | [실동작] rprtSn=4(RESOLVED) REVIEWER → **409** `이미 처리된 신고입니다` | |
| TC-LABEL-104 | resolve 산출물 미검증 gate | PASS | [실동작] rprtSn=3 REVIEWER → **409** `비식별 산출물이 확인되지 않습니다…`, 이후 DB 확인 `report=OPEN` · `DE_IDENT_YN='F'` · 작업락 LOCKED 유지(fail-closed). WARN `resolve blocked — deident artifact not verified rawSn=133`. [정적] 무결성 판정은 `:553 DeidentArtifactIntegrity.isValidVideoArtifact` **단일 지점** + 테스트 `18바이트_스텁으로는_비식별_신고가_해제되지_않는다` | |
| TC-LABEL-105 | resolve 시간조건 | PASS | [실동작] 신고 03:00:33 vs procLog `rspns_dt` 02:57:32 · 비식별 mp4 mtime 02:57:26 → 두 조건 모두 미충족 → **409**. [정적] `:504` 60초 스큐(mtime 전용)·`:565` procLog 엄격 비교 + 테스트 `신고이전_비식별본만_존재시_resolve_거부` / `신고이후_파일교체시_resolve_성공` | |
| TC-LABEL-106 | resolve 정상 + `'F'→'Y'` 복원 | PASS | [실동작·간접] rawSn 144 의 rprtSn 4·5 → `report_stts_cd=RESOLVED`, `ls_auth_work_lock` **RELEASED / rmv_rsn=MANUAL_DEIDENT_DONE**, `ls_data_raw(144).de_ident_yn='Y'`. [정적] `:374-391`(FOR UPDATE 하 복원) + 테스트 `비식별파일_존재시_resolve성공_deIdntfYn_Y복원_마킹게이트_통과` | 해당 행은 **동시 진행 중인 다른 담당 에이전트의 드라이브 산출물**을 DB 로 확인한 것(본 검증은 rawSn 133 을 OPEN 으로 보존해야 하므로 자체 성공 resolve 미수행) |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | [실동작·간접] 위 rawSn 144 는 resolve 후에도 `DATA_STTS_CD=FAILED` 유지(되감김 없음). [정적] `:384-391` 주석·구현이 `markDeidentified("Y")` 만 수행 + 테스트 `APPROVED_영상_신고_해소시_배치단계가_되감기지_않는다` | CWE-664 |
| TC-LABEL-108 | listReports status allowlist | PASS | [실동작] `?status=X` → **400**, `?status=resolved`(소문자) → **400**. 메시지 접두 `list.status:` 로 **컨트롤러 `@Pattern` 이 먼저** 발화함을 확인(서비스 `toUpperCase` 관용은 도달 불가) | `:426-438` |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | [실동작] status 미지정 → `totalElements=1`, content = rprtSn 3(OPEN)만. RESOLVED 2건(4·5) 미노출 | `:427-429` |
| TC-LABEL-110 | labelVersion 불일치 409 | PASS | [실동작] srcSn=77 현재 ver=1 에 `labelVersion:0` 전송 → **409** `다른 사용자가 먼저 저장했습니다…`, 라벨 미변경 | `LabelService.java:457-465` |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip | PASS | [실동작] labelVersion 없이 신규 1건 저장 → **200**, `LS_DATA_SRC.LBL_VER 0→1` | 하위호환 |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 | PASS | [실동작] 동일 세트 재전송(`[[10,10],[100,200]]` ↔ 저장값 `[[10.0,10.0],[100.0,200.0]]`) → 200, `LBL_VER` **1 유지**. `pointCn` 수치 정규화 비교가 실제로 동작함을 함께 확증 | `:400-406` |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 | PASS | [실동작] `[[999999,888888],…]` → **400** `좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=1920x1080)` — 클램프 아님 | ★3 확정 정책 정합 |
| TC-LABEL-114 | 경계값 x==width 허용 | PASS | [실동작] `[[1900,1000],[1920,1080]]` → **200** 저장 | `:768` `x > width` |
| TC-LABEL-115 | 레거시 out-of-bounds 무변경 재저장 허용 | PASS | [실동작] DB 로 기존 라벨 좌표를 `[[1900,1000],[5000,4000]]` 로 만든 뒤 **동일 좌표 재전송 → 200**(프레임 전체 저장 차단 회귀 없음) | `:330-335` |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 | PASS | [실동작] 경계 안 라벨을 `[…,[5000,4000]]` 로 변경 → **400** / 위 레거시 라벨을 `[…,[6000,4000]]` 로 **변경**해도 **400** | `:333-335` |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip | PASS | [실동작] srcSn=25(이미지 파일 부재) 에 `[[500000,400000],[600000,500000]]` → **200 저장** + WARN `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=25 cause=CustomException`. [정적] 메트릭 `label.bounds.skipped{reason}` 계상은 `FrameBoundsResolver:103/111/118` | actuator 는 인증 필요로 메트릭 값 직접 조회 불가 |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 | PASS | [실동작] 17점 중 `[99999,99999,**0**]` 포함 → **200**(제외) / 같은 좌표를 `v=2` 로 바꾸면 → **400** `이미지=1920x1080` | `:764-767`·`:777-780` |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 | PASS | [실동작] `LS_LABEL(37, USE_YN='N')` 참조 라벨을 DB 로 만든 뒤 `id`+동일 `labelId=37` 재전송 + 같은 프레임의 다른 라벨 좌표 수정 → **200** | `:685-695` |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 | PASS | [실동작] `id` 지정 + `labelId` 를 37 로 **변경** → **409** `사용 중지된 라벨입니다: labelId=37` / 신규(id=null)에 37 부여도 **409** | `:674-677` — "id 붙이면 통과" 우회 차단 확인 |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 | PASS | [실동작] srcSn=80(rawSn 133 `'F'`) — WORKER **412**, REVIEWER **412**(역할 무관). 대조군 srcSn=66 → 200 | `LabelService.java:193`(인가 `:188` 이후) |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 | PASS | [실동작] `GET /v1/frames/80/label-history` — WORKER **412**, REVIEWER **412** | `LabelService.java:482`(인가 `:477` 이후) |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 | PASS | [정적] `DeidentReportGate.java:66-71` 는 `findDeIdntfYnByRawSn(rawSn)` **단일 행 projection** 이며 `ORGNL_RAW_SN` 을 참조하지 않음(`:23-47` 폐기 사유 명시) + 테스트 `LabelAccessGuardDeidentGateTest ★원본이_신고중이어도_파생영상_프레임은_열려있다` / `파생영상도_자기행이_F면_차단된다`. [실동작] 파생 프레임 68 라벨 조회 **200** | 현 DB 에 "부모 `'F'` × 파생 `'Y'`" 조합이 없어(133 은 파생 없음, 129~131 의 부모 126 은 `'Y'`) 실동작은 부분. **파생 경유 열람은 확정 정책의 귀결 — 결함 아님** |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 | PASS | [실동작] rawSn 133 프레임(78·80·82·84·86·88) 라벨 **28건 유지**, `LS_LABEL_VERSION` **0행**. 20029 재현에서도 라벨 1→1·스냅샷 0 | `:197-205` |
| TC-LABEL-125 | 개인정보 리셋 행 단위 감사 | PASS | [실동작] srcSn=53 신고 시 `LS_DATA_LBL_HSTRY` **1행 신규**: `chg_dtl_cn={"event":"PRIVACY_META_RESET","deidentReportSn":6,"changes":[]}`, `add/mdfcn/del_cnt=0`, `reg_id=2001`(actor)·`reg_dt` 기록 → 라벨 델타 0건이라 `V_COMPLETED_LABEL_CHANGE` V139 필터로 미노출 | `:218-226` |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 | PASS | [정적] `:387-391` `'F'→'Y'` 복원만으로 `LabelAccessGuard:135-141` 게이트가 열림(별도 복원 API 없음) + 테스트 `DeidentReportLabelPreservationIT 비식별_신고_상태에서는_라벨_조회가_차단되고_resolve_후에는_기존_라벨이_그대로_조회된다` · `DeidentReportGateCoverageIT resolve_후에는_위_경로_전부가_다시_열리고_라벨이_그대로다` | 실동작 미수행 — rawSn 133 을 OPEN 으로 보존하라는 임무 제약 |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 | PASS | [정적] `DeidentReportController.java:122`(`POST /v1/videos/{rawSn}/deident-report`)·`DeidentReportService.java:147-155` → 부수효과는 `doReport` 공용 본체(`:169-257`) 로 수렴, 통지 `srcSn=null` + 테스트 `마킹단계_rawSn_신고시_작업락과_F전이와_개인정보리셋이_srcSn경로와_동일하게_수행된다`. [실동작] rawSn 경로의 409/403/412 계열은 전건 실측(TC-94/128/129/130) | 201 정상 접수 실동작은 srcSn 경로로만 수행(20029) |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 | PASS | [실동작] `POST /v1/videos/129/deident-report`(REVIEWER) → **412**, 본문 = `이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다` — **부모 rawSn 미노출·원본 유도 없음**. 신고행 3건 불변. WARN 감사 `rejected — derivative video is out of the report workflow rawSn=129 orgnlRawSn=126 reason=derivative test`(사유 sanitize 후 기록) | `:183`·`:295-306` |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 | PASS | [실동작] rawSn 20030 을 일시 `DE_IDENT_YN='N'` 으로 두고 요청 → **412** `아직 비식별 처리가 완료되지 않은 영상입니다…`(즉시 `'Y'` 원복). 반면 이미 `'F'` 인 133 은 통과해 기존 **409** 경로 유지 | `:186`·`:329-337`, 판정 원천 `LsDataRaw.hasDeidentArtifact()` |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess | PASS | [실동작] WORKER(2001) → 미배정 rawSn 129 → **403** `본인에게 배정되지 않은 영상입니다`(파생 412 보다 **먼저** 평가되어 존재 여부 미노출) | `:151`·`LabelAccessGuard.java:83-100` |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 | PASS | [실동작] 파생 srcSn=68 → **412**(파생 문구) / 비식별 미수행 srcSn=54 → **412**(미수행 문구). 두 진입점 응답 동일 | `:125`·`:169-186` |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 | PASS | [정적] `:487-495` — 승인 여부 무관 발행, `DeidentReportResolvedEvent` 만 조건부 + 테스트 `미승인_영상_해소시_게이트_재개방만_발행되고_export_재산출은_없다 — VLM 보류 재개 경로 보존` | |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 | PASS | [정적] `:492-494` `isReviewApproved` 가드 + 테스트 `resolve시_승인영상은_자기_rawSn으로만_export가_재트리거된다` | |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 | PASS | [실동작] srcSn=53 신고 직후 로그 `[StreamMetaCache] evicted rawSn=20029` **단건**(다른 rawSn 무접촉). [정적] `:248`(신고)·`:395`(해소) | 파생 캐시 미접촉 = 확정 정책 정합 |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 | PASS | [실동작] ①잠긴 영상(srcSn=80/rawSn 133) → **409** `작업이 잠긴 영상입니다` ②락 없이 `'F'`(rawSn 132 일시 `'F'`, srcSn=79) → **412** `비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다`(즉시 `'Y'` 원복) | `AutolabelOnlineService.java:411-419` — 작업락 판정 선행 확인 |
| TC-LABEL-136 | 검출 좌표 상한 clamp | PASS | [정적] `DetectionBoxNormalizer.java:59-68`(`clamp(v, upperBound)`) + 테스트 `DetectionBoxNormalizerTest 이미지_상한_초과좌표는_이미지_경계로_clamp된다` · `AutolabelOnlineServiceTest ai_응답_좌표_이미지_상한_초과시_이미지_경계로_clamp된다` | 실모델이 in-bounds 만 출력해 런타임 강제 불가 |
| TC-LABEL-137 | 검출 좌표 음수 clamp | PASS | [정적] 동상(하한 0 clamp) + 테스트 `경계밖_음수좌표는_0으로_clamp되어_반환된다` · `ai_응답_좌표_음수면_거부하지않고_0으로_clamp해_반환한다`. [실동작] srcSn=66 BBOX 6건 정상 반환, 400 **0건**(구 동작이면 전량 400) | ★3 확정 정책 정합 |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 | PASS | [정적] `:65-67` `Optional.empty()` + `AutolabelOnlineService.java:571-575` WARN 후 `continue` + 테스트 `clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다` · `이미지_전체_밖_박스는_해당_검출만_스킵되고_나머지는_반환된다` | 400 아님 |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp | PASS | [정적] `:71-77` `Double.MAX_VALUE` + `AutolabelOnlineService.java:556-561`(`resolve(...).orElse(null)`) + 테스트 `이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다` · `프레임_해상도_측정실패시에도_음수는_clamp되어_검출이_반환된다` | |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) | PASS | [정적] `AutolabelOnlineService.java:581-617` — 음수/비유한 거부하되 호출부가 박스별 try/catch 로 **그 박스만 스킵**(부분 성공). 비대칭 근거는 javadoc `:585-598` 에 명문화 | ★3 확정 정책 정합 — 통일 제안 없음 |
| TC-LABEL-141 | `/deid-image` 정상 200 | PASS | [실동작] WORKER/srcSn=66 → **200** `Content-Type: image/jpeg` · `Content-Length: 247266` · `X-Content-Type-Options: nosniff` · `Content-Disposition: inline; filename="frame_deid_66.jpg"`(파일명 유래 문자열 미사용 — CWE-113) | 근거 라인 드리프트(§근거 드리프트) |
| TC-LABEL-142 | `/deid-image` 원본 폴백 없음 → 404 | PASS | [실동작] srcSn=25 (`DE_IDNTF_SRC_FILE_PATH_NM=null`, `SRC_FILE_PATH_NM` 은 존재) → **404** `비식별 이미지 파일이 존재하지 않습니다`. [정적] `FrameImageLookupService.deidPathBySrcSn` 이 원본 경로를 **트랜잭션 밖으로 내보내지도 않음** | verdict BLANK/MISSING/NOT_REGULAR_FILE/REALPATH_FAILED → 404 수렴 확인 |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 | **PARTIAL** | [실동작] PORTAL_USER 토큰: `/deid-image` **403** (기대 일치) · `/image` **도 403** (기대 "통과" 불일치, 본문 `권한이 없습니다`). [정적] `@PreAuthorize` 차이는 실재(`FrameImageController:117` vs `:80`) 하나 `SecurityConfig` 의 `/v1/**` = `CHANNEL_INTERNAL ∧ (REVIEWER∨WORKER)` 가 컨트롤러 도달 전 차단 → `/image` 의 `PORTAL_USER` 허용은 **dead grant** | → **C-ISSUE-41** |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 | PASS | [실동작] 미배정 WORKER(파생 srcSn=68) → **403** / 배정 WORKER(신고 srcSn=80) → **412**. 인가→게이트→경로해석 순서 확인 | `FrameImageService.serveDeidentified` + `FrameImageLookupService:98-102` |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 | PASS | [정적] `FrameImageService.java:313-320`(판정 실경로를 `openNoFollow` 로 open, IOException → 404 fail-closed)·`:362-366`(`readAttributes`/`newInputStream` 모두 `NOFOLLOW_LINKS`) + 테스트 `FrameImageServingHardeningTest openNoFollow_대상이_심링크면_열지_않고_실패한다_TOCTOU_fail_closed` · `FrameDeidImageControllerTest H5_DeidImage_비식별경로가_원본프레임_심볼릭링크면_403` · `architecture/FileServingLinkFollowGuardTest` | 운영 스토리지에 심링크 생성은 파괴적이라 실동작 미수행 |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 | PASS | [정적] `FrameImageService.java:291-301` 이 `StorageSubtreePolicy.verifyDeidentifiedFile` **단일 판정기**에 위임(default verdict → FORBIDDEN). 서비스/컨트롤러 어디에도 재구현 없음 + 테스트 `DeidImage_비식별_서브트리_밖_경로면_403` · `FrameDeidImageSameBaseTest H4_…raw와_deid_base가_동일해도_원본프레임_서브트리는_403` | |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` | PASS | [실동작] 응답 헤더 `Cache-Control: no-store` 확인 + 테스트 `FrameDeidImageControllerTest:140` | `FrameImageService.java:327` |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` | PASS | [실동작] `Cache-Control: no-store` 확인(WORKER 200) + 테스트 `FrameImageControllerTest:122` | ⚠ 케이스 **기대결과 서술**("이 경로는 비식별 판정 없이 **원본 프레임**을 서빙") 은 현 구현과 불일치 — 이 경로는 기본 DEID 서빙이며 `FrameImageLookupService.bySrcSn` 에서 게이트를 평가한다(§근거 드리프트). 단언(`no-store`) 자체는 성립 |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` | PASS | [실동작] REVIEWER 기본·`raw=true` 양쪽 **200 + `Cache-Control: no-store`**. IDOR 가드(`VideoController:302 verifyRawAccess`) → 게이트(`FrameImageLookupService:73`) → 서빙 순서 정적 확인, 신고 영상 133 은 **412** 실측 | `no-store` 세팅 위치는 `FrameImageService.java:252`(§근거 드리프트) |

> 폐기 각주: `~~TC-LABEL-99~~`(신고 라벨 0건 스킵)는 **[폐기 2026-07-30]** 로 집계에서 제외했다. 신고 경로에 라벨 스냅샷/삭제 분기가 존재하지 않음을 `DeidentReportService.java:197-205`(설명 주석만 남고 코드 부재)로 재확인했다.

## 근거 드리프트

> 판정에는 영향이 없으나 카탈로그의 `file:line` 이 현 HEAD(`ca3c712b`)와 어긋난 항목. 원인은 07-30 커밋 `46f47cee`/`476bc91a`(파생영상 캔버스 백지 해소 · 서빙 경로 하드닝)로 `FrameImageService` 에 `serveBySrcSn`/`serveFrame` 가 삽입되면서 이후 라인이 약 +60~70 밀린 것이다.

| TC | 카탈로그 근거 | 현 HEAD 실제 | 비고 |
|---|---|---|---|
| TC-LABEL-141 | `FrameImageController.java:162-171` · `FrameImageService.java:214-269` | `FrameImageController.java:115-122` · `FrameImageService.java:283-334` | 메서드 자체는 동일(`getDeidImage`/`serveDeidentified`) |
| TC-LABEL-142 | `FrameImageService.java:223-234` | `FrameImageService.java:290-301` | verdict→404 switch |
| TC-LABEL-143 | `FrameImageController.java:164 vs :90` | `FrameImageController.java:117 vs :80` | @PreAuthorize 두 지점 |
| TC-LABEL-144 | `FrameImageService.java:214-226` | `FrameImageService.java:283-293` (+ 인가·게이트 실체는 `FrameImageLookupService.java:98-102`) | 조회/게이트가 별도 빈으로 분리됨(W3) — 근거 파일 자체가 늘어남 |
| TC-LABEL-145 | `FrameImageService.java:246-256` | `FrameImageService.java:313-320`(open) · `:362-366`(`openNoFollow` 본체) | |
| TC-LABEL-146 | `FrameImageService.java:224-233` | `FrameImageService.java:291-301` | |
| TC-LABEL-147 | `FrameImageService.java:263` | `FrameImageService.java:327` | |
| TC-LABEL-148 | `FrameImageController.java:132` | `FrameImageService.java:252` — **컨트롤러에 헤더 세팅 코드가 없다**(전량 서비스 위임). 더불어 기대결과 서술 "비식별 판정 없이 원본 프레임을 서빙" 은 폐기된 구 구현 기준 | **서술 드리프트**(단언은 유효) |
| TC-LABEL-149 | `VideoController.java:277-295` · `FrameImageService.java:186` | `VideoController.java:285-304`(277~284 는 @Operation 본문) · `FrameImageService.java:252` | |

> 위 9건 외 **TC-LABEL-74~80·90~109·110~140 의 근거 `file:line` 은 전건 정확**했다(`AutolabelOnlineService`·`DeidentReportService`·`LabelService`·`DetectionBoxNormalizer`·`DeidentReportGate`·`LabelAccessGuard`·`FrameBoundsResolver`·`AutolabelRequest`·`LabelBulkUpsertRequest` 실측 대조).

## 이슈 상세

### [C-ISSUE-41] TC-LABEL-143 — `/v1/frames/{srcSn}/image` 의 `PORTAL_USER` 허용은 도달 불가한 dead grant (케이스 기대값 "통과" 불성립)

> ⚠ 번호는 임무 지시(part3 = 41번부터)를 따른 것이며, **1차 ISSUES.md 의 `C-ISSUE-41`(YOLO 좌표 clamp — 이미 해소)과는 다른 이슈**다.

- **심각도**: LOW (보안 위험 없음 — 오히려 더 좁게 닫혀 있음. 계약·문서 정합 문제)
- **기대 동작(기대효과)**: 카탈로그 TC-LABEL-143 은 "두 형제 경로의 역할 집합이 **의도적으로 다름**" 을 검증 대상으로 삼아 `/deid-image` = 403 / `/image` = **통과**를 기대한다.
- **현재 동작**: PORTAL 채널 토큰으로 두 경로 모두 **403**(`{"errorCode":"FORBIDDEN","message":"권한이 없습니다."}`).
  - `FrameImageController.java:80` 은 실제로 `@PreAuthorize("hasAnyRole('REVIEWER','WORKER','PORTAL_USER')")` 이나,
  - `SecurityConfig.java:147-153` 이 그보다 앞서 `/v1/**` 에 대해 `CHANNEL_INTERNAL ∧ (ROLE_REVIEWER ∨ ROLE_WORKER ∨ STREAM_SIGNED)` 를 요구한다. PORTAL 토큰은 `CHANNEL_PORTAL` 이라 **컨트롤러에 도달하지 못한다**.
  - 설령 채널 검사를 통과하더라도 `LabelAccessGuard.verifyAndGet:59-71` 이 REVIEWER/WORKER 외 역할을 `FORBIDDEN` 으로 끝낸다(2중 차단).
  - 즉 `/image` 의 `PORTAL_USER` 항목은 **어떤 경로로도 실행되지 않는 dead grant** 이며, 두 형제 경로의 관측 가능한 역할 집합은 **동일**하다.
- **재현/확인 경로**:
  ```bash
  PT=$(curl -s -X POST $B/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/image      # 403
  curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $PT" $B/v1/frames/66/deid-image # 403
  ```
  테스트 자산에도 `/deid-image` 의 PORTAL 403 케이스(`FrameDeidImageControllerTest DeidImage_PORTAL_USER_채널은_내부_프레임_접근_403`)만 있고 `/image` 의 PORTAL 통과를 단언하는 테스트는 **0건**이다.
- **영향**: 보안상 위험은 없다(포털은 ADR-013 대로 `/v1/portal/**` 전용 경로를 쓰며 실제로 닫혀 있다). 다만 ①`@PreAuthorize` 가 실제 정책보다 넓게 적혀 있어 **읽는 사람이 포털 접근을 허용된 것으로 오해**할 수 있고 ②채널 격리가 나중에 완화되면 그 순간 내부 프레임이 포털에 열린다(잠재 fail-open 표면) ③카탈로그 기대값이 영구히 성립하지 않는다.
- **수정 방향(제안)**: (a) `FrameImageController:80` 의 `@PreAuthorize` 에서 `PORTAL_USER` 를 제거해 `/deid-image` 와 동일하게 맞추고 (b) TC-LABEL-143 의 기대결과를 "두 경로 모두 PORTAL 403 — 포털은 `/v1/portal/**` 전용" 으로 정정. ⚠ **구현하지 않는다.**

### [C-ISSUE-42] TC-LABEL-149 — `GET /v1/videos/{rawSn}/frames/{frameNo}/image` 의 412(신고 게이트)가 OpenAPI 에 선언되어 있지 않다

- **심각도**: LOW (문서·계약 갭. 런타임 동작은 정상)
- **기대 동작(기대효과)**: 형제 경로(`FrameImageController` 의 `/image`·`/deid-image`)와 동일하게, 비식별 누락 신고 구간에서 반환되는 **412** 가 API 문서(@ApiResponses)에 선언되어 FE·관제가 분기를 인지할 수 있어야 한다.
- **현재 동작**: `VideoController.java:279-284` 의 `@ApiResponses` 는 200/401/403/404 만 선언한다. 그러나 실제로는 신고 구간에서 **412** 를 반환한다(실측):
  ```
  GET /v1/videos/133/frames/1/image  (WORKER, rawSn 133 = DE_IDENT_YN 'F')
  → 412 {"errorCode":"PRECONDITION_FAILED","message":"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."}
  ```
  같은 게이트를 쓰는 `FrameImageController` 는 `/image`(:77)·`/deid-image`(:113) 양쪽에 412 를 선언하고 있어 **형제 경로 간 문서 규약만 어긋난 상태**다.
- **재현/확인 경로**: 위 curl. 또는 `/swagger-ui` 에서 두 컨트롤러의 응답 코드 목록 비교.
- **영향**: FE/외부 소비자가 412 를 미문서화 오류로 취급해 일반 에러 처리로 흘릴 수 있다(신고 구간 전용 안내 UI 분기 누락). 보안 영향 없음.
- **수정 방향(제안)**: `VideoController.getFrameImage` 의 `@ApiResponses` 에 412 항목 추가(문구는 `FrameImageController:77` 과 동일하게). 부수적으로 같은 블록의 404 설명("비식별 미완료")도 412 와 구분되도록 정리. ⚠ **구현하지 않는다.**

---

## 검증 중 개입 및 원복 (투명성)

> 실동작 검증을 위해 만든 변경은 전부 **본 검증이 만든 데이터에 한정**되며 종료 시점에 원복했다. rawSn 126·133 은 임무 제약대로 보호했다.

| # | 대상 | 무엇을 | 왜 | 원복 |
|---|---|---|---|---|
| 1 | srcSn 77 (rawSn 132, 라벨 0건 프레임) | 라벨 저장/수정 API 로 라벨 생성·수정, 일부 좌표·`LBL_ID` 를 DB 로 조작(레거시 out-of-bounds·비활성 마스터 참조 상황 재현) | TC-110~120 은 "기존 라벨이 이미 경계 밖" 등 **정상 API 로는 만들 수 없는 상태**가 전제 | `PUT {"items":[]}` 로 전량 삭제 → 라벨 **0건**, `LS_DATA_LBL_HSTRY` 삭제, `LBL_VER=0` 복원 |
| 2 | srcSn 25 (rawSn 20001 시드) | 큰 좌표 라벨 1건 생성 | TC-117(치수 측정 실패 프레임) | 동상 — 라벨 0건 복원 |
| 3 | rawSn 20029 / srcSn 53 (시드) | 개인정보 3필드 `Y/N/Y` 세팅 후 **실제 신고 접수(201)** | TC-90/97/125/134 는 신고 정상 경로가 필요. 126·132·133 을 쓸 수 없어 시드 영상 사용 | 신고행·작업락·이력 삭제, `DE_IDENT_YN='Y'` 복원, 라벨·`LBL_VER` 복원 — **완전 원복 확인** |
| 4 | rawSn 20030 | `DE_IDENT_YN` `'Y'→'N'` 일시 | TC-129(비식별 미수행 412)는 `'N'` 영상이 DB 에 하나도 없어 재현 불가 | 즉시 `'Y'` 복원 |
| 5 | rawSn 132 | `DE_IDENT_YN` `'Y'→'F'` 일시(수 초) | TC-135 의 "락 없이 `'F'`" 분기(412)는 락 없는 `'F'` 영상이 없어 재현 불가 | 즉시 `'Y'` 복원 |

**개입하지 않은 것(명시)**: rawSn **126**(라벨 22건·export·버전스냅샷 무접촉 — 온라인 오토라벨은 미저장 경로라 srcSn 66 라벨 9건 **불변** 확인), rawSn **133**(신고 rprtSn=3 `OPEN` · `DE_IDENT_YN='F'` · 라벨 28건 유지), `LS_LABEL` 마스터(비활성 라벨 `lbl_id=37` 은 **다른 에이전트가 만든 것**으로 읽기만 함), 파생영상 129/130/131, `LS_DATASET_EXPORT`·`LS_LABEL_VERSION`·`LS_CONTROL_NOTIFY_FALLBACK`.

## 부기 — 다른 에이전트와의 동시 실행 관측

- 검증 중 rawSn **134~155**(`DEV-CLIP-92xx/95xx`)와 신고 rprtSn **4·5**(rawSn 144, RESOLVED)가 다른 담당의 드라이브로 생성돼 있었다. TC-LABEL-106/107 은 이 rawSn 144 산출물을 **DB 로 사후 확인**한 것이며 본 검증이 만든 것이 아니다(성공 resolve 를 직접 수행하면 rawSn 133 의 OPEN 보존 제약과 충돌하므로 의도적으로 회피).
- `LS_LABEL` 에 `lbl_id=37 (ZZTEST-C2, USE_YN='N')` 가 존재해 TC-119/120 의 비활성 마스터 픽스처로 **읽기만** 사용했다.
