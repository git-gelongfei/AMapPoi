package com.civitasv.spider;

import com.civitasv.spider.controller.POIController;
import com.civitasv.spider.controller.helper.ControllerFactory;
import com.civitasv.spider.util.ControllerUtils;
import com.civitasv.spider.util.GitHubUtils;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.Paths;
import java.util.Objects;

public class MainApplication extends Application {
    @Override
    public void init() throws Exception {
        super.init();
    }

    @Override
    public void start(Stage stage) throws IOException {
        ControllerFactory controllerFactory = ControllerUtils.getControllerFactory();
        POIController controller = controllerFactory.createController(POIController.class);
        controller.show();
        GitHubUtils.tryGetLatestRelease(false);
    }

    @Override
    public void stop() throws Exception {
        super.stop();
    }

    public static void main(String[] args) {
        try (RandomAccessFile randomAccessFile =
                     new RandomAccessFile(
                             Paths.get("vendor", ".lock").toFile(),
                             "rw")) {
            FileChannel channel = randomAccessFile.getChannel();
            if (channel.tryLock() == null)
                System.out.println("只能同时运行一个实例");
            else
                launch();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}