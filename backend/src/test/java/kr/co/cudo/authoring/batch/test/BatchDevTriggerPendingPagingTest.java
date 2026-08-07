package kr.co.cudo.authoring.batch.test;

import kr.co.cudo.authoring.batch.orchestrator.BatchOrchestrator;
import kr.co.cudo.authoring.common.exception.GlobalExceptionHandler;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.TrainingVideoIngestService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/dev/batch/pending} 페이징 계약 회귀 가드.
 *
 * <p>고정하는 것:
 * <ul>
 *   <li>기본값 {@code page=0} / {@code size=20} (파라미터 미지정 호출)</li>
 *   <li>{@code size} 상한 100 — 경계(100 통과 / 101 거부) · {@code size<1} · {@code page<0} 는 400
 *       ({@code INVALID_INPUT})</li>
 *   <li>응답 {@code data} 가 배열이 아니라 <b>페이지 객체</b>
 *       ({@code content}/{@code totalElements}/{@code totalPages}/{@code number}/{@code size})</li>
 *   <li>2페이지 이상일 때 {@code page=1} 이 <b>다음 묶음</b>을 반환 — 전량 반환이 아님</li>
 * </ul>
 *
 * <p><b>왜 standalone MockMvc + 검증 프록시인가</b>: 400 의 원천은 파라미터의
 * {@code @Min}/{@code @Max} 선언적 검증이라 컨트롤러 메서드 직접 호출로는 재현되지 않는다
 * (기본값 주입도 마찬가지). 운영에서는 {@code @Validated} 클래스가 AOP 프록시로 감싸져
 * {@code ConstraintViolationException} → {@link GlobalExceptionHandler} → 400 이 되므로,
 * 여기서도 {@link MethodValidationPostProcessor} 로 <b>같은 경로</b>를 만든다.
 * ({@code @SpringBootTest} 는 이 계약을 보기 위해 전체 컨텍스트·DB 를 띄우는 비용이 과하다.)
 */
class BatchDevTriggerPendingPagingTest {

    private static final String PENDING = "PENDING";

    private MockMvc mockMvc;
    private VideoRepository videoRepository;

    @BeforeEach
    void setUp() {
        videoRepository = mock(VideoRepository.class);
        BatchDevTriggerController controller = new BatchDevTriggerController(
                mock(BatchOrchestrator.class),
                videoRepository,
                mock(TrainingVideoIngestService.class));

        MethodValidationPostProcessor validationProxy = new MethodValidationPostProcessor();
        validationProxy.afterPropertiesSet();
        Object validated = validationProxy.postProcessAfterInitialization(controller, "batchDevTriggerController");

        mockMvc = MockMvcBuilders.standaloneSetup(validated)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** 저장소가 실제로 Pageable 대로 잘라 주는 것처럼 동작시킨다 — 컨트롤러가 페이징을 무시하면 드러난다. */
    private void givenPendingRows(int total) {
        List<LsDataRaw> all = new ArrayList<>(total);
        for (int i = 1; i <= total; i++) {
            LsDataRaw row = mock(LsDataRaw.class);
            given(row.getRawSn()).willReturn((long) i);
            all.add(row);
        }
        given(videoRepository.findAllByDataSttsCd(eq(PENDING), any(Pageable.class)))
                .willAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(1);
                    int from = Math.min((int) pageable.getOffset(), all.size());
                    int to = Math.min(from + pageable.getPageSize(), all.size());
                    return new PageImpl<>(all.subList(from, to), pageable, all.size());
                });
    }

    @Test
    @DisplayName("파라미터_미지정이면_page0_size20_기본값이_적용된다")
    void defaultsToPageZeroSizeTwenty() throws Exception {
        givenPendingRows(25);

        mockMvc.perform(get("/v1/dev/batch/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.content.length()").value(20));
    }

    @Test
    @DisplayName("응답_data는_배열이_아니라_페이지_객체다")
    void responseIsPageObject() throws Exception {
        givenPendingRows(25);

        mockMvc.perform(get("/v1/dev/batch/pending").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isMap())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0]").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(25))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.size").value(20));
    }

    @Test
    @DisplayName("2페이지_이상일_때_page1은_다음_묶음만_반환한다_전량_반환_아님")
    void secondPageReturnsNextChunkOnly() throws Exception {
        givenPendingRows(25);

        mockMvc.perform(get("/v1/dev/batch/pending").param("page", "1").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.number").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.content[0]").value(21))
                .andExpect(jsonPath("$.data.content[4]").value(25))
                .andExpect(jsonPath("$.data.totalElements").value(25));
    }

    @Test
    @DisplayName("결과가_0건이면_빈_content와_totalElements_0을_반환한다")
    void emptyResultReturnsEmptyPage() throws Exception {
        givenPendingRows(0);

        mockMvc.perform(get("/v1/dev/batch/pending"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isEmpty())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.totalPages").value(0));
    }

    @Test
    @DisplayName("size_100은_상한_경계라_통과한다")
    void sizeAtUpperBoundPasses() throws Exception {
        givenPendingRows(3);

        mockMvc.perform(get("/v1/dev/batch/pending").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("size_101은_상한_초과라_400_INVALID_INPUT_이고_조회하지_않는다")
    void sizeOverUpperBoundIsRejected() throws Exception {
        mockMvc.perform(get("/v1/dev/batch/pending").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(videoRepository, never()).findAllByDataSttsCd(eq(PENDING), any(Pageable.class));
    }

    @Test
    @DisplayName("size_0은_400_INVALID_INPUT_이다")
    void sizeBelowLowerBoundIsRejected() throws Exception {
        mockMvc.perform(get("/v1/dev/batch/pending").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(videoRepository, never()).findAllByDataSttsCd(eq(PENDING), any(Pageable.class));
    }

    @Test
    @DisplayName("음수_page는_400_INVALID_INPUT_이다")
    void negativePageIsRejected() throws Exception {
        mockMvc.perform(get("/v1/dev/batch/pending").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_INPUT"));

        verify(videoRepository, never()).findAllByDataSttsCd(eq(PENDING), any(Pageable.class));
    }

    @Test
    @DisplayName("페이징은_저장소_계층에서_수행된다_전량조회_메서드_미사용")
    void pagingHappensInRepository() throws Exception {
        givenPendingRows(25);

        mockMvc.perform(get("/v1/dev/batch/pending").param("page", "1").param("size", "10"))
                .andExpect(status().isOk());

        verify(videoRepository).findAllByDataSttsCd(eq(PENDING), any(Pageable.class));
        // 전량 조회 오버로드(List 반환)를 타면 메모리에서 자르는 구 동작으로 되돌아간 것이다.
        verify(videoRepository, never()).findAllByDataSttsCd(PENDING);
    }
}
