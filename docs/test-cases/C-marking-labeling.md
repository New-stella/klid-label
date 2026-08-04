# C. 마킹 + 라벨링 — 테스트 케이스

> **275 케이스**(실측 — 표 행 수) · 계층: unit / integration / security · 우선순위 P0(Critical)~P2 · [← README](README.md)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|---|--:|--:|--:|---|
| 1 | 2026-07-30 | 212건 | 61건 | 2건 | 07-25 1차 검증 이후 Phase 4/6/7 + 좌표정책 일원화(6d1b3703)·라벨 보존 정책 반전(b0647c4c)·마킹 활성 1건(41b0504d)·`/deid-image` 신설(27977e72)·포털 SAM2 원본 전송 차단(3630558d) 반영. **비식별 신고 = 라벨 보존 + 조회 게이트 412**(구 "전량 삭제 + 스냅샷" 폐기), **AI 검출 좌표 = clamp/퇴화 스킵**(구 "음수 400 all-or-nothing" 폐기), **신고 게이트 판정 = 자기 rawSn 행 하나**(조상/자손 전파 도입 후 철회 — 재도입 금지). 근거 `file:line` **212건 전량 재확인**(그중 기대결과·전제가 실제로 바뀐 건 33건, 나머지는 라인 드리프트 정정). **신설 `GET /v1/frames/{srcSn}/deid-image` 7건 + `Cache-Control: no-store` C 소관 3경로**(TC-LABEL-141~149) 포함 |
| 2 | 2026-08-03 | 1건 | 0건 | 0건 | **결정 3 의 파급만 반영**(케이스 신설·폐기 없음, BE 무변경). 라벨링 화면의 라벨 선택 표면이 좌측 상시 패널 → **라벨 선택 모달**로 바뀌면서 목록 소스가 **라벨 마스터 전체**로 못 박혔다 → **C-6 절 머리말에 "프리셋은 오토라벨링 전용, 수동 라벨링 선택 목록 아님" 경계 명시**(FE 상세는 [H-3 하위 절](H-frontend-e2e.md) TC-FE-279~292) |
| 3 | 2026-08-03 | 78건 | 2건 | 0건 | **근거 `file:line` 전수 재확인 회차** — 273행 전량 대조. 라인 드리프트 57건 정정(마킹 15·라벨/오토라벨 8·비식별프레임서빙 9·SAM2 23·YOLO트랙 1 — 대부분 리팩터링·메서드 재배치로 인한 위치 이동, 동작 자체는 불변) + 기대결과 정정 1건(TC-SAM2-23: `Sam2TrackService` 의 ai 응답 폴리곤 검증은 `Sam2CoordinateValidator` 가 `INVALID_INPUT`(400)을 던지며 `EXTERNAL_API_ERROR`(502) 아님 — 구 기재 오류 정정, "정점부족" 조건은 이 경로에 없음도 명시). `Sam2TrackRequest.java`/`YoloTrackRequest.java` 동명이인 basename 정합(TC-SAM2-16~20 → `label/Sam2TrackRequest.java` 명시). `FrameImageController.java` 초과 라인(TC-LABEL-141/143/148) 은 리팩터링으로 판정이 `FrameImageService`/`FrameImageLookupService` 로 이동한 결과였음을 확인해 정정. **⊕ 같은 날 후속 — 코드 수정에 따른 재정정 2건 + 신규 2건**: ①`Sam2TrackService` 응답 폴리곤에 **최소 정점 수(3) 검증을 추가**(위반 502 `EXTERNAL_API_ERROR`) → 본 회차에서 적었던 *"정점부족 조건은 이 경로에 없음"* 은 **폐기**하고 TC-SAM2-23 을 "좌표 형식 축(400)" 으로 좁힘 + **TC-SAM2-34**(정점<3 → 502)·**TC-SAM2-35**(요청 축 1점 클릭 허용 / 요청 400 ↔ 응답 502 분리 회귀 가드) 신설. 규칙은 `Sam2CoordinateValidator.validateResponseMinPoints` 로 분리 — 공용 `validatePolygon` 에 넣으면 **SAM2 클릭 프롬프트(1점)가 400 으로 죽는다.** ②`Sam2SegmentService` 의 **미호출 dead code `resolveSafe` 삭제** + 없는 보호를 주장하던 클래스 javadoc 을 실제 보호 지점(`FrameImageEncoder` 위임)으로 정정 → TC-SAM2-06 재정정. 두 변경으로 `Sam2SegmentService`/`Sam2TrackService` 라인이 다시 이동해 SAM2 근거 18건 재대조 |
| 4 | 2026-08-04 | 1건(TC-LABEL-90) | 0건 | 1건(TC-LABEL-97) | **비식별 누락 신고의 개인정보 3필드 리셋 폐기**(사용자 확정, 구속) — 라벨 보존 정책과 같은 취지로 개인정보 판정도 보존한다. 구 정책(프레임 축·영상 축 3필드 NULL 리셋)은 폐기 표기로 보존하며 보존 검증은 B 카탈로그 TC-DEID-058 이 담당. ⚠ 개인정보 메타 PUT **412 게이트는 유지**(근거만 교체) |

> **이 파일의 판정 기준**: 루트 `CLAUDE.md` 의 ★ 구속 정책이 정본이다. 특히 ①"파생영상은 비식별 신고 체계 바깥 — 양방향 무관"
> ②"신고 게이트 판정 범위 = 자기 rawSn 행 하나(조상/자손 전파 폐기)" ③"신고 시 라벨 보존 + 조회 차단(412)"
> ④"오토라벨 좌표 = 경계 clamp · 퇴화 스킵" 은 **확정 정책이므로 결함으로 재분류하지 않는다.**
> "파생 경유 열람"(부모 신고 구간에 파생본으로 라벨·프레임을 보는 것)은 위 정책의 필연적 귀결이며 **케이스로 쓰지 않는다.**

---

