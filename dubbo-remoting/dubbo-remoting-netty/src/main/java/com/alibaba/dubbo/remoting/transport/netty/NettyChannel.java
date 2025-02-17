/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.remoting.transport.netty;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.transport.AbstractChannel;

import org.jboss.netty.channel.ChannelFuture;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * NettyChannel.
 */
final class NettyChannel extends AbstractChannel {

    private static final Logger logger = LoggerFactory.getLogger(NettyChannel.class);

    /**
     * 通道集合
     * K ：Netty 自己的 Channel，  V ：Dubbo 的 NettyChannel
     */
    private static final ConcurrentMap<org.jboss.netty.channel.Channel, NettyChannel> channelMap = new ConcurrentHashMap<org.jboss.netty.channel.Channel, NettyChannel>();

    /**
     * Netty 自己的 Channel （通道）
     */
    private final org.jboss.netty.channel.Channel channel;

    /**
     * 属性集合
     */
    private final Map<String, Object> attributes = new ConcurrentHashMap<String, Object>();

    private NettyChannel(org.jboss.netty.channel.Channel channel, URL url, ChannelHandler handler) {
        super(url, handler);
        if (channel == null) {
            throw new IllegalArgumentException("netty channel == null;");
        }
        this.channel = channel;
    }

    static NettyChannel getOrAddChannel(org.jboss.netty.channel.Channel ch, URL url, ChannelHandler handler) {
        if (ch == null) {
            return null;
        }
        // 先从集合中取 通道
        NettyChannel ret = channelMap.get(ch);
        if (ret == null) {
            // 如果为空，创建
            NettyChannel nc = new NettyChannel(ch, url, handler);
            // 如果通道连接着
            if (ch.isConnected()) {
                // 加入集合
                ret = channelMap.putIfAbsent(ch, nc);
            }
            if (ret == null) {
                ret = nc;
            }
        }
        return ret;
    }

    /**
     * 该方法是当通道没有连接的时候，从集合中移除它。
     * @param ch
     */
    static void removeChannelIfDisconnected(org.jboss.netty.channel.Channel ch) {
        if (ch != null && !ch.isConnected()) {
            channelMap.remove(ch);
        }
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return (InetSocketAddress) channel.getRemoteAddress();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    /**
     * 该方法是发送消息，其中用到了channe.write方法传输消息，并且通过返回的future来判断是否发送成功。
     * @param message
     * @param sent    already sent to socket?
     * @throws RemotingException
     */
    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        // 调用父类方法，检测通道是否关闭
        super.send(message, sent);

        boolean success = true;
        int timeout = 0;
        try {
            // 写入数据，发送消息
            ChannelFuture future = channel.write(message);
            // 如果已经发送
            if (sent) {
                // 获取超时时间
                timeout = getUrl().getPositiveParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);
                // 等待 timeout 的连接时间后 查看是否发送成功
                success = future.await(timeout);
            }
            // 查看是否有异常
            Throwable cause = future.getCause();
            if (cause != null) {
                throw cause;
            }
        } catch (Throwable e) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress() + ", cause: " + e.getMessage(), e);
        }

        if (!success) {
            throw new RemotingException(this, "Failed to send message " + message + " to " + getRemoteAddress()
                    + "in timeout(" + timeout + "ms) limit");
        }
    }

    @Override
    public void close() {
        try {
            super.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 如果通道断开，则移除该通道
            removeChannelIfDisconnected(channel);
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            // 清空属性
            attributes.clear();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
        try {
            if (logger.isInfoEnabled()) {
                logger.info("Close netty channel " + channel);
            }
            // 关闭通道
            channel.close();
        } catch (Exception e) {
            logger.warn(e.getMessage(), e);
        }
    }

    @Override
    public boolean hasAttribute(String key) {
        return attributes.containsKey(key);
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) { // The null value unallowed in the ConcurrentHashMap.
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public void removeAttribute(String key) {
        attributes.remove(key);
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((channel == null) ? 0 : channel.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        NettyChannel other = (NettyChannel) obj;
        if (channel == null) {
            if (other.channel != null) return false;
        } else if (!channel.equals(other.channel)) return false;
        return true;
    }

    @Override
    public String toString() {
        return "NettyChannel [channel=" + channel + "]";
    }

}
