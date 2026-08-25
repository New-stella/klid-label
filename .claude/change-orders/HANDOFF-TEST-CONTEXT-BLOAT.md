# 인계 — 백엔드 시험 컨텍스트 비대화로 전체 회귀가 완주하지 못한다

> 작성 2026-08-25 · 발견 경위: CO-014 작업 중 전체 회귀가 두 번 연속 GC 에 묶임
> 이 문서는 **별도 세션이 단독으로 착수**할 수 있게 쓴 것이다. CO-014 를 알 필요는 없다.

## 1. 증상

`./gradlew cleanTest test`(전체 회귀)가 **결과를 하나도 내지 못한 채 매달린다.**

- 테스트 워커 힙(2g) Old 영역이 **100%** 로 차고 회수되지 않는다
- Full GC 가 **12초에 27회**, GC 점유율 **94%**
- **결과 XML 도 `BUILD` 줄도 안 나온다** → 로그에 `FAILED` 가 없다는 것이 통과의 증거가 되지 못한다
- 교착이 아니다. 전 스레드 `RUNNABLE` 이며 회수할 게 없는데 GC 가 무한히 도는 상태다

⚠ **CPU 로 진행 여부를 판정하지 마라.** GC 스레드가 도는 것과 일하는 것이 CPU 로는 구분되지 않는다
(실제로 CPU 783% 를 「진행 중」으로 오독한 사고가 있었다). 판정은 반드시 아래로 한다:

```bash
W=$(pgrep -f "org.gradle.internal.worker" | head -1)
jstat -gcutil $W ; sleep 12 ; jstat -gcutil $W
# Old(O) 가 안 내려가고 FGC 가 12초에 20회 넘게 늘면 그 실행은 끝나지 않는다
```

## 2. 원인 (측정값 — ★그대로 믿지 말고 다시 재라)

`backend/src/test/java` 기준. 재측정 명령을 함께 적었다. **`grep -a` 를 반드시 쓴다** — 이 저장소에는
`file` 이 정상 UTF-8 소스를 `data` 로 오판해 **grep 이 소스 10개를 말없이 건너뛴** 실사고가 있다.

| 항목 | 측정값 | 재측정 |
|---|---:|---|
| 시험 클래스 총수 | 843 | `find . -name '*.java' \| wc -l` |
| **`@SpringBootTest`** | **316** | `grep -ral "@SpringBootTest" . \| wc -l` |
| `@DataJpaTest` | 1 | `grep -ral "@DataJpaTest" . \| wc -l` |
| `@WebMvcTest` / `@JsonTest` | 0 / 0 | 〃 |
| 순수 Mockito(컨텍스트 없음) | 86 | `grep -ral "@ExtendWith(MockitoExtension" . \| wc -l` |
| **웹 계층을 안 쓰는 `@SpringBootTest`** | **202** | 아래 ※1 |
| **기본 컨텍스트와 다른 구성을 요구하는 클래스** | **76** | 아래 ※2 |
| `@MockBean` 조합 종류 | 21 (**17종은 단 한 클래스 전용**) | 아래 ※3 |
| `@TestPropertySource` 선언 종류 | 11 | `grep -rhao '@TestPropertySource([^)]*)' . \| sort -u \| wc -l` |

```bash
# ※1 웹 계층 미사용 추정 (heuristic — 간접 의존이 있을 수 있으니 표본을 열어 확인할 것)
grep -ral "@SpringBootTest" . | while read f; do
  grep -qa "MockMvc\|WebEnvironment\|TestRestTemplate" "$f" || echo "$f"; done | wc -l

# ※2 컨텍스트를 가르는 클래스(중복 제거)
{ grep -ral "@MockBean" . ; grep -ral "@TestPropertySource" . ; grep -ral "@DynamicPropertySource" . ; } | sort -u | wc -l

# ※3 @MockBean 조합 다양성
grep -ral "@MockBean" . | while read f; do
  grep -ao "@MockBean[^;]*" "$f" | grep -oE "[A-Z][A-Za-z0-9_]+ " | tr -d ' ' | sort -u | paste -sd, -
done | sort | uniq -c | sort -rn
```

### 산수