## C-1. TC-MARK — 마킹 (자동/수동)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-MARK-01 | AUTO 마킹 정상 생성 | 배정 WORKER, deidY, MARKING_READY, evntType 존재, 활성 마킹 0건 | mode=AUTO, intervalFrames=30 | 201, marks 배열, STTS=PENDING, MarkingCompletedEvent 발행 | integration | P1 | MarkingService.java:105-143, :167-241 |
| TC-MARK-02 | MANUAL 마킹 정상 생성 | 위 동일 | mode=MANUAL, marks=[{0,"00:00"}] | 201, marks 직렬화, fps pin | integration | P1 | MarkingService.java:203-209 |
| TC-MARK-03 | mode 누락(blank) | 인증됨 | mode="" | 400 @NotBlank | unit | P1 | MarkingRequest.java:20 |
| TC-MARK-04 | mode 비AUTO/MANUAL | precondition 통과 | mode="XYZ" | 400 INVALID_INPUT | unit | P1 | MarkingService.java:210-212 |
| TC-MARK-05 | AUTO intervalFrames null | AUTO | intervalFrames=null | 400 "1 이상" | unit | P1 | MarkingService.java:196-198 |
| TC-MARK-06 | AUTO intervalFrames 0/음수(경계) | AUTO | 0,-1 | 400 | unit | P1 | MarkingService.java:196-198 |
| TC-MARK-07 | MANUAL marks 빈/null | MANUAL | marks=[] | 400 "marks 필수" | unit | P1 | MarkingService.java:204-206 |
| TC-MARK-08 | AUTO durationSec null(해석 실패 backstop) | AUTO, 길이 미상 | autoDurationSec=null | 400 "영상 길이 확인 불가" | unit | P1 | MarkingService.java:269-273 |
| TC-MARK-09 | AUTO durationSec 0/음수(경계) | AUTO | durationSec=0 | 400(퇴화 방지) | unit | P1 | MarkingService.java:269-273 |
| TC-MARK-10 | generateAutoMarks off-by-one | dur=10s,fps=30,interval=30 | totalFrames=300 | frameIndex<300만(끝경계 미포함) | unit | P2 | MarkingService.java:276-281 |
| TC-MARK-11 | 분수 fps 반올림(29.97) | AUTO | fps=29.97 | Math.round(dur×fps), mm:ss. **1차 PARTIAL(B-ISSUE-27)** — 29.97 직접 계산 테스트 여전히 부재 | unit | P2 | MarkingService.java:276-279 |
| TC-MARK-12 | fps 미상 → 30 폴백 | fps 미적재 | resolveFps=30 | 30 폴백, 무회귀 | unit | P2 | MarkingService.java:191 |
| TC-MARK-13 | fps pin 저장(TOCTOU) | AUTO/MANUAL | 생성 | LsMarking.fps 저장(추출이 재조회 안 함) | unit | P1 | LsMarking.java:117, :177, :228 |
| TC-MARK-14 | 인가: 미인증 actor=null | — | actor=null | 401(존재확인 전) | unit | P0 | MarkingGuards.java:53-55 |
| TC-MARK-15 | 인가: 미배정 WORKER(IDOR) | WORKER 타 영상 | rawSn 미배정 | 403(미존재도 403 — 존재 은닉) | security | P0 | MarkingGuards.java:56-64 |
| TC-MARK-16 | 인가: REVIEWER 전체 허용 | REVIEWER | 임의 rawSn | 통과 | unit | P1 | MarkingGuards.java:56-58 |
| TC-MARK-17 | 프리컨디션: 영상 미존재 | 인가 통과 | raw=null | 404 | unit | P1 | MarkingGuards.java:80-82 |
| TC-MARK-18 | 프리컨디션: 비식별 미완료 | deIdntfYn≠Y | — | 412 | unit | P0 | MarkingGuards.java:83-86 |
| TC-MARK-19 | 프리컨디션: MARKING_READY 아님(재마킹 차단) | PROCESSING/COMPLETED | — | 412. ※"반려 후 재마킹" 동선은 존재하지 않음(2026-07-27 확정) — 결함 아님 | unit | P1 | MarkingGuards.java:87-90 |
| TC-MARK-20 | 프리컨디션: evntTypeCd 미지정 | blank | — | 400 | unit | P1 | MarkingGuards.java:91-95 |
| TC-MARK-21 | 프로브-이전 사전확인(리소스) | 미배정 WORKER AUTO | — | precheck 403, ffprobe 미트리거 | security | P1 | MarkingService.java:110-126 |
| TC-MARK-22 | 프리체크 REQUIRES_NEW 격리 | AUTO | — | readonly REQUIRES_NEW 즉시 반납 | integration | P2 | MarkingPrecheckReader.java:44 |
| TC-MARK-23 | persist 이중화 방어 | 사전확인 후 상태변경 | — | persist 에서 인가·프리컨디션·활성중복 규칙 재강제(동일 헬퍼) | integration | P1 | MarkingService.java:169-178 |
| TC-MARK-24 | 이벤트명 자동소싱(API-047) | eventName 없음 | — | raw.evntTypeCd 사용 | unit | P2 | MarkingService.java:182 |
| TC-MARK-25 | LsMarking.createAuto 검증 | — | rawSn null/eventName blank/interval≤0/videoPath null | IllegalArgumentException | unit | P2 | LsMarking.java:157-168 |
| TC-MARK-26 | LsMarking.createManual 검증 | — | rawSn null/eventName blank/videoPath null | IllegalArgumentException | unit | P2 | LsMarking.java:211-219 |
| TC-MARK-27 | markVlmRequested PENDING만 전이 | STTS=PENDING | — | true, VLM_REQUESTED | unit | P1 | LsMarking.java:246-253 |
| TC-MARK-28 | markVlmRequested 이미 완료 no-op | VLM_COMPLETED | — | false(역행 차단) | unit | P1 | LsMarking.java:246-249 |
| TC-MARK-29 | 배치브릿지: 배치단계 PROCESSING/COMPLETED 스킵 | LsDataRaw.dataSttsCd=COMPLETED | AFTER_COMMIT | 재트리거 스킵 + reason=STAGE_ALREADY_RUN | integration | P0 | MarkingBatchBridge.java:91-92, :125-130 |
| TC-MARK-30 | 배치브릿지: 비식별 미완료 스킵 | deIdntfYn≠Y | — | 트리거 스킵 + reason=NOT_DEIDENTIFIED | integration | P1 | MarkingBatchBridge.java:134-139 |
| TC-MARK-31 | 배치브릿지: row 부재 시 생성 | 미배정 REVIEWER 직접 마킹 | tx1 claim false | tx2 tryCreateBatchQueuedRow 신규 생성 후 트리거 | integration | P1 | MarkingBatchBridge.java:152-161 |
| TC-MARK-32 | 배치브릿지: 동시 2노드 유니크 경합 | 동시 이벤트 | DataIntegrityViolation | 1건만 트리거(나머지 조용히 스킵) | integration | P0 | MarkingBatchBridge.java:152-161 |
| TC-MARK-33 | 배치브릿지: 영상 미존재 스킵 | rawOpt empty | — | 스킵 + reason=VIDEO_NOT_FOUND | integration | P2 | MarkingBatchBridge.java:114-119 |
| TC-MARK-34 | 로그 인젝션 방어 | CR/LF 값 | — | sanitize 개행 제거(:127, :136 적용) | security | P2 | MarkingBatchBridge.java:127, :136, :196-198 |
| TC-MARK-35 | 컨트롤러 권한 매핑 | PORTAL_USER 토큰 | — | 403(REVIEWER/WORKER만) | security | P1 | MarkingController.java:51 |
| TC-MARK-36 | 활성 마킹 중복 409(순차) (신규) | rawSn 에 STTS=PENDING 마킹 존재 | 같은 rawSn 재요청 | 409 CONFLICT "이미 진행 중인 마킹" — ffprobe·쓰기 이전 1선 거부 | integration | P0 | MarkingGuards.java:109-114 |
| TC-MARK-37 | 활성 마킹 동시 요청 → DB 유니크 최종 방어 (신규) | 동시 3요청(서로 미커밋 행 미관측) | 동시 POST ×3 | 1건 201 · 나머지 409(DataIntegrityViolation→CONFLICT). PG tx abort 라 같은 tx 재시도 없음 | integration | P0 | MarkingService.java:226-233 · V142__*.sql |
| TC-MARK-38 | "활성" 정의 = PENDING/VLM_REQUESTED (신규) | 종결(VLM_COMPLETED/VLM_FAILED) 마킹만 존재 | 재마킹 요청 | 중복 가드 통과(단, TC-MARK-19 배치단계 가드는 별개). `LsMarking.ACTIVE_STATUSES` ↔ V142 인덱스 술어 **문자 일치** | unit | P1 | LsMarking.java:80 · V142__*.sql |
| TC-MARK-39 | 프리체크 단계 활성중복 판정 (신규) | 활성 마킹 존재 + AUTO | — | precheck 에서 409 → **ffprobe 미트리거** | integration | P1 | MarkingPrecheckReader.java:49 |
| TC-MARK-40 | MANUAL frameIndex 음수 400 (신규 · C-ISSUE-01) | MANUAL | frameIndex=-5 | 400 @Min(0). ※구 결함: `marks` 에 `@Valid` 가 없어 `@NotNull` 조차 미발화했음 | security | P1 | MarkItem.java:23-25 · MarkingRequest.java:30 |
| TC-MARK-41 | MANUAL 중복 frameIndex 400 (신규 · C-ISSUE-01) | MANUAL | [{0},{0}] | 400 "중복된 마킹 시점" | unit | P1 | MarkingService.java:312-315 |
| TC-MARK-42 | MANUAL frameIndex 상한 초과 400 (신규 · C-ISSUE-01) | dur·fps 해석 성공 | frameIndex=999999999 | 400 "영상 길이를 벗어난 마킹 시점". 상한=round(dur×fps)+ceil(fps) **배타** — 1초 마진(fps 불일치·정수초 절단 보정) | unit | P1 | MarkingService.java:322-329, :350-352 |
| TC-MARK-43 | MANUAL 길이 미상 → 상한만 skip (신규) | VDO_LEN_SEC·메타 모두 부재 | frameIndex=999999 | 통과(상한 skip) + WARN 로그. **중복·하한 검증은 유지**(전부 스킵 금지) | unit | P1 | MarkingService.java:317-321 |
| TC-MARK-44 | timestamp 형식 위반 400 (신규) | MANUAL | timestamp="99:99" / "-1:00" | 400 @Pattern(`^\d{1,4}:[0-5]\d(:\d{1,3})?$`), null 은 허용 | unit | P2 | MarkItem.java:27-29 |
| TC-MARK-45 | marks 개수 상한 20000 (신규, CWE-770) | MANUAL | 20001건 | 400 @Size | security | P2 | MarkingRequest.java:31 |
| TC-MARK-46 | 배치 미트리거 사유 응답 반영 (신규) | 검수 소유 상태(PENDING/IN_REVIEW/APPROVED/REJECTED) 영상 마킹 | — | 201 + 응답에 batchTriggered=false·reason 동반(무음 스킵 제거). 스레드로컬은 요청 시작 시 begin()으로 초기화 | integration | P1 | MarkingService.java:108, :132-142 · MarkingBatchBridge.java:76-81, :162-170 |

> C-1 부수: 마킹 단계 비식별 누락 신고(`POST /v1/videos/{rawSn}/deident-report`)는 C-2 의 TC-LABEL-127~130 참조.

---

