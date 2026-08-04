# C 클러스터 part4 — C-2. TC-LABEL 뒷부분 (파일 169~212행 = TC-LABEL-106 ~ 149, 44건)

> 담당 범위: `docs/test-cases/C-marking-labeling.md` **169~222행**. 실제 케이스 행은 **169~212행(TC-LABEL-106~149, 44건)**
> 이고 213~222행은 `no-store 5경로` 주석 블록 + 구분선 + `## C-3` 헤딩이다(케이스 아님).
> 검증일 2026-08-04 KST(3차) · 스택: `_raw/stack-bringup.md` 기준 재빌드된 HEAD 이미지(Flyway v163) · 전 케이스 실동작 우선.

## 사용한 검증 데이터 (다른 파트와 분리 확보)

| 용도 | rawSn | srcSn | 상태 |
|---|---|---|---|
| 신고/해소 주 시나리오 | **115** | 508·509·510 | `Y`→신고→`F`→resolve→`Y`, WORKER 2001 배정(assignment 76), 워크플로 `ASSIGNED` |
| rawSn 진입점 신고(TC-127) | **112** | 507 | `Y`→신고→`F`→resolve→`Y` (복구 완료) |
| 파생영상 412(TC-128/131) | **88**(부모 900) · **98**(부모 906) | 439 · 466 | `ORGNL_RAW_SN` non-null |
| 비식별 미수행 412(TC-129/131) | **901** | 434 | `DE_IDNTF_YN='N'` |
| 게이트 자기행 판정(TC-123) | **88**(부모 900=`'F'`) | 439 | 파생은 `'Y'` |
| bounds 미상(TC-117)·서브트리(TC-145/146) | **110** | 499·502 | 검증 후 원상복구 |
| 락 없는 `'F'`(TC-135b) | **110** | 499 | SQL 로 `'F'` 세팅 후 원복 |

> 검증용 DB 데이터 조작(개인정보 3필드 세팅, 프레임 경로 임시 변경, `de_ident_yn` 임시 변경)과 심링크 생성은
> **모두 원상복구**했다. 프로덕션 코드·설정·마이그레이션은 일절 수정하지 않았다.
> ⚠ 검증 중 생성한 신고 32(rawSn 115)·35(rawSn 112)는 정상 resolve 로 종결했고, rawSn 900 에 잘못 접수된 신고 34 는
> 원상태(`'F'` + 락 없음 + 신고 없음)로 복구했다.

---

## 판정 결과

