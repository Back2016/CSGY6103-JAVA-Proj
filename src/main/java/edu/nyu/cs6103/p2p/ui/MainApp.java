package edu.nyu.cs6103.p2p.ui;

import edu.nyu.cs6103.p2p.common.AppConfig;
import edu.nyu.cs6103.p2p.common.CsvUtils;
import edu.nyu.cs6103.p2p.common.ThrowableUtils;
import edu.nyu.cs6103.p2p.db.TrackerDatabase;
import edu.nyu.cs6103.p2p.model.DownloadHistoryEntry;
import edu.nyu.cs6103.p2p.model.SearchResult;
import edu.nyu.cs6103.p2p.model.TrackerRecord;
import edu.nyu.cs6103.p2p.peer.PeerNode;
import edu.nyu.cs6103.p2p.tracker.TrackerServer;
import edu.nyu.cs6103.p2p.tracker.TrackerClient;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.util.Duration;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.concurrent.Worker;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class MainApp extends Application {
    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("0.0");
    private static final Path APP_HOME = Path.of(System.getProperty("user.home"), "P2PFileSharing");
    private static final Path DEFAULT_TRACKER_RECORDS_DIR = APP_HOME.resolve("trackerRecords");
    private static final Path DEFAULT_DOWNLOADS_DIR = APP_HOME.resolve("downloads");
    private static final Path DEFAULT_TRACKER_DB = APP_HOME.resolve("tracker").resolve("tracker.db");

    private final ObservableList<String> sharedFiles = FXCollections.observableArrayList();
    private final ObservableList<SearchResult> searchResults = FXCollections.observableArrayList();
    private final ObservableList<DownloadHistoryEntry> historyEntries = FXCollections.observableArrayList();
    private final ObservableList<TrackerRecord> trackerRecords = FXCollections.observableArrayList();

    private final VBox notificationPane = new VBox(8);
    private final Label peerCountLabel = new Label("— peers online");
    private final List<String> recentSearches = new ArrayList<>();
    private final FlowPane recentSearchPane = new FlowPane(6, 6);
    private Timeline peerCountTimeline;

    private PeerNode peerNode;
    private TrackerServer localTrackerServer;
    private Thread localTrackerThread;
    private volatile boolean localTrackerRunning;
    private volatile boolean trackerConnected;
    private volatile boolean transferBusy;

    @Override
    public void start(Stage stage) {
        TextField trackerHostField = styledField("localhost");
        TextField trackerPortField = styledField(String.valueOf(AppConfig.DEFAULT_TRACKER_PORT));
        TextField peerPortField = styledField(String.valueOf(AppConfig.DEFAULT_PEER_PORT));
        TextField peerIdField = styledField(PeerNode.generatePeerId());
        TextField peerHostField = styledField(suggestPeerHost());
        TextField trackerRecordsDirField = styledField(DEFAULT_TRACKER_RECORDS_DIR.toAbsolutePath().toString());
        TextField downloadsDirField = styledField(DEFAULT_DOWNLOADS_DIR.toAbsolutePath().toString());
        TextField searchField = styledField("");
        searchField.setPromptText("Search by full filename or partial filename");

        Button checkTrackerButton = secondaryButton("Check Tracker");
        Button startLocalTrackerButton = secondaryButton("Start Tracker Here");
        Button stopLocalTrackerButton = secondaryButton("Stop Tracker Here");
        Button connectTrackerButton = primaryButton("Connect to Tracker");
        Button disconnectTrackerButton = secondaryButton("Disconnect Tracker");
        Button chooseTrackerRecordsDirButton = secondaryButton("Folder");
        Button chooseDownloadsDirButton = secondaryButton("Folder");
        Button openDownloadsDirButton = secondaryButton("Open");
        Button shareFileButton = primaryButton("Share a File");
        Button searchButton = primaryButton("Search");
        Button downloadButton = primaryButton("Download Selected");
        Button refreshHistoryButton = secondaryButton("Refresh");
        Button refreshTrackerRecordsButton = secondaryButton("Refresh Records");
        Button loadTrackerRecordsCsvButton = secondaryButton("Read Past Records");
        Button openDownloadPathButton = secondaryButton("Open Folder");

        shareFileButton.setDisable(true);
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        refreshHistoryButton.setDisable(true);
        refreshTrackerRecordsButton.setDisable(true);
        loadTrackerRecordsCsvButton.setDisable(false);
        openDownloadPathButton.setDisable(true);
        disconnectTrackerButton.setDisable(true);
        stopLocalTrackerButton.setDisable(true);

        Label appTitle = new Label("Peer-to-Peer File Sharing");
        appTitle.setStyle("-fx-font-size: 28px; -fx-font-weight: 800; -fx-text-fill: #132238;");
        Label subtitle = new Label("Choose a tracker, connect to it, then share files or download from other peers.");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #5c6b7a;");

        Label readinessPill = new Label("Tracker Disconnected");
        readinessPill.setStyle(pillStyle("#fde68a", "#7c5a00"));

        peerCountLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #607282;");

        Label setupHint = new Label("1. Check Tracker  2. Connect  3. Share File  4. Search  5. Download");
        setupHint.setStyle("-fx-text-fill: #38556f; -fx-font-size: 13px; -fx-font-weight: 600;");

        ListView<String> sharedListView = new ListView<>(sharedFiles);
        sharedListView.setPlaceholder(new Label("No files shared yet."));
        sharedListView.setPrefHeight(180);

        ListView<SearchResult> searchListView = new ListView<>(searchResults);
        searchListView.setPlaceholder(new Label("Search results will appear here."));
        searchListView.setCellFactory(list -> new SearchResultCell());

        ListView<DownloadHistoryEntry> historyListView = new ListView<>(historyEntries);
        historyListView.setPlaceholder(new Label("No downloads yet."));
        historyListView.setCellFactory(list -> new DownloadHistoryCell());

        ListView<TrackerRecord> trackerRecordsListView = new ListView<>(trackerRecords);
        trackerRecordsListView.setPlaceholder(new Label("No tracker records yet."));
        trackerRecordsListView.setCellFactory(list -> new TrackerRecordCell());


        TextArea logArea = new TextArea();
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(7);
        logArea.setStyle("-fx-font-family: 'SF Mono', 'Menlo', monospace; -fx-font-size: 12px;");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setMaxWidth(Double.MAX_VALUE);
        progressBar.setStyle("-fx-accent: #1f7a5c;");
        Label statusLabel = new Label("Idle");
        statusLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #35556c;");

        chooseTrackerRecordsDirButton.setOnAction(event -> chooseDirectory(stage, trackerRecordsDirField));
        chooseDownloadsDirButton.setOnAction(event -> chooseDirectory(stage, downloadsDirField));
        openDownloadsDirButton.setOnAction(event -> openDownloadLocation(Path.of(downloadsDirField.getText().trim()), logArea));
        historyListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) ->
                openDownloadPathButton.setDisable(selected == null));

        checkTrackerButton.setOnAction(event -> {
            String host = trackerHostField.getText().trim();
            int port = Integer.parseInt(trackerPortField.getText().trim());
            statusLabel.setText("Checking tracker health...");
            Task<Boolean> checkTask = new Task<>() {
                @Override
                protected Boolean call() throws Exception {
                    return pingTracker(host, port);
                }
            };
            checkTask.setOnSucceeded(evt -> {
                if (Boolean.TRUE.equals(checkTask.getValue())) {
                    readinessPill.setText(trackerConnected ? "Connected to Tracker" : "Tracker Healthy");
                    readinessPill.setStyle(pillStyle("#cdeccf", "#0d5d31"));
                    statusLabel.setText("Healthy tracker found on " + host + ":" + port);
                    appendLog(logArea, "Tracker responded successfully at " + host + ":" + port);
                } else {
                    readinessPill.setText("Tracker Missing");
                    readinessPill.setStyle(pillStyle("#ffd8d2", "#8b2e1f"));
                    statusLabel.setText("No tracker responding on " + host + ":" + port);
                    appendLog(logArea, "No healthy tracker found on " + host + ":" + port + ". Start one here or switch host/port.");
                }
            });
            checkTask.setOnFailed(evt -> {
                readinessPill.setText("Tracker Missing");
                readinessPill.setStyle(pillStyle("#ffd8d2", "#8b2e1f"));
                statusLabel.setText("No tracker responding on " + host + ":" + port);
                appendLog(logArea, "Tracker check failed on " + host + ":" + port + ": " + checkTask.getException().getMessage());
            });
            runTask(checkTask);
        });

        startLocalTrackerButton.setOnAction(event -> {
            if (localTrackerRunning) {
                statusLabel.setText("Local tracker is already running");
                return;
            }
            try {
                Files.createDirectories(DEFAULT_TRACKER_DB.getParent());
                localTrackerServer = new TrackerServer(
                        Integer.parseInt(trackerPortField.getText().trim()),
                        new TrackerDatabase("jdbc:sqlite:" + DEFAULT_TRACKER_DB.toAbsolutePath())
                );
            } catch (Exception exception) {
                appendLog(logArea, "Could not prepare tracker storage: " + exception.getMessage());
                statusLabel.setText("Tracker start failed");
                return;
            }

            startLocalTrackerButton.setDisable(true);
            stopLocalTrackerButton.setDisable(false);
            readinessPill.setText("Starting Tracker");
            readinessPill.setStyle(pillStyle("#dbeafe", "#1d4f91"));
            statusLabel.setText("Starting local tracker...");
            appendLog(logArea, "Starting local tracker on port " + trackerPortField.getText().trim());

            localTrackerThread = new Thread(() -> {
                try {
                    localTrackerRunning = true;
                    localTrackerServer.start();
                } catch (Exception exception) {
                    Platform.runLater(() -> {
                        if (localTrackerRunning) {
                            appendLog(logArea, "Local tracker failed: " + exception.getMessage());
                            readinessPill.setText("Tracker Failed");
                            readinessPill.setStyle(pillStyle("#ffd8d2", "#8b2e1f"));
                            statusLabel.setText("Local tracker failed");
                        }
                    });
                } finally {
                    localTrackerRunning = false;
                    localTrackerServer = null;
                    Platform.runLater(() -> {
                        startLocalTrackerButton.setDisable(false);
                        stopLocalTrackerButton.setDisable(true);
                        if (!trackerConnected) {
                            readinessPill.setText("Tracker Disconnected");
                            readinessPill.setStyle(pillStyle("#fde68a", "#7c5a00"));
                        }
                    });
                }
            }, "ui-local-tracker");
            localTrackerThread.setDaemon(true);
            localTrackerThread.start();

            readinessPill.setText("Tracker Healthy");
            readinessPill.setStyle(pillStyle("#cdeccf", "#0d5d31"));
            statusLabel.setText("Local tracker is running on this machine");
            appendLog(logArea, "Local tracker is ready on localhost:" + trackerPortField.getText().trim());
        });

        stopLocalTrackerButton.setOnAction(event -> {
            if (!localTrackerRunning || localTrackerServer == null) {
                statusLabel.setText("No local tracker is running");
                return;
            }
            String warning = null;
            if (trackerConnected && peerNode != null && isCurrentTrackerLocal(trackerHostField.getText().trim(), peerHostField.getText().trim())) {
                try {
                    peerNode.unregisterFromTracker();
                } catch (IOException exception) {
                    warning = exception.getMessage();
                    peerNode.clearSharedFiles();
                } finally {
                    peerNode.stopServer();
                }
                peerNode = null;
                trackerConnected = false;
                sharedFiles.clear();
                searchResults.clear();
            }
            localTrackerServer.stop();
            localTrackerRunning = false;
            localTrackerServer = null;
            trackerRecords.clear();
            peerCountLabel.setText("— peers online");
            if (peerCountTimeline != null) { peerCountTimeline.stop(); peerCountTimeline = null; }
            setConnectionState(false, connectTrackerButton, checkTrackerButton, disconnectTrackerButton,
                    trackerHostField, trackerPortField, peerPortField, peerIdField, peerHostField,
                    trackerRecordsDirField, downloadsDirField,
                    chooseTrackerRecordsDirButton, chooseDownloadsDirButton, shareFileButton, searchButton,
                    downloadButton, refreshHistoryButton, refreshTrackerRecordsButton, openDownloadPathButton, searchListView,
                    progressBar, statusLabel, readinessPill);
            statusLabel.setText("Local tracker stopped");
            appendLog(logArea, "Stopped local tracker on port " + trackerPortField.getText().trim());
            if (warning != null) {
                appendLog(logArea, "Tracker unregister failed while stopping local tracker: " + warning);
            }
        });

        connectTrackerButton.setOnAction(event -> {
            ConnectionConfig config = readConnectionConfig(trackerHostField, trackerPortField, peerPortField, peerIdField,
                    peerHostField, trackerRecordsDirField, downloadsDirField);

            Task<PeerNode> connectTask = new Task<>() {
                @Override
                protected PeerNode call() throws Exception {
                    if (!pingTracker(config.trackerHost(), config.trackerPort())) {
                        throw new IOException("No healthy tracker found on " + config.trackerHost() + ":" + config.trackerPort());
                    }
                    PeerNode node = new PeerNode(
                            config.peerId(),
                            config.trackerHost(),
                            config.trackerPort(),
                            config.peerPort(),
                            config.peerHost(),
                            config.trackerRecordsDir(),
                            config.downloadsDir()
                    );
                    node.startServer();
                    return node;
                }
            };

            connectTask.setOnRunning(evt -> {
                connectTrackerButton.setDisable(true);
                checkTrackerButton.setDisable(true);
                statusLabel.setText("Connecting to tracker, starting peer service, and opening peer session...");
            });
            connectTask.setOnSucceeded(evt -> {
                peerNode = connectTask.getValue();
                trackerConnected = true;
                setConnectionState(true, connectTrackerButton, checkTrackerButton, disconnectTrackerButton,
                        trackerHostField, trackerPortField, peerPortField, peerIdField, peerHostField,
                        trackerRecordsDirField, downloadsDirField, chooseTrackerRecordsDirButton, chooseDownloadsDirButton,
                        shareFileButton, searchButton, downloadButton, refreshHistoryButton, refreshTrackerRecordsButton,
                        openDownloadPathButton, searchListView, progressBar, statusLabel, readinessPill);
                readinessPill.setText("Connected to Tracker");
                readinessPill.setStyle(pillStyle("#c7f2d8", "#0e5d35"));
                statusLabel.setText("Connected. This peer can now share and download.");

                appendLog(logArea, "Connected to tracker " + config.trackerHost() + ":" + config.trackerPort());
                appendLog(logArea, "Peer ready: " + config.peerId() + " on " + config.peerHost() + ":" + config.peerPort());
                refreshHistory();
                refreshTrackerRecords(peerNode, logArea);
                peerCountTimeline = new Timeline(new KeyFrame(Duration.seconds(2), e -> {
                    if (peerNode != null) {
                        Task<Integer> countTask = new Task<>() {
                            @Override
                            protected Integer call() throws Exception {
                                return peerNode.fetchPeerCount();
                            }
                        };
                        countTask.setOnSucceeded(ev -> peerCountLabel.setText(countTask.getValue() + " peer(s) online"));
                        runTask(countTask);
                    }
                }));
                peerCountTimeline.setCycleCount(Timeline.INDEFINITE);
                peerCountTimeline.play();
            });
            connectTask.setOnFailed(evt -> {
                trackerConnected = false;
                setConnectionState(false, connectTrackerButton, checkTrackerButton, disconnectTrackerButton,
                        trackerHostField, trackerPortField, peerPortField, peerIdField, peerHostField,
                        trackerRecordsDirField, downloadsDirField, chooseTrackerRecordsDirButton, chooseDownloadsDirButton,
                        shareFileButton, searchButton, downloadButton, refreshHistoryButton, refreshTrackerRecordsButton,
                        openDownloadPathButton, searchListView, progressBar, statusLabel, readinessPill);
                readinessPill.setText("Tracker Missing");
                readinessPill.setStyle(pillStyle("#ffd8d2", "#8b2e1f"));
                appendLog(logArea, "Could not connect to tracker: " + connectTask.getException().getMessage());
                appendLog(logArea, "Tip: use Check Tracker first, or start a tracker on this port if needed.");
                statusLabel.setText("No healthy tracker found. Start one here or switch host/port.");
            });
            runTask(connectTask);
        });

        disconnectTrackerButton.setOnAction(event -> {
            if (peerNode == null) {
                return;
            }
            PeerNode nodeToDisconnect = peerNode;
            Task<String> disconnectTask = new Task<>() {
                @Override
                protected String call() {
                    String warning = null;
                    try {
                        nodeToDisconnect.unregisterFromTracker();
                    } catch (IOException exception) {
                        warning = exception.getMessage();
                        nodeToDisconnect.clearSharedFiles();
                    } finally {
                        nodeToDisconnect.stopServer();
                    }
                    return warning;
                }
            };
            disconnectTask.setOnSucceeded(evt -> {
                peerNode = null;
                trackerConnected = false;
                sharedFiles.clear();
                searchResults.clear();
                trackerRecords.clear();
            peerCountLabel.setText("— peers online");
            if (peerCountTimeline != null) { peerCountTimeline.stop(); peerCountTimeline = null; }
                setConnectionState(false, connectTrackerButton, checkTrackerButton, disconnectTrackerButton,
                        trackerHostField, trackerPortField, peerPortField, peerIdField, peerHostField,
                        trackerRecordsDirField, downloadsDirField,
                        chooseTrackerRecordsDirButton, chooseDownloadsDirButton, shareFileButton, searchButton,
                        downloadButton, refreshHistoryButton, refreshTrackerRecordsButton, openDownloadPathButton, searchListView,
                        progressBar, statusLabel, readinessPill);
                String warning = disconnectTask.getValue();
                appendLog(logArea, warning == null
                        ? "Disconnected from tracker and cleared shared file registrations."
                        : "Disconnected locally, but tracker unregister request failed: " + warning);
            });
            disconnectTask.setOnFailed(evt -> {
                peerNode = null;
                trackerConnected = false;
                sharedFiles.clear();
                searchResults.clear();
                trackerRecords.clear();
            peerCountLabel.setText("— peers online");
            if (peerCountTimeline != null) { peerCountTimeline.stop(); peerCountTimeline = null; }
                setConnectionState(false, connectTrackerButton, checkTrackerButton, disconnectTrackerButton,
                        trackerHostField, trackerPortField, peerPortField, peerIdField, peerHostField,
                        trackerRecordsDirField, downloadsDirField, chooseTrackerRecordsDirButton, chooseDownloadsDirButton,
                        shareFileButton, searchButton, downloadButton, refreshHistoryButton, refreshTrackerRecordsButton,
                        openDownloadPathButton, searchListView, progressBar, statusLabel, readinessPill);
                appendLog(logArea, "Disconnect failed: " + disconnectTask.getException().getMessage());
            });
            runTask(disconnectTask);
        });

        shareFileButton.setOnAction(event -> {
            if (peerNode == null) {
                return;
            }
            FileChooser chooser = new FileChooser();
            var selectedFile = chooser.showOpenDialog(stage);
            if (selectedFile == null) {
                return;
            }

            statusLabel.setText("Registering shared file...");
            String sharePassword = promptForSharePassword(stage, selectedFile.toPath());
            if (sharePassword == null) {
                statusLabel.setText("Share cancelled");
                return;
            }
            setTransferState(false, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
            Task<Void> shareTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    peerNode.shareFile(selectedFile.toPath(), sharePassword);
                    return null;
                }
            };
            shareTask.setOnSucceeded(evt -> {
                setTransferState(true, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
                sharedFiles.setAll(peerNode.getSharedFileNames());
                appendLog(logArea, "Registered shared file: " + selectedFile.getName() +
                        (sharePassword.isBlank() ? "" : " (encrypted)"));
                statusLabel.setText("Shared " + selectedFile.getName());
                refreshTrackerRecords(peerNode, logArea);
            });
            shareTask.setOnFailed(evt -> {
                setTransferState(true, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
                appendLog(logArea, "Failed to share file: " + shareTask.getException().getMessage());
                statusLabel.setText("Failed to share file");
            });
            runTask(shareTask);
        });

        Runnable searchAction = () -> {
            if (peerNode == null) {
                return;
            }

            String query = searchField.getText().trim();
            statusLabel.setText(query.isEmpty() ? "Loading all known files..." : "Searching for \"" + query + "\"...");

            if (!query.isEmpty()) {
                recentSearches.remove(query);
                recentSearches.add(0, query);
                if (recentSearches.size() > 3) recentSearches.remove(recentSearches.size() - 1);
                recentSearchPane.getChildren().clear();
                for (String q : recentSearches) {
                    Button chip = new Button("🔍 " + q);
                    chip.setStyle("-fx-background-color: #e8f0fe; -fx-text-fill: #1a4a8a; -fx-font-size: 11px; " +
                            "-fx-padding: 3 10 3 10; -fx-background-radius: 12; -fx-cursor: hand; -fx-border-color: #b3c8f5; " +
                            "-fx-border-radius: 12;");
                    chip.setOnAction(e -> {
                        searchField.setText(q);
                        searchButton.fire();
                    });
                    recentSearchPane.getChildren().add(chip);
                }
                recentSearchPane.setVisible(true);
            }

            Task<List<SearchResult>> searchTask = new Task<>() {
                @Override
                protected List<SearchResult> call() throws Exception {
                    return peerNode.search(query);
                }
            };
            searchTask.setOnSucceeded(evt -> {
                searchResults.setAll(searchTask.getValue());
                appendLog(logArea, "Search returned " + searchTask.getValue().size() + " result(s).");
                statusLabel.setText(searchTask.getValue().isEmpty() ? "No matching files found" : "Select a result to download");
            });
            searchTask.setOnFailed(evt -> {
                appendLog(logArea, "Search failed: " + searchTask.getException().getMessage());
                statusLabel.setText("Search failed");
            });
            runTask(searchTask);
        };

        searchButton.setOnAction(event -> searchAction.run());
        searchField.setOnAction(event -> searchAction.run());

        searchListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            if (!transferBusy) {
                downloadButton.setDisable(selected == null || peerNode == null);
            }
        });
        searchListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2 && searchListView.getSelectionModel().getSelectedItem() != null && !downloadButton.isDisabled()) {
                downloadButton.fire();
            }
        });

        downloadButton.setOnAction(event -> {
            if (peerNode == null) {
                return;
            }
            SearchResult selected = searchListView.getSelectionModel().getSelectedItem();
            if (selected == null) {
                appendLog(logArea, "Select a search result to download.");
                return;
            }

            String downloadPassword = "";
            if (selected.encrypted()) {
                downloadPassword = promptForDownloadPassword(stage, selected);
                if (downloadPassword == null) {
                    statusLabel.setText("Download cancelled");
                    return;
                }
            }
            final String finalDownloadPassword = downloadPassword;

            setTransferState(false, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
            appendLog(logArea, "Starting download for " + selected.filename() + " from peers: " + peerNode.describeRemotePeers(selected));

            Task<Path> downloadTask = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    updateProgress(0, 1);
                    updateMessage("Starting download");
                    return peerNode.download(
                            selected,
                            finalDownloadPassword,
                            value -> updateProgress(value, 1),
                            this::updateMessage
                    );
                }
            };

            progressBar.progressProperty().bind(downloadTask.progressProperty());
            statusLabel.textProperty().bind(downloadTask.messageProperty());

            downloadTask.setOnSucceeded(evt -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setProgress(1);
                statusLabel.setText("Download complete");
                appendLog(logArea, "Downloaded to: " + downloadTask.getValue().toAbsolutePath());
                refreshHistory();
                setTransferState(true, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
            });
            downloadTask.setOnFailed(evt -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setProgress(0);
                statusLabel.setText("Download failed");
                Throwable failure = downloadTask.getException();
                appendLog(logArea, "Download failed: " + failure.getMessage());
                appendLog(logArea, "Root cause: " + ThrowableUtils.rootCauseMessage(failure));
                refreshHistory();
                setTransferState(true, shareFileButton, searchButton, downloadButton, refreshHistoryButton, searchListView);
            });
            runTask(downloadTask);
            showDownloadNotification(selected.filename(), downloadTask);
        });

        refreshHistoryButton.setOnAction(event -> refreshHistory());
        refreshTrackerRecordsButton.setOnAction(event -> {
            if (peerNode != null) {
                refreshTrackerRecords(peerNode, logArea);
            }
        });
        loadTrackerRecordsCsvButton.setOnAction(event ->
                showTrackerRecordsCsvDialog(stage, chooseTrackerRecordsCsv(stage, trackerRecordsDirField), logArea));
        openDownloadPathButton.setOnAction(event -> {
            DownloadHistoryEntry selected = historyListView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                openDownloadLocation(Path.of(selected.destinationPath()), logArea);
            }
        });

        GridPane configPane = new GridPane();
        configPane.setHgap(12);
        configPane.setVgap(12);
        configPane.addRow(0, fieldStack("Tracker Host", "Computer running the tracker", trackerHostField),
                fieldStack("Tracker Port", "Default 5050", trackerPortField));
        configPane.addRow(1, fieldStack("Peer ID", "Auto-generated is fine", peerIdField),
                fieldStack("Peer Port", "This peer's upload port", peerPortField));
        configPane.add(fieldStack("Peer Host / LAN IP", "Other computers must be able to reach this address", peerHostField), 0, 2, 2, 1);
        configPane.add(fieldStackWithButton("Tracker Records Folder", "Where per-session tracker records and CSV history are stored", trackerRecordsDirField, chooseTrackerRecordsDirButton), 0, 3, 2, 1);
        configPane.add(fieldStackWithButton("Downloads Folder", "Where downloads and peer history are stored", downloadsDirField, chooseDownloadsDirButton, openDownloadsDirButton), 0, 4, 2, 1);

        HBox trackerDiscoveryActions = new HBox(10, checkTrackerButton, startLocalTrackerButton);
        HBox.setHgrow(checkTrackerButton, Priority.ALWAYS);
        HBox.setHgrow(startLocalTrackerButton, Priority.ALWAYS);
        checkTrackerButton.setMaxWidth(Double.MAX_VALUE);
        startLocalTrackerButton.setMaxWidth(Double.MAX_VALUE);

        stopLocalTrackerButton.setMaxWidth(Double.MAX_VALUE);

        HBox connectionActions = new HBox(10, connectTrackerButton, disconnectTrackerButton);
        HBox.setHgrow(connectTrackerButton, Priority.ALWAYS);
        HBox.setHgrow(disconnectTrackerButton, Priority.ALWAYS);
        connectTrackerButton.setMaxWidth(Double.MAX_VALUE);
        disconnectTrackerButton.setMaxWidth(Double.MAX_VALUE);

        VBox setupCard = card(
                new Label("Step 1: Tracker Connection"),
                setupHint,
                helperLabel("Check whether a tracker is already alive. If it is not, you can start one on this machine, then connect this peer to it. For LAN testing, set Peer Host / LAN IP to the address other computers use to reach this machine."),
                configPane,
                sectionLabel("Local Tracker"),
                helperLabel("Use these controls to inspect, start, or stop the tracker process running on this machine."),
                trackerDiscoveryActions,
                stopLocalTrackerButton,
                sectionLabel("Peer Session"),
                helperLabel("Connect this peer to a tracker session, or disconnect it and clear its shared registrations."),
                connectionActions
        );
        styleCardTitle((Label) setupCard.getChildren().get(0));

        VBox shareCard = card(
                titledLabel("Step 2: Share Files"),
                helperLabel("Choose any local file to make it available to other peers after the tracker connection is active. Add a share password to encrypt the served copy."),
                shareFileButton,
                sectionLabel("Shared Files"),
                sharedListView
        );

        HBox searchControls = new HBox(10, searchField, searchButton, downloadButton);
        searchControls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        recentSearchPane.setVisible(false);
        recentSearchPane.managedProperty().bind(recentSearchPane.visibleProperty());

        VBox searchCard = card(
                titledLabel("Step 3: Search and Download"),
                helperLabel("Search by filename, select a result, or double-click it to download."),
                searchControls,
                recentSearchPane,
                searchListView
        );
        VBox.setVgrow(searchListView, Priority.ALWAYS);

        VBox transferCard = card(
                titledLabel("Transfer Status"),
                progressBar,
                statusLabel
        );

        VBox historyHeader = new VBox(4,
                titledLabel("Download History"),
                helperLabel("Recent completed or failed downloads for this peer."));

        HBox historyActions = new HBox(10, spacer(), openDownloadPathButton, refreshHistoryButton);
        historyActions.setAlignment(Pos.CENTER_LEFT);

        HBox trackerRecordsHeader = new HBox(10, titledLabel("Tracker Records"), spacer(), peerCountLabel, loadTrackerRecordsCsvButton, refreshTrackerRecordsButton);
        trackerRecordsHeader.setAlignment(Pos.CENTER_LEFT);

        VBox trackerRecordsCard = card(
                trackerRecordsHeader,
                helperLabel("Tracker metadata records below are the live records from the currently connected tracker. Use Read Past Records to open an older saved tracker_records.csv file."),
                trackerRecordsListView
        );
        VBox.setVgrow(trackerRecordsListView, Priority.ALWAYS);

        VBox historyCard = card(
                historyHeader,
                historyActions,
                historyListView
        );
        VBox.setVgrow(historyListView, Priority.ALWAYS);

        VBox activityCard = card(
                titledLabel("Activity Log"),
                helperLabel("Useful for debugging tracker connection, registration, and transfers."),
                logArea
        );
        VBox.setVgrow(logArea, Priority.ALWAYS);

        VBox leftColumn = new VBox(16, setupCard, shareCard);
        leftColumn.setPrefWidth(430);
        VBox.setVgrow(shareCard, Priority.ALWAYS);

        VBox centerColumn = new VBox(16, searchCard, transferCard, activityCard);
        VBox.setVgrow(searchCard, Priority.ALWAYS);
        VBox.setVgrow(activityCard, Priority.ALWAYS);

        VBox rightColumn = new VBox(16, trackerRecordsCard, historyCard);
        rightColumn.setPrefWidth(390);
        VBox.setVgrow(trackerRecordsCard, Priority.ALWAYS);
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        ScrollPane leftScroll = columnScrollPane(leftColumn);
        leftScroll.setPrefWidth(430);

        ScrollPane centerScroll = columnScrollPane(centerColumn);
        ScrollPane rightScroll = columnScrollPane(rightColumn);
        rightScroll.setPrefWidth(390);

        HBox header = new HBox(16, new VBox(6, appTitle, subtitle), spacer(), readinessPill);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(18, leftScroll, centerScroll, rightScroll);
        HBox.setHgrow(centerScroll, Priority.ALWAYS);
        HBox.setHgrow(rightScroll, Priority.ALWAYS);

        VBox rootContent = new VBox(18, header, new Separator(), content);
        VBox.setVgrow(content, Priority.ALWAYS);
        rootContent.setPadding(new Insets(22));
        rootContent.setStyle("""
                -fx-background-color:
                    linear-gradient(from 0% 0% to 100% 100%, #f7f3e8 0%, #eef5f3 45%, #f7fbff 100%);
                -fx-font-family: 'Aptos', 'Segoe UI', sans-serif;
                """);

        notificationPane.setPickOnBounds(false);
        notificationPane.setPadding(new Insets(0, 20, 20, 0));
        notificationPane.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(notificationPane, Pos.BOTTOM_RIGHT);

        BorderPane root = new BorderPane(rootContent);
        StackPane sceneRoot = new StackPane(root, notificationPane);

        Scene scene = new Scene(sceneRoot, 1540, 900);
        stage.setMinWidth(1260);
        stage.setMinHeight(760);
        stage.setTitle("Peer-to-Peer File Sharing System");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            if (peerNode != null) {
                try {
                    peerNode.unregisterFromTracker();
                } catch (IOException ignored) {
                    peerNode.clearSharedFiles();
                } finally {
                    peerNode.stopServer();
                    peerNode = null;
                    trackerConnected = false;
                }
            }
            if (localTrackerServer != null) {
                localTrackerServer.stop();
                localTrackerServer = null;
                localTrackerRunning = false;
            }
        });
        stage.show();
    }

    private TextField styledField(String value) {
        TextField field = new TextField(value);
        field.setStyle("""
                -fx-background-radius: 10;
                -fx-border-radius: 10;
                -fx-border-color: #c7d4df;
                -fx-background-color: #ffffff;
                -fx-padding: 10 12 10 12;
                -fx-font-size: 13px;
                """);
        return field;
    }

    private Button primaryButton(String text) {
        Button button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setStyle("""
                -fx-background-color: #1f7a5c;
                -fx-text-fill: white;
                -fx-font-weight: 700;
                -fx-background-radius: 12;
                -fx-padding: 11 18 11 18;
                -fx-cursor: hand;
                """);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(text);
        button.setStyle("""
                -fx-background-color: #e7eef5;
                -fx-text-fill: #21384d;
                -fx-font-weight: 700;
                -fx-background-radius: 10;
                -fx-padding: 10 14 10 14;
                -fx-cursor: hand;
                """);
        return button;
    }

    private VBox fieldStack(String title, String hint, TextField field) {
        VBox box = new VBox(6, sectionLabel(title), helperLabel(hint), field);
        VBox.setVgrow(field, Priority.NEVER);
        return box;
    }

    private VBox fieldStackWithButton(String title, String hint, TextField field, Button... buttons) {
        HBox row = new HBox(10, field);
        row.getChildren().addAll(buttons);
        HBox.setHgrow(field, Priority.ALWAYS);
        return new VBox(6, sectionLabel(title), helperLabel(hint), row);
    }

    private VBox card(javafx.scene.Node... children) {
        VBox box = new VBox(12, children);
        box.setPadding(new Insets(18));
        box.setStyle("""
                -fx-background-color: rgba(255,255,255,0.92);
                -fx-background-radius: 18;
                -fx-border-radius: 18;
                -fx-border-color: rgba(166, 185, 200, 0.55);
                -fx-effect: dropshadow(gaussian, rgba(32,56,77,0.08), 18, 0.18, 0, 6);
                """);
        return box;
    }

    private ScrollPane columnScrollPane(VBox content) {
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPannable(true);
        scrollPane.setStyle("""
                -fx-background-color: transparent;
                -fx-background-insets: 0;
                -fx-padding: 0;
                """);
        scrollPane.setContent(content);
        return scrollPane;
    }

    private Label titledLabel(String text) {
        Label label = new Label(text);
        styleCardTitle(label);
        return label;
    }

    private void styleCardTitle(Label label) {
        label.setStyle("-fx-font-size: 18px; -fx-font-weight: 800; -fx-text-fill: #163048;");
    }

    private Label helperLabel(String text) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setStyle("-fx-font-size: 12px; -fx-text-fill: #607282;");
        return label;
    }

    private Label sectionLabel(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #30485e;");
        return label;
    }

    private Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private String pillStyle(String background, String textColor) {
        return "-fx-background-color: " + background + ";" +
                "-fx-text-fill: " + textColor + ";" +
                "-fx-font-weight: 800;" +
                "-fx-padding: 8 14 8 14;" +
                "-fx-background-radius: 999;";
    }

    private void chooseDirectory(Stage stage, TextField targetField) {
        DirectoryChooser chooser = new DirectoryChooser();
        var directory = chooser.showDialog(stage);
        if (directory != null) {
            targetField.setText(directory.toPath().toAbsolutePath().toString());
        }
    }

    private void appendLog(TextArea logArea, String message) {
        Platform.runLater(() -> logArea.appendText(message + System.lineSeparator()));
    }

    private void refreshHistory() {
        if (peerNode == null) {
            return;
        }
        historyEntries.setAll(peerNode.getDownloadHistory());
    }

    private void refreshTrackerRecords(PeerNode node, TextArea logArea) {
        Task<Object[]> task = new Task<>() {
            @Override
            protected Object[] call() throws Exception {
                return new Object[]{node.fetchTrackerRecords(), node.fetchPeerCount()};
            }
        };
        task.setOnSucceeded(evt -> {
            @SuppressWarnings("unchecked")
            List<TrackerRecord> records = (List<TrackerRecord>) task.getValue()[0];
            int count = (int) task.getValue()[1];
            trackerRecords.setAll(records);
            peerCountLabel.setText(count + " peer(s) online");
        });
        task.setOnFailed(evt -> appendLog(logArea, "Could not refresh tracker records: " + task.getException().getMessage()));
        runTask(task);
    }

    private void applyDisconnectedState(Button connectTrackerButton,
                                        Button checkTrackerButton,
                                        Button disconnectTrackerButton,
                                        TextField trackerHostField,
                                        TextField trackerPortField,
                                        TextField peerPortField,
                                        TextField peerIdField,
                                        TextField peerHostField,
                                        TextField trackerRecordsDirField,
                                        TextField downloadsDirField,
                                        Button chooseTrackerRecordsDirButton,
                                        Button chooseDownloadsDirButton,
                                        Button shareFileButton,
                                        Button searchButton,
                                        Button downloadButton,
                                        Button refreshHistoryButton,
                                        Button refreshTrackerRecordsButton,
                                        Button openDownloadPathButton,
                                        ProgressBar progressBar,
                                        Label statusLabel,
                                        Label readinessPill) {
        connectTrackerButton.setDisable(false);
        checkTrackerButton.setDisable(false);
        disconnectTrackerButton.setDisable(true);
        trackerHostField.setDisable(false);
        trackerPortField.setDisable(false);
        peerPortField.setDisable(false);
        peerIdField.setDisable(false);
        peerHostField.setDisable(false);
        trackerRecordsDirField.setDisable(false);
        downloadsDirField.setDisable(false);
        chooseTrackerRecordsDirButton.setDisable(false);
        chooseDownloadsDirButton.setDisable(false);
        shareFileButton.setDisable(true);
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        refreshHistoryButton.setDisable(true);
        refreshTrackerRecordsButton.setDisable(true);
        openDownloadPathButton.setDisable(true);
        progressBar.setProgress(0);
        statusLabel.setText("Disconnected from tracker");
        readinessPill.setText(localTrackerRunning ? "Tracker Healthy" : "Tracker Disconnected");
        readinessPill.setStyle(localTrackerRunning ? pillStyle("#cdeccf", "#0d5d31") : pillStyle("#fde68a", "#7c5a00"));
    }

    private boolean isCurrentTrackerLocal(String trackerHost, String peerHost) {
        return "localhost".equalsIgnoreCase(trackerHost)
                || "127.0.0.1".equals(trackerHost)
                || trackerHost.equals(peerHost);
    }

    private void openDownloadLocation(Path destination, TextArea logArea) {
        if (!Files.exists(destination)) {
            appendLog(logArea, "Downloaded file is missing: " + destination);
            return;
        }
        try {
            Path target = Files.isDirectory(destination) ? destination : destination.getParent();
            if (target == null) {
                target = destination;
            }
            openInFileManager(target);
        } catch (IOException exception) {
            appendLog(logArea, "Could not open download location: " + exception.getMessage());
        }
    }

    private void openInFileManager(Path target) throws IOException {
        if (Desktop.isDesktopSupported()) {
            Desktop desktop = Desktop.getDesktop();
            if (desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) && Files.isRegularFile(target)) {
                desktop.browseFileDirectory(target.toFile());
                return;
            }
            if (desktop.isSupported(Desktop.Action.OPEN)) {
                desktop.open(target.toFile());
                return;
            }
        }

        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            new ProcessBuilder("open", target.toAbsolutePath().toString()).start();
        } else if (os.contains("win")) {
            new ProcessBuilder("explorer", target.toAbsolutePath().toString()).start();
        } else {
            new ProcessBuilder("xdg-open", target.toAbsolutePath().toString()).start();
        }
    }

    private Path chooseTrackerRecordsCsv(Stage owner, TextField trackerRecordsDirField) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Choose Past tracker_records.csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV Files", "*.csv"));
        Path initialDir = Path.of(trackerRecordsDirField.getText().trim());
        if (Files.exists(initialDir)) {
            chooser.setInitialDirectory(initialDir.toFile());
        }
        chooser.setInitialFileName("tracker_records.csv");
        var selected = chooser.showOpenDialog(owner);
        return selected == null ? null : selected.toPath();
    }

    private void showTrackerRecordsCsvDialog(Stage owner, Path csvPath, TextArea logArea) {
        if (csvPath == null) {
            appendLog(logArea, "Past tracker records selection cancelled.");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(csvPath);
            List<String> records = new ArrayList<>();
            for (int index = 1; index < lines.size(); index++) {
                if (lines.get(index).isBlank()) {
                    continue;
                }
                List<String> values = CsvUtils.parseLine(lines.get(index));
                if (values.size() < 10) {
                    continue;
                }
                records.add(values.get(2) + " [" + shortId(values.get(0)) + "]\n"
                        + "hash: " + values.get(1) + "\n"
                        + "size: " + values.get(3) + " bytes, chunks: " + values.get(5) + "\n"
                        + "encrypted: " + values.get(6) + "\n"
                        + "updated: " + values.get(7) + "\n"
                        + "peers: " + values.get(8) + "\n"
                        + "chunk records: " + values.get(9));
            }

            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(owner);
            alert.setTitle("Past Tracker Records");
            alert.setHeaderText("Loaded " + records.size() + " tracker record(s) from " + csvPath.toAbsolutePath());
            ListView<String> listView = new ListView<>(FXCollections.observableArrayList(records));
            listView.setPrefWidth(760);
            listView.setPrefHeight(420);
            alert.getDialogPane().setContent(listView);
            alert.getDialogPane().setMinHeight(480);
            alert.showAndWait();
        } catch (IOException exception) {
            appendLog(logArea, "Could not read tracker_records.csv: " + exception.getMessage());
        }
    }

    private String promptForSharePassword(Stage stage, Path selectedFile) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("Share File");
        dialog.setHeaderText("Set a password for " + selectedFile.getFileName());
        dialog.setContentText("Leave blank to share without encryption:");
        return dialog.showAndWait().map(String::trim).orElse(null);
    }

    private String promptForDownloadPassword(Stage stage, SearchResult selected) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(stage);
        dialog.setTitle("Encrypted Download");
        dialog.setHeaderText(selected.filename() + " is encrypted");
        dialog.setContentText("Enter the file password:");
        return dialog.showAndWait().map(String::trim).orElse(null);
    }

    private void runTask(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean pingTracker(String host, int port) {
        try {
            return new TrackerClient(host, port).ping() != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private ConnectionConfig readConnectionConfig(TextField trackerHostField,
                                                  TextField trackerPortField,
                                                  TextField peerPortField,
                                                  TextField peerIdField,
                                                  TextField peerHostField,
                                                  TextField trackerRecordsDirField,
                                                  TextField downloadsDirField) {
        return new ConnectionConfig(
                trackerHostField.getText().trim(),
                Integer.parseInt(trackerPortField.getText().trim()),
                Integer.parseInt(peerPortField.getText().trim()),
                peerIdField.getText().trim(),
                peerHostField.getText().trim(),
                Path.of(trackerRecordsDirField.getText().trim()),
                Path.of(downloadsDirField.getText().trim())
        );
    }

    private void setConnectionState(boolean connected,
                                    Button connectTrackerButton,
                                    Button checkTrackerButton,
                                    Button disconnectTrackerButton,
                                    TextField trackerHostField,
                                    TextField trackerPortField,
                                    TextField peerPortField,
                                    TextField peerIdField,
                                    TextField peerHostField,
                                    TextField trackerRecordsDirField,
                                    TextField downloadsDirField,
                                    Button chooseTrackerRecordsDirButton,
                                    Button chooseDownloadsDirButton,
                                    Button shareFileButton,
                                    Button searchButton,
                                    Button downloadButton,
                                    Button refreshHistoryButton,
                                    Button refreshTrackerRecordsButton,
                                    Button openDownloadPathButton,
                                    ListView<SearchResult> searchListView,
                                    ProgressBar progressBar,
                                    Label statusLabel,
                                    Label readinessPill) {
        transferBusy = false;
        connectTrackerButton.setDisable(connected);
        checkTrackerButton.setDisable(connected);
        disconnectTrackerButton.setDisable(!connected);
        trackerHostField.setDisable(connected);
        trackerPortField.setDisable(connected);
        peerPortField.setDisable(connected);
        peerIdField.setDisable(connected);
        peerHostField.setDisable(connected);
        trackerRecordsDirField.setDisable(connected);
        downloadsDirField.setDisable(connected);
        chooseTrackerRecordsDirButton.setDisable(connected);
        chooseDownloadsDirButton.setDisable(connected);
        shareFileButton.setDisable(!connected);
        searchButton.setDisable(!connected);
        downloadButton.setDisable(!connected || searchListView.getSelectionModel().getSelectedItem() == null || peerNode == null);
        refreshHistoryButton.setDisable(!connected);
        refreshTrackerRecordsButton.setDisable(!connected);
        if (!connected) {
            openDownloadPathButton.setDisable(true);
        }
        progressBar.setProgress(0);
        statusLabel.setText(connected ? "Connected to tracker" : "Disconnected from tracker");
        readinessPill.setText(localTrackerRunning ? (connected ? "Connected to Tracker" : "Tracker Healthy") : "Tracker Disconnected");
        readinessPill.setStyle(localTrackerRunning ? pillStyle("#cdeccf", "#0d5d31") : pillStyle("#fde68a", "#7c5a00"));
    }

    private void setTransferState(boolean enabled,
                                  Button shareFileButton,
                                  Button searchButton,
                                  Button downloadButton,
                                  Button refreshHistoryButton,
                                  ListView<SearchResult> searchListView) {
        transferBusy = !enabled;
        shareFileButton.setDisable(!enabled);
        searchButton.setDisable(!enabled);
        refreshHistoryButton.setDisable(!enabled);
        downloadButton.setDisable(!enabled || peerNode == null || searchListView.getSelectionModel().getSelectedItem() == null);
    }

    private void showDownloadNotification(String filename, Task<Path> task) {
        ProgressIndicator indicator = new ProgressIndicator(0);
        indicator.setPrefSize(40, 40);
        indicator.setStyle("-fx-progress-color: #1f7a5c;");
        indicator.progressProperty().bind(task.progressProperty());

        String displayName = filename.length() > 26 ? filename.substring(0, 23) + "…" : filename;
        Label nameLabel = new Label(displayName);
        nameLabel.setStyle("-fx-font-size: 13px; -fx-font-weight: 700; -fx-text-fill: #163048;");

        Label msgLabel = new Label("Starting download…");
        msgLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #607282;");
        msgLabel.textProperty().bind(task.messageProperty());

        Button closeBtn = new Button("✕");
        closeBtn.setStyle("""
                -fx-background-color: transparent;
                -fx-text-fill: #8899aa;
                -fx-font-size: 13px;
                -fx-cursor: hand;
                -fx-padding: 2 6 2 6;
                """);

        VBox textBox = new VBox(3, nameLabel, msgLabel);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox card = new HBox(12, indicator, textBox, spacer, closeBtn);
        card.setPadding(new Insets(14));
        card.setPrefWidth(310);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setStyle("""
                -fx-background-color: white;
                -fx-background-radius: 14;
                -fx-border-radius: 14;
                -fx-border-color: rgba(166,185,200,0.5);
                -fx-effect: dropshadow(gaussian, rgba(32,56,77,0.18), 20, 0.2, 0, 6);
                """);

        closeBtn.setOnAction(e -> notificationPane.getChildren().remove(card));

        task.stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                indicator.progressProperty().unbind();
                msgLabel.textProperty().unbind();
                indicator.setProgress(1.0);
                nameLabel.setText("✅ " + displayName);
                msgLabel.setText("Download complete");
                msgLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #0e5d35; -fx-font-weight: 700;");
                card.setStyle("""
                        -fx-background-color: #f0faf3;
                        -fx-background-radius: 14;
                        -fx-border-radius: 14;
                        -fx-border-color: rgba(14,93,53,0.3);
                        -fx-effect: dropshadow(gaussian, rgba(32,56,77,0.18), 20, 0.2, 0, 6);
                        """);
            } else if (newState == Worker.State.FAILED) {
                indicator.progressProperty().unbind();
                msgLabel.textProperty().unbind();
                nameLabel.setText("❌ " + displayName);
                msgLabel.setText("Download failed");
                msgLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #8b2e1f; -fx-font-weight: 700;");
                card.setStyle("""
                        -fx-background-color: #fff5f5;
                        -fx-background-radius: 14;
                        -fx-border-radius: 14;
                        -fx-border-color: rgba(139,46,31,0.3);
                        -fx-effect: dropshadow(gaussian, rgba(32,56,77,0.18), 20, 0.2, 0, 6);
                        """);
            }
        });

        if (notificationPane.getChildren().size() >= 2) {
            notificationPane.getChildren().remove(0);
        }
        notificationPane.getChildren().add(card);
    }

    private static String suggestPeerHost() {
        try {
            return PeerNode.suggestAdvertisedHost();
        } catch (IOException exception) {
            return "127.0.0.1";
        }
    }

    private static String humanReadableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return SIZE_FORMAT.format(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return SIZE_FORMAT.format(mb) + " MB";
        }
        return SIZE_FORMAT.format(mb / 1024.0) + " GB";
    }

    private record ConnectionConfig(String trackerHost,
                                    int trackerPort,
                                    int peerPort,
                                    String peerId,
                                    String peerHost,
                                    Path trackerRecordsDir,
                                    Path downloadsDir) {
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static String shortId(String value) {
        return value == null ? "" : value.substring(0, Math.min(8, value.length()));
    }

    private static final class SearchResultCell extends ListCell<SearchResult> {
        @Override
        protected void updateItem(SearchResult item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label filename = new Label(item.filename());
            filename.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #12283c;");
            String encryptedSuffix = item.encrypted() ? " • encrypted" : "";
            Label details = new Label(humanReadableSize(item.size()) + " • " + item.chunkCount() + " chunks • " + item.peers().size() + " peer(s)" + encryptedSuffix);
            details.setStyle("-fx-font-size: 12px; -fx-text-fill: #617486;");
            Label idLabel = new Label("ID: " + shortId(item.fileId()));
            idLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #35556c;");
            Label actionHint = new Label("Select and click Download, or double-click here.");
            actionHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #1f7a5c;");

            VBox content = new VBox(4, filename, details, idLabel, actionHint);
            content.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(content);
        }
    }

    private static final class DownloadHistoryCell extends ListCell<DownloadHistoryEntry> {
        @Override
        protected void updateItem(DownloadHistoryEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label filename = new Label(item.filename());
            filename.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #12283c;");
            Label details = new Label(item.status() + " • " + item.createdAt());
            details.setStyle("-fx-font-size: 12px; -fx-text-fill: #617486;");
            Label idLabel = new Label("ID: " + shortId(item.fileId()));
            idLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #35556c;");
            Label path = new Label(item.destinationPath());
            path.setWrapText(true);
            path.setStyle("-fx-font-size: 11px; -fx-text-fill: #35556c;");

            VBox content = new VBox(4, filename, details, idLabel, path);
            content.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(content);
        }
    }

    private static final class TrackerRecordCell extends ListCell<TrackerRecord> {
        @Override
        protected void updateItem(TrackerRecord item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            Label filename = new Label(item.filename());
            filename.setStyle("-fx-font-size: 14px; -fx-font-weight: 800; -fx-text-fill: #12283c;");
            Label details = new Label(humanReadableSize(item.size()) + " • " + item.chunkCount() + " chunks • " +
                    item.peers().size() + " peer(s)" + (item.encrypted() ? " • encrypted" : ""));
            details.setStyle("-fx-font-size: 12px; -fx-text-fill: #617486;");
            Label idLabel = new Label("ID: " + shortId(item.fileId()));
            idLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #35556c;");
            Label chunks = new Label(item.chunkRecords().isEmpty()
                    ? "No chunk records"
                    : "Chunk records: " + item.chunkRecords().get(0) +
                    (item.chunkRecords().size() > 1 ? " ... +" + (item.chunkRecords().size() - 1) : ""));
            chunks.setWrapText(true);
            chunks.setStyle("-fx-font-size: 11px; -fx-text-fill: #35556c;");

            VBox content = new VBox(4, filename, details, idLabel, chunks);
            content.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(content);
        }
    }
}