## C-2. TC-LABEL — 라벨 CRUD / full-replace / 마스터 / 온라인 오토라벨 / 비식별신고

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-LABEL-01 | 프레임 라벨 조회 정상 | 배정 WORKER, 신고 구간 아님 | GET labels | 200, siblings+aiInfo+lsLabel enrich + `labelVersion` 포함 | integration | P1 | LabelService.java:187-213 |
| TC-LABEL-02 | 조회 IDOR: 타 영상 프레임 | WORKER 미배정 | — | 403 | security | P0 | LabelAccessGuard.java:62-69 |
| TC-LABEL-03 | 조회: 미인증 | actor=null | — | 401 | security | P1 | LabelAccessGuard.java:48-50 |
| TC-LABEL-04 | 조회: 프레임 미존재 | srcSn 없음/null | — | 404 | unit | P1 | LabelAccessGuard.java:54-58 |
| TC-LABEL-05 | frameImageType RAW(REVIEWER+raw) | REVIEWER | raw=true | "RAW" 반환 | unit | P2 | LabelService.java:229-234 |
| TC-LABEL-06 | frameImageType DEID(WORKER raw무시) | WORKER | raw=true | "DEID" 강제 | security | P1 | LabelService.java:229-234 |
| TC-LABEL-07 | bulkUpsert 신규 INSERT(수동) | 배정 | id=null,source=MANUAL | AUTO_LBL_YN='N', ADDED 이력 | integration | P1 | LabelService.java:359-365 |
| TC-LABEL-08 | bulkUpsert 기존 UPDATE | id 지정 | 좌표 변경 | UPDATE, before/after 스냅샷 | integration | P1 | LabelService.java:323-348 |
| TC-LABEL-09 | full-replace: 빠진 라벨 삭제 | 기존 3건 | items 2건만 | 나머지 1건 실삭제(DELETED 이력 + before 스냅샷) | integration | P0 | LabelService.java:375-393 |
| TC-LABEL-10 | full-replace: 빈 items=전량 삭제 | 기존 존재 | items=[] | 프레임 전체 삭제 | integration | P0 | LabelService.java:375-393 |
| TC-LABEL-11 | 삭제 순서 FK고아 방지 | 자식 존재 | 삭제 | ATTR_VAL→AI_INFO→LBL 순 bulk delete | integration | P0 | LabelService.java:390-392 |
| TC-LABEL-12 | 작업락 시 저장 차단 | isRawLocked | — | 409 CONFLICT | integration | P0 | LabelService.java:255-258 |
| TC-LABEL-13 | dedupById 동일 id 중복 | 같은 id 2개 | — | last-value-wins | unit | P2 | LabelService.java:501-519 |
| TC-LABEL-14 | 좌표 검증: 빈 points | — | points=[] | 400 (DTO `@NotEmpty` 가 먼저 발화 — 서비스 메시지는 도달 불가) | unit | P1 | LabelItemDto.java:42 · LabelService.java:707-709 |
| TC-LABEL-15 | 좌표 검증: [x,y] 형식 위반 | — | pair size≠2 | 400 | unit | P1 | LabelService.java:719-722 |
| TC-LABEL-16 | 좌표 검증: 음수 | — | x=-1 | 400 "0 이상". ※**사용자 저장 경로 한정** — AI 검출 응답은 clamp 정책(TC-LABEL-137) | security | P0 | LabelService.java:725-728 |
| TC-LABEL-17 | 좌표 검증: 0 허용(경계) | — | x=0,y=0 | 통과 | unit | P2 | LabelService.java:725-728 |
| TC-LABEL-18 | 신규 라벨 >1000점 차단(DoS) | id=null | 1001점 | 400(CWE-770). 경계 1000점은 통과 | security | P0 | LabelService.java:715-718 |
| TC-LABEL-19 | 기존 라벨 >1000점 simplify | id 지정 | 1001점 | 400 아님, capPoints(Douglas-Peucker) ≤1000 | unit | P1 | LabelService.java:838, :907-912 |
| TC-LABEL-20 | labelId 미존재 | 부재 | — | 404 | unit | P1 | LabelService.java:670-673 |
| TC-LABEL-21 | labelId USE_YN='N' → **신규 부여에만** 409 (정정) | 비활성 마스터 | id=null 로 비활성 labelId 부여 | 409. **기존 라벨이 같은 labelId 를 유지하는 저장은 통과**(C-ISSUE-25 수정 — 구 "요청 전체 labelId 검사"는 프레임 저장 영구 차단이었다) | unit | P1 | LabelService.java:650-679, :685-695 |
| TC-LABEL-22 | Mass Assignment: autoLblYn 무시 | 요청 autoLblYn='Y' | UPDATE | 무시(응답전용 — `ls_data_lbl` 에 컬럼 자체 없음, AI_INFO row 존재로 파생) | security | P1 | LabelService.java:133-143, :436 |
| TC-LABEL-23 | provenance AUTO_YOLO→AI_INFO | id=null,AUTO_YOLO | confScore | AUTO_LBL_YN='Y'+AI_INFO 행 생성 | integration | P1 | LabelService.java:351-358, :853-869 |
| TC-LABEL-24 | provenance 화이트리스트 위반 | source="HACK" | — | 400 @Pattern(MANUAL/AUTO_YOLO/AUTO_SAM2) | security | P1 | LabelItemDto.java:44-45 |
| TC-LABEL-25 | confScore 범위 초과 | 1.5 | — | 400 @DecimalMax(1.0) | unit | P2 | LabelItemDto.java:46-47 |
| TC-LABEL-26 | items >500 상한(DoS) | 501건 | — | 400 @Size | security | P1 | LabelBulkUpsertRequest.java:24 |
| TC-LABEL-27 | 무변경 재저장 이력 미발행(R7) | 동일 좌표 | — | UPDATED 이력·통지 미발행 | integration | P1 | LabelService.java:342-348, :397-399 |
| TC-LABEL-28 | R7 정규화 비교(5 vs 5.0) | 표현만 다름 | — | 무변경 판정 | unit | P2 | LabelService.java:547-561 |
| TC-LABEL-29 | R7 레거시 3포맷 흡수 | 객체배열/평탄 | — | 정규화 후 동일 판정 | unit | P2 | LabelService.java:568-585 |
| TC-LABEL-30 | R7 손상 JSON fail-safe | 파싱 실패 | — | '변경됨' 처리(이력 유실 방지). ※전용 회귀 테스트 여전히 부재(C-ISSUE-23) | unit | P2 | LabelService.java:556-559, :582-584 |
| TC-LABEL-31 | TASK_MODIFIED: APPROVED만 | APPROVED, 변경 有 | — | 변경 종류별 이벤트 발행 + `exportRegenerated=true` | integration | P1 | LabelService.java:413-426 |
| TC-LABEL-32 | TASK_MODIFIED: 검수전 미발행 | PENDING/ASSIGNED | — | 미발행 | integration | P1 | LabelService.java:413 |
| TC-LABEL-33 | 이력 조회 IDOR | WORKER 타 프레임 | — | 403 | security | P1 | LabelService.java:477 |
| TC-LABEL-34 | 이력 페이지 상한 클램프 | size=500 | — | 100 클램프 | unit | P2 | LabelService.java:593-594 |
| TC-LABEL-35 | 이력 임의 sort 무시(500 차단) | ?sort=badfield | — | 서버 고정 정렬(REG_DT DESC, LBL_HSTRY_SN DESC) | unit | P1 | LabelService.java:588-600 |
| TC-LABEL-36 | 동시 저장 lost update **차단** (정정 · C-ISSUE-21) | 두 세션이 같은 프레임 편집 | stale `labelVersion` 첨부 저장 | **409 CONFLICT** — 구 동작(무경고 침묵 삭제) 폐기. 프레임 행 비관적 락 + **스칼라 프로젝션**으로 락 획득 시점 DB 값 CAS(1차 캐시 우회) | integration | P0 | LabelService.java:272-274, :457-465 |
| TC-LABEL-37 | 삭제 감사 로깅 PII 미출력 | 대량 삭제 | — | 카운트+버전만 로그(좌표·PII 없음) | security | P2 | LabelService.java:408-409 |
| TC-LABEL-40 | 마스터 생성 정상 | REVIEWER | name/color/type | 201 | integration | P1 | LabelMasterService.java:74-95 |
| TC-LABEL-41 | 마스터 근사중복(대소문+공백) | 활성 "car" | " Car " | 409 | security | P0 | LabelMasterService.java:76-78 |
| TC-LABEL-42 | 마스터 동시 생성 DB유니크 | 경합 | — | V120 UK_LS_LABEL_NM_CI 차단 → 409 | integration | P0 | LabelMasterService.java:74-78 · LsLabelRepository.java:37 |
| TC-LABEL-43 | dtctTypeCd allowlist 위반 | "human" | — | 400(CocoClasses 80종) | security | P0 | LabelMasterService.java:122-134 |
| TC-LABEL-44 | dtctTypeCd 활성 중복 매핑 | person 재매핑 | — | 409(1 COCO 클래스 = 1 활성 라벨) | unit | P1 | LabelMasterService.java:81-82, :109-111 |
| TC-LABEL-45 | dtctTypeCd 미매핑 해제 허용 | null/blank | — | null 저장 | unit | P2 | LabelMasterService.java:122-134 |
| TC-LABEL-46 | color 소문자 hex 거부 | "#ffffff" | — | 400 @Pattern(대문자만) | unit | P2 | LabelMasterRequest.java:33 |
| TC-LABEL-47 | type allowlist | "FOO" | — | 400 @Pattern(BBOX/POLYGON/POINT/SKELETON) | unit | P2 | LabelMasterRequest.java:37 |
| TC-LABEL-48 | sortNo 음수 | -1 | — | 400 @Min | unit | P2 | LabelMasterRequest.java:40 |
| TC-LABEL-49 | 마스터 수정 미존재 | 없음 | — | 404 | unit | P1 | LabelMasterService.java:100-101 |
| TC-LABEL-50 | 마스터 수정 근사중복(자기제외) | 타 라벨명 충돌 | — | 409 | unit | P1 | LabelMasterService.java:104-105 |
| TC-LABEL-51 | 마스터 삭제 soft delete | — | DELETE | USE_YN='N'(hard delete 없음) | integration | P1 | LabelMasterService.java:190-195 |
| TC-LABEL-52 | 관리 권한: WORKER POST 차단 | WORKER | — | 403 | security | P0 | LabelMasterController.java:82-83 |
| TC-LABEL-53 | 조회 권한: WORKER/PORTAL 허용 | 인증 | GET | 200 | security | P2 | LabelMasterController.java:57 |
| TC-LABEL-54 | findLabelIdByDtctType null/blank | — | null | Optional.empty | unit | P2 | LabelMasterService.java:151-156 |
| TC-LABEL-55 | findLabelIdByDtctType 활성 유니크 최대1 | 활성 매핑 | trim 매칭 | 최대 1건, NonUniqueResult 없음 | integration | P1 | LsLabelRepository.java:84 |
| TC-LABEL-60 | autolabel BBOX 정상 | 배정 WORKER | POST autolabel | 200 좌표만(미저장, lblSn=null) | integration | P1 | AutolabelOnlineService.java:206-280 |
| TC-LABEL-61 | autolabel IDOR | WORKER 미배정 | — | 403 | security | P0 | AutolabelOnlineService.java:210 |
| TC-LABEL-62 | autolabel 작업락 | isRawLocked | — | 409 | integration | P0 | AutolabelOnlineService.java:413-416 |
| TC-LABEL-63 | autolabel inFlight 중복 | 진행 중 재요청 | — | 409, finally 락해제 | integration | P1 | AutolabelOnlineService.java:217-219, :277-279 |
| TC-LABEL-64 | resolveDetectClasses 매핑 0건 게이팅 | 매핑 없음 | — | ai 미호출, 0건+NO_MAPPED 안내 | integration | P0 | AutolabelOnlineService.java:228-235, :457-461 |
| TC-LABEL-65 | resolveDetectClasses 화이트리스트 교집합 | [person,hack] | mapped={person} | person만 전달, hack drop(WARN) | security | P0 | AutolabelOnlineService.java:465-475 |
| TC-LABEL-66 | resolveDetectClasses 우회 시도 | 미매핑 강제 | — | 교집합만(FE 불신) | security | P0 | AutolabelOnlineService.java:457-476 |
| TC-LABEL-67 | autolabel mock 응답 차단 | ai mock=true | — | 좌표 미반환, detectedCount=0 + MOCK 메시지 | integration | P0 | AutolabelOnlineService.java:246-251 |
| TC-LABEL-68 | 검출 좌표 개수 ≠4 → all-or-nothing 400 (정정) | ai 응답 3좌표 | — | 400. **형식 위반만** 전체 거부(부분 반환 금지) | unit | P1 | DetectionBoxNormalizer.java:51-53 · AutolabelOnlineService.java:565-570 |
| TC-LABEL-69 | 검출 좌표 NaN/Infinity → 400 (정정) | NaN | — | 400. **유한성 가드가 clamp 이전**(`NaN<0`=false 라 음수검사를 통과해 역직렬화 500 유발하던 회귀 차단) | security | P1 | DetectionBoxNormalizer.java:54-58 |
| ~~TC-LABEL-70~~ | ~~validateBbox 음수/순서위반 400~~ | ~~x2≤x1~~ | ~~400~~ | **[폐기 2026-07-30]** 좌표정책 반전(6d1b3703) — 음수는 400 이 아니라 **경계 clamp**, 순서 역전·퇴화는 **해당 검출만 스킵**(400 아님). 대체: TC-LABEL-137(clamp)·TC-LABEL-138(퇴화 스킵). `AutolabelOnlineService.validateBbox` 는 제거됨 | — | — | (구 AutolabelOnlineService.java:509-518 — 현재 부재) |
| TC-LABEL-71 | 검출 0건 SAM 스킵 | empty | — | reCheckLock 후 빈 결과 | unit | P2 | AutolabelOnlineService.java:258-264 |
| TC-LABEL-72 | POLYGON maxBoxes 상한 | 검출>maxBoxes | — | limit까지만 SAM + truncated 안내 | integration | P1 | AutolabelOnlineService.java:296-301 |
| TC-LABEL-73 | POLYGON wall-clock 예산 소진 | 예산 초과 | — | truncate+부분반환 | unit | P1 | AutolabelOnlineService.java:304, :314-319 |
| TC-LABEL-74 | POLYGON 박스별 실패 스킵 | SAM null/mock | — | 성공분만, skipped 안내(비-mock 실패도 고지) | integration | P2 | AutolabelOnlineService.java:321-348, :370-380 |
| TC-LABEL-75 | POLYGON bulkhead 429 즉시전파 | TOO_MANY_REQUESTS | — | fail-fast 전파(스킵 흡수 금지) | security | P1 | AutolabelOnlineService.java:341-346, :531-535 |
| TC-LABEL-76 | POLYGON TOCTOU 중간 잠금/신고 | 배치중 신고→잠금 | — | 409(락) 또는 412(신고), 좌표 미반환 | integration | P0 | AutolabelOnlineService.java:308-312, :352-353 |
| TC-LABEL-77 | YOLO bulkhead 초과 429 | 동시 상한 초과 | — | 429 | integration | P1 | AutolabelOnlineService.java:499-505 |
| TC-LABEL-78 | ai-server 호출 실패 502 | RuntimeException | — | EXTERNAL_API_ERROR(스택·경로 미노출) | security | P1 | AutolabelOnlineService.java:506-511 |
| TC-LABEL-79 | AutolabelRequest conf 범위 | 0.9 | — | 400 @DecimalMax(0.80) | unit | P2 | AutolabelRequest.java:39-40 |
| TC-LABEL-80 | classes 100개 초과 | 101개 | — | 400 @Size | unit | P2 | AutolabelRequest.java:36 |
| TC-LABEL-90 | 신고 정상 — **라벨 보존** (정정) | 배정 WORKER, 비파생, 비식별 산출물 有 | POST `/v1/labels/{srcSn}/deident-report` | 201 · **라벨 삭제 0건 · LS_LABEL_VERSION 스냅샷 미생성** · 작업락 + `DE_IDNTF_YN='F'` · **개인정보 3필드도 보존**(2026-08-04 리셋 폐기). 구 정책("전체 라벨 삭제 + `SAVE_REASON='DEIDENT_REPORT'` 비활성 스냅샷")은 **폐기**(2026-07-27 사용자 확정) | integration | P0 | DeidentReportService.java:118-126, :169-257 |
| TC-LABEL-91 | 신고 reason 누락 | reason="" | — | 400(컨트롤러 @Valid 우회 호출도 서비스 백스톱) | unit | P1 | DeidentReportService.java:158-162 |
| TC-LABEL-92 | 신고 reason 1000자 초과 | 1001자 | — | 400 @Size | unit | P2 | DeidentReportRequest.java:19 |
| TC-LABEL-93 | 신고 IDOR(srcSn 경로) | WORKER 미배정 | — | 403 | security | P0 | DeidentReportService.java:122 |
| TC-LABEL-94 | 신고 이미 잠금 | isRawLocked | — | 409(중복신고 차단) | integration | P1 | DeidentReportService.java:189-191 |
| TC-LABEL-95 | 신고 PII TOCTOU(FOR UPDATE) | 동시 증강 콜백 | — | `findByRawSnForUpdate` 로 부모 RAW 행 잠금 → 직렬화 | integration | P0 | DeidentReportService.java:178-180 |
| TC-LABEL-96 | 신고 동시 락 유니크 | 동시 신고 | DataIntegrityViolation | 409 | integration | P0 | DeidentReportService.java:237-241 |
| ~~TC-LABEL-97~~ | ~~신고 개인정보 3필드 리셋~~ | ~~프레임 존재~~ | ~~`resetPrivacyMetaByRawSn`~~ | **[폐기 2026-08-04]** ★정책 반전(사용자 확정) — 신고는 개인정보 3필드를 **리셋하지 않고 보존**한다(라벨 보존 정책과 같은 취지). stale 우려는 신고 구간 export 보류 + 해제 시 재산출이 담당. 대체: TC-DEID-058(보존 확인) | — | — | (구 DeidentReportService.java:218-219 — 현재 부재) |
| TC-LABEL-98 | 신고 APPROVED→TASK_MODIFIED (정정) | APPROVED | — | **`META_UPDATED`** 통지. 구 `LABEL_DELETED` 는 라벨 보존 정책과 모순이라 폐기 | integration | P1 | DeidentReportService.java:230-233 |
| ~~TC-LABEL-99~~ | ~~신고 라벨 0건 스킵~~ | ~~라벨 없음~~ | ~~스냅샷/삭제/이력 스킵~~ | **[폐기 2026-07-30]** 라벨 보존 정책 반전(b0647c4c)으로 신고 경로에 **라벨 스냅샷·삭제·삭제이력 분기 자체가 없어짐** — "0건이면 스킵"할 대상이 존재하지 않는다. 대체: TC-LABEL-124(라벨 무변경 확인) | — | — | (구 DeidentReportService.java:141-142 — 현재 부재) |
| TC-LABEL-100 | resolve 미인증 | actor=null | — | 401 | unit | P1 | DeidentReportService.java:354-356 |
| TC-LABEL-101 | resolve 신고 미존재 | rprtSn 없음 | — | 404 | unit | P1 | DeidentReportService.java:357-358 |
| TC-LABEL-102 | resolve IDOR | WORKER 타 영상 | — | 403 | security | P0 | DeidentReportService.java:361 |
| TC-LABEL-103 | resolve OPEN 아님 | RESOLVED | — | 409 | unit | P1 | DeidentReportService.java:364-366 |
| TC-LABEL-104 | resolve 산출물 미검증 gate | procLog 없음/경로 blank/무결성 실패 | — | 409, OPEN·락·'F' 유지(fail-closed). 무결성 판정은 `DeidentArtifactIntegrity` **단일 지점**(18바이트 스텁 통과 회귀 차단) | integration | P0 | DeidentReportService.java:542-581 |
| TC-LABEL-105 | resolve 시간조건(신고 이후 재비식별) | procLog·mtime 모두 신고 이전 | — | 409. mtime 은 60초 클럭스큐 관용, procLog 는 엄격 비교 | integration | P0 | DeidentReportService.java:504, :563-580 |
| TC-LABEL-106 | resolve 정상+'F'→'Y' 복원 | 검증 통과 | — | RESOLVED+락해제+'Y'(FOR UPDATE 잠금 하) | integration | P0 | DeidentReportService.java:374-391 |
| TC-LABEL-107 | resolve 배치상태 역행 금지 | COMPLETED/APPROVED 신고 | — | DATA_STTS_CD 유지(CWE-664) | integration | P1 | DeidentReportService.java:384-391 |
| TC-LABEL-108 | listReports status allowlist | "X" | — | 400. ※소문자 "resolved" 는 컨트롤러 `@Pattern` 이 먼저 400(서비스 `toUpperCase` 관용은 도달 불가) | unit | P2 | DeidentReportService.java:426-438 |
| TC-LABEL-109 | listReports 기본 OPEN | status=null | — | OPEN 필터 | unit | P2 | DeidentReportService.java:427-429 |
| TC-LABEL-110 | labelVersion 불일치 409 (신규 · C-ISSUE-21) | 조회 시 labelVersion=0 수신 후 타 세션이 저장(→1) | PUT labels with labelVersion=0 | 409 "다른 사용자가 먼저 저장했습니다" — 라벨 미변경 | integration | P0 | LabelService.java:457-465 |
| TC-LABEL-111 | labelVersion 미첨부 = 검사 skip (신규, 하위호환) | FE 미반영 클라이언트 | labelVersion 없음 | 200 저장(기존 동작 불변) | integration | P1 | LabelBulkUpsertRequest.java:26-32 · LabelService.java:458 |
| TC-LABEL-112 | 무변경 저장은 버전 미증가 (신규) | 동일 세트 재전송 | — | changes 비어 있음 → `bumpLabelVersionIn` 미호출, 응답 labelVersion 동일(타 세션 토큰 무효화 안 함) | integration | P1 | LabelService.java:400-406 |
| TC-LABEL-113 | 좌표 상한 초과 신규 라벨 400 (신규 · C-ISSUE-22) | 1280x720 프레임 | points=[[999999,888888]] | 400 "좌표가 이미지 경계를 벗어났습니다 (…, 이미지=1280x720)" — **클램프 아님**(사용자 확정) | security | P0 | LabelService.java:290-292, :747-774 |
| TC-LABEL-114 | 경계값 x==width 허용 (신규) | 1280x720 | [[1280,720]] | 200 통과(우/하단 끝 정상 좌표 — `Sam2SegmentService` 외부응답 기준과 동일) | unit | P2 | LabelService.java:768 |
| TC-LABEL-115 | 레거시 out-of-bounds 라벨 무변경 재저장 허용 (신규) | 기존 라벨이 이미 경계 밖 | 좌표 그대로 재전송 | 200 — 프레임 전체 저장이 영구 차단되는 회귀 방지(MAX_POINTS 와 동일 정책) | integration | P1 | LabelService.java:330-335 |
| TC-LABEL-116 | 기존 라벨을 경계 밖으로 이동 → 400 (신규) | 기존 라벨 | 좌표를 실제로 경계 밖으로 변경 | 400(pointsEqual 불일치 시 상한 강제) | security | P1 | LabelService.java:333-335 |
| TC-LABEL-117 | 치수 측정 실패 시 상한만 skip (신규) | 프레임 이미지 부재·손상 | 큰 좌표 | 200(상한 skip) + WARN + `label.bounds.skipped{reason}` 메트릭. 하한·형식 검증은 유지 | integration | P1 | LabelService.java:278, :748-750 · FrameBoundsResolver.java:76, :95-121 |
| TC-LABEL-118 | SKELETON v=0 키포인트 상한 제외 (신규) | SKELETON | [0,0,0] 자리표시자 | 상한 검사 제외(통과) | unit | P2 | LabelService.java:764-767, :777-780 |
| TC-LABEL-119 | 비활성 마스터 기존 참조 유지 저장 허용 (신규 · C-ISSUE-25) | 프레임에 USE_YN='N' labelId 참조 라벨 존재 | id 지정 + 같은 labelId 재전송 | 200 — 프레임의 다른 라벨 수정도 정상. 구 동작(프레임 전체 409 영구 차단) 폐기 | integration | P0 | LabelService.java:685-695 |
| TC-LABEL-120 | 비활성 마스터를 기존 라벨에 새로 부여 → 409 (신규) | 기존 라벨의 labelId 를 비활성 값으로 변경 | id 지정 + labelId 변경 | 409 — "id 붙이면 통과" 우회 차단 | security | P0 | LabelService.java:674-677, :685-695 |
| TC-LABEL-121 | 신고 구간 라벨 조회 412 (신규) | `DE_IDNTF_YN='F'` | GET `/v1/frames/{srcSn}/labels` | **412** PRECONDITION_FAILED, **역할 무관**(REVIEWER 포함). 인가(403/404) **이후** 평가 | security | P0 | LabelService.java:193 · LabelAccessGuard.java:135-141 |
| TC-LABEL-122 | 신고 구간 라벨 이력 412 (신규) | 동일 | GET `/v1/frames/{srcSn}/label-history` | 412 — 이력 `chgDtlCn` 에 before/after 좌표 전문이 실려 게이트 우회가 되던 창 차단 | security | P0 | LabelService.java:482 |
| TC-LABEL-123 | 게이트 판정 = 자기 rawSn 행 하나 (신규 · 구속) | 부모 `'F'`, 파생본(ORGNL_RAW_SN non-null) `'Y'` | 파생 프레임 라벨 조회 | **200** — 조상 체인을 타고 올라가지 않는다. 조상/자손 전파는 도입 후 철회(재도입 금지). "파생 경유 열람"은 확정 정책의 귀결이며 결함 아님 | security | P0 | DeidentReportGate.java:23-47, :66-71 |
| TC-LABEL-124 | 신고 후 라벨 무변경 확인 (신규) | 라벨 N건 보유 프레임 | 신고 접수 | `LS_DATA_LBL` 건수 불변 · `LS_LABEL_VERSION` 신규 행 없음 · 라벨셋 버전 bump 없음 | integration | P0 | DeidentReportService.java:197-205 |
| TC-LABEL-125 | 개인정보 리셋 행 단위 감사 (신규) | 3필드 보유 프레임 M건 | 신고 접수 | `LS_DATA_LBL_HSTRY` 에 프레임당 1행(actor·시각·rprtSn). 라벨 델타 0건이라 V139 필터로 `V_COMPLETED_LABEL_CHANGE` 미노출 | integration | P1 | DeidentReportService.java:218-226 |
| TC-LABEL-126 | resolve 후 게이트 자동 해제 + 보존 라벨 재사용 (신규) | 신고 → resolve 성공 | 라벨 조회 재시도 | 200, **신고 전과 동일한 라벨** 반환(별도 복원 API 없음) | integration | P0 | DeidentReportService.java:387-391 · LabelAccessGuard.java:135-141 |
| TC-LABEL-127 | 마킹 단계 rawSn 신고 정상 (신규) | 배정 WORKER, 비파생, `DE_IDNTF_YN='Y'` | POST `/v1/videos/{rawSn}/deident-report` | 201. 부수효과 5종이 srcSn 경로와 **동일**(`doReport` 공용 본체). 통지의 `srcSn=null`(영상 단위) | integration | P0 | DeidentReportController.java:122-131 · DeidentReportService.java:147-155 |
| TC-LABEL-128 | rawSn 신고 — 파생영상 412 (신규 · 구속) | `ORGNL_RAW_SN` non-null | 신고 요청 | **412**. 안내는 사실만("파생영상이라 이 화면에서 재비식별 요청 불가") — **원본으로 유도하지 않고 부모 rawSn 도 미노출**. 신고 행 미생성 + REVIEWER 알림 없음 + 사유는 sanitize 후 WARN 감사로그 | security | P0 | DeidentReportService.java:183, :295-306 |
| TC-LABEL-129 | rawSn 신고 — 비식별 미수행 412 (신규) | `DE_IDNTF_YN='N'`/null(PENDING 영상) | 신고 요청 | **412**. 판정은 `LsDataRaw.hasDeidentArtifact()` 단일 원천 — **이미 `'F'` 인 영상은 통과**해 기존 409(재비식별 진행 중) 경로 유지 | security | P0 | DeidentReportService.java:186, :329-337 |
| TC-LABEL-130 | rawSn 신고 인가 축 = verifyRawAccess (신규) | WORKER 미배정 rawSn | 신고 요청 | 403 — 영상 조회 **이전**에 평가(미인가자에게 존재 여부 미노출) | security | P0 | DeidentReportService.java:151 · LabelAccessGuard.java:83-100 |
| TC-LABEL-131 | srcSn 경로도 파생/미수행 412 (신규) | 파생 프레임 · 비식별 미수행 프레임 | POST `/v1/labels/{srcSn}/deident-report` | 412 — 두 진입점이 `doReport` 로 수렴해 갈라질 수 없음(차이는 인가 축 + 통지 srcSn 뿐) | integration | P0 | DeidentReportService.java:125, :169-186 |
| TC-LABEL-132 | resolve 시 DeidentGateReopenedEvent 항상 발행 (신규) | 미승인 영상 신고 → resolve | — | 승인 여부 무관 발행(보류된 VLM 시계열 위탁 재개용). 미발행 시 시계열 메타 영구 결손 | integration | P0 | DeidentReportService.java:487-495 |
| TC-LABEL-133 | DeidentReportResolvedEvent 는 APPROVED만 (신규) | 미승인 영상 resolve | — | 미발행(불필요한 v1 export 생성 방지). APPROVED 면 발행 → export 재산출·관제 재통지 | integration | P1 | DeidentReportService.java:492-494 |
| TC-LABEL-134 | 신고·해소 시 스트림 메타 캐시 무효화 (신규) | 재생 중 신고 | — | `evictAfterCommit(rawSn)` — 대상은 **이 영상 하나**(파생 캐시 미접촉) | integration | P1 | DeidentReportService.java:248, :395 |
| TC-LABEL-135 | 오토라벨 신고 구간 차단 순서 (신규) | 신고로 잠긴 영상 / 락 없이 `'F'` | POST autolabel | 잠김이면 **409**(기존 규약 보존), 락 없이 `'F'` 면 **412**(라벨 계열 관례). 작업락 판정이 먼저 | security | P0 | AutolabelOnlineService.java:411-419 |
| TC-LABEL-136 | 검출 좌표 상한 clamp (신규 · C-ISSUE-41) | 1280x720 | ai 응답 x2=1300 | 1280 으로 clamp 후 반환(400 아님) | integration | P1 | DetectionBoxNormalizer.java:59-68 |
| TC-LABEL-137 | 검출 좌표 음수 clamp (신규 · C-ISSUE-41) | ai 응답 x1=-1.57 | — | 0 으로 clamp 후 **정상 반환**. 구 동작(음수 1건 → 프레임 전체 400, 실측 5프레임 중 4프레임 실패) 폐기 | integration | P0 | DetectionBoxNormalizer.java:59-68 · AutolabelOnlineService.java:253-256 |
| TC-LABEL-138 | 퇴화 박스 검출 단위 스킵 (신규 · C-ISSUE-41) | clamp 후 x2≤x1 또는 y2≤y1 | — | **해당 검출만** 제외 + WARN, 같은 프레임의 정상 검출은 반환(400 아님) | integration | P0 | DetectionBoxNormalizer.java:65-67 · AutolabelOnlineService.java:571-575 |
| TC-LABEL-139 | bounds 미상 시 하한만 clamp (신규) | 치수 측정 실패 | 경계 초과 좌표 | 상한 없음(`Double.MAX_VALUE`)으로 취급, 하한 0 clamp 만 적용 — 정상 작업 전면 차단 방지 | unit | P1 | DetectionBoxNormalizer.java:71-77 · AutolabelOnlineService.java:556-561 |
| TC-LABEL-140 | SAM 폴리곤은 clamp 미적용(거부 유지) (신규) | POLYGON 경로 SAM 응답에 음수 | — | 그 박스만 스킵(부분 성공). **BBOX=clamp / SAM 폴리곤=거부** 는 원천 특성에 맞는 정합 상태이며 비대칭 결함 아님 | unit | P1 | AutolabelOnlineService.java:581-617 |
| TC-LABEL-141 | `/deid-image` 정상 200 (신규) | 배정 WORKER, `DE_IDNTF_SRC_FILE_PATH_NM` 실재 | GET `/v1/frames/{srcSn}/deid-image` | 200 image/jpeg 또는 image/png · Content-Length · `X-Content-Type-Options: nosniff` · Content-Disposition 은 **srcSn + MIME 파생 확장자로만** 조립(파일명 유래 문자열 미사용 — CWE-113) | integration | P1 | FrameImageController.java:115-122 · FrameImageService.java:305, :322-333 |
| TC-LABEL-142 | `/deid-image` 원본 폴백 **없음** → 404 (신규) | `DE_IDNTF_SRC_FILE_PATH_NM` null/blank 또는 파일 부재 | 동일 | **404** "비식별 이미지 파일이 존재하지 않습니다" — `SRC_FILE_PATH_NM` 이 채워져 있어도 **원본을 서빙하지 않는다**(메서드가 `getSrcFilePathNm()` 를 참조조차 하지 않음). verdict BLANK/MISSING/NOT_REGULAR_FILE/REALPATH_FAILED 전부 404 로 수렴 | security | P0 | FrameImageService.java:266-270, :290-301 |
| TC-LABEL-143 | `/deid-image` 권한 범위가 `/image` 와 다름 (신규) | PORTAL_USER 토큰 | GET `/deid-image` vs GET `/image` | **`/deid-image` = 403** (`@PreAuthorize("hasAnyRole('REVIEWER','WORKER')")` — 내부 전용, 포털은 `/v1/portal/**` 전용 경로 사용) / **`/image` = 통과**(`hasAnyRole('REVIEWER','WORKER','PORTAL_USER')`). 두 형제 경로의 역할 집합이 **의도적으로 다름** | security | P0 | FrameImageController.java:117 vs :80 |
| TC-LABEL-144 | `/deid-image` 신고 구간 412 + 평가 순서 고정 (신규) | `DE_IDNTF_YN='F'` | 미배정 WORKER / 배정 WORKER 각각 요청 | 미배정=**403**(인가 먼저), 배정=**412**. 순서는 ①인가(`verifyAndGet`) → ②신고게이트 → ③경로해석 고정 — 게이트를 앞에 두면 미배정자가 412/404 차이로 프레임 존재를 탐색할 수 있음 | security | P0 | FrameImageLookupService.java:99-101 · FrameImageService.java:288 |
| TC-LABEL-145 | `/deid-image` 심링크 TOCTOU 차단 (신규) | 판정(`toRealPath`) 통과 후 open 직전 최종 컴포넌트를 원본 프레임 심링크로 교체 | 동일 | 링크를 따라가지 않고 실패 → **404**(fail-closed). 크기·스트림 모두 `LinkOption.NOFOLLOW_LINKS` 로 읽어 판정 대상과 응답 대상이 어긋나지 않음(CWE-367/59) | security | P0 | FrameImageService.java:307-320, :362-366 |
| TC-LABEL-146 | `/deid-image` 비식별 서브트리 밖 경로 403 (신규) | `DE_IDNTF_SRC_FILE_PATH_NM` 이 deid base 밖 | 동일 | **403** "허용되지 않은 이미지 경로입니다"(verdict default). 경로 판정은 `StorageSubtreePolicy.verifyDeidentifiedFile` **단일 판정기**에 위임 — 컨트롤러/서비스에서 재구현하지 않음 | security | P0 | FrameImageService.java:292-300 |
| TC-LABEL-147 | `/deid-image` 응답 `Cache-Control: no-store` (신규) | 정상 200 | 응답 헤더 검사 | `Cache-Control: no-store`. 검증자(ETag/Last-Modified)가 없어 `no-cache` 로는 대역폭 이득 없이 디스크 캐시 잔존만 남으므로 `no-store` 로 통일 — 캐시된 마스킹 실패 이미지가 412 게이트를 우회해 재노출되는 창 차단(CWE-359/525) | security | P0 | FrameImageService.java:327 |
| TC-LABEL-148 | `/v1/frames/{srcSn}/image` 응답 `no-store` (신규) | 정상 200 | 응답 헤더 검사 | `Cache-Control: no-store` — 이 경로는 비식별 판정 없이 **원본 프레임**을 서빙하므로 캐시 재사용 시 신고 게이트(:105)가 무력화된다 | security | P0 | FrameImageService.java:252 |
| TC-LABEL-149 | `/v1/videos/{rawSn}/frames/{frameNo}/image` 응답 `no-store` (신규) | 정상 200(REVIEWER raw=true 포함) | 응답 헤더 검사 | `Cache-Control: no-store`. 이 경로도 `verifyRawAccess`(IDOR) → 신고게이트(412) 통과 후 서빙되므로 매 요청 재평가가 성립해야 함 | security | P0 | VideoController.java:320-338 · FrameImageService.java:252 |

