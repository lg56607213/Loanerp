package com.jdend.erp.common.excel;

import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.xml.sax.ContentHandler;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 업로드된 xlsx를 SAX 스트리밍 방식으로 파싱한다.
 * DOM 방식(XSSFWorkbook)은 파일 크기의 수십 배 힙을 사용해 OOM이 발생하므로,
 * 행 단위로 처리하는 이벤트 기반 API를 사용한다.
 */
public final class ExcelReader {

    private ExcelReader() {}

    /** 엑셀 행 하나 — 실제 엑셀 행 번호(1-based)를 함께 들고 있다. */
    public static final class IndexedRow {
        private final int rowNumber;
        private final Map<String, String> values;
        IndexedRow(int rowNumber, Map<String, String> values) {
            this.rowNumber = rowNumber;
            this.values = values;
        }
        /** 엑셀에서 보이는 행 번호(헤더가 1행). 오류 메시지에 그대로 쓴다. */
        public int getRowNumber() { return rowNumber; }
        public Map<String, String> getValues() { return values; }
    }

    /** 헤더와 데이터 행을 함께 담는다. 필수 열 누락 판별에 헤더가 필요하다. */
    public static final class Sheet {
        private final List<String> headers;
        private final List<IndexedRow> rows;
        Sheet(List<String> headers, List<IndexedRow> rows) {
            this.headers = headers;
            this.rows = rows;
        }
        public List<String> getHeaders() { return headers; }
        public List<IndexedRow> getRows() { return rows; }
    }

    /** 기존 호출부 호환용 — 행 번호가 필요 없을 때 쓴다. */
    public static List<Map<String, String>> readRows(InputStream is) {
        List<Map<String, String>> out = new ArrayList<>();
        for (IndexedRow r : readSheet(is).getRows()) out.add(r.getValues());
        return out;
    }

    public static Sheet readSheet(InputStream is) {
        Path tmp = null;
        try {
            // OPCPackage는 seekable 스트림이 필요하므로 임시파일에 먼저 기록
            tmp = Files.createTempFile("erp_excel_", ".xlsx");
            try (OutputStream out = Files.newOutputStream(tmp)) {
                is.transferTo(out);
            }

            try (OPCPackage pkg = OPCPackage.open(tmp.toFile())) {
                XSSFReader xssfReader       = new XSSFReader(pkg);
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
                XSSFReader.SheetIterator sheets =
                        (XSSFReader.SheetIterator) xssfReader.getSheetsData();

                RowCollector handler = new RowCollector();

                if (sheets.hasNext()) {
                    try (InputStream sheetStream = sheets.next()) {
                        XMLReader xmlReader     = XMLHelper.newXMLReader();
                        ContentHandler content  = new XSSFSheetXMLHandler(
                                xssfReader.getStylesTable(), strings, handler, false);
                        xmlReader.setContentHandler(content);
                        xmlReader.parse(new InputSource(sheetStream));
                    }
                }
                return new Sheet(handler.getHeaders(), handler.getRows());
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("엑셀 파일을 읽을 수 없습니다: " + e.getMessage());
        } finally {
            if (tmp != null) {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    private static final class RowCollector implements XSSFSheetXMLHandler.SheetContentsHandler {
        private final List<IndexedRow> rows = new ArrayList<>();
        private List<String> headers = null;
        private final Map<Integer, String> currentCells = new TreeMap<>();

        @Override
        public void startRow(int rowNum) {
            currentCells.clear();
        }

        @Override
        public void endRow(int rowNum) {
            if (rowNum == 0) {
                int maxCol = currentCells.isEmpty() ? 0
                        : Collections.max(currentCells.keySet()) + 1;
                headers = new ArrayList<>(Collections.nCopies(maxCol, ""));
                currentCells.forEach((col, val) -> {
                    if (col < headers.size()) headers.set(col, val.trim());
                });
            } else if (headers != null) {
                boolean blank = currentCells.values().stream().allMatch(String::isBlank);
                if (!blank) {
                    Map<String, String> map = new LinkedHashMap<>();
                    for (int i = 0; i < headers.size(); i++) {
                        String h = headers.get(i);
                        if (!h.isBlank()) {
                            map.put(h, currentCells.getOrDefault(i, "").trim());
                        }
                    }
                    // rowNum 은 0-based 이므로 엑셀에서 보이는 번호로 +1 한다.
                    rows.add(new IndexedRow(rowNum + 1, map));
                }
            }
        }

        @Override
        public void cell(String cellReference, String formattedValue, XSSFComment comment) {
            if (formattedValue == null || formattedValue.isBlank()) return;
            CellReference ref = new CellReference(cellReference);
            currentCells.put((int) ref.getCol(), formattedValue);
        }

        public List<IndexedRow> getRows() { return rows; }
        public List<String> getHeaders() { return headers == null ? List.of() : headers; }
    }
}
