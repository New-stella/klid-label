package kr.co.cudo.authoring.video.service;

import com.zaxxer.hikari.HikariDataSource;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.port.VideoProbe;
import kr.co.cudo.authoring.video.service.port.VideoProbe.VideoMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VideoDurationResolver <b>프로브 커넥션 격리 회귀 방지</b> 통합 테스트 (MEDIUM-1).
 *
 * <p><b>검증 목표(정정):</b> 프로덕션과 동일하게 <b>ambient 트랜잭션 없이</b> {@code resolveDurationSec} 를
 * 호출하면, 최후단 ffprobe 프로브({@link VideoProbe#probe}) 시점에 이 요청이 <b>물리 DB 커넥션을 하나도
 * 점유하지 않는다</b>는 불변식을 검증한다. 값싼 DB read(①②)는 {@link VideoDurationDbReader} 의 짧은
 * {@code REQUIRES_NEW}(readOnly) 트랜잭션으로 수행되어 프로브 이전에 커밋·커넥션 반납되므로, 프로브 진행 중
 * HikariCP <b>활성 커넥션 수(getActiveConnections)가 0</b> 이어야 한다(HikariCP 풀 고갈 방지). 이 격리가
 * 회귀하면(프로브 동안 커넥션을 쥐면) 활성 커넥션이 1 이상으로 감지되어 본 테스트가 실패한다.
 *
 * <p><b>과거 테스트의 과장 정정:</b> 이전 버전은 "활성 tx 안에서 호출해도 프로브는 활성 tx 없이 실행"만
 * 단언({@code isActualTransactionActive()==false})했는데, 이는 <b>tx 동기화 정지</b>만 확인할 뿐
 * <b>물리 커넥션 미점유는 검증하지 못했다</b>(정지된 tx 의 커넥션은 checked-out 유지). 또한 {@code NOT_SUPPORTED}
 * 는 tx 를 정지시킬 뿐 단독으로 커넥션을 반납시키지 않으므로, "커넥션 미점유"는 <b>호출부가 tx 밖</b>일 때
 * 성립한다. 그래서 본 테스트는 프로덕션과 동일한 <b>비트랜잭션 호출</b>로 실제 활성 커넥션 0 을 단언한다.
 *
 * <p>{@link VideoProbe} 는 {@link MockBean} 으로 대체해 실제 ffprobe 바이너리 없이, 프로브 호출 시점의
 * {@code HikariPoolMXBean.getActiveConnections()} 를 기록한다.
 *
 * <p><b>측정 방식 (DEV_FIX — 간헐 실패 제거):</b> 전역 활성 커넥션 수를 <b>절대값 0</b> 으로 단언하면,
 * 같은 풀을 공유하는 다른 백그라운드 작업(스케줄러/비동기 러너/컨텍스트 재사용)이 그 찰나에 커넥션을
 * 체크아웃하기만 해도 회귀가 없는데 실패한다(단독 실행 PASS / 전체 스위트 간헐 FAIL 의 원인). 그래서
 * <b>호출 직전 기준값 대비 증가분</b>을 본다 — 리졸버가 커넥션을 쥐고 프로브에 들어갔다면 활성 수가
 * 기준값보다 <b>반드시 1 이상 늘어난다</b>. 여기에 <b>호출 스레드에 바인딩된 커넥션 홀더가 없다</b>는
 * 결정론적 단언을 더해, 완화된 지표가 회귀를 놓치지 않도록 이중으로 고정한다.
 *
 * <p><b>Quartz 오염 차단:</b> 이 IT 는 {@code controlDataSource}(HikariCP) 의 활성 커넥션 수를
 * 관측하는데, 같은 풀을 Quartz(JDBC JobStore, threadCount 3) 스케줄러가 공유한다. Quartz 스케줄러 스레드가
 * 트리거 획득을 위해 같은 풀에서 커넥션을 체크아웃하는 순간과 프로브 콜백 순간이 겹치면, 회귀가 없어도
 * {@code getActiveConnections()} 가 우연히 1+ 로 관측되어 <b>간헐 실패(flaky)</b> 할 수 있다. 이를 막기 위해
 * 이 IT 한정으로 {@code spring.quartz.auto-startup=false} 를 지정해 스케줄러 기동을 차단한다 —
 * 스케줄러가 시작되지 않으면 {@code QuartzSchedulerThread} 의 트리거 폴링이 실행되지 않아 백그라운드 커넥션
 * 체크아웃이 사라지고, 관측 대상 활성 커넥션은 오직 이 테스트 자신의 사용만 반영한다(결정론적).
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"spring.quartz.auto-startup=false"})
class VideoDurationResolverTxIsolationIT {

