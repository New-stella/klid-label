# F 클러스터 part2 — F-2(데이터마트 Load) · F-6(포털 자산 조회/서빙/삭제) 검증 결과

- 회차: 2026-08-03 **3차**
- 담당 범위: `docs/test-cases/F-portal.md` **F-2**(30~57행, TC-PORTAL-020~038·039·051~053 = 23건) + **F-6**(136~153행, TC-PORTALUP-020~032 = 13건) — **총 36건**
- 검증 방식: **전건 실동작 우선**. 기동 스택(backend `localhost:18081/api`, PostgreSQL `klid_system`/`public` 스키마, HEAD `e065da42` 재빌드 이미지 — `_raw/stack-bringup.md` §0 조치분)에 포털 토큰으로 직접 curl + DB 상태 조회.
- 사용 토큰: `POST /v1/dev/tokens` 로 발급한 `role=PORTAL_USER, channel=PORTAL` 2개 — **sub=3001**(주 사용자), **sub=3002**(IDOR 대조군).
- 코드/설정 **미수정**. DB·스토리지는 재현에 필요한 최소 조작만 하고 **전부 원복**했다(원복 증거는 §5).

---

## 0. ★★ 최우선 결론 — 2차 HIGH #1(F-ISSUE-01) **완전 해소**

2차 `ISSUES.md` [F-ISSUE-01] 은 `GET /v1/portal/datamart/labels` 에 **APPROVED 게이트·비식별 신고 게이트가 모두 부재**해 PORTAL_USER 가 rawSn 하나로 미승인·반려·신고구간 영상의 라벨 좌표를 전건 덤프할 수 있던 결함(CWE-862/639/359)이다.

**실동작 재현 시도 결과 — 전부 차단됨(게이트 전무 → 완전 해소).**

```
$ TOK=<PORTAL_USER sub=3001 JWT>
$ for r in 7 5 6 999999 900 4; do curl -s -w ' <%{http_code}>' -H "Authorization: Bearer $TOK" \
    "localhost:18081/api/v1/portal/datamart/labels?rawSn=$r"; echo; done

rawSn=7      (PENDING,  프레임 10건·라벨 있음) → {"errorCode":"FORBIDDEN"} <403>
rawSn=5      (REJECTED)                        → {"errorCode":"FORBIDDEN"} <403>
rawSn=6      (FAILED)                          → {"errorCode":"FORBIDDEN"} <403>
rawSn=999999 (미존재)                          → {"errorCode":"FORBIDDEN"} <403>   ← 미존재/미승인 동일 응답(오라클 차단, CWE-209)
rawSn=900    (APPROVED + DE_IDENT_YN='F')      → {"errorCode":"PRECONDITION_FAILED"} <412>
rawSn=4      (APPROVED + 'Y')                  → 200 + 라벨 목록                    ← 정상 경로 회귀 무손상
```

수정 배선 확인(2차 "수정 방향"과 정확히 일치 — 판정 국소 재구현 없음):
`PortalLabelService.java:117-126` → `requireActor(actor)` → `isExposedToDatamart(rawSn)` 403 → `accessGuard.requireNotUnderDeidentReport(rawSn)` 412 → **그 다음에야** `lblRepository.findAllByRawSn(rawSn)`(:130). 즉 거부될 요청은 라벨 풀스캔조차 하지 않는다.
회귀 가드도 등재됨: `PortalUserLabelServiceTest`(미승인_PENDING_영상의_datamart_라벨조회는_403이다 / rawSn이_존재하지_않으면_예외없이_403이다 / 비식별신고구간_영상의_datamart_라벨조회는_412이다 / 신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다) — `_raw/test-baseline.md` backend 5,203건 전건 성공(실패 0) 확인.

> **반증 시도(추가)**: 게이트 순서를 우회할 수 있는 입력을 별도로 찔렀다 — ①`rawSn` 누락 → 400(게이트 이전에 400 이지만 데이터 미노출) ②비정수 `rawSn` → 400 ③`page`/`size` 극단값으로 조기 return 을 유도해 게이트를 건너뛰는 경로 → 존재하지 않음(게이트가 clamp/subList 계산보다 **앞**에 있다, :122-126 vs :128-143). **우회 경로 미발견.**

또한 2차 [F-ISSUE-27](배포 jar 가 소스보다 오래되어 포털 정렬 allowlist 미탑재 → `sort=status` 500)도 **해소 확인**: `?sort=status,asc` → **200**(allowlist 등록 키 `status→uldSttsCd`, `SortAllowlist.java:216-223`), `?sort=orgnlFileNm,asc` → **400**(미등록 키 strict 거부). 3차 스택은 검증 기준 소스와 동일 산출물이다.

