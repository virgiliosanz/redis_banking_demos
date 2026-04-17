package com.redis.workshop.tools;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.File;
import java.util.*;

/**
 * Utility to parse PDF files and split their text content into overlapping chunks
 * suitable for vector embedding and RAG retrieval.
 */
public class PdfChunker {

    private static final int CHUNK_SIZE_CHARS = 2000;  // ~500 tokens × 4 chars/token
    private static final int OVERLAP_CHARS = 400;       // ~100 tokens overlap

    /**
     * Parse a PDF file and split its text into overlapping chunks.
     *
     * @param pdfPath  path to the PDF file
     * @param docId    identifier for the document (e.g., "psd2")
     * @param docTitle human-readable title (e.g., "PSD2 - Payment Services Directive")
     * @return list of chunk maps with keys: id, title, source, chunkIndex, content
     */
    public static List<Map<String, String>> chunkPdf(String pdfPath, String docId, String docTitle) {
        try (PDDocument document = Loader.loadPDF(new File(pdfPath))) {
            return chunkLoadedDocument(document, docId, docTitle);
        } catch (Exception e) {
            System.err.println("Failed to parse PDF: " + pdfPath + " — " + e.getMessage());
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

    /**
     * Parse a PDF from raw bytes (e.g., loaded from classpath) and split its text into overlapping chunks.
     */
    public static List<Map<String, String>> chunkPdf(byte[] pdfBytes, String docId, String docTitle) {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            return chunkLoadedDocument(document, docId, docTitle);
        } catch (Exception e) {
            System.err.println("Failed to parse PDF bytes for " + docId + " — " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private static List<Map<String, String>> chunkLoadedDocument(PDDocument document, String docId, String docTitle) {
        List<Map<String, String>> chunks = new ArrayList<>();
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            String fullText = stripper.getText(document);

            // Clean up extracted text
            fullText = fullText.replaceAll("\\r\\n", "\n");
            fullText = fullText.replaceAll("[ \\t]+", " ");
            fullText = fullText.replaceAll("\\n{3,}", "\n\n");
            fullText = fullText.trim();

            // Split into overlapping chunks
            int index = 0;
            int chunkNum = 0;

            while (index < fullText.length()) {
                int end = Math.min(index + CHUNK_SIZE_CHARS, fullText.length());

                // Try to break at a sentence boundary
                if (end < fullText.length()) {
                    int lastPeriod = fullText.lastIndexOf(". ", end);
                    if (lastPeriod > index + CHUNK_SIZE_CHARS / 2) {
                        end = lastPeriod + 2; // include the period and space
                    }
                }

                String chunk = fullText.substring(index, end).trim();

                // Skip tiny or empty chunks
                if (chunk.length() > 50) {
                    Map<String, String> entry = new LinkedHashMap<>();
                    entry.put("id", docId + ":chunk:" + chunkNum);
                    entry.put("title", docTitle);
                    entry.put("source", docId);
                    entry.put("chunkIndex", String.valueOf(chunkNum));
                    entry.put("content", chunk);
                    chunks.add(entry);
                    chunkNum++;
                }

                // Move forward with overlap
                index = end - OVERLAP_CHARS;
                if (index < 0) index = 0;
                if (end >= fullText.length()) break;
            }

            System.out.println("  Parsed " + docTitle + ": " + document.getNumberOfPages()
                    + " pages, " + fullText.length() + " chars → " + chunks.size() + " chunks");

        } catch (Exception e) {
            System.err.println("Failed to parse PDF text for " + docId + " — " + e.getMessage());
            e.printStackTrace();
        }
        return chunks;
    }
}
