# 19. 외부 시스템 · 보안 · CVAT 포팅 · 설계 문서

> 출처: CLAUDE.md, R1 NFR, `.claude/rules/security.md`, 코드(`common/client`, `webhook`, `common/util`), docs/design
> 관련: 전체 페이지

## 19.1 외부 시스템

### A. 연동 외부 시스템 (호출/통신)

| 외부 시스템 | 연동 | 코드 |
|------------|------|------|
| **관제서버** | JWT 발급, TASK_COMPLETED/MODIFIED 통지 수신, 조회 API + View SELECT, MNG_* 소유 | `ControlNotifyClient`, `controlnotify/` → [15](15-control-notify.md) |
| **포털 서버** | 포털 DB 공유(데이터마트 영상 Load) | `PortalDataSourceConfig`, `portal/` → [16](16-portal.md) |
| **비식별화 서버** | 영상 비식별 위탁 + 콜백 (발주기관 SW 직접구매) | `DeidentifyClient`, `webhook/DeidentifyResultController` → [08](08-deidentification.md) |
| **외부 VLM 서비스** | 시계열 메타 호출 + 콜백 | `VlmClient`, `VlmTimeseriesStep`, `webhook/VlmResultController` → [09](09-vlm-timeseries.md) |
| **외부 증강/생성 시스템** | 증강 위탁 + 콜백 | `ExternalAugmentClient`, `webhook/AugmentResultController` → [14](14-augmentation.md) |

> **ai-server(YOLO/SAM2/VLM 추론)는 외부 아님** — 모노레포 내부 별도 프로세스(`AiServerClient`). → [11](11-ai-assisted.md)

### B. 범위 외 시스템 (책임 위임)

