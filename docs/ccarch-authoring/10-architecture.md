# ARCHITECTURE 노드 정의

> 저작도구 서브시스템(SUBSYSTEM) 1건. SYSTEM 전체 아키텍처는 본 폴더 범위 외.

## A-01. 저작도구 서브시스템 아키텍처

```json
{
  "type": "ARCHITECTURE",
  "title": "학습데이터 저작도구 서브시스템 아키텍처",
  "content": "## 모듈 구성\n\n- **Backend (Spring Boot 3.3, Java 17)** — 인증/DB/오케스트레이션, 라벨 CRUD, 검수, 버전관리, 외부 연동\n- **ai-server (Python 3.11, FastAPI)** — Stateless 추론 (YOLO/SAM2). 본 도구 영역, 별도 서비스로 분리 배포 (GPU 자원 격리)\n- **Frontend (React 18 + TS 5.4 + Vite 5)** — 관제 채널 + 포털 진입 라벨링 UI\n\n## 기술 스택 (Backend)\n\nSpring Security, Spring Data JPA + QueryDSL 5.1, MapStruct, Lombok, Flyway 10.13, Quartz, Resilience4j 2.2.0, WebFlux WebClient, JJWT 0.12.6, net.bramp.ffmpeg, Springdoc OpenAPI 2.5, Caffeine, Micrometer Prometheus.\n\n## DB\n\n- MariaDB 10.11.13 LTS — **klid_system 공유** (관제서버도 같은 DB 사용)\n- 이중 DataSource (control/portal), `ddl-auto=validate`, gradle.lockfile\n- 본 도구는 학습데이터 적재만 담당, 외부 학습데이터 시스템으로의 전달은 관제서버가 DB 직접 조회\n\n## 패키지 구조 (Backend 일부)\n\n```\nbackend/src/main/java/kr/co/cudo/authoring/\n├── batch/          Quartz Orchestrator + Step (FrameExtract/Deidentify/Yolo/Sam2/VlmTimeseries)\n├── label/          라벨 CRUD + LabelAccessGuard + Mask RLE\n├── assignment/     작업 배정 + TaskBoard\n├── review/         검수 워크플로우 + @Version 낙관적 잠금\n├── version/        Gitea 자동 커밋·diff·rollback\n├── augment/        증강 요청 + 검수\n├── meta/           시계열·외부 메타 검토 UI 백엔드\n├── preset/         라벨 프리셋\n├── portal/         포털 진입 사용자 라벨링 (저작도구 기능)\n├── sysconfig/      Caffeine TTL 60s 캐시\n└── common/         ApiResponse, ErrorCode, JwtAuthFilter, GlobalExceptionHandler, RoleHierarchy, ControlDataSourceConfig, PortalDataSourceConfig, WebClientConfig(32MB), Gitea/Ai/Vlm/Deidentify Client\n```\n\n## 자동 처리 파이프라인\n\n```\nPENDING (외부 책임으로 적재) → FRAME_EXTRACT(FFmpeg) → DEIDENTIFY(모든 영상 무조건) → YOLO → SAM2 → VLM_TIMESERIES(외부 VLM) → COMPLETED\n```\n- Quartz 1건/분 폴링 (interval-sec=60)\n- 재시도 max=3, exp backoff\n- 단계별 로그: LS_BATCH_PROC_LOG\n- local 비활성, dev/stg/prd 자동\n\n## 외부 연동 (Gitea 만 동기, 나머지는 모두 비동기)\n\n외부 시스템 연동은 원칙적으로 비동기로 처리한다. 단 Gitea 라벨 커밋만 예외로 동기 REST 호출 + 장애 시 fallback 큐.\n\n| 방향 | 상대 | 클라이언트 | 패턴 | 환경변수 |\n|---|---|---|---|---|\n| Outbound (비동기 위탁) | Deidentify 솔루션 | DeidentifyClient | 작업 등록 + 결과 수신, idempotency, 재등록 큐 | DEIDENTIFY_API_URL |\n| **Outbound (동기 REST)** | **Gitea** | **GiteaClient** | **timeout 70s + CircuitBreaker(50%/window10) + Retry max=3+exp backoff, 장애 시에만 fallback 큐(stg/prd)** | GITEA_BASE_URL/TOKEN/OWNER/REPO |\n| Outbound (비동기 위탁) | 외부 VLM 서비스 | VlmClient | 작업 등록 + 결과 수신, idempotency, 재등록 큐 | (운영 결정) |\n| Outbound (내부, 동기) | ai-server | AiServerClient | 본 도구 내 마이크로서비스. timeout 60s + CircuitBreaker (본 도구 영역 내부 호출) | AI_SERVER_URL |\n| Inbound (인증) | 관제서버/포털 서버 | JwtAuthenticationFilter | storage 공유 + JWT 검증 | JWT_SECRET, JWT_ISSUER |\n| Inbound (비동기 결과 인계) | 외부 생성형 AI 시스템 | /v1/augments/result | 결과 push 수신, idempotency, dead-letter | (운영 결정) |\n| DB 공유 | 관제서버 | klid_system 직접 조회 | — | — |\n\n## 인증·권한\n\n- 관제서버/포털 서버 발급 JWT(HS256) 검증 — 본 도구는 발급하지 않음\n- TokenClaims: `channel` + `role`\n- 채널: INTERNAL / PORTAL\n- 역할: REVIEWER / WORKER / PORTAL_USER\n- RoleHierarchy: REVIEWER 가 시스템 설정·사용자 관리·프리셋 권한 보유\n\n## 보안 매트릭스 (CWE/OWASP)\n\n| 위협 | 방어 | 위치 |\n|---|---|---|\n| IDOR (CWE-639) | LabelAccessGuard | LabelService, VersionService, Portal 라벨 |\n| Race Condition (CWE-362) | @Version 낙관적 잠금 | LsRawDataStatus |\n| DoS (CWE-770) | 페이지 size 100 한도, 좌표 점 1000 한도 | application.yml + LabelPointSerializer |\n| SSRF (CWE-918) | 외부 URL 은 설정값만 | WebClientConfig |\n| Mass Assignment (CWE-915) | record DTO + autoLblYn 무시 | LabelService |\n| Credential Exposure | 환경변수만 | JWT_SECRET, GITEA_TOKEN, DB_PASSWORD |\n| 권한 우회 | @PreAuthorize + requireReviewer 이중 가드 | TaskBoardController/Service |\n| Software Supply Chain | gradle.lockfile | backend/gradle.lockfile |\n\n## 환경 프로파일\n\n| 프로파일 | 배치 자동 | Gitea fallback 큐 | 비고 |\n|---|---|---|---|\n| local | false (수동) | false | H2, CORS=localhost:3000/5173, LocalProfileGuard |\n| dev | true | false | 운영 시크릿 ENV 필수 |\n| stg | true | true | Gitea fallback 큐 활성화 |\n| prd | true | true | 최고 가용성 |\n\n## 책임 외 명시\n\n본 서브시스템은 다음을 책임지지 않으며 외부 시스템(관제서버/포털 서버/분리발주 솔루션/별도 사업)이 담당한다.\n\n- 영상·이벤트 인입 경로 전체 (중계서버·1차 이벤트 시스템·VMS 변환·망연계)\n- 외부 시스템 본체 (비식별 솔루션·Gitea·외부 생성형 AI·외부 VLM·외부 학습데이터 마트)\n- 관제지원시스템 자체 기능 (GIS, 침수 시범, 모바일 공무원증, IP 화이트리스트)\n- 외부 포털 자체 기능 (회원가입·로그인·업로드·다운로드·공지사항·통계)",
  "attrs": {"scope": "SUBSYSTEM"},
  "_handle": "arch-authoring-subsystem"
}
```

---

## 등록 순서

ARCHITECTURE 는 COMPONENT/INTERFACE/ENTITY 등록 후 마지막에 등록한다. 다른 노드에 대한 `REFERS_TO` 링크 source 가 된다.

1. arch-authoring-subsystem
