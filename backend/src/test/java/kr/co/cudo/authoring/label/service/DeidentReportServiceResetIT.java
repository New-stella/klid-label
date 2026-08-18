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
 * MED-2 회귀 가드 — {@code DeidentReportService.report()} 의 <b>보존 계약</b>을
 * <b>실 EM(PostgreSQL Testcontainer) 통합 테스트</b>로 못박는다.
 *
 * <p>⚠ 클래스명의 "Reset" 은 <b>구 정책(신고 시 개인정보 3필드 NULL 리셋)의 잔재</b>다. 그 리셋은
 * 2026-08-04 폐기됐고(사용자 확정 — 라벨 보존과 같은 취지) 이 IT 는 이제 <b>리셋되지 않음</b>을
 * 고정한다. 파일명을 바꾸지 않는 것은 폐기 경위 추적을 끊지 않기 위함이다.
 *
 * <p>기존 {@code DeidentReportServiceTest} 는 순수 Mockito 라 영속성 컨텍스트를 실제로 건드리지
 * 않는다 — 그래서 누군가 신고 트랜잭션에 벌크 JPQL({@code clearAutomatically=true})을 되살려
 * 부모 RAW 를 detach 시켜도(메모리 함정: 공유 clear 가 raw 'F' dirty-update 를 유실시킨 사고 이력)
 * mock 테스트는 GREEN 으로 남는다.
 *
 * <p>본 IT 는 실 DB 커밋 후 재조회로 다음을 동시에 증명한다:
 * <ol>
 *   <li>프레임·영상 개인정보 3필드(익명/가명/개인정보 포함여부)가 <b>보존</b>됨
 *       (구 기대 "NULL 로 리셋됨"은 폐기 — 2026-08-04).</li>
 *   <li><b>{@code LS_DATA_RAW.DE_IDENT_YN='F'} 가 실제 flush 됨</b> — 신고 트랜잭션이 부모 RAW 의
 *       dirty-update(markDeidentified('F'))를 유실시키지 않았음을 실 커밋으로 증명한다.
 *       벌크 JPQL + {@code clearAutomatically=true} 회귀 시 부모가 detach 되어 'F' 가 유실되고
 *       이 단언이 RED 가 된다.</li>
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
    @DisplayName("신고_report실행후_커밋조회시_개인정보3필드_보존_및_RAW_DE_IDENT_YN_F_실제반영_라벨보존")
    void reportResetsFramePrivacyAndFlushesRawFlagOnRealEm() {
        // given — 비식별 완료('Y') 영상 + 개인정보 3필드가 채워진 프레임 3건 + 라벨 1건.
        long rawSn = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "DIDRST-" + System.nanoTime(), "CCTV-DIDRST", "EVT", "11680",
                    LsDataRaw.PRVC_TYPE_PRVC, "/var/raw/DIDRST.mp4", LocalDateTime.now(), 30);
            raw.markDeidentified("Y");
            // 영상 단위 개인정보 수동 판정(V163)도 채워 둔다 — 프레임 축과 동일하게 <보존> 대상이다.
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

        // then (별도 트랜잭션 재조회) — ① ★ 프레임 3필드는 <보존>된다(2026-08-04 정책 반전).
        //   구 기대("전부 NULL")는 폐기됐다 — 라벨 보존 정책과 같은 취지로 사람이 입력한 판정도
        //   작업 결과이므로 신고로 폐기하지 않는다. 해제 후 그대로 이어서 진행한다.
        List<LsDataSrc> frames = txTemplate.execute(s ->
                srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(frames).hasSize(3);
        assertThat(frames).extracting(LsDataSrc::getAnonyInclYn).containsExactly("Y", "N", "Y");
        assertThat(frames).extracting(LsDataSrc::getPsdoInclYn).containsExactly("N", "Y", "Y");
        assertThat(frames).extracting(LsDataSrc::getPrvcInclYn).containsExactly("Y", "Y", "N");

        // ② RAW DE_IDENT_YN='F' 실제 반영 — 신고 트랜잭션이 부모 dirty-update 를 detach 안 시켰음을
        //    실 flush 로 증명(구 리셋 벌크 JPQL 을 되살릴 때 clearAutomatically 회귀를 잡는 축).
        LsDataRaw reloadedRaw = txTemplate.execute(s -> videoRepository.findById(rawSn).orElseThrow());
        assertThat(reloadedRaw.getDeIdntfYn()).isEqualTo("F");

        // ②-1 영상 단위 개인정보 3필드(V163)도 <보존>된다 — 프레임 축과 같은 근거이며 두 축이
        //   비대칭이 되지 않는다. 구 기대(NULL 리셋)의 근거였던 "재판정 대상"은 폐기됐다
        //   (경위는 DeidentReportService 5-1 주석). stale 우려는 신고 구간 export 보류가 막는다.
        assertThat(reloadedRaw.getAnonyInclYn()).isEqualTo("Y");
        assertThat(reloadedRaw.getPsdoInclYn()).isEqualTo("N");
        assertThat(reloadedRaw.getPrvcInclYn()).isEqualTo("N");

        // ③ D-25(2026-07-27 정책 반전) — 라벨은 <b>삭제되지 않고 보존</b>된다.
        //    (구 정책은 전량 삭제였고 이 단언은 isEmpty() 였다. 삭제를 되살리면 이 단언이 RED 가 된다.)
        List<LsDataLbl> remaining = txTemplate.execute(s -> lblRepository.findAllByRawSn(rawSn));
        assertThat(remaining).hasSize(1);
    }

    /**
     * ★ 구 테스트 폐기(2026-08-04) — {@code 개인정보_3필드_리셋이_감사_가능하게_기록된다}.
     *
     * <p>폐기 사유: 신고가 개인정보 3필드를 <b>리셋하지 않게</b> 됐다(사용자 확정 — 라벨 보존 정책과
     * 같은 취지). 리셋이 없으면 "리셋 감사"의 대상도 없다. 구 근거(DEV_FIX-B/M5 — PII 표기를 되돌리는
     * 행위이므로 행 단위 감사가 필요하다, OWASP A09)는 사실이었으나 <b>그 행위 자체가 사라졌다</b>.
     * {@code LS_DATA_LBL_HSTRY}/{@code LS_TASK_EVNT_LOG} 의 {@code PRIVACY_META_RESET} 이벤트 타입과
     * 팩토리는 <b>과거 행 판독을 위해 존치</b>하되 신규 발생은 없다.
     *
     * <p>대체 검증: 신고 후에도 프레임 축 감사 이력이 <b>생성되지 않는다</b>(리셋이 없으므로).
     */
    @Test
    @DisplayName("신고해도_개인정보_리셋_감사이력이_생기지_않는다 (리셋 폐기)")
    void privacyMetaResetIsAuditedPerFrame() {
        // given — 3필드가 채워진 프레임 2건.
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
            return rs;
        });

        List<LsDataSrc> seeded = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        List<Long> withPrivacy = List.of(seeded.get(0).getSrcSn(), seeded.get(1).getSrcSn());

        TokenClaims reviewer = new TokenClaims("1", Role.REVIEWER, Channel.INTERNAL,
                Instant.now().plusSeconds(60));

        // when
        deidentReportService.report(withPrivacy.get(0), "얼굴 미블러 노출", reviewer);

        // then — 리셋이 없으므로 감사 이력도 없다. 값은 그대로 보존된다.
        List<LsDataLblHstry> audits = txTemplate.execute(s -> withPrivacy.stream()
                .flatMap(sn -> lblHstryRepository.findBySrcSnOrderByRegDtDesc(sn).stream())
                .toList());
        assertThat(audits).isEmpty();
        List<LsDataSrc> after = txTemplate.execute(s -> srcRepository.findByRawSnOrderByFrameNoAsc(rawSn));
        assertThat(after).extracting(LsDataSrc::getPrvcInclYn).containsExactly("Y", "Y");
    }
}
