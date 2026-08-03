# C 클러스터 part4 — TC-LABEL-106 ~ TC-LABEL-149 (라벨 저장/좌표검증 + 비식별 신고·resolve + 프레임 이미지 서빙)

- 대상: `docs/test-cases/C-marking-labeling.md` L167~210 (44건, 폐기 0건)
- 검증일: 2026-08-01 / 1차
- 환경: backend `localhost:18081/api`(컨테이너 klid-backend, Flyway **V158** 적용 확인) · DB `public` 스키마 · mock-server 9400 · ai-server 19300
- 판정 근거: 대부분 **실동작(curl + DB + 컨테이너 로그)**. ai-server 가 `weights_missing` mock 폴백 상태라 **YOLO 검출 0건** → TC-136~140(검출 좌표 clamp)만 정적 대조.
- **확정 정책 준수**: ★1(신고 게이트 = 자기 rawSn 행 하나 / 파생 경유 열람은 결함 아님) · ★3(사용자 저장=400 거부 / AI 검출=clamp)은 결함으로 재보고하지 않았다.
- 테스트 데이터: 신규 raw(56·57)·src·label 을 INSERT 해 사용하고 **검증 종료 후 전량 삭제·원상복구**(raw 26/27/34 = `de_ident_yn='Y'`, `DATA_STTS_CD='COMPLETED'`, 신고 전량 RESOLVED, 심링크 제거). 프로덕션/테스트/설정 파일은 **일절 수정하지 않음**.

## 판정 요약

| 판정 | 건수 |
|---|---:|
| PASS | 43 |
| PARTIAL | 1 (TC-LABEL-143) |
| FAIL / BLOCKED / N-A / 확인필요 | 0 |

부수 발견(케이스 범위 밖 인접 결함) 1건: `GET /v1/deident-reports` 정렬 키 미검증 → **500** (C-ISSUE-61).

---

## 결과표

