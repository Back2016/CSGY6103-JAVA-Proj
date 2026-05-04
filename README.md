# CSGY6103 Java Final Project

## Team Project Overview

We built a peer-to-peer file sharing system in Java for the CS6103 final project. The goal of the project was to go beyond a simple CRUD application and combine multiple advanced Java topics into one working system. Our app lets peers connect to a tracker, register files, search for files shared by other peers, and download those files in parallel chunk-by-chunk.

This repository contains the tracker server, the peer networking logic, the JavaFX desktop client, automated tests, and packaging scripts for local demos.

## Why This Project Fits The Course Requirements

This project was designed to satisfy the final project requirement of using at least 3 advanced concepts. Our implementation includes all of the following:

- JavaFX GUI for the desktop client interface
- networking with `Socket` and `ServerSocket`
- multithreading with `ExecutorService` for concurrent chunk serving and downloading
- persistent tracker metadata with SQLite
- per-session CSV history and tracker record exports
- chunk-based file transfer logic
- tracker/peer coordination across multiple machines on a LAN or VPN
- optional per-file password encryption for secure sharing

From a course perspective, the biggest advanced concepts in this project are:

- GUI development
- multithreading and concurrency
- network communication between distributed peers
- persistent data management

## Core Features

- tracker health check, connect, disconnect, start local tracker, and stop local tracker
- peer registration and file search through a central tracker
- direct peer-to-peer chunk transfer
- concurrent multi-chunk downloads
- retry logic when one peer is unavailable
- LAN and VPN-friendly peer addressing using explicit `Peer Host / LAN IP`
- per-file optional password encryption
- per-tracker-session `tracker_records.csv` and `download_history.csv`
- activity log and download history inside the GUI
- open downloaded file folder directly from the app

## Tech Stack

- Java 17+
- Maven 3.9+
- JavaFX
- SQLite JDBC
- Java sockets
- Java concurrency utilities
- CSV session exports

## Repository Layout

- `src/main/java/edu/nyu/cs6103/p2p/ui`
  JavaFX desktop application
- `src/main/java/edu/nyu/cs6103/p2p/tracker`
  tracker server and tracker main entry point
- `src/main/java/edu/nyu/cs6103/p2p/peer`
  peer networking, registration, sharing, and download logic
- `src/main/java/edu/nyu/cs6103/p2p/db`
  tracker persistence and session history helpers
- `src/main/java/edu/nyu/cs6103/p2p/model`
  data models for search results, tracker records, peers, and history
- `src/test/java/edu/nyu/cs6103/p2p`
  integration tests
- `scripts`
  packaging helpers
- `packaging`
  portable launcher assets

## Setup

Install and verify:

```bash
java -version
mvn -version
```

Recommended versions:

- Java 17 or newer
- Maven 3.9 or newer

Clone and build:

```bash
git clone https://github.com/Back2016/CSGY6103-JAVA-Proj.git
cd CSGY6103-JAVA-Proj
mvn clean compile
```

Run tests:

```bash
mvn test
```

## How To Run

### Option 1: Start the tracker from terminal

```bash
mvn exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain
```

Default tracker port:

- `5050`

Custom tracker port:

```bash
mvn exec:java -Dexec.mainClass=edu.nyu.cs6103.p2p.tracker.TrackerServerMain -Dexec.args="5051"
```

### Option 2: Start the tracker from the GUI

Launch the app:

```bash
mvn javafx:run
```

In the GUI:

1. Enter `Tracker Host`
2. Enter `Tracker Port`
3. Enter `Peer Host / LAN IP`
4. Enter `Peer Port`
5. Click `Check Tracker`
6. If no tracker is running, click `Start Tracker Here`
7. Click `Connect to Tracker`

To open a second peer window, run:

```bash
mvn javafx:run
```

Use different peer ports on the same machine:

- Peer A: `6060`
- Peer B: `6061`
- Peer C: `6062`

## Basic Demo Flow

1. Start the tracker, either from terminal or from one app window.
2. Launch Peer A and connect to the tracker.
3. Launch Peer B and connect to the same tracker.
4. On Peer A, click `Share a File`.
5. When prompted, leave the password blank for a normal file, or enter a password to encrypt that file.
6. On Peer B, search for the filename.
7. Select the file and download it.
8. If the file is encrypted, enter the correct password when prompted.
9. Confirm that the download appears in `Download History`.

## Important Configuration Notes

- `Tracker Host` and `Tracker Port` tell the peer which tracker server to connect to.
- `Peer Host / LAN IP` is the address other peers use to reach this machine.
- `Peer Port` is the upload/listening port for this peer.
- `Peer ID` is mainly used as metadata and log identity.
- If multiple peers are running on one machine, each must use a different `Peer Port`.

