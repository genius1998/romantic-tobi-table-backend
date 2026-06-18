package com.tomy.tomy.ocr;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

@Service
public class GoogleVisionOcrService implements OcrService {

    @Value("${gcp.vision.credentials:}")
    private String credentialsLocation;

    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 1_500L;

    @Override
    public OcrResult extractText(MultipartFile file) {
        if (file == null || file.isEmpty())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "파일 없음");
        if (file.getSize() > 5L * 1024 * 1024)
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "파일 최대 5MB");

        try (InputStream in = openCredentialsStream()) {
            GoogleCredentials cred = GoogleCredentials.fromStream(in);
            ImageAnnotatorSettings settings = ImageAnnotatorSettings.newBuilder()
                    .setCredentialsProvider(FixedCredentialsProvider.create(cred))
                    .build();

            try (ImageAnnotatorClient client = ImageAnnotatorClient.create(settings)) {
                byte[] imageBytes = file.getBytes();
                Image img = Image.newBuilder().setContent(ByteString.copyFrom(imageBytes)).build();
                Feature feat = Feature.newBuilder().setType(Feature.Type.DOCUMENT_TEXT_DETECTION).build();
                AnnotateImageRequest req = AnnotateImageRequest.newBuilder()
                        .addFeatures(feat).setImage(img).build();

                AnnotateImageResponse r = null;
                for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
                    if (attempt > 0) {
                        try { Thread.sleep(RETRY_DELAY_MS * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    }
                    BatchAnnotateImagesResponse resp = client.batchAnnotateImages(List.of(req));
                    r = resp.getResponses(0);
                    if (!r.hasError()) break;
                    if (attempt == MAX_RETRIES) {
                        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Vision 오류: " + r.getError().getMessage());
                    }
                }

                String text = r.getFullTextAnnotation() == null ? "" : r.getFullTextAnnotation().getText();
                if (text.isBlank())
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OCR 결과 없음");

                String meta = "pages=" + (r.getFullTextAnnotation() == null ? 0 : r.getFullTextAnnotation().getPagesCount());
                return new OcrResult(text, meta);
            }
        } catch (com.google.api.gax.rpc.UnauthenticatedException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Vision 인증 실패: 키/프로젝트 확인");
        } catch (com.google.api.gax.rpc.PermissionDeniedException e) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Vision 권한 부족(roles/vision.aiUser 필요)");
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Vision 클라이언트 실패: " + e.getMessage());
        }
    }

    private InputStream openCredentialsStream() throws IOException {
        String location = credentialsLocation == null ? "" : credentialsLocation.trim();
        if (location.isBlank()) {
            throw new IOException("GCP_VISION_CREDENTIALS가 설정되지 않았습니다.");
        }

        if (location.startsWith("classpath:")) {
            String classpathLocation = location.substring("classpath:".length());
            ClassPathResource resource = new ClassPathResource(classpathLocation);
            if (!resource.exists()) {
                throw new IOException("classpath OCR credentials not found: " + classpathLocation);
            }
            return resource.getInputStream();
        }

        Path credentialsPath = resolveFilePath(location);
        if (Files.isDirectory(credentialsPath)) {
            credentialsPath = firstJsonFile(credentialsPath);
        }
        if (!Files.exists(credentialsPath)) {
            throw new IOException("OCR credentials file not found: " + credentialsPath);
        }
        if (!Files.isRegularFile(credentialsPath)) {
            throw new IOException("OCR credentials path is not a file: " + credentialsPath);
        }
        return Files.newInputStream(credentialsPath);
    }

    private Path resolveFilePath(String location) {
        String pathText = location.startsWith("file:")
                ? location.substring("file:".length())
                : location;
        if (pathText.startsWith("/") && pathText.length() > 3 && pathText.charAt(2) == ':') {
            pathText = pathText.substring(1);
        }
        return Paths.get(pathText).toAbsolutePath().normalize();
    }

    private Path firstJsonFile(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().toLowerCase().endsWith(".json"))
                    .sorted()
                    .findFirst()
                    .orElseThrow(() -> new IOException("OCR credentials JSON not found in directory: " + directory));
        }
    }
}