| ID | 케이스명 | 판정 | 근거 확인 |
|---|---|:--:|---|
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | PASS | [실동작] `POST /v1/deident-reports/32/resolve`(WORKER 2001) → 200. DB: `ls_data_raw.de_ident_yn='F'→'Y'`, `ls_deident_report.report_stts_cd='RESOLVED'`, `resolved_dt=01:04:45.707`, 락 `work_lock_sn=36 → RELEASED/MANUAL_DEIDENT_DONE`. 로그 `[DeidentReport] resolved-manually rprtSn=32 rawSn=115 actor=2001`. ★근거 드리프트: `:374-391` → 실제 `:402-419` |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | PASS | [실동작] resolve 전후 `ls_data_raw.data_stts_cd` **COMPLETED 불변**(01:02 신고→01:04 resolve 양쪽 조회). 코드 `DeidentReportService.java:412-419` 주석·구현이 `markDeidentified("Y")` 만 수행하고 `DATA_STTS_CD` 미변경. ★드리프트 `:384-391`→`:412-419` |
| TC-LABEL-108 | listReports status allowlist | PASS | [실동작] `?status=X` → **400** `"list.status: status 는 OPEN/RESOLVED/DISMISSED 만 허용됩니다."`. `?status=resolved`(소문자)도 **400**(컨트롤러 `@Pattern` 선행 — 서비스 `toUpperCase` 관용은 도달 불가, 카탈로그 ※ 서술 그대로). WORKER 접근 403. ★드리프트 `:426-438`→`:454-466` |
| TC-LABEL-109 | listReports 기본 OPEN | PASS | [실동작] `GET /v1/deident-reports`(파라미터 없음) → 200, `totalElements=5`, `content[].status` 집합 = `{OPEN}` 단일. ★드리프트 `:427-429`→`:455-457` |
| TC-LABEL-110 | labelVersion 불일치 409 | PASS | [실동작] 현재 버전 4 인 srcSn 508 에 `labelVersion:0` 전송 → **409** `"다른 사용자가 먼저 저장했습니다. 최신 라벨을 불러온 뒤 다시 저장하세요."`, 라벨 미변경(후속 GET 동일). 근거 `LabelService.java:457-465` 유효 |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip | PASS | [실동작] 같은 stale 상황에서 `labelVersion` 필드 자체를 빼고 전송 → **200** 저장, 응답 `labelVersion 3→4`. 근거 `LabelService.java:458` 유효 / `LabelBulkUpsertRequest.java` 는 `:24-31`(카탈로그 `:26-32` 미세 드리프트) |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 | PASS | [실동작] srcSn 508 동일 세트 재전송 → 200, 응답 `labelVersion` **3 그대로**(bump 없음). 근거 `LabelService.java:400-406` 유효 |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 | PASS | [실동작] `points=[[999999,888888],…]` (id 없음) → **400** `"좌표가 이미지 경계를 벗어났습니다 (x=999999.0, y=888888.0, 이미지=320x240)"` — 클램프 아님. ⚠ **단, 이 방어는 우회 가능**(→ C-ISSUE-61) |
| TC-LABEL-114 | 경계값 x==width 허용 | PASS | [실동작] 320x240 프레임에 `[[0,0],[320,240]]` 신규 저장 → **200**(labelVersion 4→5) |
| TC-LABEL-115 | 레거시 out-of-bounds 라벨 무변경 재저장 허용 | PASS | [실동작] DB 로 lbl 741 을 `[[5000,4000],[6000,5000]]`(경계 밖)로 만든 뒤 동일 좌표 재전송 → **200**. 같은 요청에 정상 신규 라벨을 추가해도 **200**(프레임 전체 차단 회귀 없음). 근거 `:330-335` 유효 |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 | PASS | [실동작] 같은 라벨 좌표를 `[[5001,4000],…]`(1픽셀만 변경)으로 전송 → **400**(pointsEqual 불일치 시 상한 강제). 근거 `:333-335` 유효. ⚠ C-ISSUE-61 우회는 별도 |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip | PASS | [실동작] srcSn 502 의 원본·비식별 경로를 모두 존재하지 않는 파일로 바꾼 뒤 `[[99999,88888],…]` 저장 → **200**. WARN `[Label] frame image path unresolved — coordinate upper-bound check skipped srcSn=502 cause=CustomException`. 메트릭 `GET /actuator/metrics/label.bounds.skipped` → `COUNT=2`, `tag reason=[unresolved]`. **하한은 유지** — 같은 프레임에 `[[-5,-5],…]` → 400 `"좌표는 0 이상이어야 합니다"` |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 | PASS | [실동작] 대조 실험. `[99999,99999,**0**]` 포함 17점 → **200** / 같은 좌표에 가시성만 `2` 로 바꾼 `[99999,99999,**2**]` → **400** `"…이미지=320x240"`. 근거 `:764-767, :777-780` 유효 |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 | PASS | [실동작] lbl 741 의 `lbl_id` 를 비활성 마스터 **10**(`c2a-truck-x`, USE_YN='N')으로 세팅 후, 같은 labelId 재전송 + **같은 프레임의 다른 라벨(1248) 좌표 수정** → **200**(labelVersion 8→9). 프레임 전체 409 영구차단 회귀 없음. 근거 `:685-695` 유효 |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 | PASS | [실동작] ①기존 라벨(id=741)의 labelId 를 비활성 **11** 로 변경 → **409** `"사용 중지된 라벨입니다: labelId=11"` ②id 없는 신규 라벨에 비활성 **10** 부여 → **409**. "id 붙이면 통과" 우회 차단됨. 근거 `:674-677, :685-695` 유효 |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 | PASS | [실동작] rawSn 115 신고 접수 후 `GET /v1/frames/508/labels` — WORKER(배정) **412**, **REVIEWER 도 412**(역할 무관) `"비식별 재처리 대기 중인 영상입니다…"`. 인가 선행 확인: 미배정 WORKER(2002) 는 **403**. 근거 `LabelService.java:193` 유효 / `LabelAccessGuard.java:135-141`→**실제 `:139-146`** |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 | PASS | [실동작] `GET /v1/frames/508/label-history` → **412** 동일 메시지. 좌표 전문(chgDtlCn) 우회 창 차단 확인. 근거 `LabelService.java:482` 유효 |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 (구속) | PASS | [실동작] 부모 900=`'F'`, 파생 88=`'Y'` 상태에서 ①파생 프레임 `GET /v1/frames/439/labels` → **200** ②부모 프레임 `GET /v1/frames/429/labels` → **412**(대조군) ③다른 파생 98(부모 906=`'F'`) `GET /v1/frames/466/deid-image` → **200**. 조상 체인 미순회 실증. 근거 `DeidentReportGate.java:23-47, :66-71` 유효. **★1 확정 정책의 귀결이므로 결함 아님** |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 | PASS | [실동작] 신고 전후 `ls_data_lbl` 건수 **6→6**, `ls_label_version(data_raw_sn=115)` **0→0**(스냅샷 미생성), `ls_data_src.lbl_ver` **508=6·509=7·510=9 불변**(bump 없음). 로그 `labelsPreserved=true`. ★드리프트 `:197-205`→`:201-209` |
| TC-LABEL-125 | 개인정보 리셋 행 단위 감사 | PASS | [실동작] 3필드 보유 프레임 2건(508·509) 세팅 후 신고 → `ls_data_lbl_hstry` 에 **정확히 2행**(`{"event":"PRIVACY_META_RESET","deidentReportSn":32,"changes":[]}`, reg_id=2001), 영상 축은 `ls_task_event_log` 179 `PRIVACY_META_RESET`. **V139 필터 실측 확인**: `pg_get_viewdef('v_completed_label_change')` 의 `WHERE (add+mdfcn+del) > 0` 으로 델타 0 행은 미노출. ⚠ 로그의 `privacyReset=6` 은 프레임 총수(→ C-ISSUE-66 이월). ★드리프트 `:218-226`→`:222-253` |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 | PASS | [실동작] resolve 직후 `GET /v1/frames/508/labels` **200**, `deid-image` **200**, `label-history` **200**. 반환 라벨셋·`lbl_ver`(6) 이 신고 직전과 동일 — 별도 복원 API 호출 없음. ★드리프트 `:387-391`→`:415-419`, `LabelAccessGuard:135-141`→`:139-146` |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 | PASS | [실동작] `POST /v1/videos/112/deident-report`(REVIEWER) → **201** `data=35`. 부수효과 5종 srcSn 경로와 동일: `de_ident_yn='F'` / 작업락 LOCKED / 개인정보 3필드 리셋(`privacyReset=1 privacyResetAudited=1`) / 라벨·스냅샷 무변경(0건) / `[StreamMetaCache] evicted rawSn=112`. 통지 `srcSn=null` 은 코드(`reportByVideo` → `doReport(rawSn, **null**, …)`)로 확인(112 미승인이라 통지 미발행). ★드리프트 `Controller:122-131`→`:138-146`, `Service:147-155`→`:151-159` |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 | PASS | [실동작] `POST /v1/videos/88/deident-report`(부모 900) → **412** `"이 영상은 원본 영상의 비식별 결과를 복사해 만든 파생영상(증강·해상도 변환본)이라 이 화면에서는 비식별 재처리를 요청할 수 없습니다."` — **원본 유도 문구 없음, 부모 rawSn 미노출**. `ls_deident_report` 신규 행 0. 개행(`\n`) 포함 사유를 보냈으나 로그는 sanitize 된 WARN 1줄. ★드리프트 `:183, :295-306`→`:187, :323-334` |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 | PASS | [실동작] `POST /v1/videos/901/deident-report`(`de_ident_yn='N'`) → **412** `"아직 비식별 처리가 완료되지 않은 영상입니다…"`. `'F'` 통과 규약도 실측(rawSn 900 `'F'` → 412 아님). ⚠ 다만 "그 중복은 409 로 처리"는 **작업락이 있을 때만** 성립(→ C-ISSUE-67). ★드리프트 `:186, :329-337`→`:190, :357-365` |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess | PASS | [실동작] 미배정 WORKER(2002) → `POST /v1/videos/115/deident-report` **403** `"본인에게 배정되지 않은 영상입니다."`. **존재하지 않는 rawSn 999999 도 동일 403**(404 아님) → 존재 여부 오라클 없음(조회 이전 평가 실증). ★드리프트 `:151`→`:155` (`LabelAccessGuard.java:83-100` 은 유효) |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 | PASS | [실동작] ①파생 프레임 `POST /v1/labels/439/deident-report` → **412**(파생 문구) ②비식별 미수행 프레임 `POST /v1/labels/434/deident-report` → **412**(미수행 문구). rawSn 경로와 **문구·코드 완전 일치** → `doReport` 수렴 실증. ★드리프트 `:125, :169-186`→`:126, :173-190` |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 | PASS | [실동작] rawSn 115 는 **미승인(`ASSIGNED`)** 인데도 resolve 직후 로그 `[VlmResumeBridge] deident gate reopened rawSn=115 — checking withheld VLM submit`. ★드리프트 `:487-495`→`:515-523` |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 | PASS | [실동작] **대조 실증**. ①미승인 115 resolve → `VlmResumeBridge` 만 출력, `DatasetExportBridge` **없음** ②같은 시간대 APPROVED 영상 906 resolve → `[DatasetExportBridge] deident report resolved rawSn=906 — re-triggering withheld export/notify` + `AsyncDatasetExportRunner` 기동. ★드리프트 `:492-494`→`:520-522` |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 | PASS | [실동작] 신고 시 `[StreamMetaCache] evicted rawSn=115`, resolve 시 `evicted rawSn=115` — **그 영상 하나만**(같은 로그 구간에 파생 rawSn evict 0건). ★드리프트 `:248, :395`→`:275, :423` |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 | PASS | [실동작] ①신고로 잠긴 115 → `POST /v1/frames/508/autolabel` **409** `"작업이 잠긴 영상입니다."` ②락 없이 `'F'` 인 110 → **412** `"비식별 재처리 대기 중인 영상은 오토라벨을 실행할 수 없습니다."` — 작업락 판정 선행 확인. 근거 `AutolabelOnlineService.java:411-419`(실제 `:414-419`, ±5 허용) |
| TC-LABEL-136 | 검출 좌표 상한 clamp | PASS | [정적+단위] `DetectionBoxNormalizer.java:59-68` 유효(`clamp(v, upperBound)`), 단위테스트 `DetectionBoxNormalizerTest.이미지_상한_초과좌표는_이미지_경계로_clamp된다` 통과(3차 baseline 실패 0건). ⚠ 온라인 종단은 ai-server weights 미탑재로 `mockReason=weights_missing` → 실호출이 `normalizeDetections` 이전에 반환(1차 C-ISSUE-44 이월) |
| TC-LABEL-137 | 검출 좌표 음수 clamp | PASS | [정적+단위] 동상. `DetectionBoxNormalizerTest.경계밖_음수좌표는_0으로_clamp되어_반환된다` / `y좌표_음수도_0으로_clamp된다` 통과. 근거 `:59-68` 유효 |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 | PASS | [정적+단위] `:65-67` `if (x2<=x1 \|\| y2<=y1) return Optional.empty()`, 호출부 `AutolabelOnlineService.normalizeDetections` 가 `continue` + WARN(400 아님). 단위테스트 `clamp후_퇴화한_박스는_예외가_아니라_스킵신호를_반환한다`·`좌표순서가_역전된_박스도_스킵신호를_반환한다` 통과 |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp | PASS | [정적+단위] `:71-77` `upperBound()` → `Double.MAX_VALUE`. 단위테스트 `이미지_실측_해상도를_모르면_상한없이_음수만_0으로_clamp된다`·`잘못된_bounds_배열은_상한없음으로_취급한다` 통과 |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) | PASS | [정적] `AutolabelOnlineService.validatePolygonPoints`(`:602-621`)는 음수/비유한/2튜플 위반을 `INVALID_INPUT` 으로 던지고, 호출부(`:333` + `:340-350` catch)가 **박스 단위 스킵**한다(429 만 전파). all-or-nothing 아님 → 비대칭 결함 아님. 근거 `:581-617` 은 javadoc+메서드를 포괄(±근사) |
| TC-LABEL-141 | `/deid-image` 정상 200 | PASS | [실동작] `GET /v1/frames/508/deid-image`(WORKER) → **200**, `Content-Type: image/jpeg`, `Content-Length: 13164`(= 비식별 파일 크기, 원본은 9441), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_deid_508.jpg"` — **srcSn + MIME 파생 확장자만**(파일명 유래 문자열 없음, CWE-113). 근거 `Controller:115-122`·`Service:305, :322-333` 유효 |
| TC-LABEL-142 | `/deid-image` 원본 폴백 없음 → 404 | PASS | [실동작] `de_idntf_src_file_path_nm` 이 빈 값이고 `src_file_path_nm` 은 실재하는 srcSn 324 → `/deid-image` **404** `"비식별 이미지 파일이 존재하지 않습니다."` / 같은 프레임의 `/image` 는 **200**(원본이 실재함을 증명). 폴백 부재 확정. 근거 `:266-270, :290-301` 유효 |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 | PASS | [실동작] **카탈로그 기대값을 이번 회차에 정정한 뒤 판정**. PORTAL_USER 토큰(`channel=PORTAL`) → `/deid-image` **403** / `/image` 도 **403**(`"권한이 없습니다."`) — `SecurityConfig` 채널 격리가 `@PreAuthorize` 이전에 차단. 1차 **C-ISSUE-62 미해소 이월**이었고 본 회차에서 카탈로그 정정 완료(→ C-ISSUE-63) |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 고정 | PASS | [실동작] 신고 구간 rawSn 115 에서 ①미배정 WORKER(2002) → **403** ②배정 WORKER(2001) → **412**. 순서 인가→게이트→경로해석 확정(412/404 로 프레임 존재 탐색 불가). 근거 `FrameImageLookupService.java:99-101`·`FrameImageService.java:288` 유효 |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 | PASS | [실동작+정적] 비식별 base **내부**에 원본 프레임을 가리키는 심링크(`link-test.jpg`)를 만들고 DB 경로를 그리로 돌림 → **403**(링크를 따라 원본을 서빙하지 않음, `toRealPath` 가 base 밖으로 탈출 판정). 판정~open 사이 교체 레이스 자체는 재현 불가하나 `openNoFollow`(`:362-366`)가 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 읽고 실패 시 404 로 마감(`:313-320`)함을 코드로 확인. 근거 유효 |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 | PASS | [실동작] srcSn 502 의 비식별 경로를 `/app/storage/raw/...` 로 바꿈 → **403** `"허용되지 않은 이미지 경로입니다."`. 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` 단일 위임이며 base 동일 운영형상에서도 `frames/deid`·`videos` **서브트리 세그먼트 검사**(`:122`)가 남아 방어가 성립. 근거 `:292-300` 유효 |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` | PASS | [실동작] 응답 헤더 `Cache-Control: no-store` 확인(`max-age` 없음). 근거 `:327` 유효 |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` | PASS | [실동작] `Cache-Control: no-store` 확인. ⚠ **기대결과 사유 문구가 폐기된 동작을 서술**하고 있어 정정함 — 이 경로는 더 이상 "비식별 판정 없이 원본 프레임을 서빙"하지 않는다. 실측: WORKER 요청 시 Content-Length **13164 = 비식별본**(원본 9441), 응답 `frameImageType=DEID` → `serveBySrcSn`→`serveFrame` 공용 판정기 경유(2026-07-30 확정 정책). no-store 필요성 자체는 유효. 근거 `FrameImageService.java:252` 유효 (→ C-ISSUE-64) |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` | PASS | [실동작] WORKER 기본 요청 **200 + no-store**(13164=비식별본), REVIEWER `raw=true` **200 + no-store**(9441=원본). 신고 구간에는 동일 경로가 **412**. 근거 `VideoController.java:320-338`·`FrameImageService.java:252` 유효 |

