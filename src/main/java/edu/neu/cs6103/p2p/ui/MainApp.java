package edu.neu.cs6103.p2p.ui;

import edu.neu.cs6103.p2p.common.AppConfig;
import edu.neu.cs6103.p2p.db.TrackerDatabase;
import edu.neu.cs6103.p2p.model.SearchResult;
import edu.neu.cs6103.p2p.peer.PeerNode;
import edu.neu.cs6103.p2p.tracker.TrackerServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Separator;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.List;

public class MainApp extends Application {
    private static final DecimalFormat SIZE_FORMAT = new DecimalFormat("0.0");
    private static final Path APP_HOME = Path.of(System.getProperty("user.home"), "P2PFileSharing");
    private static final Path DEFAULT_SHARED_DIR = APP_HOME.resolve("shared");
    private static final Path DEFAULT_DOWNLOADS_DIR = APP_HOME.resolve("downloads");
    private static final Path DEFAULT_TRACKER_DB = APP_HOME.resolve("tracker").resolve("tracker.db");

    private final ObservableList<String> sharedFiles = FXCollections.observableArrayList();
    private final ObservableList<SearchResult> searchResults = FXCollections.observableArrayList();
    private final ObservableList<String> historyEntries = FXCollections.observableArrayList();

    private PeerNode peerNode;
    private volatile boolean localTrackerRunning;
    private volatile boolean trackerConnected;

    @Override
    public void start(Stage stage) {
        TextField trackerHostField = styledField("localhost");
        TextField trackerPortField = styledField(String.valueOf(AppConfig.DEFAULT_TRACKER_PORT));
        TextField peerPortField = styledField(String.valueOf(AppConfig.DEFAULT_PEER_PORT));
        TextField peerIdField = styledField(PeerNode.generatePeerId());
        TextField peerHostField = styledField(suggestPeerHost());
        TextField sharedDirField = styledField(DEFAULT_SHARED_DIR.toAbsolutePath().toString());
        TextField downloadsDirField = styledField(DEFAULT_DOWNLOADS_DIR.toAbsolutePath().toString());
        TextField searchField = styledField("");
        searchField.setPromptText("Search by full filename or partial filename");

        Button checkTrackerButton = secondaryButton("Check Tracker");
        Button startLocalTrackerButton = secondaryButton("Start Tracker Here");
        Button connectTrackerButton = primaryButton("Connect to Tracker");
        Button disconnectTrackerButton = secondaryButton("Disconnect Tracker");
        Button chooseSharedDirButton = secondaryButton("Folder");
        Button chooseDownloadsDirButton = secondaryButton("Folder");
        Button shareFileButton = primaryButton("Share a File");
        Button searchButton = primaryButton("Search");
        Button downloadButton = primaryButton("Download Selected");
        Button refreshHistoryButton = secondaryButton("Refresh History");

        shareFileButton.setDisable(true);
        searchButton.setDisable(true);
        downloadButton.setDisable(true);
        refreshHistoryButton.setDisable(true);
        disconnectTrackerButton.setDisable(true);

        Label appTitle = new Label("Peer-to-Peer File Sharing");
        appTitle.setStyle("-fx-font-size: 28px; -fx-font-weight: 800; -fx-text-fill: #132238;");
        Label subtitle = new Label("Choose a tracker, connect to it, then share files or download from other peers.");
        subtitle.setStyle("-fx-font-size: 14px; -fx-text-fill: #5c6b7a;");

        Label readinessPill = new Label("Tracker Disconnected");
        readinessPill.setStyle(pillStyle("#fde68a", "#7c5a00"));

        Label setupHint = new Label("1. Check Tracker  2. Connect  3. Share File  4. Search  5. Download");
        setupHint.setStyle("-fx-text-fill: #38556f; -fx-font-size: 13px; -fx-font-weight: 600;");

        ListView<String> sharedListView = new ListView<>(sharedFiles);
        sharedListView.setPlaceholder(new Label("No files shared yet."));
        sharedListView.setPrefHeight(180);

        ListView<SearchResult> searchListView = new ListView<>(searchResults);
        searchListView.setPlaceholder(new Label("Search results will appear here."));
        searchListView.setCellFactory(list -> new SearchResultCell());

        ListView<String> historyListView = new ListView<>(historyEntries);
        historyListView.setPlaceholder(new Label("No downloads yet."));

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

        chooseSharedDirButton.setOnAction(event -> chooseDirectory(stage, sharedDirField));
        chooseDownloadsDirButton.setOnAction(event -> chooseDirectory(stage, downloadsDirField));

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
            } catch (Exception exception) {
                appendLog(logArea, "Could not prepare tracker storage: " + exception.getMessage());
                statusLabel.setText("Tracker start failed");
                return;
            }