> **`no-store` 5경로 중 C 소관은 3경로**다(TC-LABEL-147/148/149). 나머지 2경로는 `GET /v1/videos/{rawSn}/stream`(B 담당,
> `VideoStreamService.java:286-296`)·`GET /v1/portal/frames/{srcSn}/image`(F 담당, `PortalLabelService.java:455`).
> **포털 업로드 자산(`GET /v1/portal/uploads/frames/{uldFrmeSn}/image`)은 이 통일 대상이 아니다** — 포털 사용자 **본인이
> 업로드한 자산**(`LS_PORTAL_ULD_FRME`)이라 비식별 처리 대상이 아니고 `LS_DATA_RAW.DE_IDNTF_YN` 라이프사이클 자체가 없다
> (ADR-013 예외, 내부 파이프라인·데이터마트와 완전 분리 — `PortalUploadService.java:195-201`). 5경로 전수 확인 시 이 경로를
> "누락"으로 세지 말 것.

---

## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-SAM2-01 | segment 정상 | 배정 | points 또는 box | 200 폴리곤+신뢰도(미저장). **클릭 프롬프트 `points` 는 1 점이 정상 입력**(공용 검증기에 최소 정점 수를 넣으면 이 경로가 400 으로 죽는다 — 회귀 가드) | integration | P1 | Sam2SegmentService.java:91-174, :97-99 · Sam2CoordinateValidator.java:65-77 |
| TC-SAM2-02 | segment path/body srcSn 불일치 | 불일치 | — | 400(CWE-345) | security | P1 | LabelController.java:168-176 |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | points+box | — | 400 @AssertTrue | unit | P1 | Sam2SegmentRequest.java:46 |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | 둘 다 null | — | 400 @AssertTrue | unit | P1 | Sam2SegmentRequest.java:46 |
| TC-SAM2-05 | segment IDOR | WORKER 미배정 | — | 403 | security | P0 | Sam2SegmentService.java:93 |
| TC-SAM2-06 | segment 경로순회 차단 (정정) | ".." | — | 400/403. **`Sam2SegmentService` 는 경로를 조립하지 않는다** — `FrameImageEncoder.resolveFrameImageForInference` 에 위임하며 차단은 그쪽 `resolveSafe` 가 수행한다. (정정: 구 기재의 로컬 `resolveSafe`(구 :198)는 호출부 0건 dead code 였고 클래스 javadoc 이 없는 보호를 주장했다 → **메서드 삭제 + javadoc 을 위임 지점으로 정정**) | security | P0 | Sam2SegmentService.java:112 · :53-56(javadoc — 위임 명시) · FrameImageEncoder.java:220-229 |
| TC-SAM2-07 | segment 이미지 미존재 | 파일 없음 | — | 404 | unit | P1 | Sam2SegmentService.java:104-105, :112 |
| TC-SAM2-08 | segment 이미지 크기 초과 | >maxImageBytes | — | 413 PAYLOAD_TOO_LARGE | integration | P1 | Sam2SegmentService.java:114-118 |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지 | ai mock=true | — | empty, MOCK 메시지(FE 자동적용 차단) | integration | P0 | Sam2SegmentService.java:145-149 · LabelController.java:177-181 |
| TC-SAM2-10 | segment 폴리곤 정점<3 | 2점 | — | 502 | unit | P1 | Sam2SegmentService.java:182-185 |
| TC-SAM2-11 | segment 좌표 경계초과 | x>imgWidth | — | 502(외부응답 불신). ※이미지 경계 상한은 **segment 전용** — track·오토라벨은 의도적으로 미적용(별건 C-ISSUE-61) | security | P1 | Sam2SegmentService.java:186-198 |
| TC-SAM2-12 | segment simplifyTolerance 범위 | 60 | — | 400 @DecimalMax(50) | unit | P2 | Sam2SegmentRequest.java:35-36 |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | simplify 2점 | — | 원본 유지 | unit | P2 | Sam2SegmentService.java:166-169 |
| TC-SAM2-14 | track 정상 POLYGON | 배정 | nextSrcSns 순회 | 200 프레임별 폴리곤(미저장) | integration | P1 | Sam2TrackService.java:74-173 |
| TC-SAM2-15 | track path/body srcSn 불일치 | 불일치 | — | 400 | security | P1 | LabelController.java:132-143 |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | 51개 | — | 400 @Size(max=50). ※FE 가 무제한 전송해 "추적 실패"로 보이던 계약 버그의 서버측 상한 | security | P0 | label/Sam2TrackRequest.java:33 |
| TC-SAM2-17 | track nextSrcSns 빈 | [] | — | 400 @NotEmpty | unit | P1 | label/Sam2TrackRequest.java:33 |
| TC-SAM2-18 | track prevPolygon <3점 | 2점 | — | 400 @Size(min=3) | unit | P1 | label/Sam2TrackRequest.java:31 |
| TC-SAM2-19 | track prevPolygon >1000점 | 1001점 | — | 400 @Size(max=1000) | unit | P2 | label/Sam2TrackRequest.java:31 |
| TC-SAM2-20 | track trackId 64자 초과 | 65자 | — | 400 @Size(max=64) | security | P1 | label/Sam2TrackRequest.java:28-29 |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | WORKER 미배정 후속 | — | 후속도 403(AI 호출 이전) | security | P0 | Sam2TrackService.java:76, :99 |
| TC-SAM2-22 | track 후속 프레임 미존재 | nextSrcSn 없음 | — | 404 | unit | P1 | Sam2TrackService.java:101-102 |
| TC-SAM2-23 | track ai 응답 폴리곤 **좌표 형식** 검증 (재정정) | 음수/비유한/[x,y] 아님 | — | **400 INVALID_INPUT** — 좌표 형식 규칙은 `Sam2CoordinateValidator.validatePolygon` 이 담당하며 그대로 400 이다. ※구 기재의 "정점부족(<3점) 조건은 이 경로에 없음"은 **폐기** — 코드 수정으로 최소 정점 수 검증이 추가됐고 그 위반은 **502**다(TC-SAM2-34). 한 호출 안에서 두 축이 공존하며 정점 수를 먼저 판정한다 | security | P1 | Sam2TrackService.java:128 · :219-221, :233-236 · Sam2CoordinateValidator.java:65-77, :99-104 |
| TC-SAM2-24 | track BBOX 외접박스 산출 | shape=BBOX | — | [[minX,minY],[maxX,maxY]] | unit | P2 | Sam2TrackService.java:196-210 |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | 폭/높이<1px | — | 해당 프레임만 스킵(전체 추적 미중단). ※이 스킵은 **BBOX 형태에서만** 동작 — POLYGON 형태의 퇴화(정점<3)는 응답 검증(TC-SAM2-34)이 502 로 막는다 | unit | P2 | Sam2TrackService.java:153-159, :206-208 |
| TC-SAM2-26 | track shape 기본 POLYGON | shape=null | — | POLYGON 정규화 | unit | P2 | Sam2TrackService.java:80 |
| TC-SAM2-27 | track ai 호출 실패 502 | Exception | — | EXTERNAL_API_ERROR | security | P1 | Sam2TrackService.java:114-123 |
| TC-SAM2-28 | track trackId 로그 sanitize | CRLF | — | LogSanitizer 정제 | security | P2 | Sam2TrackService.java:169-171 |
| TC-SAM2-29 | segment 신고 구간 412 (신규) | `DE_IDNTF_YN='F'` | POST sam2-segment | **412** — 파일을 **읽기도 전에** 차단(전송 후 폐기가 아님) | security | P0 | Sam2SegmentService.java:112 · FrameImageEncoder.java:133-136, :206-217 |
| TC-SAM2-30 | track 신고 구간 412 (신규) | 동일 | POST sam2-track | 412 — 시작·후속 프레임 인코딩이 모두 `encodeFrame` 경유 | security | P0 | Sam2TrackService.java:95, :104 · FrameImageEncoder.java:178-180 |
| TC-SAM2-31 | 게이트 없는 base64 오버로드 부재 (신규, 구조 단언) | — | 소스 스캔 | `FrameImageEncoder` 에 `encodeToBase64(String)` public 쌍둥이가 **존재하지 않음**. 경로 문자열 진입점 없이 `LsDataSrc` 를 받는 메서드만 public — 포털 SAM2 가 원본 픽셀을 ai-server 로 보내던 경로 차단(3630558d) | security | P0 | FrameImageEncoder.java:182-197 |
| TC-SAM2-32 | 게이트 없는 해석기는 패키지 전용 (신규, 구조 단언) | — | 소스 스캔 | `resolveFrameImageWithoutGate` 는 package-private. 유일한 패키지 외 소비자 `FrameBoundsResolver` 는 **치수만** 읽고 픽셀을 밖으로 내보내지 않음 | security | P1 | FrameImageEncoder.java:94 · FrameBoundsResolver.java:101 |
| TC-SAM2-33 | 비식별 우선 폴백 경로 해석 (신규) | 해상도 파생 프레임(`SRC_FILE_PATH_NM`=null) | segment/track/autolabel | 400 "이미지 경로가 비어있습니다" 가 아니라 비식별 경로로 해석되어 정상 추론 | integration | P1 | FrameImageEncoder.java:94-115 |
| TC-SAM2-34 | track ai 응답 폴리곤 **정점<3** (신규) | ai-server 가 2점 이하 폴리곤 반환(좌표 자체는 유효) | POST sam2-track | **502 EXTERNAL_API_ERROR** — 클라이언트 입력 오류가 아니라 외부 시스템이 잘못 준 것이라 400 은 의미가 틀리다(segment 응답 검증 TC-SAM2-10 과 동일 규약). 단순화(Douglas-Peucker)는 결과가 3점 미만이면 **원본을 그대로 반환**하므로 뒤에서 걸러지지 않아 여기서 막지 않으면 퇴화 폴리곤이 응답에 실려 라벨로 저장된다(CWE-20) | security | P1 | Sam2TrackService.java:128, :233-236 · Sam2CoordinateValidator.java:31, :49-54 |
| TC-SAM2-35 | 요청 축/응답 축 분리 (신규, 회귀 가드) | ①segment `points` 1점(클릭 프롬프트) ②track `prevPolygon` 2점 | ①POST sam2-segment ②POST sam2-track | ①**정상 200** — 공용 `validatePolygon` 에 최소 정점 수를 넣으면 클릭 분할이 400 으로 죽으므로 그 규칙은 응답 전용 메서드로 분리돼 있어야 한다 ②**400** `@Size(min=3)`(Bean Validation) — 요청 축은 400, 응답 축은 502 로 섞이지 않는다 | security | P0 | Sam2CoordinateValidator.java:21-27, :59-60 · Sam2SegmentService.java:97-99 · label/Sam2TrackRequest.java:31 |

