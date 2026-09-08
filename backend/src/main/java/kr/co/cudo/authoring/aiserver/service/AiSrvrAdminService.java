package kr.co.cudo.authoring.aiserver.service;

import kr.co.cudo.authoring.aiserver.dto.AiSrvrCreateRequest;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrLoadResponse;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrResponse;
import kr.co.cudo.authoring.aiserver.dto.AiSrvrUpdateRequest;
import kr.co.cudo.authoring.aiserver.entity.AiSrvrStatus;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvr;
import kr.co.cudo.authoring.aiserver.entity.LsAiSrvrUsg;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrRepository;
import kr.co.cudo.authoring.aiserver.repository.LsAiSrvrUsgRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.webhook.idempotency.LsWebhookIdempotencyRepository;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 장비 원장의 <b>관리 창구</b> 서비스.
 * [@design API-226] [@design API-227] [@design API-228] [@design API-229] [@design API-230]
 * [@design ADR-046] [@design ADR-057] [@design AC-1088] [@design AC-1091]
 *
 * <h3>왜 이 창구가 필요했나</h3>
 * <p>장비 주소의 진실원이 설정값에서 원장으로 옮겨졌는데 <b>그 원장에 행을 넣을 통로가 없었다</b>.
 * 두 번째 장비를 넣으려면 운영자가 DB 에 직접 INSERT 해야 했고, 그러면 식별자 형식·상태 전이 같은
 * 규칙이 통째로 우회된다.
 *
 * <h3>판정을 여기서 다시 쓰지 않는다</h3>
 * <ul>
 *   <li>식별자 형식 — {@link AiSrvrIdPolicy}</li>
 *   <li>주소 허용 범위 — {@link AiSrvrAddressPolicy}(그 뒤는 외부 연동 주소 정책)</li>
 *   <li>상태 전이 가능 여부 — {@link AiSrvrStatus#canTransitionTo(AiSrvrStatus)}</li>
 *   <li>마지막 가용 장비 보호 — 저장소의 <b>조건부 UPDATE·DELETE</b></li>
 * </ul>
 * 넷 중 어느 것도 이 클래스가 소유하지 않는다. 복제하면 사본이 두 번째 진실원이 된다.
 *
 * <h3>★마지막 가용 장비 보호는 「세어 보고 바꾸기」로 만들지 않는다</h3>
 * <p>두 관리자가 같은 유형의 <b>서로 다른</b> 장비를 동시에 내리면 각자 「아직 하나 더 있다」를 보고
 * 둘 다 통과한다. 그래서 판정과 변경이 <b>한 문장</b>에서 일어나야 하며, 이 클래스는 그 문장의
 * 영향 행수(0 = 거부)를 읽어 응답으로 옮길 뿐이다.
 *
 * <p>⚠ 이 보호는 <b>사람의 조작</b>에만 건다. 상태점검 배치의 자동 이용불가 전이는 관측이지 결정이
 * 아니며, 막으면 죽은 장비로 계속 보내게 된다(그 경로는 {@code AiSrvrHealthTxService} 가 따로 쓴다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSrvrAdminService {

    /** {@code MDFR_ID VARCHAR(30)} — 넘치면 INSERT 시점 DB 오류(500)가 되므로 입구에서 자른다. */
    private static final int ACTOR_ID_MAX_LENGTH = 30;

    private final LsAiSrvrRepository repository;
    /** 시계열 축 부하의 원천 — 노드를 고르는 쪽과 <b>같은</b> 원장을 본다. */
    private final LsWebhookIdempotencyRepository submitLedger;
    private final LsAiSrvrUsgRepository usgRepository;
    private final AiSrvrRegistry registry;

    /**
     * 장비 목록 — 유형으로 거를 수 있다. [@design API-226]
     *
     * <p>부하는 노드마다 따로 묻지 않고 <b>한 번에</b> 읽어 붙인다. 노드마다 물으면 목록 한 번이
     * 노드 수만큼의 DB 왕복이 된다.
     *
     * @param srvrTypeCd 유형 필터. {@code null} 이면 전부
     */
    @Transactional(value = "controlTransactionManager", readOnly = true)
    public List<AiSrvrResponse> list(LsAiSrvr.SrvrType srvrTypeCd) {
        List<LsAiSrvr> servers = srvrTypeCd == null
                ? repository.findAllByOrderBySrvrIdAsc()
                : repository.findBySrvrTypeCdOrderBySrvrIdAsc(srvrTypeCd);
        if (servers.isEmpty()) {
            return List.of();
        }
        Map<String, List<AiSrvrLoadResponse>> loads = loadsBySrvrId(servers);
        return servers.stream()
                .map(server -> AiSrvrResponse.of(server,
                        loads.getOrDefault(server.getSrvrId(), List.of())))
                .toList();
    }

    /**
     * 장비 등록 — 상태는 언제나 가용으로 시작한다. [@design API-227] [@design AC-1088]
     *
     * <p>중복 식별자는 409 다. 먼저 조회해 거르되 <b>무결성 위반도 함께 흡수</b>한다 — 두 관리자가
     * 같은 식별자를 동시에 넣으면 조회 시점에는 둘 다 비어 있기 때문이다(조회는 편의이고, 실제
     * 유일성은 기본키가 보장한다).
     */
    @Transactional("controlTransactionManager")
    public AiSrvrResponse register(AiSrvrCreateRequest request, String actorId) {
        String srvrId = request.srvrId();
        // DTO 가 이미 같은 규칙을 걸지만, 서비스가 다른 경로로 불릴 때를 위해 한 겹 더 둔다.
        if (!AiSrvrIdPolicy.isValid(srvrId)) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "장비 식별자는 소문자·숫자·하이픈·밑줄만 " + AiSrvrIdPolicy.SRVR_ID_MAX_LENGTH
                            + "자 이내로 사용할 수 있습니다.");
        }
        AiSrvrAddressPolicy.requireValid(request.srvrAddr());
        if (repository.existsById(srvrId)) {
            throw new CustomException(ErrorCode.CONFLICT, "이미 등록된 장비 식별자입니다.");
        }

        LsAiSrvr server = LsAiSrvr.register(srvrId, blankToNull(request.srvrNm()),
                request.srvrAddr().trim(), request.srvrTypeCd(), LocalDateTime.now());
        server.assignRegistrar(trimActor(actorId));
        try {
            repository.saveAndFlush(server);
        } catch (DataIntegrityViolationException duplicated) {
            // 같은 식별자를 동시에 넣었다. 조회 가드가 진 것이지 결함이 아니다.
            throw new CustomException(ErrorCode.CONFLICT, "이미 등록된 장비 식별자입니다.");
        }
        registry.invalidate();
        log.info("[AiSrvr] 장비를 등록했습니다. srvrId={} srvrTypeCd={}", srvrId, request.srvrTypeCd());
        return AiSrvrResponse.of(server, loadsOf(server));
    }

    /**
     * 이름·주소 수정 — 보내지 않은 항목은 그대로 둔다. [@design API-228]
     *
     * <p>주소를 바꾸면 다음 호출부터 새 주소로 나간다. 짧은 캐시가 남아 있으면 그만큼 늦으므로
     * 여기서 비운다.
     */
    @Transactional("controlTransactionManager")
    public AiSrvrResponse updateProfile(String srvrId, AiSrvrUpdateRequest request, String actorId) {
        LsAiSrvr server = mustFind(srvrId);
        String srvrAddr = blankToNull(request.srvrAddr());
        if (srvrAddr != null) {
            AiSrvrAddressPolicy.requireValid(srvrAddr);
            srvrAddr = srvrAddr.trim();
        }
        server.applyProfileChange(blankToNull(request.srvrNm()), srvrAddr,
                trimActor(actorId), LocalDateTime.now());
        repository.saveAndFlush(server);
        registry.invalidate();
        // ★주소 「값」을 로그에 싣지 않는다 — 내부 토폴로지다(CWE-497).
        log.info("[AiSrvr] 장비 정보를 수정했습니다. srvrId={} nameChanged={} addrChanged={}",
                srvrId, request.srvrNm() != null, srvrAddr != null);
        return AiSrvrResponse.of(server, loadsOf(server));
    }

    /**
     * 상태 전이 — 전이 규칙과 <b>그 유형의</b> 마지막 가용 장비 보호가 함께 걸린다.
     * [@design API-229] [@design AC-1091]
     *
     * <p>거부는 셋 다 409 이지만 <b>사유를 메시지로 가른다</b> — 화면이 「같은 상태로 눌렀다」와
     * 「마지막 하나라 못 내린다」를 구분해 안내해야 하기 때문이다.
     */
    @Transactional("controlTransactionManager")
    public AiSrvrResponse changeStatus(String srvrId, AiSrvrStatus next, String actorId) {
        LsAiSrvr server = mustFind(srvrId);
        AiSrvrStatus current = server.getSrvrSttsCd();
        LsAiSrvr.SrvrType type = server.getSrvrTypeCd();

        if (current == next) {
            // canTransitionTo 도 같은 상태를 거짓으로 돌려주지만, 그 문구로는 사유가 안 보인다.
            throw new CustomException(ErrorCode.CONFLICT, "이미 그 상태입니다.");
        }
        if (!current.canTransitionTo(next)) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "허용되지 않는 상태 전이입니다. " + current + " 에서 " + next + " 로는 바꿀 수 없습니다.");
        }

        String actor = trimActor(actorId);
        LocalDateTime now = LocalDateTime.now();
        int changed = current == AiSrvrStatus.AVAILABLE
                // 가용에서 내려오는 전이 — 판정과 변경이 한 문장에서 일어나야 한다.
                ? repository.demoteIfNotLastAvailableOfType(srvrId, type.name(), next.name(), actor, now)
                // 가용이 아닌 데서 출발하면 가용 수가 줄지 않는다. 출발 상태만 조건으로 못 박는다.
                : repository.transitionStatusFrom(srvrId, current.name(), next.name(), actor, now);

        if (changed == 0) {
            throw new CustomException(ErrorCode.CONFLICT, rejectReason(current, type));
        }
        registry.invalidate();
        log.info("[AiSrvr] 장비 상태를 바꿨습니다. srvrId={} {} -> {}", srvrId, current, next);
        LsAiSrvr after = mustFind(srvrId);
        return AiSrvrResponse.of(after, loadsOf(after));
    }

    /**
     * 장비 삭제 — 그 유형의 마지막 가용 장비는 지울 수 없다. [@design API-230] [@design AC-1091]
     *
     * <p><b>배정 이력은 삭제를 막지 않는다</b>(2026-09-01 사용자 확정 · V26 에서 외래키 제거).
     * 배정 표는 <b>처리가 도는 동안</b> 영상의 프레임을 한 노드에 묶어 두는 자리이고, 그 묶음이
     * 필요한 이유는 추적기 상태가 그 노드 프로세스의 로컬 메모리에 있기 때문이다. 처리가 끝나면
     * 그 메모리는 이미 사라졌으므로 <b>끝난 배정은 순수 이력</b>이다. 그것까지 삭제를 막으면
     * 한 번이라도 영상을 처리한 장비는 <b>영구히 교체 불가</b>가 된다.
     *
     * <p>장비를 지우면 그 이력의 장비 식별자는 <b>이제 없는 장비를 가리키는 값</b>으로 남는데
     * 그것이 의도다 — 어느 장비가 그 영상을 처리했는지는 남아야 한다. 위탁 원장의 장비 식별자가
     * 같은 이유로 처음부터 외래키를 걸지 않은 것과 <b>같은 축</b>이다.
     *
     * <p>⚠ <b>처리 중인 영상의 보호가 사라진 것이 아니다</b> — 그 보호는 외래키가 아니라
     * 정비중(DRAINING)이 담당하고("신규 배정만 막고 진행 중인 배정은 끝까지 간다"), 그 위에
     * 유형별 마지막 가용 장비 보호가 삭제·활성 이탈 양쪽을 따로 막는다.
     */
    @Transactional("controlTransactionManager")
    public void delete(String srvrId) {
        LsAiSrvr server = mustFind(srvrId);
        int deleted = repository.deleteIfNotLastAvailableOfType(srvrId, server.getSrvrTypeCd().name());
        if (deleted == 0) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "그 유형의 마지막 가용 장비라 삭제할 수 없습니다. 다른 장비를 먼저 등록하세요.");
        }
        registry.invalidate();
        log.info("[AiSrvr] 장비를 삭제했습니다. srvrId={} srvrTypeCd={}", srvrId, server.getSrvrTypeCd());
    }

    // ---------------------------------------------------------------------------------------

    private LsAiSrvr mustFind(String srvrId) {
        return repository.findById(srvrId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "등록되지 않은 장비입니다."));
    }

    /**
     * 조건부 문장이 0 행을 돌려준 사유 — <b>추측이 아니라 재조회로</b> 판정한다.
     *
     * <p>가용에서 출발한 전이가 막히는 사유는 둘이다: 그 유형의 마지막 가용 장비였거나, 그 사이 누가
     * 상태를 바꿨거나. 둘을 같은 문구로 묶으면 운영자가 「다른 장비를 먼저 등록하라」는 잘못된 안내를
     * 받는다.
     */
    private String rejectReason(AiSrvrStatus current, LsAiSrvr.SrvrType type) {
        if (current != AiSrvrStatus.AVAILABLE) {
            return "그 사이 장비 상태가 바뀌었습니다. 목록을 새로 고친 뒤 다시 시도하세요.";
        }
        long remaining = repository.findBySrvrTypeCdOrderBySrvrIdAsc(type).stream()
                .filter(node -> node.getSrvrSttsCd() == AiSrvrStatus.AVAILABLE)
                .count();
        return remaining <= 1
                ? "그 유형의 마지막 가용 장비라 상태를 내릴 수 없습니다. 다른 장비를 먼저 등록하세요."
                : "그 사이 장비 상태가 바뀌었습니다. 목록을 새로 고친 뒤 다시 시도하세요.";
    }

    private Map<String, List<AiSrvrLoadResponse>> loadsBySrvrId(List<LsAiSrvr> servers) {
        Map<String, List<AiSrvrLoadResponse>> loads = new java.util.HashMap<>();

        // 추론 축 — 폴러가 채운 용도별 관측표. 목록 1회 조회로 끝낸다(장비마다 부르면 N+1).
        List<String> inferenceIds = servers.stream()
                .filter(s -> s.getSrvrTypeCd() != LsAiSrvr.SrvrType.TIMESERIES)
                .map(LsAiSrvr::getSrvrId)
                .toList();
        if (!inferenceIds.isEmpty()) {
            loads.putAll(usgRepository.findBySrvrIdIn(inferenceIds).stream()
                    .sorted(Comparator.comparing(usg -> usg.getUsgTypeCd().name()))
                    .collect(Collectors.groupingBy(LsAiSrvrUsg::getSrvrId,
                            Collectors.mapping(AiSrvrLoadResponse::from, Collectors.toList()))));
        }

        // 시계열 축 — 우리 원장의 미결 위탁 수. 노드를 고르는 쪽과 같은 원천이다(위 loadsOf 참조).
        List<String> timeseriesIds = servers.stream()
                .filter(s -> s.getSrvrTypeCd() == LsAiSrvr.SrvrType.TIMESERIES)
                .map(LsAiSrvr::getSrvrId)
                .toList();
        if (!timeseriesIds.isEmpty()) {
            Map<String, Integer> outstanding = outstandingSubmitsOf(timeseriesIds);
            for (String srvrId : timeseriesIds) {
                loads.put(srvrId, List.of(AiSrvrLoadResponse.outstandingSubmits(
                        outstanding.getOrDefault(srvrId, 0))));
            }
        }
        return loads;
    }

    /**
     * 장비별 미결 위탁 수 — 집계에 없는 장비는 0 건이다({@code GROUP BY} 는 행이 없는 장비를
     * 돌려주지 않는다). 조회가 실패해도 <b>목록 자체를 실패로 만들지 않는다</b> — 부하는 참고
     * 수치이고, 그것 때문에 장비 목록을 못 보면 장애 중에 손쓸 방법이 사라진다.
     */
    private Map<String, Integer> outstandingSubmitsOf(List<String> srvrIds) {
        try {
            Map<String, Integer> counts = new java.util.HashMap<>();
            submitLedger.countAcceptedBySrvrId(srvrIds).forEach(row ->
                    counts.put(row.getSrvrId(),
                            (int) Math.min(row.getLoadCount(), Integer.MAX_VALUE)));
            return counts;
        } catch (RuntimeException e) {
            log.warn("[AiSrvr] 시계열 부하 집계에 실패해 0 으로 봅니다. cause={}",
                    e.getClass().getSimpleName());
            return Map.of();
        }
    }

    /**
     * 그 장비의 부하 — <b>축마다 원천이 다르다</b>. [@design API-226]
     *
     * <p>추론은 폴러가 상대를 찔러 채운 용도별 관측표를 읽고, 시계열은 <b>우리 원장의 미결 위탁
     * 수</b>를 센다. 시계열이 다른 이유는 그 축이 위탁 후 콜백이라 <b>상대의 큐를 볼 수 없기</b>
     * 때문이다 — 폴러도 시계열 노드를 찌르지 않는다.
     *
     * <p>★<b>노드를 고르는 쪽과 같은 원천을 쓴다.</b> 다른 것을 읽으면 화면이 라우팅과 다른 수치를
     * 보여 주는데, 그 어긋남은 아무 오류도 내지 않아 <b>사람이 화면을 믿고 잘못된 판단</b>을 하기
     * 전까지 드러나지 않는다. 실제로 이 창구는 처음에 시계열까지 관측표에서 읽어 <b>늘 「관측 없음」</b>
     * 이 나올 뻔했다.
     */
    private List<AiSrvrLoadResponse> loadsOf(LsAiSrvr server) {
        if (server.getSrvrTypeCd() == LsAiSrvr.SrvrType.TIMESERIES) {
            return List.of(AiSrvrLoadResponse.outstandingSubmits(
                    outstandingSubmitsOf(List.of(server.getSrvrId()))
                            .getOrDefault(server.getSrvrId(), 0)));
        }
        return usgRepository.findBySrvrId(server.getSrvrId()).stream()
                .sorted(Comparator.comparing(usg -> usg.getUsgTypeCd().name()))
                .map(AiSrvrLoadResponse::from)
                .toList();
    }

    /**
     * 결과를 기다리고 있는 위탁 건수. 집계에 없으면 0 건이다({@code GROUP BY} 는 행이 없는 장비를
     * 돌려주지 않는다). 조회가 실패해도 <b>목록 자체를 실패로 만들지 않는다</b> — 부하는 참고 수치이고,
     * 그것 때문에 장비 목록을 못 보면 장애 중에 손쓸 방법이 사라진다.
     */


    /** 빈 문자열은 「지운다」가 아니라 「안 보냈다」로 읽는다 — 부분 수정 창구의 규약이다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 감사 주체는 인증에서만 온다. 컬럼 폭을 넘으면 자른다(넘쳐서 500 이 되지 않도록). */
    private static String trimActor(String actorId) {
        if (actorId == null || actorId.isBlank()) {
            return null;
        }
        String trimmed = actorId.trim();
        return trimmed.length() <= ACTOR_ID_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, ACTOR_ID_MAX_LENGTH);
    }
}