            Task<Void> trackerTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    TrackerDatabase database = new TrackerDatabase("jdbc:sqlite:" + DEFAULT_TRACKER_DB.toAbsolutePath());
                    TrackerServer trackerServer = new TrackerServer(Integer.parseInt(trackerPortField.getText().trim()), database);
                    localTrackerRunning = true;
                    trackerServer.start();
                    return null;
                }
            };

            trackerTask.setOnRunning(evt -> {
                startLocalTrackerButton.setDisable(true);
                readinessPill.setText("Starting Tracker");
                readinessPill.setStyle(pillStyle("#dbeafe", "#1d4f91"));
                statusLabel.setText("Starting local tracker...");
                appendLog(logArea, "Starting local tracker on port " + trackerPortField.getText().trim());
            });
            trackerTask.setOnFailed(evt -> {
                localTrackerRunning = false;
                startLocalTrackerButton.setDisable(false);
                readinessPill.setText("Tracker Failed");
                readinessPill.setStyle(pillStyle("#ffd8d2", "#8b2e1f"));
                appendLog(logArea, "Local tracker failed: " + trackerTask.getException().getMessage());
                statusLabel.setText("Local tracker failed");
            });
            runTask(trackerTask);

            Platform.runLater(() -> {
                localTrackerRunning = true;
                readinessPill.setText("Tracker Healthy");
                readinessPill.setStyle(pillStyle("#cdeccf", "#0d5d31"));
                statusLabel.setText("Local tracker is running on this machine");
                appendLog(logArea, "Local tracker is ready on localhost:" + trackerPortField.getText().trim());
            });
        });

        connectTrackerButton.setOnAction(event -> {
            String host = trackerHostField.getText().trim();
            int port = Integer.parseInt(trackerPortField.getText().trim());

            Task<PeerNode> connectTask = new Task<>() {
                @Override
                protected PeerNode call() throws Exception {
                    if (!pingTracker(host, port)) {
                        throw new IOException("No healthy tracker found on " + host + ":" + port);
                    }
                    PeerNode node = new PeerNode(
                            peerIdField.getText().trim(),
                            host,
                            port,
                            Integer.parseInt(peerPortField.getText().trim()),
                            peerHostField.getText().trim(),
                            Path.of(sharedDirField.getText().trim()),
                            Path.of(downloadsDirField.getText().trim())
                    );
                    node.startServer();
                    return node;
                }
            };

            connectTask.setOnRunning(evt -> {
                connectTrackerButton.setDisable(true);
                checkTrackerButton.setDisable(true);
                statusLabel.setText("Connecting to tracker and starting peer service...");
            });
            connectTask.setOnSucceeded(evt -> {
                peerNode = connectTask.getValue();
                trackerConnected = true;

                disconnectTrackerButton.setDisable(false);
                trackerHostField.setDisable(true);
                trackerPortField.setDisable(true);
                peerPortField.setDisable(true);
                peerIdField.setDisable(true);
                peerHostField.setDisable(true);
                sharedDirField.setDisable(true);
                downloadsDirField.setDisable(true);
                chooseSharedDirButton.setDisable(true);
                chooseDownloadsDirButton.setDisable(true);
                shareFileButton.setDisable(false);
                searchButton.setDisable(false);
                refreshHistoryButton.setDisable(false);

                readinessPill.setText("Connected to Tracker");
                readinessPill.setStyle(pillStyle("#c7f2d8", "#0e5d35"));
                statusLabel.setText("Connected. This peer can now share and download.");

                appendLog(logArea, "Connected to tracker " + host + ":" + port);
                appendLog(logArea, "Peer ready: " + peerIdField.getText().trim() + " on " + peerHostField.getText().trim() + ":" + peerPortField.getText().trim());
                refreshHistory();
            });
            connectTask.setOnFailed(evt -> {
                trackerConnected = false;
                connectTrackerButton.setDisable(false);
                checkTrackerButton.setDisable(false);
                disconnectTrackerButton.setDisable(true);
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

            peerNode.stopServer();
            peerNode = null;
            trackerConnected = false;
            sharedFiles.clear();
            searchResults.clear();

            connectTrackerButton.setDisable(false);
            checkTrackerButton.setDisable(false);
            disconnectTrackerButton.setDisable(true);
            trackerHostField.setDisable(false);
            trackerPortField.setDisable(false);
            peerPortField.setDisable(false);
            peerIdField.setDisable(false);
            peerHostField.setDisable(false);
            sharedDirField.setDisable(false);
            downloadsDirField.setDisable(false);
            chooseSharedDirButton.setDisable(false);
            chooseDownloadsDirButton.setDisable(false);
            shareFileButton.setDisable(true);
            searchButton.setDisable(true);
            downloadButton.setDisable(true);
            refreshHistoryButton.setDisable(true);
            progressBar.setProgress(0);
            statusLabel.setText("Disconnected from tracker");
            readinessPill.setText(localTrackerRunning ? "Tracker Healthy" : "Tracker Disconnected");
            readinessPill.setStyle(localTrackerRunning ? pillStyle("#cdeccf", "#0d5d31") : pillStyle("#fde68a", "#7c5a00"));
            appendLog(logArea, "Disconnected from tracker. You can switch to a different tracker now.");
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
            Task<Void> shareTask = new Task<>() {
                @Override
                protected Void call() throws Exception {
                    peerNode.shareFile(selectedFile.toPath());
                    return null;
                }
            };
            shareTask.setOnSucceeded(evt -> {
                sharedFiles.setAll(peerNode.getSharedFileNames());
                appendLog(logArea, "Registered shared file: " + selectedFile.getName());
                statusLabel.setText("Shared " + selectedFile.getName());
            });
            shareTask.setOnFailed(evt -> {
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

        searchListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) ->
                downloadButton.setDisable(selected == null || peerNode == null));
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

            shareFileButton.setDisable(true);
            searchButton.setDisable(true);
            downloadButton.setDisable(true);
            refreshHistoryButton.setDisable(true);

            Task<Path> downloadTask = new Task<>() {
                @Override
                protected Path call() throws Exception {
                    updateProgress(0, 1);
                    updateMessage("Starting download");
                    return peerNode.download(
                            selected,
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
                shareFileButton.setDisable(false);
                searchButton.setDisable(false);
                refreshHistoryButton.setDisable(false);
                downloadButton.setDisable(searchListView.getSelectionModel().getSelectedItem() == null);
            });
            downloadTask.setOnFailed(evt -> {
                progressBar.progressProperty().unbind();
                statusLabel.textProperty().unbind();
                progressBar.setProgress(0);
                statusLabel.setText("Download failed");
                appendLog(logArea, "Download failed: " + downloadTask.getException().getMessage());
                refreshHistory();
                shareFileButton.setDisable(false);
                searchButton.setDisable(false);
                refreshHistoryButton.setDisable(false);
                downloadButton.setDisable(searchListView.getSelectionModel().getSelectedItem() == null);
            });
            runTask(downloadTask);
        });

        refreshHistoryButton.setOnAction(event -> refreshHistory());

        GridPane configPane = new GridPane();
        configPane.setHgap(12);
        configPane.setVgap(12);
        configPane.addRow(0, fieldStack("Tracker Host", "Computer running the tracker", trackerHostField),
                fieldStack("Tracker Port", "Default 5050", trackerPortField));
        configPane.addRow(1, fieldStack("Peer ID", "Auto-generated is fine", peerIdField),
                fieldStack("Peer Port", "This peer's upload port", peerPortField));
        configPane.add(fieldStack("Peer Host / LAN IP", "Other computers must be able to reach this address", peerHostField), 0, 2, 2, 1);
        configPane.add(fieldStackWithButton("Shared Folder", "Where your shared files live", sharedDirField, chooseSharedDirButton), 0, 3, 2, 1);
        configPane.add(fieldStackWithButton("Downloads Folder", "Where downloads and peer history are stored", downloadsDirField, chooseDownloadsDirButton), 0, 4, 2, 1);

        HBox trackerActions = new HBox(10, checkTrackerButton, startLocalTrackerButton);
        HBox.setHgrow(checkTrackerButton, Priority.ALWAYS);
        HBox.setHgrow(startLocalTrackerButton, Priority.ALWAYS);
        checkTrackerButton.setMaxWidth(Double.MAX_VALUE);
        startLocalTrackerButton.setMaxWidth(Double.MAX_VALUE);

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
                trackerActions,
                connectionActions
        );
        styleCardTitle((Label) setupCard.getChildren().get(0));

        VBox shareCard = card(
                titledLabel("Step 2: Share Files"),
                helperLabel("Choose any local file to make it available to other peers after the tracker connection is active."),
                shareFileButton,
                sectionLabel("Shared Files"),
                sharedListView
        );

        HBox searchControls = new HBox(10, searchField, searchButton, downloadButton);
        searchControls.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(searchField, Priority.ALWAYS);

        VBox searchCard = card(
                titledLabel("Step 3: Search and Download"),
                helperLabel("Search by filename, select a result, or double-click it to download."),
                searchControls,
                searchListView
        );
        VBox.setVgrow(searchListView, Priority.ALWAYS);

        VBox transferCard = card(
                titledLabel("Transfer Status"),
                progressBar,
                statusLabel
        );

        HBox historyHeader = new HBox(10, titledLabel("Download History"), spacer(), refreshHistoryButton);
        historyHeader.setAlignment(Pos.CENTER_LEFT);

        VBox historyCard = card(
                historyHeader,
                helperLabel("Recent completed or failed downloads for this peer."),
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

        VBox rightColumn = new VBox(16, historyCard);
        rightColumn.setPrefWidth(390);
        VBox.setVgrow(historyCard, Priority.ALWAYS);

        HBox header = new HBox(16, new VBox(6, appTitle, subtitle), spacer(), readinessPill);
        header.setAlignment(Pos.CENTER_LEFT);

        HBox content = new HBox(18, leftColumn, centerColumn, rightColumn);
        HBox.setHgrow(centerColumn, Priority.ALWAYS);
        HBox.setHgrow(rightColumn, Priority.ALWAYS);

        VBox rootContent = new VBox(18, header, new Separator(), content);
        rootContent.setPadding(new Insets(22));
        rootContent.setStyle("""
                -fx-background-color:
                    linear-gradient(from 0% 0% to 100% 100%, #f7f3e8 0%, #eef5f3 45%, #f7fbff 100%);
                -fx-font-family: 'Aptos', 'Segoe UI', sans-serif;
                """);

        BorderPane root = new BorderPane(rootContent);

        Scene scene = new Scene(root, 1540, 900);
        stage.setMinWidth(1260);
        stage.setMinHeight(760);
        stage.setTitle("Peer-to-Peer File Sharing System");
        stage.setScene(scene);
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

    private VBox fieldStackWithButton(String title, String hint, TextField field, Button button) {
        HBox row = new HBox(10, field, button);
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
        historyEntries.setAll(peerNode.getDownloadHistory().stream().map(Object::toString).toList());
    }

    private void runTask(Task<?> task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private static boolean pingTracker(String host, int port) throws IOException {
        try (Socket socket = new Socket(host, port);
             DataOutputStream output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
             DataInputStream input = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
            output.writeUTF("PING");
            output.flush();
            boolean success = input.readBoolean();
            if (!success) {
                return false;
            }
            return "PONG".equals(input.readUTF());
        }
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

    public static void main(String[] args) {
        launch(args);
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
            Label details = new Label(humanReadableSize(item.size()) + " • " + item.chunkCount() + " chunks • " + item.peers().size() + " peer(s)");
            details.setStyle("-fx-font-size: 12px; -fx-text-fill: #617486;");
            Label actionHint = new Label("Select and click Download, or double-click here.");
            actionHint.setStyle("-fx-font-size: 11px; -fx-text-fill: #1f7a5c;");

            VBox content = new VBox(4, filename, details, actionHint);
            content.setPadding(new Insets(8, 4, 8, 4));
            setGraphic(content);
        }
    }
}
