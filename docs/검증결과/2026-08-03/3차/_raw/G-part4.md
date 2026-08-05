# G클러스터 part4 — G-5(VLM verify-objects) + G-6(계약 정합/공통 인프라) — 3차 회차

- 담당 범위: `docs/test-cases/G-ai-server.md` 115~162행 (G-5 전체 14건 `TC-AIVLM-01~14` + G-6 전체 20건 `TC-AICONTRACT-01~12`·`TC-AIINFRA-01~08`) = **34건**
- 이슈 ID: `G-ISSUE-61`부터
- 환경: `_raw/stack-bringup.md`(3차) 기준 — ai-server(:19300, 컨테이너 내부 9300) 직접 curl, `AI_MOCK_MODE=false`(실추론 경로), YOLO weights 미탑재는 내 범위(VLM/계약) 영향 없음. VLM 모델(`get_vlm_model()`)은 실구현 자체가 없어 mock_reason이 항상 `weights_missing`으로 관측(코드상 정상 — `vlm_loader.py` "real load not implemented yet" 주석과 일치).
- 빌드/테스트 미실행(지시 준수). 실동작은 `curl localhost:19300` 직접 호출, 정적 대조는 Read.

## 판정 집계

| 판정 | 건수 |
|---|---|
| PASS | 34 |
| FAIL | 0 |
| PARTIAL | 0 |
| BLOCKED | 0 |
| N/A | 0 |
| 확인필요 | 0 |
| **합계** | **34** |

## G-5. VLM — ai-server 자체 `/infer/vlm/verify-objects`

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AIVLM-01 | PASS | [실동작] `expected_label=person` → `{"verified":true,"confidence":0.92,"mock":true,"source":"mock","mock_reason":"weights_missing"}`. `routers/vlm.py:89-104`(`_mock_verify`) 라인 정확 |
| TC-AIVLM-02 | PASS | [실동작] `expected_label=unicorn` → `verified:false, confidence:0.18`. 라인 일치 |
| TC-AIVLM-03 | PASS | [실동작]+[정적] 현재 배선(`AI_MOCK_MODE=false`+VLM 미구현)에서 `mock_reason=weights_missing` 관측 — 기대 집합 `{env_mock,weights_missing,not_implemented}` 내. `routers/vlm.py:38-69`(`verify_objects`+`_should_mock`+`_mock_reason`) 라인 정확 |
| TC-AIVLM-04 | PASS | [실동작] `expected_label=PERSON`(대문자) → `verified:true`. `:93` `obj.expected_label.lower()` 라인 정확 |
| TC-AIVLM-05 | PASS | [실동작] `objects:[]` → HTTP 400. `schemas.py:251` `min_length=1` 라인 정확 |
| TC-AIVLM-06 | PASS | [실동작] `bbox:[1,2,3]`(3원소) → 400. `schemas.py:244` 라인 정확 |
| TC-AIVLM-07 | PASS | [실동작] `obj_id` 누락 → 400. `schemas.py:239-244`(`ObjectToVerify`) 라인 정확 |
| TC-AIVLM-08 | PASS | [실동작] `image_b64:"!!!notb64"` → `400 {"error_code":"INVALID_IMAGE","message":"base64 디코드 실패"}`. `routers/vlm.py:45` 라인 정확 |
| TC-AIVLM-09 | PASS | [실동작] 11MB raw(base64 15.4MB, `max_image_size_mb` 기본 10) → HTTP 413. `image_utils.py:42-43`(`if len(raw) > max_bytes: raise ImageTooLargeError`) 라인 정확 |
| TC-AIVLM-10 | PASS | [실동작]+[정적] `objects[0].extra_field=1` → 400 / 최상위 `conf=0.5` → 400. `schemas.py:240`(`ObjectToVerify.model_config`)·`:248`(`VlmVerifyRequest.model_config`) **둘 다 `extra="forbid"` 라인 정확** — ⚠ 2차 회차 G-ISSUE-63이 지적한 구 드리프트(`204,211`, 응답 모델 오귀속)는 **이미 해소됨**(현재 카탈로그가 `240,248`로 이미 정정돼 있고 실제 코드와 일치) |
| TC-AIVLM-11 | PASS | [실동작] 3개 객체(`dog`→known,`zzz`→unknown,`truck`→known) 요청 순서 그대로 응답. `routers/vlm.py:91-101` 라인 정확 |
| TC-AIVLM-12 | PASS | [실동작] `POST /infer/vlm/meta`·`/infer/vlm/video-meta` 둘 다 HTTP 404. `test_vlm.py:52`(`test_vlm_router_video_meta_endpoint_removed`) 라인 정확 |
| TC-AIVLM-13 | PASS | [실동작] 컨테이너 기동 이후 verify-objects를 9회 이상 호출했으나 `docker logs klid-ai-server \| grep -c "returning mock verify-objects"` = **1**(WARN 1회만). `routers/vlm.py:72-80`(`_warn_mock_once`) 라인 정확 |
| TC-AIVLM-14 | PASS | [정적] `AiServerClient.java:91` `.uri("/infer/vlm/verify-objects")` vs `VlmClient.java:53` `DESCRIBE_PATH="/v1/videovlm/describe"` + `:67` 생성자(별도 클라이언트) — 경로·클라이언트 완전 분리 확인. 단 **G-ISSUE-61**(아래) 참조 — 이 분리 자체는 사실이나 `verifyObjects` 호출부가 프로덕션에 없어 케이스 전제("BE가 실제 호출")가 도달 불가 경로임 |