## LAN Usage

For a normal LAN test:

- one computer runs the tracker
- all peers point to that machine using `Tracker Host`
- each peer fills `Peer Host / LAN IP` with its own reachable LAN address

Example:

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

Find your LAN IP on macOS:

```bash
ipconfig getifaddr en0
```

If needed:

```bash
ipconfig getifaddr en1
```

## VPN Usage

The project can also work over VPN if the VPN allows peer-to-peer traffic between clients.

Rules:

- `Tracker Host` should be the tracker machine's VPN IP
- `Peer Host / LAN IP` should be this machine's own VPN IP
- tracker port and peer port must be reachable through the VPN

If the VPN blocks client-to-client traffic, tracker search may work while peer download still fails.

## File Encryption

Password protection is now per file.

How it works:

- when sharing a file, the app prompts for a password
- if left blank, that file is shared normally
- if a password is entered, only that file is shared in encrypted form
- another file can use a different password or no password at all

On download:

- if the selected file is encrypted, the app prompts for the password
- after downloading the encrypted chunks, the peer decrypts the file locally

Note:

- the tracker does not store the password
- encrypted shared temp files are stored only inside the current tracker session folder

## Tracker Sessions And Local Data

The app now stores tracker-related artifacts by tracker session instead of using one shared runtime bucket.

By default:

- downloads go to `~/P2PFileSharing/downloads`
- tracker database goes to `~/P2PFileSharing/tracker/tracker.db`
- session artifacts go to `~/P2PFileSharing/trackerRecords`

Each tracker startup creates a new session id. When a peer connects, it creates a folder inside `trackerRecords` for that session.

Each session folder contains:

- `tracker_records.csv`
- `download_history.csv`
- encrypted temporary shared copies when encryption is used

This means:

- tracker records are per tracker session
- download history is also per tracker session
- restarting the tracker starts a fresh session with fresh CSV files

## Tracker Records

The GUI includes a `Tracker Records` section that shows:

- filename
- size
- original source path
- chunk size and chunk count
- whether the file is encrypted
- which peers are currently advertising the file
- chunk record summary

The same data is exported to `tracker_records.csv` for the current session.

## Download History

The GUI includes a per-session `Download History` view.

For each entry, the app records:

- timestamp
- filename
- source peers
- destination path
- status

The `Open Download Folder` action opens the containing folder for the selected download entry.

## Tracker Lifecycle Behavior

- `Start Tracker Here` starts a tracker in the current app
- `Stop Tracker Here` stops the local tracker
- every tracker restart clears old shared-file registrations
- `Disconnect Tracker` unregisters the current peer from the tracker and clears its local shared list

This helps avoid stale file advertisements between test sessions.

## Testing

The automated test suite covers:

- tracker registration, search, and end-to-end download
- retrying another peer when one peer endpoint is unavailable
- chunk distribution across multiple peers
- tracker startup clearing old share records

Run:

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

- If a download fails with `Connection refused`, check that the uploader's `Peer Host / LAN IP` and `Peer Port` are correct.
- If a download hangs on `Preparing chunks`, the downloader can see the tracker but cannot reach the uploader peer.
- If two peers run on the same machine, make sure they use different peer ports.
- If you are using WSL2, be careful: a Java process inside WSL2 may not be reachable from another computer even if tracker registration succeeds.
- If a file is encrypted and download fails after transfer, verify that the password entered for that specific file is correct.
- If macOS blocks a `.command` launcher, right-click and choose `Open`.

## Development Notes

We approached this project like a student team building a small distributed system in stages.

Our implementation path was roughly:

- first build tracker registration and search
- then add direct peer-to-peer chunk downloads
- then add multithreaded downloading and retry behavior
- then add local persistence and session exports
- then improve the GUI for actual demos on multiple machines
- then add encryption, diagnostics, and tracker lifecycle controls

During development we spent most of our time on:

- peer addressing across LAN, VPN, Windows, macOS, and WSL2
- making tracker state reset cleanly between demo sessions
- avoiding stale peer records
- making failure messages understandable enough for classroom demos

## Development And Tech Stack Summary

To align with the project description, our team intentionally combined multiple advanced Java areas into one deliverable:

- GUI and event-driven programming with JavaFX
- concurrent programming with background tasks and thread pools
- network programming with sockets for tracker-peer and peer-peer communication
- persistence using SQLite for tracker metadata
- structured CSV export for per-session recordkeeping
- file chunking and reconstruction logic
- optional cryptography for password-protected sharing

We believe this project goes beyond a simple CRUD system because it requires coordination between UI, networking, concurrency, persistence, and distributed peer behavior. That combination is what made it a good fit for the final project requirements.
