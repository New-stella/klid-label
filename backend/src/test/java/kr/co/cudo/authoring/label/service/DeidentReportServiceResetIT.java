package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MED-2 회귀 가드 — {@code DeidentReportService.report()} 의 재비식별 리셋 경로를
 * <b>실 EM(PostgreSQL Testcontainer) 통합 테스트</b>로 못박는다.
 *
 * <p>기존 {@code DeidentReportServiceTest} 는 순수 Mockito 라 {@code resetPrivacyMetaByRawSn} 이
 * 영속성 컨텍스트를 실제로 건드리지 않는다 — 그래서 누군가 리셋 벌크 JPQL 에
 * {@code clearAutomatically=true} 를 추가해 부모 RAW 를 detach 시켜도(메모리 함정:
 * 공유 clear 가 raw 'F' dirty-update 를 유실시킨 사고 이력) mock 테스트는 GREEN 으로 남는다.
 *
 * <p>본 IT 는 실 DB 커밋 후 재조회로 다음을 동시에 증명한다:
 * <ol>
 *   <li>프레임 개인정보 3필드(익명/가명/개인정보 포함여부)가 NULL 로 리셋됨.</li>
 *   <li><b>{@code LS_DATA_RAW.DE_IDENT_YN='F'} 가 실제 flush 됨</b> — 리셋 JPQL 이 부모 RAW 의
 *       dirty-update(markDeidentified('F'))를 detach 시키지 않았음을 실 커밋으로 증명한다.
 *       {@code clearAutomatically=true} 회귀 시 부모가 detach 되어 'F' 가 유실되고 이 단언이 RED 가 된다.</li>
 *   <li>영상 전체 라벨이 삭제됨.</li>
 * </ol>
 *
 * <p>공유 Testcontainers PG 를 사용하므로 시드는 {@code DIDRST-} 고유 clipId 로 만들고, 단언은 시드한
 * rawSn 으로만 좁혀 다른 통합테스트 데이터를 오염시키지 않는다. REVIEWER 토큰은 LabelAccessGuard 를
 * 전체 통과하므로 신고 진입 셋업이 단순하다.
 */
@SpringBootTest
@ActiveProfiles("local")
class DeidentReportServiceResetIT {

    @Autowired private DeidentReportService deidentReportService;
    @Autowired private VideoRepository videoRepository;
    @Autowired private LsDataSrcRepository srcRepository;
    @Autowired private LsDataLblRepository lblRepository;

    private final TransactionTemplate txTemplate;

    DeidentReportServiceResetIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager) {
        this.txTemplate = new TransactionTemplate(controlTxManager);
    }

    @Test
    @DisplayName("신고_report실행후_커밋조회시_프레임개인정보3필드_NULL_및_RAW_DE_IDENT_YN_F_실제반영_라벨삭제")
    void reportResetsFramePrivacyAndFlushesRawFlagOnRealEm() {
        // given — 비식별 완료('Y') 영상 + 개인정보 3필드가 채워진 프레임 3건 + 라벨 1건.
        long rawSn = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "DIDRST-" + System.nanoTime(), "CCTV-DIDRST", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/DIDRST.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            raw = videoRepository.save(raw);
            Long rs = raw.getRawSn();
            // 부모 프레임에 개인정보 3필드를 채워 저장(파생 프레임 create 9-arg 팩토리 재사용).
            LsDataSrc f0 = srcRepository.save(LsDataSrc.create(
                    rs, 0L, 0L, "/raw/f0.jpg", "/deid/f0.jpg", LocalDateTime.now(), "Y", "N", "Y"));
            srcRepository.save(LsDataSrc.create(
                    rs, 1L, 1L, "/raw/f1.jpg", "/deid/f1.jpg", LocalDateTime.now(), "N", "Y", "Y"));
            srcRepository.save(LsDataSrc.create(
                    rs, 2L, 2L, "/raw/f2.jpg", "/deid/f2.jpg", LocalDateTime.now(), "Y", "Y", "N"));
            lblRepository.save(LsDataLbl.createAutoBbox(
                    f0.getSrcSn(), null, "person", "[10,20,30,40]", BigDecimal.valueOf(0.9), null));
            return rs;
        });

        long firstSrcSn = txTemplate.execute(s ->
                srcRepository.findByRawSnOrderByFrameNoAsc(rawSn).get(0).getSrcSn());

        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        // when — 실제 신고 서비스 호출(자체 @Transactional — 커밋됨).
        deidentReportService.report(firstSrcSn, "얼굴 미블러 노출", reviewer);

        // then (별도 트랜잭션 재조회) — ① 프레임 3필드 NULL.
        List<LsDataSrc> frames = txTemplate.execute(s ->
                srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(frames).hasSize(3);
        assertThat(frames).allSatisfy(f -> {
            assertThat(f.getAnonyInclYn()).isNull();
            assertThat(f.getPsdoInclYn()).isNull();
            assertThat(f.getPrvcInclYn()).isNull();
        });

        // ② RAW DE_IDENT_YN='F' 실제 반영 — 리셋 JPQL 이 부모 dirty-update 를 detach 안 시켰음을 실 flush 로 증명.
        LsDataRaw reloadedRaw = txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow());
        assertThat(reloadedRaw.getDeIdntfYn()).isEqualTo("F");

        // ③ 영상 전체 라벨 삭제.
        List<LsDataLbl> remaining = txTemplate.execute(s -> lblRepository.findAllByRawSn(rawSn));
        assertThat(remaining).isEmpty();
    }
}
