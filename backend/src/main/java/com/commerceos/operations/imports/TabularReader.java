package com.commerceos.operations.imports;

import com.commerceos.shared.error.Errors;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * 엑셀(xlsx)·CSV 를 머리글 + 행(머리글 → 문자열)으로 읽는다.
 * <ul>
 *   <li>CSV 인코딩: UTF-8(BOM 포함) 우선, 아니면 CP949(국내 엑셀 기본 저장).</li>
 *   <li>엑셀 숫자 셀은 지수 표기 없이 원래 값(13자리 바코드 보호). 날짜 셀은 yyyy-MM-dd. 수식은 계산된 값.</li>
 *   <li>첫 번째 비어 있지 않은 행이 머리글. 완전히 빈 행은 건너뛴다(행 번호는 파일 기준 유지).</li>
 * </ul>
 */
public final class TabularReader {
    public static final int MAX_ROWS = 5000;
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private TabularReader() {
    }

    public record Table(List<String> headers, List<DataRow> rows, String sheetName, String encoding) {
    }

    /** rowNumber 는 사용자가 엑셀에서 보는 행 번호(머리글이 1행이면 첫 데이터는 2행). */
    public record DataRow(int rowNumber, Map<String, String> values) {
    }

    public static Table read(byte[] content, String filename, String sheetName) {
        if (content.length > MAX_BYTES) {
            throw Errors.unprocessable("IMPORT_FILE_TOO_LARGE", "파일은 10MB 까지 올릴 수 있습니다. 나눠서 올려 주세요.");
        }
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        boolean xlsx = name.endsWith(".xlsx") || (content.length > 3 && content[0] == 'P' && content[1] == 'K');
        if (name.endsWith(".xls") && !xlsx) {
            throw Errors.unprocessable("IMPORT_FORMAT_UNSUPPORTED", "예전 엑셀 형식(.xls)은 지원하지 않습니다. .xlsx 또는 CSV 로 저장해 올려 주세요.");
        }
        return xlsx ? readXlsx(content, sheetName) : readCsv(content);
    }

    private static Table readXlsx(byte[] content, String sheetName) {
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            Sheet sheet = sheetName == null ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) {
                throw Errors.unprocessable("IMPORT_SHEET_NOT_FOUND", "시트 '" + sheetName + "' 를 찾을 수 없습니다.");
            }
            List<String> headers = null;
            List<DataRow> rows = new ArrayList<>();
            for (Row r : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(r.getLastCellNum(), headers == null ? 0 : headers.size());
                for (int c = 0; c < last; c++) {
                    cells.add(text(r.getCell(c)));
                }
                if (cells.stream().allMatch(String::isEmpty)) {
                    continue;
                }
                if (headers == null) {
                    headers = cells.stream().map(String::trim).toList();
                    continue;
                }
                rows.add(new DataRow(r.getRowNum() + 1, zip(headers, cells)));
                if (rows.size() > MAX_ROWS) {
                    throw tooMany();
                }
            }
            if (headers == null) {
                throw Errors.unprocessable("IMPORT_EMPTY", "파일에 내용이 없습니다.");
            }
            return new Table(headers, rows, sheet.getSheetName(), "XLSX");
        } catch (com.commerceos.shared.error.ApiException e) {
            throw e;
        } catch (Exception e) {
            throw Errors.unprocessable("IMPORT_FILE_UNREADABLE", "엑셀 파일을 읽을 수 없습니다. 암호가 걸려 있거나 손상된 파일인지 확인하세요.");
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType t = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (t) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                    : BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
            case BOOLEAN -> cell.getBooleanCellValue() ? "Y" : "N";
            default -> "";
        };
    }

    private static Table readCsv(byte[] content) {
        String encoding = "UTF-8";
        String text;
        try {
            text = Charset.forName("UTF-8").newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(content)).toString();
        } catch (CharacterCodingException e) {
            encoding = "CP949";
            text = new String(content, Charset.forName("MS949"));
        }
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        List<List<String>> records = parseCsv(text);
        List<String> headers = null;
        List<DataRow> rows = new ArrayList<>();
        int line = 0;
        for (List<String> rec : records) {
            line++;
            if (rec.stream().allMatch(x -> x.trim().isEmpty())) {
                continue;
            }
            if (headers == null) {
                headers = rec.stream().map(String::trim).toList();
                continue;
            }
            rows.add(new DataRow(line, zip(headers, rec.stream().map(String::trim).toList())));
            if (rows.size() > MAX_ROWS) {
                throw tooMany();
            }
        }
        if (headers == null) {
            throw Errors.unprocessable("IMPORT_EMPTY", "파일에 내용이 없습니다.");
        }
        return new Table(headers, rows, null, encoding);
    }

    /** RFC 4180: 따옴표 안의 쉼표·줄바꿈·"" 이스케이프. */
    static List<List<String>> parseCsv(String s) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    private static Map<String, String> zip(List<String> headers, List<String> cells) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            if (!headers.get(i).isEmpty()) {
                m.put(headers.get(i), i < cells.size() ? cells.get(i) : "");
            }
        }
        return m;
    }

    private static com.commerceos.shared.error.ApiException tooMany() {
        return Errors.unprocessable("IMPORT_TOO_MANY_ROWS", "한 번에 " + MAX_ROWS + "행까지 올릴 수 있습니다. 파일을 나눠 주세요.");
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