### 집계

| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---:|---:|---:|---:|---:|---:|---:|
| 44 | 44 | 0 | 0 | 0 | 0 | 0 |

> ⚠ **"전건 PASS" 를 그대로 신뢰하지 말 것.** 케이스 표에 적힌 단언 자체는 전부 실동작으로 성립했으나,
> **적극적 반증 과정에서 케이스 밖 우회 경로 2건(C-ISSUE-61/62)** 을 찾았다. 특히 C-ISSUE-61 은
> TC-LABEL-113/116 이 지키려는 좌표 상한 정책을 **요청 한 줄로 무력화**한다. 케이스가 지정한 입력만 넣으면
> 통과하지만 정책은 지켜지지 않는 전형적 사례라, 카탈로그에 우회 케이스를 신설할 필요가 있다.

---

## 이전 회차 이슈 대조 (1차 `2026-08-01/1차/ISSUES.md`)

| 1차 이슈 | 대상 | 3차 상태 |
|---|---|---|
| **C-ISSUE-61**(1차) — `GET /v1/deident-reports` 정렬 키 미검증 → 500 + 엔티티 필드 열거 오라클 | TC-108/109 인접 | **✅ 해소.** `?sort=nosuchfield,desc` → **400** `"지원하지 않는 정렬 기준입니다."`, `?sort=rsn,desc`(엔티티 내부 필드) → **400**, `?sort=rawSn,desc` → **200**(allowlist 매핑 적용). `size=100000` → 응답 `size=100`(캡 유지). 500 및 200/500 오라클 소멸 |
| **C-ISSUE-62**(1차) — TC-143 기대결과 "`/image`=통과"가 성립하지 않음 | TC-143 | **미해소 → 본 회차 카탈로그 정정으로 종결.** 실측 여전히 `/image`=403·`/deid-image`=403. 카탈로그 기대결과를 실동작에 맞춰 수정함(C-ISSUE-63 참조). 코드 쪽 죽은 권한 표기(`FrameImageController.java:80` 의 `PORTAL_USER`)는 그대로 |
| **C-ISSUE-63**(1차) — TC-141~149 근거 file:line 드리프트 | TC-141~149 | **✅ 해소.** 9건 전부 1차 제안값으로 갱신되어 있고 실제 코드 위치와 일치함을 재확인(`Controller:115-122/117 vs :80`, `Service:266-270·290-301·307-320·327·252·362-366`, `VideoController:320-338`) |
| **C-ISSUE-64**(1차) — 신고 감사 로그 `privacyReset` 이 실제 변경 건수가 아님 | TC-125 인접 | **미해소 이월.** 실측 `privacyReset=6 privacyResetAudited=2`(프레임 6건 중 값 보유 2건). 로그 필드명·값 그대로(→ C-ISSUE-66) |
| **C-ISSUE-44**(1차) — ai-server 가중치 미탑재로 검출 의존 분기 실동작 불가 | TC-136~140 | **미해소 이월.** `[Autolabel] mock response — skip detection … reason=weights_missing`, `detectedCount=0`, message `"AI 모델 미로드 — 결과 신뢰 불가"`. `normalizeDetections` 종단 호출 불가(단위테스트로 대체 판정) |

