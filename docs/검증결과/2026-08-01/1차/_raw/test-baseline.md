# 자동테스트 Baseline — 2026-08-01

- 검증 대상 커밋: `56d30478910319071d852a892d6d110281e3209b`
- 워킹트리 다이제스트(backend, git ls-files 기반): `96ed23f6f60e`

## 요약

| 대상 | 총 | 성공 | 실패 | 스킵 | 소요시간 | 상태 |
|------|----|------|------|------|---------|------|
| backend (Gradle, cleanTest test --continue) | 4,755 | 4,750 | 0 | 5 | 19m 4s (elapsed 1168s) | PASS |
| frontend (vitest run) | 1,951 (332 파일) | 1,951 | 0 | 0 | 20.69s | PASS |
| ai-server (pytest) | 91 | 91 | 0 | 0 | 31.69s | PASS |

전체 실패 테스트: **0건** (backend/frontend/ai-server 모두 전량 통과)

## 상세

### backend
- 명령: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew cleanTest test --continue`
- **환경 이슈**: 시스템에 기본 `java`가 없어(`/usr/bin/java`가 java.com 안내만 출력) 최초 실행 실패. `brew`로 설치된 `openjdk@17`(`/opt/homebrew/opt/openjdk@17`)를 `JAVA_HOME`으로 지정해 재실행 후 정상 진행됨. CI/로컬 셸 PATH에 Java가 링크되어 있지 않은 환경 이슈이며 코드 결함 아님.
- 실행 증거: `find . -path '*/build/test-results/*' -name 'TEST-*.xml' | wc -l` → 565개 XML, 실행 직후 타임스탬프 확인. `BUILD SUCCESSFUL in 19m 4s` (Gradle daemon 콜드스타트 포함 elapsed 1168s).
- XML 집계: tests=4755, failures=0, errors=0, skipped=5 (테스트케이스 레벨 failure/error 태그 0건 — 실패 목록 없음)
- Testcontainers(PostgreSQL) 기반 통합 테스트 다수 포함, 로그에 반복되는 `Connection refused localhost:63492` WARN/ERROR는 각 테스트 클래스의 Testcontainer 컨테이너가 종료된 이후 잔존 스케줄러(Quartz 트리거/웹훅 가드 purge/증강 만료 스윕)가 뒤늦게 DB 접근을 시도하며 나는 정상 종료 과정의 잡음으로, 테스트 실패(failure/error)로 집계되지 않음 — 실제 실패 0건과 일치.
- 실패 테스트 목록: 없음 (0건)

### frontend
- 명령: `npx vitest run` (사전 `npm install` 필요 — `node_modules`가 비어있어 최초 설치 수행, 설치 자체는 성공)
- 결과: `Test Files 332 passed (332)`, `Tests 1951 passed (1951)`, Duration 20.69s
- 실패 테스트 목록: 없음 (0건)

### ai-server
- 명령: `.venv/bin/python -m pytest tests/ -q` (venv 미존재 → `python3.11 -m venv .venv` 신규 생성 + `pip install -r requirements.txt` 사전 수행, 설치 자체는 성공)
- 결과: `91 passed, 34 warnings in 31.69s` (경고는 deprecation류: `httpx` deprecated 권고, `torch.jit.script` deprecated, numpy non-writable array 등 — 실패 아님)
- 실패 테스트 목록: 없음 (0건)

## BLOCKED 사유
- 없음 (3개 대상 모두 완주, 사전 의존성 설치만 필요했음 — 프로덕션/테스트/설정 파일은 수정하지 않았고 `node_modules`/`.venv` 설치만 수행)

## 테스트 파일 인벤토리

| 대상 | 패턴 | 개수 |
|------|------|------|
| backend | `*Test.java` + `*IT.java` (src/test 하위) | 564 |
| frontend | `*.test.ts` + `*.test.tsx` (src 하위) | 332 |
| ai-server | `test_*.py` (tests 하위) | 13 |

(참고: backend 실행 시 XML 결과 파일은 565개로 인벤토리(564)와 근사 — 클래스 분할/파라미터화 테스트 클래스 등의 차이로 정상 범위)
