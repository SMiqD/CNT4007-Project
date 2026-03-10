import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

class Logger {

    private final int selfPeerId;
    private final PrintWriter out;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public Logger(int selfPeerId) throws IOException {
        this.selfPeerId = selfPeerId;
        this.out = new PrintWriter(new FileWriter("log_peer_" + selfPeerId + ".log", true), true);
    }

    private String now() {
        return LocalDateTime.now().format(formatter);
    }

    private synchronized void writeLine(String line) {
        out.println(line);
        out.flush();
        System.out.println(line);
    }

    public void logConnectionMade(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " makes a connection to Peer " + otherPeerId + ".");
    }

    public void logConnectionReceived(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " is connected from Peer " + otherPeerId + ".");
    }

    public void logPreferredNeighbors(List<Integer> peerIds) {
        String joined = peerIds.isEmpty()
                ? ""
                : peerIds.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
        writeLine(now() + ": Peer " + selfPeerId + " has the preferred neighbors " + joined + ".");
    }

    public void logOptimisticNeighbor(int peerId) {
        writeLine(now() + ": Peer " + selfPeerId + " has the optimistically unchoked neighbor " + peerId + ".");
    }

    public void logUnchokedBy(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " is unchoked by " + otherPeerId + ".");
    }

    public void logChokedBy(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " is choked by " + otherPeerId + ".");
    }

    public void logHaveReceived(int otherPeerId, int pieceIndex) {
        writeLine(now() + ": Peer " + selfPeerId + " received the 'have' message from "
                + otherPeerId + " for the piece " + pieceIndex + ".");
    }

    public void logInterestedReceived(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " received the 'interested' message from " + otherPeerId + ".");
    }

    public void logNotInterestedReceived(int otherPeerId) {
        writeLine(now() + ": Peer " + selfPeerId + " received the 'not interested' message from " + otherPeerId + ".");
    }

    public void logPieceDownloaded(int otherPeerId, int pieceIndex, int currentCount) {
        writeLine(now() + ": Peer " + selfPeerId + " has downloaded the piece "
                + pieceIndex + " from " + otherPeerId + ". Now the number of pieces it has is "
                + currentCount + ".");
    }

    public void logCompleteFile() {
        writeLine(now() + ": Peer " + selfPeerId + " has downloaded the complete file.");
    }

    public synchronized void close() {
        out.flush();
        out.close();
    }
}