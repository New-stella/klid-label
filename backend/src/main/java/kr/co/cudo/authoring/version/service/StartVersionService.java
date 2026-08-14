package kr.co.cudo.authoring.version.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.security.TokenClaims;
import kr.co.cudo.authoring.label.dto.LabelResponse;
import kr.co.cudo.authoring.label.entity.LsLabel;
import kr.co.cudo.authoring.label.repository.LsLabelRepository;
import kr.co.cudo.authoring.label.service.LabelAccessGuard;
import kr.co.cudo.authoring.version.config.StartVersionProperties;
import kr.co.cudo.authoring.version.dto.VersionLabelsResponse;
import kr.co.cudo.authoring.version.dto.VideoVersionItem;
import kr.co.cudo.authoring.version.repository.LsLabelVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * R6 — 영상 단위 「시작 버전 선택」의 <b>불러오기</b>(읽기 전용).
 *
 * <h3>2단계다 — 불러오기와 확정 저장이 다른 요청이다 (2026-08-11 확정, 구속)</h3>
 * <ol>
 *   <li><b>불러오기</b>(이 서비스, API-195 {@code GET /v1/videos/{rawSn}/versions/{version}/labels})
 *       — 고른 회차 시점의 라벨과 프레임 폐기 상태를 <b>영상 전체 범위로 읽어서 돌려주기만</b> 한다.
 *       <b>서버에는 아무것도 쓰지 않는다</b>: 감사 이력도, 상태 전이도, 재검토 표시도, 산출 재생성도 없다.</li>
 *   <li><b>확정 저장</b>(API-196 {@code PUT /v1/videos/{rawSn}/labels},
 *       {@code label.service.VideoLabelSaveService}) — 화면이 확인한 내용을 한 트랜잭션으로 확정한다.
 *       쓰기는 <b>여기 한 곳뿐</b>이다.</li>
 * </ol>
 *
 * <p><b>구 {@code PUT /v1/videos/{rawSn}/start-version}(1단계 즉시 적용)은 폐기됐다.</b> 즉시 적용
 * 경로가 함께 남으면 확정 게이트를 우회하는 <b>두 번째 쓰기 경로</b>가 되어, 사용자가 확인하기 전에
 * 라벨이 통째로 과거화되고(승인 영상이면 재검토 표시·산출 전량 재생성까지) 되돌릴 창이 없다.
 *
 * <h3>D4 — 「제거」가 아니라 「재배치」다</h3>
 * 버전 목록 · 버전 간 diff · 작업본 diff · 롤백은 <b>폐기되지 않는다</b>. 프레임 단위 계약
 * ({@code /v1/frames/{srcSn}/versions} · {@code /v1/versions/{version}/diff} · {@code /diff-with-working}
 * · {@code /rollback})은 그대로이며 화면이 이 모달 안으로 옮겨 담았을 뿐이다
 * (선택 전 미리보기 = 기존 {@code /diff-with-working}, 확정 = API-196).
 *
 * <h3>조회 규칙 — 회차↔스냅샷 <b>매핑</b>에서 「회차 ≤ N 중 최대」</h3>
 * 판정 원천은 {@code LS_OUTPUT_VER_SNPSH}(V183) 한 곳이다. 내용이 바뀌지 않은 프레임은
 * {@code (DATA_SRC_SN, VERSION_HASH)} UNIQUE 때문에 그 회차에 스냅샷이 생기지 않으므로(멱등 skip —
 * 정상 동작) 여전히 "≤ N 중 최대"로 고르지만, <b>기준이 번호가 아니라 매핑에 기록된 회차</b>다.
 *
 * <p><b>왜 {@code VER_NO} 로는 안 되나</b>: 그 컬럼은 값이 하나라 <b>한 스냅샷이 여러 회차의 내용</b>
 * (1:N)임을 담지 못한다. 롤백으로 옛 스냅샷을 재활성한 뒤 재승인·재산출하면 그 회차의 실제 내용은
 * 옛 스냅샷인데 번호는 갱신되지 않아, 번호 기반 규칙이 <b>그 사이 회차의 비활성 스냅샷</b>을 골라
 * <b>그 회차에 존재한 적 없는 내용</b>을 돌려줬다(예외도 미해결 집계도 없는 조용한 오복원).
 * {@code VER_NO} 는 조회·표시(회차 목록·존재 대조)용으로 <b>남아 있으나 판정에 쓰지 않는다</b>.
 *
 * <p>매핑이 없는 프레임(그 회차 이전에 승인 스냅샷이 한 번도 없던 프레임, 라벨 0건 프레임)은 그
 * 시점을 알 수 없으므로 <b>현재 작업본을 그대로</b> 실어 보내고 {@code resolved=false} 로 드러낸다 —
 * 라벨 0건으로 내려주면 화면이 빈 상태를 그리고 그 위에서 저장할 때 남아 있던 라벨이 통째로 지워진다.
 *
 * <h3>자원 상한 (CWE-770)</h3>
 * 응답 1건이 영상 전 프레임의 라벨 본문을 싣는다. 프레임 수가 설정 상한
 * ({@link StartVersionProperties#maxFrames()})을 넘으면 잘라내지 않고 {@code 400} 으로 거부한다 —
 * 조용히 자르면 화면이 <b>일부 프레임만 담긴 세트</b>를 확정 저장해 절반만 과거화된 혼합 영상이 된다.
 *
 * <h3>보안</h3>
 * <ul>
 *   <li>IDOR (CWE-639): 영상 단위 인가({@link LabelAccessGuard#verifyRawAccess}) + 요청 회차가
 *       <b>그 영상에</b> 실재하는지 대조. 대조 없이 번호를 믿으면 다른 영상 스냅샷이 흘러나온다.</li>
 *   <li>PII (CWE-359): 라벨 좌표는 개인정보 위치를 특정하는 정보다. 비식별 누락 신고 구간은
 *       <b>412</b> 로 차단하며(라벨 조회·작업본 diff 와 같은 판정기) 로그에 본문·좌표를 남기지 않는다.</li>
 *   <li>fail-closed (OWASP A10:2025): 손상 스냅샷은 빈 결과가 아니라 <b>400</b> 이다. 읽지 못한 것을
 *       라벨 0건으로 돌려주면 화면이 "변경 없음"으로 보이고 그 위에서 저장하면 라벨이 지워진다.</li>
 *   <li>CWE-209: 게이트 순서를 <b>신고(412) → 작업락</b> 으로 두어 응답 코드가 잠금 상태를 알려주는
 *       오라클이 되지 않게 한다. 다만 <b>이 경로는 작업락을 보지 않는다</b> — 읽기라서 잠글 것이 없고,
 *       락이 있어도 화면에 올려 보는 것은 무해하다(확정 저장이 락을 판정한다).</li>
 * </ul>
 *
 * @design API-195
 * @design D4
 * @design D5
 * @req R6
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(value = "controlTransactionManager", readOnly = true)
public class StartVersionService {

    private final LabelAccessGuard accessGuard;
    private final kr.co.cudo.authoring.video.repository.VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final LsLabelRepository lsLabelRepository;
    private final LsLabelVersionRepository labelVersionRepository;
    /** 회차↔스냅샷 해석·파싱의 <b>단일 진실원</b> — 확정 저장(API-196)과 같은 규칙을 공유한다. */
    private final VersionSnapshotReader snapshotReader;
    private final StartVersionProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 영상 단위 산출 버전 목록(내림차순) — 「시작 버전 선택」의 선택지.
     *
     * <p>본문을 내려주지 않으므로(번호·건수·시각뿐) 비식별 신고 게이트를 적용하지 않는다 —
     * 프레임 버전 목록({@code VersionService.listVersions})과 같은 판단이다.
     */
    public List<VideoVersionItem> listVideoVersions(Long rawSn, TokenClaims actor) {
        accessGuard.verifyRawAccess(rawSn, actor);
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        return labelVersionRepository.findVideoVersions(rawSn);
    }

    /**
     * API-195 — 고른 산출 회차의 라벨·폐기 상태를 영상 전체 범위로 <b>읽어서</b> 돌려준다.
     *
     * <p>게이트 순서는 <b>인증(401) → 입력(400) → 인가(403) → 존재(404) → 신고(412) → 회차 대조(404)
     * → 프레임 상한(400)</b> 이다. 인가를 앞세워 이후 응답이 미인가자에게 영상 상태를 알려주지 않게 한다.
     *
     * <p><b>쓰기가 하나도 없다</b> — {@code readOnly} 트랜잭션이며 감사·전이·통지·산출을 건드리지 않는다.
     *
     * @design API-195
     */
    public VersionLabelsResponse loadVersionLabels(Long rawSn, Integer versionNo, TokenClaims actor) {
        if (actor == null) {
            throw new CustomException(ErrorCode.UNAUTHORIZED, "인증 토큰이 필요합니다.");
        }
        if (rawSn == null || versionNo == null || versionNo < 1) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "시작 버전 번호가 올바르지 않습니다.");
        }
        accessGuard.verifyRawAccess(rawSn, actor);
        videoRepository.findById(rawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND, "영상을 찾을 수 없습니다."));
        // 라벨 좌표(PII 위치 특정 정보)를 내려주는 경로다 — 라벨 조회와 같은 판정기로 412 차단한다.
        //   인가 이후에 평가하는 프리컨디션이며 역할과 무관하다.
        accessGuard.requireNotUnderDeidentReport(rawSn);
        // CWE-639 — 요청 번호를 신뢰하지 않는다. 그 영상에 실재하는 회차일 때만 진행한다.
        //   ★ 없는 회차에 200 을 주면 화면이 "그 회차 상태"라고 믿고 확정 저장까지 이어진다.
        if (!snapshotReader.versionExists(rawSn, versionNo)) {
            throw new CustomException(ErrorCode.NOT_FOUND, "해당 산출 버전의 스냅샷을 찾을 수 없습니다.");
        }
        // ★ 상한은 <로드 이전>에 판정한다 — 엔티티를 먼저 읽고 세면 거부할 영상도 프레임 행이 전량
        //   힙에 올라온 뒤에야 거부되어, 상한이 지키려던 자원을 지키지 못한다(CWE-770).
        requireWithinFrameLimit(rawSn, srcRepository.countByRawSn(rawSn));

        List<LsDataSrc> frames = srcRepository.findByRawSnOrderByFrameNoAsc(rawSn);
        Map<Long, Long> targetByFrame = snapshotReader.resolveTargets(rawSn, versionNo);

        List<VersionLabelsResponse.Frame> result = new ArrayList<>(frames.size());
        int unresolved = 0;
        // 매핑이 없는 프레임의 작업본은 한 번에 읽는다(N+1 금지). 전부 해석되면 조회 자체가 없다.
        WorkingLabels working = null;
        for (LsDataSrc frame : frames) {
            Optional<VersionSnapshotReader.FrameSnapshot> target =
                    snapshotReader.readFrame(targetByFrame, frame.getSrcSn());
            if (target.isPresent()) {
                result.add(new VersionLabelsResponse.Frame(
                        frame.getSrcSn(), Math.toIntExact(frame.getFrameNo()),
                        target.get().dscdYn(), frame.getLabelVersion(), true, target.get().items()));
                continue;
            }
            // 되돌릴 근거가 없는 프레임 — 없는 과거를 추측하지 않고 <b>현재 작업본</b>을 그대로 싣는다.
            //   그래야 이 세트를 그대로 확정 저장해도 그 프레임은 no-op 이다.
            if (working == null) {
                working = loadWorkingLabels(frames);
            }
            unresolved++;
            result.add(new VersionLabelsResponse.Frame(
                    frame.getSrcSn(), Math.toIntExact(frame.getFrameNo()),
                    frame.getDscdYn() == null ? LsDataSrc.DSCD_NO : frame.getDscdYn(),
                    frame.getLabelVersion(), false, working.itemsOf(frame.getSrcSn())));
        }

        // 식별자·건수만 남긴다(라벨 본문·좌표 금지 — CWE-359).
        log.info("[Version] start version loaded rawSn={} versionNo={} frames={} unresolved={} actor={}",
                rawSn, versionNo, frames.size(), unresolved, actor.sub());
        if (unresolved > 0) {
            // 조용한 누락 방지 — 해석하지 못한 프레임이 있었다는 사실을 운영에서도 관측할 수 있게 한다.
            log.warn("[Version] start version left frames unresolved rawSn={} versionNo={} unresolved={}",
                    rawSn, versionNo, unresolved);
        }
        return new VersionLabelsResponse(rawSn, versionNo, result);
    }

    /**
     * 요청 1건이 실어 보낼 프레임 수 상한 (CWE-770).
     *
     * <p>초과분을 잘라내지 않고 <b>거부</b>한다 — 일부 프레임만 담긴 세트를 화면이 확정 저장하면 서로
     * 다른 회차가 섞인 혼합 영상이 되고, 그것이 이 기능이 막으려는 바로 그 상태다.
     */
    private void requireWithinFrameLimit(Long rawSn, long frameCount) {
        if (frameCount > properties.maxFrames()) {
            log.warn("[Version] start version load rejected — frame limit exceeded rawSn={} frames={} limit={}",
                    rawSn, frameCount, properties.maxFrames());
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "프레임이 너무 많아 한 번에 불러올 수 없습니다(최대 "
                            + properties.maxFrames() + "장).");
        }
    }

    /**
     * 해석되지 않은 프레임에 실어 보낼 <b>현재 작업본</b>을 일괄 조회한다(IN 절 3회 — N+1 금지).
     *
     * <p>라벨 조회 응답({@code GET /v1/frames/{srcSn}/labels})과 <b>같은 결합</b>(라벨 마스터)을
     * 쓴다 — 한쪽만 필드를 빠뜨리면 화면이 같은 라벨을 다르게 그린다. AI 메타는 V6 흡수로 라벨 행이
     * 직접 들고 있어 별도 결합이 필요 없다.
     */
    private WorkingLabels loadWorkingLabels(List<LsDataSrc> frames) {
        List<Long> srcSns = frames.stream().map(LsDataSrc::getSrcSn).toList();
        List<LsDataLbl> labels = new ArrayList<>(labelRepository.findBySrcSnIn(srcSns));
        // 승인 스냅샷 직렬화와 같은 결정적 순서(LBL_SN 오름차순) — 조회(heap) 순서에 의존하면 같은
        //   라벨 집합인데도 화면·확정 저장 페이로드의 순서가 흔들린다.
        labels.sort(java.util.Comparator.comparing(LsDataLbl::getLblSn,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
        List<Long> labelIds = labels.stream()
                .map(LsDataLbl::getLabelId).filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long, LsLabel> lsLabelMap = new HashMap<>();
        if (!labelIds.isEmpty()) {
            lsLabelRepository.findAllById(labelIds)
                    .forEach(master -> lsLabelMap.put(master.getLabelId(), master));
        }
        Map<Long, List<LabelResponse.Item>> byFrame = new HashMap<>();
        for (LsDataLbl label : labels) {
            byFrame.computeIfAbsent(label.getSrcSn(), key -> new ArrayList<>())
                    .add(LabelResponse.Item.from(label,
                            label.getLabelId() != null ? lsLabelMap.get(label.getLabelId()) : null,
                            objectMapper));
        }
        return new WorkingLabels(byFrame);
    }

    /** 프레임별 작업본 라벨 인덱스. */
    private record WorkingLabels(Map<Long, List<LabelResponse.Item>> byFrame) {
        List<LabelResponse.Item> itemsOf(Long srcSn) {
            return byFrame.getOrDefault(srcSn, List.of());
        }
    }
}
