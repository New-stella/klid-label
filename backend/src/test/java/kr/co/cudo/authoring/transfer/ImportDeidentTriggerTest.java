package kr.co.cudo.authoring.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import kr.co.cudo.authoring.transfer.service.ImportMappingResolver;
import kr.co.cudo.authoring.transfer.service.ImportPersistTxService;
import kr.co.cudo.authoring.transfer.service.ImportPlan;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 이관 적재가 <b>비식별 선두 단계를 언제 시작시키는가</b> — 발행 조건 시험.
 *
 * <h3>왜 필요한가</h3>
 * <p>적재는 비식별을 언제나 시작시키지 않는다. {@code 원본으로 지정 + 영상 파일을 함께 받음} 일 때만
 * 시작시킨다. <b>프레임만 받은 산출물</b>은 비식별할 대상 영상이 아예 없어 발행해도 할 일이 없고,
 * 그 경우의 승인 보류는 외부 비식별 산출물을 받아 기록하는 별도 행위가 푼다.
 *
 * <p>이 조건이 무너지는 방향은 둘 다 나쁘다. 영상이 없는데 발행하면 비식별 단계가 <b>대상 없이 돌아
 * 실패로 기록</b>되어 운영자가 고칠 것이 없는 실패를 보게 되고, 영상이 있는데 발행하지 않으면
 * 원본이 <b>비식별되지 않은 채</b> 승인 보류만 걸린 상태로 남는다.
 *
 * <p>대조군을 함께 두는 이유는 «발행 0» 단언만으로는 조건이 실제로 작동하는지와 발행 자체가 죽었는지가
 * 구분되지 않기 때문이다.
 *
 * @design DOMAIN-017
 * @design API-206
 * @design AC-046
 */
class ImportDeidentTriggerTest {

    @Test
    @DisplayName("원본으로_지정해도_영상_파일이_없으면_비식별을_시작시키지_않는다")
    void 원본으로_지정해도_영상_파일이_없으면_비식별을_시작시키지_않는다() {
        Fixture f = new Fixture();

        f.service.persist(f.plan(false, null), "1");

        // 비식별할 대상 영상이 없다 — 발행하면 대상 없이 돌아 «고칠 것이 없는 실패» 가 쌓인다.
        verify(f.eventPublisher, never()).publishEvent(any(VideoIngestedEvent.class));
    }

    @Test
    @DisplayName("원본으로_지정하고_영상_파일도_받으면_비식별을_시작시킨다")
    void 원본으로_지정하고_영상_파일도_받으면_비식별을_시작시킨다() {
        Fixture f = new Fixture();

        f.service.persist(f.plan(false, Path.of("build/tmp/import-trigger/sample.mp4")), "1");

        // 대조군 — 이것이 없으면 위 시험의 «발행 0» 이 조건 때문인지 발행이 죽은 것인지 알 수 없다.
        verify(f.eventPublisher).publishEvent(any(VideoIngestedEvent.class));
    }

    @Test
    @DisplayName("비식별이_끝난_것으로_지정하면_영상_파일이_있어도_다시_돌리지_않는다")
    void 비식별이_끝난_것으로_지정하면_영상_파일이_있어도_다시_돌리지_않는다() {
        Fixture f = new Fixture();

        f.service.persist(f.plan(true, Path.of("build/tmp/import-trigger/sample.mp4")), "1");

        verify(f.eventPublisher, never()).publishEvent(any(VideoIngestedEvent.class));
    }

    /** 발행 조건만 보는 최소 대역 — 저장 결과는 이 시험의 관심사가 아니다. */
    private static final class Fixture {
        final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        final ImportPersistTxService service;
        private final ImportedDataset dataset;

        Fixture() {
            VideoRepository videoRepository = mock(VideoRepository.class);
            when(videoRepository.saveAndFlush(any(LsDataRaw.class))).thenAnswer(inv -> {
                LsDataRaw raw = inv.getArgument(0);
                ReflectionTestUtils.setField(raw, "rawSn", 7L);
                return raw;
            });
            LsDataSrcRepository srcRepository = mock(LsDataSrcRepository.class);
            when(srcRepository.saveAll(any())).thenReturn(List.of());

            service = new ImportPersistTxService(
                    videoRepository,
                    srcRepository,
                    mock(LsDataLblRepository.class),
                    mock(LsDataMetaRepository.class),
                    mock(LsDeidentProcLogRepository.class),
                    mock(LsRawDataStatusRepository.class),
                    eventPublisher,
                    new ObjectMapper());

            dataset = new FirstAnnotationParser(new ObjectMapper())
                    .parseFolder(ImportSampleFolder.path());
        }

        /** 프레임은 비운다 — 이 시험이 보는 것은 영상 파일 유무와 지정값뿐이다. */
        ImportPlan plan(boolean deidentified, Path sourceVideo) {
            return new ImportPlan(dataset,
                    ImportSampleFolder.VMS_CLIP_ID,
                    "/nas/import/00000073.mp4",
                    deidentified,
                    sourceVideo,
                    List.of(),
                    new ImportMappingResolver.Resolved(
                            Map.of(ImportSampleFolder.LABEL_CATEGORY, 1L),
                            Map.of(ImportSampleFolder.LABEL_CATEGORY, "도로"),
                            "EV0100010", List.of()),
                    List.of(),
                    Map.of());
        }
    }
}
