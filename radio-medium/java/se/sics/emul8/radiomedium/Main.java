package se.sics.emul8.radiomedium;

import se.sics.emul8.radiomedium.net.Server;

public class Main {
    private static final int DEFAULT_PORT = 7711;

    public static void main(String[] args) throws InterruptedException {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback.xml");
        }

        Server server = new Server(DEFAULT_PORT);
        server.start();

        for (;;) {
            Thread.sleep(1000);
        }
    }

}