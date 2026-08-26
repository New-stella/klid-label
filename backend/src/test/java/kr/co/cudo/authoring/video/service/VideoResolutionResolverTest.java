package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link VideoResolutionResolver} 단위 검증 — 해상도 표시값의 조달·폴백 계약.
 * [@design API-043] [@design SCREEN-009]
 *
 * <p>지키는 것은 셋이다.
 * <ol>
 *   <li><b>키가 {@code video.resolution} 이다</b> — 같은 테이블에 {@code video.fps}·{@code video.codec} 등이
 *       함께 있어 키를 잘못 짚어도 조회는 성공한다(다른 값을 해상도로 내보낸다).</li>
 *   <li><b>값을 그대로 싣는다</b> — 파싱해 재조립하면 형식의 진실원이 적재 지점과 둘로 갈린다.</li>
 *   <li><b>미상이면 {@code null} 이다</b> — fps 와 달리 폴백을 두지 않는다. 표시 전용 값이라 지어내면
 *       화면이 사실이 아닌 해상도를 실값처럼 보여준다.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VideoResolutionResolverTest {

    private static final long RAW_SN = 1L;

    @Mock private LsDataMetaRepository metaRepository;

    @InjectMocks private VideoResolutionResolver resolver;

    private void stubStored(String storedValue) {
        LsDataMeta meta = LsDataMeta.create(RAW_SN, "video.resolution", storedValue);
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "video.resolution"))
                .thenReturn(Optional.of(meta));
    }

    @Test
    @DisplayName("★적재된_해상도를_그대로_돌려준다_재조립하지_않는다")
    void returnsStoredValueVerbatim() {
        stubStored("1920x1440");

        assertThat(resolver.resolveResolution(RAW_SN)).isEqualTo("1920x1440");
    }

    /**
     * ★조회 키가 틀려도 조회 자체는 성공한다({@code video.*} 기술메타가 같은 테이블에 여럿 있다) —
     * 그래서 키를 <b>명시적으로</b> 못 박는다. 이 단언이 없으면 fps 값이 해상도로 나가는 변형이 통과한다.
     */
    @Test
    @DisplayName("★조회_키는_video_resolution_이다")
    void looksUpTheResolutionKey() {
        stubStored("1280x720");

        resolver.resolveResolution(RAW_SN);

        Mockito.verify(metaRepository).findByRawSnAndMetaKey(RAW_SN, "video.resolution");
    }

    /**
     * ★★fps 와 갈리는 지점 — fps 는 마킹 frameIndex 계산의 입력이라 상수 폴백을 두지만, 해상도는
     * 표시 전용이라 폴백이 곧 <b>거짓 표시</b>가 된다.
     */
    @Test
    @DisplayName("★★메타가_없으면_null이다_폴백_문자열을_지어내지_않는다")
    void missingMetaYieldsNull() {
        when(metaRepository.findByRawSnAndMetaKey(RAW_SN, "video.resolution"))
                .thenReturn(Optional.empty());

        assertThat(resolver.resolveResolution(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("값이_공백뿐이면_null이다_빈_문자열을_내리지_않는다")
    void blankValueYieldsNull() {
        stubStored("   ");

        assertThat(resolver.resolveResolution(RAW_SN)).isNull();
    }

    @Test
    @DisplayName("rawSn이_null이면_조회하지_않고_null이다")
    void nullRawSnShortCircuits() {
        assertThat(resolver.resolveResolution(null)).isNull();

        Mockito.verify(metaRepository, Mockito.never()).findByRawSnAndMetaKey(anyLong(), anyString());
    }
}