---

## 카탈로그 정정 (본 회차, 담당 라인범위 169~212 안에서만 수행)

| # | 대상 | 정정 내용 |
|---|---|---|
| 1 | TC-106·107·108·109·124·125·126·127·128·129·130·131·132·133·134 | `DeidentReportService.java` / `DeidentReportController.java` **근거 라인 15건 갱신**(V163 영상 개인정보 메타 도입으로 +20~30줄 이동) |
| 2 | TC-121·126 | `LabelAccessGuard.java:135-141` → `:139-146` |
| 3 | TC-111 | `LabelBulkUpsertRequest.java:26-32` → `:24-31` |
| 4 | TC-135 | `AutolabelOnlineService.java:411-419` → `:414-419` |
| 5 | TC-143 | **기대결과 정정** — `/image` = 통과 → `/image` 도 **403**(채널 격리 `SecurityConfig` 가 `@PreAuthorize` 보다 먼저 차단). 역할 집합 차이는 애노테이션 계층에만 존재 |
| 6 | TC-148 | **기대결과 사유 정정** — "비식별 판정 없이 원본 프레임을 서빙" 서술 폐기. 이 경로는 `serveBySrcSn`→`serveFrame` 공용 판정기로 **기본 DEID** 서빙 |
| 7 | TC-113·116 뒤 | **신규 케이스 TC-LABEL-150 추가** — 존재하지 않는 `id` 를 붙인 신규 라벨의 좌표 상한 우회(C-ISSUE-61 회귀 가드) |