---

## 1. F-2. 데이터마트 Load (APPROVED 게이트 · IDOR · 비식별 서빙 · 신고 게이트) — 23건

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTAL-020 | PASS | [실동작] `GET /v1/portal/datamart/videos?size=5` 6페이지 전수 조회 → 노출 rawSn 20건이 **전부 `LS_RAW_DATA_STATUS.DATA_STTS_CD='APPROVED'`**. 비APPROVED(5 REJECTED·6 FAILED·7 PENDING·17/30/34/36 ASSIGNED 등) 0건. `PortalLabelService.java:163-164` INNER JOIN 게이트(`findAllWithReviewStatus(null, APPROVED, …)`, `VideoRepository.java:277-283`) |
| TC-PORTAL-021 | PARTIAL | [실동작] 프레임 0건 영상(8·9·15·43·48·55·66·67·146·147)은 `content` 에서 **제외됨(기대 충족)**. 그러나 `totalElements`=30(=게이트 후 원본 수), 실제 노출 20건 → **오보 + 중간 빈 페이지**(page=4 → `content:[]`, page=5 → 2건). → **F-ISSUE-21**(2차 F-ISSUE-02 미해소 이월) |
| TC-PORTAL-022 | PASS | [정적] `PortalLabelService.java:172-174` 가 페이지 rawSn 집합에 대해 `lookupFirstSrcSnByVideo`/`lookupFrameCountByVideo`/`lookupLastUpdatedAtByVideo` 3회만 호출. 각각 `LsDataSrcRepository.findFirstSrcSnGroupedByRawSn`(`:91`, `in :rawSns` 집계) · `countByRawSnsGrouped`(`:187`) · `findAllById` → **행당 반복 조회 없음** |
| TC-PORTAL-023 | PASS | [실동작] `rawSn=4&page=-1&size=99999` → 200(예외·500 없음). `size=0`/`-10` → 1건만 반환(=size 1 로 clamp), `page=-5&size=3` → 3건(=page 0). 상한 100 은 `PortalLabelService.java:128` `Math.min(Math.max(size,1),100)` |
| TC-PORTAL-024 | PASS | [실동작] `rawSn` 누락 → `{"errorCode":"INVALID_INPUT","message":"필수 파라미터가 누락되었습니다: rawSn"}` 400. 서비스단 이중 방어 `:119-121` |
| TC-PORTAL-025 | PASS | [실동작] srcSn=1(rawSn=4)에 본인 user-label 저장(userLblSn=25) 후 `GET /v1/portal/frames/1/labels` → 응답 `labels` 가 **user-label 만**(id 25/8/7/6/5/1 — 전부 `LS_PORTAL_USER_LABEL`), datamart 원본(lblSn=1 `person`) 미포함. `:435-440` |
| TC-PORTAL-026 | PASS | [실동작] 저장 이전(본인 저장분 0) 조회 시 datamart 원본 반환. 대조군 sub=3002 는 **자기 저장분(USER3002-SECRET)만** 반환 → 병합 정책·IDOR 동시 확인. `:441-448` |
| TC-PORTAL-027 | PASS | [실동작] 미승인 rawSn=7 프레임(srcSn=35) `GET labels` → 403 `데이터마트에 노출되지 않은 영상입니다.` `:404-407` |
| TC-PORTAL-028 | PASS | [실동작] `srcSn=99999999` → labels 404 / image 404, 메시지 `프레임을 찾을 수 없습니다.`(내부 정보 미노출) `:398-399` |
| TC-PORTAL-029 | PASS | [실동작] `GET /v1/portal/frames/1/image` → **200**, `Content-Type: image/jpeg`, `X-Content-Type-Options: nosniff`, **`Cache-Control: no-store`**, `Content-Length: 13164`. 구 `private, max-age=300` 흔적 없음 |
| TC-PORTAL-030 | PASS | [실동작] 미승인 srcSn=35 image → 403 |
| TC-PORTAL-031 | PASS | [실동작] deid 경로 NULL 인 APPROVED 프레임 2건(srcSn=445/rawSn=902, srcSn=880211/rawSn=880210) → **404** `비식별 프레임이 존재하지 않습니다.` **원본 폴백 없음**을 반증 확인: 두 행의 `SRC_FILE_PATH_NM`(`/app/storage/raw/frames/raw/26/frame-1.jpg`, `…/101/frame-1.jpg`)은 컨테이너에 **실재하는 파일**인데도 서빙되지 않았다 |
| TC-PORTAL-032 | PASS | [실동작] `DE_IDNTF_SRC_FILE_PATH_NM` 을 조작해 4패턴 시도(원복 완료) → `…/deid/4/../../../../../etc/passwd` **403**, `/etc/passwd` **403**, `/app/storage/raw/frames/raw/4/frame-0.jpg`(원본 서브트리) **403**, `…/deidentified/videos/26/deidentified.mp4`(base 안·비식별 서브트리지만 프레임 아님) 404. `StorageSubtreePolicy.verifyDeidentifiedFile` 배선(`:541-555`) |
| TC-PORTAL-033 | PASS | [실동작·★반증] **심링크 우회 실증 시도** — deid 서브트리 *안*(`…/deid/4/qa-symlink.jpg`)에 원본 프레임(`…/raw/frames/raw/4/frame-0.jpg`)을 가리키는 심링크를 만들고 DB 경로를 그리로 돌림. lexical `startsWith` 라면 통과했을 형상인데 → **403 `허용되지 않은 이미지 경로입니다.`**(실경로 `toRealPath()` 기준 판정). 심링크·DB 원복 완료 |
| TC-PORTAL-034 | PASS | [실동작] deid 서브트리 안의 미존재 파일 지정 → 404 `이미지 파일이 존재하지 않습니다.`(경로 원문·스택 미노출) |
| TC-PORTAL-035 | PASS | [실동작] 신고 구간(rawSn=900, `DE_IDENT_YN='F'` + `APPROVED`) 프레임 srcSn=429 `GET labels` → **412** `비식별 재처리 대기 중인 영상입니다…`. APPROVED 게이트만으로는 안 걸리는 경로임을 데이터로 확인(900 은 APPROVED 유지) |
| TC-PORTAL-036 | PASS | [실동작] 동일 srcSn=429 `GET image` → **412**(파일 판독 전 차단 — `:525-527` 이 deid 경로 조회 `:530` 보다 앞) |
| TC-PORTAL-037 | PASS | [실동작] 위 TC-029 헤더 덤프의 `Cache-Control: no-store` |
| TC-PORTAL-038 | PASS | [실동작·★1 확정정책] 부모 rawSn=900(`'F'`) → 파생 rawSn=159(`ORGNL_RAW_SN=900`, 자기 `DE_IDENT_YN='Y'`)에 APPROVED 상태를 부여하고 파생 프레임 srcSn=582 조회 → `GET labels` **200**, `GET image` **200**, `GET datamart/labels?rawSn=159` **200**. 동시에 부모 프레임 srcSn=429 는 **412** — **조상 전파 없음(자기 rawSn 행만 판정)** 실증. 결함 아님(CLAUDE.md 2026-07-29 확정 + `DeidentReportGate.java:23-37`). 테스트 데이터 원복 완료 |
| **TC-PORTAL-039** | **PASS** | [실동작] **★2차 HIGH#1 해소** — §0 참조. 미승인(PENDING/REJECTED/FAILED) 403 · 미존재 403(동일 응답) · 라벨 풀조회 미수행 |
| **TC-PORTAL-051** | **PASS** | [실동작] **★2차 HIGH#1 해소** — 신고 구간 rawSn=900 → **412**. 해제 복원은 `PortalUserLabelServiceTest(신고_해제_후_datamart_라벨조회는_다시_200으로_복원된다)` + 게이트가 `DE_IDNTF_YN` 단일 컬럼 판정이라 `'F'→'Y'` 로 자동 해제 |
| TC-PORTAL-052 | PASS | [실동작] `rawSn=4&page=2147483647&size=100` → **200 `data:[]`**(500 아님). `size=2` 로도 200 빈 리스트. `long from = (long) clampedPage * clampedSize`(`:135`) |
| TC-PORTAL-053 | PASS | [실동작+정적] size=0→1건, size=-10→1건, page=-5→page0(3건), page=-1&size=99999→200. size 상한 100 은 대상 영상 라벨이 10건뿐이라 응답으로 구분 불가 → `:128` 정적 + `PortalUserLabelServiceTest(size가_100초과면_100으로_clamp된다)` 로 보강 |