| ID | 판정 | 근거 확인 |
|---|:--:|---|
| TC-LABEL-106 | PASS | [실동작] rprtSn=15/16/17/18 전건 `POST /v1/deident-reports/{n}/resolve` → 200, `report_stts_cd=RESOLVED`+`resolved_dt` 기록, `ls_auth_work_lock` → `RELEASED/MANUAL_DEIDENT_DONE`, `ls_data_raw.de_ident_yn` `F`→`Y`. 산출물 미교체 상태 선행 호출은 **409 + 전부 롤백(OPEN·LOCKED·`F` 유지)** = fail-closed 확인. `DeidentReportService.java:353-402` |
| TC-LABEL-107 | PASS | [실동작] rawSn 27·34(배치 `COMPLETED`)·26(`COMPLETED`+검수 `APPROVED`) 3건 모두 report→resolve 후 `ls_data_raw.data_stts_cd=COMPLETED` 불변, `ls_raw_data_status.data_stts_cd` 도 불변(27=ASSIGNED). CWE-664 역행 없음. `:384-391` |
| TC-LABEL-108 | PASS | [실동작] `?status=X` → 400 `list.status: status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다.` 소문자 `resolved`·`OPEN%20`(공백)·중복 파라미터(`status=OPEN&status=X`, 역순 모두) 전부 400 — 컨트롤러 `@Pattern`(`DeidentReportController.java:74-75`)이 선행해 서비스 `toUpperCase` 관용 경로 도달 불가(카탈로그 주석과 일치). `:426-438` |
| TC-LABEL-109 | PASS | [실동작] status 미지정 → OPEN 3건(rprtSn 14·12·2)만 반환, `sort=reportDt DESC`. DB `report_stts_cd='OPEN'` 집합과 정확히 일치. ⚠ `?status=`(빈 문자열)은 서비스 blank→OPEN 관용이 아니라 `@Pattern` 400 — 실사용 영향 없음(미지정과 다른 경로) |
| TC-LABEL-110 | PASS | [실동작] src 305 저장(labelVersion 0→1) 후 다시 `labelVersion=0` 전송 → **409** "다른 사용자가 먼저 저장했습니다…", 라벨 미변경(재조회 시 직전 세트 그대로) |
| TC-LABEL-111 | PASS | [실동작] `labelVersion` 필드 없이 PUT → 200 저장, 버전 1→2 정상 증가(하위호환) |
| TC-LABEL-112 | PASS | [실동작] 동일 세트(id·좌표 동일) 재전송 → 200, 응답 `labelVersion` 1 유지(bump 없음) |
| TC-LABEL-113 | PASS | [실동작] `points=[[999999,888888],…]` → **400** `좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=320x240)` — 클램프 아님 |
| TC-LABEL-114 | PASS | [실동작] 실제 프레임 320x240 기준 경계값 `[[318,238],[320,240]]` → 200 저장 |
| TC-LABEL-115 | PASS | [실동작] DB 직접 주입한 경계 밖 레거시 라벨(`[[5000,5000],[5001,5001]]`)을 **좌표 그대로** 포함해 프레임 전체 재저장 → 200(프레임 영구 차단 회귀 없음), 라벨 3건 보존 |
| TC-LABEL-116 | PASS | [실동작] 기존 id 라벨의 좌표를 `[[318,238],[5000,5000]]` 로 이동 → 400(동일 메시지, 이미지=320x240) |
| TC-LABEL-117 | PASS | [실동작] 이미지 파일이 없는 프레임에 `[[99999,99999],…]` 저장 → **200**, 로그 `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=332`, 메트릭 `/actuator/metrics/label.bounds.skipped` COUNT=1.0 · tag `reason=unresolved` |
| TC-LABEL-118 | PASS | [실동작] SKELETON 17키포인트 중 첫 키포인트 `[0,0,0]`(v=0 자리표시자) 포함 → 200. (키포인트 개수 16개면 400 "정확히 17 개" — 개수 검증은 별도 유지) |
| TC-LABEL-119 | PASS | [실동작] 비활성 마스터(`ls_label.lbl_id=10, use_yn='N'`) 참조 라벨을 id+같은 labelId 로 재전송하면서 **다른 라벨도 수정** → 200(프레임 전체 409 차단 없음). 조회 시 해당 라벨은 `labelName='c2a-truck-x'` 로 표시만 됨 |
| TC-LABEL-120 | PASS | [실동작] 기존 라벨(id=605)의 labelId 를 비활성 10 으로 변경 → **409** "사용 중지된 라벨입니다: labelId=10". 신규 라벨에 부여해도 동일 409(“id 붙이면 통과” 우회 없음) |
| TC-LABEL-121 | PASS | [실동작] rawSn 27 신고(OPEN) 상태에서 `GET /v1/frames/301/labels` → **412** — **WORKER·REVIEWER 동일**. 미배정 WORKER 는 그 이전에 403(인가 선행). `LabelService.java:193` · `LabelAccessGuard.java:135-141` |
| TC-LABEL-122 | PASS | [실동작] `GET /v1/frames/301/label-history` → 412(동일 메시지). resolve 후 200 복귀. `LabelService.java:482` |
| TC-LABEL-123 | PASS | [실동작] 부모 raw 27=`F` 구간에서 파생본(raw 57, `ORGNL_RAW_SN=27`, `de_ident_yn='Y'`)의 프레임 라벨 조회 → **200**(+ `/deid-image` 200). 조상 체인 미추적 확인 = ★1 확정 정책의 귀결이며 결함 보고 대상 아님. `DeidentReportGate.java:23-47,:66-71` |
| TC-LABEL-124 | PASS | [실동작] 신고 전후 `ls_data_lbl` 4건 불변, `ls_label_version` where data_raw_sn=27 **0행 유지**, `ls_data_src.lbl_ver` 불변(301=1). 로그 `labelsPreserved=true` |
| TC-LABEL-125 | PASS | [실동작] 개인정보 메타 보유 프레임 2건(301·302)에서 신고 → 로그 `privacyResetAudited=2`, `ls_data_lbl_hstry` 에 프레임당 1행 `{"event":"PRIVACY_META_RESET","deidentReportSn":16,"changes":[]}` (actor `reg_id=2001`, 시각 기록, add/mdfcn/del=0). `V_COMPLETED_LABEL_CHANGE` 정의에 `WHERE (add_cnt+mdfcn_cnt+del_cnt) > 0` 확인 → 미노출. ※ 최초 관측(23:18)에서 `audited=0` 이 나온 것은 **동시 실행 중이던 다른 검증 에이전트가 같은 프레임의 개인정보 메타를 이미 비운 상태**였기 때문이며, 통제 재현(23:21)에서 정상 동작 확인 |
| TC-LABEL-126 | PASS | [실동작] resolve 후 `GET /v1/frames/301/labels` 200, 라벨 id·타입·좌표·autoLblYn·trckId 가 신고 **이전 응답과 완전 동일**(스크립트 비교 True), labelVersion 도 1 그대로. 별도 복원 API 없음 |
| TC-LABEL-127 | PASS | [실동작] `POST /v1/videos/34(·26)/deident-report` → **201**. 부수효과 srcSn 경로와 동일: 신고행 OPEN + `ls_auth_work_lock` LOCKED + `de_ident_yn='F'` + 개인정보 3필드 리셋 + 스트림 캐시 evict + REVIEWER 알림 로그. APPROVED 영상(26)에서 TASK_MODIFIED 축적 후 export 성공 시점에 발송 확인 |
| TC-LABEL-128 | PASS | [실동작] 파생 raw 18(WORKER 배정자)·25(REVIEWER) → **412**. 응답 메시지에 **부모 rawSn 미노출**(“원본 영상의 비식별 결과를 복사해 만든 파생영상…”), `ls_deident_report` 신규 행 0건, REVIEWER 알림 로그 없음, 사유는 WARN 감사로그에 **개행 제거된 한 줄**로 기록(`reason=C-part4 파생 신고 시도두번째줄 injection`, CWE-117 방어). `:183,:295-306` |
| TC-LABEL-129 | PASS | [실동작] `de_ident_yn='N'` 영상(raw 56) → **412** "아직 비식별 처리가 완료되지 않은 영상입니다", `'N'→'F'` 전이 없음(DB 확인). 이미 `'F'` 인 raw 40 은 게이트 통과 후 기존 **409**(이미 재비식별 중) 경로 유지. `:186,:329-337` |
| TC-LABEL-130 | PASS | [실동작] WORKER 미배정 rawSn 56 → **403** "본인에게 배정되지 않은 영상입니다"(412 아님 = 인가 선행). 존재하지 않는 rawSn 999999 도 WORKER 는 403 / REVIEWER 만 404 → 미인가자에게 존재 여부 미노출. `:151` · `LabelAccessGuard.java:83-100` |
| TC-LABEL-131 | PASS | [실동작] `POST /v1/labels/{srcSn}/deident-report` 에서 파생 프레임(src 45) → 412, 비식별 미수행 프레임(src 332) → 412 — rawSn 경로와 동일 문구·동일 코드(`doReport` 수렴). `:125,:169-186` |
| TC-LABEL-132 | PASS | [실동작] 미승인(ASSIGNED) raw 27 resolve 시 로그 `[VlmResumeBridge] deident gate reopened rawSn=27 — checking withheld VLM submit` 발화 = 승인 여부 무관 발행. `:487-495` |
| TC-LABEL-133 | PASS | [실동작] 같은 resolve 에서 raw 27 은 export 재산출 로그 **없음**. APPROVED raw 26 resolve 시에는 `[DatasetExportBridge] deident report resolved rawSn=26 — re-triggering withheld export/notify` → export v4 성공 → mock-server `POST /api/data-set/v2/jobs/26/notify-updated 202`(통지는 export 성공 후). `:492-494` |
| TC-LABEL-134 | PASS | [실동작] 신고 시 `[StreamMetaCache] evicted rawSn=27`, resolve 시 재차 evict. 로그상 파생(raw 57) 캐시 evict 없음 = 대상 1건. `:248,:395` |
| TC-LABEL-135 | PASS | [실동작] `'F'`+LOCKED(raw 40) → **409** "작업이 잠긴 영상입니다" / `'F'`+락없음(raw 32) → **412** "비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다" — 작업락 판정 선행 확인. `AutolabelOnlineService.java:411-421` |
| TC-LABEL-136 | PASS | [정적] `DetectionBoxNormalizer.java:59-68` `clamp(v,max)=max(0, min(max,v))` 로 상한 clamp 후 반환(예외 없음). 실동작은 ai-server `weights_missing` mock 폴백으로 검출 0건이라 불가(환경 갭 — baseline 기록됨) |
| TC-LABEL-137 | PASS | [정적] 동일 `clamp` 의 `Math.max(0.0, …)` 로 음수 → 0. 호출부 `AutolabelOnlineService.java:256` 은 형식 위반만 400 으로 승격, 음수는 400 아님 |
| TC-LABEL-138 | PASS | [정적] `:65-67` clamp 후 `x2<=x1||y2<=y1` → `Optional.empty()`, 호출부 `:570-576` 이 해당 검출만 `continue` + WARN(`detection dropped — box degenerate after clamp`), 나머지는 반환 |
| TC-LABEL-139 | PASS | [정적] `:71-77 upperBound()` — bounds null·길이≠2·0 이하면 `Double.MAX_VALUE`(상한 없음), 하한 0 clamp 만 적용. 호출부는 `frameBoundsResolver.resolve(src).orElse(null)` 로 측정 실패를 그대로 전달 |
| TC-LABEL-140 | PASS | [정적] `validatePolygonPoints`(`:580-620`)는 음수·비유한 좌표를 **거부**하며 clamp 하지 않음, 폴리곤 경로는 박스별 try/catch 부분 스킵. BBOX=clamp / SAM 폴리곤=거부 비대칭은 주석에 근거 명시(★3 정합) |
| TC-LABEL-141 | PASS | [실동작] `GET /v1/frames/301/deid-image` → 200 `Content-Type: image/jpeg`, `Content-Length: 13164`, `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_deid_301.jpg"`(= srcSn+MIME 파생 확장자만, 파일명 유래 문자열 없음) |
| TC-LABEL-142 | PASS | [실동작] `DE_IDNTF_SRC_FILE_PATH_NM` null 이고 `SRC_FILE_PATH_NM` 은 채워진 프레임 → **404** "비식별 이미지 파일이 존재하지 않습니다"(원본 미서빙), 로그 `verdict=BLANK`. base 밖·존재하지 않는 경로도 각각 403/404 로 수렴 |
| TC-LABEL-143 | PARTIAL | [실동작] `/deid-image` + PORTAL_USER → **403** (기대 일치). 그러나 형제 경로 `/image` 도 **403** — `@PreAuthorize` 에는 PORTAL_USER 가 있으나(`FrameImageController.java:80`) 그 앞단 `SecurityConfig` 채널 격리(`/v1/**` = INTERNAL 채널 + REVIEWER/WORKER 역할)가 먼저 차단한다. 즉 "역할 집합이 다르다"는 애노테이션 계층에서만 참이고 **엔드투엔드로는 둘 다 403** → 기대결과 문구가 부정확(제품은 더 엄격, 보안 결함 아님). C-ISSUE-62 |
| TC-LABEL-144 | PASS | [실동작] 신고(OPEN) 중 raw 34 프레임: 미배정 WORKER → **403**(신고 전과 동일 응답 = 존재 탐색 불가), REVIEWER → **412**. 없는 srcSn → 404. 순서 ①인가 ②게이트 ③경로해석 확인 |
| TC-LABEL-145 | PASS | [실동작+정적] `frames/deid/27/qa-c4-link.jpg → /app/storage/raw/frames/raw/27/frame-0.jpg` 심링크를 DB 경로로 지정 → **403** `verdict=OUTSIDE_DEID_SUBTREE`(realpath 기준 판정). 판정~open 사이 교체(정확한 TOCTOU 창)는 재현 불가하나 `FrameImageService.openNoFollow`(`:362-366`)가 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 열고 실패 시 404 로 마감(fail-closed) |
| TC-LABEL-146 | PASS | [실동작] deid base 밖 절대경로(`/etc/hostname`) 및 `..` 경로순회 모두 **403** "허용되지 않은 이미지 경로입니다", 로그 `verdict=OUTSIDE_BASE`. 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 판정기 위임(서비스 재구현 없음) |
| TC-LABEL-147 | PASS | [실동작] `/deid-image` 200 응답 헤더 `Cache-Control: no-store` |
| TC-LABEL-148 | PASS | [실동작] `/v1/frames/301/image` 200 응답 헤더 `Cache-Control: no-store` |
| TC-LABEL-149 | PASS | [실동작] `/v1/videos/27/frames/0/image` 200(REVIEWER) 응답 헤더 `Cache-Control: no-store` + `nosniff` + `filename="frame_27_0.jpg"` |