> **경계(설계상 정당 — 케이스 아님)**: 배치 `YoloAutolabelStep`·`Sam2SegmentStep` 은 이 인코더를 지나지 않고 **원본** 프레임을
> ai-server 로 보낸다. 배치 입력은 정책상 항상 원본이라 신고 게이트를 붙여도 새로 보호되는 픽셀이 없다.
> "모든 전송이 `FrameImageEncoder` 를 통과한다"고 단언하는 케이스를 쓰지 말 것.

---

## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | lblTypeCd=SKELETON | 17개 [x,y,v] | 200, 삼중값 직렬화 | integration | P1 | LabelService.java:792-825, :834-838 |
| TC-KEYPOINT-02 | 개수≠17 | — | 16개 | 400 | unit | P1 | LabelService.java:793-796 |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | — | [x,y]만 | 400 | unit | P1 | LabelService.java:797-801 |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | — | [10,20,null] | 400(fail-secure, 언박싱 이전) | security | P1 | LabelService.java:805-811 |
| TC-KEYPOINT-05 | v 범위 밖 | — | v=3 | 400 "0/1/2 중 하나" | unit | P1 | LabelService.java:816-819 |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | — | [0,0,0] | 통과 | unit | P2 | LabelService.java:820-823 |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | — | x=-1,v=2 | 400 | unit | P1 | LabelService.java:820-823 |
| TC-KEYPOINT-08 | KeypointSerializer toJson | 정상 리스트 | — | [[x,y,v],...] 결정적 | unit | P2 | KeypointSerializer.java:46 |
| TC-KEYPOINT-09 | fromJson 배열 아님 | 비배열 | — | IllegalArgumentException | unit | P2 | KeypointSerializer.java:80-81 |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | size≠3 | — | IAE | unit | P2 | KeypointSerializer.java:85-86 |
| TC-KEYPOINT-11 | fromJson 숫자아님 | 문자열 원소 | — | IAE | unit | P2 | KeypointSerializer.java:91-92 |
| TC-KEYPOINT-12 | fromJson 빈/[] | "[]" | — | 빈 리스트 | unit | P2 | KeypointSerializer.java:69-81 |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | 삼중값 무변경 | — | 2-튜플 파서 거부 → raw 숫자배열 폴백 비교 | unit | P2 | LabelService.java:568-585 |