### F-2 소계 — PASS 22 / PARTIAL 1 / FAIL 0

---

## 2. F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태) — 13건

사용 데이터: sub=3001 소유 10건(IMAGE 3 / VIDEO 7), sub=3002 소유 1건(uldSn=62). 삭제 케이스는 **검증용 자산을 새로 업로드(uldSn=82)** 해서 소진했다(기존 데이터 무손상).

| ID | 판정 | 근거 확인 |
|----|:--:|------|
| TC-PORTALUP-020 | PASS | [실동작] 3001 `GET /v1/portal/uploads?size=100` → 10건 `[57,59,60,61,67,68,69,70,71,72]` 전부 본인 소유. 3002 → 1건 `[62]`. **교차 노출 0건** |
| TC-PORTALUP-021 | PASS | [실동작] `?type=IMAGE` → 3건(uldTypeCd 유니크 `["IMAGE"]`), `?type=VIDEO` → 7건(`["VIDEO"]`), `?type=image`(소문자) → 3건(`toUpperCase` 정규화, `:165`) |
| TC-PORTALUP-022 | PASS | [실동작] `?type=FOO` → 400 `지원하지 않는 type 입니다. 허용: IMAGE, VIDEO`(조용한 빈 결과 아님) `:164-169` |
| TC-PORTALUP-023 | PASS | [실동작] `?size=500` → 응답 `size:100`, `pageable.pageSize:100`. `?size=2147483647` → 100. 프레임 목록 `/uploads/72/frames?size=500` → `size:100`. `PortalUploadController.java:177-182`. 참고: `size=0`/`-1` 은 Spring 이 기본 20 으로 되돌림(하드캡 규약과 무관) |
| TC-PORTALUP-024 | PASS | [실동작] 3002 → `GET /uploads/61`(3001 소유) **403**, `GET /uploads/99999999`(부재) **403** — **본문·코드 완전 동일**(`본인 자산이 아니거나 존재하지 않습니다.`) → 자원 열거 오라클 없음 |
| TC-PORTALUP-025 | PASS | [실동작] 3002 → `GET /uploads/61/frames` **403**. 3002 자기 `GET /uploads/62/frames` 는 200(과잉 차단 아님) |
| TC-PORTALUP-026 | PASS | [실동작] 3002 → `GET /uploads/frames/72/image`(3001 소유) **403**, 역방향 3001 → `/uploads/frames/62/image`(3002 소유) **403**. 소유자 스코프 조인(`frmeRepository.findByUldFrmeSnAndOwner`, `:207`) |
| TC-PORTALUP-027 | PARTIAL | [실동작] IMAGE 자산 프레임(uldFrmeSn=61) → 200 `Content-Type: image/png` + `nosniff` **정상**. 그러나 **VIDEO 자산에서 추출된 프레임**(uldFrmeSn=72, 파일 실체는 JPEG `ff d8 ff e0`) → `Content-Type: application/octet-stream` — `resolveStoredMediaType` 이 **프레임이 아니라 업로드 마스터 MIME(`video/mp4`)** 을 보기 때문. 신고 게이트 비대상·`LS_DATA_RAW` 라이프사이클 부재는 기대대로 확인. → **F-ISSUE-22** |
| TC-PORTALUP-028 | PASS | [실동작·★반증] `LS_PORTAL_ULD_FRME.FILE_PATH_NM` 조작(원복 완료): `/etc/passwd` **403**, `…/portal/../frames/raw/4/frame-0.jpg` **403**, `/app/storage/raw/frames/raw/4/frame-0.jpg` **403**, base 안 미존재 파일 404. 추가로 **portal base 안에 base 밖을 가리키는 심링크**(`…/portal/images/qa-esc.png` → 내부 파이프라인 원본 프레임) 생성 후 지정 → **403 `허용되지 않은 이미지 경로입니다.`**(`realWithinBase`, `:245-257`). 심링크 삭제·DB 원복 완료 |
| TC-PORTALUP-029 | PASS | [실동작] 신규 자산 uldSn=82 `DELETE` → **204**. 이후 `ls_portal_uld`=0행, `ls_portal_uld_frme`(uldSn=82)=0행(CASCADE), 물리 파일 `/app/storage/raw/portal/images/a254d60b-….jpeg` **삭제됨**(`ls` → No such file) |
| TC-PORTALUP-030 | PASS | [실동작] uldSn=82 를 `PROCESSING` 으로 두고 `DELETE` → **409** `프레임 추출이 진행 중인 자산은 삭제할 수 없습니다…` `:273-276` |
| TC-PORTALUP-031 | PASS | [실동작·★반증] 저장 디렉터리를 `chmod 555` 로 만들어 **실제 IOException 유발** → **500** `파일 삭제에 실패했습니다…` + **DB 행 잔존 확인**(`SELECT count(*) … uld_sn=82` → **1**). 파일 삭제가 DB 삭제보다 앞(`:286-289`)이라 부분 삭제가 생기지 않음. 권한 원복 완료(`chmod 775`) |
| TC-PORTALUP-032 | PASS | [실동작] 3002 → `DELETE /uploads/82`(3001 소유) **403**, 삭제 미수행(직후 3001 이 정상 삭제 가능) |