## G-6. 계약 정합 / 공통 인프라

| ID | 판정 | 근거 확인 |
|---|---|---|
| TC-AICONTRACT-01 | PASS | [정적] `detector_backend.py:67-85` `COCO_ID2LABEL` 80개, key 0~79 연속·중복 없음(코드 직접 카운트 확인). 라인 정확 |
| TC-AICONTRACT-02 | PASS | [정적] `CocoClasses.java:25-43`(`LABELS`, 80개)와 `detector_backend.py:67-85` **문자열·순서 완전 일치**(person…toothbrush) 직접 대조 확인. `CocoClassesDriftTest.java:48-72`(`matchesAiServerSource`) 라인 정확(48 `@Test`~72 assertion, 73 닫는 괄호) |
| TC-AICONTRACT-03 | PASS | [정적] `coco_label_from_id(0)=person, (2)=car, (79)=toothbrush` 코드 직접 확인. `detector_backend.py:88-96` 라인 정확 |
| TC-AICONTRACT-04 | PASS | [정적] `id2label` 우선 → 미스 시 `COCO_ID2LABEL.get(class_id, str(class_id))` 폴백 확인. 동일 라인 |
| TC-AICONTRACT-05 | PASS | [정적] `coco_id_from_label`: 알려진 라벨→COCO id, 숫자문자열→역파싱, 미지 라벨→해시 기반 결정적 id(`_UNKNOWN_LABEL_ID_BASE=10000` 오프셋, COCO 0~79와 비충돌). `detector_backend.py:107-124` 라인 정확 |
| TC-AICONTRACT-06 | PASS | [정적] `YoloResponse`(`schemas.py:68-88`) 필드 `detections/mock/source/mock_reason/success/message/error_code` 정확 일치. 라인 정확 |
| TC-AICONTRACT-07 | PASS | [실동작] `POST /infer/yolo/predict`→400(라우팅 O, 바디 검증 실패)·`/infer/sam2/segment`→400·`/infer/vlm/verify-objects`→400 vs `/infer/foo/bar`→404(라우팅 자체 부재)로 대조 확인. `main.py:65-67`(`include_router` 3건) 라인 정확 |
| TC-AICONTRACT-08 | PASS | [정적] `ai_mock_mode: bool = Field(default=False, ...)`. `config.py:26-33` 라인 정확 |
| TC-AICONTRACT-09 | PASS | [정적] `max_image_size_mb: int = Field(default=10, ge=1, le=100, ...)`. `config.py:47` 라인 정확 |
| TC-AICONTRACT-10 | PASS | [정적] `cors_origins_list()`가 콤마 분리+trim. `config.py:70-71` 라인 정확 |
| TC-AICONTRACT-11 | PASS | [정적] `test_config에_detector_backend_설정이_없음`이 `Settings.model_fields`에 `detector_backend`/`rtdetr_model_id` 부재·`resolved_detector_backend` 속성 부재를 단언. `test_yolo_dispatch.py:162` 라인 정확(함수 정의 그 줄) |
| TC-AICONTRACT-12 | PASS | [정적] `DetectionBoxNormalizer.normalizeBbox`: 좌표≠4/null/NaN·Infinity → `IllegalArgumentException`(all-or-nothing 거부), 정상 좌표는 0≤x≤bounds clamp, clamp 후 폭·높이≤0(퇴화)이면 `Optional.empty()`(해당 검출만 스킵) — ai-server는 정규화 책임이 없다는 케이스 전제와 일치. `DetectionBoxNormalizer.java:50-81` 라인 정확(normalizeBbox 50-69 + upperBound 71-77 + clamp 79-81 전체 포괄) |
| TC-AIINFRA-01 | PASS | [실동작] `GET /health` → `200 {"status":"ok"}`. `main.py:70-72` 라인 정확 |
| TC-AIINFRA-02 | PASS | [실동작] 임의 요청에 `x-request-id` 응답 헤더 항상 존재(위 모든 curl 응답에서 확인). `request_id.py:34-48` 라인 정확 |
| TC-AIINFRA-03 | PASS | [실동작] `X-Request-Id: abc-123` 요청 → 응답 헤더 동일값 `abc-123` 반사. `request_id.py:38-47` 라인 정확 |
| TC-AIINFRA-04 | PASS | [실동작] `X-Request-Id: abc;def`(비영숫자, `_is_safe_id`가 거부하는 문자) → 12자리 hex로 재생성(`1853754e8ab2`) 확인. (참고: curl 자체가 헤더값의 실제 `\r\n`을 전송 차단해 문자 그대로의 CRLF 재현은 도구 한계로 대체 문자셋으로 검증했으나, `_is_safe_id`가 영숫자+`-_`만 허용하므로 CRLF도 동일 분기로 거부됨을 코드로 확인) `request_id.py:39-41,51-54` 라인 정확 |
| TC-AIINFRA-05 | PASS | [실동작] 65자 id 요청 → 12자리 hex로 재생성 확인. `request_id.py:52-53`(`len(value) > 64: return False`) 라인 정확 |
| TC-AIINFRA-06 | PASS | [정적] `_unhandled` 핸들러가 `logger.exception`(서버 로그만)으로 기록하고 응답은 `{"error_code":"INTERNAL_ERROR","message":"서버 내부 오류"}`만 반환 — 예외 타입명·스택트레이스 응답 미노출. `exceptions.py:65-69` 라인 정확 |
| TC-AIINFRA-07 | PASS | [실동작]+[정적] 위 모든 400 응답(`INVALID_IMAGE`/`VALIDATION_ERROR` 등)이 `error_code`+`message`만 포함, 내부경로·스택 없음. `exceptions.py:29-31,51-59`(`_err`+`_validation`) 라인 정확 |
| TC-AIINFRA-08 | PASS | [정적] `ErrorResponse(model_config=ConfigDict(extra="forbid"))`. `schemas.py:23-29` 라인 정확 |

