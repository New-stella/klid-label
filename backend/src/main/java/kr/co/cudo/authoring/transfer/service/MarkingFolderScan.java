package kr.co.cudo.authoring.transfer.service;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 폴더를 훑어 모은 것 — 마킹 문서 목록과 <b>이름으로 찾을 수 있게 정리한</b> 영상 목록.
 *
 * <h3>왜 이름으로 정리하는가</h3>
 * <p>짝짓기 기준은 폴더 구조가 아니라 마킹 문서가 스스로 적어 둔 <b>영상 파일 이름</b>이다. 문서와
 * 영상이 서로 다른 자리에 놓여 있어도 같은 이름을 찾으면 짝이 된다(API-216). 그래서 훑는 동안 이름을
 * 키로 모아 두고, 짝짓기는 폴더를 다시 걷지 않는다.
 *
 * <h3>같은 이름이 여럿인 것은 <b>지우지 않고 남긴다</b></h3>
 * <p>값이 목록인 이유다. 하나만 남기면 「같은 이름이 둘 이상이라 정할 수 없다」와 「하나만 있다」가
 * 구분되지 않아, 짐작으로 고른 짝이 정상 짝과 똑같이 보인다(AC-1033).
 *
 * @param markingDocuments 마킹 문서의 <b>실경로</b> 목록 — 이름순으로 고정한다
 * @param videosByName     정제한 영상 파일 이름 → 그 이름을 가진 영상의 실경로 목록
 * @param scannedFileCount 훑은 파일 수
 * @param truncated        깊이·항목 수 상한에 걸려 일부만 훑었는지 여부
 * @param symlinkSkipped   따라가지 않고 건너뛴 바로가기 수
 * @param unreadableCount  열 수 없어 훑지 못한 폴더 수
 * @design DOMAIN-017
 * @design API-216
 * @design SEQ-030
 */
public record MarkingFolderScan(
        List<Path> markingDocuments,
        Map<String, List<Path>> videosByName,
        int scannedFileCount,
        boolean truncated,
        int symlinkSkipped,
        int unreadableCount) {

    /**
     * 그 이름의 영상이 <b>정확히 하나</b>일 때만 그 경로를 돌려준다.
     *
     * <p>없거나 둘 이상이면 {@code null} 이다 — 둘 이상에서 하나를 고르면 다른 영상의 마킹이 엉뚱한
     * 영상에 붙고, 저장된 뒤에는 어느 것이 짐작이었는지 구분할 수 없다.
     */
    public Path uniqueVideo(String fileName) {
        if (fileName == null) {
            return null;
        }
        List<Path> candidates = videosByName.get(fileName);
        return (candidates != null && candidates.size() == 1) ? candidates.get(0) : null;
    }

    /** 그 이름의 영상이 둘 이상이라 정할 수 없는가. */
    public boolean isAmbiguous(String fileName) {
        if (fileName == null) {
            return false;
        }
        List<Path> candidates = videosByName.get(fileName);
        return candidates != null && candidates.size() > 1;
    }
}
