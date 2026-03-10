import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class peerProcess {

    private final int selfPeerId;
    private final Config.CommonConfig commonConfig;
    private final List<Config.PeerInfo> peers;
    private final Config.PeerInfo selfInfo;

    private final FileManager fileManager;
    private final NeighborManager neighborManager;
    private final Logger logger;

    private final Map<Integer, ConnectionHandler> connections = new ConcurrentHashMap<>();
    private final Set<Integer> requestedPieces = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private volatile boolean completionLogged = false;
    private volatile boolean running = true;

    private volatile List<Integer> lastPreferredNeighbors = new ArrayList<>();
    private volatile Integer lastOptimisticNeighbor = null;

    public peerProcess(int selfPeerId,
                    Config.CommonConfig commonConfig,
                    List<Config.PeerInfo> peers) throws IOException {
        this.selfPeerId = selfPeerId;
        this.commonConfig = commonConfig;
        this.peers = peers;
        this.selfInfo = Config.findPeer(peers, selfPeerId);

        if (this.selfInfo == null) {
            throw new IllegalArgumentException("Peer ID " + selfPeerId + " not found in PeerInfo.cfg");
        }

        this.fileManager = new FileManager(selfPeerId, selfInfo.hasFile(), commonConfig);
        this.neighborManager = new NeighborManager(selfPeerId, commonConfig.totalPieces());
        this.logger = new Logger(selfPeerId);
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            System.err.println("Usage: java peerProcess <peerId>");
            return;
        }

        try {
            int peerId = Integer.parseInt(args[0]);
            Config.CommonConfig common = Config.readCommonConfig("Common.cfg");
            List<Config.PeerInfo> peers = Config.readPeerInfo("PeerInfo.cfg");

            peerProcess process = new peerProcess(peerId, common, peers);
            process.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void start() throws IOException {
        startServerThread();
        connectToPreviousPeers();
        startSchedulers();

        while (running) {
            sleepSilently(1000L);
            if (allPeersComplete()) {
                running = false;
            }
        }

        shutdown();
    }

    private void startServerThread() {
        Thread serverThread = new Thread(() -> {
            try (ServerSocket serverSocket = new ServerSocket(selfInfo.port())) {
                while (running) {
                    Socket socket = serverSocket.accept();
                    ConnectionHandler handler = new ConnectionHandler(this, socket, false, null);
                    handler.start();
                }
            } catch (IOException e) {
                if (running) {
                    System.err.println("Server listener stopped unexpectedly for peer " + selfPeerId);
                    e.printStackTrace();
                }
            }
        });

        serverThread.setDaemon(true);
        serverThread.start();
    }

    private void connectToPreviousPeers() {
        for (Config.PeerInfo peer : peers) {
            if (peer.peerId() == selfPeerId) {
                break;
            }

            try {
                Socket socket = new Socket(peer.hostName(), peer.port());
                ConnectionHandler handler = new ConnectionHandler(this, socket, true, peer.peerId());
                handler.start();
            } catch (IOException e) {
                System.err.println("Could not connect from peer " + selfPeerId + " to peer " + peer.peerId());
                e.printStackTrace();
            }
        }
    }

    private void startSchedulers() {
        scheduler.scheduleAtFixedRate(
                this::reselectPreferredNeighbors,
                commonConfig.unchokingInterval(),
                commonConfig.unchokingInterval(),
                TimeUnit.SECONDS
        );

        scheduler.scheduleAtFixedRate(
                this::reselectOptimisticNeighbor,
                commonConfig.optimisticUnchokingInterval(),
                commonConfig.optimisticUnchokingInterval(),
                TimeUnit.SECONDS
        );
    }

    private void reselectPreferredNeighbors() {
        try {
            List<Integer> preferred = neighborManager.pickPreferredNeighbors(
                    commonConfig.numberOfPreferredNeighbors(),
                    fileManager.hasCompleteFile()
            );

            neighborManager.setPreferredNeighbors(preferred);

            if (!preferred.equals(lastPreferredNeighbors)) {
                logger.logPreferredNeighbors(preferred);
                lastPreferredNeighbors = new ArrayList<>(preferred);
            }

            for (Map.Entry<Integer, ConnectionHandler> entry : connections.entrySet()) {
                int peerId = entry.getKey();
                ConnectionHandler handler = entry.getValue();

                boolean shouldUnchoke = preferred.contains(peerId)
                        || neighborManager.isOptimisticNeighbor(peerId);

                if (shouldUnchoke) {
                    if (neighborManager.isChoking(peerId)) {
                        neighborManager.setChoking(peerId, false);
                        handler.sendMessage(Message.buildUnchoke());
                    }
                } else {
                    if (!neighborManager.isChoking(peerId)) {
                        neighborManager.setChoking(peerId, true);
                        handler.sendMessage(Message.buildChoke());
                    }
                }
            }

            neighborManager.resetDownloadCounters();
        } catch (Exception e) {
            System.err.println("Preferred neighbor reselection failed for peer " + selfPeerId);
            e.printStackTrace();
        }
    }

    private void reselectOptimisticNeighbor() {
        try {
            Integer optimistic = neighborManager.pickOptimisticNeighbor();
            neighborManager.setOptimisticNeighbor(optimistic);

            if (optimistic != null && !Objects.equals(optimistic, lastOptimisticNeighbor)) {
                logger.logOptimisticNeighbor(optimistic);
                lastOptimisticNeighbor = optimistic;
            }

            if (optimistic != null) {
                ConnectionHandler handler = connections.get(optimistic);
                if (handler != null && neighborManager.isChoking(optimistic)) {
                    neighborManager.setChoking(optimistic, false);
                    handler.sendMessage(Message.buildUnchoke());
                }
            }
        } catch (Exception e) {
            System.err.println("Optimistic unchoke reselection failed for peer " + selfPeerId);
            e.printStackTrace();
        }
    }

    public synchronized void registerConnection(int remotePeerId, ConnectionHandler handler, boolean initiatedByMe) {
        connections.put(remotePeerId, handler);
        neighborManager.ensureNeighbor(remotePeerId);

        if (initiatedByMe) {
            logger.logConnectionMade(remotePeerId);
        } else {
            logger.logConnectionReceived(remotePeerId);
        }
    }

    public synchronized void removeConnection(int remotePeerId) {
        connections.remove(remotePeerId);
    }

    public void onBitfieldReceived(int remotePeerId, byte[] payload) {
        boolean[] bitfield = fileManager.bitfieldFromPayload(payload);
        neighborManager.setBitfield(remotePeerId, bitfield);

        if (neighborManager.remoteHasInterestingPiece(remotePeerId, fileManager.getLocalBitfield())) {
            sendInterested(remotePeerId);
        } else {
            sendNotInterested(remotePeerId);
        }
    }

    public void onHaveReceived(int remotePeerId, int pieceIndex) {
        neighborManager.setNeighborPiece(remotePeerId, pieceIndex, true);
        logger.logHaveReceived(remotePeerId, pieceIndex);

        if (!fileManager.hasPiece(pieceIndex)) {
            sendInterested(remotePeerId);
        } else if (!neighborManager.remoteHasInterestingPiece(remotePeerId, fileManager.getLocalBitfield())) {
            sendNotInterested(remotePeerId);
        }
    }

    public void onInterestedReceived(int remotePeerId) {
        neighborManager.setInterestedInMe(remotePeerId, true);
        logger.logInterestedReceived(remotePeerId);
    }

    public void onNotInterestedReceived(int remotePeerId) {
        neighborManager.setInterestedInMe(remotePeerId, false);
        logger.logNotInterestedReceived(remotePeerId);
    }

    public void onChokedBy(int remotePeerId) {
        neighborManager.setChokedBy(remotePeerId, true);
        logger.logChokedBy(remotePeerId);
    }

    public void onUnchokedBy(int remotePeerId) {
        neighborManager.setChokedBy(remotePeerId, false);
        logger.logUnchokedBy(remotePeerId);
        requestNextPiece(remotePeerId);
    }

    public void onRequestReceived(int remotePeerId, int pieceIndex) {
        if (neighborManager.isChoking(remotePeerId)) {
            return;
        }

        if (!fileManager.hasPiece(pieceIndex)) {
            return;
        }

        byte[] pieceData = fileManager.getPiece(pieceIndex);
        ConnectionHandler handler = connections.get(remotePeerId);
        if (handler != null && pieceData != null) {
            handler.sendMessage(Message.buildPiece(pieceIndex, pieceData));
        }
    }

    public void onPieceReceived(int remotePeerId, int pieceIndex, byte[] pieceData) {
        requestedPieces.remove(pieceIndex);

        boolean added = fileManager.savePiece(pieceIndex, pieceData);
        if (!added) {
            return;
        }

        neighborManager.addDownloadedBytes(remotePeerId, pieceData.length);
        logger.logPieceDownloaded(remotePeerId, pieceIndex, fileManager.getOwnedPieceCount());

        broadcastHave(pieceIndex);

        if (fileManager.hasCompleteFile() && !completionLogged) {
            completionLogged = true;
            logger.logCompleteFile();
        }

        for (Integer peerId : new ArrayList<>(connections.keySet())) {
            if (neighborManager.remoteHasInterestingPiece(peerId, fileManager.getLocalBitfield())) {
                sendInterested(peerId);
            } else {
                sendNotInterested(peerId);
            }
        }

        requestNextPiece(remotePeerId);
    }

    public void requestNextPiece(int remotePeerId) {
        if (neighborManager.isChokedBy(remotePeerId)) {
            return;
        }

        boolean[] remoteBitfield = neighborManager.getBitfield(remotePeerId);
        if (remoteBitfield == null) {
            return;
        }

        int pieceIndex = fileManager.chooseNeededPiece(remoteBitfield, requestedPieces);
        if (pieceIndex == -1) {
            sendNotInterested(remotePeerId);
            return;
        }

        requestedPieces.add(pieceIndex);
        ConnectionHandler handler = connections.get(remotePeerId);
        if (handler != null) {
            handler.sendMessage(Message.buildRequest(pieceIndex));
        }
    }

    public void broadcastHave(int pieceIndex) {
        Message have = Message.buildHave(pieceIndex);
        for (ConnectionHandler handler : connections.values()) {
            handler.sendMessage(have);
        }
    }

    public void sendInterested(int remotePeerId) {
        if (neighborManager.getIAmInterested(remotePeerId)) {
            return;
        }

        neighborManager.setIAmInterested(remotePeerId, true);
        ConnectionHandler handler = connections.get(remotePeerId);
        if (handler != null) {
            handler.sendMessage(Message.buildInterested());
        }
    }

    public void sendNotInterested(int remotePeerId) {
        if (!neighborManager.getIAmInterested(remotePeerId)) {
            return;
        }

        neighborManager.setIAmInterested(remotePeerId, false);
        ConnectionHandler handler = connections.get(remotePeerId);
        if (handler != null) {
            handler.sendMessage(Message.buildNotInterested());
        }
    }

    public boolean allPeersComplete() {
        if (!fileManager.hasCompleteFile()) {
            return false;
        }
        return neighborManager.allNeighborsComplete(peers.size() - 1);
    }

    private void shutdown() {
        scheduler.shutdownNow();
        logger.close();
        for (ConnectionHandler handler : connections.values()) {
            handler.closeSilently();
        }
    }

    public int getSelfPeerId() {
        return selfPeerId;
    }

    public FileManager getFileManager() {
        return fileManager;
    }

    public NeighborManager getNeighborManager() {
        return neighborManager;
    }

    public Logger getLogger() {
        return logger;
    }

    public static void sleepSilently(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}