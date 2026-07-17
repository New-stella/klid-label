package kr.co.cudo.authoring.portal.dto;

/**
 * 포털 TUS 업로드 세션 생성 커맨드.
 *
 * <p>포털 영상 업로드는 관제 TUS 와 달리 CCTV/이벤트/개인정보 메타를 요구하지 않는다
 * (포털 사용자 자기 자산). 전체 길이 + 표시용 원본 파일명만 받는다.
 *
 * @param uploadLength Upload-Length(byte) — 전체 파일 크기
 * @param fileName     표시용 원본 파일명(저장명은 서비스에서 UUID 강제)
 */
public record PortalTusCreateCommand(long uploadLength, String fileName) {
}
