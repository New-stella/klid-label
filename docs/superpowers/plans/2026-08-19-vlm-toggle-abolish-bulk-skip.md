# 구현 플랜 — 시계열 위탁 토글 폐지 + VLM 일괄 스킵 축

> 스펙: `docs/superpowers/specs/2026-08-19-vlm-toggle-abolish-bulk-skip-design.md`
> 키트: `docs/design/영상프레임-수집-DOMAIN-003/`
> 브랜치: `feat/vlm-toggle-abolish-bulk-skip-0819` (설계 커밋 `84452935` 위)

**태스크는 순차 실행한다** — 빌드/테스트 에이전트를 2개 이상 동시에 띄우면 `build/test-results` 충돌로
위양성 실패가 난다(프로젝트 `CLAUDE.md` 빌드 예산 절).

---

## Task 1 — `vlm.client.enabled` 토글 폐지 (R1·R2)

**설계**: `ADR-049`
**@design 태그**: `ADR-049`

### Files
| 파일 | 변경 |
|---|---|
| `common/config/WebClientConfig.java` | `vlmWebClient` 에서 `enabled` 파라미터 제거. `@Value("${vlm.client.url:}")`(기본 빈 값). `if (StringUtils.hasText(baseUrl)) urlPolicy.validate(baseUrl)` |
| `common/client/VlmClient.java` | 생성자 `enabled` 파라미터·필드 제거, `submitTimeseries` 의 조기반환 분기 제거, `isEnabled()` 제거 |
| `batch/step/VlmTimeseriesStep.java` | `doSubmit` 의 `if (!vlmClient.isEnabled())` NO-OP 분기 **제거**. `SKIP_REASON_DISABLED` 상수는 **존치**(I1) — javadoc 에 "신규 발생 없음, 과거 행 판독용" 명시 |
| `application.yml` | `vlm.client.enabled` 키 **제거**, `url: ${VLM_SERVICE_URL:}` (빈 기본값) |
| `application-local.yml` / `application-dev.yml` | `enabled` 키 제거 (url 은 `klid-mock-server:9400` 유지) |
| `backend/src/test/resources/application-local.yml` | `enabled` 키 제거 |
| `deploy/onprem/config/backend/env.template` | `VLM_CLIENT_ENABLED` 줄 제거, `VLM_SERVICE_URL=` **빈 값** + 주석(D3) |
| `deploy/onprem/config/backend/application.properties.template` | 동일 |
| `deploy/win-devkit/config/dev-env.example.ps1` | `VLM_CLIENT_ENABLED` 줄 제거 |
| `docker-compose*.yml` · `.env.example` · `backend/.env.example` | `VLM_CLIENT_ENABLED` 정리(있는 것만) |

### Tests (지우지 말고 뒤집는다)
- `architecture/DevProfileWiringGuardTest` — `devProfileEnablesVlmClient`/`localProfileEnablesVlmClient` 의 `enabled` 단언을 **URL 단언으로 교체**(`${VLM_SERVICE_URL` placeholder + `klid-mock-server:9400`)
- `common/config/VlmMockServerBootIntegrationTest` — `vlm.client.enabled=true` 프로퍼티 제거(URL·완화 플래그만으로 기동 확인)
- `batch/step/VlmTimeseriesStepTest` — `enabledFalseNoOp`·`skipRecordedWhenDisabled` 를 **"토글 분기가 없다"**로 뒤집음(수동 스킵 게이트는 그대로 통과해야 함)
- `batch/status/BatchStatusServiceTest` — `recordVlmSkipped` 케이스의 사유 문자열 단언을 상수 참조로(호출 자체는 유지 — 메서드는 남는다)
- **신규**: `common/config/VlmBlankUrlBootTest` — **A2** 고정. `vlm.client.url=""` + `prd` 계열 조건에서 `vlmWebClient` 빈이 생성되고 기동이 성공한다(ADR-049 기각안 회귀 차단)
- **신규**: `WebClientConfigTest` 에 "URL 이 채워져 있으면 검증한다"(A3) 케이스