---

## C-5. TC-TRACK — 트랙 보간 / 좌표변환 / MASK↔RLE / YOLO 트랙 프록시

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-TRACK-01 | BBOX 보간 정상 | 2 키프레임 | 사이 프레임 | linearInterpolate 채움 | unit | P1 | batch/interpolation/TrackInterpolator.java:48-83 |
| TC-TRACK-02 | 빈 키프레임 | [] | — | 빈 결과 | unit | P2 | TrackInterpolator.java:53-55 |
| TC-TRACK-03 | 단일 키프레임 | 1개 | — | 그 프레임만 | unit | P2 | TrackInterpolator.java:57-64 |
| TC-TRACK-04 | outside 마커 종료 | outside=true | — | 즉시 종료, 미포함 | unit | P1 | TrackInterpolator.java:59-62 |
| TC-TRACK-05 | 다음이 outside | next.outside | — | 현재만 저장 후 종료 | unit | P2 | TrackInterpolator.java:66-70 |
| TC-TRACK-06 | 마지막 이후 propagate 없음 | 마지막 키프레임 | — | 이후 미채움 | unit | P2 | TrackInterpolator.java:35-44 |
| TC-TRACK-07 | 동일 frame 중복 뒤값 우선 | span≤0 | — | LinkedHashMap 덮어쓰기 | unit | P2 | TrackInterpolator.java:66-75 |
| TC-TRACK-08 | totalFrames 음수 | -1 | — | IllegalArgumentException | unit | P1 | TrackInterpolator.java:50-52 |
| TC-TRACK-09 | keyframes null | null | — | NullPointerException | unit | P1 | TrackInterpolator.java:48-49 |
| TC-TRACK-10 | 폴리쉐이프 보간 정점수 다름 | 다른 정점 | — | PolyshapeMatcher 대응쌍 재사용 | unit | P1 | TrackInterpolator.java:100-139 |
| TC-TRACK-11 | 폴리쉐이프 closed/open | closed=true/false | — | 폐/개곡선 매칭 | unit | P2 | TrackInterpolator.java:100-127 |
| TC-TRACK-12 | rotate 중심 기준 | angle,center | — | 회전행렬 적용 | unit | P2 | CoordinateTransformer.java:21-41 |
| TC-TRACK-13 | rotate points/center null | null | — | IAE. ※전용 단위테스트 여전히 부재(C-ISSUE-81) | unit | P2 | CoordinateTransformer.java:22-27 |
| TC-TRACK-14 | scale/translate | sx,sy/dx,dy | — | 좌표 변환 | unit | P2 | CoordinateTransformer.java:44-65 |
| TC-TRACK-15 | maskToRle 첫픽셀 on | flat[0]=true | — | 맨앞 0-run 삽입 | unit | P1 | MaskRleConverter.java:30-59 |
| TC-TRACK-16 | maskToRle 빈/null | null | — | int[0] | unit | P2 | MaskRleConverter.java:30-33 |
| TC-TRACK-17 | maskToRle >1M 픽셀(DoS) | 초과 | — | IAE | security | P1 | MaskRleConverter.java:22, :37-39 |
| TC-TRACK-18 | maskToRle 비직사각형 | row 폭 불일치 | — | IAE | unit | P2 | MaskRleConverter.java:44-47 |
| TC-TRACK-19 | rleToMask width/height≤0 | 0 | — | IAE | unit | P2 | MaskRleConverter.java:93-95 |
| TC-TRACK-20 | rleToMask >1M | 초과 | — | IAE | security | P1 | MaskRleConverter.java:88-92 |
| TC-TRACK-21 | rleToMask 길이합 초과 | rle 합>total | — | IAE | unit | P1 | MaskRleConverter.java:102-104 |
| TC-TRACK-22 | rleToMask null | null | — | IAE | unit | P2 | MaskRleConverter.java:85-87 |
| TC-TRACK-23 | round-trip mask→rle→mask | 임의 mask | — | 원본 복원(멱등) | unit | P2 | MaskRleConverter.java:30-113 |
| TC-TRACK-24 | yolo-track path/body 불일치 | 불일치 | — | 400 | security | P1 | LabelController.java:198-208 |
| TC-TRACK-25 | yolo-track nextSrcSns 50 초과 | 51개 | — | 400 @Size(max=50) | security | P0 | YoloTrackRequest.java:23 |
| TC-TRACK-26 | yolo-track nextSrcSns 빈 | [] | — | 400 @NotEmpty | unit | P1 | YoloTrackRequest.java:23 |
| TC-TRACK-27 | yolo-track 좌표 clamp (신규 · C-ISSUE-41) | 경계 초과/음수 검출 | — | 이미지 경계로 clamp 후 반환(400 아님). 구 동작(음수 즉시 400)은 폐기된 정책의 잔재였음 | integration | P0 | YoloTrackService.java:163-180 · DetectionBoxNormalizer.java:59-68 |
| TC-TRACK-28 | yolo-track NaN/Infinity 400 (신규) | ai 응답 NaN | — | 400. 구 구현은 `NaN<0`=false 로 **검증을 통과해 응답 DTO 에 NaN 이 실렸음** | security | P0 | YoloTrackService.java:168-172 · DetectionBoxNormalizer.java:54-58 |
| TC-TRACK-29 | yolo-track 퇴화 박스 검출 단위 스킵 (신규) | 50프레임 시퀀스 중 1건 퇴화 | — | 해당 검출만 스킵 + WARN, **시퀀스 전체 폐기 없음** | integration | P0 | YoloTrackService.java:173-177 |
| TC-TRACK-30 | yolo-track 교차 영상 혼입 차단 (신규) | nextSrcSns 에 타 rawSn 프레임 | — | 400 — 다른 영상 프레임이 같은 트래커 clipId 세션에 흘러 틀린 trackId 를 반환하던 무결성 결함 차단 | security | P1 | YoloTrackService.java:110-115 |
| TC-TRACK-31 | 보간 진실원 단일 확인 (신규, 구조 단언) | — | 소스 스캔 | 프로덕션 보간은 `batch/interpolation/TrackInterpolator` 만 사용. 동명이인 `common/util/TrackInterpolator` 는 **여전히 잔존**하며 어떤 프로덕션 코드도 import 하지 않음(C-ISSUE-82 미해소 — dead code) | unit | P2 | batch/interpolation/TrackInterpolator.java:23 · common/util/TrackInterpolator.java |

