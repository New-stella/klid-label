package kr.co.cudo.authoring.transfer;

import kr.co.cudo.authoring.transfer.parser.ExternalNameSanitizer;
import kr.co.cudo.authoring.video.entity.LsDataRaw;

/**
 * 이관 영상의 <b>식별자와 저장 위치</b>를 정하는 단일 지점.
 *
 * <h3>식별자 — 중복 반입 거부의 실제 근거</h3>
 * <p>{@code IMPORT} 접두에 폴더 식별자와 산출물이 선언한 데이터셋 식별자를 이어 붙인다. 이 값이 들어가는
 * {@code LS_DATA_RAW.VMS_CLIP_ID} 에는 유일 제약이 걸려 있고, <b>같은 산출물을 두 번 가져오는 것을
 * 실제로 막는 것은 그 제약</b>이다. 이관 이력 표는 사람이 경위를 되짚는 기록이며, 두 노드가 같은 순간에
 * 들어오는 것까지 막지는 못한다(조회 후 판정은 check-then-act 라 창이 열린다).
 *
 * <h3>저장 위치 — 비울 수 없는 값이다</h3>
 * <p>{@code RAW_FILE_PATH_NM} 은 저장소 원본 기준경로 아래 {@code imports}, 그 아래 그 영상의 식별자,
 * 그 아래 <b>산출물이 준 원본 파일명</b>이다. 이 값을 비우면 안 되는 이유가 둘이다.
 * <ul>
 *   <li>비식별 산출물과 학습데이터 산출물의 저장 위치가 이 값의 <b>디렉터리 부분에서 파생</b>된다.
 *       비어 있으면 그 도출이 입력 오류로 끝나고, 학습데이터 산출물 생성이 <b>예외 없이 조용히
 *       실패로만 마감</b>되어 원인을 밖에서 알 수 없다.</li>
 *   <li>학습데이터 산출물의 영상 파일명이 이 경로의 <b>마지막 이름</b>에서 나온다. 자리표시자를 쓰면
 *       산출물에 인공 파일명이 실린다.</li>
 * </ul>
 *
 * <h3>외부 문자열은 조립 전에 정제한다</h3>
 * <p>폴더명과 데이터셋 식별자는 키가 되므로 허용 문자만 남기고, 파일명은 원문을 최대한 보존하되
 * 마지막 요소만 취한다(CWE-22). 길이 상한을 넘으면 <b>자르지 않고 거부</b>한다 — 잘린 경로는 존재하지
 * 않는 자리를 가리키므로, 그 상태로 적재하면 나중에 파일을 찾지 못하는 영상이 남는다.
 *
 * @design DOMAIN-017
 * @design ERD-031
 */
public final class ImportPathPolicy {

    /** 이관 영상 식별자 접두. */
    public static final String CLIP_ID_PREFIX = "IMPORT";

    /** 저장소 원본 기준경로 아래 이관 전용 디렉터리 이름. */
    public static final String SEG_IMPORTS = "imports";

    /** {@code LS_DATA_RAW.RAW_FILE_PATH_NM} 컬럼 폭. */
    public static final int RAW_FILE_PATH_MAX = 500;

    /** 식별자 조각 하나의 상한 — 둘을 합쳐도 식별자 컬럼 폭 안에 들어오게 잡는다. */
    private static final int IDENTIFIER_PART_MAX = 50;

    private ImportPathPolicy() {
    }

    /**
     * 이관 영상 식별자를 만든다.
     *
     * @param folderName        산출물 폴더 이름
     * @param datasetIdentifier 산출물 문서가 선언한 데이터셋 식별자
     * @throws IllegalArgumentException 두 값이 모두 비어 식별자를 만들 수 없을 때
     */
    public static String vmsClipId(String folderName, String datasetIdentifier) {
        String folder = ExternalNameSanitizer.identifier(folderName, IDENTIFIER_PART_MAX);
        String dataset = ExternalNameSanitizer.identifier(datasetIdentifier, IDENTIFIER_PART_MAX);
        if (folder == null && dataset == null) {
            // 둘 다 없으면 같은 산출물인지 가릴 축이 없다 — 임의 값을 만들면 중복 반입이 통과한다.
            throw new IllegalArgumentException("이관 식별자를 만들 수 없습니다. 폴더 이름과 데이터셋 식별자가 모두 비어 있습니다.");
        }
        String clipId = CLIP_ID_PREFIX + "-" + (folder == null ? "" : folder)
                + "-" + (dataset == null ? "" : dataset);
        if (clipId.length() > LsDataRaw.VMS_CLIP_ID_MAX) {
            throw new IllegalArgumentException("이관 식별자가 허용 길이를 넘습니다.");
        }
        return clipId;
    }

    /**
     * 이관 영상 파일의 저장 위치를 만든다.
     *
     * @param storageRawPath 저장소 원본 기준경로(설정값 — 외부 입력이 아니다)
     * @param vmsClipId      {@link #vmsClipId} 가 만든 이관 영상 식별자
     * @param videoFileName  산출물이 준 영상 파일명 <b>원문</b>
     * @throws IllegalArgumentException 파일명을 쓸 수 없거나 조립 결과가 컬럼 폭을 넘을 때
     */
    public static String rawFilePath(String storageRawPath, String vmsClipId, String videoFileName) {
        if (storageRawPath == null || storageRawPath.isBlank()) {
            throw new IllegalArgumentException("저장소 원본 기준경로가 설정되지 않았습니다.");
        }
        if (vmsClipId == null || vmsClipId.isBlank()) {
            throw new IllegalArgumentException("이관 영상 식별자가 비어 있습니다.");
        }
        String fileName = ExternalNameSanitizer.fileName(videoFileName, IDENTIFIER_PART_MAX * 2);
        if (fileName == null) {
            // 학습데이터 산출물의 영상 파일명이 여기서 나오므로 자리표시자를 만들지 않는다.
            throw new IllegalArgumentException("산출물이 준 영상 파일명을 쓸 수 없습니다.");
        }
        String base = storageRawPath.endsWith("/")
                ? storageRawPath.substring(0, storageRawPath.length() - 1)
                : storageRawPath;
        String path = base + "/" + SEG_IMPORTS + "/" + vmsClipId + "/" + fileName;
        if (path.length() > RAW_FILE_PATH_MAX) {
            // 자르면 존재하지 않는 자리를 가리킨다 — 자르지 않고 거부한다.
            throw new IllegalArgumentException("이관 영상 저장 경로가 허용 길이를 넘습니다.");
        }
        return path;
    }
}
