package kr.co.cudo.authoring.portal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.portal.dto.PortalAugmentCreatedResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentDetailResponse;
import kr.co.cudo.authoring.portal.dto.PortalAugmentRequest;
import kr.co.cudo.authoring.portal.dto.PortalAugmentSummaryResponse;
import kr.co.cudo.authoring.portal.upload.PortalAugmentRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadAsset;
import kr.co.cudo.authoring.portal.upload.PortalUploadAssetRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadFrameRepository;
import kr.co.cudo.authoring.portal.upload.PortalUploadLedger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * 포털 채널 증강 — <b>요청 접수 · 현황 목록 · 단건 조회</b>.
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
 * <p>⚠ <b>파생 등재 게이트를 이 목록에 재사용하지 않는다</b> — 그 술어는 검수자 결정 행을 요구하는데
 * 이 경로에는 그 행이 <b>영영 생기지 않아</b> 포털 파생본이 전부 사라진다.
 *
 * <h2>데이터마트 로드분이 요청 대상에서 빠지는 방식</h2>
 * <p>별도 제외 목록을 두지 않는다. 대상 조회가 <b>포털 업로드 자산 원장</b>({@code 출처 판별자 +
 * 소유자})만 보므로, 데이터마트에서 불러온 영상은 <b>구조적으로 조회되지 않아</b> 남의 자산·없는
 * 자산과 같은 코드로 거절된다. 제외 목록은 항목이 늘 때마다 갱신돼야 하지만 이 방식은 그렇지 않다.
 *
 * <h2>★ 아직 외부로 나가지 않는다 (설계가 정하지 않은 축)</h2>
 * <p>{@code API-231} 이 <i>"증강 종류 목록과 <b>외부 연동 지점</b>은 확정된 바가 없어 이 산출물에서
 * 정하지 않았다"</i> 고 적었고, 벤더 계약이 요구하는 <b>이벤트 유형</b>과 <b>생성 조건 항목·값역</b>의
 * 포털 채널 조달처가 어디에도 확정돼 있지 않다. 그래서 이 서비스는 <b>접수까지만</b> 한다 —
 * 위탁 이벤트를 발행하지 않는다.
 *
 * <p>지어내서 내보내면 두 가지가 동시에 일어난다: ①벤더가 거부해 확정 실패가 쌓이고 ②지어낸 값역이
 * 사실상의 계약이 되어 나중에 되돌리기 어려워진다. 접수 창구가 확정적으로 말하는 것은 원래부터
 * <b>「요청을 접수했다」</b> 하나이므로 이 범위는 계약과 어긋나지 않는다.
 *
 * @design API-231
 * @design API-232
 * @design API-233
 * @design ADR-013
 * @design ADR-058
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class PortalAugmentService {

    /**
     * 포털 채널 증강 행의 <b>종류 자리</b>에 담는 값.
     *
     * <h3>왜 새 코드를 만들지 않는가</h3>
     * <p>화면 사양({@code SCREEN-044})의 요청 현황 목록에는 <b>증강 종류 열이 없다</b> — 이 채널에서
     * 요청을 구분하는 축은 종류가 아니라 <b>생성 조건</b>이다. 즉 종류 축이 존재하지 않는데 원장 컬럼은
     * 값을 요구하므로(NOT NULL), <b>이미 있는 채널 판별자를 그대로</b> 담는다. 새 상수를 지어내면
     * 결정된 적 없는 값역이 생긴다.
     *
     * <p>이 값이 안전한 이유: ①관제 증강 3종·해상도 파생 접두와 겹치지 않아 회수 스윕·화면 계약값
     * 목록 어디에도 걸리지 않는다 ②경로 조각으로 쓰여도 안전한 토큰이다.
     *
     * <p>판별자 값을 여기서 재선언하지 않고 <b>포털 채널 소유자</b>({@link PortalUploadLedger#SRC_TYPE})를
     * 참조한다 — 채널 판별자 단일 원천 가드가 요구하는 형태다.
     *
     * <p>⚠ 종류 축이 뒤에 확정되면 이 값이 아니라 <b>그 축</b>이 들어와야 한다.
     */
    static final String PORTAL_AUG_TYPE_CD = PortalUploadLedger.SRC_TYPE;

    /** 보관 JSON 역직렬화 타입 — 접수 때 쓴 모양 그대로 되읽는다. */
    private static final TypeReference<Map<String, Object>> CONDITION_TYPE = new TypeReference<>() {};

    /** {@code LS_DATA_AUG.PROMPT_CN} 컬럼 폭 — 입구에서 fail-closed 로 확인한다(적재 시점 500 차단). */
    static final int CONDITION_JSON_MAX = 4000;

    private final PortalUploadAssetRepository assetRepository;
    private final PortalUploadFrameRepository frameRepository;
    private final PortalAugmentRepository augmentRepository;
    private final ObjectMapper objectMapper;

    // ==================================================================
    // 접수
    // ==================================================================

    /**
     * 본인이 올린 준비 완료 영상 한 건에 증강을 요청한다.
     *
     * <h3>거부 축을 셋으로 가른다</h3>
     * <ul>
     *   <li><b>403</b> — 남의 자산이거나 없는 자산. 두 경우를 가르면 존재 여부가 응답으로 드러난다.
     *       데이터마트에서 불러온 영상도 여기로 떨어진다(포털 업로드 자산이 아니다).</li>
     *   <li><b>400</b> — 자산 종류가 영상이 아니다. 기다려도 달라지지 않는 <b>영구</b> 조건이다.</li>
     *   <li><b>409</b> — 준비가 끝나지 않았다. 기다리면 풀리는 <b>일시</b> 조건이라 재시도가 정상 동선이다.</li>
     * </ul>
     *
     * <h3>평가 순서가 계약이다</h3>
     * <p>생성 조건 검증(순수 입력)을 <b>DB 조회보다 먼저</b> 한다 — 형식이 틀린 요청 하나가 조회를
     * 유발하면 인증 사용자가 반복 호출로 부하를 증폭시킬 수 있다(CWE-770). 반대로 <b>인가(소유자)
     * 조회는 자산 상태·종류 판정보다 먼저</b>다 — 뒤에 두면 응답이 「그 자산이 어떤 상태인가」를
     * 알려주는 오라클이 된다(CWE-209).
     */
    @Transactional("controlTransactionManager")
    public PortalAugmentCreatedResponse request(Long uldSn, PortalAugmentRequest req, TokenClaims actor) {
        String owner = requireOwner(actor);
        String conditionJson = serializeCondition(req);

        PortalUploadAsset asset = assetRepository.findByOwner(uldSn, owner)
                .orElseThrow(() -> new CustomException(ErrorCode.FORBIDDEN,
                        "본인 자산이 아니거나 존재하지 않습니다."));

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

        LsDataAug aug = augmentRepository.save(LsDataAug.createRequested(
                representativeSrcSn, PORTAL_AUG_TYPE_CD, owner,
                "AUG-" + UUID.randomUUID(), null, conditionJson));

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
                null,
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
     * 생성 조건을 <b>받은 그대로</b> 보관 문자열로 만든다.
     *
     * <p>항목·값역을 판정하지 않는다(설계가 정하지 않았다). 판정하는 것은 두 가지뿐이다 —
     * <b>비어 있지 않은가</b>(비면 같은 영상의 요청들을 구분할 축이 사라진다)와 <b>컬럼 폭 안인가</b>
     * (넘치면 적재 시점 DB 오류로 500 이 된다).
     *
     * <p>직렬화 실패 원문은 응답에 싣지 않는다(CWE-209).
     */
    private String serializeCondition(PortalAugmentRequest req) {
        Map<String, Object> condition = req == null ? null : req.generationCondition();
        if (condition == null || condition.isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건은 비워둘 수 없습니다.");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(condition);
        } catch (Exception e) {
            log.warn("[PortalAugment] condition serialization failed cause={}", e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건을 해석할 수 없습니다.");
        }
        if (json.length() > CONDITION_JSON_MAX) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "증강 생성 조건이 너무 깁니다.");
        }
        return json;
    }

    /**
     * 보관한 생성 조건을 되읽는다. 읽히지 않으면 <b>빈 객체</b>다 — 목록 한 건의 손상이 페이지 전체를
     * 무너뜨리지 않게 한다(이 값은 표시용이고, 실패를 오류로 올리면 다른 요청까지 볼 수 없다).
     */
    private Map<String, Object> parseCondition(LsDataAug aug) {
        String stored = aug.getPromptCn();
        if (stored == null || stored.isBlank()) {
            return Map.of();
        }
        try {
            return Optional.<Map<String, Object>>ofNullable(
                    objectMapper.readValue(stored, CONDITION_TYPE)).orElse(Map.of());
        } catch (Exception e) {
            log.warn("[PortalAugment] stored condition unreadable augSn={} cause={}",
                    aug.getDataAugSn(), e.getClass().getSimpleName());
            return Map.of();
        }
    }
}
