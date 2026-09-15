package kr.co.cudo.authoring.common.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

/**
 * 포털 <b>소재 조회</b> 창구의 응답 — 데이터셋 1건의 소재 파일 <b>경로 목록</b>이다(파일 바이트 없음).
 *
 * <h3>★ 방향이 반대다 — 이것은 우리가 포털을 부르는 축이다</h3>
 * <p>정리 삭제 트리거는 포털이 우리를 부르지만, <b>소재 조회는 우리가 포털을 부른다.</b> 읽는 쪽이
 * 가장 자주 혼동하는 지점이라 먼저 못박는다. 창구·스키마의 소유자는 <b>포털</b>이며 이 레코드는
 * 그 계약을 우리 쪽에서 받아 적은 것이다.
 *
 * <h3>★ 우리 표준 응답 봉투를 쓰지 않는다</h3>
 * <p>{@code ApiResponse} 로 감싸지 않은 <b>평문 객체</b>가 그대로 온다. 오류도 평문 객체다.
 *
 * <h3>⚠ 비어 올 수 있는 값을 실패로 다루지 않는다</h3>
 * <p>{@link #code()} · {@link #variant()} · {@link MaterialFile#checksum()} 은 <b>수신 원장 없이 적재된
 * 옛 데이터</b>에서 비어 온다. 없다고 조달을 실패시키지 않는다(INT-014 「우리 쪽 규칙 3」).
 *
 * <h3>⚠ 모르는 소재 구분은 관대하게 무시한다</h3>
 * <p>{@link MaterialFile#fileDv()} 를 우리 쪽 enum 으로 좁히지 않는다 — 그러면 벤더 값역의 <b>사본이
 * 두 번째 진실원</b>이 되어, 포털이 값을 넓히는 순간 <b>정상 값을 우리가 먼저 막는다</b>. 이 저장소가
 * 검증 이벤트 유형 허용목록에서 이미 겪은 실패 방향이다. 그래서 문자열로 받고 <b>우리가 아는 구분만
 * 골라 쓰고 나머지는 건너뛴다</b>.
 *
 * <h3>⚠ {@link MaterialFile#localPath()} 는 내부 절대경로다</h3>
 * <p>로그·응답 어디에도 싣지 않는다(CWE-209). 이 값을 <b>그대로 열지도 않는다</b> — 반드시
 * {@link #repoRootDir()} 하위인지 실경로로 재검증한 뒤 <b>그 실경로로</b> 연다(INT-014 「우리 쪽 규칙 1」).
 *
 * @design INT-014
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortalMaterialsResponse(
        Long datasetId,
        String code,
        String version,
        String variant,
        String repoRootDir,
        List<MaterialFile> files) {

    /** 배포 압축본 구분값 — 데이터셋당 1건. */
    public static final String FILE_DV_DEPLOYMENT_ZIP = "DEPLOYMENT_ZIP";

    /** 데이터셋 영상 구분값 — 0건 이상. */
    public static final String FILE_DV_DATASET_VIDEO = "DATASET_VIDEO";

    /** null 목록을 빈 목록으로 정규화 — 호출부가 매번 null 을 방어하지 않게 한다. */
    public List<MaterialFile> filesOrEmpty() {
        return files == null ? List.of() : files;
    }

    /**
     * 배포 압축본 1건 — 없으면 {@code null}.
     *
     * <p>구성은 「압축본 1 + 영상 0..N」이지만 <b>여러 건이 와도 예외를 던지지 않고 첫 건을 쓴다</b>.
     * 되받을 수 없는 입구에서의 엄격함은 곧 손실이다.
     */
    public MaterialFile deploymentZip() {
        return filesOfDivision(FILE_DV_DEPLOYMENT_ZIP).stream().findFirst().orElse(null);
    }

    /** 데이터셋 영상 목록(0건 이상). */
    public List<MaterialFile> datasetVideos() {
        return filesOfDivision(FILE_DV_DATASET_VIDEO);
    }

    /**
     * 구분값이 일치하는 소재만 고른다 — <b>모르는 구분은 여기서 자연히 빠진다</b>(거부가 아니라 무시).
     *
     * <p>비교는 대소문자를 가리지 않는다. 구분값의 대소문자 표기는 계약에 못박힌 바가 없고, 표기가
     * 달라졌을 때 <b>배포 압축본을 통째로 못 찾는</b> 쪽이 훨씬 비싸다.
     */
    private List<MaterialFile> filesOfDivision(String division) {
        return filesOrEmpty().stream()
                .filter(f -> f != null && f.fileDv() != null
                        && f.fileDv().trim().toUpperCase(Locale.ROOT).equals(division))
                .toList();
    }

    /**
     * 소재 파일 1건.
     *
     * @param fileId   포털이 부여한 파일 식별자
     * @param fileDv   소재 구분 — {@link #FILE_DV_DEPLOYMENT_ZIP} · {@link #FILE_DV_DATASET_VIDEO}.
     *                 <b>우리가 모르는 값이 올 수 있다</b>
     * @param fileName 파일명(경로가 아니다)
     * @param localPath ⚠ <b>공유 저장소 절대경로</b> — 로그·응답에 싣지 않고, 그대로 열지도 않는다
     * @param fileSize 바이트 크기
     * @param checksum 무결성 값. <b>비어 올 수 있다</b>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MaterialFile(
            Long fileId,
            String fileDv,
            String fileName,
            String localPath,
            Long fileSize,
            String checksum) {
    }
}
