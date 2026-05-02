package edu.neu.cs6103.p2p.tracker;

import edu.neu.cs6103.p2p.common.AppConfig;
import edu.neu.cs6103.p2p.db.TrackerDatabase;

public final class TrackerServerMain {
    private TrackerServerMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : AppConfig.DEFAULT_TRACKER_PORT;
        TrackerDatabase database = new TrackerDatabase("jdbc:sqlite:tracker.db");
        TrackerServer server = new TrackerServer(port, database);
        server.start();
        System.out.println("Tracker listening on port " + port);
    }
}
