package kr.co.cudo.authoring.common.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 온프렘 증분 SQL 이 개발 마이그레이션을 따라왔는지 고정한다.
 *
 * <h3>왜 이 가드가 필요한가</h3>
 * <p>온프렘은 <b>Flyway 를 쓰지 않는다</b> — 동결 기준선 덤프 + {@code deploy/onprem/db/incremental/}
 * 의 증분 SQL 을 손으로 적용한다. 그래서 {@code src/main/resources/db/migration/} 에 파일을 하나
 * 더해도 <b>온프렘 쪽은 아무 일도 일어나지 않는다.</b>
 *
 * <p>그리고 앱은 {@code ddl-auto=validate} 로 뜬다. 즉 증분을 빠뜨리면 <b>온프렘 기동이 실패</b>한다.
 *
 * <p>★ 문제는 그 부정합을 <b>아무것도 잡지 않았다</b>는 것이다. 개발 쪽 Testcontainers 는 Flyway 로
 * 전건을 적용하므로 증분이 비어 있어도 전체 회귀가 초록이다. 이 저장소가 반복해 겪은
 * 「시험이 초록인데 아무것도 안 지킨다」 자리 그대로이며, 실제로 <b>이 가드가 없던 동안 한 번
 * 뚫렸다</b>(독립 QA 가 파일 대조로 발견).
 *
 * <h3>무엇을 고정하는가</h3>
 * <ol>
 *   <li>기준선 이후 번호의 마이그레이션은 <b>같은 파일명으로</b> 증분 폴더에 있어야 한다</li>
 *   <li>그 내용이 <b>바이트 단위로 같아야</b> 한다 — 증분 폴더 규약이 「파일명까지 그대로 복사한다,
 *       손으로 다시 쓰지 않는다」이기 때문이다. 다시 쓰면 개발이 실제로 적용한 것과 어긋나고
 *       <b>그 어긋남은 조용하다</b></li>
 *   <li>반대 방향도 본다 — 증분 폴더에만 있는 번호는 개발에 없는 SQL 이 현장에 나가는 것이다</li>
 * </ol>
 *
 * <p>★ 1번은 <b>양방향 일치</b>다(빠진 것뿐 아니라 남은 것도 잡는다). 그래서 <b>기준선을 다시 동결하면
 * 이 시험이 빨개진다</b> — 새 기준선에 이미 녹아든 옛 증분이 폴더에 남아 있기 때문이다. 그건 오탐이
 * 아니라 <b>할 일 알림</b>이다: 동결과 동시에 그 증분들을 치워야 현장이 이미 적용한 것을 또 적용하지
 * 않는다. 빨개졌다고 이 단언을 「빠진 것만 본다」로 느슨하게 바꾸지 말 것.
 *
 * <h3>기준선 번호는 파일 이름에서 읽는다</h3>
 * <p>「32 부터」를 상수로 박지 않는다. 기준선을 다시 동결하면 그 숫자가 바뀌는데, 박아 두면
 * 이 시험이 <b>낡은 채로 계속 초록</b>이 된다(사양 값을 코드에 옮겨 적은 사본은 사양이 바뀌어도
 * 신호를 주지 않는다). 동결본 파일명의 {@code -V{n}} 이 단일 진실원이다.
 *
 * <h3>이 가드가 못 보는 것</h3>
 * <p>{@code deploy/onprem/db/schema.sql}(신규 설치용 전량 덤프)은 검사하지 않는다. 그건 덤프라
 * 파일 대조로 판정할 수 없고, 판정하려면 실제 DB 에 적용해 카탈로그를 읽어야 한다.
 * <b>증분을 맞췄다고 그 덤프까지 맞았다고 여기지 말 것</b> — 둘은 따로 갱신된다.
 */
@DisplayName("온프렘 증분 SQL 동기화")
class OnpremIncrementalSyncTest {

    /** 테스트 작업 디렉터리는 {@code backend/} 다. */
    private static final Path LIVE_MIGRATION_DIR = Path.of("src/main/resources/db/migration");
    private static final Path ONPREM_DB_DIR = Path.of("../deploy/onprem/db");
    private static final Path INCREMENTAL_DIR = ONPREM_DB_DIR.resolve("incremental");
    private static final Path BASELINE_DIR = ONPREM_DB_DIR.resolve("baseline");

    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern BASELINE_VERSION = Pattern.compile("-V(\\d+)\\.sql$");

