package kr.co.cudo.authoring.label.service;

import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.security.Channel;
import kr.co.cudo.authoring.common.security.Role;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.version.entity.LsDataLblHstry;
import kr.co.cudo.authoring.version.repository.LsDataLblHstryRepository;
import kr.co.cudo.authoring.version.util.LabelHistoryDiffSerializer;
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
 *   <li><b>영상 전체 라벨이 보존됨</b>(D-25, 2026-07-27 정책 반전 — 구 "전량 삭제" 폐기).</li>
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
    @Autowired private LsDataLblHstryRepository lblHstryRepository;

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
            // 영상 단위 개인정보 수동 판정(V163)도 채워 둔다 — 프레임 축과 동일하게 리셋 대상이다.
            raw.changePrivacyMeta("Y", "N", "N");
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

        // ②-1 영상 단위 개인정보 3필드(V163)도 NULL 로 리셋된다 — 프레임 축과 같은 근거다.
        //   그 판정은 <비식별이 잘못된 영상>에서 내려진 것이라 재판정 대상이고, 남겨두면 재비식별 후에도
        //   옛 판정이 export video 블록에 stale 로 실린다(CWE-359). 두 축 중 하나만 리셋하면 비대칭 결함.
        assertThat(reloadedRaw.getAnonyInclYn()).isNull();
        assertThat(reloadedRaw.getPsdoInclYn()).isNull();
        assertThat(reloadedRaw.getPrvcInclYn()).isNull();

        // ③ D-25(2026-07-27 정책 반전) — 라벨은 <b>삭제되지 않고 보존</b>된다.
        //    (구 정책은 전량 삭제였고 이 단언은 isEmpty() 였다. 삭제를 되살리면 이 단언이 RED 가 된다.)
        List<LsDataLbl> remaining = txTemplate.execute(s -> lblRepository.findAllByRawSn(rawSn));
        assertThat(remaining).hasSize(1);
    }

    /**
     * DEV_FIX-B(M5, 보안 M-3) — 개인정보 3필드 리셋은 PII 표기를 되돌리는 행위이므로
     * <b>행 단위 감사</b>가 남아야 한다(OWASP A09). 구 구현은 집계 로그 한 줄뿐이었다.
     */
    @Test
    @DisplayName("개인정보_3필드_리셋이_감사_가능하게_기록된다")
    void privacyMetaResetIsAuditedPerFrame() {
        // given — 3필드가 채워진 프레임 2건 + 이미 NULL 인 프레임 1건(감사 잡음 대상 아님).
        long rawSn = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "DIDAUD-" + System.nanoTime(), "CCTV-DIDAUD", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/DIDAUD.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            raw = videoRepository.save(raw);
            Long rs = raw.getRawSn();
            srcRepository.save(LsDataSrc.create(
                    rs, 0L, 0L, "/raw/a0.jpg", "/deid/a0.jpg", LocalDateTime.now(), "Y", "N", "Y"));
            srcRepository.save(LsDataSrc.create(
                    rs, 1L, 1L, "/raw/a1.jpg", "/deid/a1.jpg", LocalDateTime.now(), "N", "Y", "Y"));
            // 3필드가 모두 NULL 인 프레임 — 변화가 없으므로 감사 대상에서 제외되어야 한다.
            srcRepository.save(LsDataSrc.create(rs, 2, "/raw/a2.jpg", LocalDateTime.now()));
            return rs;
        });

        List<LsDataSrc> seeded = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        List<Long> withPrivacy = List.of(seeded.get(0).getSrcSn(), seeded.get(1).getSrcSn());
        Long withoutPrivacy = seeded.get(2).getSrcSn();

        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        // when
        Long rprtSn = deidentReportService.report(withPrivacy.get(0), "얼굴 미블러 노출", reviewer);

        // then — 값이 있던 프레임마다 감사 이력 1건(라벨 델타 0건 이벤트).
        List<LsDataLblHstry> audits = txTemplate.execute(s -> withPrivacy.stream()
                .flatMap(sn -> lblHstryRepository.findBySrcSnOrderByRegDtDesc(sn).stream())
                .toList());
        // 값이 이미 NULL 이던 프레임은 변화가 없으므로 감사 잡음을 만들지 않는다.
        List<LsDataLblHstry> untouched = txTemplate.execute(s ->
                lblHstryRepository.findBySrcSnOrderByRegDtDesc(withoutPrivacy));
        assertThat(untouched).isEmpty();
        assertThat(audits).hasSize(2);
        assertThat(audits).extracting(LsDataLblHstry::getSrcSn)
                .containsExactlyInAnyOrderElementsOf(withPrivacy);
        assertThat(audits).allSatisfy(h -> {
            // 행위자·시각이 남고, 라벨 변경은 0건이다(라벨을 건드리지 않는 이벤트).
            assertThat(h.getRegId()).isEqualTo("1");
            assertThat(h.getRegDt()).isNotNull();
            assertThat(h.getAddCnt() + h.getMdfcnCnt() + h.getDelCnt()).isZero();
            // 어떤 신고로 리셋됐는지 추적 가능해야 한다(신규 컬럼 없이 봉투 JSON).
            assertThat(h.getChgDtlCn())
                    .contains(LabelHistoryDiffSerializer.EVENT_PRIVACY_META_RESET)
                    .contains(String.valueOf(rprtSn));
        });
    }
}
