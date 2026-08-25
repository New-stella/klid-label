package kr.co.cudo.authoring.batch.step;

import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.pipeline.BatchContext;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.client.AiServerClient;
import kr.co.cudo.authoring.common.client.dto.YoloResponse;
import kr.co.cudo.authoring.eventtype.service.EventTypeCacheEvictor;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset;
import kr.co.cudo.authoring.preset.entity.LsLabelPreset.LabelCodeSpec;
import kr.co.cudo.authoring.preset.repository.LsLabelPresetRepository;
import kr.co.cudo.authoring.support.RawVideoFixture;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 결함#1 회귀 — 배치 스텝의 트랜잭션 경계가 <b>실제로 열리는지</b> 실 DB 로 검증한다
 * (self-invocation 으로 {@code @Transactional} 이 무력화되던 결함).
 *
 * <h3>왜 기존 4,187 건이 이 결함을 못 잡았는가</h3>
 * <ul>
 *   <li>단위테스트({@code YoloAutolabelStepTest})는 리포지토리를 mock 으로 두어 트랜잭션 유무와 무관하게
 *       통과한다 — {@code @Modifying} 벌크 UPDATE 가 트랜잭션을 요구한다는 사실 자체가 재현되지 않는다.</li>
 *   <li>DB 를 쓰는 기존 테스트는 대부분 <b>클래스/메서드에 {@code @Transactional}</b> 이 걸려 있어
 *       <b>앰비언트 트랜잭션</b>이 이미 존재했다. 그 안에서는 경계가 없어도 벌크 UPDATE 가 성공한다.</li>
 * </ul>
 * 그래서 본 테스트는 ①<b>{@code @Transactional} 을 붙이지 않고</b>(오케스트레이터
 * {@code process()} 와 동일한 무-트랜잭션 호출 형상) ②<b>주입받은 빈(=프록시)</b> 의
 * {@code execute(ctx)} 를 호출한다 — 오케스트레이터가 {@code step.execute(ctx)} 로 호출하는 그 경로다.
 *
 * <h3>수정 전 실측(RED)</h3>
 * {@code srcRepository.bumpLabelVersionIn}(네이티브 {@code @Modifying} UPDATE)에서
 * {@code InvalidDataAccessApiUsageException: Executing an update/delete query} 로 실패했고,
 * 라벨은 저장되는데 {@code LBL_VER} 는 0 으로 남아 lost update 방어가 무효화됐다.
 * 따라서 예외 부재만으로는 부족하며 {@code LBL_VER} 증가까지 단언한다.
 */
@SpringBootTest
@ActiveProfiles("local")
class YoloStepTransactionBoundaryIntegrationTest {

    private static Path tmpRawDir;

    @DynamicPropertySource
    static void overrideStoragePaths(DynamicPropertyRegistry registry) throws IOException {
        tmpRawDir = Files.createTempDirectory("yolo-tx-raw-");
        registry.add("authoring.storage.raw-path", () -> tmpRawDir.toAbsolutePath().toString());
    }

    /** 프로덕션 경로 형상 유지 — 외부 ai-server 만 대체하고 나머지(리포지토리·프록시)는 실물. */
    @MockBean
    private AiServerClient aiServerClient;

    @Autowired
    private YoloAutolabelStep yoloAutolabelStep;
    @Autowired
    private VideoRepository videoRepository;
    @Autowired
    private LsDataSrcRepository srcRepository;
    @Autowired
    private LsDataLblRepository lblRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private LsLabelRepository labelRepository;
    @Autowired
    private LsLabelPresetRepository presetRepository;
    @Autowired
    private EventTypeCacheEvictor eventTypeCacheEvictor;

    private Long rawSn;
    private Long seededLabelId;
    private Long seededPresetId;

    /**
     * ★이 시험군 전용 이벤트 유형·검출 클래스. [@design ADR-054]
     *
     * <p>구 형상은 "프리셋 미매핑 값을 두어 fail-safe 전체 통과 경로를 탄다" 였는데, 그 폴백이
     * 폐기돼(CO-014) 프리셋이 없으면 <b>추론조차 시작되지 않아</b> 이 시험의 주제(트랜잭션 경계)에
     * 도달하지 못한다. 그래서 실효 프리셋을 심는다 — 주제가 바뀐 것이 아니라 전제가 바뀐 것이다.
     */
    private static final String EVENT_TYPE_CD = "EV94000101";
    /** 활성 라벨 부분 유니크(V129)와 충돌하지 않도록 이 클래스 전용 검출 클래스명을 쓴다. */
    private static final String DTCT_TYPE_CD = "yolotxbound";

