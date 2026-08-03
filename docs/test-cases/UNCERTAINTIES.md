# 확정 정책 + 확인 필요 항목

> 검증 시 PASS/FAIL 판정 기준. **케이스 표의 기대결과와 이 문서가 어긋나면 이 문서가 우선**이며,
> 이 문서와 루트 `CLAUDE.md` 가 어긋나면 `CLAUDE.md` 가 우선입니다.
> 최초 작성 2026-07-25 · 갱신 2026-07-30(1회차 최신화) · **갱신 2026-08-03(★4·★5 추가 — 2026-08-03 사용자 확정 5건 반영)**

---

## ★ 확정 정책 — 결함으로 재보고하지 말 것 (Critical)

> 아래 5건은 여러 라운드의 시행착오 끝에 **의도적으로 그렇게 만든 것**입니다.
> 검증자가 "비일관" 또는 "누수"로 보고 다시 고치려 드는 일이 반복돼 별도 절로 못 박습니다.

| ★ | 정책 | 왜 이렇게 두는가 | 어기면 생기는 일 |
|:--:|------|------|------|
| **★1** | **비식별 신고 게이트는 자기 rawSn 행 하나만 판정한다** (`DE_IDNTF_YN='F'` 단일 컬럼). 조상/자손 전파 없음. 파생영상(`ORGNL_RAW_SN` non-null)은 **신고 접수 자체를 412로 거부**하고, 원본 신고는 파생에 아무 영향을 주지 않는다. | 파생본은 부모 산출물의 복사본이라 **재비식별할 수단이 없다.** 접수해도 해소 불가능한 신고(작업락 + `'F'` 고착)만 남는다. 조상/자손 전파는 2026-07-28~29 **4라운드에 걸쳐 시도 후 전부 철회**됐다. | 차단 범위만 넓어지고 복구 범위와 계속 어긋나 export 영구 정체 · 자손 팬아웃 상한 초과 시 **신고 0건인 정상 트리가 영구 fail-closed(DoS)**. |
| | **귀결: '파생 경유 열람'은 결함이 아니다** — 부모가 신고 구간이어도 파생 rawSn 은 `'Y'` 라 게이트를 통과하므로, 원본에서 412 로 막히는 라벨·프레임을 파생 경유로 볼 수 있다. | ★1의 필연적 귀결이며 사용자가 인지·감수한 사항. | 막으려면 "파생본 재비식별 수단"부터 만들어야 한다. |
| **★2** | **목록 API 의 미등록 정렬 키 응답이 엔드포인트별로 다르다** — `/v1/tasks/board*` = **strict 400** / `/v1/reviews*` · `/v1/videos` = **lenient 200 + 기본 정렬 폴백**. | 기준은 "변경 전에 그 엔드포인트가 200 이었는가". 작업목록은 원래 `Pageable` 을 받아 잘못된 키면 500 이었으므로 400 이 개선이고, 검수목록은 `sort` 를 받지도 않아 **항상 200** 이었다. | 검수목록에 400 을 내면 FE 가 URL 에 보존·재전송하는 `sort` 때문에 **북마크·뒤로가기에서 목록 전체가 죽는다.** 회귀 가드: `ListApiBackwardCompatibilityIT`(11건). |
| **★3** | **좌표 검증이 2축으로 갈린다** — ①사용자 저장 경로(`LabelService.validateWithinBounds`) = **이미지 경계 초과 시 400 거부**(클램프 아님) ②AI 검출 응답 경로(`DetectionBoxNormalizer`) = **clamp + 퇴화 박스 검출 단위 스킵**. | 사용자 입력은 잘못된 좌표를 조용히 고치면 안 되고(작업자가 의도한 위치가 왜곡됨), 실모델 YOLO 의 경계 음수 좌표는 **정상 출력**이라 거부하면 프레임 80% 가 400 이 된다. | 한쪽으로 통일하면 둘 중 하나가 반드시 깨진다. |

