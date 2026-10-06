package edu.example.drivebackup;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.FileContent;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.drive.Drive;
import com.google.api.services.drive.DriveScopes;
import com.google.api.services.drive.model.File;
import com.google.api.services.drive.model.FileList;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Backs up direct-child JPG files from one explicitly selected local directory. */
public final class DriveBackupClient {
    private static final String APPLICATION_NAME = "Coursework Google Drive JPG Backup";
    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_JSON = "application/json";
    private static final String MANIFEST_NAME = "backup-manifest.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MANIFEST_TYPE = new TypeToken<LinkedHashMap<String, ManifestEntry>>() {}.getType();

    private DriveBackupClient() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            List<Path> images = collectJpgFiles(options.source(), options.maxFiles());
            System.out.printf("Selected directory: %s%nJPG files found: %d%n", options.source(), images.size());

            NetHttpTransport transport = GoogleNetHttpTransport.newTrustedTransport();
            Drive drive = createDriveService(transport);
            ManifestLocation manifest = loadManifest(drive, options);
            Map<String, ManifestEntry> entries = manifest.entries();

            List<Path> newFiles = new ArrayList<>();
            for (Path path : images) {
                String name = path.getFileName().toString();
                String hash = sha256(path);
                ManifestEntry recorded = entries.get(name);
                if (recorded == null || !recorded.sha256().equals(hash)) {
                    newFiles.add(path);
                }
            }

            long pendingBytes = 0;
            for (Path path : newFiles) pendingBytes += Files.size(path);
            System.out.printf("New or changed JPG files: %d%nBytes to upload: %d%n",
                    newFiles.size(), pendingBytes);
            if (newFiles.isEmpty()) {
                System.out.println("Nothing to back up. The Drive manifest already contains these files.");
                return;
            }
            if (!options.upload()) {
                System.out.println("Preview only; no files were uploaded. Add --upload to perform the backup.");
                return;
            }

