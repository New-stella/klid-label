package kr.co.cudo.authoring.augment.service;

import kr.co.cudo.authoring.augment.dto.AugmentDiscardStateResponse;
import kr.co.cudo.authoring.augment.dto.AugmentFramePairResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultItemResponse;
import kr.co.cudo.authoring.augment.dto.AugmentResultResponse;
import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugDscd;
import kr.co.cudo.authoring.augment.entity.LsDataAugRvw;
import kr.co.cudo.authoring.augment.integration.AugmentPrompts;
import kr.co.cudo.authoring.augment.repository.LsDataAugDscdRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRvwRepository;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.DeidentReportGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BinaryOperator;

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
 *   <li><b>외부 위탁</b> — {@code LS_DATA_AUG.NEW_RAW_SN}(V155). 생성 경로가 파생 RAW 를 INSERT 한
 *       <b>같은 트랜잭션</b>에서 채우므로 추정이 없다. 같은 (영상 × 종류) 재요청이 허용된 뒤로는
 *       유형만으로 짝지으면 <b>다른 요청의 파생본</b>을 가리키므로 이 컬럼이 유일한 정답이다.</li>
 *   <li><b>해상도 파생</b> — 파생이 스스로 보유한 {@code LS_DATA_RAW.AUG_TYPE_CD} 컬럼
 *       ({@link #resolveDerivativeRawSns}, V148 신설 + V149 백필). 이쪽을 {@code NEW_RAW_SN} 으로
 *       통일하면 <b>안 된다</b> — 백필이 없어 기존 해상도 파생은 그 값이 NULL 이라 결과 화면이
 *       통째로 비어버린다(보류 확정).</li>
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
 *
 * <h3>불변식 — 총량과 항목은 <b>같은 집합</b>에서 나온다 ("센 뒤 드롭" 금지)</h3>
 * <p>드롭 술어는 전부 {@link #resolveItems} 에서 소진하고, 그 뒤 슬라이스 단계는 <b>항목을 더 걸러내지
 * 않는다</b>. 구 구현은 {@code itemTotal} 을 확정한 뒤 페이지 루프에서 해상도 파생만 두 지점(파생
 * 미해석 · 신고 보류)에서 {@code continue} 로 드롭해, {@code totalElements} 가 실제 {@code results}
 * 합계보다 컸다 — FE 페이저가 존재하지 않는 페이지를 그렸다. 새 드롭 조건이 생기면 <b>반드시</b>
 * {@code resolveItems} 에 넣는다.
 *
 * <p>⚠ 외부 위탁 항목은 신고 구간이어도 <b>항목은 남기고 이미지만</b> 뺀다({@code resultState=WITHHELD}).
 * 해상도 파생과의 이 비대칭은 <b>의도된 동작</b>이며 "일관성" 을 이유로 통일하지 않는다.
 *
 * <h3>폐기 축({@code discard}) — 반려된 결과물의 유예·복구 정보</h3>
 * <p>외부 위탁 항목에만 실린다(해상도 파생은 검수 대상이 아니라 표식이 생길 수 없다). 원장 조회는
 * 검수 행과 동일하게 <b>배치 1회</b>({@link #loadDiscardStates})이고, 그 행이 <b>지금도</b> 폐기
 * 상태인지의 판정은 {@link AugmentDiscardStateMapper} 단일 원천이 한다 — 반려마다 새 행이 쌓이고
 * 복구는 행을 닫을 뿐 지우지 않으므로, 최신 행을 상태 확인 없이 노출하면 복구된 항목이 "곧 삭제됨"
 * 으로 보인다.
 *
 * <p><b>이 축은 같은 응답의 다른 필드를 함께 구속한다</b> — 실삭제 스윕이 조회 도중 커밋되면
 * {@code LS_DATA_AUG_RVW} 만 먼저 사라져 응답이 "영구히 삭제됨" 과 "아직 결정 대기" 를 동시에 말할
 * 수 있다. 그래서 {@code decision}(→ {@code REJECTED}) · {@code reviewable}(→ false) ·
 * {@code framePairs}/{@code totalFramePairs}(→ 비움/0) · {@code resultState}(→ {@code PURGED}) 를
 * <b>모두 같은 입력</b>(매퍼가 돌려준 폐기 축)으로 맞춘다. 한 필드만 맞추면 나머지가 모순을 낸다.
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
    /** 외부 위탁 항목의 폐기(소프트 삭제) 이력 조회 — 마찬가지로 배치 1회. */
    private final LsDataAugDscdRepository discardRepository;
    /** 폐기 원장 → 응답 축 판정(복구·클레임·실삭제 분기 + 유예 산출) 단일 원천. */
    private final AugmentDiscardStateMapper discardStateMapper;
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

        // 잡 단위 요청일시 — 산출식을 여기서 다시 쓰지 않고 <목록 API 를 소유한 서비스>에 위임한다.
        // 두 화면(잡 카드 목록 · 결과 상세)이 같은 값을 말해야 하므로 진실원은 한 곳뿐이어야 한다.
        // 이 값은 페이징과 무관한 잡 단위 사실이라 <아래 세 반환 지점 전부>에 실린다(항목 0건 조기 반환 ·
        // 페이지 범위 밖 조기 반환 · 정상 경로). 한 곳만 채우면 "빈 잡에서만 null" 이 되는 결함이 난다 —
        // 그래서 조립을 Paging.response 한 곳으로 모아 컴파일러가 누락을 막게 한다.
        // 증강 행이 0건이면 null 이며 지어내지 않는다.
        LocalDateTime requestedAt = AugmentReviewService.resolveRequestedAt(augs);

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
        List<ItemCandidate> items = resolveItems(jobId, augs);
        long itemTotal = items.size();
        if (items.isEmpty()) {
            return paging.response(jobId, status, List.of(), itemTotal, requestedAt);
        }

        // 항목 축 창 — 이 페이지에 실릴 항목만 남긴다(외부/해상도 구분 없이 같은 규칙).
        List<ItemCandidate> pageItems = pageOf(items, itemPage, itemSize);
        if (pageItems.isEmpty()) {
            return paging.response(jobId, status, List.of(), itemTotal, requestedAt);
        }

        String cctvName = resolveCctvName(jobId);
        Pageable pageable = PageRequest.of(page, size);

        // 0차 — 폐기 축을 <b>프레임 조회보다 먼저</b> 읽는다(배치 1회 + 매퍼 판정까지 여기서 끝낸다).
        //        조회 순서가 반대면 "프레임은 삭제 전 스냅샷 · 폐기는 삭제 후" 를 읽어, 실삭제된 항목에
        //        이미 CASCADE 로 사라진 srcSn 을 가리키는 <b>죽은 이미지 링크</b>가 실린다(클릭하면 404).
        //        먼저 읽으면 창이 좁아지는 데다, purged 로 판정된 항목의 프레임 조회를 <b>아예 건너뛸</b>
        //        수 있다. ⚠ 순서만으로는 창이 닫히지 않으므로 조립 단계 방어(toResultItem)를 함께 둔다.
        List<LsDataAug> externalAugsOnPage = pageItems.stream()
                .filter(ItemCandidate::external).map(ItemCandidate::aug).toList();
        Map<Long, AugmentDiscardStateResponse> discards = loadDiscardStates(externalAugsOnPage);

        // 1차 — 항목별 파생 프레임 슬라이스를 모으고(항목당 1 페이지 쿼리), FRM_NO 집합을 합친다.
        //        ★ 이 페이지에 실린 항목에 대해서만 조회한다 — 프레임 쌍 쿼리(비용 큰 경로)가 항목
        //          페이징(itemSize ≤ 100)으로 자연히 제한된다.
        //        ★ 축 독립: 여기서 쓰는 창은 프레임 축(page/size)이고, 어떤 itemPage 에 있든 그 페이지에
        //          실린 항목은 자기 프레임 쌍을 그대로 받는다(항목 축이 프레임 축을 가두지 않는다).
        List<ItemSlice> slices = new ArrayList<>(pageItems.size());
        Set<Long> frameNos = new LinkedHashSet<>();
        for (ItemCandidate item : pageItems) {
            Long derivativeRawSn = item.derivativeRawSn();
            // 실삭제된 항목은 프레임 자체가 사라졌다 — 조회하지 않는다(죽은 링크 차단 + 무의미한 쿼리 제거).
            boolean purged = isPurged(discards.get(item.aug().getDataAugSn()));
            // 신고 보류 판정은 <외부 위탁 항목만> 여기서 한다. 해상도 항목은 항목 구성 단계
            // (resolveItems)에서 이미 판정·제외됐으므로 여기 남아 있는 것은 전부 보류가 아니다
            //  — 같은 영상에 두 번 묻지 않는다(PK lookup 이라도 항목 수만큼 늘어난다).
            boolean withheld = !purged && item.external() && derivativeRawSn != null
                    && deidentReportGate.isUnderDeidentReport(derivativeRawSn);
            if (withheld) {
                // 외부 위탁 항목은 <항목은 남기고 이미지만> 뺀다 — 항목을 지우면 그 증강의 결정 상태·
                // 생성 조건까지 화면에서 사라져 REVIEWER 가 무슨 일이 있었는지 알 수 없다.
                log.warn("[Augment] derivative result withheld — deident report open rawSn={}", derivativeRawSn);
            }

            Page<LsDataSrc> framePage = null;
            if (derivativeRawSn != null && !withheld && !purged) {
                // 페이징 단위 = "쌍이 성립하는 파생 프레임". 파생 프레임 전체를 페이징하면 부모 비식별
                // 경로가 없는 프레임이 표시 단계에서 드롭돼 총량(페이저·"총 처리 이미지" 표기)과 실제
                // 카드 수가 어긋난다(빈 페이지·과대 표기).
                framePage = srcRepository.findPairableDerivativeFrames(derivativeRawSn, jobId, pageable);
                framePage.getContent().forEach(f -> frameNos.add(f.getFrameNo()));
            }
            slices.add(new ItemSlice(item.aug(), item.external(), derivativeRawSn, framePage, withheld));
        }

        // 2차 — 부모 프레임을 (RAW_SN, FRM_NO) 배치 IN 조회 1회로 해결한다(H4 N+1 회피).
        Map<Long, Long> parentSrcSnByFrameNo = loadParentSrcSnByFrameNo(jobId, frameNos);
        // 3차 — 외부 위탁 항목의 최신 검수 행도 배치 1회(폐기 축은 0차에서 이미 읽었다).
        Map<Long, LsDataAugRvw> reviews = loadLatestReviews(externalAugsOnPage);

        List<AugmentResultItemResponse> results = new ArrayList<>(slices.size());
        for (ItemSlice slice : slices) {
            results.add(toResultItem(jobId, cctvName, slice, parentSrcSnByFrameNo, reviews, discards));
        }
        return paging.response(jobId, status, results, itemTotal, requestedAt);
    }

    /**
     * 항목 축의 <b>정식 원소</b>를 확정한다 — <b>드롭 술어를 여기서 전부 소진</b>한다.
     *
     * <h3>왜 슬라이스 루프가 아니라 이 단계인가 ("센 뒤 드롭" 결함)</h3>
     * <p>구 구현은 {@code itemTotal} 을 먼저 확정한 <b>뒤</b> 페이지 슬라이스 루프에서 해상도 파생만
     * 두 지점({@code 파생 미해석} · {@code 신고 보류})에서 드롭했다. 그 결과 {@code totalElements} 가
     * 실제 {@code results} 합계보다 커져 FE 페이저가 <b>존재하지 않는 페이지</b>를 그렸다(마지막 페이지가
     * 비거나 카드 수가 표기보다 적음). 총량과 항목은 <b>같은 집합</b>에서 나와야 한다.
     *
     * <p>비용은 늘지 않는다 — {@link #resolveDerivativeRawSns} 는 종전에도 jobId 단위 <b>1회</b>
     * 선조회였고, 신고 게이트 대상은 해상도 파생뿐이라 영상당 최대 3건(프리셋 3종)이다.
     *
     * <h3>⚠ 외부 위탁(WINTER/NIGHT/RAIN)은 이 필터에 걸리지 않는다 (의도된 비대칭)</h3>
     * <p>신고 구간이어도 <b>항목은 남기고 이미지만</b> 뺀다({@code withheld} + {@code resultState=WITHHELD}).
     * 결정 상태·생성 조건이 화면에서 사라지면 REVIEWER 가 무슨 일이 있었는지 알 수 없기 때문이다.
     * "일관성" 을 이유로 해상도와 통일하지 말 것. 파생 매핑({@code NEW_RAW_SN})이 없는 외부 위탁 항목도
     * 드롭하지 않는다 — 생성 조건(R9) 역추적 경로가 그 항목뿐이다.
     */
    private List<ItemCandidate> resolveItems(Long jobId, List<LsDataAug> augs) {
        List<LsDataAug> ordered = new ArrayList<>(externalAugs(augs));
        List<LsDataAug> resolutionAugs = generatedResolutionAugs(augs);
        ordered.addAll(resolutionAugs);

        // 해상도 항목이 있을 때만 1회 해석(종전과 동일한 조회 횟수).
        Map<String, Long> resolutionRawSnByType =
                resolutionAugs.isEmpty() ? Map.of() : resolveDerivativeRawSns(jobId);

        List<ItemCandidate> items = new ArrayList<>(ordered.size());
        for (LsDataAug aug : ordered) {
            boolean external = AugmentPrompts.isExternalAugType(aug.getAugTypeCd());
            if (external) {
                // V155 매핑. null 이면 아직/영영 파생이 없거나 V155 이전 요청 — 추정하지 않는다.
                items.add(new ItemCandidate(aug, true, aug.getNewRawSn()));
                continue;
            }
            Long derivativeRawSn = resolutionRawSnByType.get(aug.getAugTypeCd());
            if (derivativeRawSn == null) {
                // 해상도 항목은 파생을 못 찾으면 노출하지 않는다. 도달 조건은 셋이다 —
                //   ① AUG_TYPE_CD 미채움(V149 백필이 종류를 판별 못 한 과거 파생) ② 계약 밖 코드
                //   (레거시 'RESOLUTION' 등) ③ 파생이 아직 비식별 확정(DE_IDNTF_YN='Y') 전.
                // 구 구현의 "VMS_CLIP_ID 마커 미해석" 은 판별축이 컬럼으로 바뀌며 소멸했지만,
                // 위 세 경로가 남아 있어 이 분기 자체는 유효하다(총량 정합의 근거).
                log.warn("[Augment] resolution derivative video not found jobId={} type={}",
                        jobId, sanitize(aug.getAugTypeCd()));
                continue;
            }
            if (deidentReportGate.isUnderDeidentReport(derivativeRawSn)) {
                // 해상도 항목은 통째로 제외한다(내부 생성물 — 결정 UI 가 없어 남길 이유가 없다).
                log.warn("[Augment] derivative result withheld — deident report open rawSn={}", derivativeRawSn);
                continue;
            }
            items.add(new ItemCandidate(aug, false, derivativeRawSn));
        }
        return items;
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
     * <p>응답 조립을 값 객체에 두는 이유: {@link #result} 는 반환 지점이 3곳(항목 0건 조기 반환 ·
     * 페이지 범위 밖 조기 반환 · 정상 경로)이라, 총량 필드를 반환 지점마다 손으로 채우면
     * <b>한 곳만 빠뜨려도 그 경로에서 페이저가 사라진다</b>(이번 결함의 재발 형태).
     * 조립 경로를 하나로 묶어 그 가능성을 없앤다.
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

        /**
         * 항목 축 총량({@code itemTotal})으로 페이저 근거 4필드를 채워 응답을 만든다.
         *
         * <p>{@code requestedAt} 은 페이징과 무관한 <b>잡 단위</b> 값이지만 조립을 이 한 곳으로 모아
         * <b>모든 반환 지점이 반드시 넘기게</b> 한다 — 조기 반환 경로에서만 비는 결함을 컴파일러가 막는다.
         */
        AugmentResultResponse response(Long jobId, String status,
                                       List<AugmentResultItemResponse> results, long itemTotal,
                                       LocalDateTime requestedAt) {
            return new AugmentResultResponse(jobId, status, results, MESSAGE, page, size,
                    itemPage, itemSize, itemTotal, totalPages(itemTotal), requestedAt);
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
     *   검수 행 없음 + 폐기 표식 있음 → REJECTED                           … 표식 자체가 반려의 증거
     *   검수 행 없음 + 생성 영구실패  → REJECTED                           … 실패를 실패로 보인다
     *   검수 행 없음 + 생성 성공      → PENDING                            … 결정 대기(버튼 노출)
     *   검수 행 없음 + 생성 중        → PENDING (단 reviewable=false)      … FE 는 카드를 감춘다
     *   검수 행 없음 + 취소 종결      → 그 생성 상태 (CANCELED)            … 취소를 취소로 보인다
     * </pre>
     *
     * <p><b>"검수 행 없음 + 폐기 표식 있음" 줄이 핵심이다</b>(DEV_FIX MEDIUM). 실삭제 스윕은 한
     * 트랜잭션에서 {@code LS_DATA_AUG_RVW} → {@code LS_DATA_AUG} 를 지우고 비석에 {@code DEL_DT} 를
     * 찍는다. 조회 트랜잭션이 증강 스냅샷을 잡은 뒤 그 커밋이 끼어들면 <b>검수 행만 사라진 상태</b>를
     * 읽어, 구 구현은 같은 응답에서 "영구히 삭제됨({@code resultState=PURGED})" 과 "아직 결정 대기이니
     * 채택/반려하라({@code decision=PENDING} + {@code reviewable=true})" 를 <b>동시에</b> 말했다.
     * <b>폐기 표식의 존재 자체가 "사람이 반려했다" 는 증거</b>이므로(표식은 반려 트랜잭션에서만 생긴다),
     * 검수 행이 없는 것은 결정이 없어서가 아니라 스윕이 지웠기 때문이다.
     *
     * <p>판정 입력은 <b>매퍼가 돌려준 폐기 축</b>이다(원시 행이 아니라). 복구된 항목은 매퍼가
     * {@code null} 을 주므로 이 분기에 걸리지 않는다 — 복구 후에는 다시 채택/반려를 고를 수 있어야
     * 하며 그것이 의도된 동작이다.
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
    private static String resolveDecision(LsDataAug aug, LsDataAugRvw review, boolean discarded) {
        if (review != null) {
            return review.getRvwSttsCd();
        }
        if (discarded) {
            return LsDataAugRvw.STTS_REJECTED;
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
     *
     * <p><b>{@code purged} 가 최우선</b>이다(가장 영구한 사실). 실삭제된 항목은 프레임 쌍이 0장인데,
     * 그것만 보면 {@code PREPARING_FRAMES}("반입 중, 곧 옴")로 계산돼 같은 응답의
     * {@code discard.purged=true}("영영 없음")와 정면으로 모순된다.
     *
     * <h3>파생 매핑 부재({@code DERIVATIVE_UNLINKED})는 {@code withheld} <b>뒤</b>, 총량 판정 <b>앞</b></h3>
     * <p><b>앞(총량 판정보다)인 이유</b>: 매핑이 없으면 프레임 쌍이 <b>영원히</b> 0장이라 총량 판정에
     * 맡기면 {@code PREPARING_FRAMES}("곧 옴")로 떨어진다 — 이번 결함 그 자체다.
     *
     * <p><b>뒤(신고 보류보다)인 이유</b>: 신고 보류는 <b>해소 가능한</b> 사실이고 개인정보 노출 대응이라
     * 더 시급하다. 매핑 부재는 되돌릴 수 없는 배경 사실이므로, 둘이 겹치면 사용자가 지금 조치할 수 있는
     * 쪽을 보여준다. (현재 형상에서 둘은 <b>상호배타</b>다 — {@code withheld} 계산 자체가
     * {@code derivativeRawSn != null} 을 요구하므로 매핑이 없으면 보류로 판정될 수 없다. 그럼에도
     * 순서를 명시해 두는 것은 보류 판정 조건이 넓어질 때의 우선순위를 남기기 위함이다.)
     *
     * <p><b>⚠ 외부 위탁 전용</b>: 해상도 파생은 매핑을 못 찾으면 {@link #resolveItems} 가 항목 자체를
     * 드롭하므로(총량 정합) 여기 도달하는 해상도 항목은 매핑이 항상 있다. {@code slice.external()} 조건을
     * 빼면 그 불변식에 기대는 판정이 조용히 흐려진다.
     *
     * @param slice  결과 항목 1건의 재료 — 유형(외부/해상도) · 파생 매핑 · 보류 여부를 함께 들고 있다.
     *               판정 입력을 낱개 인자로 늘리는 대신 슬라이스를 그대로 받는다(호출부가 이미 보유).
     * @param purged 폐기 유예 경과로 <b>실삭제</b>됐는가(비교 이미지가 영구히 없다)
     */
    private static String resolveResultState(ItemSlice slice, long totalFramePairs, boolean purged) {
        LsDataAug aug = slice.aug();
        if (purged) {
            return AugmentResultItemResponse.STATE_PURGED;
        }
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
        if (slice.withheld()) {
            return AugmentResultItemResponse.STATE_WITHHELD;
        }
        if (slice.external() && slice.derivativeRawSn() == null) {
            // 생성은 성공했는데(위 분기들을 통과했다) 증강 행 ↔ 파생 영상 매핑이 없다 = V155 이전
            // 그랜드퍼더링. 신규 데이터에서는 파생 RAW INSERT 와 같은 트랜잭션에서 NEW_RAW_SN 이
            // 채워지므로("신규 파생인데 NULL" 은 커밋될 수 없다 — LsDataAug javadoc) 이 분기는
            // 구 데이터 전용이고, 그 항목의 프레임 쌍은 영원히 0장이다. 백필로 되살리지 않는다
            // (시각 기반 역추정 = 다른 요청의 파생본, V155 가 폐기한 방법).
            return AugmentResultItemResponse.STATE_DERIVATIVE_UNLINKED;
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
     * <b>이 축만으로는 부족</b>하다 — 결과물 실재 축은 {@link LsDataAug#isGenerationSucceeded()} 가,
     * <b>폐기 축</b>은 {@link #isPurged}/폐기 표식 존재 여부가 담당한다(호출부 {@link #toResultItem}
     * 참조). 특히 실삭제 스윕은 검수 행을 <b>먼저</b> 지우므로, 이 축만 보면 이미 사라진 항목이
     * "미결정" 으로 보인다.
     */
    private static boolean isUndecided(LsDataAugRvw review) {
        return review == null || LsDataAugRvw.STTS_PENDING.equals(review.getRvwSttsCd());
    }

    /**
     * DATA_AUG_SN → <b>최신 검수 행</b> (배치 1회 조회 — N+1 회피). 빈 입력은 조회하지 않는다.
     *
     * <p><b>"최신" 의 정의는 {@link LsDataAugRvw#RECENCY_ORDER} 단일 원천</b>이다(DEV_FIX MEDIUM ①).
     * 구 구현은 여기서 {@code RVW_DT} 축으로 <b>자체 판정</b>({@code isNewer})을 했는데, 복구 경로
     * ({@code AugmentDiscardService})는 리포지토리의 {@code REG_DT} 축으로 골랐다. 이 테이블에는
     * {@code DATA_AUG_SN} 유니크가 없어 중복 행이 공존할 수 있으므로 두 축이 <b>다른 행</b>을 골랐고,
     * 그때 화면이 A 행 기준으로 "복구 가능" 을 그리는데 복구 API 는 B 행을 보고 404 를 냈다.
     * 판정 규칙을 여기에 <b>복제하지 말 것</b> — 그 복제가 이 결함의 원인이다.
     */
    private Map<Long, LsDataAugRvw> loadLatestReviews(List<LsDataAug> augs) {
        if (augs.isEmpty()) {
            return Map.of();
        }
        List<Long> augSns = augs.stream().map(LsDataAug::getDataAugSn).toList();
        Map<Long, LsDataAugRvw> latest = new HashMap<>();
        for (LsDataAugRvw rvw : reviewRepository.findByDataAugSnIn(augSns)) {
            latest.merge(rvw.getDataAugSn(), rvw, LATEST_REVIEW_WINS);
        }
        return latest;
    }

    /** 같은 증강에 검수 행이 여러 건이면 {@link LsDataAugRvw#RECENCY_ORDER} 기준 최신이 이긴다. */
    private static final BinaryOperator<LsDataAugRvw> LATEST_REVIEW_WINS =
            BinaryOperator.maxBy(LsDataAugRvw.RECENCY_ORDER);

    /**
     * DATA_AUG_SN → <b>폐기 축 응답</b> (배치 1회 조회 — N+1 회피). 빈 입력은 조회하지 않는다.
     *
     * <p>정렬({@code DATA_AUG_DSCD_SN DESC})은 리포지토리가 확정하므로 여기서는 <b>먼저 만난 행</b>만
     * 취한다({@code putIfAbsent}). 반려마다 새 행이 쌓이고 복구는 행을 닫을 뿐 지우지 않으므로,
     * "최신 1행" 선택이 흔들리면 <b>이미 복구된 옛 표식</b>이 최신으로 뽑혀 멀쩡한 항목이 "곧 삭제됨"
     * 으로 표시된다. 그 행이 지금도 폐기 상태인지의 판정은 {@link AugmentDiscardStateMapper} 가 한다.
     *
     * <p><b>매퍼 판정을 조회 시점에 끝내는 이유</b>: 이 값은 응답 조립뿐 아니라 <b>프레임 조회 여부</b>
     * (실삭제분은 건너뛴다)와 {@code decision}/{@code reviewable} 판정에도 쓰인다. 원시 행을 들고
     * 다니다 지점마다 "지금도 폐기 상태인가" 를 다시 해석하면 판정이 이원화된다 — 복구된 항목은 매퍼가
     * {@code null} 을 주므로, 이 맵에 값이 있다는 것 자체가 "지금 폐기 상태" 를 뜻하게 만든다.
     */
    private Map<Long, AugmentDiscardStateResponse> loadDiscardStates(List<LsDataAug> augs) {
        if (augs.isEmpty()) {
            return Map.of();
        }
        List<Long> augSns = augs.stream().map(LsDataAug::getDataAugSn).toList();
        Map<Long, LsDataAugDscd> latest = new HashMap<>();
        for (LsDataAugDscd row : discardRepository.findByDataAugSnInOrderByDataAugDscdSnDesc(augSns)) {
            latest.putIfAbsent(row.getDataAugSn(), row);
        }
        Map<Long, AugmentDiscardStateResponse> states = new HashMap<>();
        latest.forEach((augSn, row) -> {
            AugmentDiscardStateResponse state = discardStateMapper.toResponse(row);
            if (state != null) {
                states.put(augSn, state);
            }
        });
        return states;
    }

    /** DB 실삭제가 커밋된 항목인가 — 프레임 조회 스킵·조립 방어의 단일 판정. */
    private static boolean isPurged(AugmentDiscardStateResponse discard) {
        return discard != null && discard.purged();
    }

    /**
     * 결과 항목 1건의 확정 재료 — <b>외부 위탁·해상도 파생 공통</b>.
     *
     * @param external        외부 위탁(WINTER/NIGHT/RAIN)인가 — 결정 UI 유무·짝짓기 근거가 갈린다
     * @param derivativeRawSn 파생 영상 RAW_SN (외부=NEW_RAW_SN / 해상도=AUG_TYPE_CD 컬럼 매칭, 미해석 시 null)
     * @param framePage       쌍 성립 파생 프레임 슬라이스 (파생 없음·신고 보류 시 null)
     * @param withheld        파생이 비식별 누락 신고 구간이라 이미지를 보류했는가
     */
    private record ItemSlice(LsDataAug aug, boolean external, Long derivativeRawSn,
                             Page<LsDataSrc> framePage, boolean withheld) {
    }

    /**
     * 항목 축의 원소 — <b>드롭 술어를 이미 통과한</b> 결과 항목 1건.
     *
     * <p>{@code itemTotal} 은 이 목록의 크기이고 {@code results} 는 그 부분집합(페이지)이라, 두 값이
     * 구조적으로 같은 집합에서 나온다. 슬라이스 단계에서 항목을 더 걸러내면 이 불변식이 깨진다.
     *
     * @param derivativeRawSn 파생 영상 RAW_SN. 해상도 항목은 <b>항상 non-null</b>(null 이면 이미 제외됨),
     *                        외부 위탁은 매핑이 없으면 null 일 수 있다(항목은 유지).
     */
    private record ItemCandidate(LsDataAug aug, boolean external, Long derivativeRawSn) {
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
     *       + {@code ensurePending})과 <b>정확히 같은 두 축</b>으로 계산한다.
     *       <p>여기에 <b>폐기 축</b>이 하나 더 붙는다(DEV_FIX MEDIUM): 폐기 표식이 살아 있으면 이미
     *       사람이 반려한 항목이고, 실삭제까지 됐다면 그 버튼을 누르는 순간 404 다. 스윕이 검수 행을
     *       먼저 지우므로 검수 축만으로는 "미결정" 으로 보인다 — {@code decision} 과 <b>같은 입력</b>
     *       (매퍼가 돌려준 폐기 축)으로 판정해 한 응답 안에서 두 값이 어긋나지 않게 한다.</li>
     *   <li><b>해상도 파생</b> — 검수 대상이 아니라 내부 생성물이므로 {@code decision} 은 생성 완료를
     *       뜻하는 {@code ACCEPTED} 고정, {@code reviewable=false}, {@code prompt=null}(외부 위탁이
     *       아니라 내부 ffmpeg 리스케일이라 생성 조건이 없다).</li>
     * </ul>
     */
    private AugmentResultItemResponse toResultItem(Long jobId, String cctvName, ItemSlice slice,
                                                   Map<Long, Long> parentSrcSnByFrameNo,
                                                   Map<Long, LsDataAugRvw> reviews,
                                                   Map<Long, AugmentDiscardStateResponse> discards) {
        LsDataAug aug = slice.aug();
        if (!slice.external()) {
            // 해상도 파생은 내부 생성물이라 accept/reject 가 차단돼 폐기 표식이 생길 수 없다 → 축 없음.
            List<AugmentFramePairResponse> pairs = buildFramePairs(jobId, slice, parentSrcSnByFrameNo);
            long totalFramePairs = totalFramePairs(slice);
            return new AugmentResultItemResponse(
                    aug.getDataAugSn(), jobId, cctvName, aug.getAugTypeCd(), pairs,
                    LsDataAug.STTS_ACCEPTED, aug.getRegDt(), null,
                    slice.derivativeRawSn(), totalFramePairs, false, null,
                    resolveResultState(slice, totalFramePairs, false), null,
                    // 해상도 파생은 검수·폐기 체계 밖 — 되돌릴 결정 자체가 존재할 수 없다.
                    false);
        }
        LsDataAugRvw review = reviews.get(aug.getDataAugSn());
        AugmentDiscardStateResponse discard = discards.get(aug.getDataAugSn());
        // 지금 폐기 상태인가(열림 또는 실삭제). 복구된 항목은 매퍼가 null 을 주므로 여기서 false 다.
        boolean discarded = discard != null;
        boolean purged = isPurged(discard);

        // ★ 조립 단계 방어 — 실삭제분에는 프레임 쌍을 싣지 않는다.
        //   조회 순서(폐기 먼저)로 창을 좁혔지만 닫히지는 않는다. 프레임 슬라이스를 잡은 뒤 스윕이
        //   커밋되면 이미 CASCADE 로 사라진 srcSn 을 가리키는 이미지 URL 이 응답에 실려(클릭 시 404)
        //   같은 응답의 resultState=PURGED("영구히 없음")와 정면으로 모순된다. 총량도 0 으로 맞춘다 —
        //   framePairs=[] 인데 totalFramePairs>0 이면 FE 페이저가 존재하지 않는 페이지를 그린다.
        List<AugmentFramePairResponse> pairs =
                purged ? List.of() : buildFramePairs(jobId, slice, parentSrcSnByFrameNo);
        long totalFramePairs = purged ? 0L : totalFramePairs(slice);

        return new AugmentResultItemResponse(
                aug.getDataAugSn(), jobId, cctvName, aug.getAugTypeCd(), pairs,
                resolveDecision(aug, review, discarded),
                review == null ? null : review.getRvwDt(),
                review == null ? null : review.getRejectRsn(),
                // V155 매핑. 생성 경로 2곳이 파생 RAW 를 INSERT 한 같은 트랜잭션에서 채우므로 추정이 없다
                // (V155 이전 요청은 백필하지 않아 여전히 null).
                aug.getNewRawSn(), totalFramePairs,
                // 폐기 표식이 살아 있는 항목은 결정 대상이 아니다 — 표식 자체가 반려의 증거이고,
                // 실삭제분이면 그 버튼을 누르는 순간 404 다(FE 는 이 값으로 버튼을 그린다).
                //
                // ★ 파생 매핑이 없는 그랜드퍼더링 항목(resultState=DERIVATIVE_UNLINKED)도 여기서
                //   막지 않는다 — 의도된 판단이며 "비교 이미지 0장인데 승인 가능한 건 버그" 로 보고
                //   되돌리지 말 것. reviewable=false 로 막으면 그 항목들은 <영구히> 결정 불가가 되고,
                //   등재 게이트가 리뷰 축(LS_DATA_AUG_RVW.RVW_STTS_CD='ACCEPTED')이라 파생 영상이
                //   작업목록에 영영 오르지 못한다. 이미 배정된 WORKER 의 영상이 화면에서 사라지는
                //   <고아 배정>이 되며, 이는 CLAUDE.md 가 그랜드퍼더링에서 경계한 상황 그대로다.
                //   화면은 대신 resultState 로 "비교 이미지를 제공할 수 없다" 는 사실을 알린다.
                aug.isGenerationSucceeded() && isUndecided(review) && !discarded,
                // R9 — 이 결과물을 만든 생성 조건 원문. 결정(채택/반려) <b>이전</b>에 확인 가능해야 한다.
                aug.getPromptCn(),
                resolveResultState(slice, totalFramePairs, purged),
                discard,
                // 화면이 복구 버튼을 그리는 유일한 근거 — 여기서 <BE 사전조건 그대로> 계산해 내려준다.
                resolveRestoreEligible(review, discard));
    }

    /**
     * 복구 API 가 <b>지금 이 항목을 받아주는가</b> — 복구 사전조건을 <b>서버가 직접</b> 계산해 내린다.
     *
     * <h3>왜 화면에 맡기지 않는가 (DEV_FIX HIGH-①)</h3>
     * <p>FE 는 {@code decision === 'REJECTED' && discard?.purged !== true} 로 버튼을 그렸다. 그런데
     * {@link #resolveDecision} 의 {@code REJECTED} 는 <b>세 입력</b>에서 나오고(사람의 반려 · 폐기 표식 ·
     * 생성 영구 실패) 복구 API 는 <b>앞 둘만</b> 받는다. dead-letter 로 끝난 증강은 검수 행도 표식도 없어
     * {@code restoreWithoutMark} 가 <b>항상 404</b> 를 내는데, 재조회해도 세 필드가 그대로라 버튼이 남고
     * <b>무한 재시도</b>가 됐다(서버측 중복 차단·속도 제한을 두지 않는 확정 정책이라 막는 층이 없다).
     * 응답 필드로 세 값을 더 얹어 화면이 조건을 <b>재유도</b>하게 두면 같은 드리프트가 반복되므로,
     * 판정을 <b>사전조건과 같은 입력</b>으로 한 곳에서 끝낸다.
     *
     * <h3>사전조건 정본 = {@code AugmentDiscardService} 의 <b>두 경로 모두</b></h3>
     * <p>두 경로는 진입 분기만 다를 뿐 <b>수용 조건이 같다</b> — 둘 다 마지막에 검수 행을
     * {@code REJECTED → PENDING} 으로 되돌리기 때문이다.
     * <pre>
     *   실삭제됨(DEL_DT)                          → 409  … 받지 않는다
     *   열린 표식 O + 최신 검수 행 REJECTED        → 200  … restore()          (표식 해제 + reopen)
     *   열린 표식 O + 검수 행 없음                 → 409  … restore()          "복구할 검수 이력이 없습니다"
     *   열린 표식 O + 검수 행 REJECTED 아님        → 409  … reopen 가드
     *   표식 X   + 최신 검수 행 REJECTED           → 200  … restoreWithoutMark (그랜드퍼더링 폴백)
     *   표식 X   + 검수 행 없음/REJECTED 아님      → 404  … restoreWithoutMark 의 filter
     * </pre>
     *
     * <h3>★ "열린 표식 있음" 만으로는 부족하다 (DEV_FIX LOW-②)</h3>
     * <p>구 구현은 표식이 열려 있으면 무조건 {@code true} 를 냈다. 그런데 {@code restore()} 는 표식을
     * 닫은 <b>뒤</b> 검수 행을 찾아 {@code reopen()} 을 부르고, 그 행이 없거나
     * {@link LsDataAugRvw#STTS_REJECTED} 가 아니면 <b>409</b> 다({@code LsDataAugRvw#reopen} 가드 ·
     * {@code "복구할 검수 이력이 없습니다."}). 즉 표식 축만 보면 <b>또 다른 죽은 버튼</b>이 남는다 —
     * 이번 결함 클래스의 반복이다. 그래서 두 경로의 <b>공통 수용 조건</b>인 "최신 검수 행이 REJECTED"
     * 를 표식 유무와 무관하게 요구한다.
     *
     * <p>운영 정상 형상에서는 이 강화가 <b>버튼을 줄이지 않는다</b> — 표식은 {@code reject()}
     * 트랜잭션 안에서만 생기므로 열린 표식에는 항상 {@code REJECTED} 검수 행이 동반한다. 값이 갈리는
     * 것은 스윕이 검수 행만 먼저 지운 좁은 창처럼 <b>실제로 복구가 실패하는</b> 형상뿐이다.
     *
     * <p><b>추가 조회 0</b> — 두 입력({@code reviews} · {@code discards})은 호출부가 이미 배치 1회씩
     * 읽어 들고 있는 맵이다. 여기서 리포지토리를 새로 부르면 그 배치가 곧바로 N+1 로 되돌아간다.
     * {@code review} 의 "최신" 판정도 복구 경로와 <b>같은 정의</b>({@link LsDataAugRvw#RECENCY_ORDER})
     * 를 쓴다 — 정의가 갈리면 같은 식을 써도 두 판정이 다른 행을 본다(MEDIUM ①).
     *
     * <p><b>사람의 반려 뒤 dead-letter</b> 같은 드문 조합은 검수 행이 {@code REJECTED} 로 남아 있어
     * {@code true} 다 — <b>그것이 맞다</b>(BE 가 실제로 받아준다). 반대로 화면이
     * {@code resultState=GENERATION_FAILED} 를 제외 조건으로 썼다면 <b>복구 가능한데도</b> 버튼이
     * 사라졌을 것이다.
     *
     * @param review  최신 검수 행 (없으면 null)
     * @param discard <b>매퍼가 판정한</b> 폐기 축 — {@code null} 이면 지금 폐기 상태가 아니다(복구됨 포함)
     */
    private static boolean resolveRestoreEligible(LsDataAugRvw review, AugmentDiscardStateResponse discard) {
        if (isPurged(discard)) {
            // 유예가 지나 실삭제됐다 — 되돌릴 대상 자체가 없다(409).
            //
            // ⚠ 열린 표식(discard != null && !purged)은 여기서 분기하지 않는다. 실삭제 클레임
            //   (DEL_PRCS_DT)이 잡혀 있어도 복구는 성립하므로(최종 DELETE 가 표식을 재평가한다)
            //   discard.restorable 의 보수적 힌트와 이 축이 갈리는 것은 여전히 정상이다 — 다만 그
            //   판단은 "표식이 열렸는가" 가 아니라 아래 검수 행 조건으로 내린다.
            return false;
        }
        // 두 경로의 공통 수용 조건 — restore()·restoreWithoutMark() 모두 이 행을 reopen 한다.
        return review != null && LsDataAugRvw.STTS_REJECTED.equals(review.getRvwSttsCd());
    }

    /** 총량 = 쌍이 성립하는 프레임 수(= 실제 표시 가능한 카드 수). 파생 프레임 총수가 아니다. */
    private static long totalFramePairs(ItemSlice slice) {
        return slice.framePage() == null ? 0L : slice.framePage().getTotalElements();
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
     * <p><b>판별 단일 원천은 파생이 스스로 보유한 {@code LS_DATA_RAW.AUG_TYPE_CD} 컬럼</b>(V148 신설 +
     * V149 백필, 신규 파생은 {@code createFromAugment}/{@code createFromResolution} 이 생성 시점에 채운다).
     * 구 구현은 매핑 전용 컬럼이 없어 {@code VMS_CLIP_ID} 마커를 역파싱했고, 실데이터 포맷 드리프트
     * (이중 접두 {@code _RESL_RESL_480P_} · 구형 {@code _RES_RES_480P_})를 흡수하기 위한 중앙 파서가
     * 필요했다. 컬럼이 생긴 뒤로는 <b>clipId 포맷이 무엇이든 판별에 영향을 주지 않는다</b>.
     *
     * <p>{@code deIdntfYn='Y'} 확정본만 대상 — 미확정 파생의 프레임은 비식별 서빙 대상이 아니다.
     * 같은 프리셋 파생이 복수면(재시도 잔존) 최신(최대 RAW_SN)을 택한다.
     *
     * <p>종류를 알 수 없는 확정 파생(컬럼 미채움 null · FE 계약 밖 코드)은 <b>조용히 넘기지 않고</b>
     * 식별 가능한 형태(파생 RAW_SN + clipId)로 WARN 로그를 남긴다 — 결과 누락 시 원인 추적 지점.
     * 파서 시절에도 같은 조건(판별 실패)에서 WARN 을 남겼다.
     */
    private Map<String, Long> resolveDerivativeRawSns(Long parentRawSn) {
        Map<String, Long> byType = new HashMap<>();
        for (LsDataRaw derivative : videoRepository.findAllByOrgnlRawSnOrderByRawSnAsc(parentRawSn)) {
            if (!"Y".equals(derivative.getDeIdntfYn())) {
                continue;
            }
            String type = derivative.getAugTypeCd();
            if (!LsDataAug.isContractAugType(type)) {
                // 판별 불가(컬럼 미채움 · 레거시 'RESOLUTION' · 미지 코드) — 관측 가능하게 남긴다.
                log.warn("[Augment] derivative type unresolved — parentRawSn={} derivativeRawSn={} clipId={}",
                        parentRawSn, derivative.getRawSn(), sanitize(derivative.getVmsClipId()));
                continue;
            }
            if (!type.startsWith(LsDataAug.RESL_PREFIX)) {
                continue; // 외부 위탁 증강(WINTER/NIGHT/RAIN) 파생은 본 경로 대상이 아니다(정상 스킵).
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