| **★4** | **라벨명은 라벨 마스터(`LS_LABEL`) 등록명을 그대로 표시한다** — 코드 한글 사전(`COCO_LABEL_KO`)·레거시 `LABEL_CLASS_DEFS` 치환은 **폐기**. `car`·`VEHICLE` 로 등록된 라벨이 화면에도 그대로 보이는 것이 정상. (2026-08-03 확정, 커밋 `e58aa086`) | 코드 사전은 마스터와 어긋나는 **두 번째 진실원**이 되고, 사전에 있는 라벨만 한글이라 화면이 오히려 뒤섞인다. 한글로 보이려면 **마스터에 한글로 등록**한다(마스터 단일 진실원 원칙). ⚠ 이 규칙은 **바로 앞 커밋 `d8a7a2cc` 가 넣은 "한글 우선 표시"를 되돌린 것**이다 — 사전 치환을 결함 수정 명목으로 되살리지 말 것. | 마스터에서 이름을 바꿔도 사전에 걸린 라벨만 화면이 안 바뀌는 드리프트가 재발한다. 회귀 가드: `utils/__tests__/labelDisplayName.test.ts` → [TC-FE-293~296](H-frontend-e2e.md). |
| **★5** | **영상 목록(`GET /v1/videos`) 검색 필터의 오류 처리가 비대칭이다** — 검색어 100자 초과·날짜 형식 오류·`from>to` 는 **400**, **미등록 `eventTypeCd` 는 400 이 아니라 0건**. (2026-08-03 확정) | ★2 와 같은 축("변경 전에 200 이었는가")에 **"이 값 하나로 목록 전체가 죽는가"** 가 더해진다. 날짜·길이 오류는 빈 목록으로 두면 '검색 결과 없음'과 구분되지 않아 사용자가 입력 오류를 모른다. 반면 이벤트 코드는 **북마크·뒤로가기 URL 에 담겨 재전송**되므로 400 이면 목록 진입 자체가 막힌다. | 한쪽으로 통일하면 둘 중 하나가 깨진다 — 전부 400 이면 옛 URL 진입 불가, 전부 0건이면 잘못된 날짜 입력이 침묵한다. 회귀 가드: `VideoListSearchFilterIT` → [TC-VIDEO-005·007·011·012](B-batch-deidentify.md). |

> "일관성"을 이유로 ★2·★3·★5 를 통일하지 말 것. ★4 는 "결함 수정"으로 되돌리지 말 것.

---

## 2026-08-03 사용자 확정 5건 — 케이스 반영 위치

| 결정 | 내용 | 커밋 | 케이스 |
|:--:|------|------|------|
| 1 | 라벨링 '메타' 탭 **시계열 메타 검토 블록 제거**(상태 배지·승인/반려·반려사유). 텍스트 수정·저장만. **BE `POST /v1/meta/{metaReviewSn}/approve\|reject`·`LS_DATA_META_REVIEW` 는 존치 — FE 진입점만 없음.** 검토 상태 확정의 유일 경로 = 영상 검수 승인 시 자동 동결 | `80171828` | [TC-FE-276~278](H-frontend-e2e.md) · 정정 TC-FE-079·268 · [TC-REVIEW-016](D-review-version-notify.md) |
| 2 | 영상 상세 **'버전관리로 이동'·라이트박스 '라벨링 편집' 버튼 제거** + **`/history/:videoId`(SC-010) 페이지·라우트 삭제**. ⚠ **기능 손실 없음** — 인라인 `HistoryPanel` 이 변경이력·버전(diff·롤백)을 모두 제공하고 `features/version/**` 유지 | `b27b3108` | [TC-FE-297~300](H-frontend-e2e.md) · 정정 TC-FE-087 · [D-4/D-5 머리말](D-review-version-notify.md) |
| 3 | **도형 도구 클릭 → 라벨 선택 모달 → 드로잉**. 좌측 상시 라벨 패널(`LabelSidebar`) 폐지, **전역 1~9 단축키 제거**(모달 전용), `KeypointGuide` 우측 패널 최상단 이동. 목록 소스 = **라벨 마스터 전체**(프리셋 아님) | `d8a7a2cc` | [TC-FE-279~292](H-frontend-e2e.md) · [C-6 머리말](C-marking-labeling.md) |
| 4 | **라벨명 = 마스터 등록명 그대로**(코드 사전 치환 폐지) — ★4 | `e58aa086` | [TC-FE-293~296](H-frontend-e2e.md) |
| 5 | 영상 목록 **검색 필터 4종 신설**(`cctvNameKeyword`·`eventTypeCd`·`from`·`to`) + **`capturedAt` = `SHT_DT`** 축 정정 — ★5 | (커밋 대기) | [TC-VIDEO-001~018](B-batch-deidentify.md) · [TC-FE-301~303](H-frontend-e2e.md) |

