package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDataSrcHstry;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcHstryRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
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
 * 증강 프레임 재추출 확정 — <b>Phase C(영속)</b>. 커넥션-점유 분리 리팩터의 3단계(증강 경로).
 *
 * <p><b>짧은 {@code REQUIRES_NEW} 트랜잭션</b>으로 Phase B 산출 파일을 DB 에 원자 영속한다. 대용량 파일
 * I/O(ffmpeg 재추출)는 Phase B 에서 이미 끝났으므로 이 단계는 짧다 — 커넥션 점유 시간이 프레임 수에 비례해
 * 늘어나던 구 {@code extractAndCopy} 의 병목(추출+DB 를 한 트랜잭션에 묶음)을 제거한다.
 *
 * <h3>부모 잠금·비식별 재검증 미추가 (설계 유지)</h3>
 * <p>{@link AugmentExtractSnapshot} 과 동일하게, 부모 재잠금·PII 재검증을 여기서 하지 않는다. 본 리팩터는
 * 순수 커넥션 분리이며 동작·PII 자세는 구 {@code AugmentFrameExtractionService.extractAndCopy} 와 동일하다.
 *
 * <h3>수행 (구 extractAndCopy 의 DB 파트 이관)</h3>
 * <ol>
 *   <li>멱등 재확인 — 신규 RAW 가 이미 {@code deIdntfYn=='Y'} 면 {@link Result#SKIPPED}(A~C 창 중복 트리거 방어)</li>
 *   <li>Phase B 산출 파일 기준 LS_DATA_SRC 프레임 INSERT(+ 생성 이력) + videoFrameNo 기준 명시 키 매핑</li>
 *   <li>부모 라벨 좌표 그대로 복사({@code copyForNewSrc}) + LS_DATA_AUG_LBL_MAP(COORD_RECALC_YN='N') 적재</li>
 *   <li>성공 시에만 비식별 완료 불변식 확정: {@code deIdntfYn='Y'} + COMPLETED(배치 마감) + SUCCESS procLog</li>
 *   <li>부모 메타 <b>전체</b> 복사({@code video.*} 포함) + 부모 검수행 있던 메타만 미검수 검수행 신규 생성
 *       — {@link DerivedMetaCopier} 위임(해상도 파생 경로와 통일)</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentExtractPersist {

    private final VideoRepository videoRepository;
    private final LsDataAugRepository augRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataSrcHstryRepository hstryRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataAugLblMapRepository augLblMapRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final DerivedMetaCopier derivedMetaCopier;

    /** 영속 결과 — 정상 확정(PERSISTED) 또는 이미 확정돼 skip(SKIPPED, A~C 창 중복 트리거 패자). */
    public enum Result {
        PERSISTED,
        SKIPPED
    }

    /**
     * Phase B 산출 파일을 DB 에 원자 영속한다.
     *
     * @return {@link Result#PERSISTED} 정상 확정 / {@link Result#SKIPPED} 이미 확정(중복 트리거 패자)
     * @throws CustomException 신규 RAW/증강행 부재(NOT_FOUND) — 러너가 catch 하여 cleanup + FAILED 전이
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Result persist(AugmentExtractPlan plan) {
        LsDataRaw newRaw = videoRepository.findById(plan.newRawSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 신규 영상을 찾을 수 없습니다: rawSn=" + plan.newRawSn()));

        // 1) 멱등 재확인 — Phase A~C 창에서 중복 트리거가 먼저 확정(비식별 완료)했으면 프레임 재삽입 없이 skip.
        //    파일은 동일 경로(같은 rawSn)라 승자 산출물이므로 러너가 cleanup 하지 않는다.
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Augment][ExtractC] already finalized rawSn={} — skip", plan.newRawSn());
            return Result.SKIPPED;
        }

        LsDataAug aug = augRepository.findById(plan.dataAugSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + plan.dataAugSn()));

        // 2) Phase B 산출 파일 기준 프레임 INSERT + videoFrameNo 기준 부모→신규 SRC_SN 명시 매핑.
        Map<Long, Long> parentSrcToNewSrc = new LinkedHashMap<>();
        for (AugmentExtractPlan.FrameSpec f : plan.frames()) {
            LsDataSrc nf = srcRepository.save(LsDataSrc.create(
                    plan.newRawSn(), f.frameNo(), f.videoFrameNo(), f.dst().toString(), f.shtDt()));
            hstryRepository.save(LsDataSrcHstry.recordCreated(nf.getSrcSn()));
            parentSrcToNewSrc.put(f.parentSrcSn(), nf.getSrcSn());
        }

        // 3) 라벨 복사 — 좌표 그대로(해상도 동일, COORD_RECALC_YN='N'). 각 라벨을 자신의 부모 SRC_SN 이 매핑된
        //    신규 SRC_SN 으로 재지정한다(videoFrameNo 기준 정확 매핑).
        int copiedLabelCount = 0;
        List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
        if (!parentLabels.isEmpty()) {
            // saveAll 반환 순서에 의존하지 않도록 원본 lblSn 을 복사본과 동반(LabelCopy)해 명시적 키 매핑.
            List<LabelCopy> labelCopies = parentLabels.stream()
                    .map(lbl -> new LabelCopy(lbl.getLblSn(),
                            LsDataLbl.copyForNewSrc(parentSrcToNewSrc.get(lbl.getSrcSn()), lbl)))
                    .toList();
            List<LsDataLbl> copies = labelCopies.stream().map(LabelCopy::copy).toList();
            lblRepository.saveAll(copies);
            copiedLabelCount = copies.size();
            augLblMapRepository.saveAll(buildAugLabelMaps(aug, labelCopies));
        }

        // 4) 성공 시에만 비식별 완료 불변식 확정(같은 커밋): DE_IDNTF_YN='Y' + SUCCESS procLog + COMPLETED.
        //    증강본은 이미 비식별된 소스 파생이라 산출 영상 자체가 비식별본이며, 비식별 결과 경로 = 증강본 파일 경로.
        //    파생본은 원본 라벨을 좌표 복사해 적재하므로 마킹·배치가 불필요하다 — 배치 단계 상태
        //    (LS_DATA_RAW.DATA_STTS_CD)를 COMPLETED 로 마감해 작업보드(COMPLETED 필터)에 라벨링/검수 대상으로
        //    노출한다. 작업/검수 워크플로 상태(LS_RAW_DATA_STATUS)는 건드리지 않는다(배정 시점 미검수 시작).
        //  [순서 주의] 이 확정 블록은 아래 메타 복사(upsertMetaBatch)보다 먼저 수행해야 한다. 배치 upsert 는
        //  실행 후 영속성 컨텍스트를 clear 한다(구 upsertMeta clearAutomatically 재현). 확정을 뒤에 두면 clear 로
        //  detach 된 newRaw 의 dirty 변경(COMPLETED·deIdntfYn='Y')이 flush 되지 않아 신규 RAW 가 영영
        //  확정되지 않는다. 앞에 두면 배치 upsert 의 flush(실행 전)가 이 변경들을 먼저 flush 한다.
        String filePath = newRaw.getRawFilePathNm();
        newRaw.markDeidentified("Y");
        newRaw.markCompleted();
        LsDeidentProcLog procLog = LsDeidentProcLog.request(newRaw.getRawSn(), null, filePath, "aug-frame-extract");
        procLog.succeed(filePath);
        deidentProcLogRepository.save(procLog);

        // 5) 메타 전체 복사(video.* 포함) + 부모 검수행 있던 메타만 미검수 검수행 신규 생성 — 해상도 파생 경로와
        //    통일된 로직을 DerivedMetaCopier 에 위임. [순서] 위 확정 블록(4) 이후에 호출한다 — 내부 배치 upsert 가
        //    실행 후 컨텍스트를 clear 하므로, 앞서 두면 newRaw 의 확정 dirty 변경이 유실된다.
        DerivedMetaCopier.CopyResult metaResult =
                derivedMetaCopier.copyMetaAndReviews(plan.parentRawSn(), newRaw.getRawSn());

        log.info("[Augment][ExtractC] persisted rawSn={} orgnlRawSn={} frames={} labels={} metas={} metaReviews={}",
                plan.newRawSn(), plan.parentRawSn(), parentSrcToNewSrc.size(), copiedLabelCount,
                metaResult.copiedMetaCount(), metaResult.createdReviewCount());
        return Result.PERSISTED;
    }

    /** 원본 라벨의 lblSn 을 복사본 엔티티와 동반해 saveAll 반환 순서 의존 없이 명시적 매핑을 성립시킨다. */
    private record LabelCopy(Long originalLblSn, LsDataLbl copy) {
    }

    /**
     * 원본 lblSn 을 동반한 {@link LabelCopy} 목록으로 LS_DATA_AUG_LBL_MAP 엔티티를 생성한다. saveAll 반환
     * 순서에 의존하지 않고 각 pair 의 {@code originalLblSn}↔{@code copy.getLblSn()} 을 직접 매핑한다.
     * 외부 증강 3종은 해상도 동일 → COORD_RECALC_YN='N', scaleX/scaleY 는 null.
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
