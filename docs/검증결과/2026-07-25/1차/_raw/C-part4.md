## C-3. TC-SAM2 — SAM2 분할 / 트랙 프록시

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-SAM2-01 | segment 정상 | PASS | [실동작] REVIEWER 토큰으로 srcSn=446(rawSn=26, 실제 프레임) 클릭 프롬프트 실호출 → 200, 실 폴리곤 28점 + score=0.6892(mock 아님, ai-server AI_MOCK_MODE=false). Sam2SegmentService.java:80-149 로직과 일치 | Sam2SegmentServiceTest#클릭_프롬프트로_폴리곤과_신뢰도가_반환됨 | |
| TC-SAM2-02 | segment path/body srcSn 불일치 | PASS | [실동작] path=446,body=447 → 400 "path 의 srcSn 과 body 의 srcSn 이 다릅니다" 확인. LabelController.java:163-171 일치 | 없음(컨트롤러 로직, 서비스 테스트 아님) | |
| TC-SAM2-03 | points/box 배타 위반(둘 다) | PASS | [실동작] 둘 다 제공 → 400 "exactlyOnePrompt" 확인 | Sam2SegmentRequestValidationTest#동시_전달시_400 | |
| TC-SAM2-04 | points/box 배타 위반(둘 다 빈) | PASS | [실동작] srcSn만 제공 → 400 확인 | Sam2SegmentRequestValidationTest#포인트와_박스_모두_없으면_400 | |
| TC-SAM2-05 | segment IDOR | PASS | [실동작] WORKER(userNo=2002, rawSn=26 미배정) 토큰으로 srcSn=446 요청 → 403 "본인에게 배정되지 않은 영상입니다" 확인(assignment 테이블 조회로 2002 미배정 사전 확인) | Sam2SegmentServiceTest#타_배정_프레임_segment_요청시_403 | |
| TC-SAM2-06 | segment 경로순회 차단 | PASS | [정적] resolveSafe(baseDir, relativePath).normalize()+startsWith 검증 Sam2SegmentService.java:175-184 일치. srcFilePathNm 은 DB 값(요청 바디에 경로 필드 없음)이라 일반 HTTP 요청으로 ".." 를 직접 주입할 경로는 없음 — DB 오염/legacy 데이터에 대한 심층방어로 확인 | 없음(전용 유닛 미확인, 코드 존재) | 실제 API 표면으로는 트리거 불가한 방어선(정상) |
| TC-SAM2-07 | segment 이미지 미존재 | PASS | [실동작] 임시 ls_data_src 행(raw_sn=26,frm_no=9999, 존재하지 않는 파일 경로) 삽입 후 요청 → 404 "이미지 파일을 찾을 수 없습니다" 확인, 테스트 후 행 삭제(원복 완료) | 없음(Sam2SegmentServiceTest 에 전용 케이스 미확인) | 쓰기 수반 실동작 — 완료 후 정리함 |
| TC-SAM2-08 | segment 이미지 크기 초과 | PASS | [정적] maxImageBytes(기본 20MB) 초과 시 413 Sam2SegmentService.java:94-98 일치 | Sam2SegmentServiceTest#이미지_크기_상한_초과시_400 | 실동작 미실시(대용량 프레임 파일 준비 필요, 위험 대비 효익 낮음) |
| TC-SAM2-09 | segment mock→빈 폴리곤+메시지(자동적용 차단) | PASS | [정적]+[실동작 간접] Sam2SegmentService.java:118-123(mock=true→empty), LabelController.java:172-176(empty→MOCK_UNAVAILABLE_MESSAGE 세팅). FE OverlayLayer.tsx:406-412 `res.polygon.length===0` 시 `onMockWarning` 콜백 후 `return`(자동 적용 차단, 프리뷰도 해제) 확인. 현재 ai-server 는 AI_MOCK_MODE=false(실모델)라 실제 mock 경로는 강제 재현 안 함(환경 변경 없이 검증) | Sam2SegmentMockMessageWiringTest, Sam2SegmentServiceTest#Sam2Segment_내부mock이면_빈폴리곤과_안내메시지_반환 | mock=true 강제 재현은 환경설정 변경(AI_MOCK_MODE=true+재기동) 필요해 미실시 — 코드경로는 양방향(BE 판정→FE 차단) 모두 확인됨 |
| TC-SAM2-10 | segment 폴리곤 정점<3 | PASS | [정적] validatePolygon polygon.size()<3 → EXTERNAL_API_ERROR(502) Sam2SegmentService.java:156-159 일치 | Sam2SegmentServiceTest#3점_미만_폴리곤_응답시_오류 | 실 ai-server 가 3점 미만 응답하도록 강제 불가(정상 모델은 항상 폐곡선 반환) |
| TC-SAM2-11 | segment 좌표 경계초과 | PASS | [정적] validatePolygon 각 좌표 0≤x≤imgWidth, 0≤y≤imgHeight 미충족 시 502 Sam2SegmentService.java:160-172 일치. 실호출(TC-SAM2-01 등) 응답 좌표는 항상 상한 이내였음(모델이 정상 범위로 반환) | Sam2SegmentServiceTest#응답_폴리곤_좌표_이미지경계_초과시_오류 | |
| TC-SAM2-12 | segment simplifyTolerance 범위 | PASS | [실동작] simplifyTolerance=60 → 400 "경계 세밀함은 50.0 이하여야 합니다" 확인 | Sam2SegmentRequestValidationTest#simplifyTolerance_범위밖60_요청시_400 | |
| TC-SAM2-13 | segment 단순화 3점 미만→원본유지 | PASS | [정적] 단순화 결과<3점이면 aiRes.polygon() 원본 사용 Sam2SegmentService.java:140-143 일치 | Sam2SegmentServiceTest#AI분할_simplify_결과가_3점미만이면_원본폴리곤유지 | |
| TC-SAM2-14 | track 정상 POLYGON | PASS | [실동작] srcSn=446 시작, nextSrcSns=[447,448] 실호출 → 200, 프레임별 실 폴리곤(447: 7점 score=0.768, 448: 6점 score=0.900) 반환, DB 미저장 확인(ls_data_lbl 카운트 불변) | Sam2TrackServiceTest#배정된_WORKER는_sam2_track_정상동작하되_미저장, #추적은_DB에_저장하지_않고_좌표만_반환한다 | |
| TC-SAM2-15 | track path/body srcSn 불일치 | PASS | [실동작] path=446,body=447 → 400 확인. LabelController.java:137-142 일치 | 없음(컨트롤러 로직) | |
| TC-SAM2-16 | track nextSrcSns 50 초과(경계) | PASS | [실동작] nextSrcSns 51개 → 400 "size must be between 0 and 50" 확인. FE `SAM2_TRACK_CHUNK_SIZE`=50 청크 분할로 BE 계약과 정합(api.ts:596-605, 과거 "추적실패" 원인이던 무제한 전송 버그는 FE 청크 로직으로 해소됨 확인) | Sam2TrackRequest 값 객체 — 전용 유닛 미확인, DTO @Size(max=50) 검증 | 메모리(sam2-track-nextsrcsns-contract-bug) 지적 계약 불일치는 FE 청크 로직으로 해소되어 현재 재현 안 됨 |
| TC-SAM2-17 | track nextSrcSns 빈 | PASS | [실동작] nextSrcSns=[] → 400 "must not be empty" 확인 | 없음(DTO @NotEmpty) | |
| TC-SAM2-18 | track prevPolygon <3점 | PASS | [실동작] 2점 → 400 "size must be between 3 and 1000" 확인 | Sam2TrackServiceTest#sam2_track_prevPolygon_검증실패시_400(서비스단), DTO @Size(min=3) | |
| TC-SAM2-19 | track prevPolygon >1000점 | PASS | [실동작] 1001점 → 400 "size must be between 3 and 1000" 확인 | 없음(DTO @Size(max=1000)) | |
| TC-SAM2-20 | track trackId 64자 초과 | PASS | [실동작] 65자 trackId → 400 "size must be between 0 and 64" 확인 | 없음(DTO @Size(max=64)) | |
| TC-SAM2-21 | track IDOR 시작+후속 각각 | PASS | [실동작] ① WORKER(2002, rawSn=26 미배정) 시작프레임 요청 → 403. ② WORKER(2001, rawSn=26 배정됨이나 nextSrcSns 에 미배정 rawSn=5 프레임(srcSn=2) 포함) → 403 "본인에게 배정되지 않은 영상입니다"(후속 프레임도 개별 IDOR 적용 확인). Sam2TrackService.java:86-87 일치 | Sam2TrackServiceTest#미배정_WORKER가_sam2_track_호출시_403_FORBIDDEN_ai미호출 | 후속 프레임 개별 IDOR 는 실동작으로 별도 확인(시작만 배정된 케이스) |
| TC-SAM2-22 | track 후속 프레임 미존재 | PASS | [실동작] nextSrcSns=[999999] → 404 "프레임을 찾을 수 없습니다" 확인 | Sam2TrackServiceTest#sam2_track_후속프레임없으면_404 | 실제로는 LabelAccessGuard.verifyAccess(nextSrcSn,..)(87행)의 findById 가 먼저 404 를 던짐 — Sam2TrackService.java:89-90 의 "후속 프레임을 찾을 수 없습니다: " 메시지 분기는 이 경로에서는 도달 안 함(가드가 선점, 결과 404 는 동일하여 기능상 문제 없음) |
| TC-SAM2-23 | track ai 응답 좌표 검증 | PASS | [실동작+정적] validatePolygon 은 요청 prevPolygon 과 ai 응답 polygon 에 동일 로직 적용(Sam2TrackService.java:68,116,185-201). 실동작으로 prevPolygon=[[-5,1],...] → 400 "prevPolygon 좌표는 유한한 0 이상의 수여야 합니다" 확인(공유 검증함수이므로 ai 응답 경로도 동일 방어 확인됨) | Sam2TrackServiceTest#sam2_track_ai응답폴리곤_검증실패시_400 | |
| TC-SAM2-24 | track BBOX 외접박스 산출 | PASS | [실동작] shape="BBOX" 요청 → 200, points=[[296.0,0.0],[361.0,69.0]](외접 [[minX,minY],[maxX,maxY]] 형식) 확인. Sam2TrackService.java:168-182 일치 | Sam2TrackServiceTest#추적_shape가_박스면_폴리곤_외접bbox로_반환하고_선택라벨을_부여한다 | |
| TC-SAM2-25 | track 퇴화 bbox 프레임 스킵 | PASS | [정적] (maxX-minX)<1px 또는 (maxY-minY)<1px 이면 null→해당 프레임만 continue Sam2TrackService.java:126-131 일치 | Sam2TrackServiceTest#추적_박스형태_퇴화폴리곤은_해당프레임만_스킵한다 | 실모델이 퇴화 폴리곤을 반환하도록 강제 불가(정상 케이스에선 재현 안 됨) |
| TC-SAM2-26 | track shape 기본 POLYGON | PASS | [실동작] shape 필드 생략 요청(TC-SAM2-14) → 응답 shapeType="POLYGON" 확인. Sam2TrackRequest.java:37-39 일치. FE 측(ObjectAttributePanel.tsx:26-30, `shapeToDetectType(target.shape) ?? track.shape`)도 선택 객체 형태 우선 정책으로 구현되어 "shape 미지정→BE 기본값 POLYGON 으로 새는" 회귀가 재발하지 않음 확인 | Sam2TrackServiceTest#추적_shape미지정이면_POLYGON_기본 | |
| TC-SAM2-27 | track ai 호출 실패 502 | PASS | [정적] aiServerClient.track 예외 시 EXTERNAL_API_ERROR Sam2TrackService.java:103-111 일치 | Sam2TrackServiceTest#sam2_track_ai호출실패시_502 | |
| TC-SAM2-28 | track trackId 로그 sanitize | PASS | [실동작] trackId="evil\r\nFAKE LOG LINE INJECTED" 로 실제 track 호출 → 응답은 200(정상 처리, CRLF 는 API 응답 바디에는 그대로 echo 되나 이는 로그가 아님). docker logs 실측(`od -c`) 결과 로그 라인은 "trackId=evilFAKE LOG LINE INJECTED"(CR/LF 완전 제거)로 출력되어 LogSanitizer.sanitize 실동작 확인(CWE-117 방어 실증) | 없음(전용 유닛 미확인, 실동작으로 대체 확인) | |