> 프로덕션 코드·테스트·설정은 일절 수정하지 않았다.

---

## 이슈 대장

### [C-ISSUE-61] TC-LABEL-113/116 인접 — 존재하지 않는 `id` 를 붙이면 좌표 경계 상한(400) 검증이 통째로 우회된다

- **심각도**: HIGH
- **기대 동작(기대효과)**: ★3(확정 정책) — **사용자 저장 경로의 좌표는 이미지 경계를 넘으면 400 으로 거부**한다(클램프 아님, 사용자 확정). 이유는 "작업자가 의도한 위치가 조용히 왜곡되면 안 된다"이며, 그 결과 `LS_DATA_LBL` 에는 프레임 밖 좌표가 들어가지 않아야 한다(export JSON·데이터마트로 그대로 흘러가는 값이다). 신규 라벨 판정은 "그 프레임에 실재하지 않는 라벨"이면 전부 신규여야 한다 — TC-LABEL-120 이 `USE_YN` 축에서 이미 이 원칙("id 붙이면 통과" 우회 차단)을 명시적으로 구현하고 있다.
- **현재 동작(이슈 내용)**: 상한 검증은 **`item.id() == null` 일 때만** 수행되는데, 저장 분기는 `id != null` 이어도 **그 프레임에 없는 id 면 신규 라벨로 만든다.** 두 판정 기준이 어긋나 상한 검증이 전혀 걸리지 않는 창이 생긴다.
  ```java
  // LabelService.java:288-292  (사전 검증)
  validatePoints(item.lblTypeCd(), item.points(), item.id() == null);
  if (item.id() == null) {                       // ← id 가 있으면 상한 검증 자체를 건너뜀
      validateWithinBounds(item.lblTypeCd(), item.points(), bounds);
  }

  // LabelService.java:331-335  (UPDATE 분기 — idIndex 에 있을 때만)
  if (item.id() != null && idIndex.containsKey(item.id())) {
      if (!pointsEqual(found.getPointCn(), pointsJson)) { validateWithinBounds(...); }
  } else {
      // LabelService.java:349-365 — 신규 생성. validateWithinBounds 호출 없음.
      created = labelRepository.save(LsDataLbl.createManual(srcSn, ..., pointsJson, actorNo));
  }
  ```
  대조 `LabelService.java:685-695`(`isNewLabelAssignment`)는 **같은 상황을 "신규 취급"으로 올바르게 처리**한다 — 즉 USE_YN 축은 막혀 있고 좌표 축만 뚫려 있다(설계 의도가 아니라 누락으로 판단).
  **실측**(WORKER 2001, srcSn 509, 프레임 320x240):
  ```
  PUT /v1/frames/509/labels
    {"items":[{"id":99999999,"lblTypeCd":"BBOX","labelId":1,"label":"bypass-oob",
               "points":[[999999,888888],[999999,888889]]}]}
    → 200 OK   (id 를 빼면 동일 요청이 400 "좌표가 이미지 경계를 벗어났습니다 … 이미지=320x240")
  DB : select lbl_sn, point_cn from ls_data_lbl where lbl_nm='bypass-oob';
       1257 | [[999999.0,888888.0],[999999.0,888889.0]]     ← 경계 밖 좌표가 실제로 적재됨
  ```
