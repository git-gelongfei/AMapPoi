package com.civitasv.spider.util;

import com.civitasv.spider.MainApplication;
import com.civitasv.spider.model.GitHubRelease;
import com.civitasv.spider.webdao.GitHubDao;
import com.civitasv.spider.webdao.impl.GitHubDaoImpl;
import javafx.application.Platform;
import javafx.scene.control.Alert;

import java.awt.*;
import java.io.File;
import java.net.URI;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;

public class GitHubUtils {
    private final static GitHubDao gitHubDao = new GitHubDaoImpl();
    private final static String username = "Civitasv";
    private final static String repoName = "AMapPoi";

    public static GitHubRelease getGitHubReleaseLatest() {
        return gitHubDao.getReleaseInfo(username, repoName);
    }

    public static void tryGetLatestRelease(boolean showWhenNoNewVersion) {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.submit(() -> {
            try {
                GitHubRelease gitHubReleaseLatest = GitHubUtils.getGitHubReleaseLatest();
                String versionFilePath = Paths.get("vendor", "version").toString();

                String currentVersion = FileUtil.readFile(versionFilePath);
                if (currentVersion.equals(gitHubReleaseLatest.tagName())) {
                    if (showWhenNoNewVersion) {
                        Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.INFORMATION,
                                "检查新版本",
                                "检查新版本",
                                "当前软件已经为最新版本，无需更新"));
                    }
                    return;
                }
                final FutureTask<Boolean> query = new FutureTask<>(() ->
                        MessageUtil.alertConfirmationDialog("新版本更新提示",
                                "新版本更新提示",
                                "POIKit已发布新版本，版本相关信息如下：\n" +
                                        "版本号：" + gitHubReleaseLatest.tagName() + "\n" +
                                        "标题：" + gitHubReleaseLatest.name() + "\n" +
                                        "描述：\n" + gitHubReleaseLatest.body().replace("*", "").replace("=", "") + "\n",
                                "前往下载新版本",
                                "关闭"));
                Platform.runLater(query);
                // 阻塞本线程
                if (query.get()) {
                    Desktop.getDesktop().browse(new URI(gitHubReleaseLatest.htmlUrl()));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }
}