---

## Task 2 — 승인 이력 게이트의 VLM 예외 (R7)

**설계**: `API-201`
**@design 태그**: `API-201`

### Files
- `batch/service/BatchStageRerunService.java` — 5번 게이트 `reviewApprovalGate.hasEverApproved(rawSn)` 를
  **`bundle == BatchStageBundle.AUTOLABEL` 일 때만** 평가하도록 좁힌다. 평가 위치(클레임 앞)는 **유지**(I8).
  `EVER_APPROVED_REASON` 문구에 오토라벨 한정임을 반영.

### Tests
- `batch/service/BatchStageRerunServiceTest` — 기존 "승인 이력이면 400" 케이스를 **AUTOLABEL 로 고정**하고,
  **신규**: "승인 이력이 있어도 VLM 은 수락된다"(A6). `hasEverApproved` 가 VLM 경로에서 **호출조차 되지 않는지**는 단언하지 않는다(구현 자유도).

---

## Task 3 — 일괄 3 API (R3·R4·R5·R6)

**설계**: `API-212` · `API-213` · `API-214` (계약 원본 `API-199`)
**@design 태그**: 각 컨트롤러 메서드에 해당 API ID

### Files (신규)
| 파일 | 내용 |
|---|---|
| `batch/dto/BatchStageSkipBulkRequest.java` | `record(@NotEmpty @Size(max=MAX_SIZE) List<@Min(1) Long> rawSns, @NotBlank @Size(max=ManualStageSkip.REASON_MAX_LENGTH) String reason)` + `distinctRawSns()`. `MAX_SIZE=100` |
| `batch/dto/BatchStageBulkRequest.java` | 해제·재수행 공용 — `rawSns` 만 |
| `batch/dto/BatchStageBulkResponse.java` | `API-199` 의 `BatchBulkRetryResponse` 를 **그대로 베낀 형태** — `(int successCount, int failureCount, List<Item> results)`, `Item(Long rawSn, boolean success, String reason)`, `Item.ok`/`Item.fail`, `of(List<Item>)` |
| `batch/service/BatchStageBulkService.java` | `skipAll` · `clearAll` · `rerunAll`. **판정 복제 금지** — `BatchStageSkipService.skip/clearSkip` · `BatchStageRerunService.rerun` 위임. `@Transactional` **없음**(건별 경계 격리 — `BatchBulkRetryService` 와 동일) |
| `batch/controller/BatchStageBulkController.java` | `@RequestMapping("/v1/videos")` · `@PreAuthorize("hasRole('REVIEWER')")`. `POST /batch/stages/{stage}/skip` · `DELETE /batch/stages/{stage}/skip`(본문 있음) · `POST /batch/stages/{stage}/rerun` |

### 규칙
- `stage` 는 컨트롤러/서비스에서 **`VLM` 만** 수락 → 그 외 400 `INVALID_INPUT`. 판정 지점은 **한 곳**(R5).
  거부 메시지에 **요청 값을 되비추지 않는다**(반사 XSS·로그 오염 — 기존 `requireSkippableBundle` 관례).
- 상한·빈 목록·원소 1 이상은 **Bean Validation 어노테이션**으로(A8). 중복 축약·순서 보존은 DTO 의 `LinkedHashSet`(A9).
- 건별 실패 사유는 `catch (CustomException e) → e.getMessage()`, `catch (RuntimeException) → 고정 문구`(A12).
- 한 건도 성공 못 해도 **200**(I7·A10).
- ⚠ **D1** — 이미 스킵/미스킵은 **성공**으로 처리한다(단건과 동일). 실패는 영상 없음·파생영상·예상 밖 오류뿐.
- ⚠ **경로 충돌 주의** — 기존 단건이 `/v1/videos/{rawSn}/batch/stages/{stage}/skip` 이라
  `{rawSn}` 자리에 리터럴 `batch` 가 오는 신규 경로와 매칭이 겹칠 수 있다. `{rawSn}` 이 `@Min(1) Long` 이라
  타입 변환에서 갈리지만, **테스트로 실제 라우팅을 고정**한다.

