package kr.co.cudo.authoring.transfer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import kr.co.cudo.authoring.assignment.entity.LsRawDataStatus;
import kr.co.cudo.authoring.assignment.repository.LsRawDataStatusRepository;
import kr.co.cudo.authoring.batch.entity.LsDataLbl;
import kr.co.cudo.authoring.batch.entity.LsDataMeta;
import kr.co.cudo.authoring.batch.entity.LsDataSrc;
import kr.co.cudo.authoring.batch.entity.LsDeidentProcLog;
import kr.co.cudo.authoring.batch.repository.LsDataLblRepository;
import kr.co.cudo.authoring.batch.repository.LsDataMetaRepository;
import kr.co.cudo.authoring.batch.repository.LsDataSrcRepository;
import kr.co.cudo.authoring.batch.repository.LsDeidentProcLogRepository;
import kr.co.cudo.authoring.common.util.LabelPointSerializer;
import kr.co.cudo.authoring.common.util.Point;
import kr.co.cudo.authoring.dataset.export.ExportPrivacyPolicy;
import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.transfer.parser.ImportedDataset;
import kr.co.cudo.authoring.video.entity.LsDataRaw;
import kr.co.cudo.authoring.video.event.VideoIngestedEvent;
import kr.co.cudo.authoring.video.repository.VideoRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 이관 결과를 <b>한 트랜잭션에</b> 영속한다 — 영상·프레임·라벨·메타·비식별 이력·검수 상태.
 *
 * <h3>왜 한 트랜잭션인가</h3>
 * <p>나누면 "영상만 있고 프레임이 없는" 상태가 남는다. 그 영상은 검수 목록에 나타나는데 열면 비어
 * 있고, 학습데이터 산출은 프레임 0건으로 조용히 건너뛴다. 실패하면 통째로 되돌리는 편이 낫다.
 *
 * <h3>이 경로가 <b>하지 않는</b> 것 (ADR-048)</h3>
 * <ul>
 *   <li><b>관제 수신 원장에 쓰지 않는다</b> — 그 원장은 "관제가 무엇을 보냈는가"의 기록이라, 저작도구가
 *       자기 판단으로 행을 넣으면 나중에 관제가 보낸 것과 우리가 넣은 것을 구분할 수 없다.</li>
 *   <li><b>작업자 제출 단계를 거치지 않는다</b> — 이 경로에는 라벨링 작업 자체가 없다. 검수 대기
 *       ({@code PENDING})로 바로 만들고 배정은 검수자가 고칠 것이 있을 때 한다.</li>
 *   <li><b>적재가 비식별 선두 단계를 언제나 시작시키지는 않는다</b> — 아래 발행 조건 참조.</li>
 * </ul>
 *
 * <h3>비식별 선두 이벤트는 한 경우에만 발행한다</h3>
 * <p>{@code 원본으로 지정 + 영상 파일을 함께 받음} 일 때만 {@link VideoIngestedEvent} 를 발행한다.
 * 비식별이 끝났다고 지정해 받은 산출물은 다시 돌릴 이유가 없고, 프레임만 받은 산출물은 비식별할 대상
 * 영상이 아예 없어 발행해도 할 일이 없다(그 경우의 보류는 외부 비식별 산출물을 받아 기록하는 별도
 * 행위가 푼다). 발행은 <b>트랜잭션 안</b>에서 한다 — 수신 배선이 {@code AFTER_COMMIT} 이라 트랜잭션
 * 밖에서 발행하면 아무도 받지 못한다.
 *
 * @design DOMAIN-017
 * @design API-206
 * @design ERD-031
 * @design ADR-048
 * @design AC-045
 * @design AC-046
 * @design AC-047
 */
@Service
@RequiredArgsConstructor
public class ImportPersistTxService {

    private static final Logger log = LoggerFactory.getLogger(ImportPersistTxService.class);

    /** {@code LS_DATA_LBL.LBL_NM} 컬럼 폭. */
    private static final int LABEL_NAME_MAX = 80;

    /** 이관 프레임의 개인정보 유형 — fail-closed 로 <b>개인정보 포함</b>으로 둔다. */
    private static final String IMPORT_PRVC_TYPE = LsDataRaw.PRVC_TYPE_PRVC;

    private final VideoRepository videoRepository;
    private final LsDataSrcRepository srcRepository;
    private final LsDataLblRepository labelRepository;
    private final LsDataMetaRepository metaRepository;
    private final LsDeidentProcLogRepository procLogRepository;
    private final LsRawDataStatusRepository statusRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    /** 적재 결과 요약 — 이관 이력에 그대로 기록된다. */
    public record Persisted(long rawSn, int frameCount, int labelCount) {
    }