    @Test
    @DisplayName("기준선 이후 마이그레이션은 전부 온프렘 증분 폴더에 같은 이름으로 있다")
    void everyMigrationAfterBaselineIsCarriedToOnprem() throws IOException {
        int baseline = baselineVersion();

        Map<Integer, String> live = versionedFiles(LIVE_MIGRATION_DIR);
        Map<Integer, String> onprem = versionedFiles(INCREMENTAL_DIR);

        List<String> expected = live.entrySet().stream()
                .filter(e -> e.getKey() > baseline)
                .map(Map.Entry::getValue)
                .toList();

        assertThat(onprem.values())
                .as("온프렘은 Flyway 를 쓰지 않는다 — 여기 빠진 마이그레이션은 현장에 영영 적용되지 않고, "
                        + "ddl-auto=validate 라 기동이 실패한다. 빠졌다면 %s 에서 그 파일을 "
                        + "%s 로 <파일명까지 그대로> 복사할 것(손으로 다시 쓰지 말 것).",
                        LIVE_MIGRATION_DIR, INCREMENTAL_DIR)
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("증분 폴더의 SQL 은 개발 마이그레이션과 바이트까지 같다")
    void carriedSqlIsByteIdentical() throws IOException {
        int baseline = baselineVersion();

        for (Map.Entry<Integer, String> entry : versionedFiles(LIVE_MIGRATION_DIR).entrySet()) {
            if (entry.getKey() <= baseline) {
                continue;
            }
            String name = entry.getValue();
            Path carried = INCREMENTAL_DIR.resolve(name);
            if (!Files.exists(carried)) {
                continue;   // 존재 여부는 위 시험이 판정한다 — 여기서 중복 실패를 내지 않는다
            }
            assertThat(Files.readAllBytes(carried))
                    .as("%s 가 개발 마이그레이션과 내용이 다르다. 증분은 <복사>이지 <재작성>이 아니다 — "
                            + "다시 쓰면 현장이 적용하는 것과 개발이 검증한 것이 갈리고, 그 어긋남은 조용하다.", name)
                    .isEqualTo(Files.readAllBytes(LIVE_MIGRATION_DIR.resolve(name)));
        }
    }

    @Test
    @DisplayName("증분 폴더에만 있는 마이그레이션은 없다")
    void onpremHasNoMigrationOfItsOwn() throws IOException {
        Map<Integer, String> live = versionedFiles(LIVE_MIGRATION_DIR);

        assertThat(versionedFiles(INCREMENTAL_DIR).values())
                .as("증분 폴더에만 있는 SQL 은 <개발이 한 번도 적용해 보지 않은 것>이 현장에 나가는 것이다. "
                        + "필요한 변경이면 먼저 %s 에 정식 마이그레이션으로 넣을 것.", LIVE_MIGRATION_DIR)
                .isSubsetOf(live.values());
    }

    /** 동결 기준선 파일명({@code schema-baseline-{날짜}-V{n}.sql})에서 기준선 번호를 읽는다. */
    private static int baselineVersion() throws IOException {
        try (Stream<Path> files = Files.list(BASELINE_DIR)) {
            return files.map(p -> p.getFileName().toString())
                    .map(BASELINE_VERSION::matcher)
                    .filter(Matcher::find)
                    .map(m -> Integer.parseInt(m.group(1)))
                    .max(Integer::compareTo)
                    .orElseThrow(() -> new IllegalStateException(
                            BASELINE_DIR + " 에서 동결 기준선(schema-baseline-…-V{n}.sql)을 찾지 못했다. "
                                    + "이름 규칙이 바뀌었다면 이 시험의 패턴도 함께 고쳐야 한다."));
        }
    }

    private static Map<Integer, String> versionedFiles(Path dir) throws IOException {
        Map<Integer, String> byVersion = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.map(p -> p.getFileName().toString())
                    .forEach(name -> {
                        Matcher m = VERSIONED.matcher(name);
                        if (m.matches()) {
                            byVersion.put(Integer.parseInt(m.group(1)), name);
                        }
                    });
        }
        return byVersion;
    }
}
