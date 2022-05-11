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

package com.alibaba.dubbo.remoting.buffer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

public class DynamicChannelBuffer extends AbstractChannelBuffer {

    /**
     * 通道缓存工厂
     */
    private final ChannelBufferFactory factory;

    /**
     * 通道缓存区
     */
    private ChannelBuffer buffer;

    /**
     * 构造方法
     * 工厂默认是 HeapChannelBufferFactory
     *
     * @param estimatedLength 估计长度
     */
    public DynamicChannelBuffer(int estimatedLength) {
        this(estimatedLength, HeapChannelBufferFactory.getInstance());
    }

    public DynamicChannelBuffer(int estimatedLength, ChannelBufferFactory factory) {
        if (estimatedLength < 0) {
            throw new IllegalArgumentException("estimatedLength: " + estimatedLength);
        }
        if (factory == null) {
            throw new NullPointerException("factory");
        }
        // 设置工厂
        this.factory = factory;
        // 创建缓冲区
        buffer = factory.getBuffer(estimatedLength);
    }


    /**
     * 确保可写字节
     *
     * @param minWritableBytes 最小可写字节
     */
    @Override
    public void ensureWritableBytes(int minWritableBytes) {
        // 如果最小写入的字节数 不大于 可写的字节数，则结束
        if (minWritableBytes <= writableBytes()) {
            return;
        }

        // 新增容量
        int newCapacity;
        // 此缓冲区 可包含的字节数等于0
        if (capacity() == 0) {
            // 新增容量设置为 1
            newCapacity = 1;
        } else {
            // 否则，新增容量设置为 缓冲区可包含的字节数
            newCapacity = capacity();
        }
        // 最小新增容量 = 当前的写索引 + 最新写入的字节数
        int minNewCapacity = writerIndex() + minWritableBytes;
        // 当新增容量 小于 最小新增容量，循环加倍，重新设置新增容量
        while (newCapacity < minNewCapacity) {
            // 新增容量左移一位，也就是加倍
            newCapacity <<= 1;
        }

        // 通过工厂创建该容量大小的 缓冲区
        ChannelBuffer newBuffer = factory().getBuffer(newCapacity);
        // 从原有的 buffer 中读取数据到 newBuffer 中
        newBuffer.writeBytes(buffer, 0, writerIndex());
        // 替换掉原来的缓冲区
        buffer = newBuffer;
    }


    @Override
    public int capacity() {
        return buffer.capacity();
    }


    @Override
    public ChannelBuffer copy(int index, int length) {
        // 创建缓冲区，预计长度最小64，或者更大
        DynamicChannelBuffer copiedBuffer = new DynamicChannelBuffer(Math.max(length, 64), factory());
        // 复制数据
        copiedBuffer.buffer = buffer.copy(index, length);
        // 设置索引，读索引设置为 0，写索引设置为 copy 的数据长度
        copiedBuffer.setIndex(0, length);
        return copiedBuffer;
    }


    @Override
    public ChannelBufferFactory factory() {
        return factory;
    }


    @Override
    public byte getByte(int index) {
        return buffer.getByte(index);
    }


    @Override
    public void getBytes(int index, byte[] dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, ByteBuffer dst) {
        buffer.getBytes(index, dst);
    }


    @Override
    public void getBytes(int index, ChannelBuffer dst, int dstIndex, int length) {
        buffer.getBytes(index, dst, dstIndex, length);
    }


    @Override
    public void getBytes(int index, OutputStream dst, int length) throws IOException {
        buffer.getBytes(index, dst, length);
    }


    @Override
    public boolean isDirect() {
        return buffer.isDirect();
    }


    @Override
    public void setByte(int index, int value) {
        buffer.setByte(index, value);
    }


    @Override
    public void setBytes(int index, byte[] src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public void setBytes(int index, ByteBuffer src) {
        buffer.setBytes(index, src);
    }


    @Override
    public void setBytes(int index, ChannelBuffer src, int srcIndex, int length) {
        buffer.setBytes(index, src, srcIndex, length);
    }


    @Override
    public int setBytes(int index, InputStream src, int length) throws IOException {
        return buffer.setBytes(index, src, length);
    }


    @Override
    public ByteBuffer toByteBuffer(int index, int length) {
        return buffer.toByteBuffer(index, length);
    }

    @Override
    public void writeByte(int value) {
        ensureWritableBytes(1);
        super.writeByte(value);
    }

    @Override
    public void writeBytes(byte[] src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ChannelBuffer src, int srcIndex, int length) {
        ensureWritableBytes(length);
        super.writeBytes(src, srcIndex, length);
    }

    @Override
    public void writeBytes(ByteBuffer src) {
        ensureWritableBytes(src.remaining());
        super.writeBytes(src);
    }

    @Override
    public int writeBytes(InputStream in, int length) throws IOException {
        ensureWritableBytes(length);
        return super.writeBytes(in, length);
    }


    @Override
    public byte[] array() {
        return buffer.array();
    }


    @Override
    public boolean hasArray() {
        return buffer.hasArray();
    }


    @Override
    public int arrayOffset() {
        return buffer.arrayOffset();
    }
}
