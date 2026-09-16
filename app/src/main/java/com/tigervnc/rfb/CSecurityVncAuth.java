/* Modified in 2026 by Bill Kang for vncviewer-for-games.
 * GPL-2.0 section 2(a) asks modified files to say so; this is that notice.
 *
 * This file was rewritten far enough that its upstream TigerVNC header went
 * missing.  Upstream carries a file of the same name, so it is treated as
 * derived from TigerVNC and the header below has been restored.  The copyright
 * line is the project-wide one from NOTICE, not a per-file record -- the exact
 * upstream revision this came from was never written down.
 */
/* Copyright (C) 2002-2005 RealVNC Ltd.  All Rights Reserved.
 * Copyright (C) 2011-2026 Brian P. Hinz
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

package com.tigervnc.rfb;

import java.nio.ByteBuffer;
import com.tigervnc.rdr.InStream;
import com.tigervnc.rdr.OutStream;

/**
 * Standard VNC authentication (secTypeVncAuth = 2).
 * The server sends a 16-byte challenge; the password is used as a DES key to encrypt it and the
 * result is sent back.
 *
 * 🔑 The password comes from {@link CSecurity#upg}, which the viewer **must** set.
 *    Without it this fails immediately — quietly sending an empty password would make the cause
 *    impossible to find.
 * ⚠️ VNC authentication uses **at most 8 bytes** of the password (protocol). Anything longer is
 *    truncated.
 */
public class CSecurityVncAuth extends CSecurity {

  public boolean processMsg(CConnection cc) {
    InStream is = cc.getInStream();
    OutStream os = cc.getOutStream();

    byte[] challenge = new byte[VncAuth.challengeSize];
    is.readBytes(ByteBuffer.wrap(challenge), VncAuth.challengeSize);

    if (upg == null)
      throw new AuthFailureException(
        "no way to obtain a password - CSecurity.upg was never set");

    StringBuffer passwd = new StringBuffer();
    upg.getUserPasswd(false, null, passwd);
    if (passwd.length() == 0)
      throw new AuthFailureException("the server asked for a password and the password is empty");

    VncAuth.encryptChallenge(challenge, passwd.toString());
    os.writeBytes(challenge, 0, VncAuth.challengeSize);
    os.flush();
    return true;
  }

  public int getType() { return Security.secTypeVncAuth; }
  public String description() { return "VNC Authentication"; }

  static LogWriter vlog = new LogWriter("CSecurityVncAuth");
}
