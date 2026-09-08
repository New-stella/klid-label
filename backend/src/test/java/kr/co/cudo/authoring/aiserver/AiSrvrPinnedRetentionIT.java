package kr.co.cudo.authoring.aiserver;

import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrAltmnt;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrAltmntRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrBatchAssignment;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ★ <b>고정된 장비를 지금 쓸 수 없을 때만 옮긴다</b> — 정비중과 이용불가를 <b>대칭으로</b> 문다.
 * [@design ADR-057] [@design AC-1100]
 *
 * <h3>왜 이 축의 시험이 필요한가 (2026-09-08 — 이 축의 시험이 0건이었다)</h3>
 * <p>「신규 배정을 받을 수 있는가」와 「이미 붙은 배정을 유지하는가」는 <b>다른 술어</b>인데, 고정 유지
 * 판정이 신규 배정 판정과 <b>같은 후보 목록</b>을 보고 있었다. 그래서 관리자가 장비를 정비로 내리는
 * 순간 그 장비에 고정돼 있던 영상이 곧바로 다른 장비로 재배정됐고, 「신규 배정만 막고 진행 중인 작업은
 * 끝까지」라는 정비중의 정의가 깨졌다.
 *
 * <p>실제 도달 경로는 이렇다 — YOLO 가 gpu01 에 고정 → 관리자가 gpu01 을 정비로 내림 → 이어지는 SAM2 의
 * 목적지 해석이 gpu02 로 옮김 ⇒ 뒤 단계가 앞 단계의 추적 상태를 이어받지 못한다.
 *
 * <h3>대칭으로 무는 이유</h3>
 * <p>한쪽만 물면 반대쪽으로 무너진다. 정비중을 유지하지 않으면 무중단 정비가 깨지고, 이용불가까지
 * 유지하면 그 영상이 <b>영영 죽은 장비에 묶인다</b>(영상당 배정이 한 건이고 자동 재개 장치가 없어
 * 사람이 원장을 직접 고쳐야 풀린다).
 *
 * <p>★ 단위 시험이 아니라 통합으로 두는 이유는 <b>「그 주소를 그대로 쓴다」</b>가 선택기·원장 캐시·배정
 * 저장소를 함께 지나야 성립하기 때문이다. 선택기를 목으로 대체하면 정확히 이번에 깨진 판정이 시험 밖으로
 * 빠진다.
 */
@SpringBootTest
@ActiveProfiles("local")
class AiSrvrPinnedRetentionIT {

    private static final long RAW_SN = 900_301L;

    @Autowired private LsAiSrvrRepository srvrRepository;
    @Autowired private LsAiSrvrAltmntRepository altmntRepository;
    @Autowired private AiSrvrBatchAssignment batchAssignment;
    @Autowired private AiSrvrRegistry registry;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired @Qualifier("controlTransactionManager") private PlatformTransactionManager txManager;

    @BeforeEach
    @AfterEach
    void resetLedger() {
        jdbcTemplate.update("DELETE FROM ls_ai_srvr_altmnt");
        jdbcTemplate.update("DELETE FROM ls_ai_srvr");
        // ★원장 스냅샷은 TTL 5초 캐시다 — 비우지 않으면 방금 지운 장비가 다음 시험에서 되살아난다.
        registry.invalidate();
    }

    @Test
    @DisplayName("★고정_장비가_정비중이면_재배정하지_않고_그_주소를_그대로_쓴다")
    void 고정_장비가_정비중이면_그_주소를_그대로_쓴다() {
        seed("gpu01", "http://gpu1:9300");
        seed("gpu02", "http://gpu2:9300");
        assertThat(batchAssignment.resolveAddress(RAW_SN)).isNotNull();
        String pinned = altmntRepository.findByRawSn(RAW_SN).orElseThrow().getSrvrId();

        // when — 관리자가 그 장비를 정비로 내린다(진행 중인 작업은 끝까지 흘려보내는 상태다)
        drain(pinned);

        // then — 고정이 유지되고 배정 행도 그대로다.
        assertThat(batchAssignment.resolveAddress(RAW_SN))
                .as("정비중은 신규 배정만 막는다 — 붙어 있던 영상까지 옮기면 무중단 정비가 아니다")
                .isEqualTo(addressOf(pinned));
        LsAiSrvrAltmnt after = altmntRepository.findByRawSn(RAW_SN).orElseThrow();
        assertThat(after.getSrvrId()).isEqualTo(pinned);
        assertThat(after.getAltmntRsn())
                .as("옮기지 않았는데 재배정 사유가 남으면 사후 진단이 거짓말을 한다")
                .isNull();
    }

    @Test
    @DisplayName("★고정_장비가_이용불가면_재배정하고_사유를_남긴다")
    void 고정_장비가_이용불가면_재배정한다() {
        seed("gpu01", "http://gpu1:9300");
        seed("gpu02", "http://gpu2:9300");
        assertThat(batchAssignment.resolveAddress(RAW_SN)).isNotNull();
        String pinned = altmntRepository.findByRawSn(RAW_SN).orElseThrow().getSrvrId();

        // when — 상태점검이 그 장비를 이용불가로 내렸다(그 프로세스의 추적 상태는 이미 사라졌다)
        transition(pinned, AiSrvrStatus.UNAVAILABLE);

        // then — 남은 장비로 옮기고 그 사실이 원장에 남는다.
        String moved = batchAssignment.resolveAddress(RAW_SN);
        assertThat(moved).isNotNull().isNotEqualTo(addressOf(pinned));
        LsAiSrvrAltmnt after = altmntRepository.findByRawSn(RAW_SN).orElseThrow();
        assertThat(after.getSrvrId()).isNotEqualTo(pinned);
        assertThat(after.getAltmntRsn())
                .as("옮긴 사실이 원장에 남지 않으면 추적 불연속의 원인을 되짚을 수 없다")
                .isNotBlank()
                .contains(pinned);
    }

    private void seed(String srvrId, String addr) {
        srvrRepository.saveAndFlush(LsAiSrvr.register(srvrId, null, addr,
                LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));
        registry.invalidate();
    }

    private void drain(String srvrId) {
        transition(srvrId, AiSrvrStatus.DRAINING);
    }

    private void transition(String srvrId, AiSrvrStatus next) {
        // ★조건부 UPDATE 는 트랜잭션 안에서만 flush 된다 — 경계를 열지 않으면 상태가 바뀌지 않는다.
        Integer moved = new TransactionTemplate(txManager).execute(status ->
                srvrRepository.transitionStatusFrom(srvrId, AiSrvrStatus.AVAILABLE.name(),
                        next.name(), "it", LocalDateTime.now()));
        assertThat(moved).as("전제 조건: 상태 전이가 실제로 이루어져야 한다").isOne();
        registry.invalidate();
    }

    private String addressOf(String srvrId) {
        return srvrRepository.findById(srvrId).orElseThrow().getSrvrAddr();
    }
}
