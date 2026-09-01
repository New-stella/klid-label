package kr.co.cudo.authoring.batch.step;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ai-server 호출의 <b>용도 표시</b>가 경로별로 올바른지 소스에서 직접 확인한다. [design: ADR-056]
 *
 * <p>ai-server 는 용도별 실행 슬롯 둘(배치 전용 / 화면 전용)로 요청을 가르고, 백엔드는 같은 축으로
 * 서킷을 가른다. 배치가 용도를 빠뜨리면 <b>화면 슬롯으로 떨어져 작업자 요청과 한 줄에 서고</b>,
 * 반대로 화면이 배치 용도를 물면 <b>배치 큐 뒤에 줄서 21.9초를 기다리던 옛 형상으로 되돌아간다</b>.
 *
 * <p><b>왜 소스를 읽는 시험인가</b> — 두 성질 중 뒤엣것("화면 경로가 배치 오버로드를 물지 않았다")은
 * 동작으로 드러나지 않는다. 화면 경로가 배치 용도를 실어도 응답은 정상이고, 배치가 밀려 있을 때만
 * 느려지므로 단위 시험으로는 잡히지 않는다. 그래서 호출 지점 자체를 검사한다.
 *
 * <p>배치 쪽 용도 표시는 각 스텝 시험의 스텁이 {@code eq(AiWorkload.BATCH)} 로 이미 강제하지만,
 * 여기서 한 번 더 확인해 <b>표시가 어디에 있어야 하는지</b>를 한 자리에서 읽을 수 있게 한다.
 */
class AiWorkloadCallSiteTest {

    private static final Path MAIN = Paths.get("src/main/java/kr/co/cudo/authoring");

    /** 배치 파이프라인 — ai-server 를 부를 때 반드시 배치 용도를 명시해야 하는 자리. */
    private static final String[] BATCH_CALLERS = {
            "batch/step/YoloAutolabelStep.java",
            "batch/step/Sam2SegmentStep.java",
    };

    /**
     * 저작도구 화면 — <b>기본값이 화면</b>이라 표시가 없는 것이 정상이다.
     * 여기에 배치 표시가 생기면 사람이 쓰는 경로가 배치 뒤에 줄선다.
     */
    private static final String[] INTERACTIVE_CALLERS = {
            "label/service/AutolabelOnlineService.java",
            "label/service/Sam2SegmentService.java",
            "label/service/Sam2TrackService.java",
            "label/service/YoloTrackService.java",
    };

    private static String read(String relativePath) throws IOException {
        Path path = MAIN.resolve(relativePath);
        assertThat(Files.exists(path))
                .as("대상 파일이 옮겨졌거나 이름이 바뀌었다 — 시험이 조용히 무력해지지 않도록 먼저 막는다: %s", path)
                .isTrue();
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("배치_스텝은_ai_server_호출에_배치_용도를_명시한다")
    void batchStepsDeclareBatchWorkload() throws IOException {
        for (String caller : BATCH_CALLERS) {
            assertThat(read(caller))
                    .as("%s 가 용도를 빠뜨리면 화면 슬롯으로 떨어져 작업자 요청과 한 줄에 선다", caller)
                    .contains("AiWorkload.BATCH");
        }
    }

    @Test
    @DisplayName("화면_경로는_배치_용도를_물지_않는다_기본값이_화면이다")
    void interactiveCallersDoNotDeclareBatchWorkload() throws IOException {
        for (String caller : INTERACTIVE_CALLERS) {
            assertThat(read(caller))
                    .as("%s 가 배치 용도를 실으면 배치 큐 뒤에 줄서 옛 형상으로 되돌아간다", caller)
                    .doesNotContain("AiWorkload.BATCH");
        }
    }
}
