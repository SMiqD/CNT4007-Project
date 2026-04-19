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

    // Tracks which peer a reserved/requested piece is currently tied to
    private final Map<Integer, Integer> requestedPieceOwners = new ConcurrentHashMap<>();

    // NEW: persistent completion tracking for all peers
    private final Map<Integer, Boolean> peerCompletionStatus = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    private volatile boolean completionLogged = false;
    private volatile boolean running = true;

    private volatile List<Integer> lastPreferredNeighbors = new ArrayList<>();
    private volatile Integer lastOptimisticNeighbor = null;

    // NEW: keep the server socket as a field so shutdown can close it
    private volatile ServerSocket serverSocket;

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

        initializeCompletionTracking();
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

    private void initializeCompletionTracking() {
        for (Config.PeerInfo peer : peers) {
            peerCompletionStatus.put(peer.peerId(), peer.hasFile());
        }

        // self may already have complete file, or may not
        peerCompletionStatus.put(selfPeerId, fileManager.hasCompleteFile());

        if (fileManager.hasCompleteFile()) {
            completionLogged = true; // seeder starts complete; do not log "downloaded complete file"
        }
    }

    public void start() throws IOException {
        startServerThread();
        connectToPreviousPeers();
        startSchedulers();

        while (running) {
            sleepSilently(500L);

            if (allPeersComplete()) {
                running = false;
            }
        }

        shutdown();
    }

    private void startServerThread() {
        Thread serverThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(selfInfo.port());

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
        if (!running) {
            return;
        }

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
        if (!running) {
            return;
        }

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

        // Release any requested pieces that were tied to this peer
        List<Integer> toRelease = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : requestedPieceOwners.entrySet()) {
            if (entry.getValue() == remotePeerId) {
                toRelease.add(entry.getKey());
            }
        }

        for (Integer pieceIndex : toRelease) {
            requestedPieceOwners.remove(pieceIndex);
            requestedPieces.remove(pieceIndex);
        }
    }

    public void onBitfieldReceived(int remotePeerId, byte[] payload) {
        boolean[] bitfield = fileManager.bitfieldFromPayload(payload);
        neighborManager.setBitfield(remotePeerId, bitfield);

        if (isBitfieldComplete(bitfield)) {
            markPeerComplete(remotePeerId);
        }

        if (neighborManager.remoteHasInterestingPiece(remotePeerId, fileManager.getLocalBitfield())) {
            sendInterested(remotePeerId);
        } else {
            sendNotInterested(remotePeerId);
        }
    }

    public void onHaveReceived(int remotePeerId, int pieceIndex) {
        neighborManager.setNeighborPiece(remotePeerId, pieceIndex, true);
        logger.logHaveReceived(remotePeerId, pieceIndex);

        if (neighborManager.isNeighborComplete(remotePeerId)) {
            markPeerComplete(remotePeerId);
        }

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

    // If I already have the complete file, and this neighbor is not interested in me,
    // then the neighbor must also have the complete file.
        if (fileManager.hasCompleteFile()) {
            markPeerComplete(remotePeerId);
        }
    }
    public void onChokedBy(int remotePeerId) {
        neighborManager.setChokedBy(remotePeerId, true);
        logger.logChokedBy(remotePeerId);

        // Release pending requests tied to this peer, because a requested piece may never arrive
        List<Integer> toRelease = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : requestedPieceOwners.entrySet()) {
            if (entry.getValue() == remotePeerId) {
                toRelease.add(entry.getKey());
            }
        }

        for (Integer pieceIndex : toRelease) {
            requestedPieceOwners.remove(pieceIndex);
            requestedPieces.remove(pieceIndex);
        }
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
        requestedPieceOwners.remove(pieceIndex);

        boolean added = fileManager.savePiece(pieceIndex, pieceData);
        if (!added) {
            return;
        }

        neighborManager.addDownloadedBytes(remotePeerId, pieceData.length);
        logger.logPieceDownloaded(remotePeerId, pieceIndex, fileManager.getOwnedPieceCount());

        broadcastHave(pieceIndex);

        if (fileManager.hasCompleteFile() && !completionLogged) {
            completionLogged = true;
            markPeerComplete(selfPeerId);
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
        requestedPieceOwners.put(pieceIndex, remotePeerId);

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

    private void markPeerComplete(int peerId) {
        peerCompletionStatus.put(peerId, true);
    }

    private boolean isBitfieldComplete(boolean[] bitfield) {
        if (bitfield == null) {
            return false;
        }

        for (boolean piece : bitfield) {
            if (!piece) {
                return false;
            }
        }
        return true;
    }

    public boolean allPeersComplete() {
        for (Config.PeerInfo peer : peers) {
            Boolean done = peerCompletionStatus.get(peer.peerId());
            if (done == null || !done) {
                return false;
            }
        }
        return true;
    }

    private void shutdown() {
        running = false;

        scheduler.shutdownNow();

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
        }

        for (ConnectionHandler handler : connections.values()) {
            handler.closeSilently();
        }

        logger.close();
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