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
package com.alibaba.dubbo.common.serialize.serialization;

import com.alibaba.dubbo.common.serialize.ObjectInput;
import com.alibaba.dubbo.common.serialize.ObjectOutput;
import com.alibaba.dubbo.common.serialize.hessian2.Hessian2Serialization;
import org.junit.Ignore;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;

import static org.junit.Assert.*;

public class Hessian2SerializationTest extends AbstractSerializationPersionFailTest {
    {
        serialization = new Hessian2Serialization();
    }

    // Hessian2 

    @Test
    public void test_boolArray_withType() throws Exception {
        boolean[] data = new boolean[]{true, false, true};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertTrue(Arrays.equals(data, (boolean[]) deserialize.readObject(boolean[].class)));

        try {
            deserialize.readObject(boolean[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Ignore("type missing, char[] -> String")
    @Test
    public void test_charArray() throws Exception {
    }

    @Test
    public void test_shortArray_withType() throws Exception {
        short[] data = new short[]{37, 39, 12};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, (short[]) deserialize.readObject(short[].class));

        try {
            deserialize.readObject(short[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void test_intArray_withType() throws Exception {
        int[] data = new int[]{234, 0, -1};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, (int[]) deserialize.readObject());

        try {
            deserialize.readObject(int[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void test_longArray_withType() throws Exception {
        long[] data = new long[]{234, 0, -1};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, (long[]) deserialize.readObject());

        try {
            deserialize.readObject(long[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void test_floatArray_withType() throws Exception {
        float[] data = new float[]{37F, -3.14F, 123456.7F};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, (float[]) deserialize.readObject(), 0.0001F);

        try {
            deserialize.readObject(float[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void test_doubleArray_withType() throws Exception {
        double[] data = new double[]{37D, -3.14D, 123456.7D};

        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, (double[]) deserialize.readObject(double[].class), 0.0001);

        try {
            deserialize.readObject(double[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void test_StringArray_withType() throws Exception {

        String[] data = new String[]{"1", "b"};


        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        assertArrayEquals(data, deserialize.readObject(String[].class));

        try {
            deserialize.readObject(String[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }

    @Test
    public void testObejct() throws Exception {

//        String[] data = new String[]{"1", "b"};

        User data = new User();
        data.setName("ss");
        data.setTime(new Date());


        ObjectOutput objectOutput = serialization.serialize(url, byteArrayOutputStream);
        objectOutput.writeObject(data);
        objectOutput.flushBuffer();

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(
                byteArrayOutputStream.toByteArray());
        ObjectInput deserialize = serialization.deserialize(url, byteArrayInputStream);

        deserialize.readObject(User.class);

        try {
            deserialize.readObject(String[].class);
            fail();
        } catch (ArrayIndexOutOfBoundsException e) {
        }
        // NOTE: Hessian2 throws ArrayIndexOutOfBoundsException instead of IOException, let's live with this.
    }


    public static class User implements Serializable {
        String name;

        Date time;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Date getTime() {
            return time;
        }

        public void setTime(Date time) {
            this.time = time;
        }
    }

    @Ignore("type missing, Byte -> Integer")
    @Test
    public void test_ByteWrap() throws Exception {
    }

    // FIXME
    @Ignore("Bad Stream read other type data")
    @Test
    public void test_MediaContent_badStream() throws Exception {
    }
}