- **재현/확인 경로**: 위 curl 2줄(같은 payload 를 `id` 유/무로 각각 전송해 200/400 대조). 사후 정리: 해당 라벨은 full-replace 델타로 삭제됨.
- **영향**: ①확정 정책(★3)이 무력화되어 프레임 밖 좌표가 `LS_DATA_LBL` → 검수 승인 스냅샷(`LS_LABEL_VERSION`) → export JSON → 데이터마트까지 그대로 전파된다(학습데이터 오염, 데이터 정합) ②입력 검증 우회(CWE-20 / CWE-1287 Improper Validation of Specified Type of Input) ③TC-LABEL-113·116 의 회귀 가드가 "지정된 입력에서만" 성립해 방어가 실질적으로 없는 상태를 통과로 보이게 한다.
- **수정 방향(제안)**: 상한 검증의 판정 기준을 **저장 분기와 동일한 술어**로 맞춘다 — 사전 검증 루프에서 `item.id() == null` 대신 `isNewLabelAssignment` 와 같은 축(즉 `item.id() == null || !idIndex.containsKey(item.id())`)을 쓰거나, `else`(신규 생성) 분기 안에서 `validateWithinBounds` 를 호출한다. 후자가 안전하다(사전 루프는 `idIndex` 적재 이전에 돌기 때문). 동일 술어를 `validatePoints(..., enforceMaxPoints)` 에도 적용해야 C-ISSUE-62 가 함께 닫힌다. 회귀 가드로 TC-LABEL-150 신설(본 회차 카탈로그에 추가함).

### [C-ISSUE-62] TC-LABEL-113 인접 — 같은 우회로 `MAX_POINTS_PER_LABEL`(1000) 상한도 무력화된다

- **심각도**: LOW
- **기대 동작(기대효과)**: 신규 라벨의 좌표 개수 상한 1000점은 과대 좌표 DoS(CWE-770) 방어로 명시돼 있다(`LabelService.java:700-717` javadoc). 기존 라벨만 상한 없이 통과시키고 저장 직전 simplify 하는 것이 설계다.
- **현재 동작(이슈 내용)**: `validatePoints(..., item.id() == null)` 이므로 존재하지 않는 `id` 를 붙이면 신규 라벨인데도 `enforceMaxPoints=false` 가 되어 상한 검사를 건너뛴다.
  ```
  PUT /v1/frames/509/labels  {"items":[{"id":99999998, ... ,"points": 1500점}]}   → 200
  PUT /v1/frames/509/labels  {"items":[{           ... ,"points": 1500점}]}       → 400 "라벨당 좌표 개수 초과 (최대 1000 점)"
  ```
- **재현/확인 경로**: 위 2줄 대조.
- **영향**: 데이터 영향은 없다 — 저장 직전 `capPoints`(Douglas-Peucker)가 실제 적재를 줄인다(실측: 1500점 요청 → 저장 29점). 남는 것은 **요청 파싱·직렬화·simplify 연산이 상한 없이 수행**되는 자원 소모 표면뿐이며 `@Size(max=500)` items 상한과 요청 바디 크기 제한이 뒤를 받친다. 그래서 LOW.
- **수정 방향(제안)**: C-ISSUE-61 과 **같은 한 줄**로 닫힌다(판정 술어를 저장 분기와 일치시키면 `enforceMaxPoints` 도 함께 정상화). 별도 수정 불필요.

### [C-ISSUE-63] TC-LABEL-143 — 기대결과 "`/image` = 통과"가 성립하지 않음 (1차 C-ISSUE-62 이월 → 본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 실동작이 기대보다 **더 엄격**)
- **기대 동작(기대효과)**: 카탈로그는 PORTAL_USER 토큰에 대해 `/deid-image`=403 / `/image`=통과 로 "두 형제 경로의 역할 집합이 의도적으로 다름"을 검증하려 한다.
- **현재 동작(이슈 내용)**: 실측 **양쪽 모두 403**(`{"message":"권한이 없습니다.","errorCode":"FORBIDDEN"}`). PORTAL_USER 토큰은 `channel=PORTAL` 이라 `SecurityConfig` 의 내부 채널 격리(`/v1/portal/**` 외 `/v1/**` = `CHANNEL_INTERNAL`)에서 `@PreAuthorize` 도달 **이전에** 차단된다. `FrameImageController.java:80` 의 `hasAnyRole(..., 'PORTAL_USER')` 는 현 배선상 **도달 불가 권한 표기**다. 1차에서 동일 결론이 났으나 카탈로그가 갱신되지 않아 이월됐다.
- **재현/확인 경로**: `POST /v1/dev/tokens {"userNo":"3001","role":"PORTAL_USER","channel":"PORTAL"}` → `GET /v1/frames/508/image` 403 / `GET /v1/frames/508/deid-image` 403.
- **영향**: 보안상 더 강함(영향 없음). 다만 기대값이 틀린 채로 두면 다음 회차에 위양성 FAIL 을 유발하고, 컨트롤러의 죽은 권한 표기가 정책 오독을 부른다.
- **수정 방향(제안)**: ①**카탈로그 기대결과는 본 회차에서 정정 완료**(양쪽 403 + 채널 격리 선행 명시) ②코드 쪽은 `FrameImageController` `/image` 의 `PORTAL_USER` 표기를 제거하거나 "채널 격리로 실효 없음" 주석을 다는 것을 검토(동작 변경 아님). 2회 연속 같은 지적이 반복됐으므로 코드 쪽 정리를 권장.

### [C-ISSUE-64] TC-LABEL-148 — 기대결과의 사유 문구가 폐기된 동작을 서술한다 (본 회차 카탈로그 정정)