`backend/build.gradle` 의 `test` 블록에 **`forkEvery`·`maxParallelForks` 선언이 0건**이라 316개가
**한 JVM** 에서 돈다. Spring 은 테스트 컨텍스트를 **캐시에 살려 두고 기본 상한이 32** 다.
JPA·Hibernate·Flyway·Quartz·WebClient 를 물고 있는 컨텍스트 하나가 **50~150MB** 급이므로
**32칸만 차도 2GB 힙을 넘는다.** 그런데 서로 다른 구성을 요구하는 클래스가 76개라 **32칸을 두고
서로를 밀어낸다.**

### ★이건 단조 증가한다

`@MockBean` 조합을 새로 쓰는 시험이 하나 늘 때마다 컨텍스트가 하나 늘고 **되돌아가지 않는다.**
그래서 이 문제는 "언젠가 터지는" 것이 아니라 **터지는 시점이 정해져 있는** 것이다.

## 3. ⚠ 이미 실패한 처방 — 되풀이하지 마라

1. **힙 상향** — `maxHeapSize = '2g'` 가 바로 그 처방이었다. 그 옆 주석이 *"통합 테스트 추가로 캐시되는
   컨텍스트 수가 늘어 워커 OutOfMemoryError 가 간헐 발생한다"* 라고 그때를 기록하고 있다.
   **시간을 벌어 둔 패치였고 그 시간이 끝났다.** 또 올리면 같은 자리에 다시 온다.
2. **「이번 변경이 천장을 넘겼다」로 귀속** — 2026-08-25 에 그렇게 판단했다가 **교차 관측으로 기각**됐다.
   같은 시각 **다른 워크트리(다른 브랜치·다른 작업)** 의 워커도 99.82% 로 같은 증상이었다.
   최근 추가된 `@SpringBootTest` 를 범인으로 지목하기 전에 **다른 워크트리를 먼저 확인하라.**
3. **캐시 상한만 걸고 「해결」로 닫기** — `spring.test.context.cache.maxSize` 는 메모리를 시간
   (컨텍스트 재생성)으로 바꿀 뿐이다. **완화이지 해결이 아니다.** 닫았다고 보고하지 마라.

## 4. 할 일

### 4-A. 먼저 — 실패를 보이게 만든다 (별건, 이미 착수 중일 수 있음)

`backend/build.gradle` 의 `test` 블록에 아래를 넣는다. **고치는 설정이 아니라 진단 가능하게 하는 설정**이다.

```groovy
jvmArgs '-XX:GCTimeLimit=90', '-XX:GCHeapFreeLimit=5', '-XX:+HeapDumpOnOutOfMemoryError'
```

지금은 힙이 차도 OOM 을 안 던지고 무한히 GC 만 돈다. 이 둘을 걸면 **회수 불가일 때 즉시 OOM + 힙덤프**로
끝나서, 「조용한 40분」이 「1분 만의 명확한 실패」가 된다.

⚠ **`maxHeapSize` 를 같이 올리지 마라** — 올리면 어느 조치가 들었는지 판별할 수 없다.

### 4-B. 본체 — 컨텍스트 다양성을 줄인다

우선순위대로.

1. **웹 계층을 안 쓰는 202개**를 가벼운 축으로 내린다.
   - 협력 객체를 mock 으로 대체하는 순수 단위시험으로 충분한 것 → `@ExtendWith(MockitoExtension.class)`
     (이 저장소에 이미 86개 선례가 있다)
   - 리포지토리·쿼리만 보는 것 → `@DataJpaTest`
   - **전부 옮기려 하지 마라.** 실제로 컨텍스트가 필요한 것(트랜잭션 경계·이벤트 리스너·`AFTER_COMMIT`
     사슬·Testcontainers 실 DB)은 그대로 둔다.
2. **한 클래스 전용 `@MockBean` 조합 17종을 합친다.** `@MockBean` 은 조합이 다를 때마다 **새 컨텍스트**를
   만든다. 공용 테스트 구성 하나로 모으면 그만큼 칸이 준다.
3. **`@TestPropertySource` 11종**도 같은 축이다. 같은 값을 쓸 수 있는 것끼리 합친다.

### 4-C. 효과 측정 (숫자로)

**바꾸기 전에 기준선을 먼저 뜬다.** 안 뜨면 나아졌는지 말할 수 없다.

| 지표 | 어떻게 |
|---|---|
| 캐시된 컨텍스트 수 | `-Dlogging.level.org.springframework.test.context.cache=DEBUG` 후 로그의 캐시 통계 |
| 힙 최고점 | 실행 중 `jstat -gcutil` 의 Old 최댓값 |
| 전체 회귀 벽시계 | `BUILD SUCCESSFUL in Xm Ys` |
| 실행 테스트 수 | 결과 XML 집계 (아래) |