---

## C-6. TC-PRESET — 라벨 프리셋

> 패키지 이동: `label/service/PresetService` → **`preset/service/PresetService`**. 근거 경로 전량 정정.
>
> **★ 소비처 경계(2026-08-03 재확인)**: 프리셋은 **오토라벨링 전용**이다. 같은 날 신설된 라벨링 화면의 **라벨 선택 모달**(`LabelPickerModal` → [TC-FE-286](H-frontend-e2e.md))은
> 목록 소스로 **라벨 마스터(`LS_LABEL`) 전체**를 쓰며 **프리셋 API 를 호출하지 않는다.** "수동 라벨링에서 프리셋을 고른다"는 기대결과를 만들지 말 것.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|---|---|---|---|---|---|:--:|---|
| TC-PRESET-01 | 생성 정상(labelId 기반) | 유효 labelIds | — | 저장, 코드 문자열(LBL_CD) 미저장 | integration | P1 | preset/service/PresetService.java:62-73, :163-172 |
| TC-PRESET-02 | 생성 이벤트타입 무효 | 미유효 | — | 400 | unit | P1 | PresetService.java:126-134 |
| TC-PRESET-03 | 생성 이벤트타입 빈값 허용 | "" | — | 통과 | unit | P2 | PresetService.java:126-134 |
| TC-PRESET-04 | 생성 이름 중복 | existsByPresetNm | — | 409 | unit | P1 | PresetService.java:65-67 |
| TC-PRESET-05 | 생성 labelId 미존재/비활성 | 잘못된 labelId | — | 400 "존재하지 않거나 비활성 라벨입니다" | unit | P1 | PresetService.java:144-160 |
| TC-PRESET-06 | 생성 이벤트 유니크 경합 | 동시 동일 이벤트 | DataIntegrityViolation | 409 | integration | P0 | PresetService.java:174-181 |
| TC-PRESET-07 | 수정 미존재 | 없음 | — | 404 | unit | P1 | PresetService.java:79-80 |
| TC-PRESET-08 | 수정 이름 중복(자기제외) | 타 프리셋명 | — | 409 | unit | P1 | PresetService.java:81-83 |
| TC-PRESET-09 | 수정 이벤트 유니크 위반 | saveAndFlush | DataIntegrityViolation | 409 | integration | P1 | PresetService.java:88-93 |
| TC-PRESET-10 | 삭제 멱등 | 미존재 id | — | no-op | unit | P2 | PresetService.java:98-103 |
| TC-PRESET-11 | 복제 이름 시퀀스 | "X" 존재 | — | "X (복사본)" / "X (복사본 2)" | unit | P2 | PresetService.java:183-195 |
| TC-PRESET-12 | 복제 이벤트 미상속 | 원본 이벤트 有 | — | 이벤트 null(충돌 회피) | unit | P1 | PresetService.java:106-118 |
| TC-PRESET-13 | 복제 이름 50회 초과 실패 | 대량 복사본 | — | 409 | unit | P2 | PresetService.java:185-195 |
| TC-PRESET-14 | 코드뷰 미연결(labelId null) | labelId=null | — | linked=false, legacy 코드 노출(오류 아님) | unit | P1 | PresetService.java:237-244 |
| TC-PRESET-15 | 코드뷰 마스터 미존재/soft delete | 비활성 labelId | — | linked=false(자동 생성/삭제 없음) | unit | P1 | PresetService.java:238-244 |
| TC-PRESET-16 | 코드뷰 형태 마스터 파생 | 연결됨 | LBL_TYPE_CD | bbox/polygon 을 마스터에서 파생(프리셋 개별 토글 불가) | unit | P1 | PresetService.java:245-251 |
| TC-PRESET-17 | 부분유니크 labelId 중복 방지 | 같은 labelId 2회 | — | `UK_LS_LABEL_PRESET_CODE_LBLID` 차단 | integration | P1 | (LsLabelPresetCodeLabelIdUniqueIT) |
| TC-PRESET-18 | N+1 회피 배치 조회 | 다수 코드 | list() | labelId IN 1회 join | integration | P2 | PresetService.java:220-235 |

