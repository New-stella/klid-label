package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.batch.step.FfmpegFrameExtractor;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.video.service.VideoMetaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 증강 신규 영상의 프레임 <b>재추출 + 라벨/메타 복사 + 비식별 완료 불변식 확정</b>을 단일 트랜잭션으로
 * 수행하는 서비스 (Phase 11 — 증강본 프레임 복사→재추출 비동기 전환).
 *
 * <h3>동기/비동기 경계 (Phase 11 — 반드시 준수)</h3>
 * <p>부모 잠금({@code findByRawSnForUpdate}) + {@code deIdntfYn=='Y'} 게이트 + 콜백 멱등 앵커는
 * {@link AugmentResultService#handle} 의 <b>동기 트랜잭션</b>에서만 유효하다(그 시점의 부모 안전 판정만
 * PII TOCTOU 를 막는다 — CWE-359). 본 서비스는 그 <b>이후</b> 신규 RAW 의 프레임/라벨을 채우는 후속
 * 단계이며, 부모 안전 재판정은 여기서 하지 않는다(하면 동기 판정 이후 창을 재개방한다).
 *
 * <h3>MARKING_READY·deIdntfYn='Y'·SUCCESS procLog 는 추출 성공 후에만</h3>
 * <p>{@link AsyncDeidentifyRunner} 의 "성공 시에만 MARKING_READY" 패턴을 따른다. 프레임 재추출이
 * all-or-nothing 으로 전량 성공하고 라벨/메타 복사까지 마친 <b>같은 커밋</b>에서만
 * {@code deIdntfYn='Y'} + SUCCESS procLog + {@code markMarkingReady()} 를 확정한다. 실패 시 이
 * 트랜잭션(REQUIRES_NEW)이 롤백되어 고아 프레임/라벨/procLog 가 남지 않고, 신규 RAW 는 PENDING 에
 * 머문다(스트리밍/마킹 진입 차단). FAILED 전이는 호출자({@link AsyncAugmentFrameRunner})가
 * 별도 커밋으로 기록한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentFrameExtractionService {

    private final VideoRepository videoRepository;
    private final LsDataAugRepository augRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsDataAugLblMapRepository augLblMapRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final FfmpegFrameExtractor ffmpegFrameExtractor;

    /**
     * 신규 증강 RAW 의 프레임을 증강 파일에서 재추출하고 부모 라벨/메타를 복사한 뒤 비식별 완료 불변식을
     * 확정한다. 전 과정을 단일 REQUIRES_NEW 트랜잭션으로 원자 처리한다(부분 실패 시 전체 롤백).
     *
     * @param newRawSn   증강 신규 RAW_SN (동기 handle 에서 PENDING·deIdntfYn='N' 으로 커밋됨)
     * @param dataAugSn  LS_DATA_AUG PK (라벨 매핑 LS_DATA_AUG_LBL_MAP 적재용)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void extractAndCopy(Long newRawSn, Long dataAugSn) {
        LsDataRaw newRaw = videoRepository.findById(newRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 신규 영상을 찾을 수 없습니다: rawSn=" + newRawSn));

        // 멱등 — 이미 처리(비식별 완료)된 신규 RAW 면 재실행하지 않는다(AFTER_COMMIT 중복 트리거 방어).
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Augment][FrameExtract] already finalized rawSn={} — skip", newRawSn);
            return;
        }

        LsDataAug aug = augRepository.findById(dataAugSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + dataAugSn));

        Long parentRawSn = newRaw.getOrgnlRawSn();
        List<LsDataSrc> parentFrames = srcRepository.findByRawSnOrderByFrameNoAsc(parentRawSn);
        if (parentFrames.isEmpty()) {
            // 동기 단계에서 가드했으므로 정상적으로 도달하지 않는다. 방어적으로 실패 처리.
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "부모 프레임이 없습니다: parentRawSn=" + parentRawSn);
        }

        // 부모 프레임의 디코더 프레임 번호(videoFrameNo) 목록 — 증강 파일에서 동일 번호를 재추출한다.
        List<Long> frameNumbers = parentFrames.stream()
                .map(AugmentFrameExtractionService::frameNumberOf)
                .toList();

        // 이슈2 [LOW] — 부모 프레임에 중복 videoFrameNo 가 있으면 frameNoToNewSrc(Map) 키가 붕괴해 고아
        // 프레임 + 라벨 이중매핑이 발생한다(개수 assert 로는 못 잡음). fail-fast 로 고아 생성을 원천 차단한다.
        long distinctFrameNos = frameNumbers.stream().distinct().count();
        if (distinctFrameNos != frameNumbers.size()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "부모 프레임에 중복 videoFrameNo 가 있습니다: parentRawSn=" + parentRawSn
                            + " frames=" + parentFrames.size() + " distinct=" + distinctFrameNos);
        }

        // 증강 파일에서 프레임 신규 추출(all-or-nothing). 같은 트랜잭션(REQUIRED)에 참여한다.
        List<LsDataSrc> newFrames = ffmpegFrameExtractor.extractByFrameNumbers(newRaw, frameNumbers);

        // Phase 11 #4 — 저장 전 개수 assert. 다르면 예외→롤백→FAILED(고아 라벨 원천 차단).
        if (newFrames.size() != parentFrames.size()) {
            throw new CustomException(ErrorCode.INTERNAL_ERROR,
                    "재추출 프레임 수가 부모와 다릅니다: extracted=" + newFrames.size()
                            + " parent=" + parentFrames.size());
        }

        // Phase 11 #4 — 인덱스 zip 금지. videoFrameNo 기준 명시적 key 매핑.
        //  1) 신규 프레임: videoFrameNo → 신규 SRC_SN
        Map<Long, Long> frameNoToNewSrc = new LinkedHashMap<>();
        for (LsDataSrc nf : newFrames) {
            frameNoToNewSrc.put(nf.getVideoFrameNo(), nf.getSrcSn());
        }
        //  2) 부모 SRC_SN → 신규 SRC_SN (부모의 videoFrameNo 를 key 로 신규 프레임을 찾는다)
        Map<Long, Long> parentSrcToNewSrc = new LinkedHashMap<>();
        for (LsDataSrc pf : parentFrames) {
            Long newSrcSn = frameNoToNewSrc.get(frameNumberOf(pf));
            if (newSrcSn == null) {
                throw new CustomException(ErrorCode.INTERNAL_ERROR,
                        "부모 프레임에 대응하는 신규 프레임을 찾지 못했습니다: parentSrcSn=" + pf.getSrcSn());
            }
            parentSrcToNewSrc.put(pf.getSrcSn(), newSrcSn);
        }

        // 라벨 복사 — 좌표 그대로(해상도 동일, COORD_RECALC_YN='N'). 각 라벨을 자신의 부모 SRC_SN 이
        // 매핑된 신규 SRC_SN 으로 재지정한다(videoFrameNo 기준 정확 매핑).
        int copiedLabelCount = 0;
        List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
        if (!parentLabels.isEmpty()) {
            // 이슈3 [LOW] — saveAll 반환 순서에 의존하지 않도록 원본 lblSn 을 복사본과 동반(LabelCopy)해
            // 명시적 키 매핑으로 짝짓는다. saveAll 은 같은 복사본 인스턴스에 신규 lblSn 을 부여하므로,
            // 반환 리스트 순서와 무관하게 copy 참조에서 신규 lblSn 을 읽어 정확 매핑한다.
            List<LabelCopy> labelCopies = parentLabels.stream()
                    .map(lbl -> new LabelCopy(lbl.getLblSn(),
                            LsDataLbl.copyForNewSrc(parentSrcToNewSrc.get(lbl.getSrcSn()), lbl)))
                    .toList();
            List<LsDataLbl> copies = labelCopies.stream().map(LabelCopy::copy).toList();
            lblRepository.saveAll(copies);
            copiedLabelCount = copies.size();
            augLblMapRepository.saveAll(buildAugLabelMaps(aug, labelCopies));
        }

        // 성공 시에만 비식별 완료 불변식 확정(같은 커밋): DE_IDNTF_YN='Y' + SUCCESS procLog + MARKING_READY.
        // 증강본은 이미 비식별된 소스 파생이라 산출 영상 자체가 비식별본이며, 비식별 결과 경로 = 증강본 파일 경로.
        //  [순서 주의] 이 확정 블록은 아래 메타 복사(upsertMeta)보다 <b>먼저</b> 수행해야 한다. upsertMeta 는
        //  {@code @Modifying(clearAutomatically=true)} 라 실행 후 영속성 컨텍스트를 비운다. 확정을 뒤에 두면
        //  clear 로 detach 된 newRaw 의 dirty 변경(MARKING_READY·deIdntfYn='Y')이 flush 되지 않아 신규 RAW 가
        //  영영 확정되지 않는다. 앞에 두면 upsertMeta 의 flushAutomatically 가 이 변경들을 먼저 flush 한다.
        String filePath = newRaw.getRawFilePathNm();
        newRaw.markDeidentified("Y");
        newRaw.markMarkingReady();
        LsDeidentProcLog procLog = LsDeidentProcLog.request(newRaw.getRawSn(), null, filePath, "aug-frame-extract");
        procLog.succeed(filePath);
        deidentProcLogRepository.save(procLog);

        // 메타 복사 — 이슈1 [CRITICAL].
        //  (1) video.* 기술메타는 제외한다 — 그 키는 asyncVideoMetaRunner 가 증강 파일에서 ffprobe 로
        //      소유한다. 부모 파일 고유값(파일크기·코덱·재생시간)을 다른 인코딩의 증강 파일 RAW 에 복사하면
        //      논리 오손이며, 메타러너 upsert 와 (RAW_SN, META_KEY) UNIQUE 충돌 레이스를 유발한다(CWE-362).
        //  (2) 콘텐츠/시계열 메타(VLM 등)는 saveAll(평범한 INSERT) 대신 upsertMeta(ON CONFLICT)로 복사해
        //      잔여 동시 충돌도 안전화한다(멱등). 확정 블록 이후에 두어 flushAutomatically 로 확정이 선반영된다.
        List<LsDataMeta> parentMetas = metaRepository.findByRawSn(parentRawSn);
        int copiedMetaCount = 0;
        for (LsDataMeta meta : parentMetas) {
            if (VideoMetaService.isTechnicalKey(meta.getMetaKey())) {
                continue; // 메타러너 소유 — 증강 파일 프로브값 보존(부모값 복사 금지)
            }
            metaRepository.upsertMeta(newRaw.getRawSn(), meta.getMetaKey(), meta.getMetaVl());
            copiedMetaCount++;
        }

        log.info("[Augment][FrameExtract] completed rawSn={} orgnlRawSn={} frames={} labels={} metas={}",
                newRaw.getRawSn(), parentRawSn, newFrames.size(), copiedLabelCount, copiedMetaCount);
    }

    /** 원본 라벨의 lblSn 을 복사본 엔티티와 동반해 saveAll 반환 순서 의존 없이 명시적 매핑을 성립시킨다(이슈3). */
    private record LabelCopy(Long originalLblSn, LsDataLbl copy) {
    }

    /**
     * 부모 프레임의 재추출 대상 디코더 프레임 번호. {@code videoFrameNo}(실제 영상 프레임 위치)가 있으면
     * 그것을, 없으면(구 데이터) 추출 순번 {@code frameNo} 로 폴백한다. 신규 프레임의 videoFrameNo 에도
     * 동일 값을 실어 라벨 재매핑 key 로 사용한다(양쪽 동일 계산식이라 매핑 정합).
     */
    private static long frameNumberOf(LsDataSrc frame) {
        return frame.getVideoFrameNo() != null ? frame.getVideoFrameNo() : frame.getFrameNo();
    }

    /**
     * 원본 lblSn 을 동반한 {@link LabelCopy} 목록으로 LS_DATA_AUG_LBL_MAP 엔티티를 생성한다(이슈3).
     * saveAll 반환 순서에 의존하지 않고 각 pair 의 {@code originalLblSn}↔{@code copy.getLblSn()} 을 직접
     * 매핑한다. 외부 증강 3종은 해상도 동일 → COORD_RECALC_YN='N', scaleX/scaleY 는 null.
     */
    private static List<LsDataAugLblMap> buildAugLabelMaps(LsDataAug aug, List<LabelCopy> labelCopies) {
        List<LsDataAugLblMap> maps = new ArrayList<>(labelCopies.size());
        for (LabelCopy lc : labelCopies) {
            maps.add(LsDataAugLblMap.create(
                    aug.getDataAugSn(),
                    lc.originalLblSn(),
                    lc.copy().getLblSn(),
                    false,   // 해상도 동일 → 좌표 그대로 복사 (COORD_RECALC_YN='N')
                    null,    // scaleX
                    null,    // scaleY
                    aug.getRegUserNo()));
        }
        return maps;
    }
}