- **심각도**: LOW (제품 결함 아님 — 문서/코드 드리프트)
- **기대 동작(기대효과)**: 케이스가 검증하려는 것은 `GET /v1/frames/{srcSn}/image` 응답의 `Cache-Control: no-store` 이며, 이는 **성립한다**.
- **현재 동작(이슈 내용)**: 기대결과 문장이 *"이 경로는 비식별 판정 없이 **원본 프레임**을 서빙하므로"* 라고 사유를 적었는데, 2026-07-30 확정 정책(`CLAUDE.md` "라벨링 캔버스는 비식별 프레임을 서빙한다")으로 이 경로는 `serveBySrcSn`→`serveFrame` 공용 판정기를 타 **기본 DEID** 를 서빙하도록 바뀌었다. 실측: WORKER 요청 `Content-Length: 13164`(비식별 파일 크기) — 원본은 9441 이고 REVIEWER `raw=true` 일 때만 9441 이 나온다. 응답 JSON 의 `frameImageType` 도 `DEID`.
- **재현/확인 경로**: `GET /v1/frames/508/image`(WORKER) 와 `GET /v1/videos/115/frames/0/image?raw=true`(REVIEWER) 의 `Content-Length` 대조 + `docker exec klid-backend ls -l` 로 두 파일 크기 확인.
- **영향**: 검증자가 "원본이 서빙되는 경로"로 오독해 다음 회차에 결함으로 오분류할 위험(이 저장소의 "폐기된 동작을 계속 검증 대상으로 들고 있는" 반복 패턴).
- **수정 방향(제안)**: **본 회차에서 카탈로그 사유 문구 정정 완료.** 코드 수정 불필요.

### [C-ISSUE-65] TC-LABEL-106~134 — 근거 `file:line` 드리프트 15건 (본 회차 카탈로그 정정)

- **심각도**: LOW
- **기대 동작(기대효과)**: 근거 file:line 이 실제 구현 위치를 가리켜야 재검증 비용이 들지 않는다.
- **현재 동작(이슈 내용)**: V163(영상 개인정보 메타) 도입으로 `DeidentReportService` 가 +20~30줄 밀리면서 신고/해소 계열 근거가 일제히 어긋났다(1차에서는 "TC-106~140 근거 전건 유효"였으므로 **본 회차 신규 드리프트**).
  | TC | 카탈로그 | 실제 |
  |---|---|---|
  | 106 | `:374-391` | `:402-419` |
  | 107 | `:384-391` | `:412-419` |
  | 108 | `:426-438` | `:454-466` |
  | 109 | `:427-429` | `:455-457` |
  | 121·126 | `LabelAccessGuard.java:135-141` | `:139-146` |
  | 124 | `:197-205` | `:201-209` |
  | 125 | `:218-226` | `:222-253` |
  | 126 | `:387-391` | `:415-419` |
  | 127 | `Controller:122-131` · `Service:147-155` | `Controller:138-146` · `Service:151-159` |
  | 128 | `:183, :295-306` | `:187, :323-334` |
  | 129 | `:186, :329-337` | `:190, :357-365` |
  | 130 | `:151` | `:155` |
  | 131 | `:125, :169-186` | `:126, :173-190` |
  | 132 | `:487-495` | `:515-523` |
  | 133 | `:492-494` | `:520-522` |
  | 134 | `:248, :395` | `:275, :423` |
  | 111 | `LabelBulkUpsertRequest.java:26-32` | `:24-31` |
  | 135 | `AutolabelOnlineService.java:411-419` | `:414-419` |
  (TC-110·112~120·122·123·136~149 의 근거는 전건 유효.)
- **재현/확인 경로**: 해당 파일 Read 대조.
- **영향**: 검증 비용 증가·오판 위험.
- **수정 방향(제안)**: **본 회차에서 카탈로그 갱신 완료.**

### [C-ISSUE-66] TC-LABEL-125 인접 — 신고 감사 로그의 `privacyReset` 이 "실제로 지워진 프레임 수"가 아니다 (1차 C-ISSUE-64 미해소 이월)

- **심각도**: LOW
- **기대 동작(기대효과)**: 개인정보 표기를 되돌리는 행위의 감사 로그는 **실제 변경 건수**를 말해야 한다(OWASP A09). 행 단위 감사(`privacyResetAudited`)와 집계 로그가 다른 수를 말하면 운영자가 어느 쪽을 믿을지 알 수 없다.
- **현재 동작(이슈 내용)**: `srcRepository.resetPrivacyMetaByRawSn(rawSn)`(`DeidentReportService.java:223`)은 `where s.rawSn = :rawSn` 만으로 전 프레임을 무조건 update 하므로 반환값이 곧 "프레임 총수"다.
  ```
  [DeidentReport] created rprtSn=32 rawSn=115 ... privacyReset=6 privacyResetAudited=2 videoPrivacyReset=true
  ```
  실제 3필드 값을 보유한 프레임은 2건(508·509)인데 `privacyReset=6`(rawSn 115 의 전체 프레임 수)이다. 1차 실측(`privacyReset=5 / audited=0~2`)과 동일 양상 — **미해소**.
- **재현/확인 경로**: 개인정보 3필드가 전부 NULL 인 영상에 신고 접수 → 로그의 `privacyReset` 이 프레임 수와 같음을 확인. 실측 대조군: rawSn 906 `privacyReset=2 privacyResetAudited=0`.
- **영향**: 감사 로그 오독(실제 변경이 없었는데 "6건 리셋"으로 읽힘). 데이터 정합·보안 자체에는 영향 없음(행 단위 감사 `LS_DATA_LBL_HSTRY` 는 정확하다).
- **수정 방향(제안)**: 로그 필드명을 `privacyResetScanned`(또는 `frameCount`)로 바꾸거나, 값을 `privacyResetSrcSns.size()`(=실제 변경분)로 통일. 벌크 update 자체는 멱등이라 조건 추가는 불필요.