### 테스트 자산 대조 (`_raw/test-baseline.md`: backend 4,755 tests / 실패 0)

| 영역 | 커버 테스트 |
|---|---|
| 신고·resolve 본체 | `label/service/DeidentReportServiceTest.java`(46건 — 파생 412·비식별 미수행 412·rawSn 진입점 5건·resolve 산출물 검증 8건·상태 역행 금지·이벤트 발행 2건 포함) |
| 라벨 보존/게이트 | `DeidentReportLabelPreservationIT`(3) · `DeidentReportGateCoverageIT`(8) · `DeidentReportServiceResetIT`(2, 개인정보 리셋 감사) |
| 게이트 판정 단일화 | `video/service/DeidentReportGateTest` · `AiInferenceDeidentReportGateTest` · `DatasetExportDeidentReportGateIT` · `DeidentReportStreamGateIT` |
| 프레임 이미지 | `label/FrameImageControllerTest` · `FrameImageSrcSnPolicyTest` · `video/FrameImageRawFrameNoTest` · `portal/PortalFrameImageCacheControlTest` |
| 오토라벨 좌표 | `label/AutolabelOnlineServiceTest` · `AutolabelPolygonServiceTest` |

---

## 이슈

### [C-ISSUE-61] TC-LABEL-108/109 (인접) — `GET /v1/deident-reports` 정렬 키 미검증 → 미등록 키에 500 + 엔티티 필드 열거 오라클
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 목록 API 의 정렬 키는 **allowlist 매핑으로만** 해석하고 개수 상한을 둔다(CLAUDE.md "목록 화면 정렬·필터 정책", CWE-89/CWE-770). 미등록 키는 그 엔드포인트의 확정 규약(strict 400 또는 lenient 200 폴백) 중 하나로 끝나야 하며, **사용자 입력으로 500 이 나면 안 된다**(오류 모니터링 오염 + 가용성).
- **현재 동작(이슈 내용)**: `DeidentReportController.java:76` 이 `@PageableDefault(...) Pageable` 을 **그대로** `DeidentReportService.listReports`(`:417-423`) → `reportRepository.findByReportSttsCd(normalized, pageable)` 로 넘겨, Spring Data 가 엔티티 프로퍼티를 직접 해석한다. 미등록 키면 `PropertyReferenceException` 이 `GlobalExceptionHandler` 의 unhandled 분기로 떨어져 **500**.
  ```
  GET /v1/deident-reports?sort=nosuchfield,desc   → 500 INTERNAL_ERROR
  GET /v1/deident-reports?sort=rawSn,desc         → 500  (엔티티 필드명은 dataRawSn)
  GET /v1/deident-reports?sort=rsn,desc           → 200  (신고 사유 컬럼으로 정렬됨)
  GET /v1/deident-reports?sort=reportSttsCd,asc   → 200
  로그: PropertyReferenceException: No property 'rsn;drop' found for type 'LsDeidentReport'
  ```
  200/500 차이가 **엔티티 내부 필드명 존재 여부를 알려주는 오라클**이 된다(CWE-209 계열). 응답 본문 자체에는 스택트레이스가 없어 유출은 없다.
  ※ ★2(작업목록 strict 400 vs 검수목록 lenient 200)는 `/v1/tasks/board*` 와 `/v1/reviews*`·`/v1/videos` 만 정의한다. 이 엔드포인트는 **두 정책 어느 쪽도 적용되지 않은 세 번째 목록 API** 이므로 "비일관성 재보고"가 아니라 **미배선 갭**이다.