## C-4. TC-KEYPOINT — 17-keypoint COCO SKELETON

| ID | 케이스명 | 판정 | 근거 확인 | 기존 테스트 | 비고 |
|----|---------|:--:|----------|-----------|------|
| TC-KEYPOINT-01 | SKELETON 정상 저장 | PASS | [실동작] 임시 프레임(srcSn=469)에 17개 [x,y,2] PUT /labels → 200, 응답 points 17개 삼중값 그대로 반환·DB point_cn 확인. 테스트 후 라벨+임시프레임 삭제(원복 완료) | LabelServiceKeypointTest#SKELETON_17개_삼중값_저장후_조회시_동일값_반환 | 쓰기 수반 실동작 — 완료 후 정리함 |
| TC-KEYPOINT-02 | 개수≠17 | PASS | [실동작] 16개 요청 → 400 "SKELETON 키포인트는 정확히 17 개여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_포인트_16개면_400 | |
| TC-KEYPOINT-03 | 삼중값 크기≠3 | PASS | [실동작] 1개 원소만 [x,y](2-tuple) → 400 "키포인트는 [x, y, v] 형태여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_원소가_2튜플이면_400 | |
| TC-KEYPOINT-04 | v null 원소(NPE 방어) | PASS | [실동작] [10,20,null] 포함 → 400 "키포인트 좌표에 null 원소가 있습니다"(500 NPE 아님, fail-secure 확인) | LabelServiceKeypointTest#SKELETON_원소에_null이면_400 | |
| TC-KEYPOINT-05 | v 범위 밖 | PASS | [실동작] v=3 → 400 "가시성 v 는 0/1/2 중 하나여야 합니다" 확인 | LabelServiceKeypointTest#SKELETON_가시성_v가_3이면_400 | |
| TC-KEYPOINT-06 | v=0 좌표 0 허용(경계) | PASS | [실동작] 17개 전부 [0,0,0] → 200 통과 확인 | LabelServiceKeypointTest#SKELETON_v0_미표기_점_x_y_0_허용 | |
| TC-KEYPOINT-07 | 음수 좌표(v>0) | PASS | [실동작] x=-1,v=2 → 400 "좌표는 0 이상이어야 합니다 (x=-1.0, y=20.0)" 확인 | 없음(LabelServiceKeypointTest 에 음수 전용 케이스 미확인, 06 케이스만 존재) | |
| TC-KEYPOINT-08 | KeypointSerializer toJson | PASS | [정적] toJson 결정적 직렬화(순서 보존, "[[1.5,2.5,2]]" 등) KeypointSerializer.java:46-60 일치 | KeypointSerializerTest#roundTrip, #toJsonTripletFormat | |
| TC-KEYPOINT-09 | fromJson 배열 아님 | PASS | [정적] root.isArray() 미충족 시 IllegalArgumentException KeypointSerializer.java:80-82 일치 | 없음(전용 테스트 미확인) | |
| TC-KEYPOINT-10 | fromJson 삼중값 크기위반 | PASS | [정적] triplet.size()!=3 시 IAE KeypointSerializer.java:84-87 일치 | KeypointSerializerTest#rejectsTwoTuple | |
| TC-KEYPOINT-11 | fromJson 숫자아님 | PASS | [정적] x/y/v 중 isNumber() 미충족 시 IAE KeypointSerializer.java:91-93 일치 | 없음(전용 테스트 미확인) | |
| TC-KEYPOINT-12 | fromJson 빈/[] | PASS | [정적] json.isBlank() 또는 "[]" → List.of() 즉시 반환 KeypointSerializer.java:70-72 일치 | KeypointSerializerTest#emptyInputs | |
| TC-KEYPOINT-13 | SKELETON R7 비교 폴백 | PASS | [정적] pointsEqual()→normalizePoints() 가 LabelPointSerializer.fromJson(2-튜플) 예외 시 raw List<List<Double>> 파싱으로 폴백 LabelService.java:491-508,455-484 일치. SKELETON 은 결정적 직렬화(KeypointSerializer)라 대부분 fast-path(문자열 동일 비교)로 처리되고, 재직렬화 표현차 발생 시에만 이 폴백 경로를 탐 | 없음(SKELETON 전용 R7 폴백 단위테스트 미확인, LabelServiceFullReplaceIntegrationTest 는 R7 언급하나 SKELETON 케이스 미포함) | |

