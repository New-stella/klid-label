package kr.co.cudo.authoring.aiserver.config;

import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.service.AiSrvrIdPolicy;
import kr.co.cudo.authoring.aiserver.service.AiSrvrRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 기동 시 노드 원장을 <b>세우고 검증</b>한다. [@design ADR-057] [@req R10]
 *
 * <h3>1. 부트스트랩 — 하위호환의 전부</h3>
 * <p>원장이 비어 있으면 기존 설정값({@link #SRVR_ADDR_CONFIG_KEY})으로 노드 하나를 세운다.
 * 그래야 배포 직후에도 <b>현재 동작이 그대로</b> 이어진다. 마이그레이션으로 시드하지 못하는 이유는
 * 환경마다 주소가 다르고 <b>Flyway 는 애플리케이션 설정을 읽지 못하기</b> 때문이다.
 *
 * <p>설정값은 <b>최초 1회 씨앗</b>일 뿐이며 그 뒤로는 원장이 이긴다 — 둘 다 진실원으로 두면
 * "어느 쪽이 이기는가"가 코드 여기저기에 흩어진다.
 *
 * <p>⚠ <b>그 기본값은 틀려 있을 수 있다.</b> 반입 설정 템플릿이 추론 서버 주소를 loopback 으로
 * 가리켜, 장비를 나눈 구성에서는 반드시 틀린다. 그리고 <b>틀려도 기동과 상태점검은 정상이고
 * 오토라벨링만 조용히 실패한다</b> — 그래서 최초 등록에 경고를 남긴다. 이 경고가 유일한 단서다.
 *
 * <h3>2. 검증 — 경고가 아니라 기동 차단인 이유</h3>
 * <p>식별자가 형식을 어기면 서킷브레이커 이름과 메트릭 라벨이 조용히 어긋나고, 그 어긋남은
 * <b>한참 뒤 대시보드에서야</b> 드러난다. 운영 로그의 경고는 배포 로그에 묻히므로 경고로는 막을 수
 * 없다. {@code QuartzClusteringGuard} 와 <b>같은 강도·구조</b>로 기동 자체를 실패시킨다.
 *
 * <p>DB 체크 제약이 이미 같은 규칙을 거는데도 여기 한 겹을 더 두는 이유는, 제약을 <b>우회해 들어온
 * 행</b>(운영 SQL · 제약 도입 이전 데이터)을 읽는 쪽이 막아야 하기 때문이다. 어느 한 겹도 다른
 * 겹을 대체하지 않는다.
 *
 * <h3>왜 {@code ApplicationRunner} 인가</h3>
 * <p>DB 를 읽어야 하므로 데이터소스·JPA 가 모두 준비된 뒤여야 한다. 이 지점의 예외는 기동을
 * 중단시킨다(fail-closed).
 */
@Slf4j
@Component
public class AiSrvrBootstrapGuard implements ApplicationRunner {

    /** 부트스트랩으로 세우는 노드의 식별자 — 스스로 형식 규약을 지킨다(하이픈 금지). */
    public static final String DEFAULT_SRVR_ID = "default01";

    /** 씨앗이 되는 설정 키 — ★로그에는 <b>값이 아니라 이 키 이름</b>만 남긴다. */
    static final String SRVR_ADDR_CONFIG_KEY = "authoring.integration.ai-server.base-url";

    /** 오류 메시지에 실을 식별자 표기 상한 — 길이가 아니라 「어느 행인지」만 알면 된다. */
    private static final int MESSAGE_ID_MAX_LENGTH = 40;

    private final LsAiSrvrRepository repository;
    private final AiSrvrRegistry registry;
    private final String configuredSrvrAddr;

    public AiSrvrBootstrapGuard(LsAiSrvrRepository repository,
                                AiSrvrRegistry registry,
                                @Value("${authoring.integration.ai-server.base-url}")
                                String configuredSrvrAddr) {
        this.repository = repository;
        this.registry = registry;
        this.configuredSrvrAddr = configuredSrvrAddr;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapIfEmpty();
        // 우리가 방금 세운 노드까지 함께 검증한다 — 스스로 만든 값이 제약에 걸리는 일이 없도록.
        verifyLedger();
    }

    /**
     * 원장이 비어 있을 때만 기존 설정값으로 노드 하나를 세운다.
     *
     * <p><b>트랜잭션을 열지 않는다.</b> 2노드가 동시에 기동해 둘 다 비어 있다고 보면 뒤에 온 쪽이
     * 기본키 충돌을 받는데, 그것은 <b>이미 같은 일이 끝났다</b>는 뜻이라 실패가 아니다. 트랜잭션·
     * 잠금을 도입하는 대신 충돌을 정상 경로로 흡수한다.
     */
    void bootstrapIfEmpty() {
        if (repository.count() > 0) {
            return;
        }
        try {
            repository.save(LsAiSrvr.register(DEFAULT_SRVR_ID, null, configuredSrvrAddr,
                    LsAiSrvr.SrvrType.INFERENCE, LocalDateTime.now()));
        } catch (DataIntegrityViolationException alreadyBootstrapped) {
            // 다른 노드가 먼저 세웠다. 원장은 이미 원하는 상태다.
            log.info("[AiSrvr] 노드 원장이 다른 인스턴스에 의해 이미 등록되어 있습니다. srvrId={}",
                    DEFAULT_SRVR_ID);
            return;
        }
        registry.invalidate();

        // ★주소 「값」을 싣지 않는다 — 내부 토폴로지이고(CWE-497), 확인해야 할 대상은 설정 키다.
        log.warn("[AiSrvr] 노드 원장이 비어 있어 기존 설정값으로 노드 1건을 최초 등록했습니다."
                        + " srvrId={} · 설정키={} — ★주소가 맞는지 확인하세요."
                        + " 추론 서버를 별도 장비로 분리한 구성에서는 이 기본값이 틀릴 수 있고,"
                        + " 틀려도 기동과 상태점검은 정상이며 오토라벨링만 실패합니다.",
                DEFAULT_SRVR_ID, SRVR_ADDR_CONFIG_KEY);
    }

    /** 원장 전 행의 식별자 형식을 확인한다 — 하나라도 어긋나면 기동을 중단시킨다. */
    void verifyLedger() {
        verifySrvrIds(repository.findAll().stream().map(LsAiSrvr::getSrvrId).toList());
    }

    /**
     * 순수 판정 — 컨테이너 없이도 단위 검증할 수 있도록 분리했다.
     *
     * <p>빈 원장은 위반이 아니다(부트스트랩이 채운다). 위반은 <b>전부</b> 알려준다 — 하나씩
     * 알려주면 고치고 다시 기동하기를 반복하게 된다.
     *
     * @throws IllegalStateException 형식을 어긴 식별자가 하나라도 있을 때
     */
    static void verifySrvrIds(Collection<String> srvrIds) {
        List<String> violations = srvrIds.stream()
                .filter(srvrId -> !AiSrvrIdPolicy.isValid(srvrId))
                .map(AiSrvrBootstrapGuard::sanitizeForMessage)
                .toList();
        if (violations.isEmpty()) {
            return;
        }
        throw new IllegalStateException(
                "AI 서버 식별자가 형식(" + AiSrvrIdPolicy.SRVR_ID_REGEX + ")을 위반했습니다: "
                        + violations + "."
                        + " 이 값은 서킷브레이커 이름과 메트릭 라벨로 조립되므로 하이픈·대문자가 섞이면"
                        + " 라벨이 조용히 어긋납니다. 실제 장비 호스트명은 SRVR_NM 에 두고,"
                        + " SRVR_ID 는 소문자·숫자 " + AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH
                        + "자 이내로 바꾸세요.");
    }

    /**
     * 메시지에 실을 식별자를 안전한 표기로 바꾼다.
     *
     * <p>이 값은 <b>DB 에서 온 신뢰할 수 없는 문자열</b>이고 그대로 로그로 나간다. 개행·제어문자가
     * 섞이면 로그 한 줄에 여러 줄이 들어가 기록을 위조할 수 있다(CWE-117).
     */
    private static String sanitizeForMessage(String srvrId) {
        if (srvrId == null) {
            return "(null)";
        }
        String printable = srvrId.replaceAll("[^\\x20-\\x7E]", "?");
        return printable.length() <= MESSAGE_ID_MAX_LENGTH
                ? printable
                : printable.substring(0, MESSAGE_ID_MAX_LENGTH) + "...";
    }
}
