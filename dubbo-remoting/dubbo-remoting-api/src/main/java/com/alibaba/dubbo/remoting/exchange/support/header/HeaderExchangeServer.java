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
package com.alibaba.dubbo.remoting.exchange.support.header;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.Server;
import com.alibaba.dubbo.remoting.exchange.ExchangeChannel;
import com.alibaba.dubbo.remoting.exchange.ExchangeServer;
import com.alibaba.dubbo.remoting.exchange.Request;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ExchangeServerImpl
 * 基于协议头的信息交换服务器实现类，HeaderExchangeServer是Server的装饰器，每个实现方法都会调用server的方法
 */
public class HeaderExchangeServer implements ExchangeServer {

    protected final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * 定时器线程池
     */
    private final ScheduledExecutorService scheduled = Executors.newScheduledThreadPool(1,
            new NamedThreadFactory(
                    "dubbo-remoting-server-heartbeat",
                    true));
    /**
     * 服务器
     */
    private final Server server;
    /**
     * 心跳定时器
     */
    private ScheduledFuture<?> heartbeatTimer;
    /**
     * 心跳周期，间隔多久发送心跳消息检测一次。 默认值为 0 ，不会执行心跳。
     * heartbeat(ms), default value is 0 , won't execute a heartbeat.
     */
    private int heartbeat;
    /**
     * 心跳超时时间
     */
    private int heartbeatTimeout;
    /**
     * 信息交换服务器 是否关闭
     */
    private AtomicBoolean closed = new AtomicBoolean(false);


