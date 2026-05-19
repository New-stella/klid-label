# ARCHITECTURE 노드 정의

> ccarch type: `ARCHITECTURE` / 필수: `title`, `content` / 옵션: `scope`(SYSTEM|SUBSYSTEM|COMPONENT)
> 사업 전체 목표시스템(SYSTEM)과 본 도구(저작도구) 서브시스템(SUBSYSTEM) 두 건을 등록한다.

## A-01. 시스템 아키텍처 (SYSTEM)

```json
{
  "type": "ARCHITECTURE",
  "title": "AI 기반 지방정부 CCTV 관제지원시스템(2차) 목표 아키텍처",
  "content": "## 사업 전체 구성도 (RFP p.13)\n\n### 외부·내부 경계\n\n- **지자체 통합관제센터(폐쇄망, 217개)**\n  - 영상관리시스템(VMS), 선별관제시스템, 관제일지, 스마트시티 통합플랫폼\n  - 클립영상 중계서버(신규 도입 5식: x86 16core/768GB/SSD 960GB×4+2.4TB HDD×8/GPU L40+, L2 스위치 10G/1G)\n  - 비식별 서버 (1차 사업 GPU 서버 활용)\n\n- **망연계 솔루션 (발주기관 SW 직접구매, 분리발주)**\n  - 폐쇄망 ↔ 개방망 데이터 전송 보장\n\n- **AI CCTV 관제지원시스템 (중앙, 본 사업)**\n  - 학습데이터 관리: 데이터 현황 / 통계정보 / 메타데이터 관리 / 증강데이터 관리\n  - 저작도구 고도화: 라벨링 기능 / 객체 추적 라벨링 / 정밀 검수 / 변경이력 추적\n  - 데이터 증강(생성형 AI 활용): 침수데이터 생성 / 산불데이터 생성\n  - GIS 기반 자원 관리: 공간 분석 / GIS 기반 관리 / 운영자원 관리\n  - 통합 DB + 데이터 마트 (학습데이터 유형별 분류·버전 관리)\n  - 지능형 CCTV 고도화를 위한 학습데이터셋 구축 (CCTV 영상 + 이미지 + json)\n\n- **AI 영상학습 사용자 포털 (외부 채널)**\n  - 공통 기능(CCTV 영상 다운로드) / 사용자 관리(운영자/사용자) / 공지사항 관리 / 이력관리\n\n- **외부 시스템 (V1.4 ~ V1.8 정리로 본 도구 범위 외)**\n  - 시계열 메타 자동 생성 본체 (VLM 분석 본체) — SFR-03\n  - 생성형 AI 모델 본체 (QWEN IMAGE, WAN2.2) — SFR-06, SFR-11\n  - 학습데이터 외부제공 시스템 (데이터마트·검색·다운로드) — SFR-13\n\n### 산출물 목표\n- 영상 학습데이터 **5,000건** (30초 이상 / 1건)\n- 이미지 학습데이터 **100,000장**\n- 콘텐츠 데이터 5,000건\n- 데이터 마트 (CCTV 영상 + 이미지 + json)\n\n### 실증 운영\n- 침수 탐지 모델 4개 지자체 시범적용 (서울 관악구, 경기 안양시, 강원 원주시, 제주특별자치도)\n- VLM 기반 이상상황(유괴) 탐지 모델 개발\n- 생성형 AI 활용 산불 데이터 구축\n\n### 지자체 확대\n- 1차 사업 연계 4개 지자체 + 2차 사업 연계 5개 지자체 = 총 9개 지자체",
  "attrs": {"scope": "SYSTEM"},
  "_handle": "arch-system-overall"
}
```

## A-02. 저작도구 서브시스템 아키텍처 (SUBSYSTEM)

