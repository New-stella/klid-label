# C-3. TC-SAM2 — SAM2 분할/트랙 프록시 (2-1차 검증)

담당: C-part3 (TC-SAM2-01~33, C-ISSUE-56~70 범위)
검증일: 2026-07-31 · 스택: backend :18081 · ai-server :19300 · mock-server :9400 · DB public 스키마

## ★ 환경 핵심 사실 — SAM2 는 mock 폴백 아님 (YOLO 와 다름)

`_raw/pipeline-drive.md` 에 기록된 배치 YOLO 는 `weights_missing` 으로 mock 폴백했지만,
**SAM2 는 실제 Meta SAM2 모델이 로드되어 실추론 중**임을 아래로 직접 확인했다.

- ai-server 직접 호출(`POST :19300/infer/sam2/segment`, `/infer/sam2/track`) 응답 원문:
  `"mock": false, "source": "model", "mock_reason": null` (실제 프레임 이미지로 실측)
- backend 온라인 SAM2 경로(`/v1/frames/{srcSn}/sam2-segment`, `sam2-track`)도 동일 —
  backend 로그 `[Sam2Segment] segmented srcSn=1 points=60 score=0.0`(WARN mock 분기 미진입)
  및 `[Sam2Track] propagated ... count=2 (no persist)` 로 실제(non-mock) 경로 확인
- 반면 배치 `Sam2SegmentStep` 은 같은 실행에서 `saved polygons rawSn=4 count=0` — 이는 모델 실패가 아니라
  **선행 YOLO mock 폴백으로 박스 시드가 0건이라 SAM2 입력 자체가 없었던 것**(연쇄 효과, SAM2 자체 결함 아님)
- 결론: **TC-SAM2-09(mock→빈 폴리곤)의 "mock 발생" 조건 자체가 이 환경에서 자연 발생하지 않는다** —
  코드 로직·단위테스트(`mockResponseReturnsEmptyPolygon`)는 정상이므로 PASS 처리하되, 실환경 mock 트리거는
  BLOCKED 성격임을 별도 표기(★ 표 09행 비고 참조)

## 결과표

