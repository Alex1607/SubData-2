package net.ME1312.SubData.Server;

import net.ME1312.Galaxi.Library.Callback.Callback;
import net.ME1312.Galaxi.Library.Callback.ReturnCallback;
import net.ME1312.Galaxi.Library.Container;
import net.ME1312.Galaxi.Library.NamedContainer;
import net.ME1312.Galaxi.Library.Util;
import net.ME1312.SubData.Server.Encryption.NEH;
import net.ME1312.SubData.Server.Library.ConnectionState;
import net.ME1312.SubData.Server.Library.DebugUtil;
import net.ME1312.SubData.Server.Library.DisconnectReason;
import net.ME1312.SubData.Server.Library.Exception.EncryptionException;
import net.ME1312.SubData.Server.Library.Exception.EndOfStreamException;
import net.ME1312.SubData.Server.Library.Exception.IllegalMessageException;
import net.ME1312.SubData.Server.Library.Exception.IllegalPacketException;
import net.ME1312.SubData.Server.Protocol.*;
import net.ME1312.SubData.Server.Protocol.Initial.InitPacketDeclaration;
import net.ME1312.SubData.Server.Protocol.Initial.InitialPacket;
import net.ME1312.SubData.Server.Protocol.Initial.InitialProtocol;
import net.ME1312.SubData.Server.Protocol.Internal.PacketDisconnect;
import net.ME1312.SubData.Server.Protocol.Internal.PacketDisconnectUnderstood;
import net.ME1312.SubData.Server.Protocol.Internal.PacketSendMessage;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.logging.Logger;

import static net.ME1312.SubData.Server.Library.ConnectionState.*;
import static net.ME1312.SubData.Server.Library.DisconnectReason.*;

/**
 * SubData Client Class
 */
public class SubDataClient extends DataClient {
    private Socket socket;
    private InetSocketAddress address;
    private ClientHandler handler;
    private LinkedList<PacketOut> queue;
    private LinkedList<PacketOut> prequeue;
    private OutputStream out;
    private Cipher cipher = NEH.get();
    private int cipherlevel = 0;
    private SubDataServer subdata;
    private ConnectionState state;
    private Timer timeout;

    SubDataClient(SubDataServer subdata, Socket client) throws IOException {
        if (Util.isNull(subdata, client)) throw new NullPointerException();
        this.subdata = subdata;
        state = PRE_INITIALIZATION;
        socket = client;
        out = client.getOutputStream();
        queue = null;
        prequeue = new LinkedList<>();
        address = new InetSocketAddress(client.getInetAddress(), client.getPort());
        timeout = new Timer("SubDataServer::Handshake_Timeout(" + address.toString() + ')');
        timeout.schedule(new TimerTask() {
            @Override
            public void run() {
                if (state.asInt() < READY.asInt()) try {
                    close(INITIALIZATION_TIMEOUT);
                } catch (IOException e) {
                    DebugUtil.logException(e, subdata.log);
                }
            }
        }, 15000);
    }