### F-6 소계 — PASS 12 / PARTIAL 1 / FAIL 0

---

## 3. 판정 집계

| 구간 | 총 | PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|---|--:|--:|--:|--:|--:|--:|--:|
| F-2 | 23 | 22 | 0 | 1 | 0 | 0 | 0 |
| F-6 | 13 | 12 | 0 | 1 | 0 | 0 | 0 |
| **합계** | **36** | **34** | **0** | **2** | **0** | **0** | **0** |

PASS율 94.4%. **FAIL 0건 — 2차의 이 구간 HIGH(게이트 전무)는 실동작으로 해소가 확증됐다.**

---

## 4. 이슈

### [F-ISSUE-21] TC-PORTAL-021 — 데이터마트 영상 목록의 `totalElements`/`totalPages` 오보 + 중간 빈 페이지(2차 F-ISSUE-02 **미해소 이월**)

- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 포털 홈 목록은 "진입 가능한 영상"만 세어야 한다. 프레임 0건 영상은 라벨링 진입 대상 프레임이 없어 의도적으로 제외되므로(서비스 주석 "MED 방어"), 페이지 메타(`totalElements`/`totalPages`)도 그 기준이어야 FE 페이저가 실제 데이터와 맞고, **뒤 페이지 영상이 사용자 눈에서 사라지지 않는다.**
- **현재 동작(이슈 내용)**: 필터는 페이지 **content 에만** 적용되고 total 은 필터 이전 값을 그대로 쓴다.
  ```java
  // PortalLabelService.java:177-189
  List<DatamartVideoResponse> content = rows.stream()
          .filter(r -> firstSrcSnByVideo.get(r.getRawSn()) != null)   // ← 페이지 안에서만 제외
          ...
  // 제외로 인해 페이지 size 보다 적어질 수 있으나 totalElements 는 원본(게이트 후) 기준 유지.
  return new PageImpl<>(content, pageable, page.getTotalElements());   // ← 30 (실제 노출 20)
  ```
  실측(`size=5`, 3001 토큰):
  ```
  page=0 total=30 pages=6 n=5  [880210,880200,159,115,110]
  page=1 total=30 pages=6 n=3  [101,94,81]        ← 5건 요청했는데 3건 (2건 조용히 증발)
  page=2 total=30 pages=6 n=5
  page=3 total=30 pages=6 n=5
  page=4 total=30 pages=6 n=0  []                 ← ★중간 빈 페이지
  page=5 total=30 pages=6 n=2  [18,4]
  ```
  전 페이지 합 20건 ≠ `totalElements` 30. `size=100` 으로 요청하면 우연히 20 으로 보이는데, 이는 서비스가 고친 게 아니라 **Spring `PageImpl` 이 "마지막 페이지면 offset+content.size() 로 total 을 재계산"** 하는 보정이 걸린 것뿐이다(작은 size 에서는 보정이 안 걸려 오보가 그대로 노출된다) — 오진 주의.