```json
{
  "type": "ARCHITECTURE",
  "title": "학습데이터 저작도구 (본 사업 책임 범위) — 서브시스템 아키텍처",
  "content": "## 기술 스택\n\n- **Backend**: Java 17 + Spring Boot 3.3, Spring Security, Spring Data JPA + QueryDSL 5.1, MapStruct, Lombok, Flyway 10.13, Quartz, Resilience4j 2.2.0, WebFlux WebClient, JJWT 0.12.6, net.bramp.ffmpeg, Springdoc OpenAPI 2.5, Caffeine, Micrometer Prometheus\n- **AI Server**: Python 3.11 + FastAPI + ultralytics(YOLO) + torch/torchvision(SAM2) + opencv-python (Stateless 추론 전용)\n- **Frontend**: React 18 + TS 5.4 + Vite 5, TanStack Query 5, Zustand 4, React Router 6, axios, Tailwind 3, konva 9 + react-konva 18, tus-js-client, react-hook-form + zod, recharts\n- **DB**: MariaDB 10.11.13 LTS (klid_system 공유, 192.168.102.101:13307), 이중 DataSource(control/portal), `ddl-auto=validate`, gradle.lockfile\n- **테스트**: JUnit 5 + Testcontainers(mariadb) + okhttp3 MockWebServer + Spring Security Test / Vitest + Testing Library + Playwright\n\n## 패키지 구조 (Backend)\n\n```\nbackend/src/main/java/kr/co/cudo/authoring/\n├── batch/          (52 파일) Quartz Orchestrator + 7 Step\n├── label/          (32 파일) 라벨 CRUD + LabelAccessGuard + Mask RLE\n├── video/          (17 파일) 영상 원본 + 비식별 PRVC_TYPE_CD 라우팅\n├── portal/         (17 파일) TUS 업로드 + 간편 라벨링\n├── assignment/     (17 파일) 작업 배정 + TaskBoard\n├── review/         (11 파일) 검수 워크플로우 + @Version 낙관적 잠금\n├── augment/        (16 파일) 증강 요청 + 검수\n├── preset/         (6 파일)  라벨 프리셋\n├── meta/                     외부 메타 검토 UI\n├── version/                  Gitea 자동 커밋·diff·rollback\n├── sysconfig/                Caffeine TTL 60s 캐시\n├── stat/                     작업자·전체 통계\n├── user/                     사용자 관리·IP 화이트리스트\n└── common/         (64 파일) ApiResponse, ErrorCode, JwtAuthFilter, GlobalExceptionHandler, RoleHierarchy, ControlDataSourceConfig, PortalDataSourceConfig, WebClientConfig(32MB), Gitea/Ai/Deidentify Client, LabelPointSerializer, MaskRleConverter\n```\n\n## 아키텍처 레이어\n\n- **PRESENTATION (FE)**: pages/ + 14 features 도메인 + components/common + components/layout + lib/api/client.ts + queryKeys.ts\n- **PRESENTATION (BE controller)**: *Controller — DTO만 다룸, Entity 직접 노출 금지\n- **APPLICATION (BE service)**: *Service — 비즈니스 로직, @Transactional, 외부 client 호출\n- **DOMAIN (BE entity)**: *Entity + AggregateRoot + @Version\n- **INFRA (BE repo+client)**: *Repository(JPA/QueryDSL) + Gitea/Ai/Deidentify Client + DataSource Config\n\n## 외부 연동 (Resilience4j)\n\n| 서비스 | 클라이언트 | timeout | CircuitBreaker | 환경변수 |\n|---|---|---|---|---|\n| Gitea | GiteaClient | 70s | 50%/window10/wait30s | GITEA_BASE_URL, GITEA_TOKEN, GITEA_OWNER, GITEA_REPO |\n| AI Server | AiServerClient | 60s | 동일 | AI_SERVER_URL |\n| Deidentify | DeidentifyClient | 60s | 동일 | DEIDENTIFY_API_URL |\n\n## 배치 파이프라인\n\n```\nPENDING → FRAME_EXTRACT(FFmpeg) → DEIDENTIFY(PRVC|PSDO 조건부) → YOLO → SAM2 → TRACK_INTERPOLATION → VLM_META(객체 검증 한정) → COMPLETED\n```\n- Quartz 1건/분 폴링 (interval-sec=60)\n- 재시도 max=3, exp backoff (1s × 2^n)\n- 단계별 로그: LS_BATCH_PROC_LOG\n- local 비활성, dev/stg/prd 자동\n\n## 보안 매트릭스 (CWE/OWASP)\n\n| 위협 | 방어 | 위치 |\n|---|---|---|\n| IDOR (CWE-639) | LabelAccessGuard | LabelService, VersionService |\n| Race Condition (CWE-362) | @Version 낙관적 잠금 | LsRawDataStatus |\n| DoS (CWE-770) | 페이지 size 100 한도, 좌표 점 1000 한도 | application.yml + LabelPointSerializer |\n| SSRF (CWE-918) | 외부 URL은 설정값만 | WebClientConfig |\n| Mass Assignment (CWE-915) | record DTO + autoLblYn 무시 | LabelService |\n| Credential Exposure | 환경변수만 | JWT_SECRET, GITEA_TOKEN, DB_PASSWORD |\n| 권한 우회 | @PreAuthorize + requireReviewer 이중 가드 | TaskBoardController/Service |\n| Software Supply Chain | gradle.lockfile | backend/gradle.lockfile |\n| 권한 인증 우회 (SER-01) | 로그인 5회 차단, 동시 로그인 차단 | JwtAuthFilter |\n\n## 환경 프로파일\n\n| 프로파일 | 배치 자동 | Gitea fallback 큐 | 비고 |\n|---|---|---|---|\n| local | false (수동) | false | H2, CORS=localhost:3000/5173, LocalProfileGuard로 dev/stg/prd 부트 거부 |\n| dev | true | false | 운영 시크릿 ENV 필수 |\n| stg | true | true | Gitea fallback 큐 활성화 |\n| prd | true | true | 최고 가용성 |\n\n## CVAT 포팅 모듈 (분석 문서 기반 Java/TS 재구현)\n\n| 모듈 | 언어 | 참고 문서 |\n|---|---|---|\n| 트랙 보간 알고리즘 | Java | docs/analysis/portable-modules/01 |\n| MASK ↔ RLE ↔ Polygon | Java | portable-modules/02 |\n| TUS 재개 가능 업로드 | Java | portable-modules/03 |\n| manifest.jsonl 포맷 | Java | portable-modules/04 |\n| Nuclio 함수 템플릿 | Python(ai-server) | portable-modules/05 |\n| 좌표 변환/회전 | Java + TS | portable-modules/06 |\n| 캔버스 드로잉 패턴 | TS | analysis/canvas-drawing.md |\n| YOLO/COCO 변환 | Java | portable-modules/07 |\n| RQ → Quartz 매핑 | Java | portable-modules/08 |\n| 품질 충돌 감지(GT Job) | Java | portable-modules/09 |\n\n## 의사결정 (ADR 요약)\n\n- **모노레포(mono) 채택**: backend + ai-server + frontend + mock + docs 단일 git\n- **DB 신규 테이블 0개 원칙**: klid_system 공유, 기존 31개 재사용 + LS_DATA_LBL 3컬럼 추가\n- **ai-server Stateless 분리**: GPU 자원 격리 + Spring Boot 오케스트레이션\n- **CVAT 직접 fork 금지**: 9개 portable-module 단위 포팅\n- **Quartz JDBC JobStore 공유**: 운영 QRTZ_* 11개 테이블 호환\n- **이중 DataSource**: control(=klid_system) / portal(=portal DB)",
  "attrs": {"scope": "SUBSYSTEM"},
  "_handle": "arch-authoring-subsystem"
}
```

---

## 등록 순서

ARCHITECTURE 2건은 COMPONENT/INTERFACE/ENTITY 등록 후 마지막에 등록한다. ARCHITECTURE는 다른 노드에 대한 `REFERS_TO` 링크 source가 된다.

권장:
1. arch-system-overall (사업 전체 상위 문서)
2. arch-authoring-subsystem (본 사업 저작도구 서브시스템)