## 이슈 상세 (FAIL / PARTIAL / 확인필요 전건)

전건 PASS — FAIL/PARTIAL/확인필요 항목 없음. 아래는 반증 시도 중 발견한 참고 관찰사항(카탈로그 결함 아님, 테이블 판정에 영향 없음).

### [C-ISSUE-61] (참고) SAM2 세그먼트 프롬프트 입력좌표 이미지 경계 사전검증 부재
- **심각도**: LOW
- **기대 동작(기대효과)**: 반증 포인트 "프롬프트 좌표가 이미지 밖"에 대한 서버측 방어 확인 목적
- **현재 동작(관찰)**: `Sam2SegmentRequest.points/box` 및 `Sam2TrackRequest.prevPolygon` 은 요청 시점에 이미지 실측 width/height 상한 검증이 없다(오직 **응답** 폴리곤만 `Sam2SegmentService.validatePolygon`(160-172행)으로 이미지 경계 검증됨). 실동작으로 `points:[[99999,99999]]`(rawSn=26 프레임 실측 폭 약 1280px 대비 크게 초과)을 srcSn=446 에 요청한 결과 200 OK + 정상 형태의 폴리곤(score 0.9466)이 반환됨 — ai-server/모델이 내부적으로 좌표를 관대하게 처리한 것으로 보이나, BE 계약상 사전 차단은 없음
- **재현/확인 경로**: `curl -X POST http://127.0.0.1:18081/api/v1/frames/446/sam2-segment -H "Authorization: Bearer $TOKEN" -d '{"srcSn":446,"points":[[99999,99999]]}'`
- **영향**: 보안 취약점 아님(응답은 여전히 사후 검증됨, path traversal/injection 경로 없음). 잘못된 프롬프트 좌표를 외부 ai-server 로 그대로 전달하는 낭비성 호출/예측불가 결과 가능성 정도의 견고성 갭
- **수정 방향(제안)**: Sam2SegmentService 진입 시 이미지 실측 해상도 확보 이후(이미 100행에서 확보) points/box 좌표도 0≤x≤imgWidth, 0≤y≤imgHeight 로 사전 400 검증 추가 검토(구현 금지 — 제안만)

## 요약
- 총 41건 (TC-SAM2 28 + TC-KEYPOINT 13) / PASS 41 / FAIL 0 / PARTIAL 0 / BLOCKED 0 / N/A 0 / 확인필요 0
- 근거 라인 드리프트: 0건 / self-fill 결함: 0건
- 실동작(ai-server 실추론·실 DB) 기반 검증: SAM2 segment 9건, SAM2 track 12건, 키포인트 저장 7건(직접 API 호출) — 나머지는 정적 코드 대조 + 기존 유닛/통합 테스트로 보강
- 채널 격리 실동작 확인: PORTAL_USER 토큰으로 내부 `/v1/frames/{id}/sam2-segment` 호출 시 403 확인(UNCERTAINTIES #1 정책 위반 없음 — 포털 전용 경로는 별도 `/v1/portal/frames/**`)
- 쓰기 수반 실동작 2건(TC-SAM2-07 임시프레임, TC-KEYPOINT-01/02~07 임시프레임+라벨) — 테스트 후 전량 원복 확인(rawSn=26 최종 상태: ls_data_src 16건/ls_data_lbl 131건, 검증 전과 동일)