데이터마트 · 생성형 AI 본체 · VLM 모델 본체 · 영상 합성 모델 본체 · 학습데이터 Export → 외부 책임. 저작도구는 연동/검수만. → [01 §1.2](01-system-overview.md#12-책임-범위)

### 공통 연동 인프라
- **Resilience4j** — 모든 외부 호출 타임아웃/재시도/서킷 (VLM 45s, ai-server 60s, 비식별 ~70s)
- **웹훅 멱등성** — `webhook/` + `LS_WEBHOOK_IDEMPOTENCY` (In-Memory/Persistent Ledger), HMAC + idempotencyKey
- **Fallback 큐** — `LS_CONTROL_NOTIFY_FALLBACK`, `LS_GITEA_FALLBACK_QUEUE`

## 19.2 보안

기준: [`.claude/rules/security.md`](../../.claude/rules/security.md) (OWASP Top 10 2025, CWE Top 25, Fortify/CodeQL).

| 영역 | 정책 |
|------|------|
| 인증 | 관제/포털 JWT 인계(`JwtAuthenticationFilter`), 독립 로그인 없음, `alg:none` 금지 |
| 인가 | `@PreAuthorize` 역할 분기, IDOR 차단(`LabelAccessGuard`, 본인 배정 외 403) |
| 입력 검증 | `@Valid`, 시스템 설정 화이트리스트 키(CWE-20), Mass Assignment 방지(DTO 분리) |
| SSRF (CWE-918) | 외부 연동 base-url 고정, 사용자 입력 URL 구성 금지 |
| 경로 순회 (CWE-22) | 비식별 출력 `STORAGE_DEIDENTIFIED_PATH` 하위 강제, `Path.normalize` |
| 정보 노출 (CWE-209) | 응답에 내부 파일 경로·스택트레이스 미포함 |
| 민감정보 (NFR-005) | PII·토큰 로그 출력 금지(Logback MaskingPatternLayout), 영상 암호화 저장, 통지 페이로드 PII 미포함 |
| 파일 업로드 | 확장자 allowlist + 크기 제한 + MIME 검증 |
| DoS (CWE-770) | RLE 변환 MAX_PIXELS 제한, 이미지 20MB 상한, diff 500건 상한 |

## 19.3 CVAT 포팅

CVAT(Django+TS) → 본 프로젝트(Spring Boot+TS). 전체 fork 아닌 **9개 독립 모듈 Phase별 포팅** (`docs/analysis/portable-modules/`).

| 모듈 | 구현 | 위치 |
|------|------|------|
| 트랙 보간 알고리즘 | `TrackInterpolator` (선형 보간) | `batch/interpolation/` → [11](11-ai-assisted.md) |
| MASK ↔ RLE ↔ Polygon 변환 | `MaskRleConverter` (CWE-770 방어) | `common/util/` |
| Polygon 단순화 | `PolygonSimplifier` (Ramer-Douglas-Peucker) | `common/util/` |
| YOLO/COCO 변환 | `YoloCocoConverter`, `CocoJson` 등 | `common/util/` |
| manifest.jsonl / TUS 업로드 | 프레임 매니페스트 / 재개 업로드 | → [05](05-video-management.md) |
| 캔버스 드로잉 패턴 | konva.js | `frontend label/canvas` → [10](10-labeling.md) |
| RQ Worker → Quartz Job 매핑 | 배치 스케줄 | → [07](07-batch-pipeline.md) |

> `cvat/`는 `.gitignore` 참조용. 소스 직접 import 금지 — 분석 문서 기반 Java 재구현.

## 19.4 설계 문서 카탈로그

| 문서 | 내용 | ID 체계 |
|------|------|---------|
| [R1 사용자요구사항정의서](../design/R1-사용자요구사항정의서.md) (v1.17) | SFR 14건(06-03·07·08·09) + NFR 7건 | `RQ-SFR-NN-NN` |
| [R2 유스케이스명세서](../design/R2-유스케이스명세서.md) | 10 서브시스템·9 액터·13 유스케이스 | `KLID-AT-SS/UC/ACT-*` |
| [R3 요구사항추적표](../design/R3-요구사항추적표.md) | SFR→UC→SC→CO 매핑 | - |
| [D1 클래스설계서](../design/D1-클래스설계서.md) | 클래스 | `KLID-AT-CL-*` |
| [D2 사용자인터페이스설계서](../design/D2-사용자인터페이스설계서.md) | 화면 23개 | `KLID-AT-SC-*` |
| [D3 컴포넌트설계서](../design/D3-컴포넌트설계서.md) | 4 UCD별 컴포넌트 | `KLID-AT-CO-*` |
| [D8 엔티티관계모형설계서](../design/D8-엔티티관계모형설계서.md) | ERD | - |
| [D9 데이터베이스설계서](../design/D9-데이터베이스설계서.md) | 테이블 명세 | - |

### 서브시스템 (KLID-AT-SS, R2)
SS-001 사용자/권한 · SS-002 마킹 · SS-003 배치 파이프라인 · SS-004 비식별화 · SS-005 시계열 메타 · SS-006 라벨링 · SS-007 검수 · SS-008 버전관리 · SS-009 데이터 증강 · SS-010 포털.

### NFR
| NFR | 내용 | 목표 |
|-----|------|------|
| NFR-001 | 배치 파이프라인 처리량 | ≥ 1건/분 (Quartz 단일) |
| NFR-002 | 이미지 학습데이터 규모 | ≥ 100,000장 |
| NFR-003 | 영상 학습데이터 규모 | ≥ 5,000건 |
| NFR-004 | 외부 API 연동 복원력 | Resilience4j 100% |
| NFR-005 | 민감정보 보호 | PII/토큰 노출 0건 |
| NFR-006 | 포털 웹 접근성 | WCAG 2.1 AA |
| NFR-007 | 메트릭·추적성 | 메트릭 수집 100%, traceId 전파 |

### SFR (요구사항)
SFR-06-03(해상도 변경) · SFR-07-01~03(증강) · SFR-08-01~05(라벨링·정밀도·버전) · SFR-09-01~05(비식별). 상세 → [R1](../design/R1-사용자요구사항정의서.md).
