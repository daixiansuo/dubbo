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
package com.alibaba.dubbo.remoting;

import com.alibaba.dubbo.common.URL;

import java.net.InetSocketAddress;

/**
 * Endpoint. (API/SPI, Prototype, ThreadSafe)
 * <p>
 * Dubbo 抽象出 "端" 概念，用 Endpoint 接口描述。端 表示一个点，点对点之间可以双向传输。
 * 在 端 的基础上衍生出 通道、客户端、服务端的概念，对应描述接口为 Channel、Client、Server。
 * Endpoint 接口当中的方法是 这三个衍生接口 共同拥有的方法。
 *
 * @see com.alibaba.dubbo.remoting.Channel
 * @see com.alibaba.dubbo.remoting.Client
 * @see com.alibaba.dubbo.remoting.Server
 */
public interface Endpoint {

    /**
     * get url.
     * 获取该端的 url
     *
     * @return url
     */
    URL getUrl();

    /**
     * get channel handler.
     * 获得该端的 通道处理器
     *
     * @return channel handler
     */
    ChannelHandler getChannelHandler();

    /**
     * get local address.
     * 获得该端的本地地址
     *
     * @return local address.
     */
    InetSocketAddress getLocalAddress();

    /**
     * send message.
     * 发送消息
     *
     * @param message
     * @throws RemotingException
     */
    void send(Object message) throws RemotingException;

    /**
     * send message.
     * 发送消息，sent 表示 是否已经发送的标记
     *
     * @param message
     * @param sent    already sent to socket?
     */
    void send(Object message, boolean sent) throws RemotingException;

    /**
     * close the channel.
     * 关闭
     */
    void close();

    /**
     * Graceful close the channel.
     * 优雅关闭，加入等待时间
     */
    void close(int timeout);

    /**
     * 开始关闭
     */
    void startClose();

    /**
     * is closed.
     * 是否已经关闭
     *
     * @return closed
     */
    boolean isClosed();

}