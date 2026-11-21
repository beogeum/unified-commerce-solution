package com.commerceos.platform.file;

/** 업로드 파일 악성코드 검사. 검사기가 연결되지 않으면 SCAN_UNAVAILABLE 을 명시한다(DEC-F15). */
public interface FileScanner {
    String scan(String filename, byte[] content);
}