| ID | 판정 | 근거확인 | 비고 |
|---|---|---|---|
| TC-SAM2-01 | PASS | [실동작] | `POST /v1/frames/1/sam2-segment` box=[10,10,100,100] → 200, polygon 60pt + score, DB 미저장(스테이트리스) 확인 |
| TC-SAM2-02 | PASS | [실동작] | path=1/body=2 → 400 `INVALID_INPUT` "path 의 srcSn 과 body 의 srcSn 이 다릅니다" (LabelController.java:167-171) |
| TC-SAM2-03 | PASS | [실동작] | points+box 동시 제공 → 400 `exactlyOnePrompt` 메시지 |
| TC-SAM2-04 | PASS | [실동작] | points/box 둘 다 미제공 → 400 `exactlyOnePrompt` 메시지 |
| TC-SAM2-05 | PASS | [실동작] | 미배정 WORKER(userNo=9999) → 403 FORBIDDEN "권한이 없습니다" (AI 호출 전 차단) |
| TC-SAM2-06 | PASS | [정적] | 실제 방어는 `FrameImageEncoder.resolveSafe`(java:220-229)+`StorageSubtreePolicy.verifyDeidentifiedFile`(실경로+심링크 방어까지) 경유. `Sam2SegmentService.java:179` 의 동명 `resolveSafe` 는 **현재 미사용(dead code)** — 카탈로그 근거줄 드리프트. `FrameImageEncoderTest#경로_순회_시도는_INVALID_INPUT_차단` 로 커버 |
| TC-SAM2-07 | PASS | [정적] | `FrameImageEncoderTest#존재하지_않는_파일은_NOT_FOUND` 로 404 커버 (encoder 공용 경로) |
| TC-SAM2-08 | PASS | [정적] | `Sam2SegmentServiceTest#imageTooLargeRejected` — b64 인코딩 전 파일크기 검사(Sam2SegmentService.java:98-102) 413 확인 |
| TC-SAM2-09 | PASS | [정적] | 코드(122-127행)·단위테스트(`mockResponseReturnsEmptyPolygon`) 정상. **단 이 실행 환경은 SAM2 실모델 로드 상태라 mock 분기가 실동작으로 자연 트리거되지 않음**(위 ★ 참조 — YOLO와 반대) |
| TC-SAM2-10 | PASS | [정적] | `Sam2SegmentService.validatePolygon` `MIN_POLYGON_POINTS=3` 미만 시 502 `EXTERNAL_API_ERROR`(159-163행). `lessThanThreePointsRejected` 테스트 확인 |
| TC-SAM2-11 | PASS | [정적] | 동일 메서드 x/y > imgWidth/imgHeight 502(171-174행). `polygonOutOfImageBoundsRejected` 테스트 확인 |
| TC-SAM2-12 | PASS | [실동작] | simplifyTolerance=60 → 400 "경계 세밀함은 50.0 이하여야 합니다" (`@DecimalMax(50.0)`) |
| TC-SAM2-13 | PASS | [정적] | `simplifyBelowMinKeepsOriginalPolygon` 테스트 — 단순화 결과<3점이면 원본 폴리곤 유지(145-147행) |
| TC-SAM2-14 | PASS | [실동작] | `POST /v1/frames/1/sam2-track` nextSrcSns=[2,3] → 200, 프레임별 polygon(shapeType=POLYGON), score=3.29e-6(실모델 값) — 미저장(no persist) 확인 |
| TC-SAM2-15 | PASS | [실동작] | path=1/body=2 → 400 CWE-345 |
| TC-SAM2-16 | PASS | [실동작] | nextSrcSns 51개 → 400 "size must be between 0 and 50" |
| TC-SAM2-17 | PASS | [실동작] | nextSrcSns=[] → 400 "must not be empty" |
| TC-SAM2-18 | PASS | [실동작] | prevPolygon 2점 → 400 "size must be between 3 and 1000" |
| TC-SAM2-19 | PASS | [실동작] | prevPolygon 1001점 → 400 동일 제약 |
| TC-SAM2-20 | PASS | [실동작] | trackId 65자 → 400 "size must be between 0 and 64" |
| TC-SAM2-21 | PASS | [실동작]+[정적] | 시작 프레임 미배정(userNo=9999) → 403(AI 호출 전, 실동작 확인). 후속 프레임 개별 IDOR(Sam2TrackService.java:90, 루프 내 매 프레임 verifyAccess)은 대칭 구조로 정적 확인(적합한 미배정 srcSn 조합을 라이브 환경에서 만들지 못해 후속 분기 자체는 코드+구조로 검증) |
| TC-SAM2-22 | PASS | [실동작] | nextSrcSns=[9999999](미존재) → 404 "프레임을 찾을 수 없습니다" |
| TC-SAM2-23 | **PARTIAL** | [정적]+[실동작 근거] | 아래 **C-ISSUE-56** 참조 — 카탈로그 기대값(502) 과 실제/테스트 확정 동작(400) 불일치 + 정점부족(2점 이하) 케이스 자체가 검증되지 않는 갭 |
| TC-SAM2-24 | PASS | [실동작] | shape=BBOX → 200 `points=[[minX,minY],[maxX,maxY]]`, shapeType=BBOX 확인 |
| TC-SAM2-25 | PASS | [정적] | `degenerateBboxSkippedNotAborted` 테스트 — 퇴화 bbox(폭/높이<1px)는 해당 프레임만 skip, 전체 미중단(128-134행) |
| TC-SAM2-26 | PASS | [실동작] | TC-SAM2-14 응답에서 shape 미지정 시 `shapeType:"POLYGON"` 확인(shapeOrDefault, Sam2TrackRequest.java:37-39) |
| TC-SAM2-27 | PASS | [정적] | `aiCallFailure502` 테스트 — aiServerClient.track() 예외 시 502 EXTERNAL_API_ERROR(108-114행) |
| TC-SAM2-28 | PASS | [실동작] | trackId에 `\r\nFAKE LOG LINE INJECTED` 포함 요청 → 200 정상 처리, backend 로그에 CRLF 제거된 단일 라인만 출력됨(`trackId=trk-crlfFAKE LOG LINE INJECTED`) — LogSanitizer 정상 동작(CWE-117) 확인 |
| TC-SAM2-29 | PASS | [실동작] | 신고 OPEN 상태인 rawSn=5(srcSn=31) 대상 segment 호출 → **412** "비식별 재처리 대기 중인 영상은 AI 추론을 실행할 수 없습니다" (파일 읽기 전 차단, ai-server 미호출) |
| TC-SAM2-30 | PASS | [실동작] | 동일 신고구간 srcSn=31 대상 track 호출(시작 프레임) → **412** 동일 |
| TC-SAM2-31 | PASS | [정적] | 전체 코드베이스 grep — `encodeToBase64(String)` public 오버로드 없음. javadoc 주석에서만 구 메서드 언급(제거 확인) |
| TC-SAM2-32 | PASS | [정적] | `resolveFrameImageWithoutGate`(FrameImageEncoder.java:94) 패키지-private 확인. 유일한 패키지 외 소비자 `FrameBoundsResolver.measure()`(치수만 사용, 픽셀 미반출) 확인 |
| TC-SAM2-33 | PASS | [정적] | `resolveFrameImageWithoutGate` 로직(94-115행) — deid 경로 우선 검증 성공 시 원본(SRC_FILE_PATH_NM) null 여부와 무관하게 즉시 반환. rawSn=4 실측 DB에서도 deid 경로가 우선 채택되는 구조 확인 |

