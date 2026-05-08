# P2P File Sharing System

**Group Members:** Yuxin Zhu (yz11952) · Yinkui Yu (yy5612) · Kai-En Huang (kh4552)

This project is a Java peer-to-peer file sharing system with a central tracker, a JavaFX desktop client, parallel chunk downloads, optional per-file encryption, and session-based tracker records.

The codebase is organized around a simple workflow:

1. A peer connects to a tracker and opens a session.
2. The peer shares a local file with the tracker.
3. Other peers search the tracker and download the file directly from peers.
4. Downloads are verified by file hash before being accepted.
5. Disconnecting a peer clears its shared registrations from the tracker.

## What The App Does

- Check tracker health, connect to a tracker, disconnect, and run a local tracker from the GUI.
- Assign every shared file a unique `fileId` instead of relying on filename alone.
- Keep same-name files separate, even when they come from different peers.
- Support optional per-file password encryption when sharing.
- Search files through the tracker and download them from other peers.
- Download files in parallel chunk-by-chunk.
- Retry other peers automatically when one peer is unavailable.
- Verify downloaded content by SHA-256 hash before marking the download complete.
- Export tracker records and local download history to CSV files.
- Open the containing folder for a selected download entry from the GUI.
- Read older saved `tracker_records.csv` files from past tracker sessions.

## Key Behavior

- `fileId` is a unique share identifier generated per shared file.
- `contentHash` is the SHA-256 hash of the actual shared payload and is used to verify downloads.
- Encrypted shares are hashed after encryption, so the same plaintext with different passwords becomes a different payload hash.
- Tracker records are session-scoped and show metadata only.
- Tracker records do not expose another user’s original absolute local path.
- `tracker_records.csv` contains file metadata, unique share ids, content hashes, peer list, and chunk layout summary for the current session.
- `download_history.csv` is local to the downloading peer and stores the file id, source peers, destination path, and status on that machine.
- Peer sessions are kept alive with heartbeats and expire automatically if they stop updating.
- `Disconnect Tracker` unregisters the current peer’s shared files from the tracker and clears local shared-file state.
- `Stop Tracker Here` stops the local tracker and disconnects the current peer first to reduce stale registrations.

## How It Works

### Sharing

- The peer generates a unique `fileId` for each share action.
- The peer also computes a SHA-256 `contentHash` for the exact payload being shared.
- For encrypted sharing, the peer creates a temporary encrypted copy first.
- Only after tracker registration succeeds does the peer keep the file in its shared-file map.
- If registration fails, the temporary encrypted copy is deleted.

### Searching

- The tracker stores shared files by `fileId`, `contentHash`, filename, size, chunk size, chunk count, encrypted flag, and session token.
- Search results are grouped by `fileId`.
- This prevents same-name files from being overwritten or merged into one result.

### Downloading

- Downloads are split into chunks and fetched in parallel.
- Each chunk request is validated for chunk index and returned length.
- The downloaded file is re-hashed and checked against `contentHash` before the app marks the transfer successful.
- If the file is encrypted, the decrypted output is written only after the encrypted payload is verified.
- Failed downloads clean up partial files and working temporary files.

## UI Guide

The desktop app is organized into separate cards so each part of the workflow is visible and easy to follow.

### 1. Tracker Connection

This is the first card in the left column.

It contains the connection fields and tracker controls:

- `Tracker Host`
- `Tracker Port`
- `Peer ID`
- `Peer Port`
- `Peer Host / LAN IP`
- `Tracker Records Folder`
- `Downloads Folder`
- `Check Tracker`
- `Start Tracker Here`
- `Stop Tracker Here`
- `Connect to Tracker`
- `Disconnect Tracker`

How to use it:

1. Fill in the tracker host and port.
2. Choose a peer port that is not already in use.
3. Set `Peer Host / LAN IP` to the address other peers can reach on the network.
4. Click `Check Tracker` if you want to verify the tracker first.
5. If this machine should also run the tracker, click `Start Tracker Here`.
6. Click `Connect to Tracker` to open the peer session.
7. Click `Disconnect Tracker` to leave the tracker session and clear this peer's shared registrations.
8. Click `Stop Tracker Here` only if you want to stop the local tracker process itself.

### 2. Share Files

This card is used after the peer is connected to a tracker.

It contains:

- `Share a File`
- the current shared file list

How to use it:

1. Click `Share a File`.
2. Select a local file.
3. Leave the password blank to share normally, or enter a password to share an encrypted copy.
4. After sharing succeeds, the file appears in the shared file list and is registered with the tracker.

### 3. Search And Download

This card is used to find files shared by other peers and download them.

It contains:

- search input
- `Search`
- `Download Selected`
- search results list

How to use it:

