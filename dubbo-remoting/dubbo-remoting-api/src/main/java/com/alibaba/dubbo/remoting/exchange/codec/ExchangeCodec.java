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
package com.alibaba.dubbo.remoting.exchange.codec;

import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.io.Bytes;
import com.alibaba.dubbo.common.io.StreamUtils;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.serialize.Cleanable;
import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.Serialization;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.remoting.Channel;
import com.alibaba.dubbo.remoting.RemotingException;
import com.alibaba.dubbo.remoting.buffer.ChannelBuffer;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferInputStream;
import com.alibaba.dubbo.remoting.buffer.ChannelBufferOutputStream;
import com.alibaba.dubbo.remoting.exchange.Request;
import com.alibaba.dubbo.remoting.exchange.Response;
import com.alibaba.dubbo.remoting.exchange.support.DefaultFuture;
import com.alibaba.dubbo.remoting.telnet.codec.TelnetCodec;
import com.alibaba.dubbo.remoting.transport.CodecSupport;
import com.alibaba.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * ExchangeCodec.
 */
public class ExchangeCodec extends TelnetCodec {

    // header length.
    /**
     * 协议头长度 16字节 = 128 bits
     * 不是协议头的长度，而是协议整体数据的长度， 具体可参考看 Dubbo 协议图
     */
    protected static final int HEADER_LENGTH = 16;