**집계**: PASS 32 · PARTIAL 1 · FAIL 0 · BLOCKED 0 · N/A 0 · 확인필요 0 (총 33)

---

## 이슈

### [C-ISSUE-56] TC-SAM2-23 — track AI 응답 폴리곤 검증 기대결과(502)와 실제 동작(400) 불일치 + 정점부족 미검증
- **심각도**: MEDIUM
- **기대 동작(기대효과)**: 카탈로그 기대결과 "음수/비유한/정점부족 좌표 → 502 EXTERNAL_API_ERROR"(외부 응답 불신 원칙 — segment 쪽과 동일한 취급). ai-server 응답은 외부 시스템 산출물이므로 검증 실패 시 "우리 요청이 잘못됨"(400)이 아니라 "외부 응답이 신뢰 불가"(502)로 분류하는 것이 API 의미론상 일관됨(TC-SAM2-10/11 의 segment 쪽은 실제로 502 로 구현되어 있음).
- **현재 동작(이슈 내용)**: `Sam2TrackService.validatePolygon(List<List<Double>> polygon, String fieldName)`(Sam2TrackService.java:188-204) 이 **요청 prevPolygon 검증(71행)과 ai-server 응답 폴리곤 검증(119행)에 동일 메서드를 재사용**하며, 예외를 전부 `ErrorCode.INVALID_INPUT`(400)으로 던진다:
  ```java
  // Sam2TrackService.java:118-119
  // 외부 시스템 응답도 신뢰하지 않음 — 동일 좌표 검증.
  validatePolygon(aiRes.polygon(), "ai-server polygon");
  ...
  // 188~
  private void validatePolygon(List<List<Double>> polygon, String fieldName) {
      if (polygon == null || polygon.isEmpty()) {
          throw new CustomException(ErrorCode.INVALID_INPUT, fieldName + " 가 비어있습니다.");
      }
      ...
      if (x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0) {
          throw new CustomException(ErrorCode.INVALID_INPUT, ...);
      }
  }
  ```
  실제 프로젝트 자체 단위테스트가 이 400 동작을 **의도된 사양으로 확정**하고 있다(`Sam2TrackServiceTest.java:271-284`, `aiPolygonInvalid400` — "음수 좌표 폴리곤은 400" 주석). 즉 카탈로그의 502 기대결과가 현재 구현/테스트와 다르다.
  **추가로**, 이 `validatePolygon` 은 `polygon.isEmpty()`(0개)와 `pair.size()!=2`(좌표쌍 형식) 만 검사하고, **세그먼트 로직처럼 최소 정점 수(3점) 하한을 검사하지 않는다.** 반면 `Sam2SegmentService.validatePolygon`(159-176행)은 `MIN_POLYGON_POINTS`(3) 미만이면 명시적으로 502 처리한다. 그 결과 track 경로에서는 ai-server 가 **2점짜리 퇴화 "폴리곤"을 반환해도 검증을 그대로 통과**하며(좌표가 유한·비음수이기만 하면), 이후 `simplify()`/`toCircumscribedBbox()` 로 전달된다.
- **재현/확인 경로**: `Sam2TrackServiceTest#aiPolygonInvalid400`(음수 좌표) 실행 시 `errorCode=INVALID_INPUT`(400) 로 assert 통과 — 502 아님을 코드 레벨로 직접 확인. 정점부족 케이스는 화이트박스 확인(라이브 재현은 실모델이 로드된 이 환경에서 ai-server 응답을 임의 조작할 수 없어 코드 정독으로 확인).
- **영향**: 기능적으로 "잘못된 좌표를 거부한다"는 핵심 방어 자체는 동작하므로 보안 결함은 아니나, ①API 오류 의미론 불일치(같은 "외부 응답 불신" 케이스인데 segment=502·track=400로 엔드포인트 간 비대칭 — UNCERTAINTIES ★2/★3 처럼 "의도된 정책"으로 문서화되지 않은 상태라 향후 "일관성" 명목으로 잘못 통일될 위험) ②2점 퇴화 "폴리곤"이 세그먼트 경로보다 느슨하게 통과되는 검증 갭(하류에서 크래시는 아니나 방어 깊이 저하).
- **수정 방향(제안)**: (a) 카탈로그 기대결과를 "400 INVALID_INPUT"로 정정하거나, (b) track 서비스가 ai 응답 검증 시에는 segment 와 동일하게 별도 502 분기(+ 최소 3점 검사)를 두도록 리팩터링 — 어느 쪽이든 **사용자 확정 후** 진행. 구현은 본 검증에서 하지 않음.
