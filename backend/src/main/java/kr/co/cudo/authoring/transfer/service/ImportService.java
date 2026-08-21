package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.common.exception.CustomException;
import kr.co.cudo.authoring.common.exception.ErrorCode;
import kr.co.cudo.authoring.common.util.LogSanitizer;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.transfer.ImportMetaKeys;
import kr.co.cudo.authoring.transfer.ImportPathPolicy;
import kr.co.cudo.authoring.transfer.ImportSourcePolicy;
import kr.co.cudo.authoring.transfer.dto.ImportCreateRequest;
import kr.co.cudo.authoring.transfer.dto.ImportCreateResponse;
import kr.co.cudo.authoring.transfer.entity.LsOtsdDatstTrnsfHstry;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.parser.FirstAnnotationParser;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 확인을 마친 산출물 폴더를 <b>실제로 적재</b>한다(API-206).
 *
 * <h3>순서가 곧 사양이다</h3>
 * <ol>
 *   <li><b>위치 판정</b>(허용 저장소 범위·실경로) — 인가는 컨트롤러가 이미 끝냈다. 권한 없는 요청에는
 *       위치의 유효성이 응답으로 새지 않는다(AC-048).</li>
 *   <li><b>훑기·파싱</b> — 트랜잭션 밖. 폴더가 큰 산출물 하나가 커넥션을 오래 쥐지 않게 한다.</li>
 *   <li><b>적재 가능 여부 재계산</b> — 미확정 분류·중복·프레임 0건·식별자 부재. 검사 응답의 값을
 *       그대로 믿지 않는다(검사와 적재 사이에 대응이 해제되거나 같은 산출물이 먼저 들어올 수 있다).
 *       요청의 <b>확인 기록은 이 판정을 해제하지 않는다</b> — 감사 기록일 뿐이다.</li>
 *   <li><b>이관 이력 시작</b> — 별도 트랜잭션. 실패해도 흔적이 남는다.</li>
 *   <li><b>파일 복사</b> — 트랜잭션 밖.</li>
 *   <li><b>영속</b> — 한 트랜잭션. 실패하면 통째로 되돌리고 <b>이번에 만든 파일만</b> 지운다.</li>
 * </ol>
 *
 * <h3>커밋 이후에는 아무것도 되돌리지 않는다</h3>
 * <p>영속은 별도 트랜잭션이라 돌아오는 순간 이미 커밋됐다. 그 뒤에 오는 이력 마감이 깨졌다고 파일을
 * 지우면 <b>커밋된 행이 가리키는 파일</b>이 사라지고 되돌릴 수단이 없다. 그래서 보상은 커밋 이전
 * 실패에만 돌고, 이력 마감 실패는 기록만 남기고 넘어간다 — 마감이 실패했다는 사실은 적재가
 * 잘못됐다는 뜻이 아니다.</p>
 *
 * <h3>중복은 두 겹으로 막는다</h3>
 * <p>사전 조회는 <b>사람에게 기존 영상을 알려 주기 위한</b> 것이고, 실제 보장은
 * {@code LS_DATA_RAW.VMS_CLIP_ID} 의 유일 제약이다. 조회만으로 판정하면 두 노드가 같은 순간에 통과한다
 * (check-then-act). 제약 위반은 트랜잭션 <b>밖</b>에서 받아 409 로 마감한다 — 안에서 잡으면 그 트랜잭션은
 * 이미 롤백 표시가 서 있어 이어서 아무것도 할 수 없다.
 *
 * @design DOMAIN-017
 * @design API-206
 * @design ADR-048
 * @design AC-044
 * @design AC-045
 * @design AC-046
 * @design AC-047
 * @design AC-048
 */