## 카탈로그 정정

**0건.** 담당 라인범위(115~162행) 34개 케이스의 근거 `file:line`을 전부 실측 대조했으며 전건 일치(라인 드리프트 없음). 2026-08-03 회차 최신화(변경이력 표 "정정 53건")가 이미 이 구간의 드리프트를 정리해둔 것으로 확인됨 — 특히 TC-AIVLM-10은 2차 검증(`G-ISSUE-63`)이 지적했던 `schemas.py:204,211`(응답 모델 오귀속) 오류가 현재 `240,248`로 이미 정정되어 코드와 정확히 일치.

## 이전 회차(2차, 2026-08-02) 이슈 대조 — 내 범위(G-5/G-6) 해당분

| 2차 이슈 | 내용 | 3차 상태 |
|---|---|---|
| G-ISSUE-62(2차) | `AiServerClient.verifyObjects` 프로덕션 호출부 0건(G-5 엔드포인트 전체가 도달 불가 표면), LOW | **미해소 — 이월**(아래 G-ISSUE-61로 3차 번호 재부여) |
| G-ISSUE-63(2차) 중 TC-AIVLM-10 드리프트분(`schemas.py:204,211`) | 근거 라인이 응답 모델(`ObjectVerification`)을 오귀속 | **✅ 해소** — 현재 `240,248`로 정정되어 코드와 일치 (나머지 TC-AIMOCK-43/45/46 드리프트는 G-10 소관, 내 범위 아님) |
| G-ISSUE-65(2차) TC-AIMOCK-40 | 외부 URL 대역 검사 첫 해석주소만 검사 | G-10 소관(TC-AIMOCK-40), 내 담당 라인범위(115~162행) 밖 — 확인 대상 아님 |
| G-ISSUE-09(2차) | TC-AIYOLO-15/27/45 근거 드리프트 | G-1/G-3 소관, 내 범위 밖 |