```bash
python3 - <<'PY'
import glob, xml.etree.ElementTree as ET
xs=glob.glob('backend/build/test-results/test/*.xml'); t=f=e=s=0
for p in xs:
    r=ET.parse(p).getroot()
    t+=int(r.get('tests',0)); f+=int(r.get('failures',0)); e+=int(r.get('errors',0)); s+=int(r.get('skipped',0))
print(f"XML={len(xs)} tests={t} failures={f} errors={e} skipped={s}")
PY
```

## 5. 이 저장소 특유의 함정 (모르면 시간을 버린다)

| 함정 | 내용 |
|---|---|
| **빌드 슬롯은 머신 단위** | 워크트리가 여러 개여도 Gradle 데몬·Docker·포트를 **머신 전체가 공유**한다. 다른 세션이 동시에 gradle 을 돌리면 **양쪽 다 위양성 실패**가 난다. 착수 전 `pgrep -f "org.gradle.internal.worker"` 로 확인하고, 다른 세션과 시간을 나눠라 |
| **`cleanTest` 없으면 통과처럼 보인다** | Gradle `test` 는 `UP-TO-DATE` 로 **실행 없이** 스킵된다. 반드시 `cleanTest test`, 그리고 결과 XML **개수와 타임스탬프**로 실행 증거를 확인하라 |
| **강제 종료 시 고아 컨테이너** | `TESTCONTAINERS_RYUK_DISABLED='true'` 라 JVM 을 kill 하면 Testcontainers 컨테이너가 남는다. `docker ps -a` 에서 **랜덤 이름**(예 `fervent_jackson`) 컨테이너를 찾아 손으로 지워라. 상시 스택은 `klid-*` 이름이니 그건 건드리지 마라 |
| **`grep` 이 소스를 조용히 건너뛴다** | `file` 이 정상 UTF-8 을 `data` 로 오판해 **소스 10개가 스캔에서 빠진** 사고가 있다. 항상 **`grep -a`**. 0건이 나오면 「위반 없음」이 아니라 **「스캔이 됐는가」를 먼저 의심**하라 |
| **환경변수가 테스트 워커까지 안 간다** | `FOO=1 ./gradlew test` 로는 워커에 안 들어간다(이미 뜬 데몬 환경을 물려받음, `--no-daemon` 으로도 안 됨). `systemProperty` 를 쓰거나 `-D` 가 워커에 전달되게 배선하라 |
| **부분 실행으로 종결되지 않는 부류** | 픽스처·정리 코드(`@BeforeEach`/`@AfterEach`)·공유 자원(고정 경로 temp·포트)을 건드리면 **결과가 실행 조합에 의존**한다. 조합 3회 통과가 전체 1회 실패를 배제하지 못한다(실측: 조합 검증 통과한 `@AfterEach` 가 전체에서 37건을 깨뜨렸다) |

## 6. 범위 경계

- **제품 코드(`src/main`)를 고치지 마라.** 이건 시험 구조 작업이다. 제품 결함이 드러나면 **고치지 말고 보고**하라.
- **시험의 의미를 바꾸지 마라.** 슬라이스로 내리면서 단언을 약화시키면 커버리지를 잃고도 초록이라 안 보인다.
  옮긴 시험은 **옮기기 전과 같은 것을 검증해야 한다.**
- **한 번에 다 하지 마라.** 묶음으로 나눠 각 묶음마다 4-C 지표를 재고, 나아지지 않으면 가설이 틀린 것이다.

## 7. 참고 (이미 조사된 것)

- 이 문제의 1차 조사 기록: auto-memory `test-heap-exhaustion-is-structural`
  - ⚠ 그 기록의 *"`@ActiveProfiles` 310"* 을 **컨텍스트 분기 요인으로 읽지 마라** — 재측정 결과
    **312개가 전부 같은 값(`"local"`)** 이라 컨텍스트를 가르지 않는다. 실제 분기 요인은
    `@MockBean`·`@TestPropertySource`·`@DynamicPropertySource` 다.
- 빌드 슬롯 경합: auto-memory `build-slot-contention-is-machine-wide`
- 빌드 예산 실측: 프로젝트 `CLAUDE.md` 「빌드 예산」 절 (backend 단독 실측이 5분 25초 ~ 9분 56초로 흔들린다)
