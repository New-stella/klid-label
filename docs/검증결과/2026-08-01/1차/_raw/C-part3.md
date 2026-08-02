# C 클러스터 part3 — TC-LABEL 중간부(라벨 마스터 join · 온라인 오토라벨 · DTCT_TYPE_CD 매칭)

- **담당 범위**: `docs/test-cases/C-marking-labeling.md` offset 120~167 (TC-LABEL-46 ~ TC-LABEL-80). 폐기 케이스(TC-LABEL-70)는 검증 대상 제외. TC-LABEL-90~106(비식별 신고)은 part4 담당이라 본 문서에서 다루지 않음.
- **검증 일시**: 2026-08-01 23:15~23:25 KST
- **스택**: `klid-backend`(:18081/api, Flyway **V158** 적용 확인) · `klid-ai-server`(:19300, `AI_MOCK_MODE=false` 이나 **모델 가중치 미탑재 → mock 폴백**) · `klid-postgres`(`public` 스키마) · `klid-mock-server`(:9400)
- **사용 토큰**: `POST /v1/dev/tokens` — REVIEWER(sub=1001) · WORKER(sub=2001)
- **사용 데이터**: rawSn=26(srcSn 296~300) / 27(301~305) / 31(329~330) / 33(311~312) — 모두 WORKER 2001 배정. 라벨 마스터 활성 매핑 6종(`person·car·bicycle·motorcycle·bus·truck`), 미매핑 활성 3종(`fire·smoke·water`)

> ⚠ **본 검증에서 수행한 DB 변경**은 모두 원복 완료: ①`ls_auth_work_lock` 임시 락 1행 INSERT→DELETE(잔여 0) ②`ls_data_raw(raw_sn=33).de_ident_yn` `Y`→`F`→`Y` 복원 확인 ③테스트 라벨 `c3-tmp`(lbl_id=20) 생성 후 soft delete. 코드·설정·테스트 파일은 일절 수정하지 않았음.

---

## 판정 표

