# F. 포털(외부 채널) — 테스트 케이스

> 169 케이스 · 계층: unit / integration / security · [← README](README.md)

## 변경 이력

| 회차 | 일자 | 정정 | 신규 | 폐기 | 요약 |
|:--:|------|:--:|:--:|:--:|------|
| 1 | 2026-07-30 | 77건 | 10건 | 0건 | 포털 SAM2 원본→비식별 전송 전환(FrameImageEncoder 쌍둥이 삭제) + 게이트 412 반영, 포털 프레임 이미지 no-store 통일, PortalUploadProperties record 전환에 따른 라인 재정렬(PortalUploadService +6, PortalVideoUploadTxService -1), SecurityConfig 라인 전면 재확인(Phase 1·7 인가 표면 수정으로 매처 순서 이동), multipart 21MB/1100MB 는 prd 전용(공통은 500MB/1200MB)으로 정정, frame-interval-sec 기본값 5초 확정(UNCERTAINTIES #5 해소), TUS 스윕 2노드 조건부 UPDATE/DELETE 신규 케이스 |

## F-1. 채널·역할 게이팅 / 인가 경계

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-001 | PORTAL_USER+PORTAL 채널로 /v1/portal/** 허용 | PORTAL_USER | role=PORTAL_USER,channel=PORTAL | 200 | integration | High | SecurityConfig.java:134-135 |
| TC-PORTAL-002 | INTERNAL 채널(WORKER)로 /v1/portal/** 403 | WORKER/INTERNAL | GET /v1/portal/datamart/videos | 403 | security | High | SecurityConfig.java:134-135 |
| TC-PORTAL-003 | REVIEWER(INTERNAL)로 포털 API 403 | REVIEWER | /v1/portal/uploads | 403 | security | High | SecurityConfig.java:134-135 |
| TC-PORTAL-004 | PORTAL_USER이나 channel≠PORTAL → 403 | role=PORTAL_USER,channel=INTERNAL | /v1/portal/** | 403(allOf 두 조건) | security | High | SecurityConfig.java:135 |
| TC-PORTAL-005 | PORTAL_USER가 내부 /v1/frames/** 차단 | PORTAL 채널 | POST /v1/frames/{srcSn}/sam2-track(내부) | 403 — `/v1/frames/**` 전용 매처는 없고 하위 catch-all `/v1/**`(CHANNEL_INTERNAL 필수)이 차단 | security | High | SecurityConfig.java:147-153 |
| TC-PORTAL-006 | PORTAL_USER가 /v1/manage/** 차단 | PORTAL 채널 | PUT /v1/manage/labels/... | 403 | security | High | SecurityConfig.java:126-127 |
| TC-PORTAL-007 | PORTAL_USER가 /v1/notices 차단 | PORTAL 채널 | GET /v1/notices | 403 | security | Med | SecurityConfig.java:132 |
| TC-PORTAL-008 | 라벨 마스터 조회(GET)는 PORTAL_USER 허용 | PORTAL 채널 | GET /v1/manage/labels | 200(authenticated) | integration | Med | SecurityConfig.java:126 |
| TC-PORTAL-009 | 토큰 sub 없음 → 401 | actor.sub()==null | 포털 컨트롤러 진입 | 401 | unit | High | PortalUploadController.java:138-143 |
| TC-PORTAL-010 | FE 채널가드: PORTAL_USER의 내부 화면 접근 → forbidden | FE 라우팅 | PORTAL_USER /video/completed | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx:69-77 |
| TC-PORTAL-011 | FE 채널가드: INTERNAL의 /portal 접근 → forbidden | FE 라우팅 | 내부 사용자 /portal | FORBIDDEN_PAGE | unit | Med | portalGuard.test.tsx:79-101 |

> 근거 라인 전면 재확인(07-30): `SecurityConfig.java`는 Phase 1(dd288bed, 웹훅 인증 우회 차단 + 인가 표면 15건) · Phase 7(41b0504d, `/v1/genai/callback` permitAll 추가 등)에서 매처 순서·행이 이동했다. `/v1/portal/**` 매처는 108→134-135 행으로 이동. `/v1/frames/**` 전용 매처는 원래도 없었고(구 근거 "121-122"는 부정확했음), 실제로는 하위 범용 `/v1/**`(CHANNEL_INTERNAL + REVIEWER/WORKER/STREAM_SIGNED) 매처(147-153행)가 차단한다 — TC-PORTAL-005 근거를 정정.

## F-2. 데이터마트 Load (APPROVED 게이트 · IDOR · 비식별 서빙 · 신고 게이트)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-020 | 데이터마트 목록은 APPROVED만 노출 | 검수완료/미완료 혼재 | GET /v1/portal/datamart/videos | APPROVED만(INNER JOIN) | integration | High | PortalLabelService.java:130-161 |
| TC-PORTAL-021 | 프레임 0건 영상 목록 제외 | firstSrcSn 부재 | 목록 조회 | 제외 | unit | Med | PortalLabelService.java:147-157 |
| TC-PORTAL-022 | 목록 N+1 회피(IN 조회) | 다수 영상 | 목록 | 각 1회 IN 쿼리 | unit | Low | PortalLabelService.java:143-145 |
| TC-PORTAL-023 | 라벨 Load 페이징 clamp | rawSn 존재 | size=99999,page=-1 | size≤100, page≥0 | unit | Med | PortalLabelService.java:104-108 |
| TC-PORTAL-024 | rawSn null 라벨 Load → 400 | - | rawSn 누락 | INVALID_INPUT | unit | Med | PortalLabelService.java:101-103 |
| TC-PORTAL-025 | 프레임 라벨 Load: 본인 user-label 우선 | 본인 저장분 존재 | GET labels | user-label만 | unit | High | PortalLabelService.java:284-292 |
| TC-PORTAL-026 | 프레임 라벨 Load: 없으면 datamart 원본 | 본인 저장분 0 | 동일 | datamart 원본 | unit | High | PortalLabelService.java:293-300 |
| TC-PORTAL-027 | 미승인 영상 프레임 라벨 Load → 403 | not APPROVED | GET labels | 403 | security | High | PortalLabelService.java:256-259 |
| TC-PORTAL-028 | 비존재 srcSn Load → 404 | 부재 | 동일 | 404 | unit | Med | PortalLabelService.java:250-251 |
| TC-PORTAL-029 | 프레임 이미지 서빙: APPROVED 비식별만 | APPROVED | GET image | 200+nosniff+**no-store**(구 private/max-age=300 폐기, 아래 TC-PORTAL-037) | integration | High | PortalLabelService.java:414-460 |
| TC-PORTAL-030 | 프레임 이미지 서빙: 미승인 → 403 | 비APPROVED | 동일 | 403 | security | High | PortalLabelService.java:420-423 |
| TC-PORTAL-031 | deid 경로 부재 시 원본 폴백 금지 → 404 | deidFilePath null/blank | 동일 | 404(원본 차단) | security | High | PortalLabelService.java:430-434 |
| TC-PORTAL-032 | 프레임 이미지 Path Traversal 차단 | 경로 조작 | baseDir 밖 | resolveSafe 거부 | security | High | PortalLabelService.java:438-439 |
| TC-PORTAL-033 | baseDir=deidentified-path(raw 회귀 방지) | deid 절대경로 | 서빙 | startsWith 검증 | unit | Med | PortalLabelService.java:85-96,438-439 |
| TC-PORTAL-034 | 파일 부재 시 내부경로 비노출 404 | 파일 없음 | 서빙 | 404, 경로 미노출 | security | Med | PortalLabelService.java:440-443 |
| TC-PORTAL-035 (신규) | 신고 구간(DE_IDNTF_YN='F') 프레임 라벨 Load → 412 | 자기 rawSn 신고 중 | GET labels | 412(PRECONDITION_FAILED) — APPROVED 게이트만으로는 안 걸림(신고는 LS_RAW_DATA_STATUS 를 건드리지 않음) | security | High | PortalLabelService.java:261-266 |
| TC-PORTAL-036 (신규) | 신고 구간 프레임 이미지 서빙 → 412(파일 읽기 전 차단) | 자기 rawSn 신고 중 | GET image | 412, 파일 미판독 | security | High | PortalLabelService.java:425-427 |
| TC-PORTAL-037 (신규) | 프레임 이미지 응답 캐시는 no-store로 통일 | 임의 프레임 | GET image | `Cache-Control: no-store`(구 `private, max-age=300` 폐기 — 신고 직후 최대 5분 재노출되던 경로 차단) | security | High | PortalLabelService.java:452-456 |
| TC-PORTAL-038 (신규) | 신고 게이트는 자기 rawSn 행만 판정(조상/자손 전파 없음) — 파생영상은 부모 신고와 무관 | 부모 'F', 자기 rawSn 'Y' | GET labels/image (파생 srcSn) | 200(정상 서빙) — "파생 경유 열람"은 확정 정책(CLAUDE.md 2026-07-29)의 필연적 귀결이며 결함 아님. 조상 순회는 4라운드 시도 후 철회됨 | security | High | DeidentReportGate.java:23-37(javadoc), AiInferenceDeidentReportGateTest.java:339-351(내부 SAM2 경로로 동일 게이트 검증, 포털도 같은 컴포넌트 재사용) |

## F-3. 데이터마트 사용자 라벨 저장 (단방향 · 원본 미수정 · IDOR)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-040 | 사용자 라벨 저장은 LS_PORTAL_USER_LABEL만 | APPROVED | POST user-labels | 201, 원본 LS_DATA_LBL 불변 | integration | High | PortalLabelService.java:196-223 |
| TC-PORTAL-041 | 미승인 sourceRawSn 저장 시도 → 403 | 비APPROVED | 저장 | 403 | security | High | PortalLabelService.java:202-205 |
| TC-PORTAL-042 | SKELETON 저장: 17점 삼중값 통과 | type=SKELETON | 17점,v∈{0,1,2},x/y≥0 | 성공 | unit | Med | PortalLabelService.java:208-209 |
| TC-PORTAL-043 | SKELETON 개수≠17 → 400 | type=SKELETON | 16점 | 400 | unit | Med | PortalLabelService.java:324-327 |
| TC-PORTAL-044 | SKELETON v 범위 밖 → 400 | v=3 | 저장 | 400 | unit | Med | PortalLabelService.java:329-332 |
| TC-PORTAL-045 | SKELETON NaN/Infinity → 400 | x=NaN | 저장 | 400 | security | Med | PortalLabelService.java:333-336 |
| TC-PORTAL-046 | 비SKELETON 빈 좌표('[]','[[]]') 거부 → 400 | type=BBOX, points='[]' | 저장 | 400(빈 row 차단) | unit | High | PortalLabelService.java:211-216 |
| TC-PORTAL-047 | points @NotBlank NULL/공백 → 400 | null/공백 | 저장 | 400(DTO) | unit | Med | PortalUserLabelRequest.java:17 |
| TC-PORTAL-048 | 본인 작업 라벨 조회 IDOR(token sub) | 타인 저장분 존재 | GET user-labels?rawSn | 본인만 | security | High | PortalLabelService.java:226-234 |
| TC-PORTAL-049 | 빈 user-label row Load 제외(stale 방어) | pointCn NULL row | Load | 빈 항목 필터 | unit | Med | PortalLabelService.java:284-286 |
| TC-PORTAL-050 | 손상 좌표 JSON fail-secure | pointCn 손상 | Load | 빈 좌표, 500 미발생 | unit | Med | PortalLabelService.java:375-387 |

## F-4. 포털 SAM2 (★정책 위반 — 문서 정본상 "미제공" · 07-29 신고 게이트 + 비식별본 전용 전송)

> ★확정: CLAUDE.md/ADR-013상 포털 SAM2는 **제공되지 않아야 함**. 아래 케이스는 **현재 코드에 노출된 기능이 정책 위반임을 검증**하는 목적. 정상기능 케이스로 두지 않고 **결함(노출되어선 안 됨)으로 플래그**. → [UNCERTAINTIES.md #1](UNCERTAINTIES.md)
>
> ★07-30 갱신: 정책 위반(엔드포인트 존재 자체) 판정은 그대로다. 다만 3630558d 로 **전송 픽셀이 원본→비식별본으로 교체**됐고 **신고 게이트(412)** 가 배선됐다 — "노출은 결함이지만, 노출되는 한 나가는 픽셀은 비식별본이고 신고 구간은 막는다"로 위험도가 낮아졌다. 아래 TC-PORTAL-075~078(신규)이 이 변경을 검증한다.

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과(정책 기준) | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTAL-060 | 포털 SAM2 분할 엔드포인트 존재 자체가 정책 위반 | APPROVED 프레임 | POST /portal/frames/{srcSn}/sam2-segment | **정책상 미제공이어야 함 → 노출 결함 플래그** (현 코드: 좌표 반환, 전송 픽셀은 비식별본) | integration | High | PortalSam2Service.java:108-134 |
| TC-PORTAL-061 | 포털 SAM2 추적 엔드포인트 존재 자체가 정책 위반 | APPROVED 시작 프레임 | POST /portal/frames/{srcSn}/sam2-track | 정책상 미제공 → 결함 플래그 | integration | High | PortalSam2Service.java:142-190 |
| TC-PORTAL-062 | (구현 유지 시) 미승인 영상 SAM2 → 403 | 비APPROVED | segment/track | 403 | security | High | PortalSam2Service.java:218-226 |
| TC-PORTAL-063 | (구현 유지 시) 비존재 프레임 → 404 | 부재 | segment/track | 404 | unit | Med | PortalSam2Service.java:219-220 |
| TC-PORTAL-064 | (구현 유지 시) path≠body srcSn → 400 | 불일치 | segment/track | 400(CWE-345) | unit | High | PortalSam2Controller.java:81-84 |
| TC-PORTAL-065 | (구현 유지 시) mock 응답 자동적용 차단 | 모델 미로드 | segment | 빈 응답+MOCK 메시지 | unit | Med | PortalSam2Service.java:125-128 |
| TC-PORTAL-066 | (구현 유지 시) bulkhead 초과 → 429 | 동시 과다 | 다수 동시 | 429 | integration | High | PortalSam2Service.java:196-204 |
| TC-PORTAL-067 | (구현 유지 시) per-user rate limit → 429 | 단일 사용자 폭주 | 연속 | 429 | security | High | PortalSam2Service.java:267-275 |
| TC-PORTAL-068 | (구현 유지 시) track wall-clock 예산 초과 → 429 | 느린 추론 | track | 429 | unit | Med | PortalSam2Service.java:154-163 |
| TC-PORTAL-069 | (구현 유지 시) ai 응답 음수/NaN → 400 | 이상 응답 | segment | 400 | security | Med | PortalSam2Service.java:236-254 |
| TC-PORTAL-070 | (구현 유지 시) ai 빈/실패 → 502 | null/예외 | segment/track | EXTERNAL_API_ERROR | unit | Med | PortalSam2Service.java:120-122,174-176 |
| TC-PORTAL-071 | (구현 유지 시) trackId 로그 CRLF 정제 | 개행 | track 로그 | LogSanitizer | security | Low | PortalSam2Service.java:186-188 |
| TC-PORTAL-072 | FE 포털 도구바: AI분할/추적/스켈레톤 노출 여부 검증 | 포털 모드 | 라벨링 화면 | **정책상 SAM2 노출 안 돼야 함(현 노출=결함)** | unit | Med | LabelingPagePortalRestrictions.test.tsx:109-124 |
| TC-PORTAL-073 | FE 포털 라벨링: 검수제출 버튼 미렌더 | 포털 모드 | 화면 | submit-review-button 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx:86-91 |
| TC-PORTAL-074 | FE 포털 라벨링: VLM/시계열 메타 탭 미노출 | 포털 모드 | 화면 | 메타 탭·VLM 텍스트 없음 | unit | Med | LabelingPagePortalRestrictions.test.tsx:93-105 |
| TC-PORTAL-075 (신규) | 포털 SAM2 분할은 원본이 아니라 **비식별 프레임**만 ai-server 로 전송(원본 폴백 금지) | 정상('Y') 영상, 원본≠비식별 바이트 | POST sam2-segment | ai-server 요청 본문의 imageB64 == 비식별 프레임 인코딩, 원본과 불일치 — 구 `encodeToBase64(원본경로)` 원본픽셀 유출(CWE-359) 회귀 차단 | security | High | PortalSam2Service.java:113-116, AiInferenceDeidentReportGateTest.java:416-434 |
| TC-PORTAL-076 (신규) | 자기 rawSn 이 신고(F) 구간이면 포털 SAM2 분할 412 + ai-server 호출 0건(파일 읽기 전 차단) | 신고 중 | POST sam2-segment | 412(PRECONDITION_FAILED), `verifyNoInteractions(aiServerClient)` | security | High | PortalSam2Service.java:115, FrameImageEncoder.java:148-152, AiInferenceDeidentReportGateTest.java:387-400 |
| TC-PORTAL-077 (신규) | 자기 rawSn 이 신고 구간이면 포털 SAM2 추적 412 + ai-server 호출 0건 | 신고 중 | POST sam2-track | 412, ai-server 호출 0건 — 후속 프레임마다 재판정(추적 도중 신고 시 그 이후 프레임은 전송 안 됨) | security | High | PortalSam2Service.java:150-167, AiInferenceDeidentReportGateTest.java:402-414 |
| TC-PORTAL-078 (신규) | 게이트 없는 `encodeToBase64(String)` 오버로드는 더 이상 존재하지 않는다(원본픽셀 유출 경로 삭제 확인) | - | 정적 확인 | 모든 외부 추론 전송이 `resolveFrameImageForInference`/`encodeFrame`/`encodeDeidentifiedFrameForInference` → private `encode(Path)` 로 수렴, public 문자열 오버로드 부재 | unit | High | FrameImageEncoder.java:172-197(특히 185-189 주석) |

## F-5. 포털 이미지 업로드 (파일 검증 · all-or-nothing · 경계)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-001 | 정상 다중 이미지 업로드 → 201, UUID·READY | jpg/png ≤20MB | POST /portal/uploads/images | 201, 대표 프레임, READY | integration | High | PortalUploadService.java:82-151 |
| TC-PORTALUP-002 | 이미지 수 상한(50) 초과 → 400 | 51장 | 업로드 | 400 | unit | High | PortalUploadService.java:89-92 |
| TC-PORTALUP-003 | 정확히 50장 경계 → 성공 | 50장 | 업로드 | 201 | unit | Med | PortalUploadService.java:89-92 |
| TC-PORTALUP-004 | 빈 목록 → 400 | empty | 업로드 | 400 | unit | Med | PortalUploadService.java:86-88 |
| TC-PORTALUP-005 | 개당 20MB 초과 → 400 | 21MB | 업로드 | 400(서비스 검증 `maxImageSizeBytes`, 프로파일 무관 상수) | unit | High | PortalUploadService.java:274-277, PortalUploadProperties.java:40 |
| TC-PORTALUP-006 | 20MB 경계값 → 성공 | 정확히 20MB | 업로드 | 201 | unit | Med | PortalUploadService.java:274 |
| TC-PORTALUP-007 | multipart max-file-size 초과 → 413(prd 한정) | prd 프로파일, 22MB | 업로드 | prd: 21MB 상한 초과 → 파싱 단계 413. **local/dev/stg 는 500MB 상한이라 22MB 는 파싱을 통과하고 서비스 20MB 검증(400)에 걸린다** — 구 카탈로그가 프로파일 구분 없이 "22MB→413"으로 서술한 것을 정정(2026-07-28 설정 전수조사로 공통값이 500MB로 승격되고 prd만 21MB override) | integration | Med | application-prd.yml:37-39, application.yml:14-30, ConfigProfileDriftGuardTest.java:43-58 |
| TC-PORTALUP-008 | multipart max-request-size 초과 → 거부(prd 한정) | prd 프로파일, 총량 초과 | 업로드 | prd: 1100MB 초과 거부(50×21MB=1050MB+헤드룸 산식). **local/dev/stg 는 1200MB** | integration | Med | application-prd.yml:37-39, ConfigProfileDriftGuardTest.java:43-58 |
| TC-PORTALUP-009 | 확장자 allowlist 밖(gif/svg) → 400 | .gif | 업로드 | 400 | security | High | PortalUploadService.java:278-283 |
| TC-PORTALUP-010 | 매직바이트 미탐지 → 400 | txt 내용 .jpg | 업로드 | 400(JPEG/PNG만) | security | High | PortalUploadService.java:284-289 |
| TC-PORTALUP-011 | 확장자↔시그니처 불일치 → 400 | MIME 위조 | 업로드 | 400 | security | High | PortalUploadService.java:290-294 |
| TC-PORTALUP-012 | truncated JPEG(EOI 없음) → 400 | header-only | 업로드 | 400(CWE-434) | security | Med | PortalUploadService.java:295-301 |
| TC-PORTALUP-013 | all-or-nothing: 1장 실패 시 전체 미저장 | 50장 중 1장 위조 | 업로드 | 400, 디스크 미기록 | integration | High | PortalUploadService.java:94-108 |
| TC-PORTALUP-014 | write 후 DB INSERT 실패 시 보상 삭제 | INSERT 예외 | 업로드 | 롤백+고아 파일 삭제 | integration | High | PortalUploadService.java:137-146 |
| TC-PORTALUP-015 | 디스크 write IOException 시 롤백 | 디스크 고갈 | 업로드 | INTERNAL_ERROR+롤백 | unit | Med | PortalUploadService.java:339-359 |
| TC-PORTALUP-016 | 저장 파일명 UUID 강제(path traversal 없음) | 경로형 파일명 | 업로드 | UUID.ext 저장 | security | High | PortalUploadService.java:117-127 |
| TC-PORTALUP-017 | 원본명 255자 초과 truncate | 긴 파일명 | 업로드 | 255자 절단 | unit | Low | PortalUploadService.java:428-433 |
| TC-PORTALUP-018 | 업로드 per-user rate limit 초과 → 429 | 폭주 | 연속 업로드 | 429(CWE-770) | security | High | PortalUploadController.java:157-165 |

## F-6. 포털 자산 조회/서빙/삭제 (IDOR · 페이징 · 상태)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-020 | 본인 자산 목록 페이징 | 소유 자산 | GET /portal/uploads | 본인 것만 | integration | High | PortalUploadService.java:156-171 |
| TC-PORTALUP-021 | type 필터 IMAGE/VIDEO | - | ?type=IMAGE | 필터 결과 | unit | Med | PortalUploadService.java:164-170 |
| TC-PORTALUP-022 | 미지원 type → 400 | ?type=FOO | 목록 | 400 | unit | Med | PortalUploadService.java:163-168 |
| TC-PORTALUP-023 | 페이지 크기 하드캡(100) | size=500 | 목록/프레임 | 100 클램프 | unit | Med | PortalUploadController.java:145-151 |
| TC-PORTALUP-024 | 타 사용자 자산 상세 → 403 | 타인 uldSn | GET /{uldSn} | 403(부재와 동일) | security | High | PortalUploadService.java:174-181 |
| TC-PORTALUP-025 | 타 사용자 프레임 목록 → 403 | 타인 uldSn | /{uldSn}/frames | 403 | security | High | PortalUploadService.java:184-191 |
| TC-PORTALUP-026 | 타 사용자 프레임 이미지 → 403(IDOR) | 타인 uldFrmeSn | /frames/{uldFrmeSn}/image | 403 | security | High | PortalUploadService.java:204-210 |
| TC-PORTALUP-027 | 프레임 이미지 서빙: DB MIME+nosniff, 신고 게이트 대상 아님 | 소유 이미지(본인 업로드분) | 서빙 | Content-Type=저장MIME, nosniff — **비식별 신고 게이트·no-store 통일 대상 아님**(ADR-013 예외, LS_DATA_RAW 라이프사이클 없음. TC-PORTAL-037 과 혼동 금지) | security | High | PortalUploadService.java:193-231 |
| TC-PORTALUP-028 | 이미지 서빙 Path Traversal 차단 | 조작 경로 | 서빙 | resolveSafe 거부 | security | High | PortalUploadService.java:213,388-398 |
| TC-PORTALUP-029 | 자산 삭제: 파일 먼저 삭제 후 DB CASCADE | READY | DELETE /{uldSn} | 204, 파일+행 삭제 | integration | High | PortalUploadService.java:239-266 |
| TC-PORTALUP-030 | PROCESSING 중 삭제 → 409 | 추출 진행중 | DELETE | 409 | unit | High | PortalUploadService.java:245-250 |
| TC-PORTALUP-031 | 파일 삭제 IOException 시 DB 행 보존 5xx | 파일 삭제 실패 | DELETE | 5xx, DB 미삭제 | unit | Med | PortalUploadService.java:374-382 |
| TC-PORTALUP-032 | 타 사용자 자산 삭제 → 403 | 타인 uldSn | DELETE | 403 | security | High | PortalUploadService.java:242-243 |

## F-7. 포털 업로드 라벨 CRUD (전체교체 · 상한 · READY 가드 · 다운로드)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-040 | 라벨 전체교체(PUT) 멱등, 빈 배열=전체 삭제 | READY | PUT labels [] | 전체 삭제 | integration | High | PortalUploadLabelService.java:92-132 |
| TC-PORTALUP-041 | 검증 실패 시 DELETE 미실행(기존 유지) | 1건 위조 | PUT | 400, 기존 보존 | integration | High | PortalUploadLabelService.java:98-106 |
| TC-PORTALUP-042 | lblTypeCd allowlist(BBOX/POLYGON)만 | type=SEGMENT | PUT | 400 fail-closed | security | High | PortalUploadLabelService.java:250-255 |
| TC-PORTALUP-043 | 라벨 배열 상한 500 초과 → 400 | 501건 | PUT | 400(@Size+서비스 이중) | security | High | PortalUploadLabelController.java:63 |
| TC-PORTALUP-044 | BBOX 좌표 2점 아님 → 400 | BBOX 3점 | PUT | 400 | unit | Med | PortalUploadLabelService.java:266-269 |
| TC-PORTALUP-045 | POLYGON 3~200점 경계 → 400 | 2점/201점 | PUT | 400 | unit | Med | PortalUploadLabelService.java:270-275 |
| TC-PORTALUP-046 | 좌표 NaN/Infinity → 400 | Inf | PUT | 400 | security | Med | PortalUploadLabelService.java:285-288 |
| TC-PORTALUP-047 | label 80자 초과 → 400 | 81자 | PUT | 400 | unit | Low | PortalUploadLabelService.java:257-260 |
| TC-PORTALUP-048 | READY 외 상태 라벨 PUT → 409 | 비READY | PUT | 409 | unit | High | PortalUploadLabelService.java:115-118 |
| TC-PORTALUP-049 | 동시 PUT 프레임 락 직렬화 | 병렬 PUT | 동일 프레임 | 비관적 락 | integration | Med | PortalUploadLabelService.java:108-109 |
| TC-PORTALUP-050 | 타 사용자 프레임 라벨 PUT → 403 | 타인 uldFrmeSn | PUT | 403 | security | High | PortalUploadLabelService.java:109-110 |
| TC-PORTALUP-051 | 라벨 PUT 본문 2MB 초과 → 413(파싱 전 조기) | 대용량 | PUT | 413(pre-parse DoS) | security | High | PortalLabelBodySizeFilter.java:73-78 |
| TC-PORTALUP-052 | 라벨 PUT chunked/Content-Length 부재 → 411 | -1 | PUT | 411 | security | High | PortalLabelBodySizeFilter.java:66-72 |
| TC-PORTALUP-053 | body size 필터 라벨 PUT 경로만 적용 | 다른 경로 | 다른 PUT | 필터 skip | unit | Low | PortalLabelBodySizeFilter.java:54-60 |
| TC-PORTALUP-054 | 라벨 목록 조회(소유자 스코프), 타인/부재 403 | 타인 프레임 | GET labels | 403 | security | Med | PortalUploadLabelService.java:137-143 |
| TC-PORTALUP-055 | export JSON: 고정명+라벨 N+1 회피 | 소유 자산 | GET export | JSON attachment | integration | Med | PortalUploadLabelService.java:152-201 |
| TC-PORTALUP-056 | 원본 다운로드 Content-Disposition CRLF 인젝션 차단 | 파일명 CRLF | GET file | 제어문자 제거, filename* 인코딩 | security | High | PortalUploadLabelService.java:321-345 |
| TC-PORTALUP-057 | 원본 다운로드: 경로 미확정/부재 → 404 | filePathNm null | GET file | 404 | unit | Med | PortalUploadLabelService.java:216-227 |
| TC-PORTALUP-058 | 타 사용자 export/다운로드 → 403 | 타인 uldSn | export/file | 403 | security | High | PortalUploadLabelService.java:154-156 |

> F-7 은 PortalUploadLabelService.java·PortalUploadLabelController.java 모두 2026-07-25 이후 무변경(`git log --since=2026-07-25` 0건) — 라인 재확인만 하고 값은 유지.

## F-8. 포털 영상 TUS 업로드 (세션 · 재개 · 완료검증 · 동시성)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-TUS-001 | OPTIONS: TUS 능력 광고(max 5GB) | PORTAL_USER | OPTIONS tus | 204+Tus-Max-Size=5368709120 | integration | Med | PortalTusUploadController.java:67-76 |
| TC-TUS-002 | POST 세션 생성 → 201+Location | 유효 Upload-Length·확장자 | POST | 201, Location /tus/{uldId} | integration | High | PortalVideoUploadService.java:80-122 |
| TC-TUS-003 | Upload-Length 누락 → 400 | 헤더 없음 | POST | 400 | unit | Med | PortalTusUploadController.java:88-90 |
| TC-TUS-004 | Upload-Length 5GB 초과 → 413 | >maxFileSizeBytes | POST | 413 | security | High | PortalVideoUploadService.java:87-90 |
| TC-TUS-005 | 확장자 allowlist(mp4/mov/avi) 밖 → 400 | .exe | POST | 400 | security | High | PortalVideoUploadService.java:91-95 |
| TC-TUS-006 | 동시 진행 세션 상한(3) 초과 → 429 | IN_PROGRESS 3건 | POST | 429 | security | Med | PortalVideoUploadService.java:96-102 |
| TC-TUS-007 | 저장 파일명 UUID 강제(경로순회 차단) | 경로형 메타 | POST | UUID.ext | security | High | PortalVideoUploadService.java:104-107 |
| TC-TUS-008 | Upload-Metadata 1KB 초과 → 413 | 큰 메타 | POST | 413 | security | Low | PortalTusUploadController.java:182-184 |
| TC-TUS-009 | Upload-Metadata base64 디코딩 실패 → 400 | 잘못된 base64 | POST | 400 | unit | Low | PortalTusUploadController.java:194-200 |
| TC-TUS-010 | 지원 안 되는 Tus-Resumable → 412 | 버전 불일치 | 모든 메서드 | 412 | unit | Med | PortalTusUploadController.java:163-168 |
| TC-TUS-011 | HEAD offset 조회(재개) | 소유 세션 | HEAD | Upload-Offset/Length+no-store | integration | High | PortalTusUploadController.java:102-116 |
| TC-TUS-012 | 타 사용자 세션 HEAD/PATCH/DELETE → 403(IDOR) | 타인 세션 | 각 메서드 | 403 | security | High | PortalVideoUploadService.java:130-132 |
| TC-TUS-013 | 만료(24h) 세션 HEAD → 410 | expiresAt 경과 | HEAD | 410 | unit | Med | PortalVideoUploadService.java:133-135 |
| TC-TUS-014 | PATCH 청크 append offset 전진 | 진행중 | PATCH | 204+새 Upload-Offset | integration | High | PortalVideoUploadTxService.java:68-124 |
| TC-TUS-015 | PATCH Upload-Offset 누락 → 400 | 헤더 없음 | PATCH | 400 | unit | Med | PortalTusUploadController.java:130-132 |
| TC-TUS-016 | offset 불일치(재개 무결성) → 409 | expected≠서버 | PATCH | 409 | integration | High | PortalVideoUploadTxService.java:98-100 |
| TC-TUS-017 | offset 범위 밖(음수/length 초과) → 400 | 잘못된 offset | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java:95-97 |
| TC-TUS-018 | 청크 크기 상한(16MB) 초과 → 413 | >maxChunk | PATCH | 413 | security | Med | PortalVideoUploadTxService.java:91-94 |
| TC-TUS-019 | 청크 길이가 잔여 용량 초과 → 400 | offset+len>length | PATCH | 400 | unit | Med | PortalVideoUploadTxService.java:101-103 |
| TC-TUS-020 | 동시 PATCH 낙관적 락 충돌 → 409+truncate 복원 | 병렬 PATCH | 동일 세션 | 409, offset 복원 | integration | High | PortalVideoUploadTxService.java:109-115 |
| TC-TUS-021 | 취소된 세션 PATCH → 409 | CANCELLED | PATCH | 409 | unit | Med | PortalVideoUploadTxService.java:81-83 |
| TC-TUS-022 | 완료 세션 마지막 청크 재전송 → 멱등 | COMPLETED | PATCH | 완료 응답(uldSn 재반환) | unit | High | PortalVideoUploadTxService.java:84-87 |
| TC-TUS-023 | 완료 검증: 매직바이트 불일치 → 거부(CANCELLED)+400 | 위조 컨테이너 | 최종 청크 | 400+파일 삭제+CANCELLED | security | High | PortalVideoUploadService.java:161-165 |
| TC-TUS-024 | 완료 검증: ffprobe 실패 → 400+CANCELLED | 손상 파일 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java:167-175 |
| TC-TUS-025 | 완료 검증: 비디오 스트림 없음 → 400 | 오디오만 | 최종 청크 | 400 | unit | Med | PortalVideoUploadService.java:176-179 |
| TC-TUS-026 | 완료 검증은 락/트랜잭션 밖(커넥션 점유 방지) | 대용량 | 최종 청크 | 짧은 tx 후 ffprobe | integration | Med | PortalVideoUploadService.java:146-186 |
| TC-TUS-027 | 완료 원자 전이(멱등): affectedRows==1만 이벤트 | 동시 완료 | finalizeCompleted | 1회만 이벤트 | integration | High | PortalVideoUploadTxService.java:132-157 |
| TC-TUS-028 | 검증 중 취소된 세션 완료 시도 → 409+ULD 보상 삭제 | 완료 중 cancel | finalizeCompleted | 409, ULD 삭제 | integration | Med | PortalVideoUploadTxService.java:141-152 |
| TC-TUS-029 | DELETE 세션 취소+임시파일 삭제 | 진행중 | DELETE | 204+파일 삭제+CANCELLED | integration | Med | PortalVideoUploadService.java:190-206 |
| TC-TUS-030 | 완료 세션 DELETE → no-op(영구 파일 삭제 금지) | COMPLETED | DELETE | 파일 미삭제 | unit | High | PortalVideoUploadService.java:198-201 |
| TC-TUS-031 | cancel도 행 잠금(PATCH와 직렬화) | 병렬 PATCH+cancel | DELETE | 락 통일 | integration | Med | PortalVideoUploadService.java:192-194 |
| TC-TUS-032 | TUS 저장 경로 Path Traversal 차단 | 조작 경로 | 세션 생성 | resolveSafe 거부 | security | High | PortalVideoUploadService.java:210-218 |

> F-8 은 PortalTusUploadController.java·PortalVideoUploadService.java 자체는 2026-07-25 이후 무변경(라인 유지). `PortalVideoUploadTxService.java`는 9dfa0d6e(`PortalUploadProperties` record 전환)로 생성자에서 `@Value` import 1줄이 빠지며 이후 라인이 전체 **-1** 이동 — TC-TUS-014~028 라인을 재확인해 반영(값·순서·응답코드는 변경 없음).

## F-9. 포털 영상 프레임 추출 (비동기 · 간격 · 상한 · 실패)

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-060 | 영상 완료 후 AFTER_COMMIT → 비동기 추출 | 완료 이벤트 | PortalVideoUploadedEvent | 별도 풀에서 추출 시작 | integration | High | PortalFrameExtractRunner.java:71-82 |
| TC-PORTALUP-061 | UPLOADED→PROCESSING 원자 전이 실패 → 중단 | 이미 삭제 | beginProcessing | skip | unit | Med | PortalFrameExtractRunner.java:86-91 |
| TC-PORTALUP-062 | 프레임 간격 sysconfig 스냅샷(기본 5초) | config 5 | 추출 | 5초 간격 — **확정(UNCERTAINTIES #5 해소)**: DB 시드값(V109) = 코드 폴백(`DEFAULT_INTERVAL_SEC`) = **5**, 60 아님 | unit | Med | PortalFrameExtractRunner.java:104-105,185-193, V109__create_ls_portal_uld.sql:101, ConfigKeys.java:40-44 |
| TC-PORTALUP-063 | sysconfig 미설정/오류 시 5초 폴백 | config 부재 | 추출 | DEFAULT_INTERVAL_SEC=5 | unit | Med | PortalFrameExtractRunner.java:185-193 |
| TC-PORTALUP-064 | 프레임 간격 config 경계 [1,600] | 검증 | - | 범위 밖 거부 | unit | Low | ConfigKeys.java:71 |
| TC-PORTALUP-065 | maxFrames 2000 초과 → 균등 샘플링 | 긴 영상 | 추출 | ≤2000 균등 | unit | High | PortalFrameExtractRunner.java:147-183 |
| TC-PORTALUP-066 | maxFrames 경계: 정확히 2000 | - | computeFrameNumbers | 2000 이하 | unit | Med | PortalFrameExtractRunner.java:161-178 |
| TC-PORTALUP-067 | 영상 길이<간격 → 최소 1프레임 보장 | 짧은 영상 | 추출 | 프레임 1개 | unit | Med | PortalFrameExtractRunner.java:158-160 |
| TC-PORTALUP-068 | fps 미상 시 30 폴백 | probe fps 0 | 추출 | DEFAULT_FPS=30 | unit | Low | PortalFrameExtractRunner.java:102,148 |
| TC-PORTALUP-069 | 추출 성공 후 원자 커밋 READY+메타 | 전체 추출 | completeReady | READY | integration | High | PortalFrameExtractRunner.java:126-132 |
| TC-PORTALUP-070 | ffmpeg/probe 실패 → 부분 파일 정리+FAILED | 예외 | 추출 | FAILED+파일 정리 | unit | High | PortalFrameExtractRunner.java:133-138 |
| TC-PORTALUP-071 | 진행 중 자산 삭제 감지 → 중단+정리 | mid-extract 삭제 | touchProcessing | abort+cleanup | integration | Med | PortalFrameExtractRunner.java:113-119 |
| TC-PORTALUP-072 | 프레임 출력 경로 Path Traversal 차단 | 조작 uldSn | resolveSafeFramesDir | root 밖 거부 | security | Med | PortalFrameExtractRunner.java:195-201 |
| TC-PORTALUP-073 | @Async 예외 전파 금지(runner 흡수) | 예외 | runAsync | 로그만 | unit | Low | PortalFrameExtractRunner.java:76-82 |

> `PortalFrameExtractRunner.java`는 2026-07-25 이후 무변경 — 라인 전부 유지, `properties.maxFrames()` 시그니처도 `PortalUploadProperties` record 전환의 영향을 받지 않음(호출부 불변).

## F-10. 상태 전이 · 정리 스윕 / F-11. 내부 파이프라인 분리

| ID | 케이스명 | 전제 | 입력/조건 | 기대결과 | 계층 | 우선 | 근거(file:line) |
|----|---------|------|----------|---------|------|:--:|----------------|
| TC-PORTALUP-080 | LsPortalUld 상태 전이 비즈니스 메서드만 | - | markProcessing/Ready/Failed | @Setter 없이 전이 | unit | Med | LsPortalUld.java:114-138 |
| TC-PORTALUP-081 | 이미지 업로드는 즉시 READY | 이미지 | uploadImages | markReady(null,null,1) | unit | Med | PortalUploadService.java:128 |
| TC-PORTALUP-082 | 만료 TUS 세션 스윕: 임시파일+행 제거 | EXPRY_DT 경과 | 30분 스윕 | 세션 정리 | integration | Med | PortalUploadSweepJob.java:71-77 |
| TC-PORTALUP-083 | 고착 자산(30m 정지) → FAILED+프레임 정리 | stuck-timeout 경과 | 스윕 | FAILED, 원본 보존 | integration | Med | PortalUploadSweepJob.java:83-89 |
| TC-PORTALUP-084 | 스윕 프레임 디렉토리 정리 root 가드 | 경로 검증 | cleanupFrameDir | root 밖 skip | security | Low | PortalUploadSweepJob.java:92-97 |
| TC-PORTALUP-085 | 스윕 트랜잭션 경계 별 위임(프록시 우회 방지) | - | run | txService 위임 | unit | Low | PortalUploadSweepJob.java:39,71-72,83-84 |
| TC-PORTALUP-086 (신규) | 만료 TUS 세션 정리는 조건부 DELETE로 2노드 중복 실행 안전 | 2노드 동시 스윕 | claimExpiredSessions | 한 노드만 1행 삭제(파일 정리도 그 노드만), 다른 노드는 0행(멱등, 예외 없음) — Active-Active 배포(CLAUDE.md "배치 성능") 대응 | integration | Med | PortalUploadSweepTxService.java:45-56, LsPortalTusUploadRepository.java:57-68 |
| TC-PORTALUP-087 (신규) | 고착 자산 FAILED 전이도 조건부 UPDATE로 2노드 중복 방지 | 2노드 동시 스윕 | failStuckUploads | 조건부 UPDATE(`failIfInStatus`, 상태 재확인 WHERE)로 한쪽만 1행 전이 | integration | Med | PortalUploadSweepTxService.java:67-84 |
| TC-PORTAL-090 | 포털 업로드는 LS_PORTAL_* 전용, 내부 미참조 | 라벨 서비스 | - | 포털 3종 리포지토리만 | integration | High | PortalUploadLabelService.java:78-82 |
| TC-PORTAL-091 | 포털 업로드 영상 비식별 미적용 | 영상 업로드 | 완료 | 비식별 파이프라인 미연결 | integration | High | PortalVideoUploadService.java:28-31 |
| TC-PORTAL-092 | 포털 사용자 라벨 저장이 LS_DATA_LBL 불변 | user-label 저장 | 실행 | 데이터마트 원본 오염 없음 | integration | High | PortalLabelService.java:196-223 |
| TC-PORTAL-093 | 포털 프레임 추출 풀 관제 배치와 격리 | 추출 실행 | - | portalExtractExecutor 별도 풀 | integration | Med | PortalFrameExtractRunner.java:25-28 |
| TC-PORTAL-094 | 포털 자산은 데이터마트 View 미노출 | 포털 자산 존재 | 데이터마트 뷰 | 미포함 | integration | Med | PortalUploadLabelService.java:44-46 |

> **불확실 항목**: #1 포털 SAM2(문서=미제공, 확정됨 — 07-30 재확인: 여전히 정책 위반, 다만 전송 픽셀은 비식별본으로 교체되고 신고 게이트가 배선됨) · #5 frame-interval **확정: 5초**(07-30 해소, DB 시드=코드 폴백=5) · #11 다운로드 기간 제한(미확정 유지) · #12 데이터마트/이미지 서빙 rate limit 부재(미확정 유지 — user-labels·datamart 목록·`PortalLabelService#serveFrameImage`에는 여전히 rate limiter 없음, 업로드/SAM2/TUS만 있음) → [UNCERTAINTIES.md](UNCERTAINTIES.md)