1. Type part of a filename or a full filename.
2. Click `Search`.
3. Select a search result.
4. Click `Download Selected`, or double-click a result.
5. If the file is encrypted, enter the password when prompted.
6. If multiple files have the same visible filename, use the short ID shown in the UI to tell them apart.

### 4. Transfer Status

This card shows the current download or transfer state.

It contains:

- progress bar
- status label

Use it as a live indicator while a download or share operation is running.

### 5. Tracker Records

This card shows the tracker-side view of shared metadata for the current session.

It contains:

- `Refresh Records`
- `Read Past Records`
- tracker record list

What it is for:

- show what the tracker currently sees as available
- display file metadata such as filename, `fileId`, `contentHash`, chunk count, and encryption flag
- show which peers are advertising the file
- summarize chunk layout information for the file
- load an older saved `tracker_records.csv` file from a previous tracker session

What it is not:

- it is not a download history
- it is not a file access log
- it does not expose the original absolute local path of another user

How `Read Past Records` works:

1. Click `Read Past Records`.
2. Choose a previously saved `tracker_records.csv` file from the tracker records folder.
3. The app opens a dialog and lists the historical tracker records stored in that CSV.

This is separate from the live `Tracker Records` panel, which always shows the current connected tracker state.

### 6. Download History

This card shows the local peer's download history.

It contains:

- `Open Folder`
- `Refresh`
- download history list

What it is for:

- show what this peer has downloaded locally
- record the file id, source peers, destination path, and status
- open the folder containing the selected download

This history is local to the current peer. It is not shared to the tracker.

### 7. Activity Log

This card is for diagnostic output.

It shows:

- tracker health checks
- connect and disconnect messages
- share and download failures
- cleanup and retry behavior

This is the best place to look when a workflow does not behave as expected.

## Repository Layout

- `src/main/java/edu/nyu/cs6103/p2p/ui`
  JavaFX desktop application.
- `src/main/java/edu/nyu/cs6103/p2p/tracker`
  tracker server, tracker client, and tracker entry point.
- `src/main/java/edu/nyu/cs6103/p2p/peer`
  peer networking, sharing, downloading, and local cleanup logic.
- `src/main/java/edu/nyu/cs6103/p2p/db`
  tracker persistence and local CSV history helpers.
- `src/main/java/edu/nyu/cs6103/p2p/model`
  data models for peers, shared files, search results, tracker records, and history entries.
- `src/test/java/edu/nyu/cs6103/p2p`
  integration tests.
- `scripts`
  packaging helpers.
- `packaging`
  portable launcher assets.

## Submission Report

This README is also our written project report. It explains:

- what the project does
- how to build and run it
- how to test the main workflow
- which advanced topics we used
- how those advanced topics appear in our implementation

## Advanced Topics Used

Our project uses more than three advanced topics from the course. The most important ones are listed below.

### 1. Networking / Distributed Systems

How we used it:

- We built the whole application around socket-based communication.
- A central tracker accepts peer registrations, search requests, health checks, and heartbeat updates.
- Peers download file chunks directly from other peers instead of routing file data through the tracker.
- This gives the project a real distributed workflow: tracker for metadata, peers for data transfer.

Where it appears:

- `src/main/java/edu/nyu/cs6103/p2p/tracker`
- `src/main/java/edu/nyu/cs6103/p2p/peer`

### 2. Multithreading / Concurrency

How we used it:

- File downloads are split into chunks and fetched in parallel with an `ExecutorService`.
- The tracker handles multiple peer connections at the same time.
- A peer can also serve multiple upload requests concurrently while still remaining responsive in the UI.
- Background JavaFX tasks are used so the GUI does not freeze during network or file operations.

Where it appears:

- parallel chunk download logic in `PeerNode`
- tracker request handling in `TrackerServer`
- peer upload serving in `PeerServer`
- background UI work in `MainApp`

### 3. Synchronization / Thread Safety

How we used it:

- Tracker-side shared state is protected so concurrent peer requests do not corrupt metadata.
- Download progress, failure signaling, and chunk completion are coordinated safely across worker threads.
- UI state and background worker state are separated carefully to avoid race conditions.

Where it appears:

- synchronized tracker database operations in `TrackerDatabase`
- coordinated download bookkeeping in `PeerNode`

### 4. GUI Programming

How we used it:

- The user-facing desktop client is built with JavaFX.
- We implemented a multi-panel workflow for tracker connection, file sharing, searching, downloading, tracker records, and download history.
- The GUI includes progress bars, alerts, file pickers, folder-opening actions, live logs, and CSV viewing dialogs.

Where it appears:

- `src/main/java/edu/nyu/cs6103/p2p/ui/MainApp.java`

### 5. Persistence / Data Storage

How we used it:

- The tracker persists live session and shared-file metadata in SQLite.
- Each tracker session also exports tracker records and download history into CSV files.
- This lets users inspect both current runtime state and saved historical session data.