| TC ID | 판정 | 근거 확인 | 비고 |
|---|:--:|---|---|
| TC-LABEL-46 | PASS | [실동작] `POST /v1/manage/labels {"color":"#ffffff"}` → **400** `INVALID_INPUT` `"color 는 대문자 hex (#RRGGBB) 형식이어야 합니다."` | `LabelMasterRequest.java:33` `@Pattern(^#[0-9A-F]{6}$)` — 라인 일치 |
| TC-LABEL-47 | PASS | [실동작] `type:"FOO"` → **400** `"type 은 BBOX/POLYGON/POINT/SKELETON 중 하나여야 합니다."` | `LabelMasterRequest.java:37` 일치 |
| TC-LABEL-48 | PASS | [실동작] `sortNo:-1` → **400** `"sortNo 는 0 이상이어야 합니다."` | `LabelMasterRequest.java:40` 일치 |
| TC-LABEL-49 | PASS | [실동작] `PUT /v1/manage/labels/999999` → **404** `NOT_FOUND` `"라벨을 찾을 수 없습니다."` | `LabelMasterService.java:100-101` 일치 |
| TC-LABEL-50 | PASS | [실동작] lbl_id=20(`c3-tmp`)을 `" PERSON "` 로 수정 → **409** `CONFLICT`. 같은 이름(`c3-tmp`)으로 수정 → **200**(자기 제외 동작) | `LabelMasterService.java:104-105` + `LsLabelRepository.java:42-47` `LOWER(TRIM())` 정규화 비교 — 대소문자·공백 무시 근사중복 실증 |
| TC-LABEL-51 | PASS | [실동작] `DELETE /v1/manage/labels/20` → **204**, DB `select use_yn from ls_label where lbl_id=20` → **`N`**(행 존속 = hard delete 없음) | `LabelMasterService.java:190-195` 일치 |
| TC-LABEL-52 | PASS | [실동작] WORKER 토큰 `POST /v1/manage/labels` → **403** `FORBIDDEN` `"권한이 없습니다."` | `LabelMasterController.java:82-83` `@PreAuthorize("hasRole('REVIEWER')")` 일치 |
| TC-LABEL-53 | PASS | [실동작] WORKER 토큰 `GET /v1/manage/labels` → **200**, `GET /v1/manage/labels/detect-candidates` → **200**(mapped=true/false 동반) | `LabelMasterController.java:57` 일치 |
| TC-LABEL-54 | PASS | [정적] `LabelMasterService.java:152-154` — `cocoLabel == null \|\| isBlank()` 시 `Optional.empty()` **조기 반환, repository 미호출**. 근거 라인 표기 `:151-156` 와 실제(151-157) 사실상 일치 | 단위테스트 커버(baseline 전량 통과) |
| TC-LABEL-55 | PASS | [실동작] DB 직접 `INSERT ls_label(dtct_type_cd='person', use_yn='Y')` → **`duplicate key value violates unique constraint "uk_ls_label_dtct_type"`**. 인덱스 실측 `UNIQUE btree (dtct_type_cd) WHERE use_yn='Y' AND dtct_type_cd IS NOT NULL` | `LsLabelRepository.java:84` `findByDtctTypeCdAndUseYn` → `Optional` 반환이 안전(NonUniqueResult 불가). 앱단 선검증(409)도 실동작 확인: `dtctTypeCd:"person"` 중복 생성 → **409 `"이미 사용 중인 검출 클래스 매핑입니다."`** |
| TC-LABEL-60 | PARTIAL | [실동작] `POST /v1/frames/296/autolabel` → **200** `{srcSn:296, detectedCount:0, savedCount:0, labels:[]}`. **미저장 정책은 실증**: 약 50회 호출 후에도 `ls_data_lbl` (rawSn=26) **0건**. 단 **좌표 반환·`lblSn=null` 은 미확인** — ai-server 가 `weights_missing` mock 이라 검출 0건 | 미검증분은 `AutolabelOnlineServiceTest:132 "온라인_오토라벨은_DB에_저장하지_않고_좌표만_반환한다"` 로 커버(baseline 통과). 근거 라인 `:206-280` 일치 → **C-ISSUE-44** |
| TC-LABEL-61 | PASS | [실동작] WORKER(2001) 가 타인(2002) 배정 프레임 `srcSn=75` 호출 → **403** `FORBIDDEN` `"본인에게 배정되지 않은 영상입니다."`. ai-server 호출 로그 없음 | `AutolabelOnlineService.java:210` `accessGuard.verifyAndGet` 최우선 — 라인 일치 |
| TC-LABEL-62 | PASS | [실동작] `ls_auth_work_lock`(RAW, rawSn=33, LOCKED) 삽입 후 `srcSn=311` 호출 → **409** `CONFLICT` `"작업이 잠긴 영상입니다."`. 락 제거 후 200 복귀 | `AutolabelOnlineService.java:411-414` 라인 일치. **추가 실증**: 락 + `de_ident_yn='F'` 동시 → **409**(락 우선), 락 해제 + `'F'` 만 → **412** `PRECONDITION_FAILED` — javadoc(:404-406)의 "작업락 먼저" 순서 규약 그대로 |
| TC-LABEL-63 | PASS | [실동작] 동일 프레임(296) 40 동시요청 → **200×1 / 409×39**. 직후 단건 재요청 **200** = `finally` in-flight 해제 확인. 502(AI실패)·429(bulkhead) 경로 뒤에도 재요청 200 확인 | `AutolabelOnlineService.java:217-219`, `:277-279` 라인 일치 |
| TC-LABEL-64 | PASS | [실동작] `{"classes":["dog","zebra","toothbrush"]}` → **200** `detectedCount:0` + message `"검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요."`. **ai-server `POST /infer/yolo/track` 누적 호출수 변화 0** (15→15) = ai 미호출 실증. 백엔드 로그 `[Autolabel] no mapped detect classes srcSn=296 rawSn=26 requested=3` | `:228-235`, `:457-461` 라인 일치. `zebra`/`toothbrush` 는 유효 COCO 이지만 **마스터 미매핑**이라 차단됨 = allowlist 축이 COCO 가 아니라 `LS_LABEL.DTCT_TYPE_CD` 임을 실증 |
| TC-LABEL-65 | PASS | [실동작] `{"classes":["person","hack","dog","PERSON"," car "]}` → 백엔드 WARN 3건 `drop unmapped detect class=hack` / `=dog` / `=PERSON`. `person`·`" car "`(trim 후 `car`) 만 통과 → ai-server 1회 호출 | `:465-475` 라인 일치. trim 정규화(`c.trim()`)·로그 위조 방지(`LogSanitizer`) 동작 확인 |
| TC-LABEL-66 | PASS | [실동작] 위 65·64 조합으로 **FE 요청이 그대로 통과하는 경로 없음** 확인 — 미매핑 요청은 전량 drop, 전부 미매핑이면 ai 미호출. `callYolo(..., effectiveClasses, ...)`(`:239`)가 원본 `classes` 가 아닌 재구성 리스트만 전달 | `:457-476` 라인 일치. ⚠ 응답측(ai-server → BE) 재검증은 없음 → 방어심층 관점 관찰 **C-ISSUE-41**(정책 위반 아님) |
| TC-LABEL-67 | PASS | [실동작] ai-server 실효 mock(`weights_missing`) 상태에서 **200 + `detectedCount:0` + `labels:[]` + message `"AI 모델 미로드 — 결과 신뢰 불가"`**. 백엔드 로그 `[Autolabel] mock response — skip detection srcSn=296 source=mock reason=weights_missing`. ai-server 로그 `[DETECT:yolox][MOCK] returning mock track ... reason=weights_missing` | `:246-251` 라인 일치. 좌표 미반환 = 학습데이터 오염 차단 실증 |
| TC-LABEL-68 | PARTIAL | [정적] `DetectionBoxNormalizer.java:51-53` 개수≠4 → `IllegalArgumentException` → `AutolabelOnlineService.java:565-570` 에서 `INVALID_INPUT`(400) 로 **all-or-nothing** 승격(부분 반환 없음). 실동작 미도달(mock 단락) | 테스트 커버: `DetectionBoxNormalizerTest:106 "좌표개수가_4개가_아니면_거부한다"`, `AutolabelOnlineServiceTest:360 "형식위반은_여전히_all_or_nothing..."`(baseline 통과). 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-69 | PARTIAL | [정적] `DetectionBoxNormalizer.java:54-58` — **`Double.isFinite` 가드가 clamp 이전**에 위치(51~58 → 59~64). `NaN<0=false` 로 음수검사를 통과하던 회귀가 구조적으로 차단됨 | 테스트: `DetectionBoxNormalizerTest:115 "NaN_Infinity_좌표는_거부한다_역직렬화_500_회귀방지"`, `AutolabelOnlineServiceTest:325/336`. 라인 일치 → **C-ISSUE-44** |
| ~~TC-LABEL-70~~ | — | 폐기 케이스(2026-07-30, 좌표정책 반전) — 검증 대상 아님. 대체 TC-137/138 은 본 담당 범위 밖 | `AutolabelOnlineService.validateBbox` 부재 확인(Grep 무결과) = 폐기 사유 실측 정합 |
| TC-LABEL-71 | PARTIAL | [정적] `:258-264` — `detections.isEmpty()` 시 `reCheckLock(rawSn)` 후 빈 결과. 실동작에서는 mock 분기(`:246-251`)가 **더 앞에서** 단락해 이 분기에 도달하지 못함 | 테스트: `AutolabelPolygonServiceTest:289 "YOLO_박스0개면_SAM호출없이_빈결과_반환한다"`, `AutolabelOnlineServiceTest:415`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-72 | PARTIAL | [정적] `:296-301` `limit=min(detected, maxBoxes)`, `truncated=detected>maxBoxes`, `buildPolygonMessage`(:370-387) 로 고지. 설정 상한은 `ConfigKeys` 범위 `[1,100]` 강제(`AUTOLABEL_POLYGON_MAX_BOXES`)라 무제한 설정 불가 | 테스트: `AutolabelPolygonServiceTest:221`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-73 | PARTIAL | [정적] `:303` 단일 wall-clock deadline, `:313-319` 잔여 예산 ≤0 시 `truncated=true` + break(부분 반환). `callSam`(:522-524)이 `min(remaining, 60s)` 로 개별 block 상한 | 테스트: `AutolabelPolygonServiceTest:344`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-74 | PARTIAL | [정적] `:321-348` 박스별 try/catch — `seg==null \|\| polygon==null \|\| mock` 이면 `skipped++` + WARN, 성공분만 반환. `:370-387` `anyExcluded = anyMock \|\| skipped>0` 로 **비-mock 실패도 고지** | 테스트: `AutolabelPolygonServiceTest:238/257/272`. 근거 라인 `:370-380` → 실제 메서드 370-387(경미 드리프트) → **C-ISSUE-44** |
| TC-LABEL-75 | PARTIAL | [정적] `:338-343` `if (e.getErrorCode()==TOO_MANY_REQUESTS) throw e;` — 스킵 흡수 전에 즉시 전파. `:529-533` `callSam` 의 `BulkheadFullException` → 429 매핑. **간접 실증**: 같은 bulkhead(`aiOnline`, `max-concurrent-calls=4`)가 BBOX 경로에서 429 를 실제로 반환(TC-77) | 테스트: `AutolabelPolygonServiceTest:318 "폴리곤_배치중_bulkhead429는_즉시_전파되고_삼켜지지_않는다"`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-76 | PARTIAL | [정적] `:308-312` 루프 매 반복 `requireNotBlocked(rawSn)`, `:351-352` 응답 조립 직전 `reCheckLock`. **게이트 자체는 실동작 실증**: 락→409 / `'F'`→412 (TC-62 참조). 폴리곤 배치 *중간* 진입은 mock 때문에 미도달 | 테스트: `AutolabelPolygonServiceTest:303 "폴리곤_배치중_작업락걸리면_409로_차단한다"`, `AutolabelOnlineServiceTest:169/185`. 라인 일치 → **C-ISSUE-44** |
| TC-LABEL-77 | PASS | [실동작] 14개 서로 다른 프레임 × 3회 = 42 동시요청 → **200×4 / 409×29 / 429×9**. `application.yml:664-666` `aiOnline.max-concurrent-calls=4`, `max-wait-duration=0`(즉시 거부) | `:499-503` 라인 일치. bulkhead 가 실제로 fail-fast 함을 실증 |
| TC-LABEL-78 | PASS | [실동작] `docker pause klid-ai-server` 상태에서 호출 → **502** `{"success":false,"message":"YOLO 오토라벨 호출 실패","errorCode":"EXTERNAL_API_ERROR"}` — **스택·내부경로·URL 미노출**. 서버 로그에만 `err=Timeout on blocking read for 70000000000 NANOSECONDS`(sanitize 적용). unpause 후 즉시 200 복귀 = in-flight 락 해제 확인 | `:504-509` 라인 일치. CWE-209 방어 실증 |
| TC-LABEL-79 | PASS | [실동작] `confThreshold:0.9` → **400** `"인식 민감도는 0.80 이하여야 합니다."`, `0.1` → **400** `"인식 민감도는 0.25 이상이어야 합니다."` | `AutolabelRequest.java:39-40` 라인 일치 |
| TC-LABEL-80 | PASS | [실동작] `classes` 101개 → **400** `"클래스는 최대 100개까지 지정할 수 있습니다."`. 추가 반증: 원소 51자 → **400** `"classes[0]: 클래스명이 너무 깁니다."`(원소 단위 `@Size(max=50)` 도 실효) | `AutolabelRequest.java:36-37` 라인 일치 |