> 이 5건은 **미확정 항목이 아니라 확정 정책**이다. 아래 "확인 필요" 표에 넣지 않는다.

---

## 1차(2026-07-25) 확정 항목 — 이번 회차 상태 변화

| # | 항목 | 07-25 확정 | **07-30 현재** |
|---|------|------|------|
| 1 | **포털 SAM2 구현·노출** vs 문서 "미제공"(F) | 문서가 정본 — 제공 안 됨. 노출은 **정책 위반 → 결함 플래그** | **판정 유지**(여전히 정책 위반). 단 **심각도 완화** — 이제 원본이 아니라 **비식별본만** ai-server 로 전송되고(`encodeDeidentifiedFrameForInference`, 게이트 없는 `encodeToBase64(String)` 쌍둥이 삭제) 신고 게이트도 걸린다 |
| 2 | 마킹단계 rawSn 신고 미구현(B) | 미확정 · 미구현 갭으로 기록 | **✅ 해소 — 구현됨.** `POST /v1/videos/{rawSn}/deident-report`. `doReport()` 공용 본체로 srcSn 경로와 부수효과 5종 동일. 파생 412 · 비식별 미수행(`'N'`) 412 |
| 3 | **TASK_COMPLETED payload 0/null 하드코딩**(D) | 실카운트(`totalFrames`/`labeledFrames`/`reviewerName`) 구현 필요 — 결함 | **✅ 해소 — 단 확정 기대값 자체가 무효.** 그 세 필드는 **계약에서 사라졌다.** 현재는 6필드 평면 snake_case(`job_id`·`event_type_cd`·`lclgv_cd`·`lclgv_nm`·`duration_sec`·`image_count`)이며 전부 DB 실측(상수 self-fill 0건). 경로도 `POST /api/data-set/v2/jobs/{job_id}/notify-completed\|notify-updated` 로 교체 |
| 4 | 관제 조회 API IDOR 세분화 부재(D) | **의도된 광범위 허용** → IDOR 케이스 제외 | **🔄 반전 — 지침 폐기.** 세 경로 모두 `LabelAccessGuard.verifyRawAccess` 적용. IDOR 케이스를 **다시 검증 대상에 포함**한다(TC-NOTIFY-052) |
| 5 | `portal.upload.frame-interval-sec` 60 vs 5(F) | 미확정 | **✅ 확정 = 5초.** DB 시드(`V109__create_ls_portal_uld.sql:101`)와 코드 폴백(`DEFAULT_INTERVAL_SEC`)이 일치 |

## 기대값·스펙 확정 필요 — 이번 회차 상태 변화

| # | 항목 | 클러스터 | **07-30 현재** |
|---|------|:---:|------|
| 6 | RoleHierarchy 빈 계층(`fromHierarchy("")`) | A | **미해소 유지.** 여전히 빈 계층이고 **이 빈을 참조하는 코드가 0건**(사실상 no-op). 빈 제거 vs 배선 중 정책 확정 필요 |
| 7 | Logback 마스킹 레이아웃 실체 | A | **✅ 해소.** 죽은 `conversionRule` 제거 후 `LayoutWrappingEncoder`+`MaskingPatternLayout` 배선, `LocalLogMaskingIT` 종단 검증. 잔여: 런타임 프로브가 비결정적(마스킹 대상 로그 미발생) |
| 8 | Quartz 클러스터링 실제 활성 | B | **✅ 확정.** 공통 `application.yml` 은 `${QUARTZ_CLUSTERED:false}`(local/dev 단일 노드)이나 **stg/prd 는 기본 `true`**, `QuartzClusteringGuard`(`@PostConstruct`)가 배포 환경에서 false 면 **기동 거부**. 온프렘 `env.template:226` 도 `true`. ⚠ 클러스터링은 **트리거 중복 발화만** 막고 잡 내부 레이스는 원자 클레임이 별도로 막는다(상호 대체 불가) |
| 9 | 동시 저장 Race(라벨 full-replace) | C | **✅ 확정.** 낙관적 버전 토큰 `labelVersion`(요청 **선택 필드** — 미첨부 시 검사 skip = 하위호환) + 프레임 행 비관적 락. 락 획득 시점 DB 값과 CAS(스칼라 프로젝션으로 1차 캐시 우회), 불일치 시 **409**. 무변경 저장은 버전 미증가 |
| 10 | 좌표 이미지 경계 초과 저장 검증 부재 | C | **✅ 확정 — 단 2축으로 갈린다. ★3 참조** |
| 11 | 다운로드 기간 제한("본인 데이터 기간 내") | F | **미해소 유지.** 해당 검증 로직 여전히 없음 |
| 12 | 데이터마트 저장/조회·이미지 서빙 rate limit 부재 | F | **미해소 유지(부재 확인).** `PortalLabelService`(user-labels 저장/조회, 데이터마트 목록, `serveFrameImage`)에 없음. 업로드·SAM2·TUS 에만 존재 |
| 13 | VLM 45s 타임아웃·콜백·IntelliVIX v2.0.1 계약 | B·G | **🔶 부분 해소.** BE `VlmClient` → mock-server 실배선 확정(`/v1/videovlm/describe`, request_id echo, 4xx 비재시도). **타임아웃은 이중 구조** — 블록 상한 45s ↔ 실효 클라이언트 타임아웃 10s(단일 45s 로 적힌 기대값은 오류). 미확정은 **IntelliVIX 실서버(목 아님) v2.0.1 대조**로 좁혀짐 |
| 14 | imgsz 무효(640 고정) 실효 검증 방법 | G | **미해소 유지.** ai-server 는 07-25 이후 커밋 0건 |
| 15 | 실모델 테스트 게이팅 | G | **미해소 유지.** 동상 |