    public HeaderExchangeServer(Server server) {
        if (server == null) {
            throw new IllegalArgumentException("server == null");
        }
        this.server = server;
        // 从服务端url参数中 获取心跳周期配置，没有默认设置为 0
        this.heartbeat = server.getUrl().getParameter(Constants.HEARTBEAT_KEY, 0);
        // 从服务端url参数中 获取心跳超时时间，不存在设置默认值：heartbeat * 3 <==> 心跳周期的三倍
        this.heartbeatTimeout = server.getUrl().getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, heartbeat * 3);
        // 检测心跳超时时间 是否小于 心跳周期的两倍
        if (heartbeatTimeout < heartbeat * 2) {
            throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
        }
        // 开启心跳
        startHeartbeatTimer();
    }

    public Server getServer() {
        return server;
    }

    @Override
    public boolean isClosed() {
        return server.isClosed();
    }

    private boolean isRunning() {
        // 获取当前服务连接的所有通道
        Collection<Channel> channels = getChannels();
        // 遍历所有通道
        for (Channel channel : channels) {

            /**
             *  If there are any client connections,
             *  our server should be running.
             */
            // 如果有一个在连接状态，则服务还运行着
            if (channel.isConnected()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        // 关闭心跳检测 和 定时器线程池
        doClose();
        // 关闭服务器
        server.close();
    }

    /**
     * 优雅关闭
     *
     * @param timeout
     */
    @Override
    public void close(final int timeout) {
        // 开始关闭
        startClose();
        // 判断超时时间
        if (timeout > 0) {
            final long max = (long) timeout;
            final long start = System.currentTimeMillis();
            if (getUrl().getParameter(Constants.CHANNEL_SEND_READONLYEVENT_KEY, true)) {
                // 发送 READONLY_EVENT 事件给 所有连接该服务器的客户端，表示 Server 不可读了
                sendChannelReadOnlyEvent();
            }
            // 当服务器还在运行，并且没有超时，睡眠，也就是等待timeout左右时间在进行关闭
            while (HeaderExchangeServer.this.isRunning()
                    && System.currentTimeMillis() - start < max) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
        // 关闭心跳检测 和 线程池
        doClose();
        // 延迟关闭
        server.close(timeout);
    }

    @Override
    public void startClose() {
        server.startClose();
    }

    private void sendChannelReadOnlyEvent() {
        // READONLY_EVENT 事件的请求
        Request request = new Request();
        request.setEvent(Request.READONLY_EVENT);
        // 不需要响应
        request.setTwoWay(false);
        request.setVersion(Version.getProtocolVersion());

        // 获取当前server 上连接的所有客户端
        Collection<Channel> channels = getChannels();
        for (Channel channel : channels) {
            try {
                // 通道是否处于连接状态
                if (channel.isConnected())
                    // 发送READONLY_EVENT事件
                    channel.send(request, getUrl().getParameter(Constants.CHANNEL_READONLYEVENT_SENT_KEY, true));
            } catch (RemotingException e) {
                logger.warn("send cannot write message error.", e);
            }
        }
    }

    private void doClose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 关闭心跳检测
        stopHeartbeatTimer();
        try {
            // 关闭线程池
            scheduled.shutdown();
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        }
    }

    @Override
    public Collection<ExchangeChannel> getExchangeChannels() {
        Collection<ExchangeChannel> exchangeChannels = new ArrayList<ExchangeChannel>();
        // 获得连接该服务器的 通道集合
        Collection<Channel> channels = server.getChannels();
        if (channels != null && !channels.isEmpty()) {
            // 遍历通道集合，为每个通道都创建 信息交换通道，并且加入信息交换通道集合
            for (Channel channel : channels) {
                exchangeChannels.add(HeaderExchangeChannel.getOrAddChannel(channel));
            }
        }
        return exchangeChannels;
    }

    @Override
    public ExchangeChannel getExchangeChannel(InetSocketAddress remoteAddress) {
        Channel channel = server.getChannel(remoteAddress);
        return HeaderExchangeChannel.getOrAddChannel(channel);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Collection<Channel> getChannels() {
        return (Collection) getExchangeChannels();
    }

    @Override
    public Channel getChannel(InetSocketAddress remoteAddress) {
        return getExchangeChannel(remoteAddress);
    }

    @Override
    public boolean isBound() {
        return server.isBound();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return server.getLocalAddress();
    }

    @Override
    public URL getUrl() {
        return server.getUrl();
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return server.getChannelHandler();
    }

    @Override
    public void reset(URL url) {
        // 重置属性
        server.reset(url);
        try {
            // 重置 心跳周期 与 心跳超时时间，并且重新开始心跳检测
            if (url.hasParameter(Constants.HEARTBEAT_KEY)
                    || url.hasParameter(Constants.HEARTBEAT_TIMEOUT_KEY)) {
                int h = url.getParameter(Constants.HEARTBEAT_KEY, heartbeat);
                int t = url.getParameter(Constants.HEARTBEAT_TIMEOUT_KEY, h * 3);
                if (t < h * 2) {
                    throw new IllegalStateException("heartbeatTimeout < heartbeatInterval * 2");
                }
                if (h != heartbeat || t != heartbeatTimeout) {
                    heartbeat = h;
                    heartbeatTimeout = t;
                    // 开启心跳检测
                    startHeartbeatTimer();
                }
            }
        } catch (Throwable t) {
            logger.error(t.getMessage(), t);
        }
    }

    @Override
    @Deprecated
    public void reset(com.alibaba.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void send(Object message) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        if (closed.get()) {
            throw new RemotingException(this.getLocalAddress(), null, "Failed to send message " + message + ", cause: The server " + getLocalAddress() + " is closed!");
        }
        server.send(message, sent);
    }

    /**
     * 开始心跳，跟HeaderExchangeClient类中的开始心跳方法唯一区别是获得的通道不一样，
     * 客户端跟通道是一一对应的，所有只要对一个通道进行心跳检测，而服务端跟通道是一对多的关系，
     * 所有需要对该服务器连接的所有通道进行心跳检测。
     */
    private void startHeartbeatTimer() {
        // 停止现有的心跳检测
        stopHeartbeatTimer();
        if (heartbeat > 0) {
            // 创建心跳检测定时器
            heartbeatTimer = scheduled.scheduleWithFixedDelay(
                    new HeartBeatTask(new HeartBeatTask.ChannelProvider() {
                        @Override
                        public Collection<Channel> getChannels() {
                            // 返回一个不可修改的 连接该服务器的 信息交换通道集合
                            return Collections.unmodifiableCollection(
                                    HeaderExchangeServer.this.getChannels());
                        }
                    }, heartbeat, heartbeatTimeout),
                    heartbeat, heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    private void stopHeartbeatTimer() {
        try {
            ScheduledFuture<?> timer = heartbeatTimer;
            if (timer != null && !timer.isCancelled()) {
                // 取消定时器
                timer.cancel(true);
            }
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
        } finally {
            heartbeatTimer = null;
        }
    }

}