    @MockBean
    private VideoProbe videoProbe;

    @Autowired
    private VideoDurationResolver resolver;

    @Autowired
    private VideoRepository videoRepository;

    /** control(HikariCP) 풀의 활성 커넥션 수를 프로브 시점에 관측하기 위한 DataSource. */
    private final HikariDataSource controlHikari;

    /** 시드/정리를 위한 커밋 트랜잭션 템플릿(REQUIRED — 리졸버 호출은 이 밖에서 tx 없이 수행). */
    private final TransactionTemplate txTemplate;

    private Long rawSn;

    VideoDurationResolverTxIsolationIT(
            @Qualifier("controlTransactionManager") PlatformTransactionManager controlTxManager,
            @Qualifier("controlDataSource") DataSource controlDataSource) throws Exception {
        this.txTemplate = new TransactionTemplate(controlTxManager);
        this.controlHikari = controlDataSource.unwrap(HikariDataSource.class);
    }

    @AfterEach
    void tearDown() {
        if (rawSn != null) {
            txTemplate.executeWithoutResult(s -> videoRepository.deleteById(rawSn));
        }
    }

    @Test
    @DisplayName("비트랜잭션_호출_시_ffprobe_프로브_진행중_활성DB커넥션_0_물리커넥션_미점유_보장")
    void probeHoldsNoPhysicalConnection() {
        // given — VDO_LEN_SEC=null·메타 없음 영상을 커밋 시드 → 리졸버가 프로브 폴백까지 도달한다.
        rawSn = txTemplate.execute(s -> {
            LsDataRaw raw = LsDataRaw.createFromIngest(
                    "CLIP-ISO-" + System.nanoTime(), "CCTV-001", "EVT-A", "11680",
                    LsDataRaw.PRVC_TYPE_ANONY, "/var/raw/iso-test.mp4",
                    LocalDateTime.now(), null);
            raw.markDeidentified("Y");
            raw.markMarkingReady();
            return videoRepository.save(raw).getRawSn();
        });

        // 프로브 시점의 활성 커넥션 수 + 스레드 바인딩 커넥션 보유 여부를 기록
        //   (-1 초기값 → 프로브 미호출 시 검증에서 드러남).
        AtomicInteger activeAtProbe = new AtomicInteger(-1);
        AtomicBoolean boundAtProbe = new AtomicBoolean(true);
        when(videoProbe.probe(any())).thenAnswer(inv -> {
            activeAtProbe.set(controlHikari.getHikariPoolMXBean().getActiveConnections());
            boundAtProbe.set(TransactionSynchronizationManager.hasResource(controlHikari));
            return new VideoMeta(1920, 1080, "h264", 30.0, null, 60_000L, null);
        });

        // 사전 조건: 호출부에 ambient 트랜잭션이 없다(프로덕션 비트랜잭션 오케스트레이션과 동일).
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

        // 기준값 — 이 호출과 무관하게 이미 사용 중인 커넥션 수(다른 백그라운드 작업 몫).
        int activeBefore = controlHikari.getHikariPoolMXBean().getActiveConnections();

        // when — ambient tx 없이 리졸버 호출. dbReader 의 REQUIRES_NEW read 는 프로브 이전에 커넥션을 반납해야 한다.
        Integer sec = resolver.resolveDurationSec(rawSn);

        // then — 프로브로 60초 해석 + 프로브 중 이 요청이 물리 커넥션을 <b>추가로 점유하지 않았다</b>.
        assertThat(sec).isEqualTo(60);
        verify(videoProbe).probe(any());
        assertThat(boundAtProbe.get())
                .as("프로브 시점에 호출 스레드가 control DataSource 커넥션을 보유하면 안 된다(결정론적 단언)")
                .isFalse();
        assertThat(activeAtProbe.get())
                .as("ffprobe 프로브 진행 중 이 요청이 점유한 물리 DB 커넥션은 0 이어야 한다"
                        + "(회귀 시 기준값보다 1 이상 증가)")
                .isLessThanOrEqualTo(activeBefore);
    }
}