- **재현/확인 경로**: 위 curl 4줄(REVIEWER 토큰). `size=100000` 은 100 으로 캡됨(=DoS 방어는 정상).
- **영향**: 사용자 입력으로 5xx 발생(가용성·모니터링 노이즈), 정렬 키를 통한 엔티티 스키마 열거, 정렬 대상이 allowlist 밖(예: 신고 사유 텍스트 `rsn`)까지 열려 인덱스 없는 컬럼 정렬 유발 가능.
- **수정 방향(제안)**: 다른 목록 API 와 동일하게 `sort` 를 **allowlist 매핑**(`reportDt`/`status`/`rawSn` → 엔티티 프로퍼티)으로 변환하는 정규화를 컨트롤러/서비스 경계에 추가하고, 이 엔드포인트는 **변경 전에도 `sort` 를 받아 500 이었던** 축이므로 ★2 기준상 **strict 400** 이 자연스럽다(검수목록형 lenient 폴백을 택할 경우 그 근거를 UNCERTAINTIES 에 명시). 추가로 `PropertyReferenceException` 을 `GlobalExceptionHandler` 에서 400 으로 매핑해 전역 fail-safe 를 둔다.

### [C-ISSUE-62] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 엔드투엔드로 성립하지 않음(카탈로그 정정)
- **심각도**: LOW (제품 결함 아님 — 실제 동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: `/deid-image` 403(기대 일치)이지만 `/image` 도 **403**. PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig`(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL` + ROLE_REVIEWER|WORKER|STREAM_SIGNED)에서 `@PreAuthorize` 도달 전에 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현재 배선상 **도달 불가 권한**이다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"role":"PORTAL_USER","channel":"PORTAL"}` 발급 후 `GET /v1/frames/301/image` → 403 / `GET /v1/frames/301/deid-image` → 403.
- **영향**: 없음(보안상 더 강함). 다만 카탈로그 기대값이 틀려 다음 회차에 위양성 FAIL 을 유발할 수 있고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ① 카탈로그 TC-LABEL-143 기대결과를 "`/deid-image`=403 / `/image`=403(채널 격리 선행). 역할 집합 차이는 애노테이션 계층 정책" 으로 정정 ② 코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님).

### [C-ISSUE-63] TC-LABEL-141~149 — 근거 `file:line` 드리프트(카탈로그 정합성)
- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: 프레임 이미지 서빙 계열 근거가 전부 어긋난다.
  | TC | 카탈로그 근거 | 실제 위치 |
  |---|---|---|
  | 141 | `FrameImageController.java:162-171` · `FrameImageService.java:214-269` | `FrameImageController.java:115-122` · `FrameImageService.java:283-334` |
  | 142 | `FrameImageService.java:223-234` | `FrameImageService.java:290-301` |
  | 143 | `FrameImageController.java:164 vs :90` | `:117 vs :80` |
  | 144 | `FrameImageService.java:214-226` | `:283-301`(+ 인가·게이트는 `FrameImageLookupService`) |
  | 145 | `FrameImageService.java:246-256` | `:313-320` + `openNoFollow :362-366` |
  | 146 | `FrameImageService.java:224-233` | `:291-301` |
  | 147 | `FrameImageService.java:263` | `:327` |
  | 148 | `FrameImageController.java:132` | 파일이 127줄로 **존재하지 않는 라인** — 실제는 `FrameImageService.java:252`(`serveFrame` 공용) |
  | 149 | `VideoController.java:277-295`(✅ 유효) · `FrameImageService.java:186` | no-store 는 `FrameImageService.java:252` |
  (TC-106~140 의 근거는 전건 유효 — `DeidentReportService`·`LabelAccessGuard`·`LabelService`·`DetectionBoxNormalizer` 라인 일치, `AutolabelOnlineService` 는 ±5줄 이내)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: 카탈로그 근거 갱신(위 표 그대로 치환).

### [C-ISSUE-64] TC-LABEL-125 (인접) — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아님
- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 서로 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `resetPrivacyMetaByRawSn`(`LsDataSrcRepository.java:198-201`)은 `where s.rawSn = :rawSn` 만으로 **영상 전 프레임을 무조건 update** 하므로 반환값이 곧 "프레임 총수"다. 실측:
  ```
  [DeidentReport] created rprtSn=15 rawSn=27 ... privacyReset=5 privacyResetAudited=0
  [DeidentReport] created rprtSn=16 rawSn=27 ... privacyReset=5 privacyResetAudited=2
  ```
  같은 영상에서 값 보유 프레임이 0건일 때도 2건일 때도 `privacyReset=5` 로 동일하다.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "5건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음.
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 로그 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

---

## 검증 중 발견한 환경/데이터 사실 (참고)

1. **ai-server 가중치 미탑재** — `[Batch][YOLO] mock response detected … mockReason=weights_missing` 로 온라인 오토라벨도 `detectedCount=0` + `message="AI 모델 미로드 — 결과 신뢰 불가"`. TC-136~140 의 실동작 판정을 막는 유일한 환경 갭(코드 결함 아님, baseline 기록과 일치).
2. **동시 검증 간섭** — 같은 시간대에 다른 C 파트 에이전트가 raw 26/27 프레임 라벨을 편집 중이어서, 신고 접수 시점 스냅샷이 흔들릴 수 있다(TC-125 최초 관측이 그 사례). 신고는 대상 영상을 잠그므로 **동시 검증 시 서로의 저장이 409 로 막힐 수 있음** — 다음 회차에는 파트별 전용 rawSn 을 배정하는 편이 안전하다.
3. **DB 컬럼명 주의** — 카탈로그·CLAUDE.md 표기는 `DE_IDNTF_YN` 이지만 실제 테이블 컬럼은 `ls_data_raw.de_ident_yn` 이다(프레임 쪽은 `de_idntf_src_file_path_nm` 으로 표기가 다름). SQL 로 검증할 때 혼동 주의.
