package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-25 + S7 회귀 가드 — 비식별 누락 신고 <b>정책 반전</b>(2026-07-27 사용자 확정)을 실 DB
 * (PostgreSQL Testcontainer) 로 못박는다.
 *
 * <ol>
 *   <li><b>D-25</b>: 신고해도 <b>라벨이 삭제되지 않는다</b>(구 정책 = 전량 삭제).</li>
 *   <li><b>D-25</b>: 신고해도 {@code LS_LABEL_VERSION} 에 {@code DEIDENT_REPORT} 스냅샷이
 *       <b>적재되지 않는다</b>(구 정책 = 삭제 직전 rawSn 스코프 비활성 스냅샷).</li>
 *   <li><b>S7</b>: 신고 구간({@code DE_IDNTF_YN='F'})에는 라벨 조회가 차단된다(412).</li>
 *   <li><b>S7 핵심 수용 기준</b>: resolve 후 라벨 조회가 다시 가능하고, <b>기존 라벨이 그대로</b>다
 *       (LBL_SN·좌표 동일 — 복원 절차 없이 보존본 재사용).</li>
 *   <li><b>S7 회귀 방어</b>: {@code DE_IDNTF_YN='Y'} 인 일반 영상의 라벨 조회는 영향받지 않는다.</li>
 * </ol>
 *
 * <p>공유 Testcontainers PG 를 사용하므로 시드는 {@code DIDPRSV-} 고유 clipId 로 만들고, 단언은 시드한
 * rawSn/srcSn 으로만 좁혀 다른 통합테스트 데이터를 오염시키지 않는다. REVIEWER 토큰은 LabelAccessGuard 의
 * 인가를 전체 통과하므로(게이트는 역할 무관) 신고·조회 셋업이 단순하다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DeidentReportLabelPreservationIT {

    @Autowired private DeidentReportService deidentReportService;
    @Autowired private LabelService labelService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;
    @Autowired private LsLabelVersionRepository labelVersionRepository;
    @Autowired private LsDeidentProcLogRepository procLogRepository;

    @Autowired private kr.co.cudo.authoring.label.repository.LsDeidentReportRepository reportRepository;
    @Autowired private kr.co.cudo.authoring.auth.service.WorkLockService workLockService;

    @TempDir Path tempDir;

    /** 이 IT 가 시드한 rawSn 목록 — 공유 PG 오염 방지용 정리 대상. */
    private final List<Long> seededRawSns = new java.util.ArrayList<>();

    private final TransactionTemplate txTemplate;

    DeidentReportLabelPreservationIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    private final TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
            Instant.now().plusSeconds(600));

    /**
     * 공유 Testcontainers PG 오염 방지 — 이 IT 가 만든 신고 행과 작업락을 정리한다.
     * (다른 통합테스트가 신고 목록 건수를 절대값으로 단언하므로 반드시 되돌린다.)
     */
    @org.junit.jupiter.api.AfterEach
    void cleanUpReports() {
        for (Long rawSn : seededRawSns) {
            txTemplate.execute(s -> {
                reportRepository.deleteAll(reportRepository.findAllByDataRawSnOrderByReportDtDesc(rawSn));
                return null;
            });
            workLockService.releaseRaw(rawSn, "system", "TEST_CLEANUP");
        }
        seededRawSns.clear();
    }

    /** 비식별 완료('Y') 영상 + 프레임 1건 + 라벨 2건 시드. */
    private long[] seed(String suffix) {
        long[] ids = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "DIDPRSV-" + suffix + "-" + System.nanoTime(), "CCTV-DIDPRSV", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/DIDPRSV.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            raw = videoRepository.save(raw);
            Long rawSn = raw.getRawSn();
            LsDataSrc f0 = srcRepository.save(LsDataSrc.create(
                    rawSn, 0L, 0L, "/raw/f0.jpg", "/deid/f0.jpg", LocalDateTime.now(), "Y", "N", "Y"));
            lblRepository.save(LsDataLbl.createAutoBbox(
                    f0.getSrcSn(), null, "person", "[10,20,30,40]", BigDecimal.valueOf(0.9), null));
            lblRepository.save(LsDataLbl.createAutoBbox(
                    f0.getSrcSn(), null, "car", "[50,60,70,80]", BigDecimal.valueOf(0.8), null));
            return new long[]{rawSn, f0.getSrcSn()};
        });
        seededRawSns.add(ids[0]);
        return ids;
    }

    /** 신고 이후 외부 솔루션이 비식별본을 교체한 상태를 만든다(resolve 산출물 검증 게이트 통과용). */
    private void seedDeidentArtifact(long rawSn) throws IOException {
        // 무결성 판정(DeidentArtifactIntegrity)을 통과하는 실제 최소 mp4 픽스처.
        Path deidFile = kr.co.cudo.authoring.support.TestVideoFixtures.writeTinyMp4(
                tempDir.resolve("deid-" + rawSn + ".mp4"));
        txTemplate.execute(s -> {
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, "req-" + rawSn, "/var/raw/DIDPRSV.mp4", "system");
            procLog.succeed(deidFile.toString());
            return procLogRepository.save(procLog);
        });
    }

    @Test
    @DisplayName("비식별_신고시_라벨이_삭제되지_않고_DEIDENT_REPORT_스냅샷도_적재되지_않는다")
    void reportPreservesLabelsAndWritesNoSnapshot() {
        // given
        long[] ids = seed("A");
        long rawSn = ids[0];
        long srcSn = ids[1];
        List<Long> before = txTemplate.execute(s -> lblRepository.findAllByRawSn(rawSn)).stream()
                .map(LsDataLbl::getLblSn).sorted().toList();
        assertThat(before).hasSize(2);

        // when — 실제 신고(자체 @Transactional — 커밋됨).
        deidentReportService.report(srcSn, "얼굴 미블러 노출", reviewer);

        // then ① 라벨 보존 — LBL_SN 까지 동일(재생성 아님).
        List<Long> after = txTemplate.execute(s -> lblRepository.findAllByRawSn(rawSn)).stream()
                .map(LsDataLbl::getLblSn).sorted().toList();
        assertThat(after).isEqualTo(before);

        // then ② DEIDENT_REPORT 스냅샷 미적재 — 해당 영상에 버전 행이 하나도 생기지 않아야 한다.
        boolean anyVersion = Boolean.TRUE.equals(txTemplate.execute(s ->
                labelVersionRepository.findFirstByDataRawSnOrderByVersionNoDesc(rawSn).isPresent()));
        assertThat(anyVersion).isFalse();

        // then ③ 신고 부작용은 유지 — DE_IDNTF_YN='F'.
        assertThat(txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow())
                .getDeIdntfYn()).isEqualTo("F");
    }

    @Test
    @DisplayName("비식별_신고_상태에서는_라벨_조회가_차단되고_resolve_후에는_기존_라벨이_그대로_조회된다")
    void labelReadBlockedDuringReportThenReopensWithSameLabels() throws IOException {
        // given — 신고 전에는 정상 조회되고, 라벨 2건이 보인다.
        long[] ids = seed("B");
        long rawSn = ids[0];
        long srcSn = ids[1];
        LabelResponse beforeReport = labelService.getByFrame(srcSn, reviewer);
        assertThat(beforeReport.items()).hasSize(2);
        List<Long> beforeIds = beforeReport.items().stream()
                .map(LabelResponse.Item::id).sorted().toList();

        // when — 신고 (라벨은 보존되지만 조회는 막혀야 한다).
        Long rprtSn = deidentReportService.report(srcSn, "얼굴 미블러 노출", reviewer);

        // then ① S7 — 신고 구간 라벨 조회 차단(412). REVIEWER 도 동일 차단(역할 무관 프리컨디션).
        assertThatThrownBy(() -> labelService.getByFrame(srcSn, reviewer))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRECONDITION_FAILED);

        // when — 외부 솔루션 수동 재비식별 완료 → resolve('F'→'Y').
        seedDeidentArtifact(rawSn);
        // R3 — 해소 시 재비식별 산출물을 목록에서 골라 지정한다(seedDeidentArtifact 가 만든 파일).
        deidentReportService.resolveManually(rprtSn, "deid-" + rawSn + ".mp4", reviewer);

        // then ② 게이트 자동 해제 + 보존된 기존 라벨이 그대로(LBL_SN·좌표 동일) 조회된다.
        LabelResponse afterResolve = labelService.getByFrame(srcSn, reviewer);
        assertThat(afterResolve.items()).hasSize(2);
        assertThat(afterResolve.items().stream().map(LabelResponse.Item::id).sorted().toList())
                .isEqualTo(beforeIds);
        assertThat(afterResolve.items())
                .extracting(LabelResponse.Item::label)
                .containsExactlyInAnyOrder("person", "car");
    }

    @Test
    @DisplayName("deIdntfYn_이_Y_인_일반영상의_라벨_조회는_영향받지_않는다")
    void normalVideoLabelReadUnaffected() {
        // given — 신고가 없는 정상('Y') 영상.
        long[] ids = seed("C");
        long srcSn = ids[1];

        // when / then — 게이트에 걸리지 않고 라벨이 그대로 조회된다.
        assertThatCode(() -> labelService.getByFrame(srcSn, reviewer)).doesNotThrowAnyException();
        assertThat(labelService.getByFrame(srcSn, reviewer).items()).hasSize(2);
    }
}