### [C-ISSUE-67] TC-LABEL-129 인접 — `'F'` 인데 작업락이 없는 영상(비식별 **실패**분)이 신고를 받아 영구 잠금될 수 있다

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `requireDeidentAttempted` 의 설계 근거는 *"이미 `'F'` 인 영상은 통과해 기존 409(재비식별 진행 중) 경로 유지"* 다(`DeidentReportService.java:351-355` javadoc, 카탈로그 TC-129 기대결과). 즉 `'F'` 통과는 **"이미 신고돼 잠겨 있으니 409 가 받는다"** 를 전제로 한다.
- **현재 동작(이슈 내용)**: `'F'` 는 의미가 둘인데(①신고 — 락 있음·산출물 있음 ②**비식별 API 실패** — 락 없음·산출물 없음, `CLAUDE.md` 가 명시), 신고 접수 경로는 **락 존재 여부만** 보고 산출물 실재를 보지 않는다.
  ```java
  // DeidentReportService.java:190  (판정은 'Y'|'F' 통과)
  requireDeidentAttempted(raw);
  // DeidentReportService.java:193-195
  if (workLockService.isRawLocked(rawSn)) { throw CONFLICT("이미 비식별 재처리 중인 영상입니다."); }
  ```
  `'F'` 를 락 없이 만드는 경로가 실재한다 — `KpstDeidentTxService.java:172,:233,:256,:271` 과 `BatchTransitionService.java:223` 의 실패 전이(`markDeidentified("F")`).
  **실측**(rawSn 900 — `de_ident_yn='F'`, `LOCKED` 락 0건):
  ```
  POST /v1/videos/900/deident-report {"reason":"중복 신고"}
    → 201 {"data":34}      (기대 서술대로면 409)
  ```
  접수되면 작업락이 걸리는데, 해소 경로 `verifyDeidentArtifact`(`:570-609`)는 **SUCCEEDED procLog + 실파일 + 신고 이후 갱신**을 요구한다. 비식별이 한 번도 성공하지 않은 영상은 이 조건을 영원히 만족할 수 없어 `resolve` 가 상시 409(`"비식별 산출물이 확인되지 않습니다…"`)로 fail-closed 된다 — 실측으로 같은 409 를 rawSn 115 에서 확인(파일 mtime 갱신 전).
- **재현/확인 경로**:
  ```sql
  -- 비식별 실패 영상 재현: 성공 procLog 가 없고 de_ident_yn='F' 이며 LOCKED 락이 없는 rawSn 선정
  select r.raw_sn from ls_data_raw r
   where r.de_ident_yn='F'
     and not exists (select 1 from ls_auth_work_lock l where l.data_raw_sn=r.raw_sn and l.lck_stts_cd='LOCKED')
     and not exists (select 1 from ls_deident_proc_log p where p.data_raw_sn=r.raw_sn and p.proc_stts_cd='SUCCEEDED');
  ```
  → `POST /v1/videos/{rawSn}/deident-report` 201 → `POST /v1/deident-reports/{rprtSn}/resolve` 항상 409.
- **영향**: 기능/가용성 — 비식별이 **실패**한 영상에 신고가 접수되면 작업락이 걸린 채 해소 수단이 없다(관리자 DB 개입 필요). `LS_DEIDENT_REPORT` 에 해소 불가 OPEN 행이 누적되어 REVIEWER 신고 관리 화면(TC-108/109)의 기본 목록을 오염시킨다. 파생·해상도 게이트의 판정 원천(`hasDeidentArtifact()`)이 "산출물 있음"으로 거짓 응답하는 문제와 같은 뿌리다.
- **수정 방향(제안)**: `requireDeidentAttempted` 를 **플래그 단독 판정에서 산출물 실재 확인으로 승격**한다 — `'F'` 인 경우 `LsDeidentProcLogRepository.findLatestSuccessByDataRawSn` 이 존재하고 경로가 비지 않았을 때만 통과시키고, 아니면 412(신고 대상 아님 — 비식별 실패 재처리 대기)로 거부. 이는 `CLAUDE.md` 가 이미 채택한 "산출물 실재 검증이 fail-closed 로 뒤를 받친다" 패턴(해상도 Phase A/B)을 신고 입구에도 적용하는 것이며, 기존 "신고 중복 → 409" 계약은 그대로 유지된다. ⚠ 카탈로그 TC-129 기대결과에 "락이 있을 때"라는 전제를 명시하는 정정도 함께 필요(본 회차에서는 정정하지 않음 — 정책 확정 대상).

---

## 검증 중 확인한 환경/데이터 사실 (참고)

1. **동시 검증 간섭 실재** — 검증 시간대(00:28~01:07)에 다른 파트가 rawSn 103·104·105·107·109·905·906 에 신고를 생성·해소 중이었다. rawSn 906 의 APPROVED resolve 로그가 TC-133 대조군으로 유용했으나, 신고는 대상 영상을 잠그므로 **파트별 전용 rawSn 배정이 필요**하다(1차에서도 같은 권고).
2. **DB 컬럼명 주의(재확인)** — 카탈로그·`CLAUDE.md` 표기는 `DE_IDNTF_YN` 이지만 실제 컬럼은 `ls_data_raw.de_ident_yn`(프레임은 `de_idntf_src_file_path_nm`). 신고 PK 도 `rprt_sn` 이 아니라 `deident_report_sn`, 락 테이블은 `ls_auth_work_lock`.
3. **actuator 는 인증 필요** — `/api/actuator/metrics/**` 는 토큰 없이 401. 메트릭 확인 시 REVIEWER 토큰 필요.
4. **프레임 실측 해상도 320x240** — 3차 시드 프레임(`clip-9101`) 기준. 좌표 경계 케이스 판정 시 1280x720 을 전제로 한 카탈로그 예시 값과 다르다(케이스 문구는 예시일 뿐 판정에는 영향 없음).
