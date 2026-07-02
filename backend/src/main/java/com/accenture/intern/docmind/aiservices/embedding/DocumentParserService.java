package com.accenture.intern.docmind.aiservices.embedding;

import com.accenture.intern.docmind.aiservices.vision.*;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class DocumentParserService {

    private static final int MIN_IMAGE_DIMENSION_PX = 100;
    private static final int MAX_IMAGES_PER_DOCUMENT = 25;

    private final WebClient webClient;
    private final ImageVisionService imageVisionService;

    public DocumentParserService(ImageVisionService imageVisionService) {
        this.imageVisionService = imageVisionService;
        this.webClient = WebClient.builder()
                .exchangeStrategies(org.springframework.web.reactive.function.client.ExchangeStrategies.builder()
                        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                        .build())
                .build();
    }

    public record ExtractedImage(byte[] imageBytes, String mimeType, int pageNumber, SemanticImage visionResponse) {}

    public record PdfParseResult(String text, List<LayoutTextStripper.PdfTextElement> elements, List<ExtractedImage> images) {}

    public PdfParseResult parsePdfWithImages(Path filePath) throws IOException {
        try (PDDocument doc = PDDocument.load(filePath.toFile())) {
            LayoutTextStripper stripper = new LayoutTextStripper();
            String text = stripper.getText(doc);
            List<ExtractedImage> images = extractAndDescribeImages(doc);
            return new PdfParseResult(text, stripper.getElements(), images);
        }
    }

    public String parseTextFile(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    public String fetchWikipedia(String pageTitle) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("en.wikipedia.org")
                        .path("/w/api.php")
                        .queryParam("action", "query")
                        .queryParam("format", "json")
                        .queryParam("prop", "extracts")
                        .queryParam("explaintext", "1")
                        .queryParam("redirects", "1")
                        .queryParam("titles", pageTitle.replace(" ", "_"))
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                        com.fasterxml.jackson.databind.JsonNode pages = root.path("query").path("pages");
                        if (pages.elements().hasNext()) {
                            return pages.elements().next().path("extract").asText();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return "";
                })
                .block();
    }

    public Mono<List<String>> searchWikipedia(String query) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .scheme("https")
                        .host("en.wikipedia.org")
                        .path("/w/api.php")
                        .queryParam("action", "opensearch")
                        .queryParam("search", query)
                        .queryParam("limit", "5")
                        .queryParam("namespace", "0")
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .bodyToMono(String.class)
                .map(json -> {
                    List<String> results = new ArrayList<>();
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
                        if (root.isArray() && root.size() > 1) {
                            com.fasterxml.jackson.databind.JsonNode titles = root.get(1);
                            if (titles.isArray()) {
                                for (com.fasterxml.jackson.databind.JsonNode titleNode : titles) {
                                    results.add(titleNode.asText());
                                }
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                    return results;
                });
    }

    private List<ExtractedImage> extractAndDescribeImages(PDDocument doc) {
        List<ExtractedImage> results = new ArrayList<>();
        int pageNumber = 0;
        int processedCount = 0;

        for (PDPage page : doc.getPages()) {
            pageNumber++;

            if (processedCount >= MAX_IMAGES_PER_DOCUMENT) {
                log.warn("Reached max images per document ({}), skipping remaining pages",
                        MAX_IMAGES_PER_DOCUMENT);
                break;
            }

            PDResources resources = page.getResources();
            if (resources == null) {
                continue;
            }

            for (COSName xObjectName : resources.getXObjectNames()) {
                if (processedCount >= MAX_IMAGES_PER_DOCUMENT) {
                    break;
                }

                try {
                    PDXObject xObject = resources.getXObject(xObjectName);
                    if (!(xObject instanceof PDImageXObject imageXObject)) {
                        continue;
                    }

                    BufferedImage bufferedImage = imageXObject.getImage();
                    if (bufferedImage == null
                            || bufferedImage.getWidth() < MIN_IMAGE_DIMENSION_PX
                            || bufferedImage.getHeight() < MIN_IMAGE_DIMENSION_PX) {
                        continue;
                    }

                    byte[] pngBytes = toPngBytes(bufferedImage);
                    if (pngBytes == null) {
                        continue;
                    }

                    String previousText = extractTextFromPage(doc, pageNumber - 1);
                    String currentText = extractTextFromPage(doc, pageNumber);
                    String nextText = extractTextFromPage(doc, pageNumber + 1);
                    String contextText = ImageContextBuilder.buildPdfContext(previousText, currentText, nextText);

                    SemanticImage visionResponse = imageVisionService.describeImage(pngBytes, "image/png", contextText).block();
                    processedCount++;

                    if (visionResponse != null && visionResponse.summary() != null && !visionResponse.summary().isBlank()) {
                        results.add(new ExtractedImage(pngBytes, "image/png", pageNumber, visionResponse));
                        log.info("Described image on page {} ({}x{})", pageNumber,
                                bufferedImage.getWidth(), bufferedImage.getHeight());
                    }
                } catch (Exception e) {
                    log.warn("Failed to extract/describe an image on page {}: {}", pageNumber, e.getMessage());
                }
            }
        }

        return results;
    }

    private String extractTextFromPage(PDDocument doc, int pageNumber) {
        if (pageNumber < 1 || pageNumber > doc.getNumberOfPages()) return "";
        try {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            return stripper.getText(doc);
        } catch (Exception e) {
            return "";
        }
    }

    private byte[] toPngBytes(BufferedImage image) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.warn("Failed to encode extracted image as PNG: {}", e.getMessage());
            return null;
        }
    }
}