Where it appears:

- SQLite storage in `TrackerDatabase`
- CSV history/record export in `ClientDatabase` and related helpers

### 6. Security / Cryptography

How we used it:

- We support optional password-protected sharing on a per-file basis.
- If a user chooses a password while sharing, the project creates an encrypted payload for that file before it is distributed.
- The tracker stores only metadata about whether a file is encrypted. It does not store the password itself.
- Download verification also uses SHA-256 hashing to validate the payload before the app accepts the transfer.

Where it appears:

- encrypted sharing flow in `PeerNode`
- SHA-256 helpers in `HashingUtils`

## Requirements

- Java 17 or newer
- JavaFX
- SQLite JDBC

Maven is bundled via Maven Wrapper (`mvnw` / `mvnw.cmd`). No separate Maven installation is needed.

## Build And Test

Check your Java version:

```bash
java -version
```

Build the project:

Windows:
```bash
.\mvnw.cmd clean compile
```

Mac / Linux:
```bash
./mvnw clean compile
```

Run the test suite:

Windows:
```bash
.\mvnw.cmd test
```

Mac / Linux:
```bash
./mvnw test
```

## How To Run

This is the recommended end-to-end workflow for graders, teammates, or anyone testing the project for the first time.

### Start the tracker from the terminal

Windows:
```bash
.\mvnw.cmd exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Mac / Linux:
```bash
./mvnw exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Default tracker port:

- `5050`

You can pass a custom port:

Windows:
```bash
.\mvnw.cmd exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain -Dexec.args="5051"
```

Mac / Linux:
```bash
./mvnw exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain -Dexec.args="5051"
```

The terminal tracker stores its SQLite file as `tracker.db` in the current working directory.

### Start the GUI client

Windows:
```bash
.\mvnw.cmd javafx:run
```

Mac / Linux:
```bash
./mvnw javafx:run
```

In the GUI:

1. Enter `Tracker Host`.
2. Enter `Tracker Port`.
3. Enter `Peer Host / LAN IP`.
4. Enter `Peer Port`.
5. Click `Check Tracker`.
6. If needed, click `Start Tracker Here`.
7. Click `Connect to Tracker`.

To run a second peer, launch another GUI window and use a different peer port.

Example peer ports on one machine:

- `6060`
- `6061`
- `6062`

## Quick Run Instructions

If you want the shortest possible run guide, use this:

1. Open a terminal in the project root.
2. Start the tracker.
3. Launch the GUI client.
4. Launch a second GUI client if you want to test peer-to-peer transfer.
5. Connect both peers to the same tracker.
6. Share a file from one peer.
7. Search and download that file from the other peer.

Example on macOS / Linux:

Terminal 1:
```bash
./mvnw exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Terminal 2:
```bash
./mvnw javafx:run
```

Terminal 3:
```bash
./mvnw javafx:run
```

Example on Windows:

Terminal 1:
```powershell
.\mvnw.cmd exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Terminal 2:
```powershell
.\mvnw.cmd javafx:run
```

Terminal 3:
```powershell
.\mvnw.cmd javafx:run
```

## Typical Demo Flow

1. Start a tracker.
2. Launch Peer A and connect it to the tracker.
3. Launch Peer B and connect it to the same tracker.
4. On Peer A, click `Share a File`.
5. Leave the password blank to share normally, or enter a password to share the file in encrypted form.
6. On Peer B, search for the filename.
7. Select the result and download it.
8. If the file is encrypted, enter the correct password when prompted.
9. Check the `Download History` view and use `Open Download Folder` if you want to inspect the saved file.

## Local Storage

By default, the GUI stores local data under `~/P2PFileSharing`.

- `~/P2PFileSharing/downloads`
  Download destination folder.
- `~/P2PFileSharing/tracker/tracker.db`
  SQLite database for the local tracker started from the GUI.
- `~/P2PFileSharing/trackerRecords`
  Per-peer, per-session tracker record exports and CSV history.

Each peer session gets its own folder under `trackerRecords`.

The session folder can contain:

- `tracker_records.csv`
- `download_history.csv`
- encrypted temporary shared copies used during password-protected sharing

## Tracker Records

The `Tracker Records` panel shows tracker metadata for the current session.

Each record includes:

- filename
- fileId
- file size
- chunk size and chunk count
- encrypted flag
- peer list
- chunk layout summary

The `chunkRecords` field is a layout summary generated from file size and chunk settings. It is not a file access log.

The tracker record export does not include original local file paths.

## Download History

The `Download History` panel is local to the peer and records:

- timestamp
- filename
- source peers
- destination path
- status

The `Open Download Folder` action opens the folder containing the selected download.

## File Encryption

