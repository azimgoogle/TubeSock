package com.firebase.tubesock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * User: greg
 * Date: 7/24/13
 * Time: 10:42 AM
 */
public class WebSocketWriter extends Thread {

    private BlockingQueue<ByteBuffer> pendingBuffers;
    private final Random random = new Random();
    private volatile boolean stop = false;
    private boolean closeSent = false;
    private WebSocket websocket;
    private WritableByteChannel channel;

    public WebSocketWriter(OutputStream output, WebSocket websocket, String threadBaseName) {
        setName(threadBaseName + "Writer");
        this.websocket = websocket;
        channel = Channels.newChannel(output);
        pendingBuffers = new LinkedBlockingQueue<ByteBuffer>();
    }

    private ByteBuffer frameInBuffer(byte opcode, boolean masking, byte[] data) throws IOException {
        int headerLength = 2; // This is just an assumed headerLength, as we use a ByteArrayOutputStream
        if (masking) {
            headerLength += 4;
        }
        int length = data.length;
        if (length < 126) {
            // nothing add to header length
        } else if (length <= 65535) {
            headerLength += 2;
        } else {
            headerLength += 8;
        }
        ByteBuffer frame = ByteBuffer.allocate(data.length + headerLength);

        byte fin = (byte) 0x80;
        byte startByte = (byte) (fin | opcode);
        frame.put(startByte);

        int length_field;

        if (length < 126) {
            if (masking) {
                length = 0x80 | length;
            }
            frame.put((byte) length);
        } else if (length <= 65535) {
            length_field = 126;
            if (masking) {
                length_field = 0x80 | length_field;
            }
            frame.put((byte) length_field);
            // We check the size above, so we know we aren't losing anything with the cast
            frame.putShort((short) length);
        } else {
            length_field = 127;
            if (masking) {
                length_field = 0x80 | length_field;
            }
            frame.put((byte) length_field);
            // Since an integer occupies just 4 bytes we fill the 4 leading length bytes with zero
            frame.putInt(0);
            frame.putInt(length);
        }

        byte[] mask;
        if (masking) {
            mask = generateMask();
            frame.put(mask);

            for (int i = 0; i < data.length; i++) {
                frame.put((byte)(data[i] ^ mask[i % 4]));
            }
        }

        frame.flip();
        return frame;
    }

    private byte[] generateMask() {
        final byte[] mask = new byte[4];
        random.nextBytes(mask);
        return mask;
    }

    public synchronized void send(byte opcode, boolean masking, byte[] data) throws IOException {
        ByteBuffer frame = frameInBuffer(opcode, masking, data);
        if (stop && (closeSent || opcode != WebSocket.OPCODE_CLOSE)) {
            throw new WebSocketException("Shouldn't be sending");
        }
        if (opcode == WebSocket.OPCODE_CLOSE) {
            closeSent = true;
        }
        pendingBuffers.add(frame);
    }

    @Override
    public void run() {
        try {
            while (!stop && !Thread.interrupted()) {
                writeMessage();
            }
            // We're stopping, clear any remaining messages
            for (int i = 0; i < pendingBuffers.size(); ++i) {
                writeMessage();
            }
        } catch ( IOException e ) {
            handleError(new WebSocketException("IO Exception", e));
        } catch ( InterruptedException e ) {
            // this thread is regularly terminated via an interrupt
            //e.printStackTrace();
        }
    }

    private void writeMessage() throws InterruptedException, IOException {
        /*byte[] msg = pendingSends.take();
        output.write(msg);*/
        ByteBuffer msg = pendingBuffers.take();
        channel.write(msg);
        //byte[] buf = msg.array();
        //output.write(buf);

    }

    public void stopIt() {
        stop = true;
    }

    private void handleError(WebSocketException e) {
        websocket.handleReceiverError(e);
    }
}
