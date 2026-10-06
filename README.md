# Google Drive Backup Client

A small Java 17 coursework client that backs up new or changed JPG images from one explicitly selected local directory to a Google Drive folder. It reads a JSON manifest stored in that Drive folder, compares SHA-256 hashes locally, uploads only new/changed images, and updates the manifest after each successful upload.

## Safety and scope

- The program scans only direct children of the directory passed with `--source`; it does not recurse.
- It processes only `.jpg` and `.jpeg` files as `image/jpeg`.
- Preview mode is the default. Files are uploaded only when `--upload` is present.
- The default cap is 20 candidate files. Use `--max-files` to set another explicit cap.
- Use a small folder containing fake/test images first. Never point it at a drive root or an entire personal directory.
- OAuth credentials and cached tokens are local and ignored by Git.

## Requirements

- JDK 17+
- Maven 3.8+
- A Google account with Google Drive enabled
- A Google Cloud project with the Google Drive API enabled

## Google authorization setup

1. In Google Cloud Console, create/select a project and enable **Google Drive API**.
2. Configure the OAuth consent screen. For a testing app, add your own Google account as a test user.
3. Create an OAuth client ID of type **Desktop app** and download its JSON file.
4. Save the downloaded file as `credentials.json` in this project directory. Do not commit or share this file.
5. Create a dedicated empty folder in Google Drive for this coursework and copy its folder ID from the URL. The app creates `backup-manifest.json` in that folder on the first successful upload. On later runs it discovers that manifest by name. You may instead pass an existing manifest ID with `--manifest-id`.

The first run opens a browser for Google sign-in and consent. The resulting token is cached under `token-store/` and is excluded from Git.

## Build and preview

From this project directory:

```powershell
mvn compile
mvn exec:java "-Dexec.args=--source C:/path/to/fake-jpgs --folder-id YOUR_DRIVE_FOLDER_ID"
```

The preview reads Drive's manifest and prints the candidate count and total bytes. It performs no upload. The first authorized use requires browser consent and the app's `credentials.json`.

## Perform a backup

After confirming the source is the intended small test directory and the preview is correct:

```powershell
mvn exec:java "-Dexec.args=--source C:/path/to/fake-jpgs --folder-id YOUR_DRIVE_FOLDER_ID --upload"
```

Optional arguments:

- `--manifest-id ID`: use a known manifest file ID instead of discovering it by name in the destination folder.
- `--max-files N`: change the default per-run safety cap of 20 candidate JPGs.

The source directory can also be given as a quoted Windows path, for example `--source "C:/Users/Student/Pictures/test"`. IDs can be supplied via `BACKUP_FOLDER_ID` and `BACKUP_MANIFEST_ID` environment variables instead of command-line options.

## Backup behavior

The manifest maps each local filename to its Drive file ID, SHA-256 digest, and byte size. An unchanged filename and digest is skipped. A changed image is uploaded as a new Drive file and its manifest entry is replaced with the new file ID and digest; the older Drive copy remains available. The manifest is saved after every image upload, so a later failure does not lose the record of already completed uploads.

This project implements the required Drive backup flow. The optional Google Calendar marker event is not included.

## References

- [Google Drive API Java quickstart](https://developers.google.com/drive/api/quickstart/java)
- [Upload file data](https://developers.google.com/workspace/drive/api/guides/manage-uploads)
- [Create and populate folders](https://developers.google.com/workspace/drive/api/guides/folder)

## 中文说明

这是一个 Java 17 课程项目客户端：只扫描命令行指定目录的第一层，只处理 JPG/JPEG。程序从 Google Drive 文件夹读取 JSON 清单，在本地比较文件名和 SHA-256；默认只预览，只有添加 `--upload` 才会实际上传。默认每次最多处理 20 个候选文件。

请先在 Google Cloud 启用 Drive API，创建桌面应用 OAuth 客户端并把下载的 JSON 放到项目目录，命名为 `credentials.json`。在 Drive 中新建专用测试文件夹。首次成功上传时程序会在目标文件夹创建 `backup-manifest.json`，之后自动发现并读取。不要把 OAuth 文件或 `token-store/` 提交到 GitHub。建议先用少量虚构 JPG 文件运行预览，确认目录和文件数后再显式添加 `--upload`。

本项目没有实现可选的 Google Calendar 备份标记功能。

