package kr.co.cudo.authoring.transfer.service;

import kr.co.cudo.authoring.transfer.parser.ImportedDataset;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 적재 <b>직전</b>에 확정된 계획 — 트랜잭션이 열리기 전에 전부 정해진 값만 담는다.
 *
 * <h3>왜 계획을 먼저 확정하는가</h3>
 * <p>경로 조립·분류 대응 해석·파일 복사는 전부 트랜잭션 밖에서 끝나야 한다. 그래야 영속 구간이
 * 짧아지고(커넥션 기아 방지), 실패했을 때 "영상만 있고 프레임이 없는" 부분 상태가 남지 않는다.
 * 이 레코드가 그 경계선이다 — 여기 담긴 값 밖의 무엇도 영속 단계에서 새로 계산하지 않는다.
 *
 * @param dataset        훑어 읽은 산출물
 * @param vmsClipId      이관 영상 식별자(중복 반입 거부의 실제 근거인 유일 제약 값)
 * @param rawFilePathNm  영상 파일의 저작도구 저장 경로 — 영상 파일을 받지 않아도 <b>비우지 않는다</b>
 * @param deidentified   가져올 때 사람이 지정한 값
 * @param sourceVideo    복사할 원본 영상 파일의 실경로. 받지 않았으면 {@code null}
 * @param framePlans     프레임별 계획(순번·경로)
 * @param mappings       확정된 분류 대응
 * @param stagedFiles    이번 이관이 만들기로 한 파일의 절대경로 — 실패 시 <b>이 목록만</b> 지운다.
 *                       디렉터리를 통째로 지우면 같은 산출물을 동시에 가져온 다른 요청의 성공분이
 *                       함께 사라진다(같은 자리를 쓴다)
 * @design DOMAIN-017
 * @design ERD-031
 */
public record ImportPlan(ImportedDataset dataset,
                         String vmsClipId,
                         String rawFilePathNm,
                         boolean deidentified,
                         Path sourceVideo,
                         List<FramePlan> framePlans,
                         ImportMappingResolver.Resolved mappings,
                         List<String> stagedFiles,
                         Map<String, String> preservedMeta) {

    /**
     * 프레임 1건의 계획.
     *
     * @param frame        산출물이 읽어 준 프레임
     * @param frameNo      추출 순번({@code FRM_NO}) — 우리가 매긴다
     * @param srcFilePath  원본 프레임 경로. 비식별 완료본을 받은 경우 <b>{@code null}</b>
     *                     (우리에게 원본 픽셀이 없다 — 해상도 파생 프레임과 같은 표현)
     * @param deidFilePath 비식별 프레임 경로. 원본을 받은 경우 {@code null}
     */
    public record FramePlan(ImportedDataset.Frame frame, long frameNo,
                            String srcFilePath, String deidFilePath) {
    }
}
