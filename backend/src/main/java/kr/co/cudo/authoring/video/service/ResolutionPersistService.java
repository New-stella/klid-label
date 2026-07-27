package kr.co.cudo.authoring.video.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.meta.service.DerivedMetaCopier;
import kr.co.cudo.authoring.label.entity.LsDeidentReport;
import kr.co.cudo.authoring.label.repository.LsDeidentReportRepository;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 해상도 파생 확정 — <b>Phase C(재검증·영속)</b>. 락-I/O 분리 리팩터의 3단계 (RQ-SFR-06-03 파생영상).
 *
 * <p><b>짧은 {@code REQUIRES_NEW} 트랜잭션 + 재잠금</b>으로 Phase B 산출 파일을 DB 에 원자 영속한다.
 * 대용량 파일 I/O 는 Phase B 에서 이미 끝났으므로 이 단계는 짧다.
 *
 * <h3>수행</h3>
 * <ol>
 *   <li>부모 {@code findByRawSnForUpdate} 재잠금 + {@code deIdntfYn=='Y'} <b>PII TOCTOU 최종 게이트</b>
 *       — Phase A~C 사이 창에서 부모가 'F' 로 전이됐으면 abort(파일 cleanup 은 러너가 담당)</li>
 *   <li><b>stale 창 게이트(H-1)</b> — 'Y' 재검증 직후, 스냅샷 이후 부모 비식별본이 재비식별로 교체됐으면
 *       abort. ①신고이력(capturedAt 이후 신고) ②최신 SUCCESS 비식별 procLog 경로 불일치
 *       ③(파일 존재 시) mtime &gt; capturedAt 중 하나라도 걸리면 CONFLICT</li>
 *   <li>파생 newRaw 재잠금 + {@code deIdntfYn=='Y'} <b>CAS 재확인</b>(#5 중복 finalize 승자 보호) —
 *       이미 확정됐으면 {@link Result#SKIPPED} 반환(프레임 재삽입 없이 skip)</li>
 *   <li>Phase B 산출 파일 기준 LS_DATA_SRC 프레임 INSERT(명시 videoFrameNo 키 매핑) + copyScaledLabels
 *       (LS_DATA_AUG_LBL_MAP) + {@code markResolutionGenerated} + {@code markDeidentified('Y')}
 *       + {@code markCompleted}(배치 마감 — 파생은 라벨 복사로 마킹·배치 불필요) + SUCCESS procLog</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResolutionPersistService {

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataAugLblMapRepository lblMapRepository;
    private final LsDataAugRepository augRepository;
    private final LsDeidentProcLogRepository deidentProcLogRepository;
    private final LsDeidentReportRepository deidentReportRepository;
    private final DerivedMetaCopier derivedMetaCopier;

    /** 비식별 저장소 base — 파생 산출물·비식별 procLog 경로의 상대경로 해석 기준(E-ISSUE-21). */
    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /** 영속 결과 — 정상 확정(PERSISTED) 또는 이미 확정돼 skip(SKIPPED, #5 중복 finalize 패자). */
    public enum Result {
        PERSISTED,
        SKIPPED
    }

    /**
     * Phase B 산출 파일을 DB 에 원자 영속한다(재잠금·재검증 포함).
     *
     * @return {@link Result#PERSISTED} 정상 확정 / {@link Result#SKIPPED} 이미 확정(중복 finalize 패자)
     * @throws CustomException PII 재검증 실패(CONFLICT) — 러너가 catch 하여 cleanup + FAILED 전이
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Result persist(ResolutionSnapshot snapshot) {
        Long newRawSn = snapshot.newRawSn();
        Long parentRawSn = snapshot.parentRawSn();

        // 1) HIGH (CWE-359 PII TOCTOU 최종 게이트) — 부모 재잠금 + deIdntfYn=='Y' 재검증.
        //    잠금 순서는 항상 parent → newRaw 로 고정한다(교착 방지).
        LsDataRaw parent = videoRepository.findByRawSnForUpdate(parentRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "원본 영상을 찾을 수 없습니다: parentRawSn=" + parentRawSn));
        if (!"Y".equals(parent.getDeIdntfYn())) {
            log.warn("[Video][ResolutionDerivative][C] parent no longer deidentified — abort (PII guard) "
                            + "parentRawSn={} newRawSn={} deIdntfYn={}",
                    parentRawSn, newRawSn, safe(parent.getDeIdntfYn()));
            throw new CustomException(ErrorCode.CONFLICT,
                    "비식별 완료된 원본 영상만 파생영상을 확정할 수 있습니다.");
        }

        // 1-1) HIGH (CWE-359 stale 창 게이트, H-1) — deIdntfYn=='Y' 재검증 직후, 부모 잠금 하에
        //      "스냅샷(Phase A) 이후 부모 비식별본이 재비식별로 교체됐는가"를 결정적으로 재검증한다.
        //      'Y' 플래그는 "비식별 상태"만 보장할 뿐, Phase B 가 복사한 파일이 스냅샷과 동일한지는
        //      보장하지 못한다. 신고→재비식별→resolve('F'→'Y') 로 파일이 교체됐으면 Phase B 는 이미
        //      구버전(PII) 픽셀을 복사했으므로 abort 해야 한다(러너가 cleanup + FAILED 전이).
        assertDeidentNotReplacedSince(parentRawSn, snapshot);

        // 2) #5 — 파생 newRaw 재잠금 + CAS 재확인. 동시 finalize 승자가 이미 'Y' 로 확정했으면 skip
        //    (프레임 재삽입/재확정 없이 SKIPPED). 재잠금으로 두 finalize 가 직렬화되어 UK 위반 이전에 걸러진다.
        LsDataRaw newRaw = videoRepository.findByRawSnForUpdate(newRawSn)
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "파생 영상을 찾을 수 없습니다: rawSn=" + newRawSn));
        if ("Y".equals(newRaw.getDeIdntfYn())) {
            log.info("[Video][ResolutionDerivative][C] already finalized by concurrent winner rawSn={} — skip", newRawSn);
            return Result.SKIPPED;
        }

        // 3) Phase B 산출 파일 기준 프레임 INSERT + 명시 videoFrameNo 키 매핑(인덱스 zip 금지).
        Map<Long, Long> parentSrcToNewSrc = insertFrames(snapshot);

        // 4) 라벨 좌표 스케일 복사 + LS_DATA_AUG_LBL_MAP(coordRecalc='Y', scaleX/scaleY) 적재.
        LsDataAug aug = augRepository.findById(snapshot.dataAugSn()).orElse(null);
        int copiedLabels = copyScaledLabels(parentSrcToNewSrc, snapshot.dataAugSn(),
                snapshot.scaleX(), snapshot.scaleY(), snapshot.offsetX(), snapshot.offsetY(), snapshot.regId());

        // 5) 성공 시에만 확정 불변식(같은 커밋): 예약 aug PENDING→ACCEPTED, deIdntfYn='Y' + COMPLETED + SUCCESS procLog.
        //    파생본은 원본 라벨을 좌표 복사해 적재하므로 마킹·배치가 불필요하다. 따라서 배치 단계 상태
        //    (LS_DATA_RAW.DATA_STTS_CD)를 MARKING_READY 가 아닌 COMPLETED 로 마감해 작업보드(COMPLETED 필터)에
        //    라벨링/검수 대상으로 노출한다. 작업/검수 워크플로 상태(LS_RAW_DATA_STATUS)는 여기서 건드리지 않는다
        //    — 배정 시점 미검수로 시작하며 COMPLETED 전이는 ReviewService.approve(검수 승인)에서만 일어난다.
        if (aug != null) {
            aug.markResolutionGenerated();
        }
        String videoDst = newRaw.getRawFilePathNm();
        newRaw.markDeidentified("Y");
        newRaw.markCompleted();
        LsDeidentProcLog procLog = LsDeidentProcLog.request(
                newRaw.getRawSn(), null, videoDst, "resolution-derivative");
        procLog.succeed(videoDst);
        deidentProcLogRepository.save(procLog);

        // 6) 메타 전체 복사(video.* 포함) + 부모 검수행 있던 메타만 미검수 검수행 신규 생성 — 증강 경로와 통일된
        //    로직을 DerivedMetaCopier 에 위임. [순서] 위 확정 블록(5) 이후에 호출한다 — 내부 배치 upsert 가
        //    실행 후 컨텍스트를 clear 하므로, 앞서 두면 newRaw·aug 의 확정 dirty 변경이 유실된다.
        DerivedMetaCopier.CopyResult metaResult = derivedMetaCopier.copyMetaAndReviews(parentRawSn, newRawSn);

        log.info("[Video][ResolutionDerivative][C] persisted rawSn={} orgnlRawSn={} dataAugSn={} frames={} labels={} metas={} metaReviews={}",
                newRawSn, parentRawSn, snapshot.dataAugSn(), parentSrcToNewSrc.size(), copiedLabels,
                metaResult.copiedMetaCount(), metaResult.createdReviewCount());
        return Result.PERSISTED;
    }

    /**
     * 파생 확정 성공 여부(비트랜잭션 러너용 읽기) — 파생 RAW 가 이미 {@code deIdntfYn=='Y'} 또는
     * COMPLETED(파생 확정 시 마감 배치 상태)면 true. 실패 catch 에서 <b>markRawDataFailed 이전</b> 중복
     * finalize 승자를 FAILED 로 덮어쓰지 않기 위한 재조회 게이트.
     *
     * <p><b>잠금(FOR UPDATE) 재조회로 승자 Phase C 커밋과 부분 직렬화(M-1 완화, CWE-362)</b>:
     * 이 게이트를 무잠금 {@code findById} 로 읽으면, 패자가 승자의 Phase C({@link #persist} 의
     * {@code findByRawSnForUpdate(newRawSn)} 잠금 뒤 커밋) <b>이전</b>에 read 를 수행할 때 미확정(false)
     * 으로 판정해 cleanup 이 승자와 동일 경로의 파생 산출물(프레임/비디오)을 삭제하는 경합이 남는다.
     * 부모/신규 RAW 잠금과 동일한 {@code findByRawSnForUpdate}(PESSIMISTIC_WRITE)로 읽어, 무잠금
     * {@code findById} 대비 이 경합 창을 크게 좁힌다.
     *
     * <p><b>보장 범위(정직한 한계)</b>: 결정적으로 창을 폐쇄하는 것이 <b>아니다</b>. 실제 직렬화가
     * 성립하는 경우는 <b>승자가 Phase C 의 {@code newRaw} 잠금을 이미 취득한 뒤 진입한 패자</b>에
     * 한한다 — 이때만 패자의 이 조회가 승자 커밋 뒤로 블록됐다가 커밋된 'Y'/COMPLETED 를 읽어
     * true→cleanup·FAILED 전이를 스킵한다. 다음 하위경로는 여전히 미폐쇄다:
     * ① <b>승자-뒤짐</b> — 패자가 승자의 Phase C 잠금 취득 이전에 {@code newRaw} 를 먼저 잠그면
     *    미확정(false)으로 통과할 수 있다. ② <b>락 타임아웃</b> — {@code lock_timeout} 초과 시
     *    호출자 catch 에서 보수적으로 false(페일오픈)로 처리해 cleanup 이 진행된다.
     * 이 잔여 경합은 best-effort cleanup 으로 수렴하며, 현 트리거가 단일 러너라 실제 재현되지 않는다.
     * (PostgreSQL 은 read-only 트랜잭션에서 SELECT … FOR UPDATE 를 금지하므로 {@code readOnly} 미지정.)
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean isAlreadyFinalized(Long newRawSn) {
        if (newRawSn == null) {
            return false;
        }
        return videoRepository.findByRawSnForUpdate(newRawSn)
                .map(r -> "Y".equals(r.getDeIdntfYn())
                        || LsDataRaw.DATA_STTS_COMPLETED.equals(r.getDataSttsCd()))
                .orElse(false);
    }

    /**
     * HIGH — finalize 실패 시 예약된 {@link LsDataAug} 행(dataAugSn)을 삭제해 부분 유니크 인덱스
     * {@code UK_LS_DATA_AUG_RESL(SRC_SN, AUG_TYPE_CD) WHERE AUG_TYPE_CD LIKE 'RESL\_%'} 슬롯을 해제한다.
     *
     * <p>예약행은 {@link ResolutionReservationPersister} 가 <b>별도 트랜잭션으로 먼저 커밋</b>하므로,
     * finalize 롤백으로는 되돌아가지 않고 고아로 잔존한다. 정리하지 않으면 동일 (부모,프리셋) 재요청이
     * 부분 유니크 위반으로 영구 CONFLICT(409) 락아웃된다.
     *
     * <p>방어: ① 라벨맵({@link LsDataAugLblMap})이 이 예약행을 참조하면 삭제하지 않는다(중복 finalize 승자가
     * 만든 참조 보호). ② {@code RESL_} 접두 예약행만 삭제 대상(오배송 방어).
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void releaseReservedAug(Long dataAugSn) {
        if (dataAugSn == null) {
            return;
        }
        List<LsDataAugLblMap> refs = lblMapRepository.findAllByDataAugSn(dataAugSn);
        if (!refs.isEmpty()) {
            log.warn("[Video][ResolutionDerivative][C] reserved aug still referenced by label map — skip release dataAugSn={} refs={}",
                    dataAugSn, refs.size());
            return;
        }
        augRepository.findById(dataAugSn).ifPresent(a -> {
            String augTypeCd = a.getAugTypeCd();
            if (augTypeCd != null && augTypeCd.startsWith(LsDataAug.RESL_PREFIX)) {
                augRepository.delete(a);
                log.info("[Video][ResolutionDerivative][C] reserved aug slot released dataAugSn={} augTypeCd={}",
                        dataAugSn, augTypeCd);
            } else {
                log.warn("[Video][ResolutionDerivative][C] reserved aug is not a resolution reservation — skip release dataAugSn={}",
                        dataAugSn);
            }
        });
    }

    /**
     * E-ISSUE-23 — 확정 실패한 파생 {@code LS_DATA_RAW} 고아 행을 정리한다.
     *
     * <p>구현은 실패 시 파일 cleanup + 예약 aug 해제 + FAILED 전이만 하고 RAW 행을 남겨,
     * 재시도마다 파생 RAW 가 무한 누적됐다(화면·이력 어디에도 노출되지 않는 침묵 쓰레기).
     *
     * <p><b>경합 안전(CWE-362)</b>: 삭제 직전 {@code findByRawSnForUpdate} 로 <b>잠금 + 상태 재확인</b>을
     * 수행하고, 다음 중 하나라도 어긋나면 삭제하지 않는다 — ①파생이 아님 ②이미 확정(deIdntfYn='Y')
     * ③상태가 FAILED 가 아님 ④프레임이 이미 적재됨. 최종 DELETE 문에도 동일 조건을 SQL 조건으로 함께
     * 걸어, 검사~삭제 사이에 상태가 바뀐 행은 0건 삭제된다.
     *
     * @return 삭제됐으면 true
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public boolean deleteFailedDerivativeRaw(Long newRawSn) {
        if (newRawSn == null) {
            return false;
        }
        LsDataRaw raw = videoRepository.findByRawSnForUpdate(newRawSn).orElse(null);
        if (raw == null || raw.getOrgnlRawSn() == null) {
            return false; // 원본 영상은 절대 삭제 대상이 아니다.
        }
        if ("Y".equals(raw.getDeIdntfYn()) || !LsDataRaw.DATA_STTS_FAILED.equals(raw.getDataSttsCd())) {
            log.info("[Video][ResolutionDerivative][C] orphan cleanup skipped — state changed rawSn={}", newRawSn);
            return false;
        }
        if (srcRepository.countByRawSn(newRawSn) > 0) {
            log.info("[Video][ResolutionDerivative][C] orphan cleanup skipped — frames present rawSn={}", newRawSn);
            return false;
        }
        int deleted = videoRepository.deleteFailedDerivative(newRawSn);
        if (deleted > 0) {
            log.info("[Video][ResolutionDerivative][C] failed derivative RAW removed rawSn={}", newRawSn);
        }
        return deleted > 0;
    }

    /**
     * Phase B 산출 프레임 스펙 기준 LS_DATA_SRC INSERT + 부모→신규 SRC_SN 매핑(라벨 재매핑용).
     *
     * <p>MEDIUM (DB) — 비식별 프레임 경로를 <b>최초 INSERT 에 함께 담아</b> 프레임당 dirty-update(2N 왕복)를
     * 제거한다. 부모 재잠금 보유 시간이 프레임 수에 비례해 늘어나는 것을 막는다({@code attachDeidPath} 제거).
     *
     * <p><b>E-ISSUE-41 (정책 A — 파생영상은 "원본 없음")</b>: 구현은 원래 유일한 물리 산출물(비식별 프레임
     * 리스케일 1벌)을 {@code SRC_FILE_PATH_NM}·{@code DE_IDNTF_SRC_FILE_PATH_NM} 두 컬럼에 <b>같은 값</b>으로
     * 넣었다. 그 결과 ①export 가 orgnl/deid 2벌을 바이트 동일하게 산출하고 ②orgnl 벌이
     * {@code anonymity="N"} 으로 오표기되며 ③마트 뷰의 "두 경로 항상 상이" 불변식이 깨졌다.
     * 해상도 파생은 설계상 <b>비식별본 복사 + 프레임 리스케일</b>이라 원본 픽셀이 실재하지 않으므로,
     * {@code SRC_FILE_PATH_NM} 을 <b>null</b>(원본 부재)로 두고 없는 원본을 있는 척하지 않는다.
     * 이에 맞춰 export 는 파생영상의 ORIGINAL 벌을 생성하지 않는다.
     */
    private Map<Long, Long> insertFrames(ResolutionSnapshot snapshot) {
        // #3 — 부모 프레임의 개인정보 3필드(익명/가명/개인정보 포함여부)를 파생 프레임에 복사(증강 경로와 일관).
        //      부모 미입력(null)이면 파생도 null 로 시작해 파생 폴백이 그대로 적용된다.
        Map<Long, LsDataSrc> parentSrcs = loadParentSrcs(snapshot.frames().stream()
                .map(ResolutionSnapshot.FrameSpec::parentSrcSn).toList());
        Map<Long, Long> parentSrcToNewSrc = new LinkedHashMap<>();
        for (ResolutionSnapshot.FrameSpec f : snapshot.frames()) {
            String dst = f.dst().toString();
            LsDataSrc parent = parentSrcs.get(f.parentSrcSn());
            LsDataSrc nf = srcRepository.save(LsDataSrc.create(
                    snapshot.newRawSn(), f.frameNo(), f.videoFrameNo(), null, dst, f.shtDt(),
                    parent == null ? null : parent.getAnonyInclYn(),
                    parent == null ? null : parent.getPsdoInclYn(),
                    parent == null ? null : parent.getPrvcInclYn()));
            parentSrcToNewSrc.put(f.parentSrcSn(), nf.getSrcSn());
        }
        return parentSrcToNewSrc;
    }

    /** 부모 프레임(SRC_SN) 을 개인정보 3필드 복사용으로 일괄 로드(N+1 회피). 빈 입력이면 빈 맵. */
    private Map<Long, LsDataSrc> loadParentSrcs(List<Long> parentSrcSns) {
        if (parentSrcSns == null || parentSrcSns.isEmpty()) {
            return Map.of();
        }
        Map<Long, LsDataSrc> map = new LinkedHashMap<>();
        for (LsDataSrc s : srcRepository.findAllById(parentSrcSns)) {
            map.put(s.getSrcSn(), s);
        }
        return map;
    }

    /**
     * HIGH (CWE-359 stale 창 게이트, H-1) — 스냅샷(Phase A) 이후 부모 비식별본이 재비식별로 교체됐으면
     * {@link CustomException}(CONFLICT) 로 abort 한다. 러너가 catch 하여 Phase B 가 복사한 (구버전 PII 가능)
     * 파일을 cleanup 하고 파생 RAW 를 FAILED 로 전이한다. 파생 RAW 는 이 시점까지 {@code deIdntfYn='N'} 이라
     * 미서빙이 보장된다.
     *
     * <p>결정적 판정(DB-only, 병용 ①+②) + best-effort(파일 mtime):
     * <ol>
     *   <li>② capturedAt 이후 생성된 비식별 신고({@link LsDeidentReport}, dataRawSn=parent)가 있으면 abort.
     *       재비식별은 이 도메인에서 항상 신고→resolve 로 유발되므로, 창 안 신고 존재가 교체의 결정적 신호다.</li>
     *   <li>① 부모 최신 SUCCESS 비식별 procLog 경로가 스냅샷 경로와 다르면(신규 경로 재비식별) abort.</li>
     *   <li>① 같은 경로라도 파일이 capturedAt 이후 교체(mtime)됐으면 abort — 파일 존재 시에만(제자리 교체 방어).</li>
     * </ol>
     */
    private void assertDeidentNotReplacedSince(Long parentRawSn, ResolutionSnapshot snapshot) {
        Instant capturedAt = snapshot.capturedAt();
        if (capturedAt == null) {
            return; // 방어 — capturedAt 미보유 스냅샷(구 경로)은 게이트 스킵(신규 경로는 항상 채운다).
        }

        // ② capturedAt 이후 부모 비식별 신고 존재 → 재비식별 창 확정.
        boolean reReportedAfterCapture = deidentReportRepository
                .findAllByDataRawSnOrderByReportDtDesc(parentRawSn).stream()
                .anyMatch(r -> isAfter(reportInstant(r), capturedAt));
        if (reReportedAfterCapture) {
            log.warn("[Video][ResolutionDerivative][C] parent re-reported after snapshot — abort (PII stale guard) "
                    + "parentRawSn={} newRawSn={}", parentRawSn, snapshot.newRawSn());
            throw new CustomException(ErrorCode.CONFLICT,
                    "스냅샷 이후 원본 비식별본이 변경되어 파생영상을 확정할 수 없습니다.");
        }

        // ① 최신 SUCCESS 비식별 procLog 경로가 스냅샷과 동일한지 재확인(신규 경로 재비식별 방어).
        //    비식별 산출물 경로이므로 상대경로 해석 기준은 비식별 저장소 base 다(Phase A 와 동일 기준).
        Path base = Paths.get(storageDeidentifiedPath).toAbsolutePath().normalize();
        Path snapshotDeid = snapshot.deidVideoSrc();
        Path currentDeid = deidentProcLogRepository.findLatestSuccessByDataRawSn(parentRawSn)
                .map(LsDeidentProcLog::getDeIdntfFilePathNm)
                .filter(p -> p != null && !p.isBlank())
                .map(p -> resolveQuietly(base, p))
                .orElse(null);
        if (currentDeid == null || !currentDeid.equals(snapshotDeid)) {
            log.warn("[Video][ResolutionDerivative][C] parent deident path changed since snapshot — abort (PII stale guard) "
                    + "parentRawSn={} newRawSn={}", parentRawSn, snapshot.newRawSn());
            throw new CustomException(ErrorCode.CONFLICT,
                    "스냅샷 이후 원본 비식별본이 변경되어 파생영상을 확정할 수 없습니다.");
        }

        // ① 같은 경로라도 파일이 capturedAt 이후 교체(mtime)됐으면 abort(제자리 교체). 파일 존재 시에만.
        try {
            if (Files.exists(snapshotDeid)) {
                Instant mtime = Files.getLastModifiedTime(snapshotDeid).toInstant();
                if (mtime.isAfter(capturedAt)) {
                    log.warn("[Video][ResolutionDerivative][C] parent deident file replaced since snapshot (mtime) — abort "
                            + "parentRawSn={} newRawSn={}", parentRawSn, snapshot.newRawSn());
                    throw new CustomException(ErrorCode.CONFLICT,
                            "스냅샷 이후 원본 비식별본이 변경되어 파생영상을 확정할 수 없습니다.");
                }
            }
        } catch (IOException e) {
            // stat 실패는 결정적 게이트(①②)가 이미 통과했으므로 보수적으로 통과(로그만). PII 는 ②신고 게이트로 닫힘.
            log.warn("[Video][ResolutionDerivative][C] deident mtime check skipped parentRawSn={} cause={}",
                    parentRawSn, e.getClass().getSimpleName());
        }
    }

    private Path resolveQuietly(Path base, String filePath) {
        try {
            Path candidate = Paths.get(filePath);
            return candidate.isAbsolute() ? candidate.normalize() : base.resolve(candidate).normalize();
        } catch (RuntimeException e) {
            return null; // 해석 불가 경로는 불일치로 취급 → 게이트 abort.
        }
    }

    private static Instant reportInstant(LsDeidentReport r) {
        LocalDateTime dt = r.getReportDt() != null ? r.getReportDt() : r.getRegDt();
        return dt == null ? Instant.EPOCH : dt.atZone(ZoneId.systemDefault()).toInstant();
    }

    private static boolean isAfter(Instant candidate, Instant reference) {
        return candidate != null && candidate.isAfter(reference);
    }

    /**
     * 부모 라벨을 좌표 스케일 복사 + LS_DATA_AUG_LBL_MAP(coordRecalc='Y', scaleX/scaleY) 적재.
     *
     * <p>G-1 — 종횡비 보존(레터박스) 리스케일이므로 좌표 변환은 <b>단순 배율이 아니라</b>
     * {@code x' = x*scale + offsetX} 다. BBOX·POLYGON·세그멘테이션·키포인트 전 종류에 동일 적용된다.
     * 매핑 행에는 기존 컬럼(SCALE_X/SCALE_Y = 균일 배율)만 기록한다 — 신규 컬럼을 추가하지 않는다.
     *
     * <p><b>추적성 한계(사실 명시 — 후속)</b>: 저장된 라벨 좌표 자체는 오프셋까지 반영된 정확한 값이지만,
     * 매핑 행만 보고 <b>오프셋을 역산할 수는 없다</b>. 오프셋은 부모 원본 치수(srcW·srcH)와 목표 치수에
     * 함께 의존하기 때문이다 — 반례: 1080×1920 과 1440×1920 은 목표 1920×1080 에 대해 <b>같은 배율</b>
     * (0.5625)이지만 offsetX 는 각각 (1920-607)/2 와 (1920-810)/2 로 다르다. 오프셋 컬럼 신설은
     * 표준용어·표준도메인 확정이 선행돼야 하므로 후속 과제로 남긴다(영향 범위: 좌표 정확도 아님, 추적성).
     */
    private int copyScaledLabels(Map<Long, Long> parentSrcToNewSrc, Long dataAugSn,
                                 double scaleX, double scaleY, int offsetX, int offsetY, String regId) {
        List<LsDataLbl> parentLabels = lblRepository.findBySrcSnIn(parentSrcToNewSrc.keySet());
        if (parentLabels.isEmpty()) {
            return 0;
        }
        BigDecimal sx = BigDecimal.valueOf(scaleX);
        BigDecimal sy = BigDecimal.valueOf(scaleY);

        // saveAll 반환 순서 비의존 — 원본 lblSn 을 복사본과 동반해 명시 매핑.
        List<LabelCopy> copies = parentLabels.stream()
                .map(lbl -> new LabelCopy(lbl.getLblSn(),
                        LsDataLbl.copyForNewSrcScaled(parentSrcToNewSrc.get(lbl.getSrcSn()), lbl,
                                scaleX, scaleY, offsetX, offsetY)))
                .toList();
        lblRepository.saveAll(copies.stream().map(LabelCopy::copy).toList());

        List<LsDataAugLblMap> maps = new ArrayList<>(copies.size());
        for (LabelCopy c : copies) {
            maps.add(LsDataAugLblMap.create(
                    dataAugSn, c.originalLblSn(), c.copy().getLblSn(),
                    LsDataAugLblMap.RECALC_Y, sx, sy, regId));
        }
        lblMapRepository.saveAll(maps);
        return copies.size();
    }

    private static String safe(String s) {
        return s == null ? "null" : s.replaceAll("[\\r\\n\\t]", "_");
    }

    /** 원본 lblSn 을 복사본과 동반(saveAll 순서 비의존 명시 매핑). */
    private record LabelCopy(Long originalLblSn, LsDataLbl copy) {
    }
}
