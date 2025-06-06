/*
 * Copyright (c) 2025, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package com.oracle.svm.hosted.webimage.codegen.phase;

import jdk.graal.compiler.debug.GraalError;
import jdk.graal.compiler.java.SuitesProviderBase;
import jdk.graal.compiler.lir.phases.LIRSuites;
import jdk.graal.compiler.options.OptionValues;
import jdk.graal.compiler.phases.PhaseSuite;
import jdk.graal.compiler.phases.tiers.HighTierContext;
import jdk.graal.compiler.phases.tiers.LowTierContext;
import jdk.graal.compiler.phases.tiers.MidTierContext;
import jdk.graal.compiler.phases.tiers.Suites;
import jdk.vm.ci.code.Architecture;

public abstract class WebImageSuitesCreator extends SuitesProviderBase {

    @Override
    public Suites createSuites(OptionValues options, Architecture arch) {
        return new Suites(createHighTier(options), createMidTier(options), createLowTier(options));
    }

    @Override
    public LIRSuites createLIRSuites(OptionValues options) {
        throw GraalError.unimplementedOverride();
    }

    protected abstract PhaseSuite<HighTierContext> createHighTier(OptionValues options);

    @SuppressWarnings("unused")
    protected PhaseSuite<MidTierContext> createMidTier(OptionValues options) {
        return new PhaseSuite<>();
    }

    protected PhaseSuite<LowTierContext> createLowTier(OptionValues options) {
        return new WebImageLowTier(options);
    }
}