### Tests
- `batch/controller/BatchStageBulkControllerTest` — `BatchBulkRetryControllerTest` 를 베낀다:
  WORKER 403 · 미인증 401 · 빈 목록 400 · 101건 400 · 원소 0 400 · **stage=AUTOLABEL 400**(A7) · 부분 성공 200 · 전건 실패 200 · 사유에 스택트레이스 없음 · **경로 라우팅**(단건과 충돌하지 않음)
- `batch/service/BatchStageBulkServiceTest` — 전건 성공 / 부분 성공 / 중복 축약 / 순서 보존 / 예상외 예외 격리 / **단건 서비스 위임 검증**(판정 복제 없음)

---

## Task 4 — 증강 미연동 접수 거부 503 (R8)

**설계**: `API-060` (DOMAIN-007)
**@design 태그**: `API-060`

### Files
- **신규** `augment/integration/AugmentExternalMode.java` — `@Component`, `@Value("${authoring.augment.external.mode:http}")`,
  `public boolean isNoop()`. **판정 단일 원천**(D4). 값 비교는 대소문자 무시 + trim.
- `augment/service/AugmentRequestService.java` — `requireNotDerivative` **직후**에 게이트 추가:
  `if (externalMode.isNoop()) throw new CustomException(ErrorCode.SERVICE_UNAVAILABLE, "외부 증강 시스템과 연동되지 않아 요청을 접수할 수 없습니다.")`
  (인가·형식 검증 뒤 — 미인증자에게 연동 상태를 알리지 않는다)

### 하지 않는 것
- noop 클라이언트·조회 경로·만료 스윕 **불변**(R8). `GenAiIntegrationWiringGuard` 도 손대지 않는다.

### Tests
- `augment/service/AugmentRequestServiceTest` — **신규**: noop 이면 503(A13) · http 면 기존 경로 그대로(A14) ·
  게이트가 인가/형식 검증보다 **뒤**, 파생 400 **뒤**에 평가됨

---

## Task 5 — 문서 동기화 (R9)

| 파일 | 변경 |
|---|---|
| `docs/v2-wiki/00-local-setup.md` (15·163행) | `VLM_CLIENT_ENABLED` 전제 제거 → 「URL 주입 여부」로. `false 면 SKIPPED` 서술 폐기 |
| `docs/v2-wiki/09-vlm-timeseries.md` (62·69행) | 토글 SKIPPED 행 폐기 → 수동 스킵 축으로 교체 + **「먼저 스킵」 운영 지침** 추가 |
| `docs/test-cases/G-ai-server.md` | `TC-AIMOCK-45` **폐기 표기**(행 삭제 금지) · `TC-AIMOCK-42` 전제 문구 정정 · 신규 케이스 4건(A2·A4·A7·A13) · 상단 변경 이력 회차 추가 |
| `CLAUDE.md` | R13 「발동 경로 3개가 전부 막혀 성립하지 않는다」 → **네 번째 경로(승인 후 VLM 재수행)** 반영 |

근거는 `파일명(심볼명)` 형식으로 적고 **라인번호를 쓰지 않는다**.

---

## Task 6 — 검증·커밋·백필

1. **FULL 회귀 1회** (`cc-build-validator` `## 검증 모드: FULL`) — 예산 1800s, 실측 6~7분
2. **선별 커밋** — `git add -A` **절대 금지**(무관 untracked 31건)
3. **LogiCraft 백필** — 구현한 ITEM 별 `create_implementation_record`(IMPREC) + 키트 현황 갱신 + `CLAUDE.md` 키트 블록
4. `mc-logi-update` 권고 목록 제출 — **D1**(API-198/200/212/213 응답 서술·example 정정)
