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

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.Client;
import com.alibaba.dubbo.remoting.exchange.Request;

import java.util.Collection;

final class HeartBeatTask implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(HeartBeatTask.class);

    /**
     * 通道管理  TODO： 作用？
     */
    private ChannelProvider channelProvider;

    /**
     * 心跳间隔周期 单位：ms
     */
    private int heartbeat;

    /**
     * 心跳超时时间 单位：ms
     */
    private int heartbeatTimeout;

    HeartBeatTask(ChannelProvider provider, int heartbeat, int heartbeatTimeout) {
        this.channelProvider = provider;
        this.heartbeat = heartbeat;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    /**
     * 1. 如果需要心跳的通道本身如果关闭了，那么跳过，不添加心跳机制。
     * 2. 无论是接收消息还是发送消息，只要超过了设置的心跳间隔，就发送心跳消息来测试是否断开
     * 3. 如果最后一次接收到消息到到现在已经超过了心跳超时时间，那就认定对方的确断开，分两种情况来处理对方断开的情况。
     *    分别是服务端断开，客户端重连以及客户端断开，服务端断开这个客户端的连接。，这里要好好品味一下谁是发送方，谁在等谁的响应，苦苦没有等到
     */

    @Override
    public void run() {
        try {
            long now = System.currentTimeMillis();
            // 遍历所有通道
            for (Channel channel : channelProvider.getChannels()) {
                // 跳过关闭状态的通道
                if (channel.isClosed()) {
                    continue;
                }
                try {

                    // 最后一次 接收消息的时间戳
                    Long lastRead = (Long) channel.getAttribute(
                            HeaderExchangeHandler.KEY_READ_TIMESTAMP);
                    // 最后一次 发送消息的时间戳
                    Long lastWrite = (Long) channel.getAttribute(
                            HeaderExchangeHandler.KEY_WRITE_TIMESTAMP);

                    // 判断 最后一次 接收或发送 消息的时间，到现在的时间间隔 是否超过了 心跳间隔周期。
                    if ((lastRead != null && now - lastRead > heartbeat)
                            || (lastWrite != null && now - lastWrite > heartbeat)) {
                        // 创建 request
                        Request req = new Request();
                        // 版本号
                        req.setVersion(Version.getProtocolVersion());
                        // 需要响应
                        req.setTwoWay(true);
                        // 设置事件类型，心跳事件
                        req.setEvent(Request.HEARTBEAT_EVENT);
                        // 发送心跳请求
                        channel.send(req);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Send heartbeat to remote channel " + channel.getRemoteAddress()
                                    + ", cause: The channel has no data-transmission exceeds a heartbeat period: " + heartbeat + "ms");
                        }
                    }

                    // 如果最后一次接收消息的时间 与 现在时间的 时间间隔，超过了 心跳超时时间
                    if (lastRead != null && now - lastRead > heartbeatTimeout) {
                        logger.warn("Close channel " + channel
                                + ", because heartbeat read idle time out: " + heartbeatTimeout + "ms");
                        // 如果该通道是客户端，也就是请求的服务器挂掉了，客户端尝试重连服务器
                        if (channel instanceof Client) {
                            try {
                                // 重连服务器
                                ((Client) channel).reconnect();
                            } catch (Exception e) {
                                //do nothing
                            }
                        } else {
                            // 如果不是客户端，也就是是服务端返回响应给客户端，但是客户端挂掉了，则服务端关闭客户端连接
                            channel.close();
                        }
                    }
                } catch (Throwable t) {
                    logger.warn("Exception when heartbeat to remote channel " + channel.getRemoteAddress(), t);
                }
            }
        } catch (Throwable t) {
            logger.warn("Unhandled exception when heartbeat, cause: " + t.getMessage(), t);
        }
    }

    interface ChannelProvider {
        // 获得所有的通道集合，需要心跳的通道数组
        Collection<Channel> getChannels();
    }

}

