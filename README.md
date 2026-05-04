# CSGY6103 Java Project

Peer-to-peer file sharing system built in Java with a tracker server, concurrent chunk downloads, JavaFX desktop UI, and SQLite-backed metadata/history.

## What This App Does

- peers register shared files with a tracker
- peers search the tracker for available files
- peers download file chunks directly from other peers
- downloads run concurrently across multiple chunks and multiple peers
- each peer keeps local download history in SQLite

## Stack

- Java 17+
- Maven 3.9+
- JavaFX
- SQLite
- `Socket` / `ServerSocket`
- `ExecutorService`

## Repository Layout

- `src/main/java/edu/neu/cs6103/p2p/tracker`: tracker server
- `src/main/java/edu/neu/cs6103/p2p/peer`: peer networking and download logic
- `src/main/java/edu/neu/cs6103/p2p/ui`: JavaFX desktop client
- `src/main/java/edu/neu/cs6103/p2p/db`: SQLite helpers
- `scripts/`: packaging/build helpers
- `packaging/`: portable distribution launchers and install notes

## Collaborator Setup

Install and verify:

```bash
java -version
mvn -version
```

Recommended versions:

- Java 17 or newer
- Maven 3.9 or newer

Clone the repo and build:

```bash
git clone https://github.com/Back2016/CSGY6103-JAVA-Proj.git
cd CSGY6103-JAVA-Proj
mvn clean compile
```

Run tests:

```bash
mvn test
```

## How to Run

### Option 1: Run tracker separately from terminal

```bash
mvn exec:java -Dexec.mainClass=edu.neu.cs6103.p2p.tracker.TrackerServerMain
```

Default tracker port:

- `5050`

Custom tracker port:

```bash
mvn exec:java -Dexec.mainClass=edu.neu.cs6103.p2p.tracker.TrackerServerMain -Dexec.args="5051"
```

### Option 2: Start a local tracker from the GUI

Launch the app:

```bash
mvn javafx:run
```

In the UI:

1. enter tracker host and tracker port
2. enter this machine's reachable `Peer Host / LAN IP`
2. click `Check Tracker`
3. if no tracker is running, click `Start Tracker Here`
4. click `Connect to Tracker`
5. after connected, share/search/download actions become available

To launch a second peer, open another terminal and run:

```bash
mvn javafx:run
```

Use a different peer port for each local peer, for example:

- Peer A: `6060`
- Peer B: `6061`
- Peer C: `6062`

## Local Demo

1. Start the tracker, or start one from the first app window.
2. Launch Peer A and connect it to the tracker.
3. Launch Peer B and connect it to the same tracker.
4. On Peer A, share a file.
5. On Peer B, search for that file.
6. Download it and confirm progress/history updates.

Important:

- `Tracker Host` + `Tracker Port` determine which tracker a peer connects to.
- `Peer Host / LAN IP` is the address other computers use to reach this peer.
- `Peer Port` must be unique for each running peer on the same machine.
- `Peer ID` is mostly an identifier for logs/metadata, not the socket address.

## LAN Usage

For a LAN with multiple computers:

- one computer usually runs the tracker
- all peers point to that computer's LAN IP as `Tracker Host`
- each peer must set `Peer Host / LAN IP` to its own LAN IP
- each peer still uses its own local peer port

Example:

- tracker machine IP: `192.168.1.20`
- tracker port: `5050`
- peers on all machines connect to `192.168.1.20:5050`

Example configuration:

- Computer A runs the tracker and also shares files
  - `Tracker Host`: `localhost` or `192.168.1.20`
  - `Tracker Port`: `5050`
  - `Peer Host / LAN IP`: `192.168.1.20`
  - `Peer Port`: `6060`
- Computer B downloads from A
  - `Tracker Host`: `192.168.1.20`
  - `Tracker Port`: `5050`
  - `Peer Host / LAN IP`: `192.168.1.21`
  - `Peer Port`: `6060`

How to find your LAN IP on macOS:

```bash
ipconfig getifaddr en0
```

If you are using a different interface, you may need:

```bash
ipconfig getifaddr en1
```

## Runtime Data

By default the app stores local peer data under:

- `~/P2PFileSharing/shared`
- `~/P2PFileSharing/downloads`
- `~/P2PFileSharing/tracker/tracker.db`

Each peer stores its download history in its configured downloads directory as `peer-client.db`.

Downloaded files are written into the configured downloads folder. On Linux or WSL2 this is commonly:

```bash
~/P2PFileSharing/downloads
```

## Packaging

Build the macOS distribution bundle with:

```bash
./scripts/package-macos.sh
```

Output goes to `dist/macos/`.

Portable distribution:

- `P2P File Sharing Portable.zip`

That portable bundle includes:

- `P2P File Sharing.command`
- `Start Tracker.command`
- bundled GUI/tracker jars

## Troubleshooting

- If a peer stays stuck or fails downloads, check that two local peers are not using the same `Peer Port`.
- If a download stays on `Preparing chunks` for a long time in LAN testing, verify that `Peer Host / LAN IP` is the correct LAN IP of the sharing machine.
- If `Check Tracker` fails, either fix the host/port or click `Start Tracker Here`.
- If macOS blocks a `.command` launcher, right-click it and choose `Open`.

## Future Improvements

- tracker discovery on LAN
- tracker authentication/password flow
- chunk/file checksum verification
- stale peer cleanup / heartbeat
- pause/resume downloads
- richer transfer diagnostics in the UI