### 집계 (담당 30건 — 폐기 TC-70 제외)

| PASS | FAIL | PARTIAL | BLOCKED | N/A | 확인필요 |
|:--:|:--:|:--:|:--:|:--:|:--:|
| 20 | 0 | 10 | 0 | 0 | 0 |

- PARTIAL 10건(TC-60·68·69·71~76)은 **전부 동일 원인**: ai-server 모델 가중치 미탑재 → mock 단락으로 검출 결과 의존 분기에 실행이 도달하지 못함. 정적 대조 + 단위테스트(baseline 전량 통과) 로만 뒷받침되어 **거짓 PASS 를 피하기 위해 PARTIAL 로 낮춤**.
- **근거 file:line 드리프트**: 실질 드리프트 없음. 경미 2건 — TC-54 `:151-156`(실제 151-157), TC-74 `:370-380`(실제 370-387). 카탈로그 정정 불요 수준.

---

## 이슈

### [C-ISSUE-41] TC-LABEL-66 — ai-server 응답 클래스명은 매핑 화이트리스트로 재검증되지 않는다(방어심층 공백)
- **심각도**: LOW
- **기대 동작(기대효과)**: `resolveDetectClasses` 는 "FE 요청 불신"을 구현해 **요청측**을 재구성한다(실동작 PASS). 같은 서비스가 좌표에 대해서는 "외부(ai-server) 응답 불신"을 명시하고 정규화·거부까지 하는데(`normalizeDetections`, `validatePolygonPoints` javadoc `:543,:582`), **클래스명(label)** 만은 응답을 그대로 신뢰한다. 대칭을 맞추면 ai-server 버전 스큐·회귀로 필터가 무력화돼도 미매핑 클래스가 화면까지 흘러가지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431 toItems()` 가 응답 detection 을 그대로 아이템화한다. 매핑 조회는 하지만 **미매핑이면 차단이 아니라 `labelId=null` 로 통과**시킨다.
  ```java
  for (YoloResponse.Detection d : detections) {
      Long labelId = labelMasterService.findLabelIdByDtctType(d.label()).orElse(null);
      items.add(new AutolabelResponse.Item(
              null, labelId, d.label(), d.points(), clampScore(d.score()), d.trackId()));
  }
  ```
  폴리곤 경로 `:334-337` 도 동일. 현재는 ai-server 가 `yolo.py:44-57 _apply_class_filter` 로 한 번 더 걸러 실피해가 없다(`if not classes: return detections` — BE 는 항상 비어있지 않은 리스트를 보내므로 필터가 실효).
- **재현/확인 경로**: 코드 경로상만 재현 가능(현 스택에서는 ai-server 필터가 살아 있어 실동작 재현 불가). ai-server `_apply_class_filter` 를 우회/롤백한 버전이면 `POST /v1/frames/296/autolabel {"classes":["person"]}` 응답에 `{"labelId":null,"label":"dog",...}` 가 섞여 나올 수 있고, 저장 DTO `LabelItemDto.labelId` 가 nullable 이라 그대로 `LS_DATA_LBL` 에 적재될 수 있다.
- **영향**: CWE-20(불완전 입력 검증, 신뢰 경계 비대칭). 라벨 마스터 미등록 클래스가 학습데이터에 유입될 수 있는 잠재 경로. **CLAUDE.md 의 명문 정책("매핑 강제는 BE 가 담당 — FE 요청을 신뢰하지 않고 … ai-server 로 전달")은 요청측만 요구하므로 정책 위반은 아니다** → FAIL 이 아닌 관찰.
- **수정 방향(제안)**: `toItems`/폴리곤 루프 진입 전에 `effectiveClasses`(또는 `mappedDetectClasses()`) 를 응답 필터로 한 번 더 적용하고, 제외 건은 `drop unmapped detect class` 와 같은 WARN 으로 계상. 구현은 하지 않음.

### [C-ISSUE-42] TC-LABEL-60/72 — 검출 건수만큼 `findLabelIdByDtctType` DB 왕복(N+1), 캐시 없음
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 라벨링 화면 툴바에서 프레임마다 호출되는 대화형 경로다. `rules/performance.md` 는 반복 단건 조회를 금지하고 일괄 조회를 요구한다. 검출 N건이면 매핑 조회도 1회로 끝나야 한다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:423-431`(BBOX) 과 `:334`(POLYGON) 이 **검출 1건마다** `labelMasterService.findLabelIdByDtctType()` 를 호출한다. 이 메서드는 `@Transactional(readOnly=true)`(`LabelMasterService.java:150-157`) 라 **호출마다 트랜잭션·커넥션 획득 + `SELECT ... WHERE dtct_type_cd=? AND use_yn='Y'` 1회**가 발생한다. 캐시(`@Cacheable`) 없음. 같은 요청에서 이미 `mappedDetectClasses()`(`:166-168`)로 전체 매핑을 읽었는데도 재사용하지 않는다.
  - 상한: BBOX 는 YOLO 검출 수 제한이 없어 혼잡 프레임이면 수십~수백 회. POLYGON 은 `maxBoxes`(최대 100)까지.
