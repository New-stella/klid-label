package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentFramePairResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultItemResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 증강 작업 결과 본문 조회 — {@code GET /v1/augments/{jobId}/result} 의 {@code results[]} 구성.
 *
 * <h3>범위 — 해상도 파생(RESL_*) <b>+ 외부 위탁 증강(WINTER/NIGHT/RAIN)</b></h3>
 * <p><b>두 유형 모두</b> 프레임 쌍(부모 비식별 ↔ 파생)을 채운다. 구 구현은 {@code List.of()} 로
 * 하드코딩해 "비교 이미지가 하나도 안 나오던" 결함을 해소했지만 <b>해상도 파생에만</b> 적용했고,
 * 정작 <b>검수(채택/반려) 대상인 유일한 유형</b>인 외부 위탁 증강은 여전히 상수 0장이었다. 그 상태에서
 * FE 는 "프레임별 비교 결과는 외부 연동 이후 표시됩니다" 를 띄우면서 바로 아래에 채택/거부 버튼을
 * 활성으로 그렸다 — 이미지를 한 장도 못 보고 승인하면 상위 요구("증강 결과에서 <b>이미지를 비교해
 * 보고</b> 사용 유무를 선택해야 작업목록에 올라간다")의 등재 게이트가 의례적 절차가 된다.
 *
 * <p>항목 자체를 내리는 것은 별개 목적이다 — "이 결과물을 어떤 조건({@code prompt})으로 만들었는가" 를
 * 볼 수 있는 경로가 구 구현에서는 accept/reject <b>응답</b>뿐이었다(= <b>결정을 내린 뒤에야</b>, 그것도
 * 재전이가 CONFLICT 로 막혀 <b>다시 조회할 수 없는</b> 형태). 같은 (영상 × 종류) 반복 요청이 허용된
 * 2026-07-31 이후에는 결과물끼리 구분이 조건으로만 가능하므로(R9 역추적) 조회(GET) 경로에 실린다.
 *
 * <h3>증강 행 ↔ 파생 영상 짝짓기 — 유형별로 근거가 다르다 (통일하지 말 것)</h3>
 * <ul>
 *   <li><b>외부 위탁</b> — {@code LS_DATA_AUG.NEW_RAW_SN}(V149). 생성 경로가 파생 RAW 를 INSERT 한
 *       <b>같은 트랜잭션</b>에서 채우므로 추정이 없다. 같은 (영상 × 종류) 재요청이 허용된 뒤로는
 *       유형만으로 짝지으면 <b>다른 요청의 파생본</b>을 가리키므로 이 컬럼이 유일한 정답이다.</li>
 *   <li><b>해상도 파생</b> — {@code VMS_CLIP_ID} 마커 파싱({@link #resolveDerivativeRawSns}). 이쪽을
 *       {@code NEW_RAW_SN} 으로 통일하면 <b>안 된다</b> — 백필이 없어 기존 해상도 파생은 그 값이
 *       NULL 이라 결과 화면이 통째로 비어버린다(보류 확정).</li>
 * </ul>
 *
 * <h3>부모↔파생 프레임 조인 = {@code (RAW_SN, FRM_NO)} 동등 조인</h3>
 * <p><b>전제</b>: 파생 프레임은 {@code ResolutionSnapshot.FrameSpec} 이 부모의 {@code frameNo} 를 그대로
 * 들고 와 {@code LsDataSrc.create(newRawSn, f.frameNo(), …)} 로 심으므로 <b>파생 프레임의 {@code FRM_NO}
 * 는 부모와 항상 동일</b>하다.
 *
 * <p><b>외부 위탁도 같은 조인을 쓴다</b>: {@code AugmentExtractSnapshot} 이 부모 프레임을
 * {@code FRM_NO} 오름차순으로 훑어 파생 프레임의 {@code FRM_NO} 를 0-base 연번으로 심으므로, 부모
 * {@code FRM_NO} 가 연속인 정상 형상(추출기가 0..n-1 로 적재)에서 두 값은 일치한다. 이 전제도 아래
 * 리스크와 같은 성격의 <b>구현 규약</b>이며, 어긋난 프레임은 쌍을 만들지 않는다(누락 &gt; 오조합).
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
 *   <li><b>생성 조건(prompt) 노출 범위</b> — 이 엔드포인트는 {@code @PreAuthorize("hasRole('REVIEWER')")}
 *       라 accept/reject 와 <b>동일한 권한 경계</b> 안에 있다. WORKER 는 403 이므로 prompt 를 조회로
 *       넓혀도 노출 대상이 늘지 않는다(목록 {@code GET /v1/augments} 는 WORKER 도 허용되므로 그쪽에는
 *       싣지 않는다).</li>
 *   <li><b>비식별 신고 게이트(CWE-359)</b> — 진입 시 {@link DeidentReportGate} 로 원본(부모)을 판정하고
 *       걸리면 412. 파생영상은 <b>각자의 행</b>으로 판정해(원본 신고는 파생에 전파되지 않는다 —
 *       2026-07-29 확정 정책) 신고된 파생만 쌍에서 제외한다.</li>
 *   <li><b>원본(PII) 경로 미노출(CWE-209)</b> — 응답에 파일 경로를 담지 않는다. 좌/우 모두
 *       {@code /v1/frames/{srcSn}/deid-image}(비식별 전용 서빙 API) 경로만 반환한다.</li>
 *   <li><b>입력 검증(CWE-20)</b> — 두 축(page/size, itemPage/itemSize) 범위를 조회 이전에 검증한다.</li>
 * </ul>
 *
 * <h3>페이징 축 분리 (DEV_FIX HIGH-1 → MED-5)</h3>
 * <p>프레임 쌍 축({@code page}/{@code size})과 결과 항목 축({@code itemPage}/{@code itemSize})은
 * <b>독립</b>이다. 한 창을 공유하면 "한쪽 축 총량이 0이면 다른 축이 갇힌다" 가 구조적으로 남는다 —
 * 실제로 프레임 쌍 0건인 순수 외부 위탁 영상에서 13번째 항목이 도달 불가가 됐다.
 *
 * <p><b>독립의 의미(MED-5 정정)</b>: 항목 축은 <b>모든 결과 항목</b>(외부 위탁 + 해상도 파생)을
 * 페이징하고, 프레임 축은 <b>그 페이지에 실린 해상도 항목 안에서</b> 프레임 쌍을 페이징한다. 중간
 * 구현은 해상도 항목을 항목 축 바깥에 두고 {@code itemPage==0} 에만 실었는데, 그러면 이번엔
 * <b>프레임 축이 항목 축 위치에 갇혀</b>(itemPage≥1 이면 비교 이미지 도달 불가) 같은 결함이 반대
 * 방향으로 재발했다. 한 {@code results[]} 안에서 "전 항목 도달 가능 + 페이지 간 중복 0 +
 * {@code totalElements} 정합" 을 동시에 만족하는 구성은 <b>해상도 파생도 항목 축의 정식 원소로
 * 세는 것</b> 하나뿐이다.
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

    /**
     * 결과 항목 기본 페이지 크기 — {@code rules/api-design.md} 표준 기본값(20).
     *
     * <p>프레임 축 기본값(12)을 따라가지 않는다. 12 는 <b>이미지 그리드 한 화면</b> 크기라 항목 축에
     * 의미가 없고, 무엇보다 항목 페이저 배선이 아직 없는 구 FE(Phase 5 대상)에서 13번째 항목이
     * 소실됐던 값이 정확히 12 다. 항목은 이미지가 없는 경량 레코드라 20 건이 응답 크기 문제가 되지 않는다.
     */
    public static final int DEFAULT_ITEM_SIZE = 20;
    /** 결과 항목 페이지 크기 상한 — {@code rules/api-design.md} 페이징 규약(max 100). */
    public static final int MAX_ITEM_SIZE = 100;

    /** 비식별 프레임 이미지 서빙 API — 좌/우 이미지 모두 이 경로로만 노출한다(경로 문자열 미노출). */
    private static final String DEID_IMAGE_URL_FORMAT = "/v1/frames/%d/deid-image";

    /** 화면 표시 순서 — 고해상도 → 저해상도. */
    private static final List<String> RESOLUTION_DISPLAY_ORDER = List.of(
            LsDataAug.AUG_RESL_1080P, LsDataAug.AUG_RESL_720P, LsDataAug.AUG_RESL_480P);

    private static final String CCTV_NAME_FALLBACK = "(이름 없음)";

    /**
     * 응답 안내 문구.
     *
     * <p>구 문구는 "외부 위탁 증강의 프레임별 결과는 외부 SFR-07 연동 이후 제공됩니다" 였는데, 외부
     * 위탁도 쌍을 채우게 된 지금은 <b>사실과 다르다</b>. 항목별 사정(반입 중/실패/보류)은 문구가 아니라
     * {@code resultState} 로 내려간다 — 한 응답에 여러 항목이 실리므로 공통 문구로는 표현할 수 없다.
     */
    private static final String MESSAGE =
            "증강·파생 결과는 원본 비식별 프레임과 파생 프레임을 쌍으로 제공합니다. "
                    + "항목별 진행 상태는 resultState 를 참고하세요.";

    private final LsDataAugRepository augRepository;
    /** 외부 위탁 항목의 결정 상태(일시·반려사유) 조회 — 항목당 조회가 아니라 배치 1회. */
    private final LsDataAugRvwRepository reviewRepository;
    private final LsDataSrcRepository srcRepository;
    private final VideoRepository videoRepository;
    private final AugmentReviewService reviewService;
    private final DeidentReportGate deidentReportGate;

    /**
     * 증강 작업 결과 본문 조회.
     *
     * @param jobId    증강 jobId (= 원본 RAW_SN)
     * @param page     <b>프레임 쌍 축</b> 페이지 번호 (0-based)
     * @param size     <b>프레임 쌍 축</b> 페이지 크기 (1..{@value #MAX_FRAME_PAIR_SIZE})
     * @param itemPage <b>결과 항목 축</b> 페이지 번호 (0-based)
     * @param itemSize <b>결과 항목 축</b> 페이지 크기 (1..{@value #MAX_ITEM_SIZE})
     * @throws CustomException 400 페이징 범위 위반, 412 비식별 누락 신고 구간
     */
    public AugmentResultResponse result(Long jobId, int page, int size, int itemPage, int itemSize) {
        Paging paging = new Paging(page, size, itemPage, itemSize);
        paging.validate();

        // (구 {@code jobId == null} 분기는 제거됐다 — 진입점이 {@code @PathVariable Long jobId} 라
        //  null 이 도달할 수 없는 사문 코드였다. 도달 불가 분기를 남기면 "여기서도 방어한다" 는 잘못된
        //  안전 신호를 준다.)
        String status = reviewService.aggregateResultStatus(jobId);

        // H1 (CWE-359) — 비식별 누락 신고 구간이면 결과 본문(프레임 쌍 = PII 위치 단서)을 노출하지 않는다.
        //                라벨 조회와 동일 정책(412). 파생영상은 아래에서 각자 판정한다.
        if (deidentReportGate.isUnderDeidentReport(jobId)) {
            log.warn("[Augment] result blocked — deident report open jobId={}", jobId);
            throw new CustomException(ErrorCode.PRECONDITION_FAILED,
                    "비식별 재처리 대기 중인 영상은 증강 결과를 조회할 수 없습니다.");
        }

        List<LsDataAug> augs = augRepository.findByOriginalRawSn(jobId);
        // ★ 항목 축의 원소 = 외부 위탁 항목 + 해상도 파생 항목 (DEV_FIX MED-5 — 축 독립의 <진짜> 성립)
        //
        //   구 구현은 해상도 파생을 항목 축 <바깥>에 두고 itemPage==0 에만 실었다. 그러면 항목 페이저를
        //   넘긴 사용자는 프레임 쌍(비교 이미지)에 <도달할 수 없다> — 프레임 축이 이번엔 항목 축 위치에
        //   갇힌 것이다(Phase 2 가 세운 "두 축은 서로 독립" 불변식의 역방향 파손).
        //
        //   해결은 셋을 동시에 만족해야 한다: ①모든 항목이 itemPage 로 도달 가능 ②어떤 항목도 페이지
        //   간 중복 없음 ③results.length ↔ totalElements 정합. 하나의 results[] 안에서 이 셋을 만족하는
        //   구성은 "해상도 파생도 항목 축의 정식 원소로 세는 것" 뿐이다. 그러면 프레임 축(page/size)은
        //   <그 항목 안에서> 독립적으로 동작하고, 항목 축은 표준 페이징 규약(api-design.md)과 정확히
        //   일치한다(totalElements 가 results 전체를 센다).
        //
        //   FE 영향 없음: FE 는 itemPage 를 보내지 않아 기본값(0/20)이 적용되고, 항목이 20건 이하인
        //   정상 형상에서는 종전과 동일하게 외부 위탁 + 해상도 파생이 한 응답에 함께 실린다.
        List<LsDataAug> items = new ArrayList<>(externalAugs(augs));
        items.addAll(generatedResolutionAugs(augs));
        long itemTotal = items.size();
        if (items.isEmpty()) {
            return paging.response(jobId, status, List.of(), itemTotal);
        }

        // 항목 축 창 — 이 페이지에 실릴 항목만 남긴다(외부/해상도 구분 없이 같은 규칙).
        List<LsDataAug> pageItems = pageOf(items, itemPage, itemSize);
        if (pageItems.isEmpty()) {
            return paging.response(jobId, status, List.of(), itemTotal);
        }

        String cctvName = resolveCctvName(jobId);
        Pageable pageable = PageRequest.of(page, size);

        // 1차 — 항목별 파생 프레임 슬라이스를 모으고(항목당 1 페이지 쿼리), FRM_NO 집합을 합친다.
        //        ★ 이 페이지에 실린 항목에 대해서만 조회한다 — 프레임 쌍 쿼리(비용 큰 경로)가 항목
        //          페이징(itemSize ≤ 100)으로 자연히 제한된다.
        //        ★ 축 독립: 여기서 쓰는 창은 프레임 축(page/size)이고, 어떤 itemPage 에 있든 그 페이지에
        //          실린 항목은 자기 프레임 쌍을 그대로 받는다(항목 축이 프레임 축을 가두지 않는다).
        List<ItemSlice> slices = new ArrayList<>(pageItems.size());
        Set<Long> frameNos = new LinkedHashSet<>();
        Map<String, Long> resolutionRawSnByType = null; // 해상도 항목이 있을 때만 1회 해석
        for (LsDataAug aug : pageItems) {
            boolean external = AugmentPrompts.isExternalAugType(aug.getAugTypeCd());
            Long derivativeRawSn;
            if (external) {
                // V149 매핑. null 이면 아직/영영 파생이 없거나 V149 이전 요청 — 추정하지 않는다.
                derivativeRawSn = aug.getNewRawSn();
            } else {
                if (resolutionRawSnByType == null) {
                    resolutionRawSnByType = resolveDerivativeRawSns(jobId);
                }
                derivativeRawSn = resolutionRawSnByType.get(aug.getAugTypeCd());
                if (derivativeRawSn == null) {
                    // 해상도 항목은 파생을 못 찾으면 노출하지 않는다(구 동작 보존 — 마커 판별 실패분).
                    log.warn("[Augment] resolution derivative video not found jobId={} type={}",
                            jobId, sanitize(aug.getAugTypeCd()));
                    continue;
                }
            }

            boolean withheld = derivativeRawSn != null
                    && deidentReportGate.isUnderDeidentReport(derivativeRawSn);
            if (withheld) {
                log.warn("[Augment] derivative result withheld — deident report open rawSn={}", derivativeRawSn);
                if (!external) {
                    continue; // 해상도 항목은 종전대로 통째로 제외(내부 생성물 — 결정 UI 가 없다)
                }
                // 외부 위탁 항목은 <항목은 남기고 이미지만> 뺀다 — 항목을 지우면 그 증강의 결정 상태·
                // 생성 조건까지 화면에서 사라져 REVIEWER 가 무슨 일이 있었는지 알 수 없다.
            }

            Page<LsDataSrc> framePage = null;
            if (derivativeRawSn != null && !withheld) {
                // 페이징 단위 = "쌍이 성립하는 파생 프레임". 파생 프레임 전체를 페이징하면 부모 비식별
                // 경로가 없는 프레임이 표시 단계에서 드롭돼 총량(페이저·"총 처리 이미지" 표기)과 실제
                // 카드 수가 어긋난다(빈 페이지·과대 표기).
                framePage = srcRepository.findPairableDerivativeFrames(derivativeRawSn, jobId, pageable);
                framePage.getContent().forEach(f -> frameNos.add(f.getFrameNo()));
            }
            slices.add(new ItemSlice(aug, external, derivativeRawSn, framePage, withheld));
        }
        if (slices.isEmpty()) {
            return paging.response(jobId, status, List.of(), itemTotal);
        }

        // 2차 — 부모 프레임을 (RAW_SN, FRM_NO) 배치 IN 조회 1회로 해결한다(H4 N+1 회피).
        Map<Long, Long> parentSrcSnByFrameNo = loadParentSrcSnByFrameNo(jobId, frameNos);
        // 3차 — 외부 위탁 항목의 최신 검수 행도 배치 1회.
        Map<Long, LsDataAugRvw> reviews = loadLatestReviews(slices.stream()
                .filter(ItemSlice::external).map(ItemSlice::aug).toList());

        List<AugmentResultItemResponse> results = new ArrayList<>(slices.size());
        for (ItemSlice slice : slices) {
            results.add(toResultItem(jobId, cctvName, slice, parentSrcSnByFrameNo, reviews));
        }
        return paging.response(jobId, status, results, itemTotal);
    }

    /**
     * 항목 목록에 <b>항목 축 창({@code itemPage}/{@code itemSize})</b>을 적용한다.
     *
     * <p>구 구현(1차 DEV_FIX 이전)은 {@code results} 를 매 호출 전량으로 초기화해 같은 항목이
     * 프레임 페이지마다 다시 내려갔다(응답 크기 × 페이지 수, CWE-770). 1차 수정은 <b>프레임 축 창을
     * 그대로</b> 항목에 적용해 중복은 없앴지만, 프레임 쌍이 0건인 영상에서는 FE 페이저가 렌더되지 않아
     * 2페이지로 갈 수단이 없어 <b>13번째 항목이 화면에서 소실</b>됐다.
     *
     * <p>축을 분리하면 두 목표가 동시에 성립한다 — 항목 슬라이스는 {@code itemSize}(≤100)로 <b>상한이
     * 있고</b>(무한정 커지지 않는다), 모든 항목은 {@code itemPage} 로 <b>도달 가능</b>하다(R9 생성 조건
     * 역추적 보존). 프레임 페이지를 넘길 때 같은 항목 슬라이스가 다시 실리는 것은 <b>의도된 동작</b>이다
     * — 항목은 화면의 탭 구성이라 프레임 이동으로 바뀌면 안 되며, 슬라이스 상한이 있어 응답 크기가
     * 무한정 곱해지지 않는다.
     *
     * <p>이 창은 <b>외부 위탁·해상도 파생을 가리지 않고</b> 적용된다(MED-5) — 한쪽만 창 밖에 두면
     * "그 항목은 특정 itemPage 에서만 보인다(=다른 페이지에서 도달 불가)" 거나 "모든 페이지에 실린다
     * (=이어붙이기 중복)" 중 하나가 반드시 생긴다.
     */
    private static <T> List<T> pageOf(List<T> all, int page, int size) {
        int from = (int) Math.min((long) page * size, all.size());
        int to = (int) Math.min((long) from + size, all.size());
        return all.subList(from, to);
    }

    /**
     * 두 페이징 축(프레임 쌍 / 결과 항목) — 검증과 응답 조립을 한곳에 모은다.
     *
     * <p>응답 조립을 값 객체에 두는 이유: {@link #result} 는 조기 반환 지점이 5곳이라, 총량 필드를
     * 반환 지점마다 손으로 채우면 <b>한 곳만 빠뜨려도 그 경로에서 페이저가 사라진다</b>(이번 결함의
     * 재발 형태). 조립 경로를 하나로 묶어 그 가능성을 없앤다.
     */
    private record Paging(int page, int size, int itemPage, int itemSize) {

        /** 입력 검증(CWE-20/770) — 조회 이전에 수행해 잘못된 페이징이 DB 까지 내려가지 않게 한다. */
        void validate() {
            validateAxis(page, size, "page", "size", MAX_FRAME_PAIR_SIZE);
            validateAxis(itemPage, itemSize, "itemPage", "itemSize", MAX_ITEM_SIZE);
        }

        private static void validateAxis(int page, int size, String pageName, String sizeName, int max) {
            if (page < 0) {
                throw new CustomException(ErrorCode.INVALID_INPUT, pageName + " 는 0 이상이어야 합니다.");
            }
            if (size < 1) {
                throw new CustomException(ErrorCode.INVALID_INPUT, sizeName + " 는 1 이상이어야 합니다.");
            }
            if (size > max) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        sizeName + " 한도 초과 (max=" + max + ")");
            }
        }

        /** 항목 축 총량({@code itemTotal})으로 페이저 근거 4필드를 채워 응답을 만든다. */
        AugmentResultResponse response(Long jobId, String status,
                                       List<AugmentResultItemResponse> results, long itemTotal) {
            return new AugmentResultResponse(jobId, status, results, MESSAGE, page, size,
                    itemPage, itemSize, itemTotal, totalPages(itemTotal));
        }

        /** 총량 0 이면 0 페이지 (Spring {@code Page.getTotalPages()} 와 동일 규약). */
        private int totalPages(long itemTotal) {
            return (int) ((itemTotal + itemSize - 1) / itemSize);
        }
    }

    /**
     * 외부 위탁 증강(WINTER/NIGHT/RAIN) 결과 항목 — <b>최신순</b>(등록일시 DESC → PK DESC).
     *
     * <p>같은 종류를 여러 번 요청할 수 있게 된 뒤로는 "몇 번째 요청의 결과인가" 가 화면 판독의 축이므로
     * <b>시간 순</b>이 정본이다(유형 우선순위로 섞지 않는다 — 목록 정렬 정책과 같은 태도).
     *
     * <h3>ASC → DESC 로 뒤집은 이유 (2026-07-31)</h3>
     * <p>오름차순이면 항목 축 1페이지에서 <b>잘려나가는 쪽이 가장 최신</b>이다. 그런데 가장 최신
     * 항목이야말로 <b>유일하게 {@code PENDING} 인 결정 대상</b>(직전 요청분)이라, 항목 페이저가 아직
     * 배선되지 않은 FE(Phase 5 대상)에서는 방금 요청한 결과에 도달할 수단이 없었다. 내림차순이면
     * 페이저 없이도 실무 도달성이 유지되고, {@code rules/api-design.md} 의 기본 정렬
     * ({@code createdAt,desc})과도 일치한다.
     */
    private List<LsDataAug> externalAugs(List<LsDataAug> augs) {
        return augs.stream()
                .filter(a -> AugmentPrompts.isExternalAugType(a.getAugTypeCd()))
                .sorted(Comparator.comparing(LsDataAug::getRegDt,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(LsDataAug::getDataAugSn,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .reversed())
                .toList();
    }

    /**
     * 카드에 표시할 상태 — <b>사람의 결정이 정본</b>이고, 결정이 존재할 수 없는 항목에 한해
     * 생성 결과를 그대로 보인다.
     *
     * <pre>
     *   검수 행 있음                 → 그 값 (PENDING/ACCEPTED/REJECTED)  … 사람의 결정
     *   검수 행 없음 + 생성 영구실패  → REJECTED                           … 실패를 실패로 보인다
     *   검수 행 없음 + 생성 성공      → PENDING                            … 결정 대기(버튼 노출)
     *   검수 행 없음 + 생성 중        → PENDING (단 reviewable=false)      … FE 는 카드를 감춘다
     *   검수 행 없음 + 취소 종결      → 그 생성 상태 (CANCELED)            … 취소를 취소로 보인다
     * </pre>
     *
     * <p><b>이것은 축을 다시 합치는 것이 아니다.</b> 축 분리는 "누가 {@code AUG_PROC_STTS_CD} 를
     * <b>쓰는가</b>" 에 관한 규칙이고(생성기만 쓴다 — 검수는 절대 쓰지 않는다), 여기서는 아무것도
     * 쓰지 않고 <b>표시</b>만 한다. 실패·취소 줄이 없으면 dead-letter 로 끝난 증강과 사용자가 취소한
     * 증강이 화면에 "미결정" 으로 뜬다 — 실제로는 영영 결정할 수 없는 항목이라 사실과 다르고,
     * FE 가 이미 갖고 있는 실패·취소 표시({@code DecisionCard} 의 CANCELED/REJECTED 분기)가
     * 영원히 도달 불가가 된다.
     *
     * <p><b>dead-letter 를 상태보다 먼저 본다</b>(DEV_FIX MEDIUM): 비동기 확정 실패는
     * {@code AUG_PROC_STTS_CD} 를 {@code ACCEPTED} 로 <b>남겨둔 채</b> {@code DEAD_LETTER_AT} 만
     * 찍는다. 상태를 먼저 보면 그런 항목이 "채택됨" 으로 표시돼 헤더의 FAILED 집계와 상반된다.
     *
     * <p><b>사람의 반려와 생성 실패는 여기서 구분되지 않는다</b> — 둘 다 {@code REJECTED} 다. 구분은
     * {@link #resolveResultState}({@code resultState}) 가 담당한다(FE 가 그 축으로 문구를 가른다).
     */
    private static String resolveDecision(LsDataAug aug, LsDataAugRvw review) {
        if (review != null) {
            return review.getRvwSttsCd();
        }
        if (aug.isProcessingFailed()) {
            return LsDataAugRvw.STTS_REJECTED;
        }
        if (aug.isGenerationSucceeded() || aug.isGenerationInProgress()) {
            return LsDataAugRvw.STTS_PENDING;
        }
        return aug.getAugProcSttsCd();
    }

    /**
     * 결과물 상태 — <b>{@code framePairs} 가 비어 있는 이유</b>를 화면이 사실대로 말하게 하는 축.
     *
     * <p>구 FE 는 쌍이 0장이면 무조건 "프레임별 비교 결과는 외부 연동 이후 표시됩니다" 를 띄웠다.
     * 외부 위탁도 쌍을 채우게 된 지금 그 문구는 <b>거짓</b>이고, 무엇보다 "아직 반입 중(정상)" 과
     * "영구 실패(기다려도 안 생김)" 가 구분되지 않으면 REVIEWER 가 <b>영영 오지 않을 이미지</b>를
     * 기다린다. {@code decision} 만으로도 안 된다 — 생성 실패와 사람의 반려가 둘 다 {@code REJECTED}
     * 라 실패한 증강이 "누군가 거부함" 으로 보인다(FIX-D).
     *
     * <p>판정 순서는 <b>영구 사실이 먼저</b>다: 실패/취소는 그 뒤에 무엇이 와도 바뀌지 않는다.
     */
    private static String resolveResultState(LsDataAug aug, boolean withheld, long totalFramePairs) {
        if (aug.isProcessingFailed()) {
            return AugmentResultItemResponse.STATE_GENERATION_FAILED;
        }
        if (LsDataAug.STTS_CANCELED.equals(aug.getAugProcSttsCd())) {
            return AugmentResultItemResponse.STATE_CANCELED;
        }
        if (aug.isGenerationInProgress()) {
            return AugmentResultItemResponse.STATE_GENERATING;
        }
        if (!aug.isGenerationSucceeded()) {
            // dead-letter 없이 REJECTED 로만 종결된 행(정상 형상에는 없다 — 실패 인계는 항상 마커를
            // 함께 찍는다). 방어적으로 "실패" 로 본다 — "준비 중" 으로 보이면 무한 대기가 된다.
            return AugmentResultItemResponse.STATE_GENERATION_FAILED;
        }
        if (withheld) {
            return AugmentResultItemResponse.STATE_WITHHELD;
        }
        return totalFramePairs > 0
                ? AugmentResultItemResponse.STATE_READY
                : AugmentResultItemResponse.STATE_PREPARING_FRAMES;
    }

    /**
     * 사람의 사용/폐기 결정이 아직 없는가 — {@code reviewable} 두 축 중 <b>검수 축</b>.
     *
     * <p>검수 행이 없거나(요청 직후) 그 행이 아직 {@code PENDING} 이면 결정 전이다. 결정된 뒤에는
     * 검수 행의 {@code ensurePending} 가드가 재결정을 409 로 막으므로 화면도 버튼을 감춰야 한다.
     * <b>이 축만으로는 부족</b>하다 — 결과물 실재 축은 {@link LsDataAug#isGenerationSucceeded()} 가
     * 담당한다(위 {@link #toExternalItems} javadoc 참조).
     */
    private static boolean isUndecided(LsDataAugRvw review) {
        return review == null || LsDataAugRvw.STTS_PENDING.equals(review.getRvwSttsCd());
    }

    /** DATA_AUG_SN → 최신 검수 행 (배치 1회 조회 — N+1 회피). 빈 입력은 조회하지 않는다. */
    private Map<Long, LsDataAugRvw> loadLatestReviews(List<LsDataAug> augs) {
        if (augs.isEmpty()) {
            return Map.of();
        }
        List<Long> augSns = augs.stream().map(LsDataAug::getDataAugSn).toList();
        Map<Long, LsDataAugRvw> latest = new HashMap<>();
        for (LsDataAugRvw rvw : reviewRepository.findByDataAugSnIn(augSns)) {
            latest.merge(rvw.getDataAugSn(), rvw, (a, b) -> isNewer(b, a) ? b : a);
        }
        return latest;
    }

    /**
     * 검수 행 최신 판정 — <b>검수 일시({@code RVW_DT})만</b> 본다.
     *
     * <p>구 주석은 "미기록이면 PK 큰 쪽" 이라 했으나 코드는 PK 를 비교하지 않는다(DEV_FIX LOW —
     * 서술 정정). 둘 다 일시가 없으면 <b>먼저 만난 행</b>이 유지된다. 검수 행은 결정 시점에 일시와
     * 함께 기록되므로 일시 없는 행이 복수인 경우는 정상 형상에 없다 — PK 비교를 넣어 "일시가 없어도
     * 최신을 안다" 는 인상을 주기보다, 판정 축이 하나임을 그대로 드러낸다.
     */
    private static boolean isNewer(LsDataAugRvw candidate, LsDataAugRvw current) {
        if (candidate.getRvwDt() != null && current.getRvwDt() != null) {
            return candidate.getRvwDt().isAfter(current.getRvwDt());
        }
        if (candidate.getRvwDt() != null) {
            return true;
        }
        return false;
    }

    /**
     * 결과 항목 1건의 확정 재료 — <b>외부 위탁·해상도 파생 공통</b>.
     *
     * @param external        외부 위탁(WINTER/NIGHT/RAIN)인가 — 결정 UI 유무·짝짓기 근거가 갈린다
     * @param derivativeRawSn 파생 영상 RAW_SN (외부=NEW_RAW_SN / 해상도=마커 파싱, 미해석 시 null)
     * @param framePage       쌍 성립 파생 프레임 슬라이스 (파생 없음·신고 보류 시 null)
     * @param withheld        파생이 비식별 누락 신고 구간이라 이미지를 보류했는가
     */
    private record ItemSlice(LsDataAug aug, boolean external, Long derivativeRawSn,
                             Page<LsDataSrc> framePage, boolean withheld) {
    }

    /**
     * 슬라이스 → 응답 항목. 프레임 쌍 산출은 <b>유형 공통</b>이고, 결정 관련 필드만 갈린다.
     *
     * <ul>
     *   <li><b>외부 위탁</b> — {@code decision}/{@code decidedAt}/{@code rejectReason} 는 검수 행의
     *       최신값, {@code reviewable} 은 <b>결과물이 실재하고</b>({@link LsDataAug#isGenerationSucceeded()})
     *       <b>사람의 결정이 아직 없을 때</b>({@link #isUndecided}) true. 두 조건 모두 필요하다 —
     *       한쪽만 보면 반대 방향으로 각각 결함이 난다:
     *       <ul>
     *         <li>생성 결과 컬럼만 보면 웹훅이 생성 성공으로 ACCEPTED 를 찍는 순간 <b>모든 증강에서
     *             검수 버튼이 사라진다</b>(사용/폐기 워크플로 도달 불가).</li>
     *         <li>검수 행만 보면 <b>아직 만들어지지도 않은 증강</b>과 <b>실패·취소로 끝난 증강</b>에
     *             채택/반려 버튼이 뜬다. FE 는 이 필드로 버튼을 그리므로 그렇게 뜬 버튼을 누르는 것은
     *             우회가 아니라 <b>정상 동선</b>이다 — 결과물 없는 승인이 성립하면 등재 게이트가
     *             무력화된다.</li>
     *       </ul>
     *       그래서 이 필드는 accept/reject 의 사전조건({@code AugmentReviewService.requireGeneratedResult}
     *       + {@code ensurePending})과 <b>정확히 같은 두 축</b>으로 계산한다.</li>
     *   <li><b>해상도 파생</b> — 검수 대상이 아니라 내부 생성물이므로 {@code decision} 은 생성 완료를
     *       뜻하는 {@code ACCEPTED} 고정, {@code reviewable=false}, {@code prompt=null}(외부 위탁이
     *       아니라 내부 ffmpeg 리스케일이라 생성 조건이 없다).</li>
     * </ul>
     */
    private AugmentResultItemResponse toResultItem(Long jobId, String cctvName, ItemSlice slice,
                                                   Map<Long, Long> parentSrcSnByFrameNo,
                                                   Map<Long, LsDataAugRvw> reviews) {
        LsDataAug aug = slice.aug();
        List<AugmentFramePairResponse> pairs = buildFramePairs(jobId, slice, parentSrcSnByFrameNo);
        // 총량 = 쌍이 성립하는 프레임 수(= 실제 표시 가능한 카드 수). 파생 프레임 총수가 아니다.
        long totalFramePairs = slice.framePage() == null ? 0L : slice.framePage().getTotalElements();
        String resultState = resolveResultState(aug, slice.withheld(), totalFramePairs);

        if (!slice.external()) {
            return new AugmentResultItemResponse(
                    aug.getDataAugSn(), jobId, cctvName, aug.getAugTypeCd(), pairs,
                    LsDataAug.STTS_ACCEPTED, aug.getRegDt(), null,
                    slice.derivativeRawSn(), totalFramePairs, false, null, resultState);
        }
        LsDataAugRvw review = reviews.get(aug.getDataAugSn());
        return new AugmentResultItemResponse(
                aug.getDataAugSn(), jobId, cctvName, aug.getAugTypeCd(), pairs,
                resolveDecision(aug, review),
                review == null ? null : review.getRvwDt(),
                review == null ? null : review.getRejectRsn(),
                // V149 매핑. 생성 경로 2곳이 파생 RAW 를 INSERT 한 같은 트랜잭션에서 채우므로 추정이 없다
                // (V149 이전 요청은 백필하지 않아 여전히 null).
                aug.getNewRawSn(), totalFramePairs,
                aug.isGenerationSucceeded() && isUndecided(review),
                // R9 — 이 결과물을 만든 생성 조건 원문. 결정(채택/반려) <b>이전</b>에 확인 가능해야 한다.
                aug.getPromptCn(), resultState);
    }

    /** 파생 프레임 슬라이스 → 프레임 쌍(좌: 부모 비식별 / 우: 파생 비식별). 짝 없는 프레임은 버린다. */
    private List<AugmentFramePairResponse> buildFramePairs(Long jobId, ItemSlice slice,
                                                           Map<Long, Long> parentSrcSnByFrameNo) {
        if (slice.framePage() == null) {
            return List.of();
        }
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
        return pairs;
    }

    /** 생성 완료(ACCEPTED)된 해상도 파생 증강행만 표시 순서대로 반환한다. */
    private List<LsDataAug> generatedResolutionAugs(List<LsDataAug> augs) {
        Map<String, LsDataAug> byType = new HashMap<>();
        for (LsDataAug aug : augs) {
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

    /** Log Injection (CWE-117) 방어 — CR/LF 제거. */
    private static String sanitize(String value) {
        return value == null ? null : value.replace('\n', '_').replace('\r', '_');
    }
}