---

## 불확실 항목 · 정책 확정 현황

| # | 항목 | 07-25 상태 | 현재(07-30) |
|:-:|---|---|---|
| #2 | 마킹 단계 rawSn 신고 미구현 | 미구현 | **해소** — `POST /v1/videos/{rawSn}/deident-report` 신설(TC-LABEL-127~130) |
| #9 | 동시 저장 Race(락 미도입) | 미확정 | **확정·해소** — 프레임 행 비관적 락 + `labelVersion` 낙관적 CAS(선택 필드), 불일치 409(TC-LABEL-36/110~112) |
| #10 | 좌표 이미지 경계 초과 저장 검증 부재 | 미확정 | **확정·해소** — 사용자 저장 경로는 **400 거부**(클램프 아님, TC-LABEL-113~118) / AI 검출 응답 경로는 **clamp**(TC-LABEL-136~139). 두 축의 정책이 다른 것이 정상 |
| — | C-ISSUE-23 (R7 손상 JSON 회귀 테스트) | LOW 갭 | 미해소 — TC-LABEL-30 비고 유지 |
| — | C-ISSUE-81 (MaskRle/Coordinate 가드 테스트 0%) | MEDIUM 갭 | 미해소 — TC-TRACK-13 비고 유지 |
| — | C-ISSUE-82 (동명이인 dead TrackInterpolator) | LOW | 미해소 — TC-TRACK-31 로 명시 |

> 상세: [UNCERTAINTIES.md](UNCERTAINTIES.md)