    /**
     * 계획대로 적재한다.
     *
     * @param actorId 적재를 실행한 사람
     * @throws org.springframework.dao.DataIntegrityViolationException
     *         같은 산출물이 동시에 들어와 {@code VMS_CLIP_ID} 유일 제약을 위반했을 때 —
     *         호출부가 <b>트랜잭션 밖</b>에서 받아 409 로 마감한다
     */
    @Transactional(value = "controlTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public Persisted persist(ImportPlan plan, String actorId) {
        ImportedDataset dataset = plan.dataset();
        ImportedDataset.VideoBlock video = dataset.video();

        LsDataRaw raw = LsDataRaw.createFromImport(
                plan.vmsClipId(),
                plan.mappings().eventTypeCd(),
                video == null ? null : video.stdgCd(),
                IMPORT_PRVC_TYPE,
                plan.rawFilePathNm(),
                video == null ? null : video.dateCreated(),
                video == null ? null : video.durationSec(),
                plan.deidentified());
        // 산출물의 촬영환경 표기를 저작도구 값으로 바꿔 저장한다(DFEAT-057) — 정규화는 파서가 이미
        //   마쳤고 허용값 밖은 null 이다(짐작해 옮기지 않는다).
        if (video != null) {
            raw.changeShootingEnvironment(video.weather(), video.timeOfDay(), video.season());
        }
        // ★ 유일 제약 위반을 <b>여기서</b> 드러나게 한다. 뒤로 미루면 프레임·라벨을 다 넣은 뒤 커밋
        //   시점에 깨져 실패 비용만 커진다.
        LsDataRaw savedRaw = videoRepository.saveAndFlush(raw);
        Long rawSn = savedRaw.getRawSn();

        // 검수 대기 — 작업자 배정도 검수 제출도 거치지 않는다(AC-045).
        LsRawDataStatus status = LsRawDataStatus.initial(rawSn);
        if (!plan.deidentified()) {
            // 원본으로 가져온 영상은 비식별이 끝나기 전까지 <b>검수 승인만</b> 막힌다(AC-046).
            //   라벨 조회·프레임 이미지·영상 스트리밍은 닫지 않는다 — 이 경로에는 라벨과 프레임이
            //   이미 들어와 있어 그것을 못 보면 검수 자체가 성립하지 않는다.
            status.markDeidentNotCompleted();
        }
        statusRepository.save(status);

        // 산출물이 프레임마다 준 개인정보 3필드가 우리 비식별 축 컬럼에 착지하는가 — 판정은 산출
        //   정책이 단독으로 소유한다(ERD-031 프레임 행 절). 여기서 다시 판정하면 같은 분기가 두 벌이 된다.
        boolean privacyLandsOnFrames =
                ExportPrivacyPolicy.importedFrameValuesLandOnDeidentAxis(plan.deidentified());

        List<LsDataSrc> frames = new ArrayList<>();
        for (ImportPlan.FramePlan framePlan : plan.framePlans()) {
            ImportedDataset.Frame frame = framePlan.frame();
            LsDataSrc src = LsDataSrc.createFromImport(rawSn, framePlan.frameNo(),
                    frame.videoFrameNo(), framePlan.srcFilePath(), framePlan.deidFilePath(),
                    frame.dateCaptured(),
                    landedYn(privacyLandsOnFrames, frame.anonymity()),
                    landedYn(privacyLandsOnFrames, frame.pseudonymity()),
                    landedYn(privacyLandsOnFrames, frame.privacyIncluded()));
            String description = frameDescription(frame);
            if (description != null) {
                src.updateDescription(description);
            }
            frames.add(src);
        }
        List<LsDataSrc> savedFrames = srcRepository.saveAll(frames);

        List<LsDataLbl> labels = new ArrayList<>();
        for (int i = 0; i < savedFrames.size(); i++) {
            LsDataSrc src = savedFrames.get(i);
            ImportedDataset.Frame frame = plan.framePlans().get(i).frame();
            for (ImportedDataset.Shape shape : frame.shapes()) {
                LsDataLbl label = toLabel(src.getSrcSn(), shape, plan.mappings());
                if (label != null) {
                    labels.add(label);
                }
            }
        }
        if (!labels.isEmpty()) {
            labelRepository.saveAll(labels);
        }

        List<LsDataMeta> metas = new ArrayList<>();
        for (Map.Entry<String, String> entry : plan.preservedMeta().entrySet()) {
            metas.add(LsDataMeta.create(rawSn, entry.getKey(), entry.getValue()));
        }
        if (!metas.isEmpty()) {
            metaRepository.saveAll(metas);
        }

        if (plan.deidentified()) {
            // 받은 영상이 곧 비식별 영상이다 — 그 위치를 이력에 <b>적재값으로</b> 남긴다.
            //   비식별 영상 경로는 언제나 이 값을 읽어 쓰며 이름을 조합하거나 추측하지 않는다
            //   (외부 비식별 처리가 붙이는 파일 이름은 처리 주체마다 다르다). 이력이 없으면 그 경로를
            //   조달할 수 없어 영상 재생과 관제 픽업이 성립하지 않는다(ERD-031 비식별 이력 절).
            LsDeidentProcLog procLog = LsDeidentProcLog.request(
                    rawSn, null, plan.rawFilePathNm(), actorId);
            procLog.succeed(plan.rawFilePathNm());
            procLogRepository.save(procLog);
        }

        if (!plan.deidentified() && plan.sourceVideo() != null) {
            // 원본을 영상 파일과 함께 받은 경우만 — 저작도구가 이미 가지고 있는 비식별 단계를 태운다.
            eventPublisher.publishEvent(new VideoIngestedEvent(rawSn));
        }

        log.info("[Import] persisted rawSn={} frames={} labels={} deidentified={}",
                rawSn, savedFrames.size(), labels.size(), plan.deidentified());
        return new Persisted(rawSn, savedFrames.size(), labels.size());
    }