## 이슈 기록

### [G-ISSUE-61] TC-AIVLM-14 — `AiServerClient.verifyObjects` 프로덕션 호출부 0건(G-5 엔드포인트 전체가 도달 불가 표면) — 2차 G-ISSUE-62 이월
- **심각도**: LOW
- **기대 동작(기대효과)**: ai-server `/infer/vlm/verify-objects`는 "YOLO/SAM2 검출 라벨 정합성 검증" 목적으로 노출됐고, 카탈로그 §G-5 전제는 BE가 `AiServerClient.verifyObjects`로 이를 실제 호출하는 것이다. 노출된 추론 표면은 실사용되거나, 아니면 제거돼야 한다(OWASP API9:2023 Improper Inventory Management).
- **현재 동작(이슈 내용)**: 클라이언트 메서드는 여전히 정의만 있고 `src/main` 어디서도 호출되지 않는다.
  ```java
  // backend/.../common/client/AiServerClient.java:89-98
  public Mono<VlmVerifyResponse> verifyObjects(VlmVerifyRequest request) {
      return webClient.post().uri("/infer/vlm/verify-objects")...
  }
  ```
  `grep -rn "verifyObjects" backend/src/main backend/src/test` → `src/main` 정의부 1건뿐(호출 0건), `src/test`는 `AiInferenceDeidentReportGateTest.java:66` 주석 언급 1건뿐(실호출 아님). `grep -rn "infer/vlm" backend/src frontend/src` → BE 3건 전부 정의/주석/DTO 주석, FE 0건.
- **재현/확인 경로**: `grep -rn "verifyObjects" backend/src/main` (호출부 0건 재확인). `docker logs klid-ai-server 2>&1 | grep "verify-objects"`로 실제 호출 발신 IP를 보면 backend 컨테이너(172.20.0.5 등) 발신이 없음을 확인 가능(본 회차는 검증자 curl만 관측).
- **영향**: 기능 결함 아님(다른 파이프라인 동작에 영향 없음). ①인증 없는 추론 표면(ai-server는 무인증)이 사용처 없이 열려 있음 ②G-5 14개 케이스가 제품 동선에서 도달 불가능한 경로를 검증 중이라 검증 리소스 배분 왜곡. `AiInferenceDeidentReportGateTest.java:66` 주석은 "verifyObjects처럼 같은 이미지를 운반하는 다른 메서드"가 향후 배선 시 비식별 신고 게이트를 우회할 잠재 위험을 지적하는데 현재는 호출부가 없어 잠재 위험으로만 남음(CWE-359 배선 시 재확인 필요).
- **수정 방향(제안)**: 정책 결정 필요 — ①실사용 계획이 없으면 `AiServerClient.verifyObjects` + `ai-server/app/routers/vlm.py` 라우트 + 관련 스키마 제거(표면 축소) ②사용 계획이 있으면 배선 시 비식별 신고 게이트(`DeidentReportGate`)를 반드시 함께 적용. 어느 쪽이든 카탈로그 §G-5에 "현재 프로덕션 호출부 0건"을 명기해 다음 검증자가 도달 불가 경로임을 알게 한다. (2차 회차부터 2회 연속 관측 — 다음 회차에도 재확인 권장)
