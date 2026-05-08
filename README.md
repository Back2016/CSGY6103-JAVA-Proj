# P2P File Sharing System

This project is a Java peer-to-peer file sharing system with a central tracker, a JavaFX desktop client, parallel chunk downloads, optional per-file encryption, and session-based tracker records.

The codebase is organized around a simple workflow:

1. A peer connects to a tracker and opens a session.
2. The peer shares a local file with the tracker.
3. Other peers search the tracker and download the file directly from peers.
4. Downloads are verified by file hash before being accepted.
5. Disconnecting a peer clears its shared registrations from the tracker.

## What The App Does

- Check tracker health, connect to a tracker, disconnect, and run a local tracker from the GUI.
- Share files by content hash instead of by filename alone.
- Keep same-name files with different content separate.
- Support optional per-file password encryption when sharing.
- Search files through the tracker and download them from other peers.
- Download files in parallel chunk-by-chunk.
- Retry other peers automatically when one peer is unavailable.
- Verify downloaded content by SHA-256 hash before marking the download complete.
- Export tracker records and local download history to CSV files.
- Open the containing folder for a selected download entry from the GUI.

## Key Behavior

- `fileId` is the SHA-256 hash of the actual shared payload.
- Encrypted shares are hashed after encryption, so the same plaintext with different passwords becomes a different share.
- Tracker records are session-scoped and show metadata only.
- Tracker records do not expose another user’s original absolute local path.
- `tracker_records.csv` contains file metadata, peer list, and chunk layout summary for the current session.
- `download_history.csv` is local to the downloading peer and stores the destination path on that machine.
- Peer sessions are kept alive with heartbeats and expire automatically if they stop updating.
- `Disconnect Tracker` unregisters the current peer’s shared files from the tracker and clears local shared-file state.
- `Stop Tracker Here` stops the local tracker and disconnects the current peer first to reduce stale registrations.

## How It Works

### Sharing

- The peer computes a SHA-256 hash for the file that is actually being shared.
- For encrypted sharing, the peer creates a temporary encrypted copy first.
- Only after tracker registration succeeds does the peer keep the file in its shared-file map.
- If registration fails, the temporary encrypted copy is deleted.

### Searching

- The tracker stores shared files by `fileId`, filename, size, chunk size, chunk count, encrypted flag, and session token.
- Search results are grouped by `fileId`.
- This prevents same-name files with different content from being merged into one result.

### Downloading

- Downloads are split into chunks and fetched in parallel.
- Each chunk request is validated for chunk index and returned length.
- The downloaded file is re-hashed before the app marks the transfer successful.
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
- tracker record list

What it is for:

- show what the tracker currently sees as available
- display file metadata such as filename, `fileId`, chunk count, and encryption flag
- show which peers are advertising the file
- summarize chunk layout information for the file

What it is not:

- it is not a download history
- it is not a file access log
- it does not expose the original absolute local path of another user

### 6. Download History

This card shows the local peer's download history.

It contains:

- `Open Folder`
- `Refresh`
- download history list

What it is for:

- show what this peer has downloaded locally
- record the source peers, destination path, and status
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

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- JavaFX
- SQLite JDBC

## Build And Test

Check your local tools:

```bash
java -version
mvn -version
```

Build the project:

```bash
mvn clean compile
```

Run the test suite:

```bash
mvn test
```

## How To Run

### Start the tracker from the terminal

```bash
mvn exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Default tracker port:

- `5050`

You can pass a custom port:

```bash
mvn exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain -Dexec.args="5051"
```

The terminal tracker stores its SQLite file as `tracker.db` in the current working directory.

### Start the GUI client

```bash
mvn javafx:run
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

```bash
mvn test
```

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

## Development Notes

The implementation combines several advanced Java areas in one project:

- JavaFX event-driven UI
- sockets for tracker-peer and peer-peer communication
- concurrency for parallel chunk downloading and chunk serving
- SQLite persistence for tracker metadata
- CSV exports for per-session records
- optional cryptography for per-file password protection

The code is split so that tracker logic, peer logic, UI logic, and storage helpers stay separate and easier to maintain.
