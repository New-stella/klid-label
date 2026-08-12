package kr.co.cudo.authoring.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★확정 저장은 <b>버전 축을 건드리지 않는다</b> — 프로덕션 소스 스캔 (사용자 확정 원칙, 구속).
 *
 * <h3>고정하는 불변식</h3>
 * 영상 라벨 확정 저장(API-196)은 회차 스냅샷을 <b>읽기만</b> 하고, 회차 기록·활성 표식
 * ({@code ACTVTN_YN})·회차↔스냅샷 매핑을 <b>일절 변경하지 않는다</b>.
 *
 * <h3>왜 이 원칙인가 (사용자 확정)</h3>
 * 각 회차는 서로 간섭해선 안 된다. 저장을 누르면 <b>기존 데이터를 덮어쓰는 것이 아니라 새로 저장</b>
 * 하는 것이고, 그래야 검수 완료 시점마다 만들어진 데이터마트를 각각 유지할 수 있다. 확정 저장이
 * 대상 스냅샷을 재활성(롤백 시맨틱)하면 그 순간 이전 회차의 산출물 기준이 흔들린다.
 *
 * <h3>왜 정적 스캔인가 — 그리고 Mockito 로는 왜 부족한가</h3>
 * 단위 테스트에서 "쓰지 않았다"를 확인하려면 그 협력자가 <b>주입돼 있어야</b> 한다. 주입되지 않은
 * 목에 {@code never()} 를 걸면 어떤 구현이든 통과하는 <b>공허한 단언</b>이다(이 저장소의 "가드가 가드를
 * 멈춘 사고" 패턴). 반대로 이 스캔은 <b>협력자를 새로 주입하는 것 자체</b>를 잡는다. 행위 축은
 * {@code VideoLabelSaveTxServiceTest.확정_저장은_회차_기록과_활성_표식을_바꾸지_않는다} 가 담당한다.
 *
 * @design API-196
 * @req R6
 */
class VersionAxisImmutableGuardTest {

    private static final Path MAIN_SRC = Paths.get("src/main/java");

    /** 확정 저장 경로 — 이 파일들이 버전 축을 쓰면 위반이다. */
    private static final List<String> SAVE_PATH_FILES = List.of(
            "VideoLabelSaveTxService.java", "VideoLabelSaveService.java", "VideoLabelController.java");

    /**
     * 버전 축을 <b>쓰는</b> 심볼 — 회차 기록·활성 표식 전이.
     *
     * <p>{@code VersionService} 는 롤백 시맨틱(대상 스냅샷 재활성 + 활성 해제)을 소유하므로 주입 자체를
     * 금지한다 — 편해 보여 재사용하기 쉬운 바로 그 지점이다.
     */
    private static final List<String> VERSION_WRITE_MARKERS = List.of(
            "recordActiveSnapshots(", "activateRollbackTarget(", "deactivateOthers(",
            "rollbackToSnapshot(", "commitApproved(", "VersionService ", "OutputVersionStamper");

    @Test
    @DisplayName("영상_라벨_확정_저장은_회차_기록_활성표식_매핑을_쓰지_않는다")
    void videoLabelSaveNeverWritesVersionAxis() {
        Set<String> violations = new TreeSet<>();
        for (String fileName : SAVE_PATH_FILES) {
            String content = read(find(fileName));
            for (String marker : VERSION_WRITE_MARKERS) {
                if (content.contains(marker)) {
                    violations.add(fileName + " → " + marker);
                }
            }
        }

        assertThat(violations)
                .as("확정 저장은 회차 스냅샷을 읽기만 해야 한다. 회차 기록·활성 표식(ACTVTN_YN)·회차↔스냅샷"
                        + " 매핑을 바꾸면 각 회차가 서로 간섭해, 검수 완료 시점마다 만들어진 데이터마트"
                        + " 기준이 흔들린다(저장은 덮어쓰기가 아니라 새로 저장이다). 발견: %s", violations)
                .isEmpty();
    }

    private static Path find(String fileName) {
        try (var paths = Files.walk(MAIN_SRC)) {
            return paths.filter(p -> p.toString().endsWith(fileName))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "소스를 찾을 수 없다(파일 이동·개명 시 목록을 갱신할 것): " + fileName));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            // 주석은 제거하지 않는다 — 주석에 심볼이 등장하면 "왜 안 쓰는가"를 설명하는 문장일 수 있어
            //   오탐이 날 수 있으나, 이 축은 <b>보수적으로</b> 잡는 편이 안전하다(설명은 심볼 표기를
            //   피해 서술로 쓴다).
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
