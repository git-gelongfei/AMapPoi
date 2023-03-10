package com.civitasv.spider.viewmodel;

import com.civitasv.spider.controller.POIController;
import com.civitasv.spider.helper.Enum.*;
import com.civitasv.spider.helper.exception.UnRetryAgainException;
import com.civitasv.spider.helper.exception.ReTryAgainException;
import com.civitasv.spider.model.Feature;
import com.civitasv.spider.model.bo.Job;
import com.civitasv.spider.model.bo.POI;
import com.civitasv.spider.model.bo.Task;
import com.civitasv.spider.model.po.JobPo;
import com.civitasv.spider.model.po.TaskPo;
import com.civitasv.spider.service.JobService;
import com.civitasv.spider.service.PoiService;
import com.civitasv.spider.service.TaskService;
import com.civitasv.spider.service.serviceImpl.JobServiceImpl;
import com.civitasv.spider.service.serviceImpl.PoiServiceImpl;
import com.civitasv.spider.service.serviceImpl.TaskServiceImpl;
import com.civitasv.spider.util.*;
import com.civitasv.spider.webdao.AMapDao;
import com.civitasv.spider.webdao.impl.AMapDaoImpl;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.opencsv.CSVWriter;
import javafx.application.Platform;
import javafx.scene.control.*;
import lombok.Builder;
import org.geotools.data.DataUtilities;
import org.geotools.feature.SchemaException;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.locationtech.jts.geom.*;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class POIViewModel {
    private final ViewHolder viewHolder;
    private final ConfigHolder configHolder;

    private final AMapDao mapDao;
    private final TaskService taskService;
    private final JobService jobService;
    private final PoiService poiService;

    private ExecutorService worker, executorService;
    private ExecutorCompletionService<Job> completionService;

    public void outputFields(List<POI.OutputFields> outputFields) {
        this.viewHolder.outputFields = outputFields;
    }

    public POIViewModel(POIController controller) {
        this.viewHolder = ViewHolder.builder()
                .threadNum(controller.threadNum)
                .keywords(controller.keywords)
                .keys(controller.keys)
                .types(controller.types)
                .adCode(controller.adCode)
                .rectangle(controller.rectangle)
                .threshold(controller.threshold)
                .format(controller.format)
                .outputDirectory(controller.outputDirectory)
                .messageDetail(controller.messageDetail)
                .userFile(controller.userFile)
                .tabs(controller.tabs)
                .directoryBtn(controller.directoryBtn)
                .execute(controller.execute)
                .poiType(controller.poiType)
                .userType(controller.userType)
                .rectangleCoordinateType(controller.rectangleCoordinateType)
                .userFileCoordinateType(controller.userFileCoordinateType)
                .wechat(controller.wechat)
                .joinQQ(controller.joinQQ)
                .poiCateBig(controller.poiCateBig)
                .poiCateMid(controller.poiCateMid)
                .poiCateSub(controller.poiCateSub)
                .poiCateAddBtn(controller.poiCateAddBtn)
                .openQPSBtn(controller.openQPSBtn)
                .chooseOutputFieldsBtn(controller.chooseOutputFieldsBtn)
                .build();
        this.viewHolder.initOutputFields();
        this.configHolder = new ConfigHolder();
        this.mapDao = new AMapDaoImpl();
        this.taskService = new TaskServiceImpl();
        this.jobService = new JobServiceImpl();
        this.poiService = new PoiServiceImpl();
    }

    @Builder
    private static class ViewHolder {
        public TextField threadNum; // ????????????
        public TextField keywords; // ?????????
        public TextArea keys; // ?????? API Key
        public TextField types; // ??????
        public TextField adCode; // ?????????????????????
        public TextField rectangle; // ???????????????#???????????????
        public TextField threshold; // ??????
        public ChoiceBox<OutputType> format; // ????????????
        public TextField outputDirectory; // ???????????????
        public Button openQPSBtn; // ?????? QPS
        public Button chooseOutputFieldsBtn; // ??????????????????
        public List<POI.OutputFields> outputFields; // ????????????
        public TextArea messageDetail; // ????????????
        public TextField userFile; // ?????????????????????
        public TabPane tabs; // tab ???
        public Button directoryBtn; // ?????????????????????
        public Button execute; // ??????
        public Button poiType; // ???????????? poi ??????
        public ChoiceBox<UserType> userType; // ????????????
        public ChoiceBox<CoordinateType> rectangleCoordinateType; // ??????????????????
        public ChoiceBox<CoordinateType> userFileCoordinateType; // ?????????????????????????????????
        public MenuItem wechat; // ??????
        public MenuItem joinQQ; // QQ???

        public ChoiceBox<String> poiCateBig; // POI??????
        public ChoiceBox<String> poiCateMid; // POI??????
        public ChoiceBox<String> poiCateSub; // POI??????
        public Button poiCateAddBtn; // poi??????

        void initOutputFields() {
            this.outputFields = Arrays.stream(POI.OutputFields.values())
                    .filter(POI.OutputFields::checked)
                    .collect(Collectors.toList());
        }
    }

    private static class ConfigHolder {
        public static final int SIZE = 20;
        public Queue<String> aMapKeys;
        public Integer threadNum;
        public Integer threshold;
        private Integer qps;
        public Integer perExecuteTime;
        public String keywords;
        public String types;
        public String tab;
        public Double[] boundary;
        public String extension = "base";
        public boolean haveSavedUnfinishedJobs = false;
        public boolean hasStart = false;
        public int waitFactorForQps = 0;
        public final Set<TryAgainErrorCode> errorCodeHashSet = Arrays.stream(new Integer[]{10019, 10020, 10021, 10022, 10014, 10015}).map(TryAgainErrorCode::getError).collect(Collectors.toSet());
    }

    private void otherParams() {
        configHolder.haveSavedUnfinishedJobs = false;
        configHolder.hasStart = false;
        configHolder.waitFactorForQps = 0;
    }

    private boolean check() {
        if (viewHolder.keys.getText().isEmpty()) {
            // keys??????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????key", null, "??????key??????????????????"));
            return false;
        }
        if (viewHolder.userType.getSelectionModel().getSelectedIndex() == -1) {
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "????????????", null, "????????????????????????"));
            return false;
        }
        if (viewHolder.keywords.getText().isEmpty() && viewHolder.types.getText().isEmpty()) {
            // ???????????????????????????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "????????????", null, "POI????????????POI?????????????????????????????????"));
            return false;
        }
        if (viewHolder.threshold.getText().isEmpty()) {
            // ????????????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????", null, "?????????????????????"));
            return false;
        }
        if (viewHolder.threadNum.getText().isEmpty()) {
            // ??????????????????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "????????????", null, "???????????????????????????"));
            return false;
        }
        if (viewHolder.format.getSelectionModel().getSelectedIndex() == -1) {
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "????????????", null, "????????????????????????"));
            return false;
        }
        if (viewHolder.outputDirectory.getText().isEmpty()) {
            // ?????????????????????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "???????????????", null, "??????????????????????????????"));
            return false;
        }
        return true;
    }

    private boolean aMapKeys() {
        appendMessage("????????????key???");
        Queue<String> aMapKeys = parseKeyText(viewHolder.keys.getText());
        if (aMapKeys == null) {
            // key????????????
            Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????key", null, "?????????key????????????"));
            return false;
        }
        configHolder.aMapKeys = aMapKeys;
        appendMessage("??????key????????????");
        return true;
    }

    private boolean threadNum() {
        appendMessage("?????????????????????");
        Integer threadNum = ParseUtil.parseStr2Int(viewHolder.threadNum.getText());
        if (threadNum == null) {
            appendMessage("?????????????????????????????????????????????");
            analysis(false);
            return false;
        }
        appendMessage("????????????????????????");
        configHolder.threadNum = threadNum;
        return true;
    }

    private boolean threshold() {
        appendMessage("???????????????");
        Integer threshold = ParseUtil.parseStr2Int(viewHolder.threshold.getText());
        if (threshold == null) {
            appendMessage("?????????????????????????????????");
            analysis(false);
            return false;
        }
        appendMessage("??????????????????");
        configHolder.threshold = threshold;
        return true;
    }

    private boolean keywords() {
        appendMessage("??????POI????????????");
        StringBuilder keywords = new StringBuilder();
        String[] keywordArr = viewHolder.keywords.getText().split(",");
        for (int i = 0; i < keywordArr.length; i++) {
            keywords.append(keywordArr[i]);
            if (i != keywordArr.length - 1)
                keywords.append("|");
        }
        appendMessage("POI?????????????????????");
        configHolder.keywords = keywords.toString();
        return true;
    }

    private boolean types() {
        appendMessage("??????POI?????????");
        StringBuilder types = new StringBuilder();
        String[] typeArr = viewHolder.types.getText().split(",");
        for (int i = 0; i < typeArr.length; i++) {
            types.append(typeArr[i]);
            if (i != typeArr.length - 1)
                types.append("|");
        }
        appendMessage("POI??????????????????");
        configHolder.types = types.toString();
        return true;
    }

    private boolean qps() {
        // ?????????????????????
        int qps = 0;
        appendMessage("??????" + viewHolder.userType.getValue());
        switch (viewHolder.userType.getValue()) {
            case IndividualDevelopers:
                qps = 10;
                break;
            case IndividualCertifiedDeveloper:
                qps = 30;
                break;
            case EnterpriseDeveloper:
                qps = 100;
                break;
        }
        configHolder.qps = qps;
        return true;
    }

    private boolean tab() {
        String tab = viewHolder.tabs.getSelectionModel().getSelectedItem().getText();
        switch (tab) {
            case "?????????":
                if (viewHolder.adCode.getText().isEmpty()) {
                    // ???????????????
                    Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "???????????????", null, "???????????????????????????"));
                    return false;
                }
                break;
            case "??????":
                if (viewHolder.rectangle.getText().isEmpty()) {
                    // ??????????????????
                    Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????", null, "????????????????????????"));
                    return false;
                }
                // ??????????????????
                if (!parseRect(viewHolder.rectangle.getText())) {
                    Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????", null, "??????????????????????????????\n ???????????????114.12,30.53#115.28,29.59"));
                    return false;
                }
                break;
            case "?????????":
                if (viewHolder.userFile.getText().isEmpty()) {
                    // ???????????????
                    Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "?????????", null, "?????????geojson???????????????"));
                    return false;
                }
                break;
        }
        configHolder.tab = tab;
        return true;
    }

    private void extension() {
        for (POI.OutputFields item : viewHolder.outputFields) {
            if (item.inExtension()) {
                configHolder.extension = "all";
                return;
            }
        }
        configHolder.extension = "base";
    }

    private void alterThreadNum() {
        if (configHolder.threadNum > configHolder.qps * configHolder.aMapKeys.size()) {
            int maxThreadNum = getMaxThreadNum(configHolder.qps, configHolder.aMapKeys.size());
            appendMessage(viewHolder.userType.getValue() + "?????????????????????" + maxThreadNum);
            configHolder.threadNum = maxThreadNum;
            appendMessage("?????????????????????" + maxThreadNum);
        }
    }

    public int getMaxThreadNum(int qps, int keyNum) {
        return qps * keyNum;
    }

    /**
     * ??????????????????key ????????? QPS ????????????????????????
     * <p>
     * threadNum: ????????????
     * <p>
     * qps:       ?????? qps
     * <p>
     * keysNum:   key ??????
     * <p>
     * ?????????????????? ms
     */
    private void perExecuteTime() {
        int threadNum = configHolder.threadNum, qps = configHolder.qps, keysNum = configHolder.aMapKeys.size();
        configHolder.perExecuteTime = getPerExecuteTime(keysNum, threadNum, qps, 1);
    }

    private int getPerExecuteTime(int keysNum, int threadNum, int qps, double waitFactor) {
        return (int) ((1000 * (threadNum * 1.0 / (qps * keysNum))) * waitFactor);
    }

    public static Double[] getBoundaryFromGeometry(Geometry geometry) {
        Envelope envelopeInternal = geometry.getEnvelopeInternal();

        double left = envelopeInternal.getMinX(), bottom = envelopeInternal.getMinY(),
                right = envelopeInternal.getMaxX(), top = envelopeInternal.getMaxY();
        return new Double[]{left, bottom, right, top};
    }

    public void execute(Task task) {
        clearMessage();
        if (!check()) return;
        if (!aMapKeys()) return;
        if (!threadNum()) return;
        if (!threshold()) return;
        if (!keywords()) return;
        if (!types()) return;
        if (!qps()) return;
        if (!tab()) return;
        extension();
        alterThreadNum();
        perExecuteTime();
        otherParams();
        analysis(true);

        worker = Executors.newSingleThreadExecutor();
        if (task == null) {
            String boundaryConfig = "";
            switch (configHolder.tab) {
                case "?????????":
                    if (!configHolder.hasStart) return;
                    String adCode = viewHolder.adCode.getText();
                    appendMessage("??????????????? " + adCode + " ???????????????");
                    // ??????????????????????????????
                    Map<String, Object> data = DataVUtil.getBoundaryAndAdNameByAdCodeFromDataV(adCode);
                    if (data == null) {
                        if (!configHolder.hasStart) return;
                        Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "???????????????", null, "????????????????????????????????????????????????????????????????????????"));
                        analysis(false);
                        return;
                    }
                    String adName = (String) data.get("adName");
                    Geometry geometry = (Geometry) data.get("gcj02Boundary");
                    configHolder.boundary = getBoundaryFromGeometry(geometry);
                    if (!configHolder.hasStart) return;
                    appendMessage("????????????????????? " + adCode + ":" + adName + " ????????????");
                    boundaryConfig = configHolder.tab + "#" + adCode + "," + adName;
                    break;
                case "??????":
                    // ??????????????????
                    if (!configHolder.hasStart) return;
                    String rectangle = viewHolder.rectangle.getText();
                    CoordinateType type = viewHolder.rectangleCoordinateType.getValue();
                    boundaryConfig = configHolder.tab + "#" + rectangle + "," + type;
                    appendMessage("?????????????????????");
                    Double[] rectangleBoundary = BoundaryUtil.getBoundaryByRectangle(rectangle, type);
                    if (rectangleBoundary == null) {
                        if (!configHolder.hasStart) return;
                        Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "??????", null, "???????????????????????????????????????????????????"));
                        analysis(false);
                        return;
                    }
                    configHolder.boundary = rectangleBoundary;
                    if (!configHolder.hasStart) return;
                    appendMessage("????????????????????????");
                    break;
                case "?????????":
                    if (!configHolder.hasStart) return;
                    appendMessage("????????????geojson?????????");
                    Geometry boundary;
                    try {
                        boundary = BoundaryUtil.getBoundaryByUserFile(viewHolder.userFile.getText(), viewHolder.userFileCoordinateType.getValue());
                    } catch (IOException e) {
                        e.printStackTrace();
                        if (!configHolder.hasStart) return;
                        Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "?????????", null, "geojson?????????????????????" + e.getMessage()));
                        analysis(false);
                        return;
                    }
                    configHolder.boundary = getBoundaryFromGeometry(boundary);

                    boundaryConfig = configHolder.tab + "#" + viewHolder.userFile.getText() + ","
                            + viewHolder.userFileCoordinateType.getValue().description();
                    if (!configHolder.hasStart) return;
                    appendMessage("????????????????????????");
                    break;
            }
            try {
                BoundaryType boundaryType = BoundaryType.getBoundaryType(boundaryConfig.split("#")[0]);
                task = Task.builder()
                        .aMapKeys(configHolder.aMapKeys)
                        .types(configHolder.types)
                        .keywords(configHolder.keywords)
                        .threadNum(configHolder.threadNum)
                        .threshold(configHolder.threshold)
                        .outputDirectory(viewHolder.outputDirectory.getText())
                        .outputType(viewHolder.format.getValue())
                        .userType(viewHolder.userType.getValue())
                        .requestExpectedTimes(0)
                        .requestActualTimes(0)
                        .poiActualCount(0)
                        .poiExpectedCount(0)
                        .totalExecutedTimes(0)
                        .taskStatus(TaskStatus.UnStarted)
                        .boundaryConfig(boundaryConfig)
                        .boundary(configHolder.boundary)
                        .boundaryType(boundaryType)
                        .jobs(new ArrayList<>())
                        .filter(TaskUtil.generateFilter(boundaryConfig, boundaryType))
                        .build();
                TaskPo taskPo = task.toTaskPo();
                taskService.save(taskPo);
                // ?????? id
                task = taskPo.toTask();
            } catch (IOException e) {
                e.printStackTrace();
                Platform.runLater(() -> MessageUtil.alert(Alert.AlertType.ERROR, "?????????", null, "task???????????????" + e.getMessage()));
                return;
            }
        }

        Task finalTask = task;
        worker.execute(() -> {
            executorService = Executors.newFixedThreadPool(finalTask.threadNum());
            completionService = new ExecutorCompletionService<>(executorService);
            executeTask(finalTask);
            analysis(false);
        });
    }


    public void cancel() {
        analysis(false);
    }

    private void analysis(boolean start) {
        if (!configHolder.hasStart && !start) {
            return;
        }
        if (configHolder.hasStart && start) {
            return;
        }
        configHolder.hasStart = start;
        setDisable(start);
        appendMessage(start ? "??????POI?????????????????????" : "??????POI??????");
        if (!start && executorService != null)
            executorService.shutdownNow();
        if (!start && worker != null)
            worker.shutdownNow();
    }

    private void setDisable(boolean isAnalysis) {
        Platform.runLater(() -> {
            viewHolder.execute.setDisable(isAnalysis);
            viewHolder.threadNum.setDisable(isAnalysis);
            viewHolder.keys.setDisable(isAnalysis);
            viewHolder.keywords.setDisable(isAnalysis);
            viewHolder.types.setDisable(isAnalysis);
            viewHolder.tabs.setDisable(isAnalysis);
            viewHolder.threshold.setDisable(isAnalysis);
            viewHolder.format.setDisable(isAnalysis);
            viewHolder.outputDirectory.setDisable(isAnalysis);
            viewHolder.directoryBtn.setDisable(isAnalysis);
            viewHolder.poiType.setDisable(isAnalysis);
            viewHolder.userType.setDisable(isAnalysis);
            viewHolder.poiCateBig.setDisable(isAnalysis);
            viewHolder.poiCateMid.setDisable(isAnalysis);
            viewHolder.poiCateSub.setDisable(isAnalysis);
            viewHolder.poiCateAddBtn.setDisable(isAnalysis);
            viewHolder.chooseOutputFieldsBtn.setDisable(isAnalysis);
            viewHolder.openQPSBtn.setDisable(isAnalysis);
        });
    }

    private Queue<String> parseKeyText(String keys) {
        List<String> keyList = Arrays.asList(keys.split(","));
        String pattern = "^[A-Za-z0-9]+$";
        for (String key : keyList) {
            boolean isMatch = Pattern.matches(pattern, key);
            if (!isMatch) {
                return null;
            }
        }
        return new ArrayDeque<>(keyList);
    }

    private boolean continueLargeTaskByDialog(int jobSize, int hintCount) {
        if (jobSize < hintCount) {
            return true;
        }
        final FutureTask<Boolean> query = new FutureTask<>(() ->
                MessageUtil.alertConfirmationDialog(
                        "???????????????",
                        "???????????????????????????????????????????????????",
                        "???Task??????????????????" + jobSize + "????????????????????????????????????????????????????????????????????????",
                        "??????",
                        "??????"));

        Platform.runLater(query);
        try {
            // ???????????????
            return query.get();
        } catch (InterruptedException | ExecutionException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * ??????????????????
     *
     * @param task task??????
     */
    private void executeTask(Task task) {
        configHolder.waitFactorForQps = 0;
        if (TaskStatus.UnStarted.equals(task.taskStatus()) || TaskStatus.Preprocessing.equals(task.taskStatus())) {
            // ???????????????
            jobService.clearTable();
            poiService.clearTable();
            task.taskStatus(TaskStatus.Preprocessing);
            taskService.updateById(task.toTaskPo());

            // 1. ????????????????????????????????????
            appendMessage("???????????????????????????");
            List<Job> firstPageJobs;
            try {
                firstPageJobs = getAnalysisGridsReTry(task.boundary(), task, 3);
            } catch (UnRetryAgainException e) {
                e.printStackTrace();
                if (configHolder.hasStart) appendMessage(e.getMessage());
                return;
            }
            task.jobs().addAll(firstPageJobs);
            appendMessage("?????????????????????????????????" + firstPageJobs.size() + "???????????????");

            // 2. ???????????????????????????Job
            List<Job> jobsAfterSecondPage = generateJobsAfterSecondPage(firstPageJobs);
            task.jobs().addAll(jobsAfterSecondPage);

            appendMessage("???????????????????????????" + task.jobs().size() + "??????????????????" + jobsAfterSecondPage.size() + "?????????????????????");
            int requestLeastCount = jobsAfterSecondPage.size();
            if (!continueLargeTaskByDialog(requestLeastCount, 5000)) {
                analysis(false);
                return;
            }
            // ??????Task
            task.taskStatus(TaskStatus.Processing);
            taskService.updateById(task.toTaskPo());
        }

        // 3. ????????????
        if (!configHolder.hasStart) return;
        appendMessage("??????POI?????????" + (!task.keywords().isEmpty() ? "POI????????????" + task.keywords() : "") + (!task.types().isEmpty() ? (" POI?????????" + task.types()) : ""));

        List<POI.Info> pois;
        try {
            pois = getPoiOfJobsWithReTry(task, 3);
        } catch (UnRetryAgainException e) {
            e.printStackTrace();
            if (configHolder.hasStart) appendMessage(e.getMessage());
            return;
        }

//        appendMessage("?????????????????????");

        pois = pois.stream().filter(task.filter()).collect(Collectors.toList());
//        appendMessage("????????????????????????POI???" + pois.size() + "???");
        appendMessage("?????????????????????POI???" + pois.size() + "???");

        // ??????res
        switch (viewHolder.format.getValue()) {
            case CSV:
                writeToCsv(pois, viewHolder.outputFields);
                break;
            case GEOJSON:
                writeToGeoJson(pois, viewHolder.outputFields);
                break;
            case SHAPEFILE:
                writeToShp(pois, viewHolder.outputFields);
        }
        taskService.updateById(task.toTaskPo());

        int allJobSize = jobService.count();
        int unFinishJobSize = jobService.countUnFinished();
        List<POI.Info> finalPois = pois;
        Platform.runLater(() -> MessageUtil.alert(
                Alert.AlertType.INFORMATION,
                "????????????",
                "??????????????????",
                "poi???????????????????????????\n" +
                        "???????????????" + task.taskStatus().description() + "\n" +
                        "????????????" + (allJobSize - unFinishJobSize) + "/" + allJobSize + " \n" +
                        "????????????poi?????????" + finalPois.size() + "\n"
        ));
    }

    /**
     * ???getAnalysisGrids????????????????????????????????????
     *
     * @param beginRect ??????????????????
     * @param task      task??????
     * @param tryTimes  ????????????
     * @return ?????????job??????
     * @throws UnRetryAgainException ????????????????????????????????????NoTryAgainException??????
     */
    private List<Job> getAnalysisGridsReTry(Double[] beginRect, Task task, int tryTimes) throws UnRetryAgainException {
        Job beginJob = new Job(null, task.id(), beginRect, task.types(), task.keywords(), 1, configHolder.SIZE);
        ArrayList<Job> falseJobs = new ArrayList<>();
        List<Job> analysisGrids = getAnalysisGrids(Collections.singletonList(beginJob), task, 0, falseJobs);
        int i = 1;
        while (falseJobs.size() != 0) {
            appendMessage("??????????????????" + i + "???");
            ArrayList<Job> newTryJobs = new ArrayList<>(falseJobs);
            falseJobs.clear();
            analysisGrids.addAll(getAnalysisGrids(newTryJobs, task, analysisGrids.size(), falseJobs));
            if (i == tryTimes) {
                appendMessage("???????????????" + "?????????????????????" + falseJobs.size() + "???Job?????????");
                appendMessage("??????????????????????????????????????????????????????");
                throw new UnRetryAgainException(NoTryAgainErrorCode.STOP_TASK);
            }
            i++;
        }
        int requestTimesForPreProcessing = task.requestActualTimes() - analysisGrids.size();
        appendMessage("?????????????????????????????? " + requestTimesForPreProcessing + " ???");
        task.plusRequestExceptedTimes(requestTimesForPreProcessing);
        return analysisGrids;
    }

    /**
     * ?????????????????????poi??????????????????????????????????????????????????????
     *
     * @param tryJobs ???????????????Job
     * @param task    task??????
     * @return ????????????????????????Job
     */
    private List<Job> getAnalysisGrids(List<Job> tryJobs, Task task, int baseJobCount, ArrayList<Job> falseJobs) throws UnRetryAgainException {
        ExecutorService executorService = Executors.newFixedThreadPool(task.threadNum());
        List<Job> analysisGrid = new ArrayList<>();
        CompletionService<Job> completionService = new ExecutorCompletionService<>(executorService);

        List<Job> nextTryJobs = new ArrayList<>();

        // ??????????????????????????????????????????????????????????????????poi???????????????????????????
        // ?????????????????????????????????????????????????????????????????????????????????????????????????????????
        while (tryJobs.size() != 0) {
            // unTriedJobs???????????????????????????
            List<Job> unTriedJobs = new ArrayList<>(tryJobs);
            for (Job job : tryJobs) {
                if (!configHolder.hasStart) {
                    throw new UnRetryAgainException(NoTryAgainErrorCode.STOP_TASK);
                }
                submitJob(completionService, job);
            }

            // ???????????????????????????500ms??????????????????????????????????????????????????????
            int tryTimes = (int) (20 / 0.5);
            for (int i = 0; i < tryJobs.size(); i++) {
                for (int j = 0; j < tryTimes; j++) {
                    Future<Job> future;
                    try {
                        future = completionService.poll(500, TimeUnit.MILLISECONDS);
                        if (future != null) {
                            task.plusRequestActualTimes(); //??????????????????
                            Job job = future.get();
                            unTriedJobs.remove(job);
                            // ??????????????????????????????????????????????????????????????????????????????
                            if (job.jobStatus() != JobStatus.SUCCESS) {
                                if (job.noTryAgainErrorCode() != null) {
                                    throw new UnRetryAgainException(job.noTryAgainErrorCode());
                                }
                                falseJobs.add(job);
                                break;
                            }
                            // ??????????????????????????????
                            if (job.poi().count() > task.threshold()) {
                                appendMessage("???????????????????????????????????????" + (analysisGrid.size() + baseJobCount) + "?????????");
                                // ????????????
                                Double left = job.bounds()[0], bottom = job.bounds()[1], right = job.bounds()[2], top = job.bounds()[3];
                                double itemWidth = (right - left) / 2;
                                double itemHeight = (top - bottom) / 2;
                                for (int m = 0; m < 2; m++) {
                                    for (int n = 0; n < 2; n++) {
                                        Double[] bounds = {left + m * itemWidth, bottom + n * itemHeight,
                                                left + (m + 1) * itemWidth, bottom + (n + 1) * itemHeight};
                                        nextTryJobs.add(new Job(null, task.id(), bounds, task.types(), task.keywords(), 1, configHolder.SIZE));
                                    }
                                }
                            } else {
                                analysisGrid.add(job);  // new double[]{left, bottom, right, top});
                                statistics(job, task);
                                appendMessage("?????????" + (analysisGrid.size() + baseJobCount) + "?????????");
                            }
                            break;
                        }
                        if ((j + 1) == tryTimes) {
                            throw new TimeoutException();
                        }
                    } catch (TimeoutException e) {
                        e.printStackTrace();
                        falseJobs.addAll(unTriedJobs);
                    } catch (InterruptedException | ExecutionException e) {
                        e.printStackTrace();
                        throw new UnRetryAgainException(NoTryAgainErrorCode.STOP_TASK, e);
                    }
                }
            }
            tryJobs = nextTryJobs;
            nextTryJobs = new ArrayList<>();
        }

        // ????????????????????????
        for (Job job : analysisGrid) {
            POI poi = job.poi();
            task.plusPoiExceptedSum(poi.count());
            task.plusRequestExceptedTimes((int) Math.ceil(poi.count() * 1.0 / job.size()));
            job.poiExpectedCount(Math.min(poi.count(), job.size()));
        }

        // ????????????????????????
        taskService.updateById(task.toTaskPo());
        jobService.saveBatch(BeanUtils.jobs2JobPos(analysisGrid));
        poiService.saveBatch(BeanUtils.jobs2PoiPos(analysisGrid, configHolder.extension.equals("all")));
        return analysisGrid;
    }

    /**
     * ???????????????????????????????????????????????????
     *
     * @param completionService executors
     * @param job               ????????????job
     */
    private void submitJob(CompletionService<Job> completionService, Job job) {
        completionService.submit(() -> {
            try {
                executeJob(job);
                return job;
            } catch (ReTryAgainException e) {
                // ?????????job????????????????????????
                // ?????????????????????????????????
                synchronized (this) {
                    if (configHolder.hasStart) appendMessage(e.getMessage());
                    job.jobStatus(JobStatus.Failed);
                    job.tryAgainErrorCode(e.tryAgainError());
                    return job;
                }
            } catch (UnRetryAgainException e) {
                // ?????????job????????????????????????
                // ?????????????????????????????????
                synchronized (this) {
                    if (configHolder.hasStart) appendMessage(e.getMessage());
                    // ??????????????????
                    analysis(false);
                    job.jobStatus(JobStatus.Failed);
                    job.noTryAgainErrorCode(e.noTryAgainError());
                    return job;
                }
            }
        });
    }

    /**
     * ????????????????????????Job
     *
     * @param analysisGrid ??????job?????????
     * @return ?????????Job
     */
    private List<Job> generateJobsAfterSecondPage(List<Job> analysisGrid) {
        List<Job> jobs = new ArrayList<>();
        for (Job firstPageJob : analysisGrid) {
            int total = firstPageJob.poi().count();
            int size = firstPageJob.size();
            int taskNum = (int) Math.ceil(total * 1.0 / size);
            for (int page = 2; page <= taskNum; page++) {
                Job job = new Job(null, firstPageJob.taskId(), firstPageJob.bounds(), firstPageJob.types(), firstPageJob.keywords(), page, firstPageJob.size());
                job.poiExpectedCount(page == taskNum ? total - size * (taskNum - 1) : size);
                jobs.add(job);
            }
        }
        // ????????????????????????job
        jobService.saveBatch(BeanUtils.jobs2JobPos(jobs));
        return jobs;
    }

    /**
     * ???????????????????????????
     *
     * @param task       task??????
     * @param retryTimes ????????????
     * @return ?????????poi??????
     */
    private List<POI.Info> getPoiOfJobsWithReTry(Task task, int retryTimes) throws UnRetryAgainException {
        List<Job> jobs = Collections.unmodifiableList(BeanUtils.jobPos2Jobs((jobService.listUnFinished())));
        int jobCount = jobService.count();
        int i = 0;
        while (configHolder.hasStart) {
            if (i != 0) {
                appendMessage("??????????????????" + i + "???");
            }
            configHolder.haveSavedUnfinishedJobs = false;
            spiderPoiOfJobs(jobs, task, jobCount);
            if (!configHolder.hasStart) {
                return BeanUtils.poiPo2PoiInfo(poiService.list(), configHolder.extension.equals("all"));
            }
            List<Job> newJobs = Collections.unmodifiableList(BeanUtils.jobPos2Jobs(jobService.listUnFinished()));
            if (newJobs.size() == 0) {
                task.taskStatus(TaskStatus.Success);
                break;
            }
            appendMessage((i == 0 ? "??????????????????" : "???" + i + "???????????????") + "?????????" + jobCount + "???????????????????????????" + (jobCount - newJobs.size()) + "??????????????????" + newJobs.size() + "???");
            if (i == retryTimes) {
                List<JobPo> unFinishedJobs = jobService.listUnFinished();
                appendMessage("???????????????" + "?????????????????????" + unFinishedJobs.size() + "???Job?????????");
                appendMessage("??????????????????????????????????????????????????????");
                task.taskStatus(TaskStatus.Some_Failed);
                break;
            }
            jobs = newJobs;
            i++;
        }
        return BeanUtils.poiPo2PoiInfo(poiService.list(), configHolder.extension.equals("all"));
    }

    /**
     * ?????????????????????????????????
     *
     * @param unFinishedJobs ????????????job
     * @param task           task??????
     */
    private void spiderPoiOfJobs(List<Job> unFinishedJobs, Task task, int allJobsCount) throws UnRetryAgainException {
        int finishedJobsCount = allJobsCount - unFinishedJobs.size();
        // ????????????
        int saveThreshold = 50;
        List<Job> cached = new ArrayList<>();
        ArrayList<Job> unFinishedJob = new ArrayList<>(unFinishedJobs);

        // ????????????job
        for (Job job : unFinishedJobs) {
            submitJob(completionService, job);
        }

        // ????????????
        try {
            int tryTimes = (int) (20 / 0.5);
            for (int i = 0; i < unFinishedJobs.size(); i++) {
                // ??????????????????job
                for (int j = 0; j < tryTimes; j++) {
                    Future<Job> future = completionService.poll(500, TimeUnit.MILLISECONDS);
                    if (future != null) {
                        Job job = future.get();
                        if (job.jobStatus() != JobStatus.SUCCESS) {
                            if (job.noTryAgainErrorCode() != null) {
                                throw new UnRetryAgainException(job.noTryAgainErrorCode());
                            }
                        } else {
                            statistics(job, task);
                            appendMessage("??????????????????" + (finishedJobsCount + i + 1) + "/" + allJobsCount);
                        }
                        cached.add(job);
                        unFinishedJob.remove(job);
                        break;
                    }
                    if ((j + 1) == tryTimes) {
                        throw new TimeoutException();
                    }
                }
                if ((i + 1) % saveThreshold == 0 || (i + 1) == unFinishedJobs.size()) {
                    appendMessage("??????????????????????????????...");
                    taskService.updateById(task.toTaskPo());
                    jobService.updateBatch(BeanUtils.jobs2JobPos(cached));
                    poiService.saveBatch(BeanUtils.jobs2PoiPos(
                            cached.stream()
                                    .filter(job -> job.jobStatus().equals(JobStatus.SUCCESS))
                                    .collect(Collectors.toList()), configHolder.extension.equals("all")));
                    cached.clear();
                }
            }
        } catch (TimeoutException e) {
//            e.printStackTrace();
            saveUnFinishedJob(task, cached, unFinishedJob);
        } catch (UnRetryAgainException | InterruptedException | ExecutionException e) {
//            e.printStackTrace();
            saveUnFinishedJob(task, cached, unFinishedJob);
            throw new UnRetryAgainException(NoTryAgainErrorCode.STOP_TASK, e.getMessage());

        }
    }

    /**
     * ??????????????????Jobs
     *
     * @param task          task??????
     * @param cached        ????????????
     * @param unFinishedJob ????????????Job
     */
    private synchronized void saveUnFinishedJob(Task task, List<Job> cached, ArrayList<Job> unFinishedJob) {
        if (configHolder.haveSavedUnfinishedJobs) {
            return;
        }
        appendMessage("?????????????????????????????????????????????...?????????????????????");
        for (Job unJob : unFinishedJob) {
            unJob.jobStatus(JobStatus.Failed);
            cached.add(unJob);
        }
        taskService.updateById(task.toTaskPo());
        jobService.updateBatch(BeanUtils.jobs2JobPos(cached));
        poiService.saveBatch(BeanUtils.jobs2PoiPos(
                cached.stream()
                        .filter(job -> job.jobStatus().equals(JobStatus.SUCCESS))
                        .collect(Collectors.toList()), configHolder.extension.equals("all"))
        );

        task.taskStatus(TaskStatus.Pause);
        taskService.updateById(task.toTaskPo());
        configHolder.haveSavedUnfinishedJobs = true;
    }

    /**
     * ????????????Job
     *
     * @param job ???????????????job
     * @throws ReTryAgainException ????????????????????????????????????
     */
    private void executeJob(Job job) throws UnRetryAgainException, ReTryAgainException {
        double left = job.bounds()[0], bottom = job.bounds()[1], right = job.bounds()[2], top = job.bounds()[3];
        String polygon = left + "," + top + "|" + right + "," + bottom;
        String key = getAMapKey();
        job.poi(getPoi(key, polygon, job.keywords(), job.types(), job.page(), job.size()));
        job.jobStatus(JobStatus.SUCCESS); // ?????????????????????Success
    }

    /**
     * ????????????Key?????????key???????????????
     *
     * @return ?????????key???
     * @throws UnRetryAgainException ??????????????????key?????????????????????key?????????????????????
     */
    private synchronized String getAMapKey() throws UnRetryAgainException {
        if (configHolder.aMapKeys.isEmpty()) {
            return null;
        }
        String key = configHolder.aMapKeys.poll();
        if (key == null) {
            throw new UnRetryAgainException(NoTryAgainErrorCode.KEY_POOL_RUN_OUT_OF);
        }
        configHolder.aMapKeys.offer(key);
        return key;
    }

    private synchronized boolean removeKey(String key) {
        return configHolder.aMapKeys.remove(key);
    }

    /**
     * ??????????????????
     *
     * @param job  ????????????job??????
     * @param task task??????
     */
    private void statistics(Job job, Task task) {
        POI poi = job.poi();
        // ??????getPoi???????????????????????????????????????
        // ??????Job???????????????
        // ??????Job??????????????????
        job.plusRequestActualTimes(); // ??????????????????
        job.plusToPoiActualCount(poi.details().size()); // ?????????????????????poi??????

        // ??????Task???????????????
        // ??????task??????????????????
        task.plusRequestActualTimes(); // ??????????????????
        task.plusPoiActualSum(poi.details().size());
    }

    /**
     * ????????????Job???Poi
     *
     * @param key      key
     * @param polygon  ????????????
     * @param keywords ?????????
     * @param types    ??????
     * @param page     ??????
     * @param size     ???????????????
     * @return ????????????poi??????
     * @throws ReTryAgainException ???????????????????????????????????????
     */
    private POI getPoi(String key, String polygon, String keywords, String types, int page, int size) throws UnRetryAgainException, ReTryAgainException {
        if (!configHolder.hasStart) {
            throw new UnRetryAgainException(NoTryAgainErrorCode.STOP_TASK);
        }
        LocalDateTime startTime = LocalDateTime.now();//??????????????????
        POI poi = mapDao.getPoi(key, polygon, keywords, types, configHolder.extension, page, size);
        LocalDateTime endTime = LocalDateTime.now(); //??????????????????

        // ???????????????????????????????????????
        int maxThreadNum = getMaxThreadNum(configHolder.qps, configHolder.aMapKeys.size());
        int threadNum = Math.min(maxThreadNum, configHolder.threadNum);
        int perExecuteTime = getPerExecuteTime(configHolder.aMapKeys.size(), threadNum, configHolder.qps, configHolder.threadNum * 1.0 / threadNum);
        if (Duration.between(startTime, endTime).toMillis() < perExecuteTime) { // ????????????????????????perExecuteTime
            try {
                TimeUnit.MILLISECONDS.sleep(perExecuteTime - Duration.between(startTime, endTime).toMillis() + (long) (Math.log(configHolder.waitFactorForQps) * 50L));
            } catch (InterruptedException e) {
//                e.printStackTrace();
            }
        }

        // ??????????????????
        if (poi == null || poi.infoCode() != 10000) {
            if (poi == null || poi.infoCode() == null || poi.status() == null || poi.info() == null) {
                // ????????????????????????
                throw new ReTryAgainException(TryAgainErrorCode.RETURN_NULL_DATA);
            } else {
                // ??????????????????
                NoTryAgainErrorCode noTryAgainErrorCode = NoTryAgainErrorCode.getError(poi.infoCode());
                if (noTryAgainErrorCode != null) {
                    synchronized (this) {
                        // key????????????????????????key
                        if (noTryAgainErrorCode.equals(NoTryAgainErrorCode.USER_DAILY_QUERY_OVER_LIMIT)) {
                            UnRetryAgainException unReTryAgainException = new UnRetryAgainException(NoTryAgainErrorCode.USER_DAILY_QUERY_OVER_LIMIT);
                            if (configHolder.aMapKeys.contains(key)) {
                                // ????????????????????????
                                appendMessage(unReTryAgainException.getMessage());
                                removeKey(key);
                            }
                            // ??????????????????key???????????????????????????????????????????????????
                            if (configHolder.aMapKeys.size() != 0) {
                                throw new ReTryAgainException(TryAgainErrorCode.TRY_OTHER_KEY, "??????key???" + key, unReTryAgainException);
                            }
                        }
                    }
                    throw new UnRetryAgainException(noTryAgainErrorCode);
                }
                // ???????????????
                TryAgainErrorCode tryAgainErrorCode = TryAgainErrorCode.getError(poi.infoCode());
                if (tryAgainErrorCode != null) {
                    if (configHolder.errorCodeHashSet.contains(tryAgainErrorCode)) {
                        configHolder.waitFactorForQps++;
                    }
                    throw new ReTryAgainException(tryAgainErrorCode);
                }
                throw new UnRetryAgainException(NoTryAgainErrorCode.UNKNOWN_WEB_ERROR);
            }
        }
        if (poi.count() == null || poi.details() == null || poi.infoCode() == null || poi.status() == null) {
            throw new ReTryAgainException(TryAgainErrorCode.RETURN_NULL_DATA);
        }
        return poi;
    }

    private String filename(String format) {
        String filename = viewHolder.outputDirectory.getText();
        switch (configHolder.tab) {
            case "?????????":
                filename = filename + "/????????????_" + viewHolder.adCode.getText() + (!viewHolder.types.getText().isEmpty() ? "types_" + viewHolder.types.getText() : "") + (!viewHolder.keywords.getText().isEmpty() ? "keywords_" + viewHolder.keywords.getText() : "");
                break;
            case "??????":
                filename = filename + "/????????????_" + viewHolder.rectangle.getText() + (!viewHolder.types.getText().isEmpty() ? "types_" + viewHolder.types.getText() : "") + (!viewHolder.keywords.getText().isEmpty() ? "keywords_" + viewHolder.keywords.getText() : "");
                break;
            case "?????????":
                filename = filename + "/????????????_" + FileUtil.getFileName(viewHolder.userFile.getText()) + (!viewHolder.types.getText().isEmpty() ? "types_" + viewHolder.types.getText() : "") + (!viewHolder.keywords.getText().isEmpty() ? "keywords_" + viewHolder.keywords.getText() : "");
                break;
        }
        return filename.length() > 200 ? filename.substring(0, 200) + "???." + format : filename + "." + format;
    }

    private String[] header(List<POI.OutputFields> outputFields) {
        return outputFields.stream()
                .map(POI.OutputFields::fieldName)
                .toArray(String[]::new);
    }

    private String[] content(POI.Info item, List<POI.OutputFields> outputFields) {
        List<String> result = new ArrayList<>();
        String[] lnglat = BeanUtils.obj2String(item.location()).split(",");
        double[] wgs84;
        if (lnglat.length == 2) {
            wgs84 = CoordinateTransformUtil.transformGCJ02ToWGS84(Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
        } else {
            wgs84 = new double[]{0.0, 0.0};
        }

        for (POI.OutputFields field : outputFields) {
            switch (field) {
                case ID:
                    result.add(BeanUtils.obj2String(item.poiId()));
                    break;
                case TEL:
                    result.add(BeanUtils.obj2String(item.tel()));
                    break;
                case NAME:
                    result.add(BeanUtils.obj2String(item.name()));
                    break;
                case TYPE:
                    result.add(BeanUtils.obj2String(item.type()));
                    break;
                case EMAIL:
                    result.add(BeanUtils.obj2String(item.email()));
                    break;
                case AD_CODE:
                    result.add(BeanUtils.obj2String(item.adCode()));
                    break;
                case AD_NAME:
                    result.add(BeanUtils.obj2String(item.adName()));
                    break;
                case ADDRESS:
                    result.add(BeanUtils.obj2String(item.address()));
                    break;
                case WEBSITE:
                    result.add(BeanUtils.obj2String(item.website()));
                    break;
                case BIZ_TYPE:
                    result.add(BeanUtils.obj2String(item.bizType()));
                    break;
                case GCJ02_LNG:
                    if (lnglat.length == 2) {
                        result.add(lnglat[0]);
                    }
                    break;
                case GCJ02_LAT:
                    if (lnglat.length == 2) {
                        result.add(lnglat[1]);
                    }
                    break;
                case WGS84_LNG:
                    result.add(BeanUtils.obj2String(wgs84[0]));
                    break;
                case WGS84_LAT:
                    result.add(BeanUtils.obj2String(wgs84[1]));
                    break;
                case CITY_CODE:
                    result.add(BeanUtils.obj2String(item.cityCode()));
                    break;
                case CITY_NAME:
                    result.add(BeanUtils.obj2String(item.cityName()));
                    break;
                case POST_CODE:
                    result.add(BeanUtils.obj2String(item.postCode()));
                    break;
                case TYPE_CODE:
                    result.add(BeanUtils.obj2String(item.typeCode()));
                    break;
                case PROVINCE_CODE:
                    result.add(BeanUtils.obj2String(item.provinceCode()));
                    break;
                case PROVINCE_NAME:
                    result.add(BeanUtils.obj2String(item.provinceName()));
                    break;
            }
        }
        return result.toArray(new String[0]);
    }

    private void writeToCsv(List<POI.Info> res, List<POI.OutputFields> outputFields) {
        if (!configHolder.hasStart) return;
        String filename = filename("csv");
        File csvFile = FileUtil.getNewFile(filename);
        if (csvFile == null) {
            appendMessage("??????????????????????????????????????????");
            return;
        }
        try (CSVWriter writer = new CSVWriter(Files.newBufferedWriter(csvFile.toPath(), StandardCharsets.UTF_8))) {
            appendMessage("??????????????????????????????");
            writer.writeNext(header(outputFields));
            for (POI.Info info : res) {
                writer.writeNext(content(info, outputFields));
            }
            appendMessage("??????????????????????????????" + csvFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            appendMessage("????????????");
            appendMessage(e.getMessage());
        }
    }

    private void writeToGeoJson(List<POI.Info> res, List<POI.OutputFields> outputFields) {
        if (!configHolder.hasStart) return;
        String filename = filename("json");
        File jsonFile = FileUtil.getNewFile(filename);
        if (jsonFile == null) {
            appendMessage("??????????????????????????????????????????");
            return;
        }
        try (BufferedWriter writer = Files.newBufferedWriter(jsonFile.toPath(), StandardCharsets.UTF_8)) {
            appendMessage("??????????????????????????????");
            writer.write("{" +
                    "\"type\":\"" + "FeatureCollection" + "\"" +
                    ", \"features\": [");
            boolean first = true;
            for (POI.Info item : res) {
                Feature feature = getFeatureFromInfo(item, outputFields);
                if (feature != null) {
                    if (!first)
                        writer.write(",");
                    writer.write(feature.toString());
                    first = false;
                }
            }
            writer.write("]" +
                    "}");
            appendMessage("??????????????????????????????" + jsonFile.getAbsolutePath());
        } catch (IOException e) {
            e.printStackTrace();
            appendMessage("????????????");
            appendMessage(e.getMessage());
        }
    }

    private String createTypeSpecForShp(List<POI.OutputFields> outputFields) {
        // ?????? shapefile ??? GeoJSON ????????????????????????????????????????????????????????? properties ?????????
        String[] header = outputFields.stream()
                .map(POI.OutputFields::shapeFieldName)
                .toArray(String[]::new);
        StringBuilder result = new StringBuilder();
        for (String item : header) {
            result.append(item).append(":String").append(",");
        }
        result.deleteCharAt(result.length() - 1);
        return result.toString();
    }

    private void writeToShp(List<POI.Info> res, List<POI.OutputFields> outputFields) {
        if (!configHolder.hasStart) return;
        String filename = filename("shp");
        appendMessage("??????????????????????????????");
        try {
            final SimpleFeatureType type =
                    DataUtilities.createType(
                            "Location",
                            "the_geom:Point:srid=4326," + createTypeSpecForShp(outputFields)
                    );
            List<SimpleFeature> features = new ArrayList<>();
            GeometryFactory geometryFactory = JTSFactoryFinder.getGeometryFactory();
            SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(type);
            for (POI.Info info : res) {
                String[] lnglat = info.location().toString().split(",");
                if (lnglat.length == 2) {
                    double[] wgs84 = CoordinateTransformUtil.transformGCJ02ToWGS84(Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
                    Point point = geometryFactory.createPoint(new Coordinate(wgs84[0], wgs84[1]));
                    featureBuilder.add(point);

                    // attrs
                    String[] content = content(info, outputFields);
                    for (String item : content) {
                        featureBuilder.add(item);
                    }
                    SimpleFeature feature = featureBuilder.buildFeature(null);
                    features.add(feature);
                }
            }
            File shpFile = FileUtil.getNewFile(filename);
            if (shpFile == null) {
                appendMessage("????????????????????????????????????");
                return;
            }
            if (SpatialDataTransformUtil.saveFeaturesToShp(features, type, shpFile.getAbsolutePath())) {
                appendMessage("??????????????????????????????" + shpFile.getAbsolutePath());
            } else appendMessage("????????????");
        } catch (SchemaException e) {
            e.printStackTrace();
            appendMessage("????????????");
        }
    }

    private Feature getFeatureFromInfo(POI.Info item, List<POI.OutputFields> outputFields) {
        String[] lnglat = BeanUtils.obj2String(item.location()).split(",");
        double[] wgs84;
        if (lnglat.length == 2) {
            wgs84 = CoordinateTransformUtil.transformGCJ02ToWGS84(Double.parseDouble(lnglat[0]), Double.parseDouble(lnglat[1]));
        } else {
            return null;
        }
        JsonObject geometry = new JsonObject();
        geometry.addProperty("type", "Point");
        JsonArray coordinates = new JsonArray();
        coordinates.add(wgs84[0]);
        coordinates.add(wgs84[1]);
        geometry.add("coordinates", coordinates);

        Map<String, String> properties = new HashMap<>();
        for (POI.OutputFields field : outputFields) {
            switch (field) {
                case ID:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.poiId()));
                    break;
                case TEL:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.tel()));
                    break;
                case NAME:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.name()));
                    break;
                case TYPE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.type()));
                    break;
                case EMAIL:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.email()));
                    break;
                case AD_CODE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.adCode()));
                    break;
                case AD_NAME:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.adName()));
                    break;
                case ADDRESS:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.address()));
                    break;
                case WEBSITE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.website()));
                    break;
                case BIZ_TYPE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.bizType()));
                    break;
                case GCJ02_LNG:
                    properties.put(field.fieldName(), lnglat[0]);
                    break;
                case GCJ02_LAT:
                    properties.put(field.fieldName(), lnglat[1]);
                    break;
                case WGS84_LNG:
                    properties.put(field.fieldName(), BeanUtils.obj2String(wgs84[0]));
                    break;
                case WGS84_LAT:
                    properties.put(field.fieldName(), BeanUtils.obj2String(wgs84[1]));
                    break;
                case CITY_CODE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.cityCode()));
                    break;
                case CITY_NAME:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.cityName()));
                    break;
                case POST_CODE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.postCode()));
                    break;
                case TYPE_CODE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.typeCode()));
                    break;
                case PROVINCE_CODE:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.provinceCode()));
                    break;
                case PROVINCE_NAME:
                    properties.put(field.fieldName(), BeanUtils.obj2String(item.provinceName()));
                    break;
            }
        }
        return new Feature(geometry.toString(), properties);
    }

    private boolean parseRect(String text) {
        String pattern = "^(-?\\d{1,3}(\\.\\d+)?),\\s?(-?\\d{1,3}(\\.\\d+)?)#(-?\\d{1,3}(\\.\\d+)?),\\s?(-?\\d{1,3}(\\.\\d+)?)$";
        return Pattern.matches(pattern, text);
    }

    private void clearMessage() {
        Platform.runLater(() -> viewHolder.messageDetail.clear());
    }

    private void appendMessage(String text) {
        Platform.runLater(() -> viewHolder.messageDetail.appendText(text + "\r\n"));
    }
}
