# F 클러스터 part1 — F-1(채널·역할 게이팅) + F-2(데이터마트 Load) 검증 결과

> 대상: `docs/test-cases/F-portal.md` `## F-1`(11건) + `## F-2`(19건) = **30건**
> 회차: 2026-08-02 2차 · 검증 방식: **풀스택 실동작 우선**(backend `localhost:18081/api` · PostgreSQL `public` 스키마)
> 코드 기준: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801`
> 토큰: `POST /v1/dev/tokens`(dev 발급) + role/channel 불일치 조합은 `JWT_SECRET` 으로 직접 서명해 위조

## 0. 검증 환경 실측

| 항목 | 실측 |
|---|---|
| backend | `GET /api/actuator/health` → 200 (`klid-backend` Up 19h) |
| mock-server / ai-server / frontend / postgres | 전부 Up·200 |
| 스키마 | `public` (문서상 `klid_at` 아님 — 1차 stack-bringup 기록과 동일) |
| baseline | `_raw/test-baseline.md`(08-01): BE 4,755 / FE 1,951 / ai 91, **실패 0건** |
| 사용 계정 | PORTAL_USER `sub=3001`·`sub=3002`, WORKER `2001`, REVIEWER `1001` |
| 투입 테스트 데이터 | `ls_data_raw` 9500/9501/9502, `ls_data_src` 990001~990020, 라벨 123행 — **검증 후 전량 삭제(복원 확인 완료)**. 컨테이너 storage 에 만든 심링크/사본도 제거 |

### ⚠ 배포 이미지 ↔ qa-0801 워킹트리 차이 (판정 유효성 확인)

`klid-backend` 이미지 빌드 시각은 **2026-08-01 14:05**(HEAD `56d30478`)이고, qa-0801 워킹트리에는 **미커밋 수정 80파일**이 있다. 본 파트 30건의 판정 근거 파일이 그 차이에 걸리는지 전수 확인했다.

| 근거 파일 | 워킹트리 수정 | 판정 영향 |
|---|:--:|---|
| `common/security/SecurityConfig.java` | **없음** | F-1 전건(001~008) 실동작 = qa-0801 코드 그대로 |
| `portal/service/PortalLabelService.java` | **없음** | F-2 전건(020~038) + **F-ISSUE-01/02 모두 양쪽 동일** |
| `common/storage/StorageSubtreePolicy.java` | **없음** | TC-032/033/034 동일 |
| `video/service/DeidentReportGate.java` | **없음** | TC-035/036/038 동일 |
| `common/security/JwtAuthenticationFilter.java` | 있음(+16) | `exp` 클레임 **필수화**만 추가. 본 검증에 쓴 토큰은 전부 `exp` 보유 → 판정 불변 |
| `portal/controller/PortalLabelController.java` | 있음(+15) | `SortAllowlist` 배선 + javadoc 만. `requireActor`·게이트 로직 **불변** → TC-009/023/024 판정 불변 |
| `portal/controller/PortalUploadController.java` | 있음(+39) | 동일(정렬 allowlist 만). `requireActor` **불변** |

→ **30건 전건의 판정은 배포본·워킹트리 양쪽에서 동일하게 성립**한다.

부수 관찰(본 파트 케이스 아님): 배포 이미지는 정렬 allowlist 수정 이전이라 `GET /v1/portal/datamart/videos?sort=bogusKey,asc` → **500**(`INTERNAL_ERROR`), `?sort=rawFilePathNm,asc` → **200**(내부 경로 컬럼으로 정렬 성립)이다. 워킹트리에는 `SortAllowlist.apply(...)` 가 배선돼 있어 400 으로 바뀐다 = **A-ISSUE-61 은 코드상 해소, 배포 미반영**. `PortalLabelControllerTest:datamartVideos_unknownSortKey_returns400` 등 3개 테스트가 회귀를 고정한다.

---

## 1. F-1. 채널·역할 게이팅 / 인가 경계 (11건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-PORTAL-001 | PASS | [실동작] | `GET /v1/portal/datamart/videos` (PORTAL_USER+PORTAL) → **200**, 12건 반환 |
| TC-PORTAL-002 | PASS | [실동작] | WORKER/INTERNAL → **403** `{"errorCode":"FORBIDDEN"}` |
| TC-PORTAL-003 | PASS | [실동작] | REVIEWER/INTERNAL → `GET /v1/portal/uploads` **403** |
| TC-PORTAL-004 | PASS | [실동작] | **위조 토큰**(`role=PORTAL_USER`,`channel=INTERNAL`, 정상 서명) → `/v1/portal/**` **403**, 역방향 `/v1/videos` 도 **403**. `SecurityConfig.java:134-135` `allOf(ROLE_PORTAL_USER, CHANNEL_PORTAL)` 두 조건 AND 실증 |
| TC-PORTAL-005 | PASS | [실동작] | PORTAL 토큰 → `POST /v1/frames/1/sam2-track` **403** (하위 catch-all `/v1/**` = `SecurityConfig.java:147-153`) |
| TC-PORTAL-006 | PASS | [실동작] | `PUT /v1/manage/labels/1` **403** |
| TC-PORTAL-007 | PASS | [실동작] | `GET /v1/notices` **403** (`SecurityConfig.java:132`) |
| TC-PORTAL-008 | PASS | [실동작] | `GET /v1/manage/labels` **200** (라벨 마스터 6+건, `SecurityConfig.java:126`) |
| TC-PORTAL-009 | PASS | [실동작] | `sub` 클레임 **없음** → 401 `"포털 토큰 미상"`, `sub:""` **빈문자열**도 401. 근거는 `PortalUploadController.java:146-151`(카탈로그 138-143 드리프트) |
| TC-PORTAL-010 | PASS | [정적] | `portalGuard.test.tsx:69-77` 존재 + **실제 라우터도 동일 조합** 사용(`router/index.tsx:154-164`) → 테스트 전용 wrapper 가 실배선과 어긋나지 않음 |
| TC-PORTAL-011 | PASS | [정적] | `portalGuard.test.tsx:79-91`(INTERNAL→/portal, /portal/label/:id 양쪽) + 91-99(REVIEWER 도 forbidden) |

### F-1 반증 시도 기록 (전건 통과)

1. **채널 우회 전수 스캔** — PORTAL 토큰으로 내부 14개 엔드포인트 호출:
   `/v1/event-types`·`/v1/tasks/board`·`/v1/reviews`·`/v1/videos`·`/v1/videos/4/stream`·`/v1/frames/1/image`·`/v1/frames/1/labels`·`/v1/system/configs`·`/v1/notices`·`/v1/labels/1/deident-report`·`/v1/deident-reports` → **전부 403**.
   200 인 것은 `/v1/me`(의도된 예외, 자기 클레임 반향만: `{"userId":"3001","role":"PORTAL_USER"}`)와 `/actuator/health`(permitAll) 뿐 — 업무 데이터 노출 없음.
2. **교차채널 권한 상승(CWE-269/863)** — PORTAL 토큰으로 `POST /v1/auth/role-claim {"role":"REVIEWER","adminPassword":"…"}` → **403** `"해당 역할은 자가 부여할 수 없습니다"`. `RoleClaimService.java:127` 의 `actor.channel() != Channel.INTERNAL` fail-closed 화이트리스트가 **패스워드 검증 이전에** 차단(타이밍 오라클도 없음).
3. **role=null 위조** — `channel=PORTAL` + `role` 클레임 부재 토큰은 `ROLE_PORTAL_USER` authority 미부여로 `/v1/portal/**` 403(위 allOf 두 조건 중 role 축).

---

## 2. F-2. 데이터마트 Load (19건)

| ID | 판정 | 근거 확인 | 실측 |
|----|:--:|---|---|
| TC-PORTAL-020 | PASS | [실동작] | 목록 12건 전부 `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`. DB 의 PENDING(7,31)·ASSIGNED(17,27,30,33)·REJECTED(5)·FAILED(6,35) 및 상태행 부재 영상 **전건 미포함**. `findAllWithReviewStatus(null, APPROVED, …)` INNER JOIN 게이트 실증 |
| TC-PORTAL-021 | **PARTIAL** | [실동작] | 프레임 0건 제외 자체는 동작(APPROVED 20건 중 프레임 보유 12건만 노출). 그러나 **`totalElements`/`totalPages` 가 오보되고 중간에 빈 페이지가 생긴다** — `size=5`: total=20/pages=4 인데 page2 는 **0건**, page3 은 2건. `size=100`: total=**12**. → **F-ISSUE-02** |
| TC-PORTAL-022 | PASS | [실동작] | Hibernate SQL 로그 실측 — 10행 페이지 1회 요청에 `select` **5회**(content / count / `LS_DATA_SRC` firstSrcSn IN / `LS_DATA_SRC` count IN / `LS_RAW_DATA_STATUS` findAllById IN). rawSn 수에 비례하는 반복 쿼리 없음 |
| TC-PORTAL-023 | PASS | [실동작] | 121건 보유 rawSn 에 `size=99999&page=-1` → **100건**, `size=100&page=1` → 21건, `size=0` → **1건**. clamp `min(max(size,1),100)`·`max(page,0)` 실증 |
| TC-PORTAL-024 | PASS | [실동작] | `rawSn` 누락 → **400** `INVALID_INPUT` `"필수 파라미터가 누락되었습니다: rawSn"`. (컨트롤러 `@RequestParam Long rawSn` 필수 바인딩이 1차 차단, 서비스 `PortalLabelService.java:102-104` 는 방어적 이중 가드) |
| TC-PORTAL-025 | PASS | [실동작] | srcSn=1 에 3001 이 user-label 저장 후 재조회 → **본인 user-label 7건만** 반환(datamart 원본 `person` 미포함) |
| TC-PORTAL-026 | PASS | [실동작] | 3002(해당 프레임 저장분 0) 가 srcSn=23 조회 → datamart 원본 `{"id":572,"label":"person","points":[[1,1],[5,5]]}` 반환. DB `ls_data_lbl.lbl_sn=572` 와 일치 |
| TC-PORTAL-027 | PASS | [실동작] | 미승인(PENDING) rawSn=7 프레임 → **403** `"데이터마트에 노출되지 않은 영상입니다"` |
| TC-PORTAL-028 | PASS | [실동작] | srcSn=99999999 → **404** `"프레임을 찾을 수 없습니다"` |
| TC-PORTAL-029 | PASS | [실동작] | srcSn=1 → **200**, `Content-Type: image/jpeg`(13,164B, 실제 JPEG), `X-Content-Type-Options: nosniff`, **`Cache-Control: no-store`**, `X-Frame-Options: DENY` |
| TC-PORTAL-030 | PASS | [실동작] | 미승인 프레임 이미지 → **403** |
| TC-PORTAL-031 | PASS | [실동작] | `DE_IDNTF_SRC_FILE_PATH_NM` NULL 인 프레임(srcSn=445, rawSn=902 APPROVED, 원본 경로는 존재) → **404** `"비식별 프레임이 존재하지 않습니다"`. **원본 폴백 없음** 확인 |
| TC-PORTAL-032 | PASS | [실동작] | deid 경로에 `…/frames/deid/4/../../../../raw/frames/raw/4/frame-0.jpg` 주입 → **403** `"허용되지 않은 이미지 경로입니다"`(`Verdict.OUTSIDE_BASE`) |
| TC-PORTAL-033 | PASS | [실동작] | ① deid **base 하위의 raw 서브트리**(`{deidBase}/frames/raw/4/frame-0.jpg`, 실파일 배치) → **403**(`OUTSIDE_DEID_SUBTREE`) ② **심링크 우회**(`{deidBase}/frames/deid/4/qa-evil.jpg → {rawBase}/frames/raw/4/frame-0.jpg`) → **403**. 실경로(`toRealPath`) 기준 서브트리 판정(CWE-59/22) 실증 |
| TC-PORTAL-034 | PASS | [실동작] | 존재하지 않는 파일 → **404** `"이미지 파일이 존재하지 않습니다"`, 응답 본문에 경로·스택 **미노출**. 디렉터리 경로 주입도 동일 404(`NOT_REGULAR_FILE`). 서버 로그도 `verdict=` 사유코드만 기록(CWE-209/117) |
| TC-PORTAL-035 | PASS | [실동작] | `DE_IDNTF_YN='F'` + `APPROVED` 인 rawSn=900 프레임(429) 라벨 → **412** `PRECONDITION_FAILED`. APPROVED 게이트만으로는 안 걸리는 경로임을 데이터로 확인(900 은 목록에도 노출됨) |
| TC-PORTAL-036 | PASS | [실동작] | 동일 프레임 이미지 → **412**, 응답 바이트 0(파일 미판독) |
| TC-PORTAL-037 | PASS | [실동작] | 응답 헤더 `Cache-Control: no-store` 단독(구 `private, max-age=300` 흔적 없음) |
| TC-PORTAL-038 | PASS | [실동작] | 부모 9500(`'F'`) / 파생 9501(`ORGNL_RAW_SN=9500`, `'Y'`) 구성 → 파생 프레임 라벨 **200**(`derived-label` 좌표 반환) · 파생 프레임 이미지 **200**(JPEG), 부모 프레임은 **412**. ★1 확정 정책의 귀결 그대로 — **결함 아님**. `DeidentReportGate.java:24-38` javadoc · `AiInferenceDeidentReportGateTest.java:339-351`(라인 정확 일치) |

---

## 3. 이슈

### [F-ISSUE-01] TC-PORTAL-020 / TC-PORTAL-027 / TC-PORTAL-035 (인접 갭: TC-PORTAL-023·024 엔드포인트) — `GET /v1/portal/datamart/labels` 에 APPROVED 게이트·비식별 신고 게이트가 **모두 부재**해 외부 채널이 내부 전 영상의 라벨 좌표를 열람

- **심각도**: **HIGH**
- **기대 동작(기대효과)**:
  포털은 **데이터마트 노출(검수 완료=APPROVED) 영상만** 접근할 수 있어야 하고(CLAUDE.md 포털 절 · `PortalLabelService.isExposedToDatamart`),
  비식별 누락 신고 구간(`DE_IDNTF_YN='F'`)에는 **라벨 좌표 조회를 차단**해야 한다(CLAUDE.md: *"라벨 좌표가 PII 위치 특정 정보이므로 스트리밍만 막는 것으로는 부족"*, 차단 범위 목록 ①).
  실제로 형제 엔드포인트 `GET /v1/portal/frames/{srcSn}/labels` 는 두 게이트를 모두 갖는다(403 / 412).
- **현재 동작(이슈 내용)**:
  `PortalLabelService.loadDatamartLabels` (`backend/src/main/java/kr/co/cudo/authoring/portal/service/PortalLabelService.java:100-116`) 에는 `isExposedToDatamart` 호출도, `accessGuard.requireNotUnderDeidentReport` 호출도 **없다**. rawSn 만 받아 `lblRepository.findAllByRawSn(rawSn)` 를 그대로 반환한다.

  ```java
  public List<DatamartLabelResponse> loadDatamartLabels(Long rawSn, int page, int size) {
      if (rawSn == null) { throw new CustomException(ErrorCode.INVALID_INPUT, "rawSn 은 필수입니다."); }
      int clampedSize = Math.min(Math.max(size, 1), 100);
      int clampedPage = Math.max(page, 0);
      List<LsDataLbl> all = lblRepository.findAllByRawSn(rawSn);   // ← 게이트 0건
      ...
  }
  ```
  컨트롤러(`PortalLabelController.java:83-91`)도 `@PreAuthorize("hasRole('PORTAL_USER')")` + `requireActor` 만 있고 영상 단위 인가가 없다.

  **실측(투입 데이터 기준)**
  | 요청 | 대상 영상 상태 | 결과 |
  |---|---|---|
  | `GET /v1/portal/datamart/labels?rawSn=9502` | `PENDING`(미승인) | **200**, `LEAK-PENDING-LABEL` 포함 100건 + `pointsJson` 좌표 원문 |
  | `GET /v1/portal/datamart/labels?rawSn=9500` | `APPROVED` + `DE_IDNTF_YN='F'`(신고 구간) | **200**, `LEAK-REPORTED-LABEL` 좌표 반환 |
  | (대조) `GET /v1/portal/frames/990020/labels` | 동일 미승인 영상 | **403** |
  | (대조) `GET /v1/portal/frames/990010/labels` | 동일 신고 구간 영상 | **412** |

  즉 **같은 데이터에 대해 게이트가 걸린 경로와 안 걸린 경로가 공존**하며, 후자가 전자를 무력화한다. rawSn 은 1..N 연속 정수라 열거가 자명하다.
- **재현/확인 경로**:
  ```sql
  -- 미승인 영상 + 라벨 1건 만들기
  INSERT INTO ls_data_raw (raw_sn,vms_clip_id,vms_cctv_id,prvc_type_cd,prvc_yn,de_ident_yn,raw_file_path_nm,data_stts_cd,reg_dt)
    VALUES (9502,'QA-F1-PENDING','CCTV-QA','PRVC','Y','Y','/app/storage/raw/qa/p.mp4','PENDING',now());
  INSERT INTO ls_raw_data_status (raw_data_id,data_stts_cd,upd_dt) VALUES (9502,'PENDING',now());
  INSERT INTO ls_data_src (src_sn,raw_sn,frm_no,de_idntf_src_file_path_nm,reg_dt)
    VALUES (990020,9502,0,'/app/storage/deidentified/frames/deid/4/frame-0.jpg',now());
  INSERT INTO ls_data_lbl (src_sn,lbl_type_cd,lbl_nm,point_cn,reg_dt)
    VALUES (990020,'BBOX','LEAK','[[3.0,3.0],[7.0,7.0]]',now());
  ```
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL"}' | jq -r .data.token)
  curl -s -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/datamart/labels?rawSn=9502"   # → 200 + 좌표
  curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOK" \
       "localhost:18081/api/v1/portal/frames/990020/labels"          # → 403 (대조)
  ```
- **영향**:
  - **CWE-862(Missing Authorization) / CWE-639(IDOR — rawSn 열거)**: 외부 채널(PORTAL_USER)이 검수 전·반려·실패 상태 영상의 라벨 본문(라벨명·BBOX/POLYGON 좌표·`trackId`)을 전건 덤프할 수 있다. 데이터마트에 미노출인 내부 작업 중 산출물이 외부로 샌다.
  - **CWE-359(Private Information Exposure)**: 비식별 누락 신고 구간의 라벨 좌표는 **마스킹 실패 PII 의 위치 좌표**다. CLAUDE.md 가 명시적으로 차단 대상으로 규정한 자산이 그대로 나간다. 게이트 해제(`resolve`) 대기 중에도 계속 열람 가능.
  - ⚠ 이는 ★1(파생 경유 열람) 확정 정책과 **무관한 별개 경로**다 — 파생 여부와 상관없이 **원본 rawSn 자체**가 뚫린다.
- **수정 방향(제안)**:
  `PortalLabelService.loadDatamartLabels` 진입부에 형제 메서드(`loadFrameLabels`:257-267)와 **동일 순서·동일 컴포넌트**로 두 게이트를 배선한다 — ①`requireActor(actor)`(시그니처에 `TokenClaims actor` 추가) ②`if (!isExposedToDatamart(rawSn)) throw FORBIDDEN` ③`accessGuard.requireNotUnderDeidentReport(rawSn)`. 판정을 국소 재구현하지 말 것(본 결함의 원인이 정확히 "게이트 복제 누락"이다).
  아울러 **회귀 가드**로 `PortalUserLabelServiceTest` 에 미승인/신고 구간 케이스를 추가하고, 카탈로그 F-2 에 이 엔드포인트의 게이트 케이스(TC-PORTAL-039/040 신규)를 등재할 것 — 현재 카탈로그가 `/datamart/labels` 의 게이트를 **한 건도 커버하지 않아** 1차·2차 모두 케이스 통과로는 잡히지 않았다.

---

### [F-ISSUE-02] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지로 뒷 페이지 영상 유실

- **심각도**: **MEDIUM**
- **기대 동작(기대효과)**: 프레임 0건 영상을 목록에서 제외하되(진입 불가하므로), 페이징 메타(`totalElements`/`totalPages`)와 실제 순회 결과가 일치해 포털 사용자가 **노출 대상 영상 전체를 빠짐없이** 볼 수 있어야 한다.
- **현재 동작(이슈 내용)**:
  `PortalLabelService.java:148-161` 이 **DB 페이지를 가져온 뒤 애플리케이션에서 필터**하고, 총건수는 필터 전 값을 그대로 넘긴다.
  ```java
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // 페이지 내부에서만 제외
          .map(...).toList();
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());
  ```
  실측(APPROVED 20건 중 프레임 보유 12건):
  | 요청 | totalElements | totalPages | 실제 content |
  |---|--:|--:|--:|
  | `?page=0&size=5` | 20 | 4 | 5 (94,81,80,906,905) |
  | `?page=1&size=5` | 20 | 4 | 5 (902,903,901,900,26) |
  | `?page=2&size=5` | 20 | 4 | **0** |
  | `?page=3&size=5` | 20 | 4 | 2 (18, 4) |
  | `?page=0&size=100` | **12** | 1 | 12 |

  `size=100` 에서 total 이 12 로 바뀌는 것은 `PageImpl` 이 `offset + pageSize > total` 일 때 총계를 `offset + content.size()` 로 재계산하기 때문이며, 그 결과 **같은 데이터셋의 총건수가 페이지 크기에 따라 20↔12 로 달라진다**.
- **재현/확인 경로**:
  ```bash
  for p in 0 1 2 3; do
    curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
      | jq -c '{page:'"$p"',total:.data.totalElements,pages:.data.totalPages,n:(.data.content|length)}'
  done
  ```
- **영향**: 기능/데이터정합.
  ① 무한스크롤·"더 보기" 처럼 **빈 응답을 종료 신호로 쓰는 FE 구현은 page2 에서 멈춰 rawSn 18·4 를 영원히 못 본다**(영상 유실).
  ② 페이지네이터가 "총 20건"을 표시하는데 실제 순회 가능 건수는 12건이라 사용자 혼란.
  ③ 프레임 0건 영상이 늘어날수록 빈 페이지 구간이 커진다(현재도 APPROVED 20건 중 8건이 프레임 0건).
  보안 영향은 없다.
- **수정 방향(제안)**: 제외 조건을 **DB 쿼리로 내린다** — `findAllWithReviewStatus` 계열에 `EXISTS (select 1 from LsDataSrc s where s.rawSn = v.rawSn)` 조건을 추가해 페이징·카운트가 같은 술어를 쓰게 한다. 그러면 `totalElements` 가 12 로 일관되고 빈 페이지도 사라진다. 애플리케이션 필터를 유지해야 한다면 최소한 `totalElements` 를 필터 후 기준으로 별도 count 쿼리로 산출하고, 현재 주석("totalElements 는 원본 기준 유지")을 정책으로 확정할지 사용자 판단을 받을 것.

---

## 4. 근거(file:line) 드리프트 — 카탈로그 정합성 결함

> `SecurityConfig.java`(F-1 전건: 126·132·134-135·147-153)와 `AiInferenceDeidentReportGateTest.java:339-351` 은 **정확 일치**. 아래는 어긋난 항목.

| TC | 카탈로그 근거 | 실제 위치 |
|----|---|---|
| TC-PORTAL-009 | `PortalUploadController.java:138-143` | `PortalUploadController.java:146-151` |
| TC-PORTAL-020 | `PortalLabelService.java:130-161` | `:131-162` |
| TC-PORTAL-021 | `:147-157` | `:148-158` |
| TC-PORTAL-022 | `:143-145` | `:144-146` |
| TC-PORTAL-023 | `:104-108` | `:105-108` |
| TC-PORTAL-024 | `:101-103` | `:102-104` |
| TC-PORTAL-025 | `:284-292` | `:285-293` |
| TC-PORTAL-026 | `:293-300` | `:294-301` |
| TC-PORTAL-027 | `:256-259` | `:257-260` |
| TC-PORTAL-028 | `:250-251` | `:251-252` |
| TC-PORTAL-029 | `:414-460` | `:430-501` |
| TC-PORTAL-030 | `:420-423` | `:436-439` |
| TC-PORTAL-031 | `:430-434` | `:446-450` |
| TC-PORTAL-032/033 | `:438-439` | `:457-459` (+ base 세팅 `:85-97`) |
| TC-PORTAL-034 | `:440-443` | `:460-471` |
| TC-PORTAL-035 | `:261-266` | `:262-267` |
| TC-PORTAL-036 | `:425-427` | `:441-443` |
| TC-PORTAL-037 | `:452-456` | `:493-497` |
| TC-PORTAL-038 | `DeidentReportGate.java:23-37` | `:24-38` |

`PortalLabelService.java` 는 앞부분(~L108)이 **+1행**, `serveFrameImage` 구간이 **+16~+41행** 밀렸다(2026-07-30 이후 javadoc·게이트 주석 추가분).

---

## 5. 테스트 커버 대조 (`_raw/test-baseline.md` — 전건 통과 상태)

| TC | 커버 테스트 (파일:메서드) |
|----|---|
| 001~004 | `auth/SecurityConfigChannelTest.java:portalChannelAllowedOnPortal` / `internalChannelForbiddenOnPortal` / `portalChannelForbiddenOnInternalVideos` / `portalChannelForbiddenOnManage` |
| 005~007 | `portal/PortalLabelControllerTest.java:portalTokenBlockedFromInternalSam2AndAutolabel` / `portalUserBlockedFromInternalApi` / `versionApiHiddenFromPortalUser` |
| 009 | `portal/PortalUserLabelServiceTest.java:saveUserLabel_noToken_rejected` |
| 010·011 | `frontend/src/router/__tests__/portalGuard.test.tsx` (4 케이스) |
| 020·021 | `portal/PortalDatamartVideosServiceTest.java:listDatamartVideos_approvedOnly_enriched` / `_pendingExcludedByGate` / `_zeroFrameExcluded` ⚠ **페이징 메타 단언 없음** → F-ISSUE-02 미커버 |
| 023·024 | `portal/PortalUserLabelServiceTest.java:loadDatamartLabels_returnsDtoList` / `_nullRawSn_rejected` ⚠ **게이트 케이스 없음** → F-ISSUE-01 미커버 |
| 025~028 | `portal/PortalFrameLabelsServiceTest.java:loadFrameLabels_userLabel_takesPrecedence` / `_noUserLabel_returnsDatamart` / `_notApproved_forbidden` / `_frameMissing_notFound` |
| 029~034 | `portal/PortalFrameImageServiceTest.java:serveFrameImage_approvedVideo_returnsDeidImage` / `_absoluteDeidPath_underDeidentifiedDir_returns200` / `_rawFrameSubtree_forbidden` / `_symlinkToRawFrame_forbidden` / `_traversalOutsideBase_forbidden` / `_fileMissing_notFound` / `_notApproved_forbidden` / `_noDeidPath_notFound` |
| 036·037 | `portal/PortalFrameImageCacheControlTest.java:portalFrameImageIsNotCached` / `portalFrameImageBlockedUnderDeidentReport` |
| 038 | `label/service/AiInferenceDeidentReportGateTest.java:segmentNotBlockedByOriginReport` (동일 게이트 컴포넌트 공유) |
| 035 | 전용 단위 테스트 미발견(`DeidentReportGateCoverageIT` 가 게이트 배선 자체는 커버) — 실동작으로 412 확인 |

---

## 6. 참고 관찰 (이슈 미승격)

- **UNCERTAINTIES #12 (rate limit 부재) 미해소 유지 확인**: `PortalLabelController` 의 `/datamart/videos`·`/datamart/labels`·`/user-labels`·`/frames/{srcSn}/labels`·`/frames/{srcSn}/image` 5개 핸들러 어디에도 rate limit 어노테이션·필터가 없다(업로드·SAM2·TUS 에만 존재). 프레임 이미지 서빙은 `no-store` 라 매 요청 서버 I/O 가 발생하므로 열거·대량 다운로드 억제 수단이 없다. 07-30 판정 그대로 **확인필요**로 이월.
- **신고 구간 영상이 데이터마트 목록에는 노출된다**: rawSn=900(`'F'` + `APPROVED`)이 `/datamart/videos` 결과에 그대로 포함되고, 진입하면 프레임 라벨/이미지에서 412 를 만난다. 관제 뷰 쪽 확정 정책(신고 필터 미적용)과 방향은 같으므로 **결함으로 보고하지 않는다**. 다만 포털 FE 가 412 를 graceful 처리하는지는 H 클러스터에서 확인 대상.
- **`/v1/portal/auth/**` 는 `SecurityConfig.java:90` 에서 permitAll** — 본 파트 케이스 대상은 아니나 채널 게이트 밖에 있는 유일한 포털 경로이므로 A 클러스터와 교차 확인 권장.

---

## 7. 집계

| 판정 | F-1 | F-2 | 합계 |
|---|--:|--:|--:|
| PASS | 11 | 18 | **29** |
| PARTIAL | 0 | 1 | **1** |
| FAIL / BLOCKED / N/A / 확인필요 | 0 | 0 | **0** |
| **계** | **11** | **19** | **30** |

- 이슈 2건: **F-ISSUE-01(HIGH)** · **F-ISSUE-02(MEDIUM)**
- ⚠ F-ISSUE-01 은 **30개 케이스 중 어느 것으로도 잡히지 않는 커버리지 갭**에서 발견됐다(반증 과정에서 형제 엔드포인트 간 게이트 비대칭을 대조해 노출). 카탈로그 F-2 에 `/v1/portal/datamart/labels` 게이트 케이스 신규 등재가 필요하다.
- 근거 드리프트 **19건**(대부분 +1행, `serveFrameImage` 구간 +16~+41행).
# F 클러스터 2차 검증 — part2 (F-3 데이터마트 사용자 라벨 저장 · F-6 포털 자산 조회/서빙/삭제)

- 대상: `docs/test-cases/F-portal.md` **F-3 (TC-PORTAL-040~050, 11건)** + **F-6 (TC-PORTALUP-020~032, 13건)** = **24건**
- 판정: **PASS 24 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0**
- 신규 이슈: **F-ISSUE-21 ~ F-ISSUE-29 (9건)** — HIGH 1(환경/배포 드리프트) · MEDIUM 4 · LOW 4
  (전건 "카탈로그 기대결과 자체는 충족하되 인접 축의 방어가 비어 있는" 결함이라 케이스 판정과 분리해 기록)
- 코드/설정/테스트 파일 수정 **0건**, 빌드/테스트 실행 **0건**. DB 는 테스트 데이터 INSERT/UPDATE 만 수행.

---

## 0. 검증 환경 · 근거 수집 방법

| 항목 | 실측값 |
|---|---|
| backend | `http://localhost:18081/api` (context-path `/api`) — `/api/actuator/health` → `{"status":"UP"}` |
| mock-server | `http://localhost:9400/health` → `{"status":"ok"}` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |
| 토큰 | `POST /api/v1/dev/tokens` — `{"role":"PORTAL_USER","channel":"PORTAL","userNo":...}` |
| 사용 계정 | **3001/3002**(F-3 라벨) · **3011/3012**(F-6 자산 — 병렬 에이전트와 sub 충돌 회피 목적으로 신규 채번) |
| 대상 데이터 | APPROVED 영상 `rawSn=4`(`DE_IDENT_YN='Y'`, frame 30건, srcSn 1~30) · 미승인 `rawSn=7`(PENDING)·`rawSn=11`(상태행 부재) · 신고 구간 `rawSn=900`(`DE_IDENT_YN='F'`, APPROVED) |
| baseline | 2026-08-02/2차에는 `test-baseline.md` 부재 → **2026-08-01/1차** 기준 대조 (BE 4,755 tests / 실패 0 / skip 5) |

### ★ 착수 전 발견한 환경 결함 — 배포 jar 가 워킹트리 소스보다 오래됨 (F-ISSUE-27)

`git status --short` 기준 워킹트리에 **미커밋 수정 80파일**이 있고, 그중 포털 관련 5개
(`SortAllowlist.java` · `PortalLabelController.java` · `PortalUploadController.java` · `PortalSam2Controller.java` · `PortalSam2Service.java`)가
**컨테이너 jar 에 반영돼 있지 않다.** 실측 근거:

```
docker inspect klid-backend → created 2026-08-01T14:10:06Z / /app/app.jar (Aug 1 14:05)
$ docker cp klid-backend:/app/app.jar → BOOT-INF/classes/.../PortalUploadController.class
   safeSort       False      ← 소스에는 있음(PortalUploadController.java:164-166)
   SortAllowlist  False
   capped         True       ← 배포됨
$ 같은 jar 의 SortAllowlist.class
   PORTAL_UPLOAD  False / PORTAL_UPLOAD_FRAME False / DEIDENT_REPORT False
```

**영향**: `GET /v1/portal/uploads?sort=status,asc` 이 라이브에서 **500**
(`PropertyReferenceException: No property 'status' found for type 'LsPortalUld'` — backend 로그 실측),
`?sort=filePathNm,desc` 는 **200 + 내부 컬럼으로 실제 정렬**(응답 `sort.sorted=true`).
**소스 기준으로는 둘 다 400** 이다(`SortAllowlist.resolve` strict).

**판정 원칙**: 본 검증은 "코드는 qa-0801 기준"이므로 소스가 정본이다.
`PortalUploadService.java` · `PortalLabelService.java` 는 **미수정 파일**이라 라이브 동작 = 소스이고,
F-3/F-6 24건 중 **정렬 축을 기대결과로 삼는 케이스는 없다**(TC-PORTALUP-023 은 `size` 하드캡 전용이며
`capped` 는 배포본에도 있고 라이브로 확인됨). 따라서 24건 판정은 영향을 받지 않으며, 드리프트 자체를
F-ISSUE-27 로 분리 기록한다.

---

## 1. F-3. 데이터마트 사용자 라벨 저장 (단방향 · 원본 미수정 · IDOR) — 11건

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-PORTAL-040 | PASS | [실동작] | `POST /v1/portal/user-labels {sourceRawSn:4,sourceSrcSn:1,BBOX,points:"[[1,2],[3,4]]"}` → **201** `{"userLblSn":1,...}`. **단방향 실증** — 저장 전후 `ls_data_lbl` 전행 md5(`lbl_sn\|src_sn\|lbl_type_cd\|point_cn\|lbl_nm\|mdfcn_dt`) = `ccfaeb4d76b0cfb4155d83b9d72d6202 n=91` **동일**, `ls_data_lbl_hstry=70` 동일, `ls_label_version=9` 동일, `ls_dataset_export=30` 동일, **mock-server 인바운드 로그 증가 0줄**(관제 `TASK_MODIFIED` 미발생). 적재는 `ls_portal_user_label` 에만 (`PortalLabelService.java:218-220`) |
| TC-PORTAL-041 | PASS | [실동작] | PENDING `rawSn=7` → **403** `{"errorCode":"FORBIDDEN","message":"데이터마트에 노출되지 않은 영상입니다."}`. 반증 확장: 상태행 **부재** `rawSn=11` → 403, **존재하지 않는** `rawSn=999999` → 403 (`isExposedToDatamart` 의 `.orElse(false)` fail-closed, `:504-508`). 세 응답이 동일해 자원 열거도 차단 |
| TC-PORTAL-042 | PASS | [실동작] | SKELETON 17점 `[[x,y,v]×17]`, v∈{0,1,2} → **201**. 로드 round-trip 확인: `GET /v1/portal/frames/1/labels` 응답에 `(id=4,'SKELETON',points 17개)` + v 보존(`[...,1.0]`/`[...,2.0]`) |
| TC-PORTAL-043 | PASS | [실동작] | 16점 → **400** `"SKELETON 키포인트는 정확히 17 개여야 합니다."` (`:325-328`) |
| TC-PORTAL-044 | PASS | [실동작] | `v=3` → **400** `"가시성 v 는 0/1/2 중 하나여야 합니다."` (`:330-333`) |
| TC-PORTAL-045 | PASS | [실동작] | `NaN` 리터럴 → **400**(`"키포인트 좌표 형식이 올바르지 않습니다."` — Jackson 파싱 단계 fail-closed), `Infinity` 리터럴 → **400**(동일). ★반증: 파서를 통과하는 **`1e400`(double overflow → +Inf)** 로 재시도 → **400** `"좌표는 유한한 숫자여야 합니다."` → `Double.isFinite` 가드(`:334-337`)가 **죽은 코드가 아님을 실증**. 음수 `-1` → 400 `"좌표는 0 이상이어야 합니다."`(`:338-340`) |
| TC-PORTAL-046 | PASS | [실동작] | BBOX + `points:"[]"` → **400** `"points 좌표가 비어있습니다."`, `"[[]]"` → 400, 파싱 불가 `"not-json"` → 400(fail-secure 빈 리스트 → 동일 400). `ls_portal_user_label` 에 빈 row 생성 0건 (`:212-217`) |
| TC-PORTAL-047 | PASS | [실동작] | `points:null` / `"   "` / 키 자체 누락 → 전부 **400** `"points: points 는 필수입니다."` (`@NotBlank`, `PortalUserLabelRequest.java:17`) |
| TC-PORTAL-048 | PASS | [실동작] | 3002 가 `label:"USER3002-SECRET"` 저장(userLblSn=9) → 3002 의 `GET /v1/portal/user-labels?rawSn=4` = `[(9,'USER3002-SECRET')]`, **3001 의 같은 호출 = `[8,7,6,5,4,3,2,1]` — SECRET 미포함**. 프레임 로드 `GET /v1/portal/frames/1/labels`(3001) 에도 `grep -c USER3002-SECRET = 0`. 소유자 스코프 쿼리 `findByPortalUserNoAndSrcRawSn...`(`:233`) / `findByPortalUserNoAndSrcDataSrcSn...`(`:275`) |
| TC-PORTAL-049 | PASS | [실동작] | DB 직접 INSERT 로 stale row 3종(`point_cn` = NULL / `''` / `'[]'`, srcSn=5) 주입 → `GET /v1/portal/frames/5/labels` **200** `labels: []` (필터 `:285-287`). 혼재 케이스(srcSn=7 에 정상 1 + 빈 1) → **정상 1건만** 반환 |
| TC-PORTAL-050 | PASS | [실동작] | 손상 좌표 `'{not-json'`(BBOX) + 타입 불일치 `'[[1,2],[3,4]]'`(SKELETON) 주입(srcSn=6) → `GET .../frames/6/labels` **200** `labels: []`, **500 미발생**. `parsePointsRouted`/`parsePoints` 의 `catch(RuntimeException)` → 빈 리스트(`:361-364`, `:383-387`) |

### F-3 반증(확증편향 차단) 로그

| 반증 시도 | 결과 |
|---|---|
| 저장이 `LS_DATA_LBL`/이력/버전/export 를 건드리는가 | ✅ 전부 불변 (md5·건수 동일) |
| 저장이 관제 통지를 유발하는가(단방향 위반) | ✅ mock-server 인바운드 로그 증가 0 |
| `isExposedToDatamart` 가 상태행 부재 시 fail-open 하는가 | ✅ `.orElse(false)` → 403 |
| SKELETON 의 `isFinite` 가드가 Jackson 에 가려 죽은 코드인가 | ⚠ **아니다** — `1e400` 로 도달 실증 |
| **비SKELETON 경로도 같은 검증을 받는가** | ❌ **아니다 → F-ISSUE-21** (BBOX `1e400` → 201 저장) |
| 좌표 개수 상한이 있는가 | ❌ **없다 → F-ISSUE-22** (30,000점 → 201) |
| `lblTypeCd` 가 열거로 제한되는가 | ❌ **아니다 → F-ISSUE-23** (`"HACK"` 저장됨) |
| `sourceSrcSn` 이 `sourceRawSn` 소속인지 검증되는가 | ❌ **아니다 → F-ISSUE-24** (존재하지 않는 9001 / 타 영상 프레임 35 저장됨) |
| 신고 구간(`'F'`) 영상에 저장이 막히는가 | ❌ 막히지 않음 → **F-ISSUE-26**(조회는 412 로 정상 차단됨을 대조 확인) |
| 본인 라벨 조회가 페이징되는가 | ❌ 아니다 → **F-ISSUE-25** |

---

## 2. F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태) — 13건

사전 준비: 3011 이 PNG 3건 업로드(`uldSn=63,64,65` / `uldFrmeSn=63,64,65`), 3012 가 1건(`uldSn=66`). 전부 `READY`.

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|:--:|------|
| TC-PORTALUP-020 | PASS | [실동작] | `GET /v1/portal/uploads` — 3011 → `totalElements=3 ids=[63,64,65]`, 3012 → `totalElements=1 ids=[66]`. 소유자 스코프 `findAllByPortalUserNo`(`PortalUploadService.java:161`). 페이징 응답 래퍼 정상(`size/number/totalPages`) |
| TC-PORTALUP-021 | PASS | [실동작] | `?type=IMAGE` → total 3, `?type=VIDEO` → total 0, 소문자 `image`/`video` 도 동일(`toUpperCase` 정규화 `:165`) |
| TC-PORTALUP-022 | PASS | [실동작] | `?type=FOO` → **400** `"지원하지 않는 type 입니다. 허용: IMAGE, VIDEO"` (`:166-169`). 대조: `?type=`(빈값)·공백 → 200 무필터(코드 `:160-163` 과 일치, 조용한 빈결과 아님) |
| TC-PORTALUP-023 | PASS | [실동작] | `/uploads?size=` 20→20, 100→100, **101→100, 500→100, 5000→100**. `/uploads/63/frames?size=500→100, 5000→100`. `size=-1`,`0` → 기본 20. `capped()` `PortalUploadController.java:177-182`(카탈로그 표기 `:145-151` 은 드리프트) |
| TC-PORTALUP-024 | PASS | [실동작] | 3012 가 `GET /uploads/63` → **403** `"본인 자산이 아니거나 존재하지 않습니다."`. **부재와 동일 응답** 확인: `/uploads/999999` → 동일 403/동일 메시지(자원 열거 차단). 3011 본인 조회는 200 |
| TC-PORTALUP-025 | PASS | [실동작] | 3012 가 `GET /uploads/63/frames` → **403**(사전 소유권 검증 `:189` + 소유자 스코프 조인 `:190`). 3011 본인 → 200 `totalElements=1` |
| TC-PORTALUP-026 | PASS | [실동작] | 3012 가 `GET /uploads/frames/63/image` → **403**(`findByUldFrmeSnAndOwner` `:207`). 존재하지 않는 `frames/999999/image` 도 동일 403 |
| TC-PORTALUP-027 | PASS | [실동작] | 3011 본인 서빙 → **200**, `Content-Type: image/png`(DB `mime_type_nm` 와 일치), `X-Content-Type-Options: nosniff`, `Content-Disposition: inline; filename="frame_63"`(사용자 입력 미반영), 바디 매직바이트 `\x89PNG\r\n\x1a\n`. **신고 게이트 미적용 확인**(412 아님) — TC 명세대로 ADR-013 예외 경로. ★반증: DB `mime_type_nm='text/html'` 로 조작 후 재요청 → **`application/octet-stream`**(`resolveStoredMediaType` fail-closed `:426-435`) + nosniff 유지 → XSS 서빙 불가 |
| TC-PORTALUP-028 | PASS | [실동작] | DB 에 조작 경로 프레임 3종 주입 후 서빙 시도 → **전부 403** `"허용되지 않은 경로입니다."` ①`/app/storage/raw/portal/images/../../../../etc/hostname` ②절대경로 `/etc/hostname` ③상대경로 `../../../etc/hostname` (`resolveSafe` `:415-424`, 카탈로그 `:388-398` 은 드리프트). ★★반증 강화: base **안쪽**에 `images/qa-symlink-test.png → /etc/hostname` 심링크를 실제로 만들고 그 경로로 서빙 시도 → **403** `"허용되지 않은 이미지 경로입니다."`(`realWithinBase` `:245-257` 의 `toRealPath` 재검증). lexical 통과 + 심링크 탈출 모두 차단됨 (CWE-22/59/367) |
| TC-PORTALUP-029 | PASS | [실동작] | `uldSn=64`(파일 `.../3f606578-....png` 존재) + 라벨 1행(`ls_portal_uld_lbl`) 상태에서 `DELETE /uploads/64` → **204**. 직후 `ls -la` → `No such file or directory`(파일 선삭제), DB `uld=0 / frme=0 / lbl=0`(CASCADE). 순서 = 소유권→파일→DB (`:265-292`) |
| TC-PORTALUP-030 | PASS | [실동작] | `uldSn=65` 를 `PROCESSING` 으로 UPDATE → `DELETE` **409** `"프레임 추출이 진행 중인 자산은 삭제할 수 없습니다..."`, **행 보존(count=1)** (`:273-276`). 대조: `UPLOADED` → 204 삭제됨(코드와 일치, 단 주석의 "READY/FAILED 후 허용" 과 불일치 → F-ISSUE-28) |
| TC-PORTALUP-031 | PASS | [실동작] | `file_path_nm` 을 **비어있지 않은 디렉터리**(`images/qa-dir-test/`)로 가리키는 자산 주입 → `DELETE` 시 `Files.deleteIfExists` 가 `DirectoryNotEmptyException`(IOException) → **500** `"파일 삭제에 실패했습니다. 잠시 후 다시 시도하세요."`, **DB `uld_rows=1 / frme_rows=1` 보존**(`deleteFileOrThrow` `:400-408`). 응답에 경로·스택트레이스 미노출(CWE-209) |
| TC-PORTALUP-032 | PASS | [실동작] | 3012 가 `DELETE /uploads/64`(3011 소유) → **403** `"본인 자산이 아니거나 존재하지 않습니다."`, 대상 행 **보존(count=1)** (`:267-269`) |

### F-6 반증 로그

| 반증 시도 | 결과 |
|---|---|
| 403 이 "부재"와 "타인 소유"를 구분해 열거를 허용하는가 | ✅ 동일 메시지·동일 코드 → 구분 불가 |
| DB MIME 조작으로 `text/html` 서빙 가능한가 (Stored XSS) | ✅ 불가 — octet-stream fail-closed |
| base 안쪽 심링크로 `/etc` 탈출 가능한가 | ✅ 불가 — `toRealPath` 재검증 403 |
| 페이지 크기 상한 우회(`size=5000`) | ✅ 불가 — 100 클램프 |
| 파일 삭제 실패 시 DB 만 지워져 고아 행/유실이 생기는가 | ✅ 아니다 — 파일 선삭제 + 실패 시 DB 미삭제 |
| **정렬 키 allowlist 가 실제로 적용되는가** | ❌ **라이브 미적용(배포 드리프트) → F-ISSUE-27** (`sort=status` 500, `sort=filePathNm` 200 실정렬) |
| 삭제 상태 검사에 락이 있는가 | ❌ 없음 → **F-ISSUE-28** |
| 자산 상세의 프레임 목록이 페이징되는가 | ❌ 아니다(최대 2,000건 일괄) → **F-ISSUE-29** |

---

## 3. 테스트 커버 대조 (`_raw/test-baseline.md` = 2026-08-01/1차, BE 4,755 tests / 실패 0)

| 케이스 | 커버 테스트 (`파일:@DisplayName`) | baseline |
|---|---|:--:|
| TC-PORTAL-040 | `PortalUserLabelServiceTest.java:113 V2_사용자_작업_데이터_별도_적재_원본_미수정` | 통과 |
| TC-PORTAL-041 | `PortalLabelServiceKeypointTest.java:145 포털_비APPROVED_영상_사용자라벨_저장_거부_403` | 통과 |
| TC-PORTAL-042 | `PortalUserLabelServiceTest.java:158 Phase9_포털_키포인트_SKELETON_17점_정상_저장` · `PortalLabelServiceKeypointTest.java:118 …round-trip` | 통과 |
| TC-PORTAL-043 | `PortalUserLabelServiceTest.java:174 …점개수_불일치_400_저장안함` | 통과 |
| TC-PORTAL-044/045 | `PortalUserLabelServiceTest.java:188 …형식위반_400_저장안함` (v 범위/유한성 단독 케이스는 없음 — 부분 커버) | 통과 |
| TC-PORTAL-046 | `PortalUserLabelServiceTest.java:133 R17_…빈_좌표_JSON_시_INVALID_INPUT_빈라벨row_차단` | 통과 |
| TC-PORTAL-047 | `PortalLabelControllerTest.java`(DTO @Valid 경로) | 통과 |
| TC-PORTAL-048 | `PortalUserLabelServiceTest.java:215 V2_본인_작업_라벨_조회_IDOR_본인만` · `:229 V2_다른_사용자_데이터_접근_불가_IDOR` | 통과 |
| TC-PORTAL-049 | `PortalFrameLabelsServiceTest.java:149 R17_…빈좌표_user_label은_제외하고_datamart_원본_폴백` | 통과 |
| TC-PORTAL-050 | 전용 테스트 **없음**(손상 JSON fail-secure 단독 케이스 부재 — 실동작으로만 확인) | — |
| TC-PORTALUP-020/021/022 | `PortalUploadServiceTest.java:325/340`, `PortalUploadControllerTest.java:266/283` | 통과 |
| TC-PORTALUP-023 | `PortalUploadControllerTest.java:291 size_100초과시_100으로_캡` | 통과 |
| TC-PORTALUP-024/025/026 | `PortalUploadServiceTest.java:224/354/231`, `PortalUploadControllerTest.java:203/300/214` | 통과 |
| TC-PORTALUP-027 | `PortalUploadControllerTest.java:231 이미지_서빙_응답에_nosniff_헤더와_DB_확정_ContentType` | 통과 |
| TC-PORTALUP-028 | `PortalUploadServiceTest.java:419 경로조작_파일명_업로드시_저장경로가_베이스_밖으로_나가지_않음`(업로드 축) — **서빙 축 + 심링크 탈출 전용 테스트 없음(갭)** | 통과 |
| TC-PORTALUP-029 | `PortalUploadServiceTest.java:255 삭제시_파일과_DB행이_함께_제거됨`, `PortalUploadControllerTest.java:326` | 통과 |
| TC-PORTALUP-030 | `PortalUploadProcessingDeleteTest.java:34 PROCESSING_자산_삭제시_409` | 통과 |
| TC-PORTALUP-031 | `PortalUploadServiceTest.java:278 파일삭제_실패시_DB행_보존` | 통과 |
| TC-PORTALUP-032 | `PortalUploadServiceTest.java:238 타사용자_삭제시_403`, `PortalUploadControllerTest.java:223` | 통과 |

> ⚠ `PortalUploadControllerTest.java:350/368/378/396`(정렬 allowlist 4건)은 **소스에만 존재**하고 배포 jar 에는 없다 → F-ISSUE-27 의 방증.

---

## 4. 근거 드리프트 (카탈로그 `file:line` ↔ 실측)

| ID | 카탈로그 표기 | 실측 위치 |
|---|---|---|
| TC-PORTAL-040 | `PortalLabelService.java:196-223` | `:196-224` |
| TC-PORTAL-041 | `:202-205` | `:203-206` (+ 판정기 `:504-508`) |
| TC-PORTAL-042 | `:208-209` | `:209-217` (검증 본체 `:317-342`) |
| TC-PORTAL-043 | `:324-327` | `:325-328` |
| TC-PORTAL-044 | `:329-332` | `:330-333` |
| TC-PORTAL-045 | `:333-336` | `:334-337`(유한성) + `:338-340`(음수) |
| TC-PORTAL-046 | `:211-216` | `:212-217` |
| TC-PORTAL-048 | `:226-234` | `:226-235` |
| TC-PORTAL-049 | `:284-286` | `:285-287` |
| TC-PORTAL-050 | `:375-387` | `:376-388` |
| TC-PORTALUP-020 | `PortalUploadService.java:156-171` | `:156-172` |
| TC-PORTALUP-021 | `:164-170` | `:164-171` |
| TC-PORTALUP-022 | `:163-168` | `:165-169` |
| TC-PORTALUP-023 | `PortalUploadController.java:145-151` | `:176-182`(`capped`) — **31행 드리프트** |
| TC-PORTALUP-024 | `:174-181` | `:174-182` |
| TC-PORTALUP-025 | `:184-191` | `:184-192` |
| TC-PORTALUP-026 | `:204-210` | `:205-211` |
| TC-PORTALUP-027 | `:193-231` | `:194-236` |
| TC-PORTALUP-028 | `:213,388-398` | `:213-214` + `resolveSafe :415-424` + `realWithinBase :245-257` — **27행 드리프트 + 판정기 1종 누락** |
| TC-PORTALUP-029 | `:239-266` | `:265-292` — **26행 드리프트** |
| TC-PORTALUP-030 | `:245-250` | `:273-276` |
| TC-PORTALUP-031 | `:374-382` | `:400-408` |
| TC-PORTALUP-032 | `:242-243` | `:267-269` |

> 23/24 케이스가 드리프트(대부분 1~3행, 4건은 26~31행). 카탈로그 정합성 정정 대상.

---

## 5. 이슈

### [F-ISSUE-21] TC-PORTAL-045 인접 — 포털 사용자 라벨 저장이 **비SKELETON 타입에는 좌표 유한성·경계 검증을 전혀 하지 않는다**
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: SKELETON 에 적용된 fail-closed 검증(유한성·음수 거부)은 좌표를 저장하는 모든 타입에 적용돼야 한다. 저장된 좌표는 FE 캔버스가 `number` 로 소비하므로 비유한값이 들어가면 렌더가 깨지고, 경계를 벗어난 좌표는 학습데이터로서 의미가 없다. 내부 경로는 `LabelService.validateWithinBounds` 로 **400 거부**(CLAUDE.md ★3 "사용자 저장 = 400 거부")를 이미 강제한다.
- **현재 동작(이슈 내용)**: `PortalLabelService.saveUserLabel` 은 타입 분기에서 SKELETON 만 `validateSkeletonPoints` 로 보내고, 그 외는 "빈 좌표인지"만 본다.
  ```java
  // PortalLabelService.java:209-217
  if (LsDataLbl.TYPE_SKELETON.equals(req.lblTypeCd())) {
      validateSkeletonPoints(req.points());          // 유한성·범위·개수 검증
  } else {
      if (parsePoints(req.points()).isEmpty()) {     // ← 비어있는지만 본다
          throw new CustomException(ErrorCode.INVALID_INPUT, "points 좌표가 비어있습니다.");
      }
  }
  ```
  실측: `{"lblTypeCd":"BBOX","points":"[[1e400,1e400],[2,2]]"}` → **201**(userLblSn=5). 로드 시
  `GET /v1/portal/frames/1/labels` 응답이 `"points":[["Infinity","Infinity"],[2.0,2.0]]` — Jackson 이 비유한 double 을 **문자열**로 직렬화해 `number[][]` 계약이 깨진다. 또한 `[[-99999,-99999],[999999999,999999999]]` 도 **201**(경계 검증 부재).
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
     -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  curl -s -XPOST localhost:18081/api/v1/portal/user-labels -H "Authorization: Bearer $TOK" \
     -H 'Content-Type: application/json' \
     -d '{"sourceRawSn":4,"sourceSrcSn":1,"lblTypeCd":"BBOX","label":"inf","points":"[[1e400,1e400],[2,2]]"}'   # 201
  curl -s localhost:18081/api/v1/portal/frames/1/labels -H "Authorization: Bearer $TOK" | grep Infinity
  ```
- **영향**: 데이터 정합(학습데이터로 쓸 수 없는 좌표 적재) + FE 계약 파손(CWE-20 입력 검증 미흡). PII 노출은 아님.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 의 else 분기에 ①`Double.isFinite` 검사 ②(가능하면) 프레임 해상도 기반 경계 검사를 추가한다. 내부 `LabelService` 의 검증 헬퍼를 공용 유틸로 추출해 두 경로가 같은 판정기를 쓰게 하는 편이 드리프트를 막는다. ⚠ 구현하지 않음.

### [F-ISSUE-22] TC-PORTAL-040/046 인접 — 포털 사용자 라벨 **좌표 개수 상한 부재** (내부 경로는 1,000점 상한)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨 1건의 좌표 수는 상한을 가져야 한다. 내부 라벨 경로는 `LabelService.MAX_POINTS_PER_LABEL = 1000` 을 두고 초과 시 400 또는 단순화(`PolygonSimplifier`)한다. 외부 채널(포털)은 인증만 있으면 누구나 호출하므로 상한이 더 필요하다.
- **현재 동작(이슈 내용)**: 포털 저장 경로에는 개수 검사가 없다(`PortalLabelService.java:209-220` 전체에 `size()` 비교 없음). 실측: 30,000점 POLYGON(요청 본문 458KB) → **201**(userLblSn=8). 이후 `GET /v1/portal/frames/1/labels` 응답이 **519,395 bytes** 로 부풀고, 이 엔드포인트는 **페이징이 없어** 같은 프레임에 이런 라벨을 여러 건 쌓으면 응답이 선형 증가한다.
  ```
  $ curl ... /v1/portal/frames/1/labels -o f.json -w '%{size_download}'
  519395
  ```
- **재현/확인 경로**: `points` 에 `[[i,i] for i in range(30000)]` JSON 을 넣어 `POST /v1/portal/user-labels` → 201. `GET /v1/portal/frames/{srcSn}/labels` 응답 크기 확인.
- **영향**: 자원 소진(CWE-770 / OWASP API4:2023) — 저장·조회 양쪽. `portal.upload.max-label-body-bytes`(2MB)는 **포털 업로드 라벨 PUT 전용 필터**라 이 엔드포인트를 막지 않는다.
- **수정 방향(제안)**: `PortalLabelService.saveUserLabel` 에 `MAX_POINTS_PER_LABEL` 동일 상한(공용 상수 참조)을 적용해 초과 시 400. 아울러 `loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입. ⚠ 구현하지 않음.

### [F-ISSUE-23] TC-PORTAL-040 인접 — `lblTypeCd` 가 **열거로 검증되지 않아 임의 문자열이 저장**된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 라벨 타입은 `BBOX/POLYGON/SEGMENT/SKELETON/TRACK` 등 정해진 코드값만 허용돼야 한다. 타입은 로드 시 **파싱 라우팅 키**(`parsePointsRouted`)로 쓰이므로 미지의 값은 조용히 2-튜플 경로로 흘러간다.
- **현재 동작(이슈 내용)**: DTO 제약이 `@NotBlank @Size(max = 16)` 뿐이다(`PortalUserLabelRequest.java:13`). 서비스도 `TYPE_SKELETON` 동등 비교만 한다. 실측: `{"lblTypeCd":"HACK",...}` → **201**(userLblSn=7), 로드 응답에 `{"lblTypeCd":"HACK","points":[[1.0,1.0],[2.0,2.0]]}` 그대로 반환.
- **재현/확인 경로**: `POST /v1/portal/user-labels` 에 `"lblTypeCd":"HACK"` → 201, `GET /v1/portal/frames/1/labels` 응답에 그대로 노출.
- **영향**: 데이터 정합(다운로드/Export 소비자가 알 수 없는 타입을 만남). 값이 응답 JSON 문자열로만 나가므로 XSS 는 아니나 FE 분기 로직이 깨질 수 있다(CWE-20).
- **수정 방향(제안)**: DTO 에 `@Pattern` 또는 서비스에서 허용 코드 집합(allowlist) 검사 후 400. ⚠ 구현하지 않음.

### [F-ISSUE-24] TC-PORTAL-041 인접 — `sourceSrcSn` 의 **존재 여부·부모 영상 소속을 검증하지 않아** APPROVED 게이트가 프레임 축에서 무력화된다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 저장 요청의 `sourceSrcSn` 은 ①실재하는 프레임이어야 하고 ②`sourceRawSn` 에 속해야 한다. 그래야 "APPROVED 영상만 작업 가능"이라는 게이트가 프레임 단위로도 성립하고, 저장된 행이 로드 경로(프레임 기준 조회)와 일관된다.
- **현재 동작(이슈 내용)**: 게이트는 `sourceRawSn` 하나만 본다.
  ```java
  // PortalLabelService.java:203-206
  if (!isExposedToDatamart(req.sourceRawSn())) { ... throw FORBIDDEN; }
  // sourceSrcSn 은 이후 어디에서도 조회·대조되지 않고 그대로 적재된다 (:218-220)
  ```
  실측 ①존재하지 않는 프레임: `{"sourceRawSn":900,"sourceSrcSn":9001}` → **201**(rawSn 900 의 실제 프레임은 429,430). ②타 영상 프레임: `{"sourceRawSn":4,"sourceSrcSn":35}` (srcSn 35 는 **미승인** `rawSn=7` 소속) → **201**(userLblSn=19).
  DB 스키마상 FK 는 `src_raw_sn → ls_data_raw` 하나뿐이고 `src_data_src_sn` 에는 FK 가 없다(`\d ls_portal_user_label`).
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' \
    -d '{"sourceRawSn":4,"sourceSrcSn":35,"lblTypeCd":"BBOX","label":"cross","points":"[[1,1],[2,2]]"}'   # 201
  # 대조: 그 프레임을 읽으려 하면 정상적으로 막힌다
  curl .../v1/portal/frames/35/labels -H "Authorization: Bearer $TOK"   # 403 (미승인 영상)
  ```
- **영향**: 데이터 정합 위주. **정보 유출은 없다**(로드 경로 `loadFrameLabels` 가 프레임의 실제 `rawSn` 으로 다시 게이팅해 403/412 를 낸다 — 실측 확인). 다만 미승인·신고 영상 프레임 번호에 매달린 고아 라벨이 무제한 쌓이고, 향후 다운로드/집계가 프레임 기준으로 조인하면 잘못된 영상에 라벨이 붙는다(CWE-20 / 약한 형태의 CWE-639).
- **수정 방향(제안)**: `saveUserLabel` 에서 `srcRepository.findById(sourceSrcSn)` 로 프레임을 조회해 부재 시 404, `frame.getRawSn() != sourceRawSn` 이면 400/403. 겸사 `src_data_src_sn` 에 FK 추가 검토. ⚠ 구현하지 않음.

### [F-ISSUE-25] TC-PORTAL-048 인접 — `GET /v1/portal/user-labels` 가 **페이징 없이 전건 반환**한다
- **심각도**: LOW
- **기대 동작(기대효과)**: `.claude/rules/api-design.md` — "목록 조회는 반드시 페이징 적용. 전체 조회(페이징 없는 findAll) 금지."
- **현재 동작(이슈 내용)**: 컨트롤러·서비스 모두 `List<>` 를 그대로 반환한다.
  ```java
  // PortalLabelController.java:106-113  → @GetMapping("/user-labels") … ApiResponse<List<PortalUserLabelResponse>>
  // PortalLabelService.java:228-235
  return userLabelRepository.findByPortalUserNoAndSrcRawSnOrderByRegDtDesc(actor.sub(), rawSn)
          .stream().map(PortalUserLabelResponse::from).toList();
  ```
  같은 영상에 사용자가 저장한 라벨이 누적되면(현 설계상 매 저장이 **INSERT**, upsert 아님) 응답이 무한 증가한다. `loadFrameLabels`(`:249-304`)도 동일하게 무페이징이며 F-ISSUE-22 와 곱해진다.
- **재현/확인 경로**: 같은 `(rawSn, srcSn)` 으로 `POST /v1/portal/user-labels` 를 N 회 반복 → `GET /v1/portal/user-labels?rawSn=4` 응답 항목이 N 개로 선형 증가(실측: 8건까지 확인).
- **영향**: 자원 소진(CWE-770), 규칙 위반.
- **수정 방향(제안)**: `Pageable` 도입(기본 20 / 상한 100, `PortalUploadController.capped` 패턴 재사용). 하위호환이 걸리면 응답 래퍼 유지 + 상한만 우선 적용. ⚠ 구현하지 않음.

### [F-ISSUE-26] TC-PORTAL-040 인접 — 비식별 누락 신고 구간(`DE_IDENT_YN='F'`) 영상에도 **포털 사용자 라벨 저장이 허용**된다 (조회는 412)
- **심각도**: LOW
- **기대 동작(기대효과)**: 신고 구간에는 그 영상에 대한 작업 자체를 잠그는 것이 일관적이다. 내부 경로는 작업락으로 저장·수정을 409 차단하고, 포털 조회 경로는 412 로 막는다(CLAUDE.md 차단 범위 ④).
- **현재 동작(이슈 내용)**: `saveUserLabel` 에는 `accessGuard.requireNotUnderDeidentReport` 호출이 없다(`PortalLabelService.java:196-224`). 같은 서비스의 `loadFrameLabels`(`:267`)·`serveFrameImage`(`:443`) 에는 있다.
  실측: `rawSn=900`(APPROVED, `DE_IDENT_YN='F'`) 에 저장 → **201**(userLblSn=18). 대조로 같은 영상 프레임 조회 → **412** `"비식별 재처리 대기 중인 영상은 라벨을 조회할 수 없습니다."`
- **재현/확인 경로**:
  ```bash
  curl -XPOST .../v1/portal/user-labels -d '{"sourceRawSn":900,"sourceSrcSn":429,...}'   # 201
  curl .../v1/portal/frames/429/labels                                                    # 412
  ```
- **영향**: PII 노출 없음(이미지·라벨 조회가 모두 막혀 있어 유의미한 라벨을 만들 수 없다). **일관성/데이터 정합** 축의 갭이며, 신고 해제 후 남는 무의미한 라벨 행이 문제.
- **수정 방향(제안)**: 정책 판단 필요 — ①`saveUserLabel` 앞에 동일 게이트를 추가해 412 로 통일하거나 ②"저장은 본인 작업본이므로 허용"을 의도로 못 박고 주석·문서에 남긴다. ⚠ 구현하지 않음. (CLAUDE.md 차단 범위 목록에 write 경로가 없어 **확정 정책 위반으로 단정하지 않는다**)

### [F-ISSUE-27] TC-PORTALUP-023 인접 — **배포된 backend jar 가 워킹트리 소스보다 오래되어** 포털 정렬 allowlist 가 라이브에 없다 (`sort=status` → 500)
- **심각도**: HIGH (검증 환경 신뢰성 · 라이브 노출 기준으로는 CWE-209/770)
- **기대 동작(기대효과)**: 검증 대상 스택은 검증 기준 소스(qa-0801)와 동일 산출물이어야 한다. 소스는 `PortalUploadController.safeSort/safeFrameSort` → `SortAllowlist.resolve`(strict)로 미등록 정렬 키를 **400** 으로 거부한다(A-ISSUE-61 수정분, `PortalUploadController.java:164-174`).
- **현재 동작(이슈 내용)**: 컨테이너 jar 에 그 배선이 없다.
  ```
  docker inspect klid-backend → created 2026-08-01T14:10:06Z, /app/app.jar mtime Aug 1 14:05
  jar!BOOT-INF/classes/.../PortalUploadController.class : safeSort=False, SortAllowlist=False, capped=True
  jar!BOOT-INF/classes/.../SortAllowlist.class          : PORTAL_UPLOAD=False, PORTAL_UPLOAD_FRAME=False, DEIDENT_REPORT=False
  git status --short | wc -l → 80 (미커밋 수정), 그중 SortAllowlist.java·PortalUploadController.java·PortalLabelController.java 포함
  ```
  라이브 실측:
  ```
  GET /v1/portal/uploads?sort=status,asc      → 500 INTERNAL_ERROR
     backend log: ERROR GlobalExceptionHandler - unhandled exception
       org.springframework.data.mapping.PropertyReferenceException: No property 'status' found for type 'LsPortalUld'
  GET /v1/portal/uploads?sort=filePathNm,desc → 200, 응답 sort.sorted=true  (응답에 노출되지 않는 내부 저장경로 컬럼으로 실제 정렬)
  GET /v1/portal/uploads?sort=orgnlFileNm,asc → 200 (소스에서는 PII 사유로 의도적 제외 키)
  ```
- **재현/확인 경로**: 위 3개 curl + `docker cp klid-backend:/app/app.jar` 후 클래스 문자열 검사.
- **영향**: ①**검증 무효화 위험** — 이 스택 위의 "실동작 PASS" 가 소스 기준 동작을 보증하지 못한다(이번 F-3/F-6 24건은 무관함을 개별 확인했으나, 정렬·SAM2·`PortalLabelController` 를 다루는 다른 파트는 영향권). ②배포본 자체 기준으로는 내부 엔티티명이 ERROR 로그에 적재되는 CWE-209 + 정렬 항목 상한 부재(CWE-770)가 그대로 살아 있다.
- **수정 방향(제안)**: 검증 착수 전 **소스 재빌드 → 이미지 재배포**를 환경 게이트(§3-1)에 추가하고, `_raw/stack-bringup.md` 에 "배포 산출물 커밋 해시 = 검증 기준 해시" 대조 항목을 명시한다. 아울러 워킹트리의 미커밋 80파일을 커밋/정리해 기준을 확정한다. ⚠ 코드는 건드리지 않음.

### [F-ISSUE-28] TC-PORTALUP-030 인접 — 삭제 상태 검사에 **락이 없고 `UPLOADED` 도 삭제 허용**되어 비동기 추출 러너와 경합 가능
- **심각도**: LOW
- **기대 동작(기대효과)**: 주석이 선언한 대로 "처리 완료(READY/FAILED) 후 허용"이어야 하고, 상태 판정 후 파일 삭제까지 그 상태가 유지돼야 한다(프레임 추출 러너가 쓰는 파일을 삭제 중 제거하면 파일-DB 불일치).
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:268-276
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(uldSn, portalUserNo)   // 락 없음
          .orElseThrow(this::forbidden);
  if (LsPortalUld.STTS_PROCESSING.equals(uld.getUldSttsCd())) { throw CONFLICT; }   // PROCESSING 만 차단
  ```
  실측: `uld_stts_cd='UPLOADED'` 로 두고 `DELETE` → **204**(삭제됨). `UPLOADED` 는 TUS 업로드 완료 후 프레임 추출 시작 **직전** 상태라, 검사 통과 직후 러너가 `UPLOADED→PROCESSING` 으로 전이하면 삭제와 추출이 겹친다(TOCTOU). `findByUldSnAndPortalUserNo` 에 `@Lock` 없음(레포지토리 확인).
- **재현/확인 경로**: `UPDATE ls_portal_uld SET uld_stts_cd='UPLOADED' WHERE uld_sn=N;` → `DELETE /v1/portal/uploads/N` → 204. 경합 자체는 추출 러너 기동과 동시 실행이 필요해 결정적 재현은 미수행(관찰은 상태 허용 사실까지).
- **영향**: 데이터 정합(고아 프레임 행/파일), 확률 낮음(CWE-362).
- **수정 방향(제안)**: ①차단 상태를 `PROCESSING` + `UPLOADED` 로 넓히거나 ②조건부 UPDATE(`... SET uld_stts_cd='DELETING' WHERE uld_sn=? AND uld_stts_cd IN ('READY','FAILED')`)로 원자 클레임 후 삭제. 최소한 주석과 코드의 상태 집합을 일치시킨다. ⚠ 구현하지 않음.

### [F-ISSUE-29] TC-PORTALUP-023 인접 — 자산 상세(`GET /v1/portal/uploads/{uldSn}`)의 **프레임 목록이 무페이징**
- **심각도**: LOW
- **기대 동작(기대효과)**: 목록성 응답은 페이징하거나 상한을 둔다(api-design.md). 별도 엔드포인트 `/{uldSn}/frames` 는 이미 100 하드캡이 있는데 상세는 없다.
- **현재 동작(이슈 내용)**:
  ```java
  // PortalUploadService.java:176-182
  List<LsPortalUldFrme> frames = frmeRepository.findAllByUldSnOrderByFrmeNo(uld.getUldSn());
  return PortalUploadDetailResponse.of(uld, frames);   // frames 전건을 DTO 로 직렬화
  ```
  영상 자산의 프레임 수는 `portal.upload.max-frames`(기본 2,000)까지 가능하므로 상세 1회 호출이 최대 2,000 항목을 반환한다. 실측(이미지 자산 1프레임)에서는 노출되지 않는 경로라 코드/설정 기준.
- **재현/확인 경로**: 프레임 다수(수백~2,000)인 VIDEO 자산에 `GET /v1/portal/uploads/{uldSn}` → 응답 `frames` 배열 길이 = 전체 프레임 수.
- **영향**: 자원 소진(CWE-770). 상한이 2,000 으로 유한해 심각도 LOW.
- **수정 방향(제안)**: 상세 응답에서 `frames` 를 제거(별도 `/frames` 페이징 엔드포인트로 유도)하거나 상위 N 건 + `frmeCnt` 만 반환. ⚠ 구현하지 않음.

---

## 6. 남긴 테스트 데이터 (다음 회차 참고)

| 테이블 | 내용 |
|---|---|
| `ls_portal_user_label` | `portal_user_no` 3001(userLblSn 1~8,10~16,18,19) · 3002(9) — F-3 검증용. 10~16 은 stale/손상 좌표 주입분 |
| `ls_portal_uld` / `_frme` | 3011(`uldSn=63`), 3012(`uldSn=66`) 잔존. `64/65/77/79` 는 검증 중 삭제 완료 |
| 파일시스템 | `images/qa-symlink-test.png`(심링크)·`images/qa-dir-test/` 는 **검증 후 제거 완료** |
# F 클러스터 part3 — `F-4. 포털 SAM2` (TC-PORTAL-060~078, 19건)

- 검증일: 2026-08-02 (2차)
- 대상 파일: `docs/test-cases/F-portal.md` § F-4
- 코드 기준: 워크트리 `qa-0801`
- 실동작 환경: backend `localhost:18081/api` · ai-server `localhost:19300`(`AI_MOCK_MODE=false`, `source=model` 실측) · PostgreSQL `klid-postgres/klid_system/public`
- 토큰: `POST /api/v1/dev/tokens` — `{"role":"PORTAL_USER","channel":"PORTAL","sub":...}`

## 0. 사용한 실데이터 (DB 실측)

| rawSn | 작업상태 | `DE_IDENT_YN` | 파생 | 프레임 | 용도 |
|---|---|---|---|---|---|
| 4 | APPROVED | Y | - | srcSn 1~30 (raw+deid 양쪽 존재, md5 상이) | 정상 경로 |
| 18 | APPROVED | Y | `ORGNL_RAW_SN=4` | srcSn 45~74 (**`SRC_FILE_PATH_NM` NULL**, deid만) | 비식별본 전용 전송 실증 |
| 900 | APPROVED | **F**(신고 구간) | - | srcSn 429~433 | 신고 게이트 |
| 902 | APPROVED | Y | - | srcSn 445 (**raw 존재 / deid NULL**) | 원본 폴백 금지 실증 |
| 27 | ASSIGNED | Y | - | srcSn 301 | 비APPROVED 거부 |

> raw/deid 바이트 상이 확인: `raw/frames/raw/4/frame-0.jpg` 9,441B `be89bcc1…` vs `deidentified/frames/deid/4/frame-0.jpg` 13,164B `efadbd02…`

## 1. 판정 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|:--:|---|---|
| TC-PORTAL-060 | FAIL | [실동작] | **정책 위반 재확인 — 엔드포인트 여전히 살아있고 실동작한다.** `POST /api/v1/portal/frames/1/sam2-segment` (PORTAL 토큰) → **HTTP 200**, `polygon` 176점 실좌표 반환. ai-server 실추론(`source=model`) 경유. → **F-ISSUE-41** |
| TC-PORTAL-061 | FAIL | [실동작] | 동상. `POST /v1/portal/frames/1/sam2-track` (next=[2,3]) → **200**, `tracked` 2건 실좌표. `ls_data_lbl` 에 `trck_id like 'qa-track%'` 0건 = persist 없음(설계대로)이나 **기능 노출 자체가 정책 위반**. → **F-ISSUE-41** |
| TC-PORTAL-062 | PASS | [실동작] | srcSn=301(raw27 ASSIGNED) segment/track 모두 **403** `"데이터마트에 노출되지 않은 영상입니다."` — `requireExposedFrame` → `isDatamartExposed`(행 부재/타상태 = fail-closed) `PortalSam2Service.java:236-251` |
| TC-PORTAL-063 | PASS | [실동작] | srcSn=99999999 → **404** `"프레임을 찾을 수 없습니다."` `PortalSam2Service.java:237-238` |
| TC-PORTAL-064 | PASS | [실동작] | path=1 / body srcSn=2 → segment·track **양쪽 400** `"path 의 srcSn 과 body 의 srcSn 이 다릅니다."` (CWE-345) `PortalSam2Controller.java:86-90` |
| TC-PORTAL-065 | PASS | [정적] | `aiRes.untrusted()`(`AiMockMeta` 긍정증명) → `Sam2SegmentResponse.empty()` + 컨트롤러가 `MOCK_UNAVAILABLE_MESSAGE` 세팅 `PortalSam2Service.java:127-131` / `PortalSam2Controller.java:80-82`. track 은 해당 프레임 제외 + `anyMock` 안내 `:187-195`. **live 미재현 사유**: ai-server 실효 `AI_MOCK_MODE=false` + 가중치 로드 상태(직접 호출 실측 `{"mock":false,"source":"model"}`) — mock 유도는 설정 변경이 필요해 §10 절대규칙상 금지. 단위테스트 4건 커버(`PortalSam2ServiceTest#segment_mock_returnsEmptyPolygon`, `#segment_omittedMockMeta_returnsEmptyPolygon`, `#track_mockResponse_excluded`, `#track_omittedMockMeta_excluded`) |
| TC-PORTAL-066 | PASS | [실동작] | 동시 10요청(단일 포털 사용자 9002, srcSn=1) → **정확히 4건 200 / 6건 429** `"SAM2 동시 요청이 많습니다…"`. `application.yml:675-677` `portalSam2.max-concurrent-calls=4, max-wait-duration=0` 과 정확히 일치 |
| TC-PORTAL-067 | PASS | [실동작] | 신규 사용자(sub=9001) 35연속 요청 → 1~26 정상(404), **27번째부터 429** `"SAM2 요청이 너무 많습니다…"`. permit 획득이 프레임 조회보다 앞(`:111`)이라 존재하지 않는 프레임으로도 소진됨 = 설계대로. config `limit-for-period:30 / 1m / timeout 0`(`application.yml:684-687`) |
| TC-PORTAL-068 | PASS | [실동작] | nextSrcSns 50건(2~30 + 45~65) track → **HTTP 429, 소요 60.87s**, 본문 `"SAM2 추적 처리 시간이 초과되었습니다…"`. backend 로그 `[Portal][Sam2Track] wall-clock budget exceeded — abort processed=42/50`. `TRACK_WALL_CLOCK_BUDGET=60s` (`:87`, 체크 `:163-168`) |
| TC-PORTAL-069 | PASS | [실동작]+[정적] | 동일 검증기 `validatePolygon`(`:253-272`) 실동작 확인 — 음수 좌표 `[[-5,10],…]` → **400** `"폴리곤 좌표는 0 이상이어야 합니다."`, `NaN` 리터럴은 Jackson 파싱 단계 **400**. AI 응답에도 같은 검증기 적용됨(segment `:132`, track `:182`) — `!Double.isFinite` + `<0` 차단 |
| TC-PORTAL-070 | PARTIAL | [정적] | `aiRes == null \|\| aiRes.polygon() == null` → `EXTERNAL_API_ERROR`(=502) `:121-123`, `:179-181`; `catch RuntimeException` → 502 `:225-229`. **다만 `polygon: []`(빈 리스트, non-null)** 은 이 분기를 통과해 `validatePolygon` 에서 **400 INVALID_INPUT** 이 되어 기대결과(`EXTERNAL_API_ERROR`)와 다르다. 포털 SAM2 경로에 대한 이 축의 자동테스트도 0건. → **F-ISSUE-43**(LOW) |
| TC-PORTAL-071 | PASS | [실동작] | `trackId="qa\r\nINJECTED_FAKE_LOG_LINE"` 로 track 호출 → backend 로그 한 줄로 `trackId=qaINJECTED_FAKE_LOG_LINE` (CR/LF 제거). `LogSanitizer.sanitize` `:205-206` (CWE-117) |
| TC-PORTAL-072 | FAIL | [정적] | **정책 위반 재확인.** `LabelingPagePortalRestrictions.test.tsx:108-124` 가 포털 모드에서 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 **존재를 단언**한다(=노출이 기대값으로 고정됨). FE→BE 배선도 실재: `features/label/api.ts:604-610,812-823` 가 `portalMode` 면 `/portal/frames/{id}/sam2-*` 로 분기. `AI 탐지`(YOLO)만 숨김. → **F-ISSUE-42** |
| TC-PORTAL-073 | PASS | [정적] | `LabelingPage.tsx:1200` 제출 영역이 `isWorker && data` 조건 — PORTAL 토큰은 role=PORTAL_USER 라 `submit-review-button` 미렌더. 테스트 `LabelingPagePortalRestrictions.test.tsx:86-91` 가 `queryByTestId('submit-review-button')` null 단언 |
| TC-PORTAL-074 | PASS | [정적] | `LabelingPage.tsx:474-475` `showMeta = !portalMode`, `:1472` VLM/시계열 메타 블록은 내부 채널만 렌더. 테스트 `:93-105` 가 `시계열 메타`·`VLM` 텍스트/탭 부재 단언(300ms 대기 후 재확인) |
| TC-PORTAL-075 | PASS | [실동작] | **비식별본 전용 + 원본 폴백 금지 양방향 실증.** ①srcSn=45(raw18, `SRC_FILE_PATH_NM` **NULL**, deid 존재) → **200** 실좌표 = 원본 경로 없이도 동작 = 비식별 경로를 씀. ②srcSn=445(raw902, **raw 파일 9,037B 실재 / deid NULL**) → **404** `"비식별 프레임이 존재하지 않습니다."` 이며 **ai-server 호출 0건**(로그 라인 델타로 확인) = 원본으로 폴백하지 않음. 코드 `PortalSam2Service.java:116` → `FrameImageEncoder.encodeDeidentifiedFrameForInference:148-152` → `resolveDeidentifiedFrame:155-170`(폴백 없음). 단위테스트 `AiInferenceDeidentReportGateTest.java:416-433` 이 전송 base64 를 캡처해 `deid 바이트와 동일 && 원본 바이트와 상이` 를 단언 |
| TC-PORTAL-076 | PASS | [실동작] | srcSn=429(raw900 `DE_IDENT_YN='F'`) segment → **412 PRECONDITION_FAILED** `"비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다."`, **ai-server 로그 라인 델타 0** = 파일 읽기·전송 전 차단. `FrameImageEncoder:149,206-217` → `DeidentReportGate.isUnderDeidentReport`(자기 rawSn 단일 컬럼, 캐시 없음) |
| TC-PORTAL-077 | PASS | [실동작] | ①srcSn=429 track → **412 + ai 호출 0건**. ②**프레임별 재판정 적대검증**: start=1(정상) / next=[2, 429(신고), 3] → 프레임2 처리 후 **412 로 중단**, ai-server 호출 델타 **정확히 1** = 신고 프레임 및 그 이후(3)는 전송되지 않음. 코드 `PortalSam2Service.java:170-172`(루프 내 `encodeDeidentifiedFrameForInference` 재호출) |
| TC-PORTAL-078 | PASS | [정적] | `grep -rn "encodeToBase64" backend/src` → **주석·javadoc 5건뿐, 메서드 정의·호출 0건**. `FrameImageEncoder` public 표면 = `resolveFrameImageForInference(:133)` / `encodeDeidentifiedFrameForInference(:148)` / `encodeFrame(:178)` 3종이며 모두 `LsDataSrc` 를 받아 게이트를 통과한 뒤 private `encode(Path)(:190)` 로 수렴. 게이트 없는 `resolveFrameImageWithoutGate(:94)` 는 **package-private** 이고 패키지 외부 소비자 0건(유일 호출부 `FrameBoundsResolver.java:101` 은 동일 패키지·치수 판독 전용) |

### 집계
| 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|:--:|
| 19 | 15 | 3 | 1 | 0 | 0 | 0 |

## 2. 확정 정책 재확인 결과 (지시 3축)

| 축 | 결과 |
|---|---|
| ① 포털에서 SAM2 API 가 **아직도 호출 가능한가** | **그렇다.** PORTAL 채널 토큰으로 segment·track 모두 HTTP 200 + 실좌표. 기능 토글·프로파일 가드 없음(`application.yml` 에 `portalSam2` 는 bulkhead/ratelimiter config 뿐). FE 도구바·API 클라이언트까지 종단 배선. → **UNCERTAINTIES #1 의 "정책 위반 = FAIL 유지" 판정 그대로 유지** |
| ② 전송 픽셀이 **원본이 아니라 비식별본**인가 | **그렇다(심각도 완화 근거 실증).** 원본 경로가 NULL 인 파생 프레임에서 동작하고, 원본이 실재하고 비식별본만 없는 프레임에서는 404 로 끊긴다(폴백 없음). 게이트 없는 `encodeToBase64(String)` 유출 경로는 코드에서 소멸 |
| ③ 신고 게이트가 **포털 SAM2 경로에도** 걸리는가 | **그렇다.** segment·track 모두 412 + ai-server 호출 0건, track 은 후속 프레임마다 재판정. 게이트는 `DeidentReportGate` 단일 원천이며 **자기 rawSn 행 하나만** 본다(★1 확정 정책과 정합 — 파생 경유 열람은 결함으로 재보고하지 않음) |

## 3. 근거 드리프트 (카탈로그 정합성 — 판정과 별개)

`PortalSam2Service.java` 인용이 전반적으로 **약 2~18줄 앞당겨져** 있다(파일 상단 javadoc/필드 증가분 미반영).

| TC | 카탈로그 표기 | 실제 위치 |
|---|---|---|
| 060 | `PortalSam2Service.java:108-134` | `109-137` (`segment()`) |
| 061 | `:142-190` | `145-208` (`track()`) |
| 062 | `:218-226` | `236-251` (`requireExposedFrame`+`isDatamartExposed`) |
| 063 | `:219-220` | `237-238` |
| 064 | `PortalSam2Controller.java:81-84` | `86-90` |
| 065 | `:125-128` | `127-131` |
| 066 | `:196-204` | `214-230` (bulkhead catch `219-222`) |
| 067 | `:267-275` | `285-293` |
| 068 | `:154-163` | `159-168` |
| 069 | `:236-254` | `253-272` |
| 070 | `:120-122,174-176` | `121-123, 179-181` |
| 071 | `:186-188` | `204-207` |
| 075 | `:113-116` | `114-117` (1줄) — 테스트 인용 `AiInferenceDeidentReportGateTest.java:416-434` 는 정확(실 `416-433`) |
| 076 | `:115` / `FrameImageEncoder.java:148-152` / 테스트 `387-400` | `116` / **정확** / `386-399` |
| 077 | `:150-167` / 테스트 `402-414` | `157`·`159-168`·`170-172` / `401-413` |
| 078 | `FrameImageEncoder.java:172-197`(주석 `185-189`) | `182-197`(주석 **`185-189` 정확**) |

## 4. 관찰 사항 (이슈로 승격하지 않음)

- **존재 오라클**: 비존재 프레임 404 / 존재하나 비APPROVED 403 으로 갈려 포털 사용자가 `srcSn` 열거로 프레임 존재 여부를 식별할 수 있다(CWE-209 경미). 다만 카탈로그 TC-062/063 이 이 응답을 **기대값으로 명시**하고 있어 설계 의도로 판단, 결함 처리하지 않음.
- **상단 경계 좌표 미검증**: `box:[10,10,99999,99999]` 요청이 200 으로 통과한다(ai-server 가 내부 clamp). 저장 좌표가 아니라 프롬프트이고 ★3 확정 정책(AI 축 = clamp)과 정합하므로 결함 아님.
- **rate limit 소진 지점이 인가·조회보다 앞**(`:111` → `:112`): 존재하지 않는 srcSn 폭주로도 본인 permit 이 소진된다. per-user 격리라 타 사용자 영향은 없어 설계대로로 판단.

---

# 이슈 대장

### [F-ISSUE-41] TC-PORTAL-060, TC-PORTAL-061 — 포털 SAM2 분할/추적 엔드포인트가 여전히 살아 있음(정책상 미제공 기능)
- **심각도**: HIGH *(1차 대비 완화 — 전송 픽셀이 비식별본으로 교체되고 신고 게이트가 걸려 CRITICAL 아님)*
- **기대 동작(기대효과)**: 루트 `CLAUDE.md` 포털 섹션 + ADR-013 은 포털 채널에 **"오토라벨링·SAM2·VLM·버전관리·검수 미제공"** 을 명시한다. 포털은 데이터마트 영상 열람 + 수동 라벨링(BBOX/POLYGON) + 본인 자산 업로드만 담당해야 하며, GPU 추론 자원을 외부 채널에 개방하면 ①내부 파이프라인 자원 경합 ②승인 완료(데이터마트 노출) 프레임 픽셀의 외부 프로세스 반복 전송 ③요구사항 범위 확대(감리 지적)로 이어진다.
- **현재 동작(이슈 내용)**: 두 엔드포인트가 무조건 노출된다. 기능 토글·프로파일 가드가 없다.
  - `backend/.../portal/controller/PortalSam2Controller.java:44,54-55,71-72`
    ```java
    @RequestMapping("/v1/portal/frames")
    @PostMapping("/{srcSn}/sam2-track")   @PreAuthorize("hasRole('PORTAL_USER')")
    @PostMapping("/{srcSn}/sam2-segment") @PreAuthorize("hasRole('PORTAL_USER')")
    ```
  - 실동작(2026-08-02, 포털 토큰 `channel=PORTAL, role=PORTAL_USER, sub=3001`):
    ```
    POST /api/v1/portal/frames/1/sam2-segment  {"srcSn":1,"box":[10,10,100,100]}
      → 200 {"success":true,"data":{"polygon":[[16.0,10.0], … 176점 …],"score":…}}
    POST /api/v1/portal/frames/1/sam2-track    {"srcSn":1,"trackId":"qa-track-1",…,"nextSrcSns":[2,3]}
      → 200 {"data":{"tracked":[{"srcSn":2,…},{"srcSn":3,…}]}}
    ```
    ai-server 직접 조회로 실추론 확인: `{"mock":false,"source":"model"}`.
  - `application.yml` 에는 `resilience4j.bulkhead.configs.portalSam2`(:675) · `ratelimiter.configs.portalSam2`(:684) 만 있고 **기능 on/off 키가 없다** → 운영에서 끌 수단이 없음.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -X POST http://localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","sub":"3001"}' | jq -r .data.token)
  curl -s -w '\n%{http_code}\n' -X POST http://localhost:18081/api/v1/portal/frames/1/sam2-segment \
    -H "Authorization: Bearer $TOK" -H 'Content-Type: application/json' -d '{"srcSn":1,"box":[10,10,100,100]}'
  # → 200 + 실좌표
  ```
  (srcSn=1 은 rawSn=4, `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`)
- **영향**: 요구사항·ADR 위반(범위 이탈). 보안 측면은 **완화됨** — 전송 픽셀이 비식별본이고(TC-075) 신고 구간은 412 로 차단(TC-076/077)되므로 CWE-359 잔여 위험은 "승인된 비식별 프레임이 외부 채널 트리거로 ai-server 로 반복 전송됨" 수준. 자원 측면은 포털 전용 bulkhead(4)·per-user rate limit(30/min)로 내부 `aiOnline` 경로와 격리돼 있어 잠식 위험도 제한적. 남는 실질 리스크는 **정책·감리 정합성**과 **끌 수 없는 노출 표면**.
- **수정 방향(제안)**: ⚠ 구현하지 않음.
  1. (정석) `PortalSam2Controller` + `PortalSam2Service` + FE 분기(`api.ts:604-610,812-823`, `useSam2Segment`/`useSam2Track`/`CanvasShell`/`Sam2TrackTool`/`ObjectAttributePanel`) 제거, 포털 도구바에서 `AI 분할`·`AI 추적`·`스켈레톤` 비노출(F-ISSUE-42 와 동시 처리), `LabelingPagePortalRestrictions.test.tsx:108-124` 를 **부재 단언**으로 반전.
  2. (대안) 정책을 "포털 SAM2 제공"으로 **상향 확정**한다면 `CLAUDE.md` 포털 섹션·ADR-013 을 개정하고 UNCERTAINTIES #1 을 종결 — 단 그 경우에도 운영 kill-switch(`authoring.portal.sam2.enabled` 등 `@ConditionalOnProperty`)를 함께 두어야 한다.
  - 어느 쪽이든 **정책 확정이 선행**돼야 하며, 현재 상태(문서=미제공 / 코드=제공)의 방치가 가장 나쁘다.

### [F-ISSUE-42] TC-PORTAL-072 — FE 포털 라벨링 도구바가 SAM2(AI 분할/추적)·스켈레톤을 노출하고, 테스트가 그 노출을 기대값으로 고정
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 모드 라벨링 화면은 수동 도형(BBOX/POLYGON)만 제공해야 한다(ADR-013). BE 를 닫아도 FE 가 버튼을 노출하면 사용자에게 미제공 기능을 광고하게 되고, 반대로 FE 만 닫으면 API 는 열린 채로 남는다.
- **현재 동작(이슈 내용)**: 포털 모드에서 SAM2 버튼이 노출되며, **회귀 테스트가 이 노출을 단언**한다.
  - `frontend/src/features/portal/__tests__/LabelingPagePortalRestrictions.test.tsx:108-124`
    ```tsx
    // Phase 9 (ADR-013 override) — 포털 캔버스 좌측 도구바에 SAM2 분할/추적·키포인트 노출.
    it('포털_라벨링_도구바_SAM2_분할_추적_키포인트_노출_Phase9', async () => {
      expect(screen.getByRole('button', { name: 'AI 분할' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'AI 추적' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '스켈레톤' })).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'AI 탐지' })).toBeNull();  // YOLO만 숨김
    });
    ```
  - API 배선: `frontend/src/features/label/api.ts:604-610`(track), `:812-823`(segment) 가 `portalMode` 시 `/portal/frames/{id}/sam2-*` 로 분기. 소비처 `canvas/CanvasShell.tsx:40-41`, `canvas/tools/Sam2TrackTool.tsx:43-44`, `hooks/useSam2Segment.ts:40`, `hooks/useSam2Track.ts:34`, `components/ObjectAttributePanel.tsx:89`, `pages/label/LabelingPage.tsx:1511-1516`.
  - 대조군(정상): 같은 파일 `:86-105` 는 검수제출 버튼·VLM/시계열 메타 **부재**를 단언한다(TC-073/074 PASS) — 즉 다른 미제공 기능은 제대로 닫혀 있고 SAM2 만 예외.
- **재현/확인 경로**: 포털 토큰(`channel=PORTAL`)으로 `/label/{srcSn}` 진입 → 좌측 도구바에 `AI 분할`·`AI 추적`·`스켈레톤` 버튼 표시. 정적으로는 위 테스트 파일·`api.ts` 분기 확인.
- **영향**: 기능 범위(ADR-013) 위반의 사용자 접점. 테스트가 위반 상태를 고정하고 있어, F-ISSUE-41 을 BE 에서 닫으면 **이 FE 테스트가 RED 로 뒤집히는** 구조적 결합이 있다(수정 시 함께 반전 필요).
- **수정 방향(제안)**: ⚠ 구현하지 않음. F-ISSUE-41 의 정책 확정 결과에 종속. "미제공"으로 확정되면 도구바 렌더 조건에 `!portalMode` 를 추가(현재 `showMeta = !portalMode` 와 동일 패턴, `LabelingPage.tsx:474-475`)하고 위 테스트를 `queryByRole(...)` **null 단언**으로 반전한다. `api.ts` 의 포털 분기와 `PortalSam2*` 도 함께 제거.

### [F-ISSUE-43] TC-PORTAL-070 — ai-server 가 `polygon: []`(빈 배열)을 주면 502 가 아니라 400 이 나가고, 이 축의 테스트가 0건
- **심각도**: LOW
- **기대 동작(기대효과)**: 외부 추론 응답이 **비었거나 실패**하면 "외부 API 오류"(`EXTERNAL_API_ERROR` = 502)로 종결해야 한다. 502 는 "외부 의존 실패, 재시도 가능"을 뜻하고 400 은 "클라이언트 입력이 잘못됨"을 뜻하므로, 외부 원인을 400 으로 내보내면 FE·운영이 원인을 사용자 입력으로 오귀속하고 모니터링(외부 API 실패율)에도 잡히지 않는다.
- **현재 동작(이슈 내용)**: null 만 502 로 분기하고 **빈 배열은 좌표 검증으로 흘러 400** 이 된다.
  - `backend/.../portal/service/PortalSam2Service.java:121-123`
    ```java
    if (aiRes == null || aiRes.polygon() == null) {
        throw new CustomException(ErrorCode.EXTERNAL_API_ERROR, "SAM2 segment 응답이 비어있습니다.");
    }
    ```
  - 이어서 `:132` `validatePolygon(aiRes.polygon())` → `:255-257`
    ```java
    if (polygon == null || polygon.isEmpty()) {
        throw new CustomException(ErrorCode.INVALID_INPUT, "폴리곤 좌표가 비어있습니다.");  // 400
    }
    ```
  - track 도 동일 구조(`:179-181` null 체크 → `:182` 검증).
  - 테스트: `PortalSam2ServiceTest` 에 ai 실패/빈응답·좌표 이상치 케이스 **0건**(`grep "EXTERNAL_API_ERROR\|isFinite\|음수" backend/src/test/.../portal/` 무결과).
- **재현/확인 경로**: 현 환경에서는 **실동작 재현 불가** — ai-server 는 contour < 3점이거나 변환 실패 시 빈 배열이 아니라 **mock 폴백**(`mock=true, source="mock"`)을 반환하므로(`ai-server/app/routers/sam2.py:157-162, 184-201, 305-313`) `polygon: []` + `source: "model"` 조합이 실제로 나오지 않는다. 재현하려면 스텁으로 `new Sam2Response(List.of(), 0.9)` 를 주입해야 한다(단위테스트 영역).
- **영향**: 기능·보안 영향 없음(양쪽 다 fail-closed 거부이고, 좌표가 사용자에게 적용되지 않는다). **오분류·관측성** 문제이며 계약 방어의 완결성 결함. 향후 ai-server 가 빈 배열을 정상 응답으로 내보내도록 바뀌면(계약 드리프트) 그 시점에 드러난다.
- **수정 방향(제안)**: ⚠ 구현하지 않음. `:121-123`·`:179-181` 의 조건을 `polygon() == null || polygon().isEmpty()` 로 확장해 빈 응답을 502 로 일원화하고(내부 경로 `Sam2SegmentService`·`Sam2TrackService` 와 조건을 맞출 것), `PortalSam2ServiceTest` 에 ①ai null/빈 배열 → 502 ②ai 음수·NaN 좌표 → 400 두 케이스를 추가한다.
# F 클러스터 — part4 검증 결과 (F-5 포털 이미지 업로드 · F-7 포털 업로드 라벨 CRUD)

- **대상**: `docs/test-cases/F-portal.md` §F-5 (TC-PORTALUP-001~018, 18건) + §F-7 (TC-PORTALUP-040~058, 19건) = **37건**
- **검증일**: 2026-08-02 / 2차
- **환경**: backend `http://localhost:18081/api` (docker `klid-backend`, profile=local) · DB `docker exec klid-postgres psql -U klid_user -d klid_system` (스키마 `public`)
- **인증**: `JWT_SECRET`(컨테이너 실효값) 으로 HS256 토큰 자체 발급 — `{sub, iss:klid-auth, channel:PORTAL, exp}` → `Role.PORTAL_USER` 고정 경로(`JwtAuthenticationFilter:94-99`). 테스트 사용자 `portal-qa-1` / `portal-qa-2` / `portal-other` / `portal-rl-1`
- **판정 요약**: PASS **32** · FAIL **1** · PARTIAL **4** · BLOCKED 0 · N/A 0 · 확인필요 0
- **신규 이슈**: F-ISSUE-61 ~ F-ISSUE-64 (4건)

> ⚠ 본 검증은 읽기 전용 원칙을 지켰다. 코드·설정·테스트 파일은 **일절 수정하지 않았다.** 실동작 확인을 위해 **DB 데이터 조작**(상태 플립·파일경로 조작·임시 CHECK 제약 추가 후 즉시 DROP)과 **HTTP 요청**만 수행했다.

---

## 1. F-5. 포털 이미지 업로드 (18건)

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-PORTALUP-001 | PASS | [실동작] | `POST /v1/portal/uploads/images` (ok.jpg + ok.png) → **201**, 응답 `uldSttsCd=READY`·`frmeCnt=1`·`frmeSn` 각 1건. DB `ls_portal_uld` 2행 + `ls_portal_uld_frme` 2행, `file_path_nm=/app/storage/raw/portal/images/{UUID}.jpeg\|.png` |
| TC-PORTALUP-002 | PASS | [실동작] | 51장 → **400** `"요청당 최대 50 개까지 업로드할 수 있습니다."` (`PortalUploadService.java:90-93`) |
| TC-PORTALUP-003 | PASS | [실동작] | 정확히 50장 → **201**, `data` 배열 50건. DB 행수 4 → 54 (경계 통과) |
| TC-PORTALUP-004 | **PARTIAL** | [실동작] | 빈 **파일**(0바이트 파트) → 400 정상. 그러나 `files` **파트 자체 부재** → **500 INTERNAL_ERROR** (`MissingServletRequestPartException` 미매핑, 서버 로그 `unhandled exception` + 스택트레이스). → **F-ISSUE-61** |
| TC-PORTALUP-005 | PASS | [실동작] | 20,971,521B(=20MB+1) JPEG → **400** `"이미지 크기가 허용 한도를 초과했습니다."` (서비스 검증 `PortalUploadService.java:300-303`, `PortalUploadProperties.java:40` `maxImageSizeBytes=20971520`) |
| TC-PORTALUP-006 | PASS | [실동작] | 정확히 20,971,520B → **201** (`fileSz=20971520`). 경계 포함(`>` 비교) 확인 |
| TC-PORTALUP-007 | PASS | [실동작]+[정적] | local 프로파일에서 22MB → **400**(파싱 통과 후 서비스 20MB 검증) — 카탈로그의 프로파일 구분 서술과 일치. prd 21MB 상한은 `application-prd.yml:36-39` + 회귀가드 `ConfigProfileDriftGuardTest.java:42-70`(`prdMultipartLimitsStayTight`) 로 고정. `MaxUploadSizeExceededException → PAYLOAD_TOO_LARGE(413)` 매핑 존재(`GlobalExceptionHandler.java:234-238`, `ErrorCode.java:24`) |
| TC-PORTALUP-008 | PASS | [정적] | 공통 `application.yml:28-29` = 500MB/1200MB, prd override `application-prd.yml:38-39` = 21MB/1100MB. 동일 가드 테스트가 비운영 3종(local/dev/stg)이 공통 상속임도 단언 |
| TC-PORTALUP-009 | PASS | [실동작] | `.gif` → **400** `"허용되지 않는 확장자입니다. 허용: [jpg, jpeg, png]"` (`PortalUploadService.java:306-309`). 대문자 `.JPG` 는 소문자 정규화되어 201(정상) |
| TC-PORTALUP-010 | PASS | [실동작] | 텍스트 내용 + `.jpg` → **400** `"지원하지 않는 이미지 형식입니다(JPEG/PNG 만 허용)."` (`:310-315`). `GIF89a` 내용 + `.jpg` 도 동일 거부 |
| TC-PORTALUP-011 | PASS | [실동작] | JPEG 시그니처 + `.png` → **400** `"확장자와 실제 이미지 형식이 일치하지 않습니다."` (`:316-320`) |
| TC-PORTALUP-012 | PASS | [실동작] | EOI(FF D9) 없는 JPEG → **400** `"손상되었거나 완전하지 않은 이미지 파일입니다."` (`:321-327`). ⚠ 한계: `FF D8 FF D9` 4바이트 파일은 통과(201) — SOI+EOI 저비용 검사의 설계상 한계이며 전체 디코드를 하지 않는 정책의 귀결. 서빙이 `image/jpeg + nosniff` 라 스크립트 실행 벡터는 아님(FFD8FF…`<script>`…FFD9 폴리글롯 업로드 후 서빙 헤더 실측: `Content-Type: image/jpeg`·`X-Content-Type-Options: nosniff`·`Content-Disposition: inline`) |
| TC-PORTALUP-013 | PASS | [실동작] | 49장 정상 + 1장 위조 → **400**, `data[0]="[50] fake.jpg: 지원하지 않는 이미지 형식입니다"`. DB 행수 불변(4→4), 디스크 파일 증가 0. 검증(`:95-109`)이 write 루프(`:115-137`) **이전**에 전량 수행됨 |
| TC-PORTALUP-014 | **FAIL** | [실동작] | 프레임 INSERT 를 커밋 시점에 실패시키자(임시 `CHECK (frme_no <> 0) NOT VALID`) → **500 + DB 행 0건(정상 롤백)** 인데 **디스크 파일은 68→69 로 잔존**. `LsPortalUldFrme` 는 `GenerationType.SEQUENCE` 라 `save()` 가 INSERT 를 지연시켜 실패가 **커밋(flush) 시점** = `uploadImages` catch 블록 **바깥**에서 발생 → `rollbackFiles()` 미실행. → **F-ISSUE-62** |
| TC-PORTALUP-015 | **PARTIAL** | [실동작]+[정적] | 선행 파일 롤백은 성립(단위테스트 `다건_배치중_후행_write실패시_선행파일도_롤백`). 그러나 **실패한 그 파일의 부분 기록분**은 보상되지 않는다 — `writtenThisRequest.add(dst)` 가 `writeToDisk()` **뒤**(`:122-123`)라 copy 도중 IOException 시 dst 가 목록에 없다. JDK 실측(`Files.copy(InputStream,Path)` 중간 IOException → target 존재, size=100) 로 부분 파일 잔존 확인. → **F-ISSUE-63** |
| TC-PORTALUP-016 | PASS | [실동작] | 파일명 `../../../../etc/evil.jpg` 업로드 → 201, DB `orgnl_file_nm` 은 원문 보존(표시용), `file_path_nm=/app/storage/raw/portal/images/3d6e5df1-….jpeg`. 디스크 실측: `portal/images/` 하위에 UUID 파일만 존재, base 밖 생성 0 |
| TC-PORTALUP-017 | PASS | [실동작] | 304자 파일명 업로드 → 응답 `orgnlFileNm` 길이 **255**, DB `length(orgnl_file_nm)=255` (`:454-459`) |
| TC-PORTALUP-018 | PASS | [실동작] | 신규 사용자로 62회 연속 업로드 → 1~60회 400(검증 실패, permit 소비됨) · **61·62회 429**. `portalUpload` config `limit-for-period=60 / 1m / timeout=0`(`application.yml:692-695`), permit 획득이 서비스 진입 **전**(`PortalUploadController.java:83,188-196`). ⚠ 관찰: `RateLimiterRegistry` 에 per-user 리미터가 무기한 누적(TTL·eviction 없음) — 사용자 수 증가 시 메모리 누적 소지(이번 케이스 기대결과 밖이라 이슈 미발행) |

## 2. F-7. 포털 업로드 라벨 CRUD (19건)

| ID | 판정 | 근거 확인 | 실측 요약 |
|----|:--:|------|------|
| TC-PORTALUP-040 | PASS | [실동작] | `PUT /frames/1/labels` [BBOX,POLYGON] → 200 2건 저장 → `[]` PUT → 200 `data=[]` → `GET` 0건. 동일 본문 재PUT 멱등(건수 동일, PK만 재발급). 본문 미첨부(Content-Length:0)도 전체 삭제로 동작(`@RequestBody(required=false)` → `List.of()`) |
| TC-PORTALUP-041 | PASS | [실동작] | 위조 1건 포함 PUT → 400 후 `GET labels` 가 **기존 2건 그대로** 반환. 검증(`:99-107`)이 `deleteAllBy…`(`:122`) 이전에 완료 |
| TC-PORTALUP-042 | PASS | [실동작] | `lblTypeCd=SEGMENT` → **400** `"허용되지 않는 lblTypeCd 입니다. 허용: BBOX, POLYGON"` (`:265-270`, fail-closed) |
| TC-PORTALUP-043 | PASS | [실동작] | 501건 → **400**(`replaceLabels.labels:` = 컨트롤러 `@Size` 1차, `PortalUploadLabelController.java:63`) / 500건 → **200**(경계 통과). 서비스 이중 강제(`PortalUploadLabelService.java:100-103`) 존재 |
| TC-PORTALUP-044 | PASS | [실동작] | BBOX 3점 → **400** `"BBOX 는 정확히 2 점이어야 합니다."` (`:281-284`) |
| TC-PORTALUP-045 | PASS | [실동작] | POLYGON 2점 → 400 / 201점 → 400 / **3점·200점 → 200**(양 경계 포함, `:285-290`) |
| TC-PORTALUP-046 | PASS | [실동작] | `"Infinity"` 문자열 좌표 → **400** `"좌표는 유한한 숫자여야 합니다."`(`:300-303`), 비표준 JSON 리터럴 `NaN` → **400**(Jackson 파싱 거부) |
| TC-PORTALUP-047 | PASS | [실동작] | label 81자 → **400** (`@Size(80)` 컨트롤러 1차 + 서비스 `:271-275` 이중) |
| TC-PORTALUP-048 | PASS | [실동작] | 자산 상태 `FAILED` → **409** `"라벨링 가능한(READY) 자산이 아닙니다. 현재 상태: FAILED"`, `PROCESSING` → **409** (`:116-119`). 프레임 락 획득 **후** 판정이라 상태 경합 창 없음 |
| TC-PORTALUP-049 | PASS | [실동작] | 동일 프레임에 3종 페이로드(3·5·7건)를 **18병렬** PUT → 전건 200, 최종 DB 상태 `k7 × 7행` **단일 요청 집합**(혼합·중복·유실 0). `@Lock(PESSIMISTIC_WRITE)` `findByUldFrmeSnAndOwnerForUpdate`(`LsPortalUldFrmeRepository.java:42-47`) 로 직렬화 |
| TC-PORTALUP-050 | PASS | [실동작] | 타 사용자 토큰으로 `PUT /frames/1/labels` → **403** `"본인 자산이 아니거나 존재하지 않습니다."`(부재와 동일 메시지 — 자원 열거 차단) |
| TC-PORTALUP-051 | **PARTIAL** | [실동작] | 정규 경로 2.8MB/12MB 본문 → **413** 즉시(0.0017s, 파싱 전). 그러나 경로를 퍼센트 인코딩(`/frames/1/%6Cabels`)하면 필터가 스킵되고 **핸들러에 도달**(200) → 12MB 본문이 전량 파싱된 뒤 400(0.21s). pre-parse 상한 **우회 가능**. → **F-ISSUE-64** |
| TC-PORTALUP-052 | **PARTIAL** | [실동작] | 정규 경로 chunked → **411** 정상. 인코딩 경로(`%6Cabels`) + chunked → **200**(411 미적용). 동일 근본원인. → **F-ISSUE-64** |
| TC-PORTALUP-053 | PASS | [실동작] | 다른 PUT 경로(`PUT /v1/videos/1/environment-meta`) 에 chunked·2.8MB 를 보내도 411/413 이 아니라 **403**(필터 스킵) · 같은 경로에 **POST** → 405(메서드 필터) · `/labels/x` 추가 세그먼트 → 404(정규식 미매치). 경로·메서드 스코핑은 설계대로 동작 |
| TC-PORTALUP-054 | PASS | [실동작] | 타 사용자 프레임 `GET labels` → **403** / 부재 프레임(999999) → **403**(부재·타인 응답 동일) |
| TC-PORTALUP-055 | PASS | [실동작]+[정적] | `GET /1/export` → 200, `Content-Disposition: attachment; filename="portal-upload-1-labels.json"`(서버 고정명) + `nosniff` + `application/json`. 본문 키 `uldSn·uldTypeCd·orgnlFileNm·fileSz·mimeTypeNm·uldSttsCd·frmeCnt·regDt·frames`, 프레임별 라벨 중첩. N+1 회피 = 업로드 단위 일괄 조회 후 그룹핑(`:163-169`) |
| TC-PORTALUP-056 | PASS | [실동작] | `orgnl_file_nm` 에 CR/LF·`"`·`\`·한글을 주입한 뒤 `GET /1/file` → 응답 헤더 `Content-Disposition: attachment; filename="download.jpg"; filename*=UTF-8''evil%20Set-Cookie%3A%20x%3D1%3Bquote%EA%B0%80.jpg`. **개행 제거 → 헤더 분할 없음**, 주입 시도한 `Set-Cookie` 헤더 미생성(CWE-113 차단) |
| TC-PORTALUP-057 | PASS | [실동작] | `file_path_nm=null` → **404** / base 내 부재 파일 → **404** / `/etc/passwd`(base 밖) → **403** `"허용되지 않은 경로입니다."` (`:217-228`, `resolveSafe` `:398-407`) |
| TC-PORTALUP-058 | PASS | [실동작] | 타 사용자 토큰 `GET /1/export` → **403**, `GET /1/file` → **403** |

---

## 3. 근거 드리프트 (카탈로그 file:line ↔ 실제)

> F-7 헤더의 *"PortalUploadLabelService.java·PortalUploadLabelController.java 모두 2026-07-25 이후 무변경 — 라인 재확인만"* 주석과 달리 **서비스 라인이 전반적으로 +1 ~ +15 밀려 있다.** 컨트롤러(`:63`)와 Properties(`:40`)만 정확.

| TC | 카탈로그 | 실제 |
|----|------|------|
| 001 | PortalUploadService.java:82-151 | 83-152 |
| 002·003 | :89-92 | 90-93 |
| 004 | :86-88 | 87-89 |
| 005·006 | :274-277 / :274 | 300-303 / 300 |
| 009 | :278-283 | 306-309 |
| 010 | :284-289 | 310-315 |
| 011 | :290-294 | 316-320 |
| 012 | :295-301 | 321-327 |
| 013 | :94-108 | 95-109 |
| 014 | :137-146 | 133-134(frme save) · 138-147(catch) |
| 015 | :339-359 | 365-372(writeToDisk) · 374-385(rollbackFiles) |
| 017 | :428-433 | 454-459 |
| 018 | PortalUploadController.java:157-165 | 188-196 |
| 040 | PortalUploadLabelService.java:92-132 | 93-133 |
| 041 | :98-106 | 99-107 |
| 042 | :250-255 | 265-270 |
| 044 | :266-269 | 281-284 |
| 045 | :270-275 | 285-290 |
| 046 | :285-288 | 300-303 |
| 047 | :257-260 | 271-275 |
| 048 | :115-118 | 116-119 |
| 049·050 | :108-109 / :109-110 | 110-111 |
| 051 | PortalLabelBodySizeFilter.java:73-78 | 70-75 |
| 052 | :66-72 | 62-69 |
| 053 | :54-60 | 52-57 |
| 054 | :137-143 | 138-144 |
| 055 | :152-201 | 153-202 |
| 056 | :321-345 | 336-344(attachmentDisposition) · 347-360(sanitizeFileName) |
| 057 | :216-227 | 217-228 |
| 058 | :154-156 | 155-157 |

## 4. 기존 자동테스트 커버 대조 (`_raw/test-baseline.md` 2026-08-01 = BE 4,755 tests / 실패 0)

| 영역 | 파일 | 커버 |
|------|------|------|
| F-5 | `PortalUploadServiceTest`(23) · `PortalUploadControllerTest`(21) · `PortalUploadControllerRateLimitTest`(1) | 002·004(서비스 레벨)·005·009~013·014(모킹)·015(부분)·016·018 커버. **003(정확히 50장)·006(정확히 20MB) 경계 테스트 없음**, **004 의 HTTP 파트 부재 경로 테스트 없음**(F-ISSUE-61 이 그래서 자동테스트에 안 잡혔다) |
| F-7 | `PortalUploadLabelServiceTest`(32) · `PortalUploadLabelControllerTest`(9) · `PortalUploadLabelConcurrencyIT`(1) · `PortalLabelBodySizeFilterTest`(6) | 040~050·054~058 폭넓게 커버. **051/052 는 정규 경로만 테스트** — 퍼센트 인코딩 우회 케이스 부재(F-ISSUE-64 미검출 원인) |

---

## 5. 이슈

### [F-ISSUE-61] TC-PORTALUP-004 — `files` 파트 부재 업로드가 400 이 아니라 500(스택트레이스 로깅)
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 업로드할 이미지가 없는 요청은 **클라이언트 입력 오류(400 INVALID_INPUT)** 로 거부돼야 한다. 5xx 로 나가면 ①클라이언트가 재시도 가능한 서버 장애로 오인하고 ②`GlobalExceptionHandler` 최종 `Exception` 핸들러가 **ERROR + 스택트레이스**를 남겨 내부 프레임워크/필터 체인이 로그에 노출된다(CWE-209). 모니터링상 5xx 알람도 오염된다.
- **현재 동작(이슈 내용)**: `MissingServletRequestPartException` 이 `GlobalExceptionHandler` 에 매핑돼 있지 않다. 형제 클래스인 `MissingServletRequestParameterException` 만 400 으로 매핑돼 있어 누락이 눈에 띄지 않는다.
  - `backend/src/main/java/kr/co/cudo/authoring/common/exception/GlobalExceptionHandler.java:76-77`
    ```java
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException e) {
    ```
  - 실측 서버 로그: `ERROR ... GlobalExceptionHandler - [Exception] unhandled exception` + `org.springframework.web.multipart.support.MissingServletRequestPartException: Required part 'files' is not present.`
  - 서비스의 빈 목록 가드(`PortalUploadService.java:87-89`)는 **도달조차 하지 않는다**(컨트롤러 바인딩 단계에서 예외).
- **재현/확인 경로**:
  ```bash
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -X POST http://localhost:18081/api/v1/portal/uploads/images
  # → {"success":false,...,"errorCode":"INTERNAL_ERROR"} / 500
  # 참고: -F "files=" (빈 값) 도 동일하게 500
  ```
- **영향**: CWE-209(내부 정보 노출 — 스택트레이스 ERROR 로깅) + 오류 분류 오염(4xx→5xx). 인증 필요 경로라 외부 무인증 공격면은 아니나, PORTAL_USER 누구나 5xx 알람을 유발할 수 있다.
- **수정 방향(제안)**: `GlobalExceptionHandler` 에 `@ExceptionHandler(MissingServletRequestPartException.class)`(또는 상위 `ServletRequestBindingException`) 를 추가해 `ErrorCode.INVALID_INPUT`(400) 으로 매핑. 컨트롤러 테스트에 "files 파트 없는 업로드 → 400" 케이스 추가.

### [F-ISSUE-62] TC-PORTALUP-014 — 프레임 INSERT 가 커밋 시점에 실패하면 업로드 파일이 고아로 영구 잔존
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `uploadImages` 는 all-or-nothing 이다(클래스 Javadoc `#4 고아 파일 방지: write 후 DB INSERT 실패 시 이번 요청에 기록한 파일을 즉시 보상 삭제`). DB 가 롤백되면 디스크에도 아무것도 남지 않아야 한다. 남으면 ①소유 레코드 없는 **사용자 업로드 원본**이 스토리지에 무기한 잔존해 파기 요구(개인정보 보존기간)를 만족할 수 없고 ②어떤 스윕도 회수하지 않아 누적된다.
- **현재 동작(이슈 내용)**: `LsPortalUldFrme` 의 PK 전략이 `SEQUENCE` 라 `frmeRepository.save()` 는 INSERT 를 **큐에만 넣고** 실제 실행은 트랜잭션 커밋 시 flush 에서 일어난다. 그 실패는 `uploadImages` 의 try/catch **밖**이라 `rollbackFiles(writtenThisRequest)` 가 실행되지 않는다.
  - `PortalUploadService.java:133-147`
    ```java
    LsPortalUldFrme frme = frmeRepository.save(              // ← SEQUENCE: 실제 INSERT 는 커밋 시 flush
            LsPortalUldFrme.create(savedUld.getUldSn(), IMAGE_FRAME_NO, dst.toString()));
    ...
    } catch (Exception e) {
        rollbackFiles(writtenThisRequest);                    // ← 커밋 단계 실패에는 도달하지 않음
    ```
  - `LsPortalUldFrme.java:36-37` → `@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "lsPortalUldFrmeSeq")` (반면 `LsPortalUld.java:44-45` 는 IDENTITY 라 즉시 INSERT)
  - 단위테스트 `PortalUploadServiceTest#orphanFileRemovedOnDbFailure` 는 `frmeRepository.save()` 를 **모킹해 즉시 throw** 시키므로 이 지연 특성을 재현하지 못한다.
  - 회수 수단 부재: `PortalUploadSweepJob` 은 만료 TUS 임시파일과 `frames/{uldSn}/` 디렉터리만 정리하며 `portal/images/` 고아는 대상이 아니다(`PortalUploadSweepJob.java:67-113`).
- **재현/확인 경로** (실측 완료 — 임시 제약은 즉시 DROP 했음):
  ```sql
  ALTER TABLE ls_portal_uld_frme ADD CONSTRAINT qa_tmp_chk CHECK (frme_no <> 0) NOT VALID;
  ```
  ```bash
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 68
  curl -s -w "\n%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" \
       -F "files=@ok.jpg;filename=orphanprobe2.jpg" \
       http://localhost:18081/api/v1/portal/uploads/images                      # 500 INTERNAL_ERROR
  docker exec klid-backend sh -c "ls /app/storage/raw/portal/images | wc -l"   # 69  ← 고아 1건
  ```
  ```sql
  ALTER TABLE ls_portal_uld_frme DROP CONSTRAINT qa_tmp_chk;
  SELECT count(*) FROM ls_portal_uld WHERE orgnl_file_nm='orphanprobe2.jpg';   -- 0 (DB 는 정상 롤백)
  ```
  잔존 파일: `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`
- **영향**: CWE-459(Incomplete Cleanup) + 저장소 고갈(OWASP API4). 고아 파일은 **사용자 업로드 원본 이미지**이므로 소유·삭제 경로가 사라진 개인 자산이 남는다(CWE-359 인접).
- **수정 방향(제안)**: ①업로드 트랜잭션에 `TransactionSynchronization`(`afterCompletion(STATUS_ROLLED_BACK)`) 을 등록해 커밋 실패까지 포함한 보상 삭제를 걸거나 ②프레임 저장 직후 `saveAndFlush`(또는 `entityManager.flush()`)로 INSERT 실패를 메서드 내부로 끌어오거나 ③주기 스윕에 `portal/images/` ↔ `LS_PORTAL_ULD.FILE_PATH_NM` 대조 고아 회수를 추가. 아울러 단위테스트를 모킹 대신 실 DB(Testcontainers) 제약 위반으로 바꿔 지연 flush 를 재현할 것.

### [F-ISSUE-63] TC-PORTALUP-015 — write 도중 IOException 시 **실패한 그 파일의 부분 기록분**이 보상되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: 디스크 고갈 등으로 저장이 실패하면 이번 요청이 만든 파일은 **하나도 남지 않아야** 한다(클래스 Javadoc `#5 디스크 고갈: 저장 도중 IOException 시 이번 요청 파일 전부 롤백 삭제`).
- **현재 동작(이슈 내용)**: 롤백 목록 등록이 write **성공 이후**라, copy 중간에 끊긴 파일은 목록에 없어 삭제되지 않는다.
  - `PortalUploadService.java:120-123`
    ```java
    Path dst = resolveSafe(baseDir, imagesDir.resolve(storedName));
    writeToDisk(v.file(), dst);          // ← 여기서 IOException 이면 dst 는 부분 기록된 채
    writtenThisRequest.add(dst);         // ← 이 줄에 도달하지 못해 롤백 목록에서 누락
    ```
  - JDK 동작 실측(`Files.copy(InputStream, Path)` 는 중간 실패 시 target 을 지우지 않음):
    ```
    copy failed: disk full
    target exists=true size=100
    ```
  - 기존 단위테스트 `laterWriteFailureRollsBackEarlierFiles` 의 테스트 더블은 `getInputStream()` 에서 던지므로 **target 파일이 아예 생성되지 않아** 이 경로를 검증하지 못한다.
  - F-ISSUE-62 와 마찬가지로 `portal/images/` 고아를 회수하는 스윕이 없다.
- **재현/확인 경로**: 저장 볼륨을 소진시키거나(예: 작은 tmpfs 를 `/app/storage/raw/portal/images` 에 마운트) `Files.copy` 중간 IOException 을 유발한 뒤 `ls /app/storage/raw/portal/images` 에 부분 파일 잔존을 확인. 코드 순서만으로도 결정적으로 성립한다.
- **영향**: CWE-459. 디스크가 이미 고갈된 상황에서 잔여물이 남아 회복을 더 어렵게 만든다(보안 영향은 낮음).
- **수정 방향(제안)**: `writtenThisRequest.add(dst)` 를 `writeToDisk` **호출 전**으로 옮기거나(rollback 이 `deleteIfExists` 라 미생성 파일에도 안전), `writeToDisk` 내부 catch 에서 `Files.deleteIfExists(dst)` 를 수행한 뒤 예외를 던진다.

### [F-ISSUE-64] TC-PORTALUP-051 / TC-PORTALUP-052 — 라벨 PUT 본문 상한 필터가 **URL 퍼센트 인코딩 경로로 우회**됨 (pre-parse DoS 재개통)
- **심각도**: HIGH
- **기대 동작(기대효과)**: `PortalLabelBodySizeFilter` 는 이름 그대로 **Jackson 역직렬화 이전**에 본문 크기를 차단하기 위해 존재한다(클래스 Javadoc: *"대용량 페이로드로 파서 메모리를 소진시키는 pre-parse DoS(CWE-770 / OWASP API4)를 막지 못한다. 본 필터가 파싱 전에 상한을 적용한다"*). 라벨 PUT 핸들러에 **도달하는 모든 요청**이 이 상한을 통과해야 의미가 있다.
- **현재 동작(이슈 내용)**: 필터는 **디코딩되지 않은 원시 URI**(`getRequestURI()`)에 정규식을 적용하는데, Spring MVC 는 **디코딩된 경로**로 핸들러를 매핑한다. 두 해석이 어긋나 `labels` 의 한 글자만 퍼센트 인코딩해도 필터는 스킵되고 핸들러는 정상 실행된다(CWE-436 Interpretation Conflict).
  - `backend/src/main/java/kr/co/cudo/authoring/portal/config/PortalLabelBodySizeFilter.java:40-41, 56, 91-101`
    ```java
    private static final Pattern LABEL_PUT_PATH =
            Pattern.compile("^/v1/portal/uploads/frames/[^/]+/labels$");
    ...
    return !LABEL_PUT_PATH.matcher(pathWithinApp(request)).matches();
    ...
    private static String pathWithinApp(HttpServletRequest request) {
        String uri = request.getRequestURI();      // ← 퍼센트 인코딩이 살아있는 원시 URI
    ```
- **재현/확인 경로** (실측):
  ```bash
  # 12,960,000 바이트 JSON 준비
  # (1) 정규 경로 — 파싱 전 차단
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/labels
  # → code=413 time=0.0017     (본문을 읽지도 않음)

  # (2) 'l' 만 %6C 로 인코딩 — 필터 스킵, 12MB 전량 파싱 후 거부
  curl -s -o /dev/null -w "code=%{http_code} time=%{time_total}\n" -H "Authorization: Bearer $PORTAL_JWT" \
    -H "Content-Type: application/json" -X PUT --data-binary @huge.json \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → code=400 time=0.21       (413 이 아님 = 상한 미적용)

  # (3) chunked 가드(411)도 동일하게 우회 — 인코딩 경로 + Transfer-Encoding: chunked → 200 (라벨이 실제로 교체됨)
  curl -s -w " HTTP:%{http_code}\n" -H "Authorization: Bearer $PORTAL_JWT" -H "Content-Type: application/json" \
    -H "Transfer-Encoding: chunked" -X PUT --data '[]' \
    http://localhost:18081/api/v1/portal/uploads/frames/1/%6Cabels
  # → {"success":true,"data":[]} HTTP:200
  ```
  인증/인가는 우회되지 않는다(무토큰 401 · 타 사용자 403 확인). 우회되는 것은 **본문 크기·chunked 가드뿐**이다.
- **영향**: CWE-436 / CWE-770 (OWASP API4:2023 Unrestricted Resource Consumption). 인증된 PORTAL_USER 가 임의 크기 JSON 을 Jackson 에 밀어 넣어 힙·CPU 를 소모시킬 수 있다(멀티 커넥션 병행 시 증폭). chunked 차단 우회는 Content-Length 조차 선언하지 않은 무제한 스트림을 허용한다. A 클러스터에서 확인된 "HMAC 필터 URL 인코딩 우회(CWE-436)" 와 **동일 계열의 반복 결함**이다.
- **수정 방향(제안)**: 필터의 경로 판정을 원시 URI 정규식에서 **정규화·디코딩된 경로 매칭**으로 교체한다 — Spring 이 이미 사용하는 `ServletRequestPathUtils.parseAndCache(request)` + `PathPattern`(또는 최소한 `UriUtils.decode(uri, UTF_8)` 후 매칭 + 중복 슬래시 정규화). 더 견고한 대안은 경로 판정을 없애고 **모든 요청에 대해** `HandlerInterceptor`(핸들러 확정 후) 또는 컨트롤러 진입 시점의 `ContentCachingRequestWrapper` 상한을 쓰는 것이다. 회귀 가드로 `PortalLabelBodySizeFilterTest` 에 퍼센트 인코딩 경로(`%6Cabels`, `%2Fabels` 등) 케이스를 추가할 것.

---

## 6. 검증 중 생성/변경한 테스트 데이터 (참고)

- `ls_portal_uld` 신규 행: `portal-qa-1`(uld_sn 1~4·5~54·76), `portal-qa-2`(74·75·78 등) — 이미지 자산
- 데이터 조작(원복하지 않은 항목): `uld_sn=1` 의 `orgnl_file_nm`(CRLF 주입 문자열, TC-056 근거) · `uld_sn=3` 의 `file_path_nm='/etc/passwd'`(TC-057 근거)
- 임시 DB 제약 `qa_tmp_chk` 는 **추가 직후 DROP 완료**(현재 미존재)
- 고아 파일 1건 존치(F-ISSUE-62 물증): `/app/storage/raw/portal/images/87b5ba03-6f4e-4640-af90-afa73e8ab352.jpeg`
# F-part5 — F-8. 포털 영상 TUS 업로드 (세션 · 재개 · 완료검증 · 동시성) 32건

> 대상: `docs/test-cases/F-portal.md` § F-8 (TC-TUS-001~032)
> 일자: 2026-08-02 · 2차 · 코드 기준 `qa-0801`
> 검증 방식: **실동작 최우선** — 기동 중인 풀스택(backend :18081 `/api`, PostgreSQL, ai-server, mock-server :9400)에
> curl 로 TUS 프로토콜 시퀀스(OPTIONS→POST→HEAD→PATCH→DELETE)를 직접 왕복시키고,
> `ls_portal_tus_uld` / `ls_portal_uld` / `ls_portal_uld_frme` DB 상태와 컨테이너 파일시스템(`docker exec ls`),
> backend 애플리케이션 로그를 근거로 판정. 정적 대조(file:line Read)는 병행.
> **코드/설정/테스트 파일 수정 0건, 빌드/테스트 실행 0건.**

## 검증 환경

| 항목 | 값 |
|------|-----|
| backend | `http://localhost:18081/api` (context-path `/api`), 컨테이너 `klid-backend` |
| DB | `docker exec klid-postgres psql -U klid_user -d klid_system`, 스키마 `public` |
| 토큰 | 컨테이너 실효 `JWT_SECRET` 으로 HS256 자체 서명. `sub=3001`·`sub=3002` + `channel=PORTAL` → `GET /v1/me` 로 `role=PORTAL_USER` 확인. IDOR 은 3001 세션에 3002 토큰으로 접근 |
| 시료 | `ffmpeg -f lavfi -i testsrc` 로 생성한 유효 mp4(3,390B, 2s) / 오디오전용 mp4(18,802B) / ftyp 위조 파일(1,000B) / ftyp 헤더+쓰레기 본문(1,032B) / 16MB+1B 청크 |
| 실효 설정 | `portal.upload.max-file-size-bytes=5368709120`, `allowed-extensions=[mp4,mov,avi]`, `max-chunk-bytes=16777216`, `storage-path=/app/storage/raw/portal` (application.yml:464-483 실측) |
| DB 조작 | TC-013 검증용으로 세션 1건의 `expry_dt` 를 과거로 UPDATE(해당 세션은 검증 종료 후 CANCELLED 종결). 그 외 스키마·설정 변경 0 |
| 잔여 데이터 | 검증 중 생성한 IN_PROGRESS 세션은 전부 DELETE 로 회수 완료(잔여 IN_PROGRESS 0건). CANCELLED 20 / COMPLETED 8 행은 종결 상태로 잔존 |

## 판정 요약

| 판정 | 건수 |
|------|:---:|
| PASS | 29 |
| PARTIAL | 3 |
| FAIL | 0 |
| BLOCKED / N/A / 확인필요 | 0 |
| **합계** | **32** |

**근거 드리프트: 0건** — 인용된 32개 `file:line` 이 전부 실제 코드 위치와 정확히 일치(카탈로그의 `PortalVideoUploadTxService.java` -1 이동 반영이 이미 적용돼 있음).

---

## 케이스별 결과

| ID | 판정 | 근거 확인 | 상세 |
|----|------|-----------|------|
| TC-TUS-001 | PASS | [실동작] | `OPTIONS /v1/portal/uploads/tus` → **204** + `Tus-Resumable: 1.0.0` · `Tus-Version: 1.0.0` · `Tus-Extension: creation,termination` · **`Tus-Max-Size: 5368709120`**. `PortalTusUploadController.java:67-76` 라인 일치 |
| TC-TUS-002 | PASS | [실동작] | `POST` (Upload-Length: 1024, Upload-Metadata: `filename c2FtcGxlLm1wNA==`) → **201** + `Location: /v1/portal/uploads/tus/7cc90fe6-67e7-40bb-ae57-930fb3096a07`. DB `ls_portal_tus_uld` 1행 IN_PROGRESS/offset=0 생성 확인. `PortalVideoUploadService.java:80-122` 일치. ⚠ Location 이 context-path `/api` 를 누락 → **F-ISSUE-82**(케이스 기대값 자체는 충족하므로 PASS) |
| TC-TUS-003 | PASS | [실동작] | Upload-Length 헤더 생략 → **400** `{"errorCode":"INVALID_INPUT","message":"Upload-Length 헤더가 필요합니다."}`. `Controller:88-90` 일치. 추가 반증: `Upload-Length: 0` → 400, `-1` → 400, `abc` → 400(`파라미터 형식이 올바르지 않습니다: Upload-Length`) — 전부 방어됨 |
| TC-TUS-004 | PASS | [실동작] | `Upload-Length: 5368709121`(=상한+1) → **413** `PAYLOAD_TOO_LARGE` "허용 한도(5368709120 bytes) 를 초과". `Service:87-90` 일치. 커버 테스트 `PortalVideoUploadServiceTest#uploadLengthTooLarge`(baseline 통과) |
| TC-TUS-005 | PASS | [실동작] | `evil.exe` → **400** "허용되지 않는 확장자입니다. 허용: [mp4, mov, avi]". 반증 추가: 확장자 없음(메타 미전송) → 400, `a.webm` → **400**(코드의 `EXT_TO_MIME` 에 webm/mkv 가 있지만 allowlist 가 최종 게이트 — 우회 불가), `A.MP4` 대문자 → **201**(`toLowerCase` 정규화 정상). `Service:91-95` 일치. 테스트 `#invalidExtensionRejected` |
| TC-TUS-006 | PASS | [실동작] | IN_PROGRESS 3건 보유 상태에서 4번째 POST → **429** `TOO_MANY_REQUESTS` "동시 진행 가능한 업로드 세션 수(3) 를 초과". `Service:96-102` 일치 |
| TC-TUS-007 | PASS | [실동작] | `Upload-Metadata: filename <b64('../../../../etc/passwd.mp4')>` → 201. DB `file_path_nm=/app/storage/raw/portal/tus-video/b3e4c9f4-….mp4`, `orgnl_file_nm='../../../../etc/passwd.mp4'`(표시용만). 컨테이너 `ls` 로 `tus-video/` 하위 UUID 파일 실재 확인, 상위 경로 파일 생성 0. `Service:104-107` 일치. 테스트 `#pathTraversalForcedUuid` |
| TC-TUS-008 | PASS | [실동작] | `Upload-Metadata` 1,109자 → **413** "Upload-Metadata 가 1KB 를 초과했습니다.". `Controller:182-184` 일치 |
| TC-TUS-009 | PASS | [실동작] | `Upload-Metadata: filename !!!not-b64!!!` → **400** "Upload-Metadata 디코딩에 실패했습니다.". `Controller:194-200` 일치 |
| TC-TUS-010 | PARTIAL | [실동작] | `Tus-Resumable: 0.2.2` → **412** `PRECONDITION_FAILED`(POST·HEAD 양쪽 확인, `Controller:163-168` 일치). **그러나 헤더를 아예 생략하면 검사 없이 201/204 로 통과**한다 — `requireTusVersion` 이 `tusResumable != null` 가드라 null 을 허용. TUS 1.0 은 OPTIONS 외 전 요청에 Tus-Resumable 을 MUST 로 요구하고 부재 시 412 를 규정 → **F-ISSUE-83** |
| TC-TUS-011 | PASS | [실동작] | 소유 세션 HEAD → **204** + `Upload-Offset: 0` · `Upload-Length: 1024` · **`Cache-Control: no-store`**. 청크1(2,000B) 전송 후 재HEAD → `Upload-Offset: 2000`(재개 좌표 정확). `Controller:102-116` 일치 |
| TC-TUS-012 | PASS | [실동작] | 3001 소유 세션에 3002 토큰으로 **HEAD 403 / PATCH 403 / DELETE 403** (`{"errorCode":"FORBIDDEN","message":"본인의 업로드 세션이 아닙니다."}`). 세션 하이재킹(타 사용자 세션 재개) 차단 확인. 존재하지 않는 uldId → 404(존재 여부 오라클 없음). `Service:130-132` + `TxService:74-76` 일치. 테스트 `#nonOwnerPatchForbidden`·`#nonOwnerHeadDeleteForbidden` |
| TC-TUS-013 | PASS | [실동작] | `expry_dt='2020-01-01'` 로 조작한 IN_PROGRESS 세션 → **HEAD 410 GONE** / **PATCH 410** "업로드 세션이 만료되었습니다.". `Service:133-135`·`TxService:77-79` 일치. (DELETE 는 만료 검사 없이 204 로 정리 — 정리 동선상 정상) |
| TC-TUS-014 | PASS | [실동작] | 3,390B 세션에 청크1(0~1,999) PATCH → **204** + `Upload-Offset: 2000`, 청크2(2,000~3,389) → **204** + `Upload-Offset: 3390`. DB `uld_offset=3390`, `stts_cd=COMPLETED`, `uld_sn=57`. `ls_portal_uld` 57 = VIDEO/tiny.mp4/3390B/READY/frme_cnt=1. `TxService:68-124` 일치 |
| TC-TUS-015 | PASS | [실동작] | Upload-Offset 생략 PATCH → **400** "Upload-Offset 헤더가 필요합니다.". `Controller:130-132` 일치 |
| TC-TUS-016 | PASS | [실동작] | 서버 offset=0 인 세션에 `Upload-Offset: 100` → **409** `CONFLICT` "Upload-Offset 이 서버 상태와 일치하지 않습니다."(재개 무결성). `TxService:98-100` 일치 |
| TC-TUS-017 | PASS | [실동작] | `Upload-Offset: -5` → **400**, `Upload-Offset: 99999`(>length) → **400** "Upload-Offset 값이 범위를 벗어났습니다.". `TxService:95-97` 일치 |
| TC-TUS-018 | PASS | [실동작] | Upload-Length 20,000,000 세션에 16,777,217B(=16MB+1) 청크 → **413** "청크 크기가 허용 한도(16777216 bytes) 를 초과했습니다.". 사후 DB `uld_offset=0` 유지 + 컨테이너 파일 **0바이트 유지**(부분 쓰기 0). `TxService:91-94` 일치 |
| TC-TUS-019 | PASS | [실동작] | offset=2000, 잔여 1,390B 세션에 2,000B 청크 → **400** "청크 길이가 잔여 용량을 초과합니다.". `TxService:101-103` 일치 |
| TC-TUS-020 | PARTIAL | [실동작] | 동일 세션에 offset=0 PATCH 3건 **동시** 발사 → **204 정확히 1건 + 409 2건**, DB `uld_offset=1048576`·`ver=1`, 파일 크기 정확히 1,048,576B(오염 0). **결과는 기대대로**지만 **메커니즘이 케이스 전제와 다르다** — 직렬화는 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)가 담당하고 후행 요청은 `TxService:98-100` 의 **offset 불일치 409** 로 떨어진다. 케이스가 인용한 `TxService:109-115`(낙관적 락 catch + `truncateTo` 복원)는 로그(`concurrent PATCH conflict`) 0건으로 **미발화** → **F-ISSUE-87** |
| TC-TUS-021 | PASS | [실동작] | CANCELLED 세션에 PATCH → **409** "취소된 업로드 세션입니다."(완료 검사보다 먼저 평가되는 순서도 `TxService:81-87` 로 확인). 테스트 `#cancelledSessionPatchRejected` |
| TC-TUS-022 | PASS | [실동작] | COMPLETED 세션(uldSn=57)에 마지막 청크 재전송(offset=2000, 1,390B) → **204 + `Upload-Offset: 3390`**, 서비스는 `PortalTusPatchResult(3390, true, 57)` 로 기존 uldSn 재반환(`TxService:84-87` → `Service:151-157`). **`ls_portal_uld` 중복 행 0건**(재전송 전후 count 불변). 단 HTTP 응답 본문/헤더에 uldSn 이 노출되지 않는다 → **F-ISSUE-88**(unit 계층 기대값은 서비스 반환값이라 PASS 유지). 테스트 `#duplicateCompletionPublishesEventOnce` |
| TC-TUS-023 | PASS | [실동작] | `MZ\x90\x00`+패딩(1,000B, 확장자만 .mp4) 완료 청크 → **400** "업로드된 파일이 유효한 영상 컨테이너가 아닙니다." + DB `stts_cd=CANCELLED` + **컨테이너 임시파일 삭제 확인**(`ls: No such file or directory`). `Service:161-165` + `TxService:164-178`(REQUIRES_NEW 로 400 던져도 취소가 롤백 안 됨) 일치. 테스트 `#magicByteMismatchRejectsAndDeletes` |
| TC-TUS-024 | PASS | [실동작] | 유효 `ftyp isom` 헤더 + 쓰레기 본문(1,032B) → 매직바이트는 통과하고 **ffprobe 단계에서 400** "영상을 확인할 수 없습니다. 손상되었거나 지원되지 않는 형식일 수 있습니다." + CANCELLED + 파일 삭제. `Service:167-175` 일치. 타임아웃 가드(`PortalVideoProbeFfprobe` Future.get 30s)도 정적 확인 |
| TC-TUS-025 | PASS | [실동작] | 오디오 전용 mp4(aac, 18,802B) → **400** "비디오 스트림이 없는 파일입니다(오디오 전용 등)." + CANCELLED + 파일 삭제. `Service:176-179` 일치. 테스트 `#audioOnlyRejected` |
| TC-TUS-026 | PASS | [실동작] | **락/트랜잭션 밖 검증을 레이스로 실증**했다 — 최종 청크 PATCH 발사 후 20ms·40ms 뒤 DELETE 를 보내면 DELETE 가 **즉시 행 락을 획득해 204 로 임시파일을 삭제**하고, 그 뒤 진행되던 ffprobe 가 파일 부재로 실패해 400 이 났다. 검증 구간에 행 락이 걸려 있었다면 DELETE 는 대기했어야 한다 ⇒ 검증이 tx/락 밖에서 도는 것이 확인됨. 정적으로도 `Service.appendChunk`(:146-186)에 `@Transactional` 부재 + `PortalVideoUploadTxService` 별 빈 분리(self-invocation 회피) 확인 |
| TC-TUS-027 | PASS | [실동작] | 완료 7회(uldSn 57·67~72) 각각에 대해 `ls_portal_uld` 1행 / `ls_portal_uld_frme` 1행 — **1:1, 중복 0**. 조건부 UPDATE `markCompletedIfInProgress`(`LsPortalTusUploadRepository`) + `affectedRows==1` 게이트 후 `publishEvent` 순서 `TxService:132-157` 일치. 프레임 추출이 세션당 정확히 1회 실행된 것이 이벤트 단발 발행의 실측 증거. 테스트 `#duplicateCompletionPublishesEventOnce` |
| TC-TUS-028 | PARTIAL | [정적] | 정확한 창(**검증 성공 후 `finalizeCompleted` 진입 직전**에 cancel 착지)은 폭이 밀리초급이라 8회 동시 레이스 + 8단계 지연 스윕(20ms~300ms, 총 16시행)으로도 재현하지 못했다(관측된 것은 ①cancel 선행 → 409 "취소된 업로드 세션입니다" ②검증 중 cancel → 400 ffprobe 실패 ③cancel 후행 → 정상 완료 3종). **다만 인접 레이스 16시행 전부에서 고아 `ls_portal_uld` 행이 0건**이었고, 보상 삭제 경로(`TxService:141-152`: `uldRepository.delete(saved)` → 재조회 후 이미 완료면 기존 uldSn 멱등 반환, 아니면 409)를 코드로 확인. 실동작 미재현 구간이 남아 PARTIAL |
| TC-TUS-029 | PASS | [실동작] | IN_PROGRESS 세션 3건 DELETE → **204**, 각각 컨테이너 임시파일이 삭제 전 존재 → 삭제 후 부재로 확인, DB `stts_cd=CANCELLED`. `Service:190-206` 일치 |
| TC-TUS-030 | PASS | [실동작] | COMPLETED 세션(uldSn=57) DELETE → **204**, 파일 `d325816e-….mp4` **3,390B 그대로 보존**, DB 상태 `COMPLETED/uld_sn=57` 불변(영구 영상 삭제 금지 no-op). `Service:198-201` 일치 |
| TC-TUS-031 | PASS | [실동작] | 600KB 청크를 `--limit-rate 100K` 로 전송(총 6.02s)하는 도중 t≈1.5s 에 DELETE 발사 → **DELETE 가 4.26s 소요**(≈PATCH tx 커밋까지 대기)하고 그 뒤 성공. cancel 이 PATCH 와 같은 `findByUldIdForUpdate` 락으로 **직렬화**됨을 시간축으로 실증. 최종 상태 CANCELLED, 파일 삭제. `Service:192-194` 일치 |
| TC-TUS-032 | PASS | [실동작] | 저장 경로는 항상 `storageRoot/tus-video/{UUID}.{ext}` 로 조립되고 `ext` 는 `^[a-z0-9]{1,8}$` 정규화 + allowlist 통과분만 사용되므로 사용자 입력이 경로 세그먼트에 도달하지 않는다. 경로형 filename 실투입에서도 storage 밖 파일 생성 0(TC-007 참조). `resolveSafe`(`Service:210-218`)는 **도달 불가한 심층방어**이지만 존재·정확성 확인. 삭제 경로도 `TusChunkStore.deleteQuietly(path, storageRoot)` 로 root 하위 재검증 |

---

## 테스트 커버리지 대조

| 항목 | 내용 |
|------|------|
| 자동 테스트 자산 | `backend/src/test/java/kr/co/cudo/authoring/portal/PortalVideoUploadServiceTest.java`(372L, 11 테스트) 단 1개. 그 외 `PortalUploadSweepIT`·`PortalUploadSweepTxServiceTest`·`PortalUploadPropertiesBindingTest`·`V111PortalTusUploadMigrationTest` 는 F-8 직접 대상 아님 |
| baseline | `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md` — backend tests=4,755 / failures 0 / errors 0. 아래 커버 테스트는 전부 통과 상태 |
| 커버되는 TC | TC-004(`#uploadLengthTooLarge`) · 005(`#invalidExtensionRejected`) · 007·032(`#pathTraversalForcedUuid`) · 012(`#nonOwnerPatchForbidden`,`#nonOwnerHeadDeleteForbidden`) · 014·027(`#completesToUploadedAndPublishesEvent`) · 021(`#cancelledSessionPatchRejected`) · 022·027(`#duplicateCompletionPublishesEventOnce`) · 023(`#magicByteMismatchRejectsAndDeletes`,`#rejectionPersistsCancelledAndDeletesFile`) · 025(`#audioOnlyRejected`) — **11 TC** |
| **미커버 TC** | TC-001·002·003·006·008·009·010·011·013·015·016·017·018·019·020·024·026·028·029·030·031 — **21 TC**. 특히 **컨트롤러(MockMvc) 테스트가 0건**이라 헤더 프로토콜(OPTIONS 광고·Tus-Resumable 412·Location·metadata 1KB/base64·no-store) 전체가 자동 회귀 보호를 못 받는다 → **F-ISSUE-89** |

---

## 이슈

### [F-ISSUE-81] TC-TUS-014 — PATCH 의 Content-Type 불일치/부재가 415 가 아니라 500 + 스택트레이스 ERROR 로 처리됨
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: `@PatchMapping(consumes = "application/offset+octet-stream")` 과 다른 Content-Type 으로 온 PATCH 는 **415 Unsupported Media Type**(TUS 1.0 도 415 를 규정)으로 거부돼야 한다. 클라이언트가 "내 요청이 잘못됐다"를 알 수 있어야 하고, 정상적인 클라이언트 오류가 서버 장애로 집계·알림되면 안 된다.
- **현재 동작(이슈 내용)**: `HttpMediaTypeNotSupportedException` 을 처리하는 핸들러가 `GlobalExceptionHandler` 에 없어 **generic 핸들러로 낙하 → 500 + `INTERNAL_ERROR`**, 동시에 `[Exception] unhandled exception` 으로 **ERROR 레벨 전체 스택트레이스**가 로그에 남는다.
  ```
  2026-08-02 18:02:37.110 ERROR k.c.c.a.c.e.GlobalExceptionHandler - [Exception] unhandled exception
  org.springframework.web.HttpMediaTypeNotSupportedException: Content-Type 'application/octet-stream' is not supported
      at org.springframework.web.servlet.mvc.method.RequestMappingInfoHandlerMapping.handleNoMatch(...)
  ```
  응답: `{"success":false,"data":null,"message":"서버 내부 오류가 발생했습니다.","errorCode":"INTERNAL_ERROR"}` (500)
  **포털 TUS 전용이 아니라 관제 내부 TUS(`PATCH /v1/uploads/{id}`)도 동일하게 500** 이다(양쪽 실측).
- **재현/확인 경로**:
  ```bash
  curl -s -w " <%{http_code}>" -X PATCH -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" \
    -H "Content-Type: application/octet-stream" --data-binary @chunk.bin \
    http://localhost:18081/api/v1/portal/uploads/tus/00000000-0000-0000-0000-000000000000
  # → 500 INTERNAL_ERROR (기대: 415)
  # Content-Type 자체를 생략해도 동일하게 500
  ```
- **영향**: ①프로토콜 오분류 — 표준 TUS 클라이언트가 415(요청 수정)와 500(서버 장애, 재시도 대상)을 구분 못 해 무의미한 재시도를 반복한다. ②운영 노이즈/오탐 — 클라이언트 실수가 ERROR 스택트레이스로 남아 장애 알림·SLO 를 오염시킨다. ③CWE-209 인접 — 내부 프레임워크 예외 타입·스택이 로그에 그대로 적재된다(응답 본문 유출은 없음).
- **수정 방향(제안)**: `common/exception/GlobalExceptionHandler` 에 `@ExceptionHandler(HttpMediaTypeNotSupportedException.class)` 를 추가해 **415 + 도메인 errorCode**(예: `UNSUPPORTED_MEDIA_TYPE`)로 매핑하고 WARN 레벨로 낮춘다. 함께 `HttpRequestMethodNotSupportedException`(405)·`HttpMediaTypeNotAcceptableException`(406)도 같은 계층에서 처리 여부를 점검할 것. ⚠ 구현은 하지 않음.

### [F-ISSUE-82] TC-TUS-002 — 세션 생성 `Location` 헤더가 context-path `/api` 를 누락해 표준 TUS 클라이언트가 따라갈 수 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 1.0 은 POST 응답의 `Location` 을 **이후 HEAD/PATCH/DELETE 에 그대로 사용하는 URL** 로 규정한다. 컨트롤러 javadoc 이 명시적으로 *"응답: TUS 1.0 헤더 기반(ApiResponse 미사용 — **표준 tus-js-client 호환**)"*(`PortalTusUploadController.java:38-40`)을 표방하므로, Location 은 실제로 도달 가능한 경로여야 한다.
- **현재 동작(이슈 내용)**: 애플리케이션 context-path 가 `/api`(`application.yml:126-127`)인데 Location 은 `/api` 없이 조립된다.
  ```java
  // PortalTusUploadController.java:94-97
  return ResponseEntity.status(HttpStatus.CREATED)
          .header(H_RESUMABLE, TUS_VERSION)
          .header(HttpHeaders.LOCATION, "/v1/portal/uploads/tus/" + uldId)   // ← "/api" 누락
          .build();
  ```
  실측: `Location: /v1/portal/uploads/tus/7cc90fe6-67e7-40bb-ae57-930fb3096a07`. 이 절대경로를 origin 기준으로 해석하면 `http://host/v1/portal/uploads/tus/{id}` 로 **404**. 자체 FE(`frontend/src/features/upload/api/tusClient.ts:99` `location.split('/').pop()`)는 **마지막 세그먼트만 잘라 endpointBase 로 재조립**하기 때문에 우연히 동작한다. 관제 내부 `TusUploadController.java:96` 도 동일 패턴.
- **재현/확인 경로**:
  ```bash
  L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Length: 1024" \
      -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
      http://localhost:18081/api/v1/portal/uploads/tus | grep -i '^location')
  echo "$L"                       # Location: /v1/portal/uploads/tus/{uuid}
  curl -s -o /dev/null -w "%{http_code}\n" -I "http://localhost:18081/v1/portal/uploads/tus/{uuid}"  # 404
  ```
- **영향**: 기능(외부 연동) — 문서가 표방한 tus-js-client 등 표준 클라이언트 연동이 즉시 깨진다. 현재는 자체 FE 의 "마지막 세그먼트만 사용" 우회에 의존하고 있어, FE 가 표준 라이브러리로 교체되거나 외부 파트너가 붙는 순간 드러난다.
- **수정 방향(제안)**: `ServletUriComponentsBuilder.fromCurrentRequest()` 또는 `request.getContextPath()` 를 이용해 context-path 포함 URL 을 조립(포털·관제 두 컨트롤러 동시). 리버스 프록시 경로 재작성 가능성을 고려해 `server.forward-headers-strategy` 와 함께 검토. ⚠ 구현은 하지 않음.

### [F-ISSUE-83] TC-TUS-010 — `Tus-Resumable` 헤더가 **부재**하면 버전 검사를 통째로 건너뛴다(412 미발생)
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 core 규약상 OPTIONS 를 제외한 모든 요청은 `Tus-Resumable` 을 포함해야 하며, 부재 또는 미지원 버전이면 서버는 **412 Precondition Failed** 로 거부해야 한다. 버전 협상을 강제해야 클라이언트/서버 프로토콜 드리프트가 조용히 통과하지 않는다.
- **현재 동작(이슈 내용)**: null 을 예외 처리해 검사를 우회한다.
  ```java
  // PortalTusUploadController.java:163-168
  private void requireTusVersion(String tusResumable) {
      if (tusResumable != null && !TUS_VERSION.equals(tusResumable)) {   // ← null 이면 통과
          throw new CustomException(ErrorCode.PRECONDITION_FAILED, ...);
      }
  }
  ```
  실측: 헤더 생략 POST → **201**(세션 생성됨), 헤더 생략 HEAD/PATCH/DELETE 도 정상 처리. `0.2.2` 같은 잘못된 값만 412.
- **재현/확인 경로**:
  ```bash
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Authorization: Bearer $PORTAL_TOKEN" \
    -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
    http://localhost:18081/api/v1/portal/uploads/tus     # → 201 (기대: 412)
  ```
- **영향**: 프로토콜 conformance. 버전 미표기 클라이언트가 조용히 성공하므로, 향후 TUS 2.x 도입이나 헤더 규약 변경 시 호환성 문제를 사전에 못 잡는다. 보안 영향은 없다(인증·소유자 검증은 별도로 동작).
- **수정 방향(제안)**: `requireTusVersion` 을 `if (!TUS_VERSION.equals(tusResumable))` 로 바꿔 null 도 412 로 거부. 단 **기존 자체 FE 클라이언트가 항상 헤더를 보내는지 먼저 확인**(`tusClient.ts` 는 POST/HEAD/PATCH 모두 전송 확인, DELETE 경로만 재확인 필요)한 뒤 적용. 관제 내부 컨트롤러도 동일 검토. ⚠ 구현은 하지 않음.

### [F-ISSUE-84] TC-TUS-004 — 오류 응답에 `Tus-Resumable` 헤더가 포함되지 않음
- **심각도**: LOW
- **기대 동작(기대효과)**: TUS 1.0 은 서버가 **모든 응답**(오류 포함)에 `Tus-Resumable` 을 실어 보내도록 규정한다. 클라이언트가 오류 응답만 보고도 상대가 TUS 서버인지·어떤 버전인지 판별할 수 있어야 한다.
- **현재 동작(이슈 내용)**: 정상 응답(201/204)에는 컨트롤러가 직접 `H_RESUMABLE` 을 붙이지만, 예외 경로는 `GlobalExceptionHandler` 가 `ApiResponse` JSON 을 만들어 반환하므로 TUS 헤더가 전부 사라진다. 실측:
  ```
  $ curl -D - -X POST ... -H "Upload-Length: 99999999999" ...
  HTTP/1.1 413
  (Tus-Resumable 헤더 없음)
  ```
- **재현/확인 경로**: 위 curl 의 `-D -` 출력에서 `Tus-Resumable` grep → 0건. 413/412/409/400 전 오류 코드 동일.
- **영향**: 프로토콜 conformance만. 기능·보안 영향 없음.
- **수정 방향(제안)**: TUS 경로 전용 `HandlerInterceptor` 또는 `ResponseBodyAdvice` 로 `/v1/portal/uploads/tus/**`·`/v1/uploads/**` 응답에 `Tus-Resumable` 을 일괄 부착. F-ISSUE-81 의 예외 매핑 작업과 함께 처리하면 경제적. ⚠ 구현은 하지 않음.

### [F-ISSUE-85] TC-TUS-011/029 — CANCELLED 세션에 HEAD 하면 204 + 실제로는 존재하지 않는 offset 을 돌려준다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 재개(resume)의 진입점은 HEAD 다. 종료된(취소된) 업로드에 대해 서버는 **더 이상 재개 대상이 아님**을 HEAD 단계에서 알려야(TUS 1.0: 404) 클라이언트가 새 세션을 만드는 정상 동선으로 갈 수 있다.
- **현재 동작(이슈 내용)**: `getForOwner` 는 소유자·만료만 검사하고 **취소 여부를 보지 않는다**.
  ```java
  // PortalVideoUploadService.java:126-137
  LsPortalTusUpload session = tusRepository.findById(uldId).orElseThrow(... NOT_FOUND ...);
  if (!session.isOwnedBy(portalUserNo)) { throw ... FORBIDDEN ...; }
  if (session.isExpired(java.time.LocalDateTime.now())) { throw ... GONE ...; }   // isExpired 는 IN_PROGRESS 만 판정
  return session;                                                                  // CANCELLED 여도 그대로 반환
  ```
  실측 시나리오(TC-031 레이스 산출물): 느린 PATCH 로 614,400B 를 커밋한 직후 DELETE 가 착지 → DB `stts_cd=CANCELLED, uld_offset=614400`, **임시파일은 삭제됨**. 이 세션에 HEAD → **204 `Upload-Offset: 614400`**. 클라이언트는 "614,400B 부터 이어서 보내면 된다"고 판단하지만 이어지는 PATCH 는 409 `취소된 업로드 세션입니다` 로 거부된다(파일도 이미 없다).
- **재현/확인 경로**:
  ```bash
  # 세션 생성 → 청크 일부 PATCH → DELETE → HEAD
  curl -s -o /dev/null -w "%{http_code}\n" -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S
  curl -s -I -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B/v1/portal/uploads/tus/$S | grep -iE "HTTP|Upload-Offset"
  # → HTTP/1.1 204 / Upload-Offset: 614400   (기대: 404 또는 410)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,uld_offset from ls_portal_tus_uld where uld_id='$S';"
  # → CANCELLED | 614400  (파일은 삭제 상태)
  ```
- **영향**: 기능/데이터정합 — 재개 클라이언트가 무효 세션을 유효한 것으로 오인해 불필요한 재시도 루프에 빠지고, 사용자에게는 "이어받기 가능"으로 표시된 뒤 실패한다. DB offset 과 실제 파일(부재)의 불일치가 그대로 노출된다. 보안 영향은 없다(소유자 검증은 선행되고 파일도 이미 삭제).
- **수정 방향(제안)**: `getForOwner` 에 `if (session.isCancelled()) throw new CustomException(ErrorCode.NOT_FOUND, ...)` 를 추가(TUS 1.0 의 terminated upload → 404 규약). 취소 후 오래 지난 세션의 존재 여부 오라클을 만들지 않도록 **404 로 통일**하는 편이 410 보다 안전하다. 관제 내부 TUS 도 동일 패턴인지 확인. ⚠ 구현은 하지 않음.

### [F-ISSUE-86] TC-TUS-006 — TUS 세션 생성에 rate limit 이 없고 종결 세션 행이 영구 잔존해 create/cancel 루프로 무제한 증식 가능
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털은 외부 채널이므로 인증된 사용자라도 자원 소비를 제한해야 한다(OWASP API4:2023 Unrestricted Resource Consumption). 같은 포털 도메인의 **이미지 업로드(`PortalUploadController:189-192`, config `portalUpload`)와 SAM2(`PortalSam2Service:286-289`, config `portalSam2`)에는 per-user RateLimiter 가 이미 적용**돼 있어, TUS 만 빠진 것은 방어 일관성의 구멍이다.
- **현재 동작(이슈 내용)**: `PortalTusUploadController`·`PortalVideoUploadService` 어디에도 RateLimiter 배선이 없다(`grep -rn "RateLimit" portal/` → `PortalUploadController`·`PortalSam2Service` 2곳만 매치). 유일한 억제 수단은 `MAX_CONCURRENT_IN_PROGRESS = 3`(`Service:52,96-102`)인데, 이는 **IN_PROGRESS 만 세므로** create→cancel 을 반복하면 무한히 우회된다. 게다가 정리 스윕은 **IN_PROGRESS 만 삭제**한다:
  ```java
  // LsPortalTusUploadRepository
  @Query("SELECT u FROM LsPortalTusUpload u WHERE u.sttsCd = 'IN_PROGRESS' AND u.expiresAt < :now")
  List<LsPortalTusUpload> findExpired(...);
  @Query("DELETE FROM LsPortalTusUpload u WHERE u.uldId = :uldId AND u.sttsCd = 'IN_PROGRESS'")
  int deleteExpiredInProgress(...);
  ```
  → **CANCELLED/COMPLETED 행은 어떤 경로로도 삭제되지 않는다**(`PortalUploadSweepTxService.claimExpiredSessions` 실측). 세션 생성 시마다 `TusChunkStore.createEmptyFile` 로 0바이트 파일도 만들어진다(cancel 시 삭제되긴 함).
- **재현/확인 경로**:
  ```bash
  # POST → DELETE 를 N회 반복해도 429 없이 계속 성공하고, 행은 계속 쌓인다
  for i in $(seq 1 100); do
    L=$(curl -s -D - -o /dev/null -X POST -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
        -H "Upload-Length: 1024" -H "Upload-Metadata: filename $(printf a.mp4|base64)" \
        $B/v1/portal/uploads/tus | grep -i '^location' | tr -d '\r' | awk '{print $2}')
    curl -s -o /dev/null -X DELETE -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" $B$L
  done
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select stts_cd,count(*) from ls_portal_tus_uld group by stts_cd;"
  # 본 검증 세션만으로도 CANCELLED 20건이 영구 잔존 상태로 남았다
  ```
- **영향**: 가용성/저장소(CWE-770). 인증된 포털 사용자 1인이 `LS_PORTAL_TUS_ULD` 를 무제한 증식시킬 수 있고, 종결 행이 정리되지 않아 시간이 지날수록 테이블이 단조 증가한다. 부분 인덱스(`idx_lptu_user_stts`, `idx_lptu_expry`)가 IN_PROGRESS 조건부라 조회 성능 영향은 제한적이지만 스토리지·백업 비용은 누적된다.
- **수정 방향(제안)**: ①`PortalTusUploadController.create` 에 `PortalUploadController` 와 동일한 per-user RateLimiter(`portalTusCreate` config 신설 또는 `portalUpload` 재사용) 적용. ②스윕(`PortalUploadSweepTxService`)에 **종결 세션 보존기간(retention) 정리**를 추가 — `CANCELLED` 이고 `mdfcn_dt < now - N일` 인 행 조건부 벌크 삭제. `COMPLETED` 는 `uld_sn` 추적 가치가 있으니 보존기간을 따로 잡을 것. ⚠ 구현은 하지 않음.

### [F-ISSUE-87] TC-TUS-020 — 낙관적 락 충돌 복원 분기(`truncateTo`)가 비관적 락에 가려 실행되지 않는 사실상 dead branch
- **심각도**: LOW
- **기대 동작(기대효과)**: 케이스는 동시 PATCH 시 `OptimisticLockingFailureException` → 409 + **기록 시작 오프셋으로 truncate 복원**을 기대한다. 방어 코드가 실제로 도는지, 아니면 다른 계층이 먼저 막는지가 명확해야 유지보수 시 잘못된 안전감을 갖지 않는다.
- **현재 동작(이슈 내용)**: `appendChunkTx` 진입 시 이미 `findByUldIdForUpdate`(PESSIMISTIC_WRITE)로 행을 잠그므로(`TxService:71-73`) 동일 세션의 PATCH 는 **완전 직렬화**된다. 후행 요청은 락 획득 후 `expectedOffset != session.getOffsetBytes()` 에 걸려 `TxService:98-100` 에서 409 로 끝나고, `@Version` 충돌은 발생할 수 없다.
  ```java
  // TxService:109-115 — 실동작에서 미발화
  } catch (OptimisticLockingFailureException e) {
      TusChunkStore.truncateTo(session.getFilePathNm(), expectedOffset);
      log.warn("[PortalTus] concurrent PATCH conflict uldId={}", uldId);
      throw new CustomException(ErrorCode.CONFLICT, "동시 업로드 요청이 충돌했습니다. 재시도하세요.");
  }
  ```
  실측: offset=0 동시 PATCH 3건 → 204×1 + 409×2, 두 409 의 메시지는 모두 `Upload-Offset 이 서버 상태와 일치하지 않습니다`(=offset 불일치 경로). backend 로그에 `concurrent PATCH conflict` **0건**.
- **재현/확인 경로**:
  ```bash
  for i in 1 2 3; do ( curl -s -w "req$i:<%{http_code}> " -X PATCH -H "Authorization: Bearer $T" \
      -H "Tus-Resumable: 1.0.0" -H "Upload-Offset: 0" -H "Content-Type: application/offset+octet-stream" \
      --data-binary @1mb.bin $B/v1/portal/uploads/tus/$S ) & done; wait
  docker logs klid-backend 2>&1 | grep -c "concurrent PATCH conflict"   # → 0
  ```
- **영향**: 기능·보안 영향 없음(결과는 정확히 1건만 성공, 파일 무결). 다만 ①테스트 카탈로그의 전제(TC-TUS-020 이 인용한 `TxService:109-115`)와 실제 방어 계층이 어긋나고 ②커버리지가 영원히 도달하지 못하는 분기가 남아, 향후 누군가 비관적 락을 제거하면 "낙관적 락이 받쳐준다"는 잘못된 전제로 회귀를 만들 수 있다.
- **수정 방향(제안)**: 둘 중 택일 — ⓐ현 구조 유지 + 해당 catch 블록에 "PESSIMISTIC_WRITE 로 도달 불가한 최후 방어" 주석 명시 + 카탈로그 TC-TUS-020 의 근거를 `TxService:98-100`(offset 불일치 409)로 정정, ⓑ 비관적 락을 유지하는 한 낙관적 락 catch 를 제거. **ⓐ 권장**(다중 노드/락 타임아웃 상황의 심층방어 가치는 남는다). ⚠ 구현은 하지 않음.

### [F-ISSUE-88] TC-TUS-022/027 — 완료 결과(uldSn·completed)가 HTTP 응답에 노출되지 않고, FE 도 완료 시 목록을 무효화하지 않아 업로드한 영상이 화면에 안 보인다
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 서비스가 완료 시 `uldSn` 을 산출하므로(`PortalTusPatchResult(newOffset, completed, uldSn)`) 클라이언트는 어떤 자산이 만들어졌는지 알 수 있어야 하고, 최소한 사용자가 방금 올린 영상이 "내 업로드" 목록에 즉시 나타나야 한다.
- **현재 동작(이슈 내용)**: 컨트롤러가 `result.completed()`·`result.uldSn()` 을 **버리고** offset 만 반환한다.
  ```java
  // PortalTusUploadController.java:136-142
  PortalVideoUploadService.PortalTusPatchResult result =
          uploadService.appendChunk(uldId, requireUser(actor), uploadOffset, in, contentLength);
  return ResponseEntity.noContent()
          .header(H_RESUMABLE, TUS_VERSION)
          .header(H_UPLOAD_OFFSET, String.valueOf(result.newOffset()))   // completed/uldSn 미노출
          .build();
  ```
  실측: 완료 청크 PATCH → `HTTP/1.1 204` + `Upload-Offset: 3390` 뿐(본문 없음, 커스텀 헤더 없음). DB 에는 `uld_sn=57` 이 정상 기록됨.
  FE 쪽도 보완이 없다 — `PortalUploadPage.tsx:95-99` 의 `onVideoStart` 는 `tus.start(...).catch(() => undefined)` 만 하고 **`queryClient.invalidateQueries` 를 호출하지 않으며**, 목록 훅 `usePortalUploads`(`hooks/usePortalUploads.ts:17-29`)의 폴링은 **이미 캐시에 있는 목록에 진행중 자산이 있을 때만** 동작한다(`pollIntervalFor`: 전부 종결이면 `false`). 즉 목록이 전부 READY/FAILED 인 상태에서 영상을 새로 올리면 **폴링도 무효화도 없어 새 자산이 화면에 나타나지 않는다**(이미지 업로드는 `useUploadImages.ts:31` 에서 `invalidateQueries` 를 한다 — 경로별 비대칭).
- **재현/확인 경로**:
  ```bash
  # BE: 완료 응답에 uldSn 이 없다
  curl -s -D - -o /dev/null -X PATCH -H "Authorization: Bearer $T" -H "Tus-Resumable: 1.0.0" \
    -H "Upload-Offset: 2000" -H "Content-Type: application/offset+octet-stream" \
    --data-binary @chunk2.bin $B/v1/portal/uploads/tus/$S
  # → HTTP/1.1 204 / Upload-Offset: 3390  (uldSn 없음)
  docker exec klid-postgres psql -U klid_user -d klid_system -c "select uld_id,stts_cd,uld_sn from ls_portal_tus_uld where uld_id='$S';"
  # → COMPLETED | 57
  ```
  FE: 목록의 모든 자산이 READY 인 상태(`/portal/uploads`)에서 영상을 업로드 → 진행률 100% 도달 후에도 목록에 새 행이 추가되지 않음(수동 새로고침 필요).
- **영향**: 기능/UX. 업로드가 성공했는데 화면상 실패처럼 보인다. TUS 204 규약을 지키려는 의도는 타당하나, 그 경우 **클라이언트 측 후속 동선(목록 무효화)** 이 반드시 있어야 하는데 양쪽 다 빠져 있다.
- **수정 방향(제안)**: ⓐ최소안 — FE `PortalUploadPage.onVideoStart` 의 성공 콜백에서 `qc.invalidateQueries({ queryKey: PORTAL_KEYS.all })`(이미지 경로와 동일 패턴) 호출. ⓑ보강안 — BE 가 완료 시 커스텀 헤더(예: `X-Portal-Uld-Sn`)를 함께 내려 클라이언트가 생성된 자산을 직접 지목할 수 있게 한다(204 본문 없는 TUS 규약과 양립). **ⓐ 는 필수, ⓑ 는 선택**. ⚠ 구현은 하지 않음.

### [F-ISSUE-89] F-8 전반 — TUS 컨트롤러 계층 자동 테스트가 0건이라 헤더 프로토콜·동시성·만료가 회귀 무방비
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: TUS 는 **전적으로 HTTP 헤더로 동작하는 프로토콜**이라 서비스 단위 테스트만으로는 계약을 보호할 수 없다. 상태코드·헤더 조합(201+Location, 204+Upload-Offset, 412, 413, 410, 409, no-store)이 회귀 테스트로 고정돼야 한다.
- **현재 동작(이슈 내용)**: F-8 관련 자동 테스트는 `PortalVideoUploadServiceTest.java`(11 테스트, 서비스 계층 + fake repository) **단 1 파일**이다. `grep -rln "PortalTus|PortalVideoUpload|portal/uploads/tus" backend/src/test` 결과에 **MockMvc/`@WebMvcTest`/`@SpringBootTest` 기반 컨트롤러·IT 테스트가 없다**. 결과적으로 F-8 32건 중 **21건(TC-001·002·003·006·008·009·010·011·013·015·016·017·018·019·020·024·026·028·029·030·031)이 미커버**다. 미커버 항목에는 이번 검증에서 실제로 결함이 나온 지점(Tus-Resumable 부재 통과=F-ISSUE-83, Location 조립=F-ISSUE-82, CANCELLED HEAD=F-ISSUE-85)이 전부 포함된다.
- **재현/확인 경로**:
  ```bash
  grep -rln "PortalTus\|PortalVideoUpload\|portal/uploads/tus" backend/src/test
  # → PortalUploadPropertiesBindingTest / PortalUploadSweepIT / PortalUploadSweepTxServiceTest
  #    PortalVideoUploadServiceTest / V111PortalTusUploadMigrationTest   (컨트롤러 테스트 없음)
  grep -c "@DisplayName" backend/src/test/java/kr/co/cudo/authoring/portal/PortalVideoUploadServiceTest.java   # → 11
  ```
- **영향**: 품질/회귀. 헤더 계약이 코드 리팩터로 조용히 깨져도 baseline(4,755 tests) 이 전부 통과한다. 동시성(TC-020/027/028/031)·만료(TC-013)는 Testcontainers 없이는 검증 자체가 불가능한데 IT 가 없다.
- **수정 방향(제안)**: ①`PortalTusUploadControllerTest`(`@WebMvcTest` + `@WithMockUser` 또는 TokenClaims 주입)로 헤더 프로토콜 12건(TC-001·002·003·008·009·010·011·015) 고정. ②`PortalTusUploadIT`(Testcontainers PostgreSQL)로 재개 시퀀스·offset 불일치 409·만료 410·동시 PATCH 직렬화·완료 멱등(TC-013·016·018·019·020·022·027·029·030·031) 커버. ③F-ISSUE-83/85 수정 시 회귀 가드를 함께 추가. ⚠ 구현은 하지 않음.

---

## 부기 — 확증편향 반증에서 "문제 없음"으로 확인된 항목

| 반증 시도 | 결과 |
|-----------|------|
| 타 사용자 세션 하이재킹(HEAD/PATCH/DELETE) | 3경로 전부 403. `isOwnedBy` 가 HEAD 는 `getForOwner`, PATCH 는 `appendChunkTx`, DELETE 는 `cancel` 에서 **각각 독립 검사**(한 곳에 몰지 않음) |
| 세션 ID 추측 | `UUID.randomUUID()`(v4, SecureRandom). 미존재 uldId → 404, 잘못된 UUID 문자열 → 400 (존재 여부 오라클 없음) |
| 역할/채널 우회 | WORKER(2001)·REVIEWER(1001) INTERNAL 토큰 → POST/OPTIONS 모두 **403**, 무토큰 → **401**. `@PreAuthorize("hasRole('PORTAL_USER')")` + SecurityConfig 채널 격리 이중 방어 실동작 확인 |
| 확장자 우회 | `A.MP4`(대문자) 201 정상 / `a.webm`·`a.mkv`(`EXT_TO_MIME` 상수엔 존재) → **400**. allowlist(`properties.allowedExtensions()`)가 최종 게이트라 상수 잔재로는 우회 불가 |
| 매직바이트 polyglot | `ftyp` 만 위장한 파일(`corrupt.mp4`)은 매직바이트를 통과하지만 **ffprobe 2차 게이트에서 400**. 브랜드 allowlist(`VideoMagicByteValidator.ALLOWED_BRANDS`) + box size 상한(1MB)도 정적 확인 |
| 부분 쓰기 오염 | 413(청크 상한)·400(잔여 초과) 거부 후 파일 크기·DB offset 모두 불변. `TusChunkStore.writeChunkAtomically` 의 `truncateTo` 롤백 + `advanceOffset` 미호출 경로 확인 |
| 검증 실패 시 세션 고착 | `finalizeRejected` 가 `REQUIRES_NEW` 라 400 예외로 롤백되지 않음 — 실측 3케이스 모두 DB 가 CANCELLED 로 **커밋 확정**됨(IN_PROGRESS 고착 0) |
| 임의 파일 삭제(CWE-22) | `TusChunkStore.deleteQuietly(path, storageRoot)` 가 `startsWith(storageRoot)` 재검증. 저장 경로 자체가 사용자 입력 미도달이라 이중 방어 |
| 완료 이벤트 중복 | 완료 7세션 ↔ ULD 7행 ↔ 프레임 7행 (1:1:1). 조건부 UPDATE 게이트 유효 |
# F-portal 검증 결과 — part6 (F-9 · F-10 · F-11)

- 담당 범위: `## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)`(14건, TC-PORTALUP-060~073) +
  `## F-10. 상태 전이 · 정리 스윕 / F-11. 내부 파이프라인 분리`(13건, TC-PORTALUP-080~087, TC-PORTAL-090~094) = **27건**
- 스택: docker compose 기존 기동 상태 유지(klid-backend :18081→8080, klid-postgres, klid-mock-server :9400) — 신규 기동 없음
- 코드/설정/테스트 파일 수정 없음, 빌드/테스트 실행 없음(기존 baseline `docs/검증결과/2026-08-01/1차/_raw/test-baseline.md`: backend 4,755 tests 4,750 성공/0 실패/5 스킵 참조)
- 검증 대상 커밋: 워킹트리 `qa-0801` HEAD (git status clean)

## 방법

1. **실동작 최우선** — HS256 JWT 자체 서명(컨테이너 실효 `JWT_SECRET`, `sub=3001,channel=PORTAL,iss=klid-portal`→PORTAL_USER, `sub=1001,channel=INTERNAL`→REVIEWER, `GET /v1/me` 로 검증)으로 실제 TUS 업로드(`backend/src/test/resources/fixtures/tiny-video.mp4`, 실측 duration=0.04s/fps=25 — 컨테이너 내 실 ffprobe) → PATCH 청크 전송 → AFTER_COMMIT 비동기 추출 → DB(`ls_portal_uld`/`ls_portal_uld_frme`) + 파일시스템(`/app/storage/raw/portal/frames/{uldSn}`) 실측. 생성한 테스트 자산(uld_sn=58)은 검증 후 `DELETE /v1/portal/uploads/58` 로 정리, DB/실측 확인 완료(원복).
2. `portal.upload.frame-interval-sec` 시스템설정 경계값[1,600] 은 REVIEWER 토큰으로 실제 `PUT /v1/manage/configs/...` 왕복(601/0 거부 400, 600 허용 200) 후 **5로 원복 확인**.
3. 정적 대조 — file:line Read, 근거 드리프트 없음(전 라인 일치 확인).
4. 테스트 커버 — `PortalFrameExtractRunnerTest`·`PortalFrameExtractTxServiceTest`·`PortalUploadSweepJobTest`·`PortalUploadSweepTxServiceTest`·`PortalUploadSweepIT`·`LsPortalUldStateTransitionTest`·`entity/LsPortalUldTest`·`PortalUserLabelServiceTest` 등 전용 테스트 존재, 전건 baseline PASS(0 실패)에 포함.
5. F-11 분리는 실측: 업로드 후 `ls_data_raw` 에 해당 파일 참조 행 0건(`SELECT count(*) ... WHERE raw_file_path_nm LIKE '%qa-f9-test%'` → 0), `PortalVideoUploadedEvent` 리스너는 `PortalFrameExtractBridge` 유일(관제 `VideoIngestedEvent` 무관 — 코드 확인), 데이터마트 View DDL 전수에 `LS_PORTAL_*` 참조 0건(grep).

## 결과 표

| ID | 판정 | 근거확인 | 비고 |
|----|:--:|:--:|------|
| TC-PORTALUP-060 | PASS | [실동작] | `PortalVideoUploadedEvent`(AFTER_COMMIT)→`PortalFrameExtractBridge`→`runner.runAsync` 확인(`PortalFrameExtractBridge.java` 전문, `@TransactionalEventListener(phase=AFTER_COMMIT)`). 실 업로드 후 짧은 시간 내 DB `uld_stts_cd=READY`+프레임 파일 생성 실측(uld_sn=58) |
| TC-PORTALUP-061 | PASS | [실동작]+[정적] | `beginProcessing`=`transitionToProcessing`(조건부 UPDATE, WHERE `uld_stts_cd='UPLOADED'`) 0행→Optional.empty→러너 즉시 return. 단위테스트 `abortsWhenAssetDeletedAtEntry` PASS(baseline) |
| TC-PORTALUP-062 | PASS | [실동작] | DB 시드값 5 확인(`SELECT stng_value FROM ls_system_config WHERE stng_key='portal.upload.frame-interval-sec'`→`5`), 실 업로드 프레임 추출도 5초 간격 스냅샷 로직(`snapshotIntervalSec` :185-193) 경유 확인. V109:101 문구 일치 |
| TC-PORTALUP-063 | PASS | [정적] | `snapshotIntervalSec()`(:185-193) catch(RuntimeException)→`DEFAULT_INTERVAL_SEC=5` 폴백 코드 확인. `SystemConfigService.getInt`가 설정 row 부재 시 `loadOrThrow`로 예외 던짐(코드 확인) — 폴백 분기는 도달 가능하나 이 분기 자체를 직접 때리는 전용 단위테스트는 없음(운영 데이터 삭제 리스크로 실동작 재현은 보류) |
| TC-PORTALUP-064 | PASS | [실동작] | `PUT /v1/manage/configs/portal.upload.frame-interval-sec` 실 왕복: `{"value":"601"}`→400, `{"value":"0"}`→400, `{"value":"600"}`→200. `ConfigKeys.java:71` `[1,600]` 범위와 일치. 검증 후 `5`로 원복 + DB 재확인 완료 |
| TC-PORTALUP-065 | PASS | [정적]+[테스트] | `computeFrameNumbers`(:147-183) 후보>cap 시 균등 재샘플링 로직 확인. 단위테스트 `frameCountCappedByUniformSampling`(duration 1000s×30fps, interval 1s, cap 10 → 결과 ≤10·단조증가) baseline PASS. 실효 `maxFrames` 기본값 2000(env `PORTAL_MAX_FRAMES` 미설정 → application.yml 기본값, 클라이언트가 요청으로 override 불가 — 서버 config 단일값) |
| TC-PORTALUP-066 | PASS | [정적] | `if (candidates.size() <= cap) return candidates;`(:161) — 정확히 cap(2000)일 때 재샘플링 없이 그대로 반환(≤2000 보장, off-by-one 없음) |
| TC-PORTALUP-067 | PASS | [실동작] | 실 업로드 tiny-video.mp4(duration 0.04s < interval 5s) → DB `frme_cnt=1`, `ls_portal_uld_frme` 1행(`frme_no=0`) 실측. `computeFrameNumbers` 최소 1프레임 보장 코드(:158-160)와 일치 |
| TC-PORTALUP-068 | PASS | [정적] | `double fps = probe.fps() > 0 ? probe.fps() : DEFAULT_FPS;`(:102), `computeFrameNumbers` 내부도 동일 폴백(:148). 실 업로드는 ffprobe가 fps=25 를 정상 반환해 폴백 미경유(DB `fps=25` 실측) — 폴백 자체는 로직 확인으로 PASS, 0/미상 fps 실촉발 사례는 재현 안 함 |
| TC-PORTALUP-069 | PASS | [실동작] | 업로드 후 DB `uld_stts_cd=READY, vdo_len_sec=0.04, fps=25, frme_cnt=1` 원자 커밋 확인(`completeReady`:126-132, `transitionToReady` 조건부 UPDATE). 프레임 파일 `/app/storage/raw/portal/frames/58/frame-0.jpg` 실재(222 bytes, 컨테이너 내 실 ffmpeg 산출물 — self-fill 아님) |
| TC-PORTALUP-070 | PASS | [테스트]+[정적] | catch(Exception) 블록(:133-138) `cleanup`+`markFailed` 호출 확인. 단위테스트 `failureMarksFailedAndCleansPartialFiles`(writeFrameByNumber IOException 강제 → `markFailed` 호출 + frames 디렉토리 미잔존) baseline PASS. 실 ffmpeg 실패 재현(손상 영상)은 시간 예산상 보류, 코드+테스트 근거로 충분 판단 |
| TC-PORTALUP-071 | PASS | [테스트]+[정적] | `touchProcessing` 하트비트(:115) — `PROGRESS_CHECK_EVERY=50` 프레임마다 조건부 UPDATE 0행이면 abort+cleanup. 단위테스트 `abortsAndCleansWhenHeartbeatFailsMidExtract`(2번째 하트비트 false→중단, 부분파일 정리, `completeReady`/`markFailed` 모두 미호출) baseline PASS |
| TC-PORTALUP-072 | PASS | [정적] | `resolveSafeFramesDir`(:195-201) `storageRoot.resolve(...).normalize()` 후 `!resolved.startsWith(storageRoot)` 시 예외. 단 **비고**: `uldSn` 파라미터가 `Long` 타입(DB PK)이라 `../` 등 문자열 조작이 애초에 불가능한 위협모델 — 가드는 실제 동작하나 "조작 uldSn으로 탈출"은 타입 안전성상 도달 불가능한 경로(방어심층, 결함 아님) |
| TC-PORTALUP-073 | PASS | [정적] | `runAsync`(:71-82) try/catch(Exception) 로 `extract()` 예외 흡수 후 `log.warn`만 수행, 재전파 없음. `extract()` 자체도 내부 catch(:133-138)로 대부분 흡수하므로 이 상위 catch 는 안전망 |
| TC-PORTALUP-080 | PASS | [정적] | `LsPortalUld`(:114-138) `markProcessing`/`markReady`/`markFailed` 3개 비즈니스 메서드만 상태 변경, `@Setter` 미부여(`@Getter`만) 확인. `LsPortalUldStateTransitionTest`·`entity/LsPortalUldTest` baseline PASS |
| TC-PORTALUP-081 | PASS | [실동작]+[정적] | `PortalUploadService.java:128` `uld.markReady(null, null, 1);` 확인. 실동작: `POST /v1/portal/uploads/images` 로 이미지 업로드 시 DB 즉시 `uld_stts_cd=READY`(기존 데이터 uld_sn=54~56 실측, 전부 즉시 READY) |
| TC-PORTALUP-082 | PASS | [정적]+[테스트] | `cleanupExpiredSessions()`(:71-77) `txService.claimExpiredSessions()`(조건부 DELETE) → 반환 경로 `TusChunkStore.deleteQuietly`. `PortalUploadSweepJobTest`(`스윕잡이_만료_세션과_임시파일_정리`) baseline PASS |
| TC-PORTALUP-083 | PASS | [정적]+[테스트] | `failStuckUploads()`(:83-89) `txService.failStuckUploads(stuckTimeoutMinutes)`(조건부 UPDATE) → `cleanupFrameDir`. 기본 30분(`application.yml` `stuck-timeout-minutes:30`, 컨테이너 env override 없음 확인). `PortalUploadSweepJobTest`(`스윕잡이_고착_자산의_부분_프레임_정리`) baseline PASS |
| TC-PORTALUP-084 | PASS | [정적] | `cleanupFrameDir`(:92-114) `!framesDir.startsWith(storageRoot)` 가드 후 skip+WARN. TC-PORTALUP-072와 동일하게 `uldSn:Long` 이라 실 우회 불가(방어심층) |
| TC-PORTALUP-085 | PASS | [정적] | 생성자(:39-44) `PortalUploadSweepTxService` 주입, `cleanupExpiredSessions`(:71-72)·`failStuckUploads`(:83-84) 모두 `txService.*` 위임 — 잡 클래스 자체엔 `@Transactional` 없음(self-invocation 프록시 우회 방지, 클래스 Javadoc:27-28 명시) |
| TC-PORTALUP-086 (신규) | PASS | [정적]+[테스트] | `LsPortalTusUploadRepository.deleteExpiredInProgress`(:66-68) `DELETE ... WHERE uld_id=:uldId AND stts_cd='IN_PROGRESS'` 조건부 삭제 — 두 노드 동시 실행 시 한쪽만 1행. 단위테스트 `PortalUploadSweepTxServiceTest.returnsFilePathsForClaimedSessionsOnly`(claimed=1행/lost=0행 시뮬레이션) baseline PASS |
| TC-PORTALUP-087 (신규) | PASS | [정적]+[테스트] | `LsPortalUldRepository.failIfInStatus`(:76-82) `WHERE uld_sn=:uldSn AND uld_stts_cd IN :statuses` 조건부 UPDATE. 단위테스트 `returnsUldSnForFailedTransitionsOnly`(42=1행/7=0행 시뮬레이션) baseline PASS |
| TC-PORTAL-090 | PASS | [정적] | `PortalUploadLabelService`(:78-82) 생성자 필드 = `uldRepository`/`frmeRepository`/`lblRepository`(포털 3종)+`properties`+`objectMapper` 뿐. 내부 도메인 Repository/Service 주입 0건(단, 정적 유틸 `FrameImageService.openNoFollow` 호출 1건 — 안전 open 헬퍼 정적 호출이며 빈 주입·데이터 접근 아님, 위반 아님) |
| TC-PORTAL-091 | PASS | [실동작]+[정적] | `PortalVideoUploadService.java:28-31` Javadoc "관제 비식별 파이프라인 미연결" + `PortalVideoUploadTxService`(:154) `eventPublisher.publishEvent(new PortalVideoUploadedEvent(...))` 만 발행(`VideoIngestedEvent` 미발행, grep 0건). 실측: 업로드 후 `ls_data_raw` 에 참조 행 0건(`SELECT count(*) FROM ls_data_raw WHERE raw_file_path_nm LIKE '%qa-f9-test%'` → 0), `max(raw_sn)` 불변(906) |
| TC-PORTAL-092 | PASS | [정적]+[테스트] | `PortalLabelService.java:196-223` `saveUserLabel` → `userLabelRepository.save(LsPortalUserLabel.create(...))` — `LS_DATA_LBL` 미접촉. `PortalUserLabelServiceTest.V2_사용자_작업_데이터_별도_적재_원본_미수정` baseline PASS |
| TC-PORTAL-093 | PASS | [정적] | `PortalFrameExtractRunner.java:25-28` Javadoc "portalExtractExecutor 별도 스레드" + `@Async("portalExtractExecutor")`(:71). `AsyncConfig.java:210-220` 별도 `ThreadPoolTaskExecutor`(core=1,max=2,queue=20, `CallerRunsPolicy`) 빈 확인 — 관제 `batchAsyncExecutor` 와 물리적으로 분리된 빈 |
| TC-PORTAL-094 | PASS | [정적] | `PortalUploadLabelService.java:44-46` Javadoc "AC6: 데이터마트/내부 도메인 미참조". 실측: `db/migration/*.sql` 전수 grep 결과 `V_COMPLETED_*` 등 View DDL 어디에도 `LS_PORTAL_*` 테이블 참조 0건(portal 문자열이 등장하는 파일 2건은 코멘트/다른 마이그레이션 설명일 뿐 View 정의 아님) |

## 집계

| 판정 | 건수 |
|------|:--:|
| PASS | 27 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **27** |

## 이슈

없음 (F-ISSUE-101~120 범위 미사용 — 결함/부분충족/미확정 발견 0건).

## 근거 드리프트

없음 — F-9/F-10/F-11 전 케이스의 `file:line` 근거가 실제 코드와 라인 단위로 일치 확인됨(2026-07-30 최신화 표기 그대로 유효).

## 참고 — 실동작 검증에 사용한 원자료

- JWT: PORTAL_USER(`sub=3001,channel=PORTAL,iss=klid-portal`), REVIEWER(`sub=1001,channel=INTERNAL,iss=klid-auth`) — 컨테이너 실효 `JWT_SECRET`(HS256) 직접 서명, `GET /v1/me` 로 역할 확인
- 업로드 자산: `backend/src/test/resources/fixtures/tiny-video.mp4`(1546 bytes) — TUS `POST`(세션생성 201)→`PATCH`(청크 204, offset=1546=length) 완료 확인
- 생성/삭제된 테스트 행: `ls_portal_uld.uld_sn=58`(READY, 검증 후 `DELETE /v1/portal/uploads/58` 로 제거 완료, DB 0건 재확인) — 프로덕션 코드/설정/테스트 파일 변경 없음, 업로드 자산 데이터만 생성 후 원복
- 시스템설정 `portal.upload.frame-interval-sec`: 601/0/600 순 왕복 후 **5로 원복**(DB 재확인 완료)
# 2차 재검증 (targeted) — A/C 클러스터

- 일시: 2026-08-02
- 대상 워크트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801` (HEAD `56d30478` + **미커밋 워킹트리**)
- 검증자: qa 에이전트 (읽기 전용 — 코드/설정/테스트 무수정, gradle 미실행)

## 0. 환경 판정 — 실동작 검증 BLOCKED

```
$ docker exec klid-postgres psql -U klid_user -d klid_system \
    -c "SELECT MAX(version) FROM flyway_schema_history;"
 max
-----
 99
```

컨테이너 스택은 **V99** 로, 지시된 기준선 **V160 미만**이다. 즉 이번 5-Phase 버그수정이 반영되지 않은
구 이미지가 기동 중이며, curl 실동작 재현은 **BLOCKED (사유: 워크트리 코드가 컨테이너에 미반영)**.

또한 수정 코드는 `klid-label` 본체가 아니라 **이 워크트리(`qa-0801`)의 미커밋 워킹트리**에 존재한다
(`klid-label` 은 `56d30478` clean, 미추적 docs 디렉터리 1개뿐). 아래 판정은 **정적 대조 + 기존 테스트
실행 결과(XML)** 를 근거로 한다.

### 테스트 실행 증거 (기존 산출물 — 재실행 안 함)

`backend/build/test-results/test/` (575 XML, 타임스탬프 **Aug 2 15:55:55 2026**)

| 테스트 클래스 | tests | skipped | failures | errors |
|---|---:|---:|---:|---:|
| `JwtAuthenticationFilterTest` | 9 | 0 | 0 | 0 |
| `SortAllowlistTest` | 24 | 0 | 0 | 0 |
| `LogMaskingPatternsTest` | 27 | 0 | 0 | 0 |
| `LocalLogMaskingIT` | 3 | 0 | 0 | 0 |
| `AiMockMetaTest` | 5 | 0 | 0 | 0 |
| `Sam2TrackServiceTest` | 17 | 0 | 0 | 0 |
| `ListApiBackwardCompatibilityIT` | 16 | 0 | 0 | 0 |

---

## 1. TC-AUTH-005 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`docs/test-cases/A-auth-common.md:23`)의 TC-AUTH-005 는 "만료 토큰(exp **과거**) 거부"다.
> 이번 재검증 지시는 그 인접 결함인 **"exp 클레임 **부재** 토큰"**(A-ISSUE-01)을 대상으로 한다.
> 아래는 **양쪽 모두** 판정한다.

### 근거 — `common/security/JwtAuthenticationFilter.java:72-84`

```java
// A-ISSUE-01 (HIGH, CWE-613 Insufficient Session Expiration) — exp 클레임 필수화.
if (body.getExpiration() == null) {
    log.debug("[Auth] rejected token without exp claim");
    SecurityContextHolder.clearContext();
    chain.doFilter(request, response);
    return;
}
```

- issuer 게이트(L65-70)와 **동일한 fail-closed 패턴** — `SecurityContext` 를 비우고 필터체인을 계속
  진행시키므로 이후 인가 단계에서 401 로 종결된다.
- 주석이 근본원인을 정확히 기술: jjwt 는 `exp` 가 **없으면 만료 검사를 통째로 건너뛴다**. 저작도구는
  토큰을 발급하지도 폐기(revocation)하지도 않으므로 수명 상한이 `exp` 뿐이고, 발급 주체가 외부
  (관제/포털)라 "항상 exp 를 넣는다"는 계약을 검증 없이 신뢰할 수 없다.
- 게이트가 `channel` 파싱(L86) **앞**에 있어 INTERNAL·PORTAL 양 채널 모두에 적용된다.
- 후속 `TokenClaims` 생성(L102-107)의 `body.getExpiration().getTime()` NPE 도 함께 제거됨.

### 근거 — `JwtAuthenticationFilterTest` (9/9 통과)

| 테스트 | 대상 |
|---|---|
| `exp_클레임이_없는_JWT는_401_반환` | 수정 대상(A-ISSUE-01) 직접 검증 |
| `exp_없는_PORTAL_채널_토큰도_401` | PORTAL 채널 우회 차단 (채널별 분기 누락 방지) |
| `만료된_JWT로_요청시_401_반환` | 카탈로그 원문 TC-AUTH-005 회귀 |

**판정: PASS** — 수정 반영 확인, 신규 테스트 2건(exp 부재 INTERNAL/PORTAL) + 기존 회귀 1건 전부 통과.
실동작(curl) 재현은 BLOCKED.

---

## 2. TC-SORT-006 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`A-auth-common.md:230`)의 TC-SORT-006 은 "컨트롤러 allowlist 사본 금지 —
> `SortAllowlist.VIDEO` 단일 진실원"이다. 이번 지시는 인접 결함
> **A-ISSUE-61 (`/v1/deident-reports` 등 미배선 엔드포인트 → 500)** 이다. 양쪽 모두 판정한다.

### 2-1. A-ISSUE-61 — 미배선 엔드포인트 3종 배선 완료

`common/util/SortAllowlist.java` 에 allowlist 3종 신설:

| 상수 | 엔드포인트 | 모드 |
|---|---|---|
| `DEIDENT_REPORT` (L159-168) | `GET /v1/deident-reports` | strict → **400** |
| `PORTAL_UPLOAD` (L180-187) | `GET /v1/portal/uploads` | strict → **400** |
| `PORTAL_UPLOAD_FRAME` (L198-203) | `GET /v1/portal/uploads/{uldSn}/frames` | strict → **400** |

컨트롤러 배선 실측:
- `label/controller/DeidentReportController.java:91-93`
  → `SortAllowlist.apply(pageable, SortAllowlist.DEIDENT_REPORT, DEFAULT_REPORT_SORT)`
- `portal/controller/PortalUploadController.java:165,173`
  → `SortAllowlist.apply(..., PORTAL_UPLOAD | PORTAL_UPLOAD_FRAME, ...)`

`resolve()`(strict, L226-249) 는 미등록 키를 `CustomException(ErrorCode.INVALID_INPUT)` = **400** 으로
거부한다. 즉 기존 `PropertyReferenceException` → **500 + 내부 엔티티명/JPQL 로그 노출(CWE-209)** 경로가
차단됐다. 개수 상한(`maxOrders`, L310-312 — allowlist 고유 엔티티 필드 수에서 파생)도 함께 적용되어
CWE-770(쿼리 플랜 캐시 오염)도 닫혔다.

부수 보안 결정도 확인:
- `DEIDENT_REPORT` 에서 **신고 사유(`rsn`, 자유서술)·신고자(`reporterNo`) 의도적 제외** — 정렬 순서로
  본문 접두 추론 / 신고 주체 열거를 막음.
- `PORTAL_UPLOAD` 에서 **`orgnlFileNm`(사용자 원본 파일명) 제외** — PII 가 실릴 수 있음.
- 예외 메시지에 입력값·내부 필드명 미포함(L237, L242 — CWE-209).
- **기본 정렬키 포함 검증**: `reportDt`(`@PageableDefault` 값)가 allowlist 에 있어 파라미터 없는
  기존 호출이 400 이 되지 않는다.

### 2-2. 카탈로그 원문 (SortFieldMapper 단일 진실원)

`common/web/SortFieldMapper.java:59,69` 가 `SortAllowlist.resolveLenient(...)` 에 위임하고
allowlist 사본을 보유하지 않음을 확인 — 원문 TC 도 유지.

### 근거 — 테스트

`SortAllowlistTest` (24/24 통과) 중 관련:
- `신규_allowlist_3종_모두_미등록키를_400으로_거부한다`
- `신규_allowlist_예외메시지에_입력값과_내부필드명이_실리지_않는다`
- `신고목록_allowlist는_기본정렬키_reportDt를_반드시_포함한다`
- `신고목록_allowlist는_자유서술_사유와_신고자를_정렬축으로_열지_않는다`
- `포털_업로드_allowlist는_원본파일명을_정렬축으로_열지_않는다`
- `포털_프레임_allowlist는_frmeNo_별칭을_같은_엔티티필드로_매핑한다`

`ListApiBackwardCompatibilityIT` (16/16 통과) — strict/lenient 이원 정책 회귀 가드가 살아 있음
(`동일한_미등록_정렬키라도_작업목록은_400_검수목록은_200_으로_정책이_의도적으로_다르다`).
CLAUDE.md 의 "두 정책을 일관성을 이유로 통일하지 말 것" 구속이 유지됨을 확인.

**판정: PASS** — 500 경로 제거 확인(400 으로 전환), 하위호환(기본 정렬키·lenient 엔드포인트) 무파손.
실동작(curl) 재현은 BLOCKED.

---

## 3. TC-LOG-001 — **PASS** (정적 + 단위) / 실동작 BLOCKED

> ⚠ 카탈로그 원문(`A-auth-common.md:220`)의 TC-LOG-001 은 "LogSanitizer — C0/DEL/C1 제어문자 제거"다.
> 이번 지시는 인접 결함 **A-ISSUE-62 (전화번호/이메일/JSON형/bare JWT 평문 기록)** 이다. 양쪽 판정.

### 3-1. A-ISSUE-62 — 마스킹 규칙 단일 원천화 + 신규 규칙 5종

신설 `common/logging/LogMaskingPatterns.java` 가 **단일 원천**이고, 두 마스커가 위임한다:
- `MaskingPatternLayout.java:24` → `LogMaskingPatterns.mask(input)` (local, PatternLayout)
- `MaskingJsonValueMasker.java:30` → `LogMaskingPatterns.mask(input)` (dev/stg/prd, JSON 인코더)
- `logback-spring.xml:15, 41-42` 로 양 프로파일 배선 확인 → **드리프트 구조적 제거**

`maskWithin()` (L307-318) 적용 순서 실측 — 지시된 4종이 모두 존재:

| # | 규칙 | 지시 대상 | 위치 |
|---|---|---|---|
| 1 | `JSON_CREDENTIAL_PATTERN` | **JSON형** `"password":"x"` | L125-129 |
| 2 | `HEADER_COLON_PATTERN` | `X-Access-Token` 등 Authorization 외 토큰 헤더 | L135-138 |
| 3 | `COOKIE_HEADER_PATTERN` | 쿠키 세션토큰 (줄 끝까지) | L147-149 |
| 4 | `KV_PATTERN` | 기존 `key=value` (유지) | L163-167 |
| 5 | `BEARER_PATTERN` | `Bearer <값>` | L170-172 |
| 6 | `BARE_JWT_PATTERN` | **bare JWT 전문** `eyJ….….…` | L185-187 |
| 7 | `RRN_PATTERN` | 주민번호형 13자리 | L193-195 |
| 8 | `PHONE_PATTERN` | **전화번호** (`010-****-5678`) | L201-203 |
| 9 | `EMAIL_PATTERN` | **이메일** (로컬파트 1글자만 잔존) | L221-224 |

전화번호는 `rules/security.md` 표기 규약(`010-****-1234`)대로 가운데 자리만 가린다(L315).
이메일은 도메인 보존 + 로컬파트 마스킹(L316) — 진단 가치와 PII 보호 균형.

### 3-2. 방어적 하드닝(동반 확인 — 회귀 위험 지점)

새 정규식이 hot path(모든 로그 문자열 + 스택트레이스, 동기 어펜더)에 들어가므로 DoS 방어가
동반됐고, 각각 **실측 수치 기반 회귀 테스트**가 붙어 있다:
- **ReDoS(CWE-1333/400)** — 경계 lookbehind + possessive + `{0,64}` 반복 상한(L88, L110, L116)
  + `MAX_MASK_LENGTH` 64KB 상한(L75).
- **스택 고갈(CWE-674)** — 이메일 도메인 라벨 반복에 `{1,10}` 명시 상한(L94, L223).
  무제한 `+` 는 Java 가 재귀 Loop 노드로 컴파일해 8KB 입력에서 `StackOverflowError` → 로깅 스레드
  사망(실측 재현). `Error` 는 Logback `catch (Exception)` 에 안 걸림.
- **절삭 누출** — `safeCutIndex`(L271-287)가 공백 경계 + 따옴표 균형으로 되감아
  `{"password":"SE...[truncated]` 형태의 반토막 누출을 차단(fail-secure: 초과분은 **폐기**).

### 3-3. 카탈로그 원문 (LogSanitizer 제어문자)

`common/util/LogSanitizer.java:96-99` — `Character.getType()` 기반 `CONTROL`/`LINE_SEPARATOR`/
`PARAGRAPH_SEPARATOR` 제거(치환 아님) 유지 확인.

### 근거 — 테스트

`LogMaskingPatternsTest` (27/27 통과) 중 지시 대상 직접 검증:
- `A_ISSUE_62_실동작_누출_케이스_6종_전부_평문_미잔존` ← **핵심 회귀 가드**
- `전화번호_가운데자리_마스킹` / `이메일_로컬파트_마스킹` / `JSON_형태_자격증명_마스킹`
- `접두어_없는_JWT_전문_마스킹` / `Authorization_이외_토큰_헤더_XAccessToken_마스킹`
- `쿠키_헤더는_줄_끝까지_마스킹되어_세션토큰이_남지_않음`
- 하드닝 회귀 4건(ReDoS 1~3차, StackOverflow) + 절삭 누출 2건

`LocalLogMaskingIT` (3/3 통과) — local 프로파일 실배선 검증.

**판정: PASS** — 4종(전화번호/이메일/JSON형/bare JWT) 모두 마스킹 규칙 존재 + 배선 + 회귀 테스트 통과.
실동작(로그 파일 확인) 재현은 BLOCKED.

---

## 4. SAM2 Track mock 차단 (TC-SAM2-14/24/26 인접) — **PASS**

> 카탈로그(`docs/test-cases/C-marking-labeling.md:238,248,250`)의 TC-SAM2-14/24/26 은 정상경로
> (POLYGON / BBOX 외접박스 / shape 기본값)이고 **mock 전용 케이스는 카탈로그에 없었다**.
> 이번 지시대로 인접 신규 케이스로 판정한다 (C-ISSUE-81).

### 4-1. 판정 규약 — 부정 신호가 아니라 **긍정 증명**

신설 `common/client/dto/AiMockMeta.java:35-37`:

```java
public static boolean untrusted(boolean mock, String source) {
    return mock || !SOURCE_MODEL.equals(source);   // SOURCE_MODEL = "model"
}
```

지시의 조건(`mock=true` **또는** `source≠"model"`)과 **정확히 일치**한다. 근본원인 기술도 정확:
DTO 의 `mock` 이 primitive `boolean` 이라 **필드가 생략된 JSON** 을 받으면 Jackson 이 `false` 로 채워
`mock()` 만 보는 코드는 fail-open 이 된다. `source=null`·오타·미래 값도 전부 불신(fail-closed).

`Sam2TrackResponse.java:48-50` 이 `untrusted()` 를 노출하고, 호출부가 이를 사용:
`label/service/Sam2TrackService.java:134` → `if (aiRes.untrusted()) { ... }` (해당 프레임 결과 제외).
서비스 주석(L46-49)이 **왜 위험한지** 기록: ai-server `_mock_track` 은 **시드 폴리곤을 그대로 복사**하고
`score=0.9` 를 부여하므로, 거르지 않으면 "N 프레임 추적"이 조용히 "시드 폴리곤 N개 복제"가 되고
점수가 높아 FE 저신뢰 분기에도 안 걸린다.

### 4-2. 안내 메시지

`Sam2TrackService.java:89` 가 `anyMock` 을 누적하고, L170 이
`Sam2TrackOutcome.of(new Sam2TrackResponseDto(tracked), anyMock)` 로 반환해 상위에서 안내 메시지로
전달된다(SAM2 세그·YOLO 오토라벨과 동일 규약). 로그는 `LogSanitizer` 로 정제(L136-138, L168-169 —
`trackId` 가 클라이언트 원문이라 CWE-117 대응).

### 근거 — 테스트

`AiMockMetaTest` (5/5 통과) — 판정 규약 + 3 DTO(track/segment/yolo) 의 필드 생략 PoC + 실모델 회귀.
`Sam2TrackServiceTest` (17/17 통과) 중:
- `추적_ai가_mock응답이면_해당프레임을_결과에서_제외하고_전량안내를_준다`
- `추적_일부프레임만_mock이면_실결과는_유지하고_부분안내를_준다`
- `추적_ai가_mock메타를_생략하면_신뢰하지_않고_프레임을_제외한다` ← **fail-open 변종 가드**
- `추적_실모델(mock아님)_응답은_그대로_자동적용되고_안내가_없다` ← 과차단 회귀 가드
- 정상경로 회귀 3건 유지: `추적_shape가_폴리곤이면…`(TC-SAM2-14) /
  `추적_shape가_박스면_폴리곤_외접bbox로…`(TC-SAM2-24) / `추적_shape미지정이면_POLYGON_기본`(TC-SAM2-26)

**판정: PASS** — mock 및 메타 생략 응답 모두 자동적용 차단 + 안내 전달, 실모델 경로 과차단 없음.

---

## 5. 판정 요약

| TC-ID | 대상 | 판정 | 근거 |
|---|---|:---:|---|
| TC-AUTH-005 | exp 클레임 부재 JWT → 401 (A-ISSUE-01) | **PASS** | `JwtAuthenticationFilter.java:79-84` fail-closed 게이트 + `JwtAuthenticationFilterTest` 9/9 (exp 부재 INTERNAL/PORTAL 2건 신규) |
| TC-AUTH-005 (원문) | 만료 토큰(exp 과거) 거부 | **PASS** | `만료된_JWT로_요청시_401_반환` 통과 |
| TC-SORT-006 | 미등록 정렬키 500 → 4xx (A-ISSUE-61) | **PASS** | `SortAllowlist` allowlist 3종 신설 + 컨트롤러 3곳 strict 배선 → 400. `SortAllowlistTest` 24/24, `ListApiBackwardCompatibilityIT` 16/16 |
| TC-SORT-006 (원문) | SortAllowlist.VIDEO 단일 진실원 | **PASS** | `SortFieldMapper.java:59,69` 위임, 사본 없음 |
| TC-LOG-001 | 전화번호/이메일/JSON형/bare JWT 마스킹 (A-ISSUE-62) | **PASS** | `LogMaskingPatterns` 단일 원천 + 규칙 9종 + 양 프로파일 배선. `LogMaskingPatternsTest` 27/27, `LocalLogMaskingIT` 3/3 |
| TC-LOG-001 (원문) | LogSanitizer 제어문자 제거 | **PASS** | `LogSanitizer.java:96-99` 유지 |
| TC-SAM2 mock (14/24/26 인접) | mock/`source≠model` 자동적용 차단 + 안내 | **PASS** | `AiMockMeta.untrusted()` 긍정증명 규약 + `Sam2TrackService.java:134` 배선. `AiMockMetaTest` 5/5, `Sam2TrackServiceTest` 17/17 |
| **(전 항목 공통)** | **스택 실동작(curl) 재현** | **BLOCKED** | 컨테이너 flyway V99 < V160 — 워크트리 코드가 컨테이너에 미반영 |

**정적 대조 + 단위/통합 테스트 기준 7/7 PASS. 실동작 검증은 전 항목 BLOCKED.**

## 6. 후속 권고 (이번 재검증 범위 밖)

1. **실동작 재검증 필요** — 수정분이 커밋·이미지 반영(V160+)된 뒤 4개 TC 를 curl 로 재확인해야
   BLOCKED 가 해소된다. 특히 TC-LOG-001 은 실제 로그 파일 관측이 정적 대조를 대체하지 않는다
   (인코더 배선은 확인했으나 런타임 어펜더 조합은 미관측).
2. **테스트 카탈로그 갱신 필요** — 이번 수정 4건 중 3건(exp 부재 / A-ISSUE-61 / A-ISSUE-62)이
   기존 TC-ID 의 **인접 결함**이라 카탈로그에 전용 행이 없다. SAM2 mock 차단도 마찬가지다.
   `docs/test-cases/A-auth-common.md`·`C-marking-labeling.md` 에 신규 TC 행 추가 권고.
3. **정보성** — 수정 코드가 `qa-0801` 워크트리에 미커밋 상태다. 커밋 전 워크트리 유실 위험.
# 2차 재검증 — B / D+E 클러스터 타깃 (1차 CRITICAL·HIGH 수정 재검증)

- 검증일: 2026-08-02
- 대상 워킹트리: `/Users/chanki/Documents/workspace/klid-label-worktrees/qa-0801` (branch `qa-0801`, HEAD `56d30478`)
- 검증 방식: **정적 코드 대조 + 기존 테스트 결과 증거(XML) 참조** (테스트/빌드 직접 실행 없음, 코드 수정 없음)

## 0. 환경 사실관계 (BLOCKED 판정 근거)

| 항목 | 실측 |
|---|---|
| docker `klid-postgres` 최신 적용 마이그레이션 | **V158** (`installed_rank=153`, 2026-08-01 23:10) |
| 워킹트리 마이그레이션 최신 | **V160** (`V159`·`V160` 은 **untracked 신규 파일**, 미커밋) |
| docker `v_completed_video` 정의 | 구 정의 — `ex.export_stts_cd::text = 'SUCCEEDED'::text` (PARTIAL 미노출) |
| 수정 코드 커밋 상태 | 전부 **워킹트리 미커밋** (`git status` 79건 변경 — 5-Phase 수정분) |

→ **docker 이미지/DB는 수정 미반영 상태**다. 따라서 실행 중인 컨테이너 대상 실동작 검증(HTTP 요청·DB 뷰 조회)은 **BLOCKED**.
다만 아래 "실행 증거" 항목의 테스트 결과 XML 이 **2026-08-02 15:55 타임스탬프**로 574건 존재하며,
Testcontainers 기반 IT(`DatamartViewSlimIT` = `@SpringBootTest` + PostgresContainer, Flyway 전량 적용)가
**V160 이 실제 적용된 실 PostgreSQL 에서** 통과한 기록이 있어 뷰 축은 **실동작 등가 검증**으로 인정한다.

### 실행 증거 (build/test-results/test — 전부 failures=0 errors=0, mtime 2026-08-02 15:55)

| 테스트 클래스 | tests | 결과 |
|---|---:|---|
| `MarkingSkipTxServiceTest` | 4 | PASS |
| `MarkingBatchBridgeTest` | 20 | PASS |
| `BatchReprocessClaimConcurrencyIT` | 3 | PASS |
| `VideoStreamServiceTest` | 44 | PASS |
| `DatasetExportNotifyGateTest` | 8 | PASS |
| `ControlNotifyWebClientAuthHeaderTest` | 4 | PASS |
| `AsyncDatasetExportRunnerTest` | 21 | PASS |
| `DatamartViewSlimIT` | 17 | PASS |
| `FileServingLinkFollowGuardTest` | 1 | PASS |

---

## 1. TC-BATCH-057/062 인접 — 마킹 배치 skip 시 영구 고착 (B-ISSUE-41)

**판정: PASS**

### 정적 대조

- `marking/entity/LsMarking.java`
  - `STATUS_SKIPPED = "SKIPPED"` 신설 (종결 상태). `VLM_FAILED` 재사용을 명시적으로 배제(재위탁 대상 오독 방지) — 판단 타당.
  - `markSkipped()` 전이 메서드 추가. **`PENDING` 한정 가드** — 그 외 상태면 `false` no-op → 진행 중 `VLM_REQUESTED` 사이클/종결 사실을 덮지 않음.
  - `ACTIVE_STATUSES = List.of(STATUS_PENDING, STATUS_VLM_REQUESTED)` 불변 → `SKIPPED` 는 자동으로 활성 집합에서 제외.
- `marking/service/MarkingSkipTxService.java` (신규)
  - `@Transactional(controlTransactionManager, REQUIRES_NEW)` 별도 빈. AFTER_COMMIT 컨텍스트(활성 tx 없음)에서 dirty checking 이 안 되고 자기호출은 프록시 우회라는 점을 정확히 회피.
  - `markingSn == null` / 행 부재 / 비-PENDING → 조용한 no-op. 종결 실패가 마킹 API 201 응답에 영향 없음.
- `marking/listener/MarkingBatchBridge.java`
  - **skip 분기 4종 전부** `skip(rawSn, markingSn, reason)` 경유로 통일:
    `REASON_VIDEO_NOT_FOUND` / `REASON_STAGE_ALREADY_RUN` / `REASON_NOT_DEIDENTIFIED` / `REASON_ALREADY_CLAIMED`.
    누락 분기 없음(diff 상 구 `MarkingBatchTriggerReport.skipped(...)` 직접 호출은 전부 치환됨).
  - `MarkingCompletedEvent(Long rawSn, Long markingSn)` — 이벤트에 `markingSn` 이 실재하므로 종결 대상 식별 가능.
- 정합성: `V142` 부분 유니크 인덱스 술어 `WHERE STTS_CD IN ('PENDING','VLM_REQUESTED')` 와 `ACTIVE_STATUSES` 가 **정확히 일치** → `SKIPPED` 종결 시 인덱스 활성 집합에서 빠져 재마킹 409 해소.
- `V159__terminate_orphan_skipped_markings.sql` — 기존 고아 `PENDING` 마킹을 검수소유 상태(`PENDING/IN_REVIEW/APPROVED/REJECTED`) 영상에 한해 `SKIPPED` 로 백필. 기존 고착 데이터 회수 경로 존재.

### 테스트 커버리지

- `MarkingBatchBridgeTest` — skip 4종 전부 종결 확인 + **"정상 트리거시에는 마킹을 종결하지 않는다"** 과잉차단 회귀 가드 포함(20건 PASS).
- `MarkingSkipTxServiceTest` — PENDING 종결 / VLM_REQUESTED 미덮음 / 종결상태 미역행 / null·행부재 no-op (4건 PASS).

### 잔여 리스크

- `V159` 는 docker DB **미적용**(V158까지). 운영/개발 DB에 기존 고아 마킹이 남아 있으면 배포 시점까지 409 잠금 지속. 배포 시 마이그레이션 적용으로 해소 예정.

---

## 2. TC-BATCH-153/154 — 재처리 원자 클레임 (B-ISSUE-101, CWE-362)

**판정: PASS**

### 정적 대조

- `batch/status/BatchTransitionService.tryClaimReprocessFromFailed`
  - RAW 조건부 UPDATE(①)가 0행일 때 **곧바로 작업상태 폴백(②)하지 않고** `videoRepository.findDataSttsCdByRawSn(rawSn)` 로 **원인을 재판정**:
    - `PROCESSING` → 타 호출자 선점 → `false` (폴백 금지)
    - `FAILED` → UPDATE 0행인데 여전히 FAILED = 모순(재전이 레이스) → **fail-closed `false`**
    - 그 외(row 부재·COMPLETED·MARKING_READY) → "RAW 는 애초에 클레임 대상 아님" 예외 형상에 한해 ② 허용
  - 결함의 근본 원인("①이 0행"의 지배적 원인이 *남이 선점* 인데 *RAW 는 대상 아님* 으로 오독)을 정확히 겨냥.
  - 두 컬럼이 **함께 FAILED** 인 정상 실패 형상에서 A=RAW / B=작업상태를 각각 선점해 둘 다 `true` 를 받던 경로가 차단됨.
- `video/repository/VideoRepository.findDataSttsCdByRawSn` — `@Query("SELECT r.dataSttsCd FROM LsDataRaw r WHERE r.rawSn = :rawSn")` 단일 컬럼 projection. 존재 확인.
- 격리 정합: 메서드는 `REQUIRES_NEW` 로 즉시 커밋되며, READ COMMITTED 하에서 조건부 UPDATE 가 경쟁자 커밋까지 row lock 대기 → 해제 후 0행 → 재조회 시 `PROCESSING` 관측. 논리 성립.
- 로그는 고정 문자열 + `rawSn` 만 출력(CWE-117/209 준수).

### 테스트 커버리지

- `BatchReprocessClaimConcurrencyIT` (3건 PASS)
  - "두 컬럼이 함께 FAILED 인 정상실패 영상에 **동시 5요청 → 정확히 1건만 클레임**" ← 결함 재현 시나리오 직격
  - "작업상태 행이 없는 영상에 동시 5요청 → 정확히 1건" (무회귀 대조군)
  - "작업상태만 FAILED 인 예외형상은 여전히 폴백으로 1건 클레임" ← **폴백을 죽이지 않았음** 회귀 가드

---

## 3. TC-STREAM-B04/B20 — 스트리밍 심링크 PII 유출 (B-ISSUE-81, CWE-59/367/359)

**판정: PASS**

### 정적 대조

- `common/storage/VideoArtifactRootResolver`
  - `resolveRealPathUnder(target, base)` 신설 — 기존 `verifyRealPathUnder` 와 **동일 판정기**이되 통과한 **실경로를 반환**. `verifyRealPathUnder` 는 이 메서드에 위임 → 판정 로직 단일화(복제 없음).
- `video/service/VideoStreamService`
  - `resolveSafe(...)` 반환 타입을 `Path` → `VerifiedDeidFile(realPath, base)` 로 변경. lexical `startsWith` 통과 후 **`resolveRealPathUnder` 실경로 검증**을 추가하고, 2-way allowlist 특성상 한 base 실패 시 다음 후보 시도(전부 실패 시 `NOT_FOUND` 정규화).
  - **`UrlResource` 제거 → `NoFollowFileResource`** (신규 `AbstractResource` 구현). `getInputStream()` 이 `FrameImageService.openNoFollow(path)` — 프레임 4경로와 **동일 단일 헬퍼** 재사용. 심링크면 open 자체 실패 → **바이트 0 유출(fail-closed)**.
  - 존재 확인·크기 산출을 `Files.readAttributes(..., NOFOLLOW_LINKS)` 로 통일 → **판정 대상 == 응답 대상**.
  - **캐시 TTL 유출창 폐쇄**: `StreamMeta` 에 `base` 를 함께 실어, 캐시 히트 경로에서도 `revalidateOpenTarget()` 이 **매 요청** ①실경로가 base 하위인가 ②캐시 판정 당시 실경로와 동일한가 ③NOFOLLOW stat 이 정규 파일인가 **3중 판정**. 실패 시 `NOT_FOUND` (경로 원문 미노출, CWE-209).
    - ②가 핵심 — `STORAGE_RAW_PATH == STORAGE_DEIDENTIFIED_PATH` 형상에서는 **같은 base 안**의 원본을 가리키는 링크가 가능하므로 ①만으로 불충분. 이 점을 정확히 인지·처리함.
  - 재검증은 캐시된 값만 사용 → **DB 재조회 없음**(성능 회귀 없음).
- 응답 코드: 수정 전 원본 **200 서빙** → 수정 후 **404(NOT_FOUND)**. 이 엔드포인트의 기존 규약(비식별 무효 시 404)과 일치하며 412 로 갈리지 않아 오라클(CWE-209)도 만들지 않음.

### 테스트 커버리지 (`VideoStreamServiceTest` 44건 PASS)

- `비식별파일이_원본영상_심링크면_NOT_FOUND` (**B04 직격**)
- `co_locate_비식별파일이_원본영상_심링크여도_NOT_FOUND` — 벤더(KPST) 공유 마운트 신뢰경계
- `중간_디렉터리_세그먼트가_심링크로_base밖을_가리켜도_NOT_FOUND`
- `★캐시히트후_비식별파일이_원본심링크로_치환되면_NOT_FOUND` — **TTL 5분 유출창(B20) 직격**
- `★캐시히트후_Range요청도_심링크_치환되면_차단된다` — 시크 재요청 경로
- `스트리밍_Resource는_심링크를_따라_열지_않는다` — openNoFollow 규약
- 무회귀: `캐시히트_정상파일은_그대로_200이고_본문은_비식별본이다`, `206_부분응답_본문이_비식별본의_해당구간과_동일하다`, `정상_비식별파일은_심링크가드_도입후에도_그대로_200`
- `FileServingLinkFollowGuardTest` (아키텍처 가드, 1건 PASS) — 서빙 경로의 link-follow 재구현 방지

---

## 4. TC-NOTIFY-040 / 001 / 041 — export FAILED 인데 통지 발송 (D-ISSUE-61, CRITICAL)

**판정: PASS**

### 정적 대조

- `dataset/export/DatasetExportOutcome.java` (신규 enum, 7종 + `notifiable()`)
  - 통지 O: `COMPLETED` · `PARTIAL` · `IDEMPOTENT_SKIP`
  - 통지 X: `FAILED` · `VERSION_EXHAUSTED` · `NO_INPUT` · `DEIDENT_BLOCKED`
  - `metricTag()` 가 기존 `dataset.export.result{outcome}` 태그 문자열 7종을 **그대로 승계** → 관측 대시보드 계약 불변 + **관측과 통지 판정이 어긋날 수 없음**.
- `DatasetExportService.export(long, boolean)` 시그니처 `void` → `DatasetExportOutcome`.
  - **무예외 실패 4경로 전부** enum 반환으로 치환 확인:
    ① `loadPreparation` empty → `NO_INPUT`
    ② `resolveVideoRoot` 거부(`markBaseRejected`) → `FAILED`
    ③ `insertWithRetry == null` (버전 채번 소진) → `VERSION_EXHAUSTED`
    ④ 산출물 0건(`markFailed`) → `FAILED`
    (추가) 쓰기 중 런타임 예외 → `FAILED`, 신고 게이트 → `DEIDENT_BLOCKED`
  - fail-secure 초기값 `outcome = FAILED` 유지 → 판정 누락 시에도 통지 보류.
- `AsyncDatasetExportRunner.doExport`
  - `if (outcome == null || !outcome.notifiable()) { WARN; return false; }` — **`null` 도 fail-closed**.
  - 통지 판정이 `notifiable()` **단일 근거**로 수렴.
- 호출자 전수 확인: `exportService.export(...)` 호출부는 `AsyncDatasetExportRunner:136` **1곳뿐** → 판정 누락 경로 없음.
- 회수 경로 정합: `DatasetExportFailureRecoverer` 는 `runner.runApprovalAsync(rawSn)` 로 재산출하며 성공 시 완료 이벤트 재발행 → 통지는 **유실이 아니라 성공 시점으로 지연** (CLAUDE.md 구속 정책과 일치).

### 테스트 커버리지 (`DatasetExportNotifyGateTest` 8건 PASS)

- 무예외 실패 4경로 각각 **완료 통지 미발행** 확인 (산출물0건 / base거부 / 버전채번소진 / NO_INPUT)
- 승인 후 **수정 통지(TASK_MODIFIED) 콜백도 미실행** 확인
- 과잉차단 회귀 방지 3건: 정상 완료 / PARTIAL / 멱등 skip → 통지 **발행**
- `AsyncDatasetExportRunnerTest` 21건 PASS (기존 계약 무회귀)

---

## 5. TC-NOTIFY-051 — 통지 인증 헤더 누락 (D-ISSUE-62)

**판정: PASS**

### 정적 대조

- `common/config/WebClientConfig.controlNotifyWebClient`
  - `CONTROL_NOTIFY_TOKEN_HEADER = "x-access-token"` 상수 + `builder.defaultHeader(CONTROL_NOTIFY_TOKEN_HEADER, token.trim())` 로 **실제 부착 확인**.
  - 토큰 소스: `@Value("${authoring.control-notify.token:}")` ← 환경변수. **코드/yml 평문 상수 없음**(CWE-798).
  - 로그에 **토큰 값 미출력** — 존재 여부/`tokenLength` 만 (CWE-532 준수).
  - `enabled=true` && 토큰 공백 → 기동 WARN(차단 아님). local 목서버 호환 유지 근거 타당.
  - **추가 하드닝**: `http://` 평문 엔드포인트에 토큰 설정 시 CWE-319 WARN.
  - `trim()` 적용 — 환경변수 개행/공백 혼입 방어.

### 테스트 커버리지 (`ControlNotifyWebClientAuthHeaderTest` 4건 PASS)

- 헤더 **실제 요청 부착** 검증(설정만 확인이 아니라 실제 요청 관측) / 공백 trim / 빈 토큰 시 헤더 생략 / null 토큰 기동 무실패

### 잔여 리스크 (운영 배포 체크 항목)

- `CONTROL_NOTIFY_TOKEN` 환경변수가 dev/stg/prd 배포 형상에 **실제로 주입되는지**는 이 검증 범위 밖(코드는 정상). 미주입 시 기동 WARN 만 나고 실환경 401 이 재현되므로 **배포 전 확인 필요**.

---

## 6. TC-EXPORT-005 / 019 — PARTIAL export 뷰 배제 (E-ISSUE-81)

**판정: PASS** (실동작은 Testcontainers 실 PostgreSQL 기준. docker `klid-postgres` 상 검증은 **BLOCKED — V160 미적용**)

### 실측 (docker `klid-postgres`)

```
ex.export_stts_cd::text = 'SUCCEEDED'::text   ← 구 정의 유지, EXPORT_STTS_CD 출력 컬럼 없음
```
→ V160 미적용 상태 확인. **컨테이너 기준 실동작 검증 BLOCKED.**

### 정적 대조 — `V160__include_partial_export_in_completed_video_view.sql`

- LATERAL 조인 조건 `ex.EXPORT_STTS_CD IN ('SUCCEEDED', 'PARTIAL')` 로 확장.
- 출력 **맨 끝에** `e.EXPORT_STTS_CD` 1컬럼 추가 — 별칭 없이 원천 컬럼명·타입(`VARCHAR(20)` 코드값 표준도메인)·길이 100% 승계 → **신규 물리명 생성 없음**(표준용어·표준도메인 규칙 준수, V138 동일 방침).
- `ORDER BY ex.EXPORT_VER_NO DESC LIMIT 1` 유지 → **영상 1건 = 1 row 불변**.
- `CREATE OR REPLACE VIEW` + 기존 컬럼 이름·순서·타입 보존 + 끝에만 append → REPLACE 안전·재실행 멱등.
- 회수기(FAILED 앵커)는 **의도적으로 미변경** — PARTIAL 을 재시도 앵커에 넣으면 원천 이미지 영구 부재 영상이 max-attempts 소진까지 매 tick 새 버전 폴더 + 이미지 2벌을 재복사(디스크 누적). 판단 타당.
- 세 판정(뷰 / 통지 `DatasetExportOutcome.PARTIAL.notifiable()=true` / 멱등 baseline `SUCCEEDED+PARTIAL`)이 **PARTIAL 을 동일 취급**하도록 일치됨.

### 테스트 커버리지 (`DatamartViewSlimIT` 17건 PASS — `@SpringBootTest` + PostgresContainer, Flyway V160 전량 적용된 실 PostgreSQL)

- `V160_최초export가_PARTIAL이어도_EXPORT_PATH_NM이_노출된다` — `EXPORT_PATH_NM`/`FRAME_CNT` 채워짐 + `EXPORT_STTS_CD='PARTIAL'` 확인
- `V160_최신이_PARTIAL이면_구_SUCCEEDED가_아니라_최신_PARTIAL을_노출한다` — v1 SUCCEEDED(100) + v2 PARTIAL(98) → 1 row, `frame_cnt=98`
- `V160_FAILED_PENDING은_여전히_뷰에서_배제된다` — v2 FAILED · v3 PENDING 은 미노출, v1 SUCCEEDED(100) 유지 (과잉 개방 회귀 가드)

### 잔여 리스크

- docker 개발 DB 및 배포 대상 DB에 **V159/V160 미적용**. 배포 시 Flyway 적용 필요.

---

## 판정 요약

| TC-ID | 이슈 | 판정 | 근거 |
|---|---|:---:|---|
| TC-BATCH-057/062 인접 | B-ISSUE-41 마킹 영구고착 | **PASS** | `STATUS_SKIPPED`+`markSkipped()` PENDING 한정 · skip 4분기 전부 종결 배선 · V142 인덱스 술어 정합 · V159 백필 · 테스트 24건 PASS |
| TC-BATCH-153/154 | B-ISSUE-101 재처리 원자클레임 | **PASS** | 0행 원인 재판정(PROCESSING/FAILED → 폴백 금지, fail-closed) · `findDataSttsCdByRawSn` 확인 · 동시 5요청 IT 3건 PASS |
| TC-STREAM-B04 | B-ISSUE-81 심링크 PII유출 | **PASS** | `resolveRealPathUnder` + `NoFollowFileResource`(openNoFollow) + NOFOLLOW stat → 원본 200 → **404** 전환. 심링크 3종 테스트 PASS |
| TC-STREAM-B20 | B-ISSUE-81 캐시 TTL 유출창 | **PASS** | `revalidateOpenTarget` 매 요청 3중 재검증(base 하위/실경로 동일/정규파일), DB 재조회 없음. 캐시히트 치환 200·206 테스트 PASS |
| TC-NOTIFY-040 | D-ISSUE-61 export 실패 통지 | **PASS** | `DatasetExportOutcome.notifiable()` 단일 판정 · 무예외 실패 4경로 전부 enum 반환 · null fail-closed · 호출부 1곳 전수 |
| TC-NOTIFY-001 | D-ISSUE-61 (승인 TASK_COMPLETED) | **PASS** | 정상 완료/PARTIAL/멱등skip 통지 유지 확인(과잉차단 회귀 없음) |
| TC-NOTIFY-041 | D-ISSUE-61 (수정 TASK_MODIFIED) | **PASS** | 실패 시 수정 통지 콜백도 미실행 테스트 PASS |
| TC-NOTIFY-051 | D-ISSUE-62 인증헤더 누락 | **PASS** | `x-access-token` defaultHeader 부착 + 실제 요청 관측 테스트 PASS · 토큰 값 미로깅 · CWE-319 WARN 추가 |
| TC-EXPORT-005 | E-ISSUE-81 PARTIAL 뷰 배제 | **PASS**<br/>(컨테이너 실동작 BLOCKED) | V160 `IN ('SUCCEEDED','PARTIAL')` + `EXPORT_STTS_CD` 노출. Testcontainers IT PASS. docker DB 는 V158 로 미적용 |
| TC-EXPORT-019 | E-ISSUE-81 최신 PARTIAL 선택 | **PASS**<br/>(컨테이너 실동작 BLOCKED) | 최신 버전 우선 + FAILED/PENDING 배제 유지, IT PASS. docker DB 미적용 |

**총평: 재검증 대상 10개 TC 전부 PASS.** FAIL 없음.
BLOCKED 은 "코드 결함" 이 아니라 **환경 미반영**(docker DB V158 · 수정분 미커밋)에 기인하며, Testcontainers 실 PostgreSQL 검증으로 대체 충족했다.

## 후속 조치 (배포 전 필수)

1. 수정분 커밋 + docker 이미지 재빌드 → **V159/V160 Flyway 적용** 후 컨테이너 실동작 스모크(뷰 `EXPORT_STTS_CD` 컬럼 존재 + PARTIAL 노출).
2. `CONTROL_NOTIFY_TOKEN` 환경변수 dev/stg/prd 주입 확인 (미주입 시 기동 WARN 만 나고 실환경 401 재현).
3. 관제팀 통보: `V_COMPLETED_VIDEO` 에 `EXPORT_STTS_CD` 1컬럼 추가(하위호환) + 최신 산출이 PARTIAL 인 영상의 `EXPORT_PATH_NM`/`FRAME_CNT` 값이 구 SUCCEEDED/NULL → 최신 PARTIAL 로 변경됨.
