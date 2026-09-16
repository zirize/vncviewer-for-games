/* Modified in 2026 by Bill Kang for vncviewer-for-games.
 * GPL-2.0 section 2(a) asks modified files to say so; this is that notice.
 * The original copyright and license follow below and are unchanged.
 */
/* Copyright (C) 2012-2026 Brian P. Hinz
 *
 * This is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301,
 * USA.
 */
package com.tigervnc.network;

import java.io.IOException;

import java.net.SocketAddress;
import java.nio.*;
import java.nio.channels.*;
import java.nio.channels.spi.SelectorProvider;

import java.util.Set;
import java.util.Iterator;

import com.tigervnc.rdr.Exception;

public class SocketDescriptor implements FileDescriptor {

  public SocketDescriptor() throws Exception {
    DefaultSelectorProvider();
    try {
      channel = SocketChannel.open();
      channel.configureBlocking(false);
      readSelector = Selector.open();
      writeSelector = Selector.open();
      channel.register(readSelector, SelectionKey.OP_READ);
      channel.register(writeSelector, SelectionKey.OP_WRITE);
    } catch (IOException e) {
      throw new Exception(e.getMessage());
    }
  }

  public void shutdown() throws IOException {
    try {
      channel.socket().shutdownInput();
      channel.socket().shutdownOutput();
    } catch(IOException e) {
      throw new IOException(e.getMessage());
    }
  }

  public void close() throws IOException {
    try {
      if (readSelector != null) {
        try { readSelector.close(); } catch (Exception ignored) {}
      }
      if (writeSelector != null) {
        try { writeSelector.close(); } catch (Exception ignored) {}
      }
      channel.close();
    } catch(IOException e) {
      throw new IOException(e.getMessage());
    }
  }

  private static SelectorProvider DefaultSelectorProvider() {
    return SelectorProvider.provider();
  }

  // 🔴 Reads and writes must not block each other.
  // There used to be a single Selector, and this method was locked on the instance monitor, so
  // while the message loop sat inside select() waiting for data, another thread's flush() could
  // never enter - a deadlock. With no requests going out the server had nothing to send, so the
  // read never woke either: a circular wait.
  // ⇒ Each direction gets its own Selector and its own monitor.
  public int select(int interestOps, Integer timeout) throws Exception {
    Selector sel =
      ((interestOps & SelectionKey.OP_WRITE) != 0) ? writeSelector : readSelector;
    if (sel == null) return 0;
    synchronized (sel) {
      int n;
      SelectionKey key = channel.keyFor(sel);
      if (key == null || !key.isValid())
        return 0;
      key.interestOps(interestOps);
      sel.selectedKeys().clear();
      try {
        if (timeout == null) {
          n = sel.select();
        } else {
          int tv = timeout.intValue();
          switch(tv) {
          case 0:
            n = sel.selectNow();
            break;
          default:
            n = sel.select((long)tv);
            break;
          }
        }
      } catch (java.io.IOException e) {
        throw new Exception(e.getMessage());
      }
      if (n > 0 && (key.readyOps() & interestOps) == 0)
        return 0;
      return n;
    }
  }

  public int write(ByteBuffer buf, int len) throws Exception {
    try {
      int n = channel.write((ByteBuffer)buf.slice().limit(len));
      if (n > 0)
        buf.position(buf.position()+n);
      return n;
    } catch (java.io.IOException e) {
      throw new Exception(e.getMessage());
    }
  }

  public int read(ByteBuffer buf, int len) throws Exception {
    try {
      int n = channel.read((ByteBuffer)buf.slice().limit(len));
      if (n > 0)
        buf.position(buf.position()+n);
      return n;
    } catch (java.lang.Exception e) {
      throw new Exception(e.getMessage());
    }
  }

  public java.net.Socket socket() {
    return channel.socket();
  }

  public SocketAddress getRemoteAddress() throws IOException {
    if (isConnected())
      return channel.socket().getRemoteSocketAddress();
    return null;
  }

  public SocketAddress getLocalAddress() throws IOException {
    if (isConnected())
      return channel.socket().getLocalSocketAddress();
    return null;
  }

  public boolean isConnectionPending() {
    return channel.isConnectionPending();
  }

  public boolean connect(SocketAddress remote) throws IOException {
    return channel.connect(remote);
  }

  public boolean finishConnect() throws IOException {
    return channel.finishConnect();
  }

  public boolean isConnected() {
    return channel.isConnected();
  }

  protected void setChannel(SocketChannel channel_) {
    try {
      if (channel != null)
        channel.close();
      if (readSelector != null)
        readSelector.close();
      if (writeSelector != null)
        writeSelector.close();
      channel = channel_;
      channel.configureBlocking(false);
      readSelector = Selector.open();
      writeSelector = Selector.open();
      channel.register(readSelector, SelectionKey.OP_READ);
      channel.register(writeSelector, SelectionKey.OP_WRITE);
    } catch (java.io.IOException e) {
      throw new Exception(e.getMessage());
    }
  }

  protected SocketChannel channel;
  protected Selector readSelector;
  protected Selector writeSelector;

}
