package com.superior.datatunnel.plugin.sftp.fs;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.apache.hadoop.fs.sftp.SFTPFileSystem;
import org.apache.hadoop.util.StringUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Concurrent/Multiple Connections. */
class SFTPConnectionPool {

    public static final Logger LOG =
            LoggerFactory.getLogger(SFTPFileSystem.class);
    // Maximum number of allowed live connections. This doesn't mean we cannot
    // have more live connections. It means that when we have more
    // live connections than this threshold, any unused connection will be
    // closed.
    private int maxConnection;
    private int liveConnectionCount = 0;
    private HashMap<ConnectionInfo, HashSet<ChannelSftp>> idleConnections =
            new HashMap<ConnectionInfo, HashSet<ChannelSftp>>();
    private HashMap<ChannelSftp, ConnectionInfo> con2infoMap =
            new HashMap<ChannelSftp, ConnectionInfo>();

    SFTPConnectionPool(int maxConnection) {
        this.maxConnection = maxConnection;
    }

    synchronized ChannelSftp getFromPool(ConnectionInfo info) throws IOException {
        Set<ChannelSftp> cons = idleConnections.get(info);
        ChannelSftp channel;

        if (cons != null && cons.size() > 0) {
            Iterator<ChannelSftp> it = cons.iterator();
            if (it.hasNext()) {
                channel = it.next();
                idleConnections.remove(info);
                return channel;
            } else {
                throw new IOException("Connection pool error.");
            }
        }
        return null;
    }

    /** Add the channel into pool.
     * @param channel
     */
    synchronized void returnToPool(ChannelSftp channel) {
        ConnectionInfo info = con2infoMap.get(channel);
        HashSet<ChannelSftp> cons = idleConnections.get(info);
        if (cons == null) {
            cons = new HashSet<>();
            idleConnections.put(info, cons);
        }
        cons.add(channel);

    }

    /** Shutdown the connection pool and close all open connections. */
    synchronized void shutdown() {
        if (this.con2infoMap == null){
            return; // already shutdown in case it is called
        }
        LOG.info("Inside shutdown, con2infoMap size=" + con2infoMap.size());

        this.maxConnection = 0;
        Set<ChannelSftp> cons = con2infoMap.keySet();
        if (cons != null && cons.size() > 0) {
            // make a copy since we need to modify the underlying Map
            Set<ChannelSftp> copy = new HashSet<>(cons);
            // Initiate disconnect from all outstanding connections
            for (ChannelSftp con : copy) {
                try {
                    disconnect(con);
                } catch (IOException ioe) {
                    ConnectionInfo info = con2infoMap.get(con);
                    LOG.error(
                            "Error encountered while closing connection to " + info.getHost(),
                            ioe);
                }
            }
        }
        // make sure no further connections can be returned.
        this.idleConnections = null;
        this.con2infoMap = null;
    }

    public synchronized int getMaxConnection() {
        return maxConnection;
    }

    public synchronized void setMaxConnection(int maxConn) {
        this.maxConnection = maxConn;
    }

    public ChannelSftp connect(String host, int port, String user,
                               String password, String keyFile, String passPhrase) throws IOException {
        // get connection from pool
        ConnectionInfo info = new ConnectionInfo(host, port, user);
        ChannelSftp channel = getFromPool(info);

        if (channel != null) {
            if (channel.isConnected()) {
                return channel;
            } else {
                channel = null;
                synchronized (this) {
                    --liveConnectionCount;
                    con2infoMap.remove(channel);
                }
            }
        }

        // create a new connection and add to pool
        JSch jsch = new JSch();
        Session session = null;
        try {
            if (user == null || user.length() == 0) {
                user = System.getProperty("user.name");
            }

            if (password == null) {
                password = "";
            }

            if (keyFile != null && keyFile.length() > 0) {
                if (passPhrase != null && passPhrase.length() > 0) {
                    jsch.addIdentity(keyFile, passPhrase);
                } else {
                    jsch.addIdentity(keyFile);
                }
            }

            if (port <= 0) {
                session = jsch.getSession(user, host);
            } else {
                session = jsch.getSession(user, host, port);
            }

            session.setPassword(password);

            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);

            session.connect();
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect();

            synchronized (this) {
                con2infoMap.put(channel, info);
                liveConnectionCount++;
            }

            return channel;

        } catch (JSchException e) {
            throw new IOException(StringUtils.stringifyException(e));
        }
    }

    void disconnect(ChannelSftp channel) throws IOException {
        if (channel != null) {
            // close connection if too many active connections
            boolean closeConnection = false;
            synchronized (this) {
                if (liveConnectionCount > maxConnection) {
                    --liveConnectionCount;
                    con2infoMap.remove(channel);
                    closeConnection = true;
                }
            }
            if (closeConnection) {
                if (channel.isConnected()) {
                    try {
                        Session session = channel.getSession();
                        channel.disconnect();
                        session.disconnect();
                    } catch (JSchException e) {
                        throw new IOException(StringUtils.stringifyException(e));
                    }
                }

            } else {
                returnToPool(channel);
            }
        }
    }

    public int getIdleCount() {
        return this.idleConnections.size();
    }

    public int getLiveConnCount() {
        return this.liveConnectionCount;
    }

    public int getConnPoolSize() {
        return this.con2infoMap.size();
    }

    /**
     * Class to capture the minimal set of information that distinguish
     * between different connections.
     */
    static class ConnectionInfo {
        private String host = "";
        private int port;
        private String user = "";

        ConnectionInfo(String hst, int prt, String usr) {
            this.host = hst;
            this.port = prt;
            this.user = usr;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String hst) {
            this.host = hst;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int prt) {
            this.port = prt;
        }

        public String getUser() {
            return user;
        }

        public void setUser(String usr) {
            this.user = usr;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj instanceof ConnectionInfo) {
                ConnectionInfo con = (ConnectionInfo) obj;

                boolean ret = true;
                if (this.host == null || !this.host.equalsIgnoreCase(con.host)) {
                    ret = false;
                }
                if (this.port >= 0 && this.port != con.port) {
                    ret = false;
                }
                if (this.user == null || !this.user.equalsIgnoreCase(con.user)) {
                    ret = false;
                }
                return ret;
            } else {
                return false;
            }

        }

        @Override
        public int hashCode() {
            int hashCode = 0;
            if (host != null) {
                hashCode += host.hashCode();
            }
            hashCode += port;
            if (user != null) {
                hashCode += user.hashCode();
            }
            return hashCode;
        }

    }
}
