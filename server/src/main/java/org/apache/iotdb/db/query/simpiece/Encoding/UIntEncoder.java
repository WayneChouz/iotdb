/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// Sim-Piece code forked from https://github.com/xkitsios/Sim-Piece.git

package org.apache.iotdb.db.query.simpiece.Encoding;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class UIntEncoder {
  public static void write(long number, ByteArrayOutputStream outputStream) throws IOException {
    if (number > Math.pow(2, 8 * 4) - 1 || number < 0)
      throw new UnsupportedOperationException("Can't save number " + number + " as unsigned int");
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
    buffer.putInt((int) (number & 0xffffffffL));
    outputStream.write(buffer.array());
  }

  public static long read(ByteArrayInputStream inputStream) throws IOException {
    byte[] byteArray = new byte[Integer.BYTES];
    int k = inputStream.read(byteArray);
    if (k != Integer.BYTES) throw new IOException();
    ByteBuffer buffer = ByteBuffer.allocate(Integer.BYTES);
    buffer.put(byteArray);
    buffer.flip();

    return buffer.getInt() & 0xffffffffL;
  }
}