## 미열람 파일 — 이번 회차 해소

| # | 파일/영역 | 클러스터 | **07-30 현재** |
|---|-----------|:---:|------|
| 16 | ExternalAugmentClient/Noop 실연동 | E | **✅ 해소.** `HttpExternalAugmentClient`(`mode=http` 기본, `matchIfMissing=true`)가 활성, `NoopExternalAugmentClient` 는 `mode=noop` 전용. `syncDecision` 은 양 구현 모두 no-op |
| 17 | AsyncAugmentFrameRunner/AugmentFrameProducer | E | **✅ 해소.** A/B/C 3빈 분리. Phase B 는 **외부 산출물 반입**이며 구 ffmpeg 재추출은 폐기(재추출본이 부모와 픽셀 동일해 증강 효과가 0이던 결함) |
| 18 | DerivedMetaCopier 메타 전체복사+검수행 | E | **✅ 해소.** `video.*` 전체 복사가 **정상**(재인코딩 없이 부모 비식별본을 복사하므로 기술메타가 같아야 한다). 부모 검수행 보유 메타키만 PENDING 검수행 생성, **확정 블록 이후 호출** 계약 |
| 19 | DatasetExportWriter/TxService | E | **✅ 해소.** 전 메서드 REQUIRES_NEW, `finalizeUnlessUnderDeidentReport`(마감 직전 RAW 잠금 재판정 → 차단 시 export 행 삭제), `claimForRetry` 조건부 UPDATE |
| 20 | event_annotation 승인 동결 소스·cot 변환 | E | **🔶 부분 해소.** export 최상위 키는 `event_annotation` → **`event`** 로 확정. 동결 소스는 `LS_DATASET_VIDEO_META.EVNT_ANNO_CN`, `JsonNode` pass-through 라 배열/객체 양형 보존. ⚠ **`EvntAnnoService` 재동결 미배선**은 `CLAUDE.md` 예외 조항 그대로 |
| 21 | KpstDeidentTxService 상태전이·락해제 원자성 | B | **✅ 해소.** `tryClaimPoll`(리스 기반 조건부 UPDATE) + `claimDownloadCompletion`(완료 전이 자체가 클레임 → 프레임 이중 attach 차단) |
| 22 | 캔버스 드로잉 도구 픽셀 정확도 | H | **미해소 유지.** 런타임 브라우저 도구 필요 |
| 23 | VideoPlayer 배속(0.25~4x) UI·경계 클램프 | H | **✅ 확정.** `SPEED_OPTIONS=[0.25,0.5,1,1.5,2,4]` **이산 6버튼**, 기본 1x. **자유 수치 입력이 없어 클램프 로직이 불필요**(경계 클램프 케이스는 성립하지 않음) |
| 24 | 반응형·WCAG 색대비(4.5:1) | H | **미해소 유지.** 런타임 도구 필요 |
| 25 | DevAutolabelTestPage/내부 TUS dead-code 여부 | H | **✅ 확정.** 내부 TUS(`features/upload/*`)의 **유일한 소비자가 `DevAutolabelTestPage`** 이고 그 라우팅은 `isDevUploadEnabled()` 빌드 플래그 안에만 있다 → **prod 빌드 기준 dead-code 맞음 / dev 빌드에서는 살아있음.** 별도 폐지 작업 불필요, 플래그 게이팅만 검증. (포털 TUS `/portal/uploads/tus` 는 별개 운영 경로) |

