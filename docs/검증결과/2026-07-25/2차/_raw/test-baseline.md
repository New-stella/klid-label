# 2차 자동테스트 baseline (2026-07-31)

> 실행 환경: macOS (darwin 25.5.0), Docker 29.4.1, JDK 17 / Gradle 8.8, Node(npm ci 신규설치) + vitest, Python 3.14(.venv 신규생성) + pip install -r requirements.txt
> 주의: 소스/테스트/문서/설정 파일 수정 없음. frontend는 `node_modules` 부재로 `npm ci` 실행, ai-server는 `.venv` 부재로 `python3 -m venv .venv` + `pip install -r requirements.txt` 실행(둘 다 의존성 설치이며 코드 변경 아님). backend는 docker 이미지 재빌드가 동시 진행 중인 상태에서 실행.

## 0. 1차 대비 요약

| 대상 | 1차 총 | 1차 실패 | 2차 총 | 2차 실패 | 증감(총) | 판정 |
|------|---:|---:|---:|---:|---:|:--:|
| backend | 3013 | 0 | 4367 | 0 | +1354 | PASS |
| frontend | 1515 | 0 | 1691 | 0 | +176 | PASS |
| ai-server | 91 | 0 | 91 | 0 | 0 | PASS |

- 신규 실패 0건. 세 대상 모두 GREEN 유지.
- backend/frontend 테스트 수가 크게 증가(신규 테스트 추가분으로 추정, 1차 이후 개발 진행에 따른 자연 증가). ai-server는 동일.

## 1. backend

- 명령: `./gradlew cleanTest test --continue` (cleanTest 로 UP-TO-DATE 캐시 무효화 후 실행)
- 결과: `BUILD SUCCESSFUL in 20m 34s`
- 테스트 수: 4367 / 실패 0 / 에러 0 / 스킵 5
- **실행 증거**: `build/test-results/test/*.xml` 537개 파일, 전부 `2026-07-31 03:00` 시각(방금 실행분)으로 갱신 확인. XML aggregate(`tests`/`failures`/`errors`/`skipped` 속성 합산)로 위 수치 산출.
- 참고: 실행 중 로그에 Testcontainers PostgreSQL 컨테이너가 테스트 클래스 전환 시점마다 잠깐 내려가며 `Connection refused`/`HikariPool timeout` ERROR 로그가 다수 출력됐으나, 이는 컨테이너 교체 구간의 Quartz 스케줄러·만료 스윕(`augment-job-expiry-sweep`) 배경 스레드가 일시적으로 DB 접속을 못 잡아 남긴 노이즈이며 **테스트 실패/에러로 집계되지 않음**(XML 상 failures=0, errors=0, 최종 BUILD SUCCESSFUL). 도커 이미지 동시 재빌드로 인한 리소스 경합이 원인일 가능성 있음 — 실패로 이어지지 않았으므로 이번 baseline 판정에는 영향 없으나, 반복 재현 시 별도 확인 권장.

## 2. frontend

- 사전조치: `node_modules` 없어서 `npm ci` 실행(603 packages, 신규 lockfile 준수 설치, 소스 미변경)
- 명령: `npx vitest run`
- 결과: Test Files 292 passed(292) / Tests 1691 passed(1691) / Duration 35.55s
- 실패 0건, 스킵 0건

## 3. ai-server

- 사전조치: `.venv` 없어서 `python3 -m venv .venv` 생성 + `.venv/bin/pip install -r requirements.txt` 설치(시스템 python3.14 기반, 소스 미변경). 1차는 Python 3.11 .venv였으나 이번 환경엔 3.11이 없어 3.14로 재생성 — torch/torchvision/onnxruntime 등 3.14 wheel 정상 설치·정상 동작 확인.
- 명령: `.venv/bin/python -m pytest tests/ -q`
- 결과: `91 passed, 34 warnings in 34.71s`
- 실패 0건, 스킵 0건
- 경고: `StarletteDeprecationWarning`(httpx→httpx2 권장), `torch.jit.script Python 3.14 미지원` 경고, `numpy non-writable array` 경고 — 전부 라이브러리 버전/런타임 기인 warning이며 테스트 실패와 무관.

## 4. 실패 목록 (전건)

없음 — backend/frontend/ai-server 전체 실패 0건.

## 5. 스킵 목록 (사유별)

### backend — 5건 (전부 동일 클래스, 1차와 동일 패턴)

클래스: "라이브 목 서버 실연동 IT — KPST/VLM 클라이언트 왕복" (`LiveMockServerRoundTripIT` 계열로 추정)

| 테스트 메서드 | 사유 |
|---|---|
| Connect 는 Connect 를 받아 true 를 반환한다 | mock-server(:9400) 미기동 등 외부 목 서버 의존 — 조건부 스킵 |
| 프로젝트 생성은 prj_id 를 발급한다 | 동상 |
| 진행조회 GET+JSON바디 왕복이 성공한다 | 동상 |
| 프로젝트 삭제가 성공한다 | 동상 |
| VLM describe 는 accepted 와 request_id echo 를 반환한다 | 동상 |

1차 baseline과 동일하게 5건 모두 같은 IT 클래스이며, mock-server 실제 기동 여부에 좌우되는 조건부 스킵으로 판단됨(이번 실행 환경에서 mock-server 를 별도 기동하지 않음). ffmpeg/폰트 의존 SKIP 은 이번 실행에서 관측되지 않음.

frontend/ai-server: 스킵 0건.

## 6. 검증 판정 시 주의 — 이 baseline 을 방어 근거로 쓸 수 없는 지점

- 1차 baseline에서 지적된 "GREEN이지만 실검증 미수행" 위양성 6건(가짜 해시 픽스처, Mockito 스텁으로 DB 현실 미재현, 프로덕션 호출자 0건 메서드, 결함을 기대값으로 고정한 테스트 등)에 대해 이번 실행 범위(테스트 실행 결과 수치 확인)에서는 **개별 테스트 코드 내용을 재검토하지 않았으므로 해당 위양성 6건이 이번에 해소됐는지 여부는 확인 불가** — 이번 baseline은 "실행됐는가/통과했는가"만 담보하며 테스트의 실효성(assertion 품질)은 별도 코드 리뷰가 필요.
- backend 실행 중 Testcontainers PostgreSQL 컨테이너가 클래스 전환 시점마다 재기동되며 일시적 연결거부 로그가 다량 발생 — 결과적으로 실패 0건이었으나, 동시 진행 중이던 docker 이미지 재빌드의 리소스 경합 영향 가능성이 있어 **완전히 격리된 환경에서 재실행 시 동일 결과가 나오는지는 별도 확인 권장**.
- backend `라이브 목 서버 실연동 IT` 5건 스킵은 mock-server 미기동 조건에서 SKIP 처리된 것으로 보이며, 이 IT가 실제로 검증하는 KPST/VLM 왕복 계약은 **이번 baseline에서 전혀 실행되지 않았다** — 별도로 mock-server(:9400)를 기동한 통합 테스트가 필요.
- frontend/ai-server는 `node_modules`/`.venv`가 없어 이번에 새로 설치했다 — ai-server는 1차의 Python 3.11 대신 3.14로 재생성됐으며 dependency wheel 버전이 1차와 다를 수 있음(동작은 정상이나 정확히 동일 환경 재현은 아님).
- 특이사항 없음(그 외 신규 유사 위양성 패턴은 이번 실행 범위에서 관측되지 않음 — 단, 위 사유로 개별 테스트 코드 검토는 수행하지 않았음).
