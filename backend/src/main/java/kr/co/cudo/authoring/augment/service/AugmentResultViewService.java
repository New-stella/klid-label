package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentFramePairResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultItemResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import kr.co.cudo.authoring.video.util.AugTypeParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 증강 작업 결과 본문 조회 — {@code GET /v1/augments/{jobId}/result} 의 {@code results[]} 구성.
 *
 * <h3>범위 — 해상도 파생(RESL_*)만</h3>
 * 외부 위탁 증강(WINTER/NIGHT/RAIN)의 프레임 쌍은 외부 SFR-07 연동 이후 별도로 채워진다. 여기서는
 * <b>저작도구가 직접 생성한 해상도 파생</b>만 다뤄, 구 구현이 {@code List.of()} 로 하드코딩해
 * "비교 이미지가 하나도 안 나오던" 결함을 해소한다. 외부 증강 잡은 기존과 동일하게 빈 목록이다(회귀 0).
 *
 * <h3>부모↔파생 프레임 조인 = {@code (RAW_SN, FRM_NO)} 동등 조인</h3>
 * <p><b>전제</b>: 파생 프레임은 {@code ResolutionSnapshot.FrameSpec} 이 부모의 {@code frameNo} 를 그대로
 * 들고 와 {@code LsDataSrc.create(newRawSn, f.frameNo(), …)} 로 심으므로 <b>파생 프레임의 {@code FRM_NO}
 * 는 부모와 항상 동일</b>하다.
 *
 * <p><b>⚠ 리스크(후속 과제)</b>: {@code ResolutionPersistService.insertFrames} 가 만드는
 * {@code parentSrcSn → newSrcSn} 매핑은 <b>메서드 로컬 변수로 소멸</b>하며 어느 테이블에도 영속되지
 * 않는다({@code LS_DATA_AUG_LBL_MAP} 은 <b>라벨 PK</b> 매핑이지 프레임 매핑이 아니다). 따라서 이 조인은
 * "파생 추출이 부모 {@code FRM_NO} 를 보존한다"는 <b>구현 규약</b> 위에 서 있고, 향후 파생 프레임 추출
 * 순서·로직이 바뀌면 <b>조용히 어긋난다</b>. 프레임 매핑 영속(전용 컬럼/테이블)은 DB 스키마 변경이
 * 필요하므로 후속 과제로 남긴다. 그때까지는 <b>인덱스 zip 이 아닌 명시 키 매칭</b>으로, 짝이 없는
 * 프레임은 쌍을 만들지 않는다(엉뚱한 프레임이 쌍이 되는 것보다 누락이 안전).
 *
 * <h3>보안</h3>
 * <ul>
 *   <li><b>비식별 신고 게이트(CWE-359)</b> — 진입 시 {@link DeidentReportGate} 로 원본(부모)을 판정하고
 *       걸리면 412. 파생영상은 <b>각자의 행</b>으로 판정해(원본 신고는 파생에 전파되지 않는다 —
 *       2026-07-29 확정 정책) 신고된 파생만 쌍에서 제외한다.</li>
 *   <li><b>원본(PII) 경로 미노출(CWE-209)</b> — 응답에 파일 경로를 담지 않는다. 좌/우 모두
 *       {@code /v1/frames/{srcSn}/deid-image}(비식별 전용 서빙 API) 경로만 반환한다.</li>
 *   <li><b>입력 검증(CWE-20)</b> — page/size 범위를 조회 이전에 검증한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class AugmentResultViewService {

    /** 프레임 쌍 기본 페이지 크기 — FE 결과 화면(FrameGrid12) 기본 12장. */
    public static final int DEFAULT_FRAME_PAIR_SIZE = 12;
    /** 프레임 쌍 페이지 크기 상한 — {@code rules/api-design.md} 페이징 규약(max 100). */
    public static final int MAX_FRAME_PAIR_SIZE = 100;

    /** 비식별 프레임 이미지 서빙 API — 좌/우 이미지 모두 이 경로로만 노출한다(경로 문자열 미노출). */
    private static final String DEID_IMAGE_URL_FORMAT = "/v1/frames/%d/deid-image";

    /** 화면 표시 순서 — 고해상도 → 저해상도. */
    private static final List<String> RESOLUTION_DISPLAY_ORDER = List.of(
            LsDataAug.AUG_RESL_1080P, LsDataAug.AUG_RESL_720P, LsDataAug.AUG_RESL_480P);

    private static final String CCTV_NAME_FALLBACK = "(이름 없음)";

    private static final String MESSAGE =
            "해상도 파생 결과는 원본 비식별 프레임과 파생 프레임을 쌍으로 제공합니다. "
                    + "외부 위탁 증강(WINTER/NIGHT/RAIN)의 프레임별 결과는 외부 SFR-07 연동 이후 제공됩니다.";

    private final LsDataAugRepository augRepository;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final AugmentReviewService reviewService;
    private final DeidentReportGate deidentReportGate;

    /**
     * 증강 작업 결과 본문 조회.
     *
     * @param jobId 증강 jobId (= 원본 RAW_SN)
     * @param page  프레임 쌍 페이지 번호 (0-based)
     * @param size  프레임 쌍 페이지 크기 (1..{@value #MAX_FRAME_PAIR_SIZE})
     * @throws CustomException 400 page/size 범위 위반, 412 비식별 누락 신고 구간
     */
    public AugmentResultResponse result(Long jobId, int page, int size) {
        validatePaging(page, size);

        String status = reviewService.aggregateResultStatus(jobId);
        if (jobId == null) {
            return new AugmentResultResponse(null, status, List.of(), MESSAGE, page, size);
        }

        // H1 (CWE-359) — 비식별 누락 신고 구간이면 결과 본문(프레임 쌍 = PII 위치 단서)을 노출하지 않는다.
        //                라벨 조회와 동일 정책(412). 파생영상은 아래에서 각자 판정한다.
        if (deidentReportGate.isUnderDeidentReport(jobId)) {
            log.warn("[Augment] result blocked — deident report open jobId={}", jobId);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상은 증강 결과를 조회할 수 없습니다.");
        }

        List<LsDataAug> generated = generatedResolutionAugs(jobId);
        if (generated.isEmpty()) {
            return new AugmentResultResponse(jobId, status, List.of(), MESSAGE, page, size);
        }

        Map<String, Long> derivativeRawSnByType = resolveDerivativeRawSns(jobId);
        Pageable pageable = PageRequest.of(page, size);

        // 1차 — 유형별 파생 프레임 슬라이스를 모으고(유형당 1 페이지 쿼리), FRM_NO 집합을 합친다.
        Map<String, DerivativeSlice> slices = new LinkedHashMap<>();
        Set<Long> frameNos = new LinkedHashSet<>();
        for (LsDataAug aug : generated) {
            Long derivativeRawSn = derivativeRawSnByType.get(aug.getAugTypeCd());
            if (derivativeRawSn == null) {
                log.warn("[Augment] resolution derivative video not found jobId={} type={}",
                        jobId, sanitize(aug.getAugTypeCd()));
                continue;
            }
            if (deidentReportGate.isUnderDeidentReport(derivativeRawSn)) {
                log.warn("[Augment] derivative result withheld — deident report open rawSn={}", derivativeRawSn);
                continue;
            }
            // 페이징 단위 = "쌍이 성립하는 파생 프레임". 파생 프레임 전체를 페이징하면 부모 비식별 경로가
            // 없는 프레임이 표시 단계에서 드롭돼 총량(페이저·"총 처리 이미지" 표기)과 실제 카드 수가
            // 어긋난다(빈 페이지·과대 표기).
            Page<LsDataSrc> framePage =
                    srcRepository.findPairableDerivativeFrames(derivativeRawSn, jobId, pageable);
            slices.put(aug.getAugTypeCd(), new DerivativeSlice(aug, derivativeRawSn, framePage));
            framePage.getContent().forEach(f -> frameNos.add(f.getFrameNo()));
        }
        if (slices.isEmpty()) {
            return new AugmentResultResponse(jobId, status, List.of(), MESSAGE, page, size);
        }

        // 2차 — 부모 프레임을 (RAW_SN, FRM_NO) 배치 IN 조회 1회로 해결한다(H4 N+1 회피).
        Map<Long, Long> parentSrcSnByFrameNo = loadParentSrcSnByFrameNo(jobId, frameNos);
        String cctvName = resolveCctvName(jobId);

        List<AugmentResultItemResponse> results = new ArrayList<>(slices.size());
        for (DerivativeSlice slice : slices.values()) {
            results.add(toResultItem(jobId, cctvName, slice, parentSrcSnByFrameNo));
        }
        return new AugmentResultResponse(jobId, status, results, MESSAGE, page, size);
    }

    /** 파생 프레임 슬라이스 — 유형별 1건. */
    private record DerivativeSlice(LsDataAug aug, Long derivativeRawSn, Page<LsDataSrc> framePage) {
    }

    private AugmentResultItemResponse toResultItem(Long jobId, String cctvName, DerivativeSlice slice,
                                                   Map<Long, Long> parentSrcSnByFrameNo) {
        List<AugmentFramePairResponse> pairs = new ArrayList<>();
        int unmatched = 0;
        for (LsDataSrc derivativeFrame : slice.framePage().getContent()) {
            Long parentSrcSn = parentSrcSnByFrameNo.get(derivativeFrame.getFrameNo());
            if (parentSrcSn == null) {
                // H3 — 짝(같은 FRM_NO 의 부모 비식별 프레임)이 없으면 쌍을 만들지 않는다.
                //      인덱스 zip 으로 아무 프레임이나 붙이면 엉뚱한 비교가 되어 검수 판단을 오도한다.
                //      슬라이스가 이미 "쌍 성립 가능" 조건으로 걸러졌으므로 정상 경로에서는 도달하지
                //      않는다(동시 삭제 등 경합 시의 방어). 도달하면 총량과 어긋나므로 WARN 으로 남긴다.
                unmatched++;
                continue;
            }
            pairs.add(new AugmentFramePairResponse(
                    derivativeFrame.getSrcSn(),
                    derivativeFrame.getFrameNo(),
                    deidImageUrl(parentSrcSn),
                    hasDeidImage(derivativeFrame) ? deidImageUrl(derivativeFrame.getSrcSn()) : null));
        }
        if (unmatched > 0) {
            log.warn("[Augment] frame pairs skipped — no matching parent frame jobId={} type={} skipped={}",
                    jobId, sanitize(slice.aug().getAugTypeCd()), unmatched);
        }
        return new AugmentResultItemResponse(
                slice.aug().getDataAugSn(),
                jobId,
                cctvName,
                slice.aug().getAugTypeCd(),
                pairs,
                // 해상도 파생은 검수(accept/reject) 대상이 아니라 내부 생성물이다. 생성 완료 상태를
                // ACCEPTED 로 노출해 FE 결정 카드가 채택/거부 버튼을 띄우지 않게 한다
                // (AugmentReviewService.loadOrThrow 가 RESL_ 검수 진입을 이미 차단한다).
                LsDataAug.STTS_ACCEPTED,
                slice.aug().getRegDt(),
                null,
                slice.derivativeRawSn(),
                // 총량 = 쌍이 성립하는 프레임 수(= 실제 표시 가능한 카드 수). 파생 프레임 총수가 아니다.
                slice.framePage().getTotalElements(),
                false);
    }

    /** 생성 완료(ACCEPTED)된 해상도 파생 증강행만 표시 순서대로 반환한다. */
    private List<LsDataAug> generatedResolutionAugs(Long jobId) {
        Map<String, LsDataAug> byType = new HashMap<>();
        for (LsDataAug aug : augRepository.findByOriginalRawSn(jobId)) {
            String type = aug.getAugTypeCd();
            if (type == null || !type.startsWith(LsDataAug.RESL_PREFIX)) {
                continue; // 외부 위탁 증강(WINTER/NIGHT/RAIN) — 본 경로 대상 아님(회귀 0)
            }
            if (!LsDataAug.STTS_ACCEPTED.equals(aug.getAugProcSttsCd())) {
                continue; // 예약(PENDING) = 생성 중 — 산출물이 아직 없으므로 노출하지 않는다
            }
            byType.merge(type, aug, (a, b) -> a.getDataAugSn() >= b.getDataAugSn() ? a : b);
        }
        return RESOLUTION_DISPLAY_ORDER.stream()
                .map(byType::get)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * 부모 RAW_SN 의 <b>확정된</b> 해상도 파생 영상을 프리셋 코드별로 해석한다.
     *
     * <p>파생↔프리셋 매핑 전용 컬럼이 없어 {@code VMS_CLIP_ID} 마커로 짝짓는다
     * ({@code {부모}_RESL_{AUG_TYPE_CD}_{ts}}). <b>판별은 중앙 파서 {@link AugTypeParser} 단일 원천</b>에
     * 위임한다 — 실데이터에는 이중 접두({@code _RESL_RESL_480P_})뿐 아니라 <b>구형 접두 드리프트</b>
     * ({@code _RES_RES_480P_}) 도 존재하며, 자체 {@code contains("_RESL_480P_")} 매칭은 후자를 놓쳐
     * 증강 이력 화면(파서 사용)과 결과 화면의 판별이 어긋난다. 판별 로직을 이원화하지 않는다.
     *
     * <p>{@code deIdntfYn='Y'} 확정본만 대상 — 미확정 파생의 프레임은 비식별 서빙 대상이 아니다.
     * 같은 프리셋 파생이 복수면(재시도 잔존) 최신(최대 RAW_SN)을 택한다.
     *
     * <p>파서가 종류를 판별하지 못한 확정 파생은 <b>조용히 넘기지 않고</b> 식별 가능한 형태
     * (파생 RAW_SN + clipId)로 WARN 로그를 남긴다 — 결과 누락 시 원인 추적 지점.
     */
    private Map<String, Long> resolveDerivativeRawSns(Long parentRawSn) {
        Map<String, Long> byType = new HashMap<>();
        for (LsDataRaw derivative : videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(parentRawSn)) {
            if (!"Y".equals(derivative.getDeIdntfYn())) {
                continue;
            }
            String type = AugTypeParser.parse(derivative.getVmsClipId());
            if (type == null || !type.startsWith(LsDataAug.RESL_PREFIX)) {
                // 외부 위탁 증강(WINTER/NIGHT/RAIN) 파생은 본 경로 대상이 아니므로 조용히 건너뛰고,
                // 판별 실패(null)만 관측 가능하게 남긴다.
                if (type == null) {
                    log.warn("[Augment] derivative type unresolved — parentRawSn={} derivativeRawSn={} clipId={}",
                            parentRawSn, derivative.getRawSn(), sanitize(derivative.getVmsClipId()));
                }
                continue;
            }
            byType.merge(type, derivative.getRawSn(), Math::max);
        }
        return byType;
    }

    /**
     * 부모 프레임을 {@code (RAW_SN, FRM_NO)} 배치 조회해 {@code FRM_NO → SRC_SN} 매핑을 만든다.
     * <b>비식별 경로가 없는 부모 프레임은 제외</b>한다 — 좌측(원본)에 보여줄 비식별 이미지가 없으면
     * 쌍이 성립하지 않는다(원본 raw 프레임으로 폴백하지 않는다, H2).
     */
    private Map<Long, Long> loadParentSrcSnByFrameNo(Long parentRawSn, Set<Long> frameNos) {
        if (frameNos.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (LsDataSrc parentFrame : srcRepository.findByRawSnAndFrameNoIn(parentRawSn, frameNos)) {
            if (hasDeidImage(parentFrame)) {
                result.put(parentFrame.getFrameNo(), parentFrame.getSrcSn());
            }
        }
        return result;
    }

    private String resolveCctvName(Long rawSn) {
        for (Object[] row : videoRepository.findCctvNamesByRawSns(List.of(rawSn))) {
            String cctvNm = (String) row[1];
            String vmsCctvId = (String) row[2];
            String name = (cctvNm != null && !cctvNm.isBlank()) ? cctvNm : vmsCctvId;
            if (name != null && !name.isBlank()) {
                return name;
            }
        }
        return CCTV_NAME_FALLBACK;
    }

    private static boolean hasDeidImage(LsDataSrc frame) {
        return hasImagePath(frame.getDeIdntfSrcFilePathNm());
    }

    /**
     * 비식별 이미지 경로 <b>보유 판정 단일 기준</b> —
     * {@link LsDataSrcRepository#findPairableDerivativeFrames} 의 {@code trim(x) <> ''} 와 동일하다.
     *
     * <p><b>왜 {@code isBlank()} 가 아닌가</b>: 총량(count 쿼리)과 표시(이 판정)의 기준이 어긋나면
     * "총량 = 실제 카드 수" 정합이 깨진다. SQL {@code TRIM} 은 <b>스페이스만</b> 제거하므로
     * {@code trim(x) <> ''} 는 "스페이스가 아닌 문자가 하나라도 있는가"와 같다. 반면
     * {@link String#isBlank()} 는 탭·개행·U+3000 까지 공백으로 보아, 경로가 {@code "\t"} 인 프레임을
     * DB 는 세고 표시 단계는 드롭한다(총량 과대 + "도달 불가"라던 unmatched 경로 부활).
     * DB 쪽을 {@code isBlank()} 와 동일하게 만들 수단(유니코드 공백 전체를 커버하는 이식 가능한 SQL)이
     * 없으므로 <b>Java 를 DB 기준에 맞춘다</b>.
     */
    private static boolean hasImagePath(String path) {
        return path != null && path.chars().anyMatch(c -> c != ' ');
    }

    /** 이미지 노출은 API 경로로만 — 스토리지 경로를 응답에 싣지 않는다(CWE-209/359). */
    private static String deidImageUrl(Long srcSn) {
        return String.format(DEID_IMAGE_URL_FORMAT, srcSn);
    }

    /** 입력 검증(CWE-20) — 조회 이전에 수행해 잘못된 페이징이 DB 까지 내려가지 않게 한다. */
    private static void validatePaging(int page, int size) {
        if (page < 0) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "page 는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "size 는 1 이상이어야 합니다.");
        }
        if (size > MAX_FRAME_PAIR_SIZE) {
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "size 한도 초과 (max=" + MAX_FRAME_PAIR_SIZE + ")");
        }
    }

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        return value == null ? null : value.replace('\n', '_').replace('\r', '_');
    }
}
