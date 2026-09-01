# CO-20260901 — ffprobe 임시파일 가드를 자기 자리로 좁힌다

| 항목 | 값 |
|---|---|
| 작성 | 2026-09-01 |
| 대상 도메인 | DOMAIN-003 (영상·프레임 수집 — `upload/`) |
| 성격 | 시험 격리 결함 (제품 회귀 아님) |
| 설계 선반영 | 불요 — 사양이 아니라 시험의 관측 범위 문제 |

## 1. 왜 하는가

2026-09-01 백엔드 전건 회귀에서 **8,140건 중 1건**이 실패했다.
`UploadMediaProbeFfprobeTest$ProcessExecution.타임아웃_경로에서도_stdout_임시파일이_남지_않는다`

**제품 결함이 아니라 시험이 남의 것까지 세는 문제다.**

```java
private Set<String> probeTempFiles() {
    Path tmpRoot = Path.of(System.getProperty("java.io.tmpdir"));   // ★ 공유 시스템 임시 디렉터리
    ... .filter(name -> name.startsWith("upload-ffprobe-"))          // ★ 전역 접두
}
```

`before` 스냅샷 → probe 4회 → `newTempFilesSince(before)` 가 비어야 통과하는데, 이 접두를 만드는 것은 **프로덕션 `UploadMediaProbeFfprobe:156`** 이다. 같은 코드를 타는 다른 경로가 그 사이에 파일을 만들면 그대로 「안 지워진 파일」로 집힌다.

전건 실행에서만 깨지는 이유는 그때만 그 동시 생성자가 존재하기 때문이다 — JUnit 병렬 설정은 없고 워커도 하나지만(`forkEvery` 없음), 캐시된 스프링 컨텍스트의 비동기·스케줄 경로(업로드 완료 → 인입 → probe)가 살아 있다. 그 접두를 만드는 자리는 레포 전체에 **한 곳뿐**이라 다른 설명이 남지 않는다.

⚠ **이미 한 번 타이밍으로 오진해 손댄 이력이 있다** — `bca8093e test(upload): 프로세스 실행 가드의 타임아웃을 1초에서 5초로 넓힌다`. 그걸로는 안 잡힌다. **원인이 타이밍이 아니라 범위**다.

## 2. 판정 근거 (재현 가능)

| 확인 | 결과 |
|---|---|
| 격리 실행 `--tests UploadMediaProbeFfprobeTest` | **31건 전부 통과** |
| 전건 실행 | 그 1건만 실패 (`UploadMediaProbeFfprobeTest.java:673`) |
| 실행 후 `$TMPDIR/upload-ffprobe-*` | **0건** — 잔재가 남는 결함이 아니다 |
| `createTempFile("upload-ffprobe-", ...)` 생성처 | `src/main/.../UploadMediaProbeFfprobe.java:156` **한 곳** |
| 그날 백엔드 diff | javadoc·`@ApiResponse(description)` 문자열뿐, **실행문 0줄** — 이 시험에 닿을 수 없다 |

## 3. 도메인별 변경 상세

### DOMAIN-003 (`klid-d003-implementer`)

**대상 파일**
- `backend/src/main/java/kr/co/cudo/authoring/upload/service/UploadMediaProbeFfprobe.java`
- `backend/src/test/java/kr/co/cudo/authoring/upload/service/UploadMediaProbeFfprobeTest.java`

**변경 — 시험이 자기가 만든 파일만 세게 한다**

임시파일이 놓일 자리를 주입할 수 있게 하고, 시험은 자기 전용 디렉터리를 준다.

**이 파일에는 이미 그 관례가 있다** — `UploadMediaProbeFfprobe(String binary, int probeTimeoutSec)` 가 package-private 이고 javadoc 이 *"타임아웃 주입 생성자 — 교착 회귀 테스트 전용. 프로덕션 빈은 위 `@Autowired` 생성자로만 생성된다"* 라고 밝혀 뒀다. **같은 관례를 따르고 새 방식을 발명하지 않는다.**

- 프로덕션 경로의 동작은 **한 톨도 바뀌면 안 된다** — 주입이 없으면 지금과 완전히 같은 호출(`Files.createTempFile(prefix, suffix)`)이어야 한다. 시스템 기본 임시 디렉터리를 계속 쓴다
- 시험은 주입한 자리만 훑어 판정한다. `System.getProperty("java.io.tmpdir")` 를 훑는 코드를 **없앤다**
- `System.setProperty("java.io.tmpdir", ...)` 로 전역을 바꾸는 방식은 **금지** — 같은 JVM 의 다른 시험을 오염시킨다. 그건 이 결함을 옮기는 것이지 고치는 것이 아니다

