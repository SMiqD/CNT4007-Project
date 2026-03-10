import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

class ConnectionHandler extends Thread {

    private final peerProcess owner;
    private final Socket socket;
    private final boolean initiatedByMe;
    private final Integer expectedPeerId;

    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean running = true;
    private int remotePeerId = -1;

    public ConnectionHandler(peerProcess owner, Socket socket, boolean initiatedByMe, Integer expectedPeerId) {
        this.owner = owner;
        this.socket = socket;
        this.initiatedByMe = initiatedByMe;
        this.expectedPeerId = expectedPeerId;
    }

    @Override
    public void run() {
        try {
            out = new DataOutputStream(socket.getOutputStream());
            in = new DataInputStream(socket.getInputStream());

            doHandshake();
            owner.registerConnection(remotePeerId, this, initiatedByMe);

            if (owner.getFileManager().getOwnedPieceCount() > 0) {
                sendMessage(Message.buildBitfield(owner.getFileManager().buildBitfieldPayload()));
            }

            while (running) {
                Message msg = Message.readFrom(in);
                if (msg == null) {
                    break;
                }
                handleMessage(msg);
            }
        } catch (IOException e) {
            // connection closed or failed
        } finally {
            closeSilently();
            if (remotePeerId != -1) {
                owner.removeConnection(remotePeerId);
            }
        }
    }

    private void doHandshake() throws IOException {
        byte[] handshake = Message.buildHandshake(owner.getSelfPeerId());
        synchronized (this) {
            out.write(handshake);
            out.flush();
        }

        int peerId = Message.readHandshake(in);
        if (expectedPeerId != null && expectedPeerId != peerId) {
            throw new IOException("Handshake peer mismatch. Expected " + expectedPeerId + ", got " + peerId);
        }
        this.remotePeerId = peerId;
    }

    private void handleMessage(Message msg) throws IOException {
        switch (msg.getType()) {
            case Message.CHOKE -> owner.onChokedBy(remotePeerId);
            case Message.UNCHOKE -> owner.onUnchokedBy(remotePeerId);
            case Message.INTERESTED -> owner.onInterestedReceived(remotePeerId);
            case Message.NOT_INTERESTED -> owner.onNotInterestedReceived(remotePeerId);

            case Message.HAVE -> {
                int pieceIndex = Message.payloadToInt(msg.getPayload());
                owner.onHaveReceived(remotePeerId, pieceIndex);
            }

            case Message.BITFIELD -> owner.onBitfieldReceived(remotePeerId, msg.getPayload());

            case Message.REQUEST -> {
                int pieceIndex = Message.payloadToInt(msg.getPayload());
                owner.onRequestReceived(remotePeerId, pieceIndex);
            }

            case Message.PIECE -> {
                int pieceIndex = Message.readPieceIndex(msg.getPayload());
                byte[] pieceData = Message.readPieceBytes(msg.getPayload());
                owner.onPieceReceived(remotePeerId, pieceIndex, pieceData);
            }

            default -> throw new IOException("Unknown message type: " + msg.getType());
        }
    }

    public synchronized void sendMessage(Message msg) {
        if (!running || out == null || msg == null) {
            return;
        }

        try {
            msg.writeTo(out);
            out.flush();
        } catch (IOException e) {
            closeSilently();
        }
    }

    public synchronized void closeSilently() {
        running = false;
        try {
            if (in != null) in.close();
        } catch (IOException ignored) {
        }
        try {
            if (out != null) out.close();
        } catch (IOException ignored) {
        }
        try {
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException ignored) {
        }
    }

    public int getRemotePeerId() {
        return remotePeerId;
    }
}