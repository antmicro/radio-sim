package se.sics.emul8.mspsim;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.sics.emul8.radiomedium.net.ClientConnection;
import se.sics.emul8.radiomedium.net.ClientHandler;
import se.sics.mspsim.core.USARTListener;
import se.sics.mspsim.core.USARTSource;
import se.sics.mspsim.core.MSP430Config.UARTConfig;
import se.sics.mspsim.core.USART;
import se.sics.mspsim.platform.GenericNode;
import se.sics.mspsim.util.ArgumentManager;
import se.sics.mspsim.util.ComponentRegistry;

import com.eclipsesource.json.JsonObject;

public class EmuLink implements ClientHandler, USARTListener {

    private static final Logger log = LoggerFactory.getLogger(EmuLink.class);

    private static final int DEFAULT_PORT = 7711;
    private static final long DURATION = 1000;

    private final ClientConnection clientConnection;
    private final GenericNode node;
    private final boolean isTimeController;
    private int nodeId;
    private long myTime;
    private AtomicLong messageId = new AtomicLong();
    private long timeId;
    private volatile boolean isWaitingForTimeReply;
    private long controllerTime;
    
    public EmuLink(String host, int port, boolean isTimeController, GenericNode node) throws IOException {
        this.clientConnection = new ClientConnection(this, host, port);
        this.node = node;
        this.isTimeController = isTimeController;
        this.nodeId = (int) (Math.random() * 10);
        this.clientConnection.start();
    }

    public ClientConnection getConnection() {
        return this.clientConnection;
    }

    private JsonObject createCommand(String cmd, JsonObject params) {
        JsonObject jsonCmd = new JsonObject().add("command",  cmd).add("params", params);
        return jsonCmd;
    }

    private void sendInit() throws IOException {
        JsonObject reqNode = createCommand("node-config-set", new JsonObject().add("node-id", nodeId));
        clientConnection.send(reqNode);
    }

    void sendDummyPacket() throws IOException {
        JsonObject transmit = new JsonObject();
        transmit.add("command", "transmit");
        transmit.add("source-node-id", nodeId);
        transmit.add("packet-data", "0102030405");
        transmit.set("time", myTime);
        clientConnection.send(transmit);
    }

    private void serveForever() {
        JsonObject timeMessage = new JsonObject();
        JsonObject params = new JsonObject();
        timeMessage.add("command", "time-set");
        timeMessage.add("params", params);
        try {
            sendInit();
        } catch (IOException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
        }
        while (true) {
            try {
                if (isTimeController) {
                    if (isWaitingForTimeReply) {
                        Thread.sleep(10);
                    } else {
                        timeId = messageId.incrementAndGet();
                        controllerTime += DURATION;
                        params.set("time", controllerTime);
                        timeMessage.set("id", timeId);
                        isWaitingForTimeReply = true;
                        clientConnection.send(timeMessage);
                        Thread.sleep(5);
                    }
                } else {
                    Thread.sleep(5000);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean handleMessage(ClientConnection clientConnection, JsonObject json) {
        System.out.println("RECV: " + json);
        String cmd = json.getString("command", null);
        String replyStr = json.getString("reply", null);
        JsonObject reply = new JsonObject();
        long id;
        if ((id = json.getLong("id", -1)) != -1) {
            reply.set("id", id);
        }
        if (replyStr != null) {
            if (replyStr.equals("OK") && id == timeId) {
                // OK on time message
                isWaitingForTimeReply = false;
            } else if (id >= 0) {
                log.error("Unexpected reply:" + json.toString());
            }

        } else if (cmd != null) {
            if (cmd.equals("time-set")) {
                JsonObject params = (JsonObject) json.get("params"); 
                myTime = params.getLong("time", 0);
                System.out.println("Accepting time elapsed." + myTime);
                node.getCPU().stepMicros(myTime, 0);
                reply.set("reply", "OK");
                try {
                    clientConnection.send(reply);
                } catch (IOException e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            } else if (cmd.equals("transmit")) {
                String destId = json.getString("destination-node-id", null);
                System.out.println("Transmission for node: " + destId);
            }
        }
        return true;
    }

    @Override
    public void clientClosed(ClientConnection clientConnection) {
        log.error("Radio medium connection closed!");
        System.exit(0);
    }

    public static void main(String[] args) throws IOException {
        if (System.getProperty("logback.configurationFile") == null) {
            System.setProperty("logback.configurationFile", "logback.xml");
        }

        ArgumentManager config = new ArgumentManager();
        config.handleArguments(args);

        String nodeType = config.getProperty("nodeType");
        String platform = nodeType;
        GenericNode node;
        if (nodeType != null) {
            node = se.sics.mspsim.Main.createNode(nodeType);
        } else {
            platform = config.getProperty("platform");
            if (platform == null) {
                // Default platform
                platform = "sky";

                // Guess platform based on firmware filename suffix.
                // TinyOS firmware files are often named 'main.exe'.
                String[] a = config.getArguments();
                if (a.length > 0 && !"main.exe".equals(a[0])) {
                    int index = a[0].lastIndexOf('.');
                    if (index > 0) {
                        platform = a[0].substring(index + 1);
                    }
                }
            }
            nodeType = se.sics.mspsim.Main.getNodeTypeByPlatform(platform);
            node = se.sics.mspsim.Main.createNode(nodeType);
        }
        if (node == null) {
            System.err.println("MSPSim does not currently support the platform '" + platform + "'.");
            System.exit(1);
        }
        boolean isTimeController = config.getPropertyAsBoolean("timectrl", false);
        node.setupArgs(config);
        
        EmuLink c = new EmuLink("127.0.0.1", DEFAULT_PORT, isTimeController, node);

        ComponentRegistry r = node.getRegistry();
        USART uart = r.getComponent(USART.class);
        if (uart != null) {
            uart.addUSARTListener(c);
        }
        
        c.serveForever();
    }


    static StringBuffer sbuf = new StringBuffer();
    @Override
    public void dataReceived(USARTSource source, int data) {
        // Receive and printout UART data. 
        sbuf.append((char) data);
        if(data == '\n') {
            System.out.println("UART Data:" + sbuf.toString());
            sbuf.setLength(0);
        }
    }
}