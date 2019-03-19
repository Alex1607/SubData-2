package net.ME1312.SubData.Server.Protocol;

import net.ME1312.Galaxi.Library.Version.Version;
import net.ME1312.SubData.Server.Client;

/**
 * Message In Layout Class
 */
public interface MessageIn {

    /**
     * Receives the incoming Message
     *
     * @param client Client who sent
     * @throws Throwable
     */
    void receive(Client client) throws Throwable;

    /**
     * Protocol Version
     *
     * @return Version
     */
    Version version();

    /**
     * Checks compatibility with an Incoming Message
     *
     * @param version Version of the incoming packet
     * @return Compatibility Status
     */
    default boolean isCompatable(Version version) {
        return Version.equals(version(), version);
    }
}
