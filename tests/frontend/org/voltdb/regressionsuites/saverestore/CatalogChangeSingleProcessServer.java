/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 * IN NO EVENT SHALL THE AUTHORS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 * OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 * ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.voltdb.regressionsuites.saverestore;

import org.voltdb.BackendTarget;
import org.voltdb.compiler.VoltProjectBuilder;
import org.voltdb.regressionsuites.LocalSingleProcessServer;

/**
 * Implementation of a LocalSingleProcessServer that supports changing the
 * VoltProjectBuilder used to construct the catalog
 */
public class CatalogChangeSingleProcessServer extends LocalSingleProcessServer
{
    VoltProjectBuilder m_origBuilder;

    public CatalogChangeSingleProcessServer(String jarFileName,
                                            int siteCount,
                                            BackendTarget target)
    {
        super(jarFileName, siteCount, target);
    }

    @Override
    public boolean compile(VoltProjectBuilder builder) {
        m_origBuilder = builder;
        boolean compiled = m_origBuilder.compile(m_jarFileName, m_siteCount, 0);
        return compiled;
    }

    public boolean recompile(VoltProjectBuilder builder)
    {
        boolean compiled = builder.compile(m_jarFileName, m_siteCount, 0);
        return compiled;
    }

    public boolean recompile(int siteCount)
    {
        boolean compiled = m_origBuilder.compile(m_jarFileName, siteCount, 0);
        return compiled;
    }

    public boolean recompile(VoltProjectBuilder builder, int siteCount)
    {
        boolean compiled = builder.compile(m_jarFileName, siteCount, 0);
        return compiled;
    }

    public boolean revertCompile()
    {
        boolean compiled = m_origBuilder.compile(m_jarFileName, m_siteCount, 0);
        return compiled;
    }

    @Override
    public String getName() {
        // name is combo of the classname and the parameters

        String retval = "catalogChangeSingleProcess-";
        retval += String.valueOf(m_siteCount);
        if (m_target == BackendTarget.HSQLDB_BACKEND)
            retval += "-HSQL";
        else if (m_target == BackendTarget.NATIVE_EE_IPC)
            retval += "-IPC";
        else
            retval += "-JNI";
        return retval;
    }
}