- **재현/확인 경로**:
  ```bash
  TOK=$(curl -s -XPOST localhost:18081/api/v1/dev/tokens -H 'Content-Type: application/json' \
        -d '{"role":"PORTAL_USER","channel":"PORTAL","userNo":"3001"}' | jq -r .data.token)
  for p in 0 1 2 3 4 5; do curl -s -H "Authorization: Bearer $TOK" \
      "localhost:18081/api/v1/portal/datamart/videos?page=$p&size=5" \
    | jq -c '{p:.data.number,total:.data.totalElements,n:(.data.content|length)}'; done
  ```
  ```sql
  -- 프레임 0건 APPROVED 영상(= 제외 대상) 확인
  SELECT r.raw_sn FROM ls_data_raw r JOIN ls_raw_data_status s ON s.raw_data_id=r.raw_sn
   WHERE s.data_stts_cd='APPROVED'
     AND NOT EXISTS (SELECT 1 FROM ls_data_src d WHERE d.raw_sn=r.raw_sn);
  -- → 8,9,15,43,48,55,66,67,146,147 (10건)
  ```
- **영향**: 기능/데이터정합. ①FE 페이저가 실제보다 많은 페이지를 그려 빈 화면이 노출된다 ②**한 페이지가 통째로 비면 사용자는 "끝"으로 오인해 뒤 페이지(rawSn 18·4)의 영상에 접근하지 못한다**(무한스크롤 구현이면 더 확실히 멈춘다) ③"검수 완료 영상 N건" 카운트가 20% 이상 부풀려 보고된다. 보안 영향은 없다(노출되는 영상 자체는 APPROVED 게이트 통과분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 필터를 **쿼리로 내린다** — `findAllWithReviewStatus` 에 `AND EXISTS (SELECT 1 FROM LsDataSrc d WHERE d.rawSn = v.rawSn)` 를 추가하면 count 쿼리에도 같은 조건이 적용돼 total·page 수·페이지 채움이 동시에 정합해진다(현행 in-memory `filter` 는 제거). 프레임 유무는 `LS_DATA_SRC(RAW_SN)` 인덱스로 판정되므로 EXISTS 비용은 낮다. 대안(서비스에서 total 만 재계산)은 **중간 빈 페이지를 못 고치므로 부적절**하다.

---

### [F-ISSUE-22] TC-PORTALUP-027 — 포털 **영상** 업로드에서 추출된 프레임이 `application/octet-stream` 으로 서빙됨(자산 MIME 을 프레임 MIME 으로 오용)

- **심각도**: LOW
- **기대 동작(기대효과)**: 프레임 이미지 서빙은 **그 프레임 파일의 실제 형식**을 Content-Type 으로 선언해야 한다. `X-Content-Type-Options: nosniff` 를 함께 보내므로 선언이 틀리면 브라우저는 **정정할 수단이 없다**(sniffing 금지). 카탈로그 기대값 "Content-Type=저장MIME" 의 취지도 "확장자 추정 금지 = 저장 시점에 확정한 **그 파일의** MIME 사용"이다.
- **현재 동작(이슈 내용)**: 프레임의 MIME 을 **업로드 마스터(`LS_PORTAL_ULD.MIME_TYPE_NM`)** 에서 가져온다. 이미지 업로드는 마스터 MIME = 프레임 MIME 이라 맞지만, **영상(TUS) 업로드는 마스터가 `video/mp4`** 이고 추출된 프레임은 JPEG 이라 매핑이 어긋난다.
  ```java
  // PortalUploadService.java:210-223
  LsPortalUld uld = uldRepository.findByUldSnAndPortalUserNo(frme.getUldSn(), portalUserNo)...
  MediaType mediaType = resolveStoredMediaType(uld.getMimeTypeNm());   // ← 자산 MIME (VIDEO 면 video/mp4)
  // :433-443  resolveStoredMediaType : JPEG/PNG 가 아니면 APPLICATION_OCTET_STREAM (fail-closed)
  ```
  실측:
  ```
  GET /v1/portal/uploads/frames/61/image   (IMAGE 자산, mime=image/png)
    → 200  Content-Type: image/png            ✅
  GET /v1/portal/uploads/frames/72/image   (VIDEO 자산, mime=video/mp4)
    → 200  Content-Type: application/octet-stream   ❌  (+ nosniff, Content-Disposition: inline)
  $ docker exec klid-backend head -c 4 /app/storage/raw/portal/frames/72/frame-0.jpg | od -An -tx1
     ff d8 ff e0        ← 실체는 JPEG
  ```
  `PortalFrameExtractRunner` 는 프레임을 항상 `.jpg` 로 떨어뜨리므로 **영상 업로드 자산의 모든 프레임이 이 경로에 해당**한다(실측 3001 소유 VIDEO 7건 전부).
- **재현/확인 경로**:
  ```bash
  curl -s -D - -o /dev/null -H "Authorization: Bearer $TOK" \
    localhost:18081/api/v1/portal/uploads/frames/72/image | grep -i content-type
  # → Content-Type: application/octet-stream / X-Content-Type-Options: nosniff
  ```
- **영향**: 기능(렌더링). 현재 FE 는 `useUploadFrameImage.ts:49-53` 이 XHR 로 blob 을 받아 `URL.createObjectURL` 로 그리므로 **지금은 화면이 깨지지 않는다**(blob: URL 은 ORB 대상이 아님). 다만 ①`Content-Disposition: inline` + octet-stream 조합이라 URL 직접 열람 시 표시 대신 다운로드가 되고 ②FE 가 성능상 `<img src>` 직결로 바꾸는 순간 **nosniff + 비이미지 타입 → Chrome ORB 로 차단**되어 영상 업로드 라벨링 캔버스가 백지가 된다(내부 라벨링에서 실제로 있었던 회귀 유형). 보안 영향은 없다(오히려 fail-closed 방향).
- **수정 방향(제안)**: ⚠ 구현하지 않음. 프레임의 MIME 을 **프레임 행 기준**으로 정한다 — ①단기: `serveFrameImage` 에서 자산이 `TYPE_VIDEO` 면 추출 포맷 상수(JPEG)를 쓰거나 `FrameImageService.resolveMediaType(path)`(확장자 allowlist 기반, 내부 서빙과 동일 헬퍼) 를 재사용 ②정공: `LS_PORTAL_ULD_FRME` 에 프레임 MIME 컬럼을 추가해 추출 시점에 확정 적재(표준용어 검토 필요). ①이라도 `application/octet-stream` fail-closed 기본값은 유지할 것.

---

### [F-ISSUE-23] TC-PORTAL-025 / TC-PORTAL-026 **인접** — 포털 프레임 라벨 Load 응답에 항목·좌표 상한도 페이징도 없어 단일 프레임 응답이 500KB 를 넘음(2차 F-ISSUE-22 의 **조회측 잔여**)

- **심각도**: LOW
- **기대 동작(기대효과)**: 2차 [F-ISSUE-22] 의 수정 방향은 *"`saveUserLabel` 에 좌표 개수 상한 적용 + **`loadFrameLabels`/`listMyLabels` 응답에 항목 상한 또는 페이징 도입**"* 이었다. 저장을 막아도 **이미 적재된 행**과 **행 개수 축**이 남으므로 조회측 상한이 있어야 응답 크기가 유계가 된다.
- **현재 동작(이슈 내용)**: 저장측은 해소됐다(`validatePointCount` — BBOX 2점 / POLYGON 3~200점, `PortalLabelService.java:302-315`). 그러나 **조회측은 무제한 그대로**다 — `loadFrameLabels`(`:432-448`)·`listMyLabels`(`:380-381`) 어디에도 `Pageable`·항목 상한이 없다.
  ```
  GET /v1/portal/frames/1/labels        → 519,052 bytes
    labels = [{id:8, POLYGON, points:30000}, {id:7, "HACK"}, {id:6}, {id:5}, {id:1}]
  GET /v1/portal/user-labels?rawSn=4    → 460,428 bytes  (동일 무페이징)
  ```
  id=8 은 2차 검증이 상한 도입 전에 적재한 30,000점 레거시 행이다(신규 생성은 이제 400 으로 차단됨).
- **재현/확인 경로**:
  ```bash
  curl -s -H "Authorization: Bearer $TOK" -o /dev/null -w '%{size_download}\n' \
       localhost:18081/api/v1/portal/frames/1/labels     # → 519052
  ```
  ```sql
  SELECT user_lbl_sn, lbl_type_cd, length(point_cn) FROM ls_portal_user_label WHERE src_data_src_sn=1;
  ```
- **영향**: 자원 소진(CWE-770 / OWASP API4) — **잔여 위험은 낮다**: ①신규 유입은 좌표 200점 + 본문 상한 + per-user RateLimiter(`PortalLabelController.acquireSavePermit`)로 3중 제한 ②남은 축은 "한 프레임에 쌓인 행 개수 × 200점"과 기존 레거시 행뿐. 다만 **라벨 삭제 API 가 없어 행은 단조 증가**하므로 장기적으로는 조회 응답이 계속 커진다. 보안 노출은 없다(전부 본인 소유분).
- **수정 방향(제안)**: ⚠ 구현하지 않음. ①`loadFrameLabels`/`listMyLabels` 에 `Pageable`(하드캡 100, 형제 `PortalUploadController.capped` 규약 재사용) 또는 프레임당 항목 상한을 도입 ②레거시 초과 행 정리 마이그레이션은 별건으로 분리(조회는 예외 없이 스킵/절단, 500 금지). ⚠ FE `PortalLabelingPage` 가 전량 로드를 전제하므로 계약 변경 시 FE 동반 수정 필요.

---

## 5. 검증 중 조작한 데이터·원복 증거 (코드 미수정)

| 조작 | 목적 | 원복 |
|---|---|---|
| `ls_data_src.de_idntf_src_file_path_nm`(src_sn=1) 임시 변경 6회 | TC-PORTAL-031/032/033/034 | `/app/storage/deidentified/frames/deid/4/frame-0.jpg` 로 복구 → 재조회 **200** 확인 |
| `/app/storage/deidentified/frames/deid/4/qa-symlink.jpg` 생성 | TC-PORTAL-033 심링크 반증 | `rm -f` 완료 |
| `ls_raw_data_status` 에 `raw_data_id=159, APPROVED` 삽입 | TC-PORTAL-038(부모 'F' × 파생 APPROVED 조합이 기존 데이터에 없었음) | `DELETE` 완료(잔여 0행) |
| `ls_portal_user_label` userLblSn=25 저장 | TC-PORTAL-025 | `DELETE` 완료(잔여 0행) |
| `ls_portal_uld_frme.file_path_nm`(uld_frme_sn=61) 임시 변경 5회 + `…/portal/images/qa-esc.png` 심링크 | TC-PORTALUP-028 | 경로 복구 + 심링크 삭제 → 재조회 **200** 확인 |
| `ls_portal_uld.uld_stts_cd`(uld_sn=82) → PROCESSING → READY | TC-PORTALUP-030 | 원복 후 해당 자산은 TC-029 에서 정상 삭제 소진 |
| `/app/storage/raw/portal/images` `chmod 555` | TC-PORTALUP-031 IOException 유발 | `chmod 775` 복구 확인(`drwxrwxr-x`) |
| 검증용 이미지 1건 업로드(uldSn=82) | TC-PORTALUP-029/030/031/032 | TC-029 에서 DELETE 로 소진(DB·파일 모두 제거됨) |

기존 시드/타 에이전트 데이터는 **삭제·변경하지 않았다.**

---

## 6. 카탈로그 정정 (담당 라인범위 내 Edit 완료 — 4건)

| ID | 항목 | 구 | 신 |
|---|---|---|---|
| TC-PORTAL-038 | 근거 라인 드리프트 | `AiInferenceDeidentReportGateTest.java:283-295` | `…:284-296` (`@Test` 284 · 메서드 285-296) |
| TC-PORTAL-052 | 근거 라인 드리프트 | `PortalLabelService.java:127-141` | `…:128-143` (127 은 공백행, `toList()` 는 143행) |
| TC-PORTALUP-021 | 근거 라인 드리프트 + 케이스명 보강 | `PortalUploadService.java:164-170` | `…:165-171`, 케이스명에 **대소문자 무관(`toUpperCase` 정규화)** 명시 + 입력에 `?type=image` 추가(실동작으로 확인한 동작을 카탈로그가 커버하지 않았음) |
| TC-PORTALUP-022 | 근거 라인 드리프트 | `PortalUploadService.java:163-168` | `…:164-169` |

정정하지 않고 유지한 것:
- **TC-PORTALUP-027 의 기대결과는 그대로 뒀다.** 구현이 자산 MIME 을 쓰는 것은 카탈로그 오류가 아니라 **구현 결함**(F-ISSUE-22)이므로, 기대값을 현행 동작에 맞추면 결함을 카탈로그에 고착시키게 된다.
- TC-PORTAL-038 의 `DeidentReportGate.java:23-37` 은 재확인 결과 **정확**(23=`★ 판정 범위` 헤딩, 37=철회 문단 끝).
- 그 외 F-2·F-6 근거 라인 32건은 전건 재확인 결과 정확(수정 없음).

---

## 7. 이월·참고 (신규 이슈 아님)

- **UNCERTAINTIES #12(데이터마트/이미지 서빙 rate limit 부재) — 미해소 유지**. `PortalLabelController` 에서 RateLimiter 는 `POST /user-labels` 한 곳뿐이다(`acquireSavePermit`, `:167-175`). `/datamart/videos`·`/datamart/labels`·`GET /user-labels`·`/frames/{srcSn}/labels`·`/frames/{srcSn}/image` 5개 조회 핸들러는 여전히 무제한이며, 프레임 이미지는 `no-store` 라 매 요청 디스크 I/O 가 발생한다. **다만 3차에서 APPROVED·신고 게이트가 붙어 열거 가능한 표면이 "APPROVED 영상"으로 좁아졌으므로 위험도는 2차보다 낮다.** 확정 정책이 없어 이번에도 사실만 기록한다(케이스 판정 대상 아님).
- **신고 구간 영상이 데이터마트 목록에는 계속 노출된다**(rawSn=900 이 `/datamart/videos` 결과에 포함, 진입하면 412). 관제 뷰 확정 정책(신고 필터 미적용)과 방향이 같아 **결함으로 보고하지 않는다**(2차 판단 유지).
- **2차 F-ISSUE-27(배포 jar STALE) 해소 확인** — §0 말미 참조. 3차 스택은 HEAD `e065da42` 재빌드본이며 포털 정렬 allowlist가 실효한다.
- 2차가 남긴 레거시 오염 행이 DB 에 남아 있다: `ls_portal_user_label` 의 `lbl_type_cd='HACK'`(id 7)·`Infinity` 좌표(id 5)·음수 좌표(id 6)·30,000점(id 8). 신규 생성은 3차 수정분으로 전부 차단됐고 조회는 500 없이 처리된다(F-3 담당 범위 — 여기서는 사실만 기록).
