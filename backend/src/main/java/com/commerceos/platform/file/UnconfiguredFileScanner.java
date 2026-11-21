package com.commerceos.platform.file;

import org.springframework.stereotype.Component;

@Component
public class UnconfiguredFileScanner implements FileScanner {
    @Override
    public String scan(String filename, byte[] content) {
        return "SCAN_UNAVAILABLE";
    }
}