    /**
     * 도형 1건 → 라벨 행.
     *
     * <p>{@code LBL_SRC_CD} 는 비운다 — 그 컬럼은 <b>무엇이 만들었는가</b>를 담는 축이고 값은 자동
     * 탐지·분할·보간·시계열이다. 이관해 온 라벨은 외부 작업자가 손으로 그린 것이라 비어 있는 것이
     * 정확하다(ERD-031 라벨 행 절). {@code LBL_ID} 는 확정된 대응을 따른다 — 두 축을 섞지 않는다.
     */
    private LsDataLbl toLabel(Long srcSn, ImportedDataset.Shape shape,
                              ImportMappingResolver.Resolved mappings) {
        if (!shape.loadableAsLabel()) {
            // 좌표가 없는 도형은 라벨이 될 수 없다 — 경계상자·키포인트는 해석 규칙이 확정되지 않아
            //   파서가 원문만 담아 두었고, 그것을 여기서 짐작해 좌표로 바꾸지 않는다.
            //   ★판정은 Shape.loadableAsLabel 하나다 — 미리보기 집계가 같은 것을 부른다.
            return null;
        }
        List<List<Point>> rings = shape.polygonRings();
        Long labelId = mappings.labelIdByCategory().get(shape.categoryId());
        String labelNm = mappings.labelNameByCategory().get(shape.categoryId());
        if (labelId == null || labelNm == null) {
            // 미확정 분류가 남으면 적재 자체가 막히므로 여기 오지 않는다. 그래도 fail-closed 로 둔다 —
            //   여기서 코드 문자열을 이름 대신 넣으면 학습데이터에 존재하지 않는 분류가 실린다.
            return null;
        }
        String pointsJson = LabelPointSerializer.toJson(rings.get(0), objectMapper);
        return LsDataLbl.createRestored(srcSn, LsDataLbl.TYPE_POLYGON, labelId,
                truncate(labelNm, LABEL_NAME_MAX), pointsJson, null, null, shape.trackId(), null);
    }

    /**
     * 착지하는 축이면 산출물이 준 값을 우리 저장 형식으로 옮겨 돌려주고, 아니면 {@code null}.
     *
     * <p>{@code null} 은 "값이 없다"가 아니라 <b>"이 컬럼에 담지 않는다"</b>는 뜻이며, 엔티티 팩토리가
     * 적재 기본값으로 채운다. 옮길 수 없는 표기도 같은 자리로 떨어진다 — 짐작해 한쪽으로 접으면 그
     * 짐작이 곧 개인정보 판정 사실이 되어 산출물에 실리고, 저장된 뒤에는 구분할 수 없다.
     */
    private static String landedYn(boolean lands, String rawValue) {
        return lands ? ExternalNameSanitizer.yn(rawValue) : null;
    }

    /** 프레임 설명 — 문서의 {@code image.description} 우선, 없으면 텍스트 항목의 상황묘사. */
    private static String frameDescription(ImportedDataset.Frame frame) {
        if (frame.description() != null && !frame.description().isBlank()) {
            return frame.description();
        }
        ImportedDataset.Texts texts = frame.texts();
        if (texts != null && texts.imageDescription() != null && !texts.imageDescription().isBlank()) {
            return texts.imageDescription();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