**불변 (건드리지 말 것)**
- `probe(Path)` 의 프로세스 I/O 규약 — stdout 을 임시파일로 돌리고 stderr 를 버리는 구조. 파이프로 되돌리면 교착한다(그 근거가 파일 주석에 있다)
- 타임아웃·종료코드·예외 처리 흐름, `PROBE_TIMEOUT_SEC`·`NO_HANG_LIMIT`·`CLEANUP_PROBE_COUNT` 값
- `@Autowired` 프로덕션 생성자의 시그니처
- 임시파일 접두 `upload-ffprobe-` 와 확장자 `.out`
- 나머지 30건의 시험

**수용기준**
- 그 시험이 **자기 디렉터리만** 관측한다 — 시험 코드에 `java.io.tmpdir` 참조가 남지 않는다
- 다른 곳에서 `upload-ffprobe-*` 파일이 동시에 생겨도 이 시험이 실패하지 않는다. **그 상황을 실제로 만들어 증명한다**(시험 도중 공유 임시 디렉터리에 같은 접두 파일을 하나 놓고도 통과하는지)
- 정리 누락을 여전히 잡는다 — `finally` 의 임시파일 삭제를 지우면 **RED** 가 된다(변이로 증명)
- 프로덕션 경로는 주입 없이 시스템 기본 임시 디렉터리를 그대로 쓴다
- `UploadMediaProbeFfprobeTest` 31건 전부 통과

## 4. 공유기반 영향

없음. 마이그레이션·`common/`·앱 진입점을 건드리지 않는다.

⚠ **마이그레이션을 만들지 말 것** — 다른 워크트리(`lb-test`)가 `V23`·`V24` 를 이미 쓰고 있어 번호가 충돌한다.

## 5. 관련 설계 ITEM

없음 — 사양이 아니라 시험의 관측 범위 문제다. 설계 선반영 불요.

## 6. 구현 로그

### 완료 (2026-09-01)

**원인이 관측 범위였음을 결정론적으로 재현했다** — 관측 범위만 구 방식(공유 임시 디렉터리 전수)으로 되돌리는 변이에서 **남의 파일 1건만으로** 가드가 실패한다. 전건에서만 깨지고 격리에서 31건 통과하던 것과 정확히 같은 형태다. 앞선 시도(`bca8093e` 타임아웃 1→5초)가 왜 안 통했는지도 이걸로 설명된다.

**변경**
- 프로덕션 — 임시파일 접두·확장자를 상수로 뽑고, 놓을 자리를 받는 **package-private 3인자 생성자**를 더했다(이 파일에 이미 있던 「타임아웃 주입 생성자」 관례를 그대로 확장). 주입이 없으면 `Files.createTempFile(TEMP_PREFIX, TEMP_SUFFIX)` 로 **이전과 완전히 같은 호출**이다
- 시험 — 자기 전용 디렉터리만 관측한다. `java.io.tmpdir` 참조 **0건**

**양방향 변이로 증명했다** — 한 방향만 보면 「눈먼 가드를 격리했다」고 착각한다.

| 변이 | 결과 |
|---|---|
| 프로덕션 `finally` 의 임시파일 삭제 제거 | **3건 RED** (감시 대상이 살아 있다) |
| 시험의 관측 범위를 공유 디렉터리로 환원 | **신설 가드 1건 RED** (좁힌 것이 실제로 효과가 있다) |

**메인 독립 검증** — 32건 통과 · 정리 코드 제거 변이 재현 시 **3건 RED** · 원복 후 초록 · `$TMPDIR` 잔재 0건 · 프로덕션이 3인자 생성자를 부르는 곳 **0건**.

⚠ **시험 건수가 31 → 32 로 늘었다** — 수용기준 2를 일회성 실험이 아니라 **상시 가드**로 못 박았다(`공유_임시디렉터리에_같은_접두의_남의_파일이_있어도_정리_가드가_흔들리지_않는다`). 전건 회귀 기준선 집계에 +1 을 반영할 것.

**남은 것** — 같은 클래스의 나머지 `ProcessExecution` 시험 8건은 여전히 공유 임시 디렉터리를 쓴다. 그것들은 잔재를 **관측하지 않으므로** 무해하나, 앞으로 「임시파일이 남았는가」류 가드를 더 만들면 반드시 전용 디렉터리 쪽에 붙일 것.
