package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.event.AugmentRequestedItemEvent;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.service.AugmentCallbackUrlResolver;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.common.util.VisibleTextNormalizer;
import kr.co.cudo.authoring.portal.dto.PortalAugmentCreatedResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentRequest;
import kr.co.cudo.authoring.portal.dto.PortalAugmentSummaryResponse;
import kr.co.cudo.authoring.portal.upload.PortalAugmentRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 포털 채널 증강 — <b>요청 접수 · 외부 위탁 개시 · 현황 목록 · 단건 조회</b>.
 *
 * <h2>관제 증강 창구를 재사용하지 않는다</h2>
 * <table>
 *   <caption>같은 이름의 기능이지만 축이 다르다</caption>
 *   <tr><th>축</th><th>관제</th><th>포털</th></tr>
 *   <tr><td>요청 자격</td><td>검수 완료 영상 · 검수자</td><td><b>본인이 올린 영상</b></td></tr>
 *   <tr><td>결정 단계</td><td>채택/반려</td><td><b>없다</b> — 검수가 없어 그 개념 자체가 없다</td></tr>
 *   <tr><td>대상</td><td>—</td><td>데이터마트에서 불러온 영상은 <b>대상이 아니다</b></td></tr>
 * </table>
 *
 * <p>⚠ <b>관제 경로를 무르게 하지 않는다</b> — 그쪽의 요청 자격(검수 완료)은 그대로 선다. 이 창구가
 * 검수를 묻지 않는 것은 <b>이 경로에 검수가 없기 때문</b>이지 그 조건을 완화한 것이 아니다.
 *
 * <p>⚠ <b>파생 등재 게이트를 이 목록에 재사용하지 않는다</b> — 그 술어는 검수자 결정 행을 요구하는데
 * 이 경로에는 그 행이 <b>영영 생기지 않아</b> 포털 파생본이 전부 사라진다.
 *
 * <h2>데이터마트 로드분이 요청 대상에서 빠지는 방식</h2>
 * <p>별도 제외 목록을 두지 않는다. 대상 조회가 <b>포털 업로드 자산 원장</b>({@code 출처 판별자 +
 * 소유자})만 보므로, 데이터마트에서 불러온 영상은 <b>구조적으로 조회되지 않아</b> 남의 자산·없는
 * 자산과 같은 코드로 거절된다. 제외 목록은 항목이 늘 때마다 갱신돼야 하지만 이 방식은 그렇지 않다.
 *
 * <h2>★ 외부 위탁은 <b>관제 채널이 이미 쓰는 경로를 그대로 재사용</b>한다 (2026-09-03 · ADR-061)</h2>
 * <p>⚠ <b>구 서술 폐기 — 근거가 반대 방향이 됐다.</b> 이 자리에는 <i>"생성 조건 항목·값역과 이벤트
 * 유형의 포털 채널 조달처가 확정돼 있지 않아 접수까지만 한다 — 위탁 이벤트를 발행하지 않는다"</i> 가
 * 적혀 있었다. 그때는 맞았다. 지금은 <b>둘 다 확정됐다</b>:
 * <ul>
 *   <li><b>생성 조건</b> — 다섯 항목 전부 필수 · 닫힌 값역이며 값역의 단일 원천은
 *       {@link AugmentPrompts} 다(ADR-061).</li>
 *   <li><b>이벤트 유형 · 세부 유형 · 증강 종류</b> — 요청자가 고르지 않는다. 이벤트 유형은
 *       <b>위탁 클라이언트가 중립값을 고정 송신</b>하고 세부 유형은 <b>필드 자체가 없으며</b> 증강
 *       종류는 단일 상수다(ADR-059). 그래서 이 서비스가 조달할 것이 애초에 없다.</li>
 * </ul>
 *
 * <p>⇒ 접수 트랜잭션이 <b>커밋된 뒤</b> {@link AugmentRequestedItemEvent} 가 위탁을 개시한다
 * (수신자는 관제 채널과 같은 {@code AugmentRequestBridge}). 커밋 이후로 미루는 것은 요청 행 없이
 * 외부 위탁만 나가는 <b>고아 위탁</b>을 막기 위함이다 — 롤백되면 리스너가 발화하지 않는다.
 *
 * <p>⚠ <b>포털 전용 위탁 경로를 새로 만들지 않는다.</b> 만들면 벤더 계약 조립·멱등 키·분할 위탁·
 * 신고 재판정·회수 스윕이 두 벌이 되어 한쪽만 고쳐진다.
 *
 * <h2>★ 파생 깊이는 1 로 고정한다 — 파생본에서는 어떤 파생도 만들지 않는다</h2>
 * <p>2026-07-31 사용자 확정(구속)이며 <b>채널을 가리지 않는다</b>. 관제 요청 창구는 이미 그 규칙을
 * 지키고 있었고({@code AugmentRequestService.requireNotDerivative} → 400) 이 창구만 비어 있었다 —
 * <b>신규 정책 도입이 아니라 드리프트 정정</b>이다(해상도 변경 경로가 같은 조건에 이미 400 을 쓰는
 * 것과 같은 형태).
 *
 * <p><b>왜 이 창구에서도 필요한가</b>: 증강 결과물은 <b>부모 참조를 가진 공용 영상 원장의 새 행</b>
 * 이다(ADR-058). 그 행에 다시 증강을 걸면 변환이 중첩되고(예: 겨울로 바꾼 것을 다시 야간으로) 라벨도
 * 복사본의 복사본이라 출처 추적이 흐려진다. 깊이를 1 로 고정하면 <b>모든 파생의 부모가 항상 원본</b>
 * 이라 그 문제 자체가 소멸한다.
 *
 * <p>⚠ <b>화면이 막아 줄 것으로 기대하지 말 것</b> — 업로드 목록 응답에는 파생 여부를 가릴 값이
 * <b>하나도 없어</b> 결과물 행에도 요청 버튼이 뜬다. <b>서버 판정이 유일한 방어다.</b>
 *
 * <p>거부는 <b>400</b> 이다 — 기다려도 달라지지 않는 <b>영구</b> 조건이라 재시도 여지가 없다. 비식별
 * 신고 구간의 412(「지금은 안 되지만 해소되면 된다」)와 <b>성질이 다르다</b>. 관제가 같은 조건에 이미
 * 400 을 쓰므로 같은 사유에 다른 코드를 주지 않는다(주면 화면이 두 갈래로 분기해야 한다).

 * <h3>입력 프레임의 조달처는 이 서비스가 정하지 않는다</h3>
 * <p>관제는 비식별본, 포털 업로드 자산은 본인 원본이며 <b>서로 폴백하지 않는다</b>. 그 판정은
 * {@code AugmentInputFrameSource} 하나가 하고 <b>기본값이 비식별본</b>이라, 여기서 출처를 다시
 * 분기하면 그 fail-closed 구조가 무너진다. <b>조달처 분기를 뒤집지 말 것.</b>
 *
 * @design API-231
 * @design API-232
 * @design API-233
 * @design ADR-061
 * @design ADR-059
 * @design ADR-013
 * @design ADR-058
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class PortalAugmentService {

    /**
     * 포털 채널 증강 행의 <b>종류 자리</b>에 담는 값 — 관제 채널과 <b>같은 단일 상수</b>.
     *
     * <h3>왜 채널 판별자가 아니라 이 값인가 (2026-09-03 변경)</h3>
     * <p>구 구현은 이 자리에 <b>채널 판별자</b>를 담고 <i>"종류 축이 뒤에 확정되면 이 값이 아니라 그
     * 축이 들어와야 한다"</i> 고 적어 두었다. 그 축이 확정됐다 — 증강 종류는 <b>단일값</b>이고
     * 요청자가 고르지 않으며 서버가 고정한다(ADR-059). 그래서 그 값이 들어왔다.
     *
     * <p><b>바꾸지 않으면 회수가 통째로 막힌다</b>: 위탁 회수 스윕은 후보를 <b>외부 위탁 종류 목록</b>
     * ({@link AugmentPrompts#EXTERNAL_AUG_TYPES})으로 고른다. 채널 판별자는 그 목록에 없으므로,
     * 위탁도 콜백도 없이 남은 포털 요청을 <b>깨울 주체가 없어</b> 영원히 「기다리는 중」에 고착한다 —
     * 화면의 세 구분 중 <b>실패에 영영 도달하지 못한다</b>.
     *
     * <p><b>이 값이 관제 이력으로 새지 않는다</b>: 관제 증강 이력의 채널 술어는 종류 코드가 아니라
     * <b>부모 영상의 출처 판별자</b>({@code LsDataAugRepository.INTERNAL_CHANNEL})다. 즉 채널을 가르는
     * 축과 종류를 담는 축이 처음부터 다르고, 회귀 가드가 그 사실을 고정한다
     * ({@code PortalAugmentIT.★포털_증강_요청은_관제_증강_이력에_섞이지_않는다}).
     *
     * <p><b>새 채널 판별자를 만들지 않는다</b> — 판별자는 {@code LsDataRaw} 가 단독 소유하고 포털
     * 원장은 위임 별칭만 갖는다(아키텍처 가드).
     */
    static final String PORTAL_AUG_TYPE_CD = LsDataAug.AUG_AUGMENT;

    /**
     * 보관 JSON 최상위 키 — <b>나간 바디와 같은 이름</b>이다.
     *
     * <p>같은 원장 같은 컬럼({@code LS_DATA_AUG.PROMPT_CN})에 두 채널이 함께 앉으므로 보관 모양을
     * 갈라 두면 그 값을 읽는 사람이 채널마다 다른 규칙을 알아야 한다. 위탁 바디의 구조화 생성 조건
     * 키와 같은 이름을 써서 <b>나간 값과 보관값이 같은 모양</b>이 되게 한다.
     */
    static final String STORED_KEY_CONDITION = "mtdt";
    /** @see #STORED_KEY_CONDITION */
    static final String STORED_KEY_PROMPT = "prompt";

    /** 보관 JSON 역직렬화 타입 — 접수 때 쓴 모양 그대로 되읽는다. */
    private static final TypeReference<Map<String, Object>> CONDITION_TYPE = new TypeReference<>() {};

    /** {@code LS_DATA_AUG.PROMPT_CN} 컬럼 폭 — 입구에서 fail-closed 로 확인한다(적재 시점 500 차단). */
    static final int CONDITION_JSON_MAX = 4000;

    private final PortalUploadAssetRepository assetRepository;
    private final PortalUploadFrameRepository frameRepository;
    private final PortalAugmentRepository augmentRepository;
    private final ObjectMapper objectMapper;
    /** 위탁 개시 — 커밋 이후로 미룬다(고아 위탁 방지). 수신자는 관제 채널과 같은 브리지다. */
    private final ApplicationEventPublisher eventPublisher;
    /** 콜백 URL 조립 — 단일 원천(자체 문자열 조립 금지). */
    private final AugmentCallbackUrlResolver callbackUrlResolver;
    /**
     * 파생 여부 판정 — 관제 경로와 <b>같은 원천</b>({@code LS_DATA_RAW.ORGNL_RAW_SN})을 읽는다.
     * 포털 읽기 모델에 파생 축을 새로 달지 않는다 — 그 모델은 응답 조립에 쓰여 축이 하나 늘면
     * 계약면으로 새어 나갈 표면이 생긴다.
     */
    private final VideoRepository videoRepository;

    // ==================================================================
    // 접수
    // ==================================================================

    /**
     * 본인이 올린 준비 완료 영상 한 건에 증강을 요청하고 <b>외부 위탁을 개시</b>한다.
     *
     * <h3>거부 축을 넷으로 가른다</h3>
     * <ul>
     *   <li><b>403</b> — 남의 자산이거나 없는 자산. 두 경우를 가르면 존재 여부가 응답으로 드러난다.
     *       데이터마트에서 불러온 영상도 여기로 떨어진다(포털 업로드 자산이 아니다).</li>
     *   <li><b>400</b> — <b>파생 영상</b>(증강 결과물)이다. 파생 깊이는 1 로 고정된다.</li>
     *   <li><b>400</b> — 생성 조건 항목 누락·값역 밖, 지시문 길이 초과, 또는 자산 종류가 영상이
     *       아니다. 기다려도 달라지지 않는 <b>영구</b> 조건이다.</li>
     *   <li><b>409</b> — 준비가 끝나지 않았다. 기다리면 풀리는 <b>일시</b> 조건이라 재시도가 정상 동선이다.</li>
     * </ul>
     *
     * <h3>평가 순서가 계약이다</h3>
     * <p>생성 조건 검증(순수 입력)을 <b>DB 조회보다 먼저</b> 한다 — 형식이 틀린 요청 하나가 조회를
     * 유발하면 인증 사용자가 반복 호출로 부하를 증폭시킬 수 있다(CWE-770). 반대로 <b>인가(소유자)
     * 조회는 자산 상태·종류 판정보다 먼저</b>다 — 뒤에 두면 응답이 「그 자산이 어떤 상태인가」를
     * 알려주는 오라클이 된다(CWE-209).
     *
     * <h3>응답은 접수 사실이지 결과가 아니다</h3>
     * <p>위탁은 커밋 이후 비동기로 나가므로 이 응답이 확정적으로 말하는 것은 <b>요청을 접수했다</b>
     * 하나다. 결과 도착 여부는 현황 목록·단건 조회에서 확인한다.
     */
    @Transactional("controlTransactionManager")
    public PortalAugmentCreatedResponse request(Long uldSn, PortalAugmentRequest req, TokenClaims actor) {
        String owner = requireOwner(actor);
        RequestPayload payload = buildPayload(req);

        PortalUploadAsset asset = assetRepository.findByOwner(uldSn, owner)
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인 자산이 아니거나 존재하지 않습니다."));

        requireNotDerivative(uldSn);

        if (!PortalUploadLedger.TYPE_VIDEO.equals(asset.uldTypeCd())) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "영상 자산만 증강을 요청할 수 있습니다.");
        }
        if (!asset.isReady()) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다. 완료 후 다시 시도하세요.");
        }
        // 준비 완료는 추출이 끝났다는 뜻이지만, 원장에 남길 기준 프레임이 실제로 있는지 확인한다 —
        // 없으면 요청 행을 만들 수 없다. 상태와 같은 축(기다리면 풀린다)이라 같은 코드로 거절한다.
        Long representativeSrcSn = frameRepository
                .findFirstByRawSnOrderByFrameNoAscSrcSnAsc(uldSn)
                .map(LsDataSrc::getSrcSn)
                .orElseThrow(() -> new CustomException(ErrorCode.CONFLICT,
                        "준비가 끝나지 않은 영상은 증강을 요청할 수 없습니다. 완료 후 다시 시도하세요."));

        String idempotencyKey = "AUG-" + UUID.randomUUID();
        LsDataAug aug = augmentRepository.save(LsDataAug.createRequested(
                representativeSrcSn, PORTAL_AUG_TYPE_CD, owner,
                idempotencyKey, null, payload.storedJson()));

        // 위탁은 <커밋 이후>다 — 요청 트랜잭션이 롤백되면 이 리스너가 발화하지 않아 요청 행 없이
        // 외부 위탁만 나가는 일이 없다. 이벤트가 생성 조건을 <직접 나르는> 것도 계약이다: 위탁은
        // 다른 스레드에서 일어나므로 그쪽이 DB 를 다시 읽어 재조립하면 적재 원문과 나간 값이 두 벌이
        // 되어 갈라진다.
        // 이벤트 유형·세부 유형은 여기서 나르지 않는다 — 위탁 클라이언트가 중립값을 고정 송신하고
        // 세부 유형은 필드 자체가 없다. [design: ADR-059] [design: ADR-061]
        eventPublisher.publishEvent(new AugmentRequestedItemEvent(
                aug.getDataAugSn(), uldSn, PORTAL_AUG_TYPE_CD,
                payload.condition(), payload.promptText(),
                idempotencyKey, callbackUrlResolver.resolve(), owner));

        log.info("[PortalAugment] requested augSn={} uldSn={} srcSn={}",
                aug.getDataAugSn(), uldSn, representativeSrcSn);
        return new PortalAugmentCreatedResponse(aug.getDataAugSn(), uldSn, aug.getRegDt());
    }

    // ==================================================================
    // 조회
    // ==================================================================

    /**
     * 본인이 낸 요청 현황 한 페이지(요청 일시 내림차순 고정).
     *
     * <p>요청이 하나도 없는 것은 정상이다 — 빈 목록을 성공으로 돌려주며 오류로 다루지 않는다.
     *
     * <p><b>실패 사유를 행에 함께 싣는다</b> — 결과 도착 여부 하나로는 대기와 실패가 갈리지 않는다.
     * 화면이 실패를 가려내려고 행마다 단건 조회를 부르지 않게 하기 위한 것이며, 단건 조회와
     * <b>같은 판정기</b>({@link PortalAugmentFailureReason})를 쓴다.
     */
    public Page<PortalAugmentSummaryResponse> list(TokenClaims actor, Pageable pageable) {
        String owner = requireOwner(actor);
        Page<LsDataAug> page = augmentRepository.findPageByOwner(
                owner, PortalUploadLedger.SRC_TYPE, pageable);
        View view = loadView(owner, page.getContent());
        return page.map(aug -> new PortalAugmentSummaryResponse(
                aug.getDataAugSn(),
                view.uldSnOf(aug),
                view.fileNameOf(aug),
                aug.getRegDt(),
                parseCondition(aug),
                aug.getAugProcSttsCd(),
                PortalAugmentFailureReason.of(aug),
                view.resultUldSnOf(aug) != null,
                view.resultArrivedAtOf(aug)));
    }

    /**
     * 본인이 낸 요청 한 건 — 결과물을 가리키는 자산 식별자를 함께 돌려준다.
     *
     * <p>대기와 실패를 빈 응답으로 얼버무리지 않는다. 어느 경우든 결과물 식별자는 비어 있고, 조회
     * 자체는 성공이다.
     */
    public PortalAugmentDetailResponse get(Long augSn, TokenClaims actor) {
        String owner = requireOwner(actor);
        LsDataAug aug = augmentRepository.findByOwner(augSn, owner, PortalUploadLedger.SRC_TYPE)
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인이 낸 요청이 아니거나 존재하지 않습니다."));
        View view = loadView(owner, List.of(aug));
        Long resultUldSn = view.resultUldSnOf(aug);
        return new PortalAugmentDetailResponse(
                aug.getDataAugSn(),
                view.uldSnOf(aug),
                view.fileNameOf(aug),
                aug.getRegDt(),
                parseCondition(aug),
                aug.getAugProcSttsCd(),
                PortalAugmentFailureReason.of(aug),
                resultUldSn != null,
                resultUldSn,
                view.resultArrivedAtOf(aug));
    }

    // ==================================================================
    // 조달 — 한 페이지를 몇 번의 조회로 모은다(행마다 조회하지 않는다)
    // ==================================================================

    /**
     * 목록 한 페이지가 필요로 하는 곁가지 값 묶음.
     *
     * @param uldSnBySrcSn      대표 프레임 → 대상 영상
     * @param fileNameByUldSn   대상 영상 → 표시용 원본 파일명
     * @param arrivedAtByRawSn  결과물 영상 → 등록 일시. <b>이 표에 없는 결과물은 응답에 싣지 않는다</b>
     */
    private record View(Map<Long, Long> uldSnBySrcSn,
                        Map<Long, String> fileNameByUldSn,
                        Map<Long, LocalDateTime> arrivedAtByRawSn) {

        Long uldSnOf(LsDataAug aug) {
            return uldSnBySrcSn.get(aug.getSrcSn());
        }

        String fileNameOf(LsDataAug aug) {
            Long uldSn = uldSnOf(aug);
            return uldSn == null ? null : fileNameByUldSn.get(uldSn);
        }

        /**
         * 결과물 식별자 — <b>그 사용자의 포털 자산으로 확인된 것만</b> 돌려준다.
         *
         * <p>원장에 값이 있어도 그 자산이 이 사용자가 열 수 없는 것이면 식별자를 주지 않는다. 화면은
         * 이 값으로 후속 작업·내려받기 창구를 부르는데, 열 수 없는 식별자를 주면 그 창구에서 거절될
         * 뿐 아니라 <b>존재 자체가 드러난다</b>(CWE-639).
         */
        Long resultUldSnOf(LsDataAug aug) {
            Long newRawSn = aug.getNewRawSn();
            return newRawSn != null && arrivedAtByRawSn.containsKey(newRawSn) ? newRawSn : null;
        }

        LocalDateTime resultArrivedAtOf(LsDataAug aug) {
            Long resultUldSn = resultUldSnOf(aug);
            return resultUldSn == null ? null : arrivedAtByRawSn.get(resultUldSn);
        }
    }

    private View loadView(String owner, List<LsDataAug> augs) {
        if (augs.isEmpty()) {
            return new View(Map.of(), Map.of(), Map.of());
        }
        List<Long> srcSns = augs.stream().map(LsDataAug::getSrcSn).filter(java.util.Objects::nonNull).toList();
        Map<Long, Long> uldSnBySrcSn = new LinkedHashMap<>();
        for (LsDataSrc frame : frameRepository.findAllById(srcSns)) {
            uldSnBySrcSn.put(frame.getSrcSn(), frame.getRawSn());
        }
        Map<Long, String> fileNames = assetRepository.findOriginalFileNames(
                owner, new ArrayList<>(new java.util.LinkedHashSet<>(uldSnBySrcSn.values())));

        List<Long> resultRawSns = augs.stream()
                .map(LsDataAug::getNewRawSn)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<Long, LocalDateTime> arrivedAt = assetRepository.findRegDtByOwner(owner, resultRawSns);
        return new View(uldSnBySrcSn, fileNames, arrivedAt);
    }

    /**
     * 파생 영상(증강 결과물)에서의 증강 요청을 거부한다 — <b>파생 깊이 1 고정</b>(2026-07-31 구속).
     *
     * <p>관제 경로와 <b>같은 판정</b>이다: 부모 참조 컬럼 하나만 본다. 조상 체인을 순회하지 않는다
     * (그 방식은 2026-07-29 에 철회됐고, 깊이를 1 로 고정하면 순회할 이유 자체가 없다).
     *
     * <h3>평가 위치가 계약이다</h3>
     * <p><b>인가(소유자 조회) 뒤</b>여야 한다 — 앞서면 응답이 "그 식별자가 존재하는가/파생인가" 를
     * 알려주는 오라클이 된다(CWE-209). 동시에 <b>상태 판정(409) 앞</b>이어야 한다 — 뒤에 두면
     * 영구 조건인데 "준비가 끝나면 됩니다" 라는 <b>엉뚱한 사유</b>가 먼저 떠서 요청자가 진짜 사유에
     * 영원히 도달하지 못한다.
     *
     * <h3>원본으로 유도하지 않고 부모 식별자도 내려주지 않는다</h3>
     * <p>안내에 부모를 실어 주면 접근 권한이 없을 수 있는 자원의 존재를 알려 주는 셈이 된다
     * (CWE-209/639). 관제 경로의 규칙과 같다.
     *
     * <h3>★ 영상 행을 못 찾으면 <b>여기서 판단하지 않는다</b></h3>
     * <p>이 가드의 책임은 "파생인가" 하나다. 행이 없으면 파생도 아니므로 그대로 통과시키고 뒤 단계가
     * 자기 축으로 거절하게 둔다 — 여기서 예외를 던지면 이미 있는 깊이 2+ 잔존 데이터나 동시 삭제
     * 경합이 <b>다른 사유의 오류로 둔갑</b>한다. <b>신규 생성만 막고 기존 데이터는 정리하지 않는다.</b>
     */
    private void requireNotDerivative(Long uldSn) {
        LsDataRaw video = videoRepository.findById(uldSn).orElse(null);
        if (video == null || video.getOrgnlRawSn() == null) {
            return;
        }
        // 남기는 것은 요청 대상 식별자뿐이다 — 부모 식별자는 로그에도 응답에도 싣지 않는다.
        log.info("[PortalAugment] request blocked — derivative video uldSn={}", uldSn);
        throw new CustomException(ErrorCode.INVALID_INPUT,
                "증강으로 만들어진 영상에는 다시 증강을 요청할 수 없습니다.");
    }

    // ==================================================================
    // 입력
    // ==================================================================

    private static String requireOwner(TokenClaims actor) {
        if (actor == null || actor.sub() == null || actor.sub().isBlank()) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "포털 토큰 미상");
        }
        return actor.sub();
    }

    /**
     * 요청 본문 → <b>외부로 나갈 값</b>과 <b>보관할 문자열</b>.
     *
     * <h3>둘을 <b>한 번에</b> 만드는 것이 계약이다</h3>
     * <p>각각 따로 만들어 넘기면 위탁된 조건과 적재된 조건이 달라져 사후 역추적이 거짓이 된다.
     *
     * <h3>DTO 검증을 여기서 다시 확인하는 이유 (fail-closed)</h3>
     * <p>{@code @NotNull}/{@code @Size} 는 <b>컨트롤러 진입</b>에만 적용된다. 서비스를 직접 부르는
     * 경로(내부 호출·테스트)가 상한을 우회해 컬럼 폭 초과 적재나 무제한 외부 중계로 이어지지 않도록
     * 같은 규칙을 여기서도 확인한다.
     *
     * <p>오류 메시지에는 <b>필드 이름만</b> 싣고 입력값을 되돌려주지 않는다 — 되돌려주면 그 자체가
     * 반사형 노출 경로가 되고, 사용자가 개인정보를 적었을 경우 응답·로그로 번진다(CWE-359).
     */
    private RequestPayload buildPayload(PortalAugmentRequest req) {
        PortalAugmentRequest.GenerationCondition input = req == null ? null : req.generationCondition();
        if (input == null) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 생성 조건(generationCondition)은 필수입니다.");
        }
        Map<String, Object> condition;
        try {
            // 값역의 단일 원천에 조립을 맡긴다 — 키 이름·순서·항목 필수 여부를 여기서 재정의하지 않는다.
            condition = AugmentPrompts.mtdt(input.time(), input.season(),
                    input.weather(), input.terrain(), input.severity());
        } catch (IllegalArgumentException e) {
            // 조립기 예외 <원문>을 응답에 싣지 않는다(CWE-209) — 어느 항목이 비었는지는 DTO 검증이
            // 필드별 메시지로 이미 알려 준다. 여기는 그 검증을 우회한 직접 호출을 막는 자리다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 생성 조건 다섯 항목(time·season·weather·terrain·severity)은 전부 필수입니다.");
        }
        String promptText = normalizePromptText(req.prompt());
        return new RequestPayload(condition, promptText, serialize(condition, promptText));
    }

    /**
     * 자유 지시문 정규화 + 길이 재확인. 값이 남지 않으면 {@code null}(= 미전송).
     *
     * <p>이 값만은 사용자 자유 입력이라 정규화가 필요하다 — 제어문자(개행·탭·NUL)뿐 아니라
     * <b>보이지 않는 문자</b>까지 걷어낸다. 개행이 남으면 ①이 값이 로그에 닿는 순간 로그 위조
     * (CWE-117)가 되고 ②{@code U+0000} 은 드라이버가 거부해 적재가 500 이 된다.
     *
     * <p><b>비어 있으면 {@code null} 이다</b> — 지시문은 <b>선택</b>이므로 보이지 않는 문자만 채운
     * 값이 남았다고 거부하지 않고 "지시문 없음" 으로 취급한다. 다섯 항목과 태도가 다른 것은
     * <b>필수/선택 차이</b>다.
     */
    private static String normalizePromptText(String raw) {
        String normalized = VisibleTextNormalizer.normalizeOrNull(raw);
        if (normalized == null) {
            return null;
        }
        if (normalized.length() > AugmentPrompts.MAX_PROMPT_LENGTH) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "증강 지시문(prompt)이 너무 깁니다(최대 "
                            + AugmentPrompts.MAX_PROMPT_LENGTH + "자).");
        }
        return normalized;
    }

    /**
     * 보관용 JSON 직렬화 — 외부로 나가는 값과 <b>같은 객체</b>에서 만든다(전송본↔저장본 불일치 차단).
     *
     * <p>지시문이 없으면 키 자체를 넣지 않아 나간 바디와 모양이 같아진다.
     *
     * <p>직렬화 실패 원문은 응답에 싣지 않는다(CWE-209).
     */
    private String serialize(Map<String, Object> condition, String promptText) {
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put(STORED_KEY_CONDITION, condition);
        if (promptText != null) {
            stored.put(STORED_KEY_PROMPT, promptText);
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(stored);
        } catch (Exception e) {
            log.warn("[PortalAugment] condition serialization failed cause={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건을 해석할 수 없습니다.");
        }
        if (json.length() > CONDITION_JSON_MAX) {
            // 컬럼 폭 초과를 INSERT 시점 500 으로 흘리지 않고 입구에서 끊는다(fail-closed).
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건이 너무 깁니다.");
        }
        return json;
    }

    /**
     * 생성 조건의 두 표현 + 외부로 나갈 자유 지시문 — 한 쌍으로 묶어 다녀 <b>갈라지지 않게</b> 한다.
     *
     * @param condition  외부 전송·응답 반환용 구조화 생성 조건(불변)
     * @param promptText 외부 전송 자유 지시문(정규화 완료, 없으면 {@code null})
     * @param storedJson {@code LS_DATA_AUG.PROMPT_CN} 보관 문자열
     */
    private record RequestPayload(Map<String, Object> condition, String promptText, String storedJson) {
    }

    /**
     * 보관한 생성 조건을 되읽는다 — 응답에 싣는 것은 <b>다섯 항목</b>이다.
     *
     * <p>읽히지 않으면 <b>빈 객체</b>다 — 목록 한 건의 손상이 페이지 전체를 무너뜨리지 않게 한다
     * (이 값은 표시용이고, 실패를 오류로 올리면 다른 요청까지 볼 수 없다).
     *
     * <p>보관 모양이 <b>감싼 형태</b>({@code {"mtdt":{...},"prompt":"..."}})이므로 그 안쪽을 꺼낸다.
     * 감싸지 않은 값은 이 변경 이전에 적재된 행이므로 <b>그대로</b> 돌려준다 — 못 알아보고 빈 객체로
     * 떨어뜨리면 그 요청의 조건이 화면에서 조용히 사라진다.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseCondition(LsDataAug aug) {
        String stored = aug.getPromptCn();
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = Optional.<Map<String, Object>>ofNullable(
                    objectMapper.readValue(stored, CONDITION_TYPE)).orElse(Map.of());
            Object wrapped = parsed.get(STORED_KEY_CONDITION);
            return wrapped instanceof Map ? (Map<String, Object>) wrapped : parsed;
        } catch (Exception e) {
            log.warn("[PortalAugment] stored condition unreadable augSn={} cause={}",
                    aug.getDataAugSn(), e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