            long uploadedBytes = 0;
            int uploadedCount = 0;
            for (Path path : newFiles) {
                String name = path.getFileName().toString();
                String hash = sha256(path);
                long size = Files.size(path);
                File metadata = new File().setName(name).setParents(List.of(options.folderId()));
                File uploaded = drive.files().create(metadata, new FileContent(MIME_JPEG, path.toFile()))
                        .setFields("id,name,size,mimeType,webViewLink").execute();

                entries.put(name, new ManifestEntry(uploaded.getId(), hash, size));
                saveManifest(drive, manifest, entries, options.folderId());
                uploadedCount++;
                uploadedBytes += size;
                System.out.printf("Uploaded %s (%d bytes), Drive id=%s%n", name, size, uploaded.getId());
            }
            System.out.printf("Backup complete: %d file(s), %d byte(s). Manifest: %s%n",
                    uploadedCount, uploadedBytes, manifest.fileId());
        } catch (Exception e) {
            System.err.println("Backup failed: " + e.getMessage());
            System.exit(1);
        }
    }

    private static List<Path> collectJpgFiles(Path source, int maxFiles) throws IOException {
        if (!Files.isDirectory(source)) throw new IllegalArgumentException("Source must be an existing directory.");
        try (var paths = Files.list(source)) {
            List<Path> files = paths.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                        return name.endsWith(".jpg") || name.endsWith(".jpeg");
                    })
                    .sorted()
                    .toList();
            if (files.size() > maxFiles) {
                throw new IllegalArgumentException("Found " + files.size() + " JPG files, exceeding --max-files "
                        + maxFiles + ". Narrow the directory or raise the limit explicitly.");
            }
            return files;
        }
    }

    private static Drive createDriveService(NetHttpTransport transport) throws IOException {
        Path credentialsPath = Path.of(System.getenv().getOrDefault("GOOGLE_OAUTH_CREDENTIALS", "credentials.json"));
        if (!Files.isRegularFile(credentialsPath)) {
            throw new IllegalArgumentException("OAuth client file not found: " + credentialsPath.toAbsolutePath()
                    + ". Download a Desktop app OAuth JSON from Google Cloud and save it as credentials.json.");
        }
        GoogleClientSecrets secrets;
        try (InputStream input = Files.newInputStream(credentialsPath);
             InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            secrets = GoogleClientSecrets.load(GsonFactory.getDefaultInstance(), reader);
        }
        var flow = new GoogleAuthorizationCodeFlow.Builder(transport, GsonFactory.getDefaultInstance(), secrets,
                List.of(DriveScopes.DRIVE))
                .setDataStoreFactory(new FileDataStoreFactory(Path.of("token-store").toFile()))
                .setAccessType("offline")
                .build();
        Credential credential = new AuthorizationCodeInstalledApp(flow,
                new LocalServerReceiver.Builder().setPort(8888).build()).authorize("local-user");
        return new Drive.Builder(transport, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName(APPLICATION_NAME).build();
    }

    private static ManifestLocation loadManifest(Drive drive, Options options) throws IOException {
        String manifestId = options.manifestId();
        if (manifestId == null || manifestId.isBlank()) {
            String query = "name = '" + MANIFEST_NAME + "' and '" + options.folderId()
                    + "' in parents and trashed = false";
            FileList found = drive.files().list().setQ(query).setPageSize(1)
                    .setFields("files(id,name)").execute();
            if (!found.getFiles().isEmpty()) manifestId = found.getFiles().get(0).getId();
            else return new ManifestLocation(null, new LinkedHashMap<>());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        drive.files().get(manifestId).executeMediaAndDownloadTo(output);
        Map<String, ManifestEntry> entries = GSON.fromJson(output.toString(StandardCharsets.UTF_8), MANIFEST_TYPE);
        return new ManifestLocation(manifestId, entries == null ? new LinkedHashMap<>() : entries);
    }

    private static void saveManifest(Drive drive, ManifestLocation location,
                                     Map<String, ManifestEntry> entries, String folderId) throws IOException {
        byte[] json = GSON.toJson(entries, MANIFEST_TYPE).getBytes(StandardCharsets.UTF_8);
        ByteArrayContent media = new ByteArrayContent(MIME_JSON, json);
        if (location.fileId() == null) {
            File metadata = new File().setName(MANIFEST_NAME).setParents(List.of(folderId));
            File created = drive.files().create(metadata, media).setFields("id,name").execute();
            location.setFileId(created.getId());
        } else {
            drive.files().update(location.fileId(), new File(), media).setFields("id,name").execute();
        }
    }

    private static String sha256(Path path) throws IOException, GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private record ManifestEntry(String fileId, String sha256, long size) {}

    private static final class ManifestLocation {
        private String fileId;
        private final Map<String, ManifestEntry> entries;

        private ManifestLocation(String fileId, Map<String, ManifestEntry> entries) {
            this.fileId = fileId;
            this.entries = entries;
        }
        String fileId() { return fileId; }
        void setFileId(String fileId) { this.fileId = fileId; }
        Map<String, ManifestEntry> entries() { return entries; }
    }

    private record Options(Path source, String folderId, String manifestId, int maxFiles, boolean upload) {
        static Options parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean upload = false;
            for (int i = 0; i < args.length; i++) {
                if (args[i].equals("--upload")) {
                    upload = true;
                } else if (args[i].startsWith("--") && i + 1 < args.length) {
                    values.put(args[i], args[++i]);
                } else {
                    throw new IllegalArgumentException("Unknown or incomplete argument: " + args[i]);
                }
            }
            String sourceValue = values.get("--source");
            if (sourceValue == null) {
                throw new IllegalArgumentException("Usage: --source <small-directory> --folder-id <Drive-folder-id> "
                        + "[--manifest-id <Drive-file-id>] [--max-files <n>] [--upload]");
            }
            String folderId = values.getOrDefault("--folder-id", System.getenv("BACKUP_FOLDER_ID"));
            if (folderId == null || folderId.isBlank()) {
                throw new IllegalArgumentException("Provide --folder-id or BACKUP_FOLDER_ID.");
            }
            int max = Integer.parseInt(values.getOrDefault("--max-files", "20"));
            if (max < 1) throw new IllegalArgumentException("--max-files must be at least 1.");
            return new Options(Path.of(sourceValue).toAbsolutePath().normalize(), folderId,
                    values.getOrDefault("--manifest-id", System.getenv("BACKUP_MANIFEST_ID")), max, upload);
        }
    }
}

