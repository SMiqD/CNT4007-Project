import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class NeighborManager {

    private static class NeighborState {
        int peerId;
        boolean[] bitfield;
        boolean interestedInMe;
        boolean iAmInterested;
        boolean chokedByNeighbor = true;
        boolean chokingNeighbor = true;
        int downloadedBytesThisInterval = 0;

        NeighborState(int peerId, int totalPieces) {
            this.peerId = peerId;
            this.bitfield = new boolean[totalPieces];
        }

        boolean hasCompleteFile() {
            for (boolean piece : bitfield) {
                if (!piece) {
                    return false;
                }
            }
            return true;
        }
    }

    private final int selfPeerId;
    private final int totalPieces;
    private final Map<Integer, NeighborState> neighbors = new ConcurrentHashMap<>();

    private final Set<Integer> preferredNeighbors = ConcurrentHashMap.newKeySet();
    private volatile Integer optimisticNeighbor = null;

    public NeighborManager(int selfPeerId, int totalPieces) {
        this.selfPeerId = selfPeerId;
        this.totalPieces = totalPieces;
    }

    public void ensureNeighbor(int peerId) {
        neighbors.computeIfAbsent(peerId, id -> new NeighborState(id, totalPieces));
    }

    public void setBitfield(int peerId, boolean[] bitfield) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).bitfield = bitfield;
    }

    public boolean[] getBitfield(int peerId) {
        NeighborState state = neighbors.get(peerId);
        return state == null ? null : state.bitfield;
    }

    public void setNeighborPiece(int peerId, int pieceIndex, boolean value) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).bitfield[pieceIndex] = value;
    }

    public boolean isNeighborComplete(int peerId) {
        ensureNeighbor(peerId);
        return neighbors.get(peerId).hasCompleteFile();
    }

    public void setInterestedInMe(int peerId, boolean value) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).interestedInMe = value;
    }

    public void setIAmInterested(int peerId, boolean value) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).iAmInterested = value;
    }

    public boolean getIAmInterested(int peerId) {
        ensureNeighbor(peerId);
        return neighbors.get(peerId).iAmInterested;
    }

    public void setChokedBy(int peerId, boolean value) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).chokedByNeighbor = value;
    }

    public boolean isChokedBy(int peerId) {
        ensureNeighbor(peerId);
        return neighbors.get(peerId).chokedByNeighbor;
    }

    public void setChoking(int peerId, boolean value) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).chokingNeighbor = value;
    }

    public boolean isChoking(int peerId) {
        ensureNeighbor(peerId);
        return neighbors.get(peerId).chokingNeighbor;
    }

    public boolean remoteHasInterestingPiece(int peerId, boolean[] localBitfield) {
        ensureNeighbor(peerId);
        boolean[] remote = neighbors.get(peerId).bitfield;

        for (int i = 0; i < localBitfield.length; i++) {
            if (!localBitfield[i] && remote[i]) {
                return true;
            }
        }
        return false;
    }

    public void addDownloadedBytes(int peerId, int bytes) {
        ensureNeighbor(peerId);
        neighbors.get(peerId).downloadedBytesThisInterval += bytes;
    }

    public void resetDownloadCounters() {
        for (NeighborState state : neighbors.values()) {
            state.downloadedBytesThisInterval = 0;
        }
    }

    public List<Integer> pickPreferredNeighbors(int k, boolean selfHasCompleteFile) {
        List<NeighborState> candidates = new ArrayList<>();
        for (NeighborState state : neighbors.values()) {
            if (state.interestedInMe) {
                candidates.add(state);
            }
        }

        Collections.shuffle(candidates);

        if (!selfHasCompleteFile) {
            candidates.sort(Comparator.comparingInt((NeighborState s) -> s.downloadedBytesThisInterval).reversed());
        }

        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(k, candidates.size()); i++) {
            selected.add(candidates.get(i).peerId);
        }

        return selected;
    }

    public Integer pickOptimisticNeighbor() {
        List<Integer> candidates = new ArrayList<>();
        for (NeighborState state : neighbors.values()) {
            if (state.interestedInMe && state.chokingNeighbor) {
                candidates.add(state.peerId);
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.shuffle(candidates);
        return candidates.get(0);
    }

    public void setPreferredNeighbors(List<Integer> peerIds) {
        preferredNeighbors.clear();
        preferredNeighbors.addAll(peerIds);
    }

    public boolean isPreferredNeighbor(int peerId) {
        return preferredNeighbors.contains(peerId);
    }

    public void setOptimisticNeighbor(Integer peerId) {
        optimisticNeighbor = peerId;
    }

    public boolean isOptimisticNeighbor(int peerId) {
        return optimisticNeighbor != null && optimisticNeighbor == peerId;
    }

    public boolean allNeighborsComplete(int expectedNeighborCount) {
        if (neighbors.size() < expectedNeighborCount) {
            return false;
        }

        for (NeighborState state : neighbors.values()) {
            if (!state.hasCompleteFile()) {
                return false;
            }
        }
        return true;
    }
}