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

public class SecurityClient extends Security {

  public SecurityClient() { super(secTypes); }

  public boolean IsSupported(int secType)
  {
    if (secType == Security.secTypeNone) return true;
    if (secType == Security.secTypeVncAuth) return true;
    return false;
  }

  public CSecurity GetCSecurity(int secType)
  {
    if (secType == Security.secTypeNone) return new CSecurityNone();
    if (secType == Security.secTypeVncAuth) return new CSecurityVncAuth();
    return null;
  }

  public static void setDefaults()
  {
  }

  public static StringParameter secTypes
  = new StringParameter("SecurityTypes",
                        "Specify which security scheme to use",
                        "None", Configuration.ConfigurationObject.ConfViewer);

}