- **재현/확인 경로**: 가중치 탑재된 ai-server 로 `POST /v1/frames/{srcSn}/autolabel` 호출 후 `p6spy`/`hibernate.SQL` DEBUG 로 `ls_label` 조회 횟수 = 검출 건수인지 확인. 현 스택은 검출 0건이라 실측 불가(정적 판정).
- **영향**: 성능/자원(커넥션 풀). 서비스가 `non-transactional` 을 택한 이유가 "AI 블로킹 호출 중 HikariCP 커넥션 미점유"(`:48-50`)인데, 응답 조립 단계에서 커넥션을 N회 붙잡아 그 의도를 부분 상쇄한다. 동시 라벨러 다수 + 혼잡 프레임에서 풀 압박.
- **수정 방향(제안)**: `LsLabelRepository` 에 `findByDtctTypeCdInAndUseYn(Collection<String>, String)` 를 추가해 검출 라벨 집합을 **1회 조회 → `Map<String,Long>`** 으로 만들고 루프에서 조회. 또는 `mappedDetectClasses()` 를 `Map<String,Long>`(코드→labelId) 반환으로 확장해 이미 읽은 결과를 재사용. 구현은 하지 않음.

### [C-ISSUE-43] TC-LABEL-64 — 매핑 0건이어도 프레임 이미지 base64 인코딩을 먼저 수행
- **심각도**: LOW
- **기대 동작(기대효과)**: "매핑된 라벨이 없으면 ai 미호출 + 빈 결과"(TC-LABEL-64) 는 **아무 작업도 하지 않고 즉시 반환**하는 것이 자연스럽다. 프레임 원본 이미지 읽기 + base64 인코딩은 이 경로에서 전혀 쓰이지 않는다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:222` 가 `resolveDetectClasses`(`:228`) 보다 **먼저** 실행된다.
  ```java
  String imageB64 = frameImageEncoder.encodeFrame(src);   // :222
  ...
  List<String> effectiveClasses = resolveDetectClasses(classes);   // :228
  if (effectiveClasses.isEmpty()) { ... return 빈 결과; }           // :229-235
  ```
  실동작 확인: `{"classes":["dog","zebra","toothbrush"]}` 요청이 ai 호출 0회로 끝났으나(로그 `no mapped detect classes`), 그 직전에 프레임 이미지 인코딩은 수행됨.
- **재현/확인 경로**: `curl -X POST http://localhost:18081/api/v1/frames/296/autolabel -H "Authorization: Bearer $WK" -d '{"classes":["dog"]}'` → 200 + `NO_MAPPED_CLASS_MESSAGE`. 응답 지연이 NAS I/O + 이미지 크기에 비례.
- **영향**: 성능/자원(불필요 NAS I/O + 힙 상 base64 문자열). 매핑이 하나도 없는 초기 운영 구간(마이그레이션 직후 전 라벨 `DTCT_TYPE_CD=NULL`)에서 전 요청이 이 경로를 타므로 체감 가능.
- **수정 방향(제안)**: `resolveDetectClasses` → 빈 결과 조기 반환 블록을 `encodeFrame` **앞으로** 이동. 단 `encodeFrame` 이 신고 게이트도 겸하므로(`:56-59` javadoc), 이동 시 `requireNotBlocked` 가 이미 `:214` 에서 선행 수행됨을 확인할 것. 구현은 하지 않음.

