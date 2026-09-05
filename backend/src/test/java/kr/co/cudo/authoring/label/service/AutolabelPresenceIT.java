package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AutolabelPresence} 실 DB(PostgreSQL Testcontainer) 회귀 가드 — 「오토라벨 산출물이 있는가」.
 *
 * <ol>
 *   <li>자동 생성 라벨({@code AUTO_LBL_YN='Y'} + 오토라벨 출처)이 있으면 <b>있음</b>.</li>
 *   <li><b>사람이 그린 라벨만 있으면 없음</b> — 이 판정기의 존재 이유다. "라벨이 있는가"로 판정하면
 *       작업자가 라벨을 하나 그린 순간 조치가 필요한 영상이 목록에서 조용히 사라진다.</li>
 *   <li>라벨이 하나도 없으면 없음.</li>
 *   <li>프레임·라벨이 많아도 존재 확인은 <b>첫 한 건에서 끝난다</b>(전량을 훑지 않는다).</li>
 * </ol>
 *
 * <p>공유 Testcontainers PG 를 쓰므로 시드는 {@code AUTOPRES-} 고유 clipId 로 만들고 단언은 시드한
 * rawSn 으로만 좁힌다(다른 통합테스트 데이터에 의존하지 않는다).
 */
@SpringBootTest
@ActiveProfiles("local")
class AutolabelPresenceIT {

    @Autowired private AutolabelPresence presence;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;

    private final TransactionTemplate txTemplate;

    AutolabelPresenceIT(@Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    /** 프레임만 있는 영상(라벨 0건)을 만들고 rawSn 을 돌려준다. */
    private long seedVideoWithFrames(String suffix, int frameCount) {
        return txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "AUTOPRES-" + suffix + "-" + System.nanoTime(), "CCTV-AUTOPRES", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/AUTOPRES.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            Long rawSn = videoRepository.save(raw).getRawSn();
            for (int i = 0; i < frameCount; i++) {
                srcRepository.save(LsDataSrc.create(rawSn, i, "/raw/f" + i + ".jpg", LocalDateTime.now()));
            }
            return rawSn;
        });
    }

    private long firstSrcSn(long rawSn) {
        return txTemplate.execute(s ->
                srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).get(0).getSrcSn());
    }

    /** 배치 오토라벨과 같은 모양의 라벨 — {@code applyAiSource} 가 출처와 AUTO_LBL_YN='Y' 를 함께 세운다. */
    private void seedAutoLabel(long srcSn, String lblSrcCd, String labelNm) {
        txTemplate.execute(s -> {
            LsDataLbl label = LsDataLbl.createAutoBbox(
                    srcSn, null, labelNm, "[10,20,30,40]", BigDecimal.valueOf(0.9), null);
            label.applyAiSource(lblSrcCd, BigDecimal.valueOf(0.9));
            return lblRepository.save(label);
        });
    }

    /** 작업자가 손으로 그린 라벨 — AI 메타 3필드가 전부 null 이다. */
    private void seedManualLabel(long srcSn, String labelNm) {
        txTemplate.execute(s -> lblRepository.save(LsDataLbl.createManual(
                srcSn, LsDataLbl.TYPE_BBOX, null, labelNm, "[10,20,30,40]", "1")));
    }

    @Test
    @DisplayName("자동_생성_라벨이_있으면_산출물_있음으로_판정한다")
    void autoLabelPresent() {
        long rawSn = seedVideoWithFrames("A", 1);
        long srcSn = firstSrcSn(rawSn);
        seedAutoLabel(srcSn, LsDataLbl.SRC_YOLO, "person");

        assertThat(presence.exists(rawSn)).isTrue();
        assertThat(presence.count(rawSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AI_분할_산출물만_있어도_산출물_있음으로_판정한다")
    void sam2LabelAlsoCounts() {
        long rawSn = seedVideoWithFrames("S", 1);
        seedAutoLabel(firstSrcSn(rawSn), LsDataLbl.SRC_SAM2, "car");

        assertThat(presence.exists(rawSn)).isTrue();
    }

    @Test
    @DisplayName("★사람이_그린_라벨만_있으면_산출물_없음으로_판정한다")
    void manualLabelsOnlyIsAbsent() {
        long rawSn = seedVideoWithFrames("M", 1);
        long srcSn = firstSrcSn(rawSn);
        seedManualLabel(srcSn, "person");
        seedManualLabel(srcSn, "car");

        // 라벨은 2건 있지만 오토라벨 산출물은 0건이다 — 이 영상은 여전히 조치가 필요하다.
        List<LsDataLbl> stored = txTemplate.execute(s -> lblRepository.findBySrcSn(srcSn));
        assertThat(stored).hasSize(2);
        assertThat(presence.exists(rawSn)).isFalse();
        assertThat(presence.count(rawSn)).isZero();
    }

    @Test
    @DisplayName("라벨이_하나도_없으면_산출물_없음으로_판정한다")
    void noLabelsIsAbsent() {
        long rawSn = seedVideoWithFrames("N", 1);

        assertThat(presence.exists(rawSn)).isFalse();
        assertThat(presence.count(rawSn)).isZero();
    }

    @Test
    @DisplayName("사람_라벨이_섞여_있어도_자동_생성_라벨만_센다")
    void mixedLabelsCountOnlyAuto() {
        long rawSn = seedVideoWithFrames("X", 1);
        long srcSn = firstSrcSn(rawSn);
        seedAutoLabel(srcSn, LsDataLbl.SRC_YOLO, "person");
        seedManualLabel(srcSn, "car");
        seedManualLabel(srcSn, "bus");

        assertThat(presence.exists(rawSn)).isTrue();
        assertThat(presence.count(rawSn)).isEqualTo(1L);
    }

    @Test
    @DisplayName("프레임이_많아도_존재_확인은_첫_한_건에서_끝난다")
    void existenceStopsAtFirstRow() {
        long rawSn = seedVideoWithFrames("P", 12);
        txTemplate.execute(s -> {
            var frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
            for (var frame : frames) {
                LsDataLbl label = LsDataLbl.createAutoBbox(
                        frame.getSrcSn(), null, "person", "[10,20,30,40]", BigDecimal.valueOf(0.9), null);
                label.applyAiSource(LsDataLbl.SRC_YOLO, BigDecimal.valueOf(0.9));
                lblRepository.save(label);
            }
            return null;
        });

        // 전체는 12건이지만 존재 확인 쿼리는 1건만 읽어 온다(조기 종료).
        assertThat(presence.count(rawSn)).isEqualTo(12L);
        List<Long> firstPage = txTemplate.execute(s -> lblRepository.findAutoLabelLblSnsByRawSn(
                rawSn, AutolabelPresence.AUTOLABEL_SRC_CDS, PageRequest.of(0, 1)));
        assertThat(firstPage).hasSize(1);
        assertThat(presence.exists(rawSn)).isTrue();
    }
}
