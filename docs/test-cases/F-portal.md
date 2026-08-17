# F. 포털(외부 채널) — 테스트 케이스

> 201 케이스(표 행 실측 — **폐기 행 포함**, 행을 지우지 않으므로) · 계층: unit / integration / security · [← README](README.md) ※ 카운트 = `grep -cE '^\| ~*TC-'`(ID 취소선 폐기 행 포함, 2026-08-05 머지 회차 7 정정 · 회차 5 에서 177→180, 포털 TUS 존재 오라클 차단 `TC-TUS-033~035` 신설 · **회차 6 에서 180→201, F-12 데이터마트 ZIP 다운로드·보존기간 만료 자동 삭제 신설**)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 77건 | 10건 | 0건 | 포털 SAM2 원본→비식별 전송 전환(FrameImageEncoder 쌍둥이 삭제) + 게이트 412 반영, 포털 프레임 이미지 no-store 통일, PortalUploadProperties record 전환에 따른 라인 재정렬(PortalUploadService +6, PortalVideoUploadTxService -1), SecurityConfig 라인 전면 재확인(Phase 1·7 인가 표면 수정으로 매처 순서 이동), multipart 21MB/1100MB 는 prd 전용(공통은 500MB/1200MB)으로 정정, frame-interval-sec 기본값 5초 확정(UNCERTAINTIES #5 해소), TUS 스윕 2노드 조건부 UPDATE/DELETE 신규 케이스 |
| 2 | 2026-08-03 | 73건 | 0건 | 0건 | **근거 `file:line` 전수 재확인 회차** — F-2/F-3(`PortalLabelService.java`)·F-5/F-6(`PortalUploadService.java`)·F-7(`PortalUploadLabelService.java`) 라인 대량 드리프트 정정(F-7 은 구 노트 "07-25 이후 무변경"이 오기였음 — 실제로는 476bc91a·dcdbb827 2건 반영되어 있었음, 08-03 정정). F-8/F-9(TUS·프레임추출)·F-10/F-11(스윕·파이프라인 분리)은 전건 정확 확인(수정 없음). F-4 헤더의 포털 SAM2 제거일을 08-02→**08-03**(dcdbb827) 로 정정. TC-PORTAL-032/033 은 구현이 `resolveSafe`(lexical)→`StorageSubtreePolicy.verifyDeidentifiedFile`(실경로) 로 교체된 사실을 기대결과 문구에 반영. 폐기·UNRESOLVED 신규 없음(기존 F-3 042~045 폐기 표기는 재확인 후 유지) |
| 3 | 2026-08-04 | 8건 | 0건 | 0건 | **3차 전수 검증 회차(4파트 병렬 + 병합)** — 2차 HIGH 3건(`/v1/portal/datamart/labels` 게이트 전무·라벨 body-size 필터 URL인코딩 우회·포털 SAM2 노출) **전건 해소 실동작 확증**. F-4 머리말 폐기범위 오기 정정(`075~078`→`075~077`, TC-PORTAL-078은 활성 케이스) + F-3/F-4 절 간 `TC-PORTAL-060~062` ID 충돌 경고 블록 신설 + TC-PORTAL-075~077 폐기 삭제일 정정(08-02→08-03) + TC-PORTAL-038/052·TC-PORTALUP-021/022 근거 `file:line` 드리프트 정정 + F-3 채번 주의 노트 갱신(058~062 반영, 다음 신규 063부터). 신규 FAIL 0건(3건은 전부 F-5 2차 이슈 미해소 이월), 신규 PARTIAL 0건(5건 전부 이월). 상세는 `docs/검증결과/2026-08-03/3차/F-result.md`·`ISSUES.md` 참조 |
| 4 | 2026-08-05 | 3건 | 0건 | 0건 | **ID 중복 3건 해소 — 재번호(케이스 내용·행 수 불변)**. F-3 절(데이터마트 사용자 라벨 저장)의 신규 3건을 `TC-PORTAL-060`·`061`·`062` → **`TC-PORTAL-095`·`096`·`097`** 로 옮겼다. 같은 3개 ID 를 F-4 절(포털 SAM2 미제공 확정)이 함께 쓰고 있었고, **외부 참조가 걸린 쪽은 F-4** 다(`docs/검증결과/2026-08-02/2차/F-result.md:622-624` · 같은 회차 `ISSUES.md` 의 **F-ISSUE-41** 이 `TC-PORTAL-060/061` 을 포털 SAM2 케이스로 인용) — 그래서 참조 영향이 없는 F-3 쪽을 재번호했다. 새 번호는 이 문서의 `TC-PORTAL` **마지막 번호(094) 다음부터** 채번(저장소 규칙). 회차 2 머리말의 "별도 판단이 필요해 이번 정정 범위에서 제외" 유보는 이로써 해소. 행 수 불변이라 **총계(177) 영향 없음** ⚠ **머지 합류(2026-08-05)로 회차 번호 3 → 4 재부여** — main 의 회차 3(3차 전수 검증)과 겹쳤다. |
| 5 | 2026-08-07 | 3건(TC-TUS-012 · TC-PORTAL-072 · 072c) | 3건(TC-TUS-033~035) | 0건 | **★포털 TUS 세션의 존재 오라클 차단 — 소유자 불일치 403 → 404**(내부 업로드 [B-23](B-batch-deidentify.md) 과 같은 규약). **TC-TUS-012 의 구 기대값 403 은 폐기** — 403 은 "그 세션은 있는데 네 것이 아니다"가 되어 응답 코드가 **세션 존재 오라클**이 된다(CWE-209). 이제 `HEAD`·`PATCH`·`DELETE` 3경로 모두 미존재와 **상태코드도 메시지도 동일한 404** 이고(TC-TUS-033), 거부는 offset·완료 검사·파일 삭제보다 **먼저** 평가돼 부수효과가 0 이다(TC-TUS-034). ⚠ **인증 401 · 역할 403 · 만료 410 · offset 409 는 불변**(TC-TUS-035 가 회귀 가드) — 소유자 본인의 정상 재개 흐름은 달라지지 않았다. ⚠ 한 경로라도 403 을 남기면 그 경로로 같은 판별이 가능해 나머지 차단이 무의미해진다(오라클은 가장 느슨한 경로를 따라간다). 그 밖에 좌측 도구바 컴포넌트 개명(`DarkToolbar` → `ToolBar`)에 따라 TC-PORTAL-072·072c 의 근거 테스트 파일명을 정정했다 — **기대결과 불변** |
| 6 | 2026-08-17 | 0건 | 21건(TC-PORTAL-098~109, TC-PORTALUP-088~096) | 0건 | **★F-12 신설 — 구 V1.5 "포털 데이터마트 다운로드는 포털 자체 책임" 정책 폐기(사용자 확정).** 데이터마트 작업 데이터 ZIP 다운로드(`GET /v1/portal/datamart/videos/{rawSn}/download`, 200/403/410/412/429) + 보존기간 만료 자동 삭제(데이터마트 라벨·업로드 자산 READY/FAILED 독립 축) + 만료 예정 시각 조회 시점 파생 노출(`myLabelExpiresAt`/`expiresAt`)을 검증 대상에 추가했다. [UNCERTAINTIES.md #11](UNCERTAINTIES.md) "미해소 유지" 판정도 이 회차에서 해소로 갱신. 기존 F-7 절의 `TC-PORTALUP-055~058`(업로드 자산 원본 다운로드)은 **별개 축이라 무변경**. ⚠ **화면(SCREEN-028) 다운로드 버튼 배선은 이번 범위 밖** — 백엔드 API만 신설, TC-PORTAL-072~074(FE 포털 제약 회귀)는 무변경 |

> **2026-08-05 헤더 카운트 정정(케이스 내용 변경 없음)**: 머리말 총계 169 → **177** 로 실측 정정. 후속 회차가 행을 추가하면서 머리말만 169 로 남아 있었다 — [README](README.md) 최신화 이력에는 이미 "회차 2 … F 169→177" 로 기록돼 있어 **README 와 이 파일 머리말이 서로 달랐다**. **카운트 기준 = 표 행 실측(폐기 행 포함)**.
>
> ✅ **2026-08-05 ID 중복 해소(회차 4 — 머지 합류로 3→4 재부여)**: `TC-PORTAL-060`·`061`·`062` 가 F-3 절(신규 저장 검증)과 F-4 절(포털 SAM2 제거 확인)에 **각각 다른 케이스로** 중복돼 있었다. **F-3 쪽을 `TC-PORTAL-095`·`096`·`097` 로 재번호**했다 — 1·2차 검증 결과 문서가 이 3개 ID 를 **F-4(포털 SAM2)** 케이스로 인용하고 있어(F-ISSUE-41) 그쪽 번호를 유지해야 추적성이 끊기지 않는다. 케이스 내용·행 수는 불변이라 카운트(177) 영향 없음.

## F-1. 채널·역할 게이팅 / 인가 경계

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-001 | PORTAL_USER+PORTAL 채널로 /v1/portal/** 허용 | PORTAL_USER | role=PORTAL_USER,channel=PORTAL | 200 | integration | High | SecurityConfig.java |
| TC-PORTAL-002 | INTERNAL 채널(WORKER)로 /v1/portal/** 403 | WORKER/INTERNAL | GET /v1/portal/datamart/videos | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-003 | REVIEWER(INTERNAL)로 포털 API 403 | REVIEWER | /v1/portal/uploads | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-004 | PORTAL_USER이나 channel≠PORTAL → 403 | role=PORTAL_USER,channel=INTERNAL | /v1/portal/** | 403(allOf 두 조건) | security | High | SecurityConfig.java |
| TC-PORTAL-005 | PORTAL_USER가 내부 /v1/frames/** 차단 | PORTAL 채널 | POST /v1/frames/{srcSn}/sam2-track(내부) | 403 — `/v1/frames/**` 전용 매처는 없고 하위 catch-all `/v1/**`(CHANNEL_INTERNAL 필수)이 차단 | security | High | SecurityConfig.java |
| TC-PORTAL-006 | PORTAL_USER가 /v1/manage/** 차단 | PORTAL 채널 | PUT /v1/manage/labels/... | 403 | security | High | SecurityConfig.java |
| TC-PORTAL-007 | PORTAL_USER가 /v1/notices 차단 | PORTAL 채널 | GET /v1/notices | 403 | security | Med | SecurityConfig.java |
| TC-PORTAL-008 | 라벨 마스터 조회(GET)는 PORTAL_USER 허용 | PORTAL 채널 | GET /v1/manage/labels | 200(authenticated) | integration | Med | SecurityConfig.java |
| TC-PORTAL-009 | 토큰 sub 없음 → 401 | actor.sub()==null | 포털 컨트롤러 진입 | 401 | unit | High | PortalUploadController.java |
| TC-PORTAL-010 | FE 채널가드: PORTAL_USER의 내부 화면 접근 → forbidden | FE 라우팅 | PORTAL_USER /video/completed | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx |
| TC-PORTAL-011 | FE 채널가드: INTERNAL의 /portal 접근 → forbidden | FE 라우팅 | 내부 사용자 /portal | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx |

> 근거 라인 전면 재확인(07-30): `SecurityConfig.java`는 Phase 1(dd288bed, 웹훅 인증 우회 차단 + 인가 표면 15건) · Phase 7(41b0504d, `/v1/genai/callback` permitAll 추가 등)에서 매처 순서·행이 이동했다. `/v1/portal/**` 매처는 108→134-135 행으로 이동. `/v1/frames/**` 전용 매처는 원래도 없었고(구 근거 "121-122"는 부정확했음), 실제로는 하위 범용 `/v1/**`(CHANNEL_INTERNAL + REVIEWER/WORKER/STREAM_SIGNED) 매처(147-153행)가 차단한다 — TC-PORTAL-005 근거를 정정.

## F-2. 데이터마트 Load (APPROVED 게이트 · IDOR · 비식별 서빙 · 신고 게이트)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-020 | 데이터마트 목록은 APPROVED만 노출 | 검수완료/미완료 혼재 | GET /v1/portal/datamart/videos | APPROVED만(INNER JOIN) | integration | High | PortalLabelService.java |
| TC-PORTAL-021 | 프레임 0건 영상 목록 제외 | firstSrcSn 부재 | 목록 조회 | 제외 | unit | Med | PortalLabelService.java |
| TC-PORTAL-022 | 목록 N+1 회피(IN 조회) | 다수 영상 | 목록 | 각 1회 IN 쿼리 | unit | Low | PortalLabelService.java |
| TC-PORTAL-023 | 라벨 Load 페이징 clamp | rawSn 존재 | size=99999,page=-1 | size≤100, page≥0 | unit | Med | PortalLabelService.java |
| TC-PORTAL-024 | rawSn null 라벨 Load → 400 | - | rawSn 누락 | INVALID_INPUT | unit | Med | PortalLabelService.java |
| TC-PORTAL-025 | 프레임 라벨 Load: 본인 user-label 우선 | 본인 저장분 존재 | GET labels | user-label만 | unit | High | PortalLabelService.java |
| TC-PORTAL-026 | 프레임 라벨 Load: 없으면 datamart 원본 | 본인 저장분 0 | 동일 | datamart 원본 | unit | High | PortalLabelService.java |
| TC-PORTAL-027 | 미승인 영상 프레임 라벨 Load → 403 | not APPROVED | GET labels | 403 | security | High | PortalLabelService.java |
| TC-PORTAL-028 | 비존재 srcSn Load → 404 | 부재 | 동일 | 404 | unit | Med | PortalLabelService.java |
| TC-PORTAL-029 | 프레임 이미지 서빙: APPROVED 비식별만 | APPROVED | GET image | 200+nosniff+**no-store**(구 private/max-age=300 폐기, 아래 TC-PORTAL-037) | integration | High | PortalLabelService.java |
| TC-PORTAL-030 | 프레임 이미지 서빙: 미승인 → 403 | 비APPROVED | 동일 | 403 | security | High | PortalLabelService.java |
| TC-PORTAL-031 | deid 경로 부재 시 원본 폴백 금지 → 404 | deidFilePath null/blank | 동일 | 404(원본 차단) | security | High | PortalLabelService.java |
| TC-PORTAL-032 | 프레임 이미지 Path Traversal 차단 | 경로 조작 | baseDir 밖 | `StorageSubtreePolicy.verifyDeidentifiedFile` 거부(구 resolveSafe 는 폐기·대체됨) | security | High | PortalLabelService.java |
| TC-PORTAL-033 | baseDir=deidentified-path(raw 회귀 방지) | deid 절대경로 | 서빙 | 실경로(toRealPath) 기준 서브트리 검증(구 startsWith lexical 검증은 심링크 우회 가능해 폐기·대체됨) | unit | Med | PortalLabelService.java |
| TC-PORTAL-034 | 파일 부재 시 내부경로 비노출 404 | 파일 없음 | 서빙 | 404, 경로 미노출 | security | Med | PortalLabelService.java |
| TC-PORTAL-035 (신규) | 신고 구간(DE_IDNTF_YN='F') 프레임 라벨 Load → 412 | 자기 rawSn 신고 중 | GET labels | 412(PRECONDITION_FAILED) — APPROVED 게이트만으로는 안 걸림(신고는 LS_RAW_DATA_STATUS 를 건드리지 않음) | security | High | PortalLabelService.java |
| TC-PORTAL-036 (신규) | 신고 구간 프레임 이미지 서빙 → 412(파일 읽기 전 차단) | 자기 rawSn 신고 중 | GET image | 412, 파일 미판독 | security | High | PortalLabelService.java |
| TC-PORTAL-037 (신규) | 프레임 이미지 응답 캐시는 no-store로 통일 | 임의 프레임 | GET image | `Cache-Control: no-store`(구 `private, max-age=300` 폐기 — 신고 직후 최대 5분 재노출되던 경로 차단) | security | High | PortalLabelService.java |
| TC-PORTAL-038 (신규) | 신고 게이트는 자기 rawSn 행만 판정(조상/자손 전파 없음) — 파생영상은 부모 신고와 무관 | 부모 'F', 자기 rawSn 'Y' | GET labels/image (파생 srcSn) | 200(정상 서빙) — "파생 경유 열람"은 확정 정책(CLAUDE.md 2026-07-29)의 필연적 귀결이며 결함 아님. 조상 순회는 4라운드 시도 후 철회됨 | security | High | DeidentReportGate.java(javadoc), AiInferenceDeidentReportGateTest.java(`segmentNotBlockedByOriginReport` — 내부 SAM2 경로로 동일 게이트 검증, 포털도 같은 컴포넌트 재사용) |
| TC-PORTAL-039 (신규) | **데이터마트 라벨 Load 미승인/미존재 → 403** | 비APPROVED 또는 미존재 rawSn | GET /v1/portal/datamart/labels?rawSn | 403(FORBIDDEN), **라벨 풀조회 미수행**. 미존재와 미승인을 구분하지 않는다(존재 여부 오라클 차단 — CWE-209). 이 메서드에만 게이트가 복제 누락돼 rawSn 하나로 전건 열람이 가능했다(CWE-862/639) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(미승인_PENDING_영상의_datamart_라벨조회는_403이다 / rawSn이_존재하지_않으면_예외없이_403이다) |
| TC-PORTAL-051 (신규) | **데이터마트 라벨 Load 신고 구간 → 412** | APPROVED + `DE_IDNTF_YN='F'` | 동일 | 412(PRECONDITION_FAILED). 신고는 LS_RAW_DATA_STATUS 를 건드리지 않아 APPROVED 게이트만으로는 안 걸린다. resolve('F'→'Y') 로 자동 복원(200) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(비식별신고구간_영상의_datamart_라벨조회는_412이다 / 신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다) |
| TC-PORTAL-052 (신규) | **라벨 Load 페이징 정수 오버플로 → 500 없음** | APPROVED | `page=2147483647&size=100` | 빈 리스트(200). 구현이 `page*size` 를 int 로 계산해 음수로 접히면 `subList` IndexOutOfBounds → 500 이었다(CWE-190/129, 인증된 PORTAL_USER 누구나 트리거). long 연산으로 방어 | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(page가_Integer_MAX_근처여도_오버플로_없이_빈리스트를_반환한다) |
| TC-PORTAL-053 (신규) | 라벨 Load 페이징 clamp 경계 회귀 | APPROVED | size=0/-10 → 1, size=99999/MAX → 100, page=-5/MIN → 0 | clamp 값대로 반환 | unit | Med | PortalLabelService.java, PortalUserLabelServiceTest.java(size가_1미만이면_1로_clamp된다 외 2건) |

## F-3. 데이터마트 사용자 라벨 저장 (단방향 · 원본 미수정 · IDOR)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-040 | 사용자 라벨 저장은 LS_PORTAL_USER_LABEL만 | APPROVED | POST user-labels | 201, 원본 LS_DATA_LBL 불변 | integration | High | PortalLabelService.java |
| TC-PORTAL-041 | 미승인 sourceRawSn 저장 시도 → 403 | 비APPROVED | 저장 | 403 | security | High | PortalLabelService.java |
| ~~TC-PORTAL-042~~ | ~~SKELETON 저장: 17점 삼중값 통과~~ | **폐기 (2026-08-03)** — 포털 키포인트 서버 기능 제거. 대체: TC-PORTAL-058 | - | - | - | - | PortalKeypointRemovedTest.java |
| ~~TC-PORTAL-043~~ | ~~SKELETON 개수≠17 → 400~~ | **폐기** — SKELETON 자체가 400(타입 allowlist 위반) | - | - | - | - | 상동 |
| ~~TC-PORTAL-044~~ | ~~SKELETON v 범위 밖 → 400~~ | **폐기** — 상동 | - | - | - | - | 상동 |
| ~~TC-PORTAL-045~~ | ~~SKELETON NaN/Infinity → 400~~ | **폐기** — 상동 | - | - | - | - | 상동 |
| TC-PORTAL-046 | 빈 좌표('[]','[[]]') 거부 → 400 | type=BBOX, points='[]' | 저장 | 400(빈 row 차단) | unit | High | PortalLabelService.java(saveUserLabel) |
| TC-PORTAL-058 (신규) | **lblTypeCd allowlist — BBOX\|POLYGON 만** | APPROVED | type=SKELETON / SEGMENT / TRACK / 임의 문자열 | 400, 저장 미수행(fail-closed). FE 도구 게이팅은 신뢰 경계가 아니며, allowlist 부재로 16자 이하 임의 문자열이 그대로 `LBL_TYPE_CD` 에 적재됐다. BBOX/POLYGON 은 정상 저장(회귀) | security | High | PortalLabelService.java(validateAndNormalizeType), PortalKeypointRemovedTest.java |
| TC-PORTAL-059 (신규) | **레거시 SKELETON row 조회 안전** | 정책 이전 적재된 삼중값 row 존재 | GET /v1/portal/frames/{srcSn}/labels | 예외 없이 해당 항목만 스킵(500 미발생), BBOX/POLYGON 은 정상 반환 | unit | Med | PortalLabelService.java(parsePoints), PortalKeypointRemovedTest.java |
| TC-PORTAL-095 (신규 · 구 TC-PORTAL-060) | **좌표 개수 상한(CWE-770)** | APPROVED | POLYGON 201점 / 2점, BBOX 3점 | 400. 형제 `PortalUploadLabelService` 상수(BBOX=2, POLYGON 3~200) 재사용 — 길이 상한(65,536자)과 별개 층 | security | Med | PortalLabelService.java(validatePointCount), PortalUserLabelServiceTest.java |
| TC-PORTAL-096 (신규 · 구 TC-PORTAL-061) | **저장 경로 비식별 신고 게이트** | APPROVED + `DE_IDNTF_YN='F'` | POST /v1/portal/user-labels | 412, 저장 미수행. 조회 4경로는 모두 게이트를 갖는데 저장만 누락돼 있었다 | security | Med | PortalLabelService.java(saveUserLabel), PortalUserLabelServiceTest.java(비식별신고구간_영상의_본인라벨_저장은_412이다) |
| TC-PORTAL-097 (신규 · 구 TC-PORTAL-062) | **저장 per-user 속도 제한** | - | 같은 사용자 연속 POST(한도 초과) | 429(TOO_MANY_REQUESTS), 사용자별 격리(타 사용자 무영향). 라벨 행은 삭제 API 가 없어 누적되므로 유입 속도 제한이 자원 방어선 | security | Med | PortalLabelController.java(acquireSavePermit), PortalLabelControllerRateLimitTest.java |
| TC-PORTAL-047 | points @NotBlank NULL/공백 → 400 | null/공백 | 저장 | 400(DTO) | unit | Med | PortalUserLabelRequest.java |
| TC-PORTAL-048 | 본인 작업 라벨 조회 IDOR(token sub) | 타인 저장분 존재 | GET user-labels?rawSn | 본인만 | security | High | PortalLabelService.java |
| TC-PORTAL-049 | 빈 user-label row Load 제외(stale 방어) | pointCn NULL row | Load | 빈 항목 필터 | unit | Med | PortalLabelService.java |
| TC-PORTAL-050 | 손상 좌표 JSON fail-secure | pointCn 손상 | Load | 빈 좌표, 500 미발생 | unit | Med | PortalLabelService.java |
| TC-PORTAL-054 (신규) | **본인 라벨 조회 게이트 대칭 — 미승인 403 / 신고구간 412** | 비APPROVED, 또는 APPROVED + `'F'` | GET /v1/portal/user-labels?rawSn | 403 / 412, 조회 미수행. 형제 경로(datamart labels·frame labels)와 **동일 순서·동일 컴포넌트**. 이 경로만 무게이트라 신고 구간에도 동일 좌표가 다른 URL 로 200 으로 새어나갔다(CWE-862/359) | security | High | PortalLabelService.java, PortalUserLabelServiceTest.java(미승인_영상의_본인라벨_조회는_403이다 / 비식별신고구간_영상의_본인라벨_조회는_412이다 / APPROVED_비신고_영상의_본인라벨_조회는_기존과_동일하게_200이다) |
| TC-PORTAL-055 (신규) | **사용자 라벨 저장 본문 크기 상한(pre-parse)** | - | POST /v1/portal/user-labels, Content-Length > 상한 / chunked | 413 / 411, **체인 미진행**(Jackson 역직렬화 전 차단). 인코딩(`%6C`)·trailing slash·matrix 변형에서도 동일. 구 테스트가 이 경로를 "상한 미적용이 정상"으로 단언해 결함이 고착돼 있었다(CWE-770) | security | High | PortalLabelBodySizeFilter.java, PortalLabelBodySizeFilterTest.java(본인라벨_저장_요청도_본문상한이_적용된다 외 3건) |
| TC-PORTAL-056 (신규) | **points 필드 길이 상한(파싱 후)** | - | points 65,536자 초과 | 400(@Valid). 좌표 JSON 은 문자열이라 타입 구조로 제한되지 않고 적재 컬럼도 TEXT 무제한이었다. 본문 필터(pre-parse)와 **2층 방어**로 서로 대체하지 않는다 | security | High | PortalUserLabelRequest.java, PortalUserLabelRequestValidationTest.java |
| TC-PORTAL-057 (신규) | 경로 판정이 servlet-path-prefix 까지 MVC 와 정합 | `spring.mvc.servlet.path=/api2` 형상 | 라벨 PUT · user-labels POST | 413(상한 적용). 자체 파싱(`RequestPath.parse(uri, ctx)`)은 contextPath 만 반영해 "MVC 는 라우팅, 필터는 스킵" fail-open 이 재발한다 → `ServletRequestPathUtils.parseAndCache` 단일 규약으로 통일(CWE-436 잔여). 동일 결함 클래스의 `WebhookProtectedPaths` 도 함께 정정 | security | Med | PortalLabelBodySizeFilter.java, WebhookProtectedPaths.java, PortalLabelBodySizeFilterTest.java(servlet_path_prefix_설정_환경에서도_MVC와_동일_경로로_판정한다), WebhookProtectedPathsServletPrefixTest.java |

> **ID 채번 주의**: TC-PORTAL-040~050 이 이미 사용 중이라 3차 QA 신규 케이스는 039 + 051~057, 이어서 **058~062** 로 채번했다(섹션 순서와 번호가 연속하지 않는다). ⚠ 구 노트가 057 까지만 기술해 058~062 중복 채번 위험이 있었다(2026-08-03 3차 실측 정정).
> 그중 **060~062 는 F-4 절(포털 SAM2 제거 회귀)의 동명 ID 와 실제로 충돌**해 2026-08-05 에 이 절 쪽을 **095~097 로 재번호**했다(구 060·061·062). 따라서 이 절이 보유한 번호는 **039 · 051~059 · 095~097** 이며, **행 위치는 원래 자리(저장 검증 맥락)를 유지**했다 — 번호 순서로 읽지 말 것.
> ⚠ **다음 신규는 098 부터** 채번한다(구 노트의 "063 부터"는 재번호 이후 무효).

## F-4. 포털 SAM2 (★2026-08-03 **제거 완료** — ADR-013 정합)

> ★확정: CLAUDE.md/ADR-013상 포털 SAM2는 **제공되지 않는다**. 구 정책 위반(엔드포인트·FE 도구 노출)은
> 2026-08-03(dcdbb827) 에 **BE 컨트롤러/서비스 삭제 + FE 도구·단축키·단축키 안내 게이팅**으로 해소됐다
> (08-02 자 67dc48ca 는 이 시점엔 fail-closed 보정만 했고 실제 파일 삭제는 08-03 이었다 — 08-03 정정).
> 따라서 구 TC-PORTAL-060~071·075~077(포털 SAM2 동작 케이스)은 **대상 코드가 존재하지 않아 폐기**한다.
> *(3차 정정 — 구 표기 `075~078` 은 오기였다. TC-PORTAL-078 은 폐기가 아니라 아래 표의 **활성 신규 케이스**다.)*
> 남은 검증은 "제거됐음"의 회귀 고정뿐이며, 내부(INTERNAL) SAM2 는 SFR-08-01(VOS) 핵심 기능으로 무변경이다.
>
> ⚠ 신고 게이트·비식별본 전용 전송(구 TC-PORTAL-075~077)의 **내부 경로 검증은 계속 유효**하며
> `AiInferenceDeidentReportGateTest` 가 담당한다(포털 절만 제거됨).
>
> ✅ **ID 충돌 해소 (3차 발견 F-ISSUE-01 → 2026-08-05 회차 4 에서 해소)** — 이 절의
> `TC-PORTAL-060`·`061`·`062` 가 **F-3 절의 동명 케이스**(좌표 개수 상한 / 저장 경로 신고 게이트 /
> 저장 per-user 속도 제한)와 겹쳐 있었다. F-3 머리말이 "039 + 051~057 로 채번"이라 적어 두고 실제로는
> 058~062 까지 채번해 이 절의 기존 060~062 를 침범한 것이었다. **F-3 쪽을 `095`·`096`·`097` 로
> 재번호**해 해소했다 — 1·2차 검증 결과 문서가 이 3개 ID 를 **본 절(포털 SAM2)** 케이스로 인용하고
> 있어 인용이 깨지지 않는 쪽을 옮겼다. **본 절의 060~062 = 포털 SAM2 제거 회귀**로 그대로 유지된다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과(정책 기준) | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-060 (갱신) | 포털 SAM2 분할 엔드포인트 **미제공 확정** | PORTAL 토큰(채널 통과) | POST /v1/portal/frames/{srcSn}/sam2-segment (path≠body srcSn — 구 컨트롤러면 400) | **404**(핸들러 부재). 400 이 나오면 컨트롤러가 되살아난 것 | integration | High | PortalSam2RemovedTest.java(POST_portal_frames_sam2_segment_엔드포인트는_더이상_존재하지_않는다) |
| TC-PORTAL-061 (갱신) | 포털 SAM2 추적 엔드포인트 **미제공 확정** | PORTAL 토큰 | POST /v1/portal/frames/{srcSn}/sam2-track (path≠body srcSn) | **404**(핸들러 부재) | integration | High | PortalSam2RemovedTest.java(POST_portal_frames_sam2_track_엔드포인트는_더이상_존재하지_않는다) |
| TC-PORTAL-062 (갱신) | 포털 경로에 SAM2 핸들러 매핑 0건 | 앱 기동 | RequestMappingHandlerMapping 스캔 | `/v1/portal/**` 중 `sam2` 포함 패턴 0건 — 경로명을 바꿔 되살리는 퇴행까지 차단 | integration | High | PortalSam2RemovedTest.java(포털_경로에_SAM2_핸들러_매핑이_한_건도_등록되지_않는다) |
| TC-PORTAL-063 (갱신·회귀) | **내부 SAM2 는 영향 없음** | 앱 기동 | 매핑 스캔 | `/v1/frames/{srcSn}/sam2-segment`·`sam2-track` 매핑 잔존(SFR-08-01 VOS) | integration | High | PortalSam2RemovedTest.java(내부_INTERNAL_채널의_SAM2_엔드포인트는_영향받지_않는다) |
| TC-PORTAL-064 (갱신·회귀) | 내부 SAM2 는 포털 토큰에 여전히 403 | PORTAL 토큰 | POST /v1/frames/{srcSn}/sam2-track | 403(채널 격리) — 포털 전용 경로 제거가 내부 경로를 외부에 열지 않았다 | security | High | PortalSam2RemovedTest.java(내부_SAM2_엔드포인트는_포털_토큰에_대해_기존대로_403을_유지한다) |
| TC-PORTAL-065 (갱신) | 소스에 포털 SAM2 잔재 0건 | - | `grep -rn "PortalSam2" backend/src` | 회귀 테스트 파일 외 0건(컨트롤러·서비스·Bulkhead 빈·yml config 전부 제거) | unit | Med | Resilience4jConfig.java, application.yml(resilience4j) |
| TC-PORTAL-072 (갱신) | FE 포털 도구바: AI분할/AI추적/스켈레톤 **미노출** | 포털 모드 | 라벨링 화면 | 세 버튼 모두 부재, BBOX/폴리곤은 잔존(과잉 차단 가드) | unit | Med | ToolBar.test.tsx(구 `DarkToolbar.test.tsx` — 2026-08-07 개명), LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-072a (신규) | FE 포털 단축키 게이팅: G / Shift+T / K 무반응 | 포털 모드 | keydown | activeTool 이 SAM_SEGMENT/TRACK/KEYPOINT 로 바뀌지 않음(버튼만 숨기면 키로 우회됨), B/P 는 정상 동작 | security | Med | useLabelingShortcuts.test.tsx |
| TC-PORTAL-072b (신규) | FE 포털 단축키 **안내**에서도 제외 | 포털 모드 | 단축키 도움말(?) | 'AI 분할'·'AI 추적'·'스켈레톤' 행 미표시(내부 모드는 표시) | unit | Low | ShortcutCheatSheet.test.tsx |
| TC-PORTAL-072c (신규·회귀) | FE 내부 라벨링 도구/단축키는 무변경 | 내부 모드 | 도구바·keydown | 세 도구 버튼 노출 + G/Shift+T/K 정상 전환 | unit | High | ToolBar.test.tsx(구 `DarkToolbar.test.tsx` — 2026-08-07 개명), useLabelingShortcuts.test.tsx |
| TC-PORTAL-073 | FE 포털 라벨링: 검수제출 버튼 미렌더 | 포털 모드 | 화면 | submit-review-button 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-074 | FE 포털 라벨링: VLM/시계열 메타 탭 미노출 | 포털 모드 | 화면 | 메타 탭·VLM 텍스트 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx |
| TC-PORTAL-075~077 (폐기) | 구 포털 SAM2 의 비식별본 전송·신고 게이트 케이스 | - | - | **대상 코드 삭제(2026-08-03 `dcdbb827`)로 폐기.**(3차 정정 — 구 표기 `2026-08-02` 는 오기. 08-02 `67dc48ca` 는 수정만 했고 파일 삭제는 08-03 이며 머리말과 일치시킴) 동일 방어의 내부 경로 검증은 계속 유효 — 신고 구간 412 + `verifyNoInteractions(aiServerClient)` | security | High | AiInferenceDeidentReportGateTest.java(내부 SAM2 분할/추적·YOLO 추적·온라인 오토라벨) |
| TC-PORTAL-078 (신규) | 게이트 없는 `encodeToBase64(String)` 오버로드는 더 이상 존재하지 않는다(원본픽셀 유출 경로 삭제 확인) | - | 정적 확인 | 모든 외부 추론 전송이 `resolveFrameImageForInference`/`encodeFrame`/`encodeDeidentifiedFrameForInference` → private `encode(Path)` 로 수렴, public 문자열 오버로드 부재 | unit | High | FrameImageEncoder.java(주석) |

## F-5. 포털 이미지 업로드 (파일 검증 · all-or-nothing · 경계)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-001 | 정상 다중 이미지 업로드 → 201, UUID·READY | jpg/png ≤20MB | POST /portal/uploads/images | 201, 대표 프레임, READY | integration | High | PortalUploadService.java |
| TC-PORTALUP-002 | 이미지 수 상한(50) 초과 → 400 | 51장 | 업로드 | 400 | unit | High | PortalUploadService.java |
| TC-PORTALUP-003 | 정확히 50장 경계 → 성공 | 50장 | 업로드 | 201 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-004 | 빈 목록 → 400 | empty | 업로드 | 400 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-005 | 개당 20MB 초과 → 400 | 21MB | 업로드 | 400(서비스 검증 `maxImageSizeBytes`, 프로파일 무관 상수) | unit | High | PortalUploadService.java, PortalUploadProperties.java |
| TC-PORTALUP-006 | 20MB 경계값 → 성공 | 정확히 20MB | 업로드 | 201 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-007 | multipart max-file-size 초과 → 413(prd 한정) | prd 프로파일, 22MB | 업로드 | prd: 21MB 상한 초과 → 파싱 단계 413. **local/dev/stg 는 500MB 상한이라 22MB 는 파싱을 통과하고 서비스 20MB 검증(400)에 걸린다** — 구 카탈로그가 프로파일 구분 없이 "22MB→413"으로 서술한 것을 정정(2026-07-28 설정 전수조사로 공통값이 500MB로 승격되고 prd만 21MB override) | integration | Med | application-prd.yml, application.yml, ConfigProfileDriftGuardTest.java |
| TC-PORTALUP-008 | multipart max-request-size 초과 → 거부(prd 한정) | prd 프로파일, 총량 초과 | 업로드 | prd: 1100MB 초과 거부(50×21MB=1050MB+헤드룸 산식). **local/dev/stg 는 1200MB** | integration | Med | application-prd.yml, ConfigProfileDriftGuardTest.java |
| TC-PORTALUP-009 | 확장자 allowlist 밖(gif/svg) → 400 | .gif | 업로드 | 400 | security | High | PortalUploadService.java |
| TC-PORTALUP-010 | 매직바이트 미탐지 → 400 | txt 내용 .jpg | 업로드 | 400(JPEG/PNG만) | security | High | PortalUploadService.java |
| TC-PORTALUP-011 | 확장자↔시그니처 불일치 → 400 | MIME 위조 | 업로드 | 400 | security | High | PortalUploadService.java |
| TC-PORTALUP-012 | truncated JPEG(EOI 없음) → 400 | header-only | 업로드 | 400(CWE-434) | security | Med | PortalUploadService.java |
| TC-PORTALUP-013 | all-or-nothing: 1장 실패 시 전체 미저장 | 50장 중 1장 위조 | 업로드 | 400, 디스크 미기록 | integration | High | PortalUploadService.java |
| TC-PORTALUP-014 | write 후 DB INSERT 실패 시 보상 삭제 | INSERT 예외 | 업로드 | 롤백+고아 파일 삭제 | integration | High | PortalUploadService.java |
| TC-PORTALUP-015 | 디스크 write IOException 시 롤백 | 디스크 고갈 | 업로드 | INTERNAL_ERROR+롤백 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-016 | 저장 파일명 UUID 강제(path traversal 없음) | 경로형 파일명 | 업로드 | UUID.ext 저장 | security | High | PortalUploadService.java |
| TC-PORTALUP-017 | 원본명 255자 초과 truncate | 긴 파일명 | 업로드 | 255자 절단 | unit | Low | PortalUploadService.java |
| TC-PORTALUP-018 | 업로드 per-user rate limit 초과 → 429 | 폭주 | 연속 업로드 | 429(CWE-770) | security | High | PortalUploadController.java |

## F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-020 | 본인 자산 목록 페이징 | 소유 자산 | GET /portal/uploads | 본인 것만 | integration | High | PortalUploadService.java |
| TC-PORTALUP-021 | type 필터 IMAGE/VIDEO (대소문자 무관 — `toUpperCase` 정규화) | - | ?type=IMAGE / ?type=image | 필터 결과 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-022 | 미지원 type → 400 | ?type=FOO | 목록 | 400 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-023 | 페이지 크기 하드캡(100) | size=500 | 목록/프레임 | 100 클램프 | unit | Med | PortalUploadController.java |
| TC-PORTALUP-024 | 타 사용자 자산 상세 → 403 | 타인 uldSn | GET /{uldSn} | 403(부재와 동일) | security | High | PortalUploadService.java |
| TC-PORTALUP-025 | 타 사용자 프레임 목록 → 403 | 타인 uldSn | /{uldSn}/frames | 403 | security | High | PortalUploadService.java |
| TC-PORTALUP-026 | 타 사용자 프레임 이미지 → 403(IDOR) | 타인 uldFrmeSn | /frames/{uldFrmeSn}/image | 403 | security | High | PortalUploadService.java |
| TC-PORTALUP-027 | 프레임 이미지 서빙: DB MIME+nosniff, 신고 게이트 대상 아님 | 소유 이미지(본인 업로드분) | 서빙 | Content-Type=저장MIME, nosniff — **비식별 신고 게이트·no-store 통일 대상 아님**(ADR-013 예외, LS_DATA_RAW 라이프사이클 없음. TC-PORTAL-037 과 혼동 금지) | security | High | PortalUploadService.java |
| TC-PORTALUP-028 | 이미지 서빙 Path Traversal 차단 | 조작 경로 | 서빙 | resolveSafe 거부 | security | High | PortalUploadService.java |
| TC-PORTALUP-029 | 자산 삭제: 파일 먼저 삭제 후 DB CASCADE | READY | DELETE /{uldSn} | 204, 파일+행 삭제 | integration | High | PortalUploadService.java |
| TC-PORTALUP-030 | PROCESSING 중 삭제 → 409 | 추출 진행중 | DELETE | 409 | unit | High | PortalUploadService.java |
| TC-PORTALUP-031 | 파일 삭제 IOException 시 DB 행 보존 5xx | 파일 삭제 실패 | DELETE | 5xx, DB 미삭제 | unit | Med | PortalUploadService.java |
| TC-PORTALUP-032 | 타 사용자 자산 삭제 → 403 | 타인 uldSn | DELETE | 403 | security | High | PortalUploadService.java |

## F-7. 포털 업로드 라벨 CRUD (전체교체 · 상한 · READY 가드 · 다운로드)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-040 | 라벨 전체교체(PUT) 멱등, 빈 배열=전체 삭제 | READY | PUT labels [] | 전체 삭제 | integration | High | PortalUploadLabelService.java |
| TC-PORTALUP-041 | 검증 실패 시 DELETE 미실행(기존 유지) | 1건 위조 | PUT | 400, 기존 보존 | integration | High | PortalUploadLabelService.java |
| TC-PORTALUP-042 | lblTypeCd allowlist(BBOX/POLYGON)만 | type=SEGMENT | PUT | 400 fail-closed | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-043 | 라벨 배열 상한 500 초과 → 400 | 501건 | PUT | 400(@Size+서비스 이중) | security | High | PortalUploadLabelController.java |
| TC-PORTALUP-044 | BBOX 좌표 2점 아님 → 400 | BBOX 3점 | PUT | 400 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-045 | POLYGON 3~200점 경계 → 400 | 2점/201점 | PUT | 400 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-046 | 좌표 NaN/Infinity → 400 | Inf | PUT | 400 | security | Med | PortalUploadLabelService.java |
| TC-PORTALUP-047 | label 80자 초과 → 400 | 81자 | PUT | 400 | unit | Low | PortalUploadLabelService.java |
| TC-PORTALUP-048 | READY 외 상태 라벨 PUT → 409 | 비READY | PUT | 409 | unit | High | PortalUploadLabelService.java |
| TC-PORTALUP-049 | 동시 PUT 프레임 락 직렬화 | 병렬 PUT | 동일 프레임 | 비관적 락 | integration | Med | PortalUploadLabelService.java |
| TC-PORTALUP-050 | 타 사용자 프레임 라벨 PUT → 403 | 타인 uldFrmeSn | PUT | 403 | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-051 | 라벨 PUT 본문 2MB 초과 → 413(파싱 전 조기) | 대용량 | PUT | 413(pre-parse DoS) | security | High | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-052 | 라벨 PUT chunked/Content-Length 부재 → 411 | -1 | PUT | 411 | security | High | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-053 | body size 필터 라벨 PUT 경로만 적용 | 다른 경로 | 다른 PUT | 필터 skip | unit | Low | PortalLabelBodySizeFilter.java |
| TC-PORTALUP-054 | 라벨 목록 조회(소유자 스코프), 타인/부재 403 | 타인 프레임 | GET labels | 403 | security | Med | PortalUploadLabelService.java |
| TC-PORTALUP-055 | export JSON: 고정명+라벨 N+1 회피 | 소유 자산 | GET export | JSON attachment | integration | Med | PortalUploadLabelService.java |
| TC-PORTALUP-056 | 원본 다운로드 Content-Disposition CRLF 인젝션 차단 | 파일명 CRLF | GET file | 제어문자 제거, filename* 인코딩 | security | High | PortalUploadLabelService.java |
| TC-PORTALUP-057 | 원본 다운로드: 경로 미확정/부재 → 404 | filePathNm null | GET file | 404 | unit | Med | PortalUploadLabelService.java |
| TC-PORTALUP-058 | 타 사용자 export/다운로드 → 403 | 타인 uldSn | export/file | 403 | security | High | PortalUploadLabelService.java |

> F-7 은 PortalUploadLabelService.java·PortalUploadLabelController.java 가 2026-07-25 이후 **무변경이 아니다** — `git log --since=2026-07-25` 에 476bc91a(2026-07-30, 서빙 경로 링크추종 폐쇄)·dcdbb827(2026-08-03, 2차 검증 HIGH 11건 수정)로 2건 히트한다(구 회차 노트가 잘못 기록됨, 08-03 정정). 이번 회차에서 위 두 파일 전체 근거 라인을 재확인해 정정했다(값은 대부분 유지, 라인만 이동).

## F-8. 포털 영상 TUS 업로드 (세션 · 재개 · 완료검증 · 동시성)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-TUS-001 | OPTIONS: TUS 능력 광고(max 5GB) | PORTAL_USER | OPTIONS tus | 204+Tus-Max-Size=5368709120 | integration | Med | PortalTusUploadController.java |
| TC-TUS-002 | POST 세션 생성 → 201+Location | 유효 Upload-Length·확장자 | POST | 201, Location /tus/{uldId} | integration | High | PortalVideoUploadService.java |
| TC-TUS-003 | Upload-Length 누락 → 400 | 헤더 없음 | POST | 400 | unit | Med | PortalTusUploadController.java |
| TC-TUS-004 | Upload-Length 5GB 초과 → 413 | >maxFileSizeBytes | POST | 413 | security | High | PortalVideoUploadService.java |
| TC-TUS-005 | 확장자 allowlist(mp4/mov/avi) 밖 → 400 | .exe | POST | 400 | security | High | PortalVideoUploadService.java |
| TC-TUS-006 | 동시 진행 세션 상한(3) 초과 → 429 | IN_PROGRESS 3건 | POST | 429 | security | Med | PortalVideoUploadService.java |
| TC-TUS-007 | 저장 파일명 UUID 강제(경로순회 차단) | 경로형 메타 | POST | UUID.ext | security | High | PortalVideoUploadService.java |
| TC-TUS-008 | Upload-Metadata 1KB 초과 → 413 | 큰 메타 | POST | 413 | security | Low | PortalTusUploadController.java |
| TC-TUS-009 | Upload-Metadata base64 디코딩 실패 → 400 | 잘못된 base64 | POST | 400 | unit | Low | PortalTusUploadController.java |
| TC-TUS-010 | 지원 안 되는 Tus-Resumable → 412 | 버전 불일치 | 모든 메서드 | 412 | unit | Med | PortalTusUploadController.java |
| TC-TUS-011 | HEAD offset 조회(재개) | 소유 세션 | HEAD | Upload-Offset/Length+no-store | integration | High | PortalTusUploadController.java |
| TC-TUS-012 | **★타 사용자 세션 HEAD/PATCH/DELETE → 404 (정정 2026-08-07 · 구 403 폐기)** | 타인 세션 | 각 메서드 | **404**(IDOR 차단은 그대로이나 응답이 바뀌었다). 구 기대값 **403 은 폐기** — 403 은 "그 세션은 있는데 네 것이 아니다"가 되어 **응답 코드 자체가 세션 존재 오라클**이 된다(CWE-209). 미존재와 **구분 불가능**해야 한다 → TC-TUS-033 | security | High | PortalVideoUploadService.java(`getForOwner` · `cancel`) · PortalVideoUploadTxService.java(`appendChunk` · `sessionNotFound`) |
| TC-TUS-013 | 만료(24h) 세션 HEAD → 410 | expiresAt 경과 | HEAD | 410 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-014 | PATCH 청크 append offset 전진 | 진행중 | PATCH | 204+새 Upload-Offset | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-015 | PATCH Upload-Offset 누락 → 400 | 헤더 없음 | PATCH | 400 | unit | Med | PortalTusUploadController.java |
| TC-TUS-016 | offset 불일치(재개 무결성) → 409 | expected≠서버 | PATCH | 409 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-017 | offset 범위 밖(음수/length 초과) → 400 | 잘못된 offset | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-018 | 청크 크기 상한(16MB) 초과 → 413 | >maxChunk | PATCH | 413 | security | Med | PortalVideoUploadTxService.java |
| TC-TUS-019 | 청크 길이가 잔여 용량 초과 → 400 | offset+len>length | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-020 | 동시 PATCH 낙관적 락 충돌 → 409+truncate 복원 | 병렬 PATCH | 동일 세션 | 409, offset 복원 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-021 | 취소된 세션 PATCH → 409 | CANCELLED | PATCH | 409 | unit | Med | PortalVideoUploadTxService.java |
| TC-TUS-022 | 완료 세션 마지막 청크 재전송 → 멱등 | COMPLETED | PATCH | 완료 응답(uldSn 재반환) | unit | High | PortalVideoUploadTxService.java |
| TC-TUS-023 | 완료 검증: 매직바이트 불일치 → 거부(CANCELLED)+400 | 위조 컨테이너 | 최종 청크 | 400+파일 삭제+CANCELLED | security | High | PortalVideoUploadService.java |
| TC-TUS-024 | 완료 검증: ffprobe 실패 → 400+CANCELLED | 손상 파일 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-025 | 완료 검증: 비디오 스트림 없음 → 400 | 오디오만 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java |
| TC-TUS-026 | 완료 검증은 락/트랜잭션 밖(커넥션 점유 방지) | 대용량 | 최종 청크 | 짧은 tx 후 ffprobe | integration | Med | PortalVideoUploadService.java |
| TC-TUS-027 | 완료 원자 전이(멱등): affectedRows==1만 이벤트 | 동시 완료 | finalizeCompleted | 1회만 이벤트 | integration | High | PortalVideoUploadTxService.java |
| TC-TUS-028 | 검증 중 취소된 세션 완료 시도 → 409+ULD 보상 삭제 | 완료 중 cancel | finalizeCompleted | 409, ULD 삭제 | integration | Med | PortalVideoUploadTxService.java |
| TC-TUS-029 | DELETE 세션 취소+임시파일 삭제 | 진행중 | DELETE | 204+파일 삭제+CANCELLED | integration | Med | PortalVideoUploadService.java |
| TC-TUS-030 | 완료 세션 DELETE → no-op(영구 파일 삭제 금지) | COMPLETED | DELETE | 파일 미삭제 | unit | High | PortalVideoUploadService.java |
| TC-TUS-031 | cancel도 행 잠금(PATCH와 직렬화) | 병렬 PATCH+cancel | DELETE | 락 통일 | integration | Med | PortalVideoUploadService.java |
| TC-TUS-032 | TUS 저장 경로 Path Traversal 차단 | 조작 경로 | 세션 생성 | resolveSafe 거부 | security | High | PortalVideoUploadService.java |
| TC-TUS-033 | **★소유자 불일치와 미존재는 상태코드도 메시지도 동일하다 — 3경로 (신설 2026-08-07 · 핵심 가드)** | ①존재하지 않는 `uldId` ②타인 소유 세션 | `HEAD`·`PATCH`·`DELETE /v1/portal/uploads/tus/{uldId}` 각각 | 두 입력의 응답이 **완전히 동일**(404 + 같은 문구). 응답 생성은 팩토리 **한 곳**(`sessionNotFound`)에서만 한다 — 두 사유를 인라인으로 나누면 다음 수정에서 한쪽 문구만 바뀌어 **코드는 같은데 메시지가 실재를 알려주는** 상태로 되돌아간다. ⚠ **3경로 전부여야 한다** — 한 경로라도 403 을 남기면 공격자가 그 경로로 같은 판별을 할 수 있어 나머지 차단이 무의미해진다(오라클은 가장 느슨한 경로를 따라간다) | security | High | PortalVideoUploadTxService.java(`sessionNotFound`) · PortalVideoUploadServiceTest.java(`HEAD_소유자불일치와_미존재는_상태코드도_메시지도_동일하다` · `DELETE_소유자불일치와_미존재는_상태코드도_메시지도_동일하다` · `PATCH_소유자불일치와_미존재는_상태코드도_메시지도_동일하다`) |
| TC-TUS-034 | 소유자 불일치 거부 시 **부수효과 0** (신설 2026-08-07) | 타인 세션 | `PATCH`(청크 동반) · `DELETE` | PATCH → 파일에 **한 바이트도 기록되지 않음**·offset 미전진 / DELETE → **임시파일 미삭제·세션 미취소**. 거부가 offset·완료 검사·파일 삭제보다 **먼저** 평가된다 | security | High | PortalVideoUploadTxService.java(`appendChunk`) · PortalVideoUploadService.java(`cancel`) · PortalVideoUploadServiceTest.java(`nonOwnerPatchNotFound` · `nonOwnerHeadDeleteNotFound`) |
| TC-TUS-035 | 인증 401 · 역할 403 · 만료 410 · offset 409 는 **불변** (신설 2026-08-07 · 하위호환 회귀 가드) | ①토큰 없음 ②PORTAL_USER 아닌 역할 ③소유자 본인 + 만료 세션 ④소유자 본인 + offset 불일치 | 각 경로 | ①401 ②403 ③410 ④409 — 종전 그대로(TC-TUS-013·016 과 동일). 이번 변경은 **인가를 통과한 호출자의 소유자 판정**만 404 로 바꿨다. 미인증·미권한까지 404 로 뭉개면 인증 실패 원인을 알 수 없어지고, 소유자 본인의 정상 재개 흐름이 404 로 뭉개지면 재개 자체가 깨진다 | security | High | SecurityConfig.java · PortalVideoUploadService.java(`getForOwner`) · PortalVideoUploadTxService.java(`appendChunk`) |

> **⚠ 소유자 불일치 응답은 내부 업로드(`/v1/uploads/{uploadId}`)와 같은 규약**이다 — [B-23](B-batch-deidentify.md) TC-ULD-051~056. 두 경로 중 한쪽만 403 으로 되돌리면 그 경로가 오라클로 남는다.

> F-8 은 PortalTusUploadController.java·PortalVideoUploadService.java 자체는 2026-07-25 이후 무변경(라인 유지). `PortalVideoUploadTxService.java`는 9dfa0d6e(`PortalUploadProperties` record 전환)로 생성자에서 `@Value` import 1줄이 빠지며 이후 라인이 전체 **-1** 이동 — TC-TUS-014~028 라인을 재확인해 반영(값·순서·응답코드는 변경 없음).

## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-060 | 영상 완료 후 AFTER_COMMIT → 비동기 추출 | 완료 이벤트 | PortalVideoUploadedEvent | 별도 풀에서 추출 시작 | integration | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-061 | UPLOADED→PROCESSING 원자 전이 실패 → 중단 | 이미 삭제 | beginProcessing | skip | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-062 | 프레임 간격 sysconfig 스냅샷(기본 5초) | config 5 | 추출 | 5초 간격 — **확정(UNCERTAINTIES #5 해소)**: DB 시드값(V109) = 코드 폴백(`DEFAULT_INTERVAL_SEC`) = **5**, 60 아님 | unit | Med | PortalFrameExtractRunner.java, V109__create_ls_portal_uld.sql, ConfigKeys.java |
| TC-PORTALUP-063 | sysconfig 미설정/오류 시 5초 폴백 | config 부재 | 추출 | DEFAULT_INTERVAL_SEC=5 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-064 | 프레임 간격 config 경계 [1,600] | 검증 | - | 범위 밖 거부 | unit | Low | ConfigKeys.java |
| TC-PORTALUP-065 | maxFrames 2000 초과 → 균등 샘플링 | 긴 영상 | 추출 | ≤2000 균등 | unit | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-066 | maxFrames 경계: 정확히 2000 | - | computeFrameNumbers | 2000 이하 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-067 | 영상 길이<간격 → 최소 1프레임 보장 | 짧은 영상 | 추출 | 프레임 1개 | unit | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-068 | fps 미상 시 30 폴백 | probe fps 0 | 추출 | DEFAULT_FPS=30 | unit | Low | PortalFrameExtractRunner.java |
| TC-PORTALUP-069 | 추출 성공 후 원자 커밋 READY+메타 | 전체 추출 | completeReady | READY | integration | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-070 | ffmpeg/probe 실패 → 부분 파일 정리+FAILED | 예외 | 추출 | FAILED+파일 정리 | unit | High | PortalFrameExtractRunner.java |
| TC-PORTALUP-071 | 진행 중 자산 삭제 감지 → 중단+정리 | mid-extract 삭제 | touchProcessing | abort+cleanup | integration | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-072 | 프레임 출력 경로 Path Traversal 차단 | 조작 uldSn | resolveSafeFramesDir | root 밖 거부 | security | Med | PortalFrameExtractRunner.java |
| TC-PORTALUP-073 | @Async 예외 전파 금지(runner 흡수) | 예외 | runAsync | 로그만 | unit | Low | PortalFrameExtractRunner.java |

> `PortalFrameExtractRunner.java`는 2026-07-25 이후 무변경 — 라인 전부 유지, `properties.maxFrames()` 시그니처도 `PortalUploadProperties` record 전환의 영향을 받지 않음(호출부 불변).

## F-10. 상태 전이 · 정리 스윕 / F-11. 내부 파이프라인 분리

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-080 | LsPortalUld 상태 전이 비즈니스 메서드만 | - | markProcessing/Ready/Failed | @Setter 없이 전이 | unit | Med | LsPortalUld.java |
| TC-PORTALUP-081 | 이미지 업로드는 즉시 READY | 이미지 | uploadImages | markReady(null,null,1) | unit | Med | PortalUploadService.java |
| TC-PORTALUP-082 | 만료 TUS 세션 스윕: 임시파일+행 제거 | EXPRY_DT 경과 | 30분 스윕 | 세션 정리 | integration | Med | PortalUploadSweepJob.java |
| TC-PORTALUP-083 | 고착 자산(30m 정지) → FAILED+프레임 정리 | stuck-timeout 경과 | 스윕 | FAILED, 원본 보존 | integration | Med | PortalUploadSweepJob.java |
| TC-PORTALUP-084 | 스윕 프레임 디렉토리 정리 root 가드 | 경로 검증 | cleanupFrameDir | root 밖 skip | security | Low | PortalUploadSweepJob.java |
| TC-PORTALUP-085 | 스윕 트랜잭션 경계 별 위임(프록시 우회 방지) | - | run | txService 위임 | unit | Low | PortalUploadSweepJob.java |
| TC-PORTALUP-086 (신규) | 만료 TUS 세션 정리는 조건부 DELETE로 2노드 중복 실행 안전 | 2노드 동시 스윕 | claimExpiredSessions | 한 노드만 1행 삭제(파일 정리도 그 노드만), 다른 노드는 0행(멱등, 예외 없음) — Active-Active 배포(CLAUDE.md "배치 성능") 대응 | integration | Med | PortalUploadSweepTxService.java, LsPortalTusUploadRepository.java |
| TC-PORTALUP-087 (신규) | 고착 자산 FAILED 전이도 조건부 UPDATE로 2노드 중복 방지 | 2노드 동시 스윕 | failStuckUploads | 조건부 UPDATE(`failIfInStatus`, 상태 재확인 WHERE)로 한쪽만 1행 전이 | integration | Med | PortalUploadSweepTxService.java |
| TC-PORTAL-090 | 포털 업로드는 LS_PORTAL_* 전용, 내부 미참조 | 라벨 서비스 | - | 포털 3종 리포지토리만 | integration | High | PortalUploadLabelService.java |
| TC-PORTAL-091 | 포털 업로드 영상 비식별 미적용 | 영상 업로드 | 완료 | 비식별 파이프라인 미연결 | integration | High | PortalVideoUploadService.java |
| TC-PORTAL-092 | 포털 사용자 라벨 저장이 LS_DATA_LBL 불변 | user-label 저장 | 실행 | 데이터마트 원본 오염 없음 | integration | High | PortalLabelService.java |
| TC-PORTAL-093 | 포털 프레임 추출 풀 관제 배치와 격리 | 추출 실행 | - | portalExtractExecutor 별도 풀 | integration | Med | PortalFrameExtractRunner.java |
| TC-PORTAL-094 | 포털 자산은 데이터마트 View 미노출 | 포털 자산 존재 | 데이터마트 뷰 | 미포함 | integration | Med | PortalUploadLabelService.java |

## F-12. 데이터마트 ZIP 다운로드 · 보존기간 만료 자동 삭제 (★2026-08-17 신설 — 구 V1.5 "포털 다운로드는 포털 자체 책임" 정책 폐기)

> ★확정(2026-08-17, 사용자 확정): 구 V1.5 "포털 데이터마트 다운로드는 포털 자체 책임 · 다운로드 기간
> 제한 미해소"는 **뒤집혔다**. `GET /v1/portal/datamart/videos/{rawSn}/download`(라벨 JSON + 비식별
> 프레임 이미지 + 비식별 영상 ZIP)와 **보존기간 만료 자동 삭제**(데이터마트 저장 라벨·업로드 자산)가
> 신설됐다 — [UNCERTAINTIES.md #11](UNCERTAINTIES.md) 참조.
>
> ⚠ **화면(SCREEN-028) 다운로드 버튼은 이번 범위 밖이다.** 백엔드 API만 신설됐고, 포털 홈 다운로드
> 버튼 배선은 별도 라운드에서 진행한다. 아래 TC-PORTAL-072/073 및 `LabelingPagePortalRestrictions.test.tsx`
> 계열 FE 회귀 테스트는 여전히 "다운로드 UI 없음"을 단언하며 이번 변경으로 손대지 않는다.
>
> ⚠ **F-7 절의 `TC-PORTALUP-055~058`("원본 다운로드")과는 축이 다르다** — 그건 포털 **업로드 자산**
> (본인 이미지/영상 업로드, `PortalUploadLabelService`)의 export/원본 파일 다운로드이고, 이 절은
> **데이터마트 영상**(관제 인입 → 검수 완료 영상, `PortalDatamartDownloadService`)의 작업 데이터
> 다운로드다. 혼동 금지.

### F-12a. 데이터마트 작업 데이터 ZIP 다운로드 (API-203)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-098 (신규) | 정상 다운로드 — labels.json + 비식별 프레임 이미지 + 비식별 영상 | APPROVED, 본인 저장 라벨 존재, 비식별 영상 존재 | GET /v1/portal/datamart/videos/{rawSn}/download | 200, ZIP({rawSn}/labels.json + {rawSn}/frames/{FRM_NO 4자리 zero-pad}.jpg + {rawSn}/video.{ext}) | integration | High | PortalDatamartDownloadController.java(download), PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadService.java(download) |
| TC-PORTAL-099 (신규) | 데이터마트 미노출(비APPROVED 또는 미존재) → 403, 신고 상태를 묻지 않는다 | 비APPROVED 또는 미존재 rawSn | 동일 | 403(FORBIDDEN). 판정 순서가 고정(③노출→④신고→⑤라벨0건)이라 미노출 영상의 신고 여부가 응답으로 새지 않는다(CWE-209 오라클 차단) | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(notApproved_forbidden_beforeDeidentGate) |
| TC-PORTAL-100 (신규) | 비식별 누락 신고 구간(`DE_IDNTF_YN='F'`) → 412 | APPROVED + 신고 중 | 동일 | 412(PRECONDITION_FAILED), ZIP 미생성. `resolveDeidPath` 호출 이전에 독립 게이트로 평가 — 신고 영상이 "영상 없는 200"으로 새지 않는다 | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(underDeidentReport_preconditionFailed) |
| TC-PORTAL-101 (신규) | 본인 저장 라벨 0건 → 410 | APPROVED + 비신고, 본인 저장분 없음(신규 미작업 또는 보존기간 만료 삭제) | 동일 | 410(GONE) | unit | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(noSavedLabel_gone) |
| TC-PORTAL-102 (신규) | per-user 속도 제한 초과 → 429 | 같은 사용자 연속 GET(분당 3회 초과) | 동일 | 429(TOO_MANY_REQUESTS), 자원 판정(403/412/410) 이전에 차단되어 그 비용이 발생하지 않는다. 사용자별 격리(타 사용자 무영향) | security | High | PortalDatamartDownloadController.java(acquireDownloadPermit), PortalDatamartDownloadControllerRateLimitTest.java(과도한_다운로드_요청은_429로_제한되고_자원_판정에_도달하지_않는다 · 제한은_사용자별로_격리된다_다른_사용자는_영향없음) |
| TC-PORTAL-103 (신규) | 원본 폴백 없음 — 비식별 경로 부재 시 원본 영상은 어떤 경우에도 담기지 않는다 | 비식별 경로 null | 동일 | 원본(비식별 이전) 영상은 ZIP에 포함되지 않는다(AC-034 불변 규칙). 서브트리 검증 실패 프레임도 같은 원칙(`resolveFrameImages`)으로 사유 코드만 로그에 남기고 조용히 빠지나, 이 조합의 전용 단위 테스트는 아직 없다 | security | High | PortalDatamartDownloadService.java(resolveDeidVideo, resolveFrameImages), PortalDatamartDownloadServiceTest.java(noDeidVideo_neverFallsBackToOriginal) |
| TC-PORTAL-104 (신규) | 비식별 영상이 없으면 영상 없이 라벨·이미지만 담긴다 | APPROVED, 비식별 영상 이력 없음 | 동일 | 200, ZIP에 video.* 엔트리가 생성되지 않는다(labels.json + frames만) | integration | Med | PortalDatamartDownloadService.java(resolveDeidVideo), PortalDatamartDownloadServiceTest.java(noDeidVideo_zipHasLabelsAndFramesOnly) |
| TC-PORTAL-105 (신규) | 다른 사용자의 저장 라벨은 담기지 않는다(본인만) | 타 사용자 저장분 존재 | 동일 | labels.json 에 본인 저장분만 반영, 타 사용자 저장분은 병합 대상 아님 | security | High | PortalDatamartDownloadTxService.java(plan), PortalDatamartDownloadServiceTest.java(otherUsersLabelsNotIncluded) |
| TC-PORTAL-106 (신규) | 응답은 no-store, 파일명은 서버 생성 고정명 | - | 동일 | `Cache-Control: no-store`(신고 게이트가 매 요청 재평가되어야 함), `Content-Disposition` 파일명에 사용자 입력 미포함(CWE-113 여지 구조적으로 없음) | security | Med | PortalDatamartDownloadService.java(download), PortalDatamartDownloadServiceTest.java(responseHeaders) |

### F-12b. 보존기간 만료 자동 삭제 — 축 A: 데이터마트 저장 라벨

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-107 (신규) | 보존기간(기본 7일) 경과 시 그룹 조건부 DELETE — 2노드 멱등 | (사용자, 영상) 그룹의 `MAX(REG_DT)` 가 커트라인보다 이전 | 스윕 실행 | 해당 그룹 라벨 삭제, 2노드 동시 실행돼도 두 번째 노드는 0행(파일이 없어 클레임 단계 불요) | integration | High | PortalRetentionSweepTxService.java(sweepDatamartLabels), LsPortalUserLabelRepository.java(findExpiredLabelGroups, deleteExpiredLabelGroup), PortalRetentionSweepTxServiceTest.java(만료_그룹마다_조건부_삭제를_호출하고_실제로_지워진_그룹만_센다) |
| TC-PORTAL-108 (신규) | 보존기간 설정(`portal.datamart.retention-days`) 부재 시 미삭제 — 조회조차 하지 않는다 | 설정 행 없음 | 스윕 실행 | 그 회차를 skip(ERROR 로그), 폴백 상수 없이 0건 반환 — 파괴적 배치는 fail-open 하지 않는다 | security | High | PortalRetentionSweepTxService.java(sweepDatamartLabels), PortalRetentionSweepTxServiceTest.java(데이터마트_보존기간_설정이_없으면_라벨을_한_건도_지우지_않고_조회조차_하지_않는다) |
| TC-PORTAL-109 (신규) | `myLabelExpiresAt` 은 저장 컬럼이 아니라 조회 시점 파생값 | 보존기간 7→14일로 변경 후 재조회 | GET /v1/portal/datamart/videos | 만료 예정 시각이 다음 조회부터 즉시 갱신(마이그레이션·백필 없음). 본인 저장분이 없으면 null, 보존기간 설정이 없으면 그 필드만 null(목록 전체는 정상 200) | unit | Med | PortalLabelService.java(listDatamartVideos), PortalDatamartVideosServiceTest.java(포털_데이터마트_목록_보존기간을_7에서_14로_바꾸고_재조회하면_만료예정시각이_갱신된다_AC033 · 포털_데이터마트_목록_보존기간_설정이_없으면_만료예정시각만_null이고_목록은_정상_반환) |

### F-12c. 보존기간 만료 자동 삭제 — 축 B: 업로드 자산 (READY·FAILED 독립 판정)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(파일) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-088 (신규) | READY 자산 만료 시 파일 먼저 → DB 나중 순서로 삭제 | READY, 보존기간(기본 7일) 경과 | 스윕 실행 | 파일 삭제 성공 후에만 DB 행 삭제. 순서가 뒤집히면 DB 가 먼저 사라져 어느 파일을 지워야 하는지 알 수 없어진다 | integration | High | PortalRetentionSweepJob.java(sweepExpiredUploads), PortalRetentionSweepJobTest.java(파일을_먼저_지운_뒤에_DB행을_지운다) |
| TC-PORTALUP-089 (신규) | FAILED(기본 1일)와 READY(기본 7일)는 서로 다른 보존기간으로 독립 판정된다 | 같은 시각 등록, 한쪽은 FAILED 한쪽은 READY | 스윕 실행 | FAILED 는 1일 경과로 먼저 삭제되고, READY 는 7일이 안 지나 남는다(AC-037) | integration | High | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepIT.java(같은_시각_등록이어도_FAILED는_1일로_삭제되고_READY는_7일이라_남는다) |
| TC-PORTALUP-090 (신규) | PROCESSING·UPLOADED 자산은 삭제 대상이 될 수 없다(만료 없음) | PROCESSING, 등록일로부터 보존기간 경과 | 스윕 실행 | 후보 쿼리 자체가 READY/FAILED 상태 리터럴로 스코프돼 있어 DB 행·파일 모두 그대로 남는다(AC-036) | security | High | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepIT.java(처리중_자산은_등록일로부터_보존기간이_지나도_DB행과_파일이_모두_남는다) |
| TC-PORTALUP-091 (신규) | READY 설정만 부재 시 READY 축만 skip, FAILED 축은 정상 동작(축 독립) | `portal.upload.retention-days` 부재, `portal.upload.failed-retention-days` 존재 | 스윕 실행 | READY 후보 0건(ERROR 로그) + FAILED 후보는 정상 조회·삭제 — 한쪽 설정 부재가 다른 축을 막지 않는다 | security | Med | PortalRetentionSweepTxService.java(findExpiredUploads), PortalRetentionSweepTxServiceTest.java(READY_설정만_없으면_READY축만_건너뛰고_FAILED축은_그대로_동작한다) |
| TC-PORTALUP-092 (신규) | 파일 경로 판정 실패(ESCAPED/UNRESOLVABLE) 시 DB 행 보존 — 다음 회차 재후보 | 저장 경로가 허용 서브트리 밖을 가리킴 | 스윕 실행 | 파일 삭제를 건너뛰고 DB 행도 지우지 않는다(fail-closed — 원본 삭제보다 고아 파일 존치가 안전). 그 자산의 실패가 나머지 자산 처리를 막지 않는다 | security | High | PortalRetentionSweepJob.java(deleteFiles), PortalRetentionSweepJobTest.java(경로_중간_디렉터리가_저장루트_밖을_가리키면_DB행을_지우지_않고_대상파일도_살아있다 · 저장루트_밖_절대경로가_적재돼_있으면_그_자산을_건너뛰고_DB행을_남긴다 · 한_자산의_경로_판정_실패가_다음_자산_처리를_막지_않는다) |
| TC-PORTALUP-093 (신규) | 파일이 이미 없어도(ABSENT) 멱등 성공으로 DB 행을 지운다 | 파일 부재(이미 삭제됨) | 스윕 실행 | ABSENT 는 실패가 아니라 "지울 것이 없다"로 통과 — DB 행 삭제까지 정상 완료(재시도 삭제가 영구 실패하지 않게) | unit | Med | PortalRetentionSweepJob.java(deleteFiles), PortalRetentionSweepJobTest.java(파일이_이미_없어도_정상_처리해_DB행을_지운다) |
| TC-PORTALUP-094 (신규) | 2노드 동시 스윕은 조건부 DELETE 로 한쪽만 삭제(멱등, 오류 없음) | 2노드 동시 실행 | deleteExpiredReady/deleteExpiredFailed | 같은 커트라인으로 재판정하는 조건부 UPDATE/DELETE — 먼저 처리한 노드만 1행, 다른 노드는 0행이고 예외가 나지 않는다 | integration | High | LsPortalUldRepository.java(deleteExpiredReady, deleteExpiredFailed), PortalRetentionSweepIT.java(같은_후보에_배치를_두_번_돌려도_두_번째는_0행이고_오류가_없다) |
| TC-PORTALUP-095 (신규) | `expiresAt` 노출 — READY/FAILED 기준점이 다르다 | READY(등록일·라벨 최종 저장일 중 늦은 쪽), FAILED(FAILED 전이 시각) | GET /portal/uploads, GET /portal/uploads/{uldSn} | 목록·상세 응답에 `expiresAt` 동봉, 두 상태가 같은 판정기(`PortalRetentionPolicy`)를 쓰되 기준점만 다르다. PROCESSING/UPLOADED 는 `expiresAt=null`(고지할 만료 자체가 없음) | unit | Med | PortalUploadService.java(listUploads, getUpload), PortalRetentionPolicyTest.java |
| TC-PORTALUP-096 (신규) | 보존일수 하한(1) — 0/음수 설정 저장은 거부된다 | 설정 변경 요청 | `portal.datamart.retention-days`=0 또는 음수 | 400(입력값 검증 실패), 저장 미수행. 0 은 "오늘 것까지 지운다", 음수는 미래 시각이 커트라인이 되어 전량이 대상이 된다 — 복구 수단이 없는 파괴적 배치라 값 자체를 입구에서 막는다(`NUMBER_RANGE` 하한 1, 상한 3650) | security | High | ConfigKeys.java(PORTAL_DATAMART_RETENTION_DAYS, NUMBER_RANGE), SystemConfigService.java |

> **알려진 한계(인지·수용)**: per-user 속도 제한(TC-PORTAL-102)은 형제 제한기(`portalUpload`·`portalUserLabel`)와
> 동일하게 **노드별 in-memory** 라 2노드 Active-Active 배포에서는 실질 한도가 분당 3회의 2배다 — 분산
> 제한기를 새로 도입하지 않는 것은 확정 사항([UNCERTAINTIES.md #12](UNCERTAINTIES.md)의 형제 축과 동일한 성질).
> 보존기간 만료 삭제는 **비가역**이며, 삭제된 라벨·업로드 자산을 되살리는 API·배치는 없다.

> **불확실 항목**: #1 포털 SAM2(문서=미제공, 확정됨 — 07-30 재확인: 여전히 정책 위반, 다만 전송 픽셀은 비식별본으로 교체되고 신고 게이트가 배선됨) · #5 frame-interval **확정: 5초**(07-30 해소, DB 시드=코드 폴백=5) · #11 다운로드 기간 제한 **확정: 해소**(2026-08-17, 위 F-12 절 참조) · #12 데이터마트/이미지 서빙 rate limit 부재(미확정 유지 — user-labels·datamart 목록·`PortalLabelService#serveFrameImage`에는 여전히 rate limiter 없음, 업로드/SAM2/TUS/**다운로드(TC-PORTAL-102)** 에만 있음) → [UNCERTAINTIES.md](UNCERTAINTIES.md)