## 신규 확인 필요 항목 (2026-07-30 추가)

| # | 항목 | 클러스터 | 내용 |
|---|------|:---:|------|
| 26 | **관제 통지에 인증 헤더 미부착** | D | 관제 계약은 `x-access-token` 을 요구하나 `WebClientConfig` 에 배선이 없다 → **연동 갭**(실왕복 시 401 예상). 관제팀 협의 대상 |
| 27 | **관제 `event_type_cd` 코드값 목록 미수령** | D | 8대 코드 매핑표를 받지 못해 현재 보유값을 그대로 전송(`toControlEventTypeCd` 1곳). 값 정합 미확인 |
| 28 | **HSTS 가 앱·edge 어디에서도 부여되지 않음** | A | `nosniff`/`X-Frame-Options` 는 전 응답 부여되나 HSTS 는 HTTPS 요청 조건이라 실제로 미부여 상태. edge(nginx/Caddy) 설정 확인 필요 |
| ~~29~~ | ~~**`POST /v1/videos/resolution-backfill` 프로파일 제한 없음**~~ | E | **해소(2026-07-30) — 기능 전체 제거.** 프로파일 가드로 노출만 좁히려다, 이 API 가 구 스킴 산출물을 정정하는 **1회성 배치**이고 대상 데이터(생성 창 2026-07-22~07-26)가 dev 에서 이미 소진·stg/상용엔 부재임이 확인돼 서비스·스윕잡·컨트롤러·DTO·설정키를 들어냈다. 노출 표면 자체가 사라짐 |
| ~~30~~ | ~~**해상도 백필 스윕에 DB 조건부 UPDATE 클레임 없음**~~ | E | **소멸(2026-07-30)** — 스윕 잡이 백필과 함께 제거돼 판정 대상이 없다. (원 지적은 동작 결함이 아니라 규약 비정합이었다 — 삭제가 `deleteIfExists` 로 멱등이라 2노드 동시 발화도 결과가 같았다) |
| ~~31~~ | ~~`ResolutionBackfillSweepJob` 조건부 등록~~ | E | **소멸(2026-07-30)** — 잡·설정키(`authoring.resolution-backfill.*`) 모두 제거 |

## 미해소 이월 이슈 (1차 검증 발견 · 이번 회차에도 남음)

| 이슈 | 내용 | 케이스 |
|------|------|------|
| B-ISSUE-23 | AUTO 마킹 `intervalFrames` 상한 미검증 | TC-BATCH-096(현재 동작 고정) |
| B-ISSUE-63 | `/v1/videos/{rawSn}/stream` 영상 단위 배정 인가 부재 | TC-STREAM-B15 비고 |
| B-ISSUE-42 | 오토라벨 일괄저장 — **부분 해소**(왕복 감소만, IDENTITY PK 유지로 JDBC 배치 비활성) | TC-BATCH-196 |
| C-ISSUE-23 | R7 손상 JSON 회귀 테스트 부재 | — |
| C-ISSUE-82 | 동명이인 dead `common/util/TrackInterpolator` 잔존 | — |
| D-ISSUE-03 | `verify(null)` NPE 500 | — |
| D-ISSUE-05 | IN_REVIEW 상태 재배정 허용 | TC-ASSIGN-026 |
| D-ISSUE-27 | `findByHashOrThrow.get(0)` 다중 해시 비결정 선택 | TC-DIFF-026 |
| D-ISSUE-50 | 승인→롤백 뷰 정합 IT 미비 | TC-MARTVIEW-014 |
| A-ISSUE-01 | `UserRoleResolver.resolve(null)` 프록시 경유 캐시 키 NPE(실경로 미도달, LOW) | TC-AUTH-027 |
| B-ISSUE-41 | 07-22 이전 레거시 프레임 `DE_IDNTF_SRC_FILE_PATH_NM` NULL 백필(스코프 밖) | — |