@Service
@RequiredArgsConstructor
public class ImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportService.class);

    private final ImportSourcePolicy sourcePolicy;
    private final FirstAnnotationParser parser;
    private final ImportMappingResolver mappingResolver;
    private final ImportFileStager fileStager;
    private final ImportPersistTxService persistTxService;
    private final ImportHistoryTxService historyTxService;
    private final VideoRepository videoRepository;

    /** 폴더 하나를 훑을 때 읽을 항목 수 상한 — 검사 단계와 같은 설정을 본다(판정이 갈리면 안 된다). */
    @Value("${authoring.import.scan.max-entries:20000}")
    private int maxEntries;

    @Value("${authoring.storage.raw-path:./storage/raw}")
    private String storageRawPath;

    @Value("${authoring.storage.deidentified-path:./storage/deidentified}")
    private String storageDeidentifiedPath;

    /**
     * 산출물 폴더를 적재한다.
     *
     * @throws CustomException 경로·미확정 분류·프레임 부재(INVALID_INPUT) / 폴더 부재(NOT_FOUND) /
     *                         이미 가져온 산출물(CONFLICT) / 적재 실패(INTERNAL_ERROR)
     */
    public ImportCreateResponse importFolder(ImportCreateRequest request, String actorId) {
        Path folder = sourcePolicy.verifyFolder(request.folderPath());
        Path videoFile = (request.videoPath() == null || request.videoPath().isBlank())
                ? null : sourcePolicy.verifyVideoFile(request.videoPath());

        if (countEntries(folder) > maxEntries) {
            // 잘라 담으면 "덜 들어온 것"과 "원래 그만큼인 것"이 구분되지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "폴더의 파일 수가 한 번에 읽을 수 있는 상한을 넘습니다.");
        }

        ImportedDataset dataset = parse(folder);
        if (dataset.frames().isEmpty()) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "폴더에서 프레임을 하나도 찾지 못했습니다.");
        }

        String datasetId = dataset.info() == null ? null : dataset.info().identifier();
        String vmsClipId = buildClipId(dataset.folderName(), datasetId);
        findDuplicate(vmsClipId).ifPresent(existing -> {
            throw new CustomException(ErrorCode.CONFLICT, duplicateMessage(existing));
        });

        ImportMappingResolver.Resolved mappings = mappingResolver.resolve(dataset);
        if (mappings.hasUnresolved()) {
            // 짐작으로 연결하면 다른 분류로 저장되고, 저장된 뒤에는 구분할 수 없다(fail-closed).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "대응이 정해지지 않은 분류가 남아 있습니다: " + String.join(", ", mappings.unresolved()));
        }

        ImportPlan plan = buildPlan(dataset, vmsClipId, request.deidentifiedOrDefault(),
                videoFile, mappings);

        if (!request.acknowledgedOrEmpty().isEmpty()) {
            // 확인 기록은 차단 사유를 해제하지 않는다 — 사람이 무엇을 보고 진행했는지의 감사 기록이다.
            log.info("[Import] acknowledged warnings actor={} codes={}",
                    LogSanitizer.sanitize(actorId),
                    LogSanitizer.sanitize(String.join(",", request.acknowledgedOrEmpty())));
        }

        Long trnsfSn = historyTxService.start(folder.toString(), dataset.folderName(),
                ExternalNameSanitizer.identifier(datasetId, LsOtsdDatstTrnsfHstry.OTSD_DATST_ID_MAX),
                actorId);

        ImportPersistTxService.Persisted persisted;
        try {
            stage(plan);
            persisted = persistTxService.persist(plan, actorId);
        } catch (DataIntegrityViolationException e) {
            // 같은 산출물이 같은 순간에 들어왔다 — 사전 조회가 못 막는 창이며 여기서 마감한다.
            long existing = findDuplicate(vmsClipId).orElse(-1L);
            // ★ 먼저 커밋한 쪽이 있으면 <b>파일을 지우지 않는다</b>. 저장 위치가 이관 식별자로 정해져
            //   두 요청이 같은 자리를 쓰므로, 여기서 지우면 그쪽 행이 가리키는 파일이 사라진다.
            if (existing < 0) {
                fileStager.cleanupQuietly(plan.stagedFiles());
            } else {
                log.info("[Import] staged files kept — already owned by rawSn={}", existing);
            }
            historyTxService.failQuietly(trnsfSn, "이미 가져온 산출물이다.");
            throw new CustomException(ErrorCode.CONFLICT, duplicateMessage(existing));
        } catch (CustomException e) {
            fileStager.cleanupQuietly(plan.stagedFiles());
            historyTxService.failQuietly(trnsfSn, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            fileStager.cleanupQuietly(plan.stagedFiles());
            historyTxService.failQuietly(trnsfSn, e.getClass().getSimpleName());
            log.warn("[Import] import failed trnsfSn={} cause={}", trnsfSn, e.getClass().getSimpleName());
            throw new CustomException(ErrorCode.INTERNAL_ERROR, "적재에 실패했습니다.");
        }

        // ★ 여기서부터 적재는 <b>이미 커밋됐다</b>. 이 뒤에 무엇이 실패해도 파일을 지우지 않고 적재를
        //   되돌리지 않는다 — 지우면 커밋된 행이 가리키는 파일이 사라지고, 되돌릴 수단은 없다.
        //   이력 마감이 실패하는 것은 적재를 무르는 근거가 아니다(마감은 조용히 넘어간다).
        //   삼키는 자리는 트랜잭션 <b>밖</b>이어야 한다. 안에서 잡으면 그 트랜잭션에 이미 롤백 표시가
        //   서 있어 경계에서 다시 예외가 난다.
        try {
            historyTxService.succeed(trnsfSn, persisted.rawSn(),
                    persisted.frameCount(), persisted.labelCount());
        } catch (RuntimeException e) {
            log.warn("[Import] history succeed-mark skipped trnsfSn={} cause={}",
                    trnsfSn, e.getClass().getSimpleName());
        }
        log.info("[Import] imported trnsfSn={} rawSn={} frames={} labels={}",
                trnsfSn, persisted.rawSn(), persisted.frameCount(), persisted.labelCount());
        return new ImportCreateResponse(persisted.rawSn(), trnsfSn,
                persisted.frameCount(), persisted.labelCount());
    }

    // ------------------------------------------------------------------ 계획

    /**
     * 계획을 세운다 — 경로 조립·프레임 순번 부여·메타 보관 대상 선별.
     *
     * <p>프레임 순번({@code FRM_NO})은 <b>영상 내 실제 위치</b>({@code VDO_FRM_NO}) 오름차순으로 매긴다.
     * 두 값은 뜻이 다르며 바꿔 담으면 프레임과 라벨이 서로 다른 장면을 가리킨다(ERD-031). 실제 위치를
     * 모르는 프레임(짝 문서가 없는 이미지)은 뒤로 보내되 이름 순으로 고정해, 같은 폴더를 두 번 가져와도
     * 순번이 흔들리지 않게 한다.
     */
    private ImportPlan buildPlan(ImportedDataset dataset, String vmsClipId, boolean deidentified,
                                 Path videoFile, ImportMappingResolver.Resolved mappings) {
        ImportedDataset.VideoBlock video = dataset.video();
        if (video == null || video.fileName() == null) {
            // 저장 위치의 마지막 이름이 여기서 나온다 — 자리표시자를 쓰면 학습데이터 산출물에 인공
            //   파일명이 실린다. 비울 수도 없다(비식별·산출물 저장 위치가 이 값에서 파생된다).
            throw new CustomException(ErrorCode.INVALID_INPUT,
                    "산출물이 준 영상 파일명을 쓸 수 없어 저장 위치를 정할 수 없습니다.");
        }
        String base = deidentified ? storageDeidentifiedPath : storageRawPath;
        String rawFilePathNm;
        try {
            rawFilePathNm = ImportPathPolicy.rawFilePath(base, vmsClipId, video.fileName());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
        }

        List<ImportedDataset.Frame> ordered = new ArrayList<>(dataset.frames());
        ordered.sort(Comparator
                .comparing((ImportedDataset.Frame f) -> f.videoFrameNo() == null)
                .thenComparing(f -> f.videoFrameNo() == null ? 0L : f.videoFrameNo())
                .thenComparing(f -> f.imageFileName() == null ? "" : f.imageFileName()));

        List<ImportPlan.FramePlan> framePlans = new ArrayList<>();
        List<String> stagedFiles = new ArrayList<>();
        if (videoFile != null) {
            stagedFiles.add(rawFilePathNm);
        }
        long frameNo = 0;
        for (ImportedDataset.Frame frame : ordered) {
            String path = null;
            if (frame.hasImage()) {
                try {
                    // 프레임은 이관 전용 접두가 아니라 <b>원본·비식별 서브트리 규약</b> 위에 놓는다.
                    //   두 기준경로가 같은 디렉터리일 수 있어 어느 벌인지 가리는 것은 그 접두뿐이고,
                    //   규약 밖에 두면 프레임 이미지 서빙과 학습데이터 산출이 통째로 거부된다.
                    path = ImportPathPolicy.frameFilePath(base, deidentified, vmsClipId,
                            frame.imageFileName());
                } catch (IllegalArgumentException e) {
                    throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
                }
                stagedFiles.add(path);
            }
            // 비식별 완료본을 받았으면 그 이미지가 곧 비식별 프레임이다 — 원본 픽셀은 우리에게 없으므로
            //   원본 경로를 비운다(해상도 파생 프레임과 같은 표현). 원본을 받았으면 반대다.
            framePlans.add(new ImportPlan.FramePlan(frame, frameNo++,
                    deidentified ? null : path, deidentified ? path : null));
        }

        return new ImportPlan(dataset, vmsClipId, rawFilePathNm, deidentified, videoFile,
                List.copyOf(framePlans), mappings, List.copyOf(stagedFiles),
                preservedMeta(dataset, deidentified));
    }

    /**
     * 저작도구 스키마에 착지할 자리가 없는 값을 <b>원문 그대로</b> 모은다(ERD-031 메타 절).
     *
     * <p>버리면 되돌릴 수 없다. 특히 <b>원천 축 개인정보 3필드</b>는 착지 컬럼이 관제 수신 원장에만
     * 있는데 이 경로는 그 원장을 거치지 않으므로, 여기 보관한 값이 학습데이터 산출의 유일한 조달처다.
     *
     * <p><b>프레임 축</b> 3필드는 착지 자리가 축에 따라 갈린다 — 비식별이 끝난 것으로 지정해 가져오면
     * {@code LS_DATA_SRC} 컬럼에 프레임마다 적재되므로 여기 보관하지 않고(같은 사실이 두 자리에 있으면
     * 어느 것이 진실인지 갈린다), 원본이라고 지정한 경우에만 원문을 보관한다. 그 분기는
     * {@link ExportPrivacyPolicy#importedFrameValuesLandOnDeidentAxis(boolean)} 이 단독으로 정하며 여기서
     * 다시 판정하지 않는다.
     *
     * <p>보관할 때는 프레임 축 값이 프레임마다 다를 수 있으나 메타 표가 영상 단위라 행을 나눌 수 없다.
     * 그래서 <b>관측된 서로 다른 값</b>만 이어 담는다 — 값이 하나면 그 값 그대로이고, 갈리면 갈렸다는
     * 사실이 남는다. 어느 쪽이든 관측되지 않은 값을 지어내지 않는다.
     */
    private static Map<String, String> preservedMeta(ImportedDataset dataset, boolean deidentified) {
        Map<String, String> meta = new LinkedHashMap<>();
        ImportedDataset.VideoBlock video = dataset.video();
        if (video != null) {
            put(meta, ImportMetaKeys.VIDEO_EXTERNAL_ID, video.externalVideoId());
            put(meta, ImportMetaKeys.VIDEO_COORDINATES, video.coordinates());
            put(meta, ImportMetaKeys.VIDEO_LOCATION, video.location());
            put(meta, ImportMetaKeys.VIDEO_CCTV_HEIGHT, video.cctvHeight());
            put(meta, ImportMetaKeys.VIDEO_CCTV_AZIMUTH, video.cctvAzimuth());
            put(meta, ImportMetaKeys.VIDEO_CCTV_MNG_NO, video.cctvMngNo());
            put(meta, ImportMetaKeys.VIDEO_DATA_SOURCE, video.dataSource());
            put(meta, ImportMetaKeys.VIDEO_EVENT_LOG, video.eventLog());
            put(meta, ImportMetaKeys.VIDEO_EVENT_LEVEL1_NAME, video.eventLevel1Name());
            put(meta, ImportMetaKeys.VIDEO_EVENT_LEVEL2_NAME, video.eventLevel2Name());
            put(meta, ImportMetaKeys.VIDEO_EVENT_LEVEL3_NAME, video.eventLevel3Name());
            put(meta, ImportMetaKeys.VIDEO_ANONYMITY, video.anonymity());
            put(meta, ImportMetaKeys.VIDEO_PSEUDONYMITY, video.pseudonymity());
            put(meta, ImportMetaKeys.VIDEO_PRIVACY_INCLUDED, video.privacyIncluded());
        }
        if (!ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis(deidentified)) {
            // 착지 컬럼이 없는 축이라 여기 보관하는 것이 유일한 조달처다.
            put(meta, ImportMetaKeys.IMAGE_ANONYMITY,
                    distinctFrameValues(dataset, ImportedDataset.Frame::anonymity));
            put(meta, ImportMetaKeys.IMAGE_PSEUDONYMITY,
                    distinctFrameValues(dataset, ImportedDataset.Frame::pseudonymity));
            put(meta, ImportMetaKeys.IMAGE_PRIVACY_INCLUDED,
                    distinctFrameValues(dataset, ImportedDataset.Frame::privacyIncluded));
        }
        return Map.copyOf(meta);
    }

    private static String distinctFrameValues(
            ImportedDataset dataset,
            java.util.function.Function<ImportedDataset.Frame, String> field) {
        Set<String> values = new LinkedHashSet<>();
        for (ImportedDataset.Frame frame : dataset.frames()) {
            String value = field.apply(frame);
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? null : String.join(",", values);
    }

    private static void put(Map<String, String> meta, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String cleaned = ExternalNameSanitizer.multilineText(value, META_VALUE_MAX);
        if (cleaned != null) {
            meta.put(key, cleaned);
        }
    }

    /** {@code LS_DATA_META.META_VL} 컬럼 폭. */
    private static final int META_VALUE_MAX = 2000;

    // ------------------------------------------------------------------ 파일

    /** 계획대로 파일을 옮긴다 — 영상은 받았을 때만, 프레임은 실제 이미지가 있는 것만. */
    private void stage(ImportPlan plan) {
        if (plan.sourceVideo() != null) {
            fileStager.copy(plan.sourceVideo(), plan.rawFilePathNm());
        }
        for (ImportPlan.FramePlan framePlan : plan.framePlans()) {
            ImportedDataset.Frame frame = framePlan.frame();
            if (!frame.hasImage()) {
                continue;
            }
            String target = plan.deidentified() ? framePlan.deidFilePath() : framePlan.srcFilePath();
            fileStager.copy(frame.imagePath(), target);
        }
    }

    // ------------------------------------------------------------------ 보조

    private String buildClipId(String folderName, String datasetId) {
        try {
            return ImportPathPolicy.vmsClipId(folderName, datasetId);
        } catch (IllegalArgumentException e) {
            // 식별자를 만들 수 없으면 같은 산출물인지 가릴 축이 없다 — 임의 값을 만들면 중복이 통과한다.
            throw new CustomException(ErrorCode.INVALID_INPUT, e.getMessage());
        }
    }

    /**
     * 이미 가져온 산출물인가 — 이관 식별자로 가린다.
     *
     * <p>여기에 트랜잭션 경계를 두지 않는다. 같은 빈 안에서 부르면 프록시를 지나지 않아 그 표시가
     * <b>실제로는 아무 일도 하지 않는다</b>(이 저장소에 자기호출로 트랜잭션이 무력화된 전례가 있다).
     * 리포지토리 조회는 그 자체로 읽기 트랜잭션을 갖는다.
     */
    private Optional<Long> findDuplicate(String vmsClipId) {
        return videoRepository.findByVmsClipId(vmsClipId).map(LsDataRaw::getRawSn);
    }

    /**
     * 중복 안내 — <b>기존 영상의 식별번호를 사람이 볼 수 있게</b> 담는다(AC-044).
     *
     * <p>표준 오류 응답의 본문 자리는 {@code null} 로 고정돼 있어 별도 필드를 만들 수 없다. 식별번호는
     * 영상 일련번호일 뿐 개인정보가 아니므로 메시지에 싣는다.
     */
    private static String duplicateMessage(long existingRawSn) {
        if (existingRawSn < 0) {
            return "이미 가져온 산출물입니다.";
        }
        return "이미 가져온 산출물입니다. 기존 영상 번호: " + existingRawSn;
    }

    /** 폴더 바로 아래 항목 수 — 상한 + 1 까지만 센다(다 세는 것 자체가 비용이다). */
    private long countEntries(Path folder) {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.limit((long) maxEntries + 1).count();
        } catch (IOException e) {
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 폴더를 읽을 수 없습니다.");
        }
    }

    private ImportedDataset parse(Path folder) {
        try {
            return parser.parseFolder(folder);
        } catch (UncheckedIOException | IllegalArgumentException e) {
            // CWE-209 — 내부 경로·원문을 메시지에 담지 않는다.
            throw new CustomException(ErrorCode.INVALID_INPUT, "산출물 문서를 읽을 수 없습니다.");
        }
    }
}