### [C-ISSUE-44] TC-LABEL-60/68/69/71~76 — ai-server 모델 가중치 미탑재로 검출 의존 분기 10건 실동작 검증 불가
- **심각도**: MEDIUM (환경/검증 커버리지 결함 — 프로덕션 코드 결함 아님)
- **기대 동작(기대효과)**: 온라인 오토라벨의 핵심 위험 구간(좌표 정규화 400/clamp/스킵, 폴리곤 상한·예산·부분실패·TOCTOU)은 **실제 검출 결과 위에서** 판정돼야 한다. 이 프로젝트의 실패 모드는 "코드가 있으니 PASS"이므로, 검출이 0건이면 해당 단언들은 반증 시도 자체가 불가능하다.
- **현재 동작(이슈 내용)**: `klid-ai-server` 는 `AI_MOCK_MODE=false` 이나 YOLO 가중치 파일이 없어 mock 으로 폴백한다.
  ```
  ai-server: WARNING:app.routers.yolo:[DETECT:yolox][MOCK] returning mock track
             (model not loaded or AI_MOCK_MODE=true) reason=weights_missing
  backend  : [Autolabel] mock response — skip detection srcSn=296 source=mock reason=weights_missing
  ```
  BE 는 mock 을 정상적으로 차단(`:246-251`, TC-67 PASS)하므로 그 **뒤에 있는** `normalizeDetections`(`:256`) · 빈검출 분기(`:259`) · `polygonAutolabel`(`:293~`) 전부가 도달 불가 코드가 된다. `pipeline-drive.md` 의 배치 YOLO 도 같은 사유로 `yoloCount=0`.
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel` → 항상 `detectedCount:0` + `"AI 모델 미로드 — 결과 신뢰 불가"`. `docker logs klid-ai-server | grep weights_missing`.
- **영향**: 기능/검증. 좌표 clamp·퇴화 스킵·all-or-nothing 400·폴리곤 예산/상한/부분실패/TOCTOU 등 **10개 케이스가 실환경에서 한 번도 실행된 적이 없다**. 단위테스트(mock 주입)로만 커버되므로 실제 ai-server 응답 스키마 변화(필드명·좌표 순서)에 대한 회귀 감지력이 없다 — 본 검증 기준의 "self-fill / 계약 불일치" 관심사와 직결.
- **수정 방향(제안)**: ①로컬 검증 스택에 YOLOX ONNX 가중치를 마운트하거나 다운로드 스텝을 `docker-compose.local.yml` 에 추가 ②또는 mock-server 처럼 **결정적 검출 결과를 돌려주는 ai-server 스텁 모드**(mock 플래그 없이 고정 좌표 반환)를 검증 전용으로 도입해 위 분기를 실제로 태울 것. 구현은 하지 않음.

### [C-ISSUE-45] TC-LABEL-65 — 검출 클래스 화이트리스트 매칭이 대소문자 구분이라 대문자 요청이 조용히 0건이 된다
- **심각도**: LOW
- **기대 동작(기대효과)**: 화이트리스트 매칭은 fail-closed 여야 하고(현재 그러함), 동시에 **정상 사용자가 이유를 알 수 있어야** 한다. 요청 전부가 케이스 불일치면 사용자에게는 "매핑을 등록하라"는 안내가 뜨는데 실제 원인은 케이스 표기다.
- **현재 동작(이슈 내용)**: `AutolabelOnlineService.java:466-473` 이 `trim()` 만 하고 `mapped.contains(trimmed)` 로 **정확 일치** 비교한다.
  ```java
  String trimmed = c == null ? null : c.trim();
  if (trimmed != null && mapped.contains(trimmed)) { allowed.add(trimmed); }
  else { log.warn("[Autolabel] drop unmapped detect class={}", LogSanitizer.sanitize(trimmed)); }
  ```
  실동작: `"PERSON"` → `drop unmapped detect class=PERSON`(WARN), `" car "` → trim 후 통과. 요청이 `["PERSON"]` 뿐이면 응답은 `NO_MAPPED_CLASS_MESSAGE`("라벨 관리에서 AI 검출 클래스를 매핑해 주세요") 로 **원인과 다른 안내**가 나간다.
  - 비교 대상 `LS_LABEL.DTCT_TYPE_CD` 는 저장 시 `CocoClasses` allowlist 로 정규화되므로 항상 소문자 canonical 이다(`LabelMasterService.java:126-135`).
- **재현/확인 경로**: `curl -X POST .../v1/frames/296/autolabel -d '{"classes":["PERSON"]}'` → 200 `detectedCount:0` + "검출할 수 있는 라벨이 없습니다. 라벨 관리에서 AI 검출 클래스를 매핑해 주세요."
- **영향**: 기능/사용성. 보안 영향 없음(fail-closed). 정상 FE 는 `GET /v1/manage/labels/detect-candidates` 가 준 canonical 값을 그대로 보내므로 실사용 발생 확률은 낮으나, 외부 스크립트·수기 호출에서 오진단 유발.
- **수정 방향(제안)**: ①`mapped` 를 소문자 정규화 Set 으로 만들고 `trimmed.toLowerCase(Locale.ROOT)` 로 비교(통과 시 canonical 값을 ai-server 로 전달) 또는 ②drop 이 1건 이상인데 결과가 비면 `NO_MAPPED_CLASS_MESSAGE` 대신 "요청한 클래스가 매핑되지 않았습니다" 로 구분 안내. 구현은 하지 않음.

---

## 확증편향 반증 시도 기록 (PASS 로 남은 항목들의 반증 근거)

| 반증 가설 | 시도 | 결과 |
|---|---|---|
| FE 가 임의 클래스를 요청하면 ai-server 로 전달된다 | `["person","hack","dog","PERSON"," car "]` 전송 후 백엔드 WARN·ai-server 호출수 관찰 | **반증 실패(방어 유효)** — hack/dog/PERSON drop, 나머지만 전달 |
| 전부 미매핑이면 그래도 ai 를 호출한다 | `["dog","zebra","toothbrush"]` 전송 + ai-server 누적 호출수 delta 측정 | **반증 실패** — delta 0, ai 미호출 |
| COCO 유효 클래스면 마스터 미매핑이어도 통과한다 | `zebra`/`toothbrush`(유효 COCO, 마스터 미매핑) 전송 | **반증 실패** — drop. allowlist 축은 COCO 가 아니라 `DTCT_TYPE_CD` |
| 온라인 오토라벨이 DB 에 라벨을 남긴다(미저장 정책 위반) | 약 50회 호출 후 `ls_data_lbl` (rawSn=26) 카운트 | **반증 실패** — 0건 유지 |
| 신고 구간(`'F'`) 영상도 오토라벨이 실행된다 | rawSn=33 `de_ident_yn='F'` 로 전환 후 호출 | **반증 실패** — 412 `PRECONDITION_FAILED` |
| 락·신고 동시 성립 시 응답 코드가 뒤바뀐다(규약 변경) | 락+`'F'` 동시 → 409, 락만 해제 → 412 | **반증 실패** — javadoc 규약(작업락 우선) 그대로 |
| in-flight 락이 예외 경로에서 새어 영구 409 가 된다 | 429(bulkhead)·502(ai 다운)·409(중복) 직후 재요청 | **반증 실패** — 전부 200 복귀(`finally` 해제 유효) |
| bulkhead 가 설정만 있고 실효하지 않는다 | 14 프레임 × 3회 = 42 동시요청 | **반증 실패** — 429 9건 실제 발생 |
| ai-server 장애 시 스택/내부경로가 응답에 샌다 | `docker pause klid-ai-server` 후 호출 | **반증 실패** — 502 + 고정 문구만, 스택·URL 미노출 |
| 활성 라벨에 같은 COCO 매핑을 2건 심을 수 있다 | DB 직접 INSERT(`dtct_type_cd='person'`, `use_yn='Y'`) | **반증 실패** — 부분 유니크 인덱스가 차단 |
| 라벨 삭제가 hard delete 다 | `DELETE /v1/manage/labels/20` 후 행 조회 | **반증 실패** — 행 존속 + `use_yn='N'` |
| 라벨명 근사중복(대소문자·공백)이 통과한다 | `" PERSON "` 로 수정 시도 | **반증 실패** — 409 (`LOWER(TRIM())` 비교) |
| `classes` 원소 길이 제한이 리스트 크기 제한에 가려 무력하다 | 원소 1개·51자 전송 | **반증 실패** — `classes[0]` 단위 400 |
