import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

class FileManager {

    private final int selfPeerId;
    private final Config.CommonConfig commonConfig;
    private final Path peerDir;
    private final Path fullFilePath;

    private final byte[][] pieces;
    private final boolean[] hasPiece;
    private int ownedPieceCount;

    public FileManager(int selfPeerId, boolean startsWithFile, Config.CommonConfig commonConfig) throws IOException {
        this.selfPeerId = selfPeerId;
        this.commonConfig = commonConfig;
        this.peerDir = Paths.get("peer_" + selfPeerId);
        this.fullFilePath = peerDir.resolve(commonConfig.fileName());

        Files.createDirectories(peerDir);

        int totalPieces = commonConfig.totalPieces();
        this.pieces = new byte[totalPieces][];
        this.hasPiece = new boolean[totalPieces];
        this.ownedPieceCount = 0;

        if (startsWithFile) {
            loadCompleteFileFromDisk();
        }
    }

    private void loadCompleteFileFromDisk() throws IOException {
        if (!Files.exists(fullFilePath)) {
            throw new IOException("Expected full file at " + fullFilePath + " for initial seeder peer");
        }

        byte[] allBytes = Files.readAllBytes(fullFilePath);
        int pieceSize = commonConfig.pieceSize();

        for (int i = 0; i < hasPiece.length; i++) {
            int start = i * pieceSize;
            int end = Math.min(start + pieceSize, allBytes.length);

            int len = end - start;
            byte[] piece = new byte[len];
            System.arraycopy(allBytes, start, piece, 0, len);

            pieces[i] = piece;
            hasPiece[i] = true;
            ownedPieceCount++;
        }
    }

    public synchronized boolean hasPiece(int pieceIndex) {
        return pieceIndex >= 0 && pieceIndex < hasPiece.length && hasPiece[pieceIndex];
    }

    public synchronized byte[] getPiece(int pieceIndex) {
        if (!hasPiece(pieceIndex)) {
            return null;
        }
        return pieces[pieceIndex];
    }

    public synchronized boolean savePiece(int pieceIndex, byte[] pieceData) {
        if (pieceIndex < 0 || pieceIndex >= hasPiece.length) {
            return false;
        }
        if (hasPiece[pieceIndex]) {
            return false;
        }

        pieces[pieceIndex] = pieceData;
        hasPiece[pieceIndex] = true;
        ownedPieceCount++;

        if (hasCompleteFile()) {
            try {
                assembleCompleteFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return true;
    }

    private void assembleCompleteFile() throws IOException {
        int totalSize = 0;
        for (byte[] piece : pieces) {
            totalSize += piece.length;
        }

        byte[] output = new byte[totalSize];
        int offset = 0;
        for (byte[] piece : pieces) {
            System.arraycopy(piece, 0, output, offset, piece.length);
            offset += piece.length;
        }

        Files.write(fullFilePath, output);
    }

    public synchronized boolean hasCompleteFile() {
        return ownedPieceCount == hasPiece.length;
    }

    public synchronized int getOwnedPieceCount() {
        return ownedPieceCount;
    }

    public synchronized boolean[] getLocalBitfield() {
        boolean[] copy = new boolean[hasPiece.length];
        System.arraycopy(hasPiece, 0, copy, 0, hasPiece.length);
        return copy;
    }

    public synchronized byte[] buildBitfieldPayload() {
        int byteCount = (int) Math.ceil(hasPiece.length / 8.0);
        byte[] payload = new byte[byteCount];

        for (int i = 0; i < hasPiece.length; i++) {
            if (hasPiece[i]) {
                int byteIndex = i / 8;
                int bitIndex = 7 - (i % 8);
                payload[byteIndex] |= (byte) (1 << bitIndex);
            }
        }

        return payload;
    }

    public boolean[] bitfieldFromPayload(byte[] payload) {
        boolean[] result = new boolean[commonConfig.totalPieces()];

        for (int i = 0; i < result.length; i++) {
            int byteIndex = i / 8;
            int bitIndex = 7 - (i % 8);

            if (byteIndex < payload.length) {
                result[i] = ((payload[byteIndex] >> bitIndex) & 1) == 1;
            }
        }

        return result;
    }

    public synchronized int chooseNeededPiece(boolean[] remoteBitfield, Set<Integer> reservedPieces) {
        for (int i = 0; i < hasPiece.length; i++) {
            if (!hasPiece[i] && remoteBitfield[i] && !reservedPieces.contains(i)) {
                return i;
            }
        }
        return -1;
    }
}