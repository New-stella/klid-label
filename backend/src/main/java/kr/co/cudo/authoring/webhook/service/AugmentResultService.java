package kr.co.cudo.authoring.webhook.service;

import kr.co.cudo.authoring.augment.entity.LsDataAug;
import kr.co.cudo.authoring.augment.entity.LsDataAugLblMap;
import kr.co.cudo.authoring.augment.repository.LsDataAugLblMapRepository;
import kr.co.cudo.authoring.augment.repository.LsDataAugRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.ExternalUrlValidator;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import kr.co.cudo.authoring.webhook.dto.AugmentResultRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 외부 생성형 AI 증강 결과 인계 처리 서비스 — 연동정의서 정합.
 *
 * <p>{@code POST /v1/aug/callback} 의 HMAC 인증 통과 후 호출된다.
 * {@link LsDataAug} 의 PENDING 상태 행을 {@code ACCEPTED}/{@code REJECTED} 로 전이하고,
 * 성공 시 새 증강 영상(RAW_SN)을 생성한다.
 *
 * <h3>재전송 멱등 방어 (CRITICAL — 중복 영상 생성 차단)</h3>
 * <p>증강 성공 콜백은 <b>새 영상을 생성</b>하므로 webhook 재전송이 중복 영상을 만들면 안 된다.
 * 콜백 페이로드에는 요청 시점 발급 키가 없고 {@code otsd_job_id} 는 외부 시스템이 콜백 시점에
 * 부여하므로, 요청 시점 원장(ledger) 게이트로는 재전송을 막을 수 없다. 대신:
 * <ol>
 *   <li><b>1차 앵커(주 방어선)</b>: {@code data_aug_sn} 으로 대상 행을 조회해 <b>종결 상태
 *       (non-PENDING)</b>이면 재전송으로 간주하고 skip(신규 영상 미생성). 최초 콜백이
 *       PENDING→ACCEPTED/REJECTED 로 전이시키므로 순차 재전송(webhook 재시도)은 여기서 완전히
 *       차단된다.</li>
 *   <li><b>2차 앵커(동시/오배송)</b>: {@code otsd_job_id} 를 {@code LS_DATA_AUG.OTSD_JOB_ID}
 *       (UNIQUE {@code uk_aug_external_job_id})에 적재하고, 저장 시 UNIQUE 위반이면
 *       ({@link LsDataAugRepository#findByExternalJobId}) 재조회 후 멱등 흡수(skip). 동시 콜백/
 *       다른 행 오배송으로 같은 otsd_job_id 가 이미 선점된 경우를 방어한다.</li>
 * </ol>
 * <p>요청 시점 원장 발급(AugmentRequestBridge)은 고아 키 방지 구조로 유지되지만, 본 콜백 처리는
 * 원장을 참조하지 않고 위 두 앵커로만 멱등을 판별한다. {@code LS_DATA_AUG.IDMP_KEY} 컬럼은
 * 물리적으로 남지만 콜백 페이로드로 채우지 않는다(마이그레이션 없음).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AugmentResultService {

    private static final Pattern LOG_UNSAFE = Pattern.compile("[\\r\\n\\t]");

    private final LsDataAugRepository augRepository;
    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository lblRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsDataAugLblMapRepository augLblMapRepository;

    /**
     * @return true = 신규 적재 / false = 재전송 멱등 스킵(신규 영상 미생성)
     */
    @Transactional("controlTransactionManager")
    public boolean handle(AugmentResultRequest req) {
        // 1) SSRF — raw_file_path_nm
        validateFilePath(req.rawFilePathNm());

        // 2) 대상 증강 행 조회 (data_aug_sn = 요청 시 발급된 LS_DATA_AUG PK)
        LsDataAug aug = augRepository.findById(req.dataAugSn())
                .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND,
                        "증강 행을 찾을 수 없습니다: dataAugSn=" + req.dataAugSn()));

        // 3) 재전송 멱등 방어(1차 앵커) — 이미 종결(non-PENDING)된 행이면 재전송이다 → skip.
        //    순차 재전송(webhook 재시도)은 여기서 완전히 차단되어 중복 영상이 생성되지 않는다.
        if (!LsDataAug.STTS_PENDING.equals(aug.getAugProcSttsCd())) {
            log.info("[Webhook][Augment] duplicate result skipped dataAugSn={} otsdJobId={} state={}",
                    req.dataAugSn(), safe(req.otsdJobId()), safe(aug.getAugProcSttsCd()));
            return false;
        }

        // 4) augType 불일치 차단 — 외부 시스템이 다른 행에 잘못 인계하는 사고 방지
        if (!req.augTypeCd().equals(aug.getAugTypeCd())) {
            throw new CustomException(ErrorCode.CONFLICT,
                    "augType 불일치: row=" + aug.getAugTypeCd() + " request=" + req.augTypeCd());
        }

        // 5) 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
        //    uk_aug_external_job_id UNIQUE 위반(동시 콜백/다른 행 오배송)이면 재조회 후 멱등 흡수한다.
        //    flush 로 createAugmentedVideo 이전에 UNIQUE 위반을 확정 감지한다(중복 영상 작업 회피).
        String newStatus = "SUCCESS".equals(req.augProcStsCd())
                ? LsDataAug.STTS_ACCEPTED
                : LsDataAug.STTS_REJECTED;
        try {
            applyAugStateAndExternalJobId(aug, req, newStatus);
            augRepository.save(aug);
            augRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 동시/오배송 재전송 — 다른 트랜잭션이 이미 동일 otsd_job_id 를 선점했다.
            // 선점 행이 이미 처리(신규 영상 생성)를 담당하므로 본 콜백은 멱등 흡수(신규 영상 미생성).
            LsDataAug existing = augRepository.findByExternalJobId(req.otsdJobId())
                    .orElseThrow(() -> new IllegalStateException(
                            "UNIQUE(otsd_job_id) 위반 후 재조회 실패", e));
            log.warn("[Webhook][Augment] race 감지 후 멱등 흡수 dataAugSn={} otsdJobId={} winnerState={}",
                    req.dataAugSn(), safe(req.otsdJobId()), safe(existing.getAugProcSttsCd()));
            return false;
        }

        // 6) 성공 시 새 영상 생성 (원본 라벨/메타 복사)
        if (LsDataAug.STTS_ACCEPTED.equals(newStatus)) {
            createAugmentedVideo(aug, req);
        }

        log.info("[Webhook][Augment] result applied dataAugSn={} status={} otsdJobId={}",
                req.dataAugSn(), safe(req.augProcStsCd()), safe(req.otsdJobId()));
        return true;
    }

    /**
     * V2.0 — 증강 성공 시 새 영상(RAW_SN) 생성 + 원본 프레임/라벨/메타 복사.
     * 새 영상은 PENDING 상태로 시작하여 기존 배정/검수 흐름을 따른다.
     */
    private void createAugmentedVideo(LsDataAug aug, AugmentResultRequest req) {
        LsDataSrc originSrc = srcRepository.findById(aug.getSrcSn()).orElse(null);
        if (originSrc == null) {
            log.warn("[Webhook][Augment] originSrc not found srcSn={} — skip video creation", aug.getSrcSn());
            return;
        }
        LsDataRaw parentRaw = videoRepository.findById(originSrc.getRawSn()).orElse(null);
        if (parentRaw == null) {
            log.warn("[Webhook][Augment] parentRaw not found rawSn={} — skip video creation", originSrc.getRawSn());
            return;
        }

        String filePath = req.rawFilePathNm() != null ? req.rawFilePathNm() : parentRaw.getRawFilePathNm();
        LsDataRaw newRaw = videoRepository.save(LsDataRaw.createFromAugment(parentRaw, filePath, req.augTypeCd()));

        // 프레임 일괄 복사 (saveAll batch)
        List<LsDataSrc> parentFrames = srcRepository.findByRawSnOrderByFrameNoAsc(parentRaw.getRawSn());
        List<LsDataSrc> newFrames = parentFrames.stream()
                .map(f -> LsDataSrc.create(newRaw.getRawSn(), f.getFrameNo(), f.getVideoFrameNo(), f.getSrcFilePathNm(), f.getShtDt()))
                .toList();
        List<LsDataSrc> savedFrames = srcRepository.saveAll(newFrames);

        // srcSnMap: 원본 srcSn -> 신규 srcSn (zip 매핑)
        Map<Long, Long> srcSnMap = new HashMap<>();
        for (int i = 0; i < parentFrames.size(); i++) {
            srcSnMap.put(parentFrames.get(i).getSrcSn(), savedFrames.get(i).getSrcSn());
        }

        // 라벨 일괄 조회 (IN 쿼리 1회) + 일괄 저장 (N+1 해소)
        int copiedLabelCount = 0;
        if (!srcSnMap.isEmpty()) {
            List<LsDataLbl> allLabels = lblRepository.findBySrcSnIn(srcSnMap.keySet());
            List<LsDataLbl> copied = allLabels.stream()
                    .map(lbl -> LsDataLbl.copyForNewSrc(srcSnMap.get(lbl.getSrcSn()), lbl))
                    .toList();
            List<LsDataLbl> savedLabels = lblRepository.saveAll(copied);
            copiedLabelCount = copied.size();

            // UC-002 — 원본 라벨 SN ↔ 증강(복사본) 라벨 SN 매핑 기록.
            // 외부 증강 3종(WINTER/NIGHT/RAIN)은 해상도 동일 → 좌표 그대로 복사이므로
            // COORD_RECALC_YN='N', scaleX/scaleY 는 null. 라벨 복사와 같은 트랜잭션 내 일괄 저장.
            if (!savedLabels.isEmpty()) {
                List<LsDataAugLblMap> labelMaps = buildAugLabelMaps(aug, allLabels, savedLabels);
                augLblMapRepository.saveAll(labelMaps);
            }
        }

        // 메타 일괄 저장 (saveAll batch)
        List<LsDataMeta> parentMetas = metaRepository.findByRawSn(parentRaw.getRawSn());
        List<LsDataMeta> copiedMetas = parentMetas.stream()
                .map(meta -> LsDataMeta.create(newRaw.getRawSn(), meta.getMetaKey(), meta.getMetaVl()))
                .toList();
        metaRepository.saveAll(copiedMetas);

        log.info("[Webhook][Augment] new video created rawSn={} orgnlRawSn={} augType={} frames={} labels={} metas={}",
                newRaw.getRawSn(), parentRaw.getRawSn(), req.augTypeCd(),
                parentFrames.size(), copiedLabelCount, parentMetas.size());
    }

    /**
     * 원본 라벨과 복사본 라벨을 인덱스로 zip 하여 LS_DATA_AUG_LBL_MAP 엔티티 목록 생성.
     * {@code originals} 와 {@code copies} 는 동일 순서로 1:1 대응한다(saveAll 입력=출력 순서 보존).
     */
    private static List<LsDataAugLblMap> buildAugLabelMaps(LsDataAug aug, List<LsDataLbl> originals,
                                                           List<LsDataLbl> copies) {
        List<LsDataAugLblMap> maps = new java.util.ArrayList<>(copies.size());
        for (int i = 0; i < copies.size(); i++) {
            LsDataLbl original = originals.get(i);
            LsDataLbl copy = copies.get(i);
            maps.add(LsDataAugLblMap.create(
                    aug.getDataAugSn(),
                    original.getLblSn(),
                    copy.getLblSn(),
                    false,   // 해상도 동일 → 좌표 그대로 복사 (COORD_RECALC_YN='N')
                    null,    // scaleX
                    null,    // scaleY
                    aug.getRegUserNo()));
        }
        return maps;
    }

    /**
     * PENDING 상태 전이 + otsd_job_id 를 externalJobId(재전송 멱등 앵커)에 적재.
     * externalJobId 는 요청 시점 placeholder 를 콜백 시점의 실제 otsd_job_id 로 갱신한다(무조건 덮어쓰기)
     * → 종결 행이 항상 otsd_job_id 를 보유하여 UNIQUE(uk_aug_external_job_id) 재전송 방어가 성립한다.
     */
    private static void applyAugStateAndExternalJobId(LsDataAug target, AugmentResultRequest req,
                                                      String newStatus) {
        if (LsDataAug.STTS_PENDING.equals(target.getAugProcSttsCd())) {
            target.applyReviewStatus(newStatus);
        }
        // 콜백 시점 otsd_job_id 를 재전송 멱등 앵커로 적재. IDMP_KEY 컬럼은 채우지 않는다(마이그레이션 없음).
        target.assignExternalJobId(req.otsdJobId());
    }

    private void validateFilePath(String filePath) {
        if (filePath == null || filePath.isBlank()) return;
        if (filePath.contains("://")) {
            try {
                ExternalUrlValidator.validate(filePath, false, "rawFilePathNm");
            } catch (IllegalArgumentException e) {
                throw new CustomException(ErrorCode.INVALID_INPUT,
                        "rawFilePathNm SSRF 차단: " + e.getMessage());
            }
        }
    }

    private static String safe(String s) {
        if (s == null) return "null";
        return LOG_UNSAFE.matcher(s).replaceAll("_");
    }
}
