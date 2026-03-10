import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

class Message {

    public static final byte CHOKE = 0;
    public static final byte UNCHOKE = 1;
    public static final byte INTERESTED = 2;
    public static final byte NOT_INTERESTED = 3;
    public static final byte HAVE = 4;
    public static final byte BITFIELD = 5;
    public static final byte REQUEST = 6;
    public static final byte PIECE = 7;

    private static final String HANDSHAKE_HEADER = "P2PFILESHARINGPROJ";
    private static final int HANDSHAKE_TOTAL_LENGTH = 32;

    private final int length;
    private final byte type;
    private final byte[] payload;

    public Message(byte type, byte[] payload) {
        this.type = type;
        this.payload = payload == null ? new byte[0] : payload;
        this.length = 1 + this.payload.length;
    }

    public int getLength() {
        return length;
    }

    public byte getType() {
        return type;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void writeTo(DataOutputStream out) throws IOException {
        out.writeInt(length);
        out.writeByte(type);
        if (payload.length > 0) {
            out.write(payload);
        }
    }

    public static Message readFrom(DataInputStream in) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (IOException e) {
            return null;
        }

        byte type = in.readByte();
        int payloadLength = length - 1;
        byte[] payload = new byte[payloadLength];

        if (payloadLength > 0) {
            in.readFully(payload);
        }

        return new Message(type, payload);
    }

    public static Message buildChoke() {
        return new Message(CHOKE, new byte[0]);
    }

    public static Message buildUnchoke() {
        return new Message(UNCHOKE, new byte[0]);
    }

    public static Message buildInterested() {
        return new Message(INTERESTED, new byte[0]);
    }

    public static Message buildNotInterested() {
        return new Message(NOT_INTERESTED, new byte[0]);
    }

    public static Message buildHave(int pieceIndex) {
        return new Message(HAVE, intToBytes(pieceIndex));
    }

    public static Message buildBitfield(byte[] bitfield) {
        return new Message(BITFIELD, bitfield);
    }

    public static Message buildRequest(int pieceIndex) {
        return new Message(REQUEST, intToBytes(pieceIndex));
    }

    public static Message buildPiece(int pieceIndex, byte[] pieceBytes) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + pieceBytes.length);
        buffer.putInt(pieceIndex);
        buffer.put(pieceBytes);
        return new Message(PIECE, buffer.array());
    }

    public static byte[] buildHandshake(int peerId) {
        ByteBuffer buffer = ByteBuffer.allocate(HANDSHAKE_TOTAL_LENGTH);
        buffer.put(HANDSHAKE_HEADER.getBytes(StandardCharsets.UTF_8));
        buffer.put(new byte[10]);
        buffer.putInt(peerId);
        return buffer.array();
    }

    public static int readHandshake(DataInputStream in) throws IOException {
        byte[] handshake = new byte[HANDSHAKE_TOTAL_LENGTH];
        in.readFully(handshake);

        byte[] headerBytes = Arrays.copyOfRange(handshake, 0, 18);
        String header = new String(headerBytes, StandardCharsets.UTF_8);
        if (!HANDSHAKE_HEADER.equals(header)) {
            throw new IOException("Invalid handshake header");
        }

        ByteBuffer buffer = ByteBuffer.wrap(handshake);
        buffer.position(28);
        return buffer.getInt();
    }

    public static byte[] intToBytes(int value) {
        return ByteBuffer.allocate(4).putInt(value).array();
    }

    public static int payloadToInt(byte[] payload) {
        return ByteBuffer.wrap(payload).getInt();
    }

    public static int readPieceIndex(byte[] payload) {
        return ByteBuffer.wrap(payload, 0, 4).getInt();
    }

    public static byte[] readPieceBytes(byte[] payload) {
        return Arrays.copyOfRange(payload, 4, payload.length);
    }
}