    private void read(Container<Boolean> reset, InputStream data) {
        try {
            ByteArrayOutputStream pending = new ByteArrayOutputStream();
            int id = -1, version = -1;

            int b, position = 0;
            while (position < 4 && (b = data.read()) != -1) {
                position++;
                pending.write(b);
                switch (position) {
                    case 2:
                        id = ((int) ByteBuffer.wrap(pending.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getShort()) + -Short.MIN_VALUE;
                        pending.reset();
                        break;
                    case 4:
                        version = ((int) ByteBuffer.wrap(pending.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).getShort()) + -Short.MIN_VALUE;
                        pending.reset();
                        break;
                }
            }

            // Step 4 // Create a detached data forwarding InputStream
            if (state != CLOSED && id >= 0 && version >= 0) {
                InputStream forward = new InputStream() {
                    boolean open = true;

                    @Override
                    public int read() throws IOException {
                        if (open) {
                            int b = data.read();
                            if (b < 0) close();
                            return b;
                        } else return -1;
                    }

                    @Override
                    public void close() throws IOException {
                        open = false;
                        while (data.read() != -1);
                    }
                };
                HashMap<Integer, PacketIn> pIn = (state.asInt() >= READY.asInt())?subdata.protocol.pIn:Util.reflect(InitialProtocol.class.getDeclaredField("pIn"), null);
                if (!pIn.keySet().contains(id)) throw new IllegalPacketException(getAddress().toString() + ": Could not find handler for packet: [" + DebugUtil.toHex(0xFFFF, id) + ", " + DebugUtil.toHex(0xFFFF, version) + "]");
                PacketIn packet = pIn.get(id);
                if (!packet.isCompatable(version)) throw new IllegalPacketException(getAddress().toString() + ": The handler does not support this packet version (" + DebugUtil.toHex(0xFFFF, packet.version()) + "): [" + DebugUtil.toHex(0xFFFF, id) + ", " + DebugUtil.toHex(0xFFFF, version) + "]");

                // Step 5 // Invoke the Packet
                if (state == PRE_INITIALIZATION && !(packet instanceof InitPacketDeclaration)) {
                    DebugUtil.logException(new IllegalStateException("Only InitPacketDeclaration may be received during the PRE_INITIALIZATION stage"), subdata.log);
                    close(PROTOCOL_MISMATCH);
                } else if (state == CLOSING && !(packet instanceof PacketDisconnectUnderstood)) {
                    forward.close();
                } else {
                    subdata.scheduler.run(() -> {
                        try {
                            packet.receive(this);

                            if (packet instanceof PacketStreamIn) {
                                ((PacketStreamIn) packet).receive(this, forward);
                            } else forward.close();
                        } catch (Throwable e) {
                            DebugUtil.logException(new InvocationTargetException(e, getAddress().toString() + ": Exception while running packet handler"), subdata.log);

                            if (state.asInt() <= INITIALIZATION.asInt())
                                Util.isException(() -> close(PROTOCOL_MISMATCH)); // Issues during the init stages are signs of a PROTOCOL_MISMATCH
                        }
                    });
                }
            }
        } catch (Exception e) {
            if (!reset.get()) try {
                if (!(e instanceof SocketException && !Boolean.getBoolean("subdata.debug"))) {
                    DebugUtil.logException(e, subdata.log);
                } if (!(e instanceof SocketException)) {
                    close(UNHANDLED_EXCEPTION);
                } else close(CONNECTION_INTERRUPTED);
            } catch (IOException e1) {
                e1.printStackTrace();
            }
        }
    }
    void read() {
        if (!socket.isClosed()) new Thread(() -> {
            Container<Boolean> reset = new Container<>(false);
            try {
                // Step 1 // Parse Escapes in the Encrypted Data
                InputStream in = socket.getInputStream();
                InputStream raw = new InputStream() {
                    boolean open = true;
                    boolean finished = false;

                    private int next() throws IOException {
                        int b = in.read();
                        switch (b) {
                            case -1:
                                throw new EndOfStreamException();
                            case '\u0010': // [DLE] (Escape character)
                                b = in.read();
                                break;
                            case '\u0018': // [CAN] (Read Reset character)
                                if (state != PRE_INITIALIZATION)
                                    reset.set(true);
                            case '\u0017': // [ETB] (End of Packet character)
                                finished = true;
                                b = -1;
                                break;
                        }
                        return b;
                    }

                    @Override
                    public int read() throws IOException {
                        if (open) {
                            int b = next();
                            if (b <= -1) close();
                            return b;
                        } else return -1;
                    }

                    @Override
                    public void close() throws IOException {
                        if (open) {
                            open = false;
                            if (!socket.isClosed()) {
                                if (!finished) {
                                    while (next() != -1);
                                }
                            }
                            SubDataClient.this.read();
                        }
                    }
                };

                // Step 3 // Parse the SubData Packet Formatting
                PipedInputStream data = new PipedInputStream(1024);
                new Thread(() -> read(reset, data), "SubDataServer::Packet_Listener(" + address.toString() + ')').start();

                // Step 2 // Decrypt the Data
                PipedOutputStream forward = new PipedOutputStream(data);
                cipher.decrypt(raw, forward);
                forward.close();

            } catch (Exception e) {
                if (!reset.get()) try {
                    if (!(e instanceof SocketException && !Boolean.getBoolean("subdata.debug"))) {
                        DebugUtil.logException(e, subdata.log);
                    } if (!(e instanceof SocketException)) {
                        if (e instanceof EncryptionException)
                            close(ENCRYPTION_MISMATCH);  // Classes that extend EncryptionException being thrown signify an ENCRYPTION_MISMATCH
                        else close(UNHANDLED_EXCEPTION);
                    } else close(CONNECTION_INTERRUPTED);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }
        }, "SubDataServer::Data_Listener(" + address.toString() + ')').start();
    }

    private void write(PacketOut next, OutputStream data) {
        // Step 1 // Create a detached data forwarding OutputStream
        try (OutputStream forward = new OutputStream() {
            boolean open = true;

            @Override
            public void write(int b) throws IOException {
                if (open) data.write(b);
            }

            @Override
            public void close() throws IOException {
                open = false;
                Util.isException(data::close);
            }
        }) {
            // Step 2 // Write the Packet Metadata
            HashMap<Class<? extends PacketOut>, Integer> pOut = (state.asInt() >= READY.asInt())?subdata.protocol.pOut:Util.reflect(InitialProtocol.class.getDeclaredField("pOut"), null);
            if (!pOut.keySet().contains(next.getClass())) throw new IllegalMessageException("Could not find ID for packet: " + next.getClass().getCanonicalName());
            if (next.version() > 65535 || next.version() < 0) throw new IllegalMessageException("Packet version is not in range (0x0000 to 0xFFFF): " + next.getClass().getCanonicalName());

            data.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (pOut.get(next.getClass()) + Short.MIN_VALUE)).array());
            data.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((short) (next.version() + Short.MIN_VALUE)).array());
            data.flush();

            // Step 3 // Invoke the Packet
            subdata.scheduler.run(() -> {
                try {
                    if (next instanceof PacketStreamOut) {
                        ((PacketStreamOut) next).send(this, forward);
                    } else forward.close();
                } catch (Throwable e) {
                    DebugUtil.logException(e, subdata.log);
                    Util.isException(data::close);
                }
            });
        } catch (Throwable e) {
            DebugUtil.logException(e, subdata.log);
            Util.isException(data::close);
        }
    }
    void write() {
        if (queue != null && !socket.isClosed()) new Thread(() -> {
            if (queue.size() > 0) {
                try {
                    PacketOut next = Util.getDespiteException(() -> queue.get(0), null);
                    Util.isException(() -> queue.remove(0));
                    if (next != null) {
                        PipedOutputStream data = new PipedOutputStream();
                        PipedInputStream raw = new PipedInputStream(data, 1024);
                        new Thread(() -> write(next, data), "SubDataServer::Packet_Writer(" + address.toString() + ')').start();

                        // Step 5 // Add Escapes to the Encrypted Data
                        OutputStream forward = new OutputStream() {
                            boolean open = true;

                            @Override
                            public void write(int b) throws IOException {
                                if (open) {
                                    switch (b) {
                                        case '\u0010': // [DLE] (Escape character)
                                        case '\u0018': // [CAN] (Reader Reset character)
                                        case '\u0017': // [ETB] (End of Packet character)
                                            out.write('\u0010');
                                            break;
                                    }
                                    out.write(b);
                                    out.flush();
                                }
                            }

                            @Override
                            public void close() throws IOException {
                                if (open) {
                                    open = false;
                                    out.write('\u0017');
                                    out.flush();
                                    if (queue.size() > 0) SubDataClient.this.write();
                                    else queue = null;
                                }
                            }
                        };

                        // Step 4 // Encrypt the Data
                        cipher.encrypt(raw, forward);
                        forward.close();
                        raw.close();
                    }
                } catch (Throwable e) {
                    Util.isException(() -> queue.remove(0));
                    DebugUtil.logException(e, subdata.log);

                    if (queue.size() > 0) SubDataClient.this.write();
                    else queue = null;
                }
            } else queue = null;
        }, "SubDataServer::Data_Writer(" + address.toString() + ')').start();
    }

    /**
     * Send a packet to the Client
     *
     * @param packet Packet to send
     */
    public void sendPacket(PacketOut packet) {
        if (Util.isNull(packet)) throw new NullPointerException();
        if (!isClosed()) {
            if (state.asInt() < READY.asInt() && !(packet instanceof InitialPacket)) {
                prequeue.add(packet);
            } else if (state == CLOSING && !(packet instanceof PacketDisconnect || packet instanceof PacketDisconnectUnderstood)) {
                // do nothing
            } else {
                boolean init = false;

                if (queue == null) {
                    queue = new LinkedList<>();
                    init = true;
                }
                queue.add(packet);

                if (init) write();
            }
        }
    }

    public void sendMessage(MessageOut message) {
        if (Util.isNull(message)) throw new NullPointerException();
        sendPacket(new PacketSendMessage(message));
    }

    /**
     * Get the underlying Client Socket
     *
     * @return Client Socket
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Get the Server this Client belongs to
     *
     * @return SubData Server
     */
    public SubDataServer getServer() {
        return subdata;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public ClientHandler getHandler() {
        return handler;
    }

    /**
     * Sets the Handler
     *
     * @param obj Handler
     */
    public void setHandler(ClientHandler obj) {
        if (handler != null && handler.getSubData() != null && equals(handler.getSubData())) handler.setSubData(null);
        handler = obj;
        if (handler != null && (handler.getSubData() == null || !equals(handler.getSubData()))) handler.setSubData(this);
    }

    public void close() throws IOException {
        if (state.asInt() < CLOSING.asInt() && !socket.isClosed()) {
            boolean result = true;
            LinkedList<ReturnCallback<DataClient, Boolean>> events = on.close;
            on.close = new LinkedList<>();
            for (ReturnCallback<DataClient, Boolean> next : events) try {
                if (next != null) result = next.run(this) != Boolean.FALSE && result;
            } catch (Throwable e) {
                DebugUtil.logException(new InvocationTargetException(e, "Unhandled exception while running SubData Event"), subdata.log);
            }

            if (result) {
                state = CLOSING;
                if (!isClosed()) sendPacket(new PacketDisconnect());

                timeout = new Timer("SubDataServer::Disconnect_Timeout(" + address.toString() + ')');
                timeout.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        if (!socket.isClosed()) try {
                            close(CLOSE_REQUESTED);
                        } catch (IOException e) {
                            DebugUtil.logException(e, subdata.log);
                        }
                    }
                }, 5000);
            }
        }
    }
    void close(DisconnectReason reason) throws IOException {
        if (state != CLOSED) {
            if (state == CLOSING && reason == CONNECTION_INTERRUPTED) reason = CLOSE_REQUESTED;
            state = CLOSED;
            if (reason != CLOSE_REQUESTED) {
                DebugUtil.logException(new SocketException("Connection closed: " + reason), subdata.log);
            }

            if (!socket.isClosed()) getSocket().close();
            if (handler != null) {
                setHandler(null);
                handler = null;
            }
            if (subdata.getClients().values().contains(this)) subdata.removeClient(this);

            final DisconnectReason freason = reason;
            subdata.scheduler.run(() -> {
                LinkedList<Callback<NamedContainer<DisconnectReason, DataClient>>> events = on.closed;
                on.closed = new LinkedList<>();
                for (Callback<NamedContainer<DisconnectReason, DataClient>> next : events) try {
                    if (next != null) next.run(new NamedContainer<>(freason, this));
                } catch (Throwable e) {
                    DebugUtil.logException(new InvocationTargetException(e, "Unhandled exception while running SubData Event"), subdata.log);
                }
            });
        }
    }

    public boolean isClosed() {
        return state == CLOSED || socket.isClosed();
    }
}