Password protection is per file.

- If you leave the password blank, the file is shared normally.
- If you enter a password, the app shares an encrypted copy of that file.
- When someone downloads an encrypted file, the app prompts for the password.
- The tracker never stores the password.

Important detail:

- the same plaintext shared with different passwords produces different `fileId` values, so the tracker keeps them separate

## Tracker Lifecycle

- `Start Tracker Here` starts a tracker inside the GUI app.
- `Stop Tracker Here` stops the local tracker.
- `Disconnect Tracker` unregisters the current peer from the tracker and clears local shared files.
- Peer sessions expire automatically if heartbeats stop arriving.

## Testing

The integration tests cover:

- registration, search, and end-to-end download
- same filename with different content
- same plaintext with different encryption
- session validation for disconnect and register
- session expiry cleanup
- zero-byte file sharing and download
- hash mismatch failures
- wrong-password cleanup for encrypted downloads
- rollback of temporary encrypted files on share failure

Run the tests with:

Windows:
```bash
.\mvnw.cmd test
```

Mac / Linux:
```bash
./mvnw test
```

## Advanced Topics Summary For Course Requirements

To clearly satisfy the project requirement of using three or more advanced topics, our project uses all of the following:

1. Networking
   The tracker and peers communicate through TCP sockets, and peers download chunks directly from each other.
2. Multithreading
   Chunk downloads and server-side request handling run concurrently using Java thread pools.
3. Synchronization
   Shared metadata and multi-threaded download state are coordinated safely across concurrent workers.
4. GUI programming
   The application provides a full JavaFX desktop interface instead of a command-line-only experience.
5. Persistence
   The system uses SQLite and CSV exports to preserve tracker metadata and session history.
6. Cryptography
   Files can be shared with per-file password protection, and payloads are verified using SHA-256 hashes.

If a grader only wants to check three, the clearest three are:

- Networking
- Multithreading
- GUI programming

But the full implementation goes beyond that minimum.

## Packaging

Build the macOS portable distribution:

```bash
./scripts/package-macos.sh
```

Output:

- `dist/macos/P2P File Sharing Portable.zip`

Bundle contents:

- `P2P File Sharing.command`
- `Start Tracker.command`
- bundled GUI and tracker jars

## Troubleshooting

- If a download fails with `Connection refused`, check the uploader peer host and peer port.
- If a download hangs while preparing chunks, the tracker is reachable but the downloader cannot reach the uploader peer.
- If two peers run on the same machine, make sure each peer uses a different peer port.
- If a file is encrypted and download fails after transfer, verify the password for that specific file.
- If macOS blocks a `.command` launcher, right-click it and choose `Open`.
- If you stop the local tracker from the GUI, the app tries to disconnect the current peer first to avoid stale registrations.

### Windows: Other devices cannot connect or download your shared files

If you are on Windows and other devices (e.g. a Mac on the same LAN) can search but cannot download files from you, follow these steps:

**Step 1 — Find your correct LAN IP**

Open PowerShell and run:

```powershell
ipconfig | findstr "IPv4"
```

If multiple IPs appear, ask the other device to ping each one from their terminal:

```bash
ping 10.x.x.x
```

The IP that gets a response is the one that other devices can reach. Enter that IP into the `Peer Host / LAN IP` field in the app before connecting.

**Step 2 — Allow Java through Windows Firewall**

Windows Firewall may block inbound connections to the peer server even if the firewall appears to be off. Open PowerShell as Administrator and run:

```powershell
netsh advfirewall firewall add rule name="Java JDK P2P" dir=in action=allow program="C:\Program Files\Java\jdk-24\bin\java.exe" enable=yes
```

If your JDK version is different, replace `jdk-24` with your actual version. To find the correct path run:

```powershell
java -XshowSettings:properties -version 2>&1 | Select-String "java.home"
```

Then replace the path in the command above with the result followed by `\bin\java.exe`.

This rule allows Java to accept inbound connections on any port, so it works regardless of which tracker port or peer port you choose.

**Step 3 — If the above steps do not work, temporarily disable Windows Firewall**

Open PowerShell as Administrator and run:

```powershell
netsh advfirewall set allprofiles state off
```

Remember to turn it back on after testing:

```powershell
netsh advfirewall set allprofiles state on
```

## Development Notes

We built this project as a student team and tried to keep the system modular enough that each part could be implemented and tested independently.

Our development approach was:

- separate tracker logic, peer logic, UI logic, models, and storage helpers
- add integration tests for real peer/tracker workflows instead of only unit-level checks
- keep the GUI practical enough for live demos and manual testing
- add session-based CSV exports so we could inspect and debug behavior across multiple test runs

In short, this project combines distributed networking, concurrent programming, GUI design, persistence, and security-related features in one end-to-end Java application.
