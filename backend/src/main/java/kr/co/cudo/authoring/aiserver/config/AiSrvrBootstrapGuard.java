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
 * <h3>1. 부트스트랩 — 하위호환의 전부. <b>판정은 유형별</b>이다</h3>
 * <p>어떤 유형의 노드가 하나도 없으면 그 유형의 기존 설정값으로 노드 하나를 세운다 — 추론은
 * {@link #SRVR_ADDR_CONFIG_KEY}, 외부 시계열 분석은 {@link #TIMESERIES_ADDR_CONFIG_KEY} 다.
 * 그래야 배포 직후에도 <b>현재 동작이 그대로</b> 이어진다. 마이그레이션으로 시드하지 못하는 이유는
 * 환경마다 주소가 다르고 <b>Flyway 는 애플리케이션 설정을 읽지 못하기</b> 때문이다.
 *
 * <p>★<b>「원장 전체가 비었는가」로 판정하지 않는다.</b> 전체로 판정하면 추론 노드가 이미 있는
 * 기존 환경(먼저 배포된 로컬·개발계)에 시계열 노드가 <b>영영 심기지 않는다</b> — 그 유형의 위탁이
 * 고를 장비를 못 찾는다. 판정 축은 언제나 <b>그 유형이 비었는가</b>다.
 *
 * <p>설정값은 <b>최초 1회 씨앗</b>일 뿐이며 그 뒤로는 원장이 이긴다 — 둘 다 진실원으로 두면
 * "어느 쪽이 이기는가"가 코드 여기저기에 흩어진다. 이미 그 유형의 노드가 있으면 설정값이 달라도
 * <b>덮어쓰지 않는다</b>. 덮어쓰면 운영자가 관리 화면에서 바꾼 주소가 재기동 한 번에 배포 시점
 * 값으로 되돌아간다.
 *
 * <p>시계열 주소가 <b>비어 있으면 아무것도 심지 않는다</b> — 미연동이 정상인 배포에서 기대되는
 * 모습이고, 값 없이 행을 세우면 존재하지 않는 장비로 위탁이 나간다.
 *
 * <p>⚠ <b>그 기본값은 틀려 있을 수 있다.</b> 반입 설정 템플릿이 추론 서버 주소를 loopback 으로
 * 가리켜, 장비를 나눈 구성에서는 반드시 틀린다. 그리고 <b>틀려도 기동과 상태점검은 정상이고
 * 오토라벨링만 조용히 실패한다</b> — 그래서 최초 등록에 경고를 남긴다. 이 경고가 유일한 단서다.
 *
 * <h3>2. 검증 — 기동을 막지 않는다. 판정은 <b>장비를 고르는 시점</b>에 걸린다 [@design ADR-062]</h3>
 * <p>식별자가 형식을 어기면 서킷브레이커 이름과 메트릭 라벨이 조용히 어긋난다. 그러나 그 어긋남이
 * <b>실제로 일어나는 순간</b>은 기동이 아니라 <b>그 장비를 골라 위탁하는 순간</b>이다 — 고르는
 * 자리에서 위반 행을 빼면 잘못된 식별자가 나갈 길이 구조적으로 없다. 그래서 판정을 <b>없애지 않고
 * 자리만 옮겼다</b>({@link kr.co.cudo.authoring.aiserver.service.AiSrvrSelector}).
 *
 * <p>여기서는 막는 대신 위반을 <b>전부</b> ERROR 로 남긴다 — 「막지 않는다」가 「알리지 않는다」가
 * 되면 안 된다. 하나씩 알려주면 고치고 다시 보기를 반복하게 되므로 한 번에 전부 싣는다.
 *
 * <p>⚠ <b>구 동작 폐기(2026-09-03 확정)</b> — 원장에 형식 위반 행이 하나라도 있으면
 * {@code QuartzClusteringGuard} 와 같은 강도로 <b>기동 자체를 실패</b>시켰다. 설정 한 줄도 아니고
 * <b>운영 데이터 한 행</b>이 앱 전체를 못 뜨게 했다. 되살리지 말 것.
 *
 * <p>DB 체크 제약이 이미 같은 규칙을 거는데도 여기 한 겹을 더 두는 이유는, 제약을 <b>우회해 들어온
 * 행</b>(운영 SQL · 제약 도입 이전 데이터)을 읽는 쪽이 알려야 하기 때문이다. 어느 한 겹도 다른
 * 겹을 대체하지 않는다.
 *
 * <p>★ <b>여기서 낸 판정을 들고 있지 않는다</b> — 원장은 운영 화면에서 <b>런타임에</b> 바뀐다.
 * 기동 시 1회 판정을 캐시하면 뒤에 등록한 정상 장비가 영영 제외되거나 그 반대가 된다. 선택기는
 * <b>고를 때마다</b> 다시 판정한다.
 *
 * <h3>왜 {@code ApplicationRunner} 인가</h3>
 * <p>DB 를 읽어야 하므로 데이터소스·JPA 가 모두 준비된 뒤여야 한다. ⚠ <b>연동 설정·원장 데이터를
 * 이유로는 이 지점에서 기동을 중단시키지 않는다</b>(위 §2).
 */
@Slf4j
@Component
public class AiSrvrBootstrapGuard implements ApplicationRunner {

    /** 부트스트랩으로 세우는 <b>추론</b> 노드의 식별자 — 스스로 형식 규약을 지킨다. */
    public static final String DEFAULT_SRVR_ID = "default01";

    /**
     * 부트스트랩으로 세우는 <b>외부 시계열 분석</b> 노드의 식별자.
     *
     * <p>추론 씨앗과 식별자를 나눈 이유는 기본키가 하나뿐이기 때문만이 아니다 — 두 유형은 고르는
     * 축도 부르는 경로도 달라, 같은 이름을 쓰면 로그·메트릭에서 어느 축의 장비인지 구분되지 않는다.
     */
    public static final String DEFAULT_TIMESERIES_SRVR_ID = "timeseries01";

    /** 씨앗이 되는 설정 키 — ★로그에는 <b>값이 아니라 이 키 이름</b>만 남긴다. */
    static final String SRVR_ADDR_CONFIG_KEY = "authoring.integration.ai-server.base-url";

    /** 외부 시계열 분석 씨앗 설정 키 — 비어 있는 것이 정상인 배포가 있다(미연동). */
    static final String TIMESERIES_ADDR_CONFIG_KEY = "vlm.client.url";

    /** {@code SRVR_ADDR VARCHAR(200)} — 넘치면 INSERT 가 실패해 <b>기동이 통째로 막힌다</b>. */
    private static final int SRVR_ADDR_MAX_LENGTH = 200;

    /** 오류 메시지에 실을 식별자 표기 상한 — 길이가 아니라 「어느 행인지」만 알면 된다. */
    private static final int MESSAGE_ID_MAX_LENGTH = 40;

    private final LsAiSrvrRepository repository;
    private final AiSrvrRegistry registry;
    private final String configuredSrvrAddr;
    private final String configuredTimeseriesAddr;

    public AiSrvrBootstrapGuard(LsAiSrvrRepository repository,
                                AiSrvrRegistry registry,
                                // 키가 없어도 기동을 막지 않는다 — 씨앗은 값이 있을 때만 심는다.
                                //   [design: ADR-062] 연동 주소로 기동을 막지 않는다(2026-09-03 확정).
                                @Value("${authoring.integration.ai-server.base-url:}")
                                String configuredSrvrAddr,
                                // 미연동이 정상인 배포가 있어 <기본값을 빈 값>으로 둔다.
                                // 여기에 기본 주소를 박으면 연동하지 않는 배포에도 노드가 생긴다.
                                @Value("${vlm.client.url:}")
                                String configuredTimeseriesAddr) {
        this.repository = repository;
        this.registry = registry;
        this.configuredSrvrAddr = configuredSrvrAddr;
        this.configuredTimeseriesAddr = configuredTimeseriesAddr;
    }

    @Override
    public void run(ApplicationArguments args) {
        bootstrapIfEmpty();
        // 우리가 방금 세운 노드까지 함께 검증한다 — 스스로 만든 값이 제약에 걸리는 일이 없도록.
        // ★ 이 호출은 기동을 막지 않는다(알리기만 한다). 실제 차단은 장비를 고르는 시점이다.
        verifyLedger();
    }

    /**
     * <b>유형마다</b> 그 유형의 노드가 하나도 없을 때만 기존 설정값으로 노드 하나를 세운다.
     * [@design AC-1092]
     *
     * <p>두 유형을 따로 판정하는 것이 핵심이다 — 원장 전체로 판정하면 추론 노드가 이미 있는 기존
     * 환경에 시계열 노드가 영영 심기지 않는다(클래스 주석 참조).
     */
    void bootstrapIfEmpty() {
        seedIfTypeAbsent(LsAiSrvr.SrvrType.INFERENCE, DEFAULT_SRVR_ID,
                configuredSrvrAddr, SRVR_ADDR_CONFIG_KEY);
        seedIfTypeAbsent(LsAiSrvr.SrvrType.TIMESERIES, DEFAULT_TIMESERIES_SRVR_ID,
                configuredTimeseriesAddr, TIMESERIES_ADDR_CONFIG_KEY);
    }

    /**
     * 한 유형의 씨앗 — 없을 때만, 주소가 있을 때만, 딱 한 건.
     *
     * <p><b>트랜잭션을 열지 않는다.</b> 2노드가 동시에 기동해 둘 다 비어 있다고 보면 뒤에 온 쪽이
     * 기본키 충돌을 받는데, 그것은 <b>이미 같은 일이 끝났다</b>는 뜻이라 실패가 아니다. 트랜잭션·
     * 잠금을 도입하는 대신 충돌을 정상 경로로 흡수한다.
     *
     * <p>주소가 비어 있으면 <b>아무것도 하지 않고 기동은 정상</b>이다 — 미연동이 정상인 배포가 있고,
     * 값 없이 행을 세우면 존재하지 않는 장비로 위탁이 나간다.
     */
    private void seedIfTypeAbsent(LsAiSrvr.SrvrType srvrTypeCd, String srvrId,
                                  String configuredAddr, String configKey) {
        if (!repository.findBySrvrTypeCdOrderBySrvrIdAsc(srvrTypeCd).isEmpty()) {
            // 씨앗은 최초 1회뿐이다. 설정값이 원장을 덮어쓰면 진실원이 둘이 된다.
            return;
        }
        if (configuredAddr == null || configuredAddr.isBlank()) {
            log.info("[AiSrvr] 설정 주소가 비어 있어 노드를 세우지 않습니다."
                    + " srvrTypeCd={} · 설정키={} — 미연동 배포에서는 정상입니다.", srvrTypeCd, configKey);
            return;
        }
        String srvrAddr = configuredAddr.trim();
        if (srvrAddr.length() > SRVR_ADDR_MAX_LENGTH) {
            // ★기동을 죽이지 않는다. 여기서 예외를 내면 <설정 한 줄이 앱 전체를 못 뜨게> 만든다.
            //   심지 않은 사실은 시끄럽게 남기고, 운영자는 관리 창구로 직접 등록하면 된다.
            log.warn("[AiSrvr] 설정 주소가 컬럼 폭({}자)을 넘어 노드를 세우지 않았습니다."
                            + " srvrTypeCd={} · 설정키={} · 길이={} — 관리 창구에서 직접 등록하세요.",
                    SRVR_ADDR_MAX_LENGTH, srvrTypeCd, configKey, srvrAddr.length());
            return;
        }
        try {
            repository.save(LsAiSrvr.register(srvrId, null, srvrAddr, srvrTypeCd, LocalDateTime.now()));
        } catch (DataIntegrityViolationException alreadyBootstrapped) {
            // 다른 노드가 먼저 세웠다. 원장은 이미 원하는 상태다.
            log.info("[AiSrvr] 노드 원장이 다른 인스턴스에 의해 이미 등록되어 있습니다. srvrId={}", srvrId);
            return;
        }
        registry.invalidate();

        // ★주소 「값」을 싣지 않는다 — 내부 토폴로지이고(CWE-497), 확인해야 할 대상은 설정 키다.
        log.warn("[AiSrvr] {} 유형의 노드가 없어 기존 설정값으로 노드 1건을 최초 등록했습니다."
                        + " srvrId={} · 설정키={} — ★주소가 맞는지 확인하세요."
                        + " 서버를 별도 장비로 분리한 구성에서는 이 기본값이 틀릴 수 있고,"
                        + " 틀려도 기동과 상태점검은 정상이며 그 축의 위탁만 실패합니다.",
                srvrTypeCd, srvrId, configKey);
    }

    /**
     * 원장 전 행의 식별자 형식을 확인해 <b>위반을 전부 ERROR 로 알린다</b>. [@design ADR-062]
     *
     * <p>⚠ <b>이름이 남았을 뿐 더 이상 「검증 실패 = 기동 실패」가 아니다</b> — 던지지 않는다.
     *
     * <p><b>기동을 막지 않는다.</b> 막아야 할 것은 잘못된 식별자로 <b>위탁이 나가는 것</b>이고 그
     * 순간은 기동이 아니라 선택 시점이라, 판정은 {@code AiSrvrSelector} 가 <b>고를 때마다</b>
     * 다시 건다. 여기 남는 기록은 「막지 않는다」가 「알리지 않는다」가 되지 않게 하는 유일한 장치다.
     *
     * <p>⚠ 이 메서드의 결과를 <b>어디에도 저장하지 않는다</b> — 원장은 런타임에 바뀌므로 기동
     * 시점의 판정을 들고 있으면 곧 사실과 어긋난다.
     *
     * <p>조회가 실패하면 예외가 그대로 올라간다 — 그건 연동 설정이 아니라 <b>DB 를 못 읽는 상태</b>라
     * 이 결정의 사정거리 밖이다.
     */
    void verifyLedger() {
        List<String> violations =
                srvrIdViolations(repository.findAll().stream().map(LsAiSrvr::getSrvrId).toList());
        if (violations.isEmpty()) {
            return;
        }
        // 위반은 <전부> 싣는다 — 하나씩 알려주면 고치고 다시 보기를 반복하게 된다.
        log.error("[AiSrvr] 원장의 AI 서버 식별자가 형식({})을 위반했습니다: {}."
                        + " 이 값은 기록과 메트릭 라벨에 그대로 실리므로 대문자·공백·제어문자가 섞이면"
                        + " 라벨이 조용히 어긋납니다. 실제 장비 호스트명은 SRVR_NM 에 두고,"
                        + " SRVR_ID 는 소문자·숫자·하이픈·밑줄 {}자 이내로 바꾸세요."
                        + " ★기동은 통과하지만 이 장비들은 위탁 후보에서 제외되며,"
                        + " 그 축에 쓸 수 있는 장비가 하나도 남지 않으면 위탁이 거부됩니다.",
                AiSrvrIdPolicy.SRVR_ID_REGEX, violations, AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH);
    }

    /**
     * 순수 판정 — <b>예외를 던지지 않는다</b>. 컨테이너 없이도 단위 검증할 수 있도록 분리했다.
     *
     * <p>빈 원장은 위반이 아니다(부트스트랩이 채운다). 규칙 자체는 {@link AiSrvrIdPolicy} 가
     * 소유하며 여기서 다시 쓰지 않는다 — 옮겨 적으면 그 사본이 두 번째 진실원이 된다.
     *
     * <p>⚠ <b>구 형태 폐기</b> — 위반이 하나라도 있으면 {@code IllegalStateException} 을 던져
     * 기동을 중단시키던 {@code verifySrvrIds} 가 이 자리에 있었다. 되살리지 말 것([@design ADR-062]).
     *
     * @return 형식을 어긴 식별자의 <b>안전한 표기</b> 목록(위반이 없으면 빈 목록)
     */
    static List<String> srvrIdViolations(Collection<String> srvrIds) {
        return srvrIds.stream()
                .filter(srvrId -> !AiSrvrIdPolicy.isValid(srvrId))
                .map(AiSrvrBootstrapGuard::sanitizeForMessage)
                .toList();
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
