import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class Config {

    public record CommonConfig(
            int numberOfPreferredNeighbors,
            int unchokingInterval,
            int optimisticUnchokingInterval,
            String fileName,
            int fileSize,
            int pieceSize
    ) {
        public int totalPieces() {
            return (int) Math.ceil((double) fileSize / pieceSize);
        }
    }

    public record PeerInfo(
            int peerId,
            String hostName,
            int port,
            boolean hasFile
    ) {
    }

    public static CommonConfig readCommonConfig(String filename) throws IOException {
        int preferredNeighbors = 0;
        int unchokingInterval = 0;
        int optimisticUnchokingInterval = 0;
        String fileName = "";
        int fileSize = 0;
        int pieceSize = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;

                switch (parts[0]) {
                    case "NumberOfPreferredNeighbors" -> preferredNeighbors = Integer.parseInt(parts[1]);
                    case "UnchokingInterval" -> unchokingInterval = Integer.parseInt(parts[1]);
                    case "OptimisticUnchokingInterval" -> optimisticUnchokingInterval = Integer.parseInt(parts[1]);
                    case "FileName" -> fileName = parts[1];
                    case "FileSize" -> fileSize = Integer.parseInt(parts[1]);
                    case "PieceSize" -> pieceSize = Integer.parseInt(parts[1]);
                    default -> {
                    }
                }
            }
        }

        return new CommonConfig(
                preferredNeighbors,
                unchokingInterval,
                optimisticUnchokingInterval,
                fileName,
                fileSize,
                pieceSize
        );
    }

    public static List<PeerInfo> readPeerInfo(String filename) throws IOException {
        List<PeerInfo> peers = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                if (parts.length < 4) continue;

                int peerId = Integer.parseInt(parts[0]);
                String hostName = parts[1];
                int port = Integer.parseInt(parts[2]);
                boolean hasFile = "1".equals(parts[3]);

                peers.add(new PeerInfo(peerId, hostName, port, hasFile));
            }
        }

        return peers;
    }

    public static PeerInfo findPeer(List<PeerInfo> peers, int peerId) {
        for (PeerInfo peer : peers) {
            if (peer.peerId() == peerId) {
                return peer;
            }
        }
        return null;
    }
}