    @BeforeEach
    void seedPreset() {
        jdbcTemplate.update("INSERT INTO LS_EVNT_TYPE "
                        + "(EVNT_TYPE_CD, EVNT_NM, EVNT_CLSF_CD, EVNT_CTGRY_CD, CLCT_YN) "
                        + "VALUES (?, ?, '94', '0001', 'Y') "
                        + "ON CONFLICT (EVNT_TYPE_CD) DO UPDATE "
                        + "SET EVNT_NM = EXCLUDED.EVNT_NM, CLCT_YN = 'Y'",
                EVENT_TYPE_CD, "탐지경계IT");
        // 장수명(6h) 캐시라 방금 심은 마스터가 반영되려면 비워야 한다.
        eventTypeCacheEvictor.evictNow();

        LsLabel label = labelRepository.saveAndFlush(LsLabel.create(
                "탐지경계IT-" + System.nanoTime(), "#112233", "BBOX", 0, DTCT_TYPE_CD, "tester"));
        seededLabelId = label.getLabelId();
        LsLabelPreset preset = presetRepository.saveAndFlush(LsLabelPreset.createWithOptions(
                List.of(new LabelCodeSpec(seededLabelId, null)), EVENT_TYPE_CD));
        seededPresetId = preset.getPresetId();
    }

    @AfterEach
    void cleanUp() {
        if (rawSn != null) {
            // FK ON DELETE CASCADE(V146) 로 프레임·라벨까지 함께 정리된다.
            RawVideoFixture.deleteRaws(jdbcTemplate, rawSn);
        }
        if (seededPresetId != null) {
            jdbcTemplate.update("DELETE FROM LS_LABEL_PRESET_CODE WHERE PRESET_ID = ?", seededPresetId);
            jdbcTemplate.update("DELETE FROM LS_LABEL_PRESET WHERE PRESET_ID = ?", seededPresetId);
        }
        if (seededLabelId != null) {
            // 이 시험이 만든 자동 라벨이 마스터를 참조한다 — 부모 영상 정리의 CASCADE 만 믿지 않고
            //   명시적으로 끊는다(남아 있으면 FK 위반으로 정리가 실패해 다음 실행까지 오염된다).
            jdbcTemplate.update("DELETE FROM LS_DATA_LBL WHERE LBL_ID = ?", seededLabelId);
            jdbcTemplate.update("DELETE FROM LS_LABEL WHERE LBL_ID = ?", seededLabelId);
        }
        jdbcTemplate.update("DELETE FROM LS_EVNT_TYPE WHERE EVNT_TYPE_CD = ?", EVENT_TYPE_CD);
        eventTypeCacheEvictor.evictNow();
    }

    /** 실측 해상도(100x100)를 읽을 수 있는 진짜 PNG — 좌표 상한 검증 경로까지 프로덕션과 동일하게 태운다. */
    private Path seedFrameImage(String name) throws IOException {
        Path image = tmpRawDir.resolve(name);
        ImageIO.write(new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        return image;
    }

    @Test
    @DisplayName("결함1_무트랜잭션_호출자에서_YOLO_execute가_프록시경유로_트랜잭션을_열어_LBL_VER를_증가시킨다")
    void yoloExecuteViaProxy_opensTransaction_andBumpsLabelVersion() throws IOException {
        // given — 영상 1건 + 프레임 1건(실제 이미지 파일). 이벤트 타입에는 <b>실효 프리셋</b>을 심어
        //         탐지 게이트를 통과시킨다(구 fail-safe 전체 통과 전제는 CO-014 로 폐기됐다).
        Path image = seedFrameImage("frame-0.png");
        LsDataRaw raw = videoRepository.save(LsDataRaw.createFromIngest(
                "clip-yolo-tx-" + System.nanoTime(), "cctv-1", EVENT_TYPE_CD, "GOV",
                LsDataRaw.PRVC_TYPE_ANONY, image.toString(), null, 60));
        rawSn = raw.getRawSn();
        LsDataSrc frame = srcRepository.save(
                LsDataSrc.create(rawSn, 0L, 0L, image.toString(), null));
        Long srcSn = frame.getSrcSn();
        assertThat(srcRepository.findById(srcSn).orElseThrow().getLblVer()).isZero();

        when(aiServerClient.predictYoloTrack(any())).thenReturn(Mono.just(new YoloResponse(
                List.of(new YoloResponse.Detection(DTCT_TYPE_CD, List.of(10.0, 10.0, 50.0, 50.0), 0.9, 7)))));

        // when — 오케스트레이터와 동일하게 빈(프록시)의 execute 를 무-트랜잭션 컨텍스트에서 호출.
        //        수정 전에는 여기서 InvalidDataAccessApiUsageException 이 터졌다.
        yoloAutolabelStep.execute(new BatchContext(rawSn, raw));

        // then — 자동 라벨이 커밋되고, 그 프레임의 라벨셋 버전이 +1 되어 있어야 한다.
        //        (라벨만 저장되고 LBL_VER=0 인 상태가 바로 수정 전 실기동 증상이다.)
        assertThat(lblRepository.findBySrcSn(srcSn)).isNotEmpty();
        assertThat(srcRepository.findById(srcSn).orElseThrow().getLblVer()).isEqualTo(1L);
    }
}
