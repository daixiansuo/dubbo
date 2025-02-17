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

package com.alibaba.dubbo.remoting.transport;

import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.ChannelHandler;
import com.alibaba.dubbo.remoting.Decodeable;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;

/**
 * 解码处理器
 */
public class DecodeHandler extends AbstractChannelHandlerDelegate {

    private static final Logger log = LoggerFactory.getLogger(DecodeHandler.class);

    public DecodeHandler(ChannelHandler handler) {
        super(handler);
    }

    @Override
    public void received(Channel channel, Object message) throws RemotingException {
        // 如果是Decodeable类型的消息，则对整个消息解码
        if (message instanceof Decodeable) {
            decode(message);
        }

        /**
         * (request)message.getData <==> Request.mData 属性字段，此时为 DecodeableRpcInvocation 类型，所以可以进行数据解码
         * Debug 数据：Request [id=0, version=2.0.2, twoway=true, event=false, broken=false, data=RpcInvocation [methodName=sayHello, parameterTypes=[class java.lang.String], arguments=[world], attachments={path=com.alibaba.dubbo.demo.DemoService, input=201, dubbo=2.0.2, interface=com.alibaba.dubbo.demo.DemoService, version=0.0.0}, attributes={serialization_id=2}]]
         */
        // 如果是Request请求类型消息，则对请求中对请求数据解码
        if (message instanceof Request) {
            decode(((Request) message).getData());
        }

        // 如果是Response返回类型的消息，则对返回消息中对结果进行解码
        if (message instanceof Response) {
            decode(((Response) message).getResult());
        }

        // 继续将消息委托给handler，继续处理
        handler.received(channel, message);
    }

    private void decode(Object message) {
        // 如果消息类型是Decodeable，进一步调用Decodeable的decode来解码
        if (message != null && message instanceof Decodeable) {
            try {
                ((Decodeable) message).decode();
                if (log.isDebugEnabled()) {
                    log.debug("Decode decodeable message " + message.getClass().getName());
                }
            } catch (Throwable e) {
                if (log.isWarnEnabled()) {
                    log.warn("Call Decodeable.decode failed: " + e.getMessage(), e);
                }
            } // ~ end of catch
        } // ~ end of if
    } // ~ end of method decode

}