    // magic header.
    /**
     * MAGIC 二进制： 1101101010111011  十进制：55995
     */
    protected static final short MAGIC = (short) 0xdabb;
    /**
     * 魔法数高位，0-7 位：11011010
     */
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];
    /**
     * 魔法数低位，9-15 位：10111011
     */
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];


    // message flag.
    /**
     * 标志请求，Req/Res   --
     * <p>
     * 位数：16 位
     * <p>
     * 十进制：128  二进制：10000000
     */
    protected static final byte FLAG_REQUEST = (byte) 0x80;
    /**
     * 单向双向
     * <p>
     * 位数：17 位
     * <p>
     * 十进制：64   二进制：1000000
     */
    protected static final byte FLAG_TWOWAY = (byte) 0x40;
    /**
     * 事件标识
     * <p>
     * 位数：18 位
     * <p>
     * 十进制：32   二进制：100000
     */
    protected static final byte FLAG_EVENT = (byte) 0x20;
    /**
     * Serialization 编号
     * <p>
     * 位数：19 - 23 位
     * <p>
     * 十进制：31   二进制：11111
     */
    protected static final int SERIALIZATION_MASK = 0x1f;
    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() {
        return MAGIC;
    }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        if (msg instanceof Request) {
            // 如果消息是Request类型，对请求消息编码
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            // 如果消息是Response类型，对响应消息编码
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            // 直接让父类 Telnet 处理，目前是 telnet 命令的结果
            super.encode(channel, buffer, msg);
        }
    }

    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        // 读取前16字节的协议头数据，如果数据不满16字节，则读取全部
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        // 解码
        return decode(channel, buffer, readable, header);
    }

    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        // check magic number.
        // 核对魔法数
        if (readable > 0 && header[0] != MAGIC_HIGH
                || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            // 将 buffer 完全复制到 `header` 数组中
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            // telnet
            return super.decode(channel, buffer, readable, header);
        }

        // check length.
        // Header 长度不够，返回需要更多的输入，解决拆包现象
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // get data length.
        int len = Bytes.bytes2int(header, 12);
        // 检查信息头长度
        checkPayload(channel, len);

        int tt = len + HEADER_LENGTH;
        // 总长度不够，返回需要更多的输入，解决拆包现象
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        // limit input stream.
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            // 对body反序列化
            return decodeBody(channel, is, header);
        } finally {
            // 如果不可用
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    // 跳过未读完的流
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }

    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        // 用并运算符
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);
        // get request id.
        // 获取请求ID
        long id = Bytes.bytes2long(header, 4);

        // 如果第16位为 0，说明是响应
        if ((flag & FLAG_REQUEST) == 0) {
            // decode response.
            Response res = new Response(id);
            // 如果第18位不是0，则说明是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(Response.HEARTBEAT_EVENT);
            }
            // get status.
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                // 如果响应是成功的
                if (status == Response.OK) {
                    Object data;
                    if (res.isHeartbeat()) {
                        // 如果是心跳事件，则心跳事件的解码
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeHeartbeatData(channel, CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else if (res.isEvent()) {
                        // 如果是事件，则事件的解码
                        byte[] eventPayload = CodecSupport.getPayload(is);
                        data = decodeEventData(channel,
                                CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                    } else {
                        // 执行普通解码
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    // 重新设置响应结果
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        } else {
            // decode request.
            // 对请求类型解码
            Request req = new Request(id);
            req.setVersion(Version.getProtocolVersion());

            // 如果第17位不为0，则是双向
            req.setTwoWay((flag & FLAG_TWOWAY) != 0);

            // 如果18位不为0，则是心跳事件
            if ((flag & FLAG_EVENT) != 0) {
                req.setEvent(Request.HEARTBEAT_EVENT);
            }

            try {
                // 反序列化
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                Object data;
                if (req.isHeartbeat()) {
                    // 如果请求是心跳事件，则心跳事件解码
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeHeartbeatData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else if (req.isEvent()) {
                    // 如果是事件，则事件解码
                    byte[] eventPayload = CodecSupport.getPayload(is);
                    data = decodeEventData(channel,
                            CodecSupport.deserialize(channel.getUrl(), new ByteArrayInputStream(eventPayload), proto), eventPayload);
                } else {
                    // 否则，用普通解码
                    data = decodeRequestData(channel, in);
                }
                // 把重新设置请求数据
                req.setData(data);
            } catch (Throwable t) {
                // bad request
                req.setBroken(true);
                req.setData(t);
            }
            return req;
        }
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null)
            return null;
        Request req = future.getRequest();
        if (req == null)
            return null;
        return req.getData();
    }

    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {

        // 从通道中获取序列化 具体扩展实现，默认为 hessian2。
        Serialization serialization = getSerialization(channel);

        /**
         * Bytes.short2bytes(MAGIC, header);   ===》  设置了 前两个字节，一共 16位。
         * FLAG_REQUEST、FLAG_TWOWAY、FLAG_EVENT、serialization编号  共用 第三个字节，一共 8位。
         */

        // header.
        // 创建16字节的 字节数组
        byte[] header = new byte[HEADER_LENGTH];

        // set magic number.
        // 设置魔法数，设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
        Bytes.short2bytes(MAGIC, header);

        // set request and serialization flag.
        // 16-23位为serialization编号，用到或运算10000000|serialization编号，例如serialization编号为11111，则为00011111
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        // 继续上面的例子，00011111|1000000 = 01011111
        if (req.isTwoWay()) header[2] |= FLAG_TWOWAY;
        // 继续上面的例子，01011111|100000 = 011 11111 可以看到011代表请求标记、双向、是事件，这样就设置了16、17、18位，后面19-23位是Serialization 编号
        if (req.isEvent()) header[2] |= FLAG_EVENT;

        // set request id.
        // 设置 32-95 位，请求ID
        Bytes.long2bytes(req.getId(), header, 4);

        // encode request data.
        int savedWriteIndex = buffer.writerIndex();
        // 跳过 buffer 头部 16 个字节，用于序列化消息体
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        // 创建序列化对象
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
        // 如果该请求是事件
        if (req.isEvent()) {
            // 特殊事件编码
            encodeEventData(channel, out, req.getData());
        } else {
            // 序列化请求调用，data 一般是 RpcInvocation
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        // 释放资源
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        // TODO：返回写入了多少数据，即消息体长度
        int len = bos.writtenBytes();
        // 检验消息长度 是否超过默认 8MB 大小
        checkPayload(channel, len);
        // 将消息长度 写入头部第 12 个字节的 偏移量（96-127位）
        Bytes.int2bytes(len, header, 12);

        // 定位指针到 报文头部开始位置
        buffer.writerIndex(savedWriteIndex);
        // 写入完整报文头部 到 buffer
        buffer.writeBytes(header);
        // 设置 writerIndex 到消息体结束位置
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            // 获取指定或默认的序列化协议（Hessian2）
            Serialization serialization = getSerialization(channel);
            // header.
            // 创建16字节的字节数组
            byte[] header = new byte[HEADER_LENGTH];
            // set magic number.
            // 设置前16位数据，也就是设置header[0]和header[1]的数据为Magic High和Magic Low
            Bytes.short2bytes(MAGIC, header);

            // set request and serialization flag.
            // 16-23位为serialization编号，用到或运算10000000|serialization编号，例如serialization编号为11111，则为00011111

            // 在第3个字节（19～23位）存储响应标识
            header[2] = serialization.getContentTypeId();
            // 心跳事件
            if (res.isHeartbeat()) header[2] |= FLAG_EVENT;
            // 在第 4 个字节存储响应状态
            byte status = res.getStatus();
            header[3] = status;
            // 设置响应唯一标识（与请求标识一一对应）
            Bytes.long2bytes(res.getId(), header, 4);

            // 空出 16 字节头部用于存储响应体报文
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);
            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
            // encode response data or error message.
            // 编码响应数据或错误消息。
            if (status == Response.OK) {
                if (res.isHeartbeat()) {
                    // 心跳编码
                    encodeHeartbeatData(channel, out, res.getResult());
                } else {
                    // 序列化响应调用，data 一般是 Result 对象
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else out.writeUTF(res.getErrorMessage());
            // 释放资源
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            int len = bos.writtenBytes();
            // 校验消息长度是否超过 默认的 8MB 大小
            checkPayload(channel, len);
            // 将消息长度写入 头部第12个字节偏移量（96 ～ 127位）
            Bytes.int2bytes(len, header, 12);

            // write
            // 定位指针到报文头部 开始位置
            buffer.writerIndex(savedWriteIndex);
            // 写入完整报文头部到 buffer
            buffer.writeBytes(header); // write header.
            // 设置 writerIndex 到消息体结束位置
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            // 如果编码失败，则复位 buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            // 将编码响应一次发送给 consumer，否则只能等待超时
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                // 长度超过默认限制异常
                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        // 告知客户端编码失败的 具体原因
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }

    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        try {
            int dataLen = eventPayload.length;
            int threshold = Integer.parseInt(System.getProperty("deserialization.event.size", "50"));
            if (dataLen > threshold) {
                throw new IllegalArgumentException("Event data too long, actual size " + dataLen + ", threshold " + threshold + " rejected for security consideration.");
            }
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in, byte[] eventPayload) throws IOException {
        return decodeEventData(channel, in, eventPayload);
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        encodeResponseData(out, data);
    }


}
