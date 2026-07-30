package kr.co.cudo.authoring.video.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

/**
 * 프레임 이미지 서빙 <b>하드닝 회귀 가드</b> — 컨텍스트 없는 결정론적 테스트.
 *
 * <ul>
 *   <li><b>M-1</b> {@code openNoFollow} 가 심링크를 <b>따라가지 않고 실패</b>한다(TOCTOU, CWE-59/367).
 *       실제 서빙에서는 판정~open 사이에 최종 컴포넌트가 심링크로 교체되는 <b>경쟁 창</b>에서만
 *       발생하므로 E2E 로는 결정적으로 재현할 수 없다. 그래서 두 서빙 분기가 공유하는 open 규약
 *       자체를 여기서 못박는다(이 헬퍼를 우회해 {@code Files.newInputStream} 을 직접 쓰면 회귀).</li>
 *   <li><b>W3</b> 서빙 빈이 <b>트랜잭션을 물지 않는다</b>: {@link FrameImageService} 에는
 *       {@code @Transactional} 이 없고, DB 조회는 <b>별도 빈</b> {@link FrameImageLookupService}
 *       (프록시 경유 = self-invocation 아님) 안에서만 일어난다. 고빈도 서빙 경로가 커넥션을 쥔 채
 *       NAS I/O 를 하면 커넥션 기아 교착으로 번진다(실사고 이력).</li>
 * </ul>
 */
class FrameImageServingHardeningTest {

    // ---------- M-1 — NOFOLLOW fail-closed ----------

    @Test
    @DisplayName("openNoFollow_정규파일은_크기와_내용을_그대로_연다")
    void openNoFollowReadsRegularFile(@TempDir Path dir) throws IOException {
        // given
        Path file = dir.resolve("frame.jpg");
        byte[] content = "FRAME-BYTES".getBytes(StandardCharsets.UTF_8);
        Files.write(file, content);

        // when
        FrameImageService.OpenedFile opened = FrameImageService.openNoFollow(file);

        // then — 크기와 스트림이 같은 파일에서 나온다
        assertThat(opened.size()).isEqualTo(content.length);
        try (InputStream in = opened.stream()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    @DisplayName("openNoFollow_대상이_심링크면_열지_않고_실패한다_TOCTOU_fail_closed")
    void openNoFollowRejectsSymlink(@TempDir Path dir) throws IOException {
        // given — 판정 이후 원본 프레임을 가리키는 심링크로 바꿔치기된 상황을 그대로 재현
        Path target = dir.resolve("original.jpg");
        Files.write(target, "RAW-ORIGINAL-PIXELS".getBytes(StandardCharsets.UTF_8));
        Path link = dir.resolve("served.jpg");
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | IOException e) {
            assumeThat(false).as("심링크 미지원 파일시스템 — 이 가드는 검증 대상 외").isTrue();
            return;
        }

        // when / then — 링크를 따라가 원본을 서빙하지 않고 IOException 으로 끝난다(호출측이 404 로 마감)
        assertThatThrownBy(() -> FrameImageService.openNoFollow(link))
                .isInstanceOf(IOException.class);
    }

    // ---------- W3 — 트랜잭션 경계 구조 고정 ----------

    @Test
    @DisplayName("FrameImageService_는_트랜잭션을_선언하지_않는다_파일IO가_커넥션을_쥐지_않도록")
    void servingBeanIsNotTransactional() {
        assertThat(FrameImageService.class.getAnnotation(Transactional.class))
                .as("서빙 빈에 클래스 레벨 @Transactional 이 붙으면 NAS 파일 I/O 가 커넥션을 쥔 채 수행된다")
                .isNull();
        for (Method m : FrameImageService.class.getDeclaredMethods()) {
            assertThat(m.getAnnotation(Transactional.class))
                    .as("서빙 메서드 %s 에 @Transactional 금지", m.getName())
                    .isNull();
        }
    }

    @Test
    @DisplayName("DB조회는_별도_빈에서만_수행되고_그_빈이_readOnly_트랜잭션을_보유한다")
    void lookupBeanOwnsTransaction() {
        Transactional tx = FrameImageLookupService.class.getAnnotation(Transactional.class);
        assertThat(tx).as("조회 전담 빈이 트랜잭션 경계를 소유해야 한다").isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.value()).isEqualTo("controlTransactionManager");

        // self-invocation 방지 — 서빙 빈은 조회 빈을 <b>주입</b>받아 호출한다(같은 빈 내부 호출이면
        // 프록시를 타지 않아 위 @Transactional 이 통째로 유실된다: 이 프로젝트의 실제 사고 패턴).
        assertThat(Arrays.stream(FrameImageService.class.getDeclaredFields())
                .map(Field::getType))
                .as("FrameImageService 는 FrameImageLookupService 를 주입받아야 한다")
                .contains(FrameImageLookupService.class);

        // 서빙 빈이 리포지토리를 직접 들고 있으면 조회가 다시 트랜잭션 밖으로 샌다(경계 이원화).
        assertThat(Arrays.stream(FrameImageService.class.getDeclaredFields())
                .map(f -> f.getType().getSimpleName())
                .filter(n -> n.endsWith("Repository")))
                .as("서빙 빈은 리포지토리를 직접 주입받지 않는다")
                .isEmpty();
    